package com.gccwebos.android.design

import com.gccwebos.android.portal.Resource
import com.gccwebos.android.portal.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class AccountUsagePresentationTest {
    private val start = Instant.parse("2026-10-02T00:00:00Z")
    private val end = Instant.parse("2026-10-31T23:00:00Z")
    private val now = start.plusSeconds(600)
    private val sample = start.plusSeconds(60)
    private val gib = 1_073_741_824L
    private val utc = ZoneId.of("UTC")

    private fun usage(total: Long = 50 * gib, used: Long = 3 * gib, status: String = "measured",
                      cycleStart: Instant? = start, cycleEnd: Instant? = end) = Usage(
        totalBytes = total,
        usedBytes = if (status == "pending") null else used,
        remainingBytes = if (status == "pending" || total == 0L) null else (total - used).coerceAtLeast(0),
        status = status,
        updatedAt = if (status == "pending") null else sample,
        cycleStartedAt = cycleStart,
        cycleEndsAt = cycleEnd,
    )

    private fun resource(usage: Usage = usage(), validUntil: Instant = end.plusSeconds(86_400),
                         days: Long? = 30) = Resource.Available(
        "resource_" + "a".repeat(64), "actual_backend_plan", validUntil, false, usage, days, 50.0)

    private fun display(resource: Resource? = resource(), at: Instant = now, failed: Boolean = false,
                        zone: ZoneId = utc) = accountUsagePresentation(resource, at, failed, zone)

    @Test fun pendingKeepsUnknownValuesAndSampleTimeInsteadOfManufacturingZero() {
        val result = display(resource(usage(status = "pending")))
        assertEquals("账户当前套餐", result.title)
        assertEquals("暂未获取", result.remainingText)
        assertEquals("暂未获取", result.usedText)
        assertEquals("50 GB", result.totalText)
        assertEquals("暂未获取", result.collectionText)
        assertNull(result.updatedAtText)
        assertNull(result.progress)
        assertFalse(result.serviceExpired)
    }

    @Test fun allThreeMeasuredStatusesPermitARealZero() {
        listOf("measured_zero", "measured", "stale").forEach { status ->
            val result = display(resource(usage(used = 0, status = status)))
            assertEquals(status, "0 GB", result.usedText)
            assertEquals(status, "50 GB", result.remainingText)
            assertEquals(status, 0f, requireNotNull(result.progress), 0f)
            assertEquals(status, if (status == "stale") "同步延迟" else null, result.collectionText)
            assertEquals("2026-10-02 00:01:00 Z", result.updatedAtText)
        }
    }

    @Test fun binaryGbUsesAtMostTwoDecimalsWithoutRoundingTheStoredUsageOrProgress() {
        val used = 1_234_567_890L
        val result = display(resource(usage(total = 10 * gib, used = used)))
        assertEquals("1.15 GB", result.usedText)
        assertEquals("8.85 GB", result.remainingText)
        assertEquals("10 GB", result.totalText)
        assertEquals((used.toDouble() / (10 * gib)).toFloat(), requireNotNull(result.progress), 0f)
        assertEquals("30天套餐", result.planText)
        assertEquals("365天套餐", display(resource(days = 365)).planText)
        assertEquals("当前套餐", display(resource(days = null)).planText)
    }

    @Test fun overQuotaRetainsTrueUsedValueAndClampsOnlyTheProgress() {
        val result = display(resource(usage(total = 10 * gib, used = 12 * gib)))
        assertEquals("12 GB", result.usedText)
        assertEquals("10 GB", result.totalText)
        assertEquals("0 GB", result.remainingText)
        assertEquals(1f, requireNotNull(result.progress), 0f)
    }

    @Test fun legacyZeroQuotaHasNoUnlimitedOrRemainingOrProgressClaim() {
        val result = display(resource(usage(total = 0, used = 2 * gib)))
        assertEquals("额度未设置", result.totalText)
        assertEquals("2 GB", result.usedText)
        assertEquals("暂未获取", result.remainingText)
        assertNull(result.progress)
    }

    @Test fun currentPeriodIsInclusiveAtStartAndExclusiveAtEnd() {
        val before = display(at = start.minusNanos(1))
        assertEquals(AccountUsagePeriod.Upcoming, before.period)
        assertEquals("周期未开始", before.periodText)
        assertNull(before.progress)
        val first = display(at = start)
        assertEquals(AccountUsagePeriod.Current, first.period)
        assertEquals("本期用量", first.periodText)
        assertTrue(requireNotNull(first.progress) > 0)
        assertEquals(AccountUsagePeriod.Current, display(at = end.minusNanos(1)).period)
        val last = display(at = end)
        assertEquals(AccountUsagePeriod.Ended, last.period)
        assertEquals("上期数据", last.periodText)
        assertNull(last.progress)
    }

    @Test fun compatibilityWithoutACompleteCycleDoesNotCreateAThisMonthPeriod() {
        val result = display(resource(usage(cycleStart = null, cycleEnd = null)))
        assertEquals(AccountUsagePeriod.Unknown, result.period)
        assertEquals("周期未确认", result.periodText)
        assertNull(result.cycleText)
        assertNull(result.progress)
        assertEquals("47 GB", result.remainingText)
    }

    @Test fun expiredServiceAndDelayedSampleAndEndedPeriodAreIndependent() {
        val expired = display(resource(usage(status = "stale"), validUntil = now))
        assertTrue(expired.serviceExpired)
        assertEquals("同步延迟", expired.collectionText)
        assertEquals(AccountUsagePeriod.Current, expired.period)
        assertTrue(requireNotNull(expired.progress) > 0)
        val valid = display(resource(usage(status = "stale")), at = end)
        assertFalse(valid.serviceExpired)
        assertEquals("同步延迟", valid.collectionText)
        assertEquals("上期数据", valid.periodText)
        assertNull(valid.progress)
        assertFalse(display(resource(validUntil = now.plusNanos(1))).serviceExpired)
    }

    @Test fun failedReadKeepsWholeOldSampleAndNeverReplacesItsTimestampWithNow() {
        val fresh = display()
        val failed = display(at = now.plusSeconds(600), failed = true)
        assertEquals(fresh.copy(refreshText = "未更新"), failed)
        assertEquals("2026-10-02 00:01:00 Z", failed.updatedAtText)
        val ended = display(at = end, failed = true)
        assertEquals("上期数据", ended.periodText)
        assertEquals("未更新", ended.refreshText)
        assertEquals(fresh.updatedAtText, ended.updatedAtText)
        assertNull(ended.progress)
    }

    @Test fun newCyclePendingDoesNotUseAnEarlierMeasuredCycle() {
        val next = resource(usage(status = "pending", cycleStart = end, cycleEnd = end.plusSeconds(300)))
        val result = display(next, at = end)
        assertEquals(AccountUsagePeriod.Current, result.period)
        assertEquals("暂未获取", result.usedText)
        assertEquals("暂未获取", result.remainingText)
        assertNull(result.updatedAtText)
        assertNull(result.progress)
        assertEquals("2026-10-31 23:00:00 Z — 2026-10-31 23:05:00 Z", result.cycleText)
    }

    @Test fun unknownNoneAndReconciliationDoNotInferExpiryOrRetainNumbers() {
        val cases = listOf(
            null to AccountUsageState.Unknown,
            Resource.None to AccountUsageState.NoActivePlan,
            Resource.Reconciliation to AccountUsageState.Reconciliation,
        )
        cases.forEach { (resource, state) ->
            val result = display(resource, at = end.plusSeconds(100_000), failed = true)
            assertEquals(state, result.state)
            assertEquals("暂未获取", result.remainingText)
            assertEquals("暂未获取", result.usedText)
            assertEquals("未更新", result.refreshText)
            assertNull(result.progress)
            assertNull(result.updatedAtText)
            assertNull(result.expiresAtText)
            assertFalse(result.serviceExpired)
        }
        assertEquals("暂无生效套餐", display(Resource.None).statusText)
        assertEquals("套餐正在核对", display(Resource.Reconciliation).statusText)
    }

    @Test fun displayedTimesRespectExplicitZoneWithoutChangingServiceOrPeriodBoundaries() {
        val utcResult = display(at = end)
        val local = display(at = end, zone = ZoneId.of("Asia/Shanghai"))
        assertEquals("2026-10-02 08:01:00 +08:00", local.updatedAtText)
        assertEquals("2026-10-02 08:00:00 +08:00 — 2026-11-01 07:00:00 +08:00", local.cycleText)
        assertEquals(utcResult.period, local.period)
        assertEquals(utcResult.serviceExpired, local.serviceExpired)
        assertEquals(utcResult.progress, local.progress)
    }
}
