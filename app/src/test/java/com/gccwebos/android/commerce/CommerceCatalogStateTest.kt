package com.gccwebos.android.commerce

import com.gccwebos.android.portal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Synthetic catalog/quote reads only. Any order, recharge, or durable write fails the fixture. */
@OptIn(ExperimentalCoroutinesApi::class)
class CommerceCatalogStateTest {
    private val now = Instant.parse("2026-09-30T00:00:00Z")
    private class ReadOnlyRecovery : CommerceRecovery {
        override fun read(binding: String, kind: String): PendingCommerce? = null
        override fun save(binding: String, value: PendingCommerce) = throw AssertionError("No order may be created")
        override fun clear(binding: String, kind: String) = throw AssertionError("No recovery may be cleared")
    }
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

    private fun repository(model: CommerceModel, calls: MutableList<String>, recovery: CommerceRecovery,
        request: suspend (String) -> WireResponse? = { null }) = CommerceRepository({ path, body, key ->
        if (path != model.previewPath) { assertNull(body); assertNull(key) }
        calls += path
        request(path) ?: when (path) {
            "/api/portal/coins/wallet" -> WireResponse(200, CommerceFixtures.wallet)
            "/api/portal/coins/recharge/offers" -> WireResponse(200, CommerceFixtures.rechargeOffers)
            "/api/portal/commerce/model" -> WireResponse(200, if (model == CommerceModel.LEGACY) CommerceFixtures.legacyModel else CommerceFixtures.walletFirstModel)
            model.offersPath -> WireResponse(200, CommerceFixtures.offerBody)
            model.previewPath -> WireResponse(200, if (model == CommerceModel.LEGACY) CommerceFixtures.quote else CommerceFixtures.deferredQuote)
            else -> throw AssertionError("Unexpected catalog/quote path: $path")
        }
    }, recovery, "synthetic_account", { now }, { throw AssertionError("No mutation key") })

    @Test fun initialLoadWaitsForReadySessionAndUsesOnlyServerSelectedCatalog() = runTest {
        for (model in CommerceModel.entries) {
            val account = account(this); val calls = mutableListOf<String>(); val recovery = ReadOnlyRecovery()
            val state = CommerceController(account, this, recovery, initialRepository = repository(model, calls, recovery))
            account.restoring = true; state.load(); runCurrent(); assertTrue(calls.isEmpty())
            account.restoring = false; account.foregroundCheckPending = true
            state.load(); runCurrent(); assertTrue(calls.isEmpty())
            account.foregroundCheckPending = false; state.load()
            assertTrue(state.busy); assertFalse(state.catalogLoaded)
            runCurrent()
            assertFalse(state.busy); assertTrue(state.catalogLoaded); assertNull(state.error)
            assertEquals(model, state.commerceModel)
            assertEquals(listOf("/api/portal/coins/wallet", "/api/portal/coins/recharge/offers", "/api/portal/commerce/model", model.offersPath), calls)
            assertEquals(30L, state.offers.single().durationDays); assertEquals(10L, state.offers.single().baseCoinUnits)
        }
    }

    @Test fun failedCatalogRemainsUnreadAndExplicitRetryLoadsWithoutInventedBalance() = runTest {
        val account = account(this); val calls = mutableListOf<String>(); val recovery = ReadOnlyRecovery()
        var fail = true
        val repo = repository(CommerceModel.LEGACY, calls, recovery) { path ->
            when {
                fail && path == "/api/portal/coins/wallet" -> throw PortalFailure(PortalFailure.Reason.NETWORK)
                fail && path == CommerceModel.LEGACY.offersPath -> WireResponse(503, "{}")
                else -> null
            }
        }
        val state = CommerceController(account, this, recovery, initialRepository = repo)
        state.load(); runCurrent()
        assertFalse(state.busy); assertFalse(state.catalogLoaded); assertTrue(state.offers.isEmpty())
        assertNull(state.wallet); assertNotNull(state.error)
        fail = false; state.load(); runCurrent()
        assertTrue(state.catalogLoaded); assertEquals(20L, state.wallet!!.spendableUnits); assertNull(state.error)
        assertEquals(2, calls.count { it == CommerceModel.LEGACY.offersPath })
    }

    @Test fun repeatedLoadAndQuoteWhileBusySendOneReadAndNeverCreateOrder() = runTest {
        val account = account(this); val calls = mutableListOf<String>(); val recovery = ReadOnlyRecovery()
        val walletRelease = CompletableDeferred<Unit>(); val quoteRelease = CompletableDeferred<Unit>()
        val repo = repository(CommerceModel.LEGACY, calls, recovery) { path ->
            if (path == "/api/portal/coins/wallet") walletRelease.await()
            if (path == CommerceModel.LEGACY.previewPath) quoteRelease.await()
            null
        }
        val state = CommerceController(account, this, recovery, initialRepository = repo)
        state.load(); runCurrent(); state.load(); runCurrent()
        assertTrue(state.busy); assertFalse(state.catalogLoaded); assertEquals(1, calls.size)
        walletRelease.complete(Unit); runCurrent()
        val offer = state.offers.single()
        state.preview(offer); runCurrent(); state.preview(offer); runCurrent()
        assertTrue(state.busy); assertNull(state.quote); assertEquals(1, calls.count { it == CommerceModel.LEGACY.previewPath })
        quoteRelease.complete(Unit); runCurrent()
        assertFalse(state.busy); assertEquals(offer.offerRef, state.quote!!.offer.offerRef)
        state.quote = null // Dismissing a quote is a local UI action.
        assertNull(state.purchase); assertNull(repo.pending("purchase"))
    }

    @Test fun oldAccountLateCatalogCannotPublishOrStartAnotherRead() = runTest {
        val account = account(this); val calls = mutableListOf<String>(); val recovery = ReadOnlyRecovery()
        val release = CompletableDeferred<Unit>()
        val repo = repository(CommerceModel.LEGACY, calls, recovery) { path ->
            if (path == CommerceModel.LEGACY.offersPath) withContext(NonCancellable) { release.await() }
            null
        }
        val state = CommerceController(account, this, recovery, initialRepository = repo)
        state.load(); runCurrent(); account.authSessionGeneration++
        release.complete(Unit); runCurrent()
        assertFalse(state.busy); assertFalse(state.catalogLoaded); assertTrue(state.offers.isEmpty()); assertNull(state.error)
        val count = calls.size; state.load(); runCurrent(); assertEquals(count, calls.size)
    }

    @Test fun cancelledCatalogCannotPublishNonCooperativeLateResponse() = runTest {
        val account = account(this); val calls = mutableListOf<String>(); val recovery = ReadOnlyRecovery()
        val release = CompletableDeferred<Unit>(); val controllerJob = Job(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + controllerJob)
        val repo = repository(CommerceModel.LEGACY, calls, recovery) { path ->
            if (path == CommerceModel.LEGACY.offersPath) withContext(NonCancellable) { release.await() }
            null
        }
        val state = CommerceController(account, scope, recovery, initialRepository = repo)
        state.load(); runCurrent(); controllerJob.cancel(); release.complete(Unit); runCurrent()
        assertFalse(state.busy); assertFalse(state.catalogLoaded); assertTrue(state.offers.isEmpty())
    }

    @Test fun queuedAndLateQuoteFromOldAccountCannotPublishOrRetainSelection() = runTest {
        val account = account(this); val calls = mutableListOf<String>(); val recovery = ReadOnlyRecovery()
        val release = CompletableDeferred<Unit>()
        val repo = repository(CommerceModel.LEGACY, calls, recovery) { path ->
            if (path == CommerceModel.LEGACY.previewPath) withContext(NonCancellable) { release.await() }
            null
        }
        val state = CommerceController(account, this, recovery, initialRepository = repo)
        state.load(); runCurrent(); val offer = state.offers.single()
        state.preview(offer) // The queued coroutine has not started yet.
        account.authSessionGeneration++; runCurrent()
        assertNull(account.purchaseReturnRef); assertEquals(0, calls.count { it == CommerceModel.LEGACY.previewPath })
        val next = CommerceController(account, this, recovery, initialRepository = repo)
        next.preview(offer); runCurrent(); assertEquals(offer.offerRef, account.purchaseReturnRef)
        account.authSessionGeneration++; account.purchaseReturnRef = null
        release.complete(Unit); runCurrent()
        assertFalse(next.busy); assertNull(next.quote); assertNull(account.purchaseReturnRef)
        assertEquals(1, calls.count { it == CommerceModel.LEGACY.previewPath })
    }

    @Test fun cancelledQuoteCannotPublishLateTypedOrUnknownFailure() = runTest {
        for (failure in listOf("portal", "commerce", "unknown")) {
            val account = account(this); val calls = mutableListOf<String>(); val recovery = ReadOnlyRecovery()
            val release = CompletableDeferred<Unit>(); val controllerJob = Job(coroutineContext[Job])
            val scope = CoroutineScope(coroutineContext + controllerJob)
            val repo = repository(CommerceModel.LEGACY, calls, recovery) { path ->
                if (path == CommerceModel.LEGACY.previewPath) {
                    withContext(NonCancellable) { release.await() }
                    when (failure) {
                        "portal" -> throw PortalFailure(PortalFailure.Reason.NETWORK)
                        "commerce" -> WireResponse(409, """{"error":{"code":"quote_stale","retryable":false}}""")
                        else -> throw IllegalStateException("Synthetic late failure")
                    }
                } else null
            }
            val state = CommerceController(account, scope, recovery, initialRepository = repo)
            state.load(); runCurrent(); state.preview(state.offers.single()); runCurrent()
            controllerJob.cancel(); release.complete(Unit); runCurrent()
            assertTrue(account.authenticated); assertFalse(state.busy); assertNull(state.quote); assertNull(state.error)
            assertEquals(1, calls.count { it == CommerceModel.LEGACY.previewPath }); assertNull(state.purchase)
        }
    }
}
