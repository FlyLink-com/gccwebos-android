package com.gccwebos.android.design.dialog

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import com.gccwebos.android.design.Brand
import com.gccwebos.android.design.GccSemantic
import com.gccwebos.android.design.GlassSurface
import com.gccwebos.android.design.rememberUiMotionEnabled

/**
 * One button of a [GccDialog]. [loading] shows a spinner in place of the label and, like the dialog, disables
 * every action while it runs. [testTag] is applied to the button so existing UI tests keep their handles.
 */
internal data class GccDialogAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val loading: Boolean = false,
    val testTag: String? = null,
)

/**
 * The shared centred dialog: glass surface, optional round icon badge, title, plain [body] and/or a free
 * [content] slot (scrolls past ~80 % of the screen height; the actions stay pinned), then up to three actions.
 *
 * - [primary] is the gradient button (red when [tone] is [GccDialogTone.Danger]); [secondary] is outlined;
 *   [tertiary] is a quiet text button. One or two short actions share a row, three or any long label stack with
 *   the primary action first (see [GccDialogRules.actionLayout]).
 * - [secure] keeps FLAG_SECURE on this dialog's window; pass true for any dialog that can show a link, node or
 *   subscription detail so it stays out of screenshots and the recents thumbnail.
 * - Reduce motion ("remove animations"): the dialog is shown at full opacity on the first frame, no scale.
 * - [testTag] tags the surface; the title and body get `"$testTag-title"` / `"$testTag-body"`.
 */
@Composable
internal fun GccDialog(
    onDismissRequest: () -> Unit,
    title: String,
    primary: GccDialogAction,
    modifier: Modifier = Modifier,
    icon: GccDialogIcon? = null,
    tone: GccDialogTone = GccDialogTone.Neutral,
    body: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
    secondary: GccDialogAction? = null,
    tertiary: GccDialogAction? = null,
    dismissOnOutsideTap: Boolean = true,
    secure: Boolean = false,
    testTag: String? = null,
) {
    val busy = listOfNotNull(primary, secondary, tertiary).any { it.loading }
    Dialog(onDismissRequest = { if (!busy) onDismissRequest() }, properties = DialogProperties(
        dismissOnBackPress = !busy, dismissOnClickOutside = dismissOnOutsideTap && !busy,
        securePolicy = if (GccDialogRules.usesSecureWindow(secure)) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit,
        usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        // The window dim is the scrim; one alpha for every dialog.
        SideEffect { window?.setDimAmount(GccDialogRules.ScrimAlpha) }
        val motion = rememberUiMotionEnabled()
        val enter = remember(motion) { GccDialogRules.enter(motion) }
        val progress = remember(enter) { Animatable(if (enter.animated) 0f else 1f) }
        LaunchedEffect(enter) {
            if (enter.animated) progress.animateTo(1f, tween(enter.fadeMillis, easing = FastOutSlowInEasing))
            else progress.snapTo(1f)
        }
        val maxHeight = GccDialogRules.maxContentHeightDp(LocalConfiguration.current.screenHeightDp).dp
        GlassSurface(modifier.padding(horizontal = 24.dp).widthIn(max = GccDialogRules.MaxWidthDp.dp).fillMaxWidth()
            .heightIn(max = maxHeight)
            .graphicsLayer {
                val p = progress.value
                alpha = p
                val s = enter.startScale + (1f - enter.startScale) * p
                scaleX = s; scaleY = s
            }.then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
            shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 20.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (icon != null) GccDialogBadge(if (tone == GccDialogTone.Danger) GccDialogIcon.Danger else icon)
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Brand.Text,
                        modifier = Modifier.semantics { heading() }
                            .then(if (testTag != null) Modifier.testTag("$testTag-title") else Modifier))
                    if (body != null) Text(body, style = MaterialTheme.typography.bodyLarge, color = Brand.Muted,
                        modifier = Modifier.then(if (testTag != null) Modifier.testTag("$testTag-body") else Modifier))
                    content?.invoke(this)
                }
                Spacer(Modifier.height(20.dp))
                GccDialogActions(primary, secondary, tertiary, tone, busy)
            }
        }
    }
}

@Composable
private fun GccDialogActions(primary: GccDialogAction, secondary: GccDialogAction?, tertiary: GccDialogAction?,
                             tone: GccDialogTone, busy: Boolean) {
    val labels = listOfNotNull(primary.label, secondary?.label, tertiary?.label)
    val layout = GccDialogRules.actionLayout(labels)
    val primaryButton: @Composable (Modifier) -> Unit = { m -> GccPrimaryButton(primary, tone, busy, m) }
    val secondaryButton: @Composable (Modifier) -> Unit = { m -> secondary?.let { GccSecondaryButton(it, busy, m) } }
    val tertiaryButton: @Composable (Modifier) -> Unit = { m -> tertiary?.let { GccTertiaryButton(it, busy, m) } }
    if (layout == GccDialogActionLayout.Stacked) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            primaryButton(Modifier.fillMaxWidth())
            secondaryButton(Modifier.fillMaxWidth())
            tertiaryButton(Modifier.fillMaxWidth())
        }
    } else {
        // Side by side reads "dismiss | confirm": the primary action is always on the right, as on the stacked order's first line.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (secondary != null) secondaryButton(Modifier.weight(1f))
            primaryButton(Modifier.weight(1f))
        }
    }
}

private val DangerGradient = listOf(Color(0xFFC93545), Color(0xFF9E2535))

@Composable
internal fun GccPrimaryButton(action: GccDialogAction, tone: GccDialogTone = GccDialogTone.Neutral, busy: Boolean = false, modifier: Modifier = Modifier) {
    val enabled = action.enabled && !busy
    val shape = RoundedCornerShape(14.dp)
    val fill = when {
        !enabled && !action.loading -> Brush.horizontalGradient(listOf(Brand.Disabled, Brand.Disabled))
        tone == GccDialogTone.Danger -> Brush.horizontalGradient(DangerGradient)
        else -> Brush.horizontalGradient(listOf(Brand.ActionStart, Brand.ActionMiddle, Brand.ActionEnd))
    }
    Button(action.onClick, enabled = enabled || action.loading, shape = shape,
        modifier = modifier.heightIn(min = 48.dp).clip(shape).background(fill)
            .then(if (action.testTag != null) Modifier.testTag(action.testTag) else Modifier),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.White,
            disabledContainerColor = Color.Transparent, disabledContentColor = Brand.DisabledText)) {
        GccButtonLabel(action)
    }
}

@Composable
internal fun GccSecondaryButton(action: GccDialogAction, busy: Boolean = false, modifier: Modifier = Modifier) {
    OutlinedButton(action.onClick, enabled = action.enabled && !busy, shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Brand.GlassBorder.copy(alpha = .45f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Text, disabledContentColor = Brand.DisabledText),
        modifier = modifier.heightIn(min = 48.dp).then(if (action.testTag != null) Modifier.testTag(action.testTag) else Modifier)) {
        GccButtonLabel(action)
    }
}

@Composable
internal fun GccTertiaryButton(action: GccDialogAction, busy: Boolean = false, modifier: Modifier = Modifier) {
    TextButton(action.onClick, enabled = action.enabled && !busy,
        colors = ButtonDefaults.textButtonColors(contentColor = Brand.Muted, disabledContentColor = Brand.DisabledText),
        modifier = modifier.heightIn(min = 48.dp).then(if (action.testTag != null) Modifier.testTag(action.testTag) else Modifier)) {
        GccButtonLabel(action)
    }
}

@Composable
private fun GccButtonLabel(action: GccDialogAction) {
    if (action.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
    else Text(action.label, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2)
}

/** Round tinted badge with a glyph drawn from simple strokes; [shown] already resolves the Danger tone. */
@Composable
private fun GccDialogBadge(shown: GccDialogIcon) {
    val tint = when (shown) {
        GccDialogIcon.Info -> Brand.Cyan
        GccDialogIcon.Success -> Brand.Green
        GccDialogIcon.Warning -> GccSemantic.Gold
        GccDialogIcon.Danger -> GccSemantic.Danger
    }
    Box(Modifier.size(44.dp).background(tint.copy(alpha = .16f), CircleShape), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(22.dp)) {
            val w = size.width
            val stroke = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (shown) {
                GccDialogIcon.Info -> {
                    drawLine(tint, Offset(w / 2, w * .46f), Offset(w / 2, w * .78f), stroke.width, StrokeCap.Round)
                    drawCircle(tint, radius = stroke.width * .62f, center = Offset(w / 2, w * .26f))
                }
                GccDialogIcon.Warning, GccDialogIcon.Danger -> {
                    drawLine(tint, Offset(w / 2, w * .2f), Offset(w / 2, w * .58f), stroke.width, StrokeCap.Round)
                    drawCircle(tint, radius = stroke.width * .62f, center = Offset(w / 2, w * .8f))
                }
                GccDialogIcon.Success -> {
                    val check = Path().apply {
                        moveTo(w * .14f, w * .54f); lineTo(w * .4f, w * .78f); lineTo(w * .86f, w * .24f)
                    }
                    drawPath(check, tint, style = stroke)
                }
            }
        }
    }
}
