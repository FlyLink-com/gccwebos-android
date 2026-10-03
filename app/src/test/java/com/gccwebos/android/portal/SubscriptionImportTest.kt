package com.gccwebos.android.portal

import java.time.Instant
import com.gccwebos.android.core.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** All material is synthetic; no network, accounts, claims, VPN, or billing side effects. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SubscriptionImportTest {
    private val start = Instant.parse("2026-09-30T00:00:00Z")
    private val reference = "resource_" + "a".repeat(64)
    private val policy = OriginPolicy("https://portal.invalid", setOf("https://sub.invalid"))
    private val yaml = """
        proxies:
          - name: synthetic only
            type: ss
            server: 192.0.2.1
            port: 443
            cipher: aes-128-gcm
            password: not-a-real-password
        rules: [MATCH,DIRECT]
        dns: {enable: true, nameserver: [https://ignored.invalid/dns]}
    """.trimIndent()
    private class Store : DeliveryStore {
        var value: DeliveryRecord? = null
        override fun read() = value
        override fun save(record: DeliveryRecord) { value = record }
        override fun clear() { value = null }
    }
    private class Wire : PortalTransport {
        val replies = ArrayDeque<WireResponse>()
        val modelReplies = ArrayDeque<WireResponse>()
        var requireQueuedModel = false
        // Ordinary import fixtures are wallet-first; fresh probes enqueue their explicit model.
        private val ordinaryModel = WireResponse(200, """{"commerceModel":"wallet_first_unique_v1"}""")
        val calls = mutableListOf<Pair<String, String?>>()
        var requestHook: suspend (String) -> Unit = {}
        override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
            calls += path to body
            requestHook(path)
            if (path == "/api/portal/commerce/model") {
                assertNull(body); assertNotNull(session)
                return if (requireQueuedModel) modelReplies.removeFirst() else ordinaryModel
            }
            return replies.removeFirst()
        }
    }
    private inner class Harness(val store: Store = Store()) {
        val wire = Wire()
        var now = start
        var elapsed = 0L
        var expectDurableAck = true
        var downloads = 0
        var raw = yaml.toByteArray()
        var downloadFailure: PortalFailure.Reason? = null
        var downloadHook: suspend () -> Unit = {}
        val repo = PortalRepository(wire, store, policy, { elapsed }, { now }, SubscriptionSource { url ->
            assertEquals("https://sub.invalid/sub/a/fixture", url.value)
            if (expectDurableAck) assertFalse(store.value!!.pendingAck)
            downloads++
            downloadHook()
            downloadFailure?.let { throw PortalFailure(it) }
            raw
        })
        suspend fun login(token: String = "session_fixture", account: String = "a@example.invalid") {
            wire.replies += WireResponse(200, """{"session":{"sessionRef":"$token","accountId":"$account","issuedAt":"2026-09-30T00:00:00.000Z","expiresAt":"2026-09-30T00:30:00.000Z"}}""")
            repo.login(account, "synthetic", HumanChallenge("fixture", "1 + 2"), "3")
        }
        fun overview(available: Boolean = true, ref: String = reference, case: String = "pending", expired: Boolean = false, expiresAt: String? = null) {
            val fixtures = JSONObject(requireNotNull(javaClass.getResource("/portal-cases.json")).readText())
            val response = fixtures.getJSONObject("resources").getJSONObject(case)
            if (case !in setOf("none", "reconciliation")) {
                response.getJSONObject("resource").put("setupAvailable", available).put("resourceRef", ref)
                if (expired) response.getJSONObject("resource").put("validUntil", "2026-09-29T00:00:00.000Z")
                if (expiresAt != null) response.getJSONObject("resource").put("validUntil", expiresAt)
            }
            wire.replies += WireResponse(200, response.toString())
        }
        fun delivery(ack: Int = 204) {
            wire.replies += WireResponse(200, """{"delivery":{"handoffRef":"fixture_receipt","kind":"subscription_url","subscriptionUrl":"https://sub.invalid/sub/a/fixture"}}""")
            wire.replies += WireResponse(ack, "")
        }
        fun saved(pending: Boolean = false, token: String = "session_fixture", ref: String = reference) {
            store.value = DeliveryRecord("a@example.invalid", sessionBinding(Secret(token)), ref, "fixture_operation",
                Secret("https://sub.invalid/sub/a/fixture"), Secret("fixture_receipt"), pending)
        }
        suspend fun successfulImport() {
            login(); overview(); delivery()
            assertEquals(ImportStatus.IMPORTED, repo.refreshAndImport().status)
        }
    }
    @Test fun importsAutomaticallyAfterDurableAckAndProjectsOwnedRules() = runTest {
        val h = Harness(); h.successfulImport()
        assertEquals(1, h.downloads); assertTrue(h.raw.all { it == 0.toByte() })
        h.repo.copyImportedConfig()!!.use { profile ->
            val bytes = profile.copyMaterial()
            try {
                val value = JSONObject(String(bytes))
                assertEquals(setOf("schemaVersion", "proxies", "rules"), value.keys().asSequence().toSet())
                assertEquals("MATCH,GCC_SELECTED_PROXY", value.getJSONArray("rules").getString(0))
                assertFalse(String(bytes).contains("ignored.invalid"))
                assertFalse(profile.toString().contains("not-a-real-password"))
            } finally { bytes.fill(0) }
        }
    }
    @Test fun noPlanAndReconciliationNeverClaimOrDownload() = runTest {
        for ((case, status) in listOf("none" to ImportStatus.NO_RESOURCE, "reconciliation" to ImportStatus.RECONCILIATION)) {
            val h = Harness(); h.login(); h.overview(case = case)
            assertEquals(status, h.repo.refreshAndImport().status)
            assertEquals(0, h.downloads); assertNull(h.repo.copyImportedConfig()); assertNull(h.store.value)
            assertFalse(h.wire.calls.any { it.first.contains("/setup") })
        }
    }
    @Test fun expiredResourceNeverClaims() = runTest {
        val h = Harness(); h.login(); h.overview(expired = true)
        assertEquals(ImportStatus.EXPIRED, h.repo.refreshAndImport().status)
        assertEquals(0, h.downloads); assertNull(h.store.value)
    }
    @Test fun exhaustedResourceNeverClaimsButUnknownQuotaIsNotInventedExhaustion() = runTest {
        val h = Harness(); h.login(); h.overview(case = "over_quota")
        assertEquals(ImportStatus.QUOTA_EXHAUSTED, h.repo.refreshAndImport().status)
        assertEquals(0, h.downloads); assertNull(h.store.value)
        h.overview(case = "zero_quota"); h.delivery()
        assertEquals(ImportStatus.IMPORTED, h.repo.refreshAndImport().status)
    }
    @Test fun sessionExpiryDuringDownloadDiscardsMaterial() = runTest {
        val h = Harness(); h.login(); h.overview(); h.delivery()
        h.downloadHook = { h.now = Instant.parse("2026-11-01T00:00:00Z") }
        // Session also expires: no imported profile is ever exposed.
        try { h.repo.refreshAndImport(); fail() }
        catch (e: PortalFailure) { assertEquals(PortalFailure.Reason.SESSION_EXPIRED, e.reason) }
        assertTrue(h.raw.all { it == 0.toByte() })
    }
    @Test fun resourceExpiryBeforeSessionDeadlineDiscardsDownloadedMaterial() = runTest {
        val h = Harness(); h.login(); h.overview(expiresAt = "2026-09-30T00:01:00.000Z"); h.delivery()
        h.downloadHook = { h.now = start.plusSeconds(61) }
        assertEquals(ImportStatus.EXPIRED, h.repo.refreshAndImport().status)
        assertNull(h.repo.copyImportedConfig()); assertTrue(h.raw.all { it == 0.toByte() })
    }
    @Test fun setupUnavailableWithoutMaterialWaitsWithoutGuessingNewClaim() = runTest {
        val h = Harness(); h.login(); h.overview(available = false)
        assertEquals(ImportStatus.WAITING_DELIVERY, h.repo.refreshAndImport().status)
        assertEquals(0, h.downloads); assertNull(h.store.value)
    }
    @Test fun acknowledgedMaterialImportsWhenSetupAvailableIsFalse() = runTest {
        val h = Harness(); h.saved(token = "previous_session"); h.login(); h.overview(available = false)
        assertEquals(ImportStatus.IMPORTED, h.repo.refreshAndImport().status)
        assertEquals(1, h.downloads); assertFalse(h.wire.calls.any { it.first.contains("/setup") })
    }
    @Test fun pendingAckRetriesSameReceiptWhenSetupUnavailable() = runTest {
        val h = Harness(); h.saved(pending = true); h.login(); h.overview(available = false)
        h.wire.replies += WireResponse(204, "")
        assertEquals(ImportStatus.IMPORTED, h.repo.refreshAndImport().status)
        assertEquals(1, h.wire.calls.count { it.first.endsWith("/ack") })
        assertFalse(h.wire.calls.any { it.first.endsWith("/setup") })
        assertEquals("fixture_receipt", JSONObject(h.wire.calls.last().second!!).getString("handoffRef"))
    }
    @Test fun setupResponseLostRetriesDurableOperationWhenSetupUnavailable() = runTest {
        val h = Harness(); h.login()
        h.store.value = DeliveryRecord("a@example.invalid", sessionBinding(Secret("session_fixture")), reference, "same-operation")
        h.overview(available = false); h.delivery()
        assertEquals(ImportStatus.IMPORTED, h.repo.refreshAndImport().status)
        val setup = h.wire.calls.single { it.first.endsWith("/setup") }
        assertEquals("same-operation", JSONObject(setup.second!!).getString("idempotencyKey"))
    }
    @Test fun ackFailureNeverDownloadsAndRetryDoesNotClaimTwice() = runTest {
        val h = Harness(); h.login(); h.overview(); h.delivery(503)
        try { h.repo.refreshAndImport(); fail() } catch (_: PortalFailure) { }
        assertEquals(0, h.downloads); assertTrue(h.store.value!!.pendingAck)
        h.overview(available = false); h.wire.replies += WireResponse(204, "")
        assertEquals(ImportStatus.IMPORTED, h.repo.refreshAndImport().status)
        assertEquals(1, h.wire.calls.count { it.first.endsWith("/setup") })
    }
    @Test fun processRestartReimportsAcknowledgedRecordAfterSameAccountLogin() = runTest {
        val first = Harness(); first.successfulImport()
        val restarted = Harness(first.store); restarted.login("new_session"); restarted.overview(available = false)
        assertEquals(ImportStatus.IMPORTED, restarted.repo.refreshAndImport().status)
        assertFalse(restarted.wire.calls.any { it.first.contains("/setup") })
    }
    @Test fun processRestartPendingAckRequiresRecoveryAndPreservesReceipt() = runTest {
        val h = Harness(); h.saved(pending = true, token = "previous_session"); val before = h.store.value
        h.login(); h.overview(available = false)
        try { h.repo.refreshAndImport(); fail() }
        catch (e: PortalFailure) { assertEquals(PortalFailure.Reason.RECOVERY_REQUIRED, e.reason) }
        assertSame(before, h.store.value); assertEquals(0, h.downloads)
        assertFalse(h.wire.calls.any { it.first.contains("/setup") })
    }
    @Test fun changedResourceDoesNotDestroyOrRebindSavedMaterial() = runTest {
        val h = Harness(); h.saved(); val before = h.store.value
        h.login(); h.overview(ref = "resource_" + "b".repeat(64))
        try { h.repo.refreshAndImport(); fail() }
        catch (e: PortalFailure) { assertEquals(PortalFailure.Reason.RECOVERY_REQUIRED, e.reason) }
        assertSame(before, h.store.value); assertEquals(0, h.downloads)
    }
    @Test fun accountSwitchClearsPreviousProfileAndDelivery() = runTest {
        val h = Harness(); h.successfulImport(); h.login("other_session", "b@example.invalid")
        assertNull(h.repo.copyImportedConfig()); assertNull(h.store.value)
        h.overview(case = "none"); assertEquals(ImportStatus.NO_RESOURCE, h.repo.refreshAndImport().status)
        assertEquals(1, h.downloads)
    }
    @Test fun unsupportedAndMalformedConfigurationNeverBecomeImported() = runTest {
        for (raw in listOf(yaml.replace("type: ss", "type: vless").toByteArray(), byteArrayOf(0xc3.toByte()))) {
            val h = Harness(); h.raw = raw; h.login(); h.overview(); h.delivery()
            assertEquals(ImportStatus.UNSUPPORTED_CONFIGURATION, h.repo.refreshAndImport().status)
            assertNull(h.repo.copyImportedConfig()); assertNotNull(h.store.value!!.url)
            assertTrue(raw.all { it == 0.toByte() })
        }
    }
    @Test fun failedOrNoPlanRefreshInvalidatesProfileWithoutDestroyingReceipt() = runTest {
        val h = Harness(); h.successfulImport(); val saved = h.store.value
        h.wire.replies += WireResponse(503, "")
        try { h.repo.refreshAndImport(); fail() } catch (_: PortalFailure) { }
        assertNull(h.repo.copyImportedConfig()); assertSame(saved, h.store.value)
        h.overview(case = "none"); h.repo.refreshAndImport()
        assertNull(h.repo.copyImportedConfig()); assertSame(saved, h.store.value)
    }
    @Test fun revokedSubscriptionClearsProfileAndNeverReclaims() = runTest {
        val h = Harness(); h.successfulImport(); h.overview(available = false)
        h.downloadFailure = PortalFailure.Reason.SUBSCRIPTION_REVOKED
        try { h.repo.refreshAndImport(); fail() }
        catch (e: PortalFailure) { assertEquals(PortalFailure.Reason.SUBSCRIPTION_REVOKED, e.reason) }
        assertNull(h.repo.copyImportedConfig()); assertNotNull(h.store.value!!.url)
        assertEquals(1, h.wire.calls.count { it.first.endsWith("/setup") })
    }
    @Test fun sessionExpiryNeverReturnsImportedConfiguration() = runTest {
        val h = Harness(); h.successfulImport(); h.elapsed = 1800000
        try { h.repo.copyImportedConfig(); fail() }
        catch (e: PortalFailure) { assertEquals(PortalFailure.Reason.SESSION_EXPIRED, e.reason) }
    }
    @Test fun logoutDuringDownloadDiscardsLateBytesAndCannotPublishProfile() = runTest {
        val h = Harness(); h.login(); h.overview(); h.delivery()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.downloadHook = { started.complete(Unit); release.await() }
        val importing = async { h.repo.refreshAndImport() }; started.await()
        val logout = launch { h.repo.clearLocalData() }; runCurrent(); release.complete(Unit)
        try { importing.await(); fail() } catch (_: CancellationException) { }
        logout.join(); assertNull(h.store.value); assertTrue(h.raw.all { it == 0.toByte() })
    }
    @Test fun cancelledIoResultIsWipedEvenWhenCallerNeverReceivesIt() = runTest {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val bytes = yaml.toByteArray()
        val reading = launch {
            receiveSubscriptionBytes {
                started.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                bytes
            }
            fail("Cancelled download must not deliver a result")
        }
        runCurrent()
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        reading.cancel(); release.countDown(); reading.join()
        assertTrue(bytes.all { it == 0.toByte() })
    }
    @Test fun logoutBetweenOverviewAndDeliveryCannotBeAdoptedAsNewImportEpoch() = runTest {
        val h = Harness(); h.login(); h.overview(); h.delivery()
        var logout: kotlinx.coroutines.Job? = null
        try {
            h.repo.refreshAndImport(onResource = {
                logout = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { h.repo.clearLocalData() }
            })
            fail()
        } catch (_: CancellationException) { }
        logout!!.join()
        assertEquals(0, h.downloads); assertNull(h.store.value)
        assertFalse(h.wire.calls.any { it.first.contains("/setup") })
    }
    @Test fun commerce401InvalidatesExistingImportedProfile() = runTest {
        val h = Harness(); h.successfulImport(); h.wire.replies += WireResponse(401, "")
        try { h.repo.commerce("/api/portal/coins/wallet"); fail() }
        catch (e: PortalFailure) { assertEquals(PortalFailure.Reason.SESSION_EXPIRED, e.reason) }
        try { h.repo.copyImportedConfig(); fail() }
        catch (e: PortalFailure) { assertEquals(PortalFailure.Reason.SESSION_EXPIRED, e.reason) }
        assertNotNull(h.store.value!!.url)
    }
    @Test fun concurrentRefreshesSerializeAndDoNotRepeatClaim() = runTest {
        val h = Harness(); h.login(); h.overview(); h.delivery()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.downloadHook = { started.complete(Unit); release.await() }
        val first = async { h.repo.refreshAndImport() }; started.await()
        h.overview(available = false)
        val second = async { h.repo.refreshAndImport() }; runCurrent()
        assertEquals(1, h.downloads)
        h.downloadHook = { h.raw = yaml.toByteArray() }
        release.complete(Unit)
        assertEquals(ImportStatus.IMPORTED, first.await().status)
        assertEquals(ImportStatus.IMPORTED, second.await().status)
        assertEquals(2, h.downloads)
        assertEquals(1, h.wire.calls.count { it.first.endsWith("/setup") })
    }
    private inner class PreparedHarness(val h: Harness, scope: CoroutineScope,
        resolver: ConnectionLineResolver = ConnectionLineResolver { _, requested ->
            ResolvedConnectionLine(requested ?: "synthetic-line-a", 0)
        }) : AutoCloseable {
        init { h.wire.requireQueuedModel = true }
        val directory = Files.createTempDirectory("fresh-config-test").toFile()
        val file = File(directory, "prepared-connection.json")
        val events = mutableListOf<String>()
        var permission: suspend () -> Boolean = { true }
        var starts = 0
        val gateway = FreshConnectionConfigGateway(h.repo, resolver, AtomicPreparedConfigStore(directory))
        val coordinator = ConnectionCoordinator(
            object : AccountGateway { override suspend fun isAuthenticated() = true },
            object : EntitlementGateway { override suspend fun current() = AccessDecision() },
            gateway,
            object : PermissionGateway { override suspend fun hasVpnPermission() = permission() },
            object : AtomicActivationTunnel {
                override suspend fun start(config: AuthorizedConfig): Boolean = error("Unfenced start")
                override fun startAtomically(config: AuthorizedConfig): Boolean {
                    assertTrue(file.isFile)
                    val bytes = config.copyMaterial()
                    try { assertArrayEquals(bytes, file.readBytes()) } finally { bytes.fill(0) }
                    events += "start"; starts++; return true
                }
                override suspend fun stop() { events += "stop" }
            },
            object : TrafficProbe { override suspend fun isUsable() = true }, scope)
        override fun close() { directory.deleteRecursively() }
        fun next(model: String = "wallet_first_unique_v1") {
            h.raw = yaml.toByteArray(); h.overview(available = model == "legacy")
            h.wire.modelReplies += WireResponse(200, """{"commerceModel":"$model"}""")
        }
    }

    @Test fun everyAdmittedConnectReconnectAndSwitchRefreshesDownloadsAndWritesBeforeStart() = runTest {
        val h = Harness(); h.saved(); h.login()
        PreparedHarness(h, backgroundScope).use { p ->
            p.next(); assertEquals(ConnectionState.Connected("synthetic-line-a"), p.coordinator.connect())
            // An identical active connect remains the documented coalesced command, not a restart.
            assertEquals(ConnectionState.Connected("synthetic-line-a"), p.coordinator.connect())
            assertEquals(1, h.downloads)
            p.next(); p.coordinator.reconnect()
            p.next(); h.raw = yaml.replace("not-a-real-password", "new-synthetic-password").toByteArray()
            assertEquals(ConnectionState.Connected("synthetic-line-b"), p.coordinator.switchLine("synthetic-line-b"))
            assertTrue(p.file.readText().contains("new-synthetic-password"))
            p.coordinator.disconnect(); p.next(); p.coordinator.connect()
            assertEquals(4, h.downloads); assertEquals(4, p.starts)
            assertEquals(4, h.wire.calls.count { it.first.endsWith("/overview") })
            assertNull(h.repo.copyImportedConfig())
            p.coordinator.close()
        }
    }
    @Test fun failedFreshDownloadNeverReusesOldFileOrCachedPreview() = runTest {
        val h = Harness(); h.successfulImport()
        PreparedHarness(h, backgroundScope).use { p ->
            p.next(); p.coordinator.connect(); val old = p.file.readBytes()
            p.next(); h.downloadFailure = PortalFailure.Reason.SUBSCRIPTION_UNAVAILABLE
            assertEquals(ConnectionState.Failed(Failure.CONFIG_UNAVAILABLE), p.coordinator.reconnect())
            assertEquals(1, p.starts); assertArrayEquals(old, p.file.readBytes())
            assertNull(h.repo.copyImportedConfig()); p.coordinator.close()
        }
    }
    @Test fun stalePermissionAttemptCannotApplyAfterNewerSwitch() = runTest {
        val h = Harness(); h.saved(); h.login()
        PreparedHarness(h, backgroundScope).use { p ->
            val waiting = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            p.permission = { waiting.complete(Unit); release.await(); true }
            p.next(); val old = async { p.coordinator.connect() }; waiting.await()
            p.permission = { true }; p.next()
            assertEquals(ConnectionState.Connected("synthetic-line-b"), p.coordinator.switchLine("synthetic-line-b"))
            release.complete(Unit); assertEquals(ConnectionState.Disconnected, old.await())
            assertEquals(1, p.starts); assertEquals(2, h.downloads); p.coordinator.close()
        }
    }
    @Test fun logoutAfterPreparationBeforePermissionNeverWritesOrStarts() = runTest {
        val h = Harness(); h.saved(); h.login()
        PreparedHarness(h, backgroundScope).use { p ->
            p.permission = { h.repo.clearLocalData(); true }; p.next()
            assertEquals(ConnectionState.Disconnected, p.coordinator.connect())
            assertEquals(0, p.starts); assertFalse(p.file.exists()); p.coordinator.close()
        }
    }
    @Test fun newLoginOrResourceRefreshInvalidatesPreparedMaterialBeforeActivation() = runTest {
        for (relogin in listOf(false, true)) {
            val h = Harness(); h.saved(); h.login()
            PreparedHarness(h, backgroundScope).use { p ->
                p.permission = {
                    if (relogin) h.login("new-session")
                    else { h.overview(case = "none"); h.repo.refresh() }
                    true
                }
                p.next(); val result = p.coordinator.connect()
                assertTrue(result !is ConnectionState.Connected)
                assertEquals(0, p.starts); assertFalse(p.file.exists()); p.coordinator.close()
            }
        }
    }
    @Test fun invalidOrUnresolvedLineNeverWritesOrStarts() = runTest {
        val h = Harness(); h.saved(); h.login()
        PreparedHarness(h, backgroundScope, ConnectionLineResolver { _, _ -> null }).use { p ->
            p.next(); assertEquals(ConnectionState.Failed(Failure.CONFIG_UNAVAILABLE), p.coordinator.connect())
            assertEquals(0, h.downloads); assertEquals(0, p.starts); assertFalse(p.file.exists())
            p.coordinator.close()
        }
    }
    @Test fun restartedPreparationFetchesNewBytesRatherThanReadingPriorConfigFile() = runTest {
        val first = Harness(); first.successfulImport()
        val restarted = Harness(first.store); restarted.login("new-session")
        PreparedHarness(restarted, backgroundScope).use { p ->
            p.file.writeText("stale configuration must never be consumed")
            p.next(); assertTrue(p.coordinator.connect() is ConnectionState.Connected)
            assertEquals(1, restarted.downloads); assertFalse(p.file.readText().contains("stale"))
            p.coordinator.close()
        }
    }
    @Test fun atomicWriteFailureDoesNotStartEngineOrReplaceOldConfiguration() = runTest {
        val h = Harness(); h.saved(); h.login()
        PreparedHarness(h, backgroundScope).use { p ->
            // A directory at the destination cannot be atomically replaced by the config file.
            assertTrue(p.file.mkdir()); File(p.file, "old").writeText("preserve")
            p.next(); assertEquals(ConnectionState.Failed(Failure.TUNNEL_FAILED), p.coordinator.connect())
            assertEquals(0, p.starts); assertEquals("preserve", File(p.file, "old").readText())
            assertFalse(p.directory.list()!!.any { it.endsWith(".tmp") }); p.coordinator.close()
        }
    }

    @Test fun legacyRereadsSetupAfterAckAndAcrossRestartWithoutAdoptingOldPendingReceipt() = runTest {
        val h = Harness(); h.saved(pending = true, token = "old-session")
        val old = h.store.value; h.expectDurableAck = false; h.login("new-session")
        PreparedHarness(h, backgroundScope).use { p ->
            p.next("legacy"); h.delivery(); assertTrue(p.coordinator.connect() is ConnectionState.Connected)
            p.next("legacy"); h.delivery(); assertTrue(p.coordinator.reconnect() is ConnectionState.Connected)
            assertSame(old, h.store.value)
            val setups = h.wire.calls.filter { it.first.endsWith("/setup") }
            assertEquals(2, setups.size)
            assertNotEquals(JSONObject(setups[0].second!!).getString("idempotencyKey"),
                JSONObject(setups[1].second!!).getString("idempotencyKey"))
            assertEquals(2, h.downloads); p.coordinator.close()
        }
        val restarted = Harness(); restarted.expectDurableAck = false; restarted.login("third-session")
        PreparedHarness(restarted, backgroundScope).use { p ->
            p.next("legacy"); restarted.delivery()
            assertTrue(p.coordinator.connect() is ConnectionState.Connected)
            assertNull(restarted.store.value); p.coordinator.close()
        }
    }
    @Test fun freshWalletPendingNewSessionStillBlocksAndPreservesOriginalKey() = runTest {
        val h = Harness(); h.saved(pending = true, token = "old-session"); val before = h.store.value
        h.login("new-session")
        PreparedHarness(h, backgroundScope).use { p ->
            p.next(); assertEquals(ConnectionState.Failed(Failure.CONFIG_UNAVAILABLE), p.coordinator.connect())
            assertSame(before, h.store.value); assertEquals(0, h.downloads); assertEquals(0, p.starts)
            assertFalse(h.wire.calls.any { it.first.endsWith("/setup") }); p.coordinator.close()
        }
    }
    @Test fun unknownCommerceModelFailsClosedWithoutClaimOrOldFileActivation() = runTest {
        val h = Harness(); h.saved(); h.login()
        PreparedHarness(h, backgroundScope).use { p ->
            p.next("future_unknown"); p.file.writeText("old")
            assertEquals(ConnectionState.Failed(Failure.CONFIG_UNAVAILABLE), p.coordinator.connect())
            assertEquals(0, h.downloads); assertEquals(0, p.starts); assertEquals("old", p.file.readText())
            p.coordinator.close()
        }
    }

    @Test fun freshWalletRetriesPersistedKeyAndPendingAckWithoutAnotherClaim() = runTest {
        val h = Harness(); h.login()
        h.store.value = DeliveryRecord("a@example.invalid", sessionBinding(Secret("session_fixture")), reference, "retained-key")
        PreparedHarness(h, backgroundScope).use { p ->
            p.next(); h.delivery(503)
            assertEquals(ConnectionState.Failed(Failure.CONFIG_UNAVAILABLE), p.coordinator.connect())
            assertEquals(0, h.downloads); assertEquals(0, p.starts)
            p.next(); h.wire.replies += WireResponse(204, "")
            assertTrue(p.coordinator.reconnect() is ConnectionState.Connected)
            val setup = h.wire.calls.single { it.first.endsWith("/setup") }
            assertEquals("retained-key", JSONObject(setup.second!!).getString("idempotencyKey"))
            assertEquals(2, h.wire.calls.count { it.first.endsWith("/ack") }); p.coordinator.close()
        }
    }
    @Test fun lateCancelledDownloadIsWipedAndCannotApplyAheadOfNewAttempt() = runTest {
        val h = Harness(); h.saved(); h.login()
        PreparedHarness(h, backgroundScope).use { p ->
            val waiting = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            p.next(); val stale = h.raw
            h.downloadHook = { waiting.complete(Unit); kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() } }
            val first = async { p.coordinator.connect() }; waiting.await()
            h.downloadHook = {}; h.overview(available = false)
            h.wire.modelReplies += WireResponse(200, """{"commerceModel":"wallet_first_unique_v1"}""")
            val second = async { p.coordinator.switchLine("synthetic-line-b") }; runCurrent()
            release.complete(Unit); assertEquals(ConnectionState.Disconnected, first.await())
            // The source deliberately returned the old (now wiped) array again: fail closed,
            // proving the late first result did not get applied or become a cache fallback.
            assertEquals(ConnectionState.Failed(Failure.CONFIG_UNAVAILABLE), second.await())
            assertTrue(stale.all { it == 0.toByte() }); assertEquals(0, p.starts); assertFalse(p.file.exists())
            p.coordinator.close()
        }
    }
    @Test fun observedProtected401BetweenPreparationAndStartInvalidatesAdmission() = runTest {
        val h = Harness(); h.saved(); h.login()
        PreparedHarness(h, backgroundScope).use { p ->
            p.next(); p.permission = {
                h.wire.replies += WireResponse(401, "")
                try { h.repo.commerce("/api/portal/coins/wallet"); fail() } catch (_: PortalFailure) { }
                true
            }
            assertEquals(ConnectionState.Disconnected, p.coordinator.connect())
            assertEquals(0, p.starts); assertFalse(p.file.exists()); p.coordinator.close()
        }
    }

    @Test fun sessionOrResourceExpiryWhileWaitingPermissionBlocksActivation() = runTest {
        for (sessionExpiry in listOf(true, false)) {
            val h = Harness(); h.saved(); h.login()
            PreparedHarness(h, backgroundScope).use { p ->
                if (sessionExpiry) p.next()
                else {
                    h.overview(available = false, expiresAt = "2026-09-30T00:01:00.000Z")
                    h.wire.modelReplies += WireResponse(200, """{"commerceModel":"wallet_first_unique_v1"}""")
                }
                p.permission = { if (sessionExpiry) h.elapsed = 1800000 else h.now = start.plusSeconds(61); true }
                assertTrue(p.coordinator.connect() !is ConnectionState.Connected)
                assertEquals(0, p.starts); assertFalse(p.file.exists()); p.coordinator.close()
            }
        }
    }
    @Test fun deniedPermissionNeverWritesOrActivatesPreparedMaterial() = runTest {
        val h = Harness(); h.saved(); h.login()
        PreparedHarness(h, backgroundScope).use { p ->
            p.next(); p.permission = { false }
            assertEquals(ConnectionState.Failed(Failure.PERMISSION_DENIED), p.coordinator.connect())
            assertEquals(1, h.downloads); assertEquals(0, p.starts); assertFalse(p.file.exists())
            p.coordinator.close()
        }
    }
    @Test fun resolverSelectsSecondExplicitlyMappedNodeWithoutInventingIndexIdentity() = runTest {
        val h = Harness(); h.saved(); h.login()
        PreparedHarness(h, backgroundScope, ConnectionLineResolver { _, requested ->
            if (requested == "synthetic-authoritative-b") ResolvedConnectionLine(requested, 1) else null
        }).use { p ->
            p.next()
            h.raw = """
                proxies:
                  - {name: one, type: ss, server: 192.0.2.1, port: 443, cipher: aes-128-gcm, password: synthetic-first}
                  - {name: two, type: ss, server: 192.0.2.2, port: 443, cipher: aes-128-gcm, password: synthetic-second}
            """.trimIndent().toByteArray()
            assertEquals(ConnectionState.Connected("synthetic-authoritative-b"), p.coordinator.switchLine("synthetic-authoritative-b"))
            assertTrue(p.file.readText().contains("synthetic-second")); assertFalse(p.file.readText().contains("synthetic-first"))
            p.coordinator.close()
        }
    }

    @Test fun queuedOlderLoginCannotAdoptNewerFailedLoginGeneration() = runTest {
        val h = Harness(); h.saved(); h.login(); h.overview(available = false)
        val waiting = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.downloadHook = { waiting.complete(Unit); release.await() }
        val importing = async { runCatching { h.repo.refreshAndImport() } }; waiting.await()
        val older = async { runCatching { h.repo.login("older@example.invalid", "synthetic", HumanChallenge("fixture", "1+2"), "3") } }
        runCurrent()
        val newer = async { runCatching { h.repo.login("newer@example.invalid", "synthetic", HumanChallenge("fixture", "1+2"), "3") } }
        runCurrent(); h.wire.replies += WireResponse(401, ""); release.complete(Unit)
        assertTrue(importing.await().exceptionOrNull() is CancellationException)
        assertTrue(older.await().exceptionOrNull() is CancellationException)
        assertEquals(PortalFailure.Reason.LOGIN_FAILED, (newer.await().exceptionOrNull() as PortalFailure).reason)
        assertEquals(2, h.wire.calls.count { it.first.endsWith("/password/session") })
        try { h.repo.copyImportedConfig(); fail() } catch (e: PortalFailure) {
            assertEquals(PortalFailure.Reason.SESSION_EXPIRED, e.reason)
        }
    }
    @Test fun queuedPreparationCannotAdoptLogoutGenerationWhileOldSessionStillExists() = runTest {
        val h = Harness(); h.saved(); h.login(); h.overview(available = false)
        val waiting = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.downloadHook = { waiting.complete(Unit); release.await() }
        val importing = async { runCatching { h.repo.refreshAndImport() } }; waiting.await()
        PreparedHarness(h, backgroundScope).use { p ->
            val preparing = async { p.coordinator.connect() }; runCurrent()
            val logout = launch { h.repo.clearLocalData() }; runCurrent(); release.complete(Unit)
            assertTrue(importing.await().exceptionOrNull() is CancellationException)
            assertEquals(ConnectionState.Failed(Failure.CONFIG_UNAVAILABLE), preparing.await())
            logout.join(); assertEquals(0, p.starts); assertFalse(p.file.exists())
            assertEquals(1, h.wire.calls.count { it.first.endsWith("/overview") }); p.coordinator.close()
        }
    }
    @Test fun queuedTotpCannotAdoptANewerFailedLoginGeneration() = runTest {
        val h = Harness(); val waiting = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.wire.requestHook = { path -> if (path.endsWith("/password/session")) { waiting.complete(Unit); release.await() } }
        h.wire.replies += WireResponse(202, """{"challenge":{"status":"second_factor_required","availableFactors":["totp"],"challengeRef":"older-challenge","expiresAt":"2026-09-30T00:05:00Z"}}""")
        val first = async { runCatching { h.repo.login("first@example.invalid", "synthetic", HumanChallenge("fixture", "1+2"), "3") } }
        waiting.await()
        val totp = async { runCatching { h.repo.totp("older-challenge", "123456") } }; runCurrent()
        val newer = async { runCatching { h.repo.login("newer@example.invalid", "synthetic", HumanChallenge("fixture", "1+2"), "3") } }; runCurrent()
        h.wire.replies += WireResponse(401, ""); h.wire.requestHook = {}; release.complete(Unit)
        assertTrue(first.await().exceptionOrNull() is CancellationException)
        assertTrue(totp.await().exceptionOrNull() is CancellationException)
        assertEquals(PortalFailure.Reason.LOGIN_FAILED, (newer.await().exceptionOrNull() as PortalFailure).reason)
        assertFalse(h.wire.calls.any { it.first.endsWith("/second-factor") })
        try { h.repo.copyImportedConfig(); fail() } catch (e: PortalFailure) {
            assertEquals(PortalFailure.Reason.SESSION_EXPIRED, e.reason)
        }
    }

    @Test fun cancelledLoginIgnoresLateSuccessfulTransportResponse() = runTest {
        val h = Harness(); val waiting = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.wire.requestHook = { waiting.complete(Unit); kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() } }
        val login = launch { h.login() }; waiting.await(); login.cancel(); release.complete(Unit); login.join()
        try { h.repo.copyImportedConfig(); fail() } catch (e: PortalFailure) {
            assertEquals(PortalFailure.Reason.SESSION_EXPIRED, e.reason)
        }
        assertNull(h.store.value)
    }

    @Test fun oldProtected401CannotCancelTheNewerLoginAlreadyQueued() = runTest {
        val h = Harness(); h.login()
        val waiting = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.wire.requestHook = { path -> if (path.endsWith("/overview")) { waiting.complete(Unit); release.await() } }
        h.wire.replies += WireResponse(401, "")
        val old = async { runCatching { h.repo.refresh() } }; waiting.await()
        val newer = async { h.login("new-session") }; runCurrent(); release.complete(Unit)
        assertTrue(old.await().exceptionOrNull() is CancellationException)
        newer.await(); assertNotNull(h.repo.commerceSessionBinding())
        assertEquals(2, h.wire.calls.count { it.first.endsWith("/password/session") })
    }

    @Test fun queuedPreparationNullSessionCannotCancelNewerQueuedLogin() = runTest {
        val h = Harness(); h.saved(); h.login(); h.overview(available = false)
        val waiting = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.downloadHook = { waiting.complete(Unit); release.await() }
        val old = async { runCatching { h.repo.refreshAndImport() } }; waiting.await()
        PreparedHarness(h, backgroundScope).use { p ->
            val preparation = async { p.coordinator.connect() }; runCurrent()
            val login = async { h.login("new-session") }; runCurrent(); release.complete(Unit)
            assertTrue(old.await().exceptionOrNull() is CancellationException)
            assertEquals(ConnectionState.Failed(Failure.CONFIG_UNAVAILABLE), preparation.await())
            login.await(); assertNotNull(h.repo.commerceSessionBinding())
            assertEquals(0, p.starts); assertFalse(p.file.exists()); p.coordinator.close()
        }
    }

}
