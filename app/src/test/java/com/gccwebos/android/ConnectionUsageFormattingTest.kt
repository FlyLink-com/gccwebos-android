package com.gccwebos.android

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionUsageFormattingTest {
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
