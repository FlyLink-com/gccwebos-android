package com.gccwebos.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.gccwebos.android.core.ConnectionSelection
import com.gccwebos.android.portal.*
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real AppShell/session validation with memory stores and synthetic reads; no device account or VPN. */
class NodeForegroundRecoveryUiTest {
    @get:Rule val compose = createComposeRule()
    private val restoration = StateRestorationTester(compose)
    private val now = Instant.parse("2026-10-04T00:00:00Z")
    private val until = Instant.parse("2099-10-04T00:00:00Z")
    private lateinit var account: AccountController
    private val overviewReads = AtomicInteger()
    private val subscriptionReads = AtomicInteger()
    private val probeCalls = CopyOnWriteArrayList<ConnectionSelection>()
    private val powerActions = mutableListOf<ConnectionPowerAction>()
    private var verificationGate: CompletableDeferred<Unit>? = null
    private var subscriptionGate: CompletableDeferred<Unit>? = null
    private var phase by mutableStateOf(ConnectionPhase.Disconnected)

    private fun launch() {
        val url = Secret("https://sub.invalid/sub/a/foreground-fixture")
        val raw = """proxies:
  - name: Foreground fixture
    type: ss
    server: 192.0.2.10
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
  - name: Foreground fixture
    type: ss
    server: 192.0.2.11
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""".toByteArray()
        val profiles = MemoryLocalProfileStore().apply {
            CachedSubscription(sessionBinding(url), raw, until, url).use(::save)
        }
        val saved = MemorySessionStore().apply {
            save(SavedSession(Secret("native_foreground_fixture"), "foreground-fixture",
                now.plusSeconds(1800), 1800000, now, 0))
        }
        val delivery = object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = error("Navigation must not write a delivery")
            override fun clear() = error("Navigation must not clear a delivery")
        }
        val wire = object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                assertEquals("Only overview GETs may validate or refresh this saved account",
                    "/api/portal/resources/current/overview", path)
                assertNull("Navigation must not send a POST", body)
                assertNotNull(session)
                overviewReads.incrementAndGet()
                verificationGate?.await()
                return WireResponse(200, """{"resource":{"state":"available",
                    "plan":{"id":"foreground-fixture","durationDays":30,"monthlyTrafficGb":10},
                    "validUntil":"2026-11-04T00:00:00.000Z","setupAvailable":true,
                    "resourceRef":"resource_${"a".repeat(64)}",
                    "usage":{"totalBytes":1024,"usedBytes":256,"remainingBytes":768,
                    "collectionStatus":"measured","updatedAt":"2026-10-04T00:00:00.000Z",
                    "cycleStartedAt":"2026-10-01T00:00:00.000Z","cycleEndsAt":"2026-11-01T00:00:00.000Z"}}}""")
            }
        }
        val source = object : SubscriptionSource {
            override suspend fun download(url: Secret): ByteArray = read(url).bytes
            override suspend fun read(url: Secret): SubscriptionRead {
                subscriptionReads.incrementAndGet()
                subscriptionGate?.await()
                return SubscriptionRead(raw.copyOf(), until)
            }
        }
        val probe = object : NodeLatencyProbe {
            override fun currentNetworkKey(): Long = 7L
            override suspend fun measure(target: NodeProbeTarget): NodeLatencyObservation {
                probeCalls += target.selection
                return NodeLatencyObservation.Success(17L, 7L)
            }
        }
        val repository = PortalRepository(wire, delivery,
            OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L }, { now },
            subscription = source, sessions = saved, profiles = profiles)
        // Isolate this fixture from other instrumentation tests' process-local handles.
        repository.localSubscriptions.invalidateNodeSelection()
        restoration.setContent {
            val scope = rememberCoroutineScope()
            val state = androidx.compose.runtime.remember { AccountController(repository, scope, probe) }
            account = state
            LaunchedEffect(state) { state.restore() }
            AppShell(accountOverride = state, autoLoadCommerce = false, autoRefreshPages = true,
                connectionControlState = ConnectionControlState(phase),
                onConnectionPowerAction = { powerActions += it })
        }
        compose.waitUntil(10_000) { account.authenticated && !account.busy && !account.restoring }
        compose.runOnIdle {
            assertEquals(2, overviewReads.get())
            assertEquals(0, subscriptionReads.get())
            assertTrue(account.localSubscription.hasProfile)
        }
        compose.onNodeWithTag("nav-Lines").performClick()
        compose.waitUntil(10_000) {
            !account.nodeSelection.busy && account.nodeSelection.snapshot.choices.size == 2 &&
                account.nodeSelection.snapshot.choices.all {
                    account.nodeLatency.reading(it.selection) is NodeLatencyObservation.Success
                }
        }
        compose.onNodeWithTag("node-choice-0").assertIsEnabled()
        compose.onNodeWithTag("node-latency-0", useUnmergedTree = true).assertContentDescriptionEquals("节点延迟 17 毫秒")
        compose.runOnIdle {
            assertEquals(1, subscriptionReads.get())
            assertEquals(until, account.nodeSelection.snapshot.validUntil)
            assertTrue(account.nodeSelection.select(account.nodeSelection.snapshot.choices[1].selection))
        }
    }

    @Test fun sameSessionForegroundValidationRecoversMountedNodesWithFreshHandlesAndNoPowerAction() {
        launch()
        val previous = compose.runOnIdle { requireNotNull(account.nodeSelection.snapshot.selected) }
        val generation = compose.runOnIdle { account.authSessionGeneration }
        compose.runOnIdle {
            verificationGate = CompletableDeferred()
            subscriptionGate = CompletableDeferred()
            account.onForeground()
        }
        compose.waitUntil(5_000) { overviewReads.get() == 3 && account.foregroundCheckPending }
        compose.onNodeWithTag("node-choice-0").assertIsNotEnabled()
        compose.onNodeWithTag("node-latency-0", useUnmergedTree = true).assertContentDescriptionEquals("节点延迟：未测试")
        compose.runOnIdle {
            assertFalse(account.nodeSelection.select(previous))
            assertNull(account.nodeLatency.reading(previous))
            assertEquals(1, subscriptionReads.get())
            verificationGate!!.complete(Unit)
        }
        // No second navigation click: the mounted page must recover after the verified owner changes.
        compose.waitUntil(10_000) { subscriptionReads.get() == 2 && account.nodeSelection.busy }
        compose.runOnIdle {
            assertTrue(account.authenticated)
            assertTrue(account.authSessionGeneration > generation)
            assertEquals(4, overviewReads.get())
            assertFalse(account.nodeSelection.select(previous))
            assertNull(account.nodeLatency.reading(previous))
            assertTrue(powerActions.isEmpty())
            subscriptionGate!!.complete(Unit)
        }
        compose.waitUntil(10_000) {
            !account.nodeSelection.busy && account.nodeSelection.snapshot.choices.size == 2 &&
                account.nodeSelection.snapshot.choices.all {
                    account.nodeLatency.reading(it.selection) is NodeLatencyObservation.Success
                }
        }
        compose.onNodeWithTag("page-Lines").assertIsDisplayed()
        compose.onNodeWithTag("node-choice-0").assertIsEnabled()
        compose.onNodeWithTag("node-latency-0", useUnmergedTree = true).assertContentDescriptionEquals("节点延迟 17 毫秒")
        compose.runOnIdle {
            assertEquals(2, subscriptionReads.get())
            assertEquals(4, overviewReads.get())
            assertNull(account.nodeSelection.snapshot.selected)
            assertTrue(account.nodeSelection.snapshot.choices.none { it.selection === previous })
            assertFalse(account.nodeSelection.select(previous))
            assertNull(account.nodeLatency.reading(previous))
            assertTrue(account.nodeSelection.snapshot.choices.all { choice -> probeCalls.any { it === choice.selection } })
            assertTrue(powerActions.isEmpty())
        }
    }

    @Test fun foregroundRecoveryRebuildsLocalHandlesWithoutSubscriptionDownloadWhileConnected() {
        launch()
        val previous = compose.runOnIdle { requireNotNull(account.nodeSelection.snapshot.selected) }
        listOf(ConnectionPhase.Connecting, ConnectionPhase.Connected, ConnectionPhase.Disconnecting).forEachIndexed { index, blocked ->
            val generation = compose.runOnIdle {
                phase = blocked
                account.authSessionGeneration
            }
            compose.runOnIdle { account.onForeground() }
            compose.waitUntil(10_000) {
                account.authenticated && !account.busy && !account.foregroundCheckPending &&
                    account.authSessionGeneration > generation
            }
            // Local rebuild restores selectable handles; never downloads a subscription mid-connection.
            compose.waitUntil(10_000) {
                !account.nodeSelection.busy && account.nodeSelection.snapshot.choices.size == 2
            }
            compose.runOnIdle {
                assertEquals(4 + 2 * index, overviewReads.get())
                assertEquals("An accepted connection blocks subscription downloads", 1, subscriptionReads.get())
                assertFalse(account.nodeSelection.select(previous))
                assertTrue(account.nodeSelection.select(account.nodeSelection.snapshot.choices[0].selection))
                assertNull(account.nodeLatency.reading(previous))
                assertTrue(powerActions.isEmpty())
            }
        }
    }

    @Test fun restoredNodesTabRecoversWithoutAnotherNavigationClick() {
        launch()
        val previous = compose.runOnIdle { requireNotNull(account.nodeSelection.snapshot.selected) }
        compose.runOnIdle {
            verificationGate = CompletableDeferred()
            subscriptionGate = CompletableDeferred()
        }
        // Only selectedName is saveable: the restored AppShell's navigationRequest starts at zero.
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5_000) { overviewReads.get() == 3 && account.restoring }
        compose.onNodeWithTag("page-Lines").assertIsDisplayed()
        compose.onNodeWithTag("nav-Lines").assertIsSelected()
        compose.runOnIdle { verificationGate!!.complete(Unit) }
        compose.waitUntil(10_000) { subscriptionReads.get() == 2 && account.nodeSelection.busy }
        compose.runOnIdle {
            assertTrue(account.authenticated)
            assertEquals(4, overviewReads.get())
            assertFalse(account.nodeSelection.select(previous))
            assertNull(account.nodeLatency.reading(previous))
            assertTrue(powerActions.isEmpty())
            subscriptionGate!!.complete(Unit)
        }
        compose.waitUntil(10_000) {
            !account.nodeSelection.busy && account.nodeSelection.snapshot.choices.size == 2 &&
                account.nodeSelection.snapshot.choices.all {
                    account.nodeLatency.reading(it.selection) is NodeLatencyObservation.Success
                }
        }
        compose.onNodeWithTag("node-choice-0").assertIsEnabled()
        compose.onNodeWithTag("node-latency-0", useUnmergedTree = true).assertContentDescriptionEquals("节点延迟 17 毫秒")
        compose.runOnIdle {
            assertEquals(2, subscriptionReads.get())
            assertEquals(4, overviewReads.get())
            assertNull(account.nodeSelection.snapshot.selected)
            assertTrue(account.nodeSelection.snapshot.choices.none { it.selection === previous })
            assertFalse(account.nodeSelection.select(previous))
            assertTrue(powerActions.isEmpty())
        }
    }
}
