package com.gccwebos.android.commerce

import com.gccwebos.android.portal.WireResponse
import com.gccwebos.android.portal.PortalFailure
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Only public references, never tokens, payment URLs, quote prices or balances are persisted. */
data class PendingCommerce(val kind: String, val model: CommerceModel?, val inputRef: String,
    val key: String, val resultRef: String? = null) {
    init {
        require(kind in setOf("purchase", "recharge"))
        require((kind == "purchase") == (model != null))
        listOfNotNull(inputRef, key, resultRef).forEach { require(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}").matches(it)) }
    }
}
interface CommerceRecovery {
    fun read(binding: String, kind: String): PendingCommerce?
    fun save(binding: String, value: PendingCommerce)
    fun clear(binding: String, kind: String)
}
class CommerceFailure(val code: String, val status: Int = 0) : Exception("Commerce operation not confirmed")

/** Session-scoped coordinator; resume is read-only. An ambiguous mutation keeps the exact durable key. */
class CommerceRepository(private val request: suspend (String, String?, String?) -> WireResponse,
    private val recovery: CommerceRecovery, private val binding: String,
    private val now: () -> Instant = Instant::now, private val newKey: () -> String = { UUID.randomUUID().toString() }) {
    private val mutex = Mutex()
    private var model: CommerceModel? = null
    suspend fun loadModel(): CommerceModel = CommerceJson.model(call("/api/portal/commerce/model")).also { model = it }
    suspend fun offers(): List<Offer> = CommerceJson.offers(call(requireNotNull(model).offersPath))
    suspend fun wallet(): Wallet = CommerceJson.wallet(call("/api/portal/coins/wallet"))
    suspend fun rechargeOffers(): List<RechargeOffer> = CommerceJson.rechargeOffers(call("/api/portal/coins/recharge/offers"))
    suspend fun orderHistory(cursor: OrderCursor? = null): OrderPage {
        val query = buildString {
            append("/api/portal/commerce/orders?limit=20")
            if (cursor != null) {
                val stamp = java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(cursor.afterCreatedAt)
                append("&afterCreatedAt=").append(java.net.URLEncoder.encode(stamp, "UTF-8"))
                append("&afterOrderRef=").append(java.net.URLEncoder.encode(cursor.afterOrderRef, "UTF-8"))
            }
        }
        return CommerceJson.orders(call(query), cursor)
    }
    suspend fun transitionHistory(): List<PurchaseResult.Deferred> = CommerceJson.transitions(call("/api/portal/commerce/deferred/transitions"))
    suspend fun rechargeHistory(): List<RechargeOrder> = CommerceJson.rechargeOrders(call("/api/portal/coins/recharge/orders"))
    suspend fun ledger(cursor: WalletLedgerCursor? = null): WalletLedgerPage {
        val query = buildString {
            append("/api/portal/coins/wallet/ledger?limit=20")
            if (cursor != null) {
                val stamp = java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(cursor.afterOccurredAt)
                append("&afterOccurredAt=").append(java.net.URLEncoder.encode(stamp, "UTF-8"))
                append("&afterEntryRef=").append(java.net.URLEncoder.encode(cursor.afterEntryRef, "UTF-8"))
            }
        }
        return CommerceJson.ledger(call(query), cursor)
    }
    fun pending(kind: String) = recovery.read(binding, kind)
    suspend fun preview(offer: Offer): Quote = mutex.withLock {
        require(pending("purchase") == null)
        val selected = requireNotNull(model)
        CommerceJson.quote(call(selected.previewPath, JSONObject().put("offerRef", offer.offerRef).toString()), selected, offer.offerRef)
    }
    suspend fun purchase(quote: Quote): PurchaseResult = mutex.withLock {
        require(quote.purchaseAvailability == "available" && now() < quote.expiresAt)
        require(pending("purchase") == null)
        val selected = requireNotNull(model)
        require((selected == CommerceModel.WALLET_FIRST) == (quote.transition != null))
        val record = PendingCommerce("purchase", selected, quote.quoteRef, newKey())
        recovery.save(binding, record)
        submitPurchase(record)
    }
    suspend fun recharge(offer: RechargeOffer): RechargeOrder = mutex.withLock {
        require(pending("recharge") == null && "alipay" in offer.paymentMethods)
        val record = PendingCommerce("recharge", null, offer.offerRef, newKey())
        recovery.save(binding, record)
        submitRecharge(record)
    }
    /** User-triggered reconciliation only; unknown outcome retries the SAME idempotency key. */
    suspend fun retryPurchase(): PurchaseResult = mutex.withLock {
        val record = requireNotNull(pending("purchase"))
        if (record.resultRef == null) submitPurchase(record) else readPurchase(record)
    }
    suspend fun retryRecharge(): RechargeOrder = mutex.withLock {
        val record = requireNotNull(pending("recharge"))
        if (record.resultRef == null) submitRecharge(record) else readRecharge(record)
    }
    suspend fun currentPurchase(): PurchaseResult? = mutex.withLock {
        pending("purchase")?.takeIf { it.resultRef != null }?.let { readPurchase(it) }
    }
    suspend fun currentRecharge(): RechargeOrder? = mutex.withLock {
        pending("recharge")?.takeIf { it.resultRef != null }?.let { readRecharge(it) }
    }
    private suspend fun submitPurchase(record: PendingCommerce): PurchaseResult {
        val selected = requireNotNull(record.model)
        // Never silently cross the server-selected model after reauthentication.
        require(selected == model)
        val result = try { CommerceJson.purchaseResult(call(selected.purchasePath,
            JSONObject().put("quoteRef", record.inputRef).toString(), record.key, setOf(201)), selected) }
        catch (failure: CommerceFailure) {
            // These authoritative pre-order rejections cannot have committed a purchase.
            if ((failure.status == 409 && failure.code in setOf("quote_expired", "quote_stale", "offer_unavailable")) ||
                (failure.status == 423 && failure.code in setOf("insufficient_coins", "wallet_not_active")))
                recovery.clear(binding, "purchase")
            throw failure
        }
        recovery.save(binding, record.copy(resultRef = result.ref)); return result
    }
    private suspend fun submitRecharge(record: PendingCommerce): RechargeOrder {
        val result = CommerceJson.rechargeOrder(call("/api/portal/coins/recharge/orders",
            JSONObject().put("offerRef", record.inputRef).put("idempotencyKey", record.key).toString(), accepted = setOf(201)))
        require(result.offerRef == record.inputRef)
        recovery.save(binding, record.copy(resultRef = result.orderRef)); return result
    }
    private suspend fun readPurchase(record: PendingCommerce): PurchaseResult = CommerceJson.purchaseResult(
        call(requireNotNull(record.model).purchasePath + "/" + record.resultRef), record.model, record.resultRef)
    private suspend fun readRecharge(record: PendingCommerce): RechargeOrder = CommerceJson.rechargeOrder(
        call("/api/portal/coins/recharge/orders/" + record.resultRef), record.resultRef)
    suspend fun cancelPurchase(): PurchaseResult = mutex.withLock {
        val record = requireNotNull(pending("purchase")); require(record.resultRef != null)
        val selected = requireNotNull(record.model)
        val current = readPurchase(record)
        require(when (current) {
            is PurchaseResult.Order -> current.paymentStatus == "held" && now() < current.cancelUntil
            is PurchaseResult.Deferred -> current.state == "reserved"
        })
        CommerceJson.purchaseResult(call(selected.purchasePath + "/" + record.resultRef + "/cancel", "{}",
            record.key + ":cancel"), selected, record.resultRef)
    }
    suspend fun finishPurchase(): Unit = mutex.withLock {
        val record = requireNotNull(pending("purchase")); require(record.resultRef != null)
        val result = readPurchase(record)
        require(when(result) {
            is PurchaseResult.Order -> result.paymentStatus == "cancelled" || (result.paymentStatus == "captured" && result.fulfillmentStatus == "delivered")
            is PurchaseResult.Deferred -> result.state in setOf("settled", "cancelled")
        })
        recovery.clear(binding, "purchase")
    }
    suspend fun finishRecharge(): Unit = mutex.withLock {
        val record = requireNotNull(pending("recharge")); require(record.resultRef != null)
        require(readRecharge(record).status in setOf("credited", "failed_no_charge", "expired"))
        recovery.clear(binding, "recharge")
    }
    private suspend fun call(path: String, body: String? = null, key: String? = null, accepted: Set<Int> = setOf(200)): String {
        val response = request(path, body, key)
        if (response.status == 401) throw PortalFailure(PortalFailure.Reason.SESSION_EXPIRED)
        if (response.status !in accepted) {
            val code = try { JSONObject(response.body).getJSONObject("error").getString("code") } catch (_: Exception) { "unconfirmed" }
            throw CommerceFailure(code, response.status)
        }
        return response.body
    }
}
