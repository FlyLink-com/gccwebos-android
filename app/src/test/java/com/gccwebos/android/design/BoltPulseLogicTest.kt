package com.gccwebos.android.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoltPulseLogicTest {
    @Test fun pulseRunsOnlyWhenBusyWithMotionAndResumed() {
        assertTrue(boltPulseActive(busy = true, motionEnabled = true, resumed = true))
        assertFalse(boltPulseActive(busy = false, motionEnabled = true, resumed = true))
        assertFalse(boltPulseActive(busy = true, motionEnabled = false, resumed = true))
        assertFalse(boltPulseActive(busy = true, motionEnabled = true, resumed = false))
    }

    @Test fun reducedMotionShowsStaticBrightBolt() {
        // Reduce-motion (animator scale 0) disables the pulse and the busy icon is fully bright, not stuck dim.
        assertFalse(boltPulseActive(busy = true, motionEnabled = uiMotionEnabled(0f), resumed = true))
        assertEquals(1f, boltStaticAlpha(busy = true), 0f)
        assertEquals(1f, boltStaticAlpha(busy = false), 0f)
    }

    @Test fun pulseStartsDimPeaksBrightAndReturnsToDim() {
        assertEquals(BoltPulseSpec.DimAlpha, boltPulseAlphaAt(0), 1e-6f)
        assertEquals(BoltPulseSpec.BrightAlpha, boltPulseAlphaAt(BoltPulseSpec.RiseMillis), 1e-6f)
        assertEquals(BoltPulseSpec.DimAlpha, boltPulseAlphaAt(BoltPulseSpec.CycleMillis), 1e-6f)
    }

    @Test fun riseIsMonotonicallyBrighter() {
        var last = -1f
        for (t in 0..BoltPulseSpec.RiseMillis step 50) {
            val a = boltPulseAlphaAt(t)
            assertTrue("alpha must not drop while rising at $t", a >= last)
            last = a
        }
    }

    @Test fun dimEndStaysVisibleAndCycleIsShort() {
        assertTrue(BoltPulseSpec.DimAlpha in 0.2f..0.5f)
        assertTrue(BoltPulseSpec.CycleMillis <= 1200)
        assertEquals(BoltPulseSpec.DimAlpha, boltPulseAlphaAt(BoltPulseSpec.CycleMillis * 3), 1e-6f)
    }
}
