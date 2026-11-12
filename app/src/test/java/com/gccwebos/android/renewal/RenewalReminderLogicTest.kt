package com.gccwebos.android.renewal

import com.gccwebos.android.portal.Resource
import com.gccwebos.android.portal.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class RenewalReminderLogicTest {
    private val sh = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 10, 10, 12, 0, 0, 0, sh).toInstant()

    private fun usage(status: String = "measured", total: Long = 1000, used: Long? = 100) = when (status) {
        "pending" -> Usage(total, null, null, "pending", null)
        else -> Usage(total, used, if (total == 0L) null else (total - used!!).coerceAtLeast(0), status, now.minusSeconds(60))
    }

    private fun resource(validUntil: Instant, usage: Usage = usage()) =
        Resource.Available("ref", "plan", validUntil, true, usage)

    @Test fun noReminderWithoutRealExpiryData() {
        assertNull(renewalReminder(null, now))
        assertNull(renewalReminder(Resource.None, now))
        assertNull(renewalReminder(Resource.Reconciliation, now))
    }

    @Test fun farExpiryHasNoReminder() {
        assertNull(renewalReminder(resource(now.plus(Duration.ofDays(8))), now))
    }

    @Test fun reminderStartsExactlySevenDaysBeforeExpiry() {
        val seven = renewalReminder(resource(now.plus(Duration.ofDays(7))), now)
        assertEquals(ReminderKind.Expiring, seven?.kind)
        assertEquals(7L, seven?.daysLeft)
        assertNull(renewalReminder(resource(now.plus(Duration.ofDays(7)).plusSeconds(1)), now))
        assertEquals(1L, renewalReminder(resource(now.plus(Duration.ofHours(1))), now)?.daysLeft)
        assertEquals(2L, renewalReminder(resource(now.plus(Duration.ofHours(25))), now)?.daysLeft)
    }

    @Test fun daysLeftRoundsUpAtWholeDayBoundaries() {
        // Fixed clock: every case pins both instants, so no wall-clock date can move the result.
        assertEquals(3L, daysLeftCeil(now.plus(Duration.ofDays(3)), now))
        assertEquals(4L, daysLeftCeil(now.plus(Duration.ofDays(3)).plusSeconds(1), now))
        assertEquals(3L, daysLeftCeil(now.plus(Duration.ofDays(3)).minusSeconds(1), now))
        assertEquals(1L, daysLeftCeil(now.plusSeconds(1), now))
        assertEquals(0L, daysLeftCeil(now, now))
        assertEquals(0L, daysLeftCeil(now.minusSeconds(1), now))
        assertEquals(7L, daysLeftCeil(now.plus(Duration.ofDays(7)), now))
        assertEquals(8L, daysLeftCeil(now.plus(Duration.ofDays(7)).plusSeconds(1), now))
    }

    @Test fun threeDaysExactlyRendersThreeDaysNotFour() {
        val exact = renewalReminder(resource(now.plus(Duration.ofDays(3))), now)!!
        assertEquals(ReminderKind.Expiring, exact.kind)
        assertEquals(3L, exact.daysLeft)
        assertTrue(popupBody(exact, sh).contains("剩余 3 天"))
        assertEquals("套餐剩余 3 天到期，点击续费", bannerText(exact))
        // The old device fixture added an extra hour on top of the 3 days; ceil turns that into 4.
        // That arithmetic — not the product — is what the flaky device assertion tripped over.
        val plusHour = renewalReminder(resource(now.plus(Duration.ofDays(3)).plusSeconds(3600)), now)!!
        assertEquals(4L, plusHour.daysLeft)
    }

    @Test fun expiredResourceIsReportedAsExpired() {
        val expired = renewalReminder(resource(now.minusSeconds(1)), now)
        assertEquals(ReminderKind.Expired, expired?.kind)
        assertEquals(0L, expired?.daysLeft)
        assertEquals(ReminderKind.Expired, renewalReminder(resource(now), now)?.kind)
    }

    @Test fun lowTrafficOnlyFromFreshServerMeasurement() {
        val far = now.plus(Duration.ofDays(30))
        val low = renewalReminder(resource(far, usage("measured", 1000, 950)), now)
        assertEquals(ReminderKind.LowTraffic, low?.kind)
        assertEquals(50L, low?.remainingBytes)
        // Exactly 10% remaining counts; just above does not.
        assertNotNull(renewalReminder(resource(far, usage("measured", 1000, 900)), now))
        assertNull(renewalReminder(resource(far, usage("measured", 1000, 899)), now))
        // Unknown / stale / not measured never claims "nearly out".
        assertNull(renewalReminder(resource(far, usage("pending")), now))
        assertNull(renewalReminder(resource(far, usage("stale", 1000, 990)), now))
        assertNull(renewalReminder(resource(far, usage("measured", 0, 0)), now))
    }

    @Test fun expiryWinsOverLowTraffic() {
        val both = renewalReminder(resource(now.plus(Duration.ofDays(3)), usage("measured", 1000, 990)), now)
        assertEquals(ReminderKind.Expiring, both?.kind)
    }

    // ---- daily state machine ----

    private val monday = LocalDate.of(2026, 10, 12)
    private val reminder = renewalReminder(resource(now.plus(Duration.ofDays(2))), now)

    @Test fun popupThenBannerThenNothingForTheRestOfTheDay() {
        var state = DayState(null, DayStage.Fresh)
        assertEquals(ReminderSurface.Popup, surfaceFor(reminder, state, monday))
        state = dismissPopup(state, monday)
        assertEquals(ReminderSurface.Banner, surfaceFor(reminder, state, monday))
        state = dismissBanner(state, monday)
        assertEquals(ReminderSurface.None, surfaceFor(reminder, state, monday))
    }

    @Test fun dismissedBannerComesBackNextDayAsPopup() {
        val state = DayState(monday, DayStage.BannerDismissed)
        assertEquals(ReminderSurface.None, surfaceFor(reminder, state, monday))
        assertEquals(ReminderSurface.Popup, surfaceFor(reminder, state, monday.plusDays(1)))
        assertEquals(ReminderSurface.Popup, surfaceFor(reminder, DayState(monday, DayStage.PopupDismissed), monday.plusDays(1)))
    }

    @Test fun nothingShowsWithoutAReminder() {
        assertEquals(ReminderSurface.None, surfaceFor(null, DayState(null, DayStage.Fresh), monday))
    }

    @Test fun dismissingThePopupAgainDoesNotRewindABannerDismissal() {
        val state = DayState(monday, DayStage.BannerDismissed)
        assertEquals(DayStage.BannerDismissed, dismissPopup(state, monday).stage)
    }

    @Test fun localDayBoundaryFollowsTheDeviceZone() {
        val instant = Instant.parse("2026-10-10T17:30:00Z")
        assertEquals(LocalDate.of(2026, 10, 10), localDay(instant, ZoneId.of("UTC")))
        assertEquals(LocalDate.of(2026, 10, 11), localDay(instant, sh))
        assertEquals(LocalDate.of(2026, 10, 10), localDay(instant, ZoneId.of("America/Los_Angeles")))
    }

    @Test fun millisUntilMidnight() {
        val t = ZonedDateTime.of(2026, 10, 10, 23, 59, 0, 0, sh).toInstant()
        assertEquals(60_000L, millisUntilNextDay(t, sh))
        assertTrue(millisUntilNextDay(ZonedDateTime.of(2026, 10, 10, 23, 59, 59, 999_000_000, sh).toInstant(), sh) >= 1_000)
    }

    // ---- 20:00 Beijing notification ----

    private fun bj(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, sh).toInstant()

    @Test fun nextSlotIsTwentyHundredBeijingTimeToday() {
        val expiry = bj(14, 9)
        assertEquals(bj(10, 20), nextNotificationTime(expiry, bj(10, 12)))
    }

    @Test fun slotAfterTwentyRollsToTomorrow() {
        val expiry = bj(14, 9)
        assertEquals(bj(11, 20), nextNotificationTime(expiry, bj(10, 20)))
        assertEquals(bj(11, 20), nextNotificationTime(expiry, bj(10, 21)))
    }

    @Test fun farExpiryArmsFirstSlotInsideWindowOnly() {
        val expiry = bj(30, 9) // window opens Oct 23 09:00
        assertEquals(bj(23, 20), nextNotificationTime(expiry, bj(10, 12)))
        assertEquals(bj(24, 20), nextNotificationTime(expiry, bj(23, 20)))
    }

    @Test fun noSlotAfterExpiryOrWhenExpiryIsBeforeNextSlot() {
        assertNull(nextNotificationTime(bj(10, 9), bj(10, 12)))
        assertNull(nextNotificationTime(bj(10, 19, 59), bj(10, 12)))
        assertEquals(bj(10, 20), nextNotificationTime(bj(10, 20, 1), bj(10, 12)))
        assertNull(nextNotificationTime(bj(10, 20), bj(10, 12))) // expiry exactly at 20:00: that slot is not before expiry
    }

    @Test fun slotIsBeijingTimeRegardlessOfDeviceZone() {
        val slot = nextNotificationTime(bj(14, 9), bj(10, 12))!!
        assertEquals(12, slot.atZone(ZoneId.of("UTC")).hour)
        assertEquals(5, slot.atZone(ZoneId.of("America/Los_Angeles")).hour) // PDT, Oct 10
    }

    @Test fun notifyOnlyInsideWindowAndOncePerBeijingDay() {
        val expiry = bj(14, 9)
        assertTrue(shouldNotify(expiry, bj(10, 20), null))
        assertFalse(shouldNotify(expiry, bj(10, 20), "2026-10-10"))
        assertTrue(shouldNotify(expiry, bj(11, 20), "2026-10-10"))
        assertFalse(shouldNotify(bj(30, 9), bj(10, 20), null)) // not yet inside the window
        assertFalse(shouldNotify(expiry, bj(14, 10), null)) // already expired: no more daily nagging
        assertFalse(shouldNotify(null, bj(10, 20), null))
    }

    @Test fun beijingDayKeyUsesBeijingNotDeviceTime() {
        assertEquals("2026-10-11", beijingDayKey(Instant.parse("2026-10-10T17:00:00Z")))
        assertEquals("2026-10-10", beijingDayKey(Instant.parse("2026-10-10T15:59:59Z")))
    }

    // ---- copy shows only real data ----

    @Test fun copyUsesRealDatesAndCounts() {
        val expiry = bj(12, 9, 30)
        val r = renewalReminder(resource(expiry), bj(10, 12))!!
        assertTrue(popupBody(r, sh).contains("2026-10-12 09:30"))
        assertTrue(popupBody(r, sh).contains("2 天"))
        assertEquals("套餐剩余 2 天到期，点击续费", bannerText(r))
        assertTrue(notificationText(expiry).contains("2026-10-12 09:30"))
        assertTrue(notificationText(expiry).contains("北京时间"))
    }
}
