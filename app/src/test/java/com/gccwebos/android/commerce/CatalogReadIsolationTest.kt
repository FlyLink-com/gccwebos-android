package com.gccwebos.android.commerce

import com.gccwebos.android.portal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Synthetic read counts; no real session, endpoint, recovery mutation, or purchase. */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogReadIsolationTest {
    private val recovery = object : CommerceRecovery {
        override fun read(binding: String, kind: String): PendingCommerce? {
            check(kind == "purchase") { "Catalog must not inspect recharge recovery" }
            return null
        }
        override fun save(binding: String, value: PendingCommerce) = error("No mutation")
        override fun clear(binding: String, kind: String) = error("No mutation")
    }
    private fun account(scope: CoroutineScope) = AccountController(PortalRepository(
        object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse = error("No real transport")
        }, object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = error("No delivery")
            override fun clear() = error("No delivery")
        }, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L }), scope).apply { authenticated = true }

    @Test fun catalogNeedsOnlyModelThenOffersAndNeverWaitsForWalletOrRecharge() = runTest {
        for (model in CommerceModel.entries) {
            val calls = mutableListOf<String>()
            val unrelatedRelease = CompletableDeferred<Unit>()
            val repo = CommerceRepository({ path, body, key ->
                assertNull(body); assertNull(key); calls += path
                when (path) {
                    "/api/portal/commerce/model" -> WireResponse(200, if (model == CommerceModel.LEGACY) CommerceFixtures.legacyModel else CommerceFixtures.walletFirstModel)
                    model.offersPath -> WireResponse(200, CommerceFixtures.offerBody)
                    else -> { unrelatedRelease.await(); error("Catalog used an unrelated endpoint") }
                }
            }, recovery, "synthetic_account")
            val state = CommerceController(account(this), this, recovery, initialRepository = repo)
            state.loadCatalog(); state.loadCatalog(); runCurrent()
            assertFalse(state.busy)
            assertTrue(state.catalogLoaded)
            assertEquals(listOf("/api/portal/commerce/model", model.offersPath), calls)
            assertNull(state.wallet); assertFalse(state.walletLoaded)
            assertTrue(state.rechargeOffers.isEmpty()); assertNull(state.purchase)
            assertFalse(unrelatedRelease.isCompleted)
        }
    }

    @Test fun lateCatalogFromPreviousAccountCannotPublishOrRequestNextStage() = runTest {
        val release = CompletableDeferred<Unit>(); val calls = mutableListOf<String>()
        val owner = account(this)
        val repo = CommerceRepository({ path, _, _ ->
            calls += path
            if (path == "/api/portal/commerce/model") {
                withContext(NonCancellable) { release.await() }
                WireResponse(200, CommerceFixtures.legacyModel)
            } else WireResponse(200, CommerceFixtures.offerBody)
        }, recovery, "synthetic_account")
        val state = CommerceController(owner, this, recovery, initialRepository = repo)
        state.loadCatalog(); runCurrent(); owner.authSessionGeneration++
        release.complete(Unit); runCurrent()
        assertEquals(listOf("/api/portal/commerce/model"), calls)
        assertFalse(state.catalogLoaded); assertTrue(state.offers.isEmpty()); assertNull(state.quote)
    }

    @Test fun unknownPurchaseKeepsItsRecoveryKeyWithoutReplayingItDuringCatalogLoad() = runTest {
        assertPendingPurchase(resultRef = null)
    }

    @Test fun failedKnownPurchaseReadDoesNotRemoveThePendingPurchaseGate() = runTest {
        assertPendingPurchase(resultRef = "order_fixture_plan")
    }

    private suspend fun kotlinx.coroutines.test.TestScope.assertPendingPurchase(resultRef: String?) {
        val pending = PendingCommerce("purchase", CommerceModel.LEGACY, "quote_fixture_plan", "fixture_key", resultRef)
        val guardedRecovery = object : CommerceRecovery {
            override fun read(binding: String, kind: String): PendingCommerce? {
                assertEquals("purchase", kind); return pending
            }
            override fun save(binding: String, value: PendingCommerce) = error("No replay or replacement key")
            override fun clear(binding: String, kind: String) = error("No recovery clear")
        }
        val calls = mutableListOf<String>()
        val repo = CommerceRepository({ path, body, key ->
            assertNull(body); assertNull(key); calls += path
            when (path) {
                "/api/portal/commerce/model" -> WireResponse(200, CommerceFixtures.legacyModel)
                CommerceModel.LEGACY.offersPath -> WireResponse(200, CommerceFixtures.offerBody)
                "${CommerceModel.LEGACY.purchasePath}/order_fixture_plan" -> throw PortalFailure(PortalFailure.Reason.NETWORK)
                else -> error("No create or unrelated read")
            }
        }, guardedRecovery, "synthetic_account")
        val state = CommerceController(account(this), this, guardedRecovery, initialRepository = repo)
        state.loadCatalog(); runCurrent()
        assertTrue(state.pendingPurchase); assertNull(state.purchase); assertNull(state.quote)
        assertTrue(state.catalogLoaded)
        val expected = listOfNotNull(resultRef?.let { "${CommerceModel.LEGACY.purchasePath}/$it" }) +
            listOf("/api/portal/commerce/model", CommerceModel.LEGACY.offersPath)
        assertEquals(expected, calls)
        assertSame(pending, guardedRecovery.read("synthetic_account", "purchase"))
    }
}
