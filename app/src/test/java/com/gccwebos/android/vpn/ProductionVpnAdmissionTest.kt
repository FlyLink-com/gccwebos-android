package com.gccwebos.android.vpn

import org.junit.Assert.*
import org.junit.Test

class ProductionVpnAdmissionTest {
    @Test fun validatesBoundariesBeforeCopy() {
        for (bytes in listOf(byteArrayOf(), ByteArray(VpnControl.MAX_CONFIG_BYTES + 1),
                byteArrayOf(0xc3.toByte()), byteArrayOf(0))) {
            assertNull(ProductionVpnAdmission().copyAndArm(1, 0, bytes, 100, 50))
        }
        assertNull(ProductionVpnAdmission().copyAndArm(0, 0, byteArrayOf(1), 100, 50))
        assertNull(ProductionVpnAdmission().copyAndArm(1, -1, byteArrayOf(1), 100, 50))
        assertNull(ProductionVpnAdmission().copyAndArm(1, 100, byteArrayOf(1), 100, 50))
        assertNotNull(ProductionVpnAdmission().copyAndArm(1, 0, ByteArray(VpnControl.MAX_CONFIG_BYTES) { 32 }, 100, 50))
    }
    @Test fun admissionCopiesAndOnlyOneTakeAndGenerationAreAllowed() {
        val admission = ProductionVpnAdmission()
        val input = byteArrayOf(65, 66)
        val ticket = requireNotNull(admission.copyAndArm(7, 0, input, 100, 50))
        input.fill(67)
        assertArrayEquals(byteArrayOf(65, 66), admission.take(ticket, 101, 51))
        assertNull(admission.take(ticket, 101, 51))
        assertNull(admission.copyAndArm(8, 0, input, 101, 51))
        assertTrue(admission.active(ticket, 101, 51))
    }
    @Test fun stopBeforeWorkerTakeErasesPendingCopyAndBlocksRestart() {
        val admission = ProductionVpnAdmission()
        val input = byteArrayOf(65, 66)
        val ticket = requireNotNull(admission.copyAndArm(1, 0, input, 100, 50))
        admission.stop()
        assertNull(admission.take(ticket, 100, 50))
        assertFalse(admission.active(ticket, 100, 50))
        assertNull(admission.copyAndArm(2, 0, input, 100, 50))
        assertArrayEquals(byteArrayOf(65, 66), input)
    }
    @Test fun knownExpiryUsesBothWallAndElapsedClocks() {
        val admission = ProductionVpnAdmission()
        val ticket = requireNotNull(admission.copyAndArm(1, 200, byteArrayOf(65), 100, 50))
        assertTrue(admission.active(ticket, 199, 149))
        assertFalse(admission.active(ticket, 50, 150)) // Wall clock rollback cannot extend lifetime.
        assertNull(admission.take(ticket, 50, 150))
        assertFalse(admission.active(ticket, 50, 100)) // Expiry is terminal.
        val forward = ProductionVpnAdmission()
        val other = requireNotNull(forward.copyAndArm(1, 200, byteArrayOf(65), 100, 50))
        assertFalse(forward.active(other, 200, 100))
    }
    @Test fun foreignTicketCannotConsumeOrAuthorizeAnotherProcess() {
        val a = ProductionVpnAdmission()
        val b = ProductionVpnAdmission()
        val first = requireNotNull(a.copyAndArm(1, 0, byteArrayOf(65), 100, 50))
        val second = requireNotNull(b.copyAndArm(1, 0, byteArrayOf(66), 100, 50))
        assertNull(a.take(second, 100, 50))
        assertFalse(a.active(second, 100, 50))
        assertArrayEquals(byteArrayOf(65), a.take(first, 100, 50))
    }
    @Test fun expiredMissingAndUnboundedAdmissionDeadlinesCannotArm() {
        for (deadline in listOf(-1L, 0L, 49L, 50L, 1_551L, Long.MAX_VALUE)) {
            assertNull("Rejected deadline grants no copy authority", ProductionVpnAdmission().copyAndArm(
                1, 0, byteArrayOf(65), 100, 50, admissionDeadlineElapsedMillis = deadline))
        }
        assertNotNull(ProductionVpnAdmission().copyAndArm(1, 0, byteArrayOf(65), 100, 50,
            admissionDeadlineElapsedMillis = 1_550))
    }
    @Test fun capturedDeadlineIsNotRenewedAtCopyOrWorkerTake() {
        val admission = ProductionVpnAdmission()
        val ticket = requireNotNull(admission.copyAndArm(1, 0, byteArrayOf(65), 100, 70,
            admissionDeadlineElapsedMillis = 80))
        assertNull("Worker cannot take at the sender's captured deadline", admission.take(ticket, 110, 80))
        assertFalse("Expired pending claim is terminal", admission.active(ticket, 110, 80))
        assertNull(admission.copyAndArm(2, 0, byteArrayOf(66), 110, 80,
            admissionDeadlineElapsedMillis = 100))
    }
    @Test fun takeBeforeAdmissionDeadlineDoesNotExpireAlreadyRunningAuthority() {
        val admission = ProductionVpnAdmission()
        val ticket = requireNotNull(admission.copyAndArm(1, 200, byteArrayOf(65), 100, 50,
            admissionDeadlineElapsedMillis = 55))
        assertArrayEquals(byteArrayOf(65), admission.take(ticket, 104, 54))
        assertNull("A duplicate take cannot stop an already-running ticket", admission.take(ticket, 106, 56))
        assertTrue("Short admission budget does not stop a running VPN", admission.active(ticket, 106, 56))
        assertTrue(admission.active(ticket, 199, 149))
        assertFalse("Resource expiry still stops the running VPN", admission.active(ticket, 50, 150))
    }
    @Test fun foreignExpiredAdmissionTicketCannotEraseOurPendingMaterial() {
        val admission = ProductionVpnAdmission()
        val own = requireNotNull(admission.copyAndArm(1, 0, byteArrayOf(65), 100, 50,
            admissionDeadlineElapsedMillis = 100))
        val foreign = requireNotNull(ProductionVpnAdmission().copyAndArm(1, 0, byteArrayOf(66), 100, 50,
            admissionDeadlineElapsedMillis = 52))
        assertNull(admission.take(foreign, 103, 53))
        assertTrue(admission.active(own, 103, 53))
        assertArrayEquals(byteArrayOf(65), admission.take(own, 104, 54))
    }
    @Test fun admissionDeadlineBoundsDoNotOverflowNearElapsedClockLimit() {
        val admission = ProductionVpnAdmission()
        val ticket = requireNotNull(admission.copyAndArm(1, 0, byteArrayOf(65), 100, Long.MAX_VALUE - 2,
            admissionDeadlineElapsedMillis = Long.MAX_VALUE))
        assertNull(admission.take(ticket, 102, Long.MAX_VALUE))
        assertFalse(admission.active(ticket, 102, Long.MAX_VALUE))
        assertNull(ProductionVpnAdmission().copyAndArm(1, 0, byteArrayOf(65), 100, -1,
            admissionDeadlineElapsedMillis = 1))
    }
}
