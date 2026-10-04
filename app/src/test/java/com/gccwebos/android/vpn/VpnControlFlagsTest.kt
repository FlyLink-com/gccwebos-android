package com.gccwebos.android.vpn

import org.junit.Assert.*
import org.junit.Test

class VpnControlFlagsTest {
    @Test fun ordinaryRemoteCallAllowsPlatformAcceptFdsAndAuxiliaryFlags() {
        // libbinder adds TF_ACCEPT_FDS even when Java transact() was called with flags=0.
        for (flags in listOf(0x10, 0x12, 0x20, 0x30, 0x32)) {
            assertTrue("Synchronous platform flags $flags", VpnControl.isSynchronousCall(true, flags))
        }
    }

    @Test fun localSynchronousCallWithoutAuxiliaryFlagsIsAccepted() {
        assertTrue(VpnControl.isSynchronousCall(true, 0))
    }

    @Test fun oneWayWithOrWithoutPlatformFlagsIsRejected() {
        for (flags in listOf(0x01, 0x11, 0x13, 0x21, 0x31, 0x33)) {
            assertFalse("One-way flags $flags", VpnControl.isSynchronousCall(true, flags))
        }
    }

    @Test fun missingReplyIsRejectedEvenForOrdinaryPlatformFlags() {
        for (flags in listOf(0, 0x10, 0x12, 0x30, 0x32)) {
            assertFalse("Missing reply with flags $flags", VpnControl.isSynchronousCall(false, flags))
        }
    }
}
