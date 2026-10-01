package com.gccwebos.android.commerce

import com.gccwebos.android.portal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Synthetic detail readers only; no Portal, payment link, mutation, or durable storage. */
@OptIn(ExperimentalCoroutinesApi::class)
class OrderDetailControllerTest {
    private val now = Instant.parse("2026-09-30T00:00:00Z")
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

    private fun detail(kind: HistoryKind, ref: String): HistoryDetail = when (kind) {
        HistoryKind.Legacy -> HistoryDetail.Purchase((CommerceJson.purchaseResult(
            CommerceFixtures.order, CommerceModel.LEGACY) as PurchaseResult.Order).copy(orderRef = ref))
        HistoryKind.Deferred -> HistoryDetail.Purchase((CommerceJson.purchaseResult(
            CommerceFixtures.deferred, CommerceModel.WALLET_FIRST) as PurchaseResult.Deferred).copy(transitionRef = ref))
        HistoryKind.Recharge -> HistoryDetail.Recharge(RechargeOrder(ref, "recharge_fixture_10", 1L,
            10L, 1000L, "CNY", 2, "xsd_v1", "alipay", "credited", null, null, null, null,
            now.plusSeconds(600), now, now))
    }

    @Test fun allKindsOpenOnlyExplicitlyAndRepeatedBusyClicksSendOneRead() = runTest {
        for (kind in HistoryKind.entries) {
            val account = account(this); val release = CompletableDeferred<Unit>()
            val selection = HistorySelection(kind, "history_fixture_record")
            val calls = mutableListOf<HistorySelection>()
            val controller = OrderDetailController(account, this) {
                calls += it; release.await(); detail(it.kind, it.ref)
            }
            runCurrent(); assertTrue(calls.isEmpty()); assertFalse(controller.visible)
            controller.open(selection); runCurrent(); controller.open(selection); controller.retry(); runCurrent()
            assertEquals(listOf(selection), calls); assertTrue(controller.busy); assertNull(controller.value)
            release.complete(Unit); runCurrent()
            assertFalse(controller.busy); assertEquals(detail(kind, selection.ref), controller.value)
            assertNull(controller.error); assertTrue(controller.visible)
            controller.back(); controller.retry(); runCurrent()
            assertFalse(controller.visible); assertEquals(selection, controller.selected); assertEquals(1, calls.size)
        }
    }

    @Test fun backBlocksNonCooperativeLateSuccessAndEveryFailureFamily() = runTest {
        for (outcome in listOf("success", "portal", "commerce", "unknown")) {
            val account = account(this); val release = CompletableDeferred<Unit>()
            val selection = HistorySelection(HistoryKind.Legacy, "order_fixture_plan")
            var calls = 0
            val controller = OrderDetailController(account, this) {
                calls++
                withContext(NonCancellable) {
                    release.await()
                    when (outcome) {
                        "portal" -> throw PortalFailure(PortalFailure.Reason.SESSION_EXPIRED)
                        "commerce" -> throw CommerceFailure("not_found", 404)
                        "unknown" -> throw IllegalStateException("Synthetic late failure")
                        else -> detail(it.kind, it.ref)
                    }
                }
            }
            controller.open(selection); runCurrent(); controller.back()
            assertFalse(controller.busy); assertFalse(controller.visible); assertEquals(selection, controller.selected)
            release.complete(Unit); runCurrent()
            assertNull(controller.value); assertNull(controller.error); assertTrue(account.authenticated)
            assertNull(account.error); assertEquals(1, calls)
        }
    }

    @Test fun olderFinallyCannotClearNewSelectionLoadingState() = runTest {
        val account = account(this); val oldRelease = CompletableDeferred<Unit>(); val newRelease = CompletableDeferred<Unit>()
        val first = HistorySelection(HistoryKind.Legacy, "order_fixture_first")
        val second = HistorySelection(HistoryKind.Deferred, "transition_fixture_second")
        val controller = OrderDetailController(account, this) {
            if (it == first) withContext(NonCancellable) { oldRelease.await() } else newRelease.await()
            detail(it.kind, it.ref)
        }
        controller.open(first); runCurrent(); controller.back(); controller.open(second); runCurrent()
        oldRelease.complete(Unit); runCurrent()
        assertTrue(controller.busy); assertEquals(second, controller.selected); assertNull(controller.value)
        newRelease.complete(Unit); runCurrent()
        assertFalse(controller.busy); assertEquals(detail(second.kind, second.ref), controller.value)
    }

    @Test fun readinessAndAccountGenerationBlockQueuedAndLateReads() = runTest {
        val account = account(this); val selection = HistorySelection(HistoryKind.Legacy, "order_fixture_plan")
        var calls = 0
        val release = CompletableDeferred<Unit>()
        val controller = OrderDetailController(account, this) {
            calls++; withContext(NonCancellable) { release.await() }; detail(it.kind, it.ref)
        }
        account.restoring = true; controller.open(selection); runCurrent(); assertEquals(0, calls)
        account.restoring = false; account.foregroundCheckPending = true
        controller.open(selection); runCurrent(); assertEquals(0, calls)
        account.foregroundCheckPending = false
        controller.open(selection); account.authSessionGeneration++; runCurrent()
        assertEquals(0, calls); assertFalse(controller.busy); assertNull(controller.value); assertNull(controller.error)
        controller.back(); controller.open(selection); runCurrent(); assertEquals(0, calls)
        val next = OrderDetailController(account, this) {
            calls++; withContext(NonCancellable) { release.await() }; detail(it.kind, it.ref)
        }
        next.open(selection); runCurrent(); assertEquals(1, calls)
        account.authSessionGeneration++; release.complete(Unit); runCurrent()
        assertFalse(next.busy); assertNull(next.value); assertNull(next.error); assertTrue(account.authenticated)
    }

    @Test fun unreadFailuresRemainDistinctAndRetryReadsTheSameSelection() = runTest {
        for (failure in listOf("portal", "commerce", "unknown")) {
            val account = account(this); val selection = HistorySelection(HistoryKind.Recharge, "recharge_fixture_order")
            val calls = mutableListOf<HistorySelection>()
            val controller = OrderDetailController(account, this) {
                calls += it
                if (calls.size == 1) when (failure) {
                    "portal" -> throw PortalFailure(PortalFailure.Reason.NETWORK)
                    "commerce" -> throw CommerceFailure("not_found", 404)
                    else -> throw IllegalArgumentException("Synthetic malformed schema")
                }
                detail(it.kind, it.ref)
            }
            controller.open(selection); runCurrent()
            assertTrue(controller.visible); assertFalse(controller.busy); assertNull(controller.value)
            assertNotNull(controller.error); assertTrue(account.authenticated); assertEquals(selection, controller.selected)
            controller.retry(); runCurrent()
            assertEquals(listOf(selection, selection), calls); assertNull(controller.error)
            assertEquals(detail(selection.kind, selection.ref), controller.value)
        }
    }

    @Test fun mismatchedResponseReferenceOrKindCannotBecomeDetail() = runTest {
        val cases = listOf(
            HistorySelection(HistoryKind.Legacy, "order_fixture_plan") to detail(HistoryKind.Legacy, "order_fixture_other"),
            HistorySelection(HistoryKind.Legacy, "order_fixture_plan") to detail(HistoryKind.Deferred, "order_fixture_plan"),
            HistorySelection(HistoryKind.Deferred, "transition_fixture_plan") to detail(HistoryKind.Recharge, "transition_fixture_plan"),
            HistorySelection(HistoryKind.Recharge, "recharge_fixture_order") to detail(HistoryKind.Legacy, "recharge_fixture_order"),
            HistorySelection(HistoryKind.Recharge, "recharge_fixture_order") to detail(HistoryKind.Recharge, "recharge_fixture_other"),
        )
        for ((selection, response) in cases) {
            val controller = OrderDetailController(account(this), this) { response }
            controller.open(selection); runCurrent()
            assertFalse(controller.busy); assertNull(controller.value); assertNotNull(controller.error)
            assertEquals(selection, controller.selected)
        }
    }

    @Test fun disposeClearsSelectionAndCancelsLateResult() = runTest {
        val release = CompletableDeferred<Unit>()
        val controller = OrderDetailController(account(this), this) {
            withContext(NonCancellable) { release.await() }; detail(it.kind, it.ref)
        }
        controller.open(HistorySelection(HistoryKind.Legacy, "order_fixture_plan")); runCurrent(); controller.dispose()
        assertNull(controller.selected); assertFalse(controller.visible); assertFalse(controller.busy)
        release.complete(Unit); runCurrent(); assertNull(controller.value); assertNull(controller.error)
    }

    @Test fun currentSessionExpiryEndsAuthenticationWithoutInventingAnEmptyDetail() = runTest {
        val account = account(this)
        val controller = OrderDetailController(account, this) { throw PortalFailure(PortalFailure.Reason.SESSION_EXPIRED) }
        controller.open(HistorySelection(HistoryKind.Legacy, "order_fixture_plan")); runCurrent()
        assertFalse(account.authenticated); assertNotNull(account.error); assertNotNull(controller.error)
        assertNull(controller.value); assertFalse(controller.busy)
    }
}
