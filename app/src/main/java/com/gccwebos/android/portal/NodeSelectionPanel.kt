package com.gccwebos.android.portal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gccwebos.android.ConnectionControlState
import com.gccwebos.android.ConnectionPhase
import com.gccwebos.android.R
import com.gccwebos.android.design.Brand
import com.gccwebos.android.design.BrandButton
import com.gccwebos.android.design.GlassSurface
import com.gccwebos.android.vpn.SafeProxyConfig
import kotlinx.coroutines.delay
import java.util.Locale

/** Display only: node selection cannot infer or start a connection. */
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
    onSelected: () -> Unit = {},
    openAccount: () -> Unit) {
    LaunchedEffect(nodes, hasCapability, accountBusy) { state.sync() }
    LaunchedEffect(state.snapshot.validUntil) {
        if (state.snapshot.validUntil != null) while (true) { delay(1000); state.sync() }
    }
    DisposableEffect(state) { onDispose { if (state.busy) state.cancel() } }
    val value = state.snapshot
    val readyChoices = if (value.status == LocalSubscriptionStatus.READY) value.choices else emptyList()
    val readOnlyNodes = if (value.choices.isEmpty()) nodes else emptyList()
    val choices = readyChoices.withIndex().toList()
    val total = readyChoices.size + readOnlyNodes.size
    val selectedChoice = readyChoices.firstOrNull { it.selection === value.selected }
    val title = when {
        state.busy -> if (selectedChoice != null) "节点已选择" else "请选择节点"
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(start = 3.dp, end = 3.dp, top = 4.dp, bottom = 5.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("选择节点", fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Text("从当前订阅中选择适合的线路", fontSize = 12.sp, color = Brand.Muted)
        }
        GlassSurface(Modifier.fillMaxWidth().testTag("nodes-selection-summary")) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NodeGlobe(feature = true)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(selectedChoice?.name ?: "未选择", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("nodes-selected-name"))
                        Text("选择后返回连接页", fontSize = 11.sp, color = Brand.Muted)
                    }
                }
                HorizontalDivider(color = Brand.RowDivider)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("当前选择", fontSize = 11.sp, color = Brand.Muted, modifier = Modifier.weight(1f))
                    Text(title, fontSize = 11.sp, color = Brand.Muted,
                        modifier = Modifier.weight(1f).testTag("node-selection-status"))
                }
            }
        }
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
        if (total > 0) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("订阅节点", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("$total 条",
                    fontSize = 11.sp, color = Brand.Muted, modifier = Modifier.testTag("nodes-count"))
            }
            if (readOnlyNodes.isNotEmpty()) Text("以下为已读取信息，配置校验后才能选择。", color = Brand.Muted,
                style = MaterialTheme.typography.bodySmall)
            GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(23.dp)) {
                Column {
                    choices.forEachIndexed { visibleIndex, indexed ->
                        val choice = indexed.value
                        val chosen = value.selected === choice.selection
                        if (visibleIndex > 0) HorizontalDivider(color = Brand.RowDivider.copy(alpha = .12f))
                        NodeChoiceRow(choice.name, choice.protocol, true, chosen,
                            modifier = Modifier.testTag("node-choice-${indexed.index}").semantics { selected = chosen }
                                .clickable(enabled = !state.busy && !accountBusy, role = Role.RadioButton) {
                                    if (state.select(choice.selection)) onSelected()
                                })
                    }
                    readOnlyNodes.forEachIndexed { index, node ->
                        if (index > 0) HorizontalDivider(color = Brand.RowDivider.copy(alpha = .12f))
                        NodeChoiceRow(node.name, node.protocol, node.supported, null,
                            modifier = Modifier.testTag("subscription-node-${node.index}"), inspectionIndex = node.index)
                    }
                }
            }
        }
        nodeConnectionStatus(connectionControl)?.let { status ->
            Text(status, color = Brand.Muted, modifier = Modifier.testTag("nodes-connection-status"))
        }
    }
}

private fun nodeProtocolLabel(protocol: String): String = when (protocol) {
    "hysteria2" -> "Hysteria2"
    else -> protocol.uppercase(Locale.ROOT)
}

@Composable
private fun NodeGlobe(feature: Boolean = false) {
    val shape = RoundedCornerShape(if (feature) 16.dp else 13.dp)
    Box(Modifier.size(if (feature) 38.dp else 30.dp)
        .background(Brand.Cyan.copy(alpha = if (feature) .10f else .07f), shape)
        .then(if (feature) Modifier.border(1.dp, Brand.Cyan.copy(alpha = .26f), shape) else Modifier),
        contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.brand_globe), contentDescription = null,
            modifier = Modifier.size(if (feature) 22.dp else 18.dp), tint = if (feature) Brand.Cyan else Brand.Muted)
    }
}

@Composable
private fun NodeChoiceRow(name: String, protocol: String, supported: Boolean, chosen: Boolean?,
    modifier: Modifier = Modifier, inspectionIndex: Int? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp)
        .then(if (chosen == true) Modifier.background(Brand.Cyan.copy(alpha = .08f)) else Modifier)
        .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NodeGlobe()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = inspectionIndex?.let { Modifier.testTag("subscription-node-name-$it") } ?: Modifier)
            Text("${nodeProtocolLabel(protocol)} · ${if (supported) "可解析" else "暂不兼容"}",
                fontSize = 11.sp, color = Brand.Muted,
                modifier = inspectionIndex?.let { Modifier.testTag("subscription-node-details-$it") } ?: Modifier)
        }
        if (chosen == null) Text("只读", color = Brand.Muted, fontSize = 10.sp)
        else Box(Modifier.size(20.dp).border(1.5.dp, if (chosen) Brand.Cyan else Brand.Border, CircleShape)
            .then(if (chosen) Modifier.background(Brand.Cyan, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
            if (chosen) Canvas(Modifier.size(12.dp)) {
                drawLine(Brand.Background, Offset(size.width * .15f, size.height * .5f),
                    Offset(size.width * .4f, size.height * .75f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(Brand.Background, Offset(size.width * .4f, size.height * .75f),
                    Offset(size.width * .85f, size.height * .25f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}
