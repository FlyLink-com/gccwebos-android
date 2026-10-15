package com.gccwebos.android.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gccwebos.android.rememberUsageMotion

/** Calm cyan rim pulse (3.2 s, alpha 0.25..0.70); static 0.42 when paused, off screen or motion is off. */
internal const val BreathingMillis = 3_200
internal const val BreathingAlphaMin = 0.25f
internal const val BreathingAlphaMax = 0.70f
internal const val BreathingAlphaStatic = 0.42f
/** Faint inner wash behind the content; its worst case is covered by contrast tests. */
internal const val BreathingWashAlphaMax = 0.08f

internal fun breathingAlpha(fraction: Float): Float =
    BreathingAlphaMin + (BreathingAlphaMax - BreathingAlphaMin) * fraction.coerceIn(0f, 1f)

/**
 * Breathing-light rim for a card: a separate overlay node (own layer, drawn under the card's content, no pointer input),
 * so a pulse frame redraws only the rim, never the card, and nothing is recomposed (the value is read in the draw phase).
 * It loops only while the card is on screen, the app is RESUMED and system animations are on (same gate as the traffic
 * card's rim, [rememberUsageMotion]); otherwise it rests at [BreathingAlphaStatic].
 * Call inside a Box that is clipped to the card shape; [shown] is fed by the caller's [rememberShownState] modifier.
 */
@Composable
internal fun BoxScope.BreathingGlowOverlay(radius: Dp, shown: Boolean, tag: String) {
    val motion = rememberUsageMotion(connected = true, shown = shown)
    val phase = remember { Animatable(0f) }
    LaunchedEffect(motion.breathing) {
        phase.snapTo(0f)
        if (motion.breathing) phase.animateTo(1f, infiniteRepeatable(tween(BreathingMillis, easing = EaseInOutSine), RepeatMode.Reverse))
    }
    androidx.compose.foundation.layout.Box(Modifier.matchParentSize().graphicsLayer().testTag(tag).drawBehind {
        drawBreathingRim(radius.toPx(), if (motion.breathing) breathingAlpha(phase.value) else BreathingAlphaStatic)
    })
}

/** Visibility feed for [BreathingGlowOverlay]: >= 5% of the host on screen counts as shown. */
@Composable
internal fun rememberShownState(): Pair<Boolean, (Modifier) -> Modifier> {
    var shown by remember { mutableStateOf(true) }
    return shown to { m -> m.onVisibilityChanged(minFractionVisible = .05f) { shown = it } }
}

private fun DrawScope.drawBreathingRim(radius: Float, alpha: Float) {
    drawRoundRect(Color(0xFF39D7FF).copy(alpha = BreathingWashAlphaMax * alpha), cornerRadius = CornerRadius(radius))
    for ((width, weight) in listOf(10.dp to .14f, 5.dp to .30f, 1.5.dp to 1f)) {
        val w = width.toPx(); val inset = w / 2
        drawRoundRect(Brand.Cyan.copy(alpha = (alpha * weight).coerceIn(0f, 1f)), Offset(inset, inset),
            Size(size.width - w, size.height - w), CornerRadius((radius - inset).coerceAtLeast(0f)), style = Stroke(w))
    }
}
