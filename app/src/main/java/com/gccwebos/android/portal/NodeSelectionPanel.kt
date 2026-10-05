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
import com.gccwebos.android.design.NodeRegionIcon
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
    latency: NodeLatencyController? = null,
    onImport: (() -> Unit)? = null,
    openAccount: () -> Unit) {
    LaunchedEffect(nodes, hasCapability, accountBusy) { state.sync() }
    val latestAccountBusy by rememberUpdatedState(accountBusy)
    LaunchedEffect(state, latency) {
        while (true) {
            delay(1000)
            if (state.snapshot.validUntil != null) state.sync()
            if (latency?.foreground == true) {
                latency.sync()
                val current = state.snapshot
                if (current.status == LocalSubscriptionStatus.READY && current.choices.isNotEmpty() &&
                    !latestAccountBusy && !state.busy) latency.ensureMeasured()
            }
        }
    }
    DisposableEffect(state) { onDispose { if (state.busy) state.cancel() } }
    DisposableEffect(latency) { onDispose { latency?.cancel() } }
    val value = state.snapshot
    val readyChoices = if (value.status == LocalSubscriptionStatus.READY) value.choices else emptyList()
    val readOnlyNodes = if (value.choices.isEmpty()) nodes else emptyList()
    val choices = readyChoices.withIndex().toList()
    val total = readyChoices.size + readOnlyNodes.size
    val selectedChoice = readyChoices.firstOrNull { it.selection === value.selected }
    val canMeasure = value.status == LocalSubscriptionStatus.READY && readyChoices.isNotEmpty() &&
        !accountBusy && !state.busy && latency?.foreground != false
    // A measurement completion must not itself trigger another batch. Selection stays independent.
    LaunchedEffect(latency, value.status, value.choices, accountBusy, state.busy, latency?.foreground) {
        if (canMeasure) latency?.ensureMeasured() else latency?.cancel()
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (onImport != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            onImport?.let { action ->
                IconButton(action, modifier = Modifier.testTag("nodes-import")) {
                    Icon(painterResource(R.drawable.brand_receipt), contentDescription = "导入订阅",
                        tint = Brand.Cyan, modifier = Modifier.size(22.dp))
                }
            }
        }
        GlassSurface(Modifier.fillMaxWidth().testTag("nodes-selection-summary")) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NodeRegionIcon(selectedChoice?.name, Modifier.testTag("nodes-selected-region"),
                        size = 38.dp, emphasized = true)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(selectedChoice?.name ?: "未选择", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("nodes-selected-name"))
                        NodeLatencyBadge(latency?.reading(selectedChoice?.selection),
                            Modifier.testTag("nodes-selected-latency"))
                    }
                }
            }
        }
        if (!hasCapability) TextButton(onClick = openAccount, enabled = !accountBusy,
            modifier = Modifier.heightIn(min = 48.dp).testTag("nodes-account")) {
            Text(if (authenticated) "订阅" else "登录")
        }
        if (total > 0) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("订阅节点", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("$total 条",
                    fontSize = 11.sp, color = Brand.Muted, modifier = Modifier.testTag("nodes-count"))
            }
            GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(23.dp)) {
                Column {
                    choices.forEachIndexed { visibleIndex, indexed ->
                        val choice = indexed.value
                        val chosen = value.selected === choice.selection
                        if (visibleIndex > 0) HorizontalDivider(color = Brand.RowDivider.copy(alpha = .12f))
                        NodeChoiceRow(choice.name, choice.protocol, true, chosen,
                            observation = latency?.reading(choice.selection),
                            regionTag = "node-region-${indexed.index}",
                            latencyTag = "node-latency-${indexed.index}",
                            modifier = Modifier.testTag("node-choice-${indexed.index}").semantics { selected = chosen }
                                .clickable(enabled = !state.busy && !accountBusy, role = Role.RadioButton) {
                                    if (state.select(choice.selection)) onSelected()
                                })
                    }
                    readOnlyNodes.forEachIndexed { index, node ->
                        if (index > 0) HorizontalDivider(color = Brand.RowDivider.copy(alpha = .12f))
                        NodeChoiceRow(node.name, node.protocol, node.supported, null,
                            regionTag = "subscription-node-region-${node.index}",
                            latencyTag = "subscription-node-latency-${node.index}",
                            modifier = Modifier.testTag("subscription-node-${node.index}"), inspectionIndex = node.index)
                    }
                }
            }
        }

    }
}

private fun nodeProtocolLabel(protocol: String): String = when (protocol) {
    "hysteria2" -> "Hysteria2"
    else -> protocol.uppercase(Locale.ROOT)
}

@Composable
private fun NodeChoiceRow(name: String, protocol: String, supported: Boolean, chosen: Boolean?,
    observation: NodeLatencyObservation? = null, regionTag: String, latencyTag: String,
    modifier: Modifier = Modifier, inspectionIndex: Int? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp)
        .then(if (chosen == true) Modifier.background(Brand.Cyan.copy(alpha = .08f)) else Modifier)
        .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NodeRegionIcon(name, Modifier.testTag(regionTag))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = inspectionIndex?.let { Modifier.testTag("subscription-node-name-$it") } ?: Modifier)
            Text(nodeProtocolLabel(protocol),
                fontSize = 11.sp, color = Brand.Muted,
                modifier = inspectionIndex?.let { Modifier.testTag("subscription-node-details-$it") } ?: Modifier)
            NodeLatencyBadge(observation, Modifier.testTag(latencyTag))
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
