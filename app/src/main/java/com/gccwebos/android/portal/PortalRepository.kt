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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** One delivery at a time, scoped to the exact account, session and resource. */
class PortalRepository(private val transport: PortalTransport, private val store: DeliveryStore,
    private val policy: OriginPolicy, private val elapsed: () -> Long,
    private val now: () -> Instant = Instant::now,
    private val subscription: SubscriptionSource = SubscriptionTransport(policy)) {
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private var session: Session? = null
    private var imported: AuthorizedConfig? = null
    private var importedUntil: Instant? = null
    private var importedGeneration: Long? = null
    private fun invalidateImport() { imported?.close(); imported = null; importedUntil = null; importedGeneration = null }
    private var totpExpiry: Pair<String, Long>? = null

    suspend fun humanChallenge(): HumanChallenge = guarded {
        val response = transport.request("/api/portal/human-challenges", JSONObject().put("scope", "portal_login").toString())
        expect(response, setOf(201), false)
        val value = JSONObject(response.body).getJSONObject("challenge")
        val lifetime = value.getLong("expiresInSeconds")
        require(lifetime in 1..3600)
        HumanChallenge(value.optString("challengeRef").ifBlank { value.getString("id") }, value.getString("prompt"), elapsed() + lifetime * 1000)
    }
    suspend fun login(account: String, password: String, challenge: HumanChallenge, answer: String): LoginResult = mutex.withLock {
        guarded {
            invalidateImport()
            val epoch = generation.get()
            if (elapsed() >= challenge.expiresElapsed) throw PortalFailure(PortalFailure.Reason.LOGIN_FAILED)
            totpExpiry = null
            val body = JSONObject().put("accountId", account).put("password", password).put("rememberMe", false)
                .put("humanVerification", JSONObject().put("challengeRef", challenge.reference).put("answer", answer))
            val response = transport.request("/api/portal/password/session", body.toString())
            expect(response, setOf(200, 202), false); checkGeneration(epoch)
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
            } else acceptSession(response.body)
        }
    }
    suspend fun totp(reference: String, code: String): LoginResult.Authenticated = mutex.withLock {
        guarded {
            val epoch = generation.get()
            val ticket = totpExpiry
            if (ticket == null || ticket.first != reference || elapsed() >= ticket.second) {
                totpExpiry = null; throw PortalFailure(PortalFailure.Reason.LOGIN_FAILED)
            }
            val response = transport.request("/api/portal/password/second-factor", JSONObject().put("challengeRef", reference)
                .put("factor", "totp").put("code", code).toString())
            expect(response, setOf(200), false); checkGeneration(epoch); acceptSession(response.body)
        }
    }
    private fun acceptSession(body: String): LoginResult.Authenticated {
        invalidateImport()
        val value = PortalJson.session(JSONObject(body), elapsed())
        require(value.valid(now(), elapsed()))
        val prior = store.read()
        if (prior != null && prior.accountId != value.accountId) store.clear()
        totpExpiry = null
        session = value
        return LoginResult.Authenticated(value)
    }
    suspend fun refresh(): Resource = mutex.withLock { guarded { refreshLocked() } }
    private suspend fun refreshLocked(epoch: Long = generation.get()): Resource {
        // Failed or superseded refreshes must not leave an old profile eligible for use.
        invalidateImport()
        checkGeneration(epoch); val active = activeSession()
        val response = transport.request("/api/portal/resources/current/overview", session = active.token)
        expect(response, setOf(200)); checkGeneration(epoch); activeSession()
        return PortalJson.resource(response.body)
    }

    /** Called after sign-in and on explicit refresh. No production connection or VPN permission. */
    suspend fun refreshAndImport(onResource: (Resource) -> Unit = {},
        onDeliveryReceived: () -> Unit = {}): ImportSnapshot = mutex.withLock {
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
            val record = acquireLocked(resource, epoch)
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
                val projected = try { SafeProxyConfig.project(raw, 0) }
                    catch (_: UnsafeProxyConfiguration) { return@guarded ImportSnapshot(resource, ImportStatus.UNSUPPORTED_CONFIGURATION) }
                try {
                    checkGeneration(epoch); activeSession(); currentCoroutineContext().ensureActive()
                    imported = AuthorizedConfig(resource.reference + ":offline-preview-index-0", projected)
                    importedUntil = resource.validUntil
                    importedGeneration = epoch
                } finally { projected.fill(0) }
                ImportSnapshot(resource, ImportStatus.IMPORTED)
            } finally { raw.fill(0) }
        }
    }

    /** Fresh in-memory projection only; not evidence that a tunnel was started or traffic verified.
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
            if (response.status == 401) { invalidateImport(); session = null; throw PortalFailure(PortalFailure.Reason.SESSION_EXPIRED) }
            response
        }
    }
    suspend fun commerceSessionBinding(): String = mutex.withLock { sessionBinding(activeSession().token) }
    suspend fun commerceAccountBinding(): String = mutex.withLock {
        // Stable account-scoped recovery survives reauthentication without saving account PII or tokens.
        sessionBinding(Secret(activeSession().accountId))
    }
    suspend fun acquire(resource: Resource.Available): Unit = mutex.withLock {
        guarded { acquireLocked(resource); Unit }
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
                store.save(record) // Persist idempotency key BEFORE first attempt.
            }
            if (record.url == null) {
                checkGeneration(epoch); activeSession()
                val response = transport.request("/api/portal/resources/current/setup",
                    JSONObject().put("idempotencyKey", record.operationKey).toString(), active.token)
                expect(response, setOf(200)); checkGeneration(epoch); activeSession()
                record = PortalJson.delivery(response.body, record, policy)
                store.save(record) // Only durable encrypted receipt permits ack.
            }
            if (record.pendingAck) {
                activeSession(); checkGeneration(epoch)
                val response = transport.request("/api/portal/resources/current/setup/ack",
                    JSONObject().put("handoffRef", requireNotNull(record.handoff).value).toString(), active.token)
                expect(response, setOf(204)); checkGeneration(epoch); activeSession()
                record = DeliveryRecord(record.accountId, record.sessionBinding, record.resource, record.operationKey,
                    record.url, record.handoff, false)
                store.save(record)
            }
            return record
    }

    /** Cancels authentication without destroying an earlier durable subscription. */
    suspend fun cancelAuthentication() {
        generation.incrementAndGet()
        mutex.withLock { invalidateImport(); session = null; totpExpiry = null }
    }
    /** Local sign-out only. There is no verified native server revocation endpoint. */
    suspend fun logout() {
        generation.incrementAndGet()
        mutex.withLock { invalidateImport(); session = null; totpExpiry = null; store.clear() }
    }
    private fun activeSession(): Session {
        val value = session
        if (value == null || !value.valid(now(), elapsed())) {
            invalidateImport(); session = null; throw PortalFailure(PortalFailure.Reason.SESSION_EXPIRED)
        }
        return value
    }
    private fun checkGeneration(epoch: Long) {
        if (generation.get() != epoch) throw CancellationException("Account operation cancelled")
    }
    private fun expect(response: WireResponse, accepted: Set<Int>, protected: Boolean = true) {
        if (response.status in accepted) return
        val reason = when(response.status) {
            400, 401 -> if(protected && response.status == 401) {
                invalidateImport(); session = null; PortalFailure.Reason.SESSION_EXPIRED
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
