package com.gccwebos.android.portal

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Pure wire fixtures: no account, transport, collector or local configuration. */
class PortalJsonUsageCycleTest {
    private val start = Instant.parse("2026-10-01T00:00:00.000Z")
    private val end = Instant.parse("2026-10-31T00:00:00.000Z")
    private val sample = Instant.parse("2026-10-02T00:00:00.000Z")
    private fun body(change: (JSONObject) -> Unit = {}): String {
        val usage = JSONObject().put("totalBytes", 100).put("usedBytes", 25).put("remainingBytes", 75)
            .put("collectionStatus", "measured").put("updatedAt", "2026-10-02T00:00:00.000Z")
            .put("cycleStartedAt", "2026-10-01T00:00:00.000Z").put("cycleEndsAt", "2026-10-31T00:00:00.000Z")
        change(usage)
        return JSONObject().put("resource", JSONObject().put("state", "available")
            .put("resourceRef", "resource_" + "a".repeat(64))
            .put("plan", JSONObject().put("id", "synthetic").put("durationDays", 30).put("monthlyTrafficGb", 0.25))
            .put("validUntil", "2027-01-01T00:00:00.000Z").put("setupAvailable", false).put("usage", usage)).toString()
    }
    private fun parse(change: (JSONObject) -> Unit = {}) = PortalJson.resource(body(change)) as Resource.Available

    @Test fun wireRetainsActualCycleSeparatelyFromPlanAndExpiry() {
        val resource = parse()
        assertEquals(start, resource.usage.cycleStartedAt)
        assertEquals(end, resource.usage.cycleEndsAt)
        assertEquals(sample, resource.usage.updatedAt)
        assertEquals(Instant.parse("2027-01-01T00:00:00.000Z"), resource.validUntil)
        assertEquals(30L, resource.durationDays)
        assertEquals(0.25, resource.monthlyTrafficGb!!, 0.0)
    }

    @Test fun pendingRetainsAllThreeUnknownsAndActualPeriod() {
        val usage = parse { it.put("collectionStatus", "pending").put("usedBytes", JSONObject.NULL)
            .put("remainingBytes", JSONObject.NULL).put("updatedAt", JSONObject.NULL) }.usage
        assertNull(usage.usedBytes); assertNull(usage.remainingBytes); assertNull(usage.updatedAt)
        assertEquals(start, usage.cycleStartedAt); assertEquals(end, usage.cycleEndsAt)
    }

    @Test fun measuredZeroMeasuredAndStaleAllAllowVerifiedZero() {
        for (status in listOf("measured_zero", "measured", "stale")) {
            val usage = parse { it.put("collectionStatus", status).put("usedBytes", 0).put("remainingBytes", 100) }.usage
            assertEquals(status, usage.status); assertEquals(0L, usage.usedBytes)
            assertEquals(100L, usage.remainingBytes); assertEquals(sample, usage.updatedAt)
        }
    }

    @Test fun usedAboveQuotaKeepsActualUsedAndZeroRemaining() {
        val usage = parse { it.put("usedBytes", 125).put("remainingBytes", 0) }.usage
        assertEquals(125L, usage.usedBytes); assertEquals(0L, usage.remainingBytes)
    }

    @Test fun legacyZeroQuotaKeepsMeasuredUsageWithoutClaimingUnlimited() {
        val usage = parse { it.put("totalBytes", 0).put("remainingBytes", JSONObject.NULL) }.usage
        assertEquals(0L, usage.totalBytes); assertEquals(25L, usage.usedBytes); assertNull(usage.remainingBytes)
        assertEquals(start, usage.cycleStartedAt)
    }

    @Test fun oldInMemoryConstructorDoesNotInventAPeriodAndRejectsHalfPeriod() {
        val usage = Usage(100, null, null, "pending", null)
        assertNull(usage.cycleStartedAt); assertNull(usage.cycleEndsAt)
        assertThrows(IllegalArgumentException::class.java) { Usage(100, null, null, "pending", null, start, null) }
        assertThrows(IllegalArgumentException::class.java) { Usage(100, null, null, "pending", null, null, end) }
        assertThrows(IllegalArgumentException::class.java) { Usage(100, null, null, "pending", null, end, start) }
    }

    @Test fun strictWireRejectsMissingNullNonCanonicalOrNonIncreasingCycles() {
        val invalid: List<(JSONObject) -> Unit> = listOf(
            { it.remove("cycleStartedAt") }, { it.put("cycleEndsAt", JSONObject.NULL) },
            { it.put("cycleStartedAt", "2026-10-01T00:00:00Z") },
            { it.put("cycleEndsAt", "2026-10-31T00:00:00.000+00:00") },
            { it.put("cycleEndsAt", "2026-10-01T00:00:00.000Z") },
            { it.put("cycleStartedAt", "2026-11-01T00:00:00.000Z") })
        for (change in invalid) assertThrows(Exception::class.java) { parse(change) }
    }

    @Test fun existingSafeIntegerNullAndRemainingRulesRemainStrict() {
        val invalid: List<(JSONObject) -> Unit> = listOf(
            { it.put("totalBytes", 9007199254740992L) }, { it.put("usedBytes", -1) },
            { it.put("usedBytes", 1.5) }, { it.put("remainingBytes", 99) },
            { it.put("collectionStatus", "pending") }, { it.put("updatedAt", JSONObject.NULL) },
            { it.put("collectionStatus", "measured_zero") }, { it.put("extra", "synthetic") })
        for (change in invalid) assertThrows(Exception::class.java) { parse(change) }
    }

    @Test fun noResourceAndReconciliationDoNotInventAnExpiredOrNeverPurchasedState() {
        assertSame(Resource.None, PortalJson.resource("""{"resource":{"state":"none"}}"""))
        assertSame(Resource.Reconciliation, PortalJson.resource("""{"resource":{"state":"reconciliation_required"}}"""))
        assertThrows(Exception::class.java) { PortalJson.resource("""{"resource":{"state":"expired"}}""") }
        assertThrows(Exception::class.java) { PortalJson.resource("""{"resource":{"state":"none","neverPurchased":true}}""") }
    }
}
