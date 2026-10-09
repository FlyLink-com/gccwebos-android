package com.gccwebos.android.portal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.gccwebos.android.ConnectionControlState
import com.gccwebos.android.R
import com.gccwebos.android.design.Brand
import com.gccwebos.android.design.GlassSurface
import com.gccwebos.android.vpn.SafeProxyConfig

/** All saved sources are visible; only the active source receives opaque node choices. */
@Composable
internal fun ConfigurationLibraryPanel(library: ConfigurationLibraryController,
    state: NodeSelectionController, nodes: List<SafeProxyConfig.NodeSummary>, resource: Resource?,
    hasCapability: Boolean, accountBusy: Boolean, authenticated: Boolean = false,
    connectionControl: ConnectionControlState = ConnectionControlState(),
    onSelected: () -> Unit = {}, latency: NodeLatencyController? = null,
    onImport: () -> Unit, onReplace: (ConfigurationHandle) -> Unit, openAccount: () -> Unit) {
    LaunchedEffect(library) { library.refresh() }
    val busy = library.busy || accountBusy || library.snapshot.storageError
    val active = library.snapshot.items.any { it.active }
    val canMeasure = active && !busy && !state.busy && latency != null && !latency.busy &&
        latency.foreground && state.snapshot.status == LocalSubscriptionStatus.READY && state.snapshot.choices.isNotEmpty()
    ConfigurationLibraryContent(library.snapshot, busy, library.error, canMeasure,
        onMeasure = { latency?.refresh() }, onImport = onImport, onActivate = library::activate,
        onRename = library::rename, onRemove = library::remove, onReplace = onReplace,
        activeContent = { item, menu ->
            NodeSelectionPanel(state, nodes, resource, hasCapability, busy, authenticated, connectionControl,
                onSelected = onSelected, latency = latency, groupTitle = item.name,
                showTools = false, showAccountAction = false, headerActions = { menu() }, openAccount = openAccount)
        })
    if (!hasCapability && !authenticated) TextButton(openAccount, enabled = !busy,
        modifier = Modifier.heightIn(min = 48.dp).testTag("nodes-account")) { Text("登录") }
}

private enum class LibraryDialog { Details, Rename, Remove }

/** Pure presentation: callbacks carry handles, never display indexes, URLs or raw configurations. */
@Composable
internal fun ConfigurationLibraryContent(snapshot: ConfigurationLibrarySnapshot, busy: Boolean, error: String?,
    canMeasure: Boolean, onMeasure: () -> Unit, onImport: () -> Unit,
    onActivate: (ConfigurationHandle) -> Unit, onRename: (ConfigurationHandle, String) -> Unit,
    onRemove: (ConfigurationHandle) -> Unit, onReplace: (ConfigurationHandle) -> Unit,
    activeContent: @Composable (ConfigurationItem, @Composable () -> Unit) -> Unit) {
    val actionsBlocked = busy || snapshot.storageError
    val activeIndex = snapshot.items.indexOfFirst { it.active }
    val canMeasureActive = canMeasure && activeIndex >= 0
    var dialogHandle by remember { mutableStateOf<ConfigurationHandle?>(null) }
    var dialog by remember { mutableStateOf<LibraryDialog?>(null) }
    var name by remember { mutableStateOf("") }
    val target = snapshot.items.firstOrNull { it.handle === dialogHandle }
    fun closeDialog() { dialogHandle = null; dialog = null; name = "" }
    LaunchedEffect(snapshot.items) { if (dialogHandle != null && target == null) closeDialog() }
    Column(Modifier.fillMaxWidth().testTag("configuration-library"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("配置", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = { if (!actionsBlocked && canMeasureActive) onMeasure() }, enabled = !actionsBlocked && canMeasureActive,
                modifier = Modifier.size(48.dp).testTag("nodes-latency-refresh")) {
                Icon(painterResource(R.drawable.brand_lightning), "测试节点延迟", Modifier.size(21.dp),
                    tint = if (!actionsBlocked && canMeasureActive) Brand.Cyan else Brand.Muted)
            }
            IconButton(onClick = { if (!actionsBlocked) onImport() }, enabled = !actionsBlocked,
                modifier = Modifier.size(48.dp).testTag("nodes-import")) {
                Icon(painterResource(R.drawable.brand_receipt), "新增配置", Modifier.size(21.dp),
                    tint = if (actionsBlocked) Brand.Muted else Brand.Cyan)
            }
        }
        // Controller errors are fixed captions. Never interpolate an exception or source material.
        if (snapshot.storageError) Text("配置暂不可用", color = Brand.Muted,
            modifier = Modifier.testTag("configuration-storage-status"))
        else error?.let { Text(it, color = Brand.Muted, modifier = Modifier.testTag("configuration-action-status")) }
        val itemMenu: @Composable (ConfigurationItem, Int) -> Unit = { item, index ->
            ConfigurationItemMenu(item, index, actionsBlocked, onDetails = {
                dialogHandle = item.handle; dialog = LibraryDialog.Details
            }, onRename = {
                dialogHandle = item.handle; name = item.name; dialog = LibraryDialog.Rename
            }, onReplace = { onReplace(item.handle) }, onRemove = {
                dialogHandle = item.handle; dialog = LibraryDialog.Remove
            })
        }
        // A local metadata refresh replaces operation handles. Keep the active panel in one
        // stable slot so that refresh cannot dispose/cancel its independent node-read task.
        if (activeIndex >= 0) {
            val item = snapshot.items[activeIndex]
            activeContent(item) { itemMenu(item, activeIndex) }
        } else if (!snapshot.storageError) Text("未选择配置", color = Brand.Muted,
            modifier = Modifier.testTag("configuration-unselected"))
        // Inactive groups follow the saved entry, not the per-refresh handle, so a background
        // refresh cannot collapse a group the user expanded.
        snapshot.items.forEachIndexed { index, item ->
            if (index != activeIndex) key(item.displayKey) {
                InactiveConfigurationGroup(item, index, actionsBlocked, { onActivate(item.handle) }) { itemMenu(item, index) }
            }
        }
    }
    if (target != null && dialog != null) {
        val editable = target.kind != ManualSourceKind.LegacyRemote
        AlertDialog(onDismissRequest = { if (!actionsBlocked) closeDialog() }, containerColor = Brand.SurfaceRaised,
            shape = Brand.Shape, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            modifier = Modifier.testTag("configuration-dialog"),
            title = { Text(when (dialog) {
                LibraryDialog.Rename -> "重命名配置"
                LibraryDialog.Remove -> "删除配置？"
                else -> target.name
            }) },
            text = {
                when (dialog) {
                    LibraryDialog.Rename -> OutlinedTextField(name, { if (it.codePointCount(0, it.length) <= 48) name = it },
                        label = { Text("配置名称") }, singleLine = true, enabled = !actionsBlocked && editable,
                        modifier = Modifier.fillMaxWidth().testTag("configuration-name-input"))
                    LibraryDialog.Remove -> Text(target.name)
                    else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(configurationKindLabel(target.kind))
                        Text(configurationStatusLabel(target))
                        Text("${target.nodes.size} 条节点")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!actionsBlocked) {
                        val handle = target.handle
                        val action = dialog
                        val renamed = name.trim()
                        closeDialog()
                        if (editable && action == LibraryDialog.Rename && renamed.isNotEmpty()) onRename(handle, renamed)
                        else if (editable && action == LibraryDialog.Remove) onRemove(handle)
                    }
                }, enabled = !actionsBlocked && (dialog != LibraryDialog.Rename || editable && name.isNotBlank()),
                    modifier = Modifier.testTag("configuration-dialog-confirm")) {
                    Text(when (dialog) { LibraryDialog.Rename -> "保存"; LibraryDialog.Remove -> "删除"; else -> "完成" })
                }
            }, dismissButton = {
                if (dialog != LibraryDialog.Details) TextButton(onClick = ::closeDialog, enabled = !actionsBlocked,
                    modifier = Modifier.testTag("configuration-dialog-cancel")) { Text("取消") }
            })
    }
}

@Composable
private fun ConfigurationItemMenu(item: ConfigurationItem, index: Int, busy: Boolean,
    onDetails: () -> Unit, onRename: () -> Unit, onReplace: () -> Unit, onRemove: () -> Unit) {
    var open by remember(item.handle) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = !busy,
            modifier = Modifier.size(48.dp).testTag("configuration-menu-$index").semantics { contentDescription = "配置操作" }) {
            Canvas(Modifier.size(20.dp)) {
                listOf(.2f, .5f, .8f).forEach { drawCircle(Brand.Muted, radius = 1.6.dp.toPx(), center = Offset(size.width * it, size.height * .5f)) }
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("查看") }, enabled = !busy,
                onClick = { open = false; onDetails() }, modifier = Modifier.testTag("configuration-details-$index"))
            if (item.kind != ManualSourceKind.LegacyRemote) {
                DropdownMenuItem(text = { Text("重命名") }, enabled = !busy,
                    onClick = { open = false; onRename() }, modifier = Modifier.testTag("configuration-rename-$index"))
                DropdownMenuItem(text = { Text("替换配置") }, enabled = !busy,
                    onClick = { open = false; onReplace() }, modifier = Modifier.testTag("configuration-replace-$index"))
                DropdownMenuItem(text = { Text("删除") }, enabled = !busy,
                    onClick = { open = false; onRemove() }, modifier = Modifier.testTag("configuration-remove-$index"))
            }
        }
    }
}

@Composable
private fun InactiveConfigurationGroup(item: ConfigurationItem, index: Int, busy: Boolean,
    onActivate: () -> Unit, menu: @Composable () -> Unit) {
    var expanded by remember(item.displayKey) { mutableStateOf(false) }
    GlassSurface(Modifier.fillMaxWidth().testTag("configuration-group-$index"), shape = RoundedCornerShape(20.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).heightIn(min = 48.dp).testTag("configuration-toggle-$index")
                    .semantics { contentDescription = if (expanded) "收起配置节点" else "展开配置节点" }
                    .clickable(role = Role.Button) { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Canvas(Modifier.size(14.dp)) {
                        val first = if (expanded) Offset(size.width * .15f, size.height * .35f) else Offset(size.width * .35f, size.height * .15f)
                        val middle = if (expanded) Offset(size.width * .5f, size.height * .7f) else Offset(size.width * .7f, size.height * .5f)
                        val last = if (expanded) Offset(size.width * .85f, size.height * .35f) else Offset(size.width * .35f, size.height * .85f)
                        drawLine(Brand.Cyan, first, middle, 1.7.dp.toPx(), StrokeCap.Round)
                        drawLine(Brand.Cyan, middle, last, 1.7.dp.toPx(), StrokeCap.Round)
                    }
                    Column(Modifier.weight(1f).padding(vertical = 5.dp)) {
                        Text(item.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("configuration-name-$index"))
                        Text("${item.nodes.size} 条 · ${configurationStatusLabel(item)}", fontSize = 10.sp,
                            color = Brand.Muted, modifier = Modifier.testTag("configuration-status-$index"))
                    }
                }
                menu()
            }
            if (expanded) {
                item.nodes.forEachIndexed { nodeIndex, node ->
                    HorizontalDivider(color = Brand.RowDivider.copy(alpha = .12f))
                    NodeChoiceRow(node.name, node.protocol, node.supported, chosen = null,
                        regionTag = "configuration-region-$index-$nodeIndex", latencyTag = "configuration-latency-$index-$nodeIndex",
                        modifier = Modifier.testTag("configuration-node-$index-$nodeIndex"))
                }
                TextButton(onClick = { if (!busy) onActivate() }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("configuration-activate-$index")) { Text("选择配置") }
            }
        }
    }
}

/** The account subscription has no entry id; saved entries keep their id across refreshes. */
private object AccountConfigurationKey
private val ConfigurationItem.displayKey: Any get() = handle.entryId ?: AccountConfigurationKey

private fun configurationKindLabel(kind: ManualSourceKind): String = when (kind) {
    ManualSourceKind.LegacyRemote -> "账户订阅"
    ManualSourceKind.ImportedRemote -> "订阅配置"
    ManualSourceKind.ImportedLocal -> "本地文件"
    ManualSourceKind.ExternalRemote -> "第三方订阅"
    ManualSourceKind.None -> "未选择"
}

private fun configurationStatusLabel(item: ConfigurationItem): String = when (item.status) {
    LocalSubscriptionStatus.READY -> if (item.active) "使用中" else "可用"
    LocalSubscriptionStatus.EXPIRED -> "已到期"
    LocalSubscriptionStatus.MISSING -> "未导入"
    LocalSubscriptionStatus.RETRY_REQUIRED -> "待更新"
    LocalSubscriptionStatus.INVALID_LINK -> "链接不可用"
    LocalSubscriptionStatus.UNSUPPORTED -> "格式不支持"
    LocalSubscriptionStatus.STORAGE_ERROR -> "暂不可用"
}
