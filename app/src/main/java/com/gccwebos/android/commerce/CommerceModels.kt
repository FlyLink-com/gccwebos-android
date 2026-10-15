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

data class WalletLedgerCursor(val afterOccurredAt: Instant, val afterEntryRef: String)
data class WalletLedgerEntry(val entryRef: String, val category: String, val coinType: String, val amountUnits: Long,
    val availableDeltaUnits: Long?, val pendingDeltaUnits: Long?, val heldDeltaUnits: Long?, val totalDeltaUnits: Long,
    val relatedOrderRef: String?, val occurredAt: Instant)
data class WalletLedgerPage(val entries: List<WalletLedgerEntry>, val nextCursor: WalletLedgerCursor?)
data class OrderCursor(val afterCreatedAt: Instant, val afterOrderRef: String)
data class OrderPage(val orders: List<PurchaseResult.Order>, val nextCursor: OrderCursor? = null)

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
    val durationDays: Long?,
    val monthlyTrafficGb: Long?,
    val discountBasisPoints: Long?,
    val category: String = "plan",
    val trafficGb: Long? = null,
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
    /**
     * UI hook only: the current server projection (v1/v2) has no bonus or promotion fields and
     * [CommerceJson.rechargeOffers] never sets these. They stay null until a server contract adds them.
     */
    val bonusCoinUnits: Long? = null,
    val promotion: RechargePromotion? = null,
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
    val awaitingUpdate: Boolean get() = status !in setOf("credited", "failed_no_charge", "expired", "reconciliation_required")
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
enum class PlanSwitchKind(val wire: String) {
    Renewal("renewal"), UpgradeInPlace("upgrade_inplace"), UpgradeRestart("upgrade_restart"), DowngradeScheduled("downgrade_scheduled");
    val isUpgrade get() = this == UpgradeInPlace || this == UpgradeRestart
}
/**
 * The server's plan-change pricing of one quote or order. [chargeUnits] is the price BEFORE the account discount (the
 * quote's amount is the discounted figure); [effectiveAt] is set for a scheduled downgrade (when the current card ends).
 */
data class PlanSwitch(val kind: PlanSwitchKind, val creditUnits: Long, val chargeUnits: Long, val effectiveAt: Instant?,
    val cancelsPendingDowngrade: Boolean)
enum class PlanSwitchAction { CurrentRenew, Upgrade, Downgrade, Unavailable }
data class PlanSwitchOffer(val offerRef: String, val action: PlanSwitchAction, val chargeUnits: Long?, val creditUnits: Long?,
    val effectiveAt: Instant?, val reasonCode: String?)
data class PlanSwitchCurrent(val offerRef: String, val validUntil: Instant)
data class PlanSwitchPending(val toOfferRef: String, val effectiveAt: Instant)
/** GET plan-switch/state: advisory display data; the server re-prices and re-checks every quote and confirmation. */
data class PlanSwitchState(val current: PlanSwitchCurrent?, val pending: PlanSwitchPending?, val offers: List<PlanSwitchOffer>)
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
    /** Present only when the server prices this quote as a plan change (renew / upgrade / scheduled downgrade). */
    val planSwitch: PlanSwitch? = null,
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
        val planSwitch: PlanSwitch? = null,
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
