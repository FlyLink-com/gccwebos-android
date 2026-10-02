package com.gccwebos.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gccwebos.android.design.*
import com.gccwebos.android.portal.AccountController
import com.gccwebos.android.portal.LocalSubscriptionSnapshot
import com.gccwebos.android.portal.LocalSubscriptionStatus
import java.time.Instant
import java.time.Duration
import kotlinx.coroutines.delay

internal enum class ConnectionVisualTone { Neutral, Connecting, Connected, Error }
internal enum class ConnectionPhase { Unavailable, Disconnected, Connecting, Connected, Disconnecting, Failed }
internal enum class ConnectionFailure { NetworkUnavailable, ResourceUnavailable, ReadFailed, Other }
internal enum class ConnectionPowerAction { None, Connect, Cancel, Disconnect }

/** Read-only UI input from a future controller, never inferred from saved configuration.
 * The controller must publish a new snapshot/revision when it accepts or rejects a command.
 * Defaults deliberately expose no working engine, cancellation or disconnect action.
 */
internal data class ConnectionControlState(
    val phase: ConnectionPhase = ConnectionPhase.Unavailable,
    val failure: ConnectionFailure? = null,
    val canConnect: Boolean = false,
    val canCancel: Boolean = false,
    val canDisconnect: Boolean = false,
    val commandPending: Boolean = false,
    val revision: Long = 0,
)

internal data class ConnectionOverviewPresentation(
    val status: LocalSubscriptionStatus,
    val title: String,
    val connectionText: String,
    val tone: ConnectionVisualTone,
    val countdown: String?,
    val usageText: String = "暂不可用",
    val action: ConnectionPowerAction = ConnectionPowerAction.None,
    val powerLabel: String = "连接暂不可用",
)

/** Account usage is absent: it cannot prove usage belongs to this local capability.
 * READY is configuration evidence only; it can never produce Connecting or Connected.
 */
internal fun connectionOverviewPresentation(
    snapshot: LocalSubscriptionSnapshot,
    now: Instant,
    control: ConnectionControlState = ConnectionControlState(),
): ConnectionOverviewPresentation {
    val status = if (snapshot.status == LocalSubscriptionStatus.READY && snapshot.hasProfile && snapshot.hasCapability &&
        snapshot.validUntil?.let { it <= now } == true) LocalSubscriptionStatus.EXPIRED else snapshot.status
    val confirmedReady = status == LocalSubscriptionStatus.READY && snapshot.hasProfile && snapshot.hasCapability &&
        snapshot.validUntil?.let { it > now } == true
    val localError = status in setOf(LocalSubscriptionStatus.EXPIRED, LocalSubscriptionStatus.RETRY_REQUIRED,
        LocalSubscriptionStatus.INVALID_LINK, LocalSubscriptionStatus.UNSUPPORTED, LocalSubscriptionStatus.STORAGE_ERROR)
    val connectionText = when (control.phase) {
        ConnectionPhase.Unavailable, ConnectionPhase.Disconnected, ConnectionPhase.Failed -> "未连接"
        ConnectionPhase.Connecting -> "连接中"
        ConnectionPhase.Connected -> "已连接"
        ConnectionPhase.Disconnecting -> "断开中"
    }
    val title = when {
        localError -> when (status) {
            LocalSubscriptionStatus.EXPIRED -> "订阅已过期"
            LocalSubscriptionStatus.RETRY_REQUIRED -> "读取失败"
            LocalSubscriptionStatus.INVALID_LINK -> "订阅资源不可用"
            LocalSubscriptionStatus.UNSUPPORTED -> "配置暂不支持"
            LocalSubscriptionStatus.STORAGE_ERROR -> "本地存储异常"
            else -> error("Local error requires an explicit local status")
        }
        control.phase == ConnectionPhase.Failed -> when (control.failure) {
            ConnectionFailure.NetworkUnavailable -> "无网络"
            ConnectionFailure.ResourceUnavailable -> "订阅资源不可用"
            ConnectionFailure.ReadFailed -> "读取失败"
            ConnectionFailure.Other, null -> "连接异常"
        }
        status == LocalSubscriptionStatus.MISSING -> "未配置"
        !confirmedReady -> "订阅待确认"
        control.phase == ConnectionPhase.Unavailable -> "连接暂不可用"
        else -> ""
    }
    val tone = when {
        localError || control.phase == ConnectionPhase.Failed -> ConnectionVisualTone.Error
        control.phase == ConnectionPhase.Connecting || control.phase == ConnectionPhase.Disconnecting -> ConnectionVisualTone.Connecting
        control.phase == ConnectionPhase.Connected -> ConnectionVisualTone.Connected
        else -> ConnectionVisualTone.Neutral
    }
    val countdown = when {
        status == LocalSubscriptionStatus.EXPIRED -> "已到期"
        confirmedReady -> snapshot.validUntil?.let { remainingValidity(it, now) }
        else -> null
    }
    val action = if (control.commandPending) ConnectionPowerAction.None else when (control.phase) {
        ConnectionPhase.Disconnected, ConnectionPhase.Failed ->
            if (control.canConnect && confirmedReady) ConnectionPowerAction.Connect else ConnectionPowerAction.None
        ConnectionPhase.Connecting -> if (control.canCancel) ConnectionPowerAction.Cancel else ConnectionPowerAction.None
        ConnectionPhase.Connected -> if (control.canDisconnect) ConnectionPowerAction.Disconnect else ConnectionPowerAction.None
        ConnectionPhase.Unavailable, ConnectionPhase.Disconnecting -> ConnectionPowerAction.None
    }
    val powerLabel = when (action) {
        ConnectionPowerAction.Connect -> if (control.phase == ConnectionPhase.Failed) "重试连接" else "连接"
        ConnectionPowerAction.Cancel -> "取消连接"
        ConnectionPowerAction.Disconnect -> "断开连接"
        ConnectionPowerAction.None -> when (control.phase) {
            ConnectionPhase.Connecting -> "连接中"
            ConnectionPhase.Connected -> "已连接"
            ConnectionPhase.Disconnecting -> "断开中"
            else -> "连接暂不可用"
        }
    }
    return ConnectionOverviewPresentation(status, title, connectionText, tone, countdown,
        action = action, powerLabel = powerLabel)
}

/** Welcome and account overview are independent of local configuration and engine admission. */
@Composable
internal fun ConnectionOverview(
    account: AccountController,
    openAccount: () -> Unit,
    openPlans: () -> Unit,
    openNodes: () -> Unit,
    control: ConnectionControlState = ConnectionControlState(),
    onPowerAction: ((ConnectionPowerAction) -> Unit)? = null,
    openRegistration: () -> Unit = openAccount,
    nowSource: () -> Instant = Instant::now,
) {
    val snapshot = account.localSubscription
    val usage by account.accountUsage.state.collectAsState()
    val clock by rememberUpdatedState(nowSource)
    val now by produceState(clock(), snapshot.validUntil) {
        value = clock()
        while (snapshot.validUntil != null) { delay(1_000); value = clock() }
    }
    // The account card wakes only at actual cycle/service boundaries, without another GET.
    val usageNow by produceState(clock(), usage.resource, account.authSessionGeneration, usage.verifying) {
        while (true) {
            value = clock()
            val boundary = nextAccountUsageBoundary(usage.resource, value) ?: break
            delay((Duration.between(value, boundary).toMillis() + 1).coerceAtLeast(1))
        }
    }
    var synchronizedNodeGeneration by remember(account) { mutableStateOf<Long?>(null) }
    LaunchedEffect(account, account.authSessionGeneration, snapshot, now) {
        account.nodeSelection.sync() // Local validity only. No configuration import or HTTP.
        synchronizedNodeGeneration = account.authSessionGeneration
    }
    val nodeSnapshot = account.nodeSelection.snapshot
    val selectedNode = nodeSnapshot.choices.firstOrNull { it.selection === nodeSnapshot.selected }?.takeIf {
        synchronizedNodeGeneration == account.authSessionGeneration && !account.nodeSelection.busy &&
            nodeSnapshot.status == LocalSubscriptionStatus.READY && nodeSnapshot.validUntil?.let { until -> until > now } != false
    }
    val local = connectionOverviewPresentation(snapshot, now, control)
    val operable = !account.busy && !account.restoring && !account.foregroundCheckPending && !account.clearing
    val verifiedUsage = account.authenticated && usage.authenticated && !usage.verifying
    val display = accountUsagePresentation(if (verifiedUsage) usage.resource else null, usageNow, usage.notUpdated)
    var showGuestChoice by remember(account.authSessionGeneration) { mutableStateOf(false) }
    var acceptedCommand by remember(control, account) { mutableStateOf(false) }
    // Only an actual controller phase may expose a connection command. Production still has no engine.
    val controlMode = control.phase in setOf(ConnectionPhase.Connecting, ConnectionPhase.Connected,
        ConnectionPhase.Disconnecting) || control.canConnect || control.commandPending
    val controlEnabled = onPowerAction != null && !acceptedCommand && local.action != ConnectionPowerAction.None &&
        (local.action != ConnectionPowerAction.Connect || !account.clearing)
    Column(Modifier.fillMaxWidth().testTag("connection-overview"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BrandCard(Modifier.testTag("connection-account-surface")) {
            ConnectionWelcome()
            Text(local.connectionText, style = MaterialTheme.typography.bodySmall,
                color = if (control.phase == ConnectionPhase.Connected) Brand.Green else Brand.Muted,
                modifier = Modifier.testTag("connection-status"))
            AccountUsageCard(display, authenticated = account.authenticated,
                onRefresh = account::refreshAccountUsage, onLogin = openAccount, onRegister = openRegistration,
                loading = usage.busy || usage.verifying || account.restoring || account.foregroundCheckPending,
                error = usage.error, refreshEnabled = verifiedUsage,
                primaryLabel = when {
                    !account.authenticated -> "开通飞连vpn"
                    display.state == AccountUsageState.NoActivePlan -> "开通飞连vpn"
                    display.state == AccountUsageState.Available -> "一键连接"
                    else -> "重试"
                },
                primaryEnabled = operable && (!account.authenticated || verifiedUsage && display.state == AccountUsageState.NoActivePlan),
                onPrimaryAction = { if (!account.authenticated) showGuestChoice = true else if (verifiedUsage &&
                    display.state == AccountUsageState.NoActivePlan && usage.error == null) openPlans() },
                showPrimary = !controlMode, primaryTestTag = "vpn-connect", embedded = true)
            if (usage.busy) TextButton(account::cancelAccountUsageUpdate,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("account-usage-cancel")) { Text("取消更新") }
            if (controlMode) BrandButton(
                when (local.action) {
                    ConnectionPowerAction.Connect -> "一键连接"
                    ConnectionPowerAction.Cancel -> "取消连接"
                    ConnectionPowerAction.Disconnect -> "断开"
                    ConnectionPowerAction.None -> local.powerLabel
                }, {
                    if (controlEnabled && !acceptedCommand) { acceptedCommand = true; onPowerAction?.invoke(local.action) }
                }, enabled = controlEnabled,
                modifier = Modifier.testTag("vpn-connect").semantics {
                    contentDescription = when (local.action) {
                        ConnectionPowerAction.Connect -> "一键连接"
                        ConnectionPowerAction.Disconnect -> "断开"
                        else -> local.powerLabel
                    }
                    stateDescription = local.connectionText
                })
        }
        BrandCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            TextButton(onClick = openNodes, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("connection-nodes")) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val summary: @Composable (Modifier) -> Unit = { modifier ->
                        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("当前节点", color = Brand.Muted, style = MaterialTheme.typography.labelSmall)
                            Text(if (account.nodeSelection.busy) "正在刷新" else selectedNode?.name ?: "未选择",
                                color = Brand.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("connection-node-name"))
                            selectedNode?.let { Text(it.protocol.uppercase(), color = Brand.Muted,
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("connection-node-protocol")) }
                        }
                    }
                    if (maxWidth < 260.dp || LocalDensity.current.fontScale > 1.3f) Column(Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        summary(Modifier.fillMaxWidth()); Text("选择节点 ›", modifier = Modifier.align(Alignment.End))
                    } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        summary(Modifier.weight(1f)); Text("选择节点 ›")
                    }
                }
            }
        }
        // Local configuration evidence has no account/resource binding and is never the account usage.
        if (snapshot.status != LocalSubscriptionStatus.MISSING) {
            val localTitle = if (local.status == LocalSubscriptionStatus.EXPIRED) "本地配置已到期" else local.title
            if (localTitle.isNotEmpty()) SupportingText(localTitle, Modifier.testTag("local-subscription-title"))
            local.countdown?.let { Text("本地配置有效时间 $it", color = Brand.Muted,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("subscription-validity-countdown")) }
            val action = when {
                local.status == LocalSubscriptionStatus.EXPIRED -> Triple("查看套餐", "expired-subscription-renew", openPlans)
                local.status == LocalSubscriptionStatus.INVALID_LINK -> Triple("登录恢复订阅", "open-account", openAccount)
                snapshot.hasCapability && local.status in setOf(LocalSubscriptionStatus.RETRY_REQUIRED,
                    LocalSubscriptionStatus.UNSUPPORTED, LocalSubscriptionStatus.STORAGE_ERROR) ->
                    Triple("重试", "local-subscription-refresh", account::refreshSubscription)
                else -> null
            }
            action?.let { TextButton(it.third, enabled = operable,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(it.second)) { Text(it.first) } }
        }
    }
    if (showGuestChoice && !account.authenticated) AlertDialog(
        modifier = Modifier.testTag("usage-guest-guidance"), onDismissRequest = { showGuestChoice = false },
        title = { Text("开通飞连vpn") }, text = { Text("登录或注册后查看套餐") },
        confirmButton = { Column {
            TextButton({ showGuestChoice = false; openAccount() }, modifier = Modifier.heightIn(min = 48.dp)
                .testTag("usage-guest-login")) { Text("登录") }
            TextButton({ showGuestChoice = false; openRegistration() }, modifier = Modifier.heightIn(min = 48.dp)
                .testTag("usage-guest-register")) { Text("注册") }
        } }, dismissButton = { TextButton({ showGuestChoice = false }) { Text("取消") } })
}

/** Draw only the upper 53% of the approved mobile asset; its old chest brand is excluded. */
@Composable
private fun ConnectionWelcome() {
    val robot = ImageBitmap.imageResource(R.drawable.brand_welcome_wave)
    val sourceHeight = (robot.height * .53f).toInt().coerceAtLeast(1)
    val greeting: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("欢迎回来", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            SupportingText("飞连vpn·与你同行")
        }
    }
    val image: @Composable (Modifier) -> Unit = { modifier ->
        Canvas(modifier.width(82.dp).aspectRatio(robot.width.toFloat() / sourceHeight)) {
            drawImage(robot, srcOffset = IntOffset.Zero, srcSize = IntSize(robot.width, sourceHeight),
                dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("connection-welcome")) {
        if (maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.5f) Column(Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            greeting(Modifier.fillMaxWidth()); image(Modifier.align(Alignment.End))
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) { greeting(Modifier.weight(1f)); image(Modifier) }
    }
}
