package com.gccwebos.android

import com.gccwebos.android.design.SubscriptionUsagePresentation
import com.gccwebos.android.design.accountUsagePresentation
import com.gccwebos.android.portal.Resource
import com.gccwebos.android.portal.Usage
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionUsageMotionTest {
    private val now = Instant.parse("2026-10-09T12:00:00Z")
    private val farExpiry = now.plus(Duration.ofDays(30))

    @Test fun remainingTrafficThresholdsMapToSeverity() {
        assertEquals(UsageSeverity.Normal, usageSeverity(.06f, farExpiry, false, now))
        assertEquals(UsageSeverity.Normal, usageSeverity(.80f, farExpiry, false, now)) // exactly 20% left
        assertEquals(UsageSeverity.Warning, usageSeverity(.81f, farExpiry, false, now))
        assertEquals(UsageSeverity.Warning, usageSeverity(.90f, farExpiry, false, now)) // exactly 10% left
        assertEquals(UsageSeverity.Critical, usageSeverity(.91f, farExpiry, false, now))
        assertEquals(UsageSeverity.Critical, usageSeverity(1f, farExpiry, false, now))
        assertEquals(UsageSeverity.Critical, usageSeverity(1.4f, farExpiry, false, now)) // clamped, never > 100%
    }

    @Test fun unknownUsageNeverInventsEmphasis() {
        assertEquals(UsageSeverity.Normal, usageSeverity(null, null, false, now))
        assertEquals(UsageSeverity.Normal, usageSeverity(Float.NaN, farExpiry, false, now))
        assertEquals(UsageSeverity.Normal, usageSeverity(null, farExpiry, false, null))
    }

    @Test fun expiryWithinThreeDaysWarnsAndExpiredIsCritical() {
        assertEquals(UsageSeverity.Normal, usageSeverity(.1f, now.plus(Duration.ofDays(3)).plusSeconds(1), false, now))
        assertEquals(UsageSeverity.Warning, usageSeverity(.1f, now.plus(Duration.ofDays(3)), false, now))
        assertEquals(UsageSeverity.Warning, usageSeverity(null, now.plusSeconds(60), false, now))
        assertEquals(UsageSeverity.Critical, usageSeverity(.1f, now, false, now))
        assertEquals(UsageSeverity.Critical, usageSeverity(.1f, farExpiry, true, now))
        // Without a clock only the explicit expired flag applies.
        assertEquals(UsageSeverity.Normal, usageSeverity(.1f, now.plusSeconds(60), false, null))
        // Low traffic outranks an expiry warning.
        assertEquals(UsageSeverity.Critical, usageSeverity(.95f, now.plusSeconds(60), false, now))
    }

    @Test fun removeAnimationsDisablesMotionAndBreathingNeedsAllGates() {
        assertFalse(usageMotionEnabled(0f))
        assertFalse(usageMotionEnabled(-1f))
        assertFalse(usageMotionEnabled(Float.NaN))
        assertTrue(usageMotionEnabled(.5f))
        assertTrue(usageMotionEnabled(1f))
        assertTrue(usageBreathingActive(connected = true, resumed = true, motionEnabled = true))
        assertFalse(usageBreathingActive(connected = false, resumed = true, motionEnabled = true))
        assertFalse(usageBreathingActive(connected = true, resumed = false, motionEnabled = true))
        assertFalse(usageBreathingActive(connected = true, resumed = true, motionEnabled = false))
    }

    @Test fun onlyConfirmedGbValuesParse() {
        assertEquals(BigDecimal("47"), usageGbAmount("47 GB"))
        assertEquals(BigDecimal("3.25"), usageGbAmount("已用 3.25 GB"))
        assertEquals(BigDecimal("0"), usageGbAmount("0 GB"))
        assertNull(usageGbAmount("暂未获取"))
        assertNull(usageGbAmount("额度未设置"))
        assertNull(usageGbAmount("47 GB 以上"))
        assertNull(usageGbAmount("1.234 GB"))
    }

    @Test fun odometerRollsOnlyBetweenTwoKnownDifferentValues() {
        assertEquals(1, usageRollDirection("3 GB", "3.5 GB"))
        assertEquals(-1, usageRollDirection("47 GB", "46.5 GB"))
        assertEquals(0, usageRollDirection("47 GB", "47 GB"))
        assertEquals(0, usageRollDirection("47 GB", "47.00 GB")) // same amount, different text: no roll
        assertEquals(0, usageRollDirection(null, "47 GB"))
        assertEquals(0, usageRollDirection("暂未获取", "47 GB"))
        assertEquals(0, usageRollDirection("47 GB", "暂未获取"))
    }

    @Test fun rollsAreThrottledToOnePerWindow() {
        assertEquals(400L, UsageRollThrottleMillis)
        assertTrue(usageRollAllowed(null, 0))
        assertFalse(usageRollAllowed(1_000, 1_399))
        assertTrue(usageRollAllowed(1_000, 1_400))
    }

    @Test fun presentationsCarryExpiryOnlyAsDisplayInput() {
        val expiry = Instant.parse("2026-11-03T00:00:00Z")
        val resource = Resource.Available("synthetic-resource", "synthetic-plan", expiry, false,
            Usage(100, null, null, "pending", null, null, null), 30, 1.0)
        assertEquals(expiry, accountUsagePresentation(resource, now).expiresAt)
        assertNull(accountUsagePresentation(null, now).expiresAt)
        val subscription = SubscriptionUsagePresentation("7 GB", "3 GB", "10 GB", .3f, null, false, "src", expiry)
        assertEquals(expiry, subscription.asCardPresentation().expiresAt)
    }
}
