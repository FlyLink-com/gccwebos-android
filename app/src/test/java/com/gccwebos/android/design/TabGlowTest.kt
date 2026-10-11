package com.gccwebos.android.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.gccwebos.android.usageMotionEnabled
import org.junit.Assert.*
import org.junit.Test

class TabGlowTest {
    private fun ratio(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance()); val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test fun onlyUnselectedInRangeRequestedTabsGlow() {
        // 月卡 / 年卡 / 流量包 with 月卡 selected: 年卡 and 流量包 glow.
        assertEquals(setOf(1, 2), tabGlowIndices(setOf(1, 2), selectedIndex = 0, count = 3))
        // Selecting one stops its glow; the other keeps going.
        assertEquals(setOf(2), tabGlowIndices(setOf(1, 2), selectedIndex = 1, count = 3))
        assertEquals(setOf(1), tabGlowIndices(setOf(1, 2), selectedIndex = 2, count = 3))
        // No selection (-1) and out-of-range indices are tolerated.
        assertEquals(setOf(1, 2), tabGlowIndices(setOf(1, 2, 7, -1), selectedIndex = -1, count = 3))
        assertTrue(tabGlowIndices(emptySet(), 0, 3).isEmpty())
    }

    @Test fun theBreathingRangeIsCalmAndTheStaticFallbackSitsInsideIt() {
        assertEquals(TabGlowAlphaMin, tabGlowLerp(0f), 0f)
        assertEquals(TabGlowAlphaMax, tabGlowLerp(1f), 0f)
        assertEquals(TabGlowAlphaMax, tabGlowLerp(9f), 0f)
        assertTrue(TabGlowAlphaStatic in TabGlowAlphaMin..TabGlowAlphaMax)
        assertTrue(TabGlowMillis >= 2_000)
    }

    @Test fun reduceMotionDisablesTheBreathingGate() {
        assertFalse(usageMotionEnabled(0f))
        assertTrue(usageMotionEnabled(1f))
    }

    @Test fun theWorstCaseGlowWashKeepsLabelContrastOnEveryGradientStop() {
        val wash = Brand.Cyan.copy(alpha = TabGlowFillAlphaMax * TabGlowAlphaMax)
        SlidingTabGradientColors.forEachIndexed { i, stop ->
            val under = wash.compositeOver(stop)
            assertTrue("text on glowing stop $i ${ratio(Brand.Text, under)}", ratio(Brand.Text, under) >= 4.5f)
            assertTrue("muted on glowing stop $i ${ratio(Brand.Muted, under)}", ratio(Brand.Muted, under) >= 4.5f)
        }
    }
}
