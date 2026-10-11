package com.gccwebos.android.design

/**
 * Pure decisions behind the shared UI motion building blocks ([UiMotion.kt]). Nothing here touches
 * Compose, so the rules (direction, durations, "remove animations") are unit tested on the JVM.
 */
internal object UiMotionSpec {
    /** Press-down response: short and never longer than 100 ms, so a tap always feels acknowledged. */
    const val PressDownMillis = 90
    const val PressedScale = 0.97f
    const val PressedAlpha = 0.85f
    /** Sibling tab indicator and content slide. */
    const val TabMillis = 260
    /** Content arriving after a load (skeleton -> data) or a page arriving after a tab tap. */
    const val FadeInMillis = 220
    /** One shimmer sweep across a skeleton placeholder. */
    const val ShimmerMillis = 1300
}

/** System "remove animations" sets ANIMATOR_DURATION_SCALE to 0; non-positive or invalid values disable motion. */
internal fun uiMotionEnabled(animatorDurationScale: Float): Boolean =
    animatorDurationScale.isFinite() && animatorDurationScale > 0f

/** +1: the new tab is to the right (content enters from the end); -1: to the left; 0: same/unknown (fade only). */
internal fun tabSlideDirection(fromIndex: Int, toIndex: Int): Int = when {
    fromIndex < 0 || toIndex < 0 || fromIndex == toIndex -> 0
    toIndex > fromIndex -> 1
    else -> -1
}

/** Scale/alpha targets for a pressed surface. Without motion the press is shown by alpha only (no scaling). */
internal fun pressTargets(pressed: Boolean, enabled: Boolean, motionEnabled: Boolean): Pair<Float, Float> = when {
    !pressed || !enabled -> 1f to 1f
    !motionEnabled -> 1f to UiMotionSpec.PressedAlpha
    else -> UiMotionSpec.PressedScale to UiMotionSpec.PressedAlpha
}

/** The only infinite animation here (shimmer) runs while the screen is resumed and motion is allowed. */
internal fun shimmerActive(motionEnabled: Boolean, resumed: Boolean): Boolean = motionEnabled && resumed

/** Duration for a finite transition; zero when motion is off so state is shown immediately. */
internal fun uiMotionMillis(motionEnabled: Boolean, millis: Int): Int = if (motionEnabled) millis.coerceAtLeast(0) else 0

/**
 * Index of the selected item clamped to the row, or -1 when nothing (or something outside the row)
 * is selected. The sliding indicator is hidden at -1 rather than drawn over the wrong tab.
 */
internal fun indicatorIndex(selectedIndex: Int, count: Int): Int = if (selectedIndex in 0 until count) selectedIndex else -1

/** Haptics are reserved for user-initiated changes; re-tapping the current tab is not a change. */
internal fun tabTapHaptic(fromIndex: Int, toIndex: Int, userInitiated: Boolean): Boolean =
    userInitiated && toIndex >= 0 && fromIndex != toIndex

/**
 * Busy "lightning" icon (node-page speed test): it never rotates. It fades from dim to bright and back.
 * With motion off (or a screen that is not resumed) the busy icon is shown static and fully bright.
 */
internal object BoltPulseSpec {
    /** Alpha at the dim end of the pulse; still clearly visible on the dark backdrop. */
    const val DimAlpha = 0.28f
    const val BrightAlpha = 1f
    /** Dim -> bright ramp, then a short fall back to dim; the whole cycle is under 1.2 s. */
    const val RiseMillis = 700
    const val FallMillis = 300
    const val CycleMillis = RiseMillis + FallMillis
}

/** The pulse (infinite animation) only runs while busy, motion is allowed and the screen is resumed. */
internal fun boltPulseActive(busy: Boolean, motionEnabled: Boolean, resumed: Boolean): Boolean =
    busy && motionEnabled && resumed

/** Alpha of the icon. Static bright when busy without motion; idle icons are never faded by the pulse. */
internal fun boltStaticAlpha(busy: Boolean): Float = if (busy) BoltPulseSpec.BrightAlpha else 1f

/** Alpha at [timeMillis] into the (repeating) cycle: linear-ish rise to bright, then a fall back to dim. */
internal fun boltPulseAlphaAt(timeMillis: Int): Float {
    val t = ((timeMillis % BoltPulseSpec.CycleMillis) + BoltPulseSpec.CycleMillis) % BoltPulseSpec.CycleMillis
    val span = BoltPulseSpec.BrightAlpha - BoltPulseSpec.DimAlpha
    return if (t <= BoltPulseSpec.RiseMillis) BoltPulseSpec.DimAlpha + span * (t / BoltPulseSpec.RiseMillis.toFloat())
    else BoltPulseSpec.BrightAlpha - span * ((t - BoltPulseSpec.RiseMillis) / BoltPulseSpec.FallMillis.toFloat())
}
