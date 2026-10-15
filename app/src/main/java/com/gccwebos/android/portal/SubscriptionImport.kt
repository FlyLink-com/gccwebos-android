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
class SubscriptionRead(val bytes: ByteArray, val validUntil: java.time.Instant? = null,
    /** Only this download's own reported usage; null means unknown. Never account data. */
    val usage: SubscriptionUsageReport? = null) {
    override fun toString() = "SubscriptionRead([REDACTED])"
}
fun interface SubscriptionSource {
    suspend fun download(url: Secret): ByteArray
    suspend fun read(url: Secret): SubscriptionRead = SubscriptionRead(download(url))
    /**
     * The same read for an import the user is watching and can cancel. Sources with no separate import policy
     * behave exactly like [read]; connect never calls this.
     */
    suspend fun readForImport(url: Secret): SubscriptionRead = read(url)
}

/** Input policy rejection is distinct from a server revoking an accepted subscription URL. */
internal class UnsupportedManualSubscriptionLink : Exception("Unsupported manual subscription link")
/** Input rejected before parsing. Extends IllegalArgumentException so existing callers keep their contract. */
internal class ManualImportInputRejected(val reason: Reason) : IllegalArgumentException("Manual import input rejected: $reason") {
    enum class Reason { EMPTY, TOO_LARGE, ENCODING }
}

/** Single user-facing message for a failed manual import. Never includes exception text (may carry URLs). */
internal fun manualImportErrorMessage(failure: Throwable): String = when (failure) {
    is ManualImportInputRejected -> when (failure.reason) {
        ManualImportInputRejected.Reason.EMPTY -> "配置内容为空，请检查文件或链接"
        ManualImportInputRejected.Reason.TOO_LARGE -> "配置文件过大（上限 ${com.gccwebos.android.vpn.SafeProxyConfig.MAX_BYTES / 1024} KB），请精简后重试"
        ManualImportInputRejected.Reason.ENCODING -> "配置文件编码无法识别，请使用 UTF-8 文本"
    }
    is UnsupportedManualSubscriptionLink -> "暂不支持此订阅链接"
    is ManualSubscriptionRedirect -> "订阅链接需要跳转，请使用直连链接"
    is ManualSubscriptionMetadataFailure -> "订阅有效期信息无效"
    is com.gccwebos.android.vpn.UnsafeProxyConfiguration -> "配置格式或协议不受支持"
    is PortalFailure -> when (failure.reason) {
        PortalFailure.Reason.STORAGE -> "本地配置暂不可用"
        PortalFailure.Reason.NETWORK -> "订阅下载失败或超过 ${com.gccwebos.android.manualimport.ImportReadBudgetSeconds} 秒未响应，请检查网络后重试"
        PortalFailure.Reason.SUBSCRIPTION_UNAVAILABLE -> "订阅不可用或未提供有效期"
        PortalFailure.Reason.SUBSCRIPTION_EXPIRED -> "订阅已过期"
        PortalFailure.Reason.SUBSCRIPTION_REVOKED -> "订阅链接不可访问"
        PortalFailure.Reason.RATE_LIMITED -> "请求过于频繁，请稍后再试"
        PortalFailure.Reason.UNAVAILABLE -> "订阅服务器暂时不可用，请稍后重试"
        PortalFailure.Reason.PROTOCOL -> "订阅服务器返回了无法识别的内容"
        else -> "配置不可用，请检查后重试"
    }
    is IllegalStateException -> "请先断开连接后再导入"
    else -> "配置不可用，请检查后重试"
}
