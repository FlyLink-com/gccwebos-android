package com.gccwebos.android.portal

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.gccwebos.android.ConnectionControlState
import com.gccwebos.android.core.ConnectionChoice
import com.gccwebos.android.ConnectionPhase
import com.gccwebos.android.R
import com.gccwebos.android.design.Brand
import com.gccwebos.android.design.GlassSurface
import com.gccwebos.android.design.NodeRegionIcon
import com.gccwebos.android.usageMotionEnabled
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

private val NodeSelectedFill = Brand.Cyan.copy(alpha = .12f)
private val NodeSelectedBorder = Brand.Cyan.copy(alpha = .85f)
private val NodeRowShape = RoundedCornerShape(12.dp)
private val ProtocolChipShape = RoundedCornerShape(6.dp)

@Composable
internal fun NodeSelectionPanel(state: NodeSelectionController, nodes: List<SafeProxyConfig.NodeSummary>,
    resource: Resource?, hasCapability: Boolean, accountBusy: Boolean, authenticated: Boolean = false,
    connectionControl: ConnectionControlState = ConnectionControlState(),
    onSelected: () -> Unit = {},
    latency: NodeLatencyController? = null,
    onImport: (() -> Unit)? = null,
    groupTitle: String? = null,
    showTools: Boolean = true,
    showAccountAction: Boolean = true,
    headerActions: @Composable RowScope.() -> Unit = {},
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
    val infoChoices = readyChoices.withIndex().filter { it.value.info }
    val proxyChoices = readyChoices.withIndex().filterNot { it.value.info }
    val proxyCount = proxyChoices.size + readOnlyNodes.count { !it.info }
    val selectedChoice = readyChoices.firstOrNull { it.selection === value.selected && !it.info }
    var expanded by rememberSaveable(groupTitle, value.sourceKind) { mutableStateOf(true) }
    var sortByLatency by rememberSaveable { mutableStateOf(false) }
    val title = groupTitle ?: when (value.sourceKind) {
        ManualSourceKind.LegacyRemote -> "账户订阅"
        ManualSourceKind.ImportedRemote -> "订阅配置"
        ManualSourceKind.ImportedLocal -> "本地配置"
        ManualSourceKind.ExternalRemote -> "第三方订阅"
        ManualSourceKind.None -> "配置"
    }
    val canMeasure = value.status == LocalSubscriptionStatus.READY && proxyChoices.isNotEmpty() &&
        !accountBusy && !state.busy && latency?.foreground != false
    // A measurement completion must not itself trigger another batch. Selection stays independent.
    LaunchedEffect(latency, value.status, value.choices, accountBusy, state.busy, latency?.foreground) {
        if (canMeasure) latency?.ensureMeasured() else latency?.cancel()
    }
    val displayProxy = if (sortByLatency && latency != null) {
        proxyChoices.sortedWith(
            compareBy<IndexedValue<ConnectionChoice>> {
                nodeLatencySortKey(latency.reading(it.value.selection))
            }.thenBy { it.index },
        )
    } else proxyChoices
    val infoLine = formatNodeInfoBarLine(extractNodeInfoBar(infoChoices.map { it.value.name }))
    val lastAgo = latency?.lastMeasuredAtElapsed()?.let { latency.latencyClockElapsed() - it }
    val lastLabel = formatLastLatencyTestAgo(lastAgo)
    val motionEnabled = rememberNodeListMotionEnabled()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showAccountAction && !hasCapability) TextButton(onClick = openAccount, enabled = !accountBusy,
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
                            // Count stays "N 条" for existing UI contracts; it excludes info notices.
                            Text("$proxyCount 条", fontSize = 10.sp, color = Brand.Muted,
                                modifier = Modifier.testTag("nodes-count"))
                        }
                    }
                    if (showTools && latency != null) {
                        NodesLatencyRefreshButton(
                            enabled = canMeasure && !latency.busy,
                            busy = latency.busy,
                            onClick = { latency.refresh() },
                            motionEnabled = motionEnabled,
                        )
                    }
                    onImport?.takeIf { showTools }?.let { action ->
                        NodesImportButton(onClick = action)
                    }
                    headerActions()
                }
                if (expanded) {
                    infoLine?.let { NodeInfoBanner(it) }
                    if (latency != null && proxyChoices.isNotEmpty()) {
                        NodeLatencyToolbar(
                            lastLabel = lastLabel,
                            canMeasure = canMeasure,
                            measuring = latency.busy,
                            sortByLatency = sortByLatency,
                            onToggleSort = { sortByLatency = !sortByLatency },
                            onMeasure = { if (canMeasure && !latency.busy) latency.refresh() },
                            motionEnabled = motionEnabled,
                        )
                    }
                    Row(Modifier.fillMaxWidth().testTag("nodes-selection-summary")
                        .background(Brand.Cyan.copy(alpha = .035f)).padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NodeRegionIcon(selectedChoice?.name, Modifier.testTag("nodes-selected-region"), size = 24.dp)
                        Text(selectedChoice?.name ?: "未选择", fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).testTag("nodes-selected-name"))
                        NodeLatencyBadge(latency?.reading(selectedChoice?.selection),
                            Modifier.testTag("nodes-selected-latency"),
                            measuring = latency?.busy == true && selectedChoice != null &&
                                latency.reading(selectedChoice.selection) == null)
                    }
                    displayProxy.forEachIndexed { visibleIndex, indexed ->
                        val choice = indexed.value
                        val chosen = value.selected === choice.selection
                        if (visibleIndex > 0) HorizontalDivider(color = Brand.RowDivider.copy(alpha = .12f))
                        val observation = latency?.reading(choice.selection)
                        val measuringCell = latency?.busy == true && observation == null
                        NodeChoiceRow(choice.name, choice.protocol, true, chosen,
                            observation = observation,
                            measuring = measuringCell,
                            onRetestLatency = latency?.let { controller ->
                                {
                                    if (canMeasure && !controller.busy) controller.refresh(choice.selection)
                                }
                            },
                            regionTag = "node-region-${indexed.index}",
                            latencyTag = "node-latency-${indexed.index}",
                            modifier = Modifier.testTag("node-choice-${indexed.index}").semantics { selected = chosen }
                                .clickable(enabled = !state.busy && !accountBusy, role = Role.RadioButton) {
                                    if (state.select(choice.selection)) onSelected()
                                })
                    }
                    readOnlyNodes.filterNot { it.info }.forEachIndexed { visibleIndex, node ->
                        if (visibleIndex > 0 || displayProxy.isNotEmpty()) {
                            HorizontalDivider(color = Brand.RowDivider.copy(alpha = .12f))
                        }
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

@Composable
private fun NodeInfoBanner(line: String) {
    Row(
        Modifier.fillMaxWidth().testTag("nodes-info-bar")
            .background(Brand.SurfaceRaised.copy(alpha = .55f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("💡", fontSize = 12.sp)
        Text(line, fontSize = 11.sp, color = Brand.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag("nodes-info-bar-text"))
    }
}

@Composable
private fun NodeLatencyToolbar(
    lastLabel: String?,
    canMeasure: Boolean,
    measuring: Boolean,
    sortByLatency: Boolean,
    onToggleSort: () -> Unit,
    onMeasure: () -> Unit,
    motionEnabled: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().testTag("nodes-latency-toolbar")
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            lastLabel ?: " ",
            fontSize = 10.sp,
            color = Brand.Muted.copy(alpha = if (lastLabel == null) 0f else .9f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag("nodes-latency-last"),
        )
        TextButton(
            onClick = onMeasure,
            enabled = canMeasure && !measuring,
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.heightIn(min = 48.dp).testTag("nodes-latency-one-tap"),
        ) {
            NodesLightningIcon(busy = measuring, motionEnabled = motionEnabled, enabled = canMeasure && !measuring,
                size = 16.dp)
            Spacer(Modifier.width(4.dp))
            Text("一键测速", fontSize = 11.sp)
        }
        TextButton(
            onClick = onToggleSort,
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.heightIn(min = 48.dp).testTag("nodes-latency-sort")
                .semantics { contentDescription = if (sortByLatency) "恢复默认排序" else "按延迟排序" },
        ) {
            Text(if (sortByLatency) "默认排序" else "按延迟排序", fontSize = 11.sp,
                color = if (sortByLatency) Brand.Cyan else Brand.Muted)
        }
    }
}

@Composable
internal fun NodesLatencyRefreshButton(
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    motionEnabled: Boolean? = null,
) {
    val motion = motionEnabled ?: rememberNodeListMotionEnabled()
    IconButton(onClick = onClick, enabled = enabled,
        modifier = modifier.size(48.dp).testTag("nodes-latency-refresh")) {
        NodesLightningIcon(busy = busy, motionEnabled = motion, enabled = enabled, size = 24.dp)
    }
}

@Composable
internal fun NodesImportButton(onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, enabled = enabled,
        modifier = modifier.size(48.dp).testTag("nodes-import")
            .semantics { contentDescription = "新增配置" }) {
        // Drawn plus keeps contrast higher than the receipt glyph against the city backdrop.
        Canvas(Modifier.size(22.dp)) {
            val stroke = 2.4.dp.toPx()
            val color = if (enabled) Brand.Cyan else Brand.Muted
            drawLine(color, Offset(size.width * .5f, size.height * .18f),
                Offset(size.width * .5f, size.height * .82f), stroke, StrokeCap.Round)
            drawLine(color, Offset(size.width * .18f, size.height * .5f),
                Offset(size.width * .82f, size.height * .5f), stroke, StrokeCap.Round)
        }
    }
}

@Composable
private fun NodesLightningIcon(busy: Boolean, motionEnabled: Boolean, enabled: Boolean, size: androidx.compose.ui.unit.Dp) {
    val spin = if (busy && motionEnabled) {
        val transition = rememberInfiniteTransition(label = "nodes-latency-spin")
        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)),
            label = "nodes-latency-spin-angle").value
    } else 0f
    Icon(
        painterResource(R.drawable.brand_lightning),
        contentDescription = "测试节点延迟",
        modifier = Modifier.size(size).then(if (spin != 0f) Modifier.rotate(spin) else Modifier),
        tint = if (enabled || busy) Brand.Cyan else Brand.Muted,
    )
}

@Composable
private fun rememberNodeListMotionEnabled(): Boolean {
    val context = LocalContext.current
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return remember(state, context) {
        val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        usageMotionEnabled(scale)
    }
}

private fun nodeProtocolLabel(protocol: String): String = when (protocol) {
    "hysteria2" -> "Hysteria2"
    else -> protocol.uppercase(Locale.ROOT)
}

@Composable
internal fun NodeChoiceRow(name: String, protocol: String, supported: Boolean, chosen: Boolean?,
    observation: NodeLatencyObservation? = null, measuring: Boolean = false,
    onRetestLatency: (() -> Unit)? = null,
    regionTag: String, latencyTag: String,
    modifier: Modifier = Modifier, inspectionIndex: Int? = null, readOnlyLabel: String = "只读") {
    val selected = chosen == true
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(
                if (selected) Modifier
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .clip(NodeRowShape)
                    .background(NodeSelectedFill, NodeRowShape)
                    .border(1.dp, NodeSelectedBorder, NodeRowShape)
                else Modifier,
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (selected) {
            Box(Modifier.width(3.dp).height(28.dp).clip(RoundedCornerShape(2.dp)).background(Brand.Cyan))
        } else {
            Spacer(Modifier.width(3.dp))
        }
        NodeRegionIcon(name, Modifier.testTag(regionTag), size = 26.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = inspectionIndex?.let { Modifier.testTag("subscription-node-name-$it") } ?: Modifier)
            // Protocol chip only; multiplier is omitted when the payload has no such field.
            Box(
                Modifier.clip(ProtocolChipShape)
                    .background(Brand.Cyan.copy(alpha = .10f))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    nodeProtocolLabel(protocol),
                    fontSize = 10.sp,
                    color = Brand.Cyan.copy(alpha = .9f),
                    maxLines = 1,
                    modifier = inspectionIndex?.let { Modifier.testTag("subscription-node-details-$it") } ?: Modifier,
                )
            }
        }
        NodeLatencyBadge(
            observation,
            Modifier.testTag(latencyTag),
            measuring = measuring,
            onRetest = onRetestLatency,
        )
        if (chosen == null) Text(readOnlyLabel, color = Brand.Muted, fontSize = 10.sp)
        else Box(
            Modifier.size(20.dp)
                .border(1.5.dp, if (chosen) Brand.Cyan else Brand.Border, CircleShape)
                .then(if (chosen) Modifier.background(Brand.Cyan, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (chosen) Canvas(Modifier.size(12.dp)) {
                drawLine(Brand.Background, Offset(size.width * .15f, size.height * .5f),
                    Offset(size.width * .4f, size.height * .75f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(Brand.Background, Offset(size.width * .4f, size.height * .75f),
                    Offset(size.width * .85f, size.height * .25f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}
