package com.gccwebos.android.commerce

import com.gccwebos.android.portal.*
import com.gccwebos.android.AccountForegroundObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.time.Instant

/** Real account restoration and commerce controllers, synthetic memory transport only. */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletForegroundResumeTest {
    private val now = Instant.parse(CommerceFixtures.instant)
    private class Recovery : CommerceRecovery {
        val records = mutableMapOf<Pair<String, String>, PendingCommerce>()
        override fun read(binding: String, kind: String) = records[binding to kind]
        override fun save(binding: String, value: PendingCommerce) { records[binding to value.kind] = value }
        override fun clear(binding: String, kind: String) { records.remove(binding to kind) }
    }
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
        fun start(observer: AccountForegroundObserver) {
            lifecycle.addObserver(observer)
            listOf(Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME)
                .forEach(lifecycle::handleLifecycleEvent)
        }
        fun browserReturn() = listOf(Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP,
            Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME).forEach(lifecycle::handleLifecycleEvent)
    }
    private inner class Fixture(scope: CoroutineScope, hasProfile: Boolean = false) {
        val recovery = Recovery()
        val sessions = MemorySessionStore().apply { save(saved()) }
        val profiles = MemoryLocalProfileStore().apply {
            if (hasProfile) {
                val url = Secret("https://sub.invalid/sub/a/wallet-profile-fixture")
                val bytes = """proxies:
  - name: fixture
    type: ss
    server: 192.0.2.30
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""".toByteArray()
                save(CachedSubscription(sessionBinding(url), bytes, now.plusSeconds(3600), url))
            }
        }
        val requests = mutableListOf<Pair<String, String?>>()
        val opened = mutableListOf<String>()
        var overview: suspend () -> WireResponse = { WireResponse(200, """{"resource":{"state":"none"}}""") }
        val account = AccountController(PortalRepository(object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                requests += path to body
                assertNotNull(session)
                return when (path) {
                    "/api/portal/resources/current/overview" -> overview()
                    "/api/portal/coins/wallet" -> WireResponse(200, CommerceFixtures.wallet)
                    "/api/portal/coins/recharge/offers" -> WireResponse(200, CommerceFixtures.rechargeOffers)
                    "/api/portal/coins/recharge/orders" -> {
                        assertNotNull(body); WireResponse(201, CommerceFixtures.rechargeOrder)
                    }
                    "/api/portal/coins/recharge/orders/recharge_fixture_order" -> {
                        assertNull(body); WireResponse(200, CommerceFixtures.rechargeOrder)
                    }
                    else -> throw AssertionError("Unexpected synthetic path: $path")
                }
            }
        }, object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = throw AssertionError("No delivery writes")
            override fun clear() = Unit
        }, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L }, { now },
            subscription = SubscriptionSource { throw AssertionError("No subscription reads") },
            sessions = sessions, profiles = profiles), scope)
        val posts get() = requests.count { it.second != null }
        fun saved(id: String = "wallet_resume_account_a", token: String = "wallet_resume_token_a") =
            SavedSession(Secret(token), id, now.plusSeconds(1800), 1800000, now, 0)
    }

    @Test fun sameSessionForegroundKeepsTheWalletControllerAndOnlyReadsTheOriginalPendingOrder() = runTest {
        val f = Fixture(backgroundScope)
        f.account.restoreAccount(); runCurrent()
        // Commerce belongs to an accepted native identity, never a display-email fixture.
        val controller = CommerceController(f.account, backgroundScope, f.recovery)
        controller.loadWallet(); runCurrent()
        controller.confirmRecharge(CommerceJson.rechargeOffers(CommerceFixtures.rechargeOffers).single()); runCurrent()
        val original = controller.recharge!!
        val flow = RechargeFlowController(f.account, controller) { f.opened += it }
        flow.reviewPayment(original, now); flow.openPayment(now)
        flow.reviewPayment(original, now) // A second unconsumed review cannot survive validation.
        assertEquals(1, f.posts); assertEquals(1, f.opened.size)
        val epoch = f.account.authSessionGeneration
        val owner = Owner().apply { start(AccountForegroundObserver(f.account)) }
        owner.browserReturn()
        controller.confirmRecharge(CommerceJson.rechargeOffers(CommerceFixtures.rechargeOffers).single())
        runCurrent()
        assertTrue(f.account.authenticated); assertFalse(f.account.localSubscription.hasProfile)
        assertTrue(f.account.authSessionGeneration > epoch)
        assertTrue("Same-session validation must not orphan the wallet controller", controller.currentSession())
        flow.openPayment(now)
        controller.pollRecharge()
        assertEquals(original.orderRef, controller.recharge?.orderRef)
        assertTrue(controller.pendingRecharge)
        assertEquals(1, f.posts); assertEquals(1, f.opened.size)
        assertTrue(f.requests.any { it.first.endsWith("/" + original.orderRef) && it.second == null })
    }

    @Test fun aRecreatedMineBalanceRestoresKnownPendingWithoutReplayingCreateOrPayment() = runTest {
        val f = Fixture(backgroundScope)
        f.account.restoreAccount(); runCurrent()
        val first = CommerceController(f.account, backgroundScope, f.recovery)
        first.loadWallet(); runCurrent()
        first.confirmRecharge(CommerceJson.rechargeOffers(CommerceFixtures.rechargeOffers).single()); runCurrent()
        val original = first.recharge!!
        val next = CommerceController(f.account, backgroundScope, f.recovery)
        next.loadBalance(); runCurrent()
        assertTrue("Mine balance must restore the durable pending flag", next.pendingRecharge)
        assertEquals(original.orderRef, next.recharge?.orderRef)
        assertEquals(1, f.posts); assertTrue(f.opened.isEmpty())
        assertTrue(next.walletLoaded)
    }

    @Test fun sameAccountTokenBindingSurvivesValidationWithAndWithoutLocalProfileButNotReplacement() = runTest {
        for (hasProfile in listOf(false, true)) {
            val f = Fixture(backgroundScope, hasProfile)
            if (hasProfile) { f.account.restoreLocal(); runCurrent() }
            f.account.restoreAccount(); runCurrent()
            assertEquals(hasProfile, f.account.localSubscription.hasProfile)
            val identity = f.account.commerceSessionIdentity!!
            val old = CommerceController(f.account, backgroundScope, f.recovery)
            val owner = Owner().apply { start(AccountForegroundObserver(f.account)) }
            owner.browserReturn()
            assertTrue(f.account.retainingCommerceSession)
            old.loadBalance(); assertFalse(old.busy)
            runCurrent()
            assertSame(identity, f.account.commerceSessionIdentity); assertTrue(old.currentSession())
            assertEquals(hasProfile, f.account.localSubscription.hasProfile)
            f.sessions.save(f.saved(token = "wallet_resume_token_b"))
            owner.browserReturn(); runCurrent()
            assertNotSame(identity, f.account.commerceSessionIdentity); assertFalse(old.currentSession())
            val other = f.account.commerceSessionIdentity!!
            f.sessions.save(f.saved(id = "wallet_resume_account_b", token = "wallet_resume_token_b"))
            owner.browserReturn(); runCurrent()
            assertNotSame(other, f.account.commerceSessionIdentity)
            assertTrue(f.requests.all { it.second == null }); assertTrue(f.opened.isEmpty())
        }
    }

    @Test fun unknownPendingRefSurvivesResumeWithoutCreatingOrGuessingAnOrder() = runTest {
        val f = Fixture(backgroundScope)
        f.account.restoreAccount(); runCurrent()
        val binding = sessionBinding(Secret("wallet_resume_account_a"))
        val pending = PendingCommerce("recharge", null, "recharge_fixture_10", "wallet_unknown_key")
        f.recovery.save(binding, pending)
        val controller = CommerceController(f.account, backgroundScope, f.recovery)
        controller.loadWallet(); runCurrent()
        val owner = Owner().apply { start(AccountForegroundObserver(f.account)) }
        owner.browserReturn(); runCurrent()
        controller.loadBalance(); runCurrent(); controller.pollRecharge()
        assertTrue(controller.pendingRecharge); assertNull(controller.recharge)
        assertEquals(pending, f.recovery.read(binding, "recharge"))
        assertEquals(0, f.posts); assertTrue(f.opened.isEmpty())
        assertFalse(f.requests.any { it.first.contains("/coins/recharge/orders") })
    }

    @Test fun failedOrMissingSessionValidationRevokesTheWalletWithoutDeletingAccountRecovery() = runTest {
        for (failure in listOf("network", "invalid", "missing")) {
            val f = Fixture(backgroundScope)
            f.account.restoreAccount(); runCurrent()
            val controller = CommerceController(f.account, backgroundScope, f.recovery)
            controller.loadWallet(); runCurrent()
            controller.confirmRecharge(CommerceJson.rechargeOffers(CommerceFixtures.rechargeOffers).single()); runCurrent()
            val binding = sessionBinding(Secret("wallet_resume_account_a"))
            val pending = f.recovery.read(binding, "recharge")!!
            val flow = RechargeFlowController(f.account, controller) { f.opened += it }
            flow.reviewPayment(controller.recharge!!, now)
            when (failure) {
                "network" -> f.overview = { throw PortalFailure(PortalFailure.Reason.NETWORK) }
                "invalid" -> f.overview = { WireResponse(401, "{}") }
                else -> f.sessions.clear()
            }
            val owner = Owner().apply { start(AccountForegroundObserver(f.account)) }
            owner.browserReturn(); runCurrent(); flow.openPayment(now)
            assertFalse(f.account.authenticated); assertNull(f.account.commerceSessionIdentity)
            assertFalse(f.account.retainingCommerceSession); assertFalse(controller.currentSession())
            assertEquals(pending, f.recovery.read(binding, "recharge")); assertEquals(1, f.posts)
            assertTrue(f.opened.isEmpty())
        }
    }

    @Test fun cancelledOldReadSuccessExpiryOrErrorCannotPublishOrClearANewerReadBusyState() = runTest {
        for (failure in listOf("success", "expired", "error")) {
            val f = Fixture(backgroundScope)
            f.account.restoreAccount(); runCurrent()
            val binding = sessionBinding(Secret("wallet_resume_account_a"))
            f.recovery.save(binding, PendingCommerce("recharge", null, "recharge_fixture_10",
                "wallet_late_key", "recharge_fixture_order"))
            val old = CompletableDeferred<Unit>(); val fresh = CompletableDeferred<Unit>()
            var reads = 0
            val repository = CommerceRepository({ path, body, key ->
                assertNull(body); assertNull(key)
                when (path) {
                    "/api/portal/coins/recharge/orders/recharge_fixture_order" -> {
                        if (++reads == 1) {
                            withContext(NonCancellable) { old.await() }
                            when (failure) {
                                "expired" -> throw PortalFailure(PortalFailure.Reason.SESSION_EXPIRED)
                                "error" -> throw IllegalStateException("synthetic late failure")
                            }
                        } else fresh.await()
                        WireResponse(200, CommerceFixtures.rechargeOrder)
                    }
                    "/api/portal/coins/wallet" -> WireResponse(200, CommerceFixtures.wallet)
                    else -> throw AssertionError("Unexpected read")
                }
            }, f.recovery, binding, { now })
            val controller = CommerceController(f.account, backgroundScope, f.recovery, initialRepository = repository)
            val sibling = backgroundScope.launch { awaitCancellation() }
            controller.loadBalance(); runCurrent(); assertTrue(controller.busy)
            controller.pauseForAuthentication()
            f.account.onForeground(); runCurrent()
            controller.loadBalance(); runCurrent()
            // The repository mutex keeps the new read queued until the old callback releases.
            old.complete(Unit); runCurrent()
            assertEquals(2, reads); assertTrue(controller.busy); assertTrue(sibling.isActive)
            assertNull(controller.recharge); assertNull(controller.wallet); assertNull(controller.error)
            assertTrue(f.account.authenticated)
            fresh.complete(Unit); runCurrent()
            assertFalse(controller.busy); assertTrue(controller.walletLoaded)
            assertEquals("recharge_fixture_order", controller.recharge?.orderRef)
            assertEquals(0, f.posts); assertTrue(f.opened.isEmpty())
        }
    }

    @Test fun cancelledValidationCompletesCleanupBeforeNewSessionRestoration() = runTest {
        val f = Fixture(backgroundScope)
        f.account.restoreAccount(); runCurrent()
        val firstIdentity = f.account.commerceSessionIdentity!!
        val old = CompletableDeferred<Unit>(); val fresh = CompletableDeferred<Unit>()
        var reads = 0
        f.overview = {
            if (++reads == 1) withContext(NonCancellable) { old.await() } else fresh.await()
            WireResponse(200, """{"resource":{"state":"none"}}""")
        }
        f.account.onForeground(); runCurrent()
        f.account.cancelAuthentication(); runCurrent()
        assertTrue(f.account.clearing); assertTrue(f.account.busy)
        // Logout drains the repository mutex before it permits another session admission.
        f.account.restoreAccount(); runCurrent()
        old.complete(Unit); runCurrent()
        assertFalse(f.account.clearing); assertFalse(f.account.busy)
        assertNull(f.account.commerceSessionIdentity)
        f.sessions.save(f.saved(token = "wallet_resume_token_c"))
        f.account.restoreAccount(); runCurrent()
        assertTrue(f.account.restoring); assertTrue(f.account.restorationPending)
        assertTrue(f.account.busy); assertNull(f.account.commerceSessionIdentity)
        fresh.complete(Unit); runCurrent()
        assertTrue(f.account.authenticated); assertFalse(f.account.restoring)
        assertNotSame(firstIdentity, f.account.commerceSessionIdentity)
        assertEquals(0, f.posts); assertTrue(f.opened.isEmpty())
    }

    @Test fun latePurchaseRechargeAndFinishResultsCannotPublishAcrossARevalidatedGeneration() = runTest {
        for (action in listOf("purchase", "queryPurchase", "cancelPurchase", "finishPurchase",
            "recharge", "queryRecharge", "finishRecharge")) {
            val f = Fixture(backgroundScope)
            f.account.restoreAccount(); runCurrent()
            val release = CompletableDeferred<Unit>()
            val binding = sessionBinding(Secret("wallet_resume_account_a"))
            val purchaseAction = action.contains("Purchase") || action == "purchase"
            if (action != "purchase" && action != "recharge") f.recovery.save(binding,
                if (purchaseAction) PendingCommerce("purchase", CommerceModel.LEGACY, "quote_fixture_plan",
                    "wallet_late_purchase_key", "order_fixture_plan")
                else PendingCommerce("recharge", null, "recharge_fixture_10", "wallet_late_recharge_key", "recharge_fixture_order"))
            val purchaseBody = if (action == "finishPurchase") JSONObject(CommerceFixtures.order).apply {
                getJSONObject("order").put("paymentStatus", "cancelled")
            }.toString() else CommerceFixtures.order
            val rechargeBody = if (action == "finishRecharge") JSONObject(CommerceFixtures.rechargeOrder).apply {
                getJSONObject("recharge").getJSONObject("order").put("status", "expired").put("nextAction", JSONObject.NULL)
            }.toString() else CommerceFixtures.rechargeOrder
            val repository = CommerceRepository({ path, body, _ ->
                if (path == "/api/portal/commerce/model") WireResponse(200, CommerceFixtures.legacyModel)
                else {
                    if (!(action == "cancelPurchase" && body == null)) withContext(NonCancellable) { release.await() }
                    WireResponse(if (action in listOf("purchase", "recharge")) 201 else 200,
                        if (purchaseAction) purchaseBody else rechargeBody)
                }
            }, f.recovery, binding, { now }, { "wallet_late_create_key" })
            repository.loadModel()
            val controller = CommerceController(f.account, backgroundScope, f.recovery, initialRepository = repository)
            controller.pendingPurchase = purchaseAction && action != "purchase"
            controller.pendingRecharge = !purchaseAction && action != "recharge"
            when (action) {
                "purchase" -> { controller.quote = CommerceJson.quote(CommerceFixtures.quote, CommerceModel.LEGACY); controller.confirmPurchase() }
                "queryPurchase" -> controller.queryPurchase()
                "cancelPurchase" -> controller.cancelPurchase()
                "finishPurchase" -> controller.finishPurchase()
                "recharge" -> controller.confirmRecharge(CommerceJson.rechargeOffers(CommerceFixtures.rechargeOffers).single())
                "queryRecharge" -> controller.queryRecharge()
                else -> controller.finishRecharge()
            }
            runCurrent(); assertTrue(controller.busy)
            // No controller disposal/cancellation: the immutable logical epoch must itself reject this callback.
            f.account.onForeground(); runCurrent()
            release.complete(Unit); runCurrent()
            assertTrue(f.account.authenticated); assertTrue(controller.currentSession())
            assertNull(controller.purchase); assertNull(controller.recharge); assertNull(controller.wallet)
            assertNull(controller.error); assertFalse(controller.busy)
            if (action == "finishPurchase") assertTrue(controller.pendingPurchase)
            if (action == "finishRecharge") assertTrue(controller.pendingRecharge)
        }
    }
}
