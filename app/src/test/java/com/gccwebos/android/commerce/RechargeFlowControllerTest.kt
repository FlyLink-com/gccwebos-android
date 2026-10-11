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

    @Test fun selectionAndCancellationStayLocalWithoutCreatingOrClearingRecovery() = runTest {
        val fixture = Fixture(this)
        fixture.flow.select(offer)
        assertEquals(offer, fixture.flow.selectedOffer)
        fixture.flow.cancelSelection()
        fixture.flow.confirmSelection()
        runCurrent()
        assertNull(fixture.flow.selectedOffer)
        assertTrue(fixture.calls.isEmpty()); assertTrue(fixture.recovery.saved.isEmpty())
        assertEquals(0, fixture.recovery.clears); assertFalse(fixture.commerce.pendingRecharge)
    }

    @Test fun everyChangedOfferSnapshotRequiresReselectionWithoutPosting() = runTest {
        val replacements = listOf(
            emptyList(), listOf(offer.copy(offerRef = "recharge_fixture_new")),
            listOf(offer.copy(quoteVersion = 2)), listOf(offer.copy(paidCoinUnits = 11)),
            listOf(offer.copy(amountMinor = 1100)), listOf(offer.copy(currency = "USD")),
            listOf(offer.copy(currencyExponent = 3)), listOf(offer.copy(paymentMethods = listOf("wechat_pay"))),
        )
        for (replacement in replacements) {
            val fixture = Fixture(this)
            fixture.flow.select(offer)
            fixture.commerce.rechargeOffers = replacement
            fixture.flow.confirmSelection(); runCurrent()
            assertNull(fixture.flow.selectedOffer); assertNotNull(fixture.flow.error)
            assertTrue(fixture.calls.isEmpty()); assertTrue(fixture.recovery.saved.isEmpty())
        }
    }

    @Test fun repeatedConfirmationPostsOnceWithKeyDurableBeforeResponse() = runTest {
        val release = CompletableDeferred<Unit>()
        val fixture = Fixture(this, beforeCreate = { release.await() })
        fixture.flow.select(offer)
        fixture.flow.confirmSelection(); fixture.flow.confirmSelection(); runCurrent()
        assertTrue(fixture.commerce.busy); assertNull(fixture.flow.selectedOffer)
        assertEquals(1, fixture.calls.size)
        val pending = requireNotNull(fixture.repo.pending("recharge"))
        assertEquals("synthetic_recharge_key", pending.key); assertNull(pending.resultRef)
        val submitted = JSONObject(requireNotNull(fixture.calls.single().body))
        assertEquals(setOf("offerRef", "idempotencyKey"), submitted.keys().asSequence().toSet())
        assertEquals(offer.offerRef, submitted.getString("offerRef"))
        assertEquals(pending.key, submitted.getString("idempotencyKey"))
        release.complete(Unit); runCurrent()
        fixture.flow.confirmSelection(); runCurrent()
        assertEquals(1, fixture.calls.size); assertFalse(fixture.commerce.busy)
        assertTrue(fixture.commerce.pendingRecharge)
        assertEquals(order.orderRef, fixture.repo.pending("recharge")?.resultRef)
        assertTrue(fixture.recovery.saved.all { it.key == pending.key }); assertEquals(0, fixture.recovery.clears)
    }

    @Test fun aPendingUnpaidRechargeNeverBlocksStartingANewOne() = runTest {
        val fixture = Fixture(this)
        // An earlier order is still awaiting payment (its entry may well be expired).
        fixture.recovery.save("synthetic_account", PendingCommerce("recharge", null, offer.offerRef, "older_key", "recharge_older_order"))
        fixture.commerce.pendingRecharge = true
        fixture.flow.select(offer)
        assertEquals(offer, fixture.flow.selectedOffer)
        fixture.flow.confirmSelection(); runCurrent()
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
        assertNull("no review step", fixture.flow.paymentReview)
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

    @Test fun aNewOrderWhoseServerFiguresMatchTheConfirmedOfferOpensPaymentAtOnce() = runTest {
        val fixture = Fixture(this)
        fixture.flow.select(offer)
        fixture.flow.confirmSelection(); runCurrent()
        assertEquals(1, fixture.calls.count { it.path == "/api/portal/coins/recharge/orders" })
        assertEquals(listOf(requireNotNull(order.nextAction).url), fixture.opened)
        assertNull("no second step", fixture.flow.paymentReview)
        assertEquals(order.orderRef, fixture.flow.requestedPaymentFor)
    }

    @Test fun aNewOrderWhoseServerFiguresDifferFromTheConfirmedOfferStopsAtTheReview() = runTest {
        val fixture = Fixture(this)
        // The user confirmed a different amount than the server then priced: never open an unseen amount.
        val shown = offer.copy(amountMinor = offer.amountMinor + 100)
        fixture.commerce.rechargeOffers = listOf(shown)
        fixture.flow.select(shown)
        fixture.flow.confirmSelection(); runCurrent()
        assertTrue(fixture.opened.isEmpty())
        assertEquals(order.orderRef, fixture.flow.paymentReview?.orderRef)
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
        fixture.flow.reviewPayment(order.copy(orderRef = "recharge_other"), now)
        assertNull(fixture.flow.paymentReview); assertNotNull(fixture.flow.error)
    }

    @Test fun unreadOrUnreadySessionCannotSelectOrConfirm() = runTest {
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
            fixture.flow.select(offer); fixture.flow.confirmSelection(); runCurrent()
            assertNull(fixture.flow.selectedOffer)
            assertTrue(fixture.calls.isEmpty()); assertTrue(fixture.recovery.saved.isEmpty())
        }
        for (gate in listOf("unread", "restoring", "foreground", "busy")) {
            val fixture = Fixture(this)
            fixture.flow.select(offer)
            when (gate) {
                "unread" -> fixture.commerce.rechargeOffersLoaded = false
                "restoring" -> fixture.account.restoring = true
                "foreground" -> fixture.account.foregroundCheckPending = true
                "busy" -> fixture.commerce.busy = true
            }
            fixture.flow.confirmSelection(); runCurrent()
            assertTrue(fixture.calls.isEmpty()); assertTrue(fixture.recovery.saved.isEmpty())
        }
    }

    @Test fun dismissingReviewPreservesPendingOrderAndNeverInvokesOpener() = runTest {
        val fixture = Fixture(this)
        fixture.installPending()
        val before = fixture.repo.pending("recharge")
        fixture.flow.reviewPayment(order, now)
        assertEquals(order.orderRef, fixture.flow.paymentReview?.orderRef)
        fixture.flow.dismissPayment(); fixture.flow.openPayment(now)
        assertNull(fixture.flow.paymentReview); assertNull(fixture.flow.requestedPaymentFor)
        assertTrue(fixture.opened.isEmpty()); assertTrue(fixture.calls.isEmpty())
        assertEquals(before, fixture.repo.pending("recharge")); assertEquals(1, fixture.recovery.saved.size)
        assertEquals(0, fixture.recovery.clears); assertEquals("entry_ready", fixture.commerce.recharge?.status)
    }

    @Test fun repeatedOpenConsumesOneReviewAndOnlyRecordsRequestedOpening() = runTest {
        val fixture = Fixture(this)
        fixture.installPending()
        val before = fixture.repo.pending("recharge")
        fixture.flow.reviewPayment(order, now)
        fixture.flow.openPayment(now); fixture.flow.openPayment(now)
        assertEquals(1, fixture.opened.size); assertNull(fixture.flow.paymentReview)
        assertEquals(order.orderRef, fixture.flow.requestedPaymentFor)
        assertEquals(order, fixture.commerce.recharge); assertNull(fixture.commerce.wallet)
        assertEquals(before, fixture.repo.pending("recharge")); assertEquals(0, fixture.recovery.clears)
        assertTrue(fixture.calls.isEmpty())
    }

    @Test fun unsafeActionAndBothExpiryBoundariesOrUpdatedOrderNeverOpen() = runTest {
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
            fixture.installPending(value)
            fixture.flow.reviewPayment(value, now); fixture.flow.openPayment(now)
            assertNull(fixture.flow.paymentReview); assertNull(fixture.flow.requestedPaymentFor)
            assertNotNull(fixture.flow.error); assertTrue(fixture.opened.isEmpty()); assertTrue(fixture.calls.isEmpty())
        }
        for (value in listOf(order.copy(expiresAt = now.plusSeconds(1)),
            order.copy(nextAction = PaymentRedirect(action.url, now.plusSeconds(1))))) {
            val fixture = Fixture(this)
            fixture.installPending(value); fixture.flow.reviewPayment(value, now)
            assertEquals(value, fixture.flow.paymentReview)
            fixture.flow.openPayment(now.plusSeconds(1))
            assertTrue(fixture.opened.isEmpty()); assertNotNull(fixture.flow.error)
        }
        for (gate in listOf("restoring", "foreground", "busy")) {
            val fixture = Fixture(this)
            fixture.installPending(); fixture.flow.reviewPayment(order, now)
            when (gate) {
                "restoring" -> fixture.account.restoring = true
                "foreground" -> fixture.account.foregroundCheckPending = true
                "busy" -> fixture.commerce.busy = true
            }
            fixture.flow.openPayment(now)
            assertNull(fixture.flow.paymentReview); assertTrue(fixture.opened.isEmpty())
        }
        val fixture = Fixture(this)
        fixture.installPending(); fixture.flow.reviewPayment(order, now)
        fixture.commerce.recharge = order.copy(status = "credited")
        fixture.flow.openPayment(now)
        assertTrue(fixture.opened.isEmpty()); assertNotNull(fixture.flow.error)
    }

    @Test fun changedGenerationAndDisposedOrFailedOpenerCannotReviveLocalConfirmation() = runTest {
        val selected = Fixture(this)
        selected.flow.select(offer); selected.account.authSessionGeneration++
        selected.flow.confirmSelection(); runCurrent()
        assertTrue(selected.calls.isEmpty()); assertTrue(selected.recovery.saved.isEmpty())

        val reviewed = Fixture(this)
        reviewed.installPending(); reviewed.flow.reviewPayment(order, now)
        reviewed.account.authSessionGeneration++; reviewed.flow.openPayment(now)
        assertTrue(reviewed.opened.isEmpty()); assertNull(reviewed.flow.requestedPaymentFor)
        assertEquals(order, reviewed.commerce.recharge)

        var openerCalls = 0
        val failed = Fixture(this, opener = { openerCalls++; throw IllegalStateException("Synthetic opener failure") })
        failed.installPending(); failed.flow.reviewPayment(order, now)
        failed.flow.openPayment(now); failed.flow.openPayment(now)
        assertEquals(1, openerCalls); assertNull(failed.flow.requestedPaymentFor)
        assertNotNull(failed.flow.error); assertFalse(failed.flow.error!!.contains(requireNotNull(order.nextAction).url))
        failed.flow.dispose(); failed.flow.reviewPayment(order, now); failed.flow.select(offer); failed.flow.openPayment(now)
        assertNull(failed.flow.paymentReview); assertNull(failed.flow.selectedOffer); assertNull(failed.flow.error)
        assertEquals(1, openerCalls); assertTrue(failed.calls.isEmpty()); assertEquals(0, failed.recovery.clears)
    }
}
