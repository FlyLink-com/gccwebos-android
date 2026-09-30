package com.gccwebos.android

import android.os.Bundle
import androidx.activity.SystemBarStyle
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
import com.gccwebos.android.portal.AccountController
import com.gccwebos.android.portal.AccountPanel
import com.gccwebos.android.portal.productionRepository
import com.gccwebos.android.update.UpdatePanel

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
    Profile(R.string.tab_profile, R.drawable.brand_user),
}

@Composable
internal fun AppShell() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    // The controller remains outside destinations, preserving in-flight authentication on navigation.
    val account = remember { AccountController(productionRepository(context), scope) }
    var selectedName by rememberSaveable { mutableStateOf(Destination.Connect.name) }
    val selected = Destination.entries.firstOrNull { it.name == selectedName } ?: Destination.Connect
    GccTheme {
        Surface(Modifier.fillMaxSize(), color = Brand.Background) {
            Scaffold(containerColor = Brand.Background, bottomBar = {
                Surface(color = Brand.Background) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        NavigationBar(Modifier.widthIn(max = 760.dp).fillMaxWidth(),
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
            }) { padding -> key(selected) { Page(selected, padding, account) { selectedName = Destination.Profile.name } } }
        }
    }
}

@Composable
private fun Page(destination: Destination, padding: PaddingValues, account: AccountController,
                 openAccount: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
        Image(painterResource(R.drawable.brand_lounge), null, contentScale = ContentScale.Crop,
            modifier = Modifier.widthIn(max = 680.dp).fillMaxSize())
        Box(Modifier.widthIn(max = 680.dp).fillMaxSize().background(Brand.Background.copy(alpha = .82f)))
        // Readable line lengths on tablets; no fixed-height account forms or data cards.
        Column(Modifier.widthIn(max = 680.dp).fillMaxSize()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp)
            .testTag("page-${destination.name}"),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            BrandHeader()
            when (destination) {
                Destination.Connect -> {
                    WelcomeCard(account.authenticated)
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
                    ResourceCard(account)
                    if (!account.authenticated) BrandButton("登录并同步资源", openAccount,
                        Modifier.testTag("open-account"))
                }
                Destination.Lines -> {
                    PageTitle("你的授权线路", "线路跟随账号权益，安全交付到这台设备。")
                    BrandCard {
                        Icon(painterResource(R.drawable.brand_globe), null, tint = Brand.Cyan,
                            modifier = Modifier.size(36.dp))
                        Text("线路尚未准备好", style = MaterialTheme.typography.titleLarge)
                        SupportingText("订阅可在「我的」登录后同步。当前版本尚未将订阅配置接入生产 VPN，不展示示例节点或虚构延迟。")
                        BrandButton("查看账号与订阅", openAccount)
                    }
                    InfoCard("安全交付", "配置由服务器按当前账号授权返回；订阅地址不会显示在页面中。")
                }
                Destination.Plans -> {
                    PageTitle("套餐与用量", "这里仅显示服务器确认的当前资源。")
                    ResourceCard(account)
                    if (!account.authenticated) BrandButton("登录查看套餐", openAccount)
                    InfoCard("购买入口尚未开放", "原生报价、付款和订单流程接入后开放购买。当前没有本地示例价格，也不会扣款。")
                }
                Destination.Profile -> {
                    PageTitle("我的账号", "账号、订阅与版本信息，一处管理。")
                    AccountPanel(account)
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
        Image(painterResource(R.drawable.brand_wordmark), contentDescription = "gccwebos",
            modifier = Modifier.width(148.dp).height(42.dp), contentScale = ContentScale.Fit)
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
                Eyebrow("GCCWEBOS / CONNECT")
                Text(if (authenticated) "欢迎回来" else "欢迎使用", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
                SupportingText("gccwebos · 与你同行")
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
    val display = resourcePresentation(state.authenticated, state.resource)
    BrandCard(Modifier.testTag("resource-card")) {
        Eyebrow("当前资源")
        Text(display.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = if (showOperationState) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
        SupportingText(display.detail)
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
private fun InfoCard(title: String, detail: String) {
    BrandCard {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        SupportingText(detail)
    }
}
