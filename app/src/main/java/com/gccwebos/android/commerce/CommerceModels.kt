package com.gccwebos.android.commerce

import java.time.Instant

/** This value is read from the authenticated server session, never selected by the user. */
enum class CommerceModel(
    val wireValue: String,
    val offersPath: String,
    val previewPath: String,
    val purchasePath: String,
) {
    LEGACY("legacy", "/api/portal/catalog/offers", "/api/portal/commerce/checkout-preview", "/api/portal/commerce/orders"),
    WALLET_FIRST("wallet_first_unique_v1", "/api/portal/commerce/deferred/offers", "/api/portal/commerce/deferred/checkout-preview", "/api/portal/commerce/deferred/transitions"),
}

data class LocalizedText(val zh: String, val en: String)
data class LocalizedNotices(val zh: List<String>, val en: List<String>)

/** A validated subscription-plan offer; duration and quota come from the server delivery. */
data class Offer(
    val offerRef: String,
    val offerVersionRef: String,
    val title: LocalizedText,
    val summary: LocalizedText,
    val importantNotices: LocalizedNotices,
    val baseCoinUnits: Long,
    val referenceAmountMinor: Long,
    val acceptedCoinTypes: List<String>,
    val durationDays: Long,
    val monthlyTrafficGb: Long,
    val discountBasisPoints: Long,
)

data class Wallet(
    val status: String,
    val version: Long,
    val paidCoins: Long,
    val bonusCoins: Long,
    val rewardCoins: Long,
    val spendableUnits: Long,
    val heldUnits: Long,
    val pendingUnits: Long,
)

data class RechargeOffer(
    val offerRef: String,
    val quoteVersion: Long,
    val paidCoinUnits: Long,
    val amountMinor: Long,
    val currency: String,
    val currencyExponent: Int,
    val paymentMethods: List<String>,
)

/** Payment URLs must remain transient and must not appear in generated debug strings. */
class PaymentRedirect(val url: String, val expiresAt: Instant) {
    val type: String get() = "redirect"
    override fun toString() = "PaymentRedirect([REDACTED])"
}

data class RechargeOrder(
    val orderRef: String,
    val offerRef: String,
    val quoteVersion: Long,
    val expectedPaidCoinUnits: Long,
    val amountMinor: Long,
    val currency: String,
    val currencyExponent: Int,
    val adapterKey: String,
    val paymentMethod: String,
    val status: String,
    val providerOrderRef: String?,
    val nextAction: PaymentRedirect?,
    val walletOperationRef: String?,
    val walletLotRef: String?,
    val expiresAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val terminal: Boolean get() = status in setOf("credited", "failed_no_charge", "expired")
    fun payableAt(now: Instant): Boolean = status in setOf("entry_ready", "pending") &&
        nextAction != null && now < expiresAt && now < nextAction.expiresAt
}

data class CoinAllocation(val coinType: String, val amountUnits: Long)
data class CoinBalance(val bonusCoins: Long, val paidCoins: Long, val rewardCoins: Long, val heldCoins: Long) {
    internal fun units(type: String): Long = when (type) {
        "bonus_coins" -> bonusCoins
        "paid_coins" -> paidCoins
        "reward_coins" -> rewardCoins
        else -> error("Invalid coin type")
    }
}
data class QuoteBalances(val before: CoinBalance, val after: CoinBalance)
data class QuoteTransition(val flowKind: String, val scheduledFor: Instant)
data class Quote(
    val quoteRef: String,
    val offer: Offer,
    val amountUnits: Long,
    val appliedDiscountBasisPoints: Long,
    val purchaseAvailability: String,
    val shortfallUnits: Long,
    val allocation: List<CoinAllocation>,
    val balances: QuoteBalances,
    val expiresAt: Instant,
    val transition: QuoteTransition?,
) {
    fun purchasableAt(now: Instant) = purchaseAvailability == "available" && now < expiresAt
}

/** Payment settlement and fulfillment are separate; a reservation is not an active service. */
sealed interface PurchaseResult {
    val ref: String
    val offer: Offer
    val amountUnits: Long
    val model: CommerceModel

    data class Order(
        val orderRef: String,
        override val offer: Offer,
        override val amountUnits: Long,
        val allocation: List<CoinAllocation>,
        val paymentStatus: String,
        val fulfillmentStatus: String,
        val fulfillmentSafeCode: String?,
        val cancelUntil: Instant,
        val createdAt: Instant,
        val updatedAt: Instant,
    ) : PurchaseResult {
        override val ref get() = orderRef
        override val model get() = CommerceModel.LEGACY
    }

    data class Deferred(
        val transitionRef: String,
        override val offer: Offer,
        override val amountUnits: Long,
        val flowKind: String,
        val scheduledFor: Instant,
        val state: String,
        val safeCode: String?,
        val reservedAt: Instant,
        val settledAt: Instant?,
        val createdAt: Instant,
        val updatedAt: Instant,
    ) : PurchaseResult {
        override val ref get() = transitionRef
        override val model get() = CommerceModel.WALLET_FIRST
    }
}

const val MAX_SAFE_COIN_UNITS = 9_007_199_254_740_991L

/** Source price book and mobile renderer use one integer unit per displayed Coin. */
fun formatCoins(units: Long): String {
    require(units in 0..MAX_SAFE_COIN_UNITS)
    return "$units Coins"
}

/** Fixed-point currency formatting avoids Double rounding, including large safe integers. */
fun formatMoney(amountMinor: Long, currency: String, currencyExponent: Int): String {
    require(amountMinor in 0..MAX_SAFE_COIN_UNITS && currencyExponent in 0..9)
    require(Regex("[A-Z]{3}").matches(currency))
    val digits = amountMinor.toString().padStart(currencyExponent + 1, '0')
    val amount = if (currencyExponent == 0) digits else
        digits.dropLast(currencyExponent) + "." + digits.takeLast(currencyExponent)
    return "$currency $amount"
}
