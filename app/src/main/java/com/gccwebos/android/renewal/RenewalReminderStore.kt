package com.gccwebos.android.renewal

import android.content.Context
import java.time.Instant
import java.time.LocalDate

/**
 * Small, non-secret local state for the renewal reminder: the last server-reported expiry instant (so the
 * 20:00 alarm can run without the app or a network call), today's dismissal stage and two flags.
 * It holds no account id, email, token or subscription URL, and is wiped on sign-out.
 */
internal interface RenewalPrefs {
    var validUntil: Instant?
    var dayState: DayState
    /** The notification permission is requested once, the first time the popup is shown. */
    var permissionAsked: Boolean
    /** Beijing day (yyyy-MM-dd) of the last posted notification; stops a duplicate in one day. */
    var lastNotifiedDay: String?
    fun clear()
}

internal class SharedRenewalPrefs(context: Context) : RenewalPrefs {
    private val prefs = context.applicationContext.getSharedPreferences("renewal_reminder", Context.MODE_PRIVATE)

    override var validUntil: Instant?
        get() = prefs.getLong(K_VALID_UNTIL, NONE).takeIf { it != NONE }?.let(Instant::ofEpochSecond)
        set(value) { prefs.edit().apply { if (value == null) remove(K_VALID_UNTIL) else putLong(K_VALID_UNTIL, value.epochSecond) }.apply() }

    override var dayState: DayState
        get() {
            val day = prefs.getString(K_DAY, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val stage = DayStage.entries.getOrNull(prefs.getInt(K_STAGE, 0)) ?: DayStage.Fresh
            return DayState(day, if (day == null) DayStage.Fresh else stage)
        }
        set(value) { prefs.edit().putString(K_DAY, value.day?.toString()).putInt(K_STAGE, value.stage.ordinal).apply() }

    override var permissionAsked: Boolean
        get() = prefs.getBoolean(K_ASKED, false)
        set(value) { prefs.edit().putBoolean(K_ASKED, value).apply() }

    override var lastNotifiedDay: String?
        get() = prefs.getString(K_NOTIFIED, null)
        set(value) { prefs.edit().putString(K_NOTIFIED, value).apply() }

    override fun clear() { prefs.edit().clear().apply() }

    private companion object {
        const val NONE = Long.MIN_VALUE
        const val K_VALID_UNTIL = "valid_until_epoch_s"
        const val K_DAY = "dismiss_day"
        const val K_STAGE = "dismiss_stage"
        const val K_ASKED = "permission_asked"
        const val K_NOTIFIED = "last_notified_beijing_day"
    }
}

/** In-memory fake for JVM tests. */
internal class MemoryRenewalPrefs : RenewalPrefs {
    override var validUntil: Instant? = null
    override var dayState: DayState = DayState(null, DayStage.Fresh)
    override var permissionAsked: Boolean = false
    override var lastNotifiedDay: String? = null
    override fun clear() { validUntil = null; dayState = DayState(null, DayStage.Fresh); permissionAsked = false; lastNotifiedDay = null }
}
