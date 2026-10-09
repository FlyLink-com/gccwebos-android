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

/** Latency tone thresholds (ms). Calibrated so typical 100–200 ms nodes no longer all read as one colour. */
internal const val LatencyGoodBelowMs = 80L
internal const val LatencyFairUpToMs = 150L

/**
 * Maps an observation (and whether a batch is currently running for an empty cell) to UI copy.
 * Thresholds: &lt;80 ms good (green), 80–150 ms fair (amber), &gt;150 ms or timeout poor (red).
 * Untested is "---". While the tunnel is up the node probes cannot run, so a failed read is shown
 * as a dash (skipped), not as a broken measurement. [measuring] drives a spinner.
 */
internal fun nodeLatencyPresentation(
    observation: NodeLatencyObservation?,
    measuring: Boolean = false,
    tunnelActive: Boolean = false,
): NodeLatencyPresentation = when (observation) {
    is NodeLatencyObservation.Success -> {
        val ms = observation.milliseconds
        when {
            ms < LatencyGoodBelowMs -> NodeLatencyPresentation("$ms ms", NodeLatencyTone.Good)
            ms <= LatencyFairUpToMs -> NodeLatencyPresentation("$ms ms", NodeLatencyTone.Fair)
            else -> NodeLatencyPresentation("$ms ms", NodeLatencyTone.Poor)
        }
    }
    NodeLatencyObservation.NoReply -> NodeLatencyPresentation("超时", NodeLatencyTone.Poor)
    NodeLatencyObservation.Unavailable ->
        if (measuring) NodeLatencyPresentation("", NodeLatencyTone.Neutral, measuring = true)
        else if (tunnelActive) NodeLatencyPresentation(LatencySkippedLabel, NodeLatencyTone.Neutral)
        else NodeLatencyPresentation("不可测", NodeLatencyTone.Neutral)
    NodeLatencyObservation.NetworkChanged -> NodeLatencyPresentation("网络变化", NodeLatencyTone.Neutral)
    null ->
        if (measuring) NodeLatencyPresentation("", NodeLatencyTone.Neutral, measuring = true)
        else NodeLatencyPresentation("---", NodeLatencyTone.Neutral)
}

/** A quiet dash per cell; the toolbar note ([LatencySkippedNote]) and the spoken description carry the reason. */
internal const val LatencySkippedLabel = "—"
/** Shown in the toolbar instead of a "last tested" time while the tunnel is up and nothing could be measured. */
internal const val LatencySkippedNote = "已连接时无法测速，断开后可测"

/** Compact spoken description for accessibility; never invents a millisecond value. */
internal fun nodeLatencyContentDescription(presentation: NodeLatencyPresentation): String = when {
    presentation.measuring -> "节点延迟：测试中"
    presentation.label == "---" -> "节点延迟：未测试"
    presentation.label == "超时" -> "节点延迟：超时"
    presentation.label == LatencySkippedLabel -> "节点延迟：已连接时无法测速"
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

private const val OfficialSiteHost = "gccwebos.com"

/**
 * True only when [value] is a bare host or http(s) URL whose parsed host is exactly gccwebos.com or one
 * of its subdomains. Compared on the parsed host, never by substring; anything unparsable is not official.
 */
internal fun isOfficialSiteValue(value: String): Boolean {
    val text = value.trim()
    if (text.isEmpty() || text.any { it.isWhitespace() || it.isISOControl() }) return false
    val schemeEnd = text.indexOf("://")
    val rest = if (schemeEnd >= 0) {
        val scheme = text.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return false
        text.substring(schemeEnd + 3)
    } else text
    val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
    // Userinfo ("gccwebos.com@evil.tld") makes the real host ambiguous to a reader: not official.
    if (authority.contains('@') || authority.isEmpty()) return false
    val hostPort = authority
    var host = hostPort
    val colon = hostPort.lastIndexOf(':')
    if (colon >= 0) {
        val port = hostPort.substring(colon + 1)
        if (port.isEmpty() || !port.all { it in '0'..'9' }) return false
        host = hostPort.substring(0, colon)
    }
    host = host.trimEnd('.').lowercase()
    if (host.isEmpty() || host.any { it !in 'a'..'z' && it !in '0'..'9' && it != '.' && it != '-' }) return false
    return host == OfficialSiteHost || host.endsWith(".$OfficialSiteHost")
}

/**
 * Derives compact info-bar fields from provider notice display names only. Never invents values;
 * unknown notice shapes are ignored. First match wins per field. A website notice is kept only
 * when it points at gccwebos.com; any other (or unparsable) site is dropped entirely.
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
        // Only gccwebos's own site may be shown. A third-party provider's site is never promoted here.
        if (website == null) InfoWebsite.find(name)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotEmpty() && isOfficialSiteValue(it) }?.let { website = it }
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
