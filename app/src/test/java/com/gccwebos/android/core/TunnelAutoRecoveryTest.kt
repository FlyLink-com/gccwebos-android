package com.gccwebos.android.core

import org.junit.Assert.*
import org.junit.Test

class TunnelAutoRecoveryTest {
    private var clock = 0L
    private val recovery = TunnelAutoRecovery({ clock })
    private val died = ConnectionState.Failed(Failure.ENGINE_DIED)
    private fun connect() = recovery.onState(ConnectionState.Connected("line"), true)

    @Test fun engineDeathAfterConnectedSchedulesARestartWithBackoff() {
        connect()
        val first = recovery.onState(died, true) as TunnelAutoRecovery.Decision.Restart
        assertEquals(1, first.attempt); assertEquals(1_000, first.delayMillis)
        assertTrue(recovery.stillWanted(first.token))
        recovery.onState(ConnectionState.Working(Step.TUNNEL, Operation.RECONNECT), true)
        val second = recovery.onState(died, true) as TunnelAutoRecovery.Decision.Restart // died again while recovering
        assertEquals(2, second.attempt); assertEquals(4_000, second.delayMillis)
        assertFalse("older restart is superseded", recovery.stillWanted(first.token))
    }

    @Test fun givesUpAfterTheBudgetAndRequiresAManualRetry() {
        connect()
        repeat(3) { assertTrue(recovery.onState(died, true) is TunnelAutoRecovery.Decision.Restart) }
        assertEquals(TunnelAutoRecovery.Decision.GiveUp, recovery.onState(died, true))
        assertFalse(recovery.recovering)
        assertEquals("not armed until connected again", TunnelAutoRecovery.Decision.None, recovery.onState(died, true))
    }

    @Test fun aStableConnectionEarnsAFreshBudget() {
        connect(); repeat(3) { recovery.onState(died, true) }
        connect(); clock += 60_000
        val next = recovery.onState(died, true) as TunnelAutoRecovery.Decision.Restart
        assertEquals(1, next.attempt)
    }

    @Test fun neverRetriesPermissionRevocationOtherFailuresOrDeathBeforeConnecting() {
        assertEquals(TunnelAutoRecovery.Decision.None, recovery.onState(died, true)) // never connected
        connect()
        assertEquals(TunnelAutoRecovery.Decision.None, recovery.onState(ConnectionState.Failed(Failure.PERMISSION_REVOKED), true))
        connect()
        assertEquals(TunnelAutoRecovery.Decision.None, recovery.onState(ConnectionState.Failed(Failure.TUNNEL_FAILED), true))
        connect()
        assertEquals(TunnelAutoRecovery.Decision.None, recovery.onState(ConnectionState.Blocked(BusinessBlock.EXPIRED), true))
        assertEquals(TunnelAutoRecovery.Decision.None, recovery.onState(died, true))
    }

    @Test fun userCommandCancelsAPendingRestart() {
        connect()
        val restart = recovery.onState(died, true) as TunnelAutoRecovery.Decision.Restart
        recovery.onUserCommand()
        assertFalse(recovery.stillWanted(restart.token))
        assertEquals(TunnelAutoRecovery.Decision.None, recovery.onState(died, true))
    }

    @Test fun userDisconnectAfterConnectedIsNeverRetried() {
        connect()
        recovery.onState(ConnectionState.Disconnecting, true)
        recovery.onState(ConnectionState.Disconnected, true)
        assertEquals(TunnelAutoRecovery.Decision.None, recovery.onState(died, true))
    }

    @Test fun withoutNetworkTheRestartWaitsAndDoesNotConsumeTheBudget() {
        connect()
        assertEquals(TunnelAutoRecovery.Decision.WaitingForNetwork, recovery.onState(died, false))
        assertTrue(recovery.recovering)
        val restart = recovery.onNetworkAvailable() as TunnelAutoRecovery.Decision.Restart
        assertEquals(1, restart.attempt)
        assertEquals(TunnelAutoRecovery.Decision.None, recovery.onNetworkAvailable()) // flapping: no duplicate
    }
}

class AutoRecoveringPresentationTest {
    private val clock = java.time.Instant.parse("2026-10-14T00:00:00Z")
    private val ready = com.gccwebos.android.portal.LocalSubscriptionSnapshot(com.gccwebos.android.portal.LocalSubscriptionStatus.READY,
        hasProfile = true, hasCapability = true, validUntil = clock.plusSeconds(3600))

    @Test fun recoveringFailureShowsAutoReconnectInsteadOfAnError() {
        val p = com.gccwebos.android.connectionOverviewPresentation(ready, clock, com.gccwebos.android.ConnectionControlState(
            com.gccwebos.android.ConnectionPhase.Failed, com.gccwebos.android.ConnectionFailure.Other, canConnect = true, autoRecovering = true))
        assertEquals("正在自动重连…", p.title)
        assertEquals(com.gccwebos.android.ConnectionVisualTone.Connecting, p.tone)
    }

    @Test fun afterGivingUpTheNormalFailureReturns() {
        val p = com.gccwebos.android.connectionOverviewPresentation(ready, clock, com.gccwebos.android.ConnectionControlState(
            com.gccwebos.android.ConnectionPhase.Failed, com.gccwebos.android.ConnectionFailure.Other, canConnect = true))
        assertEquals("连接异常", p.title)
        assertEquals(com.gccwebos.android.ConnectionVisualTone.Error, p.tone)
    }
}
