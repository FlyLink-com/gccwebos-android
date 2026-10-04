package com.gccwebos.android.vpn

import org.junit.Assert.*
import org.junit.Test

class VpnTransportPinTest {
    @Test fun leasedCopyRequiresExactIdentityAndDeadline() {
        val pin = VpnTransportPin(17, 1_000)
        assertTrue(pin.permitsCopy(true, 17, 1_000, 500))
        assertFalse(pin.permitsCopy(false, 0, 0, 500))
        assertFalse(pin.permitsCopy(true, 18, 1_000, 500))
        assertFalse(pin.permitsCopy(true, 17, 1_001, 500))
    }
    @Test fun deadlineIsFiniteStrictAndNotRenewedAtWorkerStart() {
        val pin = VpnTransportPin(17, 1_000)
        assertTrue(pin.permitsStart(999))
        assertFalse(pin.permitsCopy(true, 17, 1_000, 1_000))
        assertFalse(pin.permitsStart(1_000))
        assertFalse(pin.permitsStart(-1))
        assertFalse(VpnTransportPin(17, Long.MAX_VALUE).permitsStart(0))
    }
    @Test fun legacyBindingOnlyAcceptsLegacyPayload() {
        val pin = VpnTransportPin(0, 0)
        assertTrue(pin.permitsCopy(false, 0, 0, 500))
        assertFalse(pin.permitsCopy(true, 0, 0, 500))
        assertFalse(pin.permitsCopy(true, 17, 1_000, 500))
        assertTrue(pin.permitsStart(1_000_000))
    }
    @Test fun invalidMixedBindingsCannotAuthorizeCopyOrStart() {
        for (pin in listOf(VpnTransportPin(0, 1_000), VpnTransportPin(17, 0), VpnTransportPin(-1, 1_000))) {
            assertFalse(pin.permitsCopy(false, 0, 0, 500))
            assertFalse(pin.permitsCopy(true, pin.networkHandle, pin.validUntilElapsedMillis, 500))
            assertFalse(pin.permitsStart(500))
        }
    }
}
