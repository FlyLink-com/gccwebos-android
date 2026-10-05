package com.gccwebos.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.portal.*
import com.gccwebos.android.vpn.SafeProxyConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real ConnectionOverview + controller/parser with memory-only synthetic delivery.
 * No filesystem persistence, real login/challenge solving, email, order or VPN action.
 * These methods are prepared for separately approved instrumentation; compilation is not execution.
 */
class ConnectionSetupRecoveryUiTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.parse("2026-10-03T00:00:00Z")
    private lateinit var account: AccountController
    private val powers = AtomicInteger()
    private val navigations = AtomicInteger()
    private var showOverview by mutableStateOf(true)

    private fun launch(fixture: Fixture, viewportHeight: Dp? = null) {
        fixture.assertConfigurationShape()
        // These injected memory stores do not own the previous fixture's process-wide
        // selection registry. Fence it before the first Overview sync can inspect it.
        fixture.repo.localSubscriptions.invalidateNodeSelection()
        compose.setContent {
            val scope = rememberCoroutineScope()
            account = remember { AccountController(fixture.repo, scope) }
            GccTheme { Surface {
                Column(Modifier.width(360.dp)
                    .then(if (viewportHeight == null) Modifier.fillMaxHeight() else Modifier.height(viewportHeight))
                    .testTag("setup-recovery-fixture-host")
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)) {
                    if (showOverview) ConnectionOverview(account, openAccount = { navigations.incrementAndGet() },
                        openPlans = { navigations.incrementAndGet() }, openNodes = { navigations.incrementAndGet() },
                        control = fixture.control,
                        onPowerAction = { action -> assertEquals(ConnectionPowerAction.Connect, action); powers.incrementAndGet() },
                        nowSource = { now })
                }
            } }
        }
        compose.runOnIdle {
            account.account = "setup.ui.fixture@example.invalid"; account.password = "synthetic-only"
            account.answer = "synthetic-answer"
            account.challenge = HumanChallenge("fixture_not_a_server_challenge", "synthetic question, no solver")
            account.login()
        }
        compose.waitUntil(10_000) { account.authenticated && !account.busy }
    }

    @Test fun failedDownloadShowsSyncAndBusyRetryCannotDuplicateTheAcknowledgedClaim() {
        val fixture = Fixture(setupAvailable = true, failFirstDownload = true, holdRetry = true)
        launch(fixture)
        compose.runOnIdle {
            assertEquals(LocalSubscriptionStatus.MISSING, account.localSubscription.status)
            assertFalse(account.localSubscription.hasCapability)
            assertNotNull(account.error)
            fixture.setupAvailable = false
        }
        compose.onNodeWithTag("connection-setup-status").assertTextEquals("网络连接失败")
        compose.onNodeWithTag("vpn-connect").assertTextEquals("一键连接").assertIsEnabled()
            .performScrollTo().performClick()
        compose.waitUntil(10_000) { fixture.retryStarted.isCompleted && account.busy }
        compose.onNodeWithTag("vpn-connect").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(2, fixture.downloads.get())
            assertEquals(1, fixture.setups.get()); assertEquals(1, fixture.acks.get())
            assertEquals(0, powers.get()); assertEquals(0, navigations.get())
            fixture.retryRelease.complete(Unit)
        }
        compose.waitUntil(10_000) { !account.busy && account.localSubscription.hasProfile }
        compose.runOnIdle {
            assertEquals(ImportStatus.IMPORTED, account.importStatus)
            assertEquals(LocalSubscriptionStatus.READY, account.localSubscription.status)
            assertNull(account.error); assertNull(account.nodeSelection.snapshot.selected)
            assertEquals(1, fixture.setups.get()); assertEquals(1, fixture.acks.get())
            assertEquals(1, powers.get()); assertEquals(0, navigations.get())
        }
        compose.onNodeWithTag("connection-status").assertTextEquals("未连接")
    }

    @Test fun unavailableDeliveryShowsWaitingAndExplicitRetryNeverCreatesAClaim() {
        val fixture = Fixture(setupAvailable = false)
        launch(fixture)
        compose.runOnIdle {
            assertEquals(ImportStatus.WAITING_DELIVERY, account.importStatus)
            assertEquals(LocalSubscriptionStatus.MISSING, account.localSubscription.status)
            assertNull(account.error)
            assertEquals(0, fixture.setups.get()); assertEquals(0, fixture.acks.get()); assertEquals(0, fixture.downloads.get())
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("connection-setup-status").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("connection-setup-status")
            .assertTextEquals("订阅交付暂不可领取")
        compose.onNodeWithTag("vpn-connect").assertTextEquals("一键连接").assertIsEnabled()
            .performScrollTo().performClick()
        compose.waitUntil(10_000) { fixture.overviews.get() >= 2 && !account.busy }
        compose.runOnIdle {
            assertEquals(ImportStatus.WAITING_DELIVERY, account.importStatus)
            assertEquals(LocalSubscriptionStatus.MISSING, account.localSubscription.status)
            assertFalse(account.localSubscription.hasProfile); assertFalse(account.localSubscription.hasCapability)
            assertNull(fixture.store.value)
            assertEquals(0, fixture.setups.get()); assertEquals(0, fixture.acks.get()); assertEquals(0, fixture.downloads.get())
            assertEquals(0, powers.get()); assertEquals(0, navigations.get())
        }
        compose.onNodeWithTag("connection-status").assertTextEquals("未连接")
        compose.onNodeWithText("已连接", substring = false).assertDoesNotExist()
    }

    @Test fun explicitCancelKeepsLoginAndReceiptWithoutAPowerCommandFromTheLateDownload() {
        val fixture = Fixture(setupAvailable = true, failFirstDownload = true, holdRetry = true, lateDownload = true)
        launch(fixture)
        compose.onNodeWithTag("vpn-connect").performScrollTo().performClick()
        compose.waitUntil(10_000) { fixture.retryStarted.isCompleted && account.busy }
        compose.onNodeWithTag("connection-prepare-cancel").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(account.authenticated); assertFalse(account.busy)
            assertEquals("准备已取消", account.error)
            assertNotNull(fixture.store.value); assertFalse(requireNotNull(fixture.store.value).pendingAck)
            fixture.retryRelease.complete(Unit)
        }
        compose.waitUntil(10_000) { fixture.retryReturned.isCompleted }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, powers.get()); assertEquals(0, navigations.get())
            assertEquals(1, fixture.setups.get()); assertEquals(1, fixture.acks.get())
        }
    }

    @Test fun leavingTheOverviewCancelsOnlyItsPreparationAndCannotDispatchFromALateResult() {
        val fixture = Fixture(setupAvailable = true, failFirstDownload = true, holdRetry = true, lateDownload = true)
        launch(fixture)
        compose.onNodeWithTag("vpn-connect").performScrollTo().performClick()
        compose.waitUntil(10_000) { fixture.retryStarted.isCompleted && account.busy }
        compose.runOnIdle { showOverview = false }
        compose.onNodeWithTag("connection-overview").assertDoesNotExist()
        compose.runOnIdle {
            // The outer fixture/controller scope remains mounted; this exercises Overview disposal.
            assertTrue(account.authenticated); assertFalse(account.busy)
            fixture.retryRelease.complete(Unit)
        }
        compose.waitUntil(10_000) { fixture.retryReturned.isCompleted }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, powers.get()); assertEquals(0, navigations.get())
            assertNotNull(fixture.store.value)
            assertEquals(1, fixture.setups.get()); assertEquals(1, fixture.acks.get())
        }
    }

    @Test fun explicitPreparationContinuesThroughOnePowerCommandForSeveralLegalNodes() {
        val fixture = Fixture(setupAvailable = true, failFirstDownload = true, holdRetry = true, multipleNodes = true)
        launch(fixture)
        compose.runOnIdle {
            assertEquals(LocalSubscriptionStatus.MISSING, account.localSubscription.status)
            assertFalse(account.localSubscription.hasProfile)
            assertEquals("网络连接失败，请重试", account.error)
            assertEquals(1, fixture.downloads.get())
            assertEquals(1, fixture.setups.get()); assertEquals(1, fixture.acks.get())
        }
        compose.onNodeWithTag("vpn-connect").assertTextEquals("一键连接").assertIsEnabled()
            .performScrollTo().performClick()
        compose.waitUntil(10_000) { fixture.retryStarted.isCompleted }
        compose.runOnIdle { fixture.retryRelease.complete(Unit) }
        compose.waitUntil(10_000) { fixture.retryReturned.isCompleted && !account.busy }
        compose.runOnIdle {
            // Await the operation's terminal state, then expose a real parser/storage
            // failure directly instead of hiding it behind a hasProfile timeout.
            assertNull("Preparation failed: ${account.error}", account.error)
            assertEquals(ImportStatus.IMPORTED, account.importStatus)
            assertEquals(LocalSubscriptionStatus.READY, account.localSubscription.status)
            assertTrue(account.localSubscription.hasProfile); assertTrue(account.localSubscription.hasCapability)
            assertEquals(2, account.localSubscription.nodes.size)
            assertTrue(account.localSubscription.nodes.all { it.supported })
            assertEquals(2, account.nodeSelection.snapshot.choices.size)
            assertNull(account.nodeSelection.snapshot.selected)
            assertEquals(2, fixture.downloads.get())
            // The owner/gateway owns random selection and fresh admission; UI must only dispatch once.
            assertEquals(1, powers.get()); assertEquals(0, navigations.get())
            assertEquals(1, fixture.setups.get()); assertEquals(1, fixture.acks.get())
        }
        compose.onNodeWithTag("connection-status").assertTextEquals("未连接")
    }

    @Test fun readyExplicitClickKeepsExistingChoiceAndCannotDispatchTwiceWhileAwaitingOwner() {
        val fixture = Fixture(setupAvailable = true, multipleNodes = true)
        launch(fixture)
        val selected = compose.runOnIdle {
            account.nodeSelection.sync()
            account.nodeSelection.snapshot.choices[1].selection.also(account.nodeSelection::select)
        }
        compose.onNodeWithTag("vpn-connect").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("vpn-connect").assertTextEquals("一键连接").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithTag("account-usage-cancel").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-read-status").assertDoesNotExist()
        compose.onNodeWithTag("connection-setup-status").assertDoesNotExist()
        compose.runOnIdle {
            assertSame(selected, account.nodeSelection.snapshot.selected)
            assertEquals(1, powers.get()); assertEquals(0, navigations.get())
            assertEquals(1, fixture.downloads.get()); assertEquals(1, fixture.setups.get()); assertEquals(1, fixture.acks.get())
        }
    }

    @Test fun failedUsageReadOnReadyConfigRecoversWithoutStartingUntilAnotherExplicitClick() {
        val fixture = Fixture(setupAvailable = true)
        launch(fixture)
        compose.runOnIdle { fixture.failOverview = true; account.refreshAccountUsage() }
        compose.waitUntil(10_000) { account.accountUsage.state.value.error != null && !account.accountUsage.state.value.busy }
        compose.onNodeWithTag("account-usage-read-status").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-retry").assertDoesNotExist()
        val gate = CompletableDeferred<Unit>()
        compose.runOnIdle { fixture.failOverview = false; fixture.overviewGate = gate }
        compose.onNodeWithTag("vpn-connect").performScrollTo().assertTextEquals("一键连接").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { fixture.overviews.get() == 3 && account.accountUsage.state.value.busy }
        compose.onNodeWithTag("vpn-connect").assertTextEquals("一键连接").assertIsNotEnabled()
        compose.onNodeWithTag("account-usage-cancel").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, powers.get()); gate.complete(Unit) }
        compose.waitUntil(10_000) { !account.accountUsage.state.value.busy && account.accountUsage.state.value.error == null }
        compose.runOnIdle {
            assertEquals(LocalSubscriptionStatus.READY, account.localSubscription.status)
            assertEquals(0, powers.get()); assertEquals(0, navigations.get())
            assertEquals(1, fixture.downloads.get()); assertEquals(1, fixture.setups.get()); assertEquals(1, fixture.acks.get())
        }
        compose.onNodeWithTag("vpn-connect").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, powers.get()) }
    }

    @Test fun localReadFailureUsesTheStableActionWithoutAutomaticallyStartingAfterRecovery() {
        val fixture = Fixture(setupAvailable = true, holdRetry = true)
        launch(fixture)
        compose.runOnIdle { account.localSubscription = account.localSubscription.copy(status = LocalSubscriptionStatus.RETRY_REQUIRED) }
        compose.onNodeWithTag("local-subscription-title").assertDoesNotExist()
        compose.onNodeWithTag("local-subscription-refresh").assertDoesNotExist()
        compose.onNodeWithTag("subscription-validity-countdown").assertDoesNotExist()
        compose.onNodeWithTag("vpn-connect").performScrollTo().assertTextEquals("一键连接").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { fixture.retryStarted.isCompleted && account.nodeSelection.busy }
        compose.onNodeWithTag("vpn-connect").assertTextEquals("一键连接").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle {
            assertFalse(account.busy) // The local selection reader owns this work, not authentication.
            assertEquals(0, powers.get()); assertEquals(2, fixture.downloads.get())
            fixture.retryRelease.complete(Unit)
        }
        compose.waitUntil(10_000) { !account.nodeSelection.busy && account.localSubscription.status == LocalSubscriptionStatus.READY }
        compose.runOnIdle {
            assertEquals(0, powers.get()); assertEquals(0, navigations.get())
            assertEquals(2, fixture.downloads.get()); assertEquals(1, fixture.setups.get()); assertEquals(1, fixture.acks.get())
        }
        compose.onNodeWithTag("vpn-connect").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, powers.get()) }
    }

    @Test fun normalWidthKeepsTheNodeEntryInTheInitialViewportWithoutStartingAConnection() {
        val fixture = Fixture(setupAvailable = true, multipleNodes = true)
        launch(fixture, viewportHeight = 640.dp)
        compose.onNodeWithTag("setup-recovery-fixture-host").assertWidthIsEqualTo(360.dp).assertHeightIsEqualTo(640.dp)
        compose.onNodeWithTag("connection-nodes").assertIsDisplayed()
        val viewport = compose.onNodeWithTag("setup-recovery-fixture-host").getUnclippedBoundsInRoot()
        val nodes = compose.onNodeWithTag("connection-nodes").getUnclippedBoundsInRoot()
        assertTrue("Node entry must fit without scrolling", nodes.top >= viewport.top && nodes.bottom <= viewport.bottom)
        compose.runOnIdle {
            assertEquals(0, powers.get()); assertEquals(0, navigations.get())
            assertNull(account.nodeSelection.snapshot.selected)
        }
    }

    @Test fun aNewLiveOwnerStateBlocksThePreparedRequestsOldIdleConnectCallback() {
        val fixture = Fixture(setupAvailable = true, failFirstDownload = true, holdRetry = true)
        launch(fixture)
        compose.onNodeWithTag("vpn-connect").performScrollTo().performClick()
        compose.waitUntil(10_000) { fixture.retryStarted.isCompleted }
        compose.runOnIdle { fixture.control = ConnectionControlState(ConnectionPhase.Connecting, canCancel = true, revision = 2) }
        compose.onNodeWithTag("connection-status").assertTextEquals("连接中")
        compose.runOnIdle { fixture.retryRelease.complete(Unit) }
        compose.waitUntil(10_000) { !account.busy && account.localSubscription.hasProfile }
        compose.runOnIdle { assertEquals(0, powers.get()); assertEquals(0, navigations.get()) }
    }

    private class Store : DeliveryStore {
        var value: DeliveryRecord? = null
        override fun read() = value
        override fun save(record: DeliveryRecord) { value = record }
        override fun clear() { value = null }
    }

    private inner class Fixture(var setupAvailable: Boolean, val failFirstDownload: Boolean = false,
        val holdRetry: Boolean = false, val lateDownload: Boolean = false, val multipleNodes: Boolean = false) {
        val store = Store()
        val setups = AtomicInteger(); val acks = AtomicInteger(); val overviews = AtomicInteger()
        val downloads = AtomicInteger()
        val retryStarted = CompletableDeferred<Unit>(); val retryRelease = CompletableDeferred<Unit>()
        val retryReturned = CompletableDeferred<Unit>()
        var failOverview = false
        var overviewGate: CompletableDeferred<Unit>? = null
        var control by mutableStateOf(ConnectionControlState(ConnectionPhase.Disconnected, canConnect = true))
        private val wire = object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse = when (path) {
                "/api/portal/password/session" -> WireResponse(200, """{"session":{"sessionRef":"fixture_setup_ui_token","accountId":"fixture_setup_ui_account","issuedAt":"2026-10-03T00:00:00.000Z","expiresAt":"2026-10-03T00:30:00.000Z"}}""")
                "/api/portal/resources/current/overview" -> {
                    overviews.incrementAndGet()
                    overviewGate?.await()
                    if (failOverview) WireResponse(503, "") else
                        WireResponse(200, """{"resource":{"state":"available","resourceRef":"resource_${"b".repeat(64)}","plan":{"id":"synthetic_setup_plan","durationDays":30,"monthlyTrafficGb":1},"validUntil":"2026-11-03T00:00:00.000Z","setupAvailable":$setupAvailable,"usage":{"totalBytes":100,"usedBytes":25,"remainingBytes":75,"collectionStatus":"measured","updatedAt":"2026-10-03T00:00:00.000Z","cycleStartedAt":"2026-10-03T00:00:00.000Z","cycleEndsAt":"2026-11-02T00:00:00.000Z"}}}""")
                }
                "/api/portal/commerce/model" -> {
                    assertNull(body); assertNotNull(session)
                    WireResponse(200, """{"commerceModel":"wallet_first_unique_v1"}""")
                }
                "/api/portal/resources/current/setup" -> {
                    setups.incrementAndGet()
                    WireResponse(200, """{"delivery":{"handoffRef":"fixture_setup_ui_receipt","kind":"subscription_url","subscriptionUrl":"https://sub.invalid/sub/a/setup-ui-fixture"}}""")
                }
                "/api/portal/resources/current/setup/ack" -> { acks.incrementAndGet(); WireResponse(204, "") }
                else -> throw AssertionError("Unexpected setup UI fixture endpoint: $path")
            }
        }
        val repo = PortalRepository(wire, store,
            OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0 }, { now },
            SubscriptionSource { url ->
                assertEquals("https://sub.invalid/sub/a/setup-ui-fixture", url.value)
                assertFalse(requireNotNull(store.value).pendingAck)
                val attempt = downloads.incrementAndGet()
                if (failFirstDownload && attempt == 1) throw PortalFailure(PortalFailure.Reason.NETWORK)
                if (holdRetry && attempt == 2) {
                    retryStarted.complete(Unit)
                    if (lateDownload) withContext(NonCancellable) {
                        retryRelease.await(); retryReturned.complete(Unit)
                    } else { retryRelease.await(); retryReturned.complete(Unit) }
                }
                configuration()
            }, sessions = MemorySessionStore(), profiles = MemoryLocalProfileStore())

        fun assertConfigurationShape() {
            val raw = configuration()
            try {
                val nodes = SafeProxyConfig.inspect(raw)
                assertEquals(if (multipleNodes) 2 else 1, nodes.size)
                assertTrue(nodes.all { it.supported })
                SafeProxyConfig.project(raw, 0).fill(0)
            } finally { raw.fill(0) }
        }

        private fun configuration(): ByteArray {
            val first = """
                proxies:
                  - name: synthetic setup UI node
                    type: ss
                    server: 192.0.2.1
                    port: 443
                    cipher: aes-128-gcm
                    password: synthetic-only
            """.trimIndent()
            val second = if (multipleNodes) "\n" + """
                  - name: synthetic setup UI node two
                    type: ss
                    server: 192.0.2.2
                    port: 443
                    cipher: aes-128-gcm
                    password: synthetic-only
            """.trimIndent().prependIndent("  ") else ""
            return (first + second).toByteArray()
        }
    }
}
