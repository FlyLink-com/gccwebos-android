package com.gccwebos.android.commerce

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PlanSwitchPresentationTest {
    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-15T00:00:00Z")
    private val expiry = Instant.parse("2026-10-30T00:00:00Z")
    private val monthly50 = "offer_plan_30d_50gb_monthly"
    private val monthly100 = "offer_plan_30d_100gb_monthly"
    private val yearly = "offer_plan_365d_200gb_monthly"
    private fun state(pending: PlanSwitchPending? = null, current: PlanSwitchCurrent? = PlanSwitchCurrent(monthly50, expiry),
        offers: List<PlanSwitchOffer> = listOf(
            PlanSwitchOffer(monthly50, PlanSwitchAction.CurrentRenew, 20, 0, null, null),
            PlanSwitchOffer(monthly100, PlanSwitchAction.Upgrade, 5, 0, null, null),
            PlanSwitchOffer(yearly, PlanSwitchAction.Unavailable, null, null, null, "plan_switch_cycle_misaligned"))) =
        PlanSwitchState(current, pending, offers)

    @Test fun upgradeShowsThePriceDifferenceAndTakesEffectNowWhileRenewIsPlain() {
        val cards = planSwitchCards(state(), now, utc)
        assertEquals("续费", cards.getValue(monthly50).chip)
        assertEquals("升级 · 补差价约 5 Coins · 立即生效", cards.getValue(monthly100).chip)
        assertEquals("补差价约 5 Coins · 立即生效", cards.getValue(monthly100).dockPrice)
        assertEquals("立即升级", cards.getValue(monthly100).dockAction)
        assertTrue(cards.getValue(monthly100).selectable)
    }

    @Test fun downgradeSaysItTakesEffectAtExpiryAndNamesTheDate() {
        val cards = planSwitchCards(state(offers = listOf(PlanSwitchOffer(monthly50, PlanSwitchAction.Downgrade, 20, 0, expiry, null))), now, utc)
        val card = cards.getValue(monthly50)
        assertEquals("降级 · 到期后生效", card.chip)
        assertTrue(card.note!!.contains("2026-10-30")); assertTrue(card.note!!.contains("不退款"))
        assertEquals("预约降级", card.dockAction)
        assertEquals("约 20 Coins · 到期后生效", card.dockPrice)
    }

    @Test fun anUnavailableChangeIsNotSelectableAndExplainsWhyInPlainChinese() {
        val card = planSwitchCards(state(), now, utc).getValue(yearly)
        assertFalse(card.selectable)
        assertEquals("暂不可切换", card.chip)
        assertTrue(card.note!!.contains("年卡"))
        // "unavailable" with no reason means the tier is not in the plan-change table: an ordinary purchase, no card label.
        assertFalse(yearly in planSwitchCards(state(offers = listOf(PlanSwitchOffer(yearly, PlanSwitchAction.Unavailable, null, null, null, null))), now, utc))
        // A refused offer of the same table says a card is active, not "switch later".
        val active = planSwitchCards(state(offers = listOf(PlanSwitchOffer(yearly, PlanSwitchAction.Unavailable, null, null, null, "offer_unavailable"))), now, utc).getValue(yearly)
        assertFalse(active.selectable); assertEquals("当前套餐生效中，暂不能购买其他套餐。", active.note)
        val renewWithPending = planSwitchCards(state(pending = PlanSwitchPending(monthly50, expiry)), now, utc).getValue(monthly50)
        assertEquals("续费会取消已预约的降级", renewWithPending.note)
    }

    @Test fun withoutAStateOrAfterItsDeadlineTheOrdinaryCatalogShows() {
        assertTrue(planSwitchCards(null, now, utc).isEmpty())
        assertTrue(planSwitchCards(state(), Instant.parse("2026-10-30T00:00:01Z"), utc).isEmpty())
        assertNull(planSwitchBanner(null, emptyList(), now, utc))
        // A named plan-change reason still explains itself even with no live card.
        val blocked = state(current = null, offers = listOf(PlanSwitchOffer(monthly100, PlanSwitchAction.Unavailable, null, null, null, "plan_switch_precheck_unavailable")))
        assertTrue(planSwitchCards(blocked, now, utc).getValue(monthly100).note!!.contains("暂时无法确认"))
        val ordinary = state(current = null, offers = listOf(PlanSwitchOffer(monthly100, PlanSwitchAction.Unavailable, null, null, null, null)))
        assertTrue(planSwitchCards(ordinary, now, utc).isEmpty())
    }

    private fun offer(ref: String, title: String) = CommerceJson.offers(CommerceFixtures.offerBody).single().copy(offerRef = ref, title = LocalizedText(title, title))

    @Test fun theBannerShowsTheCardAndAScheduledDowngradeAsPending() {
        val offers = listOf(offer(monthly50, "50 GB 月卡"), offer(monthly100, "100 GB 月卡"))
        val banner = planSwitchBanner(state(pending = PlanSwitchPending(monthly50, expiry)), offers, now, utc)!!
        assertEquals("当前套餐：50 GB 月卡，有效期至 2026-10-30", banner.current)
        assertTrue(banner.pending!!.startsWith("待生效：2026-10-30 起切换为 50 GB 月卡"))
        // A pending switch past its date is never shown as pending.
        assertNull(planSwitchBanner(state(pending = PlanSwitchPending(monthly50, Instant.parse("2026-10-14T00:00:00Z"))), offers, now, utc)!!.pending)
    }

    private fun quoteOf(change: PlanSwitch?, amount: Long = change?.chargeUnits ?: 10): Quote =
        CommerceJson.quote(CommerceFixtures.quote, CommerceModel.LEGACY).copy(planSwitch = change, amountUnits = amount)

    @Test fun theConfirmationNamesWhatHappensAndWhen() {
        val upgrade = planSwitchDialog(quoteOf(PlanSwitch(PlanSwitchKind.UpgradeInPlace, 0, 5, null, false)), utc)!!
        assertEquals("确认升级套餐", upgrade.title); assertEquals("确认升级", upgrade.confirm)
        assertTrue("本次实扣：5 Coins" in upgrade.lines); assertTrue("立即生效" in upgrade.lines)
        assertTrue("到期日不变，已用流量不清零" in upgrade.lines)
        val yearlyUpgrade = planSwitchDialog(quoteOf(PlanSwitch(PlanSwitchKind.UpgradeRestart, 8, 10, null, false)), utc)!!
        assertTrue("从今天起重新计 365 天，旧卡剩余已折价抵扣" in yearlyUpgrade.lines); assertTrue("折价抵扣：8 Coins" in yearlyUpgrade.lines)
        assertTrue("已用流量不清零" in yearlyUpgrade.lines); assertFalse(yearlyUpgrade.lines.any { it.contains("到期日不变") })
        val down = planSwitchDialog(quoteOf(PlanSwitch(PlanSwitchKind.DowngradeScheduled, 0, 10, expiry, false)), utc)!!
        assertEquals("确认预约降级", down.title)
        assertTrue("到期后生效（2026-10-30）" in down.lines); assertTrue(down.lines.any { it.contains("不变") })
        val renew = planSwitchDialog(quoteOf(PlanSwitch(PlanSwitchKind.Renewal, 0, 10, null, true)), utc)!!
        assertEquals("确认续费", renew.title); assertTrue(renew.lines.any { it.contains("已预约的降级将被取消") })
        assertNull(planSwitchDialog(quoteOf(null)))
    }

    @Test fun aDiscountedChargeShowsBothFigures() {
        val dialog = planSwitchDialog(quoteOf(PlanSwitch(PlanSwitchKind.UpgradeInPlace, 0, 10, null, false), amount = 9), utc)!!
        assertTrue("本次实扣：9 Coins" in dialog.lines)
        assertTrue(dialog.lines.any { it.contains("10 Coins") && it.contains("9 Coins") })
    }

    @Test fun noDialogLineOrErrorTextEverShowsAHostOrAReference() {
        val kinds = listOf(PlanSwitch(PlanSwitchKind.UpgradeRestart, 3, 10, null, true), PlanSwitch(PlanSwitchKind.DowngradeScheduled, 0, 10, expiry, false))
        val texts = kinds.flatMap { planSwitchDialog(quoteOf(it), utc)!!.lines } +
            PlanSwitchRejections.mapNotNull(::planSwitchErrorText) + listOfNotNull(planSwitchCards(state(), now, utc).values.joinToString { it.chip + it.note })
        assertEquals(PlanSwitchRejections.size, PlanSwitchRejections.mapNotNull(::planSwitchErrorText).size)
        val forbidden = Regex("https?://|\\.com|\\.cn|offer_|quote_|order_|plan_switch", RegexOption.IGNORE_CASE)
        texts.forEach { assertFalse(it, forbidden.containsMatchIn(it)) }
    }

    @Test fun everyPlanChangeCodeHasPlainWordingAndOtherCodesAreLeftAlone() {
        assertTrue(planSwitchErrorText("plan_switch_upgrade_window_closed")!!.contains("剩余有效期不足 60 分钟"))
        assertTrue(planSwitchErrorText("plan_switch_upgrade_window_closed")!!.contains("续费"))
        assertTrue(planSwitchErrorText("plan_switch_cycle_misaligned")!!.contains("年卡"))
        assertTrue(planSwitchErrorText("plan_switch_credit_exceeds_price")!!.contains("年卡"))
        assertEquals("正在应用已预约的降级，请稍后。", planSwitchErrorText("plan_switch_application_pending"))
        assertEquals("当前套餐生效中，暂不能购买其他套餐。", planSwitchErrorText("offer_unavailable"))
        assertTrue(planSwitchErrorText("plan_switch_state_changed")!!.contains("重新选择"))
        assertNull(planSwitchErrorText("quote_expired")); assertNull(planSwitchErrorText("anything_else"))
    }

    @Test fun aQuoteThatIsNotTheChangeTheCardPromisedIsNeverConfirmable() {
        val cards = planSwitchCards(state(), now, utc)
        val upgradeQuote = quoteOf(PlanSwitch(PlanSwitchKind.UpgradeInPlace, 0, 5, null, false))
        assertNull(planSwitchQuoteIssue(cards.getValue(monthly100), upgradeQuote))
        // The card promised an upgrade, but the server priced a plain purchase (full price) or a downgrade.
        assertNotNull(planSwitchQuoteIssue(cards.getValue(monthly100), quoteOf(null)))
        assertNotNull(planSwitchQuoteIssue(cards.getValue(monthly100), quoteOf(PlanSwitch(PlanSwitchKind.DowngradeScheduled, 0, 10, expiry, false))))
        assertNotNull(planSwitchQuoteIssue(cards.getValue(yearly), upgradeQuote))
        // Renewal may be an ordinary quote; with no card at all the quote alone is authoritative.
        assertNull(planSwitchQuoteIssue(cards.getValue(monthly50), quoteOf(null)))
        assertNull(planSwitchQuoteIssue(null, upgradeQuote)); assertNull(planSwitchQuoteIssue(null, quoteOf(null)))
    }

    @Test fun planChangeOrdersReadInPlainWords() {
        val order = CommerceJson.purchaseResult(CommerceFixtures.order, CommerceModel.LEGACY) as PurchaseResult.Order
        fun status(kind: PlanSwitchKind, payment: String, fulfillment: String) = planSwitchOrderStatus(order.copy(
            paymentStatus = payment, fulfillmentStatus = fulfillment, planSwitch = PlanSwitch(kind, 0, 10, null, false)))
        assertTrue(status(PlanSwitchKind.UpgradeInPlace, "captured", "delivered")!!.contains("立即生效"))
        assertTrue(status(PlanSwitchKind.DowngradeScheduled, "captured", "delivered")!!.contains("到期后生效"))
        assertEquals("套餐变更已取消，Coins 预留已释放，未扣款", status(PlanSwitchKind.UpgradeRestart, "cancelled", "not_requested"))
        assertNull(status(PlanSwitchKind.UpgradeInPlace, "held", "not_requested"))
        assertNull(planSwitchOrderStatus(order))
    }

    @Test fun aDowngradePastItsDateShowsProcessingAndLocksEveryCardUntilTheServerClearsIt() {
        val applying = state(pending = PlanSwitchPending(monthly50, expiry), offers = listOf(
            PlanSwitchOffer(monthly50, PlanSwitchAction.Unavailable, null, null, null, "plan_switch_application_pending"),
            PlanSwitchOffer(monthly100, PlanSwitchAction.Unavailable, null, null, null, "plan_switch_application_pending")))
        val after = expiry.plusSeconds(30)
        assertTrue(applying.applying(after)); assertFalse(applying.applying(expiry.minusSeconds(1)))
        // The current card has expired by now, yet the state is kept (not dropped as overdue) and every card says 处理中.
        val cards = planSwitchCards(applying, after, utc, shownOfferRefs = listOf(yearly))
        assertEquals(setOf(monthly50, monthly100, yearly), cards.keys)
        cards.values.forEach { assertEquals("处理中", it.chip); assertFalse(it.selectable); assertEquals("正在应用已预约的降级，请稍后。", it.note) }
        assertEquals("处理中：已预约的降级正在应用，请稍候。", planSwitchBanner(applying, emptyList(), after, utc)!!.pending)
        // Even when the server has not (yet) flagged the offers, an overdue pending switch locks them.
        val unflagged = applying.copy(offers = listOf(PlanSwitchOffer(monthly100, PlanSwitchAction.Upgrade, 5, 0, null, null)))
        assertFalse(planSwitchCards(unflagged, after, utc).getValue(monthly100).selectable)
    }

    @Test fun anApplyingStateThatNeverClearsIsDroppedAfterFifteenMinutes() {
        val applying = state(pending = PlanSwitchPending(monthly50, expiry))
        assertTrue(applying.applying(expiry.plusSeconds(14 * 60)))
        assertFalse(applying.applying(expiry.plusSeconds(15 * 60)))
        assertTrue(planSwitchCards(applying, expiry.plusSeconds(15 * 60), utc).isEmpty())
    }
}
