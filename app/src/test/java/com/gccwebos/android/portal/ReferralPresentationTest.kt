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

    private val forbidden = listOf("永久", "实时", "首次", "首充", "提现", "舍去", "四舍五入", "退款", "扣回", "撤回", "对账", "只计", "一次")
    private fun assertPositiveCopy(text: String) = forbidden.forEach { assertFalse("'$it' in: $text", text.contains(it)) }

    @Test fun percentAndHeroComeFromThePolicy() {
        assertEquals("7.35", ReferralPresentation.percent(735))
        assertEquals("20", ReferralPresentation.percent(2000))
        assertEquals("奖励比例 7.35%", ReferralPresentation.rateLabel(policy()))
        val hero = ReferralPresentation.hero(policy(rate = 2000))
        assertEquals("邀请好友 · 赚 Coins 奖励", hero.title)
        assertPositiveCopy(hero.title + hero.subtitle)
        assertThrows(IllegalArgumentException::class.java) { ReferralPresentation.percent(0) }
    }

    @Test fun missingPolicyNeverPromisesARewardOrDefaultsNumbers() {
        val hero = ReferralPresentation.hero(null)
        assertFalse(Regex("\\d").containsMatchIn(hero.title + hero.subtitle))
        assertFalse((hero.title + hero.subtitle).contains("奖励"))
        val rules = ReferralPresentation.rules(ReferralProfile(true, null, null, 30))
        assertEquals(listOf("attribution"), rules.map { it.key })
        assertEquals("30 天有效追踪", rules.first().title)
        assertTrue(ReferralPresentation.rules(ReferralProfile(true, null, null, null)).isEmpty())
    }

    @Test fun rulesAndStepsUseServerDaysInShortPositiveCopy() {
        val rules = ReferralPresentation.rules(ReferralProfile(true, null, policy(), 47))
        assertEquals(listOf("attribution", "confirmation", "usage"), rules.map { it.key })
        val byKey = rules.associateBy { it.key }
        assertEquals("47 天有效追踪", byKey.getValue("attribution").title)
        assertTrue(byKey.getValue("attribution").body.contains("47 天内完成注册"))
        assertEquals("9 天确认期", byKey.getValue("confirmation").title)
        assertTrue(byKey.getValue("confirmation").body.contains("9 天后"))
        rules.forEach { assertPositiveCopy(it.title + it.body) }
        val instant = ReferralPresentation.rules(ReferralProfile(true, null, policy(confirmation = 0), 47)).first { it.key == "confirmation" }
        assertEquals("确认后到账", instant.title); assertFalse(instant.body.contains("0 天"))
        val steps = ReferralPresentation.steps(policy())
        assertEquals(listOf("发送邀请", "好友充值", "自动到账"), steps.map { it.title })
        assertEquals("9 天确认期后奖励自动到账", steps.last().body)
        steps.forEach { assertPositiveCopy(it.title + it.body) }
    }

    @Test fun statsMirrorReportedPositiveFieldsOnly() {
        val stats = ReferralPresentation.stats(performance)
        assertEquals(listOf("invites", "recharged", "available", "pending"), stats.map { it.key })
        assertEquals(listOf("4", "1", "5", "3"), stats.map { it.value })
        stats.forEach { assertPositiveCopy(it.label + it.note) }
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
