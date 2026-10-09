package com.gccwebos.android.commerce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogOfferPresentationTest {
    private val plan = Offer("p50", "v1", LocalizedText("50 GB 月度套餐", "50 GB monthly"),
        LocalizedText("", ""), LocalizedNotices(emptyList(), emptyList()), 20, 2000,
        listOf("bonus_coins", "paid_coins"), 30, 50, 0)
    private val plan100 = plan.copy(offerRef = "p100", title = LocalizedText("100 GB 月度套餐", "100 GB monthly"),
        baseCoinUnits = 30, monthlyTrafficGb = 100)
    private val pack = plan.copy(offerRef = "pack", category = "data_pack", durationDays = null,
        monthlyTrafficGb = null, discountBasisPoints = null, trafficGb = 23)

    @Test fun monthlyPlanShowsFigureAndOneLineSubtitleWithoutRepeatingTitle() {
        val text = catalogOfferText(plan)
        assertEquals("50 GB", text.headline)
        assertNull(text.headlineSuffix)
        assertEquals("月度套餐 · 30天有效", catalogOfferSubtitle(plan))
        assertEquals("30天", text.validity)
    }

    @Test fun nonMonthlyTermsKeepPerMonthUnitAndExactServerDuration() {
        val yearly = plan.copy(durationDays = 365)
        assertEquals("/月", catalogOfferText(yearly).headlineSuffix)
        assertEquals("年度套餐 · 365天有效", catalogOfferSubtitle(yearly))
        assertEquals("套餐 · 47天有效", catalogOfferSubtitle(plan.copy(durationDays = 47)))
    }

    @Test fun dataPackSubtitleUsesNeverExpiringDelivery() {
        assertEquals("23 GB", catalogOfferText(pack).headline)
        assertEquals("流量包 · 永久有效，用完为止", catalogOfferSubtitle(pack))
    }

    @Test fun bestValueIsStrictlyLowestCoinsPerGbAmongComparableOffers() {
        // 20/50 = 0.4 vs 30/100 = 0.3 Coins per GB.
        assertEquals("p100", catalogBestValueRef(listOf(plan, plan100)))
        assertEquals("p100", catalogBestValueRef(listOf(plan100, plan)))
    }

    @Test fun noBadgeForSingleTiedMixedTermOrMixedCategoryOffers() {
        assertNull(catalogBestValueRef(listOf(plan100)))
        assertNull(catalogBestValueRef(listOf(plan, plan.copy(offerRef = "p50b", baseCoinUnits = 40, monthlyTrafficGb = 100))))
        assertNull(catalogBestValueRef(listOf(plan, plan100.copy(durationDays = 47))))
        assertNull(catalogBestValueRef(listOf(plan, pack)))
        assertNull(catalogBestValueRef(emptyList()))
    }

    @Test fun dataPacksCompareTotalTraffic() {
        val big = pack.copy(offerRef = "pack2", trafficGb = 100, baseCoinUnits = 40)
        assertEquals("pack2", catalogBestValueRef(listOf(pack, big)))
    }

    @Test fun indistinguishableCardsKeepServerTitle() {
        val twin = plan.copy(offerRef = "twin", title = LocalizedText("50 GB 月度套餐（推广）", "promo"), baseCoinUnits = 18)
        assertEquals(setOf("p50", "twin"), catalogOffersNeedingTitle(listOf(plan, twin, plan100)))
        assertTrue(catalogOffersNeedingTitle(listOf(plan, plan100)).isEmpty())
    }

    @Test fun dockSummaryNamesSelectionAndPrice() {
        assertEquals("已选 100 GB 月度套餐 | 30 Coins", catalogDockSummary("100 GB 月度套餐", "30 Coins"))
        assertEquals("请选择套餐", catalogDockSummary(null, null))
    }

    @Test fun coinLabelsAndBenefitsDoNotRepeatValidityOrTraffic() {
        assertEquals("赠送 Coins", catalogCoinTypeLabel("bonus_coins"))
        assertEquals("已购 Coins", catalogCoinTypeLabel("paid_coins"))
        assertEquals("奖励 Coins", catalogCoinTypeLabel("reward_coins"))
        val labels = CatalogProductBenefits.map { it.first }
        assertEquals(listOf("极速节点", "多设备通用", "安全加密", "全局加速"), labels)
        assertTrue(labels.none { "有效" in it || "流量" in it || "GB" in it })
    }
}
