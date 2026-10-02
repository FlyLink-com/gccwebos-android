package com.gccwebos.android

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.gccwebos.android.core.ConnectionSelection
import com.gccwebos.android.portal.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Unmodified MainActivity and its default account/repository restore a synthetic encrypted cache.
 * No Activity content replacement or restore call manufactures a post-recreation result. Test-side
 * managers only inspect the legitimate process authority after recreation. This is configuration
 * recreation, not process death or a production Portal E2E test. Only Connect/Nodes/radio are used:
 * a valid cached profile and absent session/delivery keep both production HTTP paths unreachable.
 * Run only on a fresh isolated candidate, never against a user's installed account/configuration.
 */
class SelectedNodeActivityRecreationTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var profiles: EncryptedLocalProfileStore
    private lateinit var inspector: LocalSubscriptionManager
    private val url = Secret("https://sub.gccwebos.com/sub/a/activity-recreation-fixture")
    private val expiry = Instant.now().plusSeconds(3600)
    private val subscriptionReads = AtomicInteger()
    private val policy = OriginPolicy("https://portal.gccwebos.com", setOf("https://sub.gccwebos.com"))
    private val delivery = object : DeliveryStore {
        override fun read(): DeliveryRecord? = null
        override fun save(record: DeliveryRecord) = error("No delivery writes in recreation fixture")
        override fun clear() = Unit
    }
    private val bytes = """proxies:
  - name: 同名节点
    type: ss
    server: 192.0.2.40
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
  - name: 同名节点
    type: ss
    server: 192.0.2.41
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""".toByteArray()

    private fun manager() = LocalSubscriptionManager(delivery, profiles,
        SubscriptionSource {
            subscriptionReads.incrementAndGet()
            error("Inspector must never download a subscription")
        }, policy, { Instant.now() })

    private val fixtureRule = object : TestRule {
        override fun apply(base: Statement, description: Description) = object : Statement() {
            override fun evaluate() {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val context = instrumentation.targetContext
                // Check before the Activity rule can launch the default production content.
                // Never delete or overwrite an existing encrypted account/profile/delivery.
                listOf("portal-session.bin", "portal-profile.bin", "portal-delivery.bin").forEach { name ->
                    listOf("", ".bak", ".new").forEach { suffix ->
                        assertFalse("Requires a fresh candidate without private stores: $name$suffix",
                            File(context.noBackupFilesDir, name + suffix).exists())
                    }
                }
                // All guards passed before ownership or any typed clear/write is permitted.
                profiles = EncryptedLocalProfileStore(context)
                val seed = manager()
                inspector = seed
                var writeStarted = false
                try {
                    instrumentation.runOnMainSync {
                        writeStarted = true
                        seed.unlink() // Fresh guarded fixture only; reset prior suite registry state.
                        CachedSubscription(sessionBinding(url), bytes, expiry, url).use { profiles.save(it) }
                        assertEquals(LocalSubscriptionStatus.READY, seed.remember(bytes, url, expiry).status)
                        assertEquals(LocalSubscriptionStatus.READY, runBlocking { seed.restore() }.status)
                    }
                    base.evaluate() // Default MainActivity creates/restores its own real owners.
                    assertEquals("Inspector subscription source was never used", 0, subscriptionReads.get())
                    assertPrivateAccountStoresAbsent()
                } finally {
                    // Inner Compose/Activity rule has already disposed and destroyed its Activity.
                    // A guard failure never reaches this block and never clears an existing store.
                    if (writeStarted) instrumentation.runOnMainSync { seed.unlink() }
                }
            }
        }
    }

    @get:Rule val rules: TestRule = RuleChain.outerRule(fixtureRule).around(compose)

    private fun assertPrivateAccountStoresAbsent() {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir
        listOf("portal-session.bin", "portal-delivery.bin").forEach { name ->
            listOf("", ".bak", ".new").forEach { suffix ->
                assertFalse("No account or delivery created: $name$suffix", File(directory, name + suffix).exists())
            }
        }
    }

    private fun waitForReadyNodes() {
        try {
            compose.waitUntil(5000) {
                inspector.nodeSelectionSnapshot().let { it.status == LocalSubscriptionStatus.READY && it.choices.size == 2 }
            }
            compose.waitForIdle()
        } catch (failure: Throwable) {
            val state = inspector.nodeSelectionSnapshot()
            throw AssertionError("Registry status=${state.status}, choices=${state.choices.size}, selected=${state.selected != null}", failure)
        }
    }

    private fun selectSecondAndNavigate(): ConnectionSelection {
        waitForReadyNodes()
        compose.onNodeWithTag("nav-Lines").performClick()
        compose.onNodeWithTag("nodes-refresh").assertDoesNotExist()
        compose.onNodeWithTag("nodes-account").assertDoesNotExist()
        val selected = inspector.nodeSelectionSnapshot().choices[1].selection
        compose.onNodeWithTag("node-choice-1").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("node-choice-0").assertIsNotSelected()
        repeat(2) {
            compose.onNodeWithTag("nav-Connect").performClick()
            assertSelectedConnection()
            compose.onNodeWithTag("nav-Lines").performClick()
            compose.onNodeWithTag("node-choice-1").assertIsSelected()
            compose.onNodeWithTag("node-choice-0").assertIsNotSelected()
        }
        compose.runOnIdle { assertSame(selected, inspector.nodeSelectionSnapshot().selected) }
        return selected
    }

    private fun assertSelectedConnection() {
        compose.onNodeWithTag("connection-node-name", useUnmergedTree = true).assertTextEquals("同名节点")
        compose.onNodeWithTag("connection-node-protocol", useUnmergedTree = true).assertTextEquals("SS")
        compose.onNodeWithTag("local-subscription-title").assertTextEquals("连接暂不可用")
        // This signed-out fixture exposes registration guidance, never a VPN command.
        compose.onNodeWithTag("vpn-connect").assertTextEquals("开通飞连vpn").assertIsEnabled()
        compose.onNodeWithTag("connection-power").assertDoesNotExist()
        compose.onNodeWithTag("connection-status").assertTextEquals("未连接")
    }

    private fun recreateDefaultActivityAndVerify() {
        val oldActivity = compose.activity
        val oldInspector = inspector
        compose.activityRule.scenario.recreate()
        // Constructor/observations only. No restore/remember/refresh/select manufactures recovery.
        inspector = manager()
        assertNotSame(oldInspector, inspector)
        waitForReadyNodes()
        compose.activityRule.scenario.onActivity { current ->
            assertNotSame(oldActivity, current)
            assertEquals(Lifecycle.State.DESTROYED, oldActivity.lifecycle.currentState)
        }
        // Nodes is a non-default tab: this asserts restored saved state, not an accidental default.
        listOf("Connect", "Lines", "Plans", "Profile").forEach {
            compose.onNodeWithTag("nav-$it").assertExists()
        }
        compose.onNodeWithTag("nav-Orders").assertDoesNotExist()
        compose.onNodeWithTag("page-Lines").assertExists()
        compose.onNodeWithTag("nav-Lines").assertIsSelected()
        compose.onNodeWithTag("nodes-refresh").assertDoesNotExist()
        compose.onNodeWithTag("nodes-account").assertDoesNotExist()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-evidence")
        check(directory.isDirectory || directory.mkdirs())
        val file = File(directory, "$name.png")
        check(!file.exists()) { "Never reuse a previous screenshot" }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            check(bitmap.width > 0 && bitmap.height > 0)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            check(file.length() > 8)
            val signature = file.inputStream().use { input -> ByteArray(8).also { check(input.read(it) == 8) } }
            check(signature.contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)))
        } finally { bitmap.recycle() }
    }

    @Test fun selectedSameNameSecondNodeSurvivesRealActivityRecreationWithTheSameOpaqueIdentity() {
        val selected = selectSecondAndNavigate()
        screenshot("selected-node-before-real-activity-recreation")
        recreateDefaultActivityAndVerify()
        compose.runOnIdle {
            assertSame(selected, inspector.nodeSelectionSnapshot().selected)
            assertSame(selected, inspector.nodeSelectionSnapshot().choices[1].selection)
            assertNotSame(selected, inspector.nodeSelectionSnapshot().choices[0].selection)
        }
        compose.onNodeWithTag("node-choice-1").assertIsSelected()
        compose.onNodeWithTag("node-choice-0").assertIsNotSelected()
        screenshot("selected-node-after-real-activity-recreation")
        compose.onNodeWithTag("nav-Connect").performClick()
        assertSelectedConnection()
        screenshot("selected-node-connection-after-real-activity-recreation")
    }

    @Test fun changedSameNameCachedContentClearsSelectionDuringRealActivityRecreationAndRejectsOldOpaque() {
        val old = selectSecondAndNavigate()
        compose.runOnIdle {
            val cached = requireNotNull(profiles.read())
            try {
                val raw = cached.copyBytes()
                val changed = try { raw.toString(Charsets.UTF_8).replace("192.0.2.41", "192.0.2.42").toByteArray() }
                    finally { raw.fill(0) }
                val replacement = CachedSubscription(cached.binding, changed, cached.validUntil, cached.url)
                try { profiles.save(replacement) } finally { replacement.close(); changed.fill(0) }
            } finally { cached.close() }
            assertSame("Cache write alone must not clear the selected opaque handle", old, inspector.nodeSelectionSnapshot().selected)
        }
        recreateDefaultActivityAndVerify()
        compose.runOnIdle {
            val snapshot = inspector.nodeSelectionSnapshot()
            assertNull(snapshot.selected)
            assertEquals(listOf("同名节点", "同名节点"), snapshot.choices.map { it.name })
            assertTrue(snapshot.choices.none { it.selection === old })
            assertFalse(inspector.selectNode(old))
            assertNull(inspector.nodeSelectionSnapshot().selected)
        }
        compose.onNodeWithTag("node-choice-0").assertIsNotSelected()
        compose.onNodeWithTag("node-choice-1").assertIsNotSelected()
        compose.onNodeWithTag("node-selection-status").assertTextEquals("请选择节点")
        screenshot("changed-cache-after-real-activity-recreation-nodes-unselected")
        compose.onNodeWithTag("nav-Connect").performClick()
        compose.onNodeWithTag("connection-node-name", useUnmergedTree = true).assertTextEquals("未选择")
        compose.onNodeWithTag("connection-node-protocol", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("local-subscription-title").assertTextEquals("连接暂不可用")
        compose.onNodeWithTag("vpn-connect").assertTextEquals("开通飞连vpn").assertIsEnabled()
        compose.onNodeWithTag("connection-status").assertTextEquals("未连接")
        compose.onNodeWithTag("connection-power").assertDoesNotExist()
        screenshot("changed-cache-after-real-activity-recreation-unselected")
        compose.onNodeWithTag("nav-Lines").performClick()
        compose.onNodeWithTag("node-choice-1").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("node-choice-0").assertIsNotSelected()
        compose.runOnIdle {
            assertSame(inspector.nodeSelectionSnapshot().choices[1].selection, inspector.nodeSelectionSnapshot().selected)
            assertNotSame(old, inspector.nodeSelectionSnapshot().selected)
        }
    }
}
