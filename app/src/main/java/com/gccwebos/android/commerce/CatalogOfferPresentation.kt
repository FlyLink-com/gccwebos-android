package com.gccwebos.android.commerce

import java.math.BigInteger

/**
 * Display text for one catalog offer, derived only from validated server delivery fields.
 * Nothing here is a plan fact of its own: no local price book, traffic or validity table.
 */
internal data class CatalogOfferText(
    /** Large traffic figure, e.g. "50 GB". */
    val headline: String,
    /** Unit suffix next to the figure, only when it adds information (e.g. a 365-day plan's "/月"). */
    val headlineSuffix: String?,
    /** Short kind label, e.g. "月度套餐" / "年度套餐" / "套餐" / "流量包". */
    val kind: String,
    /** Validity value, e.g. "30天" or "永久有效，用完为止". */
    val validity: String,
    /** Trailing word after a duration validity ("有效"), null when the value already reads as a sentence. */
    val validitySuffix: String?,
)

internal fun catalogOfferText(offer: Offer): CatalogOfferText = if (offer.category == "data_pack") {
    CatalogOfferText("${offer.trafficGb} GB", null, "流量包", "永久有效，用完为止", null)
} else {
    val period = catalogPeriod(offer)
    CatalogOfferText(
        headline = "${offer.monthlyTrafficGb} GB",
        // A 30-day plan's monthly quota IS its whole quota; longer or odd terms keep the unit.
        headlineSuffix = if (offer.durationDays == 30L) null else "/月",
        kind = when (period) {
            CatalogPeriod.Monthly -> "月度套餐"
            CatalogPeriod.Yearly -> "年度套餐"
            CatalogPeriod.Other -> "套餐"
        },
        validity = offer.durationDays?.let { "${it}天" } ?: offer.title.zh,
        validitySuffix = offer.durationDays?.let { "有效" },
    )
}

/** One-line subtitle as rendered, e.g. "月度套餐 · 30天有效". */
internal fun catalogOfferSubtitle(offer: Offer): String = catalogOfferText(offer).let {
    "${it.kind} · ${it.validity}${it.validitySuffix.orEmpty()}"
}

/**
 * When two displayed offers would render the same figure and subtitle, the server title is the
 * only remaining distinction, so it is shown for those offers instead of being dropped.
 */
internal fun catalogOffersNeedingTitle(shown: List<Offer>): Set<String> {
    val key = { offer: Offer -> catalogOfferText(offer).let { listOf(it.headline, it.headlineSuffix, catalogOfferSubtitle(offer)) } }
    return shown.groupBy(key).values.filter { it.size > 1 }.flatten().map { it.offerRef }.toSet()
}

/**
 * Data-driven "best value" marker: the single displayed offer with the strictly lowest
 * Coins per GB among at least two comparable offers. Plans are comparable only when every
 * displayed plan has the same duration (so monthly quota compares like for like); data packs
 * compare total traffic. Ties, mixed terms or missing figures yield no badge.
 */
internal fun catalogBestValueRef(shown: List<Offer>): String? {
    if (shown.size < 2 || shown.map { it.offerRef }.distinct().size != shown.size) return null
    val categories = shown.map { it.category }.distinct()
    if (categories.size != 1) return null
    val category = categories.single()
    if (category != "plan" && category != "data_pack") return null
    if (category == "plan" && shown.map { it.durationDays }.distinct().size != 1) return null
    val gb: (Offer) -> Long? = { if (category == "plan") it.monthlyTrafficGb else it.trafficGb }
    if (shown.any { (gb(it) ?: 0L) <= 0L }) return null
    // a/b < c/d  <=>  a*d < c*b, exact for every safe integer.
    val cheaper = { a: Offer, b: Offer ->
        BigInteger.valueOf(a.baseCoinUnits) * BigInteger.valueOf(requireNotNull(gb(b))) <
            BigInteger.valueOf(b.baseCoinUnits) * BigInteger.valueOf(requireNotNull(gb(a)))
    }
    val best = shown.firstOrNull { candidate -> shown.all { it === candidate || cheaper(candidate, it) } }
    return best?.offerRef
}

internal fun catalogCoinTypeLabel(value: String) = when (value) {
    "paid_coins" -> "已购 Coins"; "bonus_coins" -> "赠送 Coins"; "reward_coins" -> "奖励 Coins"; else -> "Coins"
}

/** Dock summary, e.g. "已选 100 GB 月度套餐 | 30 Coins"; the server title names the selection. */
internal fun catalogDockSummary(title: String?, price: String?): String =
    if (title == null) "请选择套餐" else listOfNotNull("已选 $title", price).joinToString(" | ")
