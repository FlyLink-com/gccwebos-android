package com.gccwebos.android.commerce

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Synthetic fixtures of the server's plan-change projections (quote.planSwitch, order.planSwitch, plan-switch/state). */
class PlanSwitchJsonTest {
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected the projection to be rejected") } catch (_: IllegalArgumentException) { } }
    private val switchObject = """{"kind":"upgrade_inplace","creditUnits":0,"chargeUnits":5,"effectiveAt":null,"cancelsPendingDowngrade":false}"""

    /** The fixture quote with a 5-Coin price difference: 10 Coins list price, 20 available, 5 held. */
    private fun quoteWith(plan: String? = switchObject, amount: Int = 5): String = JSONObject(CommerceFixtures.quote).also {
        val quote = it.getJSONObject("quote")
        quote.put("amountUnits", amount)
        quote.getJSONArray("allocation").getJSONObject(0).put("amountUnits", amount)
        quote.getJSONObject("balances").getJSONObject("after").put("paidCoins", 20 - amount).put("heldCoins", amount)
        quote.put("planSwitch", plan?.let(::JSONObject) ?: JSONObject.NULL)
    }.toString()

    @Test fun anUpgradeQuoteIsPricedFromTheServersDifferenceNotTheListPrice() {
        val quote = CommerceJson.quote(quoteWith(), CommerceModel.LEGACY)
        assertEquals(5L, quote.amountUnits)
        val change = requireNotNull(quote.planSwitch)
        assertEquals(PlanSwitchKind.UpgradeInPlace, change.kind); assertEquals(5L, change.chargeUnits)
        assertNull(change.effectiveAt); assertFalse(change.cancelsPendingDowngrade)
        assertNull(CommerceJson.quote(CommerceFixtures.quote, CommerceModel.LEGACY).planSwitch)
    }

    @Test fun theAccountDiscountStillAppliesToTheChangeCharge() {
        // 10 Coins of difference at 10 % off is 9 Coins; the amount must equal the discounted change charge.
        val plan = """{"kind":"upgrade_restart","creditUnits":3,"chargeUnits":10,"effectiveAt":null,"cancelsPendingDowngrade":false}"""
        val body = JSONObject(quoteWith(plan, 9)).also { it.getJSONObject("quote").put("appliedDiscountBasisPoints", 1000) }.toString()
        val quote = CommerceJson.quote(body, CommerceModel.LEGACY)
        assertEquals(9L, quote.amountUnits); assertEquals(3L, quote.planSwitch!!.creditUnits)
        rejects { CommerceJson.quote(JSONObject(body).also { it.getJSONObject("quote").put("appliedDiscountBasisPoints", 0) }.toString(), CommerceModel.LEGACY) }
    }

    @Test fun aQuoteWhoseAmountDisagreesWithItsChangeChargeFailsClosed() {
        rejects { CommerceJson.quote(quoteWith(amount = 6), CommerceModel.LEGACY) }
        rejects { CommerceJson.quote(quoteWith(switchObject.replace("\"chargeUnits\":5", "\"chargeUnits\":4")), CommerceModel.LEGACY) }
    }

    @Test fun aChangeChargeAboveTheListPriceOrMalformedChangeObjectsAreRejected() {
        rejects { CommerceJson.quote(quoteWith(switchObject.replace("\"chargeUnits\":5", "\"chargeUnits\":11"), 11), CommerceModel.LEGACY) }
        rejects { CommerceJson.quote(quoteWith(switchObject.replace("upgrade_inplace", "free_upgrade")), CommerceModel.LEGACY) }
        rejects { CommerceJson.quote(quoteWith(switchObject.replace("\"chargeUnits\":5", "\"chargeUnits\":0"), 5), CommerceModel.LEGACY) }
        rejects { CommerceJson.quote(quoteWith(switchObject.replace("\"cancelsPendingDowngrade\":false", "\"cancelsPendingDowngrade\":\"no\"")), CommerceModel.LEGACY) }
        rejects { CommerceJson.quote(quoteWith(switchObject.dropLast(1) + ""","extra":1}"""), CommerceModel.LEGACY) }
        rejects { CommerceJson.quote(quoteWith(null), CommerceModel.LEGACY) }
    }

    @Test fun onlyADowngradeCarriesItsTakeEffectTime() {
        val downgrade = """{"kind":"downgrade_scheduled","creditUnits":0,"chargeUnits":10,"effectiveAt":"2026-10-30T00:00:00.000Z","cancelsPendingDowngrade":false}"""
        val quote = CommerceJson.quote(quoteWith(downgrade, 10), CommerceModel.LEGACY)
        assertEquals(Instant.parse("2026-10-30T00:00:00Z"), quote.planSwitch!!.effectiveAt)
        rejects { CommerceJson.quote(quoteWith(downgrade.replace("\"2026-10-30T00:00:00.000Z\"", "null"), 10), CommerceModel.LEGACY) }
        rejects { CommerceJson.quote(quoteWith(switchObject.replace("\"effectiveAt\":null", "\"effectiveAt\":\"2026-10-30T00:00:00.000Z\"")), CommerceModel.LEGACY) }
    }

    @Test fun theWalletFirstModelNeverAcceptsAPlanChangeObject() {
        rejects { CommerceJson.quote(JSONObject(CommerceFixtures.deferredQuote).also { it.getJSONObject("quote").put("planSwitch", JSONObject(switchObject)) }.toString(), CommerceModel.WALLET_FIRST) }
    }

    @Test fun anOrderKeepsItsPlanChangeAndStillRejectsUnknownFields() {
        val order = JSONObject(CommerceFixtures.order).also {
            val value = it.getJSONObject("order"); value.put("amountUnits", 5)
            value.getJSONArray("allocation").getJSONObject(0).put("amountUnits", 5)
            value.put("planSwitch", JSONObject(switchObject))
        }.toString()
        val result = CommerceJson.purchaseResult(order, CommerceModel.LEGACY) as PurchaseResult.Order
        assertEquals(PlanSwitchKind.UpgradeInPlace, result.planSwitch!!.kind)
        assertNull((CommerceJson.purchaseResult(CommerceFixtures.order, CommerceModel.LEGACY) as PurchaseResult.Order).planSwitch)
        rejects { CommerceJson.purchaseResult(JSONObject(order).also { it.getJSONObject("order").put("surprise", true) }.toString(), CommerceModel.LEGACY) }
    }

    private val state = """{"current":{"offerRef":"offer_plan_30d_50gb_monthly","validUntil":"2026-10-30T00:00:00.000Z"},
        "pendingSwitch":null,"offers":[
        {"offerRef":"offer_plan_30d_50gb_monthly","action":"current_renew","chargeUnits":20,"creditUnits":0,"effectiveAt":null,"reasonCode":null},
        {"offerRef":"offer_plan_30d_100gb_monthly","action":"upgrade","chargeUnits":5,"creditUnits":0,"effectiveAt":null,"reasonCode":null},
        {"offerRef":"offer_plan_365d_200gb_monthly","action":"unavailable","chargeUnits":null,"creditUnits":null,"effectiveAt":null,"reasonCode":"plan_switch_cycle_misaligned"}]}"""

    @Test fun theStateProjectionReadsEveryActionAndKeepsReasonCodesAsText() {
        val parsed = CommerceJson.planSwitchState(state)
        assertEquals(Instant.parse("2026-10-30T00:00:00Z"), parsed.current!!.validUntil)
        assertNull(parsed.pending)
        assertEquals(listOf(PlanSwitchAction.CurrentRenew, PlanSwitchAction.Upgrade, PlanSwitchAction.Unavailable), parsed.offers.map { it.action })
        assertEquals(5L, parsed.offers[1].chargeUnits)
        assertEquals("plan_switch_cycle_misaligned", parsed.offers[2].reasonCode)
        val scheduled = state.replace("\"pendingSwitch\":null", """"pendingSwitch":{"toOfferRef":"offer_plan_30d_50gb_monthly","effectiveAt":"2026-10-30T00:00:00.000Z"}""")
        assertEquals("offer_plan_30d_50gb_monthly", CommerceJson.planSwitchState(scheduled).pending!!.toOfferRef)
    }

    @Test fun aMalformedStateIsRejectedSoTheCatalogFallsBackToOrdinary() {
        rejects { CommerceJson.planSwitchState(state.replace("\"action\":\"upgrade\"", "\"action\":\"gift\"")) }
        rejects { CommerceJson.planSwitchState(state.replace("\"chargeUnits\":5", "\"chargeUnits\":null")) }
        rejects { CommerceJson.planSwitchState(state.replace("\"offers\":[", "\"surprise\":1,\"offers\":[")) }
        rejects { CommerceJson.planSwitchState(state.replace("2026-10-30T00:00:00.000Z", "tomorrow")) }
        rejects { CommerceJson.planSwitchState(state.replace("plan_switch_cycle_misaligned", "Not A Code")) }
        rejects { CommerceJson.planSwitchState(state.replace("offer_plan_30d_100gb_monthly\",\"action\":\"upgrade", "offer_plan_30d_50gb_monthly\",\"action\":\"upgrade")) }
        rejects { CommerceJson.planSwitchState("[]") }
    }
}
