package com.gccwebos.android.design

import com.gccwebos.android.portal.Resource
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class ResourcePresentation(
    val title: String,
    val detail: String,
    val facts: List<Pair<String, String>> = emptyList(),
    val countdown: String? = null,
)

/** Display only. Loading/errors are not authoritative "none" responses; unknown usage is not zero. */
internal fun resourcePresentation(
    authenticated: Boolean,
    resource: Resource?,
    busy: Boolean = false,
    error: String? = null,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): ResourcePresentation {
    if (!authenticated) return ResourcePresentation("登录后查看资源", "使用你的账号读取套餐、有效期与服务器确认的用量。")
    if (busy) return ResourcePresentation("正在读取资源", "正在向服务器确认当前资源，请稍候。")
    if (error != null) return ResourcePresentation("资源暂时无法确认", "本次同步未完成，可在「我的」刷新或重试。")
    return when (resource) {
        null -> ResourcePresentation("等待读取资源", "尚未取得服务器响应，可在「我的」刷新或重试。")
        Resource.None -> ResourcePresentation("暂无已发放资源", "服务器已确认当前账号暂无资源，可查看套餐或前往钱包充值。")
        Resource.Reconciliation -> ResourcePresentation("资源正在对账", "对账完成前暂不能领取配置，请稍后刷新。")
        is Resource.Available -> {
            val usage = resource.usage
            val title = when (val days = resource.durationDays) {
                365L -> "年卡365天"
                null -> "当前订阅"
                else -> if (days > 0) "${days}天" else "当前订阅"
            }
            ResourcePresentation(
                title = title,
                detail = "权益与用量来自服务器最近一次成功响应；剩余有效时间按设备当前时间计算。",
                countdown = remainingValidity(resource.validUntil, now),
                facts = buildList {
                    add("到期时间" to formatLocalTime(resource.validUntil, zone))
                    add("总流量" to if (usage.totalBytes > 0) formatBytes(usage.totalBytes) else "额度未确认")
                    add("已用流量" to when (usage.status) {
                        "pending" -> "尚未采集"
                        "stale" -> "${formatBytes(usage.usedBytes)} · 数据已过期"
                        else -> formatBytes(usage.usedBytes)
                    })
                    add("剩余流量" to when {
                        usage.status == "pending" || usage.remainingBytes == null -> "暂不可确定"
                        usage.status == "stale" -> "${formatBytes(usage.remainingBytes)} · 数据已过期"
                        else -> formatBytes(usage.remainingBytes)
                    })
                    usage.updatedAt?.let { add("采集时间" to formatLocalTime(it, zone)) }
                },
            )
        }
    }
}

/** Round a partial remaining second up, and never show a negative or already-expired countdown. */
internal fun remainingValidity(validUntil: Instant, now: Instant): String {
    if (validUntil <= now) return "已到期"
    val remaining = Duration.between(now, validUntil)
    val seconds = remaining.seconds + if (remaining.nano > 0) 1 else 0
    val days = seconds / 86_400
    val hours = seconds % 86_400 / 3_600
    val minutes = seconds % 3_600 / 60
    val tail = seconds % 60
    return "剩余 ${days}天 ${hours}小时 ${minutes}分 ${tail}秒"
}

private fun formatLocalTime(time: Instant, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX", Locale.ROOT).withZone(zone).format(time)

internal fun formatBytes(bytes: Long?): String {
    if (bytes == null) return "尚未采集"
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB", "PiB")
    var value = bytes.toDouble()
    var unit = -1
    do { value /= 1024; unit++ } while (value >= 1024 && unit < units.lastIndex)
    return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
}
