package com.gccwebos.android.core

import com.gccwebos.android.portal.LocalSubscriptionAuthority
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Actual authority/coordinator; only in-memory gateways. No Android, HTTP, TUN or file writes. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionOwnerHandoffTest {
    private class Fixture(private val scope: CoroutineScope) {
        val authority = LocalSubscriptionAuthority()
        var constructions = 0
        var preparations = 0
        var invalidations = 0
        var accepts = 0
        var stops = 0
        var allowPreparation = false
        var failClear = false
        var failInvalidation = false
        var holdStop = false
        val stopRelease = CompletableDeferred<Unit>()
        val terminalCallbacks = mutableListOf<(TunnelTerminal) -> Unit>()
        val gateway = object : LocalConfigGateway {
            override suspend fun prepare(intent: SelectionIntent): ConfigPreparation {
                preparations++
                check(allowPreparation) { "Unexpected preparation in idle handoff fixture" }
                return ConfigPreparation.Ready(AuthorizedConfig("synthetic-only", byteArrayOf(1), activationGuard = { it() }))
            }
            override fun invalidatePending() {
                invalidations++
                if (failInvalidation) error("Synthetic invalidation failure")
            }
            override fun observeClear(onClear: () -> Unit) = authority.observeClear(onClear)
            override fun clearPreparedMaterial() {
                if (failClear) error("Synthetic material cleanup failure")
            }
        }
        fun owner(): ConnectionCoordinator {
            constructions++
            return ConnectionCoordinator(gateway, object : PermissionGateway {
                override suspend fun hasVpnPermission() = true
            }, object : LifecycleTunnel {
                override fun accept(config: AuthorizedConfig, terminal: (TunnelTerminal) -> Unit): Boolean {
                    accepts++; terminalCallbacks += terminal; return true
                }
                override suspend fun stop() {
                    stops++
                    if (holdStop) stopRelease.await()
                }
            }, object : TrafficProbe { override suspend fun isUsable() = true }, scope,
                ConnectionTimeouts(cleanupMillis = 25))
        }
        fun handoff(wait: Long = 100, claim: suspend (() -> ConnectionCoordinator) -> ConnectionCoordinator =
            { authority.awaitConnectionOwner(it) }): ConnectionOwnerHandoff =
            ConnectionOwnerHandoff(scope, claim, ::owner, wait)
        fun assertLeaseHeld() {
            assertThrows(IllegalStateException::class.java) { authority.observeClear { } }
        }
    }

    @Test fun waitsForActualOldCleanupBeforeAdoptingAndNeverPreparesOnStartup() = runTest {
        val fixture = Fixture(backgroundScope)
        val old = fixture.owner()
        val next = fixture.handoff().also { it.start() }
        runCurrent()
        assertEquals(ConnectionOwnerAvailability.Waiting, next.state.value)
        assertEquals(1, fixture.constructions)
        old.beginClose()
        assertThrows(IllegalStateException::class.java) { old.start() }
        val oldClose = async { old.close() }
        runCurrent()
        assertEquals(ConnectionState.Disconnected, oldClose.await())
        assertTrue(next.state.value is ConnectionOwnerAvailability.Ready)
        assertEquals(2, fixture.constructions)
        assertEquals(0, fixture.preparations)
        assertEquals(0, fixture.accepts)
        val done = next.retire(); runCurrent(); done.await()
    }

    @Test fun retirementImmediatelyFencesCommandsAndEveryCallerSharesOneReceipt() = runTest {
        val fixture = Fixture(backgroundScope)
        val handoff = fixture.handoff().also { it.start() }
        runCurrent()
        val owner = (handoff.state.value as ConnectionOwnerAvailability.Ready).owner
        val retired = handoff.retire()
        assertSame(retired, handoff.retire())
        assertEquals(ConnectionOwnerAvailability.Destroyed, handoff.state.value)
        assertThrows(IllegalStateException::class.java) { owner.start() }
        assertThrows(IllegalStateException::class.java) { owner.stop() }
        assertEquals(1, fixture.invalidations)
        assertFalse(retired.isCompleted)
        runCurrent()
        assertEquals(ConnectionState.Disconnected, retired.await())
        assertEquals(0, fixture.preparations)
    }

    @Test fun timeoutCannotAcquireOrPublishAfterOldLeaseEventuallyReleases() = runTest {
        val fixture = Fixture(backgroundScope)
        val oldLease = fixture.authority.observeClear { }
        val handoff = fixture.handoff().also { it.start() }
        runCurrent(); advanceTimeBy(101); runCurrent()
        assertEquals(ConnectionOwnerAvailability.Unavailable, handoff.state.value)
        assertEquals(0, fixture.constructions)
        fixture.assertLeaseHeld()
        oldLease.close(); runCurrent()
        assertEquals(ConnectionOwnerAvailability.Unavailable, handoff.state.value)
        assertEquals(0, fixture.constructions)
        val retired = handoff.retire(); runCurrent(); assertNull(retired.await())
    }

    @Test fun retiringOneWaiterDoesNotCancelOldSignalOrSurvivingWaiter() = runTest {
        val fixture = Fixture(backgroundScope)
        val oldLease = fixture.authority.observeClear { }
        val dead = fixture.handoff().also { it.start() }
        val survivor = fixture.handoff().also { it.start() }
        runCurrent()
        val deadDone = dead.retire(); runCurrent(); assertNull(deadDone.await())
        fixture.assertLeaseHeld()
        assertEquals(ConnectionOwnerAvailability.Waiting, survivor.state.value)
        oldLease.close(); runCurrent()
        assertEquals(ConnectionOwnerAvailability.Destroyed, dead.state.value)
        assertTrue(survivor.state.value is ConnectionOwnerAvailability.Ready)
        assertEquals(1, fixture.constructions)
        val done = survivor.retire(); runCurrent(); done.await()
    }

    @Test fun competingWaitersAdoptSeriallyAndLateOldReleaseCannotClearNewOwner() = runTest {
        val fixture = Fixture(backgroundScope)
        val oldLease = fixture.authority.observeClear { }
        val first = fixture.handoff().also { it.start() }
        val second = fixture.handoff().also { it.start() }
        runCurrent(); oldLease.close(); runCurrent()
        val ready = listOf(first, second).single { it.state.value is ConnectionOwnerAvailability.Ready }
        val waiting = listOf(first, second).single { it.state.value == ConnectionOwnerAvailability.Waiting }
        assertEquals(1, fixture.constructions)
        oldLease.close(); fixture.assertLeaseHeld()
        val retired = ready.retire(); runCurrent(); retired.await()
        assertTrue(waiting.state.value is ConnectionOwnerAvailability.Ready)
        assertEquals(2, fixture.constructions)
        oldLease.close(); fixture.assertLeaseHeld()
        val done = waiting.retire(); runCurrent(); done.await()
    }

    @Test fun destructionAfterClaimBeforeAdoptionClosesOwnLeaseWithoutReadyPublication() = runTest {
        val fixture = Fixture(backgroundScope)
        val acquired = CompletableDeferred<Unit>()
        val returnClaim = CompletableDeferred<Unit>()
        val handoff = fixture.handoff(claim = { factory ->
            fixture.authority.awaitConnectionOwner(factory).also { acquired.complete(Unit); returnClaim.await() }
        }).also { it.start() }
        runCurrent(); acquired.await()
        assertEquals(ConnectionOwnerAvailability.Waiting, handoff.state.value)
        fixture.assertLeaseHeld()
        val done = handoff.retire(); runCurrent()
        assertEquals(ConnectionState.Disconnected, done.await())
        assertEquals(ConnectionOwnerAvailability.Destroyed, handoff.state.value)
        returnClaim.complete(Unit); runCurrent()
        assertEquals(ConnectionOwnerAvailability.Destroyed, handoff.state.value)
        val proofOfRelease = fixture.authority.observeClear { }; proofOfRelease.close()
        assertEquals(0, fixture.preparations)
    }

    @Test fun parentCancellationWhileWaitingCannotOwnOrReleaseExistingLease() = runTest {
        val parent = SupervisorJob()
        val fixture = Fixture(CoroutineScope(parent + StandardTestDispatcher(testScheduler)))
        val oldLease = fixture.authority.observeClear { }
        val handoff = fixture.handoff().also { it.start() }
        runCurrent(); parent.cancel(); runCurrent()
        assertEquals(ConnectionOwnerAvailability.Unavailable, handoff.state.value)
        fixture.assertLeaseHeld()
        oldLease.close(); runCurrent()
        assertEquals(ConnectionOwnerAvailability.Unavailable, handoff.state.value)
        assertEquals(0, fixture.constructions)
        val done = handoff.retire(); runCurrent(); assertNull(done.await())
    }

    @Test fun parentCancellationAfterClaimCleansUnadoptedOwnerWithoutReadyPublication() = runTest {
        val parent = SupervisorJob()
        val fixture = Fixture(CoroutineScope(parent + StandardTestDispatcher(testScheduler)))
        val acquired = CompletableDeferred<Unit>()
        val returnClaim = CompletableDeferred<Unit>()
        val handoff = fixture.handoff(claim = { factory ->
            fixture.authority.awaitConnectionOwner(factory).also { acquired.complete(Unit); returnClaim.await() }
        }).also { it.start() }
        runCurrent(); acquired.await()
        fixture.assertLeaseHeld()
        parent.cancel(); runCurrent()
        assertEquals(ConnectionOwnerAvailability.Unavailable, handoff.state.value)
        assertEquals(1, fixture.constructions)
        assertTrue("Unadopted owner was invalidated and closed", fixture.invalidations > 0)
        val proofOfRelease = fixture.authority.observeClear { }; proofOfRelease.close()
        returnClaim.complete(Unit); runCurrent()
        assertEquals(ConnectionOwnerAvailability.Unavailable, handoff.state.value)
        val done = handoff.retire(); runCurrent()
        assertEquals(ConnectionState.Disconnected, done.await())
        assertEquals(0, fixture.preparations)
    }

    @Test fun lateOldTerminalCannotAffectNewOwnerAndCurrentDeathStillCleans() = runTest {
        val fixture = Fixture(backgroundScope).apply { allowPreparation = true }
        val old = fixture.owner()
        val oldConnect = old.start(); runCurrent()
        assertTrue(oldConnect.await() is ConnectionState.Connected)
        val oldTerminal = fixture.terminalCallbacks.single()
        val next = fixture.handoff().also { it.start() }
        runCurrent()
        old.beginClose(); val oldDone = async { old.close() }; runCurrent(); oldDone.await()
        val ready = next.state.value as ConnectionOwnerAvailability.Ready
        val newConnect = ready.owner.start(); runCurrent()
        val connected = newConnect.await()
        assertTrue(connected is ConnectionState.Connected)
        val stopsBeforeLateCallback = fixture.stops
        oldTerminal(TunnelTerminal.ENGINE_DIED); oldTerminal(TunnelTerminal.PERMISSION_REVOKED)
        runCurrent()
        assertSame(ready, next.state.value)
        assertEquals(connected, ready.owner.state.value)
        assertEquals(stopsBeforeLateCallback, fixture.stops)
        fixture.assertLeaseHeld()
        fixture.terminalCallbacks.last()(TunnelTerminal.ENGINE_DIED); runCurrent()
        assertEquals(ConnectionState.Failed(Failure.ENGINE_DIED), ready.owner.state.value)
        assertTrue(fixture.stops > stopsBeforeLateCallback)
        val done = next.retire(); runCurrent(); done.await()
    }

    @Test fun failedMaterialCleanupRetainsLeaseAndReplacementTimesOutUnavailable() = runTest {
        val fixture = Fixture(backgroundScope).apply { allowPreparation = true; failClear = true }
        val old = fixture.owner()
        val connect = old.start(); runCurrent()
        assertTrue(connect.await() is ConnectionState.Connected)
        old.beginClose()
        val closed = async { old.close() }; runCurrent()
        assertEquals(ConnectionState.Failed(Failure.CLEANUP_FAILED), closed.await())
        fixture.assertLeaseHeld()
        val next = fixture.handoff().also { it.start() }
        runCurrent(); advanceTimeBy(101); runCurrent()
        assertEquals(ConnectionOwnerAvailability.Unavailable, next.state.value)
        assertEquals(1, fixture.constructions)
        val done = next.retire(); runCurrent(); assertNull(done.await())
    }

    @Test fun timedOutTunnelCleanupCannotNotifyOrAdmitReplacement() = runTest {
        val fixture = Fixture(backgroundScope).apply { allowPreparation = true; holdStop = true }
        val old = fixture.owner()
        val connect = old.start(); runCurrent()
        assertTrue(connect.await() is ConnectionState.Connected)
        old.beginClose()
        val closed = async { old.close() }
        runCurrent()
        repeat(3) { advanceTimeBy(26); runCurrent() }
        assertEquals(ConnectionState.Failed(Failure.CLEANUP_FAILED), closed.await())
        fixture.assertLeaseHeld()
        val next = fixture.handoff().also { it.start() }
        runCurrent(); advanceTimeBy(101); runCurrent()
        assertEquals(ConnectionOwnerAvailability.Unavailable, next.state.value)
        assertEquals(1, fixture.constructions)
        val done = next.retire(); runCurrent(); assertNull(done.await())
    }

    @Test fun failedSynchronousStopSubmissionCancelsOldWorkAndRemainsFailClosed() = runTest {
        val fixture = Fixture(backgroundScope).apply { allowPreparation = true }
        val handoff = fixture.handoff().also { it.start() }
        runCurrent()
        val owner = (handoff.state.value as ConnectionOwnerAvailability.Ready).owner
        val connect = owner.start(); runCurrent()
        assertTrue(connect.await() is ConnectionState.Connected)
        fixture.failInvalidation = true
        val retired = handoff.retire() // The deliberate synchronous failure cannot escape.
        assertThrows(IllegalStateException::class.java) { owner.start() }
        runCurrent()
        assertEquals(ConnectionState.Failed(Failure.CLEANUP_FAILED), retired.await())
        assertTrue("Old connected work was actually stopped", fixture.stops > 0)
        fixture.assertLeaseHeld()
        assertEquals(ConnectionOwnerAvailability.Destroyed, handoff.state.value)
    }
}
