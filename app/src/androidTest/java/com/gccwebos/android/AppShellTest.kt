package com.gccwebos.android

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.gccwebos.android.portal.*
import com.gccwebos.android.commerce.*
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
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

    @Test fun signedOutLaunchShowsOnlyThreeTabsAndTruthfullyDisabledConnection() {
        launch()
        compose.onNodeWithTag("page-Connect").assertExists()
        listOf("Connect", "Plans", "Profile").forEach { compose.onNodeWithTag("nav-$it").assertExists() }
        listOf("Lines", "Orders").forEach { compose.onNodeWithTag("nav-$it").assertDoesNotExist() }
        compose.onNodeWithTag("login-screen").assertDoesNotExist()
        compose.onNodeWithTag("connection-power").assertExists()
        compose.onNodeWithTag("subscription-waterdrop").assertDoesNotExist()
        compose.onNodeWithTag("vpn-connect").assertIsNotEnabled()
        compose.onNodeWithTag("vpn-connect").assertContentDescriptionEquals("连接暂不可用")
        compose.onNodeWithTag("local-subscription-title").assertTextEquals("未配置")
        compose.onNodeWithTag("subscription-usage-unavailable").assertTextEquals("暂不可用")
        compose.runOnIdle { assertEquals(emptyList<String>(), requests.toList()) }
    }

    @Test fun readyConfigurationNeverShowsConnectedOrEnablesUnwiredProductionPower() {
        val state = launch()
        compose.runOnIdle {
            state.localSubscription = LocalSubscriptionSnapshot(LocalSubscriptionStatus.READY, hasProfile = true,
                hasCapability = true, validUntil = Instant.now().plusSeconds(600))
        }
        compose.onNodeWithTag("connection-status").assertTextEquals("未连接")
        compose.onNodeWithTag("local-subscription-title").assertTextEquals("连接暂不可用")
        compose.onNodeWithTag("vpn-connect").assertContentDescriptionEquals("连接暂不可用").assertIsNotEnabled()
        compose.onNodeWithText("已连接").assertDoesNotExist()
        compose.onNodeWithTag("subscription-usage-unavailable").assertTextEquals("暂不可用")
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
        compose.onNodeWithTag("vpn-connect").assertContentDescriptionEquals("连接").assertIsEnabled().performClick()
        compose.onNodeWithTag("vpn-connect").assertIsNotEnabled().performTouchInput { click(); click() }
        compose.onNodeWithTag("nav-Plans").performClick()
        compose.onNodeWithTag("nav-Connect").performClick()
        compose.onNodeWithTag("vpn-connect").assertIsNotEnabled().performTouchInput { click(); click() }
        compose.runOnIdle {
            assertEquals(listOf(ConnectionPowerAction.Connect), actions.toList())
            control.value = ConnectionControlState(ConnectionPhase.Connecting, revision = 1)
        }
        compose.onNodeWithTag("connection-status").assertTextEquals("连接中")
        compose.onNodeWithTag("vpn-connect").assertContentDescriptionEquals("连接中").assertIsNotEnabled()
        compose.runOnIdle {
            state.busy = true // Account work cannot remove a real controller's cancellation capability.
            control.value = control.value.copy(canCancel = true, revision = 2)
        }
        compose.onNodeWithTag("vpn-connect").assertContentDescriptionEquals("取消连接").assertIsEnabled().performClick()
        compose.onNodeWithTag("vpn-connect").assertIsNotEnabled().performTouchInput { click(); click() }
        compose.runOnIdle {
            assertEquals(listOf(ConnectionPowerAction.Connect, ConnectionPowerAction.Cancel), actions.toList())
            control.value = ConnectionControlState(ConnectionPhase.Connected, canDisconnect = true, revision = 3)
        }
        compose.onNodeWithTag("connection-status").assertTextEquals("已连接")
        compose.onNodeWithTag("vpn-connect").assertContentDescriptionEquals("断开连接").assertIsEnabled().performClick()
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
        compose.onNodeWithTag("vpn-connect").assertIsNotEnabled()
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

    @Test fun anonymousPlansNeverExposePrivateCatalogOrBalanceAndNodesStayInsideConnect() {
        launch()
        compose.onNodeWithTag("nav-Plans").performClick()
        compose.onNodeWithTag("commerce-signed-out").assertExists()
        compose.onNodeWithText("选择适合你的套餐").assertExists()
        compose.onNodeWithText("当前套餐尚未加载").assertExists()
        compose.onNodeWithTag("anonymous-plans-login").assertIsEnabled()
        compose.onNodeWithTag("wallet-balance").assertDoesNotExist()
        compose.onNodeWithTag("catalog-status").assertDoesNotExist()
        compose.onNodeWithTag("nav-Connect").performClick()
        compose.onNodeWithTag("connection-nodes").performScrollTo().performClick()
        compose.onNodeWithTag("page-Lines").assertExists()
        compose.onNodeWithTag("nav-Lines").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), requests.toList()) }
    }

    @Test fun onlyKnownExpiryShowsRenewalWhileRetryAndStorageKeepDistinctStates() {
        val state = launch()
        compose.runOnIdle {
            state.localSubscription = LocalSubscriptionSnapshot(LocalSubscriptionStatus.RETRY_REQUIRED, validUntil = Instant.EPOCH)
        }
        compose.onNodeWithTag("local-subscription-title").assertTextEquals("读取失败")
        compose.onNodeWithTag("expired-subscription-renew").assertDoesNotExist()
        compose.runOnIdle { state.localSubscription = LocalSubscriptionSnapshot(LocalSubscriptionStatus.STORAGE_ERROR) }
        compose.onNodeWithTag("local-subscription-title").assertTextEquals("本地存储异常")
        compose.onNodeWithTag("expired-subscription-renew").assertDoesNotExist()
        compose.runOnIdle {
            state.localSubscription = LocalSubscriptionSnapshot(LocalSubscriptionStatus.READY, hasProfile = true,
                hasCapability = true, validUntil = Instant.EPOCH)
        }
        compose.onNodeWithTag("local-subscription-title").assertTextEquals("订阅已过期")
        compose.onNodeWithTag("expired-subscription-renew").performScrollTo().assertExists()
    }

    @Test fun mineHistoryKeepsOrdersAndLedgerReadOnlyEvenWithARetainedQuote() {
        val recovery = object : CommerceRecovery {
            override fun read(binding: String, kind: String): PendingCommerce? = error("No recovery read in UI-only fixture")
            override fun save(binding: String, value: PendingCommerce) = error("No order writes")
            override fun clear(binding: String, kind: String) = error("No recovery clear")
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val owner = remember { offlineUiAccount(scope) }
            val state = remember {
                CommerceController(owner, scope, recovery, includeHistory = true).apply {
                    commerceModel = CommerceModel.LEGACY; catalogLoaded = true
                    orderPage = OrderPage(emptyList()); ledgerPage = WalletLedgerPage(emptyList(), null)
                    val offer = Offer("offer_fixture_navigation", "version_fixture_navigation", LocalizedText("离线商品", "Offline"),
                        LocalizedText("离线", "Offline"), LocalizedNotices(emptyList(), emptyList()), 73, 7300,
                        listOf("paid_coins"), 47, 17, 0)
                    quote = Quote("quote_fixture_navigation", offer, 73, 0, "available", 0,
                        emptyList(), QuoteBalances(CoinBalance(0, 100, 0, 0), CoinBalance(0, 27, 0, 0)),
                        Instant.now().plusSeconds(600), null)
                }
            }
            AppShell(owner, autoLoadCommerce = false, commerceOverride = state, commerceRecoveryOverride = recovery)
        }
        compose.onNodeWithTag("nav-Profile").performClick()
        compose.onNodeWithTag("mine-history").performScrollTo().performClick()
        compose.onNodeWithTag("orders-orders").assertExists()
        compose.onNodeWithTag("orders-state-separation").assertExists()
        compose.onNodeWithTag("purchase-confirm").assertDoesNotExist()
        compose.onNodeWithTag("mine-history-ledger").performScrollTo().performClick()
        compose.onNodeWithText("暂无账单记录").assertExists()
        compose.onNodeWithTag("purchase-confirm").assertDoesNotExist()
        compose.onNodeWithTag("mine-back").performScrollTo().performClick()
        compose.onNodeWithTag("mine-history").performScrollTo().assertExists()
        compose.onNodeWithTag("nav-Orders").assertDoesNotExist()
    }
}
