package com.gccwebos.android.design

import com.gccwebos.android.portal.Resource
import java.util.Locale

internal data class ResourcePresentation(val title: String, val detail: String, val facts: List<Pair<String, String>> = emptyList())

/** Display-only mapping. Unknown and stale usage must never become measured zero. */
internal fun resourcePresentation(authenticated: Boolean, resource: Resource?): ResourcePresentation {
    if (!authenticated) return ResourcePresentation("登录后查看资源", "使用你的账号读取套餐、有效期与服务器确认的用量。")
    return when (resource) {
        null -> ResourcePresentation("等待读取资源", "尚未取得服务器响应，可在「我的」刷新或重试。")
        Resource.None -> ResourcePresentation("暂无已发放资源", "服务器尚未向当前账号发放可用资源。")
        Resource.Reconciliation -> ResourcePresentation("资源正在对账", "对账完成前暂不能领取配置，请稍后刷新。")
        is Resource.Available -> {
            val usage = resource.usage
            val usageLabel = when (usage.status) {
                "pending" -> "尚未采集"
                "stale" -> "${formatBytes(usage.usedBytes)} · 数据已过期"
                else -> formatBytes(usage.usedBytes)
            }
            val title = if (usage.totalBytes > 0 && resource.monthlyTrafficGb != null && resource.durationDays != null)
                "${formatPlanTraffic(resource.monthlyTrafficGb)} GB · ${resource.durationDays} 天" else resource.plan
            ResourcePresentation(title, "权益与用量来自服务器最近一次成功响应。", buildList {
                resource.durationDays?.let { add("套餐时长" to "$it 天") }
                if (usage.totalBytes > 0) resource.monthlyTrafficGb?.let { add("每月套餐流量" to "${formatPlanTraffic(it)} GB") }
                if (title != resource.plan) add("套餐编号" to resource.plan)
                add("有效期" to resource.validUntil.toString())
                add("已用流量" to usageLabel)
                add("剩余流量" to if (usage.status == "pending") "暂不可确定" else
                    usage.remainingBytes?.let { formatBytes(it) + if (usage.status == "stale") " · 数据已过期" else "" } ?: "暂不可确定")
                usage.updatedAt?.let { add("采集时间" to it.toString()) }
            })
        }
    }
}

internal fun formatBytes(bytes: Long?): String {
    if (bytes == null) return "尚未采集"
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB", "PiB")
    var value = bytes.toDouble()
    var unit = -1
    do { value /= 1024; unit++ } while (value >= 1024 && unit < units.lastIndex)
    return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
}

/** The plan's GB value is server-owned; do not recalculate or replace it with quota bytes. */
private fun formatPlanTraffic(value: Double): String = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
