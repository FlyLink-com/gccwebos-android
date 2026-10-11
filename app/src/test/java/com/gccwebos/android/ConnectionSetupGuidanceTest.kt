package com.gccwebos.android

import com.gccwebos.android.portal.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** The real dispatcher/controller/repository, with strict synthetic transport and memory stores.
 * No Android activity, filesystem store, HTTP client, permission request or VPN command is used.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConnectionSetupGuidanceTest {
    private val now = Instant.parse("2026-10-03T00:00:00Z")
    private val missing = LocalSubscriptionSnapshot(LocalSubscriptionStatus.MISSING)
    private fun available(setup: Boolean = true, until: Instant = now.plusSeconds(3600),
        status: String = "pending", remaining: Long? = null): Resource.Available =
        Resource.Available("resource_" + "a".repeat(64), "synthetic_setup_plan", until, setup,
            if (status == "pending") Usage(100, null, null, status, null)
            else Usage(100, 100 - requireNotNull(remaining), remaining, status, now), durationDays = 30)

    private fun guidance(resource: Resource?, authenticated: Boolean = true, verified: Boolean = true,
        snapshot: LocalSubscriptionSnapshot = missing, operable: Boolean = true, busy: Boolean = false,
        refreshFailed: Boolean = false) =
        connectionSetupGuidance(authenticated, verified, resource, snapshot, now, operable, busy, refreshFailed)

    @Test fun signedOutChoiceDoesNotBorrowAnOldAccountsResource() {
        val choice = guidance(available(), authenticated = false, verified = false)
        assertEquals(ConnectionSetupAction.AccountChoice, choice.action)
        assertTrue(choice.enabled)
    }

    @Test fun signedOutWithAnImportedSourceAndNoNodeOffersChooseNodeNotPurchase() {
        val kinds = com.gccwebos.android.portal.ManualSourceKind.values()
        for (kind in kinds) {
            val manual = kind in setOf(com.gccwebos.android.portal.ManualSourceKind.ImportedLocal,
                com.gccwebos.android.portal.ManualSourceKind.ImportedRemote, com.gccwebos.android.portal.ManualSourceKind.ExternalRemote)
            assertEquals("$kind no node", manual, connectionChoosesNodeFirst(false, kind, hasSelectedNode = false))
            assertFalse("$kind with a node", connectionChoosesNodeFirst(false, kind, hasSelectedNode = true))
            assertFalse("$kind signed in", connectionChoosesNodeFirst(true, kind, hasSelectedNode = false))
        }
        val choose = connectionSetupGuidance(false, false, null, missing, now, true, chooseNode = true)
        assertEquals(ConnectionSetupAction.ChooseNode, choose.action)
        assertEquals("选择节点", choose.label)
        assertTrue(choose.enabled)
        assertFalse("busy account keeps it disabled", connectionSetupGuidance(false, false, null, missing, now, true, busy = true, chooseNode = true).enabled)
        assertFalse("not operable keeps it disabled", connectionSetupGuidance(false, false, null, missing, now, false, chooseNode = true).enabled)
        // Without the flag the signed-out guidance is unchanged.
        assertEquals("开通飞连vpn", connectionSetupGuidance(false, false, null, missing, now, true).label)
        // A signed-in account never gets it, even when asked.
        assertEquals(ConnectionSetupAction.Plans, connectionSetupGuidance(true, true, Resource.None, missing, now, true, chooseNode = true).action)
    }

    @Test fun chooseNodeDispatchOpensTheNodesTabOnlyAndIsInertWhenDisabled() {
        var nodes = 0
        val g = ConnectionSetupGuidance(ConnectionSetupAction.ChooseNode, "选择节点", true)
        assertTrue(dispatchConnectionSetup(g, { fail("account") }, { fail("plans") }, { fail("sync") }, { nodes++ }))
        assertEquals(1, nodes)
        assertFalse(dispatchConnectionSetup(g.copy(enabled = false), { fail("account") }, { fail("plans") }, { fail("sync") }, { nodes++ }))
        assertEquals(1, nodes)
    }

    @Test fun availableWithoutLocalConfigurationOffersOnlyExplicitSync() {
        for (setup in listOf(true, false)) {
            val sync = guidance(available(setup))
            assertEquals(ConnectionSetupAction.SyncSubscription, sync.action)
            assertEquals("一键连接", sync.label)
            assertTrue(sync.enabled)
            val control = ConnectionControlState(ConnectionPhase.Disconnected, canConnect = true)
            assertEquals(ConnectionPowerAction.None, connectionOverviewPresentation(missing, now, control).action)
        }
    }

    @Test fun confirmedNoResourceOpensPlansInsteadOfCreatingAClaim() {
        val result = guidance(Resource.None)
        assertEquals(ConnectionSetupAction.Plans, result.action)
        assertTrue(result.enabled)
    }

    @Test fun expiredOrCurrentlyMeasuredExhaustionCannotOfferSync() {
        for (resource in listOf(available(until = now), available(until = now.minusNanos(1)),
            available(status = "measured", remaining = 0))) {
            assertEquals(ConnectionSetupAction.Plans, guidance(resource).action)
        }
    }

    @Test fun staleOrPendingUsageCannotInventCurrentQuotaExhaustion() {
        for (resource in listOf(available(status = "stale", remaining = 0), available(),
            available(until = now.plusNanos(1), status = "measured", remaining = 1))) {
            assertEquals(ConnectionSetupAction.SyncSubscription, guidance(resource).action)
            assertTrue(guidance(resource).enabled)
        }
    }

    @Test fun unverifiedUnknownAndReconciliationDoNotClaimOrOpenPlans() {
        for (result in listOf(guidance(available(), verified = false), guidance(null), guidance(Resource.Reconciliation),
            guidance(available(), refreshFailed = true))) {
            assertEquals(ConnectionSetupAction.None, result.action)
            assertFalse(result.enabled)
        }
    }

    @Test fun busyAndHostVerificationDisableEveryExplicitSetupIntent() {
        val inputs = listOf(available() to true, Resource.None to true, available() to false)
        for ((resource, authenticated) in inputs) {
            assertFalse(guidance(resource, authenticated = authenticated, busy = true).enabled)
            assertFalse(guidance(resource, authenticated = authenticated, operable = false).enabled)
        }
        val ready = missing.copy(status = LocalSubscriptionStatus.READY, hasCapability = true,
            hasProfile = true, validUntil = now.plusSeconds(300))
        val result = guidance(available(), snapshot = ready)
        assertEquals(ConnectionSetupAction.None, result.action)
        assertFalse(result.enabled)
    }

    @Test fun dispatcherCallsOnlyTheSelectedEnabledCallback() {
        for (action in ConnectionSetupAction.entries) for (enabled in listOf(false, true)) {
            var accounts = 0; var plans = 0; var syncs = 0; var chooses = 0
            val dispatched = dispatchConnectionSetup(ConnectionSetupGuidance(action, "synthetic", enabled),
                onAccountChoice = { accounts++ }, onPlans = { plans++ }, onSync = { syncs++ }, onChooseNode = { chooses++ })
            val actionable = enabled && action != ConnectionSetupAction.None
            assertEquals(actionable, dispatched)
            assertEquals(if (actionable) 1 else 0, accounts + plans + syncs + chooses)
            assertEquals(if (enabled && action == ConnectionSetupAction.AccountChoice) 1 else 0, accounts)
            assertEquals(if (enabled && action == ConnectionSetupAction.Plans) 1 else 0, plans)
            assertEquals(if (enabled && action == ConnectionSetupAction.SyncSubscription) 1 else 0, syncs)
            assertEquals(if (enabled && action == ConnectionSetupAction.ChooseNode) 1 else 0, chooses)
        }
    }

    @Test fun failedFirstDownloadCanSyncTheAcknowledgedReceiptWithoutDuplicateClaim() = runTest {
        val fixture = Fixture(backgroundScope, failFirstDownload = true, holdRetry = true)
        fixture.login(); runCurrent()
        val account = fixture.account
        assertTrue(account.authenticated); assertFalse(account.busy)
        assertEquals(LocalSubscriptionStatus.MISSING, account.localSubscription.status)
        assertFalse(account.localSubscription.hasCapability)
        assertNotNull(account.error); assertNull(account.importStatus)
        val receipt = requireNotNull(fixture.store.value)
        assertFalse(receipt.pendingAck)
        assertEquals(1, fixture.wire.count(SETUP)); assertEquals(1, fixture.wire.count(ACK))
        fixture.wire.setupAvailable = false
        var prepared = 0; var callbackOwnsBusy = false
        assertTrue(dispatchSync(account) { prepared++; callbackOwnsBusy = account.busy })
        runCurrent()
        assertTrue(fixture.retryStarted.isCompleted); assertTrue(account.busy)
        assertFalse(dispatchSync(account))
        account.refresh() // The actual controller also rejects a duplicate outside Compose.
        runCurrent()
        assertEquals(2, fixture.downloads)
        assertEquals(1, fixture.wire.count(SETUP)); assertEquals(1, fixture.wire.count(ACK))
        assertEquals(0, prepared)
        fixture.retryRelease.complete(Unit); runCurrent()
        assertFalse(account.busy); assertNull(account.error)
        assertEquals(ImportStatus.IMPORTED, account.importStatus)
        assertEquals(LocalSubscriptionStatus.READY, account.localSubscription.status)
        assertTrue(account.localSubscription.hasProfile); assertTrue(account.localSubscription.hasCapability)
        assertSame(receipt, fixture.store.value)
        assertEquals(1, prepared); assertTrue(callbackOwnsBusy)
        assertEquals(1, fixture.wire.count(SETUP)); assertEquals(1, fixture.wire.count(ACK))
        assertEquals(2, fixture.wire.count("/api/portal/commerce/model"))
        assertNull(account.nodeSelection.snapshot.selected)
        assertFalse(dispatchSync(account))
    }

    @Test fun equalRepeatedOverviewCompletesPreparationWithoutRepeatingClaimOrAck() = runTest {
        val fixture = Fixture(backgroundScope, failFirstDownload = true, holdRetry = true)
        fixture.login(); runCurrent()
        val account = fixture.account
        val firstResource = requireNotNull(account.accountUsage.state.value.resource)
        val receipt = requireNotNull(fixture.store.value)
        assertTrue(account.authenticated); assertFalse(account.busy)
        assertEquals(LocalSubscriptionStatus.MISSING, account.localSubscription.status)
        assertFalse(receipt.pendingAck)
        assertEquals(1, fixture.wire.count(SETUP)); assertEquals(1, fixture.wire.count(ACK))
        // Keep every overview field, including setupAvailable, identical. Changing it
        // would make the StateFlow value unequal and conceal the accepted-reference bug.
        var prepared = 0
        var callbackOwnsBusy = false
        var callbackOwnsRequest = false
        var request: Long? = null
        request = account.prepareForConnection {
            prepared++
            callbackOwnsBusy = account.busy
            callbackOwnsRequest = request?.let(account::isConnectionPreparationPending) == true
        }
        val ownedRequest = requireNotNull(request)
        runCurrent()
        assertTrue(fixture.retryStarted.isCompleted); assertTrue(account.busy)
        assertTrue(account.isConnectionPreparationPending(ownedRequest))
        val acceptedResource = requireNotNull(account.resource)
        assertNotSame(firstResource, acceptedResource)
        assertEquals(firstResource, acceptedResource)
        assertSame(firstResource, account.accountUsage.state.value.resource)
        assertEquals(0, prepared)
        fixture.retryRelease.complete(Unit); runCurrent()
        assertFalse(account.busy); assertNull(account.error)
        assertFalse(account.isConnectionPreparationPending(ownedRequest))
        assertEquals(ImportStatus.IMPORTED, account.importStatus)
        assertEquals(LocalSubscriptionStatus.READY, account.localSubscription.status)
        assertTrue(account.localSubscription.hasProfile); assertTrue(account.localSubscription.hasCapability)
        assertSame(receipt, fixture.store.value)
        assertEquals(2, fixture.downloads)
        assertEquals(1, fixture.wire.count(SETUP)); assertEquals(1, fixture.wire.count(ACK))
        assertEquals(2, fixture.wire.count(OVERVIEW))
        assertEquals(2, fixture.wire.count("/api/portal/commerce/model"))
        assertEquals(1, prepared)
        assertTrue(callbackOwnsBusy); assertTrue(callbackOwnsRequest)
    }

    @Test fun setupUnavailableWithoutReceiptStaysWaitingAndNeverInventsAClaim() = runTest {
        val fixture = Fixture(backgroundScope, setupAvailable = false)
        fixture.login(); runCurrent()
        assertTrue(fixture.account.authenticated)
        assertEquals(ImportStatus.WAITING_DELIVERY, fixture.account.importStatus)
        assertEquals(LocalSubscriptionStatus.MISSING, fixture.account.localSubscription.status)
        assertFalse(fixture.account.localSubscription.hasCapability)
        var prepared = 0
        assertTrue(dispatchSync(fixture.account) { prepared++ }); runCurrent()
        assertEquals(ImportStatus.WAITING_DELIVERY, fixture.account.importStatus)
        assertNull(fixture.store.value); assertEquals(0, fixture.downloads)
        assertEquals(0, fixture.wire.count(SETUP)); assertEquals(0, fixture.wire.count(ACK))
        assertEquals(5, fixture.wire.paths.size) // Login, then overview/model for each explicit import.
        assertEquals(0, prepared)
    }

    @Test fun failedUsageRefreshCannotUseRetainedAvailableToDispatchSubscriptionSetup() = runTest {
        val fixture = Fixture(backgroundScope, failFirstDownload = true)
        fixture.login(); runCurrent()
        val before = fixture.account.accountUsage.state.value.resource
        assertTrue(before is Resource.Available)
        assertEquals(LocalSubscriptionStatus.MISSING, fixture.account.localSubscription.status)
        fixture.wire.failOverview = true
        fixture.account.refreshAccountUsage(); runCurrent()
        val usage = fixture.account.accountUsage.state.value
        assertSame(before, usage.resource)
        assertNotNull(usage.error); assertTrue(usage.notUpdated)
        assertFalse(dispatchSync(fixture.account))
        assertEquals(1, fixture.downloads)
        assertEquals(1, fixture.wire.count(SETUP)); assertEquals(1, fixture.wire.count(ACK))
        assertEquals(2, fixture.wire.count(OVERVIEW))
    }

    @Test fun precisePreparationCancellationKeepsReceiptAndRejectsANoncancellableLateDownload() = runTest {
        val fixture = Fixture(backgroundScope, failFirstDownload = true, holdRetry = true, lateDownload = true)
        fixture.login(); runCurrent()
        val receipt = fixture.store.value
        var prepared = 0
        val request = requireNotNull(fixture.account.prepareForConnection { prepared++ })
        runCurrent(); assertTrue(fixture.retryStarted.isCompleted)
        fixture.account.cancelConnectionPreparation(request + 1)
        assertTrue(fixture.account.busy) // A foreign request cannot cancel this one.
        fixture.account.cancelConnectionPreparation(request)
        assertFalse(fixture.account.busy); assertTrue(fixture.account.authenticated)
        assertEquals("准备已取消", fixture.account.error)
        fixture.retryRelease.complete(Unit); runCurrent()
        assertEquals(0, prepared); assertSame(receipt, fixture.store.value)
        assertEquals(1, fixture.wire.count(SETUP)); assertEquals(1, fixture.wire.count(ACK))
    }

    @Test fun logoutInvalidatesPreparationBeforeANoncancellableDownloadCanComplete() = runTest {
        val fixture = Fixture(backgroundScope, failFirstDownload = true, holdRetry = true, lateDownload = true)
        fixture.login(); runCurrent()
        var prepared = 0
        fixture.account.prepareForConnection { prepared++ }
        runCurrent(); assertTrue(fixture.retryStarted.isCompleted)
        fixture.account.logout(); runCurrent()
        assertFalse(fixture.account.authenticated)
        fixture.retryRelease.complete(Unit); runCurrent()
        assertEquals(0, prepared); assertFalse(fixture.account.authenticated)
        assertEquals(1, fixture.wire.count(SETUP)); assertEquals(1, fixture.wire.count(ACK))
    }

    @Test fun aNewAccountCannotAdoptThePreviousPreparationsCompletionCallback() = runTest {
        val fixture = Fixture(backgroundScope, failFirstDownload = true, holdRetry = true, lateDownload = true)
        fixture.login(); runCurrent()
        var oldPrepared = 0
        fixture.account.prepareForConnection { oldPrepared++ }
        runCurrent(); assertTrue(fixture.retryStarted.isCompleted)
        val oldGeneration = fixture.account.authSessionGeneration
        fixture.account.logout(); runCurrent()
        fixture.retryRelease.complete(Unit); runCurrent()
        fixture.wire.accountId = "fixture_setup_other_account"
        fixture.wire.token = "fixture_setup_other_token"
        fixture.login(); runCurrent()
        assertTrue(fixture.account.authenticated)
        assertTrue(fixture.account.authSessionGeneration > oldGeneration)
        assertEquals(0, oldPrepared)
        assertEquals("fixture_setup_other_account", fixture.store.value?.accountId)
    }

    @Test fun aNewerVerifiedResourcePublicationCannotCompleteTheOlderPreparationIntent() = runTest {
        val fixture = Fixture(backgroundScope, failFirstDownload = true, holdRetry = true)
        fixture.login(); runCurrent()
        val account = fixture.account
        val owner = AccountUsageOwner.fromAccepted(fixture.wire.accountId, sessionBinding(Secret(fixture.wire.token)))
        val authentication = fixture.repo.captureAccountUsageAuthentication(owner)
        val authGeneration = account.authSessionGeneration
        var prepared = 0
        val request = requireNotNull(account.prepareForConnection { prepared++ })
        runCurrent(); assertTrue(fixture.retryStarted.isCompleted); assertTrue(account.busy)
        val original = account.accountUsage.state.value.resource
        assertTrue(original is Resource.Available)
        fixture.wire.referenceCharacter = 'b'
        var newer: BoundAccountOverview? = null
        var published = false
        // Import owns the repository mutex while download is held. Queue a genuine read now;
        // Unconfined resumes it at mutex handoff before the old controller publishes completion.
        // No BoundAccountOverview, revision or read sequence is manufactured by the test.
        val observation = backgroundScope.launch(Dispatchers.Unconfined) {
            val bound = fixture.repo.readAccountOverview(authentication.owner, authentication.repositoryEpoch)
            assertTrue(account.isConnectionPreparationPending(request))
            assertEquals(0, prepared)
            assertTrue(account.acceptAccountOverviewObservation(bound, authentication, authGeneration))
            newer = bound; published = true
        }
        assertFalse(published); assertTrue(observation.isActive)
        fixture.retryRelease.complete(Unit); runCurrent()
        assertTrue(observation.isCompleted); assertTrue(published)
        assertNotSame(original, requireNotNull(newer).resource)
        assertEquals("resource_" + "b".repeat(64), (newer!!.resource as Resource.Available).reference)
        assertSame(newer!!.resource, account.resource)
        assertSame(newer!!.resource, account.accountUsage.state.value.resource)
        assertEquals(authGeneration, account.authSessionGeneration)
        assertTrue(account.authenticated); assertFalse(account.busy)
        assertEquals(0, prepared)
        assertEquals(1, fixture.wire.count(SETUP)); assertEquals(1, fixture.wire.count(ACK))
    }

    private fun dispatchSync(account: AccountController, onPrepared: () -> Unit = {}): Boolean {
        val usage = account.accountUsage.state.value
        val result = connectionSetupGuidance(account.authenticated, usage.authenticated && !usage.verifying,
            usage.resource, account.localSubscription, now,
            !account.busy && !account.restoring && !account.foregroundCheckPending && !account.clearing && !usage.busy,
            account.busy, refreshFailed = usage.error != null)
        return dispatchConnectionSetup(result, onAccountChoice = { fail("Unexpected account navigation") },
            onPlans = { fail("Unexpected plan navigation") }, onSync = { account.prepareForConnection(onPrepared) })
    }

    private class Store : DeliveryStore {
        var value: DeliveryRecord? = null
        override fun read() = value
        override fun save(record: DeliveryRecord) { value = record }
        override fun clear() { value = null }
    }

    private class Wire(var setupAvailable: Boolean) : PortalTransport {
        val paths = mutableListOf<String>()
        var failOverview = false
        var accountId = "fixture_setup_account"
        var token = "fixture_setup_token"
        var referenceCharacter = 'a'
        fun count(path: String) = paths.count { it == path }
        override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
            paths += path
            return when (path) {
                LOGIN -> WireResponse(200, """{"session":{"sessionRef":"$token","accountId":"$accountId","issuedAt":"2026-10-03T00:00:00.000Z","expiresAt":"2026-10-03T00:30:00.000Z"}}""")
                OVERVIEW -> if (failOverview) WireResponse(503, "") else WireResponse(200, """{"resource":{"state":"available","resourceRef":"resource_${referenceCharacter.toString().repeat(64)}","plan":{"id":"synthetic_setup_plan","durationDays":30,"monthlyTrafficGb":1},"validUntil":"2026-11-03T00:00:00.000Z","setupAvailable":$setupAvailable,"usage":{"totalBytes":100,"usedBytes":25,"remainingBytes":75,"collectionStatus":"measured","updatedAt":"2026-10-03T00:00:00.000Z","cycleStartedAt":"2026-10-03T00:00:00.000Z","cycleEndsAt":"2026-11-02T00:00:00.000Z"}}}""")
                "/api/portal/commerce/model" -> {
                    assertNull(body); assertNotNull(session)
                    WireResponse(200, """{"commerceModel":"wallet_first_unique_v1"}""")
                }
                SETUP -> WireResponse(200, """{"delivery":{"handoffRef":"fixture_setup_receipt","kind":"subscription_url","subscriptionUrl":"https://sub.invalid/sub/a/setup-fixture"}}""")
                ACK -> WireResponse(204, "")
                else -> throw AssertionError("Unexpected setup-fixture endpoint: $path")
            }
        }
    }

    private inner class Fixture(scope: CoroutineScope, setupAvailable: Boolean = true,
        val failFirstDownload: Boolean = false, val holdRetry: Boolean = false, val lateDownload: Boolean = false) {
        val store = Store()
        val wire = Wire(setupAvailable)
        var downloads = 0
        val retryStarted = CompletableDeferred<Unit>()
        val retryRelease = CompletableDeferred<Unit>()
        val repo = PortalRepository(wire, store,
            OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0 }, { now },
            SubscriptionSource { url ->
                assertEquals("https://sub.invalid/sub/a/setup-fixture", url.value)
                assertFalse(requireNotNull(store.value).pendingAck)
                downloads++
                if (failFirstDownload && downloads == 1) throw PortalFailure(PortalFailure.Reason.NETWORK)
                if (holdRetry && downloads == 2) {
                    retryStarted.complete(Unit)
                    if (lateDownload) withContext(NonCancellable) { retryRelease.await() } else retryRelease.await()
                }
                YAML.toByteArray()
            }, sessions = MemorySessionStore(), profiles = MemoryLocalProfileStore())
        val account = AccountController(repo, scope)
        fun login() {
            account.account = "setup.fixture@example.invalid"; account.password = "synthetic-only"
            account.answer = "synthetic-answer"
            account.challenge = HumanChallenge("fixture_not_a_server_challenge", "synthetic question, no solver")
            account.login()
        }
    }

    private companion object {
        const val LOGIN = "/api/portal/password/session"
        const val OVERVIEW = "/api/portal/resources/current/overview"
        const val SETUP = "/api/portal/resources/current/setup"
        const val ACK = "/api/portal/resources/current/setup/ack"
        val YAML = """
            proxies:
              - name: synthetic setup node
                type: ss
                server: 192.0.2.1
                port: 443
                cipher: aes-128-gcm
                password: synthetic-only
        """.trimIndent()
    }
}
