package com.gccwebos.android.portal

import java.time.Instant
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
        val calls = mutableListOf<Pair<String, String?>>()
        override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
            calls += path to body
            return replies.removeFirst()
        }
    }
    private inner class Harness(val store: Store = Store()) {
        val wire = Wire()
        var now = start
        var elapsed = 0L
        var downloads = 0
        var raw = yaml.toByteArray()
        var downloadFailure: PortalFailure.Reason? = null
        var downloadHook: suspend () -> Unit = {}
        val repo = PortalRepository(wire, store, policy, { elapsed }, { now }, SubscriptionSource { url ->
            assertEquals("https://sub.invalid/sub/a/fixture", url.value)
            assertFalse(store.value!!.pendingAck)
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
                assertEquals(setOf("proxies", "rules"), value.keys().asSequence().toSet())
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
        val logout = launch { h.repo.logout() }; runCurrent(); release.complete(Unit)
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
                logout = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { h.repo.logout() }
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
}
