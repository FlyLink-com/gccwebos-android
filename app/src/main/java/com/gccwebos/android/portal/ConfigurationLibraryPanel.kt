package com.gccwebos.android.portal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.gccwebos.android.ConnectionControlState
import com.gccwebos.android.ConnectionPhase
import com.gccwebos.android.design.Brand
import com.gccwebos.android.design.GlassSurface
import com.gccwebos.android.design.NodeRegionIcon
import com.gccwebos.android.design.SlidingTab
import com.gccwebos.android.design.SlidingTabRow
import com.gccwebos.android.design.nodeDisplayName
import com.gccwebos.android.vpn.SafeProxyConfig

/** All saved sources are visible; only the active source receives opaque node choices. */
@Composable
internal fun ConfigurationLibraryPanel(library: ConfigurationLibraryController,
    state: NodeSelectionController, nodes: List<SafeProxyConfig.NodeSummary>, resource: Resource?,
    hasCapability: Boolean, accountBusy: Boolean, authenticated: Boolean = false,
    connectionControl: ConnectionControlState = ConnectionControlState(),
    onSelected: () -> Unit = {}, latency: NodeLatencyController? = null,
    namingEntryId: String? = null, onNamingDone: () -> Unit = {},
    onImport: () -> Unit, onReplace: (ConfigurationHandle) -> Unit, openAccount: () -> Unit) {
    LaunchedEffect(library) { library.refresh() }
    val busy = library.busy || accountBusy || library.snapshot.storageError
    val tunnelActive = connectionControl.phase in setOf(
        ConnectionPhase.Connecting, ConnectionPhase.Connected, ConnectionPhase.Disconnecting)
    // A signed-in account whose nodes are not loaded yet loads itself once per visit, but only while that
    // cannot discard anything: no tunnel, and no node the user already chose in another source.
    var autoLoadTried by remember(authenticated) { mutableStateOf(false) }
    LaunchedEffect(authenticated, library.snapshot, library.busy, accountBusy, tunnelActive, state.snapshot.selected) {
        val account = accountToAutoLoad(authenticated && !autoLoadTried && !busy && !tunnelActive, library.snapshot.items,
            state.snapshot.selected != null) ?: return@LaunchedEffect
        autoLoadTried = true
        library.activate(account.handle)
    }
    val active = library.snapshot.items.any { it.active }
    val activeMeasurable = active && state.snapshot.status == LocalSubscriptionStatus.READY && state.snapshot.choices.isNotEmpty()
    // Nodes of the other saved sources are tested together with the active source's, so they count too.
    val othersMeasurable = library.snapshot.items.any { item -> !item.active && item.status == LocalSubscriptionStatus.READY &&
        item.nodes.any { node -> !node.info && node.supported } }
    val canMeasure = !busy && !state.busy && latency != null && !latency.busy && latency.foreground &&
        (activeMeasurable || othersMeasurable)
    val contentKeys = library.snapshot.items.map { it.contentKey }
    // A changed source (import, replace, refresh) must be captured again; stale readings are dropped by content key.
    LaunchedEffect(latency, contentKeys) { latency?.libraryChanged() }
    // With no active source the page below never starts a batch itself; the other sources' nodes still need one.
    LaunchedEffect(latency, contentKeys, active, busy, state.busy, tunnelActive, latency?.foreground) {
        if (latency == null || active || busy || state.busy || tunnelActive || !othersMeasurable) return@LaunchedEffect
        withFrameNanos { }
        delay(NodeLatencyEntryDelayMillis)
        while (true) { latency.ensureMeasured(); delay(1000) }
    }
    ConfigurationLibraryContent(library.snapshot, busy, library.error, canMeasure,
        measuring = latency?.busy == true,
        latencyFor = { item, position -> latency?.libraryReading(item.handle.entryId ?: AccountSourceId, position, item.contentKey) },
        onMeasure = { latency?.refresh() }, onImport = onImport, onActivate = library::activate,
        onChooseNode = library::activateAndChoose,
        onRename = library::rename, onRemove = library::remove, onReplace = onReplace,
        onRenameNode = library::renameNode, onDeleteNode = library::deleteNode, onRestoreNode = library::restoreNode,
        tunnelActive = tunnelActive,
        namingEntryId = namingEntryId, onNamingDone = onNamingDone,
        activeContent = { item, list ->
            NodeSelectionPanel(state, nodes, resource, hasCapability, busy, authenticated, connectionControl,
                onSelected = onSelected, latency = latency, groupTitle = UnifiedListTitle,
                showTools = false, showAccountAction = false, headerActions = list.headerAction, measureInToolbar = false,
                sourceLabel = list.sourceLabel, rowTrailing = list.rowTrailing, extraCount = list.extraCount,
                showSourceRows = list.showSourceRows, extraRows = list.extraRows, openAccount = openAccount)
        })
    if (!hasCapability && !authenticated) TextButton(openAccount, enabled = !busy,
        modifier = Modifier.heightIn(min = 48.dp).testTag("nodes-account")) { Text("登录") }
}

/**
 * The signed-in account's own subscription row, when it should load by itself: it is listed but empty, nothing is
 * busy or connected, and it would not displace a node the user already chose in another source.
 */
internal fun accountToAutoLoad(idle: Boolean, items: List<ConfigurationItem>, nodeChosen: Boolean): ConfigurationItem? {
    if (!idle) return null
    val account = items.firstOrNull { it.kind == ManualSourceKind.LegacyRemote } ?: return null
    if (account.active || account.status != LocalSubscriptionStatus.MISSING) return null
    if (items.any { it.active } && nodeChosen) return null
    return account
}

private enum class LibraryDialog { Details, Rename, Remove, Name }

/** Pure presentation: callbacks carry handles, never display indexes, URLs or raw configurations. */
@Composable
internal fun ConfigurationLibraryContent(snapshot: ConfigurationLibrarySnapshot, busy: Boolean, error: String?,
    canMeasure: Boolean, measuring: Boolean = false, onMeasure: () -> Unit, onImport: () -> Unit,
    /** Latency shown on a row of a non-active source; null while unmeasured. Display-only. */
    latencyFor: (ConfigurationItem, Int) -> NodeLatencyObservation? = { _, _ -> null },
    onActivate: (ConfigurationHandle) -> Unit, onRename: (ConfigurationHandle, String) -> Unit,
    onRemove: (ConfigurationHandle) -> Unit, onReplace: (ConfigurationHandle) -> Unit,
    onChooseNode: (ConfigurationHandle, NodeTarget) -> Unit = { _, _ -> },
    onRenameNode: (ConfigurationItem, Int, String) -> Unit = { _, _, _ -> },
    onDeleteNode: (ConfigurationItem, Int) -> Unit = { _, _ -> },
    onRestoreNode: (ConfigurationItem, Int) -> Unit = { _, _ -> },
    /** While a tunnel is up or changing, nodes of another source cannot be chosen (that would switch the source). */
    tunnelActive: Boolean = false,
    namingEntryId: String? = null, onNamingDone: () -> Unit = {},
    activeContent: @Composable (ConfigurationItem, UnifiedList) -> Unit) {
    val actionsBlocked = busy || snapshot.storageError
    val activeIndex = snapshot.items.indexOfFirst { it.active }
    val canMeasureActive = canMeasure && snapshot.items.isNotEmpty()
    var dialogHandle by remember { mutableStateOf<ConfigurationHandle?>(null) }
    var dialog by remember { mutableStateOf<LibraryDialog?>(null) }
    var name by remember { mutableStateOf("") }
    var nodeEdit by remember { mutableStateOf<NodeEdit?>(null) }
    var nodeName by remember { mutableStateOf("") }
    var filterKey by remember { mutableStateOf<Any?>(null) }
    // The post-import naming dialog follows its saved entry id, because the import's own settle
    // refresh replaces every handle right after the dialog opens.
    val target = snapshot.items.firstOrNull { it.handle === dialogHandle } ?: if (dialog == LibraryDialog.Name)
        dialogHandle?.entryId?.let { id -> snapshot.items.firstOrNull { it.handle.entryId == id } } else null
    fun closeDialog() { dialogHandle = null; dialog = null; name = "" }
    LaunchedEffect(snapshot.items) { if (dialogHandle != null && target == null) closeDialog() }
    LaunchedEffect(namingEntryId, snapshot.items, dialog) {
        if (namingEntryId == null || dialog != null) return@LaunchedEffect
        val created = snapshot.items.firstOrNull { it.handle.entryId == namingEntryId } ?: return@LaunchedEffect
        onNamingDone()
        if (created.kind == ManualSourceKind.LegacyRemote) return@LaunchedEffect
        dialogHandle = created.handle; name = created.name; dialog = LibraryDialog.Name
    }
    Column(Modifier.fillMaxWidth().testTag("configuration-library"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        if (activeIndex < 0 && !snapshot.storageError) Text("未选择配置", color = Brand.Muted,
            modifier = Modifier.testTag("configuration-unselected"))
        // Source filter: only worth a row once there is more than one source.
        if (filterKey != null && snapshot.items.none { it.displayKey == filterKey }) filterKey = null
        // The add-configuration "+" is always the far-right control of this row; the segmented switcher fills the rest once
        // there is more than one source. (There is no page title above it any more.)
        SourceSwitcherRow(snapshot.items, filterKey, enabled = !actionsBlocked, onImport = onImport) { filterKey = it }
        // One list for every source. Nodes of the active source keep latency and selection; nodes of the
        // other sources sit in the same list and one tap makes their source active and selects exactly that node.
        val others = snapshot.items.withIndex().filter { (index, item) ->
            index != activeIndex && (filterKey == null || item.displayKey == filterKey)
        }
        val unified = UnifiedList(
            sourceLabel = snapshot.items.getOrNull(activeIndex)?.let(::nodeSourceLabel),
            rowTrailing = snapshot.items.getOrNull(activeIndex)?.takeIf { item -> item.nodeKeys.isNotEmpty() }?.let { item ->
                { position: Int ->
                    if (item.canEditNode(position)) NodeRowMenu("active-$position", actionsBlocked, deleteBlocked = tunnelActive,
                        onRename = { nodeEdit = NodeEdit(item.handle.entryId, position, false); nodeName = item.nodes[position].name },
                        onDelete = { nodeEdit = NodeEdit(item.handle.entryId, position, true) })
                }
            },
            extraCount = others.sumOf { it.value.nodes.count { node -> !node.info } },
            showSourceRows = filterKey == null || snapshot.items.getOrNull(activeIndex)?.displayKey == filterKey,
            headerAction = { NodesLatencyTestAction(enabled = !actionsBlocked && canMeasureActive, busy = measuring,
                onClick = { if (!actionsBlocked && canMeasureActive) onMeasure() }) },
            extraRows = {
                OtherSourceRows(others, actionsBlocked, tunnelActive, onActivate, onChooseNode, latencyFor, measuring) { item, position, delete ->
                    nodeEdit = NodeEdit(item.handle.entryId, position, delete); nodeName = item.nodes[position].name
                }
            })
        if (activeIndex >= 0) {
            // Stable slot: a local metadata refresh replaces handles and must not dispose/cancel the node-read task.
            activeContent(snapshot.items[activeIndex], unified)
        } else if (others.isNotEmpty()) {
            GlassSurface(Modifier.fillMaxWidth().testTag("nodes-configuration-group"), shape = RoundedCornerShape(20.dp)) {
                Column {
                    // No active source: the same heading row (节点列表 left, 延迟测试 right) above the saved sources' nodes.
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp).heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 5.dp)) {
                            Text(UnifiedListTitle, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("nodes-group-title"))
                            Text("${unified.extraCount} 条", fontSize = 10.sp, color = Brand.Muted, modifier = Modifier.testTag("nodes-count"))
                        }
                        unified.headerAction(this)
                    }
                    unified.extraRows(this)
                }
            }
        }
        // Locally deleted nodes: collapsed at the bottom of the list, only when there are any. They can only be restored.
        val deleted = snapshot.items.withIndex().filter { (_, item) -> filterKey == null || item.displayKey == filterKey }
            .flatMap { (index, item) -> item.nodeDeleted.sorted().map { position -> Triple(index, item, position) } }
        if (deleted.isNotEmpty()) DeletedNodesSection(deleted, actionsBlocked, onRestoreNode)
        // Where every node comes from; also the place for source-level actions (rename / replace / delete).
        if (snapshot.items.isNotEmpty()) SourceList(snapshot.items) { item, index ->
            ConfigurationItemMenu(item, index, actionsBlocked, onDetails = {
                dialogHandle = item.handle; dialog = LibraryDialog.Details
            }, onRename = {
                dialogHandle = item.handle; name = item.name; dialog = LibraryDialog.Rename
            }, onReplace = { onReplace(item.handle) }, onRemove = {
                dialogHandle = item.handle; dialog = LibraryDialog.Remove
            })
        }
    }
    nodeEdit?.let { edit ->
        val item = snapshot.items.firstOrNull { it.handle.entryId == edit.entryId }
        val node = item?.nodes?.getOrNull(edit.position)
        if (item == null || node == null || !item.canEditNode(edit.position)) { nodeEdit = null } else {
            val tooLong = nodeName.codePointCount(0, nodeName.length) > ConfigurationName.MAX_CODE_POINTS
            val done = { nodeEdit = null; nodeName = "" }
            AlertDialog(onDismissRequest = { if (!actionsBlocked) done() }, containerColor = Brand.SurfaceRaised,
                shape = Brand.Shape, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
                modifier = Modifier.testTag("node-dialog"),
                title = { Text(if (edit.delete) "删除节点？" else "重命名节点") },
                text = {
                    if (edit.delete) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(node.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("node-dialog-name"))
                        Text("只在本机删除，不会改动订阅本身；订阅更新后仍保持删除。可在列表底部的「已删除节点」里恢复。",
                            color = Brand.Muted, fontSize = 12.sp)
                    } else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(nodeName, { if (it.codePointCount(0, it.length) <= ConfigurationName.MAX_CODE_POINTS) nodeName = it },
                            label = { Text("节点名称") }, singleLine = true, enabled = !actionsBlocked,
                            modifier = Modifier.fillMaxWidth().testTag("node-name-input"))
                        Text("只改本机显示的名称，留空可恢复原名。", color = Brand.Muted, fontSize = 12.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (!actionsBlocked) {
                            val typed = nodeName
                            done()
                            if (edit.delete) onDeleteNode(item, edit.position) else onRenameNode(item, edit.position, typed)
                        }
                    }, enabled = !actionsBlocked && !tooLong, modifier = Modifier.testTag("node-dialog-confirm")) {
                        Text(if (edit.delete) "删除" else "保存")
                    }
                },
                dismissButton = {
                    TextButton(onClick = done, enabled = !actionsBlocked, modifier = Modifier.testTag("node-dialog-cancel")) { Text("取消") }
                })
        }
    }
    if (target != null && dialog != null) {
        val editable = target.kind != ManualSourceKind.LegacyRemote
        val naming = dialog == LibraryDialog.Rename || dialog == LibraryDialog.Name
        // Compared against every other visible configuration name, including the account subscription.
        val duplicateName = naming && ConfigurationName.conflicts(name, snapshot.items.filter { it !== target }.map { it.name })
        AlertDialog(onDismissRequest = { if (!actionsBlocked) closeDialog() }, containerColor = Brand.SurfaceRaised,
            shape = Brand.Shape, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            modifier = Modifier.testTag("configuration-dialog"),
            title = { Text(when (dialog) {
                LibraryDialog.Rename -> "重命名配置"
                LibraryDialog.Name -> "为订阅命名"
                LibraryDialog.Remove -> "删除配置？"
                else -> target.name
            }) },
            text = {
                when (dialog) {
                    LibraryDialog.Rename, LibraryDialog.Name -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(name, { if (it.codePointCount(0, it.length) <= 48) name = it },
                            label = { Text(if (dialog == LibraryDialog.Name) "订阅名称" else "配置名称") }, singleLine = true,
                            enabled = !actionsBlocked && editable, isError = duplicateName,
                            modifier = Modifier.fillMaxWidth().testTag("configuration-name-input"))
                        if (duplicateName) Text(ConfigurationName.DUPLICATE_MESSAGE, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("configuration-name-duplicate"))
                    }
                    LibraryDialog.Remove -> Text(target.name)
                    else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(configurationKindLabel(target.kind))
                        Text(configurationStatusLabel(target))
                        Text("${target.nodes.count { !it.info }} 条节点")
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
                        if (editable && (action == LibraryDialog.Rename || action == LibraryDialog.Name) && renamed.isNotEmpty() &&
                            !duplicateName && (action == LibraryDialog.Rename || renamed != target.name)) onRename(handle, renamed)
                        else if (editable && action == LibraryDialog.Remove) onRemove(handle)
                    }
                }, enabled = !actionsBlocked && (!naming || editable && name.isNotBlank() && !duplicateName),
                    modifier = Modifier.testTag("configuration-dialog-confirm")) {
                    Text(when (dialog) { LibraryDialog.Rename, LibraryDialog.Name -> "保存"; LibraryDialog.Remove -> "删除"; else -> "完成" })
                }
            }, dismissButton = {
                if (dialog != LibraryDialog.Details) TextButton(onClick = ::closeDialog, enabled = !actionsBlocked,
                    modifier = Modifier.testTag("configuration-dialog-cancel")) { Text(if (dialog == LibraryDialog.Name) "跳过" else "取消") }
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

internal const val UnifiedListTitle = "节点列表"

/** Slots the unified list hands to the active source's card. */
internal class UnifiedList(val sourceLabel: String?, val rowTrailing: (@Composable (Int) -> Unit)?,
    val extraCount: Int, val showSourceRows: Boolean, val extraRows: @Composable ColumnScope.() -> Unit,
    /** 延迟测试 for the whole library, right-aligned on the 节点列表 heading row. */
    val headerAction: @Composable RowScope.() -> Unit = {})

private data class NodeEdit(val entryId: String?, val position: Int, val delete: Boolean)

/** "来源" caption of one node: the saved source's name and kind; the account subscription says it is read-only. */
internal fun nodeSourceLabel(item: ConfigurationItem): String =
    if (item.kind == ManualSourceKind.LegacyRemote) "账户订阅 · 只读" else "${item.name} · ${configurationKindLabel(item.kind)}"

/**
 * The segmented switcher (the plans page's [SlidingTabRow]): 全部 plus one tab per source, then the 添加配置 "+" at the far
 * right. Fewer than two sources have nothing to switch between, so only the label and the "+" show.
 */
@Composable
private fun SourceSwitcherRow(items: List<ConfigurationItem>, selected: Any?, enabled: Boolean, onImport: () -> Unit,
    onSelect: (Any?) -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("unified-filter"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (items.size >= 2) {
            val tabs = listOf(SlidingTab("全部", "unified-filter-all")) +
                items.mapIndexed { index, item -> SlidingTab(item.name, "unified-filter-$index") }
            val selectedIndex = items.indexOfFirst { it.displayKey == selected }.let { if (it < 0) 0 else it + 1 }
            SlidingTabRow(tabs, selectedIndex, enabled = true, modifier = Modifier.weight(1f), onSelect = { index ->
                onSelect(if (index == 0) null else items[index - 1].displayKey.takeIf { it != selected })
            })
        } else Text("添加配置", fontSize = 13.sp, color = Brand.Muted,
            modifier = Modifier.weight(1f).padding(start = 8.dp).testTag("unified-add-label"))
        NodesImportButton(onClick = { if (enabled) onImport() }, enabled = enabled)
    }
}

@Composable
private fun ColumnScope.OtherSourceRows(others: List<IndexedValue<ConfigurationItem>>, busy: Boolean, tunnelActive: Boolean,
    onActivate: (ConfigurationHandle) -> Unit, onChooseNode: (ConfigurationHandle, NodeTarget) -> Unit,
    latencyFor: (ConfigurationItem, Int) -> NodeLatencyObservation?, measuring: Boolean,
    onEdit: (ConfigurationItem, Int, Boolean) -> Unit) {
    if (others.isEmpty()) return
    if (tunnelActive && others.any { it.value.nodes.any { node -> !node.info } }) Text(
        "已连接时只能选用当前来源的节点；要用其他来源的节点，请先断开连接。",
        fontSize = 11.sp, color = Brand.Amber, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("unified-connected-hint"))
    others.forEach { (index, item) ->
        val label = nodeSourceLabel(item)
        val visible = item.nodes.withIndex().filterNot { it.value.info }
        if (visible.isEmpty()) {
            HorizontalDivider(color = Brand.RowDivider.copy(alpha = .12f))
            val loadable = item.kind == ManualSourceKind.LegacyRemote && item.status == LocalSubscriptionStatus.MISSING
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("configuration-empty-$index")
                .then(if (loadable) Modifier.clickable(enabled = !busy && !tunnelActive, role = Role.Button,
                    onClickLabel = "加载账户节点") { onActivate(item.handle) } else Modifier)
                .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (loadable) "节点还没加载，点一下加载账户订阅的节点" else "没有可选节点 · ${configurationStatusLabel(item)}",
                        fontSize = 11.sp, color = Brand.Muted)
                }
            }
        } else visible.forEach { (position, node) ->
            HorizontalDivider(color = Brand.RowDivider.copy(alpha = .12f))
            // One tap chooses this node as the egress: its source becomes active first (same fenced
            // activation as 选择配置) and the node is then selected if it is still present.
            val choosable = node.supported && !busy && !tunnelActive
            val observation = latencyFor(item, position)
            NodeChoiceRow(node.name, node.protocol, node.supported, chosen = false,
                observation = observation, measuring = measuring && observation == null && node.supported,
                tunnelActive = tunnelActive,
                regionTag = "configuration-region-$index-$position", latencyTag = "configuration-latency-$index-$position",
                sourceLabel = label,
                trailing = if (item.canEditNode(position)) ({
                    NodeRowMenu("$index-$position", busy, deleteBlocked = false,
                        onRename = { onEdit(item, position, false) }, onDelete = { onEdit(item, position, true) })
                }) else null,
                modifier = Modifier.testTag("configuration-node-$index-$position")
                    .clickable(enabled = choosable, role = Role.Button, onClickLabel = "选择此节点") {
                        onChooseNode(item.handle, NodeTarget(position, node.name, node.protocol))
                    })
        }
    }
}

@Composable
private fun DeletedNodesSection(deleted: List<Triple<Int, ConfigurationItem, Int>>, busy: Boolean,
    onRestore: (ConfigurationItem, Int) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    GlassSurface(Modifier.fillMaxWidth().testTag("unified-deleted"), shape = RoundedCornerShape(20.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("unified-deleted-toggle")
                .semantics { contentDescription = if (expanded) "收起已删除节点" else "展开已删除节点" }
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Canvas(Modifier.size(14.dp)) {
                    val first = if (expanded) Offset(size.width * .15f, size.height * .35f) else Offset(size.width * .35f, size.height * .15f)
                    val middle = if (expanded) Offset(size.width * .5f, size.height * .7f) else Offset(size.width * .7f, size.height * .5f)
                    val last = if (expanded) Offset(size.width * .85f, size.height * .35f) else Offset(size.width * .35f, size.height * .85f)
                    drawLine(Brand.Muted, first, middle, 1.7.dp.toPx(), StrokeCap.Round)
                    drawLine(Brand.Muted, middle, last, 1.7.dp.toPx(), StrokeCap.Round)
                }
                Text("已删除节点", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Brand.Muted, modifier = Modifier.weight(1f))
                Text("${deleted.size} 条", fontSize = 10.sp, color = Brand.Muted, modifier = Modifier.testTag("unified-deleted-count"))
            }
            if (expanded) deleted.forEach { (index, item, position) ->
                HorizontalDivider(color = Brand.RowDivider.copy(alpha = .12f))
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("deleted-node-$index-$position")
                    .padding(start = 15.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NodeRegionIcon(item.nodes[position].name, Modifier, size = 26.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(nodeDisplayName(item.nodes[position].name), fontSize = 13.sp, color = Brand.Muted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(nodeSourceLabel(item), fontSize = 10.sp, color = Brand.Muted, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    var open by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { open = true }, enabled = !busy,
                            modifier = Modifier.size(40.dp).testTag("deleted-menu-$index-$position")
                                .semantics { contentDescription = "已删除节点操作" }) {
                            Canvas(Modifier.size(18.dp)) {
                                listOf(.2f, .5f, .8f).forEach { drawCircle(Brand.Muted, radius = 1.5.dp.toPx(), center = Offset(size.width * .5f, size.height * it)) }
                            }
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            DropdownMenuItem(text = { Text("恢复") }, enabled = !busy,
                                onClick = { open = false; onRestore(item, position) },
                                modifier = Modifier.testTag("deleted-restore-$index-$position"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeRowMenu(tag: String, busy: Boolean, deleteBlocked: Boolean, onRename: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = !busy,
            modifier = Modifier.size(40.dp).testTag("node-menu-$tag").semantics { contentDescription = "节点操作" }) {
            Canvas(Modifier.size(18.dp)) {
                listOf(.2f, .5f, .8f).forEach { drawCircle(Brand.Muted, radius = 1.5.dp.toPx(), center = Offset(size.width * .5f, size.height * it)) }
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("重命名") }, enabled = !busy,
                onClick = { open = false; onRename() }, modifier = Modifier.testTag("node-rename-$tag"))
            DropdownMenuItem(text = { Text(if (deleteBlocked) "删除（断开连接后可用）" else "删除") }, enabled = !busy && !deleteBlocked,
                onClick = { open = false; onDelete() }, modifier = Modifier.testTag("node-delete-$tag"))
        }
    }
}

@Composable
private fun SourceList(items: List<ConfigurationItem>,
    menu: @Composable (ConfigurationItem, Int) -> Unit) {
    GlassSurface(Modifier.fillMaxWidth().testTag("unified-sources"), shape = RoundedCornerShape(20.dp)) {
        Column {
            Text("来源", fontSize = 12.sp, color = Brand.Muted, modifier = Modifier.padding(start = 14.dp, top = 10.dp, end = 14.dp))
            items.forEachIndexed { index, item ->
                key(item.displayKey) {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp).testTag("configuration-group-$index"),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(item.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("configuration-name-$index"))
                            Text(groupCaption(item), fontSize = 10.sp, color = Brand.Muted,
                                modifier = Modifier.testTag("configuration-status-$index"))
                        }
                        menu(item, index)
                    }
                }
            }
        }
    }
}

/** The account subscription has no entry id; saved entries keep their id across refreshes. */
private object AccountConfigurationKey
private val ConfigurationItem.displayKey: Any get() = handle.entryId ?: AccountConfigurationKey

/** Source label shown next to the node count, so every group says where its nodes come from. */
internal fun groupCaption(item: ConfigurationItem): String =
    "${item.nodes.count { !it.info }} 条 · ${configurationKindLabel(item.kind)} · ${configurationStatusLabel(item)}"

internal fun configurationKindLabel(kind: ManualSourceKind): String = when (kind) {
    ManualSourceKind.LegacyRemote -> "账户订阅"
    ManualSourceKind.ImportedRemote -> "订阅配置"
    ManualSourceKind.ImportedLocal -> "本地文件"
    ManualSourceKind.ExternalRemote -> "第三方订阅"
    ManualSourceKind.None -> "未选择"
}

internal fun configurationStatusLabel(item: ConfigurationItem): String = when (item.status) {
    LocalSubscriptionStatus.READY -> if (item.active) "使用中" else "可用"
    LocalSubscriptionStatus.EXPIRED -> "已到期"
    // The signed-in account row exists before its first download: selecting it loads the nodes.
    LocalSubscriptionStatus.MISSING -> if (item.kind == ManualSourceKind.LegacyRemote) "待加载" else "未导入"
    LocalSubscriptionStatus.RETRY_REQUIRED -> "待更新"
    LocalSubscriptionStatus.INVALID_LINK -> "链接不可用"
    LocalSubscriptionStatus.UNSUPPORTED -> "格式不支持"
    LocalSubscriptionStatus.STORAGE_ERROR -> "暂不可用"
}
