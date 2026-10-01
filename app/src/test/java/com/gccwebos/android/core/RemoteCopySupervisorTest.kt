package com.gccwebos.android.core

import com.gccwebos.android.core.RemoteCopySupervisor.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RemoteCopySupervisorTest {
    private fun limits(sequence: Long = 8) = Limits(8, 32, 8, 3, 4, 2, sequence)
    private class Port : CopyPort {
        var copies = 0
        var stops = 0
        var forces = 0
        var frame: CopyFrame? = null
        var key: CopyTicket? = null
        var stop: StopTicket? = null
        var lease: Lease? = null
        var onCopy: (CopyFrame) -> Unit = { }
        var onStop: (BoundEndpoint, CopyTicket, StopTicket, Lease) -> Unit = { _, _, _, _ -> }
        var onForce: (BoundEndpoint, CopyTicket, StopTicket) -> Unit = { _, _, _ -> }
        override fun sendCopy(frame: CopyFrame) { copies++; this.frame = frame; onCopy(frame) }
        override fun requestStop(endpoint: BoundEndpoint, key: CopyTicket, ticket: StopTicket, copyLease: Lease) {
            stops++; this.key = key; stop = ticket; lease = copyLease; onStop(endpoint, key, ticket, copyLease)
        }
        override fun requestForce(endpoint: BoundEndpoint, key: CopyTicket, ticket: StopTicket) {
            forces++; onForce(endpoint, key, ticket)
        }
    }
    private class Fixture(val limits: Limits = Limits(8, 32, 8, 3, 4, 2, 8),
        val recovery: RecoveryQuarantine = RecoveryQuarantine(limits),
        val factory: FakeEndpointFactory = FakeEndpointFactory(limits)) {
        val endpoint = requireNotNull(factory.bind(factory.newOwner()))
        val port = Port()
        val host = RemoteCopySupervisor(limits, endpoint, port, recovery)
        init {
            port.onCopy = { frame -> reply(factory.copy(frame)) }
            port.onStop = { _, key, ticket, lease ->
                factory.discard(key, ticket, lease)?.let { host.released(it) }
                host.quiescent(factory.quiescent(key, ticket))
            }
        }
        fun reply(reply: ReceiverReply): Boolean = when (reply) {
            is ReceiverReply.Copied -> host.copied(reply.receipt)
            is ReceiverReply.Rejected -> host.rejected(reply.receipt)
        }
        fun reserve(bytes: ByteArray = byteArrayOf(11, 22, 33)): CopyTicket {
            val result = host.reserveCopy(1, 1, bytes.size, byteArrayOf(7), bytes)
            assertTrue(result is Reservation.Accepted)
            return (result as Reservation.Accepted).ticket
        }
        fun copy(): CopyTicket { val key = reserve(); assertEquals(DispatchAttempt.ATTEMPTED, host.dispatchCopy(key)); return key }
    }
    private fun rejected(value: Reservation, reason: Reason) {
        assertTrue(value is Reservation.Rejected)
        assertEquals(reason, (value as Reservation.Rejected).reason)
    }
    private fun occupied(f: Fixture) = rejected(f.host.reserveCopy(1, 1, 1, byteArrayOf(1), byteArrayOf(2)), Reason.OCCUPIED)
    private fun probe(host: RemoteCopySupervisor): Boolean {
        val done = CountDownLatch(1)
        Thread { host.snapshot(); done.countDown() }.apply { isDaemon = true; start() }
        return done.await(2, TimeUnit.SECONDS)
    }

    @Test fun g1ActualFrameBoundsAndExplicitLimitsRejectBeforeCopy() {
        for (bad in listOf({ Limits(0, 32, 8, 3, 4, 2, 8) }, { Limits(8, 0, 8, 3, 4, 2, 8) },
            { Limits(8, 32, 0, 3, 4, 2, 8) }, { Limits(8, 32, 8, 2, 4, 2, 8) },
            { Limits(8, 32, 8, 3, 0, 2, 8) }, { Limits(8, 32, 8, 3, 4, 0, 8) }, { Limits(8, 32, 8, 3, 4, 2, 0) })) {
            try { bad(); fail("Invalid limit accepted") } catch (expected: IllegalArgumentException) { assertEquals("Invalid copy limits", expected.message) }
        }
        val f = Fixture()
        val bytes = ByteArray(33) { 9 }
        for (value in listOf(f.host.reserveCopy(2, 1, 1, byteArrayOf(1), byteArrayOf(2)),
            f.host.reserveCopy(1, 1, 0, byteArrayOf(1), byteArrayOf()),
            f.host.reserveCopy(1, 1, 33, byteArrayOf(1), bytes),
            f.host.reserveCopy(1, 1, 1, byteArrayOf(), byteArrayOf(2)),
            f.host.reserveCopy(1, 1, 1, ByteArray(9), byteArrayOf(2)),
            f.host.reserveCopy(1, 1, 2, byteArrayOf(1), byteArrayOf(2)))) rejected(value, Reason.INVALID_FRAME)
        assertTrue(bytes.all { it == 9.toByte() })
        assertEquals(0, f.port.copies)
        assertEquals(0, f.factory.copyCount(f.endpoint))
        for (badFrame in listOf({ key: CopyTicket -> CopyFrame(key, 1, 1, 1, ByteArray(9), byteArrayOf(2)) },
            { key: CopyTicket -> CopyFrame(key, 2, 1, 1, byteArrayOf(1), byteArrayOf(2)) },
            { key: CopyTicket -> CopyFrame(key, 1, 99, 1, byteArrayOf(1), byteArrayOf(2)) },
            { key: CopyTicket -> CopyFrame(key, 1, 1, 2, byteArrayOf(1), byteArrayOf(2)) })) {
            val receiver = Fixture()
            val invalidKey = CopyTicket(receiver.endpoint, 1)
            assertTrue(receiver.factory.copy(badFrame(invalidKey)) is ReceiverReply.Rejected)
            assertEquals(0, receiver.factory.copyCount(receiver.endpoint))
            assertTrue(receiver.factory.copy(CopyFrame(invalidKey, 1, 1, 1, byteArrayOf(1), byteArrayOf(2))) is ReceiverReply.Rejected)
        }
        assertEquals(0, f.factory.copyCount(f.endpoint))
        val exact = ByteArray(32) { 5 }
        val key = (f.host.reserveCopy(1, 1, 32, ByteArray(8) { 7 }, exact) as Reservation.Accepted).ticket
        f.host.dispatchCopy(key)
        assertTrue(f.factory.materialEquals(key, exact))
        assertEquals(Phase.COPIED_NOT_ACTIVE, f.host.snapshot().phase)
    }

    @Test fun g2ReceiptFollowsCompleteIndependentCopyAndOnlyOwnedBytesAreErased() {
        val f = Fixture()
        val input = byteArrayOf(11, 22, 33)
        val header = byteArrayOf(7)
        val result = f.host.reserveCopy(1, 1, input.size, header, input) as Reservation.Accepted
        input[0] = 99; header[0] = 88
        assertEquals(Phase.RESERVED, f.host.snapshot().phase)
        f.host.dispatchCopy(result.ticket)
        val frame = requireNotNull(f.port.frame)
        assertNotSame(input, frame.payload)
        assertNotSame(header, frame.header)
        assertTrue(f.factory.materialEquals(result.ticket, byteArrayOf(11, 22, 33)))
        frame.payload.fill(0) // Receiver keeps a distinct complete copy even after sender-copy erasure.
        assertTrue(f.factory.materialEquals(result.ticket, byteArrayOf(11, 22, 33)))
        f.host.stop()
        assertFalse(f.factory.materialEquals(result.ticket, byteArrayOf(11, 22, 33)))
        assertArrayEquals(byteArrayOf(99, 22, 33), input)
        assertArrayEquals(byteArrayOf(88), header)
        assertEquals(Phase.CLEANUP_CONFIRMED, f.host.snapshot().phase)
    }

    @Test fun g3OnlyBoundFactoryReceiptsForExactOwnerEndpointAndRequestAreAccepted() {
        val f = Fixture()
        val other = Fixture()
        assertNull(f.factory.bind(OwnerIncarnation(Any())))
        val key = f.reserve()
        assertFalse(f.host.copied(CopyReceipt(f.endpoint, key, Any())))
        assertFalse(f.host.copied(CopyReceipt(other.endpoint, key, other.endpoint.seal)))
        assertFalse(f.host.copied(CopyReceipt(f.endpoint, CopyTicket(f.endpoint, 1), f.endpoint.seal)))
        assertEquals(Phase.RESERVED, f.host.snapshot().phase)
        f.host.dispatchCopy(key)
        assertEquals(Phase.COPIED_NOT_ACTIVE, f.host.snapshot().phase)
        other.copy()
        val foreign = other.factory.copy(requireNotNull(other.port.frame)) as ReceiverReply.Copied
        assertFalse(f.host.copied(foreign.receipt))
        assertEquals(Phase.COPIED_NOT_ACTIVE, f.host.snapshot().phase)
    }

    @Test fun g4DuplicateKeysDoNotRecopyAndPayloadConflictCannotReleaseOriginal() {
        val f = Fixture()
        val key = f.copy()
        val frame = requireNotNull(f.port.frame)
        val duplicate = f.factory.copy(frame) as ReceiverReply.Copied
        assertFalse(f.host.copied(duplicate.receipt))
        assertEquals(1, f.factory.copyCount(f.endpoint))
        val conflict = f.factory.copy(CopyFrame(key, 1, 1, 3, byteArrayOf(7), byteArrayOf(1, 2, 3))) as ReceiverReply.Rejected
        assertTrue(f.host.rejected(conflict.receipt))
        assertEquals(Phase.UNKNOWN, f.host.snapshot().phase)
        assertEquals(Reason.CONFLICT, f.host.snapshot().reason)
        assertFalse(f.host.copied(duplicate.receipt))
        occupied(f)
        val higher = CopyTicket(f.endpoint, 2)
        assertNull(f.factory.discard(higher, StopTicket(higher), Lease(higher, true)))
        assertTrue(f.factory.materialEquals(key, byteArrayOf(11, 22, 33)))
        f.host.stop()
        assertEquals(Phase.CLEANUP_CONFIRMED, f.host.snapshot().phase)
    }

    @Test fun g5CopyIsNeverActivationAndSequenceExhaustionCannotWrapAfterRebuild() {
        val f = Fixture(limits(1))
        for (op in listOf(2, 3, 99)) rejected(f.host.reserveCopy(1, op, 1, byteArrayOf(1), byteArrayOf(2)), Reason.INVALID_FRAME)
        f.copy()
        assertEquals(Phase.COPIED_NOT_ACTIVE, f.host.snapshot().phase)
        f.host.stop()
        rejected(f.host.reserveCopy(1, 1, 1, byteArrayOf(1), byteArrayOf(2)), Reason.SEQUENCE_EXHAUSTED)
        val rebuilt = RemoteCopySupervisor(f.limits, f.endpoint, Port(), f.recovery)
        rejected(rebuilt.reserveCopy(1, 1, 1, byteArrayOf(1), byteArrayOf(2)), Reason.SEQUENCE_EXHAUSTED)
        assertFalse(Phase.entries.any { it.name.contains("CONNECTED") || it.name.contains("RUNNING") })
        assertEquals(1, f.port.copies)
    }

    @Test fun g6LostReplyCancellationAndDispatchFailureRemainUnknownWithoutReplay() {
        for (mode in 0..3) {
            val f = Fixture()
            f.port.onCopy = { frame ->
                if (mode != 0) f.factory.copy(frame) // Actual receiver copy, with receipt deliberately not delivered.
                if (mode == 0 || mode == 1) throw IllegalStateException("synthetic-transaction-too-large-or-lost-reply")
                if (mode == 2) throw CancellationException("synthetic-cancel")
            }
            val key = f.reserve()
            assertEquals(DispatchAttempt.ATTEMPTED, f.host.dispatchCopy(key))
            assertEquals(Phase.UNKNOWN, f.host.snapshot().phase)
            occupied(f)
            assertEquals(DispatchAttempt.NOT_DISPATCHED, f.host.dispatchCopy(key))
            assertEquals(1, f.port.copies)
            assertEquals(if (mode == 0) 0 else 1, f.factory.copyCount(f.endpoint))
            f.host.stop()
            assertEquals(Phase.CLEANUP_CONFIRMED, f.host.snapshot().phase)
        }
    }

    @Test fun g7StopBeforeLateCopyAndLateReceiptAfterCopyNeverReviveScope() {
        for (copyFirst in listOf(false, true)) {
            val f = Fixture()
            var receiptAccepted = false
            var inside: Phase? = null
            f.port.onCopy = { frame ->
                val early = if (copyFirst) f.factory.copy(frame) else null
                f.host.stop()
                val late = early ?: f.factory.copy(frame)
                receiptAccepted = f.reply(late)
                inside = f.host.snapshot().phase
            }
            val key = f.reserve()
            f.host.dispatchCopy(key)
            assertFalse(receiptAccepted)
            assertEquals(Phase.STOPPING, inside)
            assertEquals(Phase.CLEANUP_CONFIRMED, f.host.snapshot().phase)
            assertEquals(if (copyFirst) 1 else 0, f.factory.copyCount(f.endpoint))
            assertEquals(1, f.port.stops)
        }
        val refused = Fixture()
        var lateRefused = false
        refused.port.onCopy = { frame ->
            val bad = CopyFrame(frame.ticket, 2, 1, frame.declaredLength, frame.header, frame.payload)
            refused.reply(refused.factory.copy(bad))
            lateRefused = refused.factory.copy(frame) is ReceiverReply.Rejected
        }
        refused.copy()
        assertTrue(lateRefused)
        assertEquals(0, refused.factory.copyCount(refused.endpoint))
        assertEquals(Phase.CLEANUP_CONFIRMED, refused.host.snapshot().phase)
    }

    @Test fun g8EarlyAcksInCopyStopAndForceRequireClaimStackDrain() {
        val copy = Fixture()
        var occupiedInsideCopy = false
        var borrowedIntact = false
        copy.port.onCopy = { frame ->
            copy.reply(copy.factory.copy(frame)); copy.host.stop()
            borrowedIntact = frame.payload.contentEquals(byteArrayOf(11, 22, 33)) && frame.header.contentEquals(byteArrayOf(7))
            occupiedInsideCopy = copy.host.reserveCopy(1, 1, 1, byteArrayOf(1), byteArrayOf(2)) is Reservation.Rejected
        }
        copy.copy()
        assertTrue(occupiedInsideCopy)
        assertTrue(borrowedIntact)
        assertTrue(requireNotNull(copy.port.frame).payload.all { it == 0.toByte() })
        assertEquals(Phase.CLEANUP_CONFIRMED, copy.host.snapshot().phase)
        val stop = Fixture()
        stop.copy()
        var stopPhase: Phase? = null
        var stopOccupied = false
        stop.port.onStop = { _, key, ticket, lease ->
            stop.host.released(requireNotNull(stop.factory.discard(key, ticket, lease)))
            stop.host.quiescent(stop.factory.quiescent(key, ticket))
            stopPhase = stop.host.snapshot().phase
            stopOccupied = stop.host.reserveCopy(1, 1, 1, byteArrayOf(1), byteArrayOf(2)) is Reservation.Rejected
        }
        stop.host.stop()
        assertEquals(Phase.STOPPING, stopPhase); assertTrue(stopOccupied)
        assertEquals(Phase.CLEANUP_CONFIRMED, stop.host.snapshot().phase)
        val force = Fixture()
        force.copy(); force.port.onStop = { _, _, _, _ -> }
        val ticket = requireNotNull(force.host.stop())
        var forcePhase: Phase? = null
        var forceOccupied = false
        force.port.onForce = { _, key, current ->
            force.host.released(requireNotNull(force.factory.discard(key, current, requireNotNull(force.port.lease))))
            force.host.quiescent(force.factory.quiescent(key, current))
            forcePhase = force.host.snapshot().phase
            forceOccupied = force.host.reserveCopy(1, 1, 1, byteArrayOf(1), byteArrayOf(2)) is Reservation.Rejected
        }
        assertTrue(force.host.timeout(ticket))
        assertEquals(Phase.CLEANUP_FAILED, forcePhase); assertTrue(forceOccupied)
        assertEquals(Phase.CLEANUP_CONFIRMED, force.host.snapshot().phase)

        val retiring = Fixture()
        var replacement: Fixture? = null
        var intactDuringRebuild = false
        var quarantinedBeforeReturn = false
        retiring.port.onCopy = { frame ->
            retiring.factory.copy(frame)
            val next = Fixture(retiring.limits, retiring.recovery, retiring.factory)
            replacement = next
            next.host.stop()
            intactDuringRebuild = frame.payload.contentEquals(byteArrayOf(11, 22, 33))
            quarantinedBeforeReturn = !next.host.reconcileRecovery()
        }
        retiring.copy()
        assertTrue(intactDuringRebuild)
        assertTrue(quarantinedBeforeReturn)
        assertTrue(requireNotNull(retiring.port.frame).payload.all { it == 0.toByte() })
        assertEquals(Phase.QUARANTINED, requireNotNull(replacement).host.snapshot().phase)
        assertTrue(requireNotNull(replacement).host.reconcileRecovery())
    }

    @Test fun g9EveryLeaseAndQuiescenceAreRequiredAndCopyProofRequiresActualWipe() {
        val f = Fixture()
        val key = f.copy()
        val a = requireNotNull(f.host.registerLease(key))
        val b = requireNotNull(f.host.registerLease(key))
        f.port.onStop = { _, _, _, _ -> }
        val ticket = requireNotNull(f.host.stop())
        val copyLease = requireNotNull(f.port.lease)
        assertNull(f.factory.released(key, copyLease, ticket))
        assertFalse(f.host.released(ReleaseProof(f.endpoint, key, copyLease, ticket, f.endpoint.seal, false)))
        assertTrue(f.factory.materialEquals(key, byteArrayOf(11, 22, 33)))
        assertTrue(f.host.released(requireNotNull(f.factory.discard(key, ticket, copyLease))))
        val releaseA = requireNotNull(f.factory.released(key, a, ticket))
        assertTrue(f.host.released(releaseA)); assertFalse(f.host.released(releaseA))
        assertFalse(f.host.quiescent(f.factory.quiescent(key, StopTicket(key))))
        assertTrue(f.host.quiescent(f.factory.quiescent(key, ticket)))
        assertNull(f.host.registerLease(key))
        occupied(f)
        assertTrue(f.host.released(requireNotNull(f.factory.released(key, b, ticket))))
        assertEquals(Phase.CLEANUP_CONFIRMED, f.host.snapshot().phase)
    }

    @Test fun g10EndpointDeathNeverReleasesOtherLeasesAndOldDeathCannotClearNewScope() {
        val f = Fixture()
        val key = f.copy()
        val localLease = requireNotNull(f.host.registerLease(key))
        val wrong = requireNotNull(f.factory.bind(f.factory.newOwner()))
        assertFalse(f.host.endpointGone(requireNotNull(f.factory.simulateExit(wrong))))
        val exit = requireNotNull(f.factory.simulateExit(f.endpoint))
        assertTrue(f.host.endpointGone(exit))
        assertEquals(Phase.CLEANUP_FAILED, f.host.snapshot().phase)
        f.port.onStop = { _, _, _, _ -> }
        val ticket = requireNotNull(f.host.stop())
        assertTrue(f.host.released(requireNotNull(f.factory.discard(key, ticket, requireNotNull(f.port.lease)))))
        assertEquals(Phase.CLEANUP_FAILED, f.host.snapshot().phase)
        assertTrue(f.host.released(requireNotNull(f.factory.released(key, localLease, ticket))))
        val next = Fixture(f.limits, f.recovery, f.factory)
        next.copy()
        assertFalse(next.host.endpointGone(exit))
        assertEquals(Phase.COPIED_NOT_ACTIVE, next.host.snapshot().phase)
    }

    @Test fun g11NewOwnerCannotAdoptUnknownAsIdleAndRequiresIndependentReconciliation() {
        val old = Fixture()
        val key = old.copy()
        val priorReply = old.factory.copy(requireNotNull(old.port.frame)) as ReceiverReply.Copied
        old.host.ownerLost()
        val next = Fixture(old.limits, old.recovery, old.factory)
        try {
            RemoteCopySupervisor(old.limits, old.endpoint, Port(), RecoveryQuarantine(old.limits))
            fail("same endpoint accepted a replacement empty recovery source")
        } catch (expected: IllegalArgumentException) { assertEquals("Recovery source mismatch", expected.message) }
        val freshEndpoint = requireNotNull(old.factory.bind(old.factory.newOwner()))
        try {
            RemoteCopySupervisor(old.limits, freshEndpoint, Port(), RecoveryQuarantine(old.limits))
            fail("same factory accepted an empty replacement recovery source")
        } catch (expected: IllegalArgumentException) { assertEquals("Recovery source mismatch", expected.message) }
        assertEquals(Phase.QUARANTINED, next.host.snapshot().phase)
        rejected(next.host.reserveCopy(1, 1, 1, byteArrayOf(1), byteArrayOf(2)), Reason.OWNER_LOST)
        assertFalse(next.host.reconcileRecovery())
        assertFalse(next.host.copied(priorReply.receipt))
        next.host.stop() // Exact prior endpoint/key/ticket, through the independent fake source.
        assertFalse(old.factory.materialEquals(key, byteArrayOf(11, 22, 33)))
        assertEquals(Phase.QUARANTINED, next.host.snapshot().phase)
        assertTrue(next.host.reconcileRecovery())
        assertEquals(Phase.CLEANUP_CONFIRMED, next.host.snapshot().phase)
        next.copy()
        assertFalse(next.host.copied(priorReply.receipt))
        rejected(old.host.reserveCopy(1, 1, 1, byteArrayOf(1), byteArrayOf(2)), Reason.OWNER_CLOSED)
        assertEquals(Phase.COPIED_NOT_ACTIVE, next.host.snapshot().phase)

        val abrupt = Fixture()
        abrupt.port.onCopy = { abrupt.factory.copy(it) } // Copy completed, reply withheld: UNKNOWN.
        val unknownKey = abrupt.copy()
        assertEquals(Phase.UNKNOWN, abrupt.host.snapshot().phase)
        val recovered = Fixture(abrupt.limits, abrupt.recovery, abrupt.factory)
        recovered.host.stop()
        assertFalse(abrupt.factory.materialEquals(unknownKey, byteArrayOf(11, 22, 33)))
        assertEquals(Phase.QUARANTINED, recovered.host.snapshot().phase)
        assertFalse(recovered.host.reconcileRecovery()) // Sender-owned frame still exists; no fake release can erase it.
        assertArrayEquals(byteArrayOf(11, 22, 33), requireNotNull(abrupt.port.frame).payload)
        abrupt.host.ownerLost() // Actual surviving old object erases its own buffer, not a self-reported bool.
        assertTrue(requireNotNull(abrupt.port.frame).payload.all { it == 0.toByte() })
        assertTrue(recovered.host.reconcileRecovery())

        val reused = Fixture()
        reused.copy(); reused.host.stop()
        val takeoverPort = Port()
        val takeover = RemoteCopySupervisor(reused.limits, reused.endpoint, takeoverPort, reused.recovery)
        takeoverPort.onCopy = { frame ->
            when (val reply = reused.factory.copy(frame)) {
                is ReceiverReply.Copied -> takeover.copied(reply.receipt)
                is ReceiverReply.Rejected -> takeover.rejected(reply.receipt)
            }
        }
        val fresh = (takeover.reserveCopy(1, 1, 1, byteArrayOf(7), byteArrayOf(9)) as Reservation.Accepted).ticket
        reused.host.ownerLost(); reused.host.ownerClose() // Stale object must not close the reused binding.
        takeover.dispatchCopy(fresh)
        assertEquals(Phase.COPIED_NOT_ACTIVE, takeover.snapshot().phase)
        assertEquals(2, reused.factory.copyCount(reused.endpoint))
    }

    @Test fun g12NoAckBudgetForcesOnlyExactScopeOnceAndNeverCreatesExitProof() {
        val f = Fixture()
        val key = f.copy()
        f.port.onStop = { _, _, _, _ -> }
        val ticket = requireNotNull(f.host.stop())
        assertFalse(f.host.timeout(StopTicket(key)))
        assertTrue(f.host.timeout(ticket)); assertFalse(f.host.timeout(ticket))
        assertEquals(1, f.port.forces)
        assertEquals(Phase.CLEANUP_FAILED, f.host.snapshot().phase)
        occupied(f)

        assertTrue(f.host.released(requireNotNull(f.factory.discard(key, ticket, requireNotNull(f.port.lease)))))
        assertEquals(Phase.CLEANUP_FAILED, f.host.snapshot().phase)
        assertTrue(f.host.quiescent(f.factory.quiescent(key, ticket)))
        assertEquals(Phase.CLEANUP_CONFIRMED, f.host.snapshot().phase)
        f.host.ownerClose()
        val sameOwnerRebuilt = RemoteCopySupervisor(f.limits, f.endpoint, Port(), f.recovery)
        rejected(sameOwnerRebuilt.reserveCopy(1, 1, 1, byteArrayOf(1), byteArrayOf(2)), Reason.OWNER_CLOSED)
    }

    @Test fun g13DetachAndThrowingObserversDoNotStopOwnerAndPortsRunOutsideLock() {
        val f = Fixture()
        var callbacks = 0
        val lockFree = mutableListOf<Boolean>()
        val observer = requireNotNull(f.host.observe { callbacks++; lockFree += probe(f.host); throw IllegalStateException("synthetic-secret") })
        assertNotNull(f.host.observe { })
        assertNull(f.host.observe { })
        f.port.onCopy = { frame -> lockFree += probe(f.host); f.reply(f.factory.copy(frame)) }
        val key = f.reserve()
        assertEquals(0, callbacks)
        f.host.dispatchCopy(key)
        assertTrue(callbacks > 0); assertTrue(lockFree.all { it })
        assertTrue(f.host.detach(observer)); assertFalse(f.host.detach(observer))
        assertEquals(0, f.port.stops)
        assertNotNull(f.host.observe { })
        f.port.onStop = { _, _, _, _ -> lockFree += probe(f.host); throw IllegalStateException("synthetic-secret") }
        val ticket = requireNotNull(f.host.stop())
        assertEquals(Phase.CLEANUP_FAILED, f.host.snapshot().phase)
        f.port.onForce = { _, _, _ -> lockFree += probe(f.host); throw IllegalStateException("synthetic-secret") }
        f.host.timeout(ticket)
        assertTrue(lockFree.all { it })
        assertEquals(Reason.TRANSPORT_UNKNOWN, f.host.snapshot().reason)
        occupied(f)

        val repeating = Fixture()
        var turns = 0
        repeating.host.observe {
            turns++
            if (turns < 16) repeating.host.observe { }?.let { repeating.host.detach(it) }
            repeating.host.dispatchObservers()
        }
        repeating.reserve()
        repeating.host.dispatchObservers()
        assertEquals(1, turns) // Reentrant notifications are left for a later explicit bounded pump.
        repeating.host.dispatchObservers()
        assertEquals(2, turns)
    }

    @Test fun g14SafeOutputsExposeNoSecretsDigestsOrNumericIncarnations() {
        val f = Fixture()
        val secret = "synthetic-account-token-secret"
        val key = f.reserve(secret.toByteArray())
        f.host.dispatchCopy(key)
        val frame = requireNotNull(f.port.frame)
        val receipt = f.factory.copy(frame) as ReceiverReply.Copied
        val extra = requireNotNull(f.host.registerLease(key))
        f.port.onStop = { _, _, _, _ -> }
        val ticket = requireNotNull(f.host.stop())
        val release = requireNotNull(f.factory.released(key, extra, ticket))
        val outputs = listOf(f.host, f.host.snapshot(), f.recovery, f.factory, f.endpoint, f.endpoint.owner,
            key, frame, receipt, receipt.receipt, extra, ticket, release, f.factory.quiescent(key, ticket),
            requireNotNull(f.factory.simulateExit(f.endpoint)), requireNotNull(f.host.observe { })).joinToString("|")
        assertFalse(outputs.contains(secret))
        assertFalse(outputs.contains("digest", true))
        assertFalse(outputs.contains("sequence", true))
        assertFalse(outputs.any { it.isDigit() })
        assertEquals(0, f.port.forces)
        assertEquals(1, f.port.copies)
    }
}
