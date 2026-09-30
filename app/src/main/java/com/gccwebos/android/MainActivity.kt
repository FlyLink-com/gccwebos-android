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
import androidx.compose.foundation.background
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
import com.gccwebos.android.portal.Resource
import com.gccwebos.android.portal.productionRepository
import com.gccwebos.android.update.UpdatePanel
import java.time.Instant
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

@Composable
internal fun AppShell(accountOverride: AccountController? = null, autoLoadCommerce: Boolean = true,
    commerceOverride: com.gccwebos.android.commerce.CommerceController? = null) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    // The controller remains outside destinations, preserving in-flight authentication on navigation.
    val account = remember(accountOverride) { accountOverride ?: AccountController(productionRepository(context), scope) }
    val lifecycle = (LocalActivity.current as? ComponentActivity)?.lifecycle
    LaunchedEffect(account) { if (accountOverride == null) account.restore() }
    DisposableEffect(account, lifecycle) {
        var leftForeground = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) leftForeground = true
            if (event == Lifecycle.Event.ON_RESUME && leftForeground && accountOverride == null) {
                leftForeground = false; account.onForeground()
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    var selectedName by rememberSaveable { mutableStateOf(Destination.Connect.name) }
    val selected = Destination.entries.firstOrNull { it.name == selectedName } ?: Destination.Connect
    val hasLocal = account.localSubscription.hasProfile
    BackHandler(enabled = !account.authenticated && (account.factorReference != null || account.busy || (hasLocal && selected != Destination.Connect))) {
        if (account.factorReference != null || account.busy) account.cancelAuthentication()
        else selectedName = Destination.Connect.name
    }
    // Guidance belongs to the authenticated lifecycle, outside the keyed destination composition.
    var guidance by remember { mutableStateOf(ResourceGuidanceState()) }
    val currentGuidance = guidance.reconcile(
        authenticated = account.authenticated,
        accountKey = account.account.trim(),
        sessionGeneration = account.authSessionGeneration,
        resource = account.resource,
        busy = account.busy,
        error = account.error,
        onConnectionPage = selected == Destination.Connect,
    )
    SideEffect { if (guidance != currentGuidance) guidance = currentGuidance }
    GccTheme {
        Surface(Modifier.fillMaxSize(), color = Brand.Background) {
            if ((!account.authenticated && !hasLocal) || ((account.restoring || account.foregroundCheckPending) && !hasLocal)) {
                LoginScreen(account)
                return@Surface
            }
            Scaffold(containerColor = Brand.Background, bottomBar = {
                Surface(color = Brand.Background) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        NavigationBar(Modifier.widthIn(max = 760.dp).fillMaxWidth()
                            .heightIn(min = if (LocalDensity.current.fontScale > 1.3f) 112.dp else 80.dp),
                            containerColor = Brand.Surface, tonalElevation = 0.dp) {
                            Destination.entries.forEach { destination ->
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
                openAccount = { selectedName = Destination.Profile.name }, openPlans = { selectedName = Destination.Plans.name }) } }
            if (currentGuidance.visible) NoResourceGuidance(
                onDismiss = { guidance = currentGuidance.dismiss() },
                openPlans = { guidance = currentGuidance.dismiss(); selectedName = Destination.Plans.name },
                openWallet = { guidance = currentGuidance.dismiss(); selectedName = Destination.Profile.name },
            )
        }
    }
}

@Composable
private fun LoginScreen(account: AccountController) {
    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
        Image(painterResource(R.drawable.brand_lounge), null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brand.Background.copy(alpha = .86f)))
        Column(Modifier.widthIn(max = 520.dp).fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp).testTag("login-screen"),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            BrandHeader()
            Text("安全连接，从登录开始", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            SupportingText("欢迎使用 飞连vpn")
            if (account.restoring || account.foregroundCheckPending) BrandCard(Modifier.testTag("session-checking")) {
                CircularProgressIndicator(color = Brand.Cyan)
                Text("正在验证已保存登录", style = MaterialTheme.typography.titleLarge)
                SupportingText("服务器确认之前不会进入主页。")
                TextButton(onClick = account::cancelAuthentication) { Text("取消并返回登录") }
            } else {
                if (account.localSubscription.hasCapability) BrandCard {
                    Text("发现已保存的订阅交付", style = MaterialTheme.typography.titleMedium)
                    SupportingText("本地配置尚未就绪。可仅使用已保存的订阅链接重新读取，不需要重新领取或登录。")
                    BrandButton("重新读取本地订阅", account::restoreLocal, enabled = !account.busy, modifier = Modifier.testTag("local-subscription-retry"))
                }
                AccountPanel(account)
            }
            SupportingText("本地订阅用于 VPN 页面，不随钱包账户登录到期而删除。钱包、充值和订单仍需有效账户会话；不保存密码。")
        }
    }
}

@Composable
private fun Page(destination: Destination, padding: PaddingValues, account: AccountController,
                 autoLoadCommerce: Boolean, commerceOverride: com.gccwebos.android.commerce.CommerceController?, openAccount: () -> Unit, openPlans: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
        Image(painterResource(R.drawable.brand_lounge), null, contentScale = ContentScale.Crop,
            modifier = Modifier.widthIn(max = 680.dp).fillMaxSize())
        Box(Modifier.widthIn(max = 680.dp).fillMaxSize().background(Brand.Background.copy(alpha = .30f)))
        // Readable line lengths on tablets; no fixed-height account forms or data cards.
        Column(Modifier.widthIn(max = 680.dp).fillMaxSize()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp)
            .testTag("page-${destination.name}"),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            BrandHeader()
            when (destination) {
                Destination.Connect -> {
                    WelcomeCard(account.authenticated || account.localSubscription.hasProfile)
                    BrandCard {
                        Eyebrow("连接状态")
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(painterResource(R.drawable.brand_shield), null, tint = Brand.Cyan,
                                modifier = Modifier.size(30.dp))
                            Text("尚未连接", style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold)
                        }
                        SupportingText(stringResource(R.string.connection_explanation))
                        BrandButton("连接暂未开放", {}, enabled = false,
                            modifier = Modifier.testTag("vpn-connect"))
                        SupportingText("当前不会建立 VPN 隧道，也不展示未经测量的速度或延迟。")
                    }
                    LocalSubscriptionCard(account, openAccount, openPlans)
                    if (account.authenticated) {
                        ResourceCard(account)
                        if ((account.resource as? Resource.Available)?.validUntil?.let { it <= Instant.now() } == true)
                            BrandButton("购买／续订订阅", openPlans, modifier = Modifier.testTag("portal-expired-renew"))
                    }
                    CommercePanel(account, CommerceMode.Overview, stateOverride = commerceOverride, autoLoad = autoLoadCommerce)
                    if (!account.authenticated) BrandButton("登录并同步资源", openAccount,
                        Modifier.testTag("open-account"))
                }
                Destination.Lines -> {
                    PageTitle("你的授权线路", "线路跟随账号权益，安全交付到这台设备。")
                    if (account.nodes.isEmpty()) BrandCard {
                        Text(if (account.busy) "正在读取订阅线路" else "尚未读取到线路", style = MaterialTheme.typography.titleLarge)
                        SupportingText(account.importStatus?.let { com.gccwebos.android.portal.subscriptionImportMessage(it) }
                            ?: "请同步账号订阅；这里仅展示本次实际解析的节点，不使用示例地区或虚构延迟。")
                        BrandButton("刷新订阅线路", account::refresh, enabled = !account.busy)
                    }
                    account.nodes.forEach { node -> BrandCard(Modifier.testTag("subscription-node-${node.index}")) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(painterResource(R.drawable.brand_globe), null, tint = Brand.Cyan, modifier = Modifier.size(28.dp))
                            Column(Modifier.weight(1f)) {
                                Text(node.name, style = MaterialTheme.typography.titleMedium)
                                SupportingText(node.protocol.uppercase())
                            }
                        }
                        SupportingText(if (node.supported) "离线格式已支持 · 尚未连接" else "当前配置形态不受支持 · 不会尝试连接")
                    } }
                    InfoCard("实际订阅节点", "仅显示订阅内的名称与协议，不显示地址或凭据。节点顺序不是稳定线路身份；生产连接仍未开放。")
                }
                Destination.Plans -> {
                    PageTitle("选择套餐", "套餐与报价以服务器返回为准。")
                    CommercePanel(account, CommerceMode.Plans, stateOverride = commerceOverride, autoLoad = autoLoadCommerce, onRecharge = openAccount)
                    if (!account.authenticated) BrandButton("登录查看套餐", openAccount)
                    ResourceCard(account)
                }
                Destination.Orders -> {
                    PageTitle("我的订单", "商城、充值与套餐计划，分别核对进度。")
                    CommercePanel(account, CommerceMode.Orders, stateOverride = commerceOverride, autoLoad = autoLoadCommerce)
                }
                Destination.Profile -> {
                    LaunchedEffect(Unit) { if (!account.authenticated && account.localSubscription.hasProfile) account.restoreAccount() }
                    com.gccwebos.android.commerce.MinePanel(account, autoLoadCommerce, commerceOverride, onReturnToPlans = openPlans)
                    TelegramPanel()
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
            Text(stringResource(R.string.build_stage), color = Brand.Muted,
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
            Text("clash|飞连", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, modifier = Modifier.testTag("brand-name"))
            Box(Modifier.width(112.dp).height(3.dp).background(Brush.horizontalGradient(
                listOf(androidx.compose.ui.graphics.Color(0xFFFF27DF), Brand.Blue, Brand.Cyan))))
        }
    }
}

@Composable
private fun WelcomeCard(authenticated: Boolean) {
    val largeText = LocalDensity.current.fontScale > 1.3f
    Surface(shape = Brand.Shape, color = Brand.Surface, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.heightIn(min = if (largeText) 136.dp else 156.dp)) {
            // Preserve Library pixels. Omit decorative art at large font sizes to protect text space.
            if (!largeText) Image(painterResource(R.drawable.brand_robot_welcome), contentDescription = null,
                contentScale = ContentScale.Fit, alignment = Alignment.CenterEnd,
                modifier = Modifier.align(Alignment.CenterEnd).width(140.dp).height(170.dp))
            Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
                listOf(Brand.Background.copy(alpha = .94f), Brand.Background.copy(alpha = .62f),
                    Brand.Background.copy(alpha = .12f)))))
            Column(Modifier.fillMaxWidth(if (largeText) 1f else .64f).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("连接")
                Text(if (authenticated) "欢迎回来" else "欢迎使用", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
                SupportingText("${stringResource(R.string.app_name)} · 与你同行")
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
private fun LocalSubscriptionCard(account: AccountController, openAccount: () -> Unit, openPlans: () -> Unit) {
    val original = account.localSubscription
    val now by produceState(Instant.now(), original.validUntil) {
        while (true) { value = Instant.now(); delay(1_000) }
    }
    val value = if (original.status == com.gccwebos.android.portal.LocalSubscriptionStatus.READY && original.validUntil?.let { it <= now } == true)
        original.copy(status = com.gccwebos.android.portal.LocalSubscriptionStatus.EXPIRED) else original
    if (!value.hasCapability && !value.hasProfile) return
    BrandCard(Modifier.testTag("local-subscription-card")) {
        Eyebrow("本地订阅")
        val title = when (value.status) {
            com.gccwebos.android.portal.LocalSubscriptionStatus.READY -> "本地订阅已保存"
            com.gccwebos.android.portal.LocalSubscriptionStatus.EXPIRED -> "订阅已到期"
            com.gccwebos.android.portal.LocalSubscriptionStatus.INVALID_LINK -> "订阅链接需要恢复"
            com.gccwebos.android.portal.LocalSubscriptionStatus.UNSUPPORTED -> "配置暂不受支持"
            com.gccwebos.android.portal.LocalSubscriptionStatus.STORAGE_ERROR -> "本地安全存储需要处理"
            else -> "订阅更新尚未完成"
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
        value.validUntil?.let { SupportingText("上次服务器确认有效期：$it") }
        SupportingText("本地订阅与钱包登录分开管理。每次连接仍需重新拉取并校验订阅；不会用旧缓存直接启动隧道。")
        BrandButton("刷新订阅配置", account::refreshSubscription, enabled = !account.busy, modifier = Modifier.testTag("local-subscription-refresh"))
        if (value.status == com.gccwebos.android.portal.LocalSubscriptionStatus.EXPIRED) {
            SupportingText("有效期已到，请查看套餐购买或续订；购买时再登录账户。")
            BrandButton("购买／续订订阅", openPlans, modifier = Modifier.testTag("expired-subscription-renew"))
        } else if (value.status == com.gccwebos.android.portal.LocalSubscriptionStatus.INVALID_LINK) {
            BrandButton("登录恢复订阅", openAccount)
        } else if (value.status == com.gccwebos.android.portal.LocalSubscriptionStatus.RETRY_REQUIRED) {
            SupportingText("网络或服务暂未确认，不代表订阅已过期；请重试。")
        }
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
