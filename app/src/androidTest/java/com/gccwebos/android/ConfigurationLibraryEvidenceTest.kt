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
        sessions = MemorySessionStore(), profiles = MemoryLocalProfileStore(), manualSources = store,
        externalSubscription = forbiddenSource)

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

    @Test fun unifiedNodePageOnDevice() {
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
        awaitItems(0)
        capture("01-node-page-empty-signed-out")
        importConfig(yaml("东京 01", "东京 02", "新加坡 01", base = 10), ManualImportInputKind.Scan, 1)
        importConfig(yaml("香港 A", "美国 洛杉矶", base = 40), ManualImportInputKind.Document, 2)
        compose.onNodeWithTag("configuration-library").performScrollTo()
        capture("02-two-sources-source-labels")
        // Open the inactive source: its nodes are listed and directly tappable (one tap = choose this node).
        compose.onNodeWithTag("configuration-toggle-0").performScrollTo().performClick()
        compose.onNodeWithTag("configuration-node-0-1").performScrollTo().assertHasClickAction()
        capture("03-inactive-source-expanded-nodes-tappable")
        compose.onNodeWithTag("configuration-node-0-1").performClick()
        compose.waitUntil(10_000) { idle() && account.configurationLibrary.snapshot.items.any { it.active && it.name == "配置 1" } }
        compose.runOnIdle {
            assertNull(account.configurationLibrary.error)
            assertNotNull("the tapped node must be the selected one", account.nodeSelection.snapshot.selected)
        }
        compose.onNodeWithTag("nodes-group-title", useUnmergedTree = true).assertTextEquals("配置 1")
        compose.onNodeWithTag("nodes-group-title", useUnmergedTree = true).performScrollTo()
        capture("04-after-tapping-node-source-switched-node-selected")
        // Configuration-level menu: rename / delete exist only for the whole imported configuration.
        compose.onNodeWithTag("configuration-menu-1").performScrollTo().performClick()
        compose.onNodeWithTag("configuration-rename-1").assertExists()
        compose.onNodeWithTag("configuration-remove-1").assertExists()
        captureScreen("05-imported-source-menu-rename-delete")
        compose.onNodeWithTag("configuration-rename-1").performClick()
        compose.onNodeWithTag("configuration-name-input").performTextClearance()
        compose.onNodeWithTag("configuration-name-input").performTextInput("我的备用线路")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("configuration-rename-1").fetchSemanticsNodes().isEmpty() }
        capture("06-rename-dialog", dialog = true)
        compose.onNodeWithTag("configuration-dialog-confirm").performClick()
        awaitIdle()
        compose.waitUntil(10_000) { account.configurationLibrary.snapshot.items.any { it.name == "我的备用线路" } }
        capture("07-after-rename")
        compose.onNodeWithTag("configuration-menu-1").performScrollTo().performClick()
        compose.onNodeWithTag("configuration-remove-1").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("configuration-remove-1").fetchSemanticsNodes().isEmpty() }
        capture("08-delete-confirm-dialog", dialog = true)
        compose.onNodeWithTag("configuration-dialog-confirm").performClick()
        awaitItems(1)
        capture("09-after-delete")
        compose.runOnIdle { assertEquals(0, httpCalls.get()) }
    }
}
