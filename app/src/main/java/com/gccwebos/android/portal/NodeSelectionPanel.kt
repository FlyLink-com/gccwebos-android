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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gccwebos.android.ConnectionControlState
import com.gccwebos.android.ConnectionPhase
import com.gccwebos.android.R
import com.gccwebos.android.design.Brand
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
    groupTitle: String? = null,
    openAccount: () -> Unit) {
    LaunchedEffect(nodes, hasCapability, accountBusy) { state.expireIfNeeded() }
    val latestAccountBusy by rememberUpdatedState(accountBusy)
    LaunchedEffect(state, latency) {
        while (true) {
            delay(1000)
            state.expireIfNeeded()
            if (latency?.foreground == true) {
                latency.pruneExpired()
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
    var expanded by rememberSaveable(groupTitle, value.sourceKind) { mutableStateOf(true) }
    val title = groupTitle ?: when (value.sourceKind) {
        ManualSourceKind.LegacyRemote -> "账户订阅"
        ManualSourceKind.ImportedRemote -> "订阅配置"
        ManualSourceKind.ImportedLocal -> "本地配置"
        ManualSourceKind.ExternalRemote -> "第三方订阅"
        ManualSourceKind.None -> "配置"
    }
    val canMeasure = value.status == LocalSubscriptionStatus.READY && readyChoices.isNotEmpty() &&
        !accountBusy && !state.busy && latency?.foreground != false
    // A measurement completion must not itself trigger another batch. Selection stays independent.
    LaunchedEffect(latency, value.status, value.choices, accountBusy, state.busy, latency?.foreground) {
        if (canMeasure) latency?.ensureMeasured() else latency?.cancel()
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!hasCapability) TextButton(onClick = openAccount, enabled = !accountBusy,
            modifier = Modifier.heightIn(min = 48.dp).testTag("nodes-account")) {
            Text(if (authenticated) "订阅" else "登录")
        }
        GlassSurface(Modifier.fillMaxWidth().testTag("nodes-configuration-group"), shape = RoundedCornerShape(20.dp)) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).heightIn(min = 48.dp)
                        .testTag("nodes-group-toggle")
                        .semantics { contentDescription = if (expanded) "收起配置节点" else "展开配置节点" }
                        .clickable(role = Role.Button) { expanded = !expanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Canvas(Modifier.size(14.dp)) {
                            val first = if (expanded) Offset(size.width * .15f, size.height * .35f)
                                else Offset(size.width * .35f, size.height * .15f)
                            val middle = if (expanded) Offset(size.width * .5f, size.height * .7f)
                                else Offset(size.width * .7f, size.height * .5f)
                            val last = if (expanded) Offset(size.width * .85f, size.height * .35f)
                                else Offset(size.width * .35f, size.height * .85f)
                            drawLine(Brand.Cyan, first, middle, strokeWidth = 1.7.dp.toPx(), cap = StrokeCap.Round)
                            drawLine(Brand.Cyan, middle, last, strokeWidth = 1.7.dp.toPx(), cap = StrokeCap.Round)
                        }
                        Column(Modifier.weight(1f).padding(vertical = 5.dp)) {
                            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("nodes-group-title"))
                            Text("$total 条", fontSize = 10.sp, color = Brand.Muted,
                                modifier = Modifier.testTag("nodes-count"))
                        }
                    }
                    if (latency != null) IconButton(onClick = { latency.refresh() },
                        enabled = canMeasure && !latency.busy,
                        modifier = Modifier.size(48.dp).testTag("nodes-latency-refresh")) {
                        Icon(painterResource(R.drawable.brand_lightning), contentDescription = "测试节点延迟",
                            modifier = Modifier.size(21.dp),
                            tint = if (canMeasure && !latency.busy) Brand.Cyan else Brand.Muted)
                    }
                    onImport?.let { action ->
                        IconButton(action, modifier = Modifier.size(48.dp).testTag("nodes-import")) {
                            Icon(painterResource(R.drawable.brand_receipt), contentDescription = "导入订阅",
                                tint = Brand.Cyan, modifier = Modifier.size(21.dp))
                        }
                    }
                }
                if (expanded) {
                    Row(Modifier.fillMaxWidth().testTag("nodes-selection-summary")
                        .background(Brand.Cyan.copy(alpha = .035f)).padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NodeRegionIcon(selectedChoice?.name, Modifier.testTag("nodes-selected-region"), size = 24.dp)
                        Text(selectedChoice?.name ?: "未选择", fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).testTag("nodes-selected-name"))
                        NodeLatencyBadge(latency?.reading(selectedChoice?.selection),
                            Modifier.testTag("nodes-selected-latency"))
                    }
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
        .padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NodeRegionIcon(name, Modifier.testTag(regionTag), size = 26.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = inspectionIndex?.let { Modifier.testTag("subscription-node-name-$it") } ?: Modifier)
            Text(nodeProtocolLabel(protocol),
                fontSize = 10.sp, color = Brand.Muted, maxLines = 1,
                modifier = inspectionIndex?.let { Modifier.testTag("subscription-node-details-$it") } ?: Modifier)
        }
        NodeLatencyBadge(observation, Modifier.testTag(latencyTag))
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
