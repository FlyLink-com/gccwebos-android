package com.gccwebos.android

import com.gccwebos.android.core.ConnectionSelection
import com.gccwebos.android.portal.NodeLatencyObservation
import java.util.Locale

/** One local OS counter reading for this app's UID. Negative values mean unsupported. */
internal data class TrafficCounterSample(val elapsedMillis: Long, val rxBytes: Long, val txBytes: Long)

/** Display-only throughput in bytes per second; null fields are unknown. */
internal data class TunnelThroughput(val downBytesPerSecond: Long?, val upBytesPerSecond: Long?) {
    companion object { val Unknown = TunnelThroughput(null, null) }
}

/** Derives speed from cumulative counters. It never opens sockets or sends traffic.
 * Samples closer than [minIntervalMillis] are ignored so the UI updates at most every ~400 ms. */
internal class TunnelThroughputMeter(private val minIntervalMillis: Long = MinIntervalMillis) {
    private var previous: TrafficCounterSample? = null
    var current: TunnelThroughput = TunnelThroughput.Unknown
        private set

    fun reset() { previous = null; current = TunnelThroughput.Unknown }

    /** Returns the updated value, or the unchanged value when this sample was throttled. */
    fun update(sample: TrafficCounterSample): TunnelThroughput {
        val last = previous
        if (sample.rxBytes < 0 && sample.txBytes < 0) {
            previous = null; current = TunnelThroughput.Unknown
            return current
        }
        if (last == null) { previous = sample; return current }
        val elapsed = sample.elapsedMillis - last.elapsedMillis
        if (elapsed < 0) { previous = sample; current = TunnelThroughput.Unknown; return current }
        if (elapsed < minIntervalMillis) return current
        current = TunnelThroughput(rate(last.rxBytes, sample.rxBytes, elapsed), rate(last.txBytes, sample.txBytes, elapsed))
        previous = sample
        return current
    }

    private fun rate(before: Long, after: Long, elapsedMillis: Long): Long? {
        if (before < 0 || after < 0 || elapsedMillis <= 0) return null
        if (after < before) return null // Counter reset (e.g. UID stats rotated): unknown, not negative.
        return (after - before) * 1000 / elapsedMillis
    }

    companion object { const val MinIntervalMillis = 400L; const val SampleIntervalMillis = 500L }
}

/** Compact speed label such as "1.2 MB/s"; unknown is a dash. Binary units, matching usage labels. */
internal fun formatThroughput(bytesPerSecond: Long?): String {
    if (bytesPerSecond == null || bytesPerSecond < 0) return "–"
    val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
    var value = bytesPerSecond.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    return when {
        unit == 0 -> "${bytesPerSecond} B/s"
        value < 10 -> String.format(Locale.ROOT, "%.1f %s", value, units[unit])
        else -> String.format(Locale.ROOT, "%.0f %s", value, units[unit])
    }
}

internal fun formatConnectedLatency(observation: NodeLatencyObservation?): String = when (observation) {
    is NodeLatencyObservation.Success -> "${observation.milliseconds} ms"
    NodeLatencyObservation.NoReply -> "超时"
    else -> "– ms"
}

internal data class ConnectedNodePresentation(
    val selection: ConnectionSelection,
    val name: String,
    val protocol: String,
    val stateText: String,
    val downText: String,
    val upText: String,
    val latencyText: String,
    val latencyKnown: Boolean,
    val throughputDescription: String,
    val latencyDescription: String,
)

/** Only a Connected owner snapshot carrying the prepared node produces a connected card.
 * Any other phase returns null and the card keeps its existing selection display. */
internal fun connectedNodePresentation(control: ConnectionControlState, throughput: TunnelThroughput,
    latency: NodeLatencyObservation?): ConnectedNodePresentation? {
    if (control.phase != ConnectionPhase.Connected) return null
    val node = control.activeNode ?: return null
    val name = node.name.ifBlank { "当前节点" }
    val protocol = node.protocol.uppercase(Locale.ROOT)
    val down = formatThroughput(throughput.downBytesPerSecond)
    val up = formatThroughput(throughput.upBytesPerSecond)
    val latencyText = formatConnectedLatency(latency)
    val latencyKnown = latency is NodeLatencyObservation.Success
    fun spoken(text: String) = if (text == "–") "未知" else text
    return ConnectedNodePresentation(node.selection, name, protocol, "已连接", down, up, latencyText, latencyKnown,
        "下载速度 ${spoken(down)}，上传速度 ${spoken(up)}",
        when (latency) {
            is NodeLatencyObservation.Success -> "节点延迟 ${latency.milliseconds} 毫秒"
            NodeLatencyObservation.NoReply -> "节点延迟：超时"
            else -> "节点延迟：未知"
        })
}
