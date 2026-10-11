package com.gccwebos.android.renewal

import com.gccwebos.android.portal.Resource
import java.time.Instant
import java.time.ZoneId

/** The system side of the reminder (alarm). Abstract so the coordinator's rules are unit tested without Android. */
internal interface RenewalAlarms {
    /** Arm exactly one pending alarm at [at], replacing any earlier one. */
    fun arm(at: Instant)
    fun cancel()
    /** Remove an already posted renewal notification. */
    fun dismissPosted()
}

/** What an alarm firing leads to. */
internal data class AlarmOutcome(val notify: Boolean, val rearmAt: Instant?)

internal fun alarmOutcome(validUntil: Instant?, now: Instant, lastNotifiedDay: String?): AlarmOutcome {
    if (validUntil == null) return AlarmOutcome(false, null)
    return AlarmOutcome(shouldNotify(validUntil, now, lastNotifiedDay), nextNotificationTime(validUntil, now))
}

/**
 * Keeps the stored expiry, the daily alarm and the dismissal state consistent with what the server last
 * said about the ACCOUNT resource. Only authoritative resources change anything: an unknown or loading
 * resource (null) leaves the existing state alone; "no resource" removes the reminder.
 */
internal class RenewalReminderCoordinator(private val prefs: RenewalPrefs, private val alarms: RenewalAlarms) {
    /** Record the latest authoritative resource and re-arm (or cancel) the daily 20:00 alarm. */
    fun onResource(resource: Resource?, now: Instant) {
        when (resource) {
            is Resource.Available -> {
                prefs.validUntil = resource.validUntil
                rearm(resource.validUntil, now)
            }
            Resource.None -> forget()
            Resource.Reconciliation, null -> Unit
        }
    }

    /** Sign-out or local-data clearing: no expiry, no alarm, no leftover notification. */
    fun onSignedOut() { forget() }

    /** Boot / package replaced / app start: alarms do not survive a reboot, so arm again from stored data. */
    fun onRestore(now: Instant) {
        val validUntil = prefs.validUntil
        if (validUntil == null) alarms.cancel() else rearm(validUntil, now)
    }

    /** Alarm fired. Returns true when a notification should be posted now; always re-arms the next slot. */
    fun onAlarm(now: Instant): Boolean {
        val outcome = alarmOutcome(prefs.validUntil, now, prefs.lastNotifiedDay)
        if (outcome.notify) prefs.lastNotifiedDay = beijingDayKey(now)
        outcome.rearmAt?.let(alarms::arm)
        return outcome.notify
    }

    private fun rearm(validUntil: Instant, now: Instant) {
        val next = nextNotificationTime(validUntil, now)
        if (next == null) alarms.cancel() else alarms.arm(next)
        // Renewed: the window no longer applies, so a notification left in the shade is stale.
        if (!withinWindow(validUntil, now)) alarms.dismissPosted()
    }

    private fun forget() {
        // The permission was already asked of this device; that is not account data.
        val asked = prefs.permissionAsked
        prefs.clear()
        prefs.permissionAsked = asked
        alarms.cancel()
        alarms.dismissPosted()
    }

    // ---- UI state ----

    fun dayState(): DayState = prefs.dayState
    fun dismissPopup(now: Instant, zone: ZoneId) { prefs.dayState = dismissPopup(prefs.dayState, localDay(now, zone)) }
    fun dismissBanner(now: Instant, zone: ZoneId) { prefs.dayState = dismissBanner(prefs.dayState, localDay(now, zone)) }

    /** True exactly once: the first time the popup is shown, when the notification permission should be requested. */
    fun takePermissionRequest(): Boolean {
        if (prefs.permissionAsked) return false
        prefs.permissionAsked = true
        return true
    }
}
