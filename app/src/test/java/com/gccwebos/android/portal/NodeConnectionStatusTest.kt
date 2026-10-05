package com.gccwebos.android.portal

import com.gccwebos.android.ConnectionControlState
import com.gccwebos.android.ConnectionPhase
import org.junit.Assert.*
import org.junit.Test

/** Pure view mapping; no subscription downloads or engine commands. */
class NodeConnectionStatusTest {
    @Test fun idleDoesNotInventUnavailableCaption() {
        assertNull(nodeConnectionStatus(ConnectionControlState(ConnectionPhase.Disconnected)))
        assertNull(nodeConnectionStatus(ConnectionControlState(ConnectionPhase.Disconnected, canConnect = true)))
    }

    @Test fun explicitUnavailableStateKeepsAnAccurateCaption() {
        assertEquals("连接暂不可用", nodeConnectionStatus(ConnectionControlState()))
    }

    @Test fun livePhasesUseTheActualOwnerState() {
        assertEquals("连接中", nodeConnectionStatus(ConnectionControlState(ConnectionPhase.Connecting)))
        assertEquals("已连接", nodeConnectionStatus(ConnectionControlState(ConnectionPhase.Connected)))
        assertEquals("断开中", nodeConnectionStatus(ConnectionControlState(ConnectionPhase.Disconnecting)))
    }

    @Test fun failedStateCannotClaimConnected() {
        assertEquals("未连接", nodeConnectionStatus(ConnectionControlState(ConnectionPhase.Failed, canConnect = true)))
    }
}
