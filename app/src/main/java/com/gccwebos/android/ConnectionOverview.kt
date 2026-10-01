package com.gccwebos.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gccwebos.android.design.Brand
import com.gccwebos.android.design.SupportingText
import com.gccwebos.android.design.remainingValidity
import com.gccwebos.android.portal.AccountController
import com.gccwebos.android.portal.LocalSubscriptionSnapshot
import com.gccwebos.android.portal.LocalSubscriptionStatus
import java.time.Instant
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

/** A single power component. Production supplies no engine state or command handler yet. */
@Composable
internal fun ConnectionOverview(
    account: AccountController,
    openAccount: () -> Unit,
    openPlans: () -> Unit,
    openNodes: () -> Unit,
    control: ConnectionControlState = ConnectionControlState(),
    onPowerAction: ((ConnectionPowerAction) -> Unit)? = null,
) {
    val snapshot = account.localSubscription
    val now by produceState(Instant.now(), snapshot.validUntil) {
        while (true) { value = Instant.now(); delay(1_000) }
    }
    var synchronizedNodeGeneration by remember(account) { mutableStateOf<Long?>(null) }
    LaunchedEffect(account, account.authSessionGeneration, snapshot, now) {
        account.nodeSelection.sync() // Local registry/validity read only; never starts a refresh or tunnel.
        synchronizedNodeGeneration = account.authSessionGeneration
    }
    val nodeSnapshot = account.nodeSelection.snapshot
    val selectedNode = nodeSnapshot.choices.firstOrNull { it.selection === nodeSnapshot.selected }?.takeIf {
        synchronizedNodeGeneration == account.authSessionGeneration && !account.nodeSelection.busy &&
            nodeSnapshot.status == LocalSubscriptionStatus.READY && nodeSnapshot.validUntil?.let { until -> until > now } != false
    }
    val presentation = connectionOverviewPresentation(snapshot, now, control)
    val accent = when (presentation.tone) {
        ConnectionVisualTone.Neutral -> Brand.Muted
        ConnectionVisualTone.Connecting -> Brand.Blue
        ConnectionVisualTone.Connected -> Brand.Green
        ConnectionVisualTone.Error -> Color(0xFFFF658E)
    }
    val operable = !account.busy && !account.restoring && !account.foregroundCheckPending && !account.clearing
    var acceptedCommand by remember(control, account) { mutableStateOf(false) }
    // Session refresh/login must not prevent a controller-authorized cancel or disconnect.
    val powerEnabled = onPowerAction != null && !acceptedCommand && presentation.action != ConnectionPowerAction.None &&
        (presentation.action != ConnectionPowerAction.Connect || !account.clearing)
    Column(Modifier.fillMaxWidth().testTag("connection-overview"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp).testTag("connection-power"),
            contentAlignment = Alignment.Center) {
            Button(onClick = {
                if (powerEnabled && !acceptedCommand) {
                    acceptedCommand = true
                    onPowerAction?.invoke(presentation.action)
                }
            }, enabled = powerEnabled, shape = CircleShape, contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(184.dp)
                    .background(Brush.radialGradient(listOf(accent.copy(alpha = .20f), accent.copy(alpha = .04f))), CircleShape)
                    .border(1.5.dp, accent.copy(alpha = .65f), CircleShape)
                    .semantics {
                        contentDescription = presentation.powerLabel
                        stateDescription = listOf(presentation.connectionText, presentation.title).filter { it.isNotEmpty() }.joinToString(" · ")
                    }.testTag("vpn-connect"),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = accent,
                    disabledContainerColor = Color.Transparent, disabledContentColor = accent)) {
                Canvas(Modifier.size(72.dp)) {
                    drawArc(accent, startAngle = -45f, sweepAngle = 270f, useCenter = false,
                        topLeft = Offset(size.width * .16f, size.height * .22f),
                        size = Size(size.width * .68f, size.height * .68f),
                        style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                    drawLine(accent, Offset(size.width * .5f, size.height * .09f),
                        Offset(size.width * .5f, size.height * .5f), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(presentation.connectionText, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("connection-status"))
            if (presentation.title.isNotEmpty()) {
                SupportingText(" · ")
                Text(presentation.title, color = accent, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("local-subscription-title"))
            }
        }
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
                if (maxWidth < 260.dp || LocalDensity.current.fontScale > 1.3f) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        summary(Modifier.fillMaxWidth())
                        Text("选择节点 ›", modifier = Modifier.align(Alignment.End))
                    }
                } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    summary(Modifier.weight(1f)); Text("选择节点 ›")
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SupportingText("剩余时间")
                Text(presentation.countdown?.removePrefix("剩余 ") ?: "暂不可用", style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center, modifier = Modifier.testTag("subscription-validity-countdown"))
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SupportingText("剩余流量")
                Text(presentation.usageText, style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center, modifier = Modifier.testTag("subscription-usage-unavailable"))
            }
        }
        val subscriptionAction: @Composable (Modifier) -> Unit = { modifier ->
            val action: Triple<String, String, () -> Unit> = when {
                presentation.status == LocalSubscriptionStatus.EXPIRED -> Triple("续费", "expired-subscription-renew", openPlans)
                presentation.status == LocalSubscriptionStatus.INVALID_LINK -> Triple("登录恢复订阅", "open-account", openAccount)
                snapshot.hasCapability -> Triple("刷新订阅", "local-subscription-refresh", account::refreshSubscription)
                account.authenticated -> Triple("领取／同步订阅", "local-subscription-sync", account::refresh)
                else -> Triple("登录领取订阅", "open-account", openAccount)
            }
            TextButton(onClick = action.third, enabled = operable,
                modifier = modifier.heightIn(min = 48.dp).testTag(action.second)) { Text(action.first) }
        }
        subscriptionAction(Modifier.fillMaxWidth())
    }
}
