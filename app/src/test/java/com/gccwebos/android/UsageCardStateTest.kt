package com.gccwebos.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class UsageCardStateTest {
    @Test fun lowStartsBelowTenPercentLeftAndExhaustedMeansNothingLeft() {
        assertEquals(UsageCardState.Normal, usageCardState(.5f))
        assertEquals(UsageCardState.Normal, usageCardState(.90f)) // exactly 10% left is not yet low
        assertEquals(UsageCardState.Low, usageCardState(.91f))
        assertEquals(UsageCardState.Low, usageCardState(.999f))
        assertEquals(UsageCardState.Exhausted, usageCardState(1f))
        assertEquals(UsageCardState.Exhausted, usageCardState(1.4f)) // clamped
    }

    @Test fun unknownProgressNeverAnimatesAsAWarning() {
        assertEquals(UsageCardState.Normal, usageCardState(null))
        assertEquals(UsageCardState.Normal, usageCardState(Float.NaN))
        assertEquals(UsageCardState.Normal, usageCardState(Float.POSITIVE_INFINITY))
    }

    @Test fun thePulseLoopsOnlyForLowTrafficWhileShownResumedAndMotionAllowed() {
        assertTrue(usagePulseActive(UsageCardState.Low, shownAndResumed = true, motionEnabled = true))
        assertFalse(usagePulseActive(UsageCardState.Low, shownAndResumed = false, motionEnabled = true)) // off-screen or backgrounded
        assertFalse(usagePulseActive(UsageCardState.Low, shownAndResumed = true, motionEnabled = false)) // reduce motion
        assertFalse(usagePulseActive(UsageCardState.Normal, true, true))
        assertFalse(usagePulseActive(UsageCardState.Exhausted, true, true)) // exhausted is static after its one shake
    }

    @Test fun theConnectedRimLoopsOnlyWhileConnectedResumedAndMotionAllowed() {
        assertTrue(usageBreathingActive(connected = true, resumed = true, motionEnabled = true))
        assertFalse(usageBreathingActive(connected = true, resumed = false, motionEnabled = true))
        assertFalse(usageBreathingActive(connected = true, resumed = true, motionEnabled = false))
        assertFalse(usageBreathingActive(connected = false, resumed = true, motionEnabled = true)) // not connected: static dim rim
    }

    @Test fun glowAlphasStayCalmAndTheStaticFallbacksSitInsideTheirRanges() {
        assertTrue(UsageRimAlphaIdle < UsageRimAlphaMin && UsageRimAlphaMin < UsageRimAlphaStatic && UsageRimAlphaStatic < UsageRimAlphaMax)
        assertTrue(UsageRimAlphaMax <= .40f)
        assertTrue(UsagePulseAlphaMin < UsagePulseAlphaStatic && UsagePulseAlphaStatic < UsagePulseAlphaMax)
        assertEquals(UsageRimAlphaMin, usageLerp(UsageRimAlphaMin, UsageRimAlphaMax, -1f), 0f)
        assertEquals(UsageRimAlphaMax, usageLerp(UsageRimAlphaMin, UsageRimAlphaMax, 2f), 0f)
    }

    private fun luminance(rgb: Int): Double {
        fun channel(v: Int): Double { val c = v / 255.0; return if (c <= .03928) c / 12.92 else ((c + .055) / 1.055).pow(2.4) }
        return .2126 * channel(rgb shr 16 and 255) + .7152 * channel(rgb shr 8 and 255) + .0722 * channel(rgb and 255)
    }
    private fun contrast(a: Int, b: Int): Double {
        val l1 = luminance(a); val l2 = luminance(b)
        return (maxOf(l1, l2) + .05) / (minOf(l1, l2) + .05)
    }
    private fun blend(over: Int, under: Int, alpha: Double): Int {
        fun ch(shift: Int) = Math.round((over shr shift and 255) * alpha + (under shr shift and 255) * (1 - alpha)).toInt()
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    @Test fun amberNumberStaysReadableEvenAtTheBrightestPulseOfItsOwnGlow() {
        val amber = 0xFF9F43; val card = 0x0B1426
        // Worst case: the number's own glow (alpha 0.36 * 0.6) behind it at the pulse peak.
        val worst = blend(amber, card, UsagePulseAlphaMax * .6)
        assertTrue("amber on card ${contrast(amber, card)}", contrast(amber, card) >= 4.5)
        assertTrue("amber on glow ${contrast(amber, worst)}", contrast(amber, worst) >= 4.5)
        // The exhausted bar is graphical (3:1 is the bar for non-text).
        assertTrue(contrast(0xFF5D5D, card) >= 3.0)
    }
}
