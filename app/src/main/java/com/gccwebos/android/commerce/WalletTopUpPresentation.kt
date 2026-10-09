package com.gccwebos.android.commerce

/**
 * Pure presentation rules for the wallet top-up (钱包充值) screen. No pricing, bonus tier or
 * payment method is invented here: everything is derived from server-returned offers/wallet.
 */

/** Promotion labels a server-provided offer may carry. Nothing assigns them on the client. */
enum class RechargePromotion(val label: String) { Value("超值"), Popular("热门推荐") }

/**
 * Payment methods this client can actually complete. The order API (POST
 * /api/portal/coins/recharge/orders) accepts only offerRef + idempotencyKey and the repository
 * requires "alipay", so Alipay is the only selectable method today. Add entries here only
 * together with a real order/handoff path for that method.
 */
internal enum class RechargePaymentMethod(val wire: String, val title: String, val subtitle: String) {
    Alipay("alipay", "支付宝支付", "安全便捷的在线支付方式"),
}

internal val ClientRechargePaymentMethods: List<RechargePaymentMethod> = listOf(RechargePaymentMethod.Alipay)

/** Methods offered by at least one current server offer AND completable by this client, in client order. */
internal fun availableRechargeMethods(offers: List<RechargeOffer>,
    client: List<RechargePaymentMethod> = ClientRechargePaymentMethods): List<RechargePaymentMethod> =
    client.filter { method -> offers.any { method.wire in it.paymentMethods } }

/** An offer is payable with [method] only when the server lists that method for it. */
internal fun rechargeOfferPayable(offer: RechargeOffer, method: RechargePaymentMethod?): Boolean =
    method != null && method.wire in offer.paymentMethods

/**
 * Highlighted tile: an explicit user choice wins while it is still a current, payable offer.
 * Otherwise the first payable offer (server order, no upsell bias) is pre-selected, but only while
 * [defaultAllowed] — i.e. the directory is still the one first shown on this visit. A changed
 * directory never silently adopts a new price: the user must choose again.
 * Selecting never creates an order; the existing confirmation dialog still gates submission.
 */
internal fun effectiveRechargeSelection(offers: List<RechargeOffer>, explicit: RechargeOffer?,
    method: RechargePaymentMethod?, defaultAllowed: Boolean = true): RechargeOffer? {
    explicit?.takeIf { chosen -> offers.any { it == chosen } && rechargeOfferPayable(chosen, method) }?.let { return it }
    if (!defaultAllowed) return null
    return offers.firstOrNull { rechargeOfferPayable(it, method) }
}

/** The default may apply only to the first directory loaded on this visit. */
internal fun rechargeDefaultAllowed(initialDirectory: List<RechargeOffer>?, shown: List<RechargeOffer>): Boolean =
    initialDirectory != null && initialDirectory.isNotEmpty() && initialDirectory == shown

/** Bonus badge text, shown only when the server supplied a positive bonus. */
internal fun rechargeBonusBadge(offer: RechargeOffer): String? =
    offer.bonusCoinUnits?.takeIf { it > 0 }?.let { "送$it Coins" }

/** Customer-facing price: "￥100.00" for CNY, otherwise the existing fixed-point "USD 1.00" form. */
internal fun rechargePriceText(amountMinor: Long, currency: String, currencyExponent: Int): String {
    val formatted = formatMoney(amountMinor, currency, currencyExponent)
    return if (currency == "CNY") "￥" + formatted.removePrefix("CNY ") else formatted
}

internal fun rechargePriceText(offer: RechargeOffer): String =
    rechargePriceText(offer.amountMinor, offer.currency, offer.currencyExponent)

/**
 * One-line composition of the spendable balance from the real wallet buckets. Reward coins are
 * part of spendable funds, so they are listed whenever non-zero to keep the line truthful.
 * Held / pending funds are not spendable and live in the balance-detail view only.
 */
internal fun walletCompositionLine(wallet: Wallet?): String? {
    wallet ?: return null
    val parts = mutableListOf("已购 ${wallet.paidCoins}", "赠送 ${wallet.bonusCoins}")
    if (wallet.rewardCoins > 0) parts += "奖励 ${wallet.rewardCoins}"
    return "含 " + parts.joinToString(" · ")
}
