package com.gccwebos.android.time

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Durable, monotonic lower bound of trusted time (epoch millis). */
interface TrustedTimeStore {
    fun read(): Long?
    fun save(epochMillis: Long)
}

class MemoryTrustedTimeStore : TrustedTimeStore {
    @Volatile private var value: Long? = null
    override fun read() = value
    override fun save(epochMillis: Long) { value = epochMillis }
}

/**
 * Expiry clock that a phone clock set backwards cannot fool.
 *
 * - [observeServerTime] anchors HTTPS server time (official portal / subscription `Date`) to the monotonic clock.
 * - Trusted now = server + (elapsed - capturedElapsed); after a reboot the anchor is gone and the persisted
 *   floor (last trusted time seen, only ever increasing) is the lower bound.
 * - [now] returns the phone clock unless it is behind trusted time by more than [toleranceMillis]; then trusted time.
 *   A phone clock that is ahead is used as-is (never extends a subscription). Without any server time: phone clock.
 */
class TrustedClock(
    private val wall: () -> Instant = Instant::now,
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private var store: TrustedTimeStore? = null,
    private val toleranceMillis: Long = 120_000,
    private val persistEveryMillis: Long = 60_000,
) {
    private var anchorServer: Long? = null
    private var anchorElapsed = 0L
    private var floor: Long? = null
    private var floorLoaded = false

    @Synchronized fun install(store: TrustedTimeStore) { this.store = store; floorLoaded = false }

    @Synchronized fun observeServerTime(server: Instant) {
        anchorServer = server.toEpochMilli(); anchorElapsed = elapsedMillis()
        raiseFloor(server.toEpochMilli(), force = true)
    }

    /** Trusted time if any is known, ignoring the phone clock. */
    @Synchronized fun trustedNow(): Instant? {
        loadFloor()
        val derived = anchorServer?.let { it + (elapsedMillis() - anchorElapsed) }
        val best = listOfNotNull(derived, floor).maxOrNull() ?: return null
        if (derived != null) raiseFloor(derived, force = false)
        return Instant.ofEpochMilli(best)
    }

    fun now(): Instant {
        val phone = wall()
        val trusted = trustedNow() ?: return phone
        return if (phone.toEpochMilli() < trusted.toEpochMilli() - toleranceMillis) trusted else phone
    }

    private fun loadFloor() {
        if (floorLoaded) return
        floorLoaded = true
        val stored = try { store?.read() } catch (_: Exception) { null }
        floor = listOfNotNull(floor, stored).maxOrNull()
    }

    private fun raiseFloor(candidate: Long, force: Boolean) {
        loadFloor()
        val current = floor
        if (current != null && candidate <= current) return
        if (!force && current != null && candidate - current < persistEveryMillis) return
        floor = candidate
        try { store?.save(candidate) } catch (_: Exception) { }
    }

    companion object {
        /** Process clock used for subscription expiry everywhere. */
        val process = TrustedClock()
        fun now(): Instant = process.now()

        /** RFC 1123 `Date` header; null when absent or malformed. */
        fun parseHttpDate(value: String?): Instant? = try {
            value?.let { ZonedDateTime.parse(it.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
        } catch (_: Exception) { null }

        /** Network interceptor for official HTTPS clients only; never for third-party subscription hosts. */
        val dateInterceptor = okhttp3.Interceptor { chain ->
            val response = chain.proceed(chain.request())
            if (chain.request().isHttps) parseHttpDate(response.header("Date"))?.let(process::observeServerTime)
            response
        }
    }
}

/** SharedPreferences-backed floor; holds only a timestamp, no account data. */
class PrefsTrustedTimeStore(context: android.content.Context) : TrustedTimeStore {
    private val prefs = context.getSharedPreferences("trusted_time", android.content.Context.MODE_PRIVATE)
    override fun read(): Long? = prefs.getLong("floor", -1L).takeIf { it > 0 }
    override fun save(epochMillis: Long) { prefs.edit().putLong("floor", epochMillis).apply() }
}
