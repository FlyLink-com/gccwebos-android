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

/** Real importer/manager/store/controller/AppShell path; only owned in-memory local YAML.
 * Platform callbacks are simulated at submit: no camera, picker, real file, HTTP or VPN. */
class ConfigurationLibraryFlowUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var account: AccountController
    private val httpCalls = AtomicInteger()
    private val commits = AtomicInteger()
    private val powers = mutableListOf<ConnectionPowerAction>()
    private val store = ManualSourceRecord(kind = ManualSourceKind.None).use { MemoryManualSourceStore(it) }
    private val profiles = MemoryLocalProfileStore()
    private val forbiddenSource = object : SubscriptionSource {
        override suspend fun download(url: Secret): ByteArray {
            httpCalls.incrementAndGet(); throw AssertionError("Local library fixture must never download")
        }
    }
    private val repository = PortalRepository(object : PortalTransport {
        override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
            httpCalls.incrementAndGet(); throw AssertionError("Anonymous local fixture must never access Portal")
        }
    }, object : DeliveryStore {
        override fun read(): DeliveryRecord? = null
        override fun save(record: DeliveryRecord) = throw AssertionError("No account delivery writes")
        override fun clear() = throw AssertionError("No account delivery clears")
    }, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L },
        { Instant.parse("2026-10-05T00:00:00Z") }, subscription = forbiddenSource,
        sessions = MemorySessionStore(), profiles = profiles, manualSources = store,
        externalSubscription = forbiddenSource)

    private fun yaml(name: String, endpoint: Int = 10) = """proxies:
  - name: $name
    type: ss
    server: 192.0.2.$endpoint
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""".toByteArray()

    private fun launch() {
        compose.setContent {
            val scope = rememberCoroutineScope()
            account = remember { AccountController(repository, scope, sourceCommitFence = { action ->
                action(); commits.incrementAndGet()
            }) }
            LaunchedEffect(account) { account.restore() }
            AppShell(accountOverride = account, repositoryOverride = repository, autoLoadCommerce = false,
                autoRefreshPages = false,
                connectionControlState = ConnectionControlState(ConnectionPhase.Disconnected, canConnect = true),
                onConnectionPowerAction = { powers += it })
        }
        awaitIdle()
        compose.onNodeWithTag("nav-Lines").performClick()
        awaitItems(0)
    }

    private fun idle() = ::account.isInitialized && !account.busy && !account.restoring &&
        !account.foregroundCheckPending && !account.nodeSelection.busy &&
        !account.manualImport.busy && !account.configurationLibrary.busy

    private fun awaitIdle() { compose.waitUntil(10_000) { idle() } }
    private fun awaitItems(count: Int) {
        compose.waitUntil(10_000) { idle() && account.configurationLibrary.snapshot.items.size == count }
    }

    private fun submitNew(name: String, kind: ManualImportInputKind, count: Int) {
        awaitIdle()
        compose.onNodeWithTag("nodes-import").performScrollTo().assertIsEnabled().performClick()
        submitPlatformReturn(yaml(name, count + 10), kind)
        awaitItems(count)
        compose.onNodeWithTag("manual-import-host").assertDoesNotExist()
    }

    private fun submitPlatformReturn(bytes: ByteArray, kind: ManualImportInputKind) {
        compose.runOnIdle {
            val importer = account.manualImport
            assertTrue(importer.visible)
            importer.submit(requireNotNull(importer.begin()), bytes, kind)
            assertTrue("Importer must take and wipe the caller's payload", bytes.all { it == 0.toByte() })
        }
    }

    private fun openReplacement(index: Int) {
        awaitIdle()
        compose.onNodeWithTag("configuration-menu-$index").performScrollTo().performClick()
        compose.onNodeWithTag("configuration-replace-$index").performClick()
        compose.onNodeWithTag("manual-import-host").assertExists()
    }

    private fun storedNodeNames(): Map<String, String> = requireNotNull(store.readLibrary()).use { library ->
        library.entries.associate { entry -> entry.id to entry.copySource().use { source ->
            val bytes = requireNotNull(source.copyBytes())
            try { com.gccwebos.android.vpn.SafeProxyConfig.inspect(bytes).single().name }
            finally { bytes.fill(0) }
        } }
    }

    private fun assertNoExternalEffects() = compose.runOnIdle {
        assertFalse(account.authenticated)
        assertEquals(0, httpCalls.get()); assertTrue(powers.isEmpty()); assertNull(profiles.read())
    }

    @Test fun scanThenDocumentAppendTwoSavedConfigurationsAndBothRemainInTheRealNodePage() {
        launch()
        submitNew("Synthetic Alpha", ManualImportInputKind.Scan, 1)
        val first = storedNodeNames().entries.single()
        submitNew("Synthetic Beta", ManualImportInputKind.Document, 2)
        val stored = storedNodeNames()
        assertEquals(2, stored.size)
        assertEquals("Synthetic Alpha", stored[first.key])
        assertEquals(setOf("Synthetic Alpha", "Synthetic Beta"), stored.values.toSet())
        compose.runOnIdle {
            val items = account.configurationLibrary.snapshot.items
            assertEquals(listOf("配置 1", "配置 2"), items.map { it.name })
            assertFalse(items[0].active); assertTrue(items[1].active)
            assertEquals(2, commits.get())
        }
        compose.onNodeWithTag("nodes-group-title", useUnmergedTree = true).assertTextEquals("配置 2")
        compose.onNodeWithTag("configuration-name-0", useUnmergedTree = true).assertTextEquals("配置 1")
        compose.onNodeWithTag("configuration-toggle-0").performScrollTo().performClick()
        compose.onNodeWithTag("configuration-node-0-0").performScrollTo().assertHasNoClickAction()
        compose.onNodeWithText("Synthetic Alpha", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Synthetic Beta", useUnmergedTree = true).assertExists()
        assertNoExternalEffects()
        compose.onNodeWithTag("nodes-import").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("nodes-latency-refresh").assertIsDisplayed()
        compose.onNodeWithTag("nodes-configuration-group").assertIsDisplayed()
        compose.onNodeWithTag("configuration-group-0").assertIsDisplayed()
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "ui-evidence/configuration-library")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "two-configurations.png").outputStream().use { stream ->
            assertTrue(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }

    @Test fun explicitReplacementChangesOnlyItsTargetAndInvalidReplacementKeepsBothSavedEntries() {
        launch()
        submitNew("Synthetic Alpha", ManualImportInputKind.Document, 1)
        submitNew("Synthetic Beta", ManualImportInputKind.Scan, 2)
        val before = storedNodeNames()
        val targetId = before.entries.single { it.value == "Synthetic Alpha" }.key
        val otherId = before.entries.single { it.value == "Synthetic Beta" }.key
        openReplacement(0)
        submitPlatformReturn(yaml("Synthetic Alpha Updated", 12), ManualImportInputKind.Document)
        compose.waitUntil(10_000) { idle() && !account.manualImport.visible &&
            account.configurationLibrary.snapshot.items[0].nodes.single().name == "Synthetic Alpha Updated" }
        val updated = storedNodeNames()
        assertEquals(before.keys, updated.keys)
        assertEquals("Synthetic Alpha Updated", updated[targetId])
        assertEquals("Synthetic Beta", updated[otherId])
        compose.runOnIdle { assertEquals(3, commits.get()) }
        openReplacement(1)
        submitPlatformReturn("synthetic invalid configuration".toByteArray(), ManualImportInputKind.Scan)
        compose.waitUntil(10_000) { idle() && account.manualImport.error != null }
        assertEquals(updated, storedNodeNames())
        compose.runOnIdle {
            assertEquals(2, account.configurationLibrary.snapshot.items.size)
            assertEquals(3, commits.get())
            account.manualImport.cancel()
        }
        awaitIdle()
        compose.onNodeWithTag("nodes-group-title", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("configuration-group-0").assertExists()
        assertNoExternalEffects()
    }
}
