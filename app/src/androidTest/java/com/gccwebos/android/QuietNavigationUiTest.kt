package com.gccwebos.android

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.gccwebos.android.portal.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the production repository-override route using only owned memory fixtures. */
class QuietNavigationUiTest {
    @get:Rule val compose = createComposeRule()
    private val reads = AtomicInteger()
    private val probes = AtomicInteger()
    private val commands = AtomicInteger()
    private var failRead = false
    private var gate: CompletableDeferred<Unit>? = null
    private var control by mutableStateOf(ConnectionControlState())
    private lateinit var account: AccountController
    private lateinit var repository: PortalRepository
    private val expiry = Instant.parse("2099-01-01T00:00:00Z")
    private val bytes = """proxies:
  - name: Synthetic Japan
    type: ss
    server: 192.0.2.21
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""".toByteArray()

    private fun launch(refreshPages: Boolean, captureAccount: Boolean = false) {
        val url = Secret("https://sub.invalid/sub/a/quiet-navigation-fixture")
        val profiles = MemoryLocalProfileStore().apply {
            CachedSubscription(sessionBinding(url), bytes, expiry, url).use { save(it) }
        }
        repository = PortalRepository(object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse =
                error("This signed-out fixture must not access Portal")
        }, object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = error("No delivery writes")
            override fun clear() = Unit
        }, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L },
            subscription = object : SubscriptionSource {
                override suspend fun download(url: Secret) = read(url).bytes
                override suspend fun read(url: Secret): SubscriptionRead {
                    reads.incrementAndGet()
                    gate?.await()
                    if (failRead) throw PortalFailure(PortalFailure.Reason.NETWORK)
                    return SubscriptionRead(bytes.copyOf(), expiry)
                }
            }, sessions = MemorySessionStore(), profiles = profiles)
        // The synthetic repository shares process authority with other UI fixtures. Seed only
        // this already-bound memory profile so prior fixture rejection state cannot leak in.
        repository.localSubscriptions.invalidateNodeSelection()
        repository.localSubscriptions.remember(bytes, url, expiry)
        val probe = object : NodeLatencyProbe {
            override fun currentNetworkKey() = 7L
            override suspend fun measure(target: NodeProbeTarget): NodeLatencyObservation {
                probes.incrementAndGet()
                return NodeLatencyObservation.Success(27L, 7L)
            }
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val owner = if (captureAccount) remember {
                AccountController(repository, scope, nodeLatencyProbe = probe).also { account = it }
            } else null
            LaunchedEffect(owner) { owner?.restore() }
            AppShell(accountOverride = owner, repositoryOverride = repository, nodeLatencyProbe = probe,
                autoLoadCommerce = false, autoRefreshPages = refreshPages,
                connectionControlState = control, onConnectionPowerAction = { commands.incrementAndGet() })
        }
        compose.waitForIdle()
    }

    private fun awaitNodeChoice() {
        compose.waitUntil(5000) { compose.onAllNodesWithTag("node-choice-0").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun serviceRepositoryInjectionRetainsProbeAndDisplaysMeasuredMilliseconds() {
        launch(refreshPages = false)
        compose.onNodeWithTag("nav-Lines").performClick()
        compose.waitUntil(5000) { probes.get() == 1 }
        compose.onNodeWithTag("node-latency-0", useUnmergedTree = true).assertContentDescriptionEquals("节点延迟 27 毫秒")
        compose.onNodeWithTag("node-region-0", useUnmergedTree = true)
            .assertContentDescriptionEquals("节点标注地区：日本")
        compose.onNodeWithTag("nodes-latency-refresh").assertIsEnabled()
            .assertContentDescriptionEquals("延迟测试")
        // The last test's time rides on the count: 「N 条 · 刚刚」.
        compose.onNodeWithTag("nodes-count", useUnmergedTree = true).assertTextContains("条 · 刚刚", substring = true)
        compose.onNodeWithText("测试节点延迟").assertDoesNotExist()
        compose.onNodeWithTag("nodes-latency-hint").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, reads.get()); assertEquals(0, commands.get()) }
    }

    @Test fun repeatedNodeNavigationRetriesQuietlyAndNeverStartsAConnection() {
        failRead = true
        gate = CompletableDeferred()
        launch(refreshPages = true, captureAccount = true)
        compose.waitUntil(5000) { ::account.isInitialized && !account.busy && !account.restoring }
        // A failed/invalidated registry needs recovery. Healthy page visits use the local list.
        compose.runOnIdle { repository.localSubscriptions.invalidateNodeSelection() }
        compose.onNodeWithTag("nav-Lines").performClick()
        compose.waitUntil(5000) { reads.get() == 1 }
        compose.onNodeWithTag("nodes-refresh").assertDoesNotExist()
        compose.onNodeWithTag("nodes-cancel").assertDoesNotExist()
        compose.onNodeWithText("正在刷新").assertDoesNotExist()
        compose.onNodeWithTag("node-choice-0").assertDoesNotExist()
        compose.onNodeWithTag("nodes-latency-refresh").assertIsNotEnabled()
        compose.onNodeWithTag("nav-Lines").performClick()
        compose.onNodeWithTag("nav-Lines").performClick()
        compose.runOnIdle { assertEquals(1, reads.get()) }
        compose.runOnIdle { gate!!.complete(Unit); gate = null }
        compose.waitUntil(5000) { !account.nodeSelection.busy &&
            account.nodeSelection.snapshot.status == LocalSubscriptionStatus.RETRY_REQUIRED }
        compose.onNodeWithTag("node-choice-0").assertDoesNotExist()
        compose.runOnIdle { failRead = false }
        compose.onNodeWithTag("nav-Lines").performClick()
        compose.waitUntil(5000) { reads.get() == 2 && compose.onAllNodesWithTag("node-choice-0")
            .fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("node-choice-0").assertIsEnabled().performScrollTo().performClick()
        compose.onNodeWithTag("page-Connect").assertExists()
        compose.runOnIdle { assertEquals(0, commands.get()) }
    }

    @Test fun nodeNavigationDoesNotInvalidateAnAcceptedConnectionPreparation() {
        control = ConnectionControlState(phase = ConnectionPhase.Connecting, commandPending = true)
        launch(refreshPages = true)
        compose.onNodeWithTag("nav-Lines").performClick()
        awaitNodeChoice()
        compose.onNodeWithTag("node-choice-0").assertExists()
        val original = repository.localSubscriptions.nodeSelectionSnapshot().choices.single().selection
        compose.runOnIdle { assertEquals(0, reads.get()); control = ConnectionControlState() }
        compose.onNodeWithTag("nav-Lines").performClick()
        compose.waitForIdle()
        awaitNodeChoice()
        compose.onNodeWithTag("node-choice-0").assertIsEnabled().performClick()
        compose.onNodeWithTag("page-Connect").assertExists()
        compose.runOnIdle {
            assertSame(original, repository.localSubscriptions.nodeSelectionSnapshot().selected)
            assertEquals(0, reads.get()); assertEquals(0, commands.get())
        }
    }

    @Test fun navigationWaitingForAccountIdleRechecksTheLatestConnectionOwner() {
        launch(refreshPages = true, captureAccount = true)
        compose.waitUntil(5000) { ::account.isInitialized && !account.busy && !account.restoring }
        compose.runOnIdle {
            account.busy = true
            // Keep a real recovery request pending so the latest connection-owner guard matters.
            repository.localSubscriptions.invalidateNodeSelection()
        }
        compose.onNodeWithTag("nav-Lines").performClick()
        compose.runOnIdle { control = ConnectionControlState(phase = ConnectionPhase.Connecting, commandPending = true) }
        compose.runOnIdle { account.busy = false }
        compose.waitForIdle()
        // While connecting, revoked handles rebuild from local cache — no subscription HTTP, no power action.
        compose.runOnIdle { assertEquals(0, reads.get()); assertEquals(0, commands.get()) }
        compose.waitUntil(5000) {
            !account.nodeSelection.busy && account.nodeSelection.snapshot.choices.isNotEmpty()
        }
        compose.runOnIdle { control = ConnectionControlState() }
        compose.onNodeWithTag("nav-Lines").performClick()
        awaitNodeChoice()
        compose.runOnIdle { assertEquals(0, reads.get()); assertEquals(0, commands.get()) }
    }

    @Test fun healthyRepeatedNavigationKeepsTheLocalSelectionWithoutSubscriptionHttp() {
        launch(refreshPages = true, captureAccount = true)
        compose.waitUntil(5000) { ::account.isInitialized && !account.busy && !account.restoring }
        compose.onNodeWithTag("nav-Lines").performClick()
        awaitNodeChoice()
        compose.onNodeWithTag("node-choice-0").assertIsEnabled().performClick()
        compose.onNodeWithTag("page-Connect").assertExists()
        val original = repository.localSubscriptions.nodeSelectionSnapshot().selected
        assertNotNull(original)
        repeat(3) {
            compose.onNodeWithTag("nav-Lines").performClick()
            awaitNodeChoice()
            compose.onNodeWithTag("node-choice-0").assertIsEnabled().assertIsSelected()
            compose.onNodeWithTag("nodes-refresh").assertDoesNotExist()
            compose.onNodeWithTag("nodes-latency-hint").assertDoesNotExist()
            compose.onNodeWithTag("nav-Connect").performClick()
            compose.runOnIdle {
                assertSame(original, repository.localSubscriptions.nodeSelectionSnapshot().selected)
                assertEquals(0, reads.get()); assertEquals(0, commands.get())
            }
        }
    }
}
