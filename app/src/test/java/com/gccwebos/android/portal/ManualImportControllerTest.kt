package com.gccwebos.android.portal

import com.gccwebos.android.manualimport.ManualImportInputKind
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManualImportControllerTest {
    private val now = Instant.parse("2026-10-04T00:00:00Z")
    private fun yaml(host: String = "192.0.2.10") = """proxies:
  - name: Synthetic manual node
    type: ss
    server: $host
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""".toByteArray()
    private fun url() = "https://sub.invalid/sub/a/synthetic".toByteArray()
    private class Delivery : DeliveryStore {
        override fun read(): DeliveryRecord? = null
        override fun save(record: DeliveryRecord) = error("Import must not claim a delivery")
        override fun clear() = Unit
    }
    private inner class Fixture(scope: CoroutineScope) {
        val memory = MemoryManualSourceStore()
        var saves = 0
        val store = object : ManualSourceStore {
            override fun read() = memory.read()
            override fun save(record: ManualSourceRecord) { saves++; memory.save(record) }
        }
        var reads = 0
        var response: suspend () -> ByteArray = { yaml() }
        var allowed = true
        var fenceCalls = 0
        var fence: (() -> Unit) -> Unit = { it() }
        val snapshots = mutableListOf<LocalSubscriptionSnapshot>()
        var settled = 0
        val profiles = MemoryLocalProfileStore()
        val manager = LocalSubscriptionManager(Delivery(), profiles, object : SubscriptionSource {
            override suspend fun download(url: Secret) = read(url).bytes
            override suspend fun read(url: Secret): SubscriptionRead {
                reads++; return SubscriptionRead(response(), now.plusSeconds(600))
            }
        }, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { now },
            LocalSubscriptionAuthority(), manualSources = store)
        val controller = ManualImportController(manager, scope, { allowed },
            { action -> fenceCalls++; fence(action) }, { snapshots += it }, onSettled = { settled++ })
        fun ticket(): ManualImportController.Ticket {
            controller.open(); return requireNotNull(controller.begin())
        }
        fun revision() = store.read()!!.use { it.revision }
        suspend fun local(host: String = "192.0.2.10") {
            val bytes = yaml(host)
            try { manager.importManual(bytes) { it() } } finally { bytes.fill(0) }
        }
    }

    @Test fun useAccountWithExistingV1FreshlyReloadsSelectableNodesInTheSameOperation() = runTest {
        val f = Fixture(this)
        val accountUrl = Secret("https://sub.invalid/sub/a/synthetic-account")
        CachedSubscription(sessionBinding(accountUrl), yaml("192.0.2.90"), now.plusSeconds(600), accountUrl)
            .use(f.profiles::save)
        f.local()
        val oldManualChoice = f.manager.nodeSelectionSnapshot().choices.single().selection
        f.response = { yaml("192.0.2.91") }
        f.controller.open(); f.controller.useAccount(); runCurrent()
        assertEquals(LocalSubscriptionStatus.READY, f.snapshots.single().status)
        assertEquals(ManualSourceKind.LegacyRemote, f.snapshots.single().sourceKind)
        assertEquals(1, f.reads)
        assertEquals(1, f.fenceCalls)
        val choices = f.manager.nodeSelectionSnapshot().choices
        assertEquals(1, choices.size)
        assertNotSame(oldManualChoice, choices.single().selection)
        assertFalse(f.manager.selectNode(oldManualChoice))
        assertFalse(f.controller.busy); assertFalse(f.controller.visible)
    }

    @Test fun useAccountFreshReadFailureKeepsCachedV1ButDoesNotPublishSelectableFallback() = runTest {
        val f = Fixture(this)
        val accountUrl = Secret("https://sub.invalid/sub/a/synthetic-account")
        val retained = yaml("192.0.2.90")
        CachedSubscription(sessionBinding(accountUrl), retained, now.plusSeconds(600), accountUrl).use(f.profiles::save)
        f.local()
        f.response = { throw PortalFailure(PortalFailure.Reason.NETWORK) }
        f.controller.open(); f.controller.useAccount(); runCurrent()
        assertEquals(LocalSubscriptionStatus.RETRY_REQUIRED, f.snapshots.single().status)
        assertEquals(ManualSourceKind.LegacyRemote, f.manager.sourceKind())
        assertEquals(1, f.reads); assertEquals(1, f.fenceCalls)
        assertTrue(f.manager.nodeSelectionSnapshot().choices.isEmpty())
        f.profiles.read()!!.use { cached ->
            val bytes = cached.copyBytes()
            try { assertTrue("Failed fresh read must retain the original v1 bytes", retained.contentEquals(bytes)) }
            finally { bytes.fill(0) }
        }
        assertFalse(f.controller.busy)
    }

    @Test fun threeInputsShareExplicitAdoptionAndWipeTheTransferredArrayBeforeDispatch() = runTest {
        for (kind in ManualImportInputKind.entries) {
            val f = Fixture(this)
            val ticket = f.ticket()
            assertEquals(0, f.saves); assertTrue(f.snapshots.isEmpty())
            val bytes = if (kind == ManualImportInputKind.Link) url() else yaml()
            f.controller.submit(ticket, bytes, kind)
            assertTrue(bytes.all { it == 0.toByte() })
            assertTrue(f.controller.busy); assertEquals(0, f.saves)
            runCurrent()
            assertFalse(f.controller.busy); assertFalse(f.controller.visible)
            assertEquals(1, f.snapshots.size); assertEquals(1, f.fenceCalls)
            assertEquals(if (kind == ManualImportInputKind.Link) ManualSourceKind.ImportedRemote else ManualSourceKind.ImportedLocal,
                f.snapshots.single().sourceKind)
            assertEquals(1, f.settled); assertEquals(if (kind == ManualImportInputKind.Link) 1 else 0, f.reads)
        }
    }

    @Test fun unsupportedRemoteLinksHaveSpecificCaptionWithoutDownloadOrSourceMutation() = runTest {
        for (kind in ManualImportInputKind.entries) {
            for (input in listOf("https://third-party.invalid/api/v1/client/subscribe?token=synthetic",
                "https://third-party.invalid/sub/a/synthetic",
                "https://sub.invalid/sub/a/synthetic?token=synthetic")) {
                val f = Fixture(this); f.local()
                val revision = f.revision(); val saves = f.saves
                val selected = f.manager.nodeSelectionSnapshot().choices.single().selection
                val bytes = input.toByteArray()
                f.controller.submit(f.ticket(), bytes, kind); runCurrent()
                assertEquals("暂不支持此订阅链接", f.controller.error)
                assertEquals(0, f.reads); assertEquals(saves, f.saves); assertEquals(0, f.fenceCalls)
                assertEquals(revision, f.revision())
                assertSame(selected, f.manager.nodeSelectionSnapshot().choices.single().selection)
                assertTrue(bytes.all { it == 0.toByte() }); assertTrue(f.snapshots.isEmpty())
                assertFalse(f.controller.busy); assertTrue(f.controller.visible)
            }
        }
    }

    @Test fun scanAndDocumentOfficialUrlsUseRemoteImport() = runTest {
        for (kind in listOf(ManualImportInputKind.Scan, ManualImportInputKind.Document)) {
            val f = Fixture(this); val bytes = url()
            f.controller.submit(f.ticket(), bytes, kind); runCurrent()
            assertNull(f.controller.error); assertFalse(f.controller.visible)
            assertEquals(1, f.reads); assertEquals(1, f.saves); assertEquals(1, f.fenceCalls)
            assertEquals(ManualSourceKind.ImportedRemote, f.snapshots.single().sourceKind)
            assertTrue(bytes.all { it == 0.toByte() })
        }
    }

    @Test fun serverRejectionIsNotMisreportedAsAnUnsupportedInputLink() = runTest {
        val f = Fixture(this); f.local(); val revision = f.revision(); val saves = f.saves
        f.response = { throw PortalFailure(PortalFailure.Reason.SUBSCRIPTION_REVOKED) }
        f.controller.submit(f.ticket(), url(), ManualImportInputKind.Scan); runCurrent()
        assertNotNull(f.controller.error)
        assertNotEquals("暂不支持此订阅链接", f.controller.error)
        assertEquals(1, f.reads); assertEquals(saves, f.saves); assertEquals(0, f.fenceCalls)
        assertEquals(revision, f.revision()); assertTrue(f.snapshots.isEmpty())
    }

    @Test fun cancellationBeforeDispatchWipesInputAndNeverWritesOrPublishes() = runTest {
        val f = Fixture(this); val ticket = f.ticket(); val bytes = yaml()
        f.controller.submit(ticket, bytes, ManualImportInputKind.Document)
        f.controller.cancel(ticket)
        runCurrent()
        assertTrue(bytes.all { it == 0.toByte() })
        assertFalse(f.controller.busy); assertFalse(f.controller.visible)
        assertEquals(0, f.saves); assertEquals(0, f.fenceCalls); assertTrue(f.snapshots.isEmpty())
        assertEquals(1, f.settled)
    }

    @Test fun parentCancellationBeforeDispatchReleasesBusyAndNotifiesSettlementOnce() = runTest {
        val owner = Job(coroutineContext[Job])
        val f = Fixture(CoroutineScope(coroutineContext + owner))
        val bytes = yaml(); f.controller.submit(f.ticket(), bytes, ManualImportInputKind.Scan)
        owner.cancel(); runCurrent()
        assertTrue(bytes.all { it == 0.toByte() })
        assertFalse(f.controller.busy); assertEquals(1, f.settled)
        assertEquals(0, f.saves); assertTrue(f.snapshots.isEmpty())
    }

    @Test fun lateInputAfterCancellationCannotReplaceANewTicketOrPublish() = runTest {
        val f = Fixture(this); val old = f.ticket()
        // The same invalidation is used for host disposal/navigation and account logout/reset.
        f.controller.cancel()
        val current = f.ticket(); val late = yaml()
        f.controller.submit(old, late, ManualImportInputKind.Scan)
        assertTrue(late.all { it == 0.toByte() }); assertFalse(f.controller.busy)
        f.controller.cancel(old)
        assertTrue(f.controller.visible)
        f.controller.submit(current, yaml(), ManualImportInputKind.Document); runCurrent()
        assertEquals(1, f.snapshots.size); assertEquals(1, f.saves)
    }

    @Test fun linkOnlyAcceptsUrlShapeAndAllEntriesRejectOversizeBeforeDispatch() = runTest {
        val f = Fixture(this); val ticket = f.ticket()
        val yaml = yaml()
        f.controller.submit(ticket, yaml, ManualImportInputKind.Link)
        assertTrue(yaml.all { it == 0.toByte() }); assertEquals("配置内容无效", f.controller.error)
        for (kind in ManualImportInputKind.entries) {
            val oversized = ByteArray(65537) { 65 }
            f.controller.submit(ticket, oversized, kind)
            assertTrue(oversized.all { it == 0.toByte() }); assertEquals("配置超过 64 KiB", f.controller.error)
        }
        runCurrent()
        assertFalse(f.controller.busy); assertEquals(0, f.reads); assertEquals(0, f.saves)
        assertEquals(0, f.settled); assertTrue(f.snapshots.isEmpty())
    }

    @Test fun queuedInputRechecksAdmissionAndClearOrSourceAbaBeforeDoingWork() = runTest {
        for (mutation in listOf("blocked", "clear", "aba")) {
            val f = Fixture(this); f.local()
            val ticket = f.ticket()
            f.controller.submit(ticket, yaml("192.0.2.12"), ManualImportInputKind.Document)
            when (mutation) {
                "blocked" -> f.allowed = false
                "clear" -> f.manager.unlink()
                else -> { f.local("192.0.2.11"); f.local() }
            }
            val before = f.revision(); val saves = f.saves
            runCurrent()
            assertEquals(before, f.revision()); assertEquals(saves, f.saves)
            assertTrue(f.snapshots.isEmpty()); assertFalse(f.controller.busy)
            assertEquals(0, f.fenceCalls)
        }
    }

    @Test fun admissionChangingDuringDownloadCannotCrossTheRealCommitBoundary() = runTest {
        val f = Fixture(this); f.local(); val before = f.revision()
        val release = CompletableDeferred<Unit>()
        f.response = { release.await(); yaml("192.0.2.11") }
        f.controller.submit(f.ticket(), url(), ManualImportInputKind.Link); runCurrent()
        assertEquals(1, f.reads); assertTrue(f.controller.busy)
        f.allowed = false; release.complete(Unit); runCurrent()
        assertEquals(before, f.revision()); assertTrue(f.snapshots.isEmpty())
        assertFalse(f.controller.busy); assertEquals(1, f.settled)
    }

    @Test fun blockedOwnerFenceNeverChangesThePreviousSourceOrClaimsSuccess() = runTest {
        val f = Fixture(this); f.local(); val before = f.revision()
        f.fence = { throw IllegalStateException("Synthetic connection is active") }
        f.controller.submit(f.ticket(), yaml("192.0.2.11"), ManualImportInputKind.Document); runCurrent()
        assertEquals(1, f.fenceCalls); assertEquals(before, f.revision())
        assertTrue(f.snapshots.isEmpty()); assertFalse(f.controller.busy)
        assertEquals("请先断开连接后再导入", f.controller.error)
    }

    @Test fun canceledUncooperativeDownloadCannotClearNewOperationsBusyOrPublishLate() = runTest {
        val f = Fixture(this)
        val first = CompletableDeferred<Unit>(); val second = CompletableDeferred<Unit>()
        f.response = {
            if (f.reads == 1) withContext(NonCancellable) { first.await() } else second.await()
            yaml()
        }
        f.controller.submit(f.ticket(), url(), ManualImportInputKind.Link); runCurrent()
        f.controller.cancel()
        f.controller.submit(f.ticket(), url(), ManualImportInputKind.Scan); runCurrent()
        first.complete(Unit); runCurrent()
        assertEquals(2, f.reads); assertTrue(f.controller.busy)
        assertTrue(f.snapshots.isEmpty()); assertNull(f.controller.error)
        assertEquals(1, f.settled)
        second.complete(Unit); runCurrent()
        assertFalse(f.controller.busy); assertEquals(1, f.snapshots.size)
        assertEquals(2, f.settled); assertEquals(1, f.fenceCalls)
    }

    @Test fun explicitSelectorChangesDoNotOverwriteANewerSourceAfterWaitingForTheManager() = runTest {
        for (remove in listOf(false, true)) {
            val f = Fixture(this); f.local()
            val release = CompletableDeferred<Unit>()
            f.response = { release.await(); yaml("192.0.2.11") }
            val replacing = async { f.manager.importManual(url()) { it() } }
            runCurrent()
            f.controller.open()
            if (remove) f.controller.remove() else f.controller.useAccount()
            runCurrent() // selector is waiting behind the in-flight source replacement
            release.complete(Unit); replacing.await(); runCurrent()
            assertEquals(ManualSourceKind.ImportedRemote, f.manager.sourceKind())
            assertTrue(f.snapshots.isEmpty()); assertFalse(f.controller.busy)
        }
    }
}
