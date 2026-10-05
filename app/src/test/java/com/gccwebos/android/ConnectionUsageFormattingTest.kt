package com.gccwebos.android

import com.gccwebos.android.design.AccountUsagePresentation
import com.gccwebos.android.design.AccountUsageState
import com.gccwebos.android.design.accountUsagePresentation
import com.gccwebos.android.portal.Resource
import com.gccwebos.android.portal.Usage
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionUsageFormattingTest {
    @Test fun onlyAnEstablishedOrDisconnectingTunnelUsesTheConnectedActionColor() {
        ConnectionPhase.values().forEach { phase ->
            assertEquals(phase.name, phase in setOf(ConnectionPhase.Connected, ConnectionPhase.Disconnecting),
                connectionActionIsConnected(phase))
        }
    }

    @Test fun validThirtyDayBadgeStopsBeingActiveAtItsActualExpiry() {
        val expiry = Instant.parse("2026-11-03T00:00:00Z")
        val resource = Resource.Available("synthetic-resource", "synthetic-plan", expiry, false,
            Usage(100, null, null, "pending", null, null, null), 30, 1.0)
        val current = accountUsagePresentation(resource, expiry.minusSeconds(1))
        assertEquals("30天套餐", current.planText)
        assertTrue(connectionPlanIsActive(current))
        assertFalse(connectionPlanIsActive(accountUsagePresentation(resource, expiry)))
        assertFalse(connectionPlanIsActive(accountUsagePresentation(resource, expiry.plusSeconds(1))))
    }

    @Test fun unknownAbsentAndReconciledEntitlementsCannotUseAnActiveBadge() {
        listOf(AccountUsageState.Unknown, AccountUsageState.NoActivePlan, AccountUsageState.Reconciliation).forEach {
            assertFalse(it.name, connectionPlanIsActive(AccountUsagePresentation(it, planText = "30天套餐")))
        }
    }

    @Test fun dateSummaryKeepsTheAccountTimezoneCalendarDay() {
        assertEquals("2026.10.05", connectionDateSummary("2026-10-05 00:30:00 +08:00"))
        assertEquals("2026.10.04", connectionDateSummary("2026-10-04 23:30:00 -07:00"))
        assertEquals("2026.10.05", connectionDateSummary("2026-10-05 00:30:00 Z"))
    }

    @Test fun fullCycleIsShortenedOnlyWhenBothBoundariesAreValid() {
        assertEquals("2026.09.05 — 2026.10.05", connectionCycleSummary(
            "2026-09-05 00:00:00 +08:00 — 2026-10-05 00:00:00 +08:00"))
        listOf("周期未确认", "2026-09-05 00:00:00 Z — 暂未获取",
            "2026-09-05 00:00:00 Z — 2026-10-05 00:00:00 Z — extra").forEach {
            assertEquals(it, connectionCycleSummary(it))
        }
    }

    @Test fun unknownMalformedAndImpossibleDatesAreNotInventedOrNormalized() {
        listOf("暂未获取", "", "2026-02-30 00:00:00 Z", "2026-10-05 25:00:00 Z",
            "2026-10-05 00:00:00", "2026-10-05", "2026-10-05 00:00:00 Z extra").forEach {
            assertEquals(it, connectionDateSummary(it))
        }
    }
}
