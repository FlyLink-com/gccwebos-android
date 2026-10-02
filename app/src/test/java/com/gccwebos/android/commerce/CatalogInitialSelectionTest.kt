package com.gccwebos.android.commerce

import org.junit.Assert.*
import org.junit.Test

class CatalogInitialSelectionTest {
    private val monthly = Offer("server_monthly_b", "server_version_7",
        LocalizedText("服务器标题", "Server title"), LocalizedText("", ""),
        LocalizedNotices(emptyList(), emptyList()), 30, 12345, listOf("paid_coins"), 30, 117, 0)
    private val cheaper = monthly.copy(offerRef = "server_monthly_a", offerVersionRef = "server_version_6", baseCoinUnits = 20)
    private val yearly = monthly.copy(offerRef = "server_yearly", offerVersionRef = "server_version_8", durationDays = 365, baseCoinUnits = 200)

    @Test fun selectsTheActualUniqueSnapshotForBothAuthenticatedCatalogModels() {
        for (model in CommerceModel.entries) {
            assertSame(monthly, initialMonthlyCatalogOffer(listOf(cheaper, monthly, yearly), model))
            assertSame(monthly, initialMonthlyCatalogOffer(listOf(yearly, monthly, cheaper), model))
            assertEquals("server_version_7", initialMonthlyCatalogOffer(listOf(monthly), model)?.offerVersionRef)
            assertEquals(117L, initialMonthlyCatalogOffer(listOf(monthly), model)?.monthlyTrafficGb)
        }
    }

    @Test fun missingRequestedShapeDoesNotChooseByPricePositionOrTitle() {
        val priceOnly = monthly.copy(durationDays = 90)
        val titleOnly = yearly.copy(title = LocalizedText("30天 30Coins", "30 days 30 Coins"))
        assertNull(initialMonthlyCatalogOffer(listOf(cheaper, yearly), CommerceModel.LEGACY))
        assertNull(initialMonthlyCatalogOffer(listOf(priceOnly, titleOnly), CommerceModel.LEGACY))
        assertNull(initialMonthlyCatalogOffer(listOf(monthly.copy(baseCoinUnits = 31)), CommerceModel.LEGACY))
        assertNull(initialMonthlyCatalogOffer(emptyList(), CommerceModel.LEGACY))
    }

    @Test fun ambiguousPlansAndDuplicateStableIdentityFailClosed() {
        assertNull(initialMonthlyCatalogOffer(listOf(monthly, monthly.copy(offerRef = "other_monthly", offerVersionRef = "other_version")), CommerceModel.LEGACY))
        assertNull(initialMonthlyCatalogOffer(listOf(monthly, monthly.copy(baseCoinUnits = 20)), CommerceModel.WALLET_FIRST))
        assertNull(initialMonthlyCatalogOffer(listOf(monthly, cheaper, cheaper), CommerceModel.LEGACY))
    }

    @Test fun unknownModelAndNonPlanOfferNeverDefault() {
        assertNull(initialMonthlyCatalogOffer(listOf(monthly), null))
        val pack = monthly.copy(category = "data_pack", durationDays = null, monthlyTrafficGb = null, trafficGb = 30)
        assertNull(initialMonthlyCatalogOffer(listOf(pack), CommerceModel.LEGACY))
    }

    @Test fun catalogRevisionsReturnTheNewCompleteSnapshotForAnExplicitFreshDecision() {
        val revised = monthly.copy(offerVersionRef = "server_version_9", monthlyTrafficGb = 181,
            acceptedCoinTypes = listOf("bonus_coins", "paid_coins"))
        assertSame(revised, initialMonthlyCatalogOffer(listOf(revised), CommerceModel.WALLET_FIRST))
        assertNotEquals(monthly, revised)
        // This pure decision never retains the old version or changes the card price.
        assertEquals("30 Coins", formatCoins(revised.baseCoinUnits))
    }
}
