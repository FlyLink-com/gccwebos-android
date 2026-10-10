package com.gccwebos.android.portal

import java.net.URI
import java.time.Instant

/** Secrets deliberately have no data-class/generated string representation. */
class Secret(val value: String) { override fun toString() = "[REDACTED]" }
class Session(val token: Secret, val accountId: String, val expiresAt: Instant,
              val lifetimeMillis: Long, val receivedElapsed: Long) {
    fun valid(now: Instant, elapsed: Long) = now < expiresAt &&
        elapsed >= receivedElapsed && elapsed - receivedElapsed < lifetimeMillis
    override fun toString() = "Session([REDACTED])"
}
data class HumanChallenge(val reference: String, val prompt: String, val expiresElapsed: Long = Long.MAX_VALUE)
sealed interface LoginResult {
    class Authenticated(val session: Session) : LoginResult
    class TotpRequired(val reference: String) : LoginResult
}
sealed interface Resource {
    data object None : Resource
    data object Reconciliation : Resource
    data class Available(val reference: String, val plan: String, val validUntil: Instant,
                         val setupAvailable: Boolean, val usage: Usage,
                         val durationDays: Long? = null, val monthlyTrafficGb: Double? = null) : Resource
}
data class Usage(val totalBytes: Long, val usedBytes: Long?, val remainingBytes: Long?,
                 val status: String, val updatedAt: Instant?,
                 // Older in-memory fixtures have no period. Wire reads must supply both.
                 val cycleStartedAt: Instant? = null, val cycleEndsAt: Instant? = null) {
    init {
        require((cycleStartedAt == null) == (cycleEndsAt == null))
        if (cycleStartedAt != null) require(cycleStartedAt < requireNotNull(cycleEndsAt))
        require(totalBytes in 0..MAX_SAFE && (usedBytes == null || usedBytes in 0..MAX_SAFE))
        require(status in setOf("pending", "measured_zero", "measured", "stale"))
        if (status == "pending") require(usedBytes == null && remainingBytes == null && updatedAt == null)
        else {
            require(usedBytes != null && updatedAt != null)
            require(remainingBytes == if (totalBytes == 0L) null else (totalBytes - usedBytes).coerceAtLeast(0))
            if (status == "measured_zero") require(usedBytes == 0L)
        }
    }
    companion object { const val MAX_SAFE = 9007199254740991L }
}
class DeliveryRecord(val accountId: String, val sessionBinding: String, val resource: String,
                     val operationKey: String, val url: Secret? = null,
                     val handoff: Secret? = null, val pendingAck: Boolean = false) {
    override fun toString() = "DeliveryRecord([REDACTED])"
}
class PortalFailure(val reason: Reason, val recoveryCause: RecoveryCause? = null) : Exception(reason.name) {
    /** No account, resource, session or delivery material is included in this UI-safe cause. */
    enum class RecoveryCause { RESOURCE_CHANGED, SESSION_PENDING, MODEL_CHANGED, JOURNAL_CONFLICT }
    enum class Reason { NETWORK, PROTOCOL, LOGIN_FAILED, SESSION_EXPIRED, RECONCILIATION,
        UNAVAILABLE, RATE_LIMITED, STORAGE, RECOVERY_REQUIRED, SUBSCRIPTION_UNAVAILABLE, SUBSCRIPTION_REVOKED, SUBSCRIPTION_EXPIRED }
}

/** Independent API/subscription boundaries; rejects query strings and redirects. */
class OriginPolicy(apiOrigin: String, subscriptionOrigins: Set<String>) {
    val api: URI = origin(apiOrigin)
    private val subscriptions = subscriptionOrigins.map(::origin).toSet()
    fun apiUrl(path: String): String {
        require(path.startsWith("/api/portal/") && !path.contains('#'))
        if ('?' in path) {
            // Only audited order-history and wallet-ledger cursor contracts may add query parameters.
            val ledger = path.substringBefore('?') == "/api/portal/coins/wallet/ledger"
            require(ledger || path.substringBefore('?') == "/api/portal/commerce/orders")
            val timeKey = if (ledger) "afterOccurredAt" else "afterCreatedAt"
            val refKey = if (ledger) "afterEntryRef" else "afterOrderRef"
            val parts = path.substringAfter('?').split('&').map { it.split('=', limit = 2) }
            require(parts.all { it.size == 2 } && parts.map { it[0] }.toSet().size == parts.size)
            val query = parts.associate { it[0] to java.net.URLDecoder.decode(it[1], "UTF-8") }
            require(query.keys == setOf("limit") || query.keys == setOf("limit", timeKey, refKey))
            require(query["limit"] == "20")
            query[timeKey]?.let { require(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z").matches(it)); Instant.parse(it) }
            query[refKey]?.let { require(Regex(if (ledger) "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}" else "[a-z][a-z0-9_]{2,95}").matches(it)) }
        }
        return api.resolve(path).toString()
    }
    fun subscription(url: String): String {
        require(url.none { it.isISOControl() })
        val uri = URI(url)
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(origin("https://${uri.rawAuthority}") in subscriptions)
        // The backend issues .yaml artifact paths; retain legacy bare capabilities unchanged.
        require(Regex("/sub/a/[A-Za-z0-9_-]+(?:\\.yaml)?").matches(uri.rawPath))
        return uri.toASCIIString()
    }
    private fun origin(value: String): URI {
        val uri = URI(value)
        require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null)
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
        require(uri.rawQuery == null && uri.rawFragment == null && uri.port in listOf(-1, 443))
        return URI("https", null, uri.host.lowercase(), -1, null, null, null)
    }
}

internal fun sessionBinding(token: Secret): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(token.value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** One-time TOTP enrollment from the server; the key is shown to its owner and never logged. */
class TotpEnrollment(val enrollmentRef: String, val issuer: String, val manualKey: String, val otpauthUri: String) {
    override fun toString() = "TotpEnrollment(ref=$enrollmentRef)"
}

data class AccountSecurity(val authenticatorBound: Boolean, val initialAccount: Boolean, val passkeyEnabled: Boolean, val passkeyExported: Boolean, val setupRequired: Boolean)
