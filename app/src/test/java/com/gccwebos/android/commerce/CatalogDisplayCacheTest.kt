package com.gccwebos.android.commerce

import com.gccwebos.android.portal.CommerceSessionIdentity
import org.junit.Assert.*
import org.junit.Test

class CatalogDisplayCacheTest {
    private val offer = Offer("synthetic_month", "synthetic_month_v1", LocalizedText("合成套餐", "Synthetic"),
        LocalizedText("", ""), LocalizedNotices(emptyList(), emptyList()), 30, 1000,
        listOf("paid_coins"), 30, 100, 0)

    @Test fun sameVerifiedOwnerKeepsAReadOnlySnapshotAcrossPageReaders() {
        val owner = CommerceSessionIdentity(); val cache = CatalogDisplayCache(owner)
        cache.publish(owner, listOf(offer))
        assertEquals(listOf(offer), cache.read(owner))
        assertEquals(cache.read(owner), cache.read(owner))
        cache.publish(owner, listOf(offer.copy(baseCoinUnits = 40)))
        assertEquals(40L, cache.read(owner).single().baseCoinUnits)
        cache.publish(owner, emptyList())
        assertTrue(cache.read(owner).isEmpty())
    }

    @Test fun MissingOrDifferentOwnerCanNeitherReadNorReplaceCachedOffers() {
        val first = CommerceSessionIdentity(); val second = CommerceSessionIdentity()
        val cache = CatalogDisplayCache(first); cache.publish(first, listOf(offer))
        assertTrue(cache.read(null).isEmpty()); assertTrue(cache.read(second).isEmpty())
        cache.publish(second, listOf(offer.copy(baseCoinUnits = 99))); cache.publish(null, emptyList())
        assertEquals(listOf(offer), cache.read(first))
        val replacement = CatalogDisplayCache(second)
        replacement.publish(first, listOf(offer))
        assertTrue(replacement.read(second).isEmpty())
        val anonymous = CatalogDisplayCache(null); anonymous.publish(null, listOf(offer))
        assertTrue(anonymous.read(null).isEmpty())
    }

    @Test fun CallerMutationCannotChangeTheStoredOfferSnapshot() {
        val owner = CommerceSessionIdentity(); val cache = CatalogDisplayCache(owner)
        val coins = mutableListOf("paid_coins"); val notices = mutableListOf("合成说明")
        val supplied = mutableListOf(offer.copy(acceptedCoinTypes = coins, importantNotices = LocalizedNotices(notices, notices)))
        cache.publish(owner, supplied)
        supplied.clear(); coins.clear(); notices.clear()
        val stored = cache.read(owner).single()
        assertEquals(listOf("paid_coins"), stored.acceptedCoinTypes)
        assertEquals(listOf("合成说明"), stored.importantNotices.zh)
    }
}
