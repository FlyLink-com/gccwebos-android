package com.gccwebos.android.portal

import com.gccwebos.android.BuildConfig

/**
 * The only path from app code to logcat. Debug builds only; release builds never format or write.
 * Every line goes through [SensitiveLogRedaction] first, so a call site cannot leak a subscription
 * URL, token or payment link by mistake. A logging failure never reaches the caller.
 */
internal object DebugLog {
    @Volatile var enabled: Boolean = BuildConfig.DEBUG
    @Volatile var writer: (tag: String, line: String) -> Unit = { tag, line -> android.util.Log.i(tag, line) }
    @Volatile var warnWriter: (tag: String, line: String) -> Unit = { tag, line -> android.util.Log.w(tag, line) }

    fun i(tag: String, line: String) { if (enabled) emit(writer, tag, line) }
    fun w(tag: String, line: String) { if (enabled) emit(warnWriter, tag, line) }

    /** Builds the text only when logging is enabled. */
    inline fun lazy(tag: String, text: () -> String) { if (enabled) i(tag, text()) }

    private fun emit(out: (String, String) -> Unit, tag: String, line: String) {
        runCatching { out(tag, SensitiveLogRedaction.redact(line)) }
    }
}
