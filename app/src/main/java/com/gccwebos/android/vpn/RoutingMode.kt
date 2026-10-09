package com.gccwebos.android.vpn

import android.content.Context

/** Product-owned routing policy. Unknown or missing wire values fail closed to [Global]. */
enum class RoutingMode(val wire: String) {
    Global("global"),
    Rule("rule");

    companion object {
        fun parse(raw: String?): RoutingMode = when (raw?.trim()?.lowercase()) {
            Rule.wire -> Rule
            else -> Global
        }
    }
}

fun interface RoutingModeSource {
    fun current(): RoutingMode
}

/** Durable non-sensitive preference for the LAN DIRECT / global routing mode. */
class AndroidRoutingModeStore(context: Context) : RoutingModeSource {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun current(): RoutingMode =
        RoutingMode.parse(preferences.getString(KEY, RoutingMode.Global.wire))

    fun set(mode: RoutingMode): Boolean =
        preferences.edit().putString(KEY, mode.wire).commit()

    companion object {
        private const val PREFERENCES = "gcc_routing_mode"
        private const val KEY = "routing_mode"
    }
}
