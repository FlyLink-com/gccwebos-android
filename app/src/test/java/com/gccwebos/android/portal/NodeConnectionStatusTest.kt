package com.gccwebos.android.portal

import com.gccwebos.android.ConnectionControlState
import com.gccwebos.android.ConnectionPhase
import org.junit.Assert.*
import org.junit.Test

class NodeConnectionStatusTest {
    @Test fun idleDoesNotInventUnavailableCaption() {
        assertNull(nodeConnectionStatus(ConnectionControlState(
            phase = ConnectionPhase.Disconnected, canConnect = true)))
        assertNull(nodeConnectionStatus(ConnectionControlState(
            phase = ConnectionPhase.Disconnected, canConnect = false)))
    }

    @Test fun explicitUnavailableStateKeepsAnAccurateCaption() {
        assertEquals("连接暂不可用", nodeConnectionStatus(ConnectionControlState()))
    }

    @Test fun livePhasesUseTheActualOwnerState() {
        assertEquals("连接中", nodeConnectionStatus(ConnectionControlState(phase = ConnectionPhase.Connecting)))
        assertEquals("已连接", nodeConnectionStatus(ConnectionControlState(phase = ConnectionPhase.Connected)))
        assertEquals("断开中", nodeConnectionStatus(ConnectionControlState(phase = ConnectionPhase.Disconnecting)))
    }

    @Test fun failedStateCannotClaimConnected() {
        assertEquals("未连接", nodeConnectionStatus(ConnectionControlState(phase = ConnectionPhase.Failed)))
    }
}
