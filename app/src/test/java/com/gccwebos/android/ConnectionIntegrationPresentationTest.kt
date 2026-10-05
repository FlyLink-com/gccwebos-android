package com.gccwebos.android

import com.gccwebos.android.portal.LocalSubscriptionSnapshot
import com.gccwebos.android.portal.LocalSubscriptionStatus
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** View selection only. No service, credentials, subscription download or VPN execution. */
class ConnectionIntegrationPresentationTest {
    private val now = Instant.parse("2026-10-03T00:00:00Z")
    private val missing = LocalSubscriptionSnapshot(LocalSubscriptionStatus.MISSING)
    private val ready = LocalSubscriptionSnapshot(LocalSubscriptionStatus.READY, hasProfile = true,
        hasCapability = true, validUntil = now.plusSeconds(300))
    private fun view(snapshot: LocalSubscriptionSnapshot, control: ConnectionControlState) =
        connectionOverviewPresentation(snapshot, now, control)

    @Test fun idlePreparationCapabilityAloneCannotHideMissingConfigurationGuidance() {
        val idle = ConnectionControlState(ConnectionPhase.Disconnected, canConnect = true)
        val mapped = view(missing, idle)
        assertEquals(ConnectionPowerAction.None, mapped.action)
        assertFalse(showsConnectionCommand(mapped, idle))
        assertEquals("未连接", mapped.connectionText)
    }

    @Test fun completeCurrentLocalCapabilityRetainsTheOwnersRealConnectAction() {
        val idle = ConnectionControlState(ConnectionPhase.Disconnected, canConnect = true)
        val mapped = view(ready, idle)
        assertTrue(showsConnectionCommand(mapped, idle))
        assertEquals(ConnectionPowerAction.Connect, mapped.action)
        assertEquals("未连接", mapped.connectionText)
        val expired = view(ready.copy(validUntil = now), idle)
        assertFalse(showsConnectionCommand(expired, idle))
        assertEquals(ConnectionPowerAction.None, expired.action)
    }

    @Test fun everyLivePhaseKeepsItsOwnControlEvenWithoutAccountOrLocalReadiness() {
        val states = listOf(
            ConnectionControlState(ConnectionPhase.Connecting, canCancel = true) to ConnectionPowerAction.Cancel,
            ConnectionControlState(ConnectionPhase.Connected, canDisconnect = true) to ConnectionPowerAction.Disconnect,
            ConnectionControlState(ConnectionPhase.Disconnecting) to ConnectionPowerAction.None,
        )
        for ((control, action) in states) {
            val mapped = view(missing, control)
            assertTrue(showsConnectionCommand(mapped, control))
            assertEquals(action, mapped.action)
        }
    }

    @Test fun pendingAckRetainsDisabledControlInsteadOfPresentingAnotherIdleAction() {
        val pending = ConnectionControlState(ConnectionPhase.Disconnected, canConnect = true, commandPending = true)
        val mapped = view(missing, pending)
        assertTrue(showsConnectionCommand(mapped, pending))
        assertEquals(ConnectionPowerAction.None, mapped.action)
    }

    @Test fun failedOwnerHasTypedErrorWithoutPretendingConfigurationOrTrafficIsReady() {
        val failed = ConnectionControlState(ConnectionPhase.Failed, failure = ConnectionFailure.NetworkUnavailable,
            canConnect = true)
        val mapped = view(missing, failed)
        assertFalse(showsConnectionCommand(mapped, failed))
        assertEquals("无网络", mapped.title)
        assertEquals("未连接", mapped.connectionText)
        assertEquals(ConnectionPowerAction.None, mapped.action)
        assertEquals(ConnectionVisualTone.Error, mapped.tone)
    }
}
