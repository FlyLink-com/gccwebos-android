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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

// Nodes are a primary destination; account-owned order and ledger history stays inside My.
private val primaryDestinations = listOf(Destination.Connect, Destination.Lines, Destination.Plans, Destination.Profile)

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
    paymentOpenerOverride: ((String) -> Unit)? = null,
    connectionControlState: ConnectionControlState = ConnectionControlState(),
    onConnectionPowerAction: ((ConnectionPowerAction) -> Unit)? = null,
    glassMode: GlassMode? = null) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    // The controller remains outside destinations, preserving in-flight authentication on navigation.
    val account = remember(accountOverride) { accountOverride ?: AccountController(productionRepository(context), scope) }
    // Keep the local opaque selection synchronized outside keyed destinations; this never downloads.
    LaunchedEffect(account, account.authSessionGeneration, account.localSubscription, account.busy,
        account.restoring, account.foregroundCheckPending, account.clearing) { account.nodeSelection.sync() }
    // Keep an accepted power command outside keyed destinations. Navigation cannot replay it
    // while the controller is still publishing acceptance/rejection. A new snapshot/revision is the ack.
    var acceptedConnectionCommand by remember(connectionControlState, account) { mutableStateOf(false) }
    val powerControl = connectionControlState.copy(commandPending = connectionControlState.commandPending || acceptedConnectionCommand)
    val powerAction: ((ConnectionPowerAction) -> Unit)? = onConnectionPowerAction?.let { handler ->
        { action ->
            if (!acceptedConnectionCommand && !connectionControlState.commandPending && action != ConnectionPowerAction.None) {
                acceptedConnectionCommand = true
                handler(action)
            }
        }
    }
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
    var startRegistration by rememberSaveable { mutableStateOf(false) }
    val selected = Destination.entries.firstOrNull { it.name == selectedName } ?: Destination.Connect
    BackHandler(enabled = selected != Destination.Connect) {
        if (selected == Destination.Profile && !account.authenticated &&
            (account.factorReference != null || account.busy)) account.cancelAuthentication()
        else selectedName = Destination.Connect.name
    }
    GccTheme(materialMode = glassMode) {
        Surface(Modifier.fillMaxSize(), color = Brand.Background) {
            // One continuous backdrop also remains visible behind the floating navigation bar.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                val loginPage = selected == Destination.Profile && !account.authenticated && !account.retainingCommerceSession
                val backdropModifier = if (loginPage) Modifier.fillMaxSize() else Modifier.widthIn(max = 680.dp).fillMaxSize()
                BrandBackdrop(backdropModifier, loginPage = loginPage)
                Scaffold(containerColor = Color.Transparent, bottomBar = {
                    Box(Modifier.fillMaxWidth().navigationBarsPadding()
                        .padding(start = 14.dp, end = 14.dp, bottom = 10.dp), contentAlignment = Alignment.Center) {
                        GlassSurface(Modifier.widthIn(max = 760.dp).fillMaxWidth().testTag("glass-navigation"),
                            shape = RoundedCornerShape(25.dp), kind = GlassSurfaceKind.Navigation) {
                            // A minimum, not a fixed height: wrapped or enlarged labels grow this row.
                            Row(Modifier.fillMaxWidth().selectableGroup().heightIn(min = 70.dp).padding(horizontal = 6.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                primaryDestinations.forEach { destination ->
                                    val title = stringResource(destination.title)
                                    val chosen = destination == selected
                                    Column(Modifier.weight(1f).heightIn(min = 48.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(if (chosen) Brand.Cyan.copy(alpha = .15f) else Color.Transparent)
                                        .testTag("nav-${destination.name}")
                                        .selectable(selected = chosen, onClick = { if (destination == Destination.Profile) startRegistration = false; selectedName = destination.name }, role = Role.Tab)
                                        .padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(painterResource(destination.icon), contentDescription = null,
                                            tint = if (chosen) Brand.Cyan else Brand.Muted, modifier = Modifier.size(23.dp))
                                        Text(title, color = if (chosen) Brand.Cyan else Brand.Muted,
                                            style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                                    }
                                }
                            }
                        }
                    }
                }) { padding ->
                    // Scaffold measures the entire bar, its gap and system inset for content clearance.
                    key(selected) { Page(selected, padding, account, autoLoadCommerce, commerceOverride,
                        commerceRecoveryOverride, paymentOpenerOverride, powerControl, powerAction,
                        openAccount = { startRegistration = false; selectedName = Destination.Profile.name }, openPlans = { selectedName = Destination.Plans.name },
                        openNodes = { selectedName = Destination.Lines.name },
                        openRegistration = { startRegistration = true; selectedName = Destination.Profile.name },
                        startRegistration = startRegistration, registrationOpener = registrationOpener,
                        loginStartupReady = loginStartupReady, registrationOverride = registrationOverride) }
                }

            }
        }
    }
}

@Composable
private fun LoginScreen(account: AccountController, registrationOpener: ((String) -> Unit)?,
                         loginStartupReady: Boolean, registrationOverride: RegistrationController?,
                         padding: PaddingValues, startRegistration: Boolean) {
    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
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
                    loginStartupReady = loginStartupReady, registrationOverride = registrationOverride, startRegistration = startRegistration)
            }
        }
    }
}

@Composable
private fun Page(destination: Destination, padding: PaddingValues, account: AccountController,
                  autoLoadCommerce: Boolean, commerceOverride: com.gccwebos.android.commerce.CommerceController?,
                  commerceRecoveryOverride: com.gccwebos.android.commerce.CommerceRecovery?, paymentOpenerOverride: ((String) -> Unit)?,
                  connectionControlState: ConnectionControlState, onConnectionPowerAction: ((ConnectionPowerAction) -> Unit)?,
                  openAccount: () -> Unit, openPlans: () -> Unit, openNodes: () -> Unit,
                  openRegistration: () -> Unit, startRegistration: Boolean,
                  registrationOpener: ((String) -> Unit)?, loginStartupReady: Boolean,
                  registrationOverride: RegistrationController?) {
    if (destination == Destination.Profile) {
        LaunchedEffect(account) { if (!account.authenticated && account.localSubscription.hasProfile) account.restoreAccount() }
        if (!account.authenticated && !account.retainingCommerceSession) {
            LoginScreen(account, registrationOpener, loginStartupReady, registrationOverride, padding, startRegistration)
            return
        }
    }
    val compact = destination == Destination.Connect || destination == Destination.Lines || destination == Destination.Plans || destination == Destination.Orders || destination == Destination.Profile
    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
        // Readable line lengths on tablets; no fixed-height account forms or data cards.
        Column(Modifier.widthIn(max = 680.dp).fillMaxSize()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = if (compact) 16.dp else 20.dp)
            .testTag("page-${destination.name}"),
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp)) {
            BrandHeader()
            when (destination) {
                Destination.Connect -> {
                    ConnectionOverview(account, openAccount, openPlans, openNodes, connectionControlState,
                        onConnectionPowerAction, openRegistration)
                }
                Destination.Lines -> {
                    Text("订阅节点", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    LaunchedEffect(account.authSessionGeneration, account.authenticated) { account.nodeSelection.sync() }
                    NodeSelectionPanel(account.nodeSelection, account.nodes, account.resource,
                        account.localSubscription.hasCapability, account.busy || account.restoring || account.foregroundCheckPending,
                        authenticated = account.authenticated, openAccount = openAccount)
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
                    TextButton(onClick = { details = !details }, modifier = Modifier.heightIn(min = 48.dp).testTag("mine-more")) {
                        Text(if (details) "收起关于与更新" else "关于与更新")
                    }
                    if (details) BrandCard { UpdatePanel() }
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

