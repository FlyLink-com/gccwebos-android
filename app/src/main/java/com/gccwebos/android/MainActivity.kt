package com.gccwebos.android

import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.activity.compose.LocalActivity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gccwebos.android.design.*
import com.gccwebos.android.commerce.CommerceMode
import com.gccwebos.android.commerce.CommercePanel
import com.gccwebos.android.portal.AccountController
import com.gccwebos.android.portal.AccountPanel
import com.gccwebos.android.portal.RegistrationController
import com.gccwebos.android.portal.Resource
import com.gccwebos.android.portal.NodeSelectionPanel
import com.gccwebos.android.portal.productionRepository
import com.gccwebos.android.update.UpdatePanel
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent { AppShell() }
    }
}

private enum class Destination(@StringRes val title: Int, @DrawableRes val icon: Int) {
    Connect(R.string.tab_connect, R.drawable.brand_plane),
    Lines(R.string.tab_lines, R.drawable.brand_globe),
    Plans(R.string.tab_plans, R.drawable.brand_receipt),
    Orders(R.string.tab_orders, R.drawable.brand_receipt),
    Profile(R.string.tab_profile, R.drawable.brand_user),
}

// Nodes and history remain destinations, reached from the component that owns them.
private val primaryDestinations = listOf(Destination.Connect, Destination.Plans, Destination.Profile)

/** The same lifecycle handler is used by production and memory-backed UI fixtures. */
internal class AccountForegroundObserver(private val account: AccountController) : LifecycleEventObserver {
    private var leftForeground = false
    override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_STOP) leftForeground = true
        if (event == Lifecycle.Event.ON_RESUME && leftForeground) {
            leftForeground = false; account.onForeground()
        }
    }
}

@Composable
internal fun AppShell(accountOverride: AccountController? = null, autoLoadCommerce: Boolean = true,
    commerceOverride: com.gccwebos.android.commerce.CommerceController? = null,
    registrationOpener: ((String) -> Unit)? = null,
    registrationOverride: RegistrationController? = null,
    commerceRecoveryOverride: com.gccwebos.android.commerce.CommerceRecovery? = null,
    paymentOpenerOverride: ((String) -> Unit)? = null) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    // The controller remains outside destinations, preserving in-flight authentication on navigation.
    val account = remember(accountOverride) { accountOverride ?: AccountController(productionRepository(context), scope) }
    val lifecycle = (LocalActivity.current as? ComponentActivity)?.lifecycle
    // Restore starts before the login form may fetch its first question. A child
    // composition must not take the authentication busy slot ahead of restore.
    var loginStartupReady by remember(account) { mutableStateOf(accountOverride != null) }
    LaunchedEffect(account) {
        if (accountOverride == null) account.restore()
        loginStartupReady = true
    }
    DisposableEffect(account, lifecycle) {
        val observer = AccountForegroundObserver(account)
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    var selectedName by rememberSaveable { mutableStateOf(Destination.Connect.name) }
    val selected = Destination.entries.firstOrNull { it.name == selectedName } ?: Destination.Connect
    BackHandler(enabled = selected != Destination.Connect) {
        if (selected == Destination.Profile && !account.authenticated &&
            (account.factorReference != null || account.busy)) account.cancelAuthentication()
        else selectedName = Destination.Connect.name
    }
    // Guidance belongs to the authenticated lifecycle, outside the keyed destination composition.
    var guidance by remember { mutableStateOf(ResourceGuidanceState()) }
    val currentGuidance = guidance.reconcile(
        authenticated = account.authenticated,
        accountKey = account.authenticatedAccountId ?: if (accountOverride != null) account.account.trim() else "",
        sessionGeneration = account.authSessionGeneration,
        resource = account.resource,
        busy = account.busy,
        error = account.error,
        onConnectionPage = selected == Destination.Connect,
    )
    SideEffect { if (guidance != currentGuidance) guidance = currentGuidance }
    GccTheme {
        Surface(Modifier.fillMaxSize(), color = Brand.Background) {
            Scaffold(containerColor = Brand.Background, bottomBar = {
                Surface(color = Brand.Background) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        NavigationBar(Modifier.widthIn(max = 760.dp).fillMaxWidth()
                            .heightIn(min = if (LocalDensity.current.fontScale > 1.3f) 112.dp else 80.dp),
                            containerColor = Brand.Surface, tonalElevation = 0.dp) {
                            primaryDestinations.forEach { destination ->
                                val title = stringResource(destination.title)
                                NavigationBarItem(
                                    modifier = Modifier.testTag("nav-${destination.name}"),
                                    selected = destination == selected,
                                    onClick = { selectedName = destination.name },
                                    icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                                    label = { Text(title) },
                                    colors = NavigationBarItemDefaults.colors(selectedIconColor = Brand.Cyan,
                                        selectedTextColor = Brand.Cyan, indicatorColor = Brand.SurfaceRaised,
                                        unselectedIconColor = Brand.Muted, unselectedTextColor = Brand.Muted),
                                )
                            }
                        }
                    }
                }
             }) { padding -> key(selected) { Page(selected, padding, account, autoLoadCommerce, commerceOverride,
                 commerceRecoveryOverride, paymentOpenerOverride,
                 openAccount = { selectedName = Destination.Profile.name }, openPlans = { selectedName = Destination.Plans.name },
                 openNodes = { selectedName = Destination.Lines.name }, registrationOpener = registrationOpener,
                 loginStartupReady = loginStartupReady, registrationOverride = registrationOverride) } }
            if (currentGuidance.visible) NoResourceGuidance(
                onDismiss = { guidance = currentGuidance.dismiss() },
                openPlans = { guidance = currentGuidance.dismiss(); selectedName = Destination.Plans.name },
                openWallet = { guidance = currentGuidance.dismiss(); selectedName = Destination.Profile.name },
            )
        }
    }
}

@Composable
private fun LoginScreen(account: AccountController, registrationOpener: ((String) -> Unit)?,
                         loginStartupReady: Boolean, registrationOverride: RegistrationController?,
                         padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
        Image(painterResource(R.drawable.brand_lounge), null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brand.Background.copy(alpha = .86f)))
        Column(Modifier.widthIn(max = 520.dp).fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp).testTag("login-screen"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BrandHeader()
            if (account.restoring || account.foregroundCheckPending) Column(Modifier.testTag("session-checking"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(color = Brand.Cyan)
                Text("正在验证已保存登录", style = MaterialTheme.typography.titleLarge)
                SupportingText("验证后显示账户资源。")
                TextButton(onClick = account::cancelAuthentication) { Text("取消并返回登录") }
            } else {
                if (account.localSubscription.hasCapability) Column(Modifier.testTag("local-subscription-notice"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("发现已保存的订阅交付", style = MaterialTheme.typography.titleMedium)
                    SupportingText("本地配置尚未就绪。可仅使用已保存的订阅链接重新读取，不需要重新领取或登录。")
                    BrandButton("重新读取本地订阅", account::restoreLocal, enabled = !account.busy, modifier = Modifier.testTag("local-subscription-retry"))
                }
                AccountPanel(account, compactLogin = true, registrationOpener = registrationOpener,
                    loginStartupReady = loginStartupReady, registrationOverride = registrationOverride)
            }
        }
    }
}

@Composable
private fun Page(destination: Destination, padding: PaddingValues, account: AccountController,
                  autoLoadCommerce: Boolean, commerceOverride: com.gccwebos.android.commerce.CommerceController?,
                  commerceRecoveryOverride: com.gccwebos.android.commerce.CommerceRecovery?, paymentOpenerOverride: ((String) -> Unit)?,
                  openAccount: () -> Unit, openPlans: () -> Unit, openNodes: () -> Unit,
                  registrationOpener: ((String) -> Unit)?, loginStartupReady: Boolean,
                  registrationOverride: RegistrationController?) {
    if (destination == Destination.Profile) {
        LaunchedEffect(account) { if (!account.authenticated && account.localSubscription.hasProfile) account.restoreAccount() }
        if (!account.authenticated && !account.retainingCommerceSession) {
            LoginScreen(account, registrationOpener, loginStartupReady, registrationOverride, padding)
            return
        }
    }
    val compact = destination == Destination.Connect || destination == Destination.Lines || destination == Destination.Plans || destination == Destination.Orders || destination == Destination.Profile
    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
        Image(painterResource(R.drawable.brand_lounge), null, contentScale = ContentScale.Crop,
            modifier = Modifier.widthIn(max = 680.dp).fillMaxSize())
        Box(Modifier.widthIn(max = 680.dp).fillMaxSize().background(Brand.Background.copy(alpha = .30f)))
        // Readable line lengths on tablets; no fixed-height account forms or data cards.
        Column(Modifier.widthIn(max = 680.dp).fillMaxSize()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = if (compact) 16.dp else 20.dp)
            .testTag("page-${destination.name}"),
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp)) {
            BrandHeader()
            when (destination) {
                Destination.Connect -> {
                    ConnectionOverview(account, openAccount, openPlans, openNodes)
                    if (account.authenticated && account.localSubscription.status == com.gccwebos.android.portal.LocalSubscriptionStatus.MISSING &&
                        !account.localSubscription.hasCapability && !account.localSubscription.hasProfile &&
                        (account.resource as? Resource.Available)?.validUntil?.let { it <= Instant.now() } == true)
                        BrandButton("购买／续订订阅", openPlans, modifier = Modifier.testTag("portal-expired-renew"))
                }
                Destination.Lines -> {
                    Text("订阅节点", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    LaunchedEffect(account.authSessionGeneration, account.authenticated) { account.nodeSelection.sync() }
                    NodeSelectionPanel(account.nodeSelection, account.nodes, account.resource,
                        account.localSubscription.hasCapability, account.busy || account.restoring || account.foregroundCheckPending,
                        openAccount)
                }
                Destination.Plans -> {
                    Text("套餐", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (!account.authenticated && !account.retainingCommerceSession) {
                        BrandCard(Modifier.testTag("commerce-signed-out")) {
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("∞", color = Brand.Cyan, style = MaterialTheme.typography.displayLarge,
                                    modifier = Modifier.clearAndSetSemantics {})
                                Text("选择适合你的套餐", style = MaterialTheme.typography.titleLarge)
                                SupportingText(if (account.restoring || account.foregroundCheckPending) "正在确认账户" else "当前套餐尚未加载")
                                BrandButton("登录查看当前套餐", openAccount,
                                    enabled = !account.busy && !account.restoring && !account.foregroundCheckPending,
                                    modifier = Modifier.testTag("anonymous-plans-login"))
                            }
                        }
                    } else {
                        CommercePanel(account, CommerceMode.Plans, stateOverride = commerceOverride, autoLoad = autoLoadCommerce, onRecharge = openAccount)
                        if (account.authenticated) ResourceCard(account)
                    }
                }
                Destination.Orders -> {
                    Text("我的订单", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    CommercePanel(account, CommerceMode.Orders, stateOverride = commerceOverride, autoLoad = autoLoadCommerce)
                }
                Destination.Profile -> {
                    com.gccwebos.android.commerce.MinePanel(account, autoLoadCommerce, commerceOverride, onReturnToPlans = openPlans,
                        recoveryOverride = commerceRecoveryOverride, paymentOpener = paymentOpenerOverride)
                    var details by remember(account.authSessionGeneration) { mutableStateOf(false) }
                    TextButton(onClick = { details = !details }, modifier = Modifier.heightIn(min = 48.dp).testTag("mine-more")) { Text(if (details) "收起更多" else "更多") }
                    if (details) {
                    BrandCard(Modifier.testTag("permissions-status")) {
                        Text("连接权限与后台运行", style = MaterialTheme.typography.titleMedium)
                        SupportingText("生产 VPN 仍未开放。后续连接会按需申请系统 VPN 授权与通知权限，后台状态由前台服务通知展示。")
                        SupportingText("当前不宣称已经完成锁屏、网络切换或系统回收后的连接恢复。主动停止、撤销授权或强制停止都应结束连接。")
                        SupportingText("无需位置、电话或广泛存储权限；不会自动修改电池优化设置。")
                    }
                    BrandCard { UpdatePanel() }
                    if (BuildConfig.DEBUG) BrandCard { DebugBuildPanel() }
                    }
                }
            }
            if (!compact) Text(stringResource(R.string.build_stage), color = Brand.Muted,
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.brand_robot_avatar), contentDescription = null,
            modifier = Modifier.size(42.dp), contentScale = ContentScale.Fit)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("飞连vpn", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, modifier = Modifier.testTag("brand-name"))
            Box(Modifier.width(112.dp).height(3.dp).background(Brush.horizontalGradient(
                listOf(androidx.compose.ui.graphics.Color(0xFFFF27DF), Brand.Blue, Brand.Cyan))))
        }
    }
}

@Composable
private fun CompactNodeList(nodes: List<com.gccwebos.android.vpn.SafeProxyConfig.NodeSummary>) {
    Surface(Modifier.fillMaxWidth(), shape = Brand.Shape, color = Brand.Surface.copy(alpha = .72f),
        border = BorderStroke(1.dp, Brand.Border)) {
        Column {
            nodes.forEachIndexed { position, node ->
                if (position > 0) HorizontalDivider(color = Brand.Border.copy(alpha = .6f))
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(14.dp).testTag("subscription-node-${node.index}"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource(R.drawable.brand_globe), null, tint = Brand.Cyan, modifier = Modifier.size(28.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(node.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("subscription-node-name-${node.index}"))
                        Text("${node.protocol.uppercase()} · ${if (node.supported) "可解析" else "暂不兼容"}",
                            color = if (node.supported) Brand.Muted else Brand.Amber, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("subscription-node-details-${node.index}"))
                    }
                }
            }
        }
    }
}

@Composable
private fun PageTitle(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        SupportingText(detail)
    }
}

@Composable
internal fun ResourceCard(state: AccountController, showOperationState: Boolean = true) {
    val countdownEnabled = state.authenticated && !state.busy && state.error == null && state.resource is Resource.Available
    val now by produceState(initialValue = Instant.now(), countdownEnabled, state.resource) {
        value = Instant.now()
        while (countdownEnabled) {
            delay(1_000)
            value = Instant.now()
        }
    }
    val display = resourcePresentation(state.authenticated, state.resource, state.busy, state.error, now)
    BrandCard(Modifier.testTag("resource-card")) {
        Eyebrow("当前资源")
        Text(display.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = if (showOperationState) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
        SupportingText(display.detail)
        display.countdown?.let { remaining ->
            SupportingText("剩余有效时间")
            // Readable when focused, without announcing a ticking live region every second.
            Text(remaining, style = MaterialTheme.typography.titleMedium, color = Brand.Cyan,
                modifier = Modifier.testTag("resource-countdown"))
        }
        display.facts.forEach { (label, value) ->
            HorizontalDivider(color = Brand.Border)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SupportingText(label)
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (showOperationState && state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Brand.Cyan)
        if (showOperationState) state.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("resource-error").semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}

@Composable
private fun TelegramPanel() {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    val url = "https://t.me/flylinevpn"
    BrandCard(Modifier.testTag("official-telegram")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.drawable.brand_telegram), null, modifier = Modifier.size(32.dp))
            Text("官方 Telegram 频道", style = MaterialTheme.typography.titleLarge)
        }
        SupportingText("@flylinevpn · 公告与动态")
        SupportingText("频道不是一对一客服入口。请勿公开账户、订单、付款资料或订阅链接。")
        BrandButton("前往 Telegram", {
            try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            catch (_: Exception) { unavailable = true }
        }, modifier = Modifier.testTag("telegram-open"))
        TextButton(onClick = {
            (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("官方频道", url))
            copied = true
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (copied) "频道链接已复制" else "复制频道链接") }
        if (unavailable) SupportingText("无法打开时，可复制链接后在 Telegram 或支持访问的浏览器中打开。")
    }
}

@Composable
private fun InfoCard(title: String, detail: String) {
    BrandCard {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        SupportingText(detail)
    }
}


@Composable
private fun NoResourceGuidance(onDismiss: () -> Unit, openPlans: () -> Unit, openWallet: () -> Unit) {
    AlertDialog(
        modifier = Modifier.testTag("no-resource-guidance"),
        onDismissRequest = onDismiss,
        title = { Text("还没有订阅资源") },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            Text("服务器已确认当前账号暂无资源。可以查看套餐购买，或先到钱包充值；到账与资源发放以服务器确认为准。")
        } },
        confirmButton = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = openPlans, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("guidance-plans")) { Text("查看套餐") }
            TextButton(onClick = openWallet, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("guidance-wallet")) { Text("前往钱包充值") }
            // Keep all three actions in one measured column. Separate AlertDialog action slots
            // can overlap a full-width multi-row confirm slot at real system font_scale=2.0.
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("guidance-dismiss")) { Text("稍后再说") }
        } },
    )
}
