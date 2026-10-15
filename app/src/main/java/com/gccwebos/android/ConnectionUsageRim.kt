package com.gccwebos.android

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.unit.dp
import com.gccwebos.android.design.Brand

/**
 * The traffic card's rim (display-only, never read back as connection state).
 *  - Connected: a calm cyan rim glow, a slow 0.22..0.40 alpha pulse (2.8 s). It loops only while the card is on
 *    screen, the app is resumed and system animations are on; otherwise it rests at a static 0.28.
 *  - Not connected: nothing is drawn here; the card keeps the static dim rim passed as its edge ([UsageRimAlphaIdle]).
 * The glow is a separate overlay node with its own layer, so a pulse frame redraws only the rim, never the card's
 * content; the pulse value is read in the draw phase, so frames never recompose either.
 */
@Composable
internal fun UsageRimFrame(connected: Boolean, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(true) }
    val motion = rememberUsageMotion(connected, shown)
    val phase = remember { Animatable(0f) }
    LaunchedEffect(motion.breathing) {
        phase.snapTo(0f)
        if (motion.breathing) phase.animateTo(1f, infiniteRepeatable(tween(2_800, easing = EaseInOutSine), RepeatMode.Reverse))
    }
    Box(Modifier.fillMaxWidth().onVisibilityChanged(minFractionVisible = .05f) { shown = it }) {
        content()
        if (connected) Box(Modifier.matchParentSize().graphicsLayer().drawBehind {
            drawRim(if (motion.breathing) usageLerp(UsageRimAlphaMin, UsageRimAlphaMax, phase.value) else UsageRimAlphaStatic)
        })
    }
}

private fun DrawScope.drawRim(alpha: Float) {
    val radius = 22.dp.toPx()
    // Soft glow layers first (wide and faint), then the 1.5 dp rim itself.
    for ((width, weight) in listOf(8.dp to .15f, 4.dp to .30f, 1.5.dp to 1f)) {
        val w = width.toPx()
        val inset = w / 2
        drawRoundRect(Brand.Cyan.copy(alpha = (alpha * weight).coerceIn(0f, 1f)), Offset(inset, inset),
            Size(size.width - w, size.height - w), CornerRadius(radius - inset), style = Stroke(w))
    }
}
