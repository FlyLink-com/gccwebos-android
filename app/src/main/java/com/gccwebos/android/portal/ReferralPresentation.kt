package com.gccwebos.android.portal

import java.math.BigDecimal
import java.net.URLEncoder

/**
 * Pure copy for the referral ("邀请返利") screen. Every number comes from the Portal
 * profile/performance projections; nothing here supplies a default rate or window.
 *
 * Server facts this wording relies on (proxy-gateway-minimal, COINS_FIRST_COMMERCIAL_DECISION
 * and portal-referral-service): one reward per referred account, computed from that account's
 * first verified recharge only; registration must happen within the attribution window from the
 * last referral landing; rewards stay pending for the confirmation delay, are reversed on refund,
 * and become closed-loop reward Coins (spendable on products, not withdrawable).
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

    fun rateLabel(policy: ReferralPolicy) = "当前奖励比例 ${percent(policy.rateBps)}%"

    fun hero(policy: ReferralPolicy?): Hero = if (policy != null) Hero(
        "邀请好友 · 赚 ${percent(policy.rateBps)}% 首充奖励",
        "好友首次充值后，你可获得其首充 Coins 的 ${percent(policy.rateBps)}% 作为奖励，每位好友计一次")
    else Hero("邀请好友 · 一起用飞连", "分享你的专属链接邀请好友注册；当前暂未公布奖励规则")

    /** Shows origin plus the first/last four characters of the opaque link code. */
    fun maskedLink(url: String): String {
        val prefix = ReferralJson.SHARE_ORIGIN + "/r/"
        require(url.startsWith(prefix))
        val code = url.removePrefix(prefix)
        require(code.length > 8)
        return prefix.removePrefix("https://") + code.take(4) + "…" + code.takeLast(4)
    }

    /** Neutral share text: no reward promise is made to the invited friend. */
    fun shareMessage(url: String) = "推荐你试试飞连 VPN，用我的专属链接注册：$url"

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun telegramShareUrl(url: String) =
        "$TELEGRAM_SHARE?url=${encode(url)}&text=${encode("推荐你试试飞连 VPN，用我的专属链接注册")}"

    fun xShareUrl(url: String) = "$X_SHARE?text=${encode("推荐你试试飞连 VPN，用我的专属链接注册")}&url=${encode(url)}"

    /**
     * Only fields the Portal actually reports. availableRewardCoins is the unspent remainder of
     * released referral reward lots (spending lowers it), so no lifetime "累计总收益" is derived.
     */
    fun stats(p: ReferralPerformance): List<Stat> = listOf(
        Stat("invites", "累计邀请", p.registrations.toString(), "人", "首次充值 ${p.firstRechargeConversions} 人"),
        Stat("available", "可用奖励", p.availableRewardCoins.toString(), "Coins", "已到账，尚未使用"),
        Stat("pending", "待确认奖励", p.pendingRewardCoins.toString(), "Coins", "确认期内，暂不可用"),
        Stat("reversed", "已撤回", p.reversedRewardCoins.toString(), "Coins", "好友退款后扣回"),
    )

    fun steps(policy: ReferralPolicy?): List<Step> = listOf(
        Step("发送邀请", "把专属链接分享给好友"),
        Step("好友充值", "好友用链接注册，并完成首次充值"),
        Step("自动到账", when {
            policy == null -> "奖励规则公布后按规则自动计入"
            policy.confirmationDays == 0L -> "核对无误后奖励自动转为可用"
            else -> "${policy.confirmationDays} 天确认期后自动转为可用"
        }),
    )

    fun rules(profile: ReferralProfile): List<Rule> = buildList {
        profile.attributionDays?.let { days ->
            add(Rule("attribution", "$days 天有效追踪",
                "好友点击你的链接后 $days 天内完成注册，即记为你邀请的好友（以最后一次点击的推荐链接为准）。"))
        }
        profile.policy?.let { policy ->
            val confirmation = policy.confirmationDays
            add(Rule("confirmation", if (confirmation == 0L) "确认后到账" else "$confirmation 天确认期",
                if (confirmation == 0L) "好友首次充值到账并核对无误后，奖励自动转为可用余额。"
                else "为防止退款风险，好友首次充值到账 $confirmation 天后，奖励核对无误即自动转为可用余额。"))
            add(Rule("basis", "奖励怎么算",
                "每位好友只计一次：按其首次充值获得的 Coins 的 ${percent(policy.rateBps)}% 发放，" +
                    (if (policy.rounding == "floor") "不足 1 Coin 的部分舍去" else "按四舍五入取整") +
                    "。之后的充值和购买不再产生奖励；好友退款时对应奖励会被扣回。"))
        }
        add(Rule("usage", "奖励用途",
            "到账的奖励 Coins 可用于购买套餐，不能提现。本页为推荐奖励记录，实际可用余额以钱包为准。"))
    }
}
