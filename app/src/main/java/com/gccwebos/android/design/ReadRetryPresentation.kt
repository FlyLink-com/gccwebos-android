package com.gccwebos.android.design

/** Visibility only. Retrying must continue to call the existing read handler. */
internal fun showReadRetry(
    busy: Boolean,
    available: Boolean,
    failed: Boolean,
    unread: Boolean,
    unresolved: Boolean = false,
): Boolean = available && !busy && (failed || unread || unresolved)
