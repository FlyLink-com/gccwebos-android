package com.gccwebos.android.commerce

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CommerceJsonTest {
    private val fixture = CommerceFixtures
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected malformed response to be rejected") }
        catch (_: IllegalArgumentException) { }
    }
    private fun changed(body: String, edit: (JSONObject) -> Unit) = JSONObject(body).also(edit).toString()
    private fun mutateQuote(edit: (JSONObject) -> Unit) = changed(fixture.quote) { edit(it.getJSONObject("quote")) }
    private fun mutateRecharge(edit: (JSONObject) -> Unit) = changed(fixture.rechargeOrder) { edit(it.getJSONObject("recharge").getJSONObject("order")) }

    @Test fun serverModelSelectsOnlyVerifiedRouteFamilies() {
        assertEquals(CommerceModel.LEGACY, CommerceJson.model(fixture.legacyModel))
        assertEquals(CommerceModel.WALLET_FIRST, CommerceJson.model(fixture.walletFirstModel))
        assertEquals("/api/portal/commerce/orders", CommerceModel.LEGACY.purchasePath)
        assertEquals("/api/portal/commerce/deferred/transitions", CommerceModel.WALLET_FIRST.purchasePath)
        rejects { CommerceJson.model("""{"commerceModel":"wallet_first"}""") }
        rejects { CommerceJson.model("""{"commerceModel":"legacy","fallback":true}""") }
    }

    @Test fun plansUseActualDurationQuotaNoticesAndIntegerCoins() {
        val offer = CommerceJson.offers(fixture.offerBody).single()
        assertEquals("测试套餐", offer.title.zh)
        assertEquals(30L, offer.durationDays)
        assertEquals(50L, offer.monthlyTrafficGb)
        assertEquals(listOf("仅用于离线测试"), offer.importantNotices.zh)
        assertEquals(10L, offer.baseCoinUnits)
        assertEquals("10 Coins", formatCoins(offer.baseCoinUnits))
        assertEquals("CNY 10.00", formatMoney(1000, "CNY", 2))
        assertEquals("CNY 90071992547409.91", formatMoney(MAX_SAFE_COIN_UNITS, "CNY", 2))
        assertEquals("JPY 10", formatMoney(10, "JPY", 0))
        assertEquals("BHD 0.001", formatMoney(1, "BHD", 3))
    }

    @Test fun validDataPacksAreExcludedButMalformedDataPacksAreNotSilentlySkipped() {
        val pack = JSONObject(fixture.planOffer).put("category", "data_pack")
        pack.put("delivery", JSONObject("""{"kind":"data_pack","durationDays":null,"monthlyTrafficGb":null,"trafficGb":5,"neverExpires":true,"discountBasisPoints":null}"""))
        val catalog = JSONObject().put("offers", JSONArray().put(JSONObject(fixture.planOffer)).put(pack))
        assertEquals(1, CommerceJson.offers(catalog.toString()).size)
        pack.getJSONObject("delivery").put("durationDays", 30)
        rejects { CommerceJson.offers(catalog.toString()) }
        rejects { CommerceJson.offers(fixture.offerBody.replace("\"plan\"", "\"unknown_plan\"")) }
    }

    @Test fun walletChecksTotalsAndZeroNotCreatedProjection() {
        assertEquals(20L, CommerceJson.wallet(fixture.wallet).spendableUnits)
        rejects { CommerceJson.wallet(fixture.wallet.replace("\"spendableUnits\":20", "\"spendableUnits\":21")) }
        rejects { CommerceJson.wallet(fixture.wallet.replace("\"active\"", "\"not_created\"")) }
        val empty = changed(fixture.wallet) {
            val wallet = it.getJSONObject("wallet").put("status", "not_created").put("version", 0).put("spendableUnits", 0)
            wallet.getJSONObject("balances").put("paidCoins", 0)
        }
        assertEquals("not_created", CommerceJson.wallet(empty).status)
        rejects { CommerceJson.wallet(fixture.wallet.replace("\"active\"", "\"unknown\"")) }
    }

    @Test fun integersRejectNullStringBooleanFractionNegativeAndUnsafeValues() {
        for (bad in listOf("null", "\"20\"", "true", "20.5", "-1", "9007199254740992", "9223372036854775808", "NaN", "Infinity")) {
            rejects { CommerceJson.wallet(fixture.wallet.replace("\"paidCoins\":20", "\"paidCoins\":$bad")) }
        }
        val largest = fixture.wallet.replace("\"paidCoins\":20", "\"paidCoins\":$MAX_SAFE_COIN_UNITS")
            .replace("\"spendableUnits\":20", "\"spendableUnits\":$MAX_SAFE_COIN_UNITS")
        assertEquals(MAX_SAFE_COIN_UNITS, CommerceJson.wallet(largest).paidCoins)
        rejects { CommerceJson.wallet(largest.replace("\"bonusCoins\":0", "\"bonusCoins\":1")) }
        assertEquals(20L, CommerceJson.wallet(fixture.wallet.replace("\"paidCoins\":20", "\"paidCoins\":2e1")).paidCoins)
        rejects { CommerceJson.wallet(fixture.wallet.replace("\"paidCoins\":20", "\"paidCoins\":20.000000000000000001")) }
    }

    @Test fun strictSyntaxRejectsDuplicateKeysLenientSyntaxAndTrailingContent() {
        for (body in listOf(
            """{"commerceModel":"legacy","commerceModel":"wallet_first_unique_v1"}""",
            """{'commerceModel':'legacy'}""", """{commerceModel:"legacy"}""",
            """{"commerceModel":"legacy",}""", """{"commerceModel":"legacy"} {}""",
            """{/*comment*/"commerceModel":"legacy"}""", """["legacy"]""",
        )) rejects { CommerceJson.model(body) }
        rejects { CommerceJson.wallet(fixture.wallet.replace("\"version\":1", "\"version\":01")) }
    }

    @Test fun offersRequireExactNestedSchemaCoinOrderingAndPositiveDelivery() {
        for (body in listOf(
            fixture.offerBody.replace("\"durationDays\":30", "\"durationDays\":null"),
            fixture.offerBody.replace("\"monthlyTrafficGb\":50", "\"monthlyTrafficGb\":0"),
            fixture.offerBody.replace("\"bonus_coins\",\"paid_coins\"", "\"paid_coins\",\"bonus_coins\""),
            fixture.offerBody.replace("\"discountBasisPoints\":0", "\"discountBasisPoints\":10001"),
            fixture.offerBody.replace("\"trafficGb\":null", "\"trafficGb\":0"),
            fixture.offerBody.replace("\"neverExpires\":false", "\"neverExpires\":\"false\""),
        )) rejects { CommerceJson.offers(body) }
    }

    @Test fun availableQuoteChecksPriceAllocationBalanceMovementAndExpiry() {
        val quote = CommerceJson.quote(fixture.quote, CommerceModel.LEGACY, "offer_fixture_plan")
        assertTrue(quote.purchasableAt(Instant.parse(fixture.instant)))
        assertFalse(quote.purchasableAt(Instant.parse(fixture.expiresAt)))
        assertNull(quote.transition)
        rejects { CommerceJson.quote(fixture.quote, CommerceModel.LEGACY, "offer_different") }
        for (field in listOf("amountUnits", "shortfallUnits")) {
            rejects { CommerceJson.quote(mutateQuote { it.put(field, 1) }, CommerceModel.LEGACY) }
        }
        rejects { CommerceJson.quote(mutateQuote { it.put("appliedDiscountBasisPoints", 1000) }, CommerceModel.LEGACY) }
        rejects { CommerceJson.quote(mutateQuote { it.getJSONObject("balances").getJSONObject("after").put("heldCoins", 0) }, CommerceModel.LEGACY) }
        rejects { CommerceJson.quote(mutateQuote { it.getJSONArray("allocation").getJSONObject(0).put("amountUnits", 9) }, CommerceModel.LEGACY) }
        rejects { CommerceJson.quote(mutateQuote { it.put("purchaseAvailability", "free") }, CommerceModel.LEGACY) }
    }

    @Test fun insufficientQuoteRequiresUnchangedBalancesEmptyAllocationAndCorrectShortfall() {
        val insufficient = mutateQuote {
            it.put("purchaseAvailability", "insufficient_balance").put("shortfallUnits", 7).put("allocation", JSONArray())
            it.getJSONObject("balances").put("before", JSONObject("""{"bonusCoins":0,"paidCoins":3,"rewardCoins":0,"heldCoins":0}"""))
                .put("after", JSONObject("""{"bonusCoins":0,"paidCoins":3,"rewardCoins":0,"heldCoins":0}"""))
        }
        val quote = CommerceJson.quote(insufficient, CommerceModel.LEGACY)
        assertEquals(7L, quote.shortfallUnits)
        assertFalse(quote.purchasableAt(Instant.parse(fixture.instant)))
        rejects { CommerceJson.quote(insufficient.replace("\"shortfallUnits\":7", "\"shortfallUnits\":6"), CommerceModel.LEGACY) }
    }

    @Test fun discountsUseExactCeilingWithoutOverflow() {
        val body = mutateQuote {
            it.getJSONObject("offer").put("baseCoinUnits", MAX_SAFE_COIN_UNITS)
            it.put("amountUnits", 900719925475L).put("appliedDiscountBasisPoints", 9999L)
            it.getJSONArray("allocation").getJSONObject(0).put("amountUnits", 900719925475L)
            it.getJSONObject("balances").getJSONObject("before").put("paidCoins", 900719925475L)
            it.getJSONObject("balances").getJSONObject("after").put("paidCoins", 0).put("heldCoins", 900719925475L)
        }
        assertEquals(900719925475L, CommerceJson.quote(body, CommerceModel.LEGACY).amountUnits)
    }

    @Test fun deferredQuoteCannotBeUsedAsLegacyOrLoseItsTransition() {
        val quote = CommerceJson.quote(fixture.deferredQuote, CommerceModel.WALLET_FIRST)
        assertEquals("initial_activation", quote.transition!!.flowKind)
        assertEquals(Instant.parse(fixture.instant), quote.transition.scheduledFor)
        rejects { CommerceJson.quote(fixture.deferredQuote, CommerceModel.LEGACY) }
        rejects { CommerceJson.quote(fixture.quote, CommerceModel.WALLET_FIRST) }
        rejects { CommerceJson.quote(fixture.deferredQuote.replace("initial_activation", "activate_now"), CommerceModel.WALLET_FIRST) }
    }

    @Test fun timestampsRequireCanonicalRealUtcMillisecondInstants() {
        for (bad in listOf("2026-09-30T00:10:00Z", "2026-09-30T00:10:00.000+00:00", "2026-02-30T00:10:00.000Z", "2026-09-30T00:10:60.000Z", "tomorrow")) {
            rejects { CommerceJson.quote(fixture.quote.replace(fixture.expiresAt, bad), CommerceModel.LEGACY) }
        }
    }

    @Test fun rechargeOffersPreserveVersionAndSortedPaymentMethodsAndLegacyNoMethods() {
        val offer = CommerceJson.rechargeOffers(fixture.rechargeOffers).single()
        assertEquals(listOf("alipay", "wechat_pay"), offer.paymentMethods)
        assertEquals(1L, offer.quoteVersion)
        val v1 = changed(fixture.rechargeOffers) { it.getJSONArray("offers").getJSONObject(0).remove("paymentMethods") }
        assertTrue(CommerceJson.rechargeOffers(v1).single().paymentMethods.isEmpty())
        for (methods in listOf("[]", "[\"unknown\"]", "[\"alipay\",\"alipay\"]", "[\"wechat_pay\",\"alipay\"]")) {
            rejects { CommerceJson.rechargeOffers(fixture.rechargeOffers.replace("[\"alipay\",\"wechat_pay\"]", methods)) }
        }
        rejects { CommerceJson.rechargeOffers(fixture.rechargeOffers.replace("\"currencyExponent\":2", "\"currencyExponent\":10")) }
        rejects { CommerceJson.rechargeOffers(fixture.rechargeOffers.replace("\"currency\":\"CNY\"", "\"currency\":\"cny\"")) }
    }

    @Test fun rechargeRedirectUsesVerifiedAdapterOriginAndTransientRedactedRepresentation() {
        val order = CommerceJson.rechargeOrder(fixture.rechargeOrder, "recharge_fixture_order")
        assertTrue(order.payableAt(Instant.parse(fixture.instant)))
        assertFalse(order.payableAt(Instant.parse(fixture.expiresAt)))
        assertFalse(order.toString().contains("fixture=synthetic"))
        val paypal = mutateRecharge {
            it.put("adapterKey", "paypal_v1").put("paymentMethod", "paypal")
            it.getJSONObject("nextAction").put("url", "https://www.sandbox.paypal.com/checkout?token=synthetic")
        }
        assertNotNull(CommerceJson.rechargeOrder(paypal).nextAction)
        rejects { CommerceJson.rechargeOrder(fixture.rechargeOrder, "recharge_other") }
        rejects { CommerceJson.rechargeOrder(mutateRecharge { it.put("adapterKey", "unknown_v1") }) }
        rejects { CommerceJson.rechargeOrder(mutateRecharge { it.put("status", "success") }) }
        rejects { CommerceJson.rechargeOrder(mutateRecharge { it.getJSONObject("nextAction").put("type", "deeplink") }) }
    }

    @Test fun unsafeRedirectsNeverReachPaymentNavigation() {
        for (url in listOf(
            "", "http://www.xsd8866.com/pay", "https://www.xsd8866.com.evil.invalid/pay",
            "https://www.xsd8866.com@evil.invalid/pay", "https://user@www.xsd8866.com/pay",
            "https://www.xsd8866.com/pay#fragment", "https://www.xsd8866.com:8443/pay",
            "https://www.xsd8866.com./pay", "https://localhost/pay", "https://127.0.0.1/pay",
            "https://[::1]/pay", "javascript:alert(1)", "alipays://platformapi/startapp",
            "https://www.sandbox.paypal.com/pay", "https://www.xsd8866.com/支付", "https://www.xsd8866.com/\nfoo",
        )) {
            rejects { CommerceJson.rechargeOrder(mutateRecharge { it.getJSONObject("nextAction").put("url", url) }) }
        }
    }

    @Test fun rechargeCreditIsSeparateFromVerifiedPaymentAndReconciliationIsNotTerminal() {
        val verified = CommerceJson.rechargeOrder(mutateRecharge { it.put("status", "payment_verified").put("nextAction", JSONObject.NULL) })
        assertFalse(verified.terminal)
        assertFalse(verified.payableAt(Instant.parse(fixture.instant)))
        assertFalse(CommerceJson.rechargeOrder(mutateRecharge { it.put("status", "reconciliation_required") }).terminal)
        for (state in listOf("credited", "failed_no_charge", "expired")) {
            assertTrue(CommerceJson.rechargeOrder(mutateRecharge { it.put("status", state).put("nextAction", JSONObject.NULL) }).terminal)
        }
    }

    @Test fun purchaseKeepsPaymentAndFulfillmentStatesSeparateAndRejectsImpossiblePairings() {
        val held = CommerceJson.purchaseResult(fixture.order, CommerceModel.LEGACY) as PurchaseResult.Order
        assertEquals("held", held.paymentStatus)
        assertEquals("not_requested", held.fulfillmentStatus)
        assertEquals("order_fixture_plan", held.ref)
        val captured = changed(fixture.order) {
            it.getJSONObject("order").put("paymentStatus", "captured").getJSONObject("fulfillment").put("status", "pending")
        }
        assertEquals("pending", (CommerceJson.purchaseResult(captured, CommerceModel.LEGACY) as PurchaseResult.Order).fulfillmentStatus)
        rejects { CommerceJson.purchaseResult(fixture.order.replace("not_requested", "delivered"), CommerceModel.LEGACY) }
        rejects { CommerceJson.purchaseResult(fixture.order.replace("\"held\"", "\"captured\""), CommerceModel.LEGACY) }
        rejects { CommerceJson.purchaseResult(fixture.order.replace("\"held\"", "\"unknown\""), CommerceModel.LEGACY) }
        rejects { CommerceJson.purchaseResult(captured.replace("pending", "failed"), CommerceModel.LEGACY) }
        rejects { CommerceJson.purchaseResult(fixture.order, CommerceModel.LEGACY, "order_other") }
    }

    @Test fun deferredResultPreservesReservationScheduleAndAllServerStates() {
        val result = CommerceJson.purchaseResult(fixture.deferred, CommerceModel.WALLET_FIRST) as PurchaseResult.Deferred
        assertEquals("reserved", result.state)
        assertEquals("transition_fixture_plan", result.ref)
        assertNull(result.settledAt)
        for (state in listOf("processing", "effect_applied", "settled", "requote_required", "cancelled", "reconciliation_required")) {
            assertEquals(state, (CommerceJson.purchaseResult(fixture.deferred.replace("\"reserved\"", "\"$state\""), CommerceModel.WALLET_FIRST) as PurchaseResult.Deferred).state)
        }
        rejects { CommerceJson.purchaseResult(fixture.deferred.replace("\"reserved\"", "\"active\""), CommerceModel.WALLET_FIRST) }
        rejects { CommerceJson.purchaseResult(fixture.deferred, CommerceModel.LEGACY) }
        rejects { CommerceJson.purchaseResult(fixture.order, CommerceModel.WALLET_FIRST) }
    }
}
