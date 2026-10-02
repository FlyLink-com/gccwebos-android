package com.gccwebos.android.portal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountUsageControllerTest {
    private val firstOwner = owner("fixture_account_a", "fixture_token_a")
    private val secondOwner = owner("fixture_account_b", "fixture_token_b")
    private val repositoryEpoch = 7L
    private val uiAuthEpoch = 11L
    private val cycleStart = Instant.parse("2026-10-01T00:00:00Z")
    private val cycleEnd = Instant.parse("2026-11-01T00:00:00Z")

    private fun owner(account: String, token: String) = AccountUsageOwner.fromAccepted(account, sessionBinding(Secret(token)))
    private fun available(ref: Char = 'a', used: Long = 40, status: String = "measured",
        start: Instant = cycleStart, end: Instant = cycleEnd): Resource.Available = Resource.Available(
        "resource_" + ref.toString().repeat(64), "fixture_plan", Instant.parse("2026-12-01T00:00:00Z"), false,
        if (status == "pending") Usage(100, null, null, status, null, start, end)
        else Usage(100, used, (100 - used).coerceAtLeast(0), status, cycleStart.plusSeconds(3600), start, end),
        30, 50.0)

    private fun bound(resource: Resource, sequence: Long = 1, revision: Long = 1,
        owner: AccountUsageOwner = firstOwner, epoch: Long = repositoryEpoch) =
        BoundAccountOverview(AccountUsageReadContext(owner, epoch, revision, sequence), resource)

    private inner class Fixture(scope: CoroutineScope) {
        var calls = 0
        var expirations = 0
        var action: suspend (AccountUsageOwner, Long) -> BoundAccountOverview = { _, _ -> bound(available()) }
        val controller = AccountUsageController(scope, { owner, epoch -> calls++; action(owner, epoch) }, { expirations++ })
        val state get() = controller.state.value
        fun bind(owner: AccountUsageOwner = firstOwner, epoch: Long = repositoryEpoch, uiEpoch: Long = uiAuthEpoch) =
            controller.bind(owner, epoch, uiEpoch)
        fun observe(resource: Resource, sequence: Long = 1, revision: Long = 1) =
            controller.observeExternal(bound(resource, sequence, revision), uiAuthEpoch)
    }

    @Test fun guestUpdateDoesNothingAndOwnerMustBeAnAtomicPair() = runTest {
        val fixture = Fixture(this)
        fixture.controller.update(); runCurrent()
        assertEquals(0, fixture.calls); assertNull(fixture.state.resource); assertFalse(fixture.state.authenticated)
        try { fixture.controller.bind(firstOwner, null, uiAuthEpoch); fail("partial context accepted") }
        catch (_: IllegalArgumentException) { }
        assertNull(fixture.state.resource)
    }

    @Test fun updateUsesBoundOwnerEpochAndBusyPreventsRepeatedDispatch() = runTest {
        val fixture = Fixture(this); fixture.bind()
        val release = CompletableDeferred<Unit>(); val sample = available()
        fixture.action = { owner, epoch ->
            assertEquals(firstOwner, owner); assertEquals(repositoryEpoch, epoch)
            release.await(); bound(sample)
        }
        try {
            fixture.controller.update(); fixture.controller.update(); runCurrent()
            assertTrue(fixture.state.busy); assertEquals(1, fixture.calls)
            fixture.controller.update(); assertEquals(1, fixture.calls)
            release.complete(Unit); runCurrent()
            assertSame(sample, fixture.state.resource); assertFalse(fixture.state.busy)
            assertNull(fixture.state.error); assertFalse(fixture.state.notUpdated)
        } finally { release.complete(Unit); fixture.controller.close() }
    }

    @Test fun ordinaryFailurePreservesWholeSampleAndServerTimestamp() = runTest {
        val fixture = Fixture(this); fixture.bind(); val sample = available(status = "stale")
        assertTrue(fixture.observe(sample))
        fixture.action = { _, _ -> throw PortalFailure(PortalFailure.Reason.NETWORK) }
        fixture.controller.update(); runCurrent()
        assertSame(sample, fixture.state.resource)
        assertEquals(cycleStart.plusSeconds(3600), (fixture.state.resource as Resource.Available).usage.updatedAt)
        assertEquals("读取失败，请重试", fixture.state.error); assertTrue(fixture.state.notUpdated)
        assertFalse(fixture.state.busy); assertTrue(fixture.state.authenticated)
    }

    @Test fun firstReadFailureKeepsUnknownRatherThanFabricatingZero() = runTest {
        val fixture = Fixture(this); fixture.bind()
        fixture.action = { _, _ -> throw PortalFailure(PortalFailure.Reason.PROTOCOL) }
        fixture.controller.update(); runCurrent()
        assertNull(fixture.state.resource); assertFalse(fixture.state.notUpdated)
        assertEquals("读取失败，请重试", fixture.state.error); assertEquals(0, fixture.expirations)
    }

    @Test fun cancellingRetryRetainsPriorFailureAndWholeCache() = runTest {
        val fixture = Fixture(this); fixture.bind(); val sample = available()
        fixture.observe(sample)
        fixture.action = { _, _ -> throw PortalFailure(PortalFailure.Reason.NETWORK) }
        fixture.controller.update(); runCurrent()
        val failed = fixture.state
        val release = CompletableDeferred<Unit>()
        fixture.action = { _, _ -> withContext(NonCancellable) { release.await() }; bound(available(used = 2), 2) }
        try {
            fixture.controller.update(); runCurrent(); assertTrue(fixture.state.busy)
            fixture.controller.cancel()
            assertSame(sample, fixture.state.resource); assertEquals(failed.error, fixture.state.error)
            assertTrue(fixture.state.notUpdated); assertFalse(fixture.state.busy)
            release.complete(Unit); runCurrent()
            assertEquals(failed, fixture.state)
        } finally { release.complete(Unit); fixture.controller.close() }
    }

    @Test fun newAccountImmediatelyHidesCacheAndLateOldResponseCannotPublish() = runTest {
        val fixture = Fixture(this); fixture.bind(); fixture.observe(available())
        val release = CompletableDeferred<Unit>()
        fixture.action = { _, _ -> withContext(NonCancellable) { release.await() }; bound(available(used = 2), 2) }
        try {
            fixture.controller.update(); runCurrent()
            fixture.bind(secondOwner, 8, uiAuthEpoch + 1)
            assertNull(fixture.state.resource); assertFalse(fixture.state.notUpdated)
            release.complete(Unit); runCurrent()
            assertNull(fixture.state.resource); assertEquals(0, fixture.expirations)
            assertFalse(fixture.controller.observeExternal(bound(Resource.None, 3), uiAuthEpoch + 1))
        } finally { release.complete(Unit); fixture.controller.close() }
    }

    @Test fun sameAccountNewSessionAndRepositoryEpochEachClearCache() = runTest {
        val fixture = Fixture(this); fixture.bind(); fixture.observe(available())
        val changedToken = owner("fixture_account_a", "fixture_token_replaced")
        fixture.bind(changedToken, repositoryEpoch, uiAuthEpoch + 1)
        assertNull(fixture.state.resource)
        assertFalse(fixture.controller.observeExternal(bound(Resource.None, 2), uiAuthEpoch + 1))
        val newBound = bound(available(), 1, 1, changedToken)
        assertTrue(fixture.controller.observeExternal(newBound, uiAuthEpoch + 1))
        fixture.bind(changedToken, repositoryEpoch + 1, uiAuthEpoch + 1)
        assertNull(fixture.state.resource)
        assertFalse(fixture.controller.observeExternal(newBound, uiAuthEpoch + 1))
    }

    @Test fun externalNewResourceCancelsOldReadAndRejectsOlderSingletonNone() = runTest {
        val fixture = Fixture(this); fixture.bind(); fixture.observe(available())
        val release = CompletableDeferred<Unit>()
        fixture.action = { _, _ -> withContext(NonCancellable) { release.await() }; bound(available(), 2, 1) }
        val next = available('b', used = 8)
        try {
            fixture.controller.update(); runCurrent(); assertTrue(fixture.state.busy)
            assertTrue(fixture.observe(next, 3, 2)); assertFalse(fixture.state.busy)
            assertSame(next, fixture.state.resource)
            assertFalse(fixture.observe(Resource.None, 2, 1))
            release.complete(Unit); runCurrent(); assertSame(next, fixture.state.resource)
        } finally { release.complete(Unit); fixture.controller.close() }
    }

    @Test fun noneAndReconciliationReplaceNumericalCacheAndFencePendingRead() = runTest {
        val fixture = Fixture(this); fixture.bind(); fixture.observe(available())
        val release = CompletableDeferred<Unit>()
        fixture.action = { _, _ -> withContext(NonCancellable) { release.await() }; bound(available(), 2, 1) }
        try {
            fixture.controller.update(); runCurrent()
            assertTrue(fixture.observe(Resource.None, 3, 2))
            assertSame(Resource.None, fixture.state.resource); assertFalse(fixture.state.busy)
            assertTrue(fixture.observe(Resource.Reconciliation, 4, 3))
            assertSame(Resource.Reconciliation, fixture.state.resource)
            release.complete(Unit); runCurrent()
            assertSame(Resource.Reconciliation, fixture.state.resource)
            assertFalse(fixture.observe(available(), 2, 1))
        } finally { release.complete(Unit); fixture.controller.close() }
    }

    @Test fun newPeriodPendingClearsMeasuredFieldsWithoutChangingServiceExpiry() = runTest {
        val fixture = Fixture(this); fixture.bind(); val old = available(); fixture.observe(old)
        val pending = available(status = "pending", start = cycleEnd, end = cycleEnd.plusSeconds(86400))
        assertTrue(fixture.observe(pending, 2, 2))
        assertSame(pending, fixture.state.resource)
        val usage = (fixture.state.resource as Resource.Available).usage
        assertNull(usage.usedBytes); assertNull(usage.remainingBytes); assertNull(usage.updatedAt)
        assertEquals(cycleEnd, usage.cycleStartedAt); assertEquals(old.validUntil, pending.validUntil)
        assertFalse(fixture.observe(old, 1, 1))
    }

    @Test fun sameIdentityNewerSuccessfulObservationRejectsOlderPendingMeasurement() = runTest {
        val fixture = Fixture(this); fixture.bind(); fixture.observe(available())
        val release = CompletableDeferred<Unit>(); val newest = available(used = 9)
        fixture.action = { _, _ -> release.await(); bound(available(used = 8), 2, 1) }
        try {
            fixture.controller.update(); runCurrent()
            assertTrue(fixture.observe(newest, 3, 1)); assertTrue(fixture.state.busy)
            release.complete(Unit); runCurrent()
            assertSame(newest, fixture.state.resource); assertFalse(fixture.state.busy)
        } finally { release.complete(Unit); fixture.controller.close() }
    }

    @Test fun newerSuccessfulPendingReadMayReplaceSameIdentityExternalSample() = runTest {
        val fixture = Fixture(this); fixture.bind(); fixture.observe(available())
        val release = CompletableDeferred<Unit>(); val latest = available(used = 11)
        fixture.action = { _, _ -> release.await(); bound(latest, 3, 1) }
        try {
            fixture.controller.update(); runCurrent()
            assertTrue(fixture.observe(available(used = 9), 2, 1))
            release.complete(Unit); runCurrent(); assertSame(latest, fixture.state.resource)
        } finally { release.complete(Unit); fixture.controller.close() }
    }

    @Test fun lateOrdinaryFailureCannotMarkNewerSuccessfulSampleAsNotUpdated() = runTest {
        val fixture = Fixture(this); fixture.bind(); fixture.observe(available())
        val release = CompletableDeferred<Unit>(); val newest = available(used = 9)
        fixture.action = { _, _ -> release.await(); throw PortalFailure(PortalFailure.Reason.NETWORK) }
        try {
            fixture.controller.update(); runCurrent()
            assertTrue(fixture.observe(newest, 3, 1)); assertTrue(fixture.state.busy)
            release.complete(Unit); runCurrent()
            assertSame(newest, fixture.state.resource); assertFalse(fixture.state.busy)
            assertNull(fixture.state.error); assertFalse(fixture.state.notUpdated)
        } finally { release.complete(Unit); fixture.controller.close() }
    }

    @Test fun failedReadDoesNotAdvanceTheSuccessfulSequenceBarrier() = runTest {
        val fixture = Fixture(this); fixture.bind(); fixture.observe(available(), 1, 1)
        fixture.action = { _, _ -> throw PortalFailure(PortalFailure.Reason.NETWORK) }
        fixture.controller.update(); runCurrent(); assertTrue(fixture.state.notUpdated)
        val olderButSuccessful = available(used = 6)
        assertTrue(fixture.observe(olderButSuccessful, 2, 1))
        assertSame(olderButSuccessful, fixture.state.resource); assertFalse(fixture.state.notUpdated)
        assertNull(fixture.state.error)
    }

    @Test fun foregroundVerificationHidesValuesRejectsCallbacksAndRestoresOnlyVerifiedSameContext() = runTest {
        val fixture = Fixture(this); fixture.bind(); val cached = available(); fixture.observe(cached)
        fixture.action = { _, _ -> throw PortalFailure(PortalFailure.Reason.NETWORK) }
        fixture.controller.update(); runCurrent()
        val failed = fixture.state
        fixture.controller.suspendForVerification()
        assertTrue(fixture.state.verifying); assertNull(fixture.state.resource); assertFalse(fixture.state.authenticated)
        fixture.controller.update(); assertFalse(fixture.observe(Resource.None, 2, 2))
        fixture.bind(firstOwner, repositoryEpoch, uiAuthEpoch + 1)
        assertEquals(failed, fixture.state)
        assertFalse(fixture.controller.observeExternal(bound(Resource.None, 3, 2), uiAuthEpoch))
        assertTrue(fixture.controller.observeExternal(bound(available(used = 5), 3, 1), uiAuthEpoch + 1))
        assertFalse(fixture.state.notUpdated)
    }

    @Test fun foregroundVerificationCancelsOldPendingReadEvenWhenSameOwnerReturns() = runTest {
        val fixture = Fixture(this); fixture.bind(); val cached = available(); fixture.observe(cached)
        val release = CompletableDeferred<Unit>()
        fixture.action = { _, _ -> withContext(NonCancellable) { release.await() }; bound(available(used = 2), 2) }
        try {
            fixture.controller.update(); runCurrent()
            fixture.controller.suspendForVerification(); fixture.bind(firstOwner, repositoryEpoch, uiAuthEpoch + 1)
            release.complete(Unit); runCurrent(); assertSame(cached, fixture.state.resource)
            assertFalse(fixture.state.busy)
        } finally { release.complete(Unit); fixture.controller.close() }
    }

    @Test fun sessionExpiredClearsOwnerAndCallsAccountInvalidationOnce() = runTest {
        val fixture = Fixture(this); fixture.bind(); fixture.observe(available())
        fixture.action = { _, _ -> throw PortalFailure(PortalFailure.Reason.SESSION_EXPIRED) }
        fixture.controller.update(); runCurrent()
        assertEquals(1, fixture.expirations); assertNull(fixture.state.resource)
        assertFalse(fixture.state.authenticated); assertFalse(fixture.state.notUpdated)
        assertEquals("登录已到期，请重新登录", fixture.state.error)
        fixture.controller.update(); runCurrent(); assertEquals(1, fixture.expirations)
    }

    @Test fun lateOldSessionExpiredDoesNotInvalidateNewAccount() = runTest {
        val fixture = Fixture(this); fixture.bind()
        val release = CompletableDeferred<Unit>()
        fixture.action = { _, _ -> withContext(NonCancellable) { release.await() }; throw PortalFailure(PortalFailure.Reason.SESSION_EXPIRED) }
        try {
            fixture.controller.update(); runCurrent(); fixture.bind(secondOwner, 8, uiAuthEpoch + 1)
            val current = available('b')
            assertTrue(fixture.controller.observeExternal(bound(current, 1, 1, secondOwner, 8), uiAuthEpoch + 1))
            release.complete(Unit); runCurrent()
            assertEquals(0, fixture.expirations); assertSame(current, fixture.state.resource)
        } finally { release.complete(Unit); fixture.controller.close() }
    }

    @Test fun malformedMetadataCannotRebindOrChangeResourceWithoutRevision() = runTest {
        val fixture = Fixture(this); fixture.bind(); val original = available(); fixture.observe(original)
        assertFalse(fixture.observe(available('b'), 2, 1))
        assertFalse(fixture.observe(available(used = 3), 1, 1))
        assertFalse(fixture.observe(available(used = 3), 2, 0))
        assertFalse(fixture.controller.observeExternal(bound(Resource.None, 3, 2, secondOwner), uiAuthEpoch))
        assertFalse(fixture.controller.observeExternal(bound(Resource.None, 3, 2, epoch = repositoryEpoch + 1), uiAuthEpoch))
        assertFalse(fixture.observe(Resource.None, 0, 2))
        assertSame(original, fixture.state.resource)
    }

    @Test fun logoutAndCloseRemoveMemoryWithoutDispatchAndClosedCannotRebind() = runTest {
        val fixture = Fixture(this); fixture.bind(); fixture.observe(available())
        fixture.controller.bind(null, null, uiAuthEpoch + 1)
        assertNull(fixture.state.resource); assertFalse(fixture.state.authenticated)
        fixture.controller.update(); runCurrent(); assertEquals(0, fixture.calls)
        fixture.bind(); fixture.observe(available()); fixture.controller.close()
        fixture.bind(); fixture.controller.update(); runCurrent()
        assertNull(fixture.state.resource); assertFalse(fixture.state.authenticated); assertEquals(0, fixture.calls)
    }
}
