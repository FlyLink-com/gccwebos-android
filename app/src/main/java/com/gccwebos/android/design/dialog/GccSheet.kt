package com.gccwebos.android.design.dialog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import com.gccwebos.android.design.Brand
import com.gccwebos.android.design.GlassMaterialPolicy
import kotlinx.coroutines.launch

/**
 * The shared bottom sheet (was `GccBottomSheet`): same glass-toned surface and 24 dp top radius, a standard header
 * ([title] and an optional ✕ via [showClose]), scrolling [content], and an optional pinned [actions] bar that never
 * scrolls away. Swipe down, scrim tap and back all dismiss.
 *
 * - [content] and [actions] receive `close`, which animates the sheet away before [onDismissRequest] runs.
 * - [secure] keeps FLAG_SECURE on the sheet window (details that can show a link, node or subscription).
 * - Height is capped at 85 % of the screen; reduce motion is handled by the sheet's own state, which follows the
 *   system animation scale.
 * - [closeTag] tags the ✕ button when [showClose] is true.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GccSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    showClose: Boolean = false,
    closeTag: String? = null,
    secure: Boolean = false,
    actions: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismissRequest)
    val close: () -> Unit = remember(sheetState, scope) {
        { scope.launch { sheetState.hide() }.invokeOnCompletion { dismiss() } }
    }
    val maxHeight = GccDialogRules.maxContentHeightDp(LocalConfiguration.current.screenHeightDp,
        GccDialogRules.SheetMaxHeightFraction).dp
    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState, modifier = modifier,
        containerColor = Brand.SurfaceRaised, contentColor = Brand.Text,
        // The scrim also swallows touches, so the page behind cannot scroll while the sheet is up.
        scrimColor = Color.Black.copy(alpha = GlassMaterialPolicy.SheetScrimAlpha),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        properties = ModalBottomSheetProperties(
            securePolicy = if (GccDialogRules.usesSecureWindow(secure)) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit)) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null || showClose) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title.orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).semantics { heading() })
                if (showClose) GccSheetCloseButton(close, closeTag)
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) { content(close) }
            actions?.let { bar -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { bar(close) } }
        }
    }
}

/** 48 dp touch target around a 22 dp ✕ drawn from two strokes; spoken as "关闭". */
@Composable
private fun GccSheetCloseButton(onClick: () -> Unit, tag: String?) {
    Box(Modifier.size(48.dp).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = "关闭" }.then(if (tag != null) Modifier.testTag(tag) else Modifier),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(18.dp)) {
            val s = 2.dp.toPx()
            drawLine(Brand.Muted, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, size.height), s, StrokeCap.Round)
            drawLine(Brand.Muted, androidx.compose.ui.geometry.Offset(size.width, 0f), androidx.compose.ui.geometry.Offset(0f, size.height), s, StrokeCap.Round)
        }
    }
}
