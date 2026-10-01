package com.gccwebos.android.commerce

import com.gccwebos.android.portal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Page replacement uses one memory recovery namespace; this is not a disk or server integration test. */
@OptIn(ExperimentalCoroutinesApi::class)
class CommercePageLifecycleIntegrationTest {
    private class Recovery : CommerceRecovery {
        val records = mutableMapOf<Pair<String, String>, PendingCommerce>()
        override fun read(binding: String, kind: String) = records[binding to kind]
        override fun save(binding: String, value: PendingCommerce) { records[binding to value.kind] = value }
        override fun clear(binding: String, kind: String) { records.remove(binding to kind) }
    }

    @Test fun newWalletPageKeepsUnknownRecoveryAndOnlyExplicitRestoreReusesItsKey() = runTest {
        val now = Instant.parse(CommerceFixtures.instant)
        val recovery = Recovery(); val calls = mutableListOf<Pair<String, String?>>()
        var keysCreated = 0; var firstPost = true
        val owner = AccountController(PortalRepository(object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse =
                throw AssertionError("No Portal client in this memory commerce fixture")
        }, object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = throw AssertionError("No delivery writes")
            override fun clear() = Unit
        }, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L }, { now },
            subscription = SubscriptionSource { throw AssertionError("No subscription client") },
            sessions = MemorySessionStore(), profiles = MemoryLocalProfileStore()), backgroundScope).apply { authenticated = true }
        fun repository(binding: String) = CommerceRepository({ route, body, headerKey ->
            assertNull(headerKey); calls += route to body
            when (route) {
                "/api/portal/coins/wallet" -> WireResponse(200, CommerceFixtures.wallet)
                "/api/portal/coins/recharge/offers" -> WireResponse(200, CommerceFixtures.rechargeOffers)
                "/api/portal/coins/recharge/orders" -> {
                    assertNotNull(recovery.read(binding, "recharge"))
                    if (firstPost) { firstPost = false; throw PortalFailure(PortalFailure.Reason.NETWORK) }
                    WireResponse(201, CommerceFixtures.rechargeOrder)
                }
                else -> throw AssertionError("No other commerce route")
            }
        }, recovery, binding, { now }, { "lifecycle_key_${++keysCreated}" })
        val offer = CommerceJson.rechargeOffers(CommerceFixtures.rechargeOffers).single()
        val firstJob = Job(backgroundScope.coroutineContext[Job])
        val first = CommerceController(owner, CoroutineScope(backgroundScope.coroutineContext + firstJob), recovery,
            initialRepository = repository("fixture_account_a"))
        first.confirmRecharge(offer); runCurrent(); firstJob.cancel()
        val original = recovery.read("fixture_account_a", "recharge")!!
        assertEquals("lifecycle_key_1", original.key); assertNull(original.resultRef); assertEquals(1, keysCreated)
        val second = CommerceController(owner, backgroundScope, recovery, initialRepository = repository("fixture_account_a"))
        second.loadWallet(); runCurrent()
        assertTrue(second.pendingRecharge); assertNull(second.recharge); assertEquals(original, recovery.read("fixture_account_a", "recharge"))
        assertEquals(1, calls.count { it.second != null })
        second.confirmRecharge(offer); runCurrent()
        assertEquals(1, calls.count { it.second != null }); assertEquals(1, keysCreated)
        second.queryRecharge(); runCurrent()
        val postKeys = calls.mapNotNull { it.second?.let(::JSONObject)?.getString("idempotencyKey") }
        assertEquals(listOf("lifecycle_key_1", "lifecycle_key_1"), postKeys)
        assertEquals(1, keysCreated); assertEquals("recharge_fixture_order", second.recharge?.orderRef)
        assertNull(repository("fixture_account_b").pending("recharge"))
        assertNotNull(recovery.read("fixture_account_a", "recharge"))
    }
}
