package com.gccwebos.android.commerce

import java.math.BigDecimal
import java.math.BigInteger
import java.net.URI
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder

private typealias JsonObject = Map<String, Any?>

/**
 * Port of the verified mobile commerce/wallet/recharge projections, pinned to
 * gccltd/gccwebos-mobile 091ee6e82bb768333dcc23eaaf725c1fd07ba243.
 * All fields, discriminants, safe-integer bounds and quote arithmetic fail closed.
 * Does not access the network, select the commercial model, or infer service activation.
 */
object CommerceJson {
    private val commerceRef = Regex("[a-z][a-z0-9_]{2,95}")
    private val rechargeRef = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    private val coinPriority = listOf("bonus_coins", "paid_coins", "reward_coins")
    private val paymentMethods = listOf("alipay", "wechat_pay", "paypal")
    private val flowKinds = setOf("initial_activation", "scheduled_switch")
    private val rechargeStatuses = setOf("created", "entry_ready", "pending", "payment_verified", "credited", "failed_no_charge", "reconciliation_required", "expired")
    private val paymentStatuses = setOf("held", "capture_pending", "captured", "cancelled", "capture_failed", "reconciliation_required")
    private val fulfillmentStatuses = setOf("not_requested", "pending", "processing", "delivered", "failed")
    private val fulfillmentCodes = setOf("fulfillment_handler_failed", "fulfillment_result_invalid", "fulfillment_handler_unavailable", "fulfillment_attempt_limit")
    private val transitionStates = setOf("reserved", "processing", "effect_applied", "settled", "requote_required", "cancelled", "reconciliation_required")
    private val timestampFormat = DateTimeFormatterBuilder().appendInstant(3).toFormatter()

    // Public origins from src/api/public-runtime.mjs at the pinned mobile commit;
    // paypal_v1 is the upstream client's non-overridable default. Never trust an API-supplied origin.
    private val trustedActionOrigins = mapOf(
        "xsd_v1" to setOf("https://www.xsd8866.com"),
        "paypal_v1" to setOf("https://www.sandbox.paypal.com"),
    )

    fun model(body: String): CommerceModel {
        val value = root(body, "commerceModel").text("commerceModel")
        return CommerceModel.entries.singleOrNull { it.wireValue == value } ?: invalid()
    }

    /** Validate the entire catalog before omitting supported-but-out-of-scope data packs. */
    fun offers(body: String): List<Offer> = array(root(body, "offers")["offers"]).mapNotNull(::offerProjection)

    fun offer(body: String, expectedRef: String? = null): Offer =
        plan(root(body, "offer")["offer"]).also { matchReference(it.offerRef, expectedRef) }

    fun wallet(body: String): Wallet {
        val value = obj(root(body, "wallet")["wallet"], "status", "version", "balances", "spendableUnits", "heldUnits", "pendingUnits")
        val status = value.choice("status", setOf("not_created", "active", "frozen", "closed"))
        val balances = obj(value["balances"], "paidCoins", "bonusCoins", "rewardCoins")
        val result = Wallet(status, value.units("version"), balances.units("paidCoins"), balances.units("bonusCoins"),
            balances.units("rewardCoins"), value.units("spendableUnits"), value.units("heldUnits"), value.units("pendingUnits"))
        require(result.spendableUnits == add(add(result.paidCoins, result.bonusCoins), result.rewardCoins)) { "Invalid wallet total" }
        if (status == "not_created") require(listOf(result.version, result.paidCoins, result.bonusCoins, result.rewardCoins,
            result.spendableUnits, result.heldUnits, result.pendingUnits).all { it == 0L }) { "Invalid empty wallet" }
        return result
    }

    fun rechargeOffers(body: String): List<RechargeOffer> =
        array(root(body, "offers")["offers"]).map { raw ->
            val base = setOf("offerRef", "quoteVersion", "paidCoinUnits", "amountMinor", "currency", "currencyExponent")
            val fields = objectValue(raw).keys
            val v1 = fields == base
            val value = obj(raw, *(if (v1) base else base + "paymentMethods").toTypedArray())
            RechargeOffer(value.reference("offerRef", rechargeRef), value.units("quoteVersion", positive = true),
                value.units("paidCoinUnits", positive = true), value.units("amountMinor", positive = true),
                currency(value["currency"]), exponent(value["currencyExponent"]),
                if (v1) emptyList() else orderedChoices(value["paymentMethods"], paymentMethods))
        }

    fun rechargeOrder(body: String, expectedRef: String? = null): RechargeOrder =
        rechargeOrderProjection(obj(root(body, "recharge")["recharge"], "order")["order"])
            .also { matchReference(it.orderRef, expectedRef) }

    fun rechargeOrders(body: String): List<RechargeOrder> =
        array(root(body, "recharges")["recharges"]).map(::rechargeOrderProjection)

    fun quote(body: String, model: CommerceModel, expectedOfferRef: String? = null): Quote {
        val fields = listOf("quoteRef", "offer", "amountUnits", "appliedDiscountBasisPoints", "purchaseAvailability", "shortfallUnits", "allocation", "balances", "expiresAt")
        val value = obj(root(body, "quote")["quote"], *(fields + if (model == CommerceModel.WALLET_FIRST) listOf("transition") else emptyList()).toTypedArray())
        val offer = plan(value["offer"])
        matchReference(offer.offerRef, expectedOfferRef)
        val amount = value.units("amountUnits", positive = true)
        val discount = value.units("appliedDiscountBasisPoints").also { require(it <= 10_000) }
        val numerator = BigInteger.valueOf(offer.baseCoinUnits) * BigInteger.valueOf(10_000 - discount)
        require(amount == ((numerator + BigInteger.valueOf(9_999)) / BigInteger.valueOf(10_000)).longValueExact()) { "Invalid quote amount" }
        val availability = value.choice("purchaseAvailability", setOf("available", "insufficient_balance"))
        val shortfall = value.units("shortfallUnits")
        val balances = obj(value["balances"], "before", "after")
        val before = balance(balances["before"])
        val after = balance(balances["after"])
        val allocation: List<CoinAllocation>
        if (availability == "available") {
            require(shortfall == 0L) { "Invalid available quote" }
            allocation = allocation(value["allocation"], amount, offer.acceptedCoinTypes)
            for (type in coinPriority) {
                val allocated = allocation.singleOrNull { it.coinType == type }?.amountUnits ?: 0L
                require(after.units(type) == before.units(type) - allocated) { "Invalid balance movement" }
            }
            require(after.heldCoins == add(before.heldCoins, amount)) { "Invalid hold" }
        } else {
            require(shortfall > 0 && before == after) { "Invalid unavailable quote" }
            allocation = allocation(value["allocation"], 0, offer.acceptedCoinTypes, allowEmpty = true)
            val accepted = offer.acceptedCoinTypes.fold(0L) { sum, type -> add(sum, before.units(type)) }
            require(accepted < amount && amount - accepted == shortfall) { "Invalid shortfall" }
        }
        val transition = if (model == CommerceModel.WALLET_FIRST) {
            val projected = obj(value["transition"], "flowKind", "scheduledFor")
            QuoteTransition(projected.choice("flowKind", flowKinds), time(projected["scheduledFor"]))
        } else null
        return Quote(value.reference("quoteRef", commerceRef), offer, amount, discount, availability, shortfall,
            allocation, QuoteBalances(before, after), time(value["expiresAt"]), transition)
    }

    fun purchaseResult(body: String, model: CommerceModel, expectedRef: String? = null): PurchaseResult {
        val result = when (model) {
            CommerceModel.LEGACY -> order(root(body, "order")["order"])
            CommerceModel.WALLET_FIRST -> deferred(root(body, "transition")["transition"])
        }
        matchReference(result.ref, expectedRef)
        return result
    }

    private fun offerProjection(raw: Any?): Offer? {
        val value = obj(raw, "offerRef", "offerVersionRef", "category", "title", "summary", "importantNotices", "baseCoinUnits", "referencePrice", "acceptedCoinTypes", "delivery")
        val ref = value.reference("offerRef", commerceRef)
        val versionRef = value.reference("offerVersionRef", commerceRef)
        val title = localized(value["title"])
        val summary = localized(value["summary"])
        val notices = obj(value["importantNotices"], "zh", "en")
        val importantNotices = LocalizedNotices(notices(notices["zh"]), notices(notices["en"]))
        val units = value.units("baseCoinUnits", positive = true)
        val price = obj(value["referencePrice"], "currency", "amountMinor")
        require(price["currency"] == "CNY") { "Invalid reference currency" }
        val amountMinor = price.units("amountMinor", positive = true)
        val accepted = orderedChoices(value["acceptedCoinTypes"], coinPriority)
        val delivery = obj(value["delivery"], "kind", "durationDays", "monthlyTrafficGb", "trafficGb", "neverExpires", "discountBasisPoints")
        when (value["category"]) {
            "plan" -> {
                require(delivery["kind"] == "subscription_plan" && delivery["trafficGb"] == null && delivery["neverExpires"] == false) { "Invalid plan delivery" }
                val discount = delivery.units("discountBasisPoints").also { require(it <= 10_000) }
                return Offer(ref, versionRef, title, summary, importantNotices, units, amountMinor, accepted,
                    delivery.units("durationDays", positive = true), delivery.units("monthlyTrafficGb", positive = true), discount)
            }
            "data_pack" -> {
                require(delivery["kind"] == "data_pack" && delivery["durationDays"] == null && delivery["monthlyTrafficGb"] == null &&
                    delivery["neverExpires"] == true && delivery["discountBasisPoints"] == null) { "Invalid data pack delivery" }
                delivery.units("trafficGb", positive = true)
                return null
            }
            else -> invalid()
        }
    }

    private fun plan(raw: Any?): Offer = offerProjection(raw) ?: invalid()

    private fun order(raw: Any?): PurchaseResult.Order {
        val value = obj(raw, "orderRef", "offer", "amountUnits", "allocation", "paymentStatus", "cancelUntil", "fulfillment", "createdAt", "updatedAt")
        val payment = value.choice("paymentStatus", paymentStatuses)
        val offer = plan(value["offer"])
        val amount = value.units("amountUnits", positive = true).also { require(it <= offer.baseCoinUnits) }
        val created = time(value["createdAt"])
        val cancelUntil = time(value["cancelUntil"])
        val updated = time(value["updatedAt"])
        require(cancelUntil >= created && updated >= created) { "Invalid order chronology" }
        val fulfillment = obj(value["fulfillment"], "status", "safeCode")
        val status = fulfillment.choice("status", fulfillmentStatuses)
        when (payment) {
            "captured" -> require(status in setOf("pending", "processing", "delivered", "failed"))
            "reconciliation_required" -> require(status in setOf("not_requested", "delivered", "failed"))
            else -> require(status == "not_requested")
        }
        val safeCode = if (status == "failed") fulfillment.choice("safeCode", fulfillmentCodes) else {
            require(fulfillment["safeCode"] == null) { "Invalid fulfillment code" }; null
        }
        return PurchaseResult.Order(value.reference("orderRef", commerceRef), offer, amount,
            allocation(value["allocation"], amount, offer.acceptedCoinTypes), payment, status, safeCode, cancelUntil, created, updated)
    }

    private fun deferred(raw: Any?): PurchaseResult.Deferred {
        val value = obj(raw, "transitionRef", "offer", "amountUnits", "flowKind", "scheduledFor", "state", "safeCode", "reservedAt", "settledAt", "createdAt", "updatedAt")
        val offer = plan(value["offer"])
        val amount = value.units("amountUnits", positive = true).also { require(it <= offer.baseCoinUnits) }
        val flow = value.choice("flowKind", flowKinds)
        val state = value.choice("state", transitionStates)
        val safeCode = value.nullableReference("safeCode", commerceRef)
        val created = time(value["createdAt"])
        val reserved = time(value["reservedAt"])
        val updated = time(value["updatedAt"])
        val settled = value["settledAt"]?.let(::time)
        require(reserved >= created && updated >= created && (settled == null || settled >= reserved)) { "Invalid transition chronology" }
        return PurchaseResult.Deferred(value.reference("transitionRef", commerceRef), offer, amount, flow,
            time(value["scheduledFor"]), state, safeCode, reserved, settled, created, updated)
    }

    private fun rechargeOrderProjection(raw: Any?): RechargeOrder {
        val value = obj(raw, "orderRef", "offerRef", "quoteVersion", "expectedPaidCoinUnits", "amountMinor", "currency", "currencyExponent", "adapterKey", "paymentMethod", "status", "providerOrderRef", "nextAction", "walletOperationRef", "walletLotRef", "expiresAt", "createdAt", "updatedAt")
        val adapterKey = value.reference("adapterKey", rechargeRef)
        return RechargeOrder(value.reference("orderRef", rechargeRef), value.reference("offerRef", rechargeRef),
            value.units("quoteVersion", positive = true), value.units("expectedPaidCoinUnits", positive = true),
            value.units("amountMinor", positive = true), currency(value["currency"]), exponent(value["currencyExponent"]),
            adapterKey, value.choice("paymentMethod", paymentMethods.toSet()), value.choice("status", rechargeStatuses),
            value.nullableReference("providerOrderRef", rechargeRef), redirect(value["nextAction"], adapterKey),
            value.nullableReference("walletOperationRef", rechargeRef), value.nullableReference("walletLotRef", rechargeRef),
            time(value["expiresAt"]), time(value["createdAt"]), time(value["updatedAt"]))
    }

    private fun redirect(raw: Any?, adapterKey: String): PaymentRedirect? {
        if (raw == null) return null
        val value = obj(raw, "type", "url", "expiresAt")
        require(value["type"] == "redirect") { "Invalid payment action" }
        val url = value.text("url")
        require(url.all { it.code in 0x21..0x7e }) { "Invalid payment URL" }
        val uri = try { URI(url) } catch (_: Exception) { invalid() }
        val host = uri.host?.lowercase() ?: invalid()
        require(uri.scheme.equals("https", ignoreCase = true) && uri.rawUserInfo == null && uri.rawFragment == null &&
            uri.port in setOf(-1, 443) && !uri.isOpaque && !host.endsWith('.') &&
            host != "localhost" && !host.endsWith(".localhost") && !host.startsWith('[') &&
            !Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}").matches(host)) { "Unsafe payment URL" }
        require("https://$host" in (trustedActionOrigins[adapterKey] ?: emptySet())) { "Untrusted payment origin" }
        return PaymentRedirect(url, time(value["expiresAt"]))
    }

    private fun balance(raw: Any?): CoinBalance {
        val value = obj(raw, "bonusCoins", "paidCoins", "rewardCoins", "heldCoins")
        return CoinBalance(value.units("bonusCoins"), value.units("paidCoins"), value.units("rewardCoins"), value.units("heldCoins"))
    }

    private fun allocation(raw: Any?, amount: Long, accepted: List<String>, allowEmpty: Boolean = false): List<CoinAllocation> {
        val entries = array(raw)
        require(allowEmpty || entries.isNotEmpty()) { "Empty allocation" }
        var priority = -1
        var total = 0L
        val result = entries.map {
            val value = obj(it, "coinType", "amountUnits")
            val type = value.text("coinType")
            val next = coinPriority.indexOf(type)
            require(next > priority && type in accepted) { "Invalid allocation type" }
            priority = next
            val units = value.units("amountUnits", positive = true)
            total = add(total, units)
            CoinAllocation(type, units)
        }
        require(total == amount) { "Invalid allocation sum" }
        return result
    }

    private fun localized(raw: Any?): LocalizedText {
        val value = obj(raw, "zh", "en")
        return LocalizedText(value.text("zh"), value.text("en"))
    }

    private fun notices(raw: Any?): List<String> = array(raw).map(::normalizedText).also {
        require(it.isNotEmpty() && it.toSet().size == it.size) { "Invalid notices" }
    }

    private fun orderedChoices(raw: Any?, choices: List<String>): List<String> {
        val values = array(raw).map { it as? String ?: invalid() }
        require(values.isNotEmpty())
        var previous = -1
        values.forEach {
            val priority = choices.indexOf(it)
            require(priority > previous) { "Invalid choices" }
            previous = priority
        }
        return values
    }

    private fun root(body: String, vararg fields: String) = obj(StrictJson(body).parse(), *fields)
    private fun obj(raw: Any?, vararg fields: String): JsonObject = objectValue(raw).also {
        require(it.keys == fields.toSet()) { "Invalid object fields" }
    }
    @Suppress("UNCHECKED_CAST")
    private fun objectValue(raw: Any?): JsonObject = raw as? Map<String, Any?> ?: invalid()
    private fun array(raw: Any?): List<Any?> = raw as? List<*> ?: invalid()
    private fun normalizedText(raw: Any?): String = (raw as? String ?: invalid()).also {
        require(it.isNotEmpty() && it.trim() == it) { "Invalid text" }
    }
    private fun JsonObject.text(key: String) = normalizedText(this[key])
    private fun JsonObject.reference(key: String, expression: Regex) = text(key).also { require(expression.matches(it)) { "Invalid reference" } }
    private fun JsonObject.nullableReference(key: String, expression: Regex) = this[key]?.let { reference(key, expression) }
    private fun JsonObject.choice(key: String, choices: Set<String>) = text(key).also { require(it in choices) { "Invalid state" } }
    private fun JsonObject.units(key: String, positive: Boolean = false) = integer(this[key], if (positive) 1L else 0L)
    private fun integer(raw: Any?, minimum: Long = 0): Long {
        val number = raw as? JsonNumber ?: invalid()
        val result = try { BigDecimal(number.token).longValueExact() } catch (_: ArithmeticException) { invalid() }
        require(result in minimum..MAX_SAFE_COIN_UNITS) { "Invalid safe integer" }
        return result
    }
    private fun add(left: Long, right: Long) = (left + right).also { require(it in 0..MAX_SAFE_COIN_UNITS) { "Unsafe sum" } }
    private fun currency(raw: Any?) = (raw as? String ?: invalid()).also { require(Regex("[A-Z]{3}").matches(it)) }
    private fun exponent(raw: Any?) = integer(raw).also { require(it <= 9) }.toInt()
    private fun time(raw: Any?): Instant {
        val value = raw as? String ?: invalid()
        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z").matches(value)) { "Invalid timestamp" }
        val instant = try { Instant.parse(value) } catch (_: Exception) { invalid() }
        require(timestampFormat.format(instant) == value) { "Non-canonical timestamp" }
        return instant
    }
    private fun matchReference(actual: String, expected: String?) { require(expected == null || actual == expected) { "Mismatched reference" } }
    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid commerce projection")
}

private data class JsonNumber(val token: String)

/** Android's org.json accepts non-JSON syntax and can coerce numbers. Keep this boundary strict. */
private class StrictJson(private val input: String) {
    private var offset = 0
    fun parse(): Any? {
        require(input.length <= 1_048_576) { "Oversized commerce response" }
        val result = value(0)
        whitespace()
        require(offset == input.length) { "Trailing JSON content" }
        return result
    }
    private fun value(depth: Int): Any? {
        require(depth <= 64) { "Nested JSON too deep" }
        whitespace()
        require(offset < input.length) { "Missing JSON value" }
        return when (input[offset]) {
            '{' -> objectValue(depth + 1)
            '[' -> arrayValue(depth + 1)
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            '-', in '0'..'9' -> number()
            else -> throw IllegalArgumentException("Invalid JSON value")
        }
    }
    private fun objectValue(depth: Int): Map<String, Any?> {
        offset++
        val result = linkedMapOf<String, Any?>()
        whitespace()
        if (take('}')) return result
        do {
            whitespace()
            require(offset < input.length && input[offset] == '"') { "Invalid JSON key" }
            val key = string()
            require(!result.containsKey(key)) { "Duplicate JSON key" }
            whitespace(); expect(':')
            result[key] = value(depth)
            whitespace()
            if (take('}')) return result
            expect(',')
        } while (true)
    }
    private fun arrayValue(depth: Int): List<Any?> {
        offset++
        val result = mutableListOf<Any?>()
        whitespace()
        if (take(']')) return result
        do {
            result += value(depth)
            whitespace()
            if (take(']')) return result
            expect(',')
        } while (true)
    }
    private fun string(): String {
        expect('"')
        val result = StringBuilder()
        while (offset < input.length) {
            val next = input[offset++]
            when (next) {
                '"' -> return result.toString()
                '\\' -> {
                    require(offset < input.length) { "Truncated escape" }
                    when (val escaped = input[offset++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            require(offset + 4 <= input.length) { "Truncated Unicode escape" }
                            val hex = input.substring(offset, offset + 4)
                            require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Invalid Unicode escape" }
                            result.append(hex.toInt(16).toChar()); offset += 4
                        }
                        else -> throw IllegalArgumentException("Invalid JSON escape")
                    }
                }
                else -> { require(next.code >= 0x20) { "Unescaped control" }; result.append(next) }
            }
        }
        throw IllegalArgumentException("Unterminated JSON string")
    }
    private fun number(): JsonNumber {
        val start = offset
        take('-')
        if (!take('0')) digits()
        if (take('.')) digits()
        if (take('e') || take('E')) {
            if (!take('+')) take('-')
            digits()
        }
        val token = input.substring(start, offset)
        require(token.length <= 128) { "Oversized JSON number" }
        return JsonNumber(token)
    }
    private fun digits() {
        val start = offset
        while (offset < input.length && input[offset] in '0'..'9') offset++
        require(offset > start) { "Invalid JSON number" }
    }
    private fun literal(token: String, result: Any?): Any? {
        require(input.startsWith(token, offset)) { "Invalid JSON literal" }
        offset += token.length
        return result
    }
    private fun whitespace() { while (offset < input.length && input[offset] in " \t\r\n") offset++ }
    private fun take(char: Char): Boolean = if (offset < input.length && input[offset] == char) { offset++; true } else false
    private fun expect(char: Char) { require(take(char)) { "Invalid JSON delimiter" } }
}
