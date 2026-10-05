package com.gccwebos.android.commerce

import com.gccwebos.android.portal.CommerceSessionIdentity

/** App-shell memory only. A displayed offer never grants checkout or purchase authority. */
internal class CatalogDisplayCache(private val owner: CommerceSessionIdentity?) {
    private var offers: List<Offer> = emptyList()

    fun read(currentOwner: CommerceSessionIdentity?): List<Offer> =
        if (owner != null && currentOwner === owner) offers.map(::displayCopy) else emptyList()

    /** Only a complete, current, server-validated catalog may replace this display snapshot. */
    fun publish(currentOwner: CommerceSessionIdentity?, verifiedOffers: List<Offer>) {
        if (owner != null && currentOwner === owner) offers = verifiedOffers.map(::displayCopy)
    }

    private fun displayCopy(offer: Offer) = offer.copy(
        acceptedCoinTypes = offer.acceptedCoinTypes.toList(),
        importantNotices = LocalizedNotices(offer.importantNotices.zh.toList(), offer.importantNotices.en.toList()),
    )
}
