package com.gccwebos.android

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.gccwebos.android.portal.*
import com.gccwebos.android.commerce.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.Test

class AppShellTest {
    @get:Rule val compose = createComposeRule()
    private val requests = CopyOnWriteArrayList<String>()
    private fun account(scope: CoroutineScope) = AccountController(PortalRepository(
        object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                requests += path
                check(path == "/api/portal/human-challenges") { "UI fixture refuses all account and commerce writes" }
                check(session == null)
                return WireResponse(201, """{"challenge":{"challengeRef":"human_ui_navigation","type":"math","prompt":"离线题目：7 + 2 = ?","expiresInSeconds":180}}""")
            }
        }, object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = error("No delivery in navigation fixture")
            override fun clear() = Unit
        }, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L },
        subscription = SubscriptionSource { error("Navigation fixture cannot download configuration") }), scope)

    private fun launch(control: () -> ConnectionControlState = { ConnectionControlState() },
        onPowerAction: ((ConnectionPowerAction) -> Unit)? = null): AccountController {
        lateinit var controller: AccountController
        compose.setContent {
            val scope = rememberCoroutineScope()
            val state = remember { account(scope) }
            controller = state
            AppShell(state, autoLoadCommerce = false, connectionControlState = control(),
                onConnectionPowerAction = onPowerAction)
        }
        return controller
    }

    @Test fun signedOutLaunchShowsFourOrderedTabsAndExplicitOpeningIntentWithoutEngineClaims() {
        launch()
        compose.onNodeWithTag("page-Connect").assertExists()
        val tabs = listOf("Connect", "Lines", "Plans", "Profile").map { compose.onNodeWithTag("nav-$it").assertExists() }
        assertTrue(tabs.map { it.fetchSemanticsNode().boundsInRoot.left }.zipWithNext().all { (left, right) -> left < right })
        compose.onNodeWithTag("nav-Lines").assertTextEquals("节点")
        compose.onNodeWithTag("nav-Orders").assertDoesNotExist()
        compose.onNodeWithTag("login-screen").assertDoesNotExist()
        compose.onNodeWithTag("connection-power").assertDoesNotExist()
        compose.onNodeWithTag("connection-welcome").assertDoesNotExist()
        compose.onNodeWithTag("connection-hero").assertExists()
        compose.onNodeWithTag("account-usage-card").assertExists()
        compose.onNodeWithTag("subscription-waterdrop").assertDoesNotExist()
        compose.onNodeWithTag("vpn-connect").assertTextEquals("开通飞连vpn").assertIsEnabled()
        compose.onNodeWithTag("local-subscription-title").assertDoesNotExist()
        compose.onNodeWithTag("subscription-usage-unavailable").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-remaining").assertTextEquals("暂未获取")
        compose.onNodeWithTag("usage-guest-guidance").assertDoesNotExist()
        compose.onNodeWithTag("no-resource-guidance").assertDoesNotExist()
        compose.onNodeWithTag("vpn-connect").performScrollTo().performClick()
        compose.onNodeWithTag("usage-guest-guidance").assertExists()
        compose.onNodeWithTag("usage-guest-login").assertIsEnabled()
        compose.onNodeWithTag("usage-guest-register").assertIsEnabled()
        compose.onNodeWithTag("login-screen").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), requests.toList()) }
    }

    @Test fun readyConfigurationNeverShowsConnectedOrEnablesUnwiredProductionPower() {
        val state = launch()
        compose.runOnIdle {
            state.localSubscription = LocalSubscriptionSnapshot(LocalSubscriptionStatus.READY, hasProfile = true,
                hasCapability = true, validUntil = Instant.now().plusSeconds(600))
        }
        compose.onNodeWithTag("connection-status").assertTextEquals("未连接")
        compose.onNodeWithTag("local-subscription-title").assertDoesNotExist()
        // A saved local profile cannot authenticate this account card or open a production engine.
        compose.onNodeWithTag("vpn-connect").assertTextEquals("开通飞连vpn").assertIsEnabled()
        compose.onNodeWithText("已连接").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-remaining").assertTextEquals("暂未获取")
        compose.onNodeWithTag("subscription-usage-unavailable").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), requests.toList()) }
    }

    @Test fun controllerPowerCannotReplayOnNavigationAndAccountWorkDoesNotBlockCancelOrDisconnect() {
        val control = mutableStateOf(ConnectionControlState(ConnectionPhase.Disconnected, canConnect = true))
        val actions = CopyOnWriteArrayList<ConnectionPowerAction>()
        val state = launch({ control.value }, { actions += it })
        compose.runOnIdle {
            state.localSubscription = LocalSubscriptionSnapshot(LocalSubscriptionStatus.READY, hasProfile = true,
                hasCapability = true, validUntil = Instant.now().plusSeconds(600))
        }
        compose.onNodeWithTag("vpn-connect").assertTextEquals("一键连接").assertIsEnabled().performClick()
        compose.onNodeWithTag("vpn-connect").assertIsNotEnabled().performTouchInput { click(); click() }
        compose.onNodeWithTag("nav-Plans").performClick()
        compose.onNodeWithTag("nav-Connect").performClick()
        compose.onNodeWithTag("vpn-connect").assertIsNotEnabled().performTouchInput { click(); click() }
        compose.runOnIdle {
            assertEquals(listOf(ConnectionPowerAction.Connect), actions.toList())
            control.value = ConnectionControlState(ConnectionPhase.Connecting, revision = 1)
        }
        compose.onNodeWithTag("connection-status").assertTextEquals("连接中")
        compose.onNodeWithTag("vpn-connect").assertTextEquals("连接中").assertIsNotEnabled()
        compose.runOnIdle {
            state.busy = true // Account work cannot remove a real controller's cancellation capability.
            control.value = control.value.copy(canCancel = true, revision = 2)
        }
        compose.onNodeWithTag("vpn-connect").assertTextEquals("取消连接").assertIsEnabled().performClick()
        compose.onNodeWithTag("vpn-connect").assertIsNotEnabled().performTouchInput { click(); click() }
        compose.runOnIdle {
            assertEquals(listOf(ConnectionPowerAction.Connect, ConnectionPowerAction.Cancel), actions.toList())
            control.value = ConnectionControlState(ConnectionPhase.Connected, canDisconnect = true, revision = 3)
        }
        compose.onNodeWithTag("connection-status").assertTextEquals("已连接")
        compose.onNodeWithTag("vpn-connect").assertTextEquals("断开").assertIsEnabled().performClick()
        compose.onNodeWithTag("vpn-connect").assertIsNotEnabled().performTouchInput { click(); click() }
        compose.runOnIdle {
            assertEquals(listOf(ConnectionPowerAction.Connect, ConnectionPowerAction.Cancel, ConnectionPowerAction.Disconnect), actions.toList())
            assertEquals(emptyList<String>(), requests.toList())
        }
    }

    @Test fun signedOutRecreationRetainsConnectionWithoutAuthenticationRequests() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val scope = rememberCoroutineScope()
            AppShell(remember { account(scope) }, autoLoadCommerce = false)
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("page-Connect").assertExists()
        compose.onNodeWithTag("login-screen").assertDoesNotExist()
        compose.onNodeWithTag("connection-welcome").assertDoesNotExist()
        compose.onNodeWithTag("connection-hero").assertExists()
        compose.onNodeWithTag("vpn-connect").assertTextEquals("开通飞连vpn").assertIsEnabled()
        compose.runOnIdle { assertEquals(emptyList<String>(), requests.toList()) }
    }

    @Test fun profileLoginAndRegistrationRemainLocalAndReturnToConnection() {
        val state = launch()
        compose.onNodeWithTag("nav-Profile").performClick()
        compose.onNodeWithTag("login-screen").assertExists()
        compose.onNodeWithTag("login-account").performScrollTo().performTextInput("navigation@example.invalid")
        compose.onNodeWithTag("login-register").performScrollTo().performClick()
        compose.onNodeWithTag("registration-form").assertExists()
        compose.onNodeWithTag("nav-Connect").performClick()
        compose.onNodeWithTag("page-Connect").assertExists()
        compose.onNodeWithTag("login-screen").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("navigation@example.invalid", state.account)
            check(requests.all { it == "/api/portal/human-challenges" })
        }
    }

    @Test fun guestOpeningRequiresExplicitLoginOrRegistrationChoiceAndReturnsToConnection() {
        launch()
        compose.onNodeWithTag("vpn-connect").performScrollTo().performClick()
        compose.onNodeWithTag("usage-guest-guidance").assertExists()
        compose.runOnIdle { assertTrue(requests.isEmpty()) }
        compose.onNodeWithTag("usage-guest-login").performClick()
        compose.onNodeWithTag("login-screen").assertExists()
        compose.onNodeWithTag("registration-form").assertDoesNotExist()
        compose.onNodeWithTag("nav-Connect").performClick()
        compose.onNodeWithTag("page-Connect").assertExists()
        compose.onNodeWithTag("usage-guest-guidance").assertDoesNotExist()
        compose.onNodeWithTag("vpn-connect").performScrollTo().performClick()
        compose.onNodeWithTag("usage-guest-register").performClick()
        compose.onNodeWithTag("registration-form").assertExists()
        compose.onNodeWithTag("nav-Connect").performClick()
        compose.onNodeWithTag("page-Connect").assertExists()
        compose.onNodeWithTag("registration-form").assertDoesNotExist()
        compose.runOnIdle { check(requests.all { it == "/api/portal/human-challenges" }) }
    }

    @Test fun anonymousPlansNeverExposePrivateCatalogOrBalanceAndNodesHaveIndependentTab() {
        launch()
        compose.onNodeWithTag("nav-Plans").performClick()
        compose.onNodeWithTag("commerce-signed-out").assertExists()
        compose.onNodeWithTag("anonymous-plans-title").assertTextEquals("浏览全部套餐")
        compose.onNodeWithTag("anonymous-plans-empty-title").assertTextEquals("价格登录后显示")
        compose.onNodeWithTag("anonymous-plans-import").assertExists()
        // No price, tier or coin figure may appear before a verified session reads the catalog.
        compose.onNodeWithText("¥", substring = true).assertDoesNotExist()
        compose.onNodeWithText("当前套餐尚未加载").assertDoesNotExist()
        compose.onNodeWithTag("anonymous-plans-login").assertIsEnabled()
        compose.onNodeWithTag("wallet-balance").assertDoesNotExist()
        compose.onNodeWithTag("catalog-status").assertDoesNotExist()
        compose.onNodeWithTag("nav-Connect").performClick()
        compose.onNodeWithTag("connection-nodes").performScrollTo().performClick()
        compose.onNodeWithTag("page-Lines").assertExists()
        compose.onNodeWithTag("nav-Lines").assertExists().assertIsSelected()
        compose.onNodeWithTag("nodes-account").assertTextEquals("登录").assertIsEnabled()
        compose.runOnIdle { assertEquals(emptyList<String>(), requests.toList()) }
    }

    @Test fun missingNodeGuideRoutesToMyAndPrivateGateSurvivesComposeRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val scope = rememberCoroutineScope()
            AppShell(remember { account(scope) }, autoLoadCommerce = false)
        }
        compose.onNodeWithTag("nav-Lines").performClick()
        compose.onNodeWithTag("nodes-refresh").assertDoesNotExist()
        compose.onNodeWithTag("nodes-account").performScrollTo().performClick()
        compose.onNodeWithTag("login-screen").assertExists()
        compose.onNodeWithTag("wallet-balance").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("login-screen").assertExists()
        compose.onNodeWithTag("wallet-balance").assertDoesNotExist()
        compose.onNodeWithTag("page-Profile").assertDoesNotExist()
        compose.onNodeWithTag("nav-Connect").performClick()
        compose.onNodeWithTag("vpn-connect").assertTextEquals("开通飞连vpn").assertIsEnabled()
        compose.runOnIdle { check(requests.all { it == "/api/portal/human-challenges" }) }
    }

    private val nodeReads = AtomicInteger()
    private lateinit var nodeAccount: AccountController
    private var pendingNodeRead: CompletableDeferred<Unit>? = null
    private var nodeBytes = """proxies:
  - name: 重复节点
    type: ss
    server: 192.0.2.40
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
  - name: 重复节点
    type: ss
    server: 192.0.2.41
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""".toByteArray()

    private fun launchLoadedNodes() {
        val expiry = Instant.now().plusSeconds(3600)
        compose.setContent {
            val scope = rememberCoroutineScope()
            val owner = remember {
                val store = object : DeliveryStore {
                    override fun read() = DeliveryRecord("fixture", "fixture", "fixture", "fixture",
                        Secret("https://sub.invalid/sub/a/appshell-nodes-fixture"), null, false)
                    override fun save(record: DeliveryRecord) = error("No delivery writes")
                    override fun clear() = Unit
                }
                val source = object : SubscriptionSource {
                    override suspend fun download(url: Secret) = read(url).bytes
                    override suspend fun read(url: Secret): SubscriptionRead {
                        nodeReads.incrementAndGet()
                        val gate = pendingNodeRead
                        if (gate != null) withContext(NonCancellable) { gate.await() }
                        return SubscriptionRead(nodeBytes.copyOf(), expiry)
                    }
                }
                AccountController(PortalRepository(object : PortalTransport {
                    override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                        requests += path; error("Node navigation must not access Portal")
                    }
                }, store, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L },
                    subscription = source, sessions = MemorySessionStore(), profiles = MemoryLocalProfileStore()), scope)
                    .also { it.nodeSelection.invalidate(); nodeAccount = it }
            }
            LaunchedEffect(owner) { owner.nodeSelection.refresh() } // Explicit synthetic local-config fixture setup.
            AppShell(owner, autoLoadCommerce = false)
        }
        compose.waitUntil(5000) { ::nodeAccount.isInitialized && !nodeAccount.nodeSelection.busy &&
            nodeAccount.nodeSelection.snapshot.status == LocalSubscriptionStatus.READY }
    }

    @Test fun selectedOpaqueNodeReturnsToConnectionAndSurvivesTabsAndSameContentFreshRead() {
        launchLoadedNodes()
        compose.onNodeWithTag("connection-node-name", useUnmergedTree = true).assertTextEquals("未选择")
        compose.onNodeWithTag("nav-Lines").performClick()
        val second = nodeAccount.nodeSelection.snapshot.choices[1].selection
        compose.onNodeWithTag("node-choice-1").performScrollTo().performClick()
        assertNodeSelectionReturnedToConnection()
        compose.onNodeWithTag("connection-node-name", useUnmergedTree = true).assertTextEquals("重复节点")
        compose.onNodeWithTag("connection-node-protocol", useUnmergedTree = true).assertTextEquals("SS")
        compose.onNodeWithTag("vpn-connect").assertTextEquals("开通飞连vpn").assertIsEnabled()
        compose.onNodeWithTag("nav-Lines").performClick()
        compose.onNodeWithTag("node-choice-1").assertIsSelected()
        compose.onNodeWithTag("node-choice-0").assertIsNotSelected()
        compose.onNodeWithTag("nav-Connect").performClick()
        repeat(2) {
            compose.onNodeWithTag("connection-nodes").performScrollTo().performClick()
            compose.onNodeWithTag("node-choice-1").assertIsSelected()
            compose.onNodeWithTag("nav-Connect").performClick()
        }
        compose.runOnIdle { assertSame(second, nodeAccount.nodeSelection.snapshot.selected); assertEquals(1, nodeReads.get()) }
        compose.onNodeWithTag("nav-Lines").performClick()
        compose.onNodeWithTag("nodes-refresh").assertDoesNotExist()
        compose.runOnIdle { nodeAccount.nodeSelection.refresh() } // Explicit offline read setup; not a UI reload.
        compose.waitUntil(5000) { nodeReads.get() == 2 && !nodeAccount.nodeSelection.busy }
        compose.onNodeWithTag("node-choice-1").assertIsSelected()
        compose.onNodeWithTag("nav-Connect").performClick()
        compose.onNodeWithTag("connection-node-name", useUnmergedTree = true).assertTextEquals("重复节点")
        compose.runOnIdle { assertSame(second, nodeAccount.nodeSelection.snapshot.selected); assertTrue(requests.isEmpty()) }
        val refreshGate = CompletableDeferred<Unit>()
        try {
            compose.onNodeWithTag("local-subscription-refresh").assertDoesNotExist()
            compose.runOnIdle { pendingNodeRead = refreshGate; nodeAccount.refreshSubscription() }
            compose.waitUntil(5000) { nodeReads.get() == 3 && nodeAccount.nodeSelection.busy }
            compose.onNodeWithTag("connection-node-name", useUnmergedTree = true).assertTextEquals("重复节点")
            compose.onNodeWithTag("connection-node-protocol", useUnmergedTree = true).assertTextEquals("SS")
            compose.runOnIdle { refreshGate.complete(Unit); pendingNodeRead = null }
            compose.waitUntil(5000) { !nodeAccount.nodeSelection.busy }
            compose.onNodeWithTag("connection-node-name", useUnmergedTree = true).assertTextEquals("重复节点")
            compose.runOnIdle { assertSame(second, nodeAccount.nodeSelection.snapshot.selected); assertTrue(requests.isEmpty()) }
        } finally { refreshGate.complete(Unit); pendingNodeRead = null }
    }

    @Test fun changedContentClearsConnectionSummaryAndNeedsExplicitNewOpaqueSelection() {
        launchLoadedNodes()
        compose.onNodeWithTag("nav-Lines").performClick()
        val old = nodeAccount.nodeSelection.snapshot.choices[1].selection
        compose.onNodeWithTag("node-choice-1").performScrollTo().performClick()
        assertNodeSelectionReturnedToConnection()
        compose.onNodeWithTag("nav-Lines").performClick()
        compose.runOnIdle { nodeBytes = nodeBytes.toString(Charsets.UTF_8).replace("192.0.2.41", "192.0.2.42").toByteArray() }
        compose.onNodeWithTag("nodes-refresh").assertDoesNotExist()
        compose.runOnIdle { nodeAccount.nodeSelection.refresh() } // Explicit offline read setup; not a UI reload.
        compose.waitUntil(5000) { nodeReads.get() == 2 && !nodeAccount.nodeSelection.busy }
        compose.onNodeWithTag("node-choice-1").assertIsNotSelected()
        compose.onNodeWithTag("node-selection-message").assertDoesNotExist()
        compose.onNodeWithTag("nav-Connect").performClick()
        compose.onNodeWithTag("connection-node-name", useUnmergedTree = true).assertTextEquals("未选择")
        compose.onNodeWithTag("connection-node-protocol", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { nodeAccount.nodeSelection.select(old); assertNull(nodeAccount.nodeSelection.snapshot.selected) }
        compose.onNodeWithTag("nav-Lines").performClick()
        val current = nodeAccount.nodeSelection.snapshot.choices[0].selection
        compose.onNodeWithTag("node-choice-0").performScrollTo().performClick()
        assertNodeSelectionReturnedToConnection()
        compose.onNodeWithTag("connection-node-name", useUnmergedTree = true).assertTextEquals("重复节点")
        compose.runOnIdle { assertSame(current, nodeAccount.nodeSelection.snapshot.selected); assertTrue(requests.isEmpty()) }
    }

    @Test fun cancelledAndAccountChangedLateReadsCannotReviveConnectionNodeSummary() {
        launchLoadedNodes()
        try {
        compose.onNodeWithTag("nav-Lines").performClick()
        compose.onNodeWithTag("node-choice-1").performScrollTo().performClick()
        assertNodeSelectionReturnedToConnection()
        compose.onNodeWithTag("nav-Lines").performClick()
        compose.runOnIdle { pendingNodeRead = CompletableDeferred() }
        compose.onNodeWithTag("nodes-refresh").assertDoesNotExist()
        compose.runOnIdle { nodeAccount.nodeSelection.refresh() } // Explicit offline read setup; not a UI reload.
        compose.waitUntil(5000) { nodeAccount.nodeSelection.busy && nodeReads.get() == 2 }
        compose.onNodeWithTag("nodes-cancel").assertDoesNotExist()
        compose.runOnIdle { nodeAccount.nodeSelection.cancel() }
        compose.onNodeWithTag("nav-Connect").performClick()
        compose.onNodeWithTag("connection-node-name", useUnmergedTree = true).assertTextEquals("未选择")
        compose.runOnIdle { pendingNodeRead!!.complete(Unit); pendingNodeRead = null }
        compose.onNodeWithTag("connection-node-name", useUnmergedTree = true).assertTextEquals("未选择")
        compose.onNodeWithTag("nav-Lines").performClick()
        compose.onNodeWithTag("nodes-refresh").assertDoesNotExist()
        compose.runOnIdle { nodeAccount.nodeSelection.refresh() }
        compose.waitUntil(5000) { nodeReads.get() == 3 && !nodeAccount.nodeSelection.busy }
        compose.onNodeWithTag("node-choice-0").performScrollTo().performClick()
        assertNodeSelectionReturnedToConnection()
        compose.onNodeWithTag("nav-Lines").performClick()
        compose.runOnIdle { pendingNodeRead = CompletableDeferred() }
        compose.onNodeWithTag("nodes-refresh").assertDoesNotExist()
        compose.runOnIdle { nodeAccount.nodeSelection.refresh() } // Explicit offline read setup; not a UI reload.
        compose.waitUntil(5000) { nodeAccount.nodeSelection.busy && nodeReads.get() == 4 }
        compose.runOnIdle { nodeAccount.authSessionGeneration++ }
        compose.onNodeWithTag("nav-Connect").performClick()
        compose.onNodeWithTag("connection-node-name", useUnmergedTree = true).assertTextEquals("未选择")
        compose.runOnIdle { pendingNodeRead!!.complete(Unit); pendingNodeRead = null }
        compose.onNodeWithTag("connection-node-name", useUnmergedTree = true).assertTextEquals("未选择")
        compose.onNodeWithTag("connection-node-protocol", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { assertNull(nodeAccount.nodeSelection.snapshot.selected); assertTrue(requests.isEmpty()) }
        } finally { pendingNodeRead?.complete(Unit); pendingNodeRead = null }
    }

    private fun assertNodeSelectionReturnedToConnection() {
        compose.onNodeWithTag("page-Connect").assertExists()
        compose.onNodeWithTag("nav-Connect").assertIsSelected()
        compose.onNodeWithTag("page-Lines").assertDoesNotExist()
        compose.onNodeWithTag("connection-status").assertTextEquals("未连接")
        compose.onNodeWithTag("connection-power").assertDoesNotExist()
    }

    @Test fun onlyKnownExpiryShowsRenewalWhileRetryAndStorageKeepDistinctStates() {
        val state = launch()
        compose.runOnIdle {
            state.localSubscription = LocalSubscriptionSnapshot(LocalSubscriptionStatus.RETRY_REQUIRED, validUntil = Instant.EPOCH)
        }
        compose.onNodeWithTag("local-subscription-title").assertDoesNotExist()
        compose.onNodeWithTag("expired-subscription-renew").assertDoesNotExist()
        compose.runOnIdle { state.localSubscription = LocalSubscriptionSnapshot(LocalSubscriptionStatus.STORAGE_ERROR) }
        compose.onNodeWithTag("local-subscription-title").assertDoesNotExist()
        compose.onNodeWithTag("expired-subscription-renew").assertDoesNotExist()
        compose.runOnIdle {
            state.localSubscription = LocalSubscriptionSnapshot(LocalSubscriptionStatus.READY, hasProfile = true,
                hasCapability = true, validUntil = Instant.EPOCH)
        }
        compose.onNodeWithTag("local-subscription-title").assertDoesNotExist()
        compose.onNodeWithTag("expired-subscription-renew").performScrollTo().assertExists()
    }

    @Test fun repeatedPlansTabAndReturnRequestTheDefaultWithoutStartingCheckout() {
        val recovery = object : CommerceRecovery {
            override fun read(binding: String, kind: String): PendingCommerce? = throw AssertionError("No recovery reads")
            override fun save(binding: String, value: PendingCommerce) = throw AssertionError("No order writes")
            override fun clear(binding: String, kind: String) = throw AssertionError("No recovery clears")
        }
        val monthly = Offer("offer_nav_month", "version_nav_month", LocalizedText("合成月卡", "Synthetic month"),
            LocalizedText("", ""), LocalizedNotices(emptyList(), emptyList()), 30, 3000,
            listOf("paid_coins"), 30, 17, 0)
        val yearly = monthly.copy(offerRef = "offer_nav_year", offerVersionRef = "version_nav_year",
            title = LocalizedText("合成年卡", "Synthetic year"), durationDays = 365, baseCoinUnits = 200)
        lateinit var commerce: CommerceController
        compose.setContent {
            val scope = rememberCoroutineScope()
            val owner = remember { offlineUiAccount(scope) }
            val state = remember {
                CommerceController(owner, scope, recovery).apply {
                    commerceModel = CommerceModel.LEGACY; catalogLoaded = true
                    offers = listOf(monthly, yearly)
                }
            }
            commerce = state
            AppShell(owner, autoLoadCommerce = false, commerceOverride = state, commerceRecoveryOverride = recovery,
                paymentOpenerOverride = { throw AssertionError("Navigation cannot open payment") })
        }
        fun chooseYear() {
            compose.onNodeWithTag("catalog-yearly").performScrollTo().performClick()
            compose.onNodeWithTag("plan-offer-${yearly.offerRef}").performScrollTo().performClick().assertIsSelected()
        }
        fun assertMonthly() {
            compose.onNodeWithTag("catalog-plans").assertIsSelected()
            compose.onNodeWithTag("plan-offer-${monthly.offerRef}").assertIsSelected()
            compose.onNodeWithTag("plan-price-${monthly.offerRef}", useUnmergedTree = true).assertTextEquals("30 Coins")
            compose.onNodeWithTag("purchase-confirm").assertDoesNotExist()
        }
        compose.onNodeWithTag("nav-Plans").performClick()
        assertMonthly()
        chooseYear()
        compose.onNodeWithTag("nav-Plans").performClick() // Same tab still issues an explicit display reset.
        assertMonthly()
        chooseYear()
        compose.onNodeWithTag("nav-Connect").performClick()
        compose.onNodeWithTag("nav-Plans").performClick()
        assertMonthly()
        chooseYear()
        compose.runOnIdle { commerce.busy = true }
        compose.onNodeWithTag("nav-Plans").performClick()
        compose.onNodeWithTag("catalog-yearly").assertIsSelected()
        compose.onNodeWithTag("plan-offer-${yearly.offerRef}").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(commerce.busy); assertNull(commerce.quote) }
        compose.runOnIdle { commerce.busy = false; commerce.pendingPurchase = true }
        compose.onNodeWithTag("nav-Plans").performClick()
        compose.onNodeWithTag("purchase-result").performScrollTo().assertExists()
        compose.onNodeWithTag("plan-continue").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(commerce.pendingPurchase)
            assertNull(commerce.quote); assertNull(commerce.purchase); assertNull(commerce.recharge); assertNull(commerce.error)
        }
    }

    @Test fun mineHistoryKeepsOrdersAndLedgerReadOnlyEvenWithARetainedQuote() {
        val recovery = object : CommerceRecovery {
            override fun read(binding: String, kind: String): PendingCommerce? = throw AssertionError("No recovery read in UI-only fixture")
            override fun save(binding: String, value: PendingCommerce) = throw AssertionError("No order writes")
            override fun clear(binding: String, kind: String) = throw AssertionError("No recovery clear")
        }
        lateinit var commerce: CommerceController
        val offer = Offer("offer_fixture_navigation", "version_fixture_navigation", LocalizedText("离线商品", "Offline"),
            LocalizedText("离线", "Offline"), LocalizedNotices(emptyList(), emptyList()), 73, 7300,
            listOf("paid_coins"), 47, 17, 0)
        val retainedQuote = Quote("quote_fixture_navigation", offer, 73, 0, "available", 0,
            emptyList(), QuoteBalances(CoinBalance(0, 100, 0, 0), CoinBalance(0, 27, 0, 0)),
            Instant.now().plusSeconds(600), null)
        compose.setContent {
            val scope = rememberCoroutineScope()
            val owner = remember { offlineUiAccount(scope) }
            val state = remember {
                CommerceController(owner, scope, recovery, includeHistory = true).apply {
                    commerceModel = CommerceModel.LEGACY; catalogLoaded = true
                    orderPage = OrderPage(emptyList()); ledgerPage = WalletLedgerPage(emptyList(), null)
                    quote = retainedQuote
                }
            }
            commerce = state
            AppShell(owner, autoLoadCommerce = false, commerceOverride = state, commerceRecoveryOverride = recovery,
                paymentOpenerOverride = { throw AssertionError("History cannot open a payment") })
        }
        compose.onNodeWithTag("nav-Profile").performClick()
        compose.onNodeWithTag("mine-history").performScrollTo().performClick()
        // One flat 流水: no order/ledger tabs, the ledger is the only list.
        compose.onNodeWithTag("mine-history-tabs").assertDoesNotExist()
        compose.onNodeWithTag("orders-orders").assertDoesNotExist()
        compose.onNodeWithTag("purchase-confirm").assertDoesNotExist()
        compose.onNodeWithTag("ledger-empty").assertTextEquals("暂无账单")
        compose.onNodeWithTag("ledger-unread").assertDoesNotExist()
        compose.onNodeWithTag("ledger-loading").assertDoesNotExist()
        compose.onNodeWithTag("ledger-error").assertDoesNotExist()
        compose.onNodeWithTag("purchase-confirm").assertDoesNotExist()
        compose.onNodeWithTag("recharge-pay").assertDoesNotExist()
        compose.onNodeWithTag("payment-open-confirm").assertDoesNotExist()
        compose.onNodeWithTag("mine-back").performScrollTo().performClick()
        compose.onNodeWithTag("mine-history").performScrollTo().assertExists()
        compose.onNodeWithTag("nav-Orders").assertDoesNotExist()
        compose.runOnIdle {
            assertSame(retainedQuote, commerce.quote)
            assertNull(commerce.error)
            assertNull(commerce.purchase); assertNull(commerce.recharge)
            assertTrue(!commerce.pendingPurchase && !commerce.pendingRecharge)
        }
    }
}
