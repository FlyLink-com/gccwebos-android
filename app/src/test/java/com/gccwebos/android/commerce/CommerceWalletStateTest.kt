package com.gccwebos.android.commerce

import com.gccwebos.android.portal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Memory-only transport: checks read/mutation boundaries and late publication, never real payments. */
@OptIn(ExperimentalCoroutinesApi::class)
class CommerceWalletStateTest {
    private val now = Instant.parse(CommerceFixtures.instant)
    private class MemoryRecovery : CommerceRecovery {
        var value: PendingCommerce? = null
        override fun read(binding: String, kind: String) = value?.takeIf { it.kind == kind }
        override fun save(binding: String, value: PendingCommerce) { this.value = value }
        override fun clear(binding: String, kind: String) { value = null }
    }
    private fun account(scope: CoroutineScope) = AccountController(PortalRepository(
        object : PortalTransport { override suspend fun request(path: String, body: String?, session: Secret?): WireResponse = throw AssertionError("No Portal transport") },
        object : DeliveryStore { override fun read(): DeliveryRecord? = null; override fun save(record: DeliveryRecord) = throw AssertionError("No delivery writes"); override fun clear() = Unit },
        OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L }, { now },
        subscription = SubscriptionSource { throw AssertionError("No subscription requests") },
        sessions = MemorySessionStore(), profiles = MemoryLocalProfileStore()), scope).apply { authenticated = true }
    private fun known(recovery: MemoryRecovery) { recovery.value = PendingCommerce("recharge", null, "recharge_fixture_10", "synthetic_key", "recharge_fixture_order") }
    private fun repo(recovery: MemoryRecovery, calls: MutableList<Pair<String, String?>>, reply: suspend (String) -> WireResponse? = { null }) =
        CommerceRepository({ path, body, key ->
            assertNull(key); calls += path to body
            reply(path) ?: when (path) {
                "/api/portal/coins/wallet" -> WireResponse(200, CommerceFixtures.wallet)
                "/api/portal/coins/recharge/offers" -> WireResponse(200, CommerceFixtures.rechargeOffers)
                "/api/portal/coins/recharge/orders" -> WireResponse(201, CommerceFixtures.rechargeOrder)
                "/api/portal/coins/recharge/orders/recharge_fixture_order" -> WireResponse(200, CommerceFixtures.rechargeOrder)
                else -> throw AssertionError("Unexpected wallet path")
            }
        }, recovery, "synthetic_account", { now }, { "synthetic_key" })

    @Test fun walletOnlyLoadHasNoPurchaseModelDependencyAndUnknownCreateNeverReplays() = runTest {
        val recovery = MemoryRecovery().apply { value = PendingCommerce("recharge", null, "recharge_fixture_10", "synthetic_key", null) }
        val calls = mutableListOf<Pair<String, String?>>()
        val state = CommerceController(account(this), this, recovery, initialRepository = repo(recovery, calls))
        state.loadWallet(); state.loadWallet(); runCurrent()
        assertEquals(listOf("/api/portal/coins/wallet", "/api/portal/coins/recharge/offers"), calls.map { it.first })
        assertTrue(calls.all { it.second == null }); assertTrue(state.walletLoaded); assertTrue(state.rechargeOffersLoaded)
        assertEquals(20L, state.wallet!!.spendableUnits); assertTrue(state.pendingRecharge); assertNull(state.recharge)
        assertNull(state.commerceModel); assertFalse(state.catalogLoaded); assertEquals("synthetic_key", recovery.value!!.key)
    }
    @Test fun readFailureIsUnknownWhileConfirmedZeroAndEmptyDirectoryAreDistinct() = runTest {
        for (fail in listOf(true, false)) {
            val recovery = MemoryRecovery(); val calls = mutableListOf<Pair<String, String?>>()
            val repository = repo(recovery, calls) { path ->
                when (path) {
                    "/api/portal/coins/wallet" -> WireResponse(if (fail) 503 else 200, if (fail) "{}" else CommerceFixtures.wallet.replace("\"active\"", "\"not_created\"").replace(":20", ":0").replace("\"version\":1", "\"version\":0"))
                    "/api/portal/coins/recharge/offers" -> WireResponse(if (fail) 503 else 200, if (fail) "{}" else """{"offers":[]}""")
                    else -> null
                }
            }
            val state = CommerceController(account(this), this, recovery, initialRepository = repository)
            state.loadWallet(); runCurrent()
            assertEquals(!fail, state.walletLoaded); assertEquals(!fail, state.rechargeOffersLoaded)
            if (fail) { assertNull(state.wallet); assertNotNull(state.walletError); assertNotNull(state.rechargeOffersError) }
            else { assertEquals(0L, state.wallet!!.spendableUnits); assertNull(state.walletError); assertTrue(state.rechargeOffers.isEmpty()) }
        }
    }
    @Test fun knownStatusQueryUsesOnlyGetAndVerifiedPaymentDoesNotRefreshAsCredited() = runTest {
        val recovery = MemoryRecovery(); known(recovery); val calls = mutableListOf<Pair<String, String?>>()
        val state = CommerceController(account(this), this, recovery, initialRepository = repo(recovery, calls) {
            if (it.endsWith("recharge_fixture_order")) WireResponse(200, CommerceFixtures.rechargeOrder.replace("entry_ready", "payment_verified")) else null
        })
        state.readRechargeStatus(); state.readRechargeStatus(); runCurrent()
        assertEquals(listOf("/api/portal/coins/recharge/orders/recharge_fixture_order"), calls.map { it.first })
        assertTrue(calls.all { it.second == null }); assertEquals("payment_verified", state.recharge!!.status)
        assertNull(state.wallet); assertFalse(state.walletLoaded); assertNotNull(recovery.value)
    }
    @Test fun creditedStatusWithFailedBalanceReadClearsStaleBalanceWithoutInventingZero() = runTest {
        val recovery = MemoryRecovery(); known(recovery); val calls = mutableListOf<Pair<String, String?>>()
        val credited = JSONObject(CommerceFixtures.rechargeOrder).apply { getJSONObject("recharge").getJSONObject("order").put("status", "credited").put("nextAction", JSONObject.NULL) }.toString()
        val state = CommerceController(account(this), this, recovery, initialRepository = repo(recovery, calls) {
            if (it.endsWith("recharge_fixture_order")) WireResponse(200, credited) else WireResponse(503, "{}")
        }).apply { wallet = CommerceJson.wallet(CommerceFixtures.wallet); walletLoaded = true }
        state.readRechargeStatus(); runCurrent()
        assertEquals("credited", state.recharge!!.status); assertNull(state.wallet); assertFalse(state.walletLoaded)
        assertNotNull(state.walletError); assertTrue(calls.all { it.second == null }); assertNotNull(recovery.value)
    }
    @Test fun lateCreateAfterCancellationOrAccountReplacementCannotPublishPaymentAction() = runTest {
        for (cancelled in listOf(false, true)) {
            val recovery = MemoryRecovery(); val calls = mutableListOf<Pair<String, String?>>()
            val owner = account(this); val release = CompletableDeferred<Unit>()
            val job = Job(coroutineContext[Job]); val scope = CoroutineScope(coroutineContext + job)
            val state = CommerceController(owner, scope, recovery, initialRepository = repo(recovery, calls) { path ->
                if (path == "/api/portal/coins/recharge/orders") { withContext(NonCancellable) { release.await() }; WireResponse(201, CommerceFixtures.rechargeOrder) } else null
            })
            val offer = CommerceJson.rechargeOffers(CommerceFixtures.rechargeOffers).single()
            state.confirmRecharge(offer); runCurrent(); state.confirmRecharge(offer); runCurrent()
            assertEquals(1, calls.size); assertEquals(setOf("offerRef", "idempotencyKey"), JSONObject(calls.single().second!!).keys().asSequence().toSet())
            if (cancelled) job.cancel() else owner.authSessionGeneration++
            release.complete(Unit); runCurrent()
            assertNull(state.recharge); assertNull(state.error); assertFalse(state.busy); assertEquals("synthetic_key", recovery.value!!.key)
            job.cancel()
        }
    }
    @Test fun lateStatusSuccessOrFailureAfterCancelCannotReviveResultOrError() = runTest {
        for (success in listOf(false, true)) {
            val recovery = MemoryRecovery(); known(recovery); val calls = mutableListOf<Pair<String, String?>>()
            val release = CompletableDeferred<Unit>(); val job = Job(coroutineContext[Job]); val scope = CoroutineScope(coroutineContext + job)
            val state = CommerceController(account(this), scope, recovery, initialRepository = repo(recovery, calls) {
                withContext(NonCancellable) { release.await() }; WireResponse(if (success) 200 else 503, if (success) CommerceFixtures.rechargeOrder else "{}")
            })
            state.readRechargeStatus(); runCurrent(); job.cancel(); release.complete(Unit); runCurrent()
            assertNull(state.recharge); assertNull(state.error); assertFalse(state.busy); assertEquals(1, calls.size); assertNotNull(recovery.value)
        }
    }
}
