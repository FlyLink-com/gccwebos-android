package com.gccwebos.android.commerce

/**
 * A one-time UI preference requested for the 30-day / 30-Coins plan, not a server
 * recommendation or a price book. Only the complete validated server snapshot
 * can be selected. Missing, ambiguous or duplicate identities fail closed.
 * The caller owns session eligibility, existing selection and the one-time gate.
 */
internal fun initialMonthlyCatalogOffer(offers: List<Offer>, model: CommerceModel?): Offer? {
    if (model == null || offers.map { it.offerRef }.distinct().size != offers.size) return null
    return offers.singleOrNull {
        it.category == "plan" && it.durationDays == 30L && it.baseCoinUnits == 30L
    }
}
