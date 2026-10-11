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

    @Test fun theNodeSwitcherSurfaceIsTheDarkTokenAndDarkerThanTheGlassDefault() {
        assertEquals(Brand.Background, SlidingTabDarkSurface)
        assertEquals(1f, SlidingTabDarkSurface.alpha, 0f)
        // The plans-page default is a 6% white veil over the (lighter) backdrop; compare against the same backdrop.
        val backdrop = Brand.SurfaceRaised
        assertTrue(SlidingTabDarkSurface.luminance() < SlidingTabGlass.compositeOver(backdrop).luminance())
    }

    @Test fun tabTextKeepsAtLeast4Point5ToOneOnTheDarkBarAndOnTheSelectedFill() {
        assertTrue("text on dark bar", ratio(Brand.Text, SlidingTabDarkSurface) >= 4.5f)
        assertTrue("selected text on selected fill", ratio(Brand.Text, SlidingTabSelected) >= 4.5f)
        assertTrue("disabled (muted) text on dark bar", ratio(Brand.Muted, SlidingTabDarkSurface) >= 4.5f)
    }

    @Test fun theSelectedHighlightIsClearlyVisibleAgainstTheDarkBar() {
        // Non-text indicator: the cyan edge needs 3:1; the fill itself must also separate from the bar.
        val edge = SlidingTabSelectedEdge.compositeOver(SlidingTabDarkSurface)
        assertTrue("edge vs bar ${ratio(edge, SlidingTabDarkSurface)}", ratio(edge, SlidingTabDarkSurface) >= 3f)
        assertTrue("fill vs bar ${ratio(SlidingTabSelected, SlidingTabDarkSurface)}", ratio(SlidingTabSelected, SlidingTabDarkSurface) >= 1.8f)
    }
}
