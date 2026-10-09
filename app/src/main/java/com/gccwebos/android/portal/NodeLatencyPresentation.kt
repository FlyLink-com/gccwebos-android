package com.gccwebos.android.portal

/**
 * Display-only latency and info-bar helpers for the node list. None of these values authorize a
 * connection, change selection, or invent traffic / expiry / website figures.
 */

internal enum class NodeLatencyTone { Neutral, Good, Fair, Poor }

/** Label + severity for a single node latency cell. [measuring] drives a spinner in the UI. */
internal data class NodeLatencyPresentation(
    val label: String,
    val tone: NodeLatencyTone,
    val measuring: Boolean = false,
)

/**
 * Maps an observation (and whether a batch is currently running for an empty cell) to UI copy.
 * Thresholds: &lt;100 ms good, 100–200 ms fair, &gt;200 ms or timeout poor. Untested is "---".
 */
internal fun nodeLatencyPresentation(
    observation: NodeLatencyObservation?,
    measuring: Boolean = false,
): NodeLatencyPresentation = when (observation) {
    is NodeLatencyObservation.Success -> {
        val ms = observation.milliseconds
        when {
            ms < 100L -> NodeLatencyPresentation("$ms ms", NodeLatencyTone.Good)
            ms <= 200L -> NodeLatencyPresentation("$ms ms", NodeLatencyTone.Fair)
            else -> NodeLatencyPresentation("$ms ms", NodeLatencyTone.Poor)
        }
    }
    NodeLatencyObservation.NoReply -> NodeLatencyPresentation("超时", NodeLatencyTone.Poor)
    NodeLatencyObservation.Unavailable ->
        if (measuring) NodeLatencyPresentation("", NodeLatencyTone.Neutral, measuring = true)
        else NodeLatencyPresentation("不可测", NodeLatencyTone.Neutral)
    NodeLatencyObservation.NetworkChanged -> NodeLatencyPresentation("网络变化", NodeLatencyTone.Neutral)
    null ->
        if (measuring) NodeLatencyPresentation("", NodeLatencyTone.Neutral, measuring = true)
        else NodeLatencyPresentation("---", NodeLatencyTone.Neutral)
}

/** Compact spoken description for accessibility; never invents a millisecond value. */
internal fun nodeLatencyContentDescription(presentation: NodeLatencyPresentation): String = when {
    presentation.measuring -> "节点延迟：测试中"
    presentation.label == "---" -> "节点延迟：未测试"
    presentation.label == "超时" -> "节点延迟：超时"
    presentation.label.endsWith(" ms") ->
        "节点延迟 ${presentation.label.removeSuffix(" ms")} 毫秒"
    else -> "节点延迟：${presentation.label}"
}

/**
 * Relative "上次测速" caption from a monotonic elapsed-clock delta. Returns null when [agoMillis]
 * is null or negative so the toolbar can omit the line entirely.
 */
internal fun formatLastLatencyTestAgo(agoMillis: Long?): String? {
    if (agoMillis == null || agoMillis < 0L) return null
    val minutes = agoMillis / 60_000L
    val hours = agoMillis / 3_600_000L
    val days = agoMillis / 86_400_000L
    val body = when {
        agoMillis < 15_000L -> "刚刚"
        minutes < 1L -> "不到1分钟前"
        minutes < 60L -> "${minutes}分钟前"
        hours < 24L -> "${hours}小时前"
        days < 7L -> "${days}天前"
        else -> return null // Stale enough that a relative label is not useful.
    }
    return "上次测速：$body"
}

/** Parsed pieces of provider notice names (info=true rows). All fields stay null when absent. */
internal data class NodeInfoBarParts(
    val traffic: String? = null,
    val expiry: String? = null,
    val website: String? = null,
)

private val InfoTraffic = Regex(
    """(?:剩余流量|已用流量|总流量|流量)\s*[:：]\s*(.+)""",
)
private val InfoExpiry = Regex(
    """(?:套餐到期|到期时间|过期时间|到期)\s*[:：]\s*(.+)""",
)
private val InfoWebsite = Regex(
    """(?:官网|网址)\s*[:：]\s*(.+)""",
)
private val LeadingEmoji = Regex("""^[\p{So}\p{Sk}\s]+""")

/**
 * Derives compact info-bar fields from provider notice display names only. Never invents values;
 * unknown notice shapes are ignored. First match wins per field.
 */
internal fun extractNodeInfoBar(infoNames: Iterable<String>): NodeInfoBarParts {
    var traffic: String? = null
    var expiry: String? = null
    var website: String? = null
    for (raw in infoNames) {
        val name = raw.replace(LeadingEmoji, "").trim()
        if (traffic == null) InfoTraffic.find(name)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { traffic = it }
        if (expiry == null) InfoExpiry.find(name)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { expiry = it }
        if (website == null) InfoWebsite.find(name)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { website = it }
    }
    return NodeInfoBarParts(traffic, expiry, website)
}

/**
 * Single-line info bar: "流量：244.26 GB | 到期：2026-10-12". Returns null when nothing usable was
 * extracted so the bar can be omitted.
 */
internal fun formatNodeInfoBarLine(parts: NodeInfoBarParts): String? {
    val bits = buildList {
        parts.traffic?.let { add("流量：$it") }
        parts.expiry?.let { add("到期：$it") }
        parts.website?.let { add("官网：$it") }
    }
    return bits.takeIf { it.isNotEmpty() }?.joinToString(" | ")
}

/**
 * View-only sort key for latency: successful RTT ascending, then unanswered / unavailable, then
 * untested. Stable for equal keys (caller should keep original index as a secondary key).
 */
internal fun nodeLatencySortKey(observation: NodeLatencyObservation?): Long = when (observation) {
    is NodeLatencyObservation.Success -> observation.milliseconds.coerceAtLeast(0L)
    NodeLatencyObservation.NoReply -> 1_000_000_000L
    NodeLatencyObservation.Unavailable, NodeLatencyObservation.NetworkChanged -> 2_000_000_000L
    null -> Long.MAX_VALUE
}
