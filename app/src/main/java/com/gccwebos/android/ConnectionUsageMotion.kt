package com.gccwebos.android

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** Display-only emphasis for the connection usage card. It never gates admission or changes any value. */
internal enum class UsageSeverity { Normal, Warning, Critical }

internal const val UsageWarningRemainingFraction = 0.20
internal const val UsageCriticalRemainingFraction = 0.10
internal val UsageExpiryWarningWindow: Duration = Duration.ofDays(3)

/**
 * Pure threshold mapping. [progress] is the confirmed used fraction (null when unknown), so an unknown or
 * pending sample never produces a warning colour. Expired service is critical; expiring within three days
 * is a warning. Low remaining traffic: below 20% is a warning, below 10% (or exhausted) is critical.
 */
internal fun usageSeverity(progress: Float?, expiresAt: Instant?, serviceExpired: Boolean, now: Instant?): UsageSeverity {
    // Rounded to 0.01% so float noise (1 - 0.8f) cannot flip a boundary.
    val remaining = progress?.takeIf { it.isFinite() }?.let { Math.round((1.0 - it.coerceIn(0f, 1f)) * 10_000) / 10_000.0 }
    val expired = serviceExpired || (expiresAt != null && now != null && expiresAt <= now)
    val expiringSoon = !expired && expiresAt != null && now != null &&
        Duration.between(now, expiresAt) <= UsageExpiryWarningWindow
    return when {
        expired -> UsageSeverity.Critical
        remaining != null && remaining < UsageCriticalRemainingFraction -> UsageSeverity.Critical
        remaining != null && remaining < UsageWarningRemainingFraction -> UsageSeverity.Warning
        expiringSoon -> UsageSeverity.Warning
        else -> UsageSeverity.Normal
    }
}

/** System "remove animations" sets ANIMATOR_DURATION_SCALE to 0; any non-positive or invalid value disables motion. */
internal fun usageMotionEnabled(animatorDurationScale: Float): Boolean =
    animatorDurationScale.isFinite() && animatorDurationScale > 0f

/** The only infinite animation (breathing glow) runs solely while connected, resumed and motion is allowed. */
internal fun usageBreathingActive(connected: Boolean, resumed: Boolean, motionEnabled: Boolean): Boolean =
    connected && resumed && motionEnabled

private val UsageGbValue = Regex("(?:.* )?([0-9]+(?:\\.[0-9]{1,2})?) GB")

/** Parses only the card's own binary-GB display text ("47 GB", "已用 3.25 GB"); anything else is unknown. */
internal fun usageGbAmount(text: String): BigDecimal? =
    UsageGbValue.matchEntire(text)?.groupValues?.get(1)?.toBigDecimalOrNull()

/**
 * Odometer direction: +1 rolls the new value up from below (value increased), -1 rolls down from above,
 * 0 means no roll. Unknown ("暂未获取") or non-numeric values on either side never animate, so a fake value
 * is never shown in transit.
 */
internal fun usageRollDirection(previous: String?, next: String): Int {
    if (previous == null || previous == next) return 0
    val old = usageGbAmount(previous) ?: return 0
    val new = usageGbAmount(next) ?: return 0
    return when (new.compareTo(old)) { 1 -> 1; -1 -> -1; else -> 0 }
}

internal const val UsageRollThrottleMillis = 400L

/** A roll may start only once per throttle window; faster changes snap to the latest value instead. */
internal fun usageRollAllowed(lastRollAtMillis: Long?, nowMillis: Long, throttleMillis: Long = UsageRollThrottleMillis): Boolean =
    lastRollAtMillis == null || nowMillis - lastRollAtMillis >= throttleMillis
