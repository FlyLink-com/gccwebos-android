package com.gccwebos.android.portal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Actual AccountController + actual parser/repository, all transport and stores in memory. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountUsageBindingTest {
    private val now = Instant.parse("2026-10-02T00:00:00Z")
    private val owner = AccountUsageOwner.fromSession(Session(Secret("fixture_usage_token"), "fixture_usage_account",
        now.plusSeconds(1800), 1800000, 0))
    private class Wire : PortalTransport {
        val paths = mutableListOf<String>()
        var overview: suspend () -> WireResponse = { none() }
        override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
            paths += path
            return when(path) {
                "/api/portal/password/session" -> WireResponse(200, """{"session":{"sessionRef":"fixture_usage_token","accountId":"fixture_usage_account","issuedAt":"2026-10-02T00:00:00.000Z","expiresAt":"2026-10-02T00:30:00.000Z"}}""")
                "/api/portal/resources/current/overview" -> overview()
                else -> throw AssertionError("Unexpected account fixture endpoint")
            }
        }
        companion object { fun none() = WireResponse(200, """{"resource":{"state":"none"}}""") }
    }
    private fun fixture(scope: CoroutineScope, wire: Wire): Pair<PortalRepository, AccountController> {
        val store = object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = throw AssertionError("No configuration/claim writes in binding fixture")
            override fun clear() = Unit
        }
        val repo = PortalRepository(wire, store, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")),
            { 0 }, { now }, SubscriptionSource { throw AssertionError("No configuration download") })
        return repo to AccountController(repo, scope)
    }
    private fun AccountController.prepareLogin() {
        account = "fixture@example.invalid"; password = "synthetic"; answer = "fixture-answer"
        challenge = HumanChallenge("fixture-question", "synthetic fixture question")
        login()
    }

    @Test fun delayedOriginalNoneCannotOverwriteANewerAcceptedAvailableObservation() = runTest {
        val wire = Wire(); val (repo, state) = fixture(backgroundScope, wire)
        state.prepareLogin(); runCurrent()
        val auth = repo.captureAccountUsageAuthentication(owner)
        val oldNone = repo.readAccountOverview(owner, auth.repositoryEpoch)
        wire.overview = { available('b') }
        val current = repo.readAccountOverview(owner, auth.repositoryEpoch)
        assertTrue(state.acceptAccountOverviewObservation(current, auth, state.authSessionGeneration))
        assertFalse(state.acceptAccountOverviewObservation(oldNone, auth, state.authSessionGeneration))
        assertSame(current.resource, state.resource); assertSame(current.resource, state.accountUsage.state.value.resource)
    }

    @Test fun delayedOldCycleCannotOverwriteResourceOrUsageAfterNewCycleAccepted() = runTest {
        val wire = Wire(); val (repo, state) = fixture(backgroundScope, wire)
        state.prepareLogin(); runCurrent(); val auth = repo.captureAccountUsageAuthentication(owner)
        wire.overview = { available('a') }; val old = repo.readAccountOverview(owner, auth.repositoryEpoch)
        wire.overview = { available('a', "2026-11-01", "2026-12-01") }
        val current = repo.readAccountOverview(owner, auth.repositoryEpoch)
        assertTrue(state.acceptAccountOverviewObservation(current, auth, state.authSessionGeneration))
        assertFalse(state.acceptAccountOverviewObservation(old, auth, state.authSessionGeneration))
        assertSame(current.resource, state.resource)
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), (state.resource as Resource.Available).usage.cycleStartedAt)
    }

    @Test fun sameAccountAndTokenReauthenticationDoesNotRebindOldSingletonCallback() = runTest {
        val wire = Wire(); val (repo, state) = fixture(backgroundScope, wire)
        state.prepareLogin(); runCurrent(); val auth = repo.captureAccountUsageAuthentication(owner)
        val old = repo.readAccountOverview(owner, auth.repositoryEpoch); val oldUiEpoch = state.authSessionGeneration
        state.logout(); runCurrent(); state.prepareLogin(); runCurrent()
        val currentAuth = repo.captureAccountUsageAuthentication(owner)
        assertNotEquals(auth.repositoryEpoch, currentAuth.repositoryEpoch)
        assertFalse(state.acceptAccountOverviewObservation(old, auth, oldUiEpoch))
        assertFalse(state.acceptAccountOverviewObservation(old, currentAuth, state.authSessionGeneration))
        assertSame(Resource.None, state.resource)
    }

    @Test fun readOnlyUsageUpdateDoesNotWriteLegacyImportResourceOrCallSetup() = runTest {
        val wire = Wire(); val (repo, state) = fixture(backgroundScope, wire)
        state.prepareLogin(); runCurrent(); assertSame(Resource.None, state.resource)
        wire.paths.clear(); wire.overview = { available('b') }
        state.refreshAccountUsage(); runCurrent()
        assertEquals(listOf("/api/portal/resources/current/overview"), wire.paths)
        assertSame(Resource.None, state.resource)
        assertEquals("resource_" + "b".repeat(64), (state.accountUsage.state.value.resource as Resource.Available).reference)
        assertFalse(state.accountUsage.state.value.busy)
        assertEquals(owner, repo.captureAccountUsageAuthentication(owner).owner)
    }

    @Test fun foregroundVerificationDuringPendingLoadRejectsOldResourceBeforePublishing() = runTest {
        for (response in listOf(Wire.none(), available('a'))) {
            val wire = Wire(); val (_, state) = fixture(backgroundScope, wire)
            val firstStarted = CompletableDeferred<Unit>(); val firstRelease = CompletableDeferred<Unit>()
            val validationStarted = CompletableDeferred<Unit>(); val validationRelease = CompletableDeferred<Unit>()
            var reads = 0
            wire.overview = {
                if (++reads == 1) { firstStarted.complete(Unit); firstRelease.await(); response }
                else { validationStarted.complete(Unit); validationRelease.await(); Wire.none() }
            }
            try {
                state.prepareLogin(); runCurrent(); assertTrue(firstStarted.isCompleted); assertTrue(state.busy)
                state.onForeground(); assertTrue(state.accountUsage.state.value.verifying)
                firstRelease.complete(Unit); runCurrent(); assertTrue(validationStarted.isCompleted)
                assertNull(state.resource); assertNull(state.accountUsage.state.value.resource)
                assertTrue(state.accountUsage.state.value.verifying)
            } finally {
                firstRelease.complete(Unit); validationRelease.complete(Unit)
                state.cancelAuthentication(); runCurrent()
            }
        }
    }

    companion object {
        private fun available(ref: Char, start: String = "2026-10-01", end: String = "2026-10-31") = WireResponse(200,
            """{"resource":{"state":"available","resourceRef":"resource_${ref.toString().repeat(64)}","plan":{"id":"synthetic","durationDays":30,"monthlyTrafficGb":1},"validUntil":"2026-12-31T00:00:00.000Z","setupAvailable":false,"usage":{"totalBytes":100,"usedBytes":25,"remainingBytes":75,"collectionStatus":"measured","updatedAt":"2026-10-02T00:00:00.000Z","cycleStartedAt":"${start}T00:00:00.000Z","cycleEndsAt":"${end}T00:00:00.000Z"}}}""")
    }
}
