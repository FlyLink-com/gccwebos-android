package com.gccwebos.android.portal

/**
 * Debug-only timing marks for the connection-preparation and configuration-import paths. Release
 * builds never install a sink, so nothing is recorded or logged. Marks carry only a fixed phase
 * name and, for spans, a millisecond duration; never a URL, hostname, address or configuration byte.
 * As defense in depth, every line is passed through [SensitiveLogRedaction] before the sink.
 */
internal object PrepTrace {
    @Volatile var sink: ((String) -> Unit)? = null
    fun mark(phase: String) { sink?.invoke(SensitiveLogRedaction.redact(phase)) }

    /** Builds the text only when a sink is installed, so release builds never format it. */
    inline fun markLazy(text: () -> String) { sink?.invoke(SensitiveLogRedaction.redact(text())) }

    /** Runs [block]; with a sink installed it also records "<phase>-begin" and "<phase>-end +N ms". */
    inline fun <T> span(phase: String, block: () -> T): T {
        val out = sink ?: return block()
        out(SensitiveLogRedaction.redact("$phase-begin"))
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            out(SensitiveLogRedaction.redact("$phase-end +${(System.nanoTime() - start) / 1_000_000} ms"))
        }
    }

    /** Takes [lock] and, with a sink installed, records how long this thread waited for it (only waits of 16 ms or more). */
    inline fun <T> lockWait(phase: String, lock: Any, block: () -> T): T {
        val out = sink ?: return synchronized(lock) { block() }
        val start = System.nanoTime()
        return synchronized(lock) {
            val waited = (System.nanoTime() - start) / 1_000_000
            if (waited >= 16) out(SensitiveLogRedaction.redact("$phase-lock-wait +$waited ms thread=${Thread.currentThread().name}"))
            block()
        }
    }
}
