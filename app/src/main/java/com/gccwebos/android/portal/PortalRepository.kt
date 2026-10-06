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

/** The login contract uses ECMAScript String.trim, which differs from Kotlin's whitespace set. */
internal fun normalizedLoginPassword(password: String): String = password.trim { character ->
    character in '\u0009'..'\u000D' || character in '\u2000'..'\u200A' ||
        character in " \u00A0\u1680\u2028\u2029\u202F\u205F\u3000\uFEFF"
}

/** One delivery at a time, scoped to the exact account, session and resource. */
class PortalRepository(private val transport: PortalTransport, private val store: DeliveryStore,
    private val policy: OriginPolicy, private val elapsed: () -> Long,
    private val now: () -> Instant = Instant::now,
    private val subscription: SubscriptionSource = SubscriptionTransport(policy),
    private val sessions: SessionStore = MemorySessionStore(),
    profiles: LocalProfileStore = MemoryLocalProfileStore(),
    private val allowHostnamePreparation: Boolean = false,
    manualSources: ManualSourceStore? = null,
    externalSubscription: SubscriptionSource = ManualSubscriptionTransport(),
    private val nativeSessionRenewal: Boolean = false) {
    val localSubscriptions = LocalSubscriptionManager(store, profiles, subscription, policy, now,
        allowHostnamePreparation = allowHostnamePreparation, manualSources = manualSources, externalSource = externalSubscription)
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private val activationLock = Any()
    private var configurationGeneration = 0L
    @Volatile private var session: Session? = null
    private class RestorationFlight(val epoch: Long, val result: kotlinx.coroutines.CompletableDeferred<Session?>)
    private var restorationFlight: RestorationFlight? = null
    private var renewalRequired = false
    fun hasRenewableSession(): Boolean = synchronized(activationLock) {
        nativeSessionRenewal && sessions.read()?.refreshGrant?.valid(now(), elapsed()) == true
    }
    // Memory-only evidence of actual parsed reads, including the legacy import path.
    private var overviewRevision = 0L
    private var overviewIssuedSequence = 0L
    private var overviewAcceptedSequence = 0L
    private var overviewIdentity: AccountUsageResourceIdentity? = null
    private class OverviewReadLease(val session: Session, val epoch: Long, val issuedSequence: Long,
        val resourceRevision: Long)
    private var imported: AuthorizedConfig? = null
    private var importedUntil: Instant? = null
    private var importedGeneration: Long? = null
    private fun invalidateImport() { imported?.close(); imported = null; importedUntil = null; importedGeneration = null }
    @Volatile private var totpExpiry: Pair<String, Long>? = null

    /** The form and final submission share the repository's monotonic clock. */
    internal fun remainingHumanChallengeMillis(challenge: HumanChallenge): Long =
        (challenge.expiresElapsed - elapsed()).coerceAtLeast(0L)

    suspend fun humanChallenge(): HumanChallenge = guarded {
        val response = transport.request("/api/portal/human-challenges", JSONObject().put("scope", "portal_login").toString())
        expect(response, setOf(201), false)
        val value = JSONObject(response.body).getJSONObject("challenge")
        val lifetime = value.getLong("expiresInSeconds")
        require(lifetime in 1..3600)
        HumanChallenge(value.optString("challengeRef").ifBlank { value.getString("id") }, value.getString("prompt"), elapsed() + lifetime * 1000)
    }
    suspend fun login(account: String, password: String, challenge: HumanChallenge, answer: String): LoginResult {
        val submittedPassword = normalizedLoginPassword(password)
        if (submittedPassword.isEmpty()) throw PortalFailure(PortalFailure.Reason.LOGIN_FAILED)
        val epoch = synchronized(activationLock) { generation.incrementAndGet().also { session = null; totpExpiry = null } }
        return mutex.withLock {
            guarded {
                checkGeneration(epoch)
                invalidateImport()
                sessions.clear()
                if (elapsed() >= challenge.expiresElapsed) throw PortalFailure(PortalFailure.Reason.LOGIN_FAILED)
                totpExpiry = null
                val body = JSONObject().put("accountId", account).put("password", submittedPassword).put("rememberMe", false)
                    .put("humanVerification", JSONObject().put("challengeRef", challenge.reference).put("answer", answer))
                val response = transport.request("/api/portal/password/session", body.toString())
                expect(response, setOf(200, 202), false)
                val context = currentCoroutineContext()
                context.ensureActive()
                if (response.status == 202) synchronized(activationLock) {
                    checkGeneration(epoch)
                    run {
                        val value = JSONObject(response.body).getJSONObject("challenge")
                        require(value.getString("status") == "second_factor_required")
                        val factors = value.getJSONArray("availableFactors")
                        require((0 until factors.length()).any { factors.getString(it) == "totp" })
                        val reference = value.getString("challengeRef")
                        val ttl = java.time.Duration.between(now(), Instant.parse(value.getString("expiresAt"))).toMillis()
                        require(ttl > 0)
                        totpExpiry = reference to (elapsed() + minOf(ttl, 300000))
                        LoginResult.TotpRequired(reference)
                    }
                } else acceptSession(response.body, epoch, context)
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
    private suspend fun acceptSession(body: String, epoch: Long, context: CoroutineContext): LoginResult.Authenticated {
        val value = PortalJson.session(JSONObject(body), elapsed())
        require(value.valid(now(), elapsed()))
        // Persist the already successful short login even when the optional exchange is unavailable.
        publishSession(value, null, epoch, context)
        if (nativeSessionRenewal) {
            val pair = try {
                val response = transport.request("/api/portal/native-session", "{}", value.token)
                context.ensureActive(); checkGeneration(epoch)
                if (response.status != 200) null else PortalJson.nativeSession(response.body, now(), elapsed()).also {
                    require(it.first.token.value == value.token.value && it.first.accountId == value.accountId &&
                        it.first.expiresAt == value.expiresAt && it.first.lifetimeMillis == value.lifetimeMillis)
                    require(it.second.issuedAt == Instant.parse(JSONObject(body).getJSONObject("session").getString("issuedAt")))
                }
            } catch (failure: CancellationException) { throw failure }
              catch (_: Exception) { null } // A grant is optional; the original access authority is unchanged.
            if (pair != null) publishSession(value, pair.second, epoch, context)
        }
        context.ensureActive(); checkGeneration(epoch)
        return LoginResult.Authenticated(value)
    }
    private fun publishSession(value: Session, grant: NativeRefreshGrant?, epoch: Long, context: CoroutineContext) = synchronized(activationLock) {
        context.ensureActive(); checkGeneration(epoch)
        invalidateImport()
        localSubscriptions.whenLegacySource {
            val prior = store.read()
            context.ensureActive()
            if (prior != null && prior.accountId != value.accountId) store.clear()
        }
        totpExpiry = null
        try { sessions.save(SavedSession.capture(value, now(), elapsed(), grant)) }
        catch (failure: Throwable) { session = null; sessions.clear(); throw failure }
        // A cancelled owner may leave a committed pair, but cannot expose it or erase a newer login.
        context.ensureActive(); checkGeneration(epoch)
        renewalRequired = false
        session = value
    }
    /** Single-flight restoration/rotation. No protected mutation is ever replayed here. */
    suspend fun restoreSession(forceRenewal: Boolean = false): Session? {
        var owner = false
        val flight = synchronized(activationLock) {
            restorationFlight?.takeIf { it.epoch == generation.get() } ?: RestorationFlight(
                generation.incrementAndGet(), kotlinx.coroutines.CompletableDeferred()).also {
                restorationFlight = it; session = null; totpExpiry = null; owner = true
            }
        }
        if (!owner) return flight.result.await()
        try {
            val result = mutex.withLock { guarded { restoreLocked(flight.epoch, forceRenewal) } }
            flight.result.complete(result)
            return result
        } catch (failure: Throwable) {
            flight.result.completeExceptionally(failure)
            throw failure
        } finally { synchronized(activationLock) { if (restorationFlight === flight) restorationFlight = null } }
    }
    private suspend fun restoreLocked(epoch: Long, forceRenewal: Boolean): Session? {
        checkGeneration(epoch); invalidateImport()
        val saved = sessions.read() ?: return null
        val grant = saved.refreshGrant?.takeIf { nativeSessionRenewal && it.valid(now(), elapsed()) }
        var value = saved.restore(now(), elapsed())
        var activeGrant = grant
        var rotated = false
        if (grant != null && (value == null || forceRenewal || renewalRequired ||
                java.time.Duration.between(now(), value.expiresAt).toMillis() <= 60000)) {
            val pair = rotateSession(saved, grant, epoch)
            value = pair.first; activeGrant = pair.second; rotated = true
        }
        if (value == null) { synchronized(activationLock) { checkGeneration(epoch); sessions.clear() }; return null }
        var response = transport.request("/api/portal/resources/current/overview", session = value.token)
        checkGeneration(epoch)
        if (response.status == 401 && grant != null && !rotated) {
            val pair = rotateSession(saved, grant, epoch)
            value = pair.first; activeGrant = pair.second
            response = transport.request("/api/portal/resources/current/overview", session = value.token)
        }
        expectCurrent(response, setOf(200), epoch)
        PortalJson.resource(response.body)
        publishSession(value, activeGrant, epoch, currentCoroutineContext())
        return value
    }
    private suspend fun rotateSession(saved: SavedSession, grant: NativeRefreshGrant, epoch: Long): Pair<Session, NativeRefreshGrant> {
        val response = transport.request("/api/portal/native-session/refresh",
            JSONObject().put("refreshToken", grant.token.value).toString())
        currentCoroutineContext().ensureActive(); checkGeneration(epoch)
        if (response.status == 401) {
            synchronized(activationLock) { checkGeneration(epoch); invalidateSession(clearGrant = true) }
            throw PortalFailure(PortalFailure.Reason.SESSION_EXPIRED)
        }
        expect(response, setOf(200), false)
        val pair = PortalJson.nativeSession(response.body, now(), elapsed())
        require(pair.first.accountId == saved.accountId && pair.first.token.value != saved.token.value)
        require(pair.second.token.value != grant.token.value && pair.second.issuedAt == grant.issuedAt && pair.second.expiresAt == grant.expiresAt)
        synchronized(activationLock) {
            checkGeneration(epoch)
            // Commit the complete pair before another read. A lost overview does not lose a rotated grant.
            sessions.save(SavedSession.capture(pair.first, now(), elapsed(), pair.second))
        }
        return pair
    }

    suspend fun security(): AccountSecurity = mutex.withLock { guarded {
        val epoch = generation.get(); val active = activeSession()
        val response = transport.request("/api/portal/security/current", session = active.token)
        expectCurrent(response, setOf(200), epoch); checkGeneration(epoch); activeSession()
        PortalJson.security(response.body)
    } }
    suspend fun refresh(): Resource = mutex.withLock { guarded { refreshLocked() } }
    private suspend fun refreshLocked(epoch: Long = generation.get()): Resource = refreshOverviewLocked(epoch).resource
    /** Existing import refresh semantics, now carrying the identity of that exact read. */
    internal suspend fun refreshWithObservation(): BoundAccountOverview = mutex.withLock { guarded { refreshOverviewLocked() } }
    private suspend fun refreshOverviewLocked(epoch: Long = generation.get()): BoundAccountOverview {
        // Failed or superseded refreshes must not leave an old profile eligible for use.
        synchronized(activationLock) { configurationGeneration++; invalidateImport() }
        val lease = captureOverviewRead(expectedEpoch = epoch)
        val response = transport.request("/api/portal/resources/current/overview", session = lease.session.token)
        return acceptOverviewResponse(response, lease)
    }

    /** Only an overview GET. HTTP awaits outside the mutex so logout/new login can proceed. */
    internal suspend fun readAccountOverview(expectedOwner: AccountUsageOwner,
        expectedRepositoryEpoch: Long? = null): BoundAccountOverview = guarded {
        val captured = mutex.withLock {
            captureOverviewRead(expectedOwner, expectedRepositoryEpoch)
        }
        val response = transport.request("/api/portal/resources/current/overview", session = captured.session.token)
        mutex.withLock { acceptOverviewResponse(response, captured) }
    }

    /** Atomic authentication context only: no HTTP, resource inference or access credentials. */
    internal suspend fun captureAccountUsageAuthentication(expectedOwner: AccountUsageOwner): AccountUsageAuthentication = mutex.withLock { guarded {
        synchronized(activationLock) {
            val epoch = generation.get(); val active = activeSession()
            if (AccountUsageOwner.fromSession(active) != expectedOwner) throw CancellationException("Overview owner changed")
            AccountUsageAuthentication(expectedOwner, epoch)
        }
    } }

    private fun captureOverviewRead(expectedOwner: AccountUsageOwner? = null, expectedEpoch: Long? = null): OverviewReadLease = synchronized(activationLock) {
        val epoch = generation.get(); val active = activeSession()
        if (expectedEpoch != null && expectedEpoch != epoch) throw CancellationException("Overview authentication changed")
        if (expectedOwner != null && AccountUsageOwner.fromSession(active) != expectedOwner) throw CancellationException("Overview owner changed")
        OverviewReadLease(active, epoch, ++overviewIssuedSequence, overviewRevision)
    }

    private fun checkOverviewOwner(captured: Session, epoch: Long, expected: AccountUsageOwner) = synchronized(activationLock) {
        checkGeneration(epoch)
        val current = activeSession()
        if (AccountUsageOwner.fromSession(current) != expected || AccountUsageOwner.fromSession(captured) != expected)
            throw CancellationException("Overview owner changed")
    }

    private fun acceptOverviewResponse(response: WireResponse, lease: OverviewReadLease): BoundAccountOverview = synchronized(activationLock) {
        val owner = AccountUsageOwner.fromSession(lease.session)
        checkOverviewOwner(lease.session, lease.epoch, owner)
        // Both fences are captured at request start. Failures never advance the accepted fence.
        if (lease.issuedSequence < overviewAcceptedSequence || lease.resourceRevision != overviewRevision)
            throw CancellationException("Overview read superseded")
        expectCurrent(response, setOf(200), lease.epoch)
        val resource = PortalJson.resource(response.body)
        checkOverviewOwner(lease.session, lease.epoch, owner)
        val identity = AccountUsageResourceIdentity.of(resource)
        if (overviewIdentity != identity) { overviewIdentity = identity; overviewRevision++ }
        overviewAcceptedSequence = lease.issuedSequence
        BoundAccountOverview(AccountUsageReadContext(owner, lease.epoch, overviewRevision, lease.issuedSequence), resource)
    }

    /** Called after sign-in and on explicit refresh. No production connection or VPN permission. */
    suspend fun refreshAndImport(onResource: (Resource) -> Unit = {},
        onDeliveryReceived: () -> Unit = {}): ImportSnapshot = importFresh(onResource, onDeliveryReceived)

    internal suspend fun refreshAndImportObserved(onOverview: (BoundAccountOverview) -> Unit,
        onDeliveryReceived: () -> Unit = {}): ImportSnapshot = importFresh(onDeliveryReceived = onDeliveryReceived, onOverview = onOverview)

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
        onOverview: (BoundAccountOverview) -> Unit = {},
    ): ImportSnapshot = mutex.withLock { localSubscriptions.withLegacySource {
        guarded {
            val epoch = generation.get()
            val clearVersion = localSubscriptions.captureClearVersion()
            val overview = refreshOverviewLocked(epoch)
            val resource = overview.resource
            onResource(resource)
            onOverview(overview)
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
            val recovery = if (onPrepared == null) acquireForImportLocked(resource, overview, epoch, clearVersion) else null
            val record = (if (onPrepared != null) acquireFreshLocked(resource, epoch) else recovery?.record)
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
                val nodes = try {
                    if (allowHostnamePreparation && onPrepared == null) SafeProxyConfig.validateForPreparation(raw)
                    else SafeProxyConfig.inspect(raw)
                }
                    catch (failure: UnsafeProxyConfiguration) {
                        return@guarded ImportSnapshot(resource, ImportStatus.UNSUPPORTED_CONFIGURATION, rejection = failure.reason)
                    }
                val projected = try { SafeProxyConfig.project(raw, selected.index) }
                    catch (failure: UnsafeProxyConfiguration) {
                        if (allowHostnamePreparation && onPrepared == null &&
                            failure.reason == SafeProxyConfig.RejectionReason.EndpointHostname) null
                        else return@guarded ImportSnapshot(resource, ImportStatus.UNSUPPORTED_CONFIGURATION, nodes, failure.reason)
                    }
                try {
                    val context = currentCoroutineContext()
                    checkGeneration(epoch); activeSession(); context.ensureActive()
                    synchronized(activationLock) {
                        checkGeneration(epoch)
                        if (onPrepared != null) onPrepared(resource, selected, requireNotNull(projected), epoch, configurationGeneration)
                        else {
                            checkImportObservation(overview, epoch, clearVersion)
                            if (recovery?.journal != null) {
                                localSubscriptions.rememberRecoveredLegacy(raw, recovery.journal, resource.validUntil) {
                                    context.ensureActive(); checkImportObservation(overview, epoch, clearVersion)
                                }
                            } else localSubscriptions.remember(raw, requireNotNull(record.url), resource.validUntil)
                            invalidateImport()
                            // Hostname profiles remain original artifacts. Resolution belongs only
                            // to the fresh physical-network connection attempt, never this preview.
                            imported = projected?.let { AuthorizedConfig(selected.lineId, it) }
                            importedUntil = resource.validUntil
                            importedGeneration = epoch
                        }
                    }
                } finally { projected?.fill(0) }
                ImportSnapshot(resource, ImportStatus.IMPORTED, nodes)
            } finally { raw.fill(0) }
        }
    } }

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
    /** Referral cannot broaden the commerce paths or operate on a replaced native session. */
    internal suspend fun referral(path: String, body: String? = null, expectedSession: String? = null): WireResponse = mutex.withLock {
        guarded {
            require(ReferralJson.validRequest(path, body))
            val epoch = generation.get(); val active = activeSession()
            require(Regex("[a-z][a-z0-9_]{2,95}").matches(active.token.value))
            if (expectedSession != null && expectedSession != sessionBinding(active.token)) throw CancellationException("Referral session changed")
            val response = transport.referral(path, body, active.token)
            currentCoroutineContext().ensureActive(); checkGeneration(epoch); activeSession()
            if (response.status == 401) expectCurrent(response, emptySet(), epoch)
            response
        }
    }
    suspend fun commerceSessionBinding(): String = mutex.withLock { sessionBinding(activeSession().token) }
    suspend fun commerceAccountBinding(): String = mutex.withLock {
        // Stable account-scoped recovery survives reauthentication without saving account PII or tokens.
        sessionBinding(Secret(activeSession().accountId))
    }
    suspend fun acquire(resource: Resource.Available): Unit = mutex.withLock {
        localSubscriptions.withLegacySource { guarded { acquireLocked(resource); Unit } }
    }
    /** A partial profile write must not make the account UI bypass authenticated recovery. */
    internal fun hasPendingLegacyRecovery(): Boolean = synchronized(activationLock) {
        store.readLegacyRecovery() != null
    }
    private class ImportDelivery(val record: DeliveryRecord, val journal: LegacyRecoveryJournal? = null)

    private fun checkImportObservation(overview: BoundAccountOverview, epoch: Long, clearVersion: Long) = synchronized(activationLock) {
        checkGeneration(epoch)
        localSubscriptions.withClearVersion(clearVersion) { Unit }
        val current = activeSession()
        if (overview.context.owner != AccountUsageOwner.fromSession(current) ||
            overview.context.repositoryEpoch != epoch || overview.context.resourceRevision != overviewRevision ||
            overview.context.readSequence != overviewAcceptedSequence)
            throw CancellationException("Import observation changed")
        if ((overview.resource as? Resource.Available)?.validUntil?.let { it <= now() } != false)
            throw PortalFailure(PortalFailure.Reason.SUBSCRIPTION_EXPIRED)
    }

    /** A verified legacy reveal is repeatable; wallet-first receipts remain one-time and session-bound. */
    private suspend fun acquireForImportLocked(resource: Resource.Available, overview: BoundAccountOverview,
        epoch: Long, clearVersion: Long): ImportDelivery? {
        checkImportObservation(overview, epoch, clearVersion)
        val active = activeSession()
        val response = transport.commerce("/api/portal/commerce/model", null, active.token, null)
        expectCurrent(response, setOf(200), epoch)
        currentCoroutineContext().ensureActive(); checkImportObservation(overview, epoch, clearVersion)
        return when (CommerceJson.model(response.body)) {
            CommerceModel.WALLET_FIRST -> {
                if (store.readLegacyRecovery() != null)
                    throw PortalFailure(PortalFailure.Reason.RECOVERY_REQUIRED, PortalFailure.RecoveryCause.MODEL_CHANGED)
                acquireLocked(resource, epoch)?.let { ImportDelivery(it) }
            }
            CommerceModel.LEGACY -> acquireLegacyImportLocked(resource, overview, epoch, active, clearVersion)
        }
    }

    private suspend fun acquireLegacyImportLocked(resource: Resource.Available, overview: BoundAccountOverview,
        epoch: Long, active: Session, clearVersion: Long): ImportDelivery? {
        var journal = store.readLegacyRecovery()
        val binding = sessionBinding(active.token)
        val main = store.read()
        if (main != null && main.accountId != active.accountId)
            throw PortalFailure(PortalFailure.Reason.RECOVERY_REQUIRED, PortalFailure.RecoveryCause.JOURNAL_CONFLICT)
        if (journal != null && !sameDeliveryRecord(main, journal.original) && !sameDeliveryRecord(main, journal.candidate))
            throw PortalFailure(PortalFailure.Reason.RECOVERY_REQUIRED, PortalFailure.RecoveryCause.JOURNAL_CONFLICT)
        val reusable = journal?.candidate?.let {
            it.accountId == active.accountId && it.sessionBinding == binding && it.resource == resource.reference
        } == true
        if (!reusable) {
            if (!resource.setupAvailable) return null
            journal = LegacyRecoveryJournal(main, DeliveryRecord(active.accountId, binding, resource.reference,
                UUID.randomUUID().toString()), LegacyRecoveryPhase.ATTEMPT)
            synchronized(activationLock) {
                localSubscriptions.withClearVersion(clearVersion) {
                    checkImportObservation(overview, epoch, clearVersion); store.saveLegacyRecovery(journal)
                }
            }
        }
        var staged = requireNotNull(journal)
        fun checkStage() {
            checkImportObservation(overview, epoch, clearVersion)
            val current = store.readLegacyRecovery()
            if (current == null || !sameDeliveryRecord(current.original, staged.original) ||
                !sameDeliveryRecord(current.candidate, staged.candidate) || current.phase != staged.phase)
                throw PortalFailure(PortalFailure.Reason.RECOVERY_REQUIRED, PortalFailure.RecoveryCause.JOURNAL_CONFLICT)
        }
        if (staged.candidate.url == null) {
            val reveal = transport.request("/api/portal/resources/current/setup",
                JSONObject().put("idempotencyKey", staged.candidate.operationKey).toString(), active.token)
            expectCurrent(reveal, setOf(200), epoch)
            currentCoroutineContext().ensureActive()
            val received = PortalJson.delivery(reveal.body, staged.candidate, policy)
            synchronized(activationLock) {
                localSubscriptions.withClearVersion(clearVersion) {
                    checkStage()
                    staged = LegacyRecoveryJournal(staged.original, received, LegacyRecoveryPhase.REVEALED)
                    store.saveLegacyRecovery(staged)
                }
            }
        }
        if (staged.candidate.pendingAck) {
            synchronized(activationLock) { checkStage() }
            val ack = transport.request("/api/portal/resources/current/setup/ack",
                JSONObject().put("handoffRef", requireNotNull(staged.candidate.handoff).value).toString(), active.token)
            expectCurrent(ack, setOf(204), epoch)
            currentCoroutineContext().ensureActive()
            synchronized(activationLock) {
                localSubscriptions.withClearVersion(clearVersion) {
                    checkStage()
                    val candidate = staged.candidate
                    staged = LegacyRecoveryJournal(staged.original, DeliveryRecord(candidate.accountId,
                        candidate.sessionBinding, candidate.resource, candidate.operationKey, candidate.url, candidate.handoff,
                        false), LegacyRecoveryPhase.ACKNOWLEDGED)
                    store.saveLegacyRecovery(staged)
                }
            }
        }
        synchronized(activationLock) { checkStage() }
        return ImportDelivery(staged.candidate, staged)
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
                throw PortalFailure(PortalFailure.Reason.RECOVERY_REQUIRED, PortalFailure.RecoveryCause.RESOURCE_CHANGED)
            }
            if (record != null && record.sessionBinding != sessionBinding(active.token)) {
                // Ack is session-bound. Do not silently create a second claim or lose recoverable material.
                if (record.pendingAck || record.url == null) throw PortalFailure(PortalFailure.Reason.RECOVERY_REQUIRED,
                    PortalFailure.RecoveryCause.SESSION_PENDING)
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
        val epoch = synchronized(activationLock) {
            val admitted = generation.incrementAndGet(); session = null; totpExpiry = null; invalidateImport()
            sessions.clear() // Synchronous admission: caller cancellation cannot skip token removal.
            admitted
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            mutex.withLock { synchronized(activationLock) {
                if (generation.get() == epoch) { invalidateImport(); session = null; totpExpiry = null; sessions.clear() }
            } }
        }
    }
    /** Local sign-out only. There is no verified native server revocation endpoint. */
    suspend fun clearLocalData(cleanup: suspend () -> Unit = {},
        commitFence: ((() -> Unit) -> Unit) = { it() }) {
        synchronized(activationLock) {
            generation.incrementAndGet(); session = null; totpExpiry = null; invalidateImport()
            // Revoke the saved session before waiting for an admitted network read to drain.
            // A failed first attempt must still tombstone the source and stop its owner;
            // the final store attempt below remains mandatory and propagates failure.
            try { sessions.clear() } catch (_: Exception) { }
        }
        // Finish admitted local cleanup even if the Activity scope is disposed while IO is draining.
        // HTTPS operations holding the mutex have the transport's bounded timeout.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            localSubscriptions.unlinkAndAwait(cleanup = {
                try { mutex.withLock {
                    invalidateImport(); session = null; totpExpiry = null; sessions.clear()
                } } finally { cleanup() }
            }, commitFence = commitFence)
        }
    }
    /** Account logout does not unlink a previously acknowledged local subscription capability. */
    suspend fun signOutAccount() {
        val grant = synchronized(activationLock) {
            try { sessions.read()?.refreshGrant?.token } catch (_: Exception) { null }
        }
        cancelAuthentication()
        if (nativeSessionRenewal && grant != null) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            try { transport.request("/api/portal/native-session/logout", JSONObject().put("refreshToken", grant.value).toString()) }
            catch (_: Exception) { /* Local removal already succeeded; server expiry remains bounded. */ }
        }
    }

    private fun invalidateSession(clearGrant: Boolean = false) = synchronized(activationLock) {
        generation.incrementAndGet()
        invalidateImport()
        session = null
        renewalRequired = true
        if (clearGrant || !hasRenewableSession()) sessions.clear()
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
