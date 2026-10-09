package com.gccwebos.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gccwebos.android.design.*
import com.gccwebos.android.portal.Resource
import com.gccwebos.android.portal.Usage
import com.gccwebos.android.portal.AccountController
import com.gccwebos.android.portal.DeliveryRecord
import com.gccwebos.android.portal.DeliveryStore
import com.gccwebos.android.portal.OriginPolicy
import com.gccwebos.android.portal.PortalRepository
import com.gccwebos.android.portal.PortalTransport
import com.gccwebos.android.portal.Secret
import com.gccwebos.android.portal.WireResponse
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Presentation-only fixtures. No account, transport, subscription import or VPN command. */
class UnifiedWelcomeGlassUiTest {
    @get:Rule val compose = createComposeRule()
    private val reads = AtomicInteger()
    private val primaryActions = AtomicInteger()
    private val now = Instant.parse("2026-10-02T00:10:00Z")
    private val gib = 1_073_741_824L

    private class Fixture(presentation: AccountUsagePresentation, authenticated: Boolean) {
        var presentation by mutableStateOf(presentation)
        var authenticated by mutableStateOf(authenticated)
        var loading by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var refreshEnabled by mutableStateOf(true)
        var primaryEnabled by mutableStateOf(false)
        var showPrimary by mutableStateOf(true)
    }

    private fun measured() = Resource.Available("resource_" + "a".repeat(64), "synthetic_usage_plan",
        Instant.parse("2026-11-02T00:00:00Z"), true,
        Usage(50 * gib, 3 * gib, 47 * gib, "measured", Instant.parse("2026-10-02T00:01:00Z"),
            Instant.parse("2026-10-02T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z")),
        durationDays = 30, monthlyTrafficGb = 50.0)

    private fun presentation(resource: Resource? = null, failed: Boolean = false) =
        accountUsagePresentation(resource, now, failed, ZoneId.of("UTC"))

    private fun launch(fixture: Fixture, width: Int = 360, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GccTheme { Surface {
                    Column(Modifier.width(width.dp).fillMaxHeight().testTag("unified-welcome-host")
                        .verticalScroll(rememberScrollState()).padding(12.dp)) {
                        BrandCard(Modifier.testTag("unified-welcome-surface")) {
                            AccountUsageCard(fixture.presentation, fixture.authenticated,
                                onRefresh = { reads.incrementAndGet(); fixture.loading = true },
                                onLogin = { error("Guest login belongs to the host's explicit choice") },
                                onRegister = { error("Guest registration belongs to the host's explicit choice") },
                                embedded = true,
                                loading = fixture.loading, error = fixture.error, refreshEnabled = fixture.refreshEnabled,
                                primaryEnabled = fixture.primaryEnabled, primaryTestTag = "unified-primary",
                                showPrimary = fixture.showPrimary,
                                onPrimaryAction = { primaryActions.incrementAndGet() })
                        }
                        BrandButton("返回", {}, Modifier.testTag("unified-return"))
                    }
                } }
            }
        }
    }

    @Test fun compactGuestAndNoneHaveOneExplicitActionWithoutStaleMetricsOrRefresh() {
        val fixture = Fixture(presentation(measured()), authenticated = false).apply { primaryEnabled = true }
        launch(fixture)
        compose.onNodeWithTag("account-usage-title").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-refresh").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-login").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-register").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-used").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-remaining").assertTextEquals("暂未获取")
        compose.onNodeWithTag("unified-primary").assertTextEquals("开通飞连vpn").assertIsEnabled()
            .performScrollTo().performClick()
        assertEquals(1, primaryActions.get()); assertEquals(0, reads.get())
        compose.runOnIdle { fixture.authenticated = true; fixture.presentation = presentation(Resource.None) }
        compose.onNodeWithTag("account-usage-no-plan").assertTextEquals("暂无生效套餐")
        compose.onNodeWithTag("unified-primary").assertTextEquals("开通飞连vpn").performScrollTo().performClick()
        assertEquals(2, primaryActions.get()); assertEquals(0, reads.get())
    }

    @Test fun activeCompactCardDoesNotRefreshOnRenderAndFailureRetryCannotConnectOrRepeat() {
        val fixture = Fixture(presentation(measured()), authenticated = true)
        launch(fixture)
        compose.onNodeWithTag("account-usage-remaining").assertTextEquals("47 GB")
        compose.onNodeWithTag("account-usage-title").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-updated-at").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-refresh").assertDoesNotExist()
        compose.onNodeWithTag("unified-primary").assertTextEquals("一键连接").assertIsNotEnabled()
            .performScrollTo().performTouchInput { click() }
        assertEquals(0, reads.get()); assertEquals(0, primaryActions.get())
        compose.runOnIdle { fixture.error = "synthetic_read_failure"; fixture.presentation = presentation(measured(), true) }
        compose.onNodeWithTag("account-usage-read-status").assertTextEquals("读取失败 · 未更新")
        compose.onNodeWithTag("unified-primary").assertTextEquals("重试").assertIsEnabled()
            .performScrollTo().performClick()
        compose.onNodeWithTag("unified-primary").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithTag("account-usage-read-status").assertTextEquals("正在读取")
        assertEquals(1, reads.get()); assertEquals(0, primaryActions.get())
        // A host displaying the real connection command still has a separate read recovery action.
        compose.runOnIdle { fixture.loading = false; fixture.showPrimary = false }
        compose.onNodeWithTag("unified-primary").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-retry").assertTextEquals("重试").assertIsEnabled()
            .performScrollTo().performClick()
        compose.onNodeWithTag("account-usage-retry").assertIsNotEnabled().performTouchInput { click() }
        assertEquals(2, reads.get()); assertEquals(0, primaryActions.get())
    }

    @Test fun narrowDoubledFontKeepsDisabledActionAndReadRecoveryInsideCard() {
        val fixture = Fixture(presentation(measured()), authenticated = true)
        launch(fixture, width = 320, fontScale = 2f)
        val host = compose.onNodeWithTag("unified-welcome-host").fetchSemanticsNode().boundsInRoot
        val primary = compose.onNodeWithTag("unified-primary")
        primary.performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertIsNotEnabled()
        val bounds = primary.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= host.left); assertTrue(bounds.right <= host.right)
        compose.onNodeWithTag("account-usage-used").assertTextEquals("已用 3 GB")
        compose.onNodeWithTag("account-usage-total").assertTextEquals("总量 50 GB")
        compose.runOnIdle { fixture.error = "synthetic_network_failure" }
        primary.performScrollTo().assertIsDisplayed().assertTextEquals("重试").assertIsEnabled().performClick()
        assertEquals(1, reads.get()); assertEquals(0, primaryActions.get())
        compose.onNodeWithTag("unified-return").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test fun shellHasOneWelcomeUsageSurfaceAndReachableNavigationAtDoubledFont() {
        val requests = AtomicInteger()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val account = remember { offlineAccount(scope, requests) }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.width(320.dp).fillMaxHeight()) {
                    AppShell(account, autoLoadCommerce = false, glassMode = GlassMode.Solid)
                }
            }
        }
        compose.onNode(hasTestTag("vpn-connect") and
            hasAnyAncestor(hasTestTag("connection-hero"))).assertExists()
        compose.onNode(hasTestTag("account-usage-card") and
            hasAnyAncestor(hasTestTag("connection-account-surface"))).assertExists()
        compose.onNodeWithTag("account-usage-title").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-refresh").assertDoesNotExist()
        compose.onNodeWithTag("glass-navigation").assertIsDisplayed()
        compose.onNodeWithTag("nav-Connect").assertHeightIsAtLeast(48.dp).assertIsDisplayed()
        compose.onNodeWithTag("nav-Profile").assertHeightIsAtLeast(48.dp).assertIsDisplayed()
        compose.onNodeWithTag("vpn-connect").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("usage-guest-guidance").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("usage-guest-guidance").assertDoesNotExist()
        assertEquals(0, requests.get())
    }

    @Test fun unifiedSurfacePreservesAcceptedCommandLatchAndMissingCapabilityAdmission() {
        val requests = AtomicInteger()
        val commands = AtomicInteger()
        var control by mutableStateOf(ConnectionControlState(phase = ConnectionPhase.Connecting, canCancel = true))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val account = remember { offlineAccount(scope, requests) }
            AppShell(account, autoLoadCommerce = false, connectionControlState = control,
                onConnectionPowerAction = { action -> assertEquals(ConnectionPowerAction.Cancel, action); commands.incrementAndGet() },
                glassMode = GlassMode.Solid)
        }
        val power = compose.onNodeWithTag("vpn-connect")
        power.assertTextEquals("取消连接").performScrollTo().assertIsEnabled().performClick()
        power.assertIsNotEnabled().performTouchInput { click() }
        assertEquals(1, commands.get())
        compose.runOnIdle { control = ConnectionControlState(phase = ConnectionPhase.Disconnected, canConnect = true, revision = 1) }
        power.performScrollTo().assertTextEquals("开通飞连vpn").assertIsEnabled().performClick()
        compose.onNodeWithTag("usage-guest-guidance").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        assertEquals(1, commands.get()); assertEquals(0, requests.get())
    }

    @Test fun idleProductionOwnerRetainsGuestGuidanceAndShowsTypedFailureWithNoLocalConfig() {
        val requests = AtomicInteger()
        val commands = AtomicInteger()
        var control by mutableStateOf(ConnectionControlState(ConnectionPhase.Disconnected, canConnect = true))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val account = remember { offlineAccount(scope, requests) }
            AppShell(account, autoLoadCommerce = false, connectionControlState = control,
                onConnectionPowerAction = { commands.incrementAndGet() }, glassMode = GlassMode.Solid)
        }
        compose.onNodeWithTag("vpn-connect").assertTextEquals("开通飞连vpn").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("usage-guest-guidance").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle {
            control = ConnectionControlState(ConnectionPhase.Failed, failure = ConnectionFailure.NetworkUnavailable,
                canConnect = true, revision = 1)
        }
        compose.onNodeWithTag("connection-failure").assertTextEquals("无网络")
        compose.onNodeWithTag("connection-status").assertTextEquals("未连接")
        compose.onNodeWithTag("vpn-connect").assertTextEquals("开通飞连vpn").assertIsEnabled()
        assertEquals(0, commands.get()); assertEquals(0, requests.get())
    }

    @Test fun busyAuthenticationCannotBlockOwnerCancelDisconnectOrReplayAcceptedCommands() {
        val requests = AtomicInteger()
        val commands = mutableListOf<ConnectionPowerAction>()
        var control by mutableStateOf(ConnectionControlState(ConnectionPhase.Connecting, canCancel = true))
        compose.setContent {
            val scope = rememberCoroutineScope()
            val account = remember {
                offlineAccount(scope, requests).apply {
                    busy = true; restoring = true; foregroundCheckPending = true; clearing = true
                }
            }
            AppShell(account, autoLoadCommerce = false, connectionControlState = control,
                onConnectionPowerAction = { commands.add(it) }, glassMode = GlassMode.Solid)
        }
        val power = compose.onNodeWithTag("vpn-connect")
        power.assertTextEquals("取消连接").performScrollTo().assertIsEnabled().performClick()
        power.assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { control = ConnectionControlState(ConnectionPhase.Connected, canDisconnect = true, revision = 1) }
        power.assertTextEquals("断开").performScrollTo().assertIsEnabled().performClick()
        power.assertIsNotEnabled().performTouchInput { click() }
        assertEquals(listOf(ConnectionPowerAction.Cancel, ConnectionPowerAction.Disconnect), commands)
        assertEquals(0, requests.get())
    }

    private fun offlineAccount(scope: kotlinx.coroutines.CoroutineScope, requests: AtomicInteger) =
        AccountController(PortalRepository(object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                requests.incrementAndGet()
                error("Unified welcome fixture cannot request")
            }
        }, object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = Unit
            override fun clear() = Unit
        }, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), {0}), scope)
}
