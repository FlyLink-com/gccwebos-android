package com.gccwebos.android.portal

/** No credentials, endpoint addresses, raw YAML, or proxy display names in UI state. */
enum class ImportStatus {
    NO_RESOURCE, RECONCILIATION, EXPIRED, QUOTA_EXHAUSTED, WAITING_DELIVERY,
    UNSUPPORTED_CONFIGURATION, IMPORTED,
}
data class ImportSnapshot(val resource: Resource, val status: ImportStatus)

/** Separate credential-free transport boundary. Caller owns and wipes returned bytes. */
fun interface SubscriptionSource { suspend fun download(url: Secret): ByteArray }
