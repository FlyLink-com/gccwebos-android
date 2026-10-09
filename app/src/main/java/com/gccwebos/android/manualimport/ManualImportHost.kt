package com.gccwebos.android.manualimport

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import android.view.WindowManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.gccwebos.android.design.Brand
import com.gccwebos.android.design.BrandButton
import com.gccwebos.android.design.GlassSurface
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Ticket validation and source commit belong exclusively to the caller's coordinator.
 * onSubmit transfers byte ownership; the caller must consume/copy synchronously and wipe in finally.
 * No input, payload or pending ticket is saveable. A recreated host starts with an empty menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T : Any> ManualImportHost(
    visible: Boolean,
    busy: Boolean,
    error: String?,
    canUseAccount: Boolean = false,
    canRemove: Boolean = false,
    onDismiss: () -> Unit,
    onBegin: suspend () -> T?,
    onSubmit: (T, ByteArray, ManualImportInputKind) -> Unit,
    onCancel: (T) -> Unit,
    onUseAccount: () -> Unit,
    onRemove: () -> Unit,
    onFailure: (T, ManualImportFailure) -> Unit = { _, _ -> },
    platformOverride: ManualImportPlatform? = null,
) {
    if (!visible) return
    val platform = platformOverride ?: rememberManualImportPlatform()
    val scope = rememberCoroutineScope()
    val beginLatest by rememberUpdatedState(onBegin)
    val busyLatest by rememberUpdatedState(busy)
    val cancelLatest by rememberUpdatedState(onCancel)
    val failureLatest by rememberUpdatedState(onFailure)
    val owner = remember { ManualHostOwner<T> { cancelLatest(it) } }
    var mode by remember { mutableStateOf(ManualHostMode.Menu) }
    var link by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<ManualImportFailure?>(null) }
    var staged by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var pasteHint by remember { mutableStateOf<String?>(null) }
    DisposableEffect(owner) { onDispose { link = ""; owner.close() } }
    fun dismiss() { link = ""; owner.close(); onDismiss() }
    fun fail(ticket: T, reason: ManualImportFailure) {
        failure = reason
        failureLatest(ticket, reason)
    }
    fun begin(kind: ManualImportInputKind) {
        if (busyLatest || preparing || owner.closed || owner.ticket != null) return
        preparing = true
        val work = scope.launch(start = CoroutineStart.LAZY) {
            var acquired: T? = null
            var adopted = false
            try {
                val ticket = beginLatest() ?: return@launch
                acquired = ticket
                coroutineContext.ensureActive()
                if (owner.closed || busyLatest || owner.ticket != null) return@launch
                owner.ticket = ticket
                adopted = true
                owner.kind = kind
                failure = null
                if (kind == ManualImportInputKind.Link) mode = ManualHostMode.Link else {
                    mode = ManualHostMode.External
                    owner.external = platform.launch(kind) { result ->
                        if (!owner.accepts(ticket)) {
                            (result as? ManualInputResult.Content)?.bytes?.fill(0)
                        } else when (result) {
                            is ManualInputResult.Content -> {
                                owner.payload?.fill(0)
                                owner.payload = result.bytes
                                staged = true
                                mode = ManualHostMode.Confirm
                            }
                            is ManualInputResult.Failure -> fail(ticket, result.reason)
                        }
                    }
                }
            } finally {
                if (!adopted) acquired?.let { cancelLatest(it) }
                owner.preparation = null
                preparing = false
            }
        }
        owner.preparation = work
        work.start()
    }
    fun submit() {
        val ticket = owner.ticket ?: return
        if (busy || submitted || !owner.accepts(ticket)) return
        val bytes = try {
            if (mode == ManualHostMode.Link) ManualInputBytes.fromText(link)
            else owner.payload?.also { owner.payload = null } ?: return
        } catch (invalid: ManualInputException) { fail(ticket, invalid.failure); return }
        link = ""; staged = false; submitted = true
        try { onSubmit(ticket, bytes, requireNotNull(owner.kind)) }
        catch (_: Exception) {
            bytes.fill(0)
            fail(ticket, ManualImportFailure.InvalidInput)
        }
    }
    val menuEnabled = !busy && !preparing
    if (mode == ManualHostMode.Menu) {
        // Menu is a bottom sheet; link / confirm steps stay in a secure dialog (no auto clipboard read).
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = ::dismiss,
            sheetState = sheetState,
            containerColor = Brand.SurfaceRaised,
            contentColor = Brand.Text,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            modifier = Modifier.testTag("manual-import-host"),
        ) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("导入配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                ManualImportMenuRow(
                    title = "扫描二维码",
                    subtitle = "使用摄像头扫描订阅二维码",
                    enabled = menuEnabled,
                    testTag = "manual-import-scan",
                    onClick = { begin(ManualImportInputKind.Scan) },
                    icon = { ManualImportScanIcon() },
                )
                ManualImportMenuRow(
                    title = "导入订阅链接",
                    subtitle = "粘贴订阅链接导入",
                    enabled = menuEnabled,
                    testTag = "manual-import-link",
                    onClick = { begin(ManualImportInputKind.Link) },
                    icon = { ManualImportLinkIcon() },
                )
                ManualImportMenuRow(
                    title = "导入配置文件",
                    subtitle = "从手机存储选择配置文件",
                    enabled = menuEnabled,
                    testTag = "manual-import-document",
                    onClick = { begin(ManualImportInputKind.Document) },
                    icon = { ManualImportFileIcon() },
                )
                if (canUseAccount || canRemove) HorizontalDivider(color = Brand.RowDivider)
                if (canUseAccount) TextButton({ if (menuEnabled) onUseAccount() }, enabled = menuEnabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("manual-use-account")) { Text("使用账户订阅") }
                if (canRemove) TextButton({ if (menuEnabled) mode = ManualHostMode.Remove }, enabled = menuEnabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("manual-remove")) { Text("移除导入配置") }
                (failure?.caption ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("manual-import-error")) }
                TextButton(::dismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("manual-import-cancel")) { Text("取消") }
            }
        }
        return
    }
    Dialog(onDismissRequest = ::dismiss, properties = DialogProperties(
        securePolicy = SecureFlagPolicy.SecureOn, usePlatformDefaultWidth = false)) {
        // The link field never takes focus by itself: the keyboard appears only when the user taps it.
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { dialogWindow?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(mode) {
            if (mode != ManualHostMode.Link) return@LaunchedEffect
            withFrameNanos { }
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
        val clipboard = LocalClipboard.current
        val context = LocalContext.current
        // Reads the clipboard only on an explicit tap. The masked field never displays the pasted text.
        fun paste() {
            val ticket = owner.ticket ?: return
            if (busy || submitted || mode != ManualHostMode.Link) return
            scope.launch {
                val raw = try {
                    clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)
                } catch (_: Exception) { null }
                if (!owner.accepts(ticket) || mode != ManualHostMode.Link || submitted) return@launch
                val text = clipboardLinkText(raw)
                when {
                    text == null -> pasteHint = "剪贴板中没有可粘贴的内容"
                    text.length > ManualInputBytes.Limit -> fail(ticket, ManualImportFailure.TooLarge)
                    else -> { link = text; failure = null; pasteHint = null }
                }
            }
        }
        GlassSurface(Modifier.padding(20.dp).widthIn(max = 420.dp).fillMaxWidth().testTag("manual-import-host")) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when (mode) {
                    ManualHostMode.Link -> "订阅链接"
                    ManualHostMode.Remove -> "移除导入配置？"
                    else -> "导入配置"
                }, style = MaterialTheme.typography.titleMedium)
                if (mode == ManualHostMode.Remove) {
                    BrandButton("确认移除", { if (!busy) onRemove() }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("manual-remove-confirm"))
                } else {
                    if (mode == ManualHostMode.Link) OutlinedTextField(link, { value ->
                        if (value.length <= ManualInputBytes.Limit) { link = value; failure = null; pasteHint = null }
                        else owner.ticket?.let { fail(it, ManualImportFailure.TooLarge) }
                    }, label = { Text("订阅链接") }, singleLine = true, enabled = !busy && !submitted,
                        visualTransformation = PasswordVisualTransformation(),
                        trailingIcon = {
                            // IconButton keeps the 48dp minimum touch target inside the field.
                            IconButton(::paste, enabled = !busy && !submitted,
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("manual-link-paste")) {
                                Icon(PasteFromClipboardIcon, contentDescription = "从剪贴板粘贴",
                                    tint = if (!busy && !submitted) Brand.Cyan else Brand.DisabledText,
                                    modifier = Modifier.size(22.dp))
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("manual-link-input"))
                    if (mode == ManualHostMode.Link) pasteHint?.let { Text(it, color = Brand.Muted,
                        modifier = Modifier.testTag("manual-link-paste-hint")) }
                    if (mode != ManualHostMode.External) BrandButton("导入", ::submit,
                        enabled = !busy && !submitted && (staged || (mode == ManualHostMode.Link && link.isNotBlank())),
                        modifier = Modifier.fillMaxWidth().testTag("manual-import-confirm"))
                }
                (failure?.caption ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("manual-import-error")) }
                TextButton(::dismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("manual-import-cancel")) { Text("取消") }
            }
        }
    }
}

@Composable
private fun ManualImportMenuRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .background(Brand.Cyan.copy(alpha = if (enabled) .08f else .04f), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { icon() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = if (enabled) Brand.Text else Brand.DisabledText)
            Text(subtitle, fontSize = 12.sp, color = Brand.Muted)
        }
    }
}

@Composable
private fun ManualImportScanIcon() {
    Canvas(Modifier.size(24.dp)) {
        val s = 2.2.dp.toPx()
        val c = Brand.Cyan
        val inset = size.minDimension * .12f
        val arm = size.minDimension * .28f
        // Four corner brackets.
        drawLine(c, Offset(inset, inset + arm), Offset(inset, inset), s, StrokeCap.Round)
        drawLine(c, Offset(inset, inset), Offset(inset + arm, inset), s, StrokeCap.Round)
        drawLine(c, Offset(size.width - inset - arm, inset), Offset(size.width - inset, inset), s, StrokeCap.Round)
        drawLine(c, Offset(size.width - inset, inset), Offset(size.width - inset, inset + arm), s, StrokeCap.Round)
        drawLine(c, Offset(inset, size.height - inset - arm), Offset(inset, size.height - inset), s, StrokeCap.Round)
        drawLine(c, Offset(inset, size.height - inset), Offset(inset + arm, size.height - inset), s, StrokeCap.Round)
        drawLine(c, Offset(size.width - inset - arm, size.height - inset), Offset(size.width - inset, size.height - inset), s, StrokeCap.Round)
        drawLine(c, Offset(size.width - inset, size.height - inset - arm), Offset(size.width - inset, size.height - inset), s, StrokeCap.Round)
    }
}

@Composable
private fun ManualImportLinkIcon() {
    Canvas(Modifier.size(24.dp)) {
        val s = 2.2.dp.toPx()
        val c = Brand.Cyan
        // Two offset chain links (simple rounded rectangles as strokes).
        drawRoundRect(c, topLeft = Offset(size.width * .12f, size.height * .28f),
            size = androidx.compose.ui.geometry.Size(size.width * .46f, size.height * .28f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension * .14f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(s))
        drawRoundRect(c, topLeft = Offset(size.width * .42f, size.height * .44f),
            size = androidx.compose.ui.geometry.Size(size.width * .46f, size.height * .28f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension * .14f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(s))
    }
}

@Composable
private fun ManualImportFileIcon() {
    Canvas(Modifier.size(24.dp)) {
        val s = 2.2.dp.toPx()
        val c = Brand.Cyan
        val left = size.width * .22f
        val top = size.height * .12f
        val right = size.width * .78f
        val bottom = size.height * .88f
        val fold = size.width * .55f
        val foldY = size.height * .32f
        // Document outline with folded corner.
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(left, top); lineTo(fold, top); lineTo(right, foldY); lineTo(right, bottom)
            lineTo(left, bottom); close()
        }
        drawPath(path, c, style = androidx.compose.ui.graphics.drawscope.Stroke(s))
        drawLine(c, Offset(fold, top), Offset(fold, foldY), s, StrokeCap.Round)
        drawLine(c, Offset(fold, foldY), Offset(right, foldY), s, StrokeCap.Round)
        drawLine(c, Offset(left + size.width * .12f, size.height * .52f),
            Offset(right - size.width * .12f, size.height * .52f), s, StrokeCap.Round)
        drawLine(c, Offset(left + size.width * .12f, size.height * .66f),
            Offset(right - size.width * .18f, size.height * .66f), s, StrokeCap.Round)
    }
}

private enum class ManualHostMode { Menu, Link, External, Confirm, Remove }
private class ManualHostOwner<T : Any>(private val cancel: (T) -> Unit) {
    var ticket: T? = null
    var kind: ManualImportInputKind? = null
    var payload: ByteArray? = null
    var external: ManualInputCancellation? = null
    var preparation: Job? = null
    var closed = false
        private set
    fun accepts(value: T) = !closed && ticket === value
    fun close() {
        if (closed) return
        closed = true
        preparation?.cancel(); preparation = null
        external?.cancel(); external = null
        payload?.fill(0); payload = null
        ticket?.let(cancel); ticket = null
    }
}

/** Clipboard text for the link field: trimmed, or null when there is nothing usable to paste. */
internal fun clipboardLinkText(raw: CharSequence?): String? = raw?.toString()?.trim()?.takeIf { it.isNotEmpty() }

/** Two overlapping rounded squares (copy/paste glyph), drawn locally to avoid the icons-extended dependency. */
internal val PasteFromClipboardIcon: ImageVector by lazy {
    ImageVector.Builder("PasteFromClipboard", 24.dp, 24.dp, 24f, 24f).apply {
        // Back square: only the visible L-shaped edges, so it reads as sitting behind the front one.
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round) {
            moveTo(5f, 15f); lineTo(4.5f, 15f)
            curveTo(3.67f, 15f, 3f, 14.33f, 3f, 13.5f)
            lineTo(3f, 4.5f)
            curveTo(3f, 3.67f, 3.67f, 3f, 4.5f, 3f)
            lineTo(13.5f, 3f)
            curveTo(14.33f, 3f, 15f, 3.67f, 15f, 4.5f)
            lineTo(15f, 5f)
        }
        // Front square.
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round) {
            moveTo(10.5f, 9f); lineTo(19.5f, 9f)
            curveTo(20.33f, 9f, 21f, 9.67f, 21f, 10.5f)
            lineTo(21f, 19.5f)
            curveTo(21f, 20.33f, 20.33f, 21f, 19.5f, 21f)
            lineTo(10.5f, 21f)
            curveTo(9.67f, 21f, 9f, 20.33f, 9f, 19.5f)
            lineTo(9f, 10.5f)
            curveTo(9f, 9.67f, 9.67f, 9f, 10.5f, 9f)
            close()
        }
    }.build()
}
