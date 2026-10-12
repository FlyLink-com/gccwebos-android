package com.gccwebos.android.commerce

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Plain-Chinese presentation of the server's monthly-card plan change (renew / upgrade / scheduled downgrade).
 * Everything here is derived from the server state and quote; the app prices nothing and decides nothing itself.
 * With the feature off on the server the state is null and every function yields "no plan change": the ordinary
 * catalog is shown exactly as before. No host, URL or reference ever appears in these texts.
 */
internal const val PLAN_NOW = "立即生效"
internal const val PLAN_AT_EXPIRY = "到期后生效"
internal const val PLAN_PENDING = "待生效"
internal const val PLAN_APPLYING = "处理中"
internal const val PLAN_APPLYING_TEXT = "正在应用已预约的降级，请稍后。"
internal const val PLAN_ACTIVE_TEXT = "当前套餐生效中，暂不能购买其他套餐。"
/** How long past its date a scheduled downgrade is still shown as 处理中; after that the plain catalog (and the server's own 409) speak. */
private const val APPLYING_WINDOW_SECONDS = 15L * 60

private fun day(value: Instant, zone: ZoneId) = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(zone).format(value)

/** What one catalog card shows while the account has a monthly card. [selectable] is false for a change the server refuses. */
internal data class PlanSwitchCard(val action: PlanSwitchAction, val selectable: Boolean, val chip: String, val note: String?,
    /** Dock price text ("补差价 5 Coins · 立即生效"); null leaves the ordinary price. */
    val dockPrice: String?, val dockAction: String)

/**
 * The scheduled downgrade's date has passed but the server has not cleared it yet (it is being applied). The server may
 * briefly answer every offer as unavailable with plan_switch_application_pending in that gap.
 */
internal fun PlanSwitchState.applying(now: Instant): Boolean =
    pending != null && pending.effectiveAt <= now && now < pending.effectiveAt.plusSeconds(APPLYING_WINDOW_SECONDS)

/** The state is ignored once its own deadline passed: an overdue answer must not look like a live card. */
internal fun PlanSwitchState.liveAt(now: Instant): PlanSwitchState? {
    if (applying(now)) return this
    // Like the portal: an answer past its own deadline is dropped whole (it describes a card that no longer exists).
    if (current != null && current.validUntil <= now) return null
    val live = current
    val pendingLive = if (live != null) pending?.takeIf { it.effectiveAt > now } else null
    val blocking = offers.any { it.reasonCode?.startsWith("plan_switch_") == true }
    // The portal rule: switch labels only while a card is active or the server names a plan-change reason.
    return if (live != null || blocking) PlanSwitchState(live, pendingLive, offers) else null
}

internal fun planSwitchCards(state: PlanSwitchState?, now: Instant, zone: ZoneId = ZoneId.systemDefault(),
    shownOfferRefs: Collection<String> = emptyList()): Map<String, PlanSwitchCard> {
    val live = state?.liveAt(now) ?: return emptyMap()
    if (live.applying(now)) {
        // Nothing can be bought or changed until the server has applied the downgrade.
        val busy = PlanSwitchCard(PlanSwitchAction.Unavailable, false, PLAN_APPLYING, PLAN_APPLYING_TEXT, null, "立即购买")
        return (live.offers.map { it.offerRef } + shownOfferRefs).associateWith { busy }
    }
    // "unavailable" without a reason means the tier is not part of the plan-change table: an ordinary purchase.
    return live.offers.filterNot { it.action == PlanSwitchAction.Unavailable && it.reasonCode == null }.associate { entry ->
        entry.offerRef to when (entry.action) {
            PlanSwitchAction.CurrentRenew -> PlanSwitchCard(entry.action, true, "续费",
                live.pending?.let { "续费会取消已预约的降级" }, null, "立即续费")
            PlanSwitchAction.Upgrade -> {
                // The state's figure is before the account discount, so the card says 约; the confirmation shows the real deduction.
                val diff = entry.chargeUnits?.let { "补差价约 ${formatCoins(it)}" }
                PlanSwitchCard(entry.action, true, listOfNotNull("升级", diff, PLAN_NOW).joinToString(" · "),
                    "已用流量不清零，不退款", listOfNotNull(diff, PLAN_NOW).joinToString(" · "), "立即升级")
            }
            PlanSwitchAction.Downgrade -> {
                val until = (entry.effectiveAt ?: live.current?.validUntil)?.let { day(it, zone) }
                PlanSwitchCard(entry.action, true, "降级 · $PLAN_AT_EXPIRY",
                    "当前套餐用到期${until?.let { "（$it）" }.orEmpty()}，之后自动切换；已付费用不退款",
                    listOfNotNull(entry.chargeUnits?.let { "约 ${formatCoins(it)}" }, PLAN_AT_EXPIRY).joinToString(" · "), "预约降级")
            }
            PlanSwitchAction.Unavailable -> PlanSwitchCard(entry.action, false, "暂不可切换",
                entry.reasonCode?.let(::planSwitchErrorText)
                    ?: live.current?.let { "你当前的套餐有效期至 ${day(it.validUntil, zone)}，该操作暂不支持，请在到期后切换。" }
                    ?: "该操作暂不支持。", null, "立即购买")
        }
    }
}

/** The two lines above the card list: the active card and, while it exists, the scheduled (待生效) downgrade. */
internal data class PlanSwitchBanner(val current: String?, val pending: String?)

internal fun planSwitchBanner(state: PlanSwitchState?, offers: List<Offer>, now: Instant, zone: ZoneId = ZoneId.systemDefault()): PlanSwitchBanner? {
    val live = state?.liveAt(now) ?: return null
    val name = { ref: String -> offers.firstOrNull { it.offerRef == ref }?.title?.zh ?: "套餐" }
    val current = live.current?.let { "当前套餐：${name(it.offerRef)}，有效期至 ${day(it.validUntil, zone)}" }
    val pending = live.pending?.let {
        if (live.applying(now)) "$PLAN_APPLYING：已预约的降级正在应用，请稍候。"
        else "$PLAN_PENDING：${day(it.effectiveAt, zone)} 起切换为 ${name(it.toOfferRef)}。此前套餐和流量不变，已付费用不退款。"
    }
    return if (current == null && pending == null) null else PlanSwitchBanner(current, pending)
}

/**
 * The card the user chose and the quote the server priced must describe the same change. A quote that is not a plan
 * change although the card promised one (or the other way round) is never confirmable: it would charge the wrong thing.
 */
internal fun planSwitchQuoteIssue(card: PlanSwitchCard?, quote: Quote): String? {
    val priced = quote.planSwitch ?: return when (card?.action) {
        PlanSwitchAction.Upgrade, PlanSwitchAction.Downgrade -> "套餐状态已变化，请关闭后重新选择套餐。"
        else -> null
    }
    return when (card?.action) {
        null -> null
        PlanSwitchAction.CurrentRenew -> if (priced.kind == PlanSwitchKind.Renewal) null else CHANGED
        PlanSwitchAction.Upgrade -> if (priced.kind.isUpgrade) null else CHANGED
        PlanSwitchAction.Downgrade -> if (priced.kind == PlanSwitchKind.DowngradeScheduled) null else CHANGED
        PlanSwitchAction.Unavailable -> CHANGED
    }
}
private const val CHANGED = "套餐状态已变化，请关闭后重新选择套餐。"

/** Texts of the confirmation for a plan-change quote; the ordinary purchase dialog is untouched. */
internal data class PlanSwitchDialog(val title: String, val confirm: String, val lines: List<String>)

internal fun planSwitchDialog(quote: Quote, zone: ZoneId = ZoneId.systemDefault()): PlanSwitchDialog? {
    val change = quote.planSwitch ?: return null
    val target = quote.offer.title.zh
    val lines = mutableListOf<String>()
    val discounted = quote.amountUnits != change.chargeUnits
    when (change.kind) {
        PlanSwitchKind.Renewal -> {
            lines += "续费：$target"
            lines += "本次实扣：${formatCoins(quote.amountUnits)}"
        }
        PlanSwitchKind.UpgradeInPlace, PlanSwitchKind.UpgradeRestart -> {
            lines += "升级到：$target"
            lines += "本次实扣：${formatCoins(quote.amountUnits)}"
            if (discounted) lines += "补差价 ${formatCoins(change.chargeUnits)}，折扣后实扣 ${formatCoins(quote.amountUnits)}"
            lines += PLAN_NOW
            if (change.kind == PlanSwitchKind.UpgradeRestart) {
                lines += "从今天起重新计 365 天，旧卡剩余已折价抵扣"
                if (change.creditUnits > 0) lines += "折价抵扣：${formatCoins(change.creditUnits)}"
                lines += "已用流量不清零"
            } else lines += "到期日不变，已用流量不清零"
        }
        PlanSwitchKind.DowngradeScheduled -> {
            lines += "降级到：$target"
            lines += "本次实扣：${formatCoins(quote.amountUnits)}"
            lines += "$PLAN_AT_EXPIRY${change.effectiveAt?.let { "（${day(it, zone)}）" }.orEmpty()}"
            lines += "到期前当前套餐和流量不变"
        }
    }
    if (change.cancelsPendingDowngrade) lines += "已预约的降级将被取消，其已付费用折抵本次金额"
    lines += "升级立即生效、降级到期后生效，已支付的费用不退款。"
    return PlanSwitchDialog(
        when (change.kind) {
            PlanSwitchKind.Renewal -> "确认续费"
            PlanSwitchKind.DowngradeScheduled -> "确认预约降级"
            else -> "确认升级套餐"
        },
        when (change.kind) {
            PlanSwitchKind.Renewal -> "确认续费"
            PlanSwitchKind.DowngradeScheduled -> "确认预约降级"
            else -> "确认升级"
        }, lines)
}

/** Consumer sentence for a server code of the plan-change feature; null for codes this feature does not own. */
internal fun planSwitchErrorText(code: String): String? = when (code) {
    "plan_switch_already_scheduled" -> "已有待生效的降级，不能再预约一次降级。"
    "plan_switch_application_pending" -> PLAN_APPLYING_TEXT
    "plan_switch_authority_mode_unsupported", "plan_switch_authority_row_present", "plan_switch_commerce_model_unsupported" ->
        "当前账户暂不支持更换套餐，请到期后再购买。"
    "plan_switch_credit_exceeds_price" -> "旧卡剩余价值已不低于年卡价格，暂不能升级到年卡；请到期后再购买。"
    "plan_switch_precheck_unavailable" -> "暂时无法确认是否可以更换套餐，请稍后再查看。"
    "plan_switch_state_changed" -> "套餐状态已变化，请重新选择套餐后再确认。"
    "plan_switch_cycle_misaligned" -> "暂时不能升级到年卡：升级会改变已用流量的统计周期。可在当前套餐到期后购买年卡。"
    "plan_switch_upgrade_window_closed" -> "剩余有效期不足 60 分钟，暂不能升级；请先续费，或到期后再购买。"
    "offer_unavailable" -> PLAN_ACTIVE_TEXT
    else -> null
}

/** Order status line for a plan change; null leaves the ordinary status text. */
internal fun planSwitchOrderStatus(order: PurchaseResult.Order): String? {
    val change = order.planSwitch ?: return null
    val settled = order.paymentStatus == "captured" && order.fulfillmentStatus == "delivered"
    return when {
        settled && change.kind == PlanSwitchKind.DowngradeScheduled -> "降级已预约，$PLAN_AT_EXPIRY；请同步资源"
        settled && change.kind.isUpgrade -> "升级已完成，$PLAN_NOW；请同步资源"
        settled -> "续费已完成；请同步资源"
        // Capture-time cancellation (monthly to yearly: cycle, entitlement drift, expiry, pending downgrade) releases the hold uncharged.
        order.paymentStatus == "cancelled" -> "套餐变更已取消，Coins 预留已释放，未扣款"
        else -> null
    }
}
