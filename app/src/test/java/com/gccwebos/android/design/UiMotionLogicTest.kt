package com.gccwebos.android.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiMotionLogicTest {
    @Test fun removeAnimationsDisablesMotion() {
        assertFalse(uiMotionEnabled(0f))
        assertFalse(uiMotionEnabled(-1f))
        assertFalse(uiMotionEnabled(Float.NaN))
        assertFalse(uiMotionEnabled(Float.POSITIVE_INFINITY))
        assertTrue(uiMotionEnabled(0.5f))
        assertTrue(uiMotionEnabled(1f))
    }

    @Test fun slideDirectionFollowsTabIndex() {
        assertEquals(1, tabSlideDirection(0, 2))
        assertEquals(-1, tabSlideDirection(3, 1))
        assertEquals(0, tabSlideDirection(1, 1))
        // Loading/unknown slots never slide, they only fade.
        assertEquals(0, tabSlideDirection(-1, 2))
        assertEquals(0, tabSlideDirection(2, -1))
    }

    @Test fun pressTargetsScaleOnlyWithMotion() {
        assertEquals(1f to 1f, pressTargets(pressed = false, enabled = true, motionEnabled = true))
        assertEquals(UiMotionSpec.PressedScale to UiMotionSpec.PressedAlpha, pressTargets(true, true, true))
        assertEquals(1f to UiMotionSpec.PressedAlpha, pressTargets(true, true, motionEnabled = false))
        assertEquals(1f to 1f, pressTargets(true, enabled = false, motionEnabled = true))
        assertEquals(0.97f, UiMotionSpec.PressedScale)
        assertTrue(UiMotionSpec.PressDownMillis <= 100)
        assertTrue(UiMotionSpec.TabMillis in 220..300)
    }

    @Test fun shimmerRunsOnlyWhenResumedWithMotion() {
        assertTrue(shimmerActive(motionEnabled = true, resumed = true))
        assertFalse(shimmerActive(motionEnabled = false, resumed = true))
        assertFalse(shimmerActive(motionEnabled = true, resumed = false))
    }

    @Test fun finiteDurationsCollapseWithoutMotion() {
        assertEquals(260, uiMotionMillis(true, 260))
        assertEquals(0, uiMotionMillis(false, 260))
        assertEquals(0, uiMotionMillis(true, -5))
    }

    @Test fun indicatorHidesForOutOfRangeSelection() {
        assertEquals(2, indicatorIndex(2, 4))
        assertEquals(-1, indicatorIndex(4, 4))
        assertEquals(-1, indicatorIndex(-1, 4))
        assertEquals(-1, indicatorIndex(0, 0))
    }

    @Test fun hapticOnlyForUserTapOnAnotherTab() {
        assertTrue(tabTapHaptic(0, 1, userInitiated = true))
        assertFalse(tabTapHaptic(1, 1, userInitiated = true))
        assertFalse(tabTapHaptic(0, 1, userInitiated = false))
        assertFalse(tabTapHaptic(0, -1, userInitiated = true))
    }
}
