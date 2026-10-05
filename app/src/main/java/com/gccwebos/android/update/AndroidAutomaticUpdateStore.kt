package com.gccwebos.android.update

import android.content.Context
import java.io.IOException

/** Non-sensitive scheduling preferences. The controller calls every method on its IO dispatcher. */
internal class AndroidAutomaticUpdateStore(context: Context) : AutomaticUpdateStore {
    private val app = context.applicationContext
    private fun preferences() = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override suspend fun tryBeginCheck(now: Long, interval: Long): Boolean = synchronized(lock) {
        val preferences = preferences()
        val last = (preferences.all[LAST_ATTEMPT] as? Long)?.takeIf { it > 0 } ?: 0L
        if (!automaticCheckDue(last, now, interval)) return@synchronized false
        if (!preferences.edit().putLong(LAST_ATTEMPT, now).commit()) throw IOException("Update schedule unavailable")
        true
    }

    override suspend fun wasPrompted(code: Long): Boolean = synchronized(lock) {
        code <= highestPrompted()
    }

    override suspend fun recordPrompt(code: Long) = synchronized(lock) {
        require(code in 1..2_100_000_000L)
        if (code > highestPrompted() && !preferences().edit().putLong(HIGHEST_PROMPTED, code).commit())
            throw IOException("Update schedule unavailable")
        Unit
    }

    private fun highestPrompted(): Long = (preferences().all[HIGHEST_PROMPTED] as? Long)
        ?.takeIf { it in 1..2_100_000_000L } ?: 0L

    internal companion object {
        const val PREFERENCES = "official-update-schedule"
        const val LAST_ATTEMPT = "last-attempt-millis"
        const val HIGHEST_PROMPTED = "highest-prompted-version-code"
        private val lock = Any()
    }
}
