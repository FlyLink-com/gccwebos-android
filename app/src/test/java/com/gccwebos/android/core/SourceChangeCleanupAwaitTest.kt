package com.gccwebos.android.core

import java.io.Closeable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SourceChangeCleanupAwaitTest {
    private class Fixture(scope: kotlinx.coroutines.CoroutineScope) {
        val stopEntered = CompletableDeferred<Unit>()
        var stopGate: CompletableDeferred<Unit>? = null
        var failClear = false
        var cleared = false
        val coordinator = ConnectionCoordinator(object : LocalConfigGateway {
            override suspend fun prepare(intent: SelectionIntent) = ConfigPreparation.Ready(
                AuthorizedConfig("synthetic", "synthetic".toByteArray(), activationGuard = { it() }))
            override fun invalidatePending() = Unit
            override fun clearPreparedMaterial() { check(!failClear); cleared = true }
            override fun observeClear(onClear: () -> Unit) = Closeable { }
        }, object : PermissionGateway {
            override suspend fun hasVpnPermission() = true
        }, object : LifecycleTunnel {
            override fun accept(config: AuthorizedConfig, terminal: (TunnelTerminal) -> Unit) = true
            override suspend fun stop() { stopEntered.complete(Unit); stopGate?.await() }
        }, object : TrafficProbe { override suspend fun isUsable() = true }, scope)
    }
    @Test fun stopAwaitDoesNotReturnUntilTunnelAndPreparedMaterialAreBothClean() = runTest {
        val f = Fixture(backgroundScope); f.coordinator.start(); runCurrent()
        f.stopGate = CompletableDeferred()
        val cleanup = async { f.coordinator.stopAndAwaitClean() }
        f.stopEntered.await(); assertFalse(cleanup.isCompleted); assertFalse(f.cleared)
        assertThrows(IllegalStateException::class.java) { f.coordinator.withIdleSourceChange { fail() } }
        f.stopGate!!.complete(Unit); cleanup.await()
        assertTrue(f.cleared)
        f.coordinator.withIdleSourceChange { }
        f.coordinator.close()
    }
    @Test fun failedPreparedRemovalMakesAwaitFailAndSourceCommitRemainBlocked() = runTest {
        val f = Fixture(backgroundScope); f.coordinator.start(); runCurrent(); f.failClear = true
        try { f.coordinator.stopAndAwaitClean(); fail("Failed prepared removal reported idle") }
        catch (_: IllegalStateException) { }
        assertEquals(ConnectionState.Failed(Failure.CLEANUP_FAILED), f.coordinator.state.value)
        assertThrows(IllegalStateException::class.java) { f.coordinator.withIdleSourceChange { fail() } }
        f.failClear = false; f.coordinator.stopAndAwaitClean()
        assertTrue(f.cleared); f.coordinator.withIdleSourceChange { }
        f.coordinator.close()
    }
    @Test fun retiredOwnerCannotBeUsedAsACleanupReceiptForSourceChange() = runTest {
        val f = Fixture(backgroundScope); f.coordinator.close()
        try { f.coordinator.stopAndAwaitClean(); fail("Retired owner supplied cleanup receipt") }
        catch (_: IllegalStateException) { }
        assertThrows(IllegalStateException::class.java) { f.coordinator.withIdleSourceChange { fail() } }
    }
    @Test fun cancelledAwaiterDoesNotCancelTheServiceOwnedStop() = runTest {
        val f = Fixture(backgroundScope); f.coordinator.start(); runCurrent()
        f.stopGate = CompletableDeferred()
        val waiting = async { f.coordinator.stopAndAwaitClean() }
        f.stopEntered.await(); waiting.cancel(); waiting.join()
        assertThrows(IllegalStateException::class.java) { f.coordinator.withIdleSourceChange { fail() } }
        f.stopGate!!.complete(Unit); runCurrent()
        assertTrue(f.cleared)
        assertEquals(ConnectionState.Disconnected, f.coordinator.state.value)
        f.coordinator.withIdleSourceChange { }
        f.coordinator.close()
    }
    @Test fun supersedingStartCannotUseAnOlderStopAsAnIdleReceipt() = runTest {
        val f = Fixture(backgroundScope); f.coordinator.start(); runCurrent()
        f.stopGate = CompletableDeferred()
        val waiting = async { runCatching { f.coordinator.stopAndAwaitClean() } }
        f.stopEntered.await()
        val replacement = f.coordinator.start()
        f.stopGate!!.complete(Unit); runCurrent()
        assertTrue(waiting.await().exceptionOrNull() is IllegalStateException)
        assertTrue(replacement.await() is ConnectionState.Connected)
        assertThrows(IllegalStateException::class.java) { f.coordinator.withIdleSourceChange { fail() } }
        f.coordinator.close()
    }
}
