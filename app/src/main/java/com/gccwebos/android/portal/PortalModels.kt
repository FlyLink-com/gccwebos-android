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
                         val setupAvailable: Boolean, val usage: Usage) : Resource
}
data class Usage(val totalBytes: Long, val usedBytes: Long?, val remainingBytes: Long?,
                 val status: String, val updatedAt: Instant?) {
    init {
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
class PortalFailure(val reason: Reason) : Exception(reason.name) {
    enum class Reason { NETWORK, PROTOCOL, LOGIN_FAILED, SESSION_EXPIRED, RECONCILIATION,
        UNAVAILABLE, RATE_LIMITED, STORAGE, RECOVERY_REQUIRED, SUBSCRIPTION_UNAVAILABLE, SUBSCRIPTION_REVOKED }
}

/** Independent API/subscription boundaries; rejects query strings and redirects. */
class OriginPolicy(apiOrigin: String, subscriptionOrigins: Set<String>) {
    val api: URI = origin(apiOrigin)
    private val subscriptions = subscriptionOrigins.map(::origin).toSet()
    fun apiUrl(path: String): String {
        require(path.startsWith("/api/portal/") && !path.contains('?') && !path.contains('#'))
        return api.resolve(path).toString()
    }
    fun subscription(url: String): String {
        require(url.none { it.isISOControl() })
        val uri = URI(url)
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(origin("https://${uri.rawAuthority}") in subscriptions)
        require(Regex("/sub/a/[A-Za-z0-9_-]+").matches(uri.rawPath))
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
