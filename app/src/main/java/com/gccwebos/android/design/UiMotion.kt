package com.gccwebos.android.design

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.launch

/*
 * Shared motion building blocks. Every per-frame value is read inside graphicsLayer/draw lambdas, so an
 * animation frame re-draws but never recomposes. All of them honour the system "remove animations"
 * switch (ANIMATOR_DURATION_SCALE == 0): finite transitions become instant and no infinite animation runs.
 */

/** Re-read on each lifecycle transition, so toggling "remove animations" applies when the user returns. */
@Composable
internal fun rememberUiMotionEnabled(): Boolean {
    val context = LocalContext.current
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val override = LocalAnimatorScaleOverride.current
    return remember(state, context, override) {
        uiMotionEnabled(override ?: Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))
    }
}

/**
 * Test seam only (null in production): lets an instrumented test render the "remove animations" case on a device
 * where the system animation scale cannot be changed from adb. It never changes what the real setting means.
 */
internal val LocalAnimatorScaleOverride = androidx.compose.runtime.staticCompositionLocalOf<Float?> { null }

/** Light tick for user-initiated taps only (tab switch, primary CTA). Call it from an onClick, never from an effect. */
@Composable
internal fun rememberTapHaptic(): (UiHaptic) -> Unit {
    val haptic = LocalHapticFeedback.current
    return remember(haptic) {
        { kind -> haptic.performHapticFeedback(if (kind == UiHaptic.Tab) HapticFeedbackType.SegmentTick else HapticFeedbackType.ContextClick) }
    }
}

internal enum class UiHaptic { Tab, Primary }

// ---- 1. Press feedback --------------------------------------------------------------------------------

/** Pressed scale/alpha for one surface. Pass [interactionSource] to the clickable/Button it decorates. */
@Stable
internal class PressFeedback(val interactionSource: MutableInteractionSource) {
    internal val scale = Animatable(1f)
    internal val alpha = Animatable(1f)
}

@Composable
internal fun rememberPressFeedback(enabled: Boolean = true,
                                  interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }): PressFeedback {
    val feedback = remember(interactionSource) { PressFeedback(interactionSource) }
    val motion = rememberUiMotionEnabled()
    val isEnabled by rememberUpdatedState(enabled)
    LaunchedEffect(feedback, motion) {
        val presses = mutableListOf<PressInteraction.Press>()
        interactionSource.interactions.collect { interaction: Interaction ->
            when (interaction) {
                is PressInteraction.Press -> presses += interaction
                is PressInteraction.Release -> presses -= interaction.press
                is PressInteraction.Cancel -> presses -= interaction.press
                else -> return@collect
            }
            val pressed = presses.isNotEmpty()
            val (scale, alpha) = pressTargets(pressed, isEnabled, motion)
            launch {
                if (!motion) feedback.scale.snapTo(scale)
                else if (pressed) feedback.scale.animateTo(scale, tween(UiMotionSpec.PressDownMillis))
                else feedback.scale.animateTo(scale, spring(dampingRatio = .55f, stiffness = Spring.StiffnessMedium))
            }
            launch {
                if (!motion) feedback.alpha.snapTo(alpha)
                else feedback.alpha.animateTo(alpha, tween(if (pressed) UiMotionSpec.PressDownMillis else 160))
            }
        }
    }
    return feedback
}

/** Reads the animated values in the layer block: pressing re-draws the layer only. */
internal fun Modifier.pressFeedback(feedback: PressFeedback): Modifier = graphicsLayer {
    val s = feedback.scale.value
    scaleX = s; scaleY = s
    alpha = feedback.alpha.value
}

// ---- 2. Sliding tab indicator, tab row and tab content ---------------------------------------------------

/** Animated (fractional) indicator position; -1 hides the indicator. */
@Stable
internal class SlidingIndicator internal constructor(initial: Int) {
    internal val position = Animatable(initial.toFloat())
    internal var target: Int = initial
}

@Composable
internal fun rememberSlidingIndicator(selectedIndex: Int, count: Int): SlidingIndicator {
    val index = indicatorIndex(selectedIndex, count)
    val indicator = remember { SlidingIndicator(index) }
    val motion = rememberUiMotionEnabled()
    LaunchedEffect(index, motion) {
        val from = indicator.target
        indicator.target = index
        // Appearing from "nothing selected" (or with motion off) snaps; a real change slides.
        if (index < 0 || from < 0 || !motion) indicator.position.snapTo(index.toFloat())
        else indicator.position.animateTo(index.toFloat(), tween(UiMotionSpec.TabMillis, easing = FastOutSlowInEasing))
    }
    return indicator
}

/**
 * Draws one rounded block behind equally weighted cells of a row with [count] items. [inset] is the
 * row's own horizontal/vertical padding, so the block lines up with each cell's bounds.
 */
internal fun Modifier.slidingIndicator(indicator: SlidingIndicator, count: Int, color: Color, corner: Dp,
                                       horizontalInset: Dp = 0.dp, verticalInset: Dp = 0.dp,
                                       edge: Color? = null): Modifier = drawBehind {
    if (count <= 0 || indicator.target < 0) return@drawBehind
    val h = horizontalInset.toPx(); val v = verticalInset.toPx()
    val cell = (size.width - 2 * h) / count
    if (cell <= 0f) return@drawBehind
    val x = h + indicator.position.value.coerceIn(0f, (count - 1).toFloat()) * cell
    val r = CornerRadius(corner.toPx())
    val blockSize = Size(cell, size.height - 2 * v)
    drawRoundRect(color, Offset(x, v), blockSize, r)
    edge?.let { drawRoundRect(it, Offset(x, v), blockSize, r, style = Stroke(1.dp.toPx())) }
}

internal data class SlidingTab(val title: String, val tag: String)

/**
 * Capsule segmented row: equal cells, one sliding highlight. Each cell keeps Role.Tab, selected/disabled
 * semantics, its test tag and a ≥48 dp touch target. [onSelect] runs for every user tap (as the former chips did);
 * the haptic fires only when the tab actually changes.
 */
@Composable
internal fun SlidingTabRow(tabs: List<SlidingTab>, selectedIndex: Int, enabled: Boolean, onSelect: (Int) -> Unit,
                           modifier: Modifier = Modifier, containerColor: Color = SlidingTabGlass) {
    val indicator = rememberSlidingIndicator(selectedIndex, tabs.size)
    val haptic = rememberTapHaptic()
    val shape = CircleShape
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape)
        .background(containerColor)
        .slidingIndicator(indicator, tabs.size, SlidingTabSelected, corner = 24.dp,
            horizontalInset = 2.dp, verticalInset = 2.dp, edge = SlidingTabSelectedEdge)
        .selectableGroup().padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
        tabs.forEachIndexed { index, tab ->
            val selected = index == selectedIndex
            val press = rememberPressFeedback(enabled)
            Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(shape)
                .pressFeedback(press)
                .selectable(selected = selected, enabled = enabled, role = Role.Tab,
                    interactionSource = press.interactionSource, indication = androidx.compose.material3.ripple(),
                    onClick = {
                        if (tabTapHaptic(selectedIndex, index, userInitiated = true)) haptic(UiHaptic.Tab)
                        onSelect(index) // callers keep their former re-tap behaviour
                    })
                .testTag(tab.tag).padding(horizontal = 6.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                Text(tab.title, textAlign = TextAlign.Center, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (!enabled) Brand.Muted else Brand.Text)
            }
        }
    }
}

/** Default capsule background (plans page): a faint glass veil over the backdrop. */
internal val SlidingTabGlass = Color.White.copy(alpha = .06f)
/** Darker capsule background for rows that must read over the busy city backdrop (the nodes page source switcher). */
internal val SlidingTabDarkSurface = Brand.Background
/** Selected-tab fill; with the cyan edge it stays clearly visible on both the glass and the dark surface. */
internal val SlidingTabSelected = Color(0xFF0B5368)
internal val SlidingTabSelectedEdge = Brand.Cyan.copy(alpha = .85f)

/**
 * Sibling content: slides horizontally (direction from [indexOf]) and cross-fades; changes with no index
 * on either side (e.g. loading -> data) only fade. Keyed by [targetState], so each tab keeps its own slot
 * and state hoisted above this call is untouched (no reload).
 */
@Composable
internal fun <T> TabContent(targetState: T, indexOf: (T) -> Int, modifier: Modifier = Modifier,
                            content: @Composable (T) -> Unit) {
    val motion = rememberUiMotionEnabled()
    AnimatedContent(targetState, modifier, label = "tab-content", transitionSpec = {
        val direction = tabSlideDirection(indexOf(initialState), indexOf(targetState))
        val d = uiMotionMillis(motion, UiMotionSpec.TabMillis)
        val f = uiMotionMillis(motion, UiMotionSpec.FadeInMillis)
        (if (direction == 0) fadeIn(tween(f)) togetherWith fadeOut(tween(f / 2))
        else (slideInHorizontally(tween(d, easing = FastOutSlowInEasing)) { w -> direction * w / 5 } + fadeIn(tween(d))) togetherWith
            (slideOutHorizontally(tween(d, easing = FastOutSlowInEasing)) { w -> -direction * w / 5 } + fadeOut(tween(d / 2))))
            .using(SizeTransform(clip = false))
    }) { state -> content(state) }
}

/**
 * Entrance for content that replaces itself without a second composition (top-level pages): fades in and
 * drifts [direction] * 24 dp into place. The outgoing page is not kept alive, so its effects never overlap.
 */
@Stable
internal class EnterMotion internal constructor(start: Boolean, internal val direction: Int) {
    internal val progress = Animatable(if (start) 0f else 1f)
}

@Composable
internal fun rememberEnterMotion(direction: Int = 0): EnterMotion {
    val motion = rememberUiMotionEnabled()
    // Direction is fixed when the content arrives; later recompositions do not change it.
    val enter = remember { EnterMotion(motion, direction) }
    LaunchedEffect(enter) {
        if (motion) enter.progress.animateTo(1f, tween(UiMotionSpec.FadeInMillis, easing = FastOutSlowInEasing))
        else enter.progress.snapTo(1f)
    }
    return enter
}

internal fun Modifier.enterMotion(enter: EnterMotion): Modifier = graphicsLayer {
    val p = enter.progress.value
    alpha = p
    translationX = (1f - p) * enter.direction * 24.dp.toPx()
}

// ---- 4. Skeleton / shimmer --------------------------------------------------------------------------------

/** Shimmer sweep progress, or null when motion is off or the screen is not resumed (then nothing animates). */
@Composable
internal fun rememberShimmerProgress(): State<Float>? {
    val motion = rememberUiMotionEnabled()
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    if (!shimmerActive(motion, state.isAtLeast(Lifecycle.State.RESUMED))) return null
    val transition = rememberInfiniteTransition(label = "skeleton-shimmer")
    return transition.animateFloat(0f, 1f, infiniteRepeatable(tween(UiMotionSpec.ShimmerMillis, easing = LinearEasing)),
        label = "skeleton-sweep")
}

private val SkeletonBase = Color.White.copy(alpha = .07f)
private val SkeletonHighlight = Color.White.copy(alpha = .16f)

/** A placeholder block; the highlight is read in the draw phase. */
internal fun Modifier.skeleton(shimmer: State<Float>?, shape: Shape = RoundedCornerShape(12.dp)): Modifier =
    clip(shape).drawWithContent {
        drawRect(SkeletonBase)
        val p = shimmer?.value ?: return@drawWithContent
        val band = size.width * .6f
        val x = -band + p * (size.width + 2 * band)
        drawRect(Brush.linearGradient(listOf(Color.Transparent, SkeletonHighlight, Color.Transparent),
            start = Offset(x - band / 2, 0f), end = Offset(x + band / 2, size.height)))
    }

/** [count] skeleton cards for a list that is loading; hidden from accessibility as one "loading" node. */
@Composable
internal fun SkeletonCards(count: Int, cardHeight: Dp, modifier: Modifier = Modifier, spacing: Dp = 12.dp,
                           shape: Shape = RoundedCornerShape(20.dp), lines: Int = 2) {
    val shimmer = rememberShimmerProgress()
    Column(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "正在加载" }, verticalArrangement = Arrangement.spacedBy(spacing)) {
        repeat(count) {
            Box(Modifier.fillMaxWidth().height(cardHeight).skeleton(shimmer, shape)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(lines) { line ->
                        Box(Modifier.fillMaxWidth(if (line == 0) .45f else .7f).height(if (line == 0) 18.dp else 12.dp)
                            .background(Color.White.copy(alpha = .06f), RoundedCornerShape(6.dp)))
                    }
                }
            }
        }
    }
}

// ---- 3. Bottom sheet --------------------------------------------------------------------------------------

/**
 * Half-height sheet for light, read-only or navigation-only flows. Swipe down, scrim tap and back all
 * dismiss. [content] receives `close`, which animates the sheet away before [onDismissRequest].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GccBottomSheet(onDismissRequest: () -> Unit, modifier: Modifier = Modifier, title: String? = null,
                            content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismissRequest)
    val close: () -> Unit = remember(sheetState, scope) {
        { scope.launch { sheetState.hide() }.invokeOnCompletion { dismiss() } }
    }
    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState, modifier = modifier,
        containerColor = Brand.SurfaceRaised, contentColor = Brand.Text,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
            content(close)
        }
    }
}
