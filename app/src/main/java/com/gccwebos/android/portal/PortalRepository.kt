package com.gccwebos.android.portal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.gccwebos.android.core.AuthorizedConfig
import com.gccwebos.android.vpn.SafeProxyConfig
import com.gccwebos.android.vpn.UnsafeProxyConfiguration
import org.json.JSONObject
import com.gccwebos.android.commerce.CommerceJson
import com.gccwebos.android.commerce.CommerceModel
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/** One delivery at a time, scoped to the exact account, session and resource. */
class PortalRepository(private val transport: PortalTransport, private val store: DeliveryStore,
    private val policy: OriginPolicy, private val elapsed: () -> Long,
    private val now: () -> Instant = Instant::now,
    private val subscription: SubscriptionSource = SubscriptionTransport(policy),
    private val sessions: SessionStore = MemorySessionStore(),
    profiles: LocalProfileStore = MemoryLocalProfileStore()) {
    val localSubscriptions = LocalSubscriptionManager(store, profiles, subscription, policy, now)
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private val activationLock = Any()
    private var configurationGeneration = 0L
    @Volatile private var session: Session? = null
    private var imported: AuthorizedConfig? = null
    private var importedUntil: Instant? = null
    private var importedGeneration: Long? = null
    private fun invalidateImport() { imported?.close(); imported = null; importedUntil = null; importedGeneration = null }
    @Volatile private var totpExpiry: Pair<String, Long>? = null

    suspend fun humanChallenge(): HumanChallenge = guarded {
        val response = transport.request("/api/portal/human-challenges", JSONObject().put("scope", "portal_login").toString())
        expect(response, setOf(201), false)
        val value = JSONObject(response.body).getJSONObject("challenge")
        val lifetime = value.getLong("expiresInSeconds")
        require(lifetime in 1..3600)
        HumanChallenge(value.optString("challengeRef").ifBlank { value.getString("id") }, value.getString("prompt"), elapsed() + lifetime * 1000)
    }
    suspend fun login(account: String, password: String, challenge: HumanChallenge, answer: String): LoginResult {
        val epoch = synchronized(activationLock) { generation.incrementAndGet().also { session = null; totpExpiry = null } }
        return mutex.withLock {
            guarded {
                checkGeneration(epoch)
                invalidateImport()
                sessions.clear()
                if (elapsed() >= challenge.expiresElapsed) throw PortalFailure(PortalFailure.Reason.LOGIN_FAILED)
                totpExpiry = null
                val body = JSONObject().put("accountId", account).put("password", password).put("rememberMe", false)
                    .put("humanVerification", JSONObject().put("challengeRef", challenge.reference).put("answer", answer))
                val response = transport.request("/api/portal/password/session", body.toString())
                expect(response, setOf(200, 202), false)
                val context = currentCoroutineContext()
                context.ensureActive()
                synchronized(activationLock) {
                    checkGeneration(epoch)
                    if (response.status == 202) {
                        val value = JSONObject(response.body).getJSONObject("challenge")
                        require(value.getString("status") == "second_factor_required")
                        val factors = value.getJSONArray("availableFactors")
                        require((0 until factors.length()).any { factors.getString(it) == "totp" })
                        val reference = value.getString("challengeRef")
                        val ttl = java.time.Duration.between(now(), Instant.parse(value.getString("expiresAt"))).toMillis()
                        require(ttl > 0)
                        totpExpiry = reference to (elapsed() + minOf(ttl, 300000))
                        LoginResult.TotpRequired(reference)
                    } else acceptSession(response.body, epoch, context)
                }
            }
        }
    }
    suspend fun totp(reference: String, code: String): LoginResult.Authenticated {
        val context = currentCoroutineContext()
        val epoch = synchronized(activationLock) { generation.get() }
        return mutex.withLock {
            guarded {
                checkGeneration(epoch)
                val ticket = totpExpiry
                if (ticket == null || ticket.first != reference || elapsed() >= ticket.second) {
                    totpExpiry = null; throw PortalFailure(PortalFailure.Reason.LOGIN_FAILED)
                }
                val response = transport.request("/api/portal/password/second-factor", JSONObject().put("challengeRef", reference)
                    .put("factor", "totp").put("code", code).toString())
                expect(response, setOf(200), false); currentCoroutineContext().ensureActive(); checkGeneration(epoch); acceptSession(response.body, epoch, context)
            }
        }
    }
    private fun acceptSession(body: String, epoch: Long, context: CoroutineContext): LoginResult.Authenticated = synchronized(activationLock) {
        context.ensureActive()
        checkGeneration(epoch)
        invalidateImport()
        val value = PortalJson.session(JSONObject(body), elapsed())
        require(value.valid(now(), elapsed()))
        val prior = store.read()
        context.ensureActive()
        if (prior != null && prior.accountId != value.accountId) { store.clear() }
        context.ensureActive()
        totpExpiry = null
        try {
            sessions.save(SavedSession.capture(value, now(), elapsed()))
            context.ensureActive(); checkGeneration(epoch)
        } catch (failure: Throwable) { sessions.clear(); throw failure }
        session = value
        LoginResult.Authenticated(value)
    }
    /** Read-only server validation before a saved native token can expose authenticated UI. */
    suspend fun restoreSession(): Session? {
        val epoch = synchronized(activationLock) { generation.incrementAndGet().also { session = null; totpExpiry = null } }
        return mutex.withLock { guarded {
            checkGeneration(epoch); invalidateImport()
            val saved = sessions.read() ?: return@guarded null
            val value = saved.restore(now(), elapsed())
            if (value == null) { sessions.clear(); return@guarded null }
            val response = transport.request("/api/portal/resources/current/overview", session = value.token)
            expectCurrent(response, setOf(200), epoch)
            PortalJson.resource(response.body) // Unknown/malformed data never admits a saved session.
            val context = currentCoroutineContext(); context.ensureActive()
            synchronized(activationLock) {
                checkGeneration(epoch)
                if (!value.valid(now(), elapsed())) { sessions.clear(); throw PortalFailure(PortalFailure.Reason.SESSION_EXPIRED) }
                val prior = store.read()
                if (prior != null && prior.accountId != value.accountId) { store.clear() }
                try {
                    sessions.save(SavedSession.capture(value, now(), elapsed()))
                    context.ensureActive(); checkGeneration(epoch)
                } catch (failure: Throwable) { sessions.clear(); throw failure }
                session = value
            }
            value
        } }
    }

    suspend fun security(): AccountSecurity = mutex.withLock { guarded {
        val epoch = generation.get(); val active = activeSession()
        val response = transport.request("/api/portal/security/current", session = active.token)
        expectCurrent(response, setOf(200), epoch); checkGeneration(epoch); activeSession()
        PortalJson.security(response.body)
    } }
    suspend fun refresh(): Resource = mutex.withLock { guarded { refreshLocked() } }
    private suspend fun refreshLocked(epoch: Long = generation.get()): Resource {
        // Failed or superseded refreshes must not leave an old profile eligible for use.
        synchronized(activationLock) { configurationGeneration++; invalidateImport() }
        checkGeneration(epoch); val active = activeSession()
        val response = transport.request("/api/portal/resources/current/overview", session = active.token)
        expectCurrent(response, setOf(200), epoch); checkGeneration(epoch); activeSession()
        return PortalJson.resource(response.body)
    }

    /** Called after sign-in and on explicit refresh. No production connection or VPN permission. */
    suspend fun refreshAndImport(onResource: (Resource) -> Unit = {},
        onDeliveryReceived: () -> Unit = {}): ImportSnapshot = importFresh(onResource, onDeliveryReceived)

    internal suspend fun prepareFreshConnection(requested: String?, resolver: ConnectionLineResolver,
        files: AtomicPreparedConfigStore): AuthorizedConfig? {
        var prepared: AuthorizedConfig? = null
        var consumed = false
        try {
            importFresh(selection = { resource -> resolver.resolve(resource, requested)?.also {
                require(requested == null || requested == it.lineId)
            } }, onPrepared = { resource, selected, bytes, epoch, revision ->
                prepared = AuthorizedConfig(selected.lineId, bytes) { action ->
                    synchronized(activationLock) {
                        check(!consumed) { "Preparation already consumed" }
                        consumed = true
                        checkGeneration(epoch)
                        activeSession()
                        check(configurationGeneration == revision && resource.validUntil > now()) {
                            "Preparation expired"
                        }
                        val copy = requireNotNull(prepared).copyMaterial()
                        try { files.replace(copy) } finally { copy.fill(0) }
                        activeSession()
                        check(resource.validUntil > now()) { "Preparation expired" }
                        // Commit and engine acceptance are one account/attempt-fenced handoff.
                        action()
                    }
                }
            })
            currentCoroutineContext().ensureActive()
            return prepared
        } catch (failure: Throwable) { prepared?.close(); throw failure }
    }

    private suspend fun importFresh(onResource: (Resource) -> Unit = {}, onDeliveryReceived: () -> Unit = {},
        selection: ((Resource.Available) -> ResolvedConnectionLine?)? = null,
        onPrepared: ((Resource.Available, ResolvedConnectionLine, ByteArray, Long, Long) -> Unit)? = null,
    ): ImportSnapshot = mutex.withLock {
        guarded {
            val epoch = generation.get()
            val resource = refreshLocked(epoch)
            onResource(resource)
            checkGeneration(epoch)
            val inactive = when {
                resource == Resource.None -> ImportStatus.NO_RESOURCE
                resource == Resource.Reconciliation -> ImportStatus.RECONCILIATION
                resource is Resource.Available && resource.validUntil <= now() -> ImportStatus.EXPIRED
                resource is Resource.Available && resource.usage.status in setOf("measured", "measured_zero") &&
                    resource.usage.remainingBytes == 0L -> ImportStatus.QUOTA_EXHAUSTED
                else -> null
            }
            if (inactive != null) return@guarded ImportSnapshot(resource, inactive)
            resource as Resource.Available
            val selected = if (selection != null) selection(resource)
                ?: return@guarded ImportSnapshot(resource, ImportStatus.UNSUPPORTED_CONFIGURATION)
                else ResolvedConnectionLine(resource.reference + ":offline-preview-index-0", 0)
            val record = (if (onPrepared != null) acquireFreshLocked(resource, epoch) else acquireLocked(resource, epoch))
                ?: return@guarded ImportSnapshot(resource, ImportStatus.WAITING_DELIVERY)
            checkGeneration(epoch); activeSession()
            onDeliveryReceived()
            val raw = subscription.download(requireNotNull(record.url))
            try {
                checkGeneration(epoch); activeSession(); currentCoroutineContext().ensureActive()
                if (resource.validUntil <= now()) return@guarded ImportSnapshot(resource, ImportStatus.EXPIRED)
                // Offline compatibility preview only: list order is not a stable line ID or
                // server recommendation. Production has no gateway/service consuming this profile.
                // Every node is validated even though only index 0 is retained for this preview.
                val nodes = try { SafeProxyConfig.inspect(raw) }
                    catch (_: UnsafeProxyConfiguration) { return@guarded ImportSnapshot(resource, ImportStatus.UNSUPPORTED_CONFIGURATION) }
                val projected = try { SafeProxyConfig.project(raw, selected.index) }
                    catch (_: UnsafeProxyConfiguration) { return@guarded ImportSnapshot(resource, ImportStatus.UNSUPPORTED_CONFIGURATION, nodes) }
                try {
                    checkGeneration(epoch); activeSession(); currentCoroutineContext().ensureActive()
                    synchronized(activationLock) {
                        checkGeneration(epoch)
                        if (onPrepared != null) onPrepared(resource, selected, projected, epoch, configurationGeneration)
                        else {
                            localSubscriptions.remember(raw, requireNotNull(record.url), resource.validUntil)
                            imported = AuthorizedConfig(selected.lineId, projected)
                            importedUntil = resource.validUntil
                            importedGeneration = epoch
                        }
                    }
                } finally { projected.fill(0) }
                ImportSnapshot(resource, ImportStatus.IMPORTED, nodes)
            } finally { raw.fill(0) }
        }
    }

    /** Cached offline preview only. NEVER use as a fresh connect/reconnect/switch source.
     * Not evidence that a tunnel was started or traffic verified.
     * Consumers own/close their copy. Production VPN remains separately gated off.
     */
    suspend fun copyImportedConfig(): AuthorizedConfig? = mutex.withLock {
        guarded {
            activeSession()
            if (importedGeneration != generation.get() || importedUntil?.let { it <= now() } != false) { invalidateImport(); return@guarded null }
            val value = imported ?: return@guarded null
            val bytes = value.copyMaterial()
            try { AuthorizedConfig(value.lineId, bytes) } finally { bytes.fill(0) }
        }
    }

    /** Commerce shares the authenticated session but cannot read or mutate delivery credentials. */
    suspend fun commerce(path: String, body: String? = null, key: String? = null, expectedSession: String? = null): WireResponse = mutex.withLock {
        guarded {
            require(path.startsWith("/api/portal/commerce/") || path.startsWith("/api/portal/catalog/") ||
                path.startsWith("/api/portal/coins/"))
            val epoch = generation.get(); val active = activeSession()
            if (expectedSession != null && expectedSession != sessionBinding(active.token)) throw CancellationException("Commerce session changed")
            val response = transport.commerce(path, body, active.token, key)
            checkGeneration(epoch); activeSession()
            if (response.status == 401) expectCurrent(response, emptySet(), epoch)
            response
        }
    }
    suspend fun remainingSessionMillis(): Long = mutex.withLock {
        val value = activeSession()
        minOf(value.lifetimeMillis - (elapsed() - value.receivedElapsed),
            java.time.Duration.between(now(), value.expiresAt).toMillis(), SavedSession.MAX_NATIVE_SESSION_MILLIS).also { require(it > 0) }
    }
    suspend fun commerceSessionBinding(): String = mutex.withLock { sessionBinding(activeSession().token) }
    suspend fun commerceAccountBinding(): String = mutex.withLock {
        // Stable account-scoped recovery survives reauthentication without saving account PII or tokens.
        sessionBinding(Secret(activeSession().accountId))
    }
    suspend fun acquire(resource: Resource.Available): Unit = mutex.withLock {
        guarded { acquireLocked(resource); Unit }
    }
    /** Current model is authoritative; do not infer it from setupAvailable or an opaque ID. */
    private suspend fun acquireFreshLocked(resource: Resource.Available, epoch: Long): DeliveryRecord? {
        checkGeneration(epoch)
        val active = activeSession()
        val modelResponse = transport.commerce("/api/portal/commerce/model", null, active.token, null)
        expectCurrent(modelResponse, setOf(200), epoch); checkGeneration(epoch); activeSession()
        return when (CommerceJson.model(modelResponse.body)) {
            CommerceModel.WALLET_FIRST -> acquireLocked(resource, epoch)
            CommerceModel.LEGACY -> {
                // Legacy setup re-reads the current server-selected request; its ACK is non-consuming.
                // Never adopt an old-session handoff or overwrite a potentially wallet-first receipt.
                // No historical request lookup and no subscription refresh/rotation endpoint.
                if (!resource.setupAvailable) return null
                val attempt = DeliveryRecord(active.accountId, sessionBinding(active.token), resource.reference,
                    UUID.randomUUID().toString())
                val response = transport.request("/api/portal/resources/current/setup",
                    JSONObject().put("idempotencyKey", attempt.operationKey).toString(), active.token)
                expectCurrent(response, setOf(200), epoch); checkGeneration(epoch); activeSession()
                val received = PortalJson.delivery(response.body, attempt, policy)
                val ack = transport.request("/api/portal/resources/current/setup/ack",
                    JSONObject().put("handoffRef", requireNotNull(received.handoff).value).toString(), active.token)
                expectCurrent(ack, setOf(204), epoch); checkGeneration(epoch); activeSession()
                received
            }
        }
    }

    private suspend fun acquireLocked(resource: Resource.Available, epoch: Long = generation.get()): DeliveryRecord? {
            require(resource.validUntil > now())
            checkGeneration(epoch); val active = activeSession()
            var record = store.read()
            if (record != null && record.accountId != active.accountId) {
                // A confirmed new login must never adopt another account's material.
                store.clear(); record = null
            }
            if (record != null && record.resource != resource.reference) {
                // A renewal can change resourceRef without issuing new delivery material.
                // Preserve the receipt; no verified stable delivery identity permits rebinding it.
                throw PortalFailure(PortalFailure.Reason.RECOVERY_REQUIRED)
            }
            if (record != null && record.sessionBinding != sessionBinding(active.token)) {
                // Ack is session-bound. Do not silently create a second claim or lose recoverable material.
                if (record.pendingAck || record.url == null) throw PortalFailure(PortalFailure.Reason.RECOVERY_REQUIRED)
                policy.subscription(requireNotNull(record.url).value)
                return record // Existing acknowledged material is retained for this same account/resource.
            }
            if (record == null) {
                if (!resource.setupAvailable) return null
                record = DeliveryRecord(active.accountId, sessionBinding(active.token), resource.reference, UUID.randomUUID().toString())
                synchronized(activationLock) { checkGeneration(epoch); store.save(record) } // Persist BEFORE first attempt.
            }
            if (record.url == null) {
                checkGeneration(epoch); activeSession()
                val response = transport.request("/api/portal/resources/current/setup",
                    JSONObject().put("idempotencyKey", record.operationKey).toString(), active.token)
                expectCurrent(response, setOf(200), epoch); checkGeneration(epoch); activeSession()
                record = PortalJson.delivery(response.body, record, policy)
                synchronized(activationLock) { checkGeneration(epoch); store.save(record) } // Only durable receipt permits ACK.
            }
            if (record.pendingAck) {
                activeSession(); checkGeneration(epoch)
                val response = transport.request("/api/portal/resources/current/setup/ack",
                    JSONObject().put("handoffRef", requireNotNull(record.handoff).value).toString(), active.token)
                expectCurrent(response, setOf(204), epoch); checkGeneration(epoch); activeSession()
                record = DeliveryRecord(record.accountId, record.sessionBinding, record.resource, record.operationKey,
                    record.url, record.handoff, false)
                synchronized(activationLock) { checkGeneration(epoch); store.save(record) }
            }
            return record
    }

    /** Cancels authentication without destroying an earlier durable subscription. */
    suspend fun cancelAuthentication() {
        synchronized(activationLock) {
            generation.incrementAndGet(); session = null; totpExpiry = null; invalidateImport()
            sessions.clear() // Synchronous admission: caller cancellation cannot skip token removal.
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            mutex.withLock { invalidateImport(); session = null; totpExpiry = null; sessions.clear() }
        }
    }
    /** Local sign-out only. There is no verified native server revocation endpoint. */
    suspend fun clearLocalData() {
        synchronized(activationLock) {
            generation.incrementAndGet(); session = null; totpExpiry = null; invalidateImport()
            try { sessions.clear() } finally { localSubscriptions.unlink() }
        }
        // Finish admitted local cleanup even if the Activity scope is disposed while IO is draining.
        // HTTPS operations holding the mutex have the transport's bounded timeout.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            mutex.withLock { invalidateImport(); session = null; totpExpiry = null; try { sessions.clear() } finally { localSubscriptions.unlink() } }
        }
    }
    /** Account logout does not unlink a previously acknowledged local subscription capability. */
    suspend fun signOutAccount() = cancelAuthentication()

    private fun invalidateSession() = synchronized(activationLock) {
        generation.incrementAndGet()
        invalidateImport()
        session = null
        sessions.clear()
    }
    private fun activeSession(): Session = synchronized(activationLock) {
        val value = session ?: throw PortalFailure(PortalFailure.Reason.SESSION_EXPIRED)
        if (!value.valid(now(), elapsed())) {
            invalidateSession(); throw PortalFailure(PortalFailure.Reason.SESSION_EXPIRED)
        }
        value
    }
    private fun checkGeneration(epoch: Long) {
        if (generation.get() != epoch) throw CancellationException("Account operation cancelled")
    }
    private fun expectCurrent(response: WireResponse, accepted: Set<Int>, epoch: Long) = synchronized(activationLock) {
        checkGeneration(epoch)
        expect(response, accepted)
    }
    private fun expect(response: WireResponse, accepted: Set<Int>, protected: Boolean = true) {
        if (response.status in accepted) return
        val reason = when(response.status) {
            400, 401 -> if(protected && response.status == 401) {
                invalidateSession(); PortalFailure.Reason.SESSION_EXPIRED
            } else PortalFailure.Reason.LOGIN_FAILED
            409 -> PortalFailure.Reason.RECONCILIATION
            429 -> PortalFailure.Reason.RATE_LIMITED
            in 500..599 -> PortalFailure.Reason.UNAVAILABLE
            else -> PortalFailure.Reason.PROTOCOL
        }
        throw PortalFailure(reason)
    }
    private suspend fun <T> guarded(block: suspend () -> T): T = try { block() }
        catch (failure: CancellationException) { throw failure }
        catch (failure: PortalFailure) { throw failure }
        catch (_: Exception) { throw PortalFailure(PortalFailure.Reason.PROTOCOL) }
}
