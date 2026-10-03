package com.gccwebos.android.portal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gccwebos.android.ConnectionControlState
import com.gccwebos.android.ConnectionPhase
import com.gccwebos.android.design.Brand
import com.gccwebos.android.vpn.SafeProxyConfig
import kotlinx.coroutines.delay

internal fun nodeConnectionStatus(control: ConnectionControlState): String? = when (control.phase) {
    ConnectionPhase.Disconnected -> null
    ConnectionPhase.Unavailable -> "连接暂不可用"
    ConnectionPhase.Connecting -> "连接中"
    ConnectionPhase.Connected -> "已连接"
    ConnectionPhase.Disconnecting -> "断开中"
    ConnectionPhase.Failed -> "未连接"
}

@Composable
internal fun NodeSelectionPanel(state: NodeSelectionController, nodes: List<SafeProxyConfig.NodeSummary>,
    resource: Resource?, hasCapability: Boolean, accountBusy: Boolean, authenticated: Boolean = false,
    connectionControl: ConnectionControlState = ConnectionControlState(),
    openAccount: () -> Unit) {
    LaunchedEffect(nodes, hasCapability, accountBusy) { state.sync() }
    LaunchedEffect(state.snapshot.validUntil) {
        if (state.snapshot.validUntil != null) while (true) { delay(1000); state.sync() }
    }
    DisposableEffect(state) { onDispose { if (state.busy) state.cancel() } }
    val value = state.snapshot
    val title = when {
        state.busy -> "正在读取配置"
        value.status == LocalSubscriptionStatus.MISSING && resource == Resource.None -> "暂无套餐"
        value.status == LocalSubscriptionStatus.MISSING && resource == Resource.Reconciliation -> "资源正在对账"
        value.status == LocalSubscriptionStatus.MISSING -> "尚无订阅配置"
        value.status == LocalSubscriptionStatus.EXPIRED -> "订阅已到期"
        value.status == LocalSubscriptionStatus.INVALID_LINK -> "订阅链接已失效"
        value.status == LocalSubscriptionStatus.UNSUPPORTED -> "配置暂不受支持"
        value.status == LocalSubscriptionStatus.STORAGE_ERROR -> "本地配置存储失败"
        value.status == LocalSubscriptionStatus.RETRY_REQUIRED -> "配置读取失败，请重试"
        value.selected != null -> "节点已选择"
        value.choices.isEmpty() -> "请刷新配置后选择节点"
        else -> "请选择节点"
    }
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("node-selection-status"))
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Brand.Cyan)
    state.message?.let { Text(it, color = Brand.Muted, modifier = Modifier.testTag("node-selection-message")) }
    if (hasCapability) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = state::refresh, enabled = !accountBusy,
            modifier = Modifier.heightIn(min = 48.dp).testTag("nodes-refresh")) { Text("刷新配置") }
        if (state.busy) TextButton(onClick = state::cancel,
            modifier = Modifier.heightIn(min = 48.dp).testTag("nodes-cancel")) { Text("取消") }
    } else TextButton(onClick = openAccount, enabled = !accountBusy,
        modifier = Modifier.heightIn(min = 48.dp).testTag("nodes-account")) { Text(if (authenticated) "查看／领取订阅" else "登录领取订阅") }
    if (value.status == LocalSubscriptionStatus.READY) value.choices.forEachIndexed { index, choice ->
        val chosen = value.selected === choice.selection
        Surface(Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .testTag("node-choice-$index").semantics { selected = chosen }
            .clickable(enabled = !state.busy && !accountBusy, role = Role.RadioButton) { state.select(choice.selection) },
            shape = Brand.Shape, color = if (chosen) Brand.SurfaceRaised else Brand.Surface.copy(alpha = .72f),
            border = BorderStroke(1.dp, if (chosen) Brand.Cyan else Brand.Border)) {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RadioButton(selected = chosen, onClick = null, enabled = !state.busy && !accountBusy)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${index + 1}. ${choice.name}", style = MaterialTheme.typography.titleMedium)
                    Text("${choice.protocol.uppercase()} · 可解析", style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                }
            }
        }
    }
    if (value.choices.isEmpty() && nodes.isNotEmpty()) {
        Text("以下仅为已读取的节点信息，请刷新并通过整份配置校验后选择。", color = Brand.Muted)
        nodes.forEach { node ->
            Surface(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("subscription-node-${node.index}"),
                shape = Brand.Shape, color = Brand.Surface.copy(alpha = .72f), border = BorderStroke(1.dp, Brand.Border)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(node.name, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("subscription-node-name-${node.index}"))
                    Text("${node.protocol.uppercase()} · ${if (node.supported) "可解析" else "暂不兼容"}",
                        color = Brand.Muted, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("subscription-node-details-${node.index}"))
                }
            }
        }
    }
    nodeConnectionStatus(connectionControl)?.let {
        Text(it, color = Brand.Muted, modifier = Modifier.testTag("nodes-connection-status"))
    }
}
