package com.gccwebos.android.vpn

import com.gccwebos.android.ConnectionPhase
import com.gccwebos.android.core.ConnectionState
import com.gccwebos.android.core.Failure
import com.gccwebos.android.core.Step
import org.junit.Assert.*
import org.junit.Test

class ConnectionControlsTest {
    @Test fun configurationAndNativeReadinessCannotPublishConnected() {
        val control = connectionControls(ConnectionState.Working(Step.TRAFFIC), 4)
        assertEquals(ConnectionPhase.Connecting, control.phase)
        assertFalse(control.canConnect)
        assertTrue(control.canCancel)
        assertFalse(control.canDisconnect)
    }
    @Test fun unresolvedCleanupCannotAdmitAnotherConnect() {
        val failed = connectionControls(ConnectionState.Failed(Failure.CLEANUP_FAILED), 9)
        assertEquals(ConnectionPhase.Failed, failed.phase)
        assertFalse(failed.canConnect)
        assertFalse(failed.canCancel)
        assertEquals(9L, failed.revision)
    }
    @Test fun onlyVerifiedConnectedOffersDisconnect() {
        val connected = connectionControls(ConnectionState.Connected("synthetic"), 10)
        assertEquals(ConnectionPhase.Connected, connected.phase)
        assertTrue(connected.canDisconnect)
        assertFalse(connected.canConnect)
    }
    @Test fun activeNodeLabelIsPublishedOnlyWithConnected() {
        val label = com.gccwebos.android.core.PreparedNodeLabel(com.gccwebos.android.core.ConnectionSelection(),
            "🇺🇸 美国 01", "hysteria2")
        assertSame(label, connectionControls(ConnectionState.Connected("synthetic"), 11, label).activeNode)
        assertNull(connectionControls(ConnectionState.Working(Step.TUNNEL), 12, label).activeNode)
        assertNull(connectionControls(ConnectionState.Disconnecting, 13, label).activeNode)
        assertNull(connectionControls(ConnectionState.Disconnected, 14, label).activeNode)
        assertNull(connectionControls(ConnectionState.Failed(Failure.ENGINE_DIED), 15, label).activeNode)
        assertFalse(connectionControls(ConnectionState.Connected("synthetic"), 16, label).toString().contains("美国"))
    }
}
