package com.gccwebos.android.commerce

import com.gccwebos.android.portal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/**
 * Entry cost of 我的 → 订单明细. Portal reads are serialized behind one mutex, so the number of
 * sequential requests is the page's wait. The old default tab ran the full commerce load; the flat
 * 流水 runs the single ledger read and keeps the last page of the same session on screen while refreshing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MineHistoryEntryCostTest {
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
    private val ledgerBody = """{"entries":[{"entryRef":"entry_a","category":"credit","coinType":"paid_coins","amountUnits":30,
        "availableDeltaUnits":null,"pendingDeltaUnits":null,"heldDeltaUnits":null,"totalDeltaUnits":30,"relatedOrderRef":null,
        "occurredAt":"2026-09-30T00:00:00.000Z"}],"nextCursor":null}"""
    private fun repo(calls: MutableList<String>, gate: CompletableDeferred<Unit>? = null) =
        CommerceRepository({ path, body, key ->
            assertNull(body); assertNull(key); calls += path
            when (path) {
                "/api/portal/coins/wallet" -> WireResponse(200, CommerceFixtures.wallet)
                "/api/portal/coins/recharge/offers" -> WireResponse(200, CommerceFixtures.rechargeOffers)
                "/api/portal/commerce/model" -> WireResponse(200, CommerceFixtures.legacyModel)
                CommerceModel.LEGACY.offersPath -> WireResponse(200, CommerceFixtures.offerBody)
                "/api/portal/commerce/orders?limit=20" -> WireResponse(200, """{"orders":[],"nextCursor":null}""")
                "/api/portal/coins/recharge/orders" -> WireResponse(200, """{"recharges":[]}""")
                "/api/portal/coins/wallet/ledger?limit=20" -> { gate?.await(); WireResponse(200, ledgerBody) }
                else -> throw AssertionError("Unexpected path $path")
            }
        }, recovery, "synthetic_account", { now }, { throw AssertionError("No mutation key") })

    @Test fun flatLedgerEntryIsTwoSmallReadsWhileTheOldDefaultTabWasTheFullCommerceLoad() = runTest {
        val before = mutableListOf<String>()
        CommerceController(account(this), this, recovery, includeHistory = true, initialRepository = repo(before)).load()
        runCurrent()
        val after = mutableListOf<String>()
        val flat = CommerceController(account(this), this, recovery, includeHistory = true, initialRepository = repo(after))
        flat.loadLedger(); runCurrent()
        println("MEASURE entry requests: old default tab=${before.size} flat ledger=${after.size}")
        assertTrue("old default tab read ${before.size}", before.size >= 6)
        // The ledger itself plus the recharge-order list, so an unpaid order can be listed (and paid) in 订单明细.
        assertEquals(listOf("/api/portal/coins/wallet/ledger?limit=20", "/api/portal/coins/recharge/orders"), after)
        assertEquals(1, flat.ledgerPage!!.entries.size)
    }

    @Test fun reopeningKeepsThePreviousPageVisibleUntilTheRefreshArrives() = runTest {
        val calls = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val state = CommerceController(account(this), this, recovery, includeHistory = true, initialRepository = repo(calls, gate))
        state.loadLedger(); runCurrent(); gate.complete(Unit); advanceUntilIdle()
        val first = state.ledgerPage
        assertNotNull(first)
        val gate2 = CompletableDeferred<Unit>()
        val again = CommerceController(account(this), this, recovery, includeHistory = true, initialRepository = repo(calls, gate2))
        again.ledgerPage = first
        again.loadLedger(); runCurrent()
        assertTrue(again.busy)
        assertSame("stale page stays on screen while the refresh is in flight", first, again.ledgerPage)
        gate2.complete(Unit); advanceUntilIdle()
        assertNull(again.error); assertFalse(again.busy)
        assertEquals("the refresh did hit the server (ledger + recharge orders, twice)", 4, calls.size)
        assertEquals(first, again.ledgerPage)
    }
}
