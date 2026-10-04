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

/** Synthetic, in-memory boundary tests. No real accounts, server calls, claims, payments or VPN. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LegacySubscriptionRecoveryTest {
    private val start = Instant.parse("2026-09-30T00:00:00Z")
    private val account = "legacy-fixture@example.invalid"
    private val resource = "resource_" + "a".repeat(64)
    private val otherResource = "resource_" + "b".repeat(64)
    private val policy = OriginPolicy("https://portal.invalid", setOf("https://sub.invalid"))
    private val nextUrl = "https://sub.invalid/sub/a/recovered_fixture"
    private val yaml = """
        proxies:
          - name: Synthetic recovery node
            type: ss
            server: 192.0.2.31
            port: 443
            cipher: aes-128-gcm
            password: synthetic-recovery-only
    """.trimIndent()

    private class Store(val events: MutableList<String>) : DeliveryStore {
        var value: DeliveryRecord? = null
        var journal: LegacyRecoveryJournal? = null
        var failMainSave = false
        var failMainReadback = false
        var failJournalPhase: LegacyRecoveryPhase? = null
        private var mainSaved = false
        override fun read(): DeliveryRecord? {
            if (mainSaved && failMainReadback) throw PortalFailure(PortalFailure.Reason.STORAGE)
            return value
        }
        override fun save(record: DeliveryRecord) {
            events += "main-save"
            if (failMainSave) throw PortalFailure(PortalFailure.Reason.STORAGE)
            value = record
            mainSaved = true
        }
        override fun clear() {
            value = null; journal = null; mainSaved = false
            events += "clear"
        }
        override fun readLegacyRecovery() = journal
        override fun saveLegacyRecovery(value: LegacyRecoveryJournal) {
            events += "journal-${value.phase.name}"
            if (value.phase == failJournalPhase) throw PortalFailure(PortalFailure.Reason.STORAGE)
            journal = value
        }
        override fun clearLegacyRecovery() { events += "journal-clear"; journal = null }
    }

    private class Profiles(val events: MutableList<String>, val beforeSave: () -> Unit) : LocalProfileStore {
        val memory = MemoryLocalProfileStore()
        var failSave = false
        var failReadback = false
        private var saved = false
        override fun read(): CachedSubscription? {
            if (saved && failReadback) throw PortalFailure(PortalFailure.Reason.STORAGE)
            return memory.read()
        }
        override fun save(value: CachedSubscription) {
            events += "profile-save"; beforeSave()
            if (failSave) throw PortalFailure(PortalFailure.Reason.STORAGE)
            memory.save(value); saved = true
        }
        override fun clear() { memory.clear(); saved = false }
    }

    private data class Call(val path: String, val body: String?, val session: Secret?)
    private inner class Wire(val events: MutableList<String>, val store: Store) : PortalTransport {
        val calls = mutableListOf<Call>()
        val loginReplies = ArrayDeque<WireResponse>()
        var model = WireResponse(200, """{"commerceModel":"legacy"}""")
        var overviewRef = resource
        var setupAvailable = true
        var setupStatus = 200
        var ackStatus = 204
        var handoff = "new_fixture_handoff"
        var url = nextUrl
        var hook: suspend (String) -> Unit = {}
        override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
            calls += Call(path, body, session)
            events += when {
                path.endsWith("/model") -> "model-read"
                path.endsWith("/ack") -> "ack"
                path.endsWith("/setup") -> "reveal"
                path.endsWith("/overview") -> "overview"
                else -> "login"
            }
            if (path.endsWith("/ack") && model.body.contains("\"legacy\"")) {
                val stage = requireNotNull(store.journal)
                assertEquals(LegacyRecoveryPhase.REVEALED, stage.phase)
                assertTrue(stage.candidate.pendingAck)
                assertEquals(stage.candidate.handoff!!.value, JSONObject(body!!).getString("handoffRef"))
                assertEquals(setOf("handoffRef"), JSONObject(body).keys().asSequence().toSet())
                assertEquals(stage.candidate.sessionBinding, sessionBinding(requireNotNull(session)))
            }
            hook(path)
            return when (path) {
                "/api/portal/password/session" -> loginReplies.removeFirst()
                "/api/portal/resources/current/overview" -> {
                    val fixtures = JSONObject(requireNotNull(javaClass.getResource("/portal-cases.json")).readText())
                    val response = fixtures.getJSONObject("resources").getJSONObject("pending")
                    response.getJSONObject("resource").put("resourceRef", overviewRef).put("setupAvailable", setupAvailable)
                    WireResponse(200, response.toString())
                }
                "/api/portal/commerce/model" -> {
                    assertNull(body); assertNotNull(session); model
                }
                "/api/portal/resources/current/setup" -> {
                    assertEquals(setOf("idempotencyKey"), JSONObject(body!!).keys().asSequence().toSet())
                    val stage = requireNotNull(store.journal)
                    assertEquals(LegacyRecoveryPhase.ATTEMPT, stage.phase)
                    assertEquals(stage.candidate.operationKey, JSONObject(body).getString("idempotencyKey"))
                    WireResponse(setupStatus, """{"delivery":{"handoffRef":"$handoff","kind":"subscription_url","subscriptionUrl":"$url"}}""")
                }
                "/api/portal/resources/current/setup/ack" -> WireResponse(ackStatus, "")
                else -> error("Unexpected synthetic path: $path")
            }
        }
    }

    private inner class Harness(val store: Store = Store(mutableListOf()), private val expectedUrl: String = nextUrl,
        allowHostnamePreparation: Boolean = false) {
        val events = store.events
        val wire = Wire(events, store).apply { url = expectedUrl }
        var expectedOriginal: DeliveryRecord? = store.value
        var checkLegacyOrdering = true
        val profiles = Profiles(events) {
            if (checkLegacyOrdering) {
                assertSame(expectedOriginal, store.value)
                assertEquals(LegacyRecoveryPhase.PROFILE_COMMITTING, store.journal!!.phase)
                assertFalse(store.journal!!.candidate.pendingAck)
            }
        }
        var downloads = 0
        var downloadFailure: PortalFailure.Reason? = null
        var downloadHook: suspend () -> Unit = {}
        var content = yaml
        var lastBytes: ByteArray? = null
        val repo = PortalRepository(wire, store, policy, { 0L }, { start }, SubscriptionSource { requested ->
            events += "download"; downloads++
            if (checkLegacyOrdering) {
                assertSame(expectedOriginal, store.value)
                assertTrue(store.journal!!.phase in setOf(LegacyRecoveryPhase.ACKNOWLEDGED, LegacyRecoveryPhase.PROFILE_COMMITTING))
                assertFalse(store.journal!!.candidate.pendingAck)
            }
            assertTrue("Synthetic download capability changed", expectedUrl == requested.value)
            downloadHook()
            downloadFailure?.let { throw PortalFailure(it) }
            content.toByteArray().also { lastBytes = it }
        }, profiles = profiles, allowHostnamePreparation = allowHostnamePreparation)
        suspend fun login(token: String = "new_fixture_session", owner: String = account) {
            wire.loginReplies += WireResponse(200, """{"session":{"sessionRef":"$token","accountId":"$owner","issuedAt":"2026-09-30T00:00:00.000Z","expiresAt":"2026-09-30T00:30:00.000Z"}}""")
            repo.login(owner, "synthetic-password", HumanChallenge("challenge_fixture", "1 + 2"), "3")
            expectedOriginal = store.value
        }
        fun loginController(controller: AccountController) {
            wire.loginReplies += WireResponse(200, """{"session":{"sessionRef":"new_fixture_session","accountId":"$account","issuedAt":"2026-09-30T00:00:00.000Z","expiresAt":"2026-09-30T00:30:00.000Z"}}""")
            controller.account = account; controller.password = "synthetic-password"
            controller.challenge = HumanChallenge("challenge_fixture", "1 + 2"); controller.answer = "3"
            controller.login()
        }
        fun old(pending: Boolean = true, ref: String = resource, token: String = "old_fixture_session"): DeliveryRecord {
            val result = DeliveryRecord(account, sessionBinding(Secret(token)), ref, "old_fixture_operation",
                Secret("https://sub.invalid/sub/a/old_fixture"), Secret("old_fixture_handoff"), pending)
            store.value = result; expectedOriginal = result
            return result
        }
        fun setupCalls() = wire.calls.filter { it.path.endsWith("/setup") }
        fun ackCalls() = wire.calls.filter { it.path.endsWith("/ack") }
        fun assertImported() {
            assertEquals(LocalSubscriptionStatus.READY, repo.localSubscriptions.snapshot().status)
            assertFalse(store.value!!.pendingAck)
            assertTrue("Synthetic saved capability changed", expectedUrl == store.value!!.url!!.value)
            assertNull(store.journal)
            assertTrue(lastBytes!!.all { it == 0.toByte() })
        }
    }

    private suspend fun failure(reason: PortalFailure.Reason, block: suspend () -> Unit) {
        try { block(); fail("Expected $reason") }
        catch (actual: PortalFailure) { assertEquals(reason, actual.reason) }
    }
    private suspend fun cancelled(block: suspend () -> Unit) {
        try { block(); fail("Expected operation cancellation") }
        catch (_: CancellationException) { }
    }
    private fun assertBefore(events: List<String>, first: String, second: String) {
        assertTrue("Missing $first", events.indexOf(first) >= 0)
        assertTrue("$first must precede $second", events.indexOf(first) < events.indexOf(second))
    }

    @Test fun hostnameImportPreservesOriginalProfileAndNeverCreatesCachedEngineAuthority() = runTest {
        val h = Harness(allowHostnamePreparation = true)
        h.content = yaml.replace("192.0.2.31", "synthetic-endpoint.invalid")
        h.login()
        val result = h.repo.refreshAndImport()
        assertEquals(ImportStatus.IMPORTED, result.status)
        assertTrue(result.nodes.single().supported)
        h.assertImported()
        assertNull(h.repo.copyImportedConfig())
        val cached = requireNotNull(h.profiles.read())
        try {
            val bytes = cached.copyBytes()
            try { assertTrue("Original synthetic artifact must be preserved", bytes.contentEquals(h.content.toByteArray())) }
            finally { bytes.fill(0) }
        } finally { cached.close() }
    }

    @Test fun disablingHostnamePreparationRetainsStrictEndpointRejection() = runTest {
        val h = Harness()
        h.content = yaml.replace("192.0.2.31", "synthetic-endpoint.invalid")
        h.login()
        val result = h.repo.refreshAndImport()
        assertEquals(ImportStatus.UNSUPPORTED_CONFIGURATION, result.status)
        assertEquals(com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.EndpointHostname, result.rejection)
        assertFalse(h.events.contains("profile-save"))
        assertNull(h.repo.copyImportedConfig())
    }

    @Test fun hostnameImportStillRejectsOtherUnsupportedNodeOptions() = runTest {
        val h = Harness(allowHostnamePreparation = true)
        h.content = yaml.replace("192.0.2.31", "synthetic-endpoint.invalid") + "\n    unknown-option: true"
        h.login()
        val result = h.repo.refreshAndImport()
        assertEquals(ImportStatus.UNSUPPORTED_CONFIGURATION, result.status)
        assertEquals(com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.NodeOptions, result.rejection)
        assertFalse(h.events.contains("profile-save"))
        assertNull(h.repo.copyImportedConfig())
    }

    @Test fun backendYamlCapabilityCompletesImportWithoutResidualRecoveryJournal() = runTest {
        val h = Harness(expectedUrl = nextUrl + ".yaml"); h.login()
        val result = h.repo.refreshAndImport()
        assertEquals(ImportStatus.IMPORTED, result.status)
        assertNull(result.rejection)
        assertEquals(1, result.nodes.size)
        assertEquals(1, h.downloads)
        assertEquals(1, h.setupCalls().size)
        assertEquals(1, h.ackCalls().size)
        h.assertImported()
        assertBefore(h.events, "journal-ATTEMPT", "reveal")
        assertBefore(h.events, "journal-REVEALED", "ack")
        assertBefore(h.events, "journal-ACKNOWLEDGED", "download")
        assertBefore(h.events, "profile-save", "journal-clear")
    }

    @Test fun acknowledgedBackendYamlCapabilityRestoresCachedNodesWithoutPortalOrDownload() = runTest {
        val h = Harness(expectedUrl = nextUrl + ".yaml"); h.login()
        assertEquals(ImportStatus.IMPORTED, h.repo.refreshAndImport().status)
        h.assertImported()
        val callsBefore = h.wire.calls.size
        val manager = LocalSubscriptionManager(h.store, h.profiles,
            SubscriptionSource { error("Cached restore must not download") },
            policy, { start }, LocalSubscriptionAuthority())
        val restored = manager.restore()
        assertEquals(LocalSubscriptionStatus.READY, restored.status)
        assertEquals(1, restored.nodes.size)
        assertEquals(1, manager.nodeSelectionSnapshot().choices.size)
        assertEquals(callsBefore, h.wire.calls.size)
        assertEquals(1, h.downloads)
        assertNull(h.store.journal)
    }

    @Test fun newLegacyImportCommitsOnlyAfterDurableStageCurrentAckAndProfile() = runTest {
        val h = Harness(); h.login()
        assertEquals(ImportStatus.IMPORTED, h.repo.refreshAndImport().status); h.assertImported()
        assertBefore(h.events, "model-read", "journal-ATTEMPT")
        assertBefore(h.events, "journal-ATTEMPT", "reveal")
        assertBefore(h.events, "journal-REVEALED", "ack")
        assertBefore(h.events, "journal-ACKNOWLEDGED", "download")
        assertBefore(h.events, "journal-PROFILE_COMMITTING", "profile-save")
        assertBefore(h.events, "profile-save", "main-save")
        assertBefore(h.events, "main-save", "journal-clear")
        assertEquals(1, h.setupCalls().size); assertEquals(1, h.ackCalls().size)
    }

    @Test fun legacyNewSessionRevealsCurrentMaterialWithoutRebindingOldReceipt() = runTest {
        val h = Harness(); val old = h.old(); h.login()
        assertEquals(ImportStatus.IMPORTED, h.repo.refreshAndImport().status); h.assertImported()
        assertEquals("old_fixture_handoff", old.handoff!!.value)
        assertTrue(old.pendingAck)
        assertNotEquals(old.sessionBinding, h.store.value!!.sessionBinding)
        assertEquals("new_fixture_handoff", JSONObject(h.ackCalls().single().body!!).getString("handoffRef"))
        assertEquals("new_fixture_session", h.ackCalls().single().session!!.value)
    }

    @Test fun legacyChangedResourceUsesCurrentRevealAndRetainsOldUntilProfileCommit() = runTest {
        val h = Harness(); val old = h.old(pending = false, ref = otherResource); h.login()
        assertEquals(ImportStatus.IMPORTED, h.repo.refreshAndImport().status); h.assertImported()
        assertEquals(otherResource, old.resource); assertEquals(resource, h.store.value!!.resource)
        assertNotEquals(old.operationKey, h.store.value!!.operationKey)
    }

    @Test fun failedRevealKeepsOriginalAndReusesDurableAttemptOnExplicitRetry() = runTest {
        val h = Harness(); val old = h.old(); h.login(); h.wire.setupStatus = 503
        failure(PortalFailure.Reason.UNAVAILABLE) { h.repo.refreshAndImport() }
        assertSame(old, h.store.value); assertEquals(0, h.ackCalls().size); assertEquals(0, h.downloads)
        val key = h.store.journal!!.candidate.operationKey
        h.wire.setupStatus = 200
        assertEquals(ImportStatus.IMPORTED, h.repo.refreshAndImport().status); h.assertImported()
        assertEquals(listOf(key, key), h.setupCalls().map { JSONObject(it.body!!).getString("idempotencyKey") })
    }

    @Test fun failedCurrentAckKeepsOriginalAndRetriesSameStageWithoutAnotherReveal() = runTest {
        val h = Harness(); val old = h.old(); h.login(); h.wire.ackStatus = 503
        failure(PortalFailure.Reason.UNAVAILABLE) { h.repo.refreshAndImport() }
        assertSame(old, h.store.value); assertEquals(0, h.downloads)
        assertEquals(LegacyRecoveryPhase.REVEALED, h.store.journal!!.phase)
        h.wire.ackStatus = 204
        assertEquals(ImportStatus.IMPORTED, h.repo.refreshAndImport().status); h.assertImported()
        assertEquals(1, h.setupCalls().size); assertEquals(2, h.ackCalls().size)
        assertEquals(1, h.ackCalls().map { JSONObject(it.body!!).getString("handoffRef") }.distinct().size)
    }

    @Test fun failedDownloadRetainsAcknowledgedStageAndDoesNotRevealOrAckAgain() = runTest {
        val h = Harness(); val old = h.old(); h.login(); h.downloadFailure = PortalFailure.Reason.NETWORK
        failure(PortalFailure.Reason.NETWORK) { h.repo.refreshAndImport() }
        assertSame(old, h.store.value); assertEquals(LegacyRecoveryPhase.ACKNOWLEDGED, h.store.journal!!.phase)
        h.downloadFailure = null
        assertEquals(ImportStatus.IMPORTED, h.repo.refreshAndImport().status); h.assertImported()
        assertEquals(1, h.setupCalls().size); assertEquals(1, h.ackCalls().size); assertEquals(2, h.downloads)
    }

    @Test fun unsafeDownloadedConfigurationNeverReplacesOriginalOrPublishesProfile() = runTest {
        val h = Harness(); val old = h.old(); h.login(); h.content = yaml.replace("type: ss", "type: vless")
        assertEquals(ImportStatus.UNSUPPORTED_CONFIGURATION, h.repo.refreshAndImport().status)
        assertSame(old, h.store.value); assertNull(h.profiles.memory.read()); assertNotNull(h.store.journal)
        assertFalse(h.events.contains("main-save")); assertTrue(h.lastBytes!!.all { it == 0.toByte() })
        assertNull(h.repo.copyImportedConfig())
    }

    @Test fun rejectedRootOptionsPublishOnlyFixedReasonWithoutProfileOrImportedAuthority() = runTest {
        val h = Harness(); val old = h.old(); h.login(); h.content = "unknown-root: true\n" + yaml
        val result = h.repo.refreshAndImport()
        assertEquals(ImportStatus.UNSUPPORTED_CONFIGURATION, result.status)
        assertEquals(com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.TopLevelOptions, result.rejection)
        assertTrue(result.nodes.isEmpty())
        assertSame(old, h.store.value); assertNull(h.profiles.memory.read())
        assertNull(h.repo.copyImportedConfig()); assertTrue(h.lastBytes!!.all { it == 0.toByte() })
    }

    @Test fun rejectedMixedNodePreservesSummariesButPublishesNoProfileOrImportedAuthority() = runTest {
        val h = Harness(); val old = h.old(); h.login()
        h.content = yaml + "\n" + """
              - name: Synthetic incompatible node
                type: hysteria2
                server: 192.0.2.32
                port: 443
                password: synthetic-only
                sni: node.invalid
                udp: true
        """.trimIndent().prependIndent("  ")
        val result = h.repo.refreshAndImport()
        assertEquals(ImportStatus.UNSUPPORTED_CONFIGURATION, result.status)
        assertEquals(com.gccwebos.android.vpn.SafeProxyConfig.RejectionReason.NodeOptionUdp, result.rejection)
        assertEquals(2, result.nodes.size)
        assertTrue(result.nodes[0].supported); assertFalse(result.nodes[1].supported)
        assertSame(old, h.store.value); assertNull(h.profiles.memory.read())
        assertNull(h.repo.copyImportedConfig()); assertTrue(h.lastBytes!!.all { it == 0.toByte() })
    }

    @Test fun profileSaveFailureKeepsOriginalAndCommittingJournalForExplicitRecovery() = runTest {
        val h = Harness(); val old = h.old(); h.login(); h.profiles.failSave = true
        failure(PortalFailure.Reason.STORAGE) { h.repo.refreshAndImport() }
        assertSame(old, h.store.value); assertSame(old, h.store.journal!!.original)
        assertEquals(LegacyRecoveryPhase.PROFILE_COMMITTING, h.store.journal!!.phase)
        assertNull(h.profiles.memory.read()); assertNull(h.repo.copyImportedConfig())
    }

    @Test fun profileReadbackFailureDoesNotCommitMainOrExposePartiallySavedProfile() = runTest {
        val h = Harness(); val old = h.old(); h.login(); h.profiles.failReadback = true
        failure(PortalFailure.Reason.STORAGE) { h.repo.refreshAndImport() }
        assertSame(old, h.store.value); assertSame(old, h.store.journal!!.original)
        assertFalse(h.events.contains("main-save")); assertNull(h.repo.copyImportedConfig())
        assertNotEquals(LocalSubscriptionStatus.READY, h.repo.localSubscriptions.snapshot().status)
    }

    @Test fun mainSaveFailureRetainsOldReceiptAndJournalEvenWhenProfileWasSaved() = runTest {
        val h = Harness(); val old = h.old(); h.login(); h.store.failMainSave = true
        failure(PortalFailure.Reason.STORAGE) { h.repo.refreshAndImport() }
        assertSame(old, h.store.value); assertSame(old, h.store.journal!!.original)
        h.profiles.memory.read()!!.close()
        assertNull(h.repo.copyImportedConfig())
        assertNotEquals(LocalSubscriptionStatus.READY, h.repo.localSubscriptions.snapshot().status)
    }

    @Test fun mainReadbackFailureRetainsOriginalBackupAndDoesNotDeclareCommitSuccessful() = runTest {
        val h = Harness(); val old = h.old(); h.login(); h.store.failMainReadback = true
        failure(PortalFailure.Reason.STORAGE) { h.repo.refreshAndImport() }
        assertSame(old, h.store.journal!!.original)
        assertEquals(LegacyRecoveryPhase.PROFILE_COMMITTING, h.store.journal!!.phase)
        assertFalse(h.events.contains("journal-clear")); assertNull(h.repo.copyImportedConfig())
        assertNotEquals(LocalSubscriptionStatus.READY, h.repo.localSubscriptions.snapshot().status)
    }

    @Test fun receiptJournalFailureNeverAcknowledgesUndurableRevealedMaterial() = runTest {
        val h = Harness(); val old = h.old(); h.login(); h.store.failJournalPhase = LegacyRecoveryPhase.REVEALED
        failure(PortalFailure.Reason.STORAGE) { h.repo.refreshAndImport() }
        assertSame(old, h.store.value); assertEquals(0, h.ackCalls().size); assertEquals(0, h.downloads)
        assertEquals(LegacyRecoveryPhase.ATTEMPT, h.store.journal!!.phase)
    }

    @Test fun ackJournalFailureNeverDownloadsBeforeAcknowledgementIsDurable() = runTest {
        val h = Harness(); val old = h.old(); h.login(); h.store.failJournalPhase = LegacyRecoveryPhase.ACKNOWLEDGED
        failure(PortalFailure.Reason.STORAGE) { h.repo.refreshAndImport() }
        assertSame(old, h.store.value); assertEquals(1, h.ackCalls().size); assertEquals(0, h.downloads)
        assertEquals(LegacyRecoveryPhase.REVEALED, h.store.journal!!.phase)
    }

    private fun committingJournal(old: DeliveryRecord): LegacyRecoveryJournal {
        val candidate = DeliveryRecord(account, sessionBinding(Secret("new_fixture_session")), resource,
            "stage_fixture_operation", Secret(nextUrl), Secret("new_fixture_handoff"), false)
        return LegacyRecoveryJournal(old, candidate, LegacyRecoveryPhase.PROFILE_COMMITTING)
    }

    @Test fun processRestartCommittingJournalCannotRestoreSavedButUncommittedProfileAsReady() = runTest {
        val h = Harness(); val old = h.old(pending = false)
        h.store.journal = committingJournal(old)
        val cached = CachedSubscription(sessionBinding(Secret(nextUrl)), yaml.toByteArray(), start.plusSeconds(3600), Secret(nextUrl))
        try { h.profiles.memory.save(cached) } finally { cached.close() }
        val manager = LocalSubscriptionManager(h.store, h.profiles, SubscriptionSource { error("No offline download") },
            policy, { start }, LocalSubscriptionAuthority())
        assertNotEquals(LocalSubscriptionStatus.READY, manager.restore().status)
        assertSame(old, h.store.value); assertNotNull(h.store.journal)
        assertTrue(manager.nodeSelectionSnapshot().choices.isEmpty())
    }

    @Test fun explicitSameContextRetryCompletesCommittingJournalWithoutNewRevealOrAck() = runTest {
        val h = Harness(); val old = h.old(); h.store.journal = committingJournal(old); h.login()
        assertEquals(ImportStatus.IMPORTED, h.repo.refreshAndImport().status); h.assertImported()
        assertEquals(0, h.setupCalls().size); assertEquals(0, h.ackCalls().size); assertEquals(1, h.downloads)
    }

    @Test fun processRestartMainAlreadyCandidateStillBlocksUntilJournalIsClearedByVerifiedRetry() = runTest {
        val h = Harness(); val old = h.old(pending = false)
        val journal = committingJournal(old)
        h.store.value = journal.candidate; h.store.journal = journal
        val cached = CachedSubscription(sessionBinding(Secret(nextUrl)), yaml.toByteArray(), start.plusSeconds(3600), Secret(nextUrl))
        try { h.profiles.memory.save(cached) } finally { cached.close() }
        val manager = LocalSubscriptionManager(h.store, h.profiles, SubscriptionSource { error("No offline download") },
            policy, { start }, LocalSubscriptionAuthority())
        assertNotEquals(LocalSubscriptionStatus.READY, manager.restore().status)
        assertSame(journal.candidate, h.store.value); assertSame(old, h.store.journal!!.original)
        h.login()
        assertEquals(ImportStatus.IMPORTED, h.repo.refreshAndImport().status); h.assertImported()
        assertEquals(0, h.setupCalls().size); assertEquals(0, h.ackCalls().size)
        assertFalse(h.events.contains("main-save"))
    }

    @Test fun mainChangedDuringDownloadIsNotOverwrittenByOlderRecoveryTransaction() = runTest {
        val h = Harness(); val old = h.old(); h.login()
        val newer = DeliveryRecord(account, sessionBinding(Secret("new_fixture_session")), otherResource,
            "newer_writer_operation", Secret("https://sub.invalid/sub/a/newer_writer"), Secret("newer_handoff"), false)
        h.downloadHook = { h.store.value = newer }
        failure(PortalFailure.Reason.RECOVERY_REQUIRED) { h.repo.refreshAndImport() }
        assertSame(newer, h.store.value); assertSame(old, h.store.journal!!.original)
        assertFalse(h.events.contains("profile-save")); assertFalse(h.events.contains("main-save"))
        assertNull(h.repo.copyImportedConfig()); assertTrue(h.lastBytes!!.all { it == 0.toByte() })
    }

    @Test fun localUnlinkDuringDownloadCannotResurrectClearedProfileReceiptOrJournal() = runTest {
        val h = Harness(); h.old(); h.login()
        h.downloadHook = { h.repo.localSubscriptions.unlink() }
        cancelled { h.repo.refreshAndImport() }
        assertNull(h.store.value); assertNull(h.store.journal); assertNull(h.profiles.memory.read())
        assertFalse(h.events.contains("profile-save")); assertFalse(h.events.contains("main-save"))
        assertNull(h.repo.copyImportedConfig()); assertTrue(h.lastBytes!!.all { it == 0.toByte() })
    }

    @Test fun unlinkWhileOverviewWaitsCancelsLateResponseBeforeModelOrRecoveryWrites() = runTest {
        val h = Harness(); h.old(); h.login()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.wire.hook = { if (it.endsWith("/overview")) { started.complete(Unit); release.await() } }
        val importing = async { h.repo.refreshAndImport() }; started.await()
        h.repo.localSubscriptions.unlink(); release.complete(Unit)
        cancelled { importing.await() }
        assertNull(h.store.value); assertNull(h.store.journal); assertNull(h.profiles.memory.read())
        assertEquals(0, h.setupCalls().size); assertEquals(0, h.ackCalls().size); assertEquals(0, h.downloads)
        assertFalse(h.events.contains("model-read")); assertFalse(h.events.any { it.startsWith("journal-") })
        assertFalse(h.events.contains("profile-save")); assertFalse(h.events.contains("main-save"))
        assertNull(h.repo.copyImportedConfig())
    }

    @Test fun unlinkWhileModelWaitsCancelsLateResponseBeforeAnyRecoverySideEffect() = runTest {
        val h = Harness(); h.old(); h.login()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.wire.hook = { if (it.endsWith("/model")) { started.complete(Unit); release.await() } }
        val importing = async { h.repo.refreshAndImport() }; started.await()
        h.repo.localSubscriptions.unlink(); release.complete(Unit)
        cancelled { importing.await() }
        assertNull(h.store.value); assertNull(h.store.journal); assertNull(h.profiles.memory.read())
        assertEquals(0, h.setupCalls().size); assertEquals(0, h.ackCalls().size); assertEquals(0, h.downloads)
        assertEquals(1, h.wire.calls.count { it.path.endsWith("/model") })
        assertFalse(h.events.any { it.startsWith("journal-") })
        assertFalse(h.events.contains("profile-save")); assertFalse(h.events.contains("main-save"))
        assertNull(h.repo.copyImportedConfig())
    }

    @Test fun newSessionDoesNotAckStageFromPreviousSessionAndRevealsCurrentHandoff() = runTest {
        val h = Harness(); val old = h.old(); h.login(); h.wire.ackStatus = 503
        failure(PortalFailure.Reason.UNAVAILABLE) { h.repo.refreshAndImport() }
        val previous = h.store.journal!!.candidate
        h.login("later_fixture_session"); h.wire.ackStatus = 204; h.wire.handoff = "later_fixture_handoff"
        assertEquals(ImportStatus.IMPORTED, h.repo.refreshAndImport().status); h.assertImported()
        assertSame(old, h.expectedOriginal)
        assertNotEquals(previous.sessionBinding, h.store.value!!.sessionBinding)
        assertEquals("later_fixture_handoff", JSONObject(h.ackCalls().last().body!!).getString("handoffRef"))
        assertEquals("later_fixture_session", h.ackCalls().last().session!!.value)
        assertEquals(2, h.setupCalls().size)
    }

    @Test fun changedResourceBetweenRetriesCannotAckPreviousResourceStage() = runTest {
        val h = Harness(); val old = h.old(); h.login(); h.wire.ackStatus = 503
        failure(PortalFailure.Reason.UNAVAILABLE) { h.repo.refreshAndImport() }
        val previous = h.store.journal!!.candidate
        h.wire.overviewRef = otherResource; h.wire.ackStatus = 204; h.wire.handoff = "later_resource_handoff"
        assertEquals(ImportStatus.IMPORTED, h.repo.refreshAndImport().status); h.assertImported()
        assertEquals(resource, previous.resource); assertEquals(otherResource, h.store.value!!.resource)
        assertEquals("later_resource_handoff", JSONObject(h.ackCalls().last().body!!).getString("handoffRef"))
        assertEquals(resource, old.resource); assertEquals(2, h.setupCalls().size)
    }

    @Test fun walletFirstOldSessionPendingReceiptFailsClosedWithoutReclaimOrRecoveryJournal() = runTest {
        val h = Harness(); val old = h.old(); h.login(); h.checkLegacyOrdering = false
        h.wire.model = WireResponse(200, """{"commerceModel":"wallet_first_unique_v1"}""")
        failure(PortalFailure.Reason.RECOVERY_REQUIRED) { h.repo.refreshAndImport() }
        assertSame(old, h.store.value); assertNull(h.store.journal)
        assertEquals(0, h.setupCalls().size); assertEquals(0, h.ackCalls().size); assertEquals(0, h.downloads)
    }

    @Test fun walletFirstResourceMismatchPreservesReceiptAndDoesNotRebindOrReclaim() = runTest {
        val h = Harness(); val old = h.old(pending = false, ref = otherResource); h.login(); h.checkLegacyOrdering = false
        h.wire.model = WireResponse(200, """{"commerceModel":"wallet_first_unique_v1"}""")
        failure(PortalFailure.Reason.RECOVERY_REQUIRED) { h.repo.refreshAndImport() }
        assertSame(old, h.store.value); assertNull(h.store.journal)
        assertEquals(0, h.setupCalls().size); assertEquals(0, h.downloads)
    }

    @Test fun walletFirstAcknowledgedSameResourceRemainsUsableWithoutNewClaim() = runTest {
        val h = Harness(); h.checkLegacyOrdering = false
        val old = DeliveryRecord(account, sessionBinding(Secret("old_fixture_session")), resource,
            "old_fixture_operation", Secret(nextUrl), Secret("old_fixture_handoff"), false)
        h.store.value = old; h.login(); h.wire.setupAvailable = false
        h.wire.model = WireResponse(200, """{"commerceModel":"wallet_first_unique_v1"}""")
        assertEquals(ImportStatus.IMPORTED, h.repo.refreshAndImport().status); h.assertImported()
        assertSame(old, h.store.value); assertNull(h.store.journal)
        assertEquals(0, h.setupCalls().size); assertEquals(0, h.ackCalls().size); assertEquals(1, h.downloads)
    }

    @Test fun unknownModelAndFailedModelReadNeverMutateReceiptOrReveal() = runTest {
        for ((response, reason) in listOf(
            WireResponse(200, """{"commerceModel":"unverified_future_model"}""") to PortalFailure.Reason.PROTOCOL,
            WireResponse(503, "") to PortalFailure.Reason.UNAVAILABLE,
            WireResponse(200, """{"commerceModel":"legacy","unexpected":true}""") to PortalFailure.Reason.PROTOCOL)) {
            val h = Harness(); val old = h.old(); h.login(); h.wire.model = response
            failure(reason) { h.repo.refreshAndImport() }
            assertSame(old, h.store.value); assertNull(h.store.journal)
            assertEquals(0, h.setupCalls().size); assertEquals(0, h.ackCalls().size); assertEquals(0, h.downloads)
        }
    }

    @Test fun legacyWithoutVerifiedSetupSupportDoesNotInventCurrentReveal() = runTest {
        val h = Harness(); h.login(); h.wire.setupAvailable = false
        assertEquals(ImportStatus.WAITING_DELIVERY, h.repo.refreshAndImport().status)
        assertNull(h.store.value); assertNull(h.store.journal)
        assertEquals(0, h.setupCalls().size); assertEquals(0, h.downloads)
    }

    @Test fun logoutDuringRevealCannotAckOrCommitLateMaterial() = runTest {
        val h = Harness(); val old = h.old(); h.login()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.wire.hook = { if (it.endsWith("/setup")) { started.complete(Unit); release.await() } }
        val importing = async { h.repo.refreshAndImport() }; started.await()
        val logout = launch { h.repo.signOutAccount() }; runCurrent(); release.complete(Unit)
        cancelled { importing.await() }; logout.join()
        assertSame(old, h.store.value); assertEquals(0, h.ackCalls().size); assertEquals(0, h.downloads)
        assertFalse(h.events.contains("profile-save")); assertFalse(h.events.contains("main-save"))
    }

    @Test fun logoutDuringAckCannotDownloadOrPublishLateAcknowledgement() = runTest {
        val h = Harness(); val old = h.old(); h.login()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.wire.hook = { if (it.endsWith("/ack")) { started.complete(Unit); release.await() } }
        val importing = async { h.repo.refreshAndImport() }; started.await()
        val logout = launch { h.repo.signOutAccount() }; runCurrent(); release.complete(Unit)
        cancelled { importing.await() }; logout.join()
        assertSame(old, h.store.value); assertEquals(0, h.downloads)
        assertEquals(LegacyRecoveryPhase.REVEALED, h.store.journal!!.phase)
        assertFalse(h.events.contains("main-save"))
    }

    @Test fun accountSwitchDuringDownloadRejectsOldResultAndClearsForeignJournal() = runTest {
        val h = Harness(); h.old(); h.login()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        h.downloadHook = { started.complete(Unit); release.await() }
        val importing = async { h.repo.refreshAndImport() }; started.await()
        val switching = launch { h.login("other_account_session", "other-fixture@example.invalid") }
        runCurrent(); release.complete(Unit)
        cancelled { importing.await() }; switching.join()
        assertNull(h.store.value); assertNull(h.store.journal); assertNull(h.repo.copyImportedConfig())
        assertFalse(h.events.contains("profile-save")); assertFalse(h.events.contains("main-save"))
        assertTrue(h.lastBytes!!.all { it == 0.toByte() })
    }

    @Test fun controllerExplicitSubscriptionRefreshRecoversCommittingJournalEvenWithSavedProfile() = runTest {
        val h = Harness(); val old = h.old(); h.store.failMainSave = true
        val controller = AccountController(h.repo, backgroundScope)
        h.loginController(controller); runCurrent()
        assertTrue(controller.authenticated); assertFalse(controller.busy); assertNotNull(controller.error)
        assertTrue(controller.localSubscription.hasProfile)
        assertEquals(LocalSubscriptionStatus.STORAGE_ERROR, controller.localSubscription.status)
        assertEquals(LegacyRecoveryPhase.PROFILE_COMMITTING, h.store.journal!!.phase)
        assertSame(old, h.store.value)
        h.profiles.memory.read()!!.close()
        assertEquals(1, h.downloads)
        h.store.failMainSave = false
        controller.refreshSubscription(); runCurrent()
        assertFalse(controller.busy); assertNull(controller.error)
        assertEquals(LocalSubscriptionStatus.READY, controller.localSubscription.status)
        assertEquals(ImportStatus.IMPORTED, controller.importStatus); h.assertImported()
        assertEquals(2, h.wire.calls.count { it.path.endsWith("/model") })
        assertEquals(2, h.wire.calls.count { it.path.endsWith("/overview") })
        assertEquals(2, h.downloads); assertEquals(1, h.setupCalls().size); assertEquals(1, h.ackCalls().size)
    }

    @Test fun controllerSavedProfileCannotBypassUnknownOrChangedModelDuringPendingRecovery() = runTest {
        for (model in listOf("unverified_future_model", "wallet_first_unique_v1")) {
            val h = Harness(); val old = h.old(); h.store.failMainSave = true
            val controller = AccountController(h.repo, backgroundScope)
            h.loginController(controller); runCurrent()
            assertTrue(controller.localSubscription.hasProfile)
            assertEquals(LocalSubscriptionStatus.STORAGE_ERROR, controller.localSubscription.status)
            val journal = h.store.journal
            h.store.failMainSave = false
            h.wire.model = WireResponse(200, """{"commerceModel":"$model"}""")
            controller.refreshSubscription(); runCurrent()
            assertTrue(controller.authenticated); assertFalse(controller.busy); assertNotNull(controller.error)
            assertNotEquals(LocalSubscriptionStatus.READY, controller.localSubscription.status)
            assertNull(controller.importStatus); assertNull(h.repo.copyImportedConfig())
            assertSame(old, h.store.value); assertSame(journal, h.store.journal)
            assertEquals(2, h.wire.calls.count { it.path.endsWith("/model") })
            assertEquals(1, h.downloads); assertEquals(1, h.setupCalls().size); assertEquals(1, h.ackCalls().size)
        }
    }
}
