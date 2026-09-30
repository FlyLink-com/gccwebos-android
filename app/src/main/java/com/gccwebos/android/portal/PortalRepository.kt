package com.gccwebos.android.portal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** One delivery at a time, scoped to the exact account, session and resource. */
class PortalRepository(private val transport: PortalTransport, private val store: DeliveryStore,
    private val policy: OriginPolicy, private val elapsed: () -> Long,
    private val now: () -> Instant = Instant::now) {
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private var session: Session? = null
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
        val value = PortalJson.session(JSONObject(body), elapsed())
        require(value.valid(now(), elapsed()))
        val prior = store.read()
        if (prior != null && prior.accountId != value.accountId) store.clear()
        totpExpiry = null
        session = value
        return LoginResult.Authenticated(value)
    }
    suspend fun refresh(): Resource = mutex.withLock {
        guarded {
            val epoch = generation.get(); val active = activeSession()
            val response = transport.request("/api/portal/resources/current/overview", session = active.token)
            expect(response, setOf(200)); checkGeneration(epoch); activeSession()
            PortalJson.resource(response.body)
        }
    }
    suspend fun acquire(resource: Resource.Available): Unit = mutex.withLock {
        guarded {
            require(resource.setupAvailable && resource.validUntil > now())
            val epoch = generation.get(); val active = activeSession()
            var record = store.read()
            if (record != null && record.accountId != active.accountId) {
                // A confirmed new login must never adopt another account's material.
                store.clear(); record = null
            }
            if (record != null && record.resource != resource.reference) { store.clear(); record = null }
            if (record != null && record.sessionBinding != sessionBinding(active.token)) {
                // Ack is session-bound. Do not silently create a second claim or lose recoverable material.
                if (record.pendingAck || record.url == null) throw PortalFailure(PortalFailure.Reason.RECOVERY_REQUIRED)
                policy.subscription(requireNotNull(record.url).value)
                return@guarded // Existing acknowledged material is retained for this same account/resource.
            }
            if (record == null) {
                record = DeliveryRecord(active.accountId, sessionBinding(active.token), resource.reference, UUID.randomUUID().toString())
                store.save(record) // Persist idempotency key BEFORE first attempt.
            }
            if (record.url == null) {
                activeSession()
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
                store.save(DeliveryRecord(record.accountId, record.sessionBinding, record.resource, record.operationKey,
                    record.url, record.handoff, false))
            }
        }
    }
    /** Local sign-out only. There is no verified native server revocation endpoint. */
    suspend fun logout() {
        generation.incrementAndGet()
        mutex.withLock { session = null; totpExpiry = null; store.clear() }
    }
    private fun activeSession(): Session {
        val value = session
        if (value == null || !value.valid(now(), elapsed())) {
            session = null; throw PortalFailure(PortalFailure.Reason.SESSION_EXPIRED)
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
                session = null; PortalFailure.Reason.SESSION_EXPIRED
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
