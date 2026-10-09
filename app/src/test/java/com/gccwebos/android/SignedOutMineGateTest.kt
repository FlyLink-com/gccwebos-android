package com.gccwebos.android

import org.junit.Assert.*
import org.junit.Test

class SignedOutMineGateTest {
    private fun gate(authenticated: Boolean = false, retaining: Boolean = false, requested: Boolean = false,
        restoring: Boolean = false, foreground: Boolean = false, pending: Boolean = false, factor: Boolean = false,
        busy: Boolean = false) = showsSignedOutMine(authenticated, retaining, requested, restoring, foreground, pending, factor, busy)

    @Test fun idleSignedOutShowsTheMineLayout() = assertTrue(gate())

    @Test fun signedInOrRetainedCommerceNeverUsesTheSignedOutLayout() {
        assertFalse(gate(authenticated = true))
        assertFalse(gate(retaining = true))
    }

    @Test fun explicitRequestsAndInFlightSignInKeepTheExistingLoginFlow() {
        assertFalse(gate(requested = true))
        assertFalse(gate(restoring = true))
        assertFalse(gate(foreground = true))
        assertFalse(gate(pending = true))
        assertFalse(gate(factor = true))
        assertFalse(gate(busy = true))
    }
}
