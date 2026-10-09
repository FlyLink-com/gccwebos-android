package com.gccwebos.android

import com.gccwebos.android.core.ConnectionSelection
import com.gccwebos.android.core.PreparedNodeLabel
import com.gccwebos.android.portal.NodeLatencyObservation
import org.junit.Assert.*
import org.junit.Test

class ConnectedNodePresentationTest {
    private val label = PreparedNodeLabel(ConnectionSelection(), "🇺🇸 美国 01", "hysteria2")
    private val connected = ConnectionControlState(ConnectionPhase.Connected, canDisconnect = true, activeNode = label)

    @Test fun throughputFormatting() {
        assertEquals("–", formatThroughput(null))
        assertEquals("–", formatThroughput(-1))
        assertEquals("0 B/s", formatThroughput(0))
        assertEquals("512 B/s", formatThroughput(512))
        assertEquals("1.0 KB/s", formatThroughput(1024))
        assertEquals("1.2 MB/s", formatThroughput(1_258_291))
        assertEquals("35 KB/s", formatThroughput(35 * 1024L))
        assertEquals("120 MB/s", formatThroughput(120L * 1024 * 1024))
        assertEquals("2.0 GB/s", formatThroughput(2L * 1024 * 1024 * 1024))
    }

    @Test fun meterNeedsTwoSamplesAndThrottles() {
        val meter = TunnelThroughputMeter()
        assertEquals(TunnelThroughput.Unknown, meter.update(TrafficCounterSample(1_000, 0, 0)))
        // 200 ms later: throttled, still unknown.
        assertEquals(TunnelThroughput.Unknown, meter.update(TrafficCounterSample(1_200, 999, 999)))
        val first = meter.update(TrafficCounterSample(1_500, 512_000, 51_200))
        assertEquals(1_024_000L, first.downBytesPerSecond)
        assertEquals(102_400L, first.upBytesPerSecond)
        val second = meter.update(TrafficCounterSample(2_000, 512_000, 51_200))
        assertEquals(0L, second.downBytesPerSecond)
        assertEquals(0L, second.upBytesPerSecond)
    }

    @Test fun meterNeverReportsNegativeOrUnsupportedRates() {
        val meter = TunnelThroughputMeter()
        meter.update(TrafficCounterSample(0, 10_000, 10_000))
        val reset = meter.update(TrafficCounterSample(500, 100, 20_000))
        assertNull(reset.downBytesPerSecond)
        assertEquals(20_000L, reset.upBytesPerSecond)
        assertEquals(TunnelThroughput.Unknown, meter.update(TrafficCounterSample(1_000, -1, -1)))
        val clockBack = TunnelThroughputMeter().apply { update(TrafficCounterSample(5_000, 0, 0)) }
        assertEquals(TunnelThroughput.Unknown, clockBack.update(TrafficCounterSample(4_000, 10, 10)))
    }

    @Test fun connectedNodeShowsActiveNodeStateSpeedAndLatency() {
        val p = requireNotNull(connectedNodePresentation(connected, TunnelThroughput(1_258_291, 35 * 1024L),
            NodeLatencyObservation.Success(85, 1)))
        assertSame(label.selection, p.selection)
        assertEquals("🇺🇸 美国 01", p.name)
        assertEquals("HYSTERIA2", p.protocol)
        assertEquals("已连接", p.stateText)
        assertEquals("1.2 MB/s", p.downText)
        assertEquals("35 KB/s", p.upText)
        assertEquals("85 ms", p.latencyText)
        assertTrue(p.latencyKnown)
        assertEquals("节点延迟 85 毫秒", p.latencyDescription)
    }

    @Test fun unknownLatencyAndSpeedShowDash() {
        val p = requireNotNull(connectedNodePresentation(connected, TunnelThroughput.Unknown, null))
        assertEquals("–", p.downText); assertEquals("–", p.upText)
        assertEquals("– ms", p.latencyText); assertFalse(p.latencyKnown)
        assertTrue(p.trafficIdle)
        assertEquals("等待流量", p.throughputDescription)
        assertEquals("– ms", connectedNodePresentation(connected, TunnelThroughput.Unknown,
            NodeLatencyObservation.Unavailable)!!.latencyText)
        assertEquals("超时", connectedNodePresentation(connected, TunnelThroughput.Unknown,
            NodeLatencyObservation.NoReply)!!.latencyText)
    }

    @Test fun idleTunnelReadsWaitingForTrafficNotZeroSpeeds() {
        assertTrue(connectedNodePresentation(connected, TunnelThroughput(0, 0), null)!!.trafficIdle)
        assertTrue(connectedNodePresentation(connected, TunnelThroughput(null, 0), null)!!.trafficIdle)
        val flowing = connectedNodePresentation(connected, TunnelThroughput(0, 2048), null)!!
        assertFalse(flowing.trafficIdle)
        assertEquals("下载速度 0 B/s，上传速度 2.0 KB/s", flowing.throughputDescription)
    }

    @Test fun notConnectedOrNoLabelKeepsExistingCard() {
        for (phase in ConnectionPhase.entries.filter { it != ConnectionPhase.Connected })
            assertNull(connectedNodePresentation(connected.copy(phase = phase), TunnelThroughput.Unknown, null))
        assertNull(connectedNodePresentation(connected.copy(activeNode = null), TunnelThroughput.Unknown, null))
    }
}

/** C1 hint gate (display only). */
class ConnectionNeedsNodeChoiceTest {
    private fun needs(action: ConnectionPowerAction = ConnectionPowerAction.Connect, pending: Boolean = false,
        hasNode: Boolean = false, busy: Boolean = false, sync: Boolean = true,
        status: com.gccwebos.android.portal.LocalSubscriptionStatus = com.gccwebos.android.portal.LocalSubscriptionStatus.READY,
        choices: Boolean = true) = connectionNeedsNodeChoice(action, pending, hasNode, busy, sync, status, choices)

    @Test fun readyListWithoutASelectionShowsTheAutoSelectHint() = assertTrue(needs())
    @Test fun aSelectedNodeShowsNoHint() = assertFalse(needs(hasNode = true))
    @Test fun otherActionsAndPendingCommandsAreUntouched() {
        for (action in ConnectionPowerAction.entries.filter { it != ConnectionPowerAction.Connect }) assertFalse(needs(action = action))
        assertFalse(needs(pending = true))
    }
    @Test fun unloadedBusyOrEmptyListsKeepTheExistingOneClickPath() {
        assertFalse(needs(busy = true)); assertFalse(needs(sync = false)); assertFalse(needs(choices = false))
        assertFalse(needs(status = com.gccwebos.android.portal.LocalSubscriptionStatus.MISSING))
        assertFalse(needs(status = com.gccwebos.android.portal.LocalSubscriptionStatus.EXPIRED))
    }
}
