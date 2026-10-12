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
        assertEquals("升级 · 补差价 5 Coins · 立即生效", cards.getValue(monthly100).chip)
        assertEquals("补差价 5 Coins · 立即生效", cards.getValue(monthly100).dockPrice)
        assertEquals("立即升级", cards.getValue(monthly100).dockAction)
        assertTrue(cards.getValue(monthly100).selectable)
    }

    @Test fun downgradeSaysItTakesEffectAtExpiryAndNamesTheDate() {
        val cards = planSwitchCards(state(offers = listOf(PlanSwitchOffer(monthly50, PlanSwitchAction.Downgrade, 20, 0, expiry, null))), now, utc)
        val card = cards.getValue(monthly50)
        assertEquals("降级 · 到期后生效", card.chip)
        assertTrue(card.note!!.contains("2026-10-30")); assertTrue(card.note!!.contains("不退款"))
        assertEquals("预约降级", card.dockAction)
    }

    @Test fun anUnavailableChangeIsNotSelectableAndExplainsWhyInPlainChinese() {
        val card = planSwitchCards(state(), now, utc).getValue(yearly)
        assertFalse(card.selectable)
        assertEquals("暂不可切换", card.chip)
        assertTrue(card.note!!.contains("年度套餐"))
        val unknown = planSwitchCards(state(offers = listOf(PlanSwitchOffer(yearly, PlanSwitchAction.Unavailable, null, null, null, null))), now, utc).getValue(yearly)
        assertTrue(unknown.note!!.contains("2026-10-30"))
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
        assertTrue("本次补差价：5 Coins" in upgrade.lines); assertTrue("立即生效" in upgrade.lines)
        assertTrue(upgrade.lines.any { it.contains("已用流量不清零") })
        val yearlyUpgrade = planSwitchDialog(quoteOf(PlanSwitch(PlanSwitchKind.UpgradeRestart, 8, 10, null, false)), utc)!!
        assertTrue(yearlyUpgrade.lines.any { it.contains("365 天") }); assertTrue("未使用时长已抵扣 8 Coins" in yearlyUpgrade.lines)
        val down = planSwitchDialog(quoteOf(PlanSwitch(PlanSwitchKind.DowngradeScheduled, 0, 10, expiry, false)), utc)!!
        assertEquals("确认预约降级", down.title)
        assertTrue("到期后生效（2026-10-30）" in down.lines); assertTrue(down.lines.any { it.contains("不变") })
        val renew = planSwitchDialog(quoteOf(PlanSwitch(PlanSwitchKind.Renewal, 0, 10, null, true)), utc)!!
        assertEquals("确认续费", renew.title); assertTrue(renew.lines.any { it.contains("已预约的降级将被取消") })
        assertNull(planSwitchDialog(quoteOf(null)))
    }

    @Test fun aDiscountedChargeShowsBothFigures() {
        val dialog = planSwitchDialog(quoteOf(PlanSwitch(PlanSwitchKind.UpgradeInPlace, 0, 10, null, false), amount = 9), utc)!!
        assertTrue("本次补差价：9 Coins" in dialog.lines)
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
        assertTrue(planSwitchErrorText("plan_switch_upgrade_window_closed")!!.contains("剩余有效期不足"))
        assertTrue(planSwitchErrorText("plan_switch_cycle_misaligned")!!.contains("年度套餐"))
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
        assertEquals("套餐变更已取消，Coins 预留已释放", status(PlanSwitchKind.UpgradeRestart, "cancelled", "not_requested"))
        assertNull(status(PlanSwitchKind.UpgradeInPlace, "held", "not_requested"))
        assertNull(planSwitchOrderStatus(order))
    }
}
