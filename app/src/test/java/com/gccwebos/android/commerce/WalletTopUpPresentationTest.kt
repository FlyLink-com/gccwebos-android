package com.gccwebos.android.commerce

import org.junit.Assert.*
import org.junit.Test

class WalletTopUpPresentationTest {
    private fun offer(ref: String, coins: Long, methods: List<String> = listOf("alipay", "wechat_pay"),
        bonus: Long? = null, promotion: RechargePromotion? = null) =
        RechargeOffer(ref, 1, coins, coins * 100, "CNY", 2, methods, bonus, promotion)

    private val offers = listOf(offer("coins_10", 10), offer("coins_30", 30), offer("coins_50", 50))

    @Test fun onlyClientCompletableMethodsAdvertisedByServerAreListed() {
        assertEquals(listOf(RechargePaymentMethod.Alipay), availableRechargeMethods(offers))
        // The server advertises wechat_pay today, but the client has no order path for it.
        assertEquals(emptyList<RechargePaymentMethod>(), availableRechargeMethods(listOf(offer("w", 10, listOf("wechat_pay")))))
        assertEquals(emptyList<RechargePaymentMethod>(), availableRechargeMethods(emptyList()))
        assertEquals(emptyList<RechargePaymentMethod>(), availableRechargeMethods(listOf(offer("legacy", 10, emptyList()))))
    }

    @Test fun defaultSelectionIsFirstPayableAndExplicitChoiceWinsWhileCurrent() {
        val alipay = RechargePaymentMethod.Alipay
        assertEquals(offers[0], effectiveRechargeSelection(offers, null, alipay))
        assertEquals(offers[2], effectiveRechargeSelection(offers, offers[2], alipay))
        // A stale choice (not in current offers) falls back to the default, never to the stale price.
        val stale = offers[2].copy(amountMinor = 9999)
        assertEquals(offers[0], effectiveRechargeSelection(offers, stale, alipay))
        val mixed = listOf(offer("a", 10, listOf("wechat_pay")), offer("b", 30))
        assertEquals(mixed[1], effectiveRechargeSelection(mixed, null, alipay))
        assertEquals(mixed[1], effectiveRechargeSelection(mixed, mixed[0], alipay))
        assertNull(effectiveRechargeSelection(offers, null, null))
        assertNull(effectiveRechargeSelection(emptyList(), null, alipay))
        // Changed directory: no silent default, but a current explicit choice is still honoured.
        assertNull(effectiveRechargeSelection(offers, null, alipay, defaultAllowed = false))
        assertEquals(offers[1], effectiveRechargeSelection(offers, offers[1], alipay, defaultAllowed = false))
    }

    @Test fun defaultOnlyForTheFirstDirectoryShownThisVisit() {
        assertFalse(rechargeDefaultAllowed(null, offers))
        assertFalse(rechargeDefaultAllowed(emptyList(), emptyList()))
        assertTrue(rechargeDefaultAllowed(offers, offers.toList()))
        val repriced = listOf(offers[0].copy(quoteVersion = 2, amountMinor = 1100)) + offers.drop(1)
        assertFalse(rechargeDefaultAllowed(offers, repriced))
    }

    @Test fun bonusAndPromotionAppearOnlyWhenServerDataProvidesThem() {
        assertNull(rechargeBonusBadge(offer("x", 100)))
        assertNull(rechargeBonusBadge(offer("x", 100, bonus = 0)))
        assertEquals("送10 Coins", rechargeBonusBadge(offer("x", 100, bonus = 10)))
        assertEquals("超值", RechargePromotion.Value.label)
        assertEquals("热门推荐", RechargePromotion.Popular.label)
        // The current wire format has no bonus/promotion fields, so parsed offers never carry them.
        val parsed = CommerceJson.rechargeOffers("""{"offers":[{"offerRef":"coins_100","quoteVersion":1,"paidCoinUnits":100,"amountMinor":10000,"currency":"CNY","currencyExponent":2,"paymentMethods":["alipay","wechat_pay"]}]}""")
        assertNull(parsed.single().bonusCoinUnits); assertNull(parsed.single().promotion)
    }

    @Test fun priceTextUsesYuanSignForCnyAndFixedPointOtherwise() {
        assertEquals("￥100.00", rechargePriceText(10000, "CNY", 2))
        assertEquals("￥0.05", rechargePriceText(5, "CNY", 2))
        assertEquals("USD 1.00", rechargePriceText(100, "USD", 2))
        assertEquals("￥10.00", rechargePriceText(offers[0]))
    }

    @Test fun compositionUsesRealBucketsAndNeverIncludesHeldOrPending() {
        assertNull(walletCompositionLine(null))
        val w = Wallet("active", 3, paidCoins = 17, bonusCoins = 5, rewardCoins = 0, spendableUnits = 22, heldUnits = 7, pendingUnits = 11)
        assertEquals("含 已购 17 · 赠送 5", walletCompositionLine(w))
        assertEquals("含 已购 17 · 赠送 5 · 奖励 3", walletCompositionLine(w.copy(rewardCoins = 3, spendableUnits = 25)))
    }
}
