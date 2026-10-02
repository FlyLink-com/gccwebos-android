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
}
