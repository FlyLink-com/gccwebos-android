package com.gccwebos.android.portal

import androidx.compose.ui.graphics.Color
import com.gccwebos.android.design.Brand
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

    @Test fun measuringEmptyCellKeepsStaticDashAndTestingDescription() {
        val p = nodeLatencyPresentation(null, measuring = true)
        assertTrue(p.measuring)
        assertEquals("---", p.label)
        assertEquals("节点延迟：测试中", nodeLatencyContentDescription(p))
    }

    @Test fun successBoundariesAreGreenBlueOrangeRed() {
        fun shown(ms: Long) = nodeLatencyPresentation(NodeLatencyObservation.Success(ms, 1))
        assertEquals(NodeLatencyTone.Good, shown(149).tone)
        assertEquals(NodeLatencyTone.Good, shown(150).tone)
        assertEquals(NodeLatencyTone.Moderate, shown(151).tone)
        assertEquals(NodeLatencyTone.Moderate, shown(199).tone)
        assertEquals(NodeLatencyTone.Slow, shown(200).tone)
        assertEquals(NodeLatencyTone.Slow, shown(299).tone)
        assertEquals(NodeLatencyTone.Poor, shown(300).tone)
        assertEquals("149 ms", shown(149).label)
        assertEquals("150 ms", shown(150).label)
        assertEquals("151 ms", shown(151).label)
        assertEquals("199 ms", shown(199).label)
        assertEquals("200 ms", shown(200).label)
        assertEquals("299 ms", shown(299).label)
        assertEquals("300 ms", shown(300).label)
        assertEquals("节点延迟 150 毫秒", nodeLatencyContentDescription(shown(150)))
        assertEquals("节点延迟 200 毫秒", nodeLatencyContentDescription(shown(200)))
        assertEquals("节点延迟 300 毫秒", nodeLatencyContentDescription(shown(300)))
    }

    @Test fun successBoundaryColorsUseSemanticConstants() {
        assertEquals(Brand.Green, LatencyGoodColor)
        assertEquals(Brand.Blue, LatencyModerateColor)
        assertEquals(Color(0xFFFF9F43), LatencySlowColor)
        assertEquals(Color(0xFFFF5D5D), LatencyPoor)
        assertEquals(LatencyGoodColor, nodeLatencyToneColor(NodeLatencyTone.Good))
        assertEquals(LatencyModerateColor, nodeLatencyToneColor(NodeLatencyTone.Moderate))
        assertEquals(LatencySlowColor, nodeLatencyToneColor(NodeLatencyTone.Slow))
        assertEquals(LatencyPoor, nodeLatencyToneColor(NodeLatencyTone.Poor))
        assertEquals(Brand.Muted.copy(alpha = .75f), nodeLatencyToneColor(NodeLatencyTone.Neutral))
    }

    @Test fun unavailableWhileTunnelUpIsSkippedNotBroken() {
        val p = nodeLatencyPresentation(NodeLatencyObservation.Unavailable, tunnelActive = true)
        assertEquals("—", p.label)
        assertEquals("节点延迟：已连接时无法测速", nodeLatencyContentDescription(p))
        // A real result is never rewritten. A running batch keeps the spoken testing state and a static dash.
        assertEquals("超时", nodeLatencyPresentation(NodeLatencyObservation.NoReply, tunnelActive = true).label)
        val measuringUnavailable = nodeLatencyPresentation(
            NodeLatencyObservation.Unavailable, measuring = true, tunnelActive = true)
        assertTrue(measuringUnavailable.measuring)
        assertEquals("---", measuringUnavailable.label)
        assertEquals("节点延迟：测试中", nodeLatencyContentDescription(measuringUnavailable))
    }

    @Test fun timeoutIsPoorCapsuleLabel() {
        val p = nodeLatencyPresentation(NodeLatencyObservation.NoReply)
        assertEquals("超时", p.label)
        assertEquals(NodeLatencyTone.Poor, p.tone)
        assertEquals(LatencyPoor, nodeLatencyToneColor(p.tone))
        assertEquals("节点延迟：超时", nodeLatencyContentDescription(p))
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
        // N7: a third-party provider's site is never shown; traffic and expiry stay.
        assertNull(parts.website)
        assertEquals("流量：244.26 GB | 到期：2026-10-12", formatNodeInfoBarLine(parts))
        assertNull(formatNodeInfoBarLine(extractNodeInfoBar(listOf("🇺🇸 United States 01"))))
    }

    @Test fun infoBarShowsOnlyGccwebosOfficialSite() {
        fun line(vararg site: String) = formatNodeInfoBarLine(extractNodeInfoBar(
            listOf("剩余流量：1 GB", "套餐到期：2026-10-12") + site))
        assertEquals("流量：1 GB | 到期：2026-10-12 | 官网：gccwebos.com", line("官网：gccwebos.com"))
        assertEquals("流量：1 GB | 到期：2026-10-12 | 官网：https://www.gccwebos.com/x", line("🌐 网址：https://www.gccwebos.com/x"))
        assertEquals("流量：1 GB | 到期：2026-10-12 | 官网：GCCWEBOS.COM:443", line("官网: GCCWEBOS.COM:443"))
        val hidden = "流量：1 GB | 到期：2026-10-12"
        for (bad in listOf("官网：kitty.fo", "官网：gccwebos.com.evil.tld", "官网：evilgccwebos.com",
            "官网：https://evil.tld/gccwebos.com", "官网：https://gccwebos.com@evil.tld", "官网：evil.tld?u=gccwebos.com",
            "官网：gccwebos.com evil.tld", "官网：ftp://gccwebos.com", "官网：gccwebos.cоm", "官网：未知"))
            assertEquals(bad, hidden, line(bad))
        // A later official notice still shows when an earlier third-party one is dropped.
        assertEquals("流量：1 GB | 到期：2026-10-12 | 官网：gccwebos.com", line("官网：kitty.fo", "网址：gccwebos.com"))
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
