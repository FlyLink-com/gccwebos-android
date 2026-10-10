package com.gccwebos.android.renewal

import com.gccwebos.android.portal.Resource
import com.gccwebos.android.portal.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

private class FakeAlarms : RenewalAlarms {
    var armed: Instant? = null
    var cancels = 0
    var dismissals = 0
    override fun arm(at: Instant) { armed = at }
    override fun cancel() { armed = null; cancels++ }
    override fun dismissPosted() { dismissals++ }
}

class RenewalReminderCoordinatorTest {
    private val sh = ZoneId.of("Asia/Shanghai")
    private fun bj(day: Int, hour: Int = 12) = ZonedDateTime.of(2026, 10, day, hour, 0, 0, 0, sh).toInstant()
    private fun available(validUntil: Instant) =
        Resource.Available("ref", "plan", validUntil, true, Usage(1000, 100, 900, "measured", bj(9)))

    private val prefs = MemoryRenewalPrefs()
    private val alarms = FakeAlarms()
    private val coordinator = RenewalReminderCoordinator(prefs, alarms)

    @Test fun accountResourceStoresExpiryAndArmsTheNextTwentyHundred() {
        coordinator.onResource(available(bj(14)), bj(10))
        assertEquals(bj(14), prefs.validUntil)
        assertEquals(bj(10, 20), alarms.armed)
    }

    @Test fun unknownOrReconcilingResourceChangesNothing() {
        coordinator.onResource(available(bj(14)), bj(10))
        coordinator.onResource(null, bj(10))
        coordinator.onResource(Resource.Reconciliation, bj(10))
        assertEquals(bj(14), prefs.validUntil)
        assertEquals(bj(10, 20), alarms.armed)
    }

    @Test fun renewalStopsTheReminderAndRemovesThePostedNotification() {
        coordinator.onResource(available(bj(11)), bj(10))
        val before = alarms.dismissals
        // Renewed for another year: the expiry moves far away, the window no longer applies.
        coordinator.onResource(available(bj(10) .plusSeconds(365L * 86_400)), bj(10, 13))
        assertTrue(alarms.dismissals > before)
        assertEquals(bj(10).plusSeconds(358L * 86_400).atZone(sh).toLocalDate(), alarms.armed!!.atZone(sh).toLocalDate())
        assertFalse(coordinator.onAlarm(bj(10, 20))) // not inside the window anymore: no notification
    }

    @Test fun alarmPostsOncePerDayAndRearmsTomorrow() {
        coordinator.onResource(available(bj(14)), bj(10))
        assertTrue(coordinator.onAlarm(bj(10, 20)))
        assertEquals(bj(11, 20), alarms.armed)
        assertFalse(coordinator.onAlarm(bj(10, 20))) // duplicate delivery the same day
        assertTrue(coordinator.onAlarm(bj(11, 20)))
    }

    @Test fun alarmAfterExpiryDoesNothingAndDoesNotRearm() {
        coordinator.onResource(available(bj(11)), bj(10))
        alarms.armed = null
        assertFalse(coordinator.onAlarm(bj(12, 20)))
        assertNull(alarms.armed)
    }

    @Test fun signOutWipesExpiryAlarmAndNotificationButKeepsPermissionFlag() {
        coordinator.onResource(available(bj(14)), bj(10))
        coordinator.takePermissionRequest()
        coordinator.dismissPopup(bj(10), sh)
        coordinator.onSignedOut()
        assertNull(prefs.validUntil)
        assertNull(alarms.armed)
        assertTrue(alarms.dismissals > 0)
        assertEquals(DayStage.Fresh, prefs.dayState.stage)
        assertTrue(prefs.permissionAsked)
        assertFalse(coordinator.onAlarm(bj(10, 20)))
    }

    @Test fun noResourceForTheAccountClearsTheReminder() {
        coordinator.onResource(available(bj(14)), bj(10))
        coordinator.onResource(Resource.None, bj(10))
        assertNull(prefs.validUntil)
        assertNull(alarms.armed)
    }

    @Test fun rebootRearmsFromStoredExpiry() {
        prefs.validUntil = bj(14)
        coordinator.onRestore(bj(10, 8))
        assertEquals(bj(10, 20), alarms.armed)
        prefs.validUntil = null
        coordinator.onRestore(bj(10, 8))
        assertNull(alarms.armed)
    }

    @Test fun permissionIsRequestedExactlyOnce() {
        assertTrue(coordinator.takePermissionRequest())
        assertFalse(coordinator.takePermissionRequest())
    }

    @Test fun dismissalIsPersistedPerDay() {
        coordinator.dismissPopup(bj(10), sh)
        assertEquals(DayStage.PopupDismissed, coordinator.dayState().stage)
        coordinator.dismissBanner(bj(10), sh)
        assertEquals(DayStage.BannerDismissed, coordinator.dayState().stageOn(bj(10).atZone(sh).toLocalDate()))
        assertEquals(DayStage.Fresh, coordinator.dayState().stageOn(bj(11).atZone(sh).toLocalDate()))
    }

    @Test fun alarmOutcomeWithoutExpiryIsEmpty() {
        assertEquals(AlarmOutcome(false, null), alarmOutcome(null, bj(10, 20), null))
    }
}
