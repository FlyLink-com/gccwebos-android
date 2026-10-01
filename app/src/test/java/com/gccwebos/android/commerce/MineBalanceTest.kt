package com.gccwebos.android.commerce

import com.gccwebos.android.portal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** My balance reads only the server wallet, independently of catalog, recharge and resources. */
@OptIn(ExperimentalCoroutinesApi::class)
class MineBalanceTest {
    private fun owner(scope: CoroutineScope) = AccountController(PortalRepository(
        object : PortalTransport { override suspend fun request(path: String, body: String?, session: Secret?): WireResponse = throw AssertionError("No Portal transport") },
        object : DeliveryStore { override fun read(): DeliveryRecord? = null; override fun save(record: DeliveryRecord) = throw AssertionError("No delivery"); override fun clear() = Unit },
        OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), {0L},
        subscription = SubscriptionSource { throw AssertionError("No subscriptions") }, sessions = MemorySessionStore(), profiles = MemoryLocalProfileStore()), scope).apply { authenticated = true; resource = Resource.None }
    private val recovery = object : CommerceRecovery {
        override fun read(binding: String, kind: String): PendingCommerce? = throw AssertionError("Balance needs no recovery")
        override fun save(binding: String, value: PendingCommerce) = throw AssertionError("No orders")
        override fun clear(binding: String, kind: String) = throw AssertionError("No order clears")
    }
    @Test fun myBalanceReadsOnlyWalletAndFailureCannotBecomeZeroOrResourceDerivedCoins() = runTest {
        for (fail in listOf(false, true)) {
            val paths = mutableListOf<String>()
            val repo = CommerceRepository({ path, body, key ->
                paths += path; assertNull(body); assertNull(key); assertEquals("/api/portal/coins/wallet", path)
                WireResponse(if (fail) 503 else 200, if (fail) "{}" else CommerceFixtures.wallet)
            }, recovery, "mine_fixture_account")
            val state = CommerceController(owner(this), this, recovery, initialRepository = repo)
            state.loadBalance(); state.loadBalance(); runCurrent()
            assertEquals(listOf("/api/portal/coins/wallet"), paths)
            if (fail) { assertNull(state.wallet); assertFalse(state.walletLoaded); assertNotNull(state.walletError) }
            else { assertEquals(20L, state.wallet!!.spendableUnits); assertTrue(state.walletLoaded); assertNull(state.walletError) }
            assertNull(state.commerceModel); assertFalse(state.rechargeOffersLoaded); assertNull(state.recharge)
        }
    }
    @Test fun cancelledOrReplacedAccountLateBalanceCannotPublishOldAmount() = runTest {
        for (cancelled in listOf(false, true)) {
            val account = owner(this); val release = CompletableDeferred<Unit>(); val job = Job(coroutineContext[Job])
            val scope = CoroutineScope(coroutineContext + job)
            val repo = CommerceRepository({ _, body, key -> assertNull(body); assertNull(key)
                withContext(NonCancellable) { release.await() }; WireResponse(200, CommerceFixtures.wallet)
            }, recovery, "mine_fixture_account", { Instant.EPOCH })
            val state = CommerceController(account, scope, recovery, initialRepository = repo)
            state.loadBalance(); runCurrent()
            if (cancelled) job.cancel() else account.authSessionGeneration++
            release.complete(Unit); runCurrent()
            assertNull(state.wallet); assertFalse(state.walletLoaded); assertNull(state.error); assertFalse(state.busy)
            job.cancel()
        }
    }
}
