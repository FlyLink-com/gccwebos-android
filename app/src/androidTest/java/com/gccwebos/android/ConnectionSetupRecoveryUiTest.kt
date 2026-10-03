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
import androidx.compose.ui.unit.dp
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.portal.*
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

    private fun launch(fixture: Fixture) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            account = remember { AccountController(fixture.repo, scope) }
            GccTheme { Surface {
                Column(Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                    .padding(12.dp).testTag("setup-recovery-fixture-host")) {
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
        compose.onNodeWithTag("connection-setup-status").assertTextEquals("网络连接失败，请重试")
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
        compose.onNodeWithTag("connection-setup-status")
            .assertTextEquals("订阅交付暂不可领取，请稍后重试或联系支持")
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

    @Test fun severalPreparedNodesRequireExplicitSelectionInsteadOfAPowerCommand() {
        val fixture = Fixture(setupAvailable = true, failFirstDownload = true, holdRetry = true, multipleNodes = true)
        launch(fixture)
        compose.onNodeWithTag("vpn-connect").performScrollTo().performClick()
        compose.waitUntil(10_000) { fixture.retryStarted.isCompleted }
        compose.runOnIdle { fixture.retryRelease.complete(Unit) }
        compose.waitUntil(10_000) { !account.busy && account.localSubscription.hasProfile }
        compose.runOnIdle {
            assertEquals(2, account.nodeSelection.snapshot.choices.size)
            assertNull(account.nodeSelection.snapshot.selected)
            assertEquals(0, powers.get()); assertEquals(1, navigations.get())
            assertEquals(1, fixture.setups.get()); assertEquals(1, fixture.acks.get())
        }
        compose.onNodeWithTag("connection-status").assertTextEquals("未连接")
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
        var control by mutableStateOf(ConnectionControlState(ConnectionPhase.Disconnected, canConnect = true))
        private val wire = object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse = when (path) {
                "/api/portal/password/session" -> WireResponse(200, """{"session":{"sessionRef":"fixture_setup_ui_token","accountId":"fixture_setup_ui_account","issuedAt":"2026-10-03T00:00:00.000Z","expiresAt":"2026-10-03T00:30:00.000Z"}}""")
                "/api/portal/resources/current/overview" -> {
                    overviews.incrementAndGet()
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
                (first + second).toByteArray()
            }, sessions = MemorySessionStore(), profiles = MemoryLocalProfileStore())
    }
}
