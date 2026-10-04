package com.gccwebos.android.vpn

import kotlinx.coroutines.async
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VpnTransportReceiptTest {
    @Test fun cancelledSuccessfulBindingCannotConfirmCleanupBeforeLateReceiptAndDeath() = runTest {
        val receipt = VpnTransportReceipt<Any>()
        val remote = Any()
        receipt.beginStop()
        val cleanup = async {
            val owned = receipt.forCleanup(bound = true)
            receipt.dead.await()
            owned
        }
        runCurrent()
        assertFalse(cleanup.isCompleted)
        var deathLinked = false
        assertTrue(receipt.capture(remote) { deathLinked = true })
        assertTrue(deathLinked)
        assertFalse("Late receipt cannot restore activation", receipt.admit())
        runCurrent()
        assertFalse("A receipt alone is not remote death", cleanup.isCompleted)
        receipt.binderDied()
        runCurrent()
        assertSame(remote, cleanup.await())
    }

    @Test fun noReceiptRetainsBindingDebtRatherThanReturningAnUnownedCleanupResult() = runTest {
        val receipt = VpnTransportReceipt<Any>()
        receipt.beginStop()
        val cleanup = async { receipt.forCleanup(bound = true) }
        runCurrent()
        assertFalse("Successful bind still needs a death-capable receipt", cleanup.isCompleted)
        cleanup.cancel()
    }

    @Test fun neverBoundTransportNeedsNoRemoteReceipt() = runTest {
        val receipt = VpnTransportReceipt<Any>()
        receipt.beginStop()
        assertNull(receipt.forCleanup(bound = false))
    }

    @Test fun transportLossCannotStandInForDeathButLateReceiptCanDrainTheOwnedBinding() = runTest {
        val receipt = VpnTransportReceipt<Any>()
        val remote = Any()
        receipt.transportLost()
        receipt.beginStop()
        assertFalse(receipt.dead.isCompleted)
        assertTrue(receipt.capture(remote) {})
        assertSame(remote, receipt.forCleanup(bound = true))
        assertFalse(receipt.admit())
        assertFalse(receipt.dead.isCompleted)
    }

    @Test fun anotherReceiptCannotReplaceTheBoundIncarnationOrAuthorizeItsSuccessor() = runTest {
        val old = VpnTransportReceipt<Any>()
        val remote = Any()
        assertTrue(old.capture(remote) {})
        assertTrue(old.admit())
        old.beginStop()
        assertFalse(old.capture(Any()) { error("Foreign death witness must not be linked") })
        old.binderDied()
        assertFalse(old.admit())
        val fresh = VpnTransportReceipt<Any>()
        assertFalse(fresh.dead.isCompleted)
        assertTrue(fresh.capture(Any()) {})
        assertTrue(fresh.admit())
        assertTrue(fresh.isCurrent())
    }

    @Test fun emergencyStopPrecedesUnbindAndDeathMustFollowBeforeCleanupAcknowledgement() = runTest {
        val receipt = VpnTransportReceipt<Any>()
        val remote = Any()
        val events = mutableListOf<String>()
        assertTrue(receipt.capture(remote) {})
        receipt.beginStop()
        val cleanup = async {
            receipt.stopAndReleaseBinding(true, { assertSame(remote, it); events += "stop" },
                { events += "unbind" })
            events += "cleaned"
        }
        runCurrent()
        assertEquals(listOf("stop", "unbind"), events)
        assertFalse(cleanup.isCompleted)
        receipt.binderDied()
        cleanup.await()
        assertEquals(listOf("stop", "unbind", "cleaned"), events)
    }

    @Test fun unboundAfterStopStillOwesExactDeathWhenFirstCleanupTimesOut() = runTest {
        val receipt = VpnTransportReceipt<Any>()
        assertTrue(receipt.capture(Any()) {})
        receipt.beginStop()
        var bound = true
        val first = async {
            try {
                receipt.stopAndReleaseBinding(bound, {}, { bound = false })
                false
            } catch (_: TimeoutCancellationException) { true }
        }
        runCurrent()
        assertFalse(bound)
        advanceTimeBy(4_501)
        runCurrent()
        assertTrue(first.await())
        val retry = async { receipt.stopAndReleaseBinding(bound, {}, {}) }
        runCurrent()
        assertFalse("Removing AUTO_CREATE is not exact death", retry.isCompleted)
        receipt.binderDied()
        retry.await()
    }
}
