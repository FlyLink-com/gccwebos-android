package com.gccwebos.android.portal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Synthetic memory-only transactions. No real endpoint, account, file, or tunnel is used. */
class ConfigurationLibraryManagerTest {
    private val now = Instant.parse("2026-10-05T00:00:00Z")
    private val manualKinds = setOf(ManualSourceKind.ImportedLocal, ManualSourceKind.ImportedRemote, ManualSourceKind.ExternalRemote)
    private fun yaml(vararg names: String): ByteArray = buildString {
        append("proxies:\n")
        names.forEachIndexed { index, name ->
            append("  - name: $name\n    type: ss\n    server: 192.0.2.${10 + index}\n")
            append("    port: 443\n    cipher: aes-128-gcm\n    password: synthetic-only\n")
        }
    }.toByteArray()

    private inner class Fixture {
        val store = MemoryManualSourceStore()
        val profiles = MemoryLocalProfileStore()
        val delivery = object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = error("Library cannot create an account delivery")
            override fun clear() = Unit
        }
        var reads = 0
        var officialReads = 0
        var response = yaml("Remote Alpha", "Remote Beta")
        var failDownload = false
        private val external = object : SubscriptionSource {
            override suspend fun download(url: Secret) = read(url).bytes
            override suspend fun read(url: Secret): SubscriptionRead {
                reads++
                if (failDownload) throw PortalFailure(PortalFailure.Reason.NETWORK)
                return SubscriptionRead(response.copyOf(), now.plusSeconds(3600))
            }
        }
        fun recreate() = LocalSubscriptionManager(delivery, profiles,
            SubscriptionSource { officialReads++; error("No official HTTP expected") },
            OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { now },
            LocalSubscriptionAuthority(), manualSources = store, externalSource = external)
        val manager = recreate()
        suspend fun local(vararg names: String) = import(yaml(*names))
        suspend fun remote() = import("https://subscription.example/library?token=synthetic-only".toByteArray())
        private suspend fun import(input: ByteArray): LocalSubscriptionSnapshot =
            try { manager.importManual(input) { it() } } finally { input.fill(0) }
        fun entries(manager: LocalSubscriptionManager = this.manager): List<ConfigurationItem> {
            val snapshot = manager.configurationLibrarySnapshot()
            assertFalse(snapshot.storageError)
            return snapshot.items.filter { it.kind in manualKinds }
        }
        fun entry(node: String) = entries().single { item -> item.nodes.any { it.name == node } }
        suspend fun clear() = manager.unlinkAndAwait(cleanup = {}, commitFence = { it() })
        fun seedAccount() {
            val bytes = yaml("Account fixture")
            val source = Secret("https://sub.invalid/sub/a/library-fixture")
            try { CachedSubscription(sessionBinding(source), bytes, now.plusSeconds(3600), source).use(profiles::save) }
            finally { bytes.fill(0) }
        }
    }

    @Test fun twoImportsRemainSeparateAndRecreationRestoresBothWithoutDownloading() = runTest {
        val f = Fixture(); f.local("Local Alpha"); f.local("Local Beta")
        val original = f.entries()
        assertEquals(2, original.size)
        assertEquals(setOf("Local Alpha", "Local Beta"), original.flatMap { it.nodes }.map { it.name }.toSet())
        assertEquals("Local Beta", original.single { it.active }.nodes.single().name)
        assertEquals(2, original.map { it.handle.entryId }.distinct().size)
        val recreated = f.recreate(); recreated.restore()
        val restored = f.entries(recreated)
        assertEquals(original.map { it.handle.entryId }.toSet(), restored.map { it.handle.entryId }.toSet())
        assertEquals("Local Beta", restored.single { it.active }.nodes.single().name)
        assertEquals(0, f.reads); assertEquals(0, f.officialReads)
    }

    @Test fun accountProfileRemainsAnIndependentVisibleRowAfterManualImports() = runTest {
        val f = Fixture(); f.seedAccount(); f.local("Local Alpha"); f.local("Local Beta")
        val snapshot = f.manager.configurationLibrarySnapshot()
        assertFalse(snapshot.storageError); assertEquals(3, snapshot.items.size)
        val account = snapshot.items.single { it.kind == ManualSourceKind.LegacyRemote }
        assertNull(account.handle.entryId); assertFalse(account.active)
        assertEquals(listOf("Account fixture"), account.nodes.map { it.name })
        assertEquals(LocalSubscriptionStatus.READY, account.status)
        f.profiles.read()!!.use { assertEquals(now.plusSeconds(3600), it.validUntil) }
        assertEquals(0, f.officialReads); assertEquals(0, f.reads)
    }

    @Test fun identicalNamesDoNotMergeOrReplaceDifferentConfigurationIds() = runTest {
        val f = Fixture(); f.local("Local Alpha"); f.local("Local Beta")
        f.manager.renameConfiguration(f.entry("Local Alpha").handle, "同名配置")
        f.manager.renameConfiguration(f.entry("Local Beta").handle, "同名配置")
        val entries = f.entries()
        assertEquals(2, entries.size); assertTrue(entries.all { it.name == "同名配置" })
        assertEquals(2, entries.map { it.handle.entryId }.distinct().size)
        assertEquals("Local Beta", entries.single { it.active }.nodes.single().name)
    }

    @Test fun renamingActiveConfigurationPreservesOpaqueNodeChoiceAndContent() = runTest {
        val f = Fixture(); f.local("Local Alpha")
        val old = f.entry("Local Alpha")
        val selection = f.manager.nodeSelectionSnapshot().choices.single().selection
        assertTrue(f.manager.selectNode(selection))
        val revision = f.store.read()!!.use { it.revision }
        f.manager.renameConfiguration(old.handle, "新名称")
        val updated = f.entry("Local Alpha")
        assertEquals(old.handle.entryId, updated.handle.entryId); assertEquals("新名称", updated.name)
        assertEquals(revision, f.store.read()!!.use { it.revision })
        val choices = f.manager.nodeSelectionSnapshot()
        assertSame(selection, choices.choices.single().selection); assertSame(selection, choices.selected)
        assertTrue(f.manager.selectNode(selection)); assertEquals(0, f.reads)
    }

    @Test fun deletingInactiveConfigurationLeavesActiveSelectionUntouched() = runTest {
        val f = Fixture(); f.local("Local Alpha"); f.local("Local Beta")
        val selection = f.manager.nodeSelectionSnapshot().choices.single().selection
        assertTrue(f.manager.selectNode(selection))
        val revision = f.store.read()!!.use { it.revision }
        f.manager.removeConfiguration(f.entry("Local Alpha").handle) { it() }
        val remaining = f.entries().single()
        assertTrue(remaining.active); assertEquals("Local Beta", remaining.nodes.single().name)
        assertEquals(revision, f.store.read()!!.use { it.revision })
        assertSame(selection, f.manager.nodeSelectionSnapshot().selected)
        assertTrue(f.manager.selectNode(selection)); assertEquals(0, f.reads)
    }

    @Test fun deletingActiveConfigurationSelectsNoneAndDoesNotAdoptAnotherEntryOrAccount() = runTest {
        val f = Fixture(); f.seedAccount(); f.local("Local Alpha"); f.local("Local Beta")
        val oldSelection = f.manager.nodeSelectionSnapshot().choices.single().selection
        f.manager.removeConfiguration(f.entry("Local Beta").handle) { it() }
        assertEquals(ManualSourceKind.None, f.manager.sourceKind())
        assertEquals(1, f.entries().size); assertFalse(f.entries().single().active)
        assertTrue(f.manager.configurationLibrarySnapshot().items.none { it.active })
        assertTrue(f.manager.nodeSelectionSnapshot().choices.isEmpty())
        assertFalse(f.manager.selectNode(oldSelection)); assertNotNull(f.profiles.read()?.also { it.close() })
        val recreated = f.recreate(); assertEquals(LocalSubscriptionStatus.MISSING, recreated.restore().status)
        assertEquals(1, f.entries(recreated).size); assertEquals(ManualSourceKind.None, recreated.sourceKind())
        assertEquals(0, f.reads); assertEquals(0, f.officialReads)
    }

    @Test fun handlesCapturedBeforeAddOrClearCannotMutateTheLaterLibrary() = runTest {
        val f = Fixture(); f.local("Local Alpha")
        val beforeAdd = f.entry("Local Alpha").handle
        f.local("Local Beta")
        rejected { f.manager.renameConfiguration(beforeAdd, "不可提交") }
        rejected { f.manager.activateConfiguration(beforeAdd) { it() } }
        rejected { f.manager.removeConfiguration(beforeAdd) { it() } }
        assertEquals(2, f.entries().size)
        val beforeClear = f.entry("Local Beta").handle
        f.clear()
        rejected { f.manager.renameConfiguration(beforeClear, "不可复活") }
        rejected { f.manager.activateConfiguration(beforeClear) { it() } }
        rejected { f.manager.removeConfiguration(beforeClear) { it() } }
        assertTrue(f.entries().isEmpty()); assertEquals(ManualSourceKind.None, f.manager.sourceKind())
    }

    @Test fun inactiveRemoteActivationDownloadsFreshAndNeverReusesAnOldNodeIndex() = runTest {
        val f = Fixture(); f.remote()
        val oldChoice = f.manager.nodeSelectionSnapshot().choices.first().selection
        assertTrue(f.manager.selectNode(oldChoice))
        f.local("Local Beta")
        val remote = f.entries().single { it.kind == ManualSourceKind.ExternalRemote }
        assertFalse(remote.active); assertEquals(1, f.reads)
        f.response = yaml("Remote Gamma", "Remote Alpha")
        val result = f.manager.activateConfiguration(remote.handle) { it() }
        assertEquals(2, f.reads); assertEquals(LocalSubscriptionStatus.READY, result.status)
        assertEquals(listOf("Remote Gamma", "Remote Alpha"), result.nodes.map { it.name })
        assertEquals(remote.handle.entryId, f.entries().single { it.active }.handle.entryId)
        assertFalse(f.manager.selectNode(oldChoice))
        val fresh = f.manager.nodeSelectionSnapshot()
        assertNull(fresh.selected); assertTrue(fresh.choices.none { it.selection === oldChoice })
        assertEquals(listOf("Remote Gamma", "Remote Alpha"), fresh.choices.map { it.name })
    }

    @Test fun failedInactiveRemoteActivationDoesNotFallBackToCachedRemoteOrReplaceActiveLocal() = runTest {
        val f = Fixture(); f.remote(); f.local("Local Beta")
        val before = f.entry("Local Beta")
        val choice = f.manager.nodeSelectionSnapshot().choices.single().selection
        f.failDownload = true
        try {
            f.manager.activateConfiguration(f.entries().single { it.kind == ManualSourceKind.ExternalRemote }.handle) { it() }
            fail("A failed remote activation must not accept cached content")
        } catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.NETWORK, failure.reason) }
        assertEquals(before.handle.entryId, f.entries().single { it.active }.handle.entryId)
        assertEquals(2, f.entries().size); assertEquals(2, f.reads)
        assertSame(choice, f.manager.nodeSelectionSnapshot().choices.single().selection)
    }

    @Test fun failedNewImportPreservesEveryEntryAndTheExistingActiveChoice() = runTest {
        val f = Fixture(); f.local("Local Alpha"); f.local("Local Beta")
        val ids = f.entries().map { it.handle.entryId }.toSet()
        val active = f.entries().single { it.active }.handle.entryId
        val choice = f.manager.nodeSelectionSnapshot().choices.single().selection
        val invalid = "proxies:\n  - name: Rejected fixture\n    type: unknown\n".toByteArray()
        try {
            f.manager.importManual(invalid) { it() }; fail("Unsupported configuration accepted")
        } catch (_: Exception) { } finally { invalid.fill(0) }
        assertEquals(ids, f.entries().map { it.handle.entryId }.toSet())
        assertEquals(active, f.entries().single { it.active }.handle.entryId)
        assertSame(choice, f.manager.nodeSelectionSnapshot().choices.single().selection)
    }

    @Test fun activeDeleteStillRequiresTheConnectionOwnerFence() = runTest {
        val f = Fixture(); f.local("Local Alpha"); f.local("Local Beta")
        val active = f.entries().single { it.active }.handle.entryId
        rejected { f.manager.removeConfiguration(f.entry("Local Beta").handle) { throw CancellationException() } }
        assertEquals(2, f.entries().size)
        assertEquals(active, f.entries().single { it.active }.handle.entryId)
        assertEquals(ManualSourceKind.ImportedLocal, f.manager.sourceKind())
    }

    @Test fun replacingInactiveEntryKeepsItsIdAndNameAndDoesNotChangeTheActiveNode() = runTest {
        val f = Fixture(); f.local("Local Alpha"); f.local("Local Beta")
        f.manager.renameConfiguration(f.entry("Local Alpha").handle, "保留名称")
        val target = f.entry("Local Alpha")
        val active = f.entry("Local Beta")
        val choice = f.manager.nodeSelectionSnapshot().choices.single().selection
        assertTrue(f.manager.selectNode(choice))
        val activeRevision = f.store.read()!!.use { it.revision }
        val input = yaml("Replacement Alpha")
        try { f.manager.replaceConfiguration(input, target.handle, f.manager.captureManualSourceVersion()) { it() } }
        finally { input.fill(0) }
        val changed = f.entry("Replacement Alpha")
        assertEquals(target.handle.entryId, changed.handle.entryId); assertEquals("保留名称", changed.name)
        assertFalse(changed.active); assertEquals(2, f.entries().size)
        assertEquals(active.handle.entryId, f.entries().single { it.active }.handle.entryId)
        assertEquals(activeRevision, f.store.read()!!.use { it.revision })
        assertSame(choice, f.manager.nodeSelectionSnapshot().selected)
        assertTrue(f.manager.selectNode(choice)); assertEquals(0, f.reads)
    }

    @Test fun replacingActiveEntryKeepsItsIdButRevokesThePreviousOpaqueChoice() = runTest {
        val f = Fixture(); f.local("Local Alpha"); f.local("Local Beta")
        f.manager.renameConfiguration(f.entry("Local Beta").handle, "保留活动名称")
        val active = f.entry("Local Beta")
        val choice = f.manager.nodeSelectionSnapshot().choices.single().selection
        assertTrue(f.manager.selectNode(choice))
        val input = yaml("Replacement Beta")
        try { f.manager.replaceConfiguration(input, active.handle, f.manager.captureManualSourceVersion()) { it() } }
        finally { input.fill(0) }
        val changed = f.entry("Replacement Beta")
        assertTrue(changed.active); assertEquals(active.handle.entryId, changed.handle.entryId)
        assertEquals("保留活动名称", changed.name); assertEquals(2, f.entries().size)
        assertFalse(f.manager.selectNode(choice))
        val fresh = f.manager.nodeSelectionSnapshot()
        assertNull(fresh.selected); assertNotSame(choice, fresh.choices.single().selection)
        assertEquals("Replacement Beta", fresh.choices.single().name)
    }

    @Test fun failedReplacementKeepsBothEntriesAndActiveSelection() = runTest {
        val f = Fixture(); f.local("Local Alpha"); f.local("Local Beta")
        val ids = f.entries().map { it.handle.entryId }.toSet()
        val choice = f.manager.nodeSelectionSnapshot().choices.single().selection
        for (node in listOf("Local Alpha", "Local Beta")) {
            val target = f.entry(node)
            val input = "proxies:\n  - name: Rejected fixture\n    type: unknown\n".toByteArray()
            try {
                f.manager.replaceConfiguration(input, target.handle, f.manager.captureManualSourceVersion()) { it() }
                fail("Unsupported replacement accepted")
            } catch (_: Exception) { } finally { input.fill(0) }
            assertEquals(ids, f.entries().map { it.handle.entryId }.toSet())
            assertEquals("Local Beta", f.entries().single { it.active }.nodes.single().name)
            assertSame(choice, f.manager.nodeSelectionSnapshot().choices.single().selection)
        }
    }

    @Test fun staleReplacementHandleRejectsEvenWhenTheActiveSourceVersionIsCurrent() = runTest {
        val f = Fixture(); f.local("Local Alpha")
        val stale = f.entry("Local Alpha").handle
        f.local("Local Beta")
        val input = yaml("Cannot replace")
        try {
            rejected { f.manager.replaceConfiguration(input, stale, f.manager.captureManualSourceVersion()) { it() } }
        } finally { input.fill(0) }
        assertEquals(2, f.entries().size)
        assertEquals(setOf("Local Alpha", "Local Beta"), f.entries().flatMap { it.nodes }.map { it.name }.toSet())
    }

    @Test fun fullClearEmptiesLibraryAndAccountProfileWithoutReappearingAfterRecreation() = runTest {
        val f = Fixture(); f.seedAccount(); f.local("Local Alpha"); f.remote()
        f.clear()
        assertTrue(f.entries().isEmpty()); assertNull(f.profiles.read())
        f.store.readLibrary()!!.use {
            assertTrue(it.entries.isEmpty()); assertEquals(ConfigurationSelector.None, it.selector); assertFalse(it.clearPending)
        }
        val recreated = f.recreate()
        assertEquals(LocalSubscriptionStatus.MISSING, recreated.restore().status)
        assertTrue(f.entries(recreated).isEmpty()); assertEquals(ManualSourceKind.None, recreated.sourceKind())
        assertTrue(recreated.configurationLibrarySnapshot().items.none { it.active })
        assertEquals(1, f.reads); assertEquals(0, f.officialReads)
    }

    private suspend fun rejected(action: suspend () -> Unit) {
        try { action(); fail("Stale or unavailable configuration operation was accepted") }
        catch (_: CancellationException) { }
    }
}
