package com.gccwebos.android.commerce

import com.gccwebos.android.portal.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Synthetic orders, memory storage and an in-memory opener; no payment or Portal requests. */
@OptIn(ExperimentalCoroutinesApi::class)
class RechargeFlowControllerTest {
    private val now = Instant.parse(CommerceFixtures.instant)
    private val offer = CommerceJson.rechargeOffers(CommerceFixtures.rechargeOffers).single()
    private val order = CommerceJson.rechargeOrder(CommerceFixtures.rechargeOrder)

    private class MemoryRecovery : CommerceRecovery {
        val records = mutableMapOf<Pair<String, String>, PendingCommerce>()
        val saved = mutableListOf<PendingCommerce>()
        var clears = 0
        override fun read(binding: String, kind: String) = records[binding to kind]
        override fun save(binding: String, value: PendingCommerce) {
            records[binding to value.kind] = value
            saved += value
        }
        override fun clear(binding: String, kind: String) {
            clears++
            records.remove(binding to kind)
        }
    }

    private data class Call(val path: String, val body: String?, val key: String?)

    private fun account(scope: CoroutineScope) = AccountController(PortalRepository(
        object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse =
                throw AssertionError("No real Portal transport")
        }, object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = throw AssertionError("No delivery writes")
            override fun clear() = Unit
        }, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L }, { now },
        subscription = SubscriptionSource { throw AssertionError("No subscription requests") },
        sessions = MemorySessionStore(), profiles = MemoryLocalProfileStore()), scope).apply { authenticated = true }

    private inner class Fixture(scope: CoroutineScope, beforeCreate: suspend () -> Unit = {},
        opener: ((String) -> Unit)? = null) {
        val account = this@RechargeFlowControllerTest.account(scope)
        val recovery = MemoryRecovery()
        val calls = mutableListOf<Call>()
        val opened = mutableListOf<String>()
        val repo = CommerceRepository({ path, body, key ->
            calls += Call(path, body, key)
            when (path) {
                "/api/portal/coins/recharge/orders" -> {
                    assertNotNull(body); assertNull(key)
                    assertNotNull(recovery.read("synthetic_account", "recharge"))
                    beforeCreate()
                    WireResponse(201, CommerceFixtures.rechargeOrder)
                }
                "/api/portal/coins/wallet" -> {
                    assertNull(body); assertNull(key)
                    WireResponse(200, CommerceFixtures.wallet)
                }
                else -> throw AssertionError("Unexpected synthetic recharge path: $path")
            }
        }, recovery, "synthetic_account", { now }, { "synthetic_recharge_key" })
        val commerce = CommerceController(account, scope, recovery, initialRepository = repo).apply {
            rechargeOffers = listOf(offer)
            rechargeOffersLoaded = true
        }
        val flow = RechargeFlowController(account, commerce, clock = { now }, opener = opener ?: { opened += it })
        fun installPending(value: RechargeOrder = order) {
            recovery.save("synthetic_account", PendingCommerce("recharge", null, value.offerRef,
                "synthetic_recharge_key", value.orderRef))
            commerce.recharge = value
            commerce.pendingRecharge = true
        }
    }

    @Test fun tappingRechargeCreatesTheOrderAndOpensPaymentAtOnceWithNoDialogStep() = runTest {
        val fixture = Fixture(this)
        fixture.flow.rechargeNow(offer); runCurrent()
        assertEquals(1, fixture.calls.count { it.path == "/api/portal/coins/recharge/orders" })
        assertEquals(listOf(requireNotNull(order.nextAction).url), fixture.opened)
        assertEquals(order.orderRef, fixture.flow.requestedPaymentFor)
        assertNull(fixture.flow.error)
        // There is no confirmation or review state left on the controller at all.
        assertTrue(RechargeFlowController::class.java.declaredFields.none { it.name in setOf("selectedOffer", "paymentReview") })
    }

    @Test fun everyChangedOfferSnapshotStopsBeforePostingAndAsksForReselection() = runTest {
        val replacements = listOf(
            emptyList(), listOf(offer.copy(offerRef = "recharge_fixture_new")),
            listOf(offer.copy(quoteVersion = 2)), listOf(offer.copy(paidCoinUnits = 11)),
            listOf(offer.copy(amountMinor = 1100)), listOf(offer.copy(currency = "USD")),
            listOf(offer.copy(currencyExponent = 3)), listOf(offer.copy(paymentMethods = listOf("wechat_pay"))),
        )
        for (replacement in replacements) {
            val fixture = Fixture(this)
            fixture.commerce.rechargeOffers = replacement
            fixture.flow.rechargeNow(offer); runCurrent()
            assertNotNull(fixture.flow.error)
            assertTrue(fixture.calls.isEmpty()); assertTrue(fixture.recovery.saved.isEmpty()); assertTrue(fixture.opened.isEmpty())
        }
    }

    @Test fun repeatedTapsPostOnceWithKeyDurableBeforeResponse() = runTest {
        val release = CompletableDeferred<Unit>()
        val fixture = Fixture(this, beforeCreate = { release.await() })
        fixture.flow.rechargeNow(offer); fixture.flow.rechargeNow(offer); runCurrent()
        assertTrue(fixture.commerce.busy)
        assertEquals(1, fixture.calls.size)
        val pending = requireNotNull(fixture.repo.pending("recharge"))
        assertEquals("synthetic_recharge_key", pending.key); assertNull(pending.resultRef)
        val submitted = JSONObject(requireNotNull(fixture.calls.single().body))
        assertEquals(setOf("offerRef", "idempotencyKey"), submitted.keys().asSequence().toSet())
        assertEquals(offer.offerRef, submitted.getString("offerRef"))
        assertEquals(pending.key, submitted.getString("idempotencyKey"))
        release.complete(Unit); runCurrent()
        assertEquals(1, fixture.calls.size); assertEquals(1, fixture.opened.size)
        assertTrue(fixture.commerce.pendingRecharge)
        assertEquals(order.orderRef, fixture.repo.pending("recharge")?.resultRef)
        assertTrue(fixture.recovery.saved.all { it.key == pending.key }); assertEquals(0, fixture.recovery.clears)
    }

    @Test fun aPendingUnpaidRechargeNeverBlocksStartingANewOne() = runTest {
        val fixture = Fixture(this)
        // An earlier order is still awaiting payment (its entry may well be expired).
        fixture.recovery.save("synthetic_account", PendingCommerce("recharge", null, offer.offerRef, "older_key", "recharge_older_order"))
        fixture.commerce.pendingRecharge = true
        fixture.flow.rechargeNow(offer); runCurrent()
        assertEquals(1, fixture.calls.count { it.path == "/api/portal/coins/recharge/orders" })
        // The new order replaces the local pointer; the server keeps the older one in its history.
        assertEquals("synthetic_recharge_key", fixture.repo.pending("recharge")?.key)
        assertEquals(order.orderRef, fixture.repo.pending("recharge")?.resultRef)
        assertEquals(order.orderRef, fixture.commerce.recharge?.orderRef)
    }

    @Test fun aPayableUnpaidOrderListedInOrderDetailsOpensItsEntryDirectlyWithNoReviewAndNoRequest() = runTest {
        val fixture = Fixture(this)
        // Not the current local order (that pointer was replaced by a newer recharge); it only lives in 订单明细.
        fixture.commerce.rechargeHistory = listOf(order)
        assertNull(fixture.commerce.recharge)
        assertTrue(fixture.commerce.canOpenPayment(order, now))
        fixture.flow.payNow(order, now)
        assertEquals(listOf(requireNotNull(order.nextAction).url), fixture.opened)
        assertEquals(order.orderRef, fixture.flow.requestedPaymentFor)
        assertTrue("paying never creates or posts anything", fixture.calls.isEmpty())
    }

    @Test fun payNowOnAnOrderThatCannotBePaidOpensNothing() = runTest {
        val fixture = Fixture(this)
        fixture.commerce.rechargeHistory = listOf(order)
        fixture.flow.payNow(order, order.expiresAt)           // past its own expiry
        fixture.flow.payNow(order.copy(orderRef = "recharge_other"), now) // not in the list
        assertTrue(fixture.opened.isEmpty()); assertNull(fixture.flow.requestedPaymentFor); assertNotNull(fixture.flow.error)
    }

    @Test fun aNewOrderWhoseServerFiguresDifferFromTheSelectedOfferOpensNothingAndShowsOneInlineMessage() = runTest {
        val fixture = Fixture(this)
        // The server prices the order differently from what the user selected: never open an unseen amount.
        val shown = offer.copy(amountMinor = offer.amountMinor + 100)
        fixture.commerce.rechargeOffers = listOf(shown)
        fixture.flow.rechargeNow(shown); runCurrent()
        assertTrue(fixture.opened.isEmpty()); assertNull(fixture.flow.requestedPaymentFor)
        val message = requireNotNull(fixture.flow.error)
        assertFalse(Regex("https?:|xsd8866|gccwebos|\\.com", RegexOption.IGNORE_CASE).containsMatchIn(message))
        // The order is kept (idempotent record) so it can be paid from 订单明细 where its own amount is shown.
        assertEquals(order.orderRef, fixture.commerce.recharge?.orderRef)
        assertTrue(fixture.commerce.pendingRecharge)
    }

    @Test fun anOrderThatIsNotListedExpiredOrWithoutAnEntryIsNeverPayable() = runTest {
        val fixture = Fixture(this)
        fixture.commerce.rechargeHistory = listOf(order)
        assertFalse("unknown order", fixture.commerce.canOpenPayment(order.copy(orderRef = "recharge_other"), now))
        assertFalse("past the order expiry", fixture.commerce.canOpenPayment(order, order.expiresAt))
        fixture.commerce.rechargeHistory = listOf(order.copy(nextAction = null))
        assertFalse("no payment entry", fixture.commerce.canOpenPayment(order.copy(nextAction = null), now))
        fixture.commerce.rechargeHistory = listOf(order.copy(status = "credited"))
        assertFalse("already paid", fixture.commerce.canOpenPayment(order.copy(status = "credited"), now))
    }

    @Test fun unreadOrUnreadySessionCannotStartARecharge() = runTest {
        for (gate in listOf("unread", "auth", "restoring", "foreground", "busy", "method")) {
            val fixture = Fixture(this)
            when (gate) {
                "unread" -> fixture.commerce.rechargeOffersLoaded = false
                "auth" -> fixture.account.authenticated = false
                "restoring" -> fixture.account.restoring = true
                "foreground" -> fixture.account.foregroundCheckPending = true
                "busy" -> fixture.commerce.busy = true
                "method" -> fixture.commerce.rechargeOffers = listOf(offer.copy(paymentMethods = listOf("wechat_pay")))
            }
            fixture.flow.rechargeNow(offer); runCurrent()
            assertTrue(fixture.calls.isEmpty()); assertTrue(fixture.recovery.saved.isEmpty()); assertTrue(fixture.opened.isEmpty())
        }
    }

    @Test fun anUnsafeOrExpiredOrderFromOrderDetailsNeverOpens() = runTest {
        val action = requireNotNull(order.nextAction)
        val invalid = listOf(
            order.copy(expiresAt = now), order.copy(nextAction = PaymentRedirect(action.url, now)),
            order.copy(nextAction = PaymentRedirect("https://untrusted.invalid/pay", action.expiresAt)),
            order.copy(nextAction = PaymentRedirect("http://www.xsd8866.com/pay", action.expiresAt)),
            order.copy(nextAction = PaymentRedirect("https://www.xsd8866.com/pay#fragment", action.expiresAt)),
            order.copy(paymentMethod = "wechat_pay"), order.copy(adapterKey = "unknown_adapter"),
            order.copy(status = "payment_verified"),
        )
        for (value in invalid) {
            val fixture = Fixture(this)
            fixture.commerce.rechargeHistory = listOf(value)
            fixture.flow.payNow(value, now)
            assertNull(fixture.flow.requestedPaymentFor)
            assertNotNull(fixture.flow.error); assertTrue(fixture.opened.isEmpty()); assertTrue(fixture.calls.isEmpty())
        }
        for (gate in listOf("restoring", "foreground", "busy")) {
            val fixture = Fixture(this)
            fixture.commerce.rechargeHistory = listOf(order)
            when (gate) {
                "restoring" -> fixture.account.restoring = true
                "foreground" -> fixture.account.foregroundCheckPending = true
                "busy" -> fixture.commerce.busy = true
            }
            fixture.flow.payNow(order, now)
            assertTrue(fixture.opened.isEmpty())
        }
    }

    @Test fun changedGenerationAndDisposedOrFailedOpenerCannotReviveAnything() = runTest {
        val tapped = Fixture(this)
        tapped.account.authSessionGeneration++
        tapped.flow.rechargeNow(offer); runCurrent()
        assertTrue(tapped.calls.isEmpty()); assertTrue(tapped.recovery.saved.isEmpty())

        var openerCalls = 0
        val failed = Fixture(this, opener = { openerCalls++; throw IllegalStateException("Synthetic opener failure") })
        failed.commerce.rechargeHistory = listOf(order)
        failed.flow.payNow(order, now)
        assertEquals(1, openerCalls); assertNull(failed.flow.requestedPaymentFor)
        assertNotNull(failed.flow.error); assertFalse(failed.flow.error!!.contains(requireNotNull(order.nextAction).url))
        failed.flow.dispose(); failed.flow.payNow(order, now); failed.flow.rechargeNow(offer); runCurrent()
        assertNull(failed.flow.error); assertNull(failed.flow.requestedPaymentFor)
        assertEquals(1, openerCalls); assertTrue(failed.calls.isEmpty()); assertEquals(0, failed.recovery.clears)
    }
}
