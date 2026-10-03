package com.gccwebos.android.design

import com.gccwebos.android.portal.Resource
import com.gccwebos.android.portal.Usage
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AccountUsageClockTest {
    private val now = Instant.parse("2026-10-02T00:00:00Z")
    private fun resource(start: Instant? = now.minusSeconds(10), end: Instant? = now.plusSeconds(30),
                         expiry: Instant = now.plusSeconds(60)) = Resource.Available(
        reference = "resource_" + "a".repeat(64), plan = "fixture", durationDays = 30,
        monthlyTrafficGb = 1.0, validUntil = expiry, setupAvailable = false,
        usage = Usage(1024, 0, 1024, "measured", now, start, end))

    @Test fun currentPeriodSchedulesItsEndBeforeServiceExpiry() {
        assertEquals(now.plusSeconds(30), nextAccountUsageBoundary(resource(), now))
    }
    @Test fun upcomingPeriodSchedulesStartAndExactStartSchedulesEnd() {
        val value = resource(now.plusSeconds(5), now.plusSeconds(30))
        assertEquals(now.plusSeconds(5), nextAccountUsageBoundary(value, now))
        assertEquals(now.plusSeconds(30), nextAccountUsageBoundary(value, now.plusSeconds(5)))
    }
    @Test fun exactEndHasNoCurrentProgressAndSchedulesOnlyFutureExpiry() {
        val value = resource()
        val boundary = now.plusSeconds(30)
        assertNull(accountUsagePresentation(value, boundary).progress)
        assertEquals(now.plusSeconds(60), nextAccountUsageBoundary(value, boundary))
    }
    @Test fun serviceExpiryWinsAndExactExpiryIsNotScheduledAgain() {
        val value = resource(expiry = now.plusSeconds(3))
        assertEquals(now.plusSeconds(3), nextAccountUsageBoundary(value, now))
        assertTrue(accountUsagePresentation(value, now.plusSeconds(3)).serviceExpired)
        assertEquals(now.plusSeconds(30), nextAccountUsageBoundary(value, now.plusSeconds(3)))
    }
    @Test fun missingPeriodDoesNotInventOneAndPastBoundariesStopTheClock() {
        assertEquals(now.plusSeconds(60), nextAccountUsageBoundary(resource(null, null), now))
        assertNull(nextAccountUsageBoundary(resource(expiry = now.minusSeconds(1)), now.plusSeconds(30)))
    }
    @Test fun unknownNoneAndReconciliationNeedNoTimer() {
        listOf(null, Resource.None, Resource.Reconciliation).forEach { assertNull(nextAccountUsageBoundary(it, now)) }
    }
}
