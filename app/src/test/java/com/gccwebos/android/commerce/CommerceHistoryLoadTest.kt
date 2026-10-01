package com.gccwebos.android.commerce

import com.gccwebos.android.portal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Synthetic list reads; no orders, recharge, delivery, or recovery writes. */
@OptIn(ExperimentalCoroutinesApi::class)
class CommerceHistoryLoadTest {
    private val now = Instant.parse("2026-09-30T00:00:00Z")
    private val recovery = object : CommerceRecovery {
        override fun read(binding: String, kind: String): PendingCommerce? = null
        override fun save(binding: String, value: PendingCommerce) = throw AssertionError("No recovery writes")
        override fun clear(binding: String, kind: String) = throw AssertionError("No recovery clears")
    }
    private fun account(scope: CoroutineScope) = AccountController(PortalRepository(
        object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse = throw AssertionError("No Portal requests")
        }, object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = throw AssertionError("No delivery writes")
            override fun clear() = Unit
        }, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L }, { now },
        subscription = SubscriptionSource { throw AssertionError("No subscription requests") },
        sessions = MemorySessionStore(), profiles = MemoryLocalProfileStore()), scope).apply { authenticated = true }
    private fun repo(calls: MutableList<String>, response: suspend (String) -> WireResponse? = { null }) =
        CommerceRepository({ path, body, key ->
            assertNull(body); assertNull(key); calls += path
            response(path) ?: when (path) {
                "/api/portal/coins/wallet" -> WireResponse(200, CommerceFixtures.wallet)
                "/api/portal/coins/recharge/offers" -> WireResponse(200, CommerceFixtures.rechargeOffers)
                "/api/portal/commerce/model" -> WireResponse(200, CommerceFixtures.legacyModel)
                CommerceModel.LEGACY.offersPath -> WireResponse(200, CommerceFixtures.offerBody)
                "/api/portal/commerce/orders?limit=20" -> WireResponse(200, """{"orders":[],"nextCursor":null}""")
                "/api/portal/coins/recharge/orders" -> WireResponse(200, """{"recharges":[]}""")
                else -> throw AssertionError("Unexpected history path")
            }
        }, recovery, "synthetic_account", { now }, { throw AssertionError("No mutation key") })

    @Test fun unknownModelCannotFallbackButRechargeHistoryCanBeAuthoritativelyEmpty() = runTest {
        val calls = mutableListOf<String>()
        val repository = repo(calls) { path ->
            if (path == "/api/portal/commerce/model") WireResponse(200, """{"commerceModel":"unknown_fixture"}""") else null
        }
        val state = CommerceController(account(this), this, recovery, includeHistory = true, initialRepository = repository)
        state.load(); runCurrent()
        assertNull(state.commerceModel); assertNull(state.orderPage); assertNull(state.transitions)
        assertNotNull(state.purchaseHistoryError); assertEquals(emptyList<RechargeOrder>(), state.rechargeHistory)
        assertNull(state.rechargeHistoryError)
        assertFalse(calls.any { it.startsWith("/api/portal/commerce/orders") || it.startsWith("/api/portal/commerce/deferred") })
        val count = calls.size
        try { state.historyDetail(HistorySelection(HistoryKind.Legacy, "order_fixture_plan")); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(count, calls.size)
    }

    @Test fun unreadGlobalFailureDoesNotReplaceKnownEmptyOrSuccessfulHistory() {
        assertEquals("恢复记录不可用", historyReadError(null, false, null, "恢复记录不可用"))
        assertEquals("历史查询失败", historyReadError(null, false, "历史查询失败", "钱包查询失败"))
        assertNull(historyReadError(null, true, null, "先前读取失败"))
        assertNull(historyReadError(0, false, null, "钱包查询失败"))
        assertNull(historyReadError(2, false, null, "钱包查询失败"))
        assertEquals("更早记录读取失败", historyReadError(2, false, "更早记录读取失败", "钱包查询失败"))
    }

    @Test fun historyFailureIsUnreadWhileUnrelatedWalletFailureDoesNotEraseKnownEmptyHistory() = runTest {
        for (failedPath in listOf("/api/portal/coins/wallet", "/api/portal/commerce/orders?limit=20")) {
            val state = CommerceController(account(this), this, recovery, includeHistory = true,
                initialRepository = repo(mutableListOf()) { if (it == failedPath) WireResponse(503, "{}") else null })
            state.load(); runCurrent()
            assertNotNull(state.error); assertEquals(emptyList<RechargeOrder>(), state.rechargeHistory)
            assertNull(state.rechargeHistoryError)
            if (failedPath.contains("commerce/orders")) {
                assertNull(state.orderPage); assertNotNull(state.purchaseHistoryError)
            } else {
                assertEquals(emptyList<PurchaseResult.Order>(), state.orderPage!!.orders); assertNull(state.purchaseHistoryError)
            }
        }
    }

    @Test fun cancelledOrReplacedAccountPaginationCannotOverwriteTheRetainedList() = runTest {
        for (cancelled in listOf(false, true)) {
            val account = account(this); val release = CompletableDeferred<Unit>(); val calls = mutableListOf<String>()
            val controllerJob = Job(coroutineContext[Job]); val scope = CoroutineScope(coroutineContext + controllerJob)
            val repository = repo(calls) { path ->
                if (path.contains("&afterCreatedAt=")) {
                    withContext(NonCancellable) { release.await() }
                    val inner = JSONObject(CommerceFixtures.order).getJSONObject("order")
                    WireResponse(200, JSONObject().put("orders", org.json.JSONArray().put(inner)).put("nextCursor", JSONObject.NULL).toString())
                } else null
            }
            val state = CommerceController(account, scope, recovery, includeHistory = true, initialRepository = repository)
            state.load(); runCurrent()
            val order = (CommerceJson.purchaseResult(CommerceFixtures.order, CommerceModel.LEGACY) as PurchaseResult.Order)
                .copy(orderRef = "order_fixture_newer", createdAt = now.plusSeconds(60))
            val page = OrderPage(listOf(order), OrderCursor(order.createdAt, order.ref)); state.orderPage = page
            state.moreOrders(); runCurrent(); state.moreOrders(); runCurrent()
            assertEquals(1, calls.count { it.contains("&afterCreatedAt=") })
            if (cancelled) controllerJob.cancel() else account.authSessionGeneration++
            release.complete(Unit); runCurrent()
            assertSame(page, state.orderPage); assertNull(state.purchaseHistoryError); assertFalse(state.busy)
            controllerJob.cancel()
        }
    }
}
