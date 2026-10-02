package com.gccwebos.android.design

import com.gccwebos.android.portal.Resource
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class AccountUsageState { Unknown, NoActivePlan, Reconciliation, Available }
internal enum class AccountUsagePeriod { Unknown, Upcoming, Current, Ended }

/** Account overview only; none of these values describe a local configuration or a node. */
internal data class AccountUsagePresentation(
    val state: AccountUsageState,
    val title: String = "账户当前套餐",
    val statusText: String? = null,
    val planText: String? = null,
    val remainingText: String = "暂未获取",
    val usedText: String = "暂未获取",
    val totalText: String = "暂未获取",
    val period: AccountUsagePeriod = AccountUsagePeriod.Unknown,
    val periodText: String? = null,
    val cycleText: String? = null,
    val progress: Float? = null,
    val expiresAtText: String? = null,
    val serviceExpired: Boolean = false,
    val collectionText: String? = null,
    val updatedAtText: String? = null,
    val refreshText: String? = null,
)

/** Pure mapping of a whole, owner-validated snapshot; no refresh or other side effects. */
internal fun accountUsagePresentation(
    resource: Resource?,
    now: Instant,
    refreshFailed: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
): AccountUsagePresentation {
    val refreshText = if (refreshFailed) "未更新" else null
    return when (resource) {
        null -> AccountUsagePresentation(AccountUsageState.Unknown, statusText = "暂未获取", refreshText = refreshText)
        Resource.None -> AccountUsagePresentation(AccountUsageState.NoActivePlan,
            statusText = "暂无生效套餐", refreshText = refreshText)
        Resource.Reconciliation -> AccountUsagePresentation(AccountUsageState.Reconciliation,
            statusText = "套餐正在核对", refreshText = refreshText)
        is Resource.Available -> {
            val usage = resource.usage
            val start = usage.cycleStartedAt
            val end = usage.cycleEndsAt
            val period = when {
                start == null || end == null -> AccountUsagePeriod.Unknown
                now < start -> AccountUsagePeriod.Upcoming
                now >= end -> AccountUsagePeriod.Ended
                else -> AccountUsagePeriod.Current
            }
            val sampled = usage.status != "pending"
            AccountUsagePresentation(
                state = AccountUsageState.Available,
                planText = resource.durationDays?.takeIf { it > 0 }?.let { "${it}天套餐" } ?: "当前套餐",
                remainingText = usage.remainingBytes?.let(::accountUsageGb) ?: "暂未获取",
                usedText = usage.usedBytes?.let(::accountUsageGb) ?: "暂未获取",
                totalText = if (usage.totalBytes > 0) accountUsageGb(usage.totalBytes) else "额度未设置",
                period = period,
                periodText = when (period) {
                    AccountUsagePeriod.Unknown -> "周期未确认"
                    AccountUsagePeriod.Upcoming -> "周期未开始"
                    AccountUsagePeriod.Current -> "本期用量"
                    AccountUsagePeriod.Ended -> "上期数据"
                },
                cycleText = if (start != null && end != null) {
                    "${accountUsageTime(start, zone)} — ${accountUsageTime(end, zone)}"
                } else null,
                // The number is usage in this confirmed period, never a timer or connection signal.
                progress = if (period == AccountUsagePeriod.Current && sampled && usage.totalBytes > 0) {
                    (requireNotNull(usage.usedBytes).toDouble() / usage.totalBytes).coerceIn(0.0, 1.0).toFloat()
                } else null,
                expiresAtText = accountUsageTime(resource.validUntil, zone),
                serviceExpired = resource.validUntil <= now,
                collectionText = when (usage.status) {
                    "pending" -> "暂未获取"
                    "stale" -> "同步延迟"
                    else -> null
                },
                // This is the server's sample timestamp, independent of now or refresh success.
                updatedAtText = usage.updatedAt?.let { accountUsageTime(it, zone) },
                refreshText = refreshText,
            )
        }
    }
}

/** Matches the portal's binary-byte GB display, with at most two decimal places. */
private fun accountUsageGb(bytes: Long): String =
    BigDecimal.valueOf(bytes).divide(BigDecimal.valueOf(1_073_741_824L), 2, RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString() + " GB"

private fun accountUsageTime(time: Instant, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX", Locale.ROOT).withZone(zone).format(time)
