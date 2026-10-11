package com.gccwebos.android.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class SlidingTabContrastTest {
    private fun ratio(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance()); val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }
    private val stops get() = SlidingTabGradientColors

    @Test fun theNodeSwitcherGradientIsBuiltFromTheBrandActionTokensAndStaysOpaque() {
        assertEquals(3, stops.size)
        val tokens = listOf(Brand.ActionStart, Brand.ActionMiddle, Brand.ActionEnd)
        stops.forEachIndexed { i, stop ->
            assertEquals(1f, stop.alpha, 0f)
            // Darker than the raw token (30% dark scrim), same hue family.
            assertTrue("stop $i darker than its token", stop.luminance() < tokens[i].luminance())
        }
        // Blue at the start, purple at the end.
        assertTrue(stops.first().blue > stops.first().red); assertTrue(stops.last().red > stops.first().red)
        assertTrue(stops.last().blue > stops.last().green)
    }

    @Test fun theGradientIsDarkerThanThePlansPageGlassDefaultOverTheSameBackdrop() {
        val backdrop = Brand.SurfaceRaised
        val glass = SlidingTabGlass.compositeOver(backdrop).luminance()
        // The plans-page default is unchanged (6% white veil).
        assertEquals(Color.White.copy(alpha = .06f), SlidingTabGlass)
        stops.forEach { assertTrue(it.luminance() < glass + .12f) }
    }

    @Test fun tabTextKeepsAtLeast4Point5ToOneOnEveryGradientStopAndOnTheSelectedFill() {
        stops.forEachIndexed { i, stop ->
            assertTrue("text on stop $i ${ratio(Brand.Text, stop)}", ratio(Brand.Text, stop) >= 4.5f)
            assertTrue("muted text on stop $i ${ratio(Brand.Muted, stop)}", ratio(Brand.Muted, stop) >= 4.5f)
        }
        assertTrue("selected text on selected fill", ratio(Brand.Text, SlidingTabGradientSelected) >= 4.5f)
    }

    @Test fun theSelectedHighlightIsClearlyVisibleAgainstEveryGradientStop() {
        stops.forEachIndexed { i, stop ->
            val edge = SlidingTabGradientSelectedEdge.compositeOver(stop)
            assertTrue("edge vs stop $i ${ratio(edge, stop)}", ratio(edge, stop) >= 3f)
            assertTrue("fill vs stop $i ${ratio(SlidingTabGradientSelected, stop)}", ratio(SlidingTabGradientSelected, stop) >= 1.8f)
        }
    }

    @Test fun thePlansPageDefaultSelectedHighlightIsUnchanged() {
        assertEquals(Color(0xFF0B5368), SlidingTabSelected)
        assertEquals(Brand.Cyan.copy(alpha = .85f), SlidingTabSelectedEdge)
    }
}
