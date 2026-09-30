package com.gccwebos.android.design

import com.gccwebos.android.portal.Resource
import com.gccwebos.android.portal.Usage
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ResourcePresentationTest {
    private val date = Instant.parse("2026-09-30T00:00:00Z")
    private fun available(usage: Usage) = Resource.Available("fixture-reference", "Server plan", date, true, usage)
    @Test fun signedOutNeverLeaksPreviousResource() {
        val display = resourcePresentation(false, available(Usage(100, 0, 100, "measured_zero", date)))
        assertEquals("登录后查看资源", display.title)
        assertTrue(display.facts.isEmpty())
    }
    @Test fun pendingNeverBecomesZeroOrFullQuota() {
        val display = resourcePresentation(true, available(Usage(100, null, null, "pending", null)))
        assertEquals("尚未采集", display.facts.toMap()["已用流量"])
        assertEquals("暂不可确定", display.facts.toMap()["剩余流量"])
        assertFalse(display.facts.any { it.first == "采集时间" })
    }
    @Test fun measuredZeroIsActualZero() {
        val display = resourcePresentation(true, available(Usage(100, 0, 100, "measured_zero", date)))
        assertEquals("0 B", display.facts.toMap()["已用流量"])
        assertEquals("100 B", display.facts.toMap()["剩余流量"])
    }
    @Test fun staleMarksBothUsedAndRemaining() {
        val display = resourcePresentation(true, available(Usage(100, 20, 80, "stale", date)))
        assertTrue(display.facts.toMap().getValue("已用流量").contains("已过期"))
        assertTrue(display.facts.toMap().getValue("剩余流量").contains("已过期"))
    }
    @Test fun zeroQuotaIsUnknownRatherThanUnlimited() {
        val display = resourcePresentation(true, available(Usage(0, 20, null, "measured", date)))
        assertEquals("暂不可确定", display.facts.toMap()["剩余流量"])
    }
    @Test fun noResourceAndReconciliationHaveDistinctStates() {
        assertEquals("暂无已发放资源", resourcePresentation(true, Resource.None).title)
        assertEquals("资源正在对账", resourcePresentation(true, Resource.Reconciliation).title)
        assertEquals("等待读取资源", resourcePresentation(true, null).title)
    }
    @Test fun availableDoesNotClaimSubscriptionSaved() {
        assertFalse(resourcePresentation(true, available(Usage(1, null, null, "pending", null))).detail.contains("保存"))
    }
    @Test fun serverPlanParametersAppearWithoutLocalPrices() {
        val resource = available(Usage(100, null, null, "pending", null)).copy(durationDays = 30, monthlyTrafficGb = 12.5)
        val display = resourcePresentation(true, resource)
        assertEquals("12.5 GB · 30 天", display.title)
        assertEquals("12.5 GB", display.facts.toMap()["每月套餐流量"])
        assertEquals("30 天", display.facts.toMap()["套餐时长"])
        assertEquals("尚未采集", display.facts.toMap()["已用流量"])
    }
    @Test fun unknownQuotaNeverClaimsZeroOrUnlimitedPlanTraffic() {
        val resource = available(Usage(0, 20, null, "measured", date)).copy(durationDays = 365, monthlyTrafficGb = 0.0)
        val display = resourcePresentation(true, resource)
        assertEquals("Server plan", display.title)
        assertEquals("365 天", display.facts.toMap()["套餐时长"])
        assertFalse(display.facts.toMap().containsKey("每月套餐流量"))
    }
    @Test fun parserRetainsValidatedSourcePlanFields() {
        val body = """{"resource":{"state":"available","plan":{"id":"source-plan","durationDays":90,"monthlyTrafficGb":25.5},"validUntil":"2026-12-30T00:00:00.000Z","setupAvailable":true,"resourceRef":"resource_${"a".repeat(64)}","usage":{"totalBytes":100,"usedBytes":null,"remainingBytes":null,"collectionStatus":"pending","updatedAt":null,"cycleStartedAt":"2026-09-30T00:00:00.000Z","cycleEndsAt":"2026-10-30T00:00:00.000Z"}}}"""
        val resource = com.gccwebos.android.portal.PortalJson.resource(body) as Resource.Available
        assertEquals(90L, resource.durationDays)
        assertEquals(25.5, resource.monthlyTrafficGb)
        assertEquals("25.5 GB · 90 天", resourcePresentation(true, resource).title)
    }
    @Test fun binaryUnitsAreExplicitAndNullIsNotZero() {
        assertEquals("1.0 GiB", formatBytes(1024L * 1024 * 1024))
        assertEquals("尚未采集", formatBytes(null))
        assertEquals("8.0 PiB", formatBytes(Usage.MAX_SAFE))
    }
}
