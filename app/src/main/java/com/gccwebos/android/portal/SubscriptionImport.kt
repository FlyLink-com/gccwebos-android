package com.gccwebos.android.portal

/** No credentials, endpoint addresses or raw YAML in UI state. Display names are untrusted metadata. */
enum class ImportStatus {
    NO_RESOURCE, RECONCILIATION, EXPIRED, QUOTA_EXHAUSTED, WAITING_DELIVERY,
    UNSUPPORTED_CONFIGURATION, IMPORTED,
}
data class ImportSnapshot(val resource: Resource, val status: ImportStatus,
    val nodes: List<com.gccwebos.android.vpn.SafeProxyConfig.NodeSummary> = emptyList(),
    val rejection: com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason? = null)

/** Separate credential-free transport boundary. Caller owns and wipes returned bytes. */
class SubscriptionRead(val bytes: ByteArray, val validUntil: java.time.Instant? = null) {
    override fun toString() = "SubscriptionRead([REDACTED])"
}
fun interface SubscriptionSource {
    suspend fun download(url: Secret): ByteArray
    suspend fun read(url: Secret): SubscriptionRead = SubscriptionRead(download(url))
}

/** Input policy rejection is distinct from a server revoking an accepted subscription URL. */
internal class UnsupportedManualSubscriptionLink : Exception("Unsupported manual subscription link")
