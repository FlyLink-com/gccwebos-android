package com.gccwebos.android.core

import com.gccwebos.android.core.EngineAdmissionHost.Admission
import com.gccwebos.android.core.EngineAdmissionHost.Phase
import com.gccwebos.android.core.EngineAdmissionHost.Reason
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EngineAdmissionHostTest {
    private class FakeWorker : EngineAdmissionHost.Worker {
        var starts = 0
        var stops = 0
        var forces = 0
        lateinit var handle: EngineAdmissionHost.WorkerHandle
        var material: ByteArray? = null
        var ticket: EngineAdmissionHost.CleanupTicket? = null
        var onStart: (EngineAdmissionHost.WorkerHandle, ByteArray) -> Boolean = { _, _ -> true }
        var onStop: (EngineAdmissionHost.WorkerHandle, EngineAdmissionHost.CleanupTicket) -> Unit = { _, _ -> }
        var onForce: (EngineAdmissionHost.WorkerHandle, EngineAdmissionHost.CleanupTicket) -> Unit = { _, _ -> }
        override fun start(handle: EngineAdmissionHost.WorkerHandle, material: ByteArray): Boolean {
            starts++
            this.handle = handle
            this.material = material
            return onStart(handle, material)
        }
        override fun requestStop(handle: EngineAdmissionHost.WorkerHandle, ticket: EngineAdmissionHost.CleanupTicket) {
            stops++
            this.handle = handle
            this.ticket = ticket
            onStop(handle, ticket)
        }
        override fun forceTermination(handle: EngineAdmissionHost.WorkerHandle, ticket: EngineAdmissionHost.CleanupTicket) {
            forces++
            this.handle = handle
            this.ticket = ticket
            onForce(handle, ticket)
        }
    }

    private class Fixture {
        val worker = FakeWorker()
        val host = EngineAdmissionHost(64, worker)
        lateinit var admission: EngineAdmissionHost.AdmissionHandle
        fun admit(input: ByteArray = byteArrayOf(1, 2, 3)): EngineAdmissionHost.AdmissionHandle {
            val result = host.admit(input)
            assertTrue(result is Admission.Accepted)
            admission = (result as Admission.Accepted).handle
            return admission
        }
        fun starting() { admit(); assertTrue(host.start(admission)) }
        fun running() { starting(); assertTrue(host.started(worker.handle)) }
        fun lease() = requireNotNull(host.registerResource(worker.handle))
        fun stop() = requireNotNull(host.stop(admission))
    }

    private fun assertRejected(result: Admission, reason: Reason) {
        assertTrue(result is Admission.Rejected)
        assertEquals(reason, (result as Admission.Rejected).reason)
    }

    private fun otherThreadCanSnapshot(host: EngineAdmissionHost): Boolean {
        val done = CountDownLatch(1)
        Thread { host.snapshot(); done.countDown() }.apply { isDaemon = true; start() }
        return done.await(2, TimeUnit.SECONDS)
    }

    @Test fun explicitLimitAndInputValidationRejectBeforeAnyWorkerDispatch() {
        val worker = FakeWorker()
        for (limit in listOf(-1, 0, EngineAdmissionHost.MAX_EXPLICIT_CONFIG_BYTES + 1, Int.MAX_VALUE)) {
            try { EngineAdmissionHost(limit, worker); fail("invalid limit accepted") }
            catch (expected: IllegalArgumentException) { assertEquals("Invalid engine admission limit", expected.message) }
        }
        val host = EngineAdmissionHost(3, worker)
        val oversized = byteArrayOf(9, 8, 7, 6)
        assertRejected(host.admit(byteArrayOf()), Reason.EMPTY_CONFIG)
        assertRejected(host.admit(oversized), Reason.CONFIG_TOO_LARGE)
        assertArrayEquals(byteArrayOf(9, 8, 7, 6), oversized)
        assertEquals(Phase.IDLE, host.snapshot().phase)
        assertEquals(0, worker.starts)
        assertTrue(host.admit(byteArrayOf(1, 2, 3)) is Admission.Accepted)
    }

    @Test fun admissionQueuesAdvisoryEventsAndObserverCapacityIsBounded() {
        val f = Fixture()
        var admittedCallbacks = 0
        val handles = (1..EngineAdmissionHost.MAX_OBSERVERS).map {
            requireNotNull(f.host.observe { if (it.phase == Phase.ADMITTED) admittedCallbacks++ })
        }
        assertNull(f.host.observe { fail("over-capacity observer attached") })
        f.admit()
        assertEquals(0, admittedCallbacks) // No external callback inside the synchronous copy/return boundary.
        f.host.dispatchObservers()
        assertEquals(EngineAdmissionHost.MAX_OBSERVERS, admittedCallbacks)
        assertTrue(f.host.detachUiAwaiter(handles.first()))
        assertNotNull(f.host.observe { })
    }

    @Test fun acceptedBytesArePrivateAndErasureDoesNotProveResourceCleanup() {
        val f = Fixture()
        val caller = byteArrayOf(11, 22, 33)
        f.admit(caller)
        caller[0] = 99
        assertTrue(f.host.start(f.admission))
        assertNotSame(caller, f.worker.material)
        assertArrayEquals(byteArrayOf(11, 22, 33), f.worker.material)
        val lease = f.lease()
        val ticket = f.stop()
        assertArrayEquals(byteArrayOf(0, 0, 0), f.worker.material)
        assertArrayEquals(byteArrayOf(99, 22, 33), caller)
        assertEquals(Phase.STOPPING, f.host.snapshot().phase)
        assertRejected(f.host.admit(byteArrayOf(4)), Reason.OCCUPIED)
        assertTrue(f.host.workerTerminated(f.worker.handle, ticket))
        assertEquals(Phase.STOPPING, f.host.snapshot().phase)
        assertTrue(f.host.released(f.worker.handle, lease, ticket))
        assertEquals(Phase.CLEANUP_CONFIRMED, f.host.snapshot().phase)
    }

    @Test fun oneGenerationAndOneStartAreAcceptedOnlyOnce() {
        val f = Fixture()
        f.admit()
        assertRejected(f.host.admit(byteArrayOf(4)), Reason.OCCUPIED)
        assertTrue(f.host.start(f.admission))
        assertFalse(f.host.start(f.admission))
        assertEquals(1, f.worker.starts)
        assertTrue(f.host.started(f.worker.handle))
        assertFalse(f.host.started(f.worker.handle))
        assertEquals(Phase.ENGINE_RUNNING_UNVERIFIED, f.host.snapshot().phase)
        assertRejected(f.host.admit(byteArrayOf(4)), Reason.OCCUPIED)
    }

    @Test fun allocationIsPreRegisteredAndStopClosesTheResourceLedger() {
        val f = Fixture()
        var lease: EngineAdmissionHost.ResourceLease? = null
        var allocated = false
        var registrationAfterStop: EngineAdmissionHost.ResourceLease? = null
        var earlyAck = false
        var stillOccupied = false
        f.worker.onStart = { handle, _ ->
            lease = f.host.registerResource(handle)
            allocated = lease != null // Synthetic allocation happens only after registration returned.
            val ticket = f.stop()
            registrationAfterStop = f.host.registerResource(handle)
            f.host.released(handle, requireNotNull(lease), ticket)
            earlyAck = f.host.workerTerminated(handle, ticket)
            stillOccupied = f.host.admit(byteArrayOf(4)) is Admission.Rejected
            true
        }
        f.admit()
        assertFalse(f.host.start(f.admission))
        assertTrue(allocated)
        assertNull(registrationAfterStop)
        assertTrue(earlyAck)
        assertTrue(stillOccupied)
        assertEquals(Phase.CLEANUP_CONFIRMED, f.host.snapshot().phase)
        assertEquals(1, f.worker.stops)
    }

    @Test fun partialStartReturningFalseKeepsEveryRegisteredResourceOccupied() {
        val f = Fixture()
        val leases = mutableListOf<EngineAdmissionHost.ResourceLease>()
        f.worker.onStart = { handle, _ -> repeat(2) { leases += requireNotNull(f.host.registerResource(handle)) }; false }
        f.admit()
        assertFalse(f.host.start(f.admission))
        assertEquals(1, f.worker.stops)
        assertEquals(Reason.START_FAILED, f.host.snapshot().reason)
        val ticket = requireNotNull(f.worker.ticket)
        assertTrue(f.host.workerTerminated(f.worker.handle, ticket))
        assertTrue(f.host.released(f.worker.handle, leases[0], ticket))
        assertRejected(f.host.admit(byteArrayOf(4)), Reason.OCCUPIED)
        assertTrue(f.host.released(f.worker.handle, leases[1], ticket))
        assertEquals(Phase.CLEANUP_CONFIRMED, f.host.snapshot().phase)
    }

    @Test fun partialStartThrowingRetainsCleanupDebtWithoutLeakingExceptionOrCallerBytes() {
        val f = Fixture()
        val secret = "synthetic-secret-account-token"
        val caller = secret.toByteArray()
        var lease: EngineAdmissionHost.ResourceLease? = null
        f.worker.onStart = { handle, _ -> lease = f.host.registerResource(handle); throw IllegalStateException(secret) }
        f.admit(caller)
        assertFalse(f.host.start(f.admission))
        assertArrayEquals(secret.toByteArray(), caller)
        assertTrue(requireNotNull(f.worker.material).all { it == 0.toByte() })
        assertEquals(Phase.STOPPING, f.host.snapshot().phase)
        assertFalse(f.host.snapshot().toString().contains(secret))
        val ticket = requireNotNull(f.worker.ticket)
        assertTrue(f.host.workerTerminated(f.worker.handle, ticket))
        assertEquals(Phase.STOPPING, f.host.snapshot().phase)
        assertTrue(f.host.released(f.worker.handle, requireNotNull(lease), ticket))
        assertEquals(Phase.CLEANUP_CONFIRMED, f.host.snapshot().phase)
    }

    @Test fun terminalWithMissingLeaseCannotConfirmCleanup() {
        val f = Fixture()
        f.running()
        val leases = List(3) { f.lease() }
        val ticket = f.stop()
        assertTrue(f.host.workerTerminated(f.worker.handle, ticket))
        leases.take(2).forEach { assertTrue(f.host.released(f.worker.handle, it, ticket)) }
        assertEquals(Phase.STOPPING, f.host.snapshot().phase)
        assertRejected(f.host.admit(byteArrayOf(4)), Reason.OCCUPIED)
        assertTrue(f.host.released(f.worker.handle, leases.last(), ticket))
        assertEquals(Phase.CLEANUP_CONFIRMED, f.host.snapshot().phase)
    }

    @Test fun allLeasesReleasedWithoutWorkerTerminationCannotConfirmCleanup() {
        val f = Fixture()
        f.running()
        val leases = List(2) { f.lease() }
        val ticket = f.stop()
        leases.forEach { assertTrue(f.host.released(f.worker.handle, it, ticket)) }
        assertEquals(Phase.STOPPING, f.host.snapshot().phase)
        assertRejected(f.host.admit(byteArrayOf(4)), Reason.OCCUPIED)
        assertTrue(f.host.workerTerminated(f.worker.handle, ticket))
        assertEquals(Phase.CLEANUP_CONFIRMED, f.host.snapshot().phase)
    }

    @Test fun crossHostAndStaleGenerationHandlesCannotReleaseNewResources() {
        val a = Fixture()
        val b = Fixture()
        a.running(); b.running()
        val oldWorker = a.worker.handle
        val oldLease = a.lease()
        val oldAdmission = a.admission
        val oldTicket = a.stop()
        val oldBudget = requireNotNull(a.host.cleanupTimeoutTicket(oldTicket))
        val foreignLease = b.lease()
        val foreignTicket = b.stop()
        assertFalse(a.host.start(b.admission))
        assertNull(a.host.stop(b.admission))
        assertFalse(a.host.workerTerminated(b.worker.handle, oldTicket))
        assertFalse(a.host.workerTerminated(oldWorker, foreignTicket))
        assertFalse(a.host.released(oldWorker, foreignLease, oldTicket))
        assertFalse(a.host.timeout(requireNotNull(b.host.cleanupTimeoutTicket(foreignTicket))))
        assertTrue(a.host.released(oldWorker, oldLease, oldTicket))
        assertTrue(a.host.workerTerminated(oldWorker, oldTicket))
        a.running()
        val currentLease = a.lease()
        val currentTicket = a.stop()
        assertNull(a.host.stop(oldAdmission))
        assertFalse(a.host.timeout(oldBudget))
        assertFalse(a.host.workerTerminated(oldWorker, oldTicket))
        assertFalse(a.host.released(a.worker.handle, oldLease, currentTicket))
        assertTrue(a.host.released(a.worker.handle, currentLease, currentTicket))
        assertTrue(a.host.workerTerminated(a.worker.handle, currentTicket))
        assertEquals(Phase.CLEANUP_CONFIRMED, a.host.snapshot().phase)
        assertEquals(Phase.STOPPING, b.host.snapshot().phase)
    }

    @Test fun duplicateAndUnregisteredAcknowledgementsAreIgnored() {
        val f = Fixture()
        f.running()
        val lease = f.lease()
        val ticket = f.stop()
        assertFalse(f.host.released(f.worker.handle, EngineAdmissionHost.ResourceLease(Any()), ticket))
        assertTrue(f.host.released(f.worker.handle, lease, ticket))
        assertFalse(f.host.released(f.worker.handle, lease, ticket))
        assertEquals(Phase.STOPPING, f.host.snapshot().phase)
        assertTrue(f.host.workerTerminated(f.worker.handle, ticket))
        assertFalse(f.host.workerTerminated(f.worker.handle, ticket))
        assertFalse(f.host.released(f.worker.handle, lease, ticket))
        assertEquals(Phase.CLEANUP_CONFIRMED, f.host.snapshot().phase)
    }

    @Test fun lateStartedCannotUndoStopOrPublishIntoTheNextGeneration() {
        val f = Fixture()
        f.starting()
        val oldWorker = f.worker.handle
        val ticket = f.stop()
        assertFalse(f.host.started(oldWorker))
        assertTrue(f.host.workerTerminated(oldWorker, ticket))
        f.starting()
        assertFalse(f.host.started(oldWorker))
        assertEquals(Phase.STARTING, f.host.snapshot().phase)
        assertTrue(f.host.started(f.worker.handle))
        assertEquals(Phase.ENGINE_RUNNING_UNVERIFIED, f.host.snapshot().phase)
    }

    @Test fun ownerClosureIsPermanentEvenAfterTimeoutAndValidCleanup() {
        val f = Fixture()
        f.running()
        val lease = f.lease()
        val ticket = requireNotNull(f.host.ownerClose())
        assertTrue(f.host.timeout(requireNotNull(f.host.cleanupTimeoutTicket(ticket))))
        assertEquals(Phase.CLEANUP_FAILED, f.host.snapshot().phase)
        assertTrue(f.host.released(f.worker.handle, lease, ticket))
        assertTrue(f.host.workerTerminated(f.worker.handle, ticket))
        assertEquals(Phase.CLOSED, f.host.snapshot().phase)
        assertRejected(f.host.admit(byteArrayOf(4)), Reason.OWNER_CLOSED)
        assertNull(f.host.ownerClose())
        assertEquals(1, f.worker.stops)
        val idle = Fixture()
        assertNull(idle.host.ownerClose())
        assertRejected(idle.host.admit(byteArrayOf(4)), Reason.OWNER_CLOSED)
    }

    @Test fun detachingUiAwaitersNeverStopsTheServiceOwnedWorker() {
        val f = Fixture()
        val phases = mutableListOf<Phase>()
        val waiter = requireNotNull(f.host.observe { phases += it.phase })
        f.running()
        assertTrue(f.host.detachUiAwaiter(waiter))
        assertFalse(f.host.detachUiAwaiter(waiter))
        val foreign = requireNotNull(Fixture().host.observe { })
        assertFalse(f.host.detachUiAwaiter(foreign))
        val before = phases.toList()
        assertEquals(0, f.worker.stops)
        f.stop()
        assertEquals(before, phases)
        assertEquals(1, f.worker.stops)
    }

    @Test fun startupBudgetRequestsForceOnceWithoutProvingCleanup() {
        val f = Fixture()
        f.starting()
        val budget = requireNotNull(f.host.startupTimeoutTicket(f.admission))
        assertTrue(f.host.timeout(budget))
        assertFalse(f.host.timeout(budget))
        assertEquals(1, f.worker.stops)
        assertEquals(1, f.worker.forces)
        assertEquals(Phase.CLEANUP_FAILED, f.host.snapshot().phase)
        assertEquals(Reason.STARTUP_TIMEOUT, f.host.snapshot().reason)
        assertRejected(f.host.admit(byteArrayOf(4)), Reason.OCCUPIED)
        assertTrue(f.host.workerTerminated(f.worker.handle, requireNotNull(f.worker.ticket)))
        assertEquals(Phase.CLEANUP_CONFIRMED, f.host.snapshot().phase)

        val inline = Fixture()
        inline.starting()
        val lease = inline.lease()
        var forceTerminalAccepted = false
        var phaseInsideForce: Phase? = null
        var occupiedInsideForce = false
        inline.worker.onForce = { handle, ticket ->
            inline.host.released(handle, lease, ticket)
            forceTerminalAccepted = inline.host.workerTerminated(handle, ticket)
            phaseInsideForce = inline.host.snapshot().phase
            occupiedInsideForce = inline.host.admit(byteArrayOf(4)) is Admission.Rejected
        }
        assertTrue(inline.host.timeout(requireNotNull(inline.host.startupTimeoutTicket(inline.admission))))
        assertTrue(forceTerminalAccepted)
        assertEquals(Phase.CLEANUP_FAILED, phaseInsideForce)
        assertTrue(occupiedInsideForce)
        assertEquals(Phase.CLEANUP_CONFIRMED, inline.host.snapshot().phase)
        assertEquals(1, inline.worker.forces)
        assertTrue(inline.host.admit(byteArrayOf(4)) is Admission.Accepted)
    }

    @Test fun cleanupTimeoutKeepsLedgerOccupiedUntilCurrentLogicalAcksArrive() {
        val f = Fixture()
        f.running()
        val lease = f.lease()
        val ticket = f.stop()
        val budget = requireNotNull(f.host.cleanupTimeoutTicket(ticket))
        assertTrue(f.host.timeout(budget))
        assertFalse(f.host.timeout(budget))
        assertEquals(1, f.worker.forces)
        assertEquals(Phase.CLEANUP_FAILED, f.host.snapshot().phase)
        assertRejected(f.host.admit(byteArrayOf(4)), Reason.OCCUPIED)
        assertTrue(f.host.released(f.worker.handle, lease, ticket))
        assertEquals(Phase.CLEANUP_FAILED, f.host.snapshot().phase)
        assertTrue(f.host.workerTerminated(f.worker.handle, ticket))
        assertEquals(Phase.CLEANUP_CONFIRMED, f.host.snapshot().phase)
        assertTrue(f.host.admit(byteArrayOf(4)) is Admission.Accepted)
    }

    @Test fun throwingCleanupBoundariesStayOccupiedAndAllPublicOutputsAreSecretFree() {
        val f = Fixture()
        val secret = "synthetic-account-config-credential"
        val accepted = f.host.admit(secret.toByteArray()) as Admission.Accepted
        f.admission = accepted.handle
        assertTrue(f.host.start(f.admission))
        val lease = f.lease()
        val waiter = requireNotNull(f.host.observe { throw IllegalStateException(secret) })
        f.worker.onStop = { _, _ -> throw IllegalStateException(secret) }
        val ticket = f.stop()
        assertEquals(Reason.STOP_REQUEST_FAILED, f.host.snapshot().reason)
        val budget = requireNotNull(f.host.cleanupTimeoutTicket(ticket))
        f.worker.onForce = { _, _ -> throw IllegalStateException(secret) }
        assertTrue(f.host.timeout(budget))
        assertFalse(f.host.timeout(budget))
        assertEquals(Reason.FORCE_REQUEST_FAILED, f.host.snapshot().reason)
        val outputs = listOf(f.host, f.host.snapshot(), accepted, accepted.handle, f.worker.handle,
            lease, ticket, budget, waiter, f.host.admit(byteArrayOf(4))).joinToString("|")
        assertFalse(outputs.contains(secret))
        assertFalse(outputs.contains("epoch", ignoreCase = true))
        assertFalse(outputs.any { it.isDigit() })
        assertEquals(Phase.CLEANUP_FAILED, f.host.snapshot().phase)
        assertTrue(f.host.released(f.worker.handle, lease, ticket))
        assertTrue(f.host.workerTerminated(f.worker.handle, ticket))
        assertEquals(Phase.CLEANUP_CONFIRMED, f.host.snapshot().phase)
    }

    @Test fun workerAndObserverCallbacksRunOutsideTheHostMonitor() {
        val f = Fixture()
        val lockFree = mutableListOf<Boolean>()
        f.host.observe { lockFree += otherThreadCanSnapshot(f.host) }
        f.worker.onStart = { _, _ -> lockFree += otherThreadCanSnapshot(f.host); true }
        f.worker.onStop = { _, _ -> lockFree += otherThreadCanSnapshot(f.host) }
        f.worker.onForce = { _, _ -> lockFree += otherThreadCanSnapshot(f.host) }
        f.starting()
        assertTrue(f.host.timeout(requireNotNull(f.host.startupTimeoutTicket(f.admission))))
        assertTrue(lockFree.size >= 6)
        assertTrue(lockFree.all { it })
        assertTrue(f.host.workerTerminated(f.worker.handle, requireNotNull(f.worker.ticket)))
        assertEquals(Phase.CLEANUP_CONFIRMED, f.host.snapshot().phase)
    }

    @Test fun stopBeforeWorkerDispatchUsesItsOnlyTerminalAckAndCompletesAfterClaimDrains() {
        val f = Fixture()
        var terminalAccepted = false
        var occupiedInsideStop = false
        var phaseInsideStop: Phase? = null
        f.worker.onStop = { handle, ticket ->
            terminalAccepted = f.host.workerTerminated(handle, ticket)
            occupiedInsideStop = f.host.admit(byteArrayOf(4)) is Admission.Rejected
            phaseInsideStop = f.host.snapshot().phase
        }
        val observed = mutableListOf<Phase>()
        f.host.observe {
            observed += it.phase
            if (it.phase == Phase.STARTING) f.host.stop(f.admission)
        }
        f.admit()
        assertFalse(f.host.start(f.admission))
        assertEquals(0, f.worker.starts)
        assertEquals(1, f.worker.stops)
        assertTrue(terminalAccepted)
        assertTrue(occupiedInsideStop)
        assertEquals(Phase.STOPPING, phaseInsideStop)
        assertEquals(Phase.CLEANUP_CONFIRMED, f.host.snapshot().phase)
        assertFalse(observed.contains(Phase.ENGINE_RUNNING_UNVERIFIED))
        assertTrue(f.host.admit(byteArrayOf(4)) is Admission.Accepted) // No second artificial terminal ACK.
    }

    @Test fun reentrantStopAcksCannotReopenEarlyAndFailedStartAutomaticallyReconfirms() {
        for (throwing in listOf(false, true)) {
            val f = Fixture()
            var lease: EngineAdmissionHost.ResourceLease? = null
            val phasesInsideStop = mutableListOf<Phase>()
            val admissionInsideStop = mutableListOf<Admission>()
            val terminalResults = mutableListOf<Boolean>()
            f.worker.onStop = { handle, ticket ->
                lease?.let { f.host.released(handle, it, ticket) }
                terminalResults += f.host.workerTerminated(handle, ticket)
                phasesInsideStop += f.host.snapshot().phase
                admissionInsideStop += f.host.admit(byteArrayOf(4))
            }
            f.worker.onStart = { handle, _ ->
                lease = f.host.registerResource(handle)
                f.host.stop(f.admission)
                if (throwing) throw IllegalStateException("synthetic-start-failure") else false
            }
            f.admit()
            assertFalse(f.host.start(f.admission))
            assertEquals(2, f.worker.stops) // Same ticket, one reconfirmation after the failed start boundary.
            assertEquals(listOf(true, true), terminalResults)
            assertTrue(phasesInsideStop.all { it == Phase.STOPPING })
            admissionInsideStop.forEach { assertRejected(it, Reason.OCCUPIED) }
            assertEquals(Phase.CLEANUP_CONFIRMED, f.host.snapshot().phase)
            assertTrue(f.host.admit(byteArrayOf(4)) is Admission.Accepted)
        }
        val ordinary = Fixture()
        ordinary.running()
        val lease = ordinary.lease()
        var occupiedInsideStop = false
        ordinary.worker.onStop = { handle, ticket ->
            ordinary.host.released(handle, lease, ticket)
            ordinary.host.workerTerminated(handle, ticket)
            occupiedInsideStop = ordinary.host.admit(byteArrayOf(4)) is Admission.Rejected
        }
        ordinary.stop()
        assertTrue(occupiedInsideStop)
        assertEquals(Phase.CLEANUP_CONFIRMED, ordinary.host.snapshot().phase)
    }
}
