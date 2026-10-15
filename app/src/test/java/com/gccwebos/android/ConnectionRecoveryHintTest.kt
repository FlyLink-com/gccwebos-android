package com.gccwebos.android

import com.gccwebos.android.portal.LocalSubscriptionSnapshot
import com.gccwebos.android.portal.LocalSubscriptionStatus
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ConnectionRecoveryHintTest {
    private val clock = Instant.parse("2026-10-14T00:00:00Z")
    private val ready = LocalSubscriptionSnapshot(LocalSubscriptionStatus.READY, hasProfile = true, hasCapability = true,
        validUntil = clock.plusSeconds(3600))

    @Test fun everyConnectionFailureExplainsWhatToDoNext() {
        (ConnectionFailure.values().toList() + listOf<ConnectionFailure?>(null)).forEach { failure ->
            val p = connectionOverviewPresentation(ready, clock, ConnectionControlState(ConnectionPhase.Failed, failure, canConnect = true))
            assertFalse("$failure needs a hint", p.recoveryHint.isNullOrBlank())
            assertEquals("重试连接", p.powerLabel) // the recovery action itself stays the retry button
        }
    }

    @Test fun networkLossAndTunnelCrashHaveDistinctGuidance() {
        val network = connectionRecoveryHint(ConnectionPhase.Failed, ConnectionFailure.NetworkUnavailable, LocalSubscriptionStatus.READY)!!
        val crash = connectionRecoveryHint(ConnectionPhase.Failed, ConnectionFailure.Other, LocalSubscriptionStatus.READY)!!
        assertTrue(network.contains("网络") || network.contains("Wi-Fi"))
        assertTrue(crash.contains("重试连接"))
        assertNotEquals(network, crash)
    }

    @Test fun everyLocalErrorExplainsWhatToDoNext() {
        listOf(LocalSubscriptionStatus.EXPIRED, LocalSubscriptionStatus.RETRY_REQUIRED, LocalSubscriptionStatus.INVALID_LINK,
            LocalSubscriptionStatus.UNSUPPORTED, LocalSubscriptionStatus.STORAGE_ERROR).forEach { status ->
            val p = connectionOverviewPresentation(ready.copy(status = status), clock, ConnectionControlState(ConnectionPhase.Disconnected))
            assertFalse("$status needs a hint", p.recoveryHint.isNullOrBlank())
        }
    }

    @Test fun expiredByClockGetsTheRenewHint() {
        val p = connectionOverviewPresentation(ready.copy(validUntil = clock.minusSeconds(1)), clock,
            ConnectionControlState(ConnectionPhase.Disconnected, canConnect = true))
        assertEquals(LocalSubscriptionStatus.EXPIRED, p.status)
        assertTrue(p.recoveryHint!!.contains("续费"))
    }

    @Test fun healthyStatesShowNoHint() {
        listOf(ConnectionPhase.Disconnected, ConnectionPhase.Connecting, ConnectionPhase.Connected, ConnectionPhase.Unavailable).forEach { phase ->
            assertNull(connectionOverviewPresentation(ready, clock, ConnectionControlState(phase)).recoveryHint)
        }
        assertNull(connectionOverviewPresentation(ready.copy(status = LocalSubscriptionStatus.MISSING), clock).recoveryHint)
    }
}
