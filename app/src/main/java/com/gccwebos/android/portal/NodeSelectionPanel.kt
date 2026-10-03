package com.gccwebos.android.portal

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
import com.gccwebos.android.design.Brand
import com.gccwebos.android.design.BrandButton
import com.gccwebos.android.design.GlassSurface
import com.gccwebos.android.vpn.SafeProxyConfig
import kotlinx.coroutines.delay

@Composable
internal fun NodeSelectionPanel(state: NodeSelectionController, nodes: List<SafeProxyConfig.NodeSummary>,
    resource: Resource?, hasCapability: Boolean, accountBusy: Boolean, authenticated: Boolean = false,
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
        value.choices.isEmpty() -> "请重试读取配置"
        else -> "请选择节点"
    }
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("node-selection-status"))
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Brand.Cyan)
    state.message?.let { Text(it, color = Brand.Muted, modifier = Modifier.testTag("node-selection-message")) }
    // sync() is local-only: the first read and an unsuccessful read still need an explicit recovery.
    // A ready choice list has no permanent manual refresh; busy reads retain the existing cancellation.
    val showReadRetry = !state.busy && (value.status != LocalSubscriptionStatus.READY || value.choices.isEmpty())
    if (hasCapability) {
        if (showReadRetry || state.busy) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showReadRetry) BrandButton("重试", state::refresh, enabled = !accountBusy,
                modifier = Modifier.width(88.dp).testTag("nodes-refresh"))
            if (state.busy) TextButton(onClick = state::cancel,
                modifier = Modifier.heightIn(min = 48.dp).testTag("nodes-cancel")) { Text("取消") }
        }
    } else TextButton(onClick = openAccount, enabled = !accountBusy,
        modifier = Modifier.heightIn(min = 48.dp).testTag("nodes-account")) { Text(if (authenticated) "查看／领取订阅" else "登录领取订阅") }
    if (value.status == LocalSubscriptionStatus.READY) value.choices.forEachIndexed { index, choice ->
        val chosen = value.selected === choice.selection
        GlassSurface(Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .testTag("node-choice-$index").semantics { selected = chosen }
            .clickable(enabled = !state.busy && !accountBusy, role = Role.RadioButton) { state.select(choice.selection) },
            shape = Brand.Shape, selected = chosen) {
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
        Text("以下为已读取信息，配置校验后才能选择。", color = Brand.Muted)
        nodes.forEach { node ->
            GlassSurface(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("subscription-node-${node.index}"),
                shape = Brand.Shape) {
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
    Text("连接暂未开放", color = Brand.Muted, modifier = Modifier.testTag("nodes-unavailable"))
}
