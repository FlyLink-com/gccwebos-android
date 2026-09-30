package com.gccwebos.android.design

import com.gccwebos.android.portal.Resource
import com.gccwebos.android.portal.Usage
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ResourcePresentationTest {
    private val date = Instant.parse("2026-09-30T00:00:00Z")
    private fun available(usage: Usage) = Resource.Available("fixture-reference", "Server plan", date, true, usage)
    private fun display(resource: Resource?, now: Instant = date, zone: ZoneId = ZoneId.of("UTC")) =
        resourcePresentation(true, resource, now = now, zone = zone)

    @Test fun signedOutNeverLeaksPreviousResource() {
        val result = resourcePresentation(false, available(Usage(100, 0, 100, "measured_zero", date)), busy = true, error = "error")
        assertEquals("登录后查看资源", result.title)
        assertTrue(result.facts.isEmpty())
        assertNull(result.countdown)
    }
    @Test fun loadingNeverClaimsAuthoritativeNoResource() {
        val result = resourcePresentation(true, Resource.None, busy = true)
        assertEquals("正在读取资源", result.title)
        assertTrue(result.facts.isEmpty())
        assertFalse(result.detail.contains("暂无"))
    }
    @Test fun errorNeverClaimsAuthoritativeNoResource() {
        val result = resourcePresentation(true, Resource.None, error = "network unavailable")
        assertEquals("资源暂时无法确认", result.title)
        assertTrue(result.facts.isEmpty())
        assertFalse(result.detail.contains("暂无"))
    }
    @Test fun errorDoesNotDisplayOldAvailableCountdownAsCurrent() {
        val result = resourcePresentation(true, available(Usage(100, 20, 80, "measured", date)), error = "error")
        assertNull(result.countdown)
        assertTrue(result.facts.isEmpty())
    }
    @Test fun pendingNeverBecomesZeroOrFullQuota() {
        val result = display(available(Usage(100, null, null, "pending", null)))
        assertEquals("100 B", result.facts.toMap()["总流量"])
        assertEquals("尚未采集", result.facts.toMap()["已用流量"])
        assertEquals("暂不可确定", result.facts.toMap()["剩余流量"])
        assertFalse(result.facts.any { it.first == "采集时间" })
    }
    @Test fun measuredZeroIsActualZero() {
        val result = display(available(Usage(100, 0, 100, "measured_zero", date)))
        assertEquals("100 B", result.facts.toMap()["总流量"])
        assertEquals("0 B", result.facts.toMap()["已用流量"])
        assertEquals("100 B", result.facts.toMap()["剩余流量"])
    }
    @Test fun staleMarksBothUsedAndRemaining() {
        val result = display(available(Usage(100, 20, 80, "stale", date)))
        assertEquals("20 B · 数据已过期", result.facts.toMap()["已用流量"])
        assertEquals("80 B · 数据已过期", result.facts.toMap()["剩余流量"])
    }
    @Test fun zeroQuotaIsUnknownRatherThanZeroOrUnlimited() {
        val result = display(available(Usage(0, 20, null, "measured", date)))
        assertEquals("额度未确认", result.facts.toMap()["总流量"])
        assertEquals("暂不可确定", result.facts.toMap()["剩余流量"])
    }
    @Test fun noResourceAndReconciliationHaveDistinctStates() {
        assertEquals("暂无已发放资源", display(Resource.None).title)
        assertEquals("资源正在对账", display(Resource.Reconciliation).title)
        assertEquals("等待读取资源", display(null).title)
        assertNull(display(Resource.None).countdown)
        assertNull(display(Resource.Reconciliation).countdown)
    }
    @Test fun availableDoesNotClaimSubscriptionSaved() {
        assertFalse(display(available(Usage(1, null, null, "pending", null))).detail.contains("保存"))
    }
    @Test fun thirtyDayHeadingContainsOnlyServerDuration() {
        val resource = available(Usage(100, null, null, "pending", null)).copy(durationDays = 30, monthlyTrafficGb = 12.5)
        val result = display(resource)
        assertEquals("30天", result.title)
        assertFalse(result.title.contains("GB"))
        assertFalse(result.title.contains("Server plan"))
        assertEquals("100 B", result.facts.toMap()["总流量"])
    }
    @Test fun annualHeadingUsesActual365AndNot360EvenWithUnknownQuota() {
        val resource = available(Usage(0, 20, null, "measured", date)).copy(durationDays = 365, monthlyTrafficGb = 0.0)
        assertEquals("年卡365天", display(resource).title)
        assertEquals("360天", display(resource.copy(durationDays = 360)).title)
    }
    @Test fun missingDurationIsNotInventedFromPlanName() {
        val resource = available(Usage(100, null, null, "pending", null)).copy(plan = "annual")
        assertEquals("当前订阅", display(resource).title)
    }
    @Test fun parserRetainsValidatedSourcePlanFields() {
        val body = """{"resource":{"state":"available","plan":{"id":"source-plan","durationDays":90,"monthlyTrafficGb":25.5},"validUntil":"2026-12-30T00:00:00.000Z","setupAvailable":true,"resourceRef":"resource_${"a".repeat(64)}","usage":{"totalBytes":100,"usedBytes":null,"remainingBytes":null,"collectionStatus":"pending","updatedAt":null,"cycleStartedAt":"2026-09-30T00:00:00.000Z","cycleEndsAt":"2026-10-30T00:00:00.000Z"}}}"""
        val resource = com.gccwebos.android.portal.PortalJson.resource(body) as Resource.Available
        assertEquals(90L, resource.durationDays)
        assertEquals(25.5, resource.monthlyTrafficGb)
        assertEquals("90天", display(resource).title)
    }
    @Test fun countdownUsesActualExpiryAndNowRatherThanPlanDuration() {
        val resource = available(Usage(100, null, null, "pending", null)).copy(durationDays = 365,
            validUntil = date.plusSeconds(86_400 + 3_600 + 120 + 3))
        assertEquals("剩余 1天 1小时 2分 3秒", display(resource).countdown)
        assertEquals("剩余 1天 1小时 2分 2秒", display(resource, now = date.plusSeconds(1)).countdown)
    }
    @Test fun expiryBoundaryAndPastNeverBecomeNegative() {
        assertEquals("已到期", remainingValidity(date, date))
        assertEquals("已到期", remainingValidity(date, date.plusNanos(1)))
        assertEquals("已到期", remainingValidity(date, date.plusSeconds(31_536_000)))
    }
    @Test fun positivePartialSecondIsNotPrematurelyExpired() {
        assertEquals("剩余 0天 0小时 0分 1秒", remainingValidity(date.plusNanos(1), date))
        assertEquals("剩余 1天 0小时 0分 0秒", remainingValidity(date.plusSeconds(86_400), date.plusNanos(1)))
    }
    @Test fun expiryAndCollectionUseLocalZoneWithOffset() {
        val resource = available(Usage(100, 20, 80, "measured", date))
        val result = display(resource, zone = ZoneId.of("Asia/Shanghai"))
        assertEquals("2026-09-30 08:00:00 +08:00", result.facts.toMap()["到期时间"])
        assertEquals("2026-09-30 08:00:00 +08:00", result.facts.toMap()["采集时间"])
    }
    @Test fun daylightSavingOffsetComesFromExpiryInstant() {
        val resource = available(Usage(100, null, null, "pending", null))
        assertEquals("2026-09-29 17:00:00 -07:00", display(resource, zone = ZoneId.of("America/Los_Angeles")).facts.toMap()["到期时间"])
        assertEquals("2026-12-31 16:00:00 -08:00", display(resource.copy(validUntil = Instant.parse("2027-01-01T00:00:00Z")), zone = ZoneId.of("America/Los_Angeles")).facts.toMap()["到期时间"])
    }
    @Test fun binaryUnitsAreExplicitAndNullIsNotZero() {
        assertEquals("1.0 GiB", formatBytes(1024L * 1024 * 1024))
        assertEquals("尚未采集", formatBytes(null))
        assertEquals("8.0 PiB", formatBytes(Usage.MAX_SAFE))
    }
}
