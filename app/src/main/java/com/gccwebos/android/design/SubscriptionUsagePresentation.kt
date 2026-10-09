package com.gccwebos.android.design

import com.gccwebos.android.portal.SubscriptionUsage
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Usage reported by the selected configuration's own subscription response. It is labelled as such and is
 * never presented as account/plan data, a Portal sample, or a connection signal.
 */
internal data class SubscriptionUsagePresentation(
    val remainingText: String,
    val usedText: String,
    val totalText: String,
    val progress: Float?,
    val expiresAtText: String?,
    val serviceExpired: Boolean,
    val sourceText: String,
    /** Display-only expiry instant for the card's expiring-soon emphasis. */
    val expiresAt: Instant? = null,
) {
    /** Reuses the connection card's existing amount/date layout; it does not become account usage. */
    internal fun asCardPresentation() = AccountUsagePresentation(
        state = AccountUsageState.Available,
        title = "订阅提供",
        remainingText = remainingText,
        usedText = usedText,
        totalText = totalText,
        progress = progress,
        expiresAtText = expiresAtText,
        serviceExpired = serviceExpired,
        expiresAt = expiresAt,
    )
}

/** Pure mapping; null usage stays unknown, which the card shows as "暂未获取". */
internal fun subscriptionUsagePresentation(
    usage: SubscriptionUsage?,
    validUntil: Instant?,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): SubscriptionUsagePresentation? {
    if (usage == null) return null
    return SubscriptionUsagePresentation(
        remainingText = subscriptionUsageGb(usage.remainingBytes),
        usedText = subscriptionUsageGb(usage.usedBytes),
        totalText = subscriptionUsageGb(usage.totalBytes),
        progress = (usage.usedBytes.toDouble() / usage.totalBytes).coerceIn(0.0, 1.0).toFloat(),
        expiresAtText = validUntil?.let { subscriptionUsageTime(it, zone) },
        serviceExpired = validUntil?.let { it <= now } == true,
        sourceText = "订阅提供 · 更新于 " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
            .withZone(zone).format(usage.observedAt),
        expiresAt = validUntil,
    )
}

/** Same binary-byte GB display as the account card, with at most two decimal places. */
private fun subscriptionUsageGb(bytes: Long): String =
    BigDecimal.valueOf(bytes).divide(BigDecimal.valueOf(1_073_741_824L), 2, RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString() + " GB"

private fun subscriptionUsageTime(time: Instant, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX", Locale.ROOT).withZone(zone).format(time)
