package com.gccwebos.android.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.gccwebos.android.usageBreathingActive
import com.gccwebos.android.usageMotionEnabled
import org.junit.Assert.*
import org.junit.Test

class OfficialWebsiteGradientTest {
    private fun ratio(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance()); val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }
    private val stops get() = OfficialWebsiteGradientColors

    @Test fun theCardGradientIsOpaqueBlueToPurpleBuiltFromTheBrandActionTokens() {
        assertEquals(3, stops.size)
        stops.forEach { assertEquals("opaque: the glass behind never shows through", 1f, it.alpha, 0f) }
        assertTrue("blue at the start", stops.first().blue > stops.first().red)
        assertTrue("purple at the end", stops.last().red > stops.first().red && stops.last().blue > stops.last().green)
        // Lighter than the nodes switcher's darker variant, so it reads as neon.
        stops.forEachIndexed { i, s -> assertTrue("stop $i", s.luminance() > SlidingTabGradientColors[i].luminance()) }
    }

    @Test fun titleAndActionKeepFourPointFiveToOneOnEveryStopEvenUnderTheReflectionAndTheStrongestGlowWash() {
        val reflection = Color.White.copy(alpha = .12f)   // GlassSurface's top highlight
        val wash = Brand.Cyan.copy(alpha = BreathingWashAlphaMax * BreathingAlphaMax)
        stops.forEachIndexed { i, stop ->
            for ((name, under) in listOf("plain" to stop, "reflection" to reflection.compositeOver(stop),
                "glow" to wash.compositeOver(stop), "both" to reflection.compositeOver(wash.compositeOver(stop)))) {
                assertTrue("text on $name stop $i ${ratio(Brand.Text, under)}", ratio(Brand.Text, under) >= 4.5f)
            }
        }
    }

    @Test fun theBreathingRimIsCalmAndItsStaticFallbackSitsInsideTheRange() {
        assertEquals(BreathingAlphaMin, breathingAlpha(0f), 0f)
        assertEquals(BreathingAlphaMax, breathingAlpha(1f), 0f)
        assertEquals(BreathingAlphaMax, breathingAlpha(5f), 0f)
        assertTrue(BreathingAlphaStatic in BreathingAlphaMin..BreathingAlphaMax)
        assertTrue("slow", BreathingMillis >= 2_500)
    }

    @Test fun theLoopRunsOnlyWhileShownResumedAndMotionIsOn() {
        // Same gate as the traffic card's rim (rememberUsageMotion feeds these three inputs).
        assertTrue(usageBreathingActive(connected = true, resumed = true, motionEnabled = usageMotionEnabled(1f)))
        assertFalse("paused or off screen", usageBreathingActive(connected = true, resumed = false, motionEnabled = true))
        assertFalse("reduce motion (animator scale 0)", usageBreathingActive(connected = true, resumed = true, motionEnabled = usageMotionEnabled(0f)))
    }
}
