package com.gccwebos.android.portal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeLatencyPresentationTest {
    @Test fun untestedIsDashesNotUnmeasurable() {
        val p = nodeLatencyPresentation(null)
        assertEquals("---", p.label)
        assertEquals(NodeLatencyTone.Neutral, p.tone)
        assertFalse(p.measuring)
    }

    @Test fun measuringEmptyCellShowsSpinnerState() {
        val p = nodeLatencyPresentation(null, measuring = true)
        assertTrue(p.measuring)
        assertEquals("", p.label)
    }

    @Test fun successTiersMatchThresholds() {
        assertEquals(NodeLatencyTone.Good, nodeLatencyPresentation(NodeLatencyObservation.Success(45, 1)).tone)
        assertEquals(NodeLatencyTone.Good, nodeLatencyPresentation(NodeLatencyObservation.Success(99, 1)).tone)
        assertEquals(NodeLatencyTone.Fair, nodeLatencyPresentation(NodeLatencyObservation.Success(100, 1)).tone)
        assertEquals(NodeLatencyTone.Fair, nodeLatencyPresentation(NodeLatencyObservation.Success(200, 1)).tone)
        assertEquals(NodeLatencyTone.Poor, nodeLatencyPresentation(NodeLatencyObservation.Success(201, 1)).tone)
        assertEquals("150 ms", nodeLatencyPresentation(NodeLatencyObservation.Success(150, 1)).label)
    }

    @Test fun timeoutIsPoorCapsuleLabel() {
        val p = nodeLatencyPresentation(NodeLatencyObservation.NoReply)
        assertEquals("超时", p.label)
        assertEquals(NodeLatencyTone.Poor, p.tone)
    }

    @Test fun unavailableWithoutMeasuringKeepsUnmeasurable() {
        val p = nodeLatencyPresentation(NodeLatencyObservation.Unavailable)
        assertEquals("不可测", p.label)
        assertFalse(p.measuring)
    }

    @Test fun lastTestAgoOmitsNullAndStale() {
        assertNull(formatLastLatencyTestAgo(null))
        assertNull(formatLastLatencyTestAgo(-1))
        assertEquals("上次测速：刚刚", formatLastLatencyTestAgo(3_000))
        assertEquals("上次测速：10分钟前", formatLastLatencyTestAgo(10 * 60_000L))
        assertEquals("上次测速：2小时前", formatLastLatencyTestAgo(2 * 3_600_000L))
        assertNull(formatLastLatencyTestAgo(10 * 86_400_000L))
    }

    @Test fun infoBarExtractsTrafficExpiryWebsiteWithoutInventing() {
        val parts = extractNodeInfoBar(
            listOf(
                "🇦🇱 剩余流量：244.26 GB",
                "🇦🇱 套餐到期：2026-10-12",
                "官网：kitty.fo",
                "🇭🇰 Hong Kong 01",
            ),
        )
        assertEquals("244.26 GB", parts.traffic)
        assertEquals("2026-10-12", parts.expiry)
        assertEquals("kitty.fo", parts.website)
        assertEquals("流量：244.26 GB | 到期：2026-10-12 | 官网：kitty.fo", formatNodeInfoBarLine(parts))
        assertNull(formatNodeInfoBarLine(extractNodeInfoBar(listOf("🇺🇸 United States 01"))))
    }

    @Test fun infoBarAcceptsColonVariantsAndIgnoresUnknownNotices() {
        val parts = extractNodeInfoBar(listOf("剩余流量: 10 GB", "到期时间：2027-01-01", "客服：ignore-me"))
        assertEquals("10 GB", parts.traffic)
        assertEquals("2027-01-01", parts.expiry)
        assertNull(parts.website)
        assertEquals("流量：10 GB | 到期：2027-01-01", formatNodeInfoBarLine(parts))
    }

    @Test fun sortKeyOrdersSuccessBeforeFailuresBeforeUntested() {
        assertTrue(nodeLatencySortKey(NodeLatencyObservation.Success(40, 1)) <
            nodeLatencySortKey(NodeLatencyObservation.Success(180, 1)))
        assertTrue(nodeLatencySortKey(NodeLatencyObservation.Success(180, 1)) <
            nodeLatencySortKey(NodeLatencyObservation.NoReply))
        assertTrue(nodeLatencySortKey(NodeLatencyObservation.NoReply) <
            nodeLatencySortKey(null))
    }
}
