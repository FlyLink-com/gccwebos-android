package com.gccwebos.android.vpn

/** Permission results never start the VPN: a second explicit foreground click is required.
 * Cancel invalidates the outstanding result; a new request waits until that result is consumed.
 */
class LabPermissionAttempt {
    private var serial = 0L
    private var outstanding: Long? = null
    private var valid = false
    fun begin(): Long? {
        if (outstanding != null) return null
        return (++serial).also { outstanding = it; valid = true }
    }
    fun cancel() { valid = false }
    fun complete(ticket: Long): Boolean {
        if (outstanding != ticket) return false
        outstanding = null
        return valid.also { valid = false }
    }
}
