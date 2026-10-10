package com.gccwebos.android.renewal

import com.gccwebos.android.portal.Resource
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure rules for the account-subscription renewal reminder (popup, top banner, 20:00 notification).
 * Nothing here touches Android, so the rules are unit tested on the JVM. Only the ACCOUNT resource
 * (`Resource.Available`, read from the server) is an input: imported third-party subscriptions never
 * reach this code, and without a real expiry there is no reminder.
 */
internal object RenewalSpec {
    /** The reminder window: within this many days before expiry. */
    const val WINDOW_DAYS = 7L
    /** "Nearly out of traffic" is only claimed from a fresh server measurement: remaining <= 10% of the total. */
    const val LOW_TRAFFIC_PERCENT = 10L
    /** The daily system notification is sent at this local time in Beijing, whatever the device zone is. */
    val NOTIFICATION_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    val NOTIFICATION_TIME: LocalTime = LocalTime.of(20, 0)
    const val CHANNEL_ID = "renewal_reminder"
    const val NOTIFICATION_ID = 7201
    const val ACTION_OPEN_PLANS = "com.gccwebos.android.action.OPEN_PLANS"
}

internal enum class ReminderKind {
    /** Already past the server-reported expiry. */
    Expired,
    /** Inside the 7-day window before expiry. */
    Expiring,
    /** Fresh server measurement says traffic is nearly used up (expiry may be far away). */
    LowTraffic,
}

internal data class RenewalReminder(
    val kind: ReminderKind,
    val validUntil: Instant,
    /** Whole days left, rounded up (0 only when [kind] is Expired). */
    val daysLeft: Long,
    val remainingBytes: Long? = null,
)

/** The reminder that applies right now, or null. A missing/None/Reconciliation resource never produces one. */
internal fun renewalReminder(resource: Resource?, now: Instant): RenewalReminder? {
    val available = resource as? Resource.Available ?: return null
    val validUntil = available.validUntil
    if (validUntil <= now) return RenewalReminder(ReminderKind.Expired, validUntil, 0)
    val days = daysLeftCeil(validUntil, now)
    if (withinWindow(validUntil, now)) return RenewalReminder(ReminderKind.Expiring, validUntil, days)
    val usage = available.usage
    val reliable = usage.status == "measured" || usage.status == "measured_zero"
    val remaining = usage.remainingBytes
    if (reliable && usage.totalBytes > 0 && remaining != null &&
        remaining * 100 <= usage.totalBytes * RenewalSpec.LOW_TRAFFIC_PERCENT)
        return RenewalReminder(ReminderKind.LowTraffic, validUntil, days, remaining)
    return null
}

internal fun withinWindow(validUntil: Instant, now: Instant): Boolean =
    validUntil > now && Duration.between(now, validUntil) <= Duration.ofDays(RenewalSpec.WINDOW_DAYS)

internal fun daysLeftCeil(validUntil: Instant, now: Instant): Long {
    if (validUntil <= now) return 0
    val remaining = Duration.between(now, validUntil)
    val seconds = remaining.seconds + if (remaining.nano > 0) 1 else 0 // partial second rounds up
    return (seconds + 86_399) / 86_400
}

// ---- Daily dismissal state machine -------------------------------------------------------------------

internal enum class DayStage {
    /** Nothing dismissed today: the popup shows. */
    Fresh,
    /** Popup closed today: the thin banner shows. */
    PopupDismissed,
    /** Banner closed today: nothing more today. */
    BannerDismissed,
}

internal enum class ReminderSurface { None, Popup, Banner }

/** Persisted per local day. A different day always starts Fresh again. */
internal data class DayState(val day: LocalDate?, val stage: DayStage) {
    fun stageOn(today: LocalDate): DayStage = if (day == today) stage else DayStage.Fresh
}

internal fun surfaceFor(reminder: RenewalReminder?, state: DayState, today: LocalDate): ReminderSurface {
    if (reminder == null) return ReminderSurface.None
    return when (state.stageOn(today)) {
        DayStage.Fresh -> ReminderSurface.Popup
        DayStage.PopupDismissed -> ReminderSurface.Banner
        DayStage.BannerDismissed -> ReminderSurface.None
    }
}

internal fun dismissPopup(state: DayState, today: LocalDate): DayState =
    if (state.stageOn(today) == DayStage.Fresh) DayState(today, DayStage.PopupDismissed) else DayState(today, state.stageOn(today))

internal fun dismissBanner(state: DayState, today: LocalDate): DayState = DayState(today, DayStage.BannerDismissed)

/** Local day boundary on the device zone. */
internal fun localDay(now: Instant, zone: ZoneId): LocalDate = now.atZone(zone).toLocalDate()

/** Milliseconds until the next local midnight (at least 1 s), so a mounted screen re-evaluates the day. */
internal fun millisUntilNextDay(now: Instant, zone: ZoneId): Long {
    val next = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
    return Duration.between(now, next).toMillis().coerceAtLeast(1_000)
}

// ---- Daily 20:00 (Beijing) notification ---------------------------------------------------------------

/**
 * The next 20:00 Asia/Shanghai strictly after [after] that lies inside the notification window
 * (>= 7 days before expiry and before expiry). Null when expiry has passed or the next slot is after it.
 * Before the window opens this is the first 20:00 inside it, so a far-away expiry arms one alarm only.
 */
internal fun nextNotificationTime(validUntil: Instant, after: Instant): Instant? {
    val windowStart = validUntil.minus(Duration.ofDays(RenewalSpec.WINDOW_DAYS))
    val from = if (after > windowStart) after else windowStart
    var day = from.atZone(RenewalSpec.NOTIFICATION_ZONE).toLocalDate()
    var slot = ZonedDateTime.of(day, RenewalSpec.NOTIFICATION_TIME, RenewalSpec.NOTIFICATION_ZONE).toInstant()
    // Strictly after "after", and not before the window opens.
    while (slot <= after || slot < windowStart) {
        day = day.plusDays(1)
        slot = ZonedDateTime.of(day, RenewalSpec.NOTIFICATION_TIME, RenewalSpec.NOTIFICATION_ZONE).toInstant()
    }
    return slot.takeIf { it < validUntil }
}

/** Receiver-side decision when an alarm fires. [lastNotifiedDay] stops a duplicate for the same Beijing day. */
internal fun shouldNotify(validUntil: Instant?, now: Instant, lastNotifiedDay: String?): Boolean {
    if (validUntil == null || !withinWindow(validUntil, now)) return false
    return lastNotifiedDay != beijingDayKey(now)
}

internal fun beijingDayKey(now: Instant): String =
    now.atZone(RenewalSpec.NOTIFICATION_ZONE).toLocalDate().toString()

// ---- Chinese copy (real data only) --------------------------------------------------------------------

private fun date(validUntil: Instant, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(zone).format(validUntil)

internal fun popupTitle(reminder: RenewalReminder): String = when (reminder.kind) {
    ReminderKind.Expired -> "套餐已到期"
    ReminderKind.Expiring -> "套餐即将到期"
    ReminderKind.LowTraffic -> "流量即将用完"
}

internal fun popupBody(reminder: RenewalReminder, zone: ZoneId): String = when (reminder.kind) {
    ReminderKind.Expired -> "你的账户套餐已于 ${date(reminder.validUntil, zone)} 到期，续费后可继续使用。"
    ReminderKind.Expiring -> "你的账户套餐将于 ${date(reminder.validUntil, zone)} 到期（剩余 ${reminder.daysLeft} 天）。"
    ReminderKind.LowTraffic -> "本周期剩余流量已不足 ${RenewalSpec.LOW_TRAFFIC_PERCENT}%，可前往套餐页续费或充值。套餐到期时间：${date(reminder.validUntil, zone)}。"
}

internal fun bannerText(reminder: RenewalReminder): String = when (reminder.kind) {
    ReminderKind.Expired -> "套餐已到期，点击续费"
    ReminderKind.Expiring -> "套餐剩余 ${reminder.daysLeft} 天到期，点击续费"
    ReminderKind.LowTraffic -> "流量即将用完，点击续费或充值"
}

internal const val NOTIFICATION_TITLE = "套餐即将到期"

internal fun notificationText(validUntil: Instant): String =
    "你的账户套餐将于 ${date(validUntil, RenewalSpec.NOTIFICATION_ZONE)}（北京时间）到期，点击前往续费。"
