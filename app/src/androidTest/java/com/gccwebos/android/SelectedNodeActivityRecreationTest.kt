package com.gccwebos.android

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import com.gccwebos.android.core.ConnectionSelection
import com.gccwebos.android.portal.*
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Real MainActivity instances and saved state, with a fresh fake account/repository per Activity.
 * The test replaces only the Activity's Compose content; it is not a production Portal E2E test.
 * The legitimate process authority is shared, just as in production. Cached bytes are synthetic,
 * memory-backed, and both transports reject every request. This does not simulate process death.
 * Run only on a fresh isolated candidate, never against a user's installed account/configuration.
 */
@SdkSuppress(minSdkVersion = 29) // onActivityPostCreated; the evidence lanes are API33/API36.
class SelectedNodeActivityRecreationTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    private val profiles = MemoryLocalProfileStore()
    private val url = Secret("https://sub.invalid/sub/a/activity-recreation-fixture")
    private val expiry = Instant.now().plusSeconds(3600)
    private val portalRequests = AtomicInteger()
    private val subscriptionReads = AtomicInteger()
    private val owners = CopyOnWriteArrayList<AccountController>()
    private val repositories = CopyOnWriteArrayList<PortalRepository>()
    private var deliveryPresent = true
    private val delivery = object : DeliveryStore {
        override fun read() = if (deliveryPresent) DeliveryRecord("fixture", "fixture", "fixture", "fixture", url, null, false) else null
        override fun save(record: DeliveryRecord) = error("No delivery writes in recreation fixture")
        override fun clear() { deliveryPresent = false }
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

    private fun repository() = PortalRepository(object : PortalTransport {
        override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
            portalRequests.incrementAndGet()
            error("Activity recreation fixture forbids every Portal request")
        }
    }, delivery, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L },
        subscription = SubscriptionSource {
            subscriptionReads.incrementAndGet()
            error("Cached Activity recreation must never download a subscription")
        }, sessions = MemorySessionStore(), profiles = profiles)

    private fun account(scope: CoroutineScope): AccountController {
        val repository = repository()
        repositories += repository
        return AccountController(repository, scope).also { owners += it }
    }

    // Both initial and recreated Activities use exactly this content call site for saved-state keys.
    // PostCreated is deliberately used: MainActivity's own onCreate must finish before replacement.
    private fun installFixture(activity: MainActivity) {
        activity.setContent {
            val scope = rememberCoroutineScope()
            val owner = remember { account(scope) }
            LaunchedEffect(owner) { owner.restore() }
            AppShell(owner, autoLoadCommerce = false)
        }
    }

    private val fixtureRule = object : TestRule {
        override fun apply(base: Statement, description: Description) = object : Statement() {
            override fun evaluate() {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                // Check before the Activity rule can launch the default production content.
                // Never delete or overwrite an existing encrypted account/profile/delivery.
                listOf("portal-session.bin", "portal-profile.bin", "portal-delivery.bin").forEach { name ->
                    listOf("", ".bak", ".new").forEach { suffix ->
                        assertFalse("Requires a fresh candidate without private stores: $name$suffix",
                            File(context.noBackupFilesDir, name + suffix).exists())
                    }
                }
                val application = context.applicationContext as Application
                val seed = repository()
                val callbacks = lifecycleCallbacks()
                var registered = false
                try {
                    seed.localSubscriptions.unlink() // Only memory fixture/process registry; no user stores.
                    deliveryPresent = true
                    assertEquals(LocalSubscriptionStatus.READY,
                        seed.localSubscriptions.remember(bytes, url, expiry).status)
                    application.registerActivityLifecycleCallbacks(callbacks)
                    registered = true
                    base.evaluate() // The same postCreated callback installs both Activity contents.
                    assertEquals("Fake Portal made no authentication/commerce calls", 0, portalRequests.get())
                    assertEquals("Fake subscription source was never used", 0, subscriptionReads.get())
                } finally {
                    // Inner Compose/Activity rule has already disposed and destroyed its owner.
                    if (registered) application.unregisterActivityLifecycleCallbacks(callbacks)
                    seed.localSubscriptions.unlink()
                }
            }
        }
    }

    @get:Rule val rules: TestRule = RuleChain.outerRule(fixtureRule).around(compose)

    private fun lifecycleCallbacks() = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is MainActivity) installFixture(activity)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private fun waitForRestoredOwner(count: Int) {
        compose.waitUntil(5000) {
            owners.size == count && !owners.last().restoring &&
                owners.last().localSubscription.status == LocalSubscriptionStatus.READY &&
                owners.last().nodeSelection.snapshot.choices.size == 2
        }
        compose.waitForIdle()
    }

    private fun selectSecondAndNavigate(): ConnectionSelection {
        waitForRestoredOwner(1)
        compose.onNodeWithTag("nav-Lines").performClick()
        val selected = owners.last().nodeSelection.snapshot.choices[1].selection
        compose.onNodeWithTag("node-choice-1").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("node-choice-0").assertIsNotSelected()
        repeat(2) {
            compose.onNodeWithTag("nav-Connect").performClick()
            assertSelectedConnection()
            compose.onNodeWithTag("nav-Lines").performClick()
            compose.onNodeWithTag("node-choice-1").assertIsSelected()
            compose.onNodeWithTag("node-choice-0").assertIsNotSelected()
        }
        compose.runOnIdle { assertSame(selected, owners.last().nodeSelection.snapshot.selected) }
        return selected
    }

    private fun assertSelectedConnection() {
        compose.onNodeWithTag("connection-node-name", useUnmergedTree = true).assertTextEquals("同名节点")
        compose.onNodeWithTag("connection-node-protocol", useUnmergedTree = true).assertTextEquals("SS")
        compose.onNodeWithTag("vpn-connect").assertIsNotEnabled()
        compose.onNodeWithTag("connection-status").assertTextEquals("未连接")
    }

    private fun recreateAndVerifyFreshOwner() {
        val oldActivity = compose.activity
        val oldOwner = owners.last()
        val oldManager = repositories.last().localSubscriptions
        compose.activityRule.scenario.recreate()
        waitForRestoredOwner(2)
        compose.activityRule.scenario.onActivity { current ->
            assertNotSame(oldActivity, current)
            assertEquals(Lifecycle.State.DESTROYED, oldActivity.lifecycle.currentState)
        }
        compose.runOnIdle {
            assertNotSame(oldOwner, owners.last())
            assertNotSame(oldManager, repositories.last().localSubscriptions)
        }
        // Nodes is a non-default tab: this asserts restored saved state, not an accidental default.
        listOf("Connect", "Lines", "Plans", "Profile").forEach {
            compose.onNodeWithTag("nav-$it").assertExists()
        }
        compose.onNodeWithTag("nav-Orders").assertDoesNotExist()
        compose.onNodeWithTag("page-Lines").assertExists()
        compose.onNodeWithTag("nav-Lines").assertIsSelected()
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
        recreateAndVerifyFreshOwner()
        compose.runOnIdle {
            assertSame(selected, owners.last().nodeSelection.snapshot.selected)
            assertSame(selected, owners.last().nodeSelection.snapshot.choices[1].selection)
            assertNotSame(selected, owners.last().nodeSelection.snapshot.choices[0].selection)
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
                val changed = cached.copyBytes().toString(Charsets.UTF_8).replace("192.0.2.41", "192.0.2.42").toByteArray()
                val replacement = CachedSubscription(cached.binding, changed, cached.validUntil, cached.url)
                try { profiles.save(replacement) } finally { replacement.close(); changed.fill(0) }
            } finally { cached.close() }
        }
        recreateAndVerifyFreshOwner()
        compose.runOnIdle {
            val snapshot = owners.last().nodeSelection.snapshot
            assertNull(snapshot.selected)
            assertEquals(listOf("同名节点", "同名节点"), snapshot.choices.map { it.name })
            assertTrue(snapshot.choices.none { it.selection === old })
            assertFalse(repositories.last().localSubscriptions.selectNode(old))
            owners.last().nodeSelection.select(old)
            assertNull(owners.last().nodeSelection.snapshot.selected)
        }
        compose.onNodeWithTag("node-choice-0").assertIsNotSelected()
        compose.onNodeWithTag("node-choice-1").assertIsNotSelected()
        compose.onNodeWithTag("node-selection-message").assertTextEquals("节点已失效，请刷新后重新选择。")
        screenshot("changed-cache-after-real-activity-recreation-nodes-unselected")
        compose.onNodeWithTag("nav-Connect").performClick()
        compose.onNodeWithTag("connection-node-name", useUnmergedTree = true).assertTextEquals("未选择")
        compose.onNodeWithTag("connection-node-protocol", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("vpn-connect").assertIsNotEnabled()
        screenshot("changed-cache-after-real-activity-recreation-unselected")
        compose.onNodeWithTag("nav-Lines").performClick()
        compose.onNodeWithTag("node-choice-1").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("node-choice-0").assertIsNotSelected()
        compose.runOnIdle {
            assertSame(owners.last().nodeSelection.snapshot.choices[1].selection, owners.last().nodeSelection.snapshot.selected)
            assertNotSame(old, owners.last().nodeSelection.snapshot.selected)
        }
    }
}
