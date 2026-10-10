package com.gccwebos.android.portal

import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Per-node local alias / deletion of imported nodes, read-only account nodes, persistence. Synthetic data only. */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeOverridesTest {
    private val now = Instant.parse("2026-10-10T00:00:00Z")
    private fun yaml(vararg names: String): ByteArray = buildString {
        append("proxies:\n")
        names.forEachIndexed { index, name ->
            append("  - name: $name\n    type: ss\n    server: 192.0.2.${10 + Math.floorMod(name.hashCode(), 200)}\n")
            append("    port: 443\n    cipher: aes-128-gcm\n    password: synthetic-only\n")
        }
    }.toByteArray()

    private inner class Fixture {
        val store = MemoryManualSourceStore()
        val profiles = MemoryLocalProfileStore()
        val choices = MemoryNodeChoiceStore()
        val overrides = MemoryNodeOverrideStore()
        var response = yaml("Remote Alpha", "Remote Beta", "Remote Gamma")
        private val external = object : SubscriptionSource {
            override suspend fun download(url: Secret) = read(url).bytes
            override suspend fun read(url: Secret) = SubscriptionRead(response.copyOf(), now.plusSeconds(3600))
        }
        fun newManager() = LocalSubscriptionManager(object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = error("No account delivery in this fixture")
            override fun clear() = Unit
        }, profiles, SubscriptionSource { error("No official HTTP expected") },
            OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { now },
            LocalSubscriptionAuthority(), manualSources = store, externalSource = external,
            nodeChoices = choices, nodeOverrides = overrides)
        var manager = newManager()
        suspend fun local(vararg names: String) { val b = yaml(*names); try { manager.importManual(b) { it() } } finally { b.fill(0) } }
        suspend fun remote() {
            val b = "https://subscription.example/overrides?token=synthetic-only".toByteArray()
            try { manager.importManual(b) { it() } } finally { b.fill(0) }
        }
        fun seedAccount() {
            val bytes = yaml("Account fixture")
            val source = Secret("https://sub.invalid/sub/a/overrides-fixture")
            try { CachedSubscription(sessionBinding(source), bytes, now.plusSeconds(3600), source).use(profiles::save) }
            finally { bytes.fill(0) }
        }
        fun active() = manager.configurationLibrarySnapshot(true).items.single { it.active }
        fun names() = manager.nodeSelectionSnapshot().choices.filterNot { it.info }.map { it.name }
        fun restart() { manager = newManager() }
    }

    private fun ConfigurationItem.keyOf(name: String) = nodeKeys[nodes.indexOfFirst { it.name == name }]!!

    @Test fun anAliasReplacesTheNameEverywhereAndKeepsTheSelectionObject() = runTest {
        val f = Fixture(); f.local("Local Alpha", "Local Beta")
        val before = f.manager.nodeSelectionSnapshot().choices.first { it.name == "Local Beta" }
        assertTrue(f.manager.selectNode(before.selection))
        val item = f.active()
        f.manager.setNodeOverride(item.handle, item.keyOf("Local Beta"), NodeOverride(alias = "  我的备用线路 "))
        assertEquals(listOf("Local Alpha", "我的备用线路"), f.active().nodes.map { it.name })
        assertEquals(listOf("Local Alpha", "我的备用线路"), f.names())
        val after = f.manager.nodeSelectionSnapshot()
        assertSame("The selected node stays selected through a rename", before.selection, after.selected)
        assertEquals(setOf(1), f.active().nodeAliased)
    }

    @Test fun aliasAndDeletionSurviveARestartAndARefreshedSubscription() = runTest {
        val f = Fixture(); f.remote()
        val item = f.active()
        f.manager.setNodeOverride(item.handle, item.keyOf("Remote Alpha"), NodeOverride(alias = "东京 01"))
        f.manager.setNodeOverride(f.active().handle, item.keyOf("Remote Gamma"), NodeOverride(hidden = true))
        f.restart()
        // A fresh download with the same nodes in another order keeps both local edits.
        f.response = yaml("Remote Gamma", "Remote Beta", "Remote Alpha")
        f.manager.refresh()
        assertEquals(setOf("Remote Beta", "东京 01"), f.names().toSet())
        assertFalse(f.names().contains("Remote Gamma"))
        assertTrue(f.names().contains("东京 01"))
    }

    @Test fun aDeletedNodeIsNeverListedSelectableOrAutoPicked() = runTest {
        val f = Fixture(); f.local("Local Alpha", "Local Beta", "Local Gamma")
        val item = f.active()
        f.manager.setNodeOverride(item.handle, item.keyOf("Local Beta"), NodeOverride(hidden = true))
        val shown = f.active().nodes
        assertTrue(shown.single { it.name == "Local Beta" }.info) // listed nowhere: the UI shows only non-notice nodes
        assertEquals(listOf("Local Alpha", "Local Gamma"), f.names())
        val hidden = f.manager.nodeSelectionSnapshot().choices.single { it.name == "Local Beta" }
        assertFalse("A deleted node cannot be selected", f.manager.selectNode(hidden.selection))
        assertTrue(f.manager.captureNodeProbeTargets().none { t -> t.selection === hidden.selection })
        assertFalse(f.active().canEditNode(shown.indexOfFirst { it.name == "Local Beta" }))
    }

    @Test fun deletingTheSelectedNodeFallsBackToNoSelectionAndForgetsTheRememberedChoice() = runTest {
        val f = Fixture(); f.local("Local Alpha", "Local Beta")
        val choice = f.manager.nodeSelectionSnapshot().choices.first { it.name == "Local Beta" }
        assertTrue(f.manager.selectNode(choice.selection))
        assertEquals(1, f.choices.read().size)
        val item = f.active()
        f.manager.setNodeOverride(item.handle, item.keyOf("Local Beta"), NodeOverride(hidden = true))
        assertNull(f.manager.nodeSelectionSnapshot().selected)
        assertTrue("The remembered choice is dropped with the node", f.choices.read().isEmpty())
        f.restart(); f.manager.rebuildNodeSelectionFromLocal()
        assertNull("A restart does not bring the deleted node back as the selection", f.manager.nodeSelectionSnapshot().selected)
    }

    @Test fun theSelectedNodeIsRememberedAcrossARestartEvenWhenRenamed() = runTest {
        val f = Fixture(); f.local("Local Alpha", "Local Beta")
        val choice = f.manager.nodeSelectionSnapshot().choices.first { it.name == "Local Beta" }
        assertTrue(f.manager.selectNode(choice.selection))
        val item = f.active()
        f.manager.setNodeOverride(item.handle, item.keyOf("Local Beta"), NodeOverride(alias = "常用"))
        f.restart(); f.manager.rebuildNodeSelectionFromLocal()
        val restored = f.manager.nodeSelectionSnapshot()
        assertEquals("常用", restored.choices.single { it.selection === restored.selected }.name)
    }

    @Test fun aBlankAliasRestoresTheDeliveredName() = runTest {
        val f = Fixture(); f.local("Local Alpha", "Local Beta")
        val key = f.active().keyOf("Local Beta")
        f.manager.setNodeOverride(f.active().handle, key, NodeOverride(alias = "临时"))
        assertTrue(f.active().nodes.any { it.name == "临时" })
        f.manager.setNodeOverride(f.active().handle, key, NodeOverride(alias = "   "))
        assertEquals(listOf("Local Alpha", "Local Beta"), f.active().nodes.map { it.name })
        assertTrue(f.overrides.read().isEmpty())
    }

    @Test fun anInvalidAliasOrUnknownKeyChangesNothing() = runTest {
        val f = Fixture(); f.local("Local Alpha")
        val item = f.active()
        val key = item.keyOf("Local Alpha")
        for (bad in listOf("http://evil.example/x", "a\u202eb", "x".repeat(200))) {
            try { f.manager.setNodeOverride(item.handle, key, NodeOverride(alias = bad)); fail() } catch (_: IllegalArgumentException) { }
        }
        try { f.manager.setNodeOverride(item.handle, "not-a-node-key", NodeOverride(hidden = true)); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(f.overrides.read().isEmpty())
        assertEquals(listOf("Local Alpha"), f.active().nodes.map { it.name })
    }

    @Test fun accountNodesAreReadOnly() = runTest {
        val f = Fixture(); f.seedAccount(); f.local("Local Alpha")
        val account = f.manager.configurationLibrarySnapshot(true).items.single { it.kind == ManualSourceKind.LegacyRemote }
        assertEquals(listOf("Account fixture"), account.nodes.map { it.name })
        assertTrue(account.nodeKeys.isEmpty())
        assertFalse(account.canEditNode(0))
        try { f.manager.setNodeOverride(account.handle, "any", NodeOverride(hidden = true)); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(f.overrides.read().isEmpty())
    }

    @Test fun removingAConfigurationForgetsItsNodeEdits() = runTest {
        val f = Fixture(); f.local("Local Alpha", "Local Beta")
        val item = f.active()
        f.manager.setNodeOverride(item.handle, item.keyOf("Local Beta"), NodeOverride(alias = "常用"))
        assertEquals(1, f.overrides.read().size)
        f.manager.removeConfiguration(f.active().handle) { it() }
        assertTrue(f.overrides.read().isEmpty())
    }

    @Test fun editsAreScopedPerConfiguration() = runTest {
        val f = Fixture(); f.local("Same", "Other"); f.local("Same", "Third")
        val items = f.manager.configurationLibrarySnapshot(false).items
        assertEquals(2, items.size)
        val first = items.first { it.nodes.any { n -> n.name == "Other" } }
        f.manager.setNodeOverride(first.handle, first.keyOf("Same"), NodeOverride(alias = "只改这一份"))
        val after = f.manager.configurationLibrarySnapshot(false).items
        assertEquals(listOf("只改这一份", "Other"), after.first { it.handle.entryId == first.handle.entryId }.nodes.map { it.name })
        assertEquals(listOf("Same", "Third"), after.first { it.handle.entryId != first.handle.entryId }.nodes.map { it.name })
    }

    // ---- 已删除节点: list, restore, alias kept ----

    @Test fun aDeletedNodeIsListedAsDeletedAndOnlyRestorable() = runTest {
        val f = Fixture(); f.local("Local Alpha", "Local Beta")
        val item = f.active()
        f.manager.setNodeHidden(item.handle, item.keyOf("Local Beta"), true)
        val after = f.active()
        val position = after.nodes.indexOfFirst { it.name == "Local Beta" }
        assertEquals(setOf(position), after.nodeDeleted)
        assertTrue(after.canRestoreNode(position)); assertFalse(after.canEditNode(position))
        assertFalse("A visible node is not restorable", after.canRestoreNode(after.nodes.indexOfFirst { it.name == "Local Alpha" }))
        assertEquals(listOf("Local Alpha"), f.names())
    }

    @Test fun restoreBringsTheNodeBackSelectableAndClearsTheStoredFlag() = runTest {
        val f = Fixture(); f.local("Local Alpha", "Local Beta")
        val key = f.active().keyOf("Local Beta")
        f.manager.setNodeHidden(f.active().handle, key, true)
        f.manager.setNodeHidden(f.active().handle, key, false)
        assertTrue(f.active().nodeDeleted.isEmpty())
        assertEquals(listOf("Local Alpha", "Local Beta"), f.names())
        val back = f.manager.nodeSelectionSnapshot().choices.single { it.name == "Local Beta" }
        assertFalse(back.info)
        assertTrue(f.manager.selectNode(back.selection))
        assertTrue("Nothing is left over in the store", f.overrides.read().isEmpty())
    }

    @Test fun aLocalAliasSurvivesDeleteAndRestore() = runTest {
        val f = Fixture(); f.local("Local Alpha", "Local Beta")
        val key = f.active().keyOf("Local Beta")
        f.manager.setNodeOverride(f.active().handle, key, NodeOverride(alias = "常用线路"))
        f.manager.setNodeHidden(f.active().handle, key, true)
        // The deleted entry is shown under its alias, still unselectable.
        val deleted = f.active()
        assertEquals("常用线路", deleted.nodes[deleted.nodeDeleted.single()].name)
        assertEquals(listOf("Local Alpha"), f.names())
        f.manager.setNodeHidden(f.active().handle, key, false)
        assertEquals(listOf("Local Alpha", "常用线路"), f.names())
        assertEquals(1, f.active().nodeAliased.size)
    }

    @Test fun restoreDoesNotReselectTheNodeThatWasSelectedBeforeDeleting() = runTest {
        val f = Fixture(); f.local("Local Alpha", "Local Beta")
        val choice = f.manager.nodeSelectionSnapshot().choices.first { it.name == "Local Beta" }
        assertTrue(f.manager.selectNode(choice.selection))
        val key = f.active().keyOf("Local Beta")
        f.manager.setNodeHidden(f.active().handle, key, true)
        f.manager.setNodeHidden(f.active().handle, key, false)
        assertNull("The user picks again; restore never chooses for them", f.manager.nodeSelectionSnapshot().selected)
    }

    @Test fun deletedAndRestoredStateSurvivesRefreshAndRestart() = runTest {
        val f = Fixture(); f.remote()
        val gamma = f.active().keyOf("Remote Gamma")
        f.manager.setNodeHidden(f.active().handle, gamma, true)
        f.restart(); f.manager.refresh()
        assertFalse(f.names().contains("Remote Gamma"))
        assertEquals(1, f.active().nodeDeleted.size)
        f.manager.setNodeHidden(f.active().handle, gamma, false)
        f.restart(); f.manager.refresh()
        assertTrue(f.names().contains("Remote Gamma"))
        assertTrue(f.active().nodeDeleted.isEmpty())
    }

    @Test fun anInactiveSourceKeepsItsDeletedListAndCanRestoreFromIt() = runTest {
        val f = Fixture(); f.local("Local Alpha", "Local Beta"); f.local("Other One")
        val first = f.manager.configurationLibrarySnapshot(false).items.first { it.nodes.any { n -> n.name == "Local Beta" } }
        assertFalse(first.active)
        f.manager.setNodeHidden(first.handle, first.keyOf("Local Beta"), true)
        val deleted = f.manager.configurationLibrarySnapshot(false).items.first { it.handle.entryId == first.handle.entryId }
        assertEquals(1, deleted.nodeDeleted.size)
        f.manager.setNodeHidden(deleted.handle, deleted.keyOf("Local Beta"), false)
        assertTrue(f.manager.configurationLibrarySnapshot(false).items.all { it.nodeDeleted.isEmpty() })
    }

    @Test fun accountNodesNeverAppearAsDeletedAndCannotBeRestored() = runTest {
        val f = Fixture(); f.seedAccount(); f.local("Local Alpha")
        val account = f.manager.configurationLibrarySnapshot(true).items.single { it.kind == ManualSourceKind.LegacyRemote }
        assertTrue(account.nodeDeleted.isEmpty()); assertFalse(account.canRestoreNode(0))
        try { f.manager.setNodeHidden(account.handle, "any", false); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun controllerRestoreRefreshesTheListAndIgnoresNodesThatAreNotDeleted() = runTest {
        val f = Fixture(); f.local("Local Alpha", "Local Beta")
        var changed = 0
        val c = ConfigurationLibraryController(f.manager, this, { true }, { it() }, {},
            ioDispatcher = coroutineContext[kotlin.coroutines.ContinuationInterceptor] as kotlinx.coroutines.CoroutineDispatcher,
            accountSignedIn = { false }, nodesChanged = { changed++ })
        c.refresh(); advanceUntilIdle()
        val item = c.snapshot.items.single()
        val beta = item.nodes.indexOfFirst { it.name == "Local Beta" }
        c.restoreNode(item, beta); advanceUntilIdle()
        assertEquals("Restoring a visible node does nothing", 0, changed)
        c.deleteNode(item, beta); advanceUntilIdle()
        val deleted = c.snapshot.items.single()
        assertEquals(setOf(beta), deleted.nodeDeleted)
        c.restoreNode(deleted, beta); advanceUntilIdle()
        assertEquals(2, changed)
        assertTrue(c.snapshot.items.single().nodeDeleted.isEmpty())
        assertEquals(listOf("Local Alpha", "Local Beta"), c.snapshot.items.single().nodes.filterNot { it.info }.map { it.name })
    }
}
