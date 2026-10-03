package com.gccwebos.android.portal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PortalRepositoryOverviewReadTest {
    private val now = Instant.parse("2026-10-02T00:00:00Z")
    private val policy = OriginPolicy("https://portal.invalid", setOf("https://sub.invalid"))
    private class Store : DeliveryStore {
        var value: DeliveryRecord? = null
        var writes = 0
        override fun read() = value
        override fun save(record: DeliveryRecord) { value = record; writes++ }
        override fun clear() { value = null; writes++ }
    }
    private class Wire : PortalTransport {
        var accountId = "fixture_account_a"
        var token = "fixture_native_a"
        val calls = mutableListOf<Triple<String, String?, Secret?>>()
        var overview: suspend () -> WireResponse = { WireResponse(200, resourceBody()) }
        override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
            calls += Triple(path, body, session)
            return when(path) {
                "/api/portal/password/session" -> WireResponse(200, """{"session":{"sessionRef":"$token","accountId":"$accountId","issuedAt":"2026-10-02T00:00:00.000Z","expiresAt":"2026-10-02T00:30:00.000Z"}}""")
                "/api/portal/resources/current/overview" -> overview()
                "/api/portal/commerce/model" -> {
                    assertNull(body); assertNotNull(session)
                    WireResponse(200, """{"commerceModel":"wallet_first_unique_v1"}""")
                }
                "/api/portal/resources/current/setup" -> WireResponse(200, """{"delivery":{"handoffRef":"fixture_handoff","kind":"subscription_url","subscriptionUrl":"https://sub.invalid/sub/a/synthetic_only"}}""")
                "/api/portal/resources/current/setup/ack" -> WireResponse(204, "")
                else -> error("Unexpected fixture endpoint")
            }
        }
    }
    private suspend fun login(repo: PortalRepository): AccountUsageOwner {
        val result = repo.login("qa@example.invalid", "synthetic", HumanChallenge("fixture", "1 + 2"), "fixture-answer")
        return AccountUsageOwner.fromSession((result as LoginResult.Authenticated).session)
    }

    @Test fun readUsesOneNarrowGetWithActualAtomicOwnerAndRetainsCycle() = runTest {
        val wire = Wire(); val repo = PortalRepository(wire, Store(), policy, { 0 }, { now })
        val owner = login(repo); wire.calls.clear()
        val snapshot = repo.readAccountOverview(owner)
        assertEquals(owner, snapshot.context.owner); assertTrue(snapshot.context.readSequence > 0)
        assertEquals(1, wire.calls.size)
        assertEquals("/api/portal/resources/current/overview", wire.calls.single().first)
        assertNull(wire.calls.single().second); assertEquals(wire.token, wire.calls.single().third!!.value)
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), (snapshot.resource as Resource.Available).usage.cycleStartedAt)
        assertFalse(snapshot.toString().contains(wire.accountId)); assertFalse(owner.toString().contains(wire.token))
    }

    @Test fun wrongExpectedOwnerIsRejectedBeforeAnyHttp() = runTest {
        val wire = Wire(); val repo = PortalRepository(wire, Store(), policy, { 0 }, { now })
        login(repo); wire.calls.clear()
        val wrong = AccountUsageOwner.fromSession(Session(Secret("fixture_wrong"), "fixture_other", now.plusSeconds(600), 600000, 0))
        try { repo.readAccountOverview(wrong); fail("wrong owner accepted") } catch (_: CancellationException) { }
        assertTrue(wire.calls.isEmpty())
    }

    @Test fun ordinaryReadsAndFailuresDoNotInvalidateImportedCopyDeliveryProfileOrSelection() = runTest {
        val wire = Wire(); val store = Store(); val profiles = MemoryLocalProfileStore()
        var downloads = 0
        val yaml = """proxies:
  - name: Synthetic local node
    type: ss
    server: 192.0.2.10
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""".toByteArray()
        val repo = PortalRepository(wire, store, policy, { 0 }, { now },
            SubscriptionSource { downloads++; yaml.copyOf() }, profiles = profiles)
        val owner = login(repo)
        assertEquals(ImportStatus.IMPORTED, repo.refreshAndImport().status)
        val selection = repo.localSubscriptions.nodeSelectionSnapshot().choices.first().selection
        assertTrue(repo.localSubscriptions.selectNode(selection))
        val localBefore = repo.localSubscriptions.snapshot(); val deliveryBefore = store.value
        val writesBefore = store.writes; val materialBefore = repo.copyImportedConfig()!!.use { it.copyMaterial() }
        wire.calls.clear()
        try {
            repo.readAccountOverview(owner)
            wire.overview = { throw PortalFailure(PortalFailure.Reason.NETWORK) }
            try { repo.readAccountOverview(owner); fail() } catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.NETWORK, failure.reason) }
            wire.overview = { WireResponse(200, "{\"resource\":{\"state\":\"invalid\"}}") }
            try { repo.readAccountOverview(owner); fail() } catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.PROTOCOL, failure.reason) }
            wire.overview = { WireResponse(200, "{\"resource\":{\"state\":\"none\"}}") }
            repo.readAccountOverview(owner)
            assertArrayEquals(materialBefore, repo.copyImportedConfig()!!.use { it.copyMaterial() })
            assertSame(deliveryBefore, store.value); assertEquals(writesBefore, store.writes); assertEquals(1, downloads)
            assertEquals(localBefore, repo.localSubscriptions.snapshot())
            assertSame(selection, repo.localSubscriptions.nodeSelectionSnapshot().selected)
            assertEquals(4, wire.calls.size); assertTrue(wire.calls.all { it.first.endsWith("/overview") && it.second == null })
        } finally { materialBefore.fill(0); yaml.fill(0) }
    }

    @Test fun pendingHttpDoesNotLockLogoutOrNewLoginAndOld401CannotExpireNewOwner() = runTest {
        val wire = Wire(); val repo = PortalRepository(wire, Store(), policy, { 0 }, { now })
        val ownerA = login(repo); val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        wire.overview = { started.complete(Unit); release.await(); WireResponse(401, "") }
        val old = async { repo.readAccountOverview(ownerA) }
        try {
            runCurrent(); assertTrue(started.isCompleted)
            repo.signOutAccount(); wire.accountId = "fixture_account_b"; wire.token = "fixture_native_b"
            val ownerB = login(repo)
            assertFalse(old.isCompleted)
            release.complete(Unit)
            try { old.await(); fail("old owner response accepted") } catch (_: CancellationException) { }
            wire.overview = { WireResponse(200, resourceBody(ref = 'b')) }
            val current = repo.readAccountOverview(ownerB)
            assertEquals(ownerB, current.context.owner); assertNotEquals(ownerA, current.context.owner)
        } finally { release.complete(Unit); old.cancel() }
    }

    @Test fun actualCurrent401InvalidatesAuthWithoutUnlinkingLocalProfile() = runTest {
        val wire = Wire(); val profiles = MemoryLocalProfileStore()
        profiles.save(CachedSubscription("fixture-binding", byteArrayOf(1, 2), now.plusSeconds(3600)))
        val repo = PortalRepository(wire, Store(), policy, { 0 }, { now }, profiles = profiles)
        val owner = login(repo); wire.overview = { WireResponse(401, "") }
        try { repo.readAccountOverview(owner); fail() } catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.SESSION_EXPIRED, failure.reason) }
        val calls = wire.calls.size
        try { repo.readAccountOverview(owner); fail() } catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.SESSION_EXPIRED, failure.reason) }
        assertEquals(calls, wire.calls.size); profiles.read()!!.use { assertArrayEquals(byteArrayOf(1, 2), it.copyBytes()) }
    }

    @Test fun importObservationCarriesOriginalOwnerEpochAndSequenceEvenForSingletonNone() = runTest {
        val wire = Wire(); val repo = PortalRepository(wire, Store(), policy, { 0 }, { now })
        val ownerA = login(repo); wire.overview = { WireResponse(200, "{\"resource\":{\"state\":\"none\"}}") }
        var old: BoundAccountOverview? = null
        assertEquals(ImportStatus.NO_RESOURCE, repo.refreshAndImportObserved({ old = it }).status)
        repo.signOutAccount(); wire.accountId = "fixture_account_b"; wire.token = "fixture_native_b"
        val ownerB = login(repo); val current = repo.refreshWithObservation()
        assertSame(Resource.None, old!!.resource); assertSame(old!!.resource, current.resource)
        assertEquals(ownerA, old!!.context.owner); assertEquals(ownerB, current.context.owner)
        assertNotEquals(old!!.context.repositoryEpoch, current.context.repositoryEpoch)
        assertTrue(current.context.readSequence > old!!.context.readSequence)
    }

    @Test fun resourceAndCycleChangesAdvanceRevisionButSamplesOnlyAdvanceReadSequence() = runTest {
        val wire = Wire(); val repo = PortalRepository(wire, Store(), policy, { 0 }, { now }); val owner = login(repo)
        val a = repo.readAccountOverview(owner)
        val same = repo.readAccountOverview(owner)
        assertEquals(a.context.resourceRevision, same.context.resourceRevision)
        assertTrue(same.context.readSequence > a.context.readSequence)
        wire.overview = { WireResponse(200, resourceBody(cycleStart = "2026-11-01", cycleEnd = "2026-12-01")) }
        val newCycle = repo.readAccountOverview(owner)
        assertTrue(newCycle.context.resourceRevision > same.context.resourceRevision)
        wire.overview = { WireResponse(200, resourceBody(ref = 'b')) }
        val newResource = repo.readAccountOverview(owner)
        assertTrue(newResource.context.resourceRevision > newCycle.context.resourceRevision)
        wire.overview = { WireResponse(200, "{\"resource\":{\"state\":\"none\"}}") }
        assertTrue(repo.readAccountOverview(owner).context.resourceRevision > newResource.context.resourceRevision)
    }

    @Test fun alreadyExpiredCredentialDoesNotIssueHttpOrClearLocalProfile() = runTest {
        var clock = now; val wire = Wire(); val profiles = MemoryLocalProfileStore()
        profiles.save(CachedSubscription("fixture-binding", byteArrayOf(3), now.plusSeconds(3600)))
        val repo = PortalRepository(wire, Store(), policy, { 0 }, { clock }, profiles = profiles)
        val owner = login(repo); wire.calls.clear(); clock = now.plusSeconds(1801)
        try { repo.readAccountOverview(owner); fail() } catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.SESSION_EXPIRED, failure.reason) }
        assertTrue(wire.calls.isEmpty()); profiles.read()!!.use { assertArrayEquals(byteArrayOf(3), it.copyBytes()) }
    }

    @Test fun oldGetCannotRollBackNewObservedResourceOrCycleAndKeepsOriginalIssuedSequence() = runTest {
        for (cycleOnly in listOf(false, true)) {
            val wire = Wire(); val repo = PortalRepository(wire, Store(), policy, { 0 }, { now }); val owner = login(repo)
            val release = CompletableDeferred<Unit>(); val started = CompletableDeferred<Unit>(); var reads = 0
            val newBody = if (cycleOnly) resourceBody(cycleStart = "2026-11-01", cycleEnd = "2026-12-01") else resourceBody(ref = 'b')
            wire.overview = { if (++reads == 1) { started.complete(Unit); release.await(); WireResponse(200, resourceBody()) }
                else WireResponse(200, newBody) }
            val old = async { repo.readAccountOverview(owner) }
            try {
                runCurrent(); assertTrue(started.isCompleted)
                val newer = repo.refreshWithObservation()
                release.complete(Unit)
                try { old.await(); fail("late old period accepted") } catch (_: CancellationException) { }
                val latest = repo.readAccountOverview(owner)
                assertEquals(newer.resource, latest.resource)
                assertEquals(newer.context.resourceRevision, latest.context.resourceRevision)
                assertTrue(latest.context.readSequence > newer.context.readSequence)
            } finally { release.complete(Unit); old.cancel() }
        }
    }

    @Test fun newerFailedReadDoesNotBecomeASuccessfulPublicationFence() = runTest {
        for (failureStatus in listOf(503, 200)) {
            val wire = Wire(); val repo = PortalRepository(wire, Store(), policy, { 0 }, { now }); val owner = login(repo)
            val release = CompletableDeferred<Unit>(); val started = CompletableDeferred<Unit>(); var reads = 0
            wire.overview = { if (++reads == 1) { started.complete(Unit); release.await(); WireResponse(200, resourceBody()) }
                else WireResponse(failureStatus, if (failureStatus == 200) "{\"invalid\":true}" else "") }
            val old = async { repo.readAccountOverview(owner) }
            try {
                runCurrent(); assertTrue(started.isCompleted)
                try { repo.readAccountOverview(owner); fail("new failed read accepted") } catch (_: PortalFailure) { }
                release.complete(Unit)
                assertEquals(1L, old.await().context.readSequence)
            } finally { release.complete(Unit); old.cancel() }
        }
    }

    @Test fun authenticationSnapshotIsAtomicAndWrongRepositoryEpochRejectsBeforeHttp() = runTest {
        val wire = Wire(); val repo = PortalRepository(wire, Store(), policy, { 0 }, { now }); val owner = login(repo)
        val auth = repo.captureAccountUsageAuthentication(owner); wire.calls.clear()
        assertEquals(owner, auth.owner)
        try { repo.readAccountOverview(auth.owner, auth.repositoryEpoch + 1); fail() } catch (_: CancellationException) { }
        assertTrue(wire.calls.isEmpty())
        assertEquals(auth.repositoryEpoch, repo.readAccountOverview(auth.owner, auth.repositoryEpoch).context.repositoryEpoch)
    }

    companion object {
        private fun resourceBody(ref: Char = 'a', cycleStart: String = "2026-10-01", cycleEnd: String = "2026-10-31") =
            """{"resource":{"state":"available","resourceRef":"resource_${ref.toString().repeat(64)}","plan":{"id":"synthetic","durationDays":30,"monthlyTrafficGb":1},"validUntil":"2026-12-31T00:00:00.000Z","setupAvailable":true,"usage":{"totalBytes":100,"usedBytes":25,"remainingBytes":75,"collectionStatus":"measured","updatedAt":"2026-10-02T00:00:00.000Z","cycleStartedAt":"${cycleStart}T00:00:00.000Z","cycleEndsAt":"${cycleEnd}T00:00:00.000Z"}}}"""
    }
}
