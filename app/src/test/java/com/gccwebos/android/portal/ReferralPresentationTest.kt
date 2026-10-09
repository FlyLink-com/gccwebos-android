package com.gccwebos.android.portal

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Copy is rendered from Portal values only; there is no built-in rate, window or lifetime total. */
class ReferralPresentationTest {
    private val url = "https://portal.gccwebos.com/r/" + "AbCd" + "x".repeat(24) + "WxYz"
    private fun policy(rate: Long = 735, attribution: Long = 47, confirmation: Long = 9, rounding: String = "floor") =
        ReferralPolicy("policy_fixture", 3, Instant.parse("2026-09-30T00:00:00Z"), rate, rounding, attribution, confirmation)
    private val performance = ReferralPerformance(4, 1, 17, 3, 5, 2, .25, false)

    @Test fun percentAndHeroComeFromThePolicy() {
        assertEquals("7.35", ReferralPresentation.percent(735))
        assertEquals("20", ReferralPresentation.percent(2000))
        assertEquals("当前奖励比例 7.35%", ReferralPresentation.rateLabel(policy()))
        val hero = ReferralPresentation.hero(policy(rate = 2000))
        assertEquals("邀请好友 · 赚 20% 首充奖励", hero.title)
        assertTrue(hero.subtitle.contains("首充 Coins 的 20%"))
        assertTrue(hero.subtitle.contains("每位好友计一次"))
        assertThrows(IllegalArgumentException::class.java) { ReferralPresentation.percent(0) }
    }

    @Test fun missingPolicyNeverPromisesARewardOrDefaultsNumbers() {
        val hero = ReferralPresentation.hero(null)
        val all = hero.title + hero.subtitle + ReferralPresentation.steps(null).joinToString { it.title + it.body }
        assertFalse(Regex("\\d").containsMatchIn(all))
        assertFalse(all.contains("永久")); assertFalse(all.contains("实时"))
        val rules = ReferralPresentation.rules(ReferralProfile(true, null, null, 30))
        assertEquals(listOf("attribution", "usage"), rules.map { it.key })
        assertEquals("30 天有效追踪", rules.first().title)
        assertEquals(listOf("usage"), ReferralPresentation.rules(ReferralProfile(true, null, null, null)).map { it.key })
    }

    @Test fun rulesUseServerWindowsAndRounding() {
        val rules = ReferralPresentation.rules(ReferralProfile(true, null, policy(), 47)).associateBy { it.key }
        assertEquals("47 天有效追踪", rules.getValue("attribution").title)
        assertTrue(rules.getValue("attribution").body.contains("47 天内完成注册"))
        assertEquals("9 天确认期", rules.getValue("confirmation").title)
        assertTrue(rules.getValue("confirmation").body.contains("首次充值到账 9 天后"))
        assertTrue(rules.getValue("basis").body.contains("7.35%"))
        assertTrue(rules.getValue("basis").body.contains("舍去"))
        assertTrue(ReferralPresentation.rules(ReferralProfile(true, null, policy(rounding = "half_up"), 47))
            .first { it.key == "basis" }.body.contains("四舍五入"))
        val instant = ReferralPresentation.rules(ReferralProfile(true, null, policy(confirmation = 0), 47)).first { it.key == "confirmation" }
        assertEquals("确认后到账", instant.title); assertFalse(instant.body.contains("0 天"))
        assertEquals("9 天确认期后自动转为可用", ReferralPresentation.steps(policy()).last().body)
        assertTrue(rules.getValue("usage").body.contains("不能提现"))
    }

    @Test fun statsMirrorReportedFieldsWithoutDerivedTotals() {
        val stats = ReferralPresentation.stats(performance)
        assertEquals(listOf("invites", "available", "pending", "reversed"), stats.map { it.key })
        assertEquals(listOf("4", "5", "3", "2"), stats.map { it.value })
        assertEquals("首次充值 1 人", stats[0].note)
        assertTrue(stats.none { it.label.contains("累计总") })
    }

    @Test fun maskedLinkAndShareUrlsEncodeTheFullLink() {
        assertEquals("portal.gccwebos.com/r/AbCd…WxYz", ReferralPresentation.maskedLink(url))
        assertThrows(IllegalArgumentException::class.java) { ReferralPresentation.maskedLink("https://evil.invalid/r/AbCdxxxxxxxxWxYz") }
        assertTrue(ReferralPresentation.shareMessage(url).endsWith(url))
        val encoded = "https%3A%2F%2Fportal.gccwebos.com%2Fr%2FAbCd" + "x".repeat(24) + "WxYz"
        val telegram = ReferralPresentation.telegramShareUrl(url)
        assertTrue(telegram.startsWith("https://t.me/share/url?url=$encoded&text="))
        assertFalse(telegram.contains("+")); assertFalse(telegram.contains(" "))
        val x = ReferralPresentation.xShareUrl(url)
        assertTrue(x.startsWith("https://x.com/intent/post?text=")); assertTrue(x.endsWith("&url=$encoded"))
    }
}
