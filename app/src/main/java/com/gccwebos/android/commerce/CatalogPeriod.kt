package com.gccwebos.android.commerce

/** Display groups use exact server durations; nonstandard offers remain reachable. */
internal enum class CatalogPeriod(val title: String, val tag: String) {
    Monthly("月卡", "catalog-plans"), Yearly("年卡", "catalog-yearly"), Other("其他套餐", "catalog-other")
}

internal fun catalogPeriod(offer: Offer): CatalogPeriod = when (offer.durationDays) {
    30L -> CatalogPeriod.Monthly
    365L -> CatalogPeriod.Yearly
    else -> CatalogPeriod.Other
}

internal fun initialCatalogPeriod(offers: List<Offer>): CatalogPeriod {
    val periods = offers.filter { it.category == "plan" }.map(::catalogPeriod)
    return CatalogPeriod.entries.firstOrNull { it in periods } ?: CatalogPeriod.Monthly
}
