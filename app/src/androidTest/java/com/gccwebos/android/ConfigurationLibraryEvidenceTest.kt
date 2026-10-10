package com.gccwebos.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.gccwebos.android.manualimport.ManualImportInputKind
import com.gccwebos.android.portal.*
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** PR #56 on a real device: synthetic multi-node configurations only (documentation addresses, no network, no account). */
class ConfigurationLibraryEvidenceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var account: AccountController
    private val httpCalls = AtomicInteger()
    private val store = ManualSourceRecord(kind = ManualSourceKind.None).use { MemoryManualSourceStore(it) }
    private val forbiddenSource = object : SubscriptionSource {
        override suspend fun download(url: Secret): ByteArray { httpCalls.incrementAndGet(); throw AssertionError("no download") }
    }
    private val profiles = MemoryLocalProfileStore()
    private val repository = PortalRepository(object : PortalTransport {
        override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
            httpCalls.incrementAndGet(); throw AssertionError("no portal access")
        }
    }, object : DeliveryStore {
        override fun read(): DeliveryRecord? = null
        override fun save(record: DeliveryRecord) = throw AssertionError("no delivery write")
        override fun clear() = throw AssertionError("no delivery clear")
    }, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L },
        { Instant.parse("2026-10-05T00:00:00Z") }, subscription = forbiddenSource,
        sessions = MemorySessionStore(), profiles = profiles, manualSources = store,
        externalSubscription = forbiddenSource, nodeChoices = MemoryNodeChoiceStore(), nodeOverrides = MemoryNodeOverrideStore())

    private fun yaml(vararg names: String, base: Int) = buildString {
        append("proxies:\n")
        names.forEachIndexed { i, n ->
            append("  - name: $n\n    type: ss\n    server: 192.0.2.${base + i}\n    port: 443\n    cipher: aes-128-gcm\n    password: synthetic-only\n")
        }
    }.toByteArray()

    private fun idle() = ::account.isInitialized && !account.busy && !account.restoring && !account.foregroundCheckPending &&
        !account.nodeSelection.busy && !account.manualImport.busy && !account.configurationLibrary.busy
    private fun awaitIdle() { compose.waitUntil(10_000) { idle() } }
    private fun awaitItems(n: Int) { compose.waitUntil(10_000) { idle() && account.configurationLibrary.snapshot.items.size == n } }

    private fun capture(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "ui-evidence/pr56")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use { stream ->
            val node = if (dialog) compose.onNode(isDialog()) else compose.onRoot()
            assertTrue(node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }

    /** Whole-screen capture (includes a dropdown popup, which a Compose root capture cannot see). */
    private fun captureScreen(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "ui-evidence/pr56")
        check(directory.isDirectory || directory.mkdirs())
        val shot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { assertTrue(shot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun importConfig(bytes: ByteArray, kind: ManualImportInputKind, count: Int) {
        awaitIdle()
        compose.onNodeWithTag("nodes-import").performScrollTo().performClick()
        compose.runOnIdle {
            val importer = account.manualImport
            importer.submit(requireNotNull(importer.begin()), bytes, kind)
        }
        awaitItems(count)
        // A new subscription asks for a name first; keep the default name ("跳过") so the list stays predictable.
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("configuration-dialog").fetchSemanticsNodes().isNotEmpty() }
        capture(if (count == 1) "02a-new-subscription-name-prompt" else "02b-new-subscription-name-prompt-2", dialog = true)
        compose.onNodeWithTag("configuration-dialog-cancel").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("configuration-dialog").fetchSemanticsNodes().isEmpty() }
        awaitIdle()
    }

    /** A cached account subscription, synthetic: shows read-only account nodes without any sign-in or network. */
    private fun seedAccountNodes() {
        val bytes = yaml("账户 东京 01", "账户 洛杉矶 01", base = 70)
        val source = Secret("https://sub.invalid/sub/a/evidence-fixture")
        try { CachedSubscription(sessionBinding(source), bytes, Instant.parse("2026-12-01T00:00:00Z"), source).use(profiles::save) }
        finally { bytes.fill(0) }
    }

    @Test fun unifiedNodePageOnDevice() {
        seedAccountNodes()
        compose.setContent {
            val scope = rememberCoroutineScope()
            account = remember { AccountController(repository, scope, sourceCommitFence = { action -> action() }) }
            LaunchedEffect(account) { account.restore() }
            AppShell(accountOverride = account, repositoryOverride = repository, autoLoadCommerce = false, autoRefreshPages = false,
                connectionControlState = ConnectionControlState(ConnectionPhase.Disconnected, canConnect = true),
                onConnectionPowerAction = {})
        }
        awaitIdle()
        compose.onNodeWithTag("nav-Lines").performClick()
        awaitItems(1)
        capture("01-account-nodes-only-read-only")
        importConfig(yaml("东京 01", "东京 02", "新加坡 01", base = 10), ManualImportInputKind.Scan, 2)
        importConfig(yaml("香港 A", "美国 洛杉矶", base = 40), ManualImportInputKind.Document, 3)
        compose.onNodeWithTag("configuration-library").performScrollTo()
        capture("02-one-list-three-sources-with-source-labels")
        // Account nodes carry no edit menu; imported nodes do.
        compose.onNodeWithTag("configuration-node-0-0").performScrollTo().assertExists()
        compose.onNodeWithTag("node-menu-0-0").assertDoesNotExist()
        compose.onNodeWithTag("node-menu-1-1").performScrollTo().assertExists()
        capture("03-account-nodes-read-only-imported-nodes-have-menu")
        // Filter chip narrows to one source.
        compose.onNodeWithTag("unified-filter").performScrollTo()
        compose.onNodeWithTag("unified-filter-1").performClick()
        compose.onNodeWithTag("configuration-library").performScrollTo()
        capture("04-filter-one-source")
        compose.onNodeWithTag("unified-filter-all").performClick()
        // One tap on a node of another (inactive) source: that source becomes active and exactly this node is selected.
        compose.onNodeWithTag("configuration-node-1-1").performScrollTo().assertHasClickAction().performClick()
        compose.waitUntil(10_000) { idle() && account.configurationLibrary.snapshot.items.any { it.active && it.name == "配置 1" } }
        compose.runOnIdle {
            assertNull(account.configurationLibrary.error)
            assertNotNull("the tapped node must be the selected one", account.nodeSelection.snapshot.selected)
            val snapshot = account.nodeSelection.snapshot
            assertEquals("东京 02", snapshot.choices.single { it.selection === snapshot.selected }.name)
        }
        compose.onNodeWithTag("nodes-group-title", useUnmergedTree = true).performScrollTo()
        capture("05-one-tap-selected-node-source-switched")
        // Per-node menu on the (now active) imported source.
        compose.onNodeWithTag("node-menu-active-2").performScrollTo().performClick()
        compose.onNodeWithTag("node-rename-active-2").assertExists()
        compose.onNodeWithTag("node-delete-active-2").assertExists()
        captureScreen("06-per-node-menu-rename-delete")
        compose.onNodeWithTag("node-rename-active-2").performClick()
        compose.onNodeWithTag("node-name-input").performTextClearance()
        compose.onNodeWithTag("node-name-input").performTextInput("新加坡 常用")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("node-rename-active-2").fetchSemanticsNodes().isEmpty() }
        capture("07-rename-node-dialog", dialog = true)
        compose.onNodeWithTag("node-dialog-confirm").performClick()
        awaitIdle()
        compose.waitUntil(10_000) { account.nodeSelection.snapshot.choices.any { it.name == "新加坡 常用" } }
        compose.onNodeWithTag("nodes-group-title", useUnmergedTree = true).performScrollTo()
        capture("08-after-node-rename-local-alias")
        // Delete the selected node: confirm first, then it is gone and no node is selected.
        compose.onNodeWithTag("node-menu-active-1").performScrollTo().performClick()
        compose.onNodeWithTag("node-delete-active-1").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("node-delete-active-1").fetchSemanticsNodes().isEmpty() }
        capture("09-delete-node-confirm-dialog", dialog = true)
        compose.onNodeWithTag("node-dialog-confirm").performClick()
        awaitIdle()
        compose.waitUntil(10_000) { account.nodeSelection.snapshot.choices.none { !it.info && it.name == "东京 02" } }
        compose.runOnIdle {
            assertNull("deleting the selected node leaves nothing selected", account.nodeSelection.snapshot.selected)
        }
        compose.onNodeWithTag("nodes-group-title", useUnmergedTree = true).performScrollTo()
        capture("10-after-deleting-selected-node")
        // Delete a node of another source from its row.
        compose.onNodeWithTag("node-menu-2-0").performScrollTo().performClick()
        compose.onNodeWithTag("node-delete-2-0").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("node-delete-2-0").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("node-dialog-confirm").performClick()
        awaitIdle()
        compose.waitUntil(10_000) { account.configurationLibrary.snapshot.items.last().nodes.none { !it.info && it.name == "香港 A" } }
        compose.onNodeWithTag("configuration-library").performScrollTo()
        capture("11-after-deleting-node-of-another-source")
        // 已删除节点: collapsed at the bottom, restore only.
        compose.onNodeWithTag("unified-deleted").performScrollTo().assertIsDisplayed()
        capture("12-deleted-section-collapsed-at-bottom")
        compose.onNodeWithTag("unified-deleted-toggle").performClick()
        compose.onNodeWithTag("deleted-node-1-1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("deleted-node-2-0").performScrollTo().assertIsDisplayed()
        capture("13-deleted-section-expanded")
        compose.onNodeWithTag("deleted-menu-1-1").performScrollTo().performClick()
        compose.onNodeWithTag("deleted-restore-1-1").assertExists()
        captureScreen("14-deleted-node-menu-restore-only")
        compose.onNodeWithTag("deleted-restore-1-1").performClick()
        awaitIdle()
        compose.waitUntil(10_000) { account.nodeSelection.snapshot.choices.any { !it.info && it.name == "东京 02" } }
        compose.onNodeWithTag("nodes-group-title", useUnmergedTree = true).performScrollTo()
        capture("15-after-restore-node-back-in-list-unselected")
        compose.runOnIdle {
            assertNull("restore never re-selects", account.nodeSelection.snapshot.selected)
            assertEquals(0, httpCalls.get())
        }
    }
}
