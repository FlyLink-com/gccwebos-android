package com.gccwebos.android.portal

import com.gccwebos.android.canUseAccountSource
import com.gccwebos.android.core.ConnectionChoice
import com.gccwebos.android.core.ConnectionSelection
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Unified node sources: the signed-in account row, one-tap node choice across sources, source labels. Synthetic only. */
@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedNodesTest {
    private val now = Instant.parse("2026-10-10T00:00:00Z")
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
        val choices = MemoryNodeChoiceStore()
        val overrides = MemoryNodeOverrideStore()
        var response = yaml("Remote Alpha", "Remote Beta")
        var failDownload = false
        private val external = object : SubscriptionSource {
            override suspend fun download(url: Secret) = read(url).bytes
            override suspend fun read(url: Secret): SubscriptionRead {
                if (failDownload) throw PortalFailure(PortalFailure.Reason.NETWORK)
                return SubscriptionRead(response.copyOf(), now.plusSeconds(3600))
            }
        }
        val manager = LocalSubscriptionManager(object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = error("No account delivery in this fixture")
            override fun clear() = Unit
        }, profiles, SubscriptionSource { error("No official HTTP expected") },
            OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { now },
            LocalSubscriptionAuthority(), manualSources = store, externalSource = external, nodeChoices = choices, nodeOverrides = overrides)
        suspend fun local(vararg names: String) { val b = yaml(*names); try { manager.importManual(b) { it() } } finally { b.fill(0) } }
        suspend fun remote() {
            val b = "https://subscription.example/unified?token=synthetic-only".toByteArray()
            try { manager.importManual(b) { it() } } finally { b.fill(0) }
        }
        fun seedAccount() {
            val bytes = yaml("Account fixture")
            val source = Secret("https://sub.invalid/sub/a/unified-fixture")
            try { CachedSubscription(sessionBinding(source), bytes, now.plusSeconds(3600), source).use(profiles::save) }
            finally { bytes.fill(0) }
        }
        fun items(signedIn: Boolean = false) = manager.configurationLibrarySnapshot(signedIn).items
    }

    // ---- node matching ----

    private fun choice(name: String, protocol: String = "ss", info: Boolean = false) =
        ConnectionChoice(ConnectionSelection(), name, protocol, info)

    @Test fun matchesTheSamePositionWhenNameAndProtocolAgree() {
        val list = listOf(choice("A"), choice("B"), choice("C"))
        assertSame(list[1].selection, matchNodeTarget(list, NodeTarget(1, "B", "ss")))
    }

    @Test fun reorderedListFallsBackToTheSingleSameNameAndProtocol() {
        val list = listOf(choice("C"), choice("A"), choice("B"))
        assertSame(list[2].selection, matchNodeTarget(list, NodeTarget(1, "B", "ss")))
    }

    @Test fun ambiguousOrMissingOrChangedNodeNeverMatchesADifferentNode() {
        val duplicate = listOf(choice("Same"), choice("Same"))
        assertNull(matchNodeTarget(listOf(choice("X"), choice("Same"), choice("Same")), NodeTarget(0, "Same", "ss")))
        assertNull(matchNodeTarget(duplicate, NodeTarget(5, "Same", "ss")))
        assertNull(matchNodeTarget(listOf(choice("A")), NodeTarget(0, "Gone", "ss")))
        assertNull(matchNodeTarget(listOf(choice("A", "vmess")), NodeTarget(0, "A", "ss")))
        assertNull(matchNodeTarget(emptyList(), NodeTarget(0, "A", "ss")))
    }

    @Test fun providerNoticesAreNeverChosen() {
        val list = listOf(choice("剩余流量：10GB", info = true), choice("A"))
        assertNull(matchNodeTarget(list, NodeTarget(0, "剩余流量：10GB", "ss")))
        assertSame(list[1].selection, matchNodeTarget(list, NodeTarget(1, "A", "ss")))
    }

    // ---- signed-in account row (the "import first, log in later" bug) ----

    @Test fun signedInAccountAlwaysHasASelectableRowEvenAfterAnImportedConfigIsActive() = runTest {
        val f = Fixture(); f.local("Local Alpha")
        assertTrue("Without a sign-in nothing invents an account row", f.items(false).none { it.kind == ManualSourceKind.LegacyRemote })
        val account = f.items(true).single { it.kind == ManualSourceKind.LegacyRemote }
        assertNull(account.handle.entryId)
        assertFalse(account.active); assertTrue(account.nodes.isEmpty())
        assertEquals(LocalSubscriptionStatus.MISSING, account.status)
        assertEquals("待加载", configurationStatusLabel(account))
        assertEquals("0 条 · 账户订阅 · 待加载", groupCaption(account))
        // The imported configuration stays active and untouched.
        assertEquals("Local Alpha", f.items(true).single { it.active }.nodes.single().name)
    }

    @Test fun cachedAccountProfileShowsItsRealNodesAndNoPlaceholderIsAdded() = runTest {
        val f = Fixture(); f.seedAccount(); f.local("Local Alpha")
        val accounts = f.items(true).filter { it.kind == ManualSourceKind.LegacyRemote }
        assertEquals(1, accounts.size)
        assertEquals(listOf("Account fixture"), accounts.single().nodes.map { it.name })
        assertEquals("1 条 · 账户订阅 · 可用", groupCaption(accounts.single()))
    }

    @Test fun usingTheAccountSourceKeepsEveryImportedEntry() = runTest {
        val f = Fixture(); f.seedAccount(); f.local("Local Alpha"); f.local("Local Beta")
        f.manager.useAccountSource { it() }
        val items = f.items(true)
        assertEquals(ManualSourceKind.LegacyRemote, f.manager.sourceKind())
        assertEquals(setOf("Local Alpha", "Local Beta"),
            items.filter { it.kind == ManualSourceKind.ImportedLocal }.flatMap { it.nodes }.map { it.name }.toSet())
        assertTrue(items.single { it.kind == ManualSourceKind.LegacyRemote }.active)
    }

    @Test fun useAccountIsOfferedForAnySignedInNonAccountSourceOnly() {
        assertTrue(canUseAccountSource(true, ManualSourceKind.None))
        assertTrue(canUseAccountSource(true, ManualSourceKind.ImportedLocal))
        assertTrue(canUseAccountSource(true, ManualSourceKind.ImportedRemote))
        assertTrue(canUseAccountSource(true, ManualSourceKind.ExternalRemote))
        assertFalse(canUseAccountSource(true, ManualSourceKind.LegacyRemote))
        assertFalse(canUseAccountSource(false, ManualSourceKind.ImportedLocal))
    }

    // ---- one-tap node choice across sources ----

    private fun kotlinx.coroutines.test.TestScope.controller(f: Fixture, onSnapshot: (LocalSubscriptionSnapshot) -> Unit = {}) =
        ConfigurationLibraryController(f.manager, this, { true }, { it() }, onSnapshot,
            ioDispatcher = coroutineContext[kotlin.coroutines.ContinuationInterceptor] as kotlinx.coroutines.CoroutineDispatcher, accountSignedIn = { true },
            chooseNode = { target ->
                val match = matchNodeTarget(f.manager.nodeSelectionSnapshot().choices, target)
                match != null && f.manager.selectNode(match)
            })

    @Test fun tappingANodeOfAnInactiveSourceActivatesItAndSelectsThatExactNode() = runTest {
        val f = Fixture(); f.remote(); f.local("Local Beta")
        val c = controller(f); c.refresh(); advanceUntilIdle()
        val remote = c.snapshot.items.single { it.kind == ManualSourceKind.ExternalRemote }
        assertFalse(remote.active)
        val position = remote.nodes.indexOfFirst { it.name == "Remote Beta" }
        c.activateAndChoose(remote.handle, NodeTarget(position, "Remote Beta", "ss")); advanceUntilIdle()
        assertNull(c.error); assertFalse(c.busy)
        assertEquals(remote.handle.entryId, c.snapshot.items.single { it.active }.handle.entryId)
        val snapshot = f.manager.nodeSelectionSnapshot()
        val selected = snapshot.choices.single { it.selection === snapshot.selected }
        assertEquals("Remote Beta", selected.name)
        // The choice is remembered per source (PR #50 one-way key), so it survives a rebuild.
        assertTrue(f.choices.read().keys.any { it == "entry:${remote.handle.entryId}" })
    }

    @Test fun aNodeThatChangedDuringActivationAsksForANewChoiceInsteadOfPickingAnother() = runTest {
        val f = Fixture(); f.remote(); f.local("Local Beta")
        val c = controller(f); c.refresh(); advanceUntilIdle()
        val remote = c.snapshot.items.single { it.kind == ManualSourceKind.ExternalRemote }
        f.response = yaml("Remote Gamma", "Remote Alpha") // Beta disappeared in the fresh download
        c.activateAndChoose(remote.handle, NodeTarget(1, "Remote Beta", "ss")); advanceUntilIdle()
        assertEquals("节点已变化，请重新选择节点", c.error)
        assertEquals(remote.handle.entryId, c.snapshot.items.single { it.active }.handle.entryId)
        assertNull(f.manager.nodeSelectionSnapshot().selected)
    }

    @Test fun aFailedActivationKeepsTheActiveSourceAndSelectsNothing() = runTest {
        val f = Fixture(); f.remote(); f.local("Local Beta")
        val c = controller(f); c.refresh(); advanceUntilIdle()
        val remote = c.snapshot.items.single { it.kind == ManualSourceKind.ExternalRemote }
        val before = f.manager.nodeSelectionSnapshot().choices.single().selection
        f.failDownload = true
        c.activateAndChoose(remote.handle, NodeTarget(0, "Remote Alpha", "ss")); advanceUntilIdle()
        assertNotNull(c.error); assertNotEquals("节点已变化，请重新选择节点", c.error)
        assertEquals("Local Beta", c.snapshot.items.single { it.active }.nodes.single().name)
        assertSame(before, f.manager.nodeSelectionSnapshot().choices.single().selection)
    }

    @Test fun controllerListsTheSignedInAccountRowAfterEveryRefresh() = runTest {
        val f = Fixture(); f.local("Local Alpha")
        val c = controller(f); c.refresh(); advanceUntilIdle()
        assertEquals(1, c.snapshot.items.count { it.kind == ManualSourceKind.LegacyRemote })
    }

    // ---- automatic account load ----

    @Test fun accountLoadsItselfOnlyWhenItCannotDisplaceAChosenNode() = runTest {
        val f = Fixture(); f.local("Local Alpha")
        val items = f.items(true)
        assertNotNull("Nothing chosen: the signed-in account loads by itself", accountToAutoLoad(true, items, nodeChosen = false))
        assertNull("A node the user already chose is never displaced", accountToAutoLoad(true, items, nodeChosen = true))
        assertNull("Not while busy or connected", accountToAutoLoad(false, items, nodeChosen = false))
        assertNull("Signed out: no account row at all", accountToAutoLoad(true, f.items(false), nodeChosen = false))
    }

    @Test fun anAlreadyLoadedOrActiveAccountIsNotLoadedAgain() = runTest {
        val f = Fixture(); f.seedAccount(); f.local("Local Alpha")
        assertNull(accountToAutoLoad(true, f.items(true), nodeChosen = false))
        f.manager.useAccountSource { it() }
        assertNull(accountToAutoLoad(true, f.items(true), nodeChosen = false))
    }

    // ---- one list: labels, per-node edits through the controller ----

    @Test fun everySourceHasAReadableLabelAndTheAccountIsMarkedReadOnly() = runTest {
        val f = Fixture(); f.seedAccount(); f.remote(); f.local("Local Beta")
        val labels = f.items(true).associate { it.kind to nodeSourceLabel(it) }
        assertEquals("账户订阅 · 只读", labels[ManualSourceKind.LegacyRemote])
        assertTrue(labels.getValue(ManualSourceKind.ExternalRemote).endsWith("第三方订阅"))
        assertTrue(labels.getValue(ManualSourceKind.ImportedLocal).endsWith("本地文件"))
    }

    @Test fun renamingAndDeletingThroughTheControllerRefreshesTheListAndTheNodeSelection() = runTest {
        val f = Fixture(); f.remote(); f.local("Local Alpha", "Local Beta")
        var changed = 0
        val c = ConfigurationLibraryController(f.manager, this, { true }, { it() }, {},
            ioDispatcher = coroutineContext[kotlin.coroutines.ContinuationInterceptor] as kotlinx.coroutines.CoroutineDispatcher,
            accountSignedIn = { false }, nodesChanged = { changed++ },
            chooseNode = { target ->
                val match = matchNodeTarget(f.manager.nodeSelectionSnapshot().choices, target)
                match != null && f.manager.selectNode(match)
            })
        c.refresh(); advanceUntilIdle()
        val remote = c.snapshot.items.single { it.kind == ManualSourceKind.ExternalRemote }
        c.renameNode(remote, remote.nodes.indexOfFirst { it.name == "Remote Alpha" }, "我的东京"); advanceUntilIdle()
        assertNull(c.error); assertEquals(1, changed)
        val renamed = c.snapshot.items.single { it.kind == ManualSourceKind.ExternalRemote }
        assertEquals(setOf("我的东京", "Remote Beta"), renamed.nodes.map { it.name }.toSet())
        c.deleteNode(renamed, renamed.nodes.indexOfFirst { it.name == "Remote Beta" }); advanceUntilIdle()
        assertEquals(2, changed)
        val afterDelete = c.snapshot.items.single { it.kind == ManualSourceKind.ExternalRemote }
        assertEquals(listOf("我的东京"), afterDelete.nodes.filterNot { it.info }.map { it.name })
        // Tapping the renamed node still selects exactly it, by its displayed name.
        val position = afterDelete.nodes.indexOfFirst { it.name == "我的东京" }
        c.activateAndChoose(afterDelete.handle, NodeTarget(position, "我的东京", "ss")); advanceUntilIdle()
        assertNull(c.error)
        val snapshot = f.manager.nodeSelectionSnapshot()
        assertEquals("我的东京", snapshot.choices.single { it.selection === snapshot.selected }.name)
    }

    @Test fun theAccountSubscriptionRefusesNodeEditsThroughTheController() = runTest {
        val f = Fixture(); f.seedAccount(); f.local("Local Alpha")
        var changed = 0
        val c = ConfigurationLibraryController(f.manager, this, { true }, { it() }, {},
            ioDispatcher = coroutineContext[kotlin.coroutines.ContinuationInterceptor] as kotlinx.coroutines.CoroutineDispatcher,
            accountSignedIn = { true }, nodesChanged = { changed++ })
        c.refresh(); advanceUntilIdle()
        val account = c.snapshot.items.single { it.kind == ManualSourceKind.LegacyRemote }
        c.renameNode(account, 0, "x"); c.deleteNode(account, 0); advanceUntilIdle()
        assertEquals(0, changed); assertNull(c.error)
        assertEquals(listOf("Account fixture"), c.snapshot.items.single { it.kind == ManualSourceKind.LegacyRemote }.nodes.map { it.name })
    }
}
