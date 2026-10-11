package com.gccwebos.android.portal

/**
 * Debug-only timing marks for the connection-preparation path. Release builds never install a
 * sink, so nothing is recorded or logged. Marks carry only a fixed phase name, never a URL,
 * hostname, address or configuration byte.
 */
internal object PrepTrace {
    @Volatile var sink: ((String) -> Unit)? = null
    fun mark(phase: String) { sink?.invoke(phase) }
}
