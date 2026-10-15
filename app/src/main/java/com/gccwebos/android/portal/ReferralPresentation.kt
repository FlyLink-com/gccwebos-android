package com.gccwebos.android.portal

import java.math.BigDecimal
import java.net.URLEncoder

/**
 * Pure copy for the referral ("邀请返利") screen. Every number comes from the Portal
 * profile/performance projections; nothing here supplies a default rate or window.
 * User copy stays short and positive: it shows only real values (rate, tracking and
 * confirmation days, counts) and never internal boundaries or settlement mechanics.
 */
internal object ReferralPresentation {
    const val TELEGRAM_SHARE = "https://t.me/share/url"
    const val X_SHARE = "https://x.com/intent/post"

    data class Hero(val title: String, val subtitle: String)
    data class Stat(val key: String, val label: String, val value: String, val unit: String, val note: String)
    data class Step(val title: String, val body: String)
    data class Rule(val key: String, val title: String, val body: String)

    fun percent(rateBps: Long): String {
        require(rateBps in 1..10000)
        return BigDecimal.valueOf(rateBps).movePointLeft(2).stripTrailingZeros().toPlainString()
    }

    fun rateLabel(policy: ReferralPolicy) = "奖励比例 ${percent(policy.rateBps)}%"

    fun hero(policy: ReferralPolicy?): Hero = if (policy != null)
        Hero("邀请好友 · 赚 Coins 奖励", "好友通过你的专属链接注册并充值，你就能获得 Coins 奖励")
    else Hero("邀请好友 · 一起用飞连VPN", "分享你的专属链接，邀请好友一起使用")

    /** Shows origin plus the first/last four characters of the opaque link code. */
    fun maskedLink(url: String): String {
        val prefix = ReferralJson.SHARE_ORIGIN + "/r/"
        require(url.startsWith(prefix))
        val code = url.removePrefix(prefix)
        require(code.length > 8)
        return prefix.removePrefix("https://") + code.take(4) + "…" + code.takeLast(4)
    }

    /** Neutral share text: no reward promise is made to the invited friend. */
    fun shareMessage(url: String) = "推荐你试试飞连VPN，用我的专属链接注册：$url"

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun telegramShareUrl(url: String) =
        "$TELEGRAM_SHARE?url=${encode(url)}&text=${encode("推荐你试试飞连VPN，用我的专属链接注册")}"

    fun xShareUrl(url: String) = "$X_SHARE?text=${encode("推荐你试试飞连VPN，用我的专属链接注册")}&url=${encode(url)}"

    /** Only reported fields, all shown as positive counts; no derived totals. */
    fun stats(p: ReferralPerformance): List<Stat> = listOf(
        Stat("invites", "累计邀请", p.registrations.toString(), "人", "已注册好友"),
        Stat("recharged", "好友充值", p.firstRechargeConversions.toString(), "人", "已完成充值"),
        Stat("available", "可用奖励", p.availableRewardCoins.toString(), "Coins", "已到账"),
        Stat("pending", "待确认奖励", p.pendingRewardCoins.toString(), "Coins", "确认后自动到账"),
    )

    /** The 3-step flow only exists when a reward policy is published. */
    fun steps(policy: ReferralPolicy): List<Step> = listOf(
        Step("发送邀请", "把专属链接分享给好友"),
        Step("好友充值", "好友通过链接注册并充值"),
        Step("自动到账", if (policy.confirmationDays == 0L) "确认后奖励自动到账"
            else "${policy.confirmationDays} 天确认期后奖励自动到账"),
    )

    fun rules(profile: ReferralProfile): List<Rule> = buildList {
        profile.attributionDays?.let { days ->
            add(Rule("attribution", "$days 天内注册有效", "好友点击你的链接后 $days 天内完成注册，即为你邀请的好友。"))
        }
        profile.policy?.let { policy ->
            val days = policy.confirmationDays
            add(Rule("confirmation", if (days == 0L) "确认后到账" else "$days 天确认期",
                if (days == 0L) "好友充值后，奖励确认后自动转为可用余额。"
                else "好友充值 $days 天后，奖励自动转为可用余额。"))
            add(Rule("usage", "奖励用途", "到账的奖励 Coins 可用于购买套餐。"))
        }
    }
}
