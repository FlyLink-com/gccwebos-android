package com.gccwebos.android.core

import java.io.Closeable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourceChangeFenceTest {
    private class Fixture(scope: kotlinx.coroutines.CoroutineScope) {
        var prepareGate: CompletableDeferred<Unit>? = null
        var stopGate: CompletableDeferred<Unit>? = null
        var failStop = false
        var clears = 0
        val coordinator = ConnectionCoordinator(object : LocalConfigGateway {
            override suspend fun prepare(intent: SelectionIntent): ConfigPreparation {
                prepareGate?.await()
                return ConfigPreparation.Ready(AuthorizedConfig("fixture", "synthetic".toByteArray(),
                    activationGuard = { it() }))
            }
            override fun invalidatePending() = Unit
            override fun clearPreparedMaterial() { clears++ }
            override fun observeClear(onClear: () -> Unit) = Closeable { }
        }, object : PermissionGateway {
            override suspend fun hasVpnPermission() = true
        }, object : LifecycleTunnel {
            override fun accept(config: AuthorizedConfig, terminal: (TunnelTerminal) -> Unit) = true
            override suspend fun stop() {
                stopGate?.await()
                check(!failStop)
            }
        }, object : TrafficProbe {
            override suspend fun isUsable() = true
        }, scope)
    }

    @Test fun onlyCleanIdleOwnerCanCommitAndCallbackCannotReenterCommands() = runTest {
        val f = Fixture(backgroundScope)
        var writes = 0
        f.coordinator.withIdleSourceChange {
            assertThrows(IllegalStateException::class.java) { f.coordinator.start() }
            assertThrows(IllegalStateException::class.java) { f.coordinator.withIdleSourceChange { writes++ } }
            writes++
        }
        assertEquals(1, writes)
        assertEquals(ConnectionState.Disconnected, f.coordinator.state.value)
        f.coordinator.close()
        assertThrows(IllegalStateException::class.java) { f.coordinator.withIdleSourceChange { writes++ } }
        assertEquals(1, writes)
    }

    @Test fun submittedPreparingConnectedAndCleaningOwnersCannotCommit() = runTest {
        val f = Fixture(backgroundScope)
        f.prepareGate = CompletableDeferred()
        val start = f.coordinator.start()
        fun rejected() = assertThrows(IllegalStateException::class.java) {
            f.coordinator.withIdleSourceChange { fail("Source must not change") }
        }
        rejected() // The synchronous command is fenced before its worker runs.
        runCurrent(); rejected()
        f.prepareGate!!.complete(Unit); runCurrent()
        assertTrue(start.await() is ConnectionState.Connected)
        rejected()
        f.stopGate = CompletableDeferred()
        val stop = f.coordinator.stop(); runCurrent(); rejected()
        f.stopGate!!.complete(Unit); runCurrent()
        assertEquals(ConnectionState.Disconnected, stop.await())
        var committed = false
        f.coordinator.withIdleSourceChange { committed = true }
        assertTrue(committed); assertTrue(f.clears > 0)
        f.coordinator.close()
    }

    @Test fun cleanupFailureRemainsBlockedUntilExplicitSuccessfulStop() = runTest {
        val f = Fixture(backgroundScope)
        f.coordinator.start(); runCurrent()
        f.failStop = true
        val stop = f.coordinator.stop(); runCurrent()
        assertEquals(ConnectionState.Failed(Failure.CLEANUP_FAILED), stop.await())
        assertThrows(IllegalStateException::class.java) { f.coordinator.withIdleSourceChange { fail() } }
        f.failStop = false
        val retry = f.coordinator.stop(); runCurrent()
        assertEquals(ConnectionState.Disconnected, retry.await())
        f.coordinator.withIdleSourceChange { }
        f.coordinator.close()
    }

    @Test fun failedCommitReleasesFenceWithoutStartingOrOwningResources() = runTest {
        val f = Fixture(backgroundScope)
        assertThrows(IllegalArgumentException::class.java) {
            f.coordinator.withIdleSourceChange { throw IllegalArgumentException("synthetic") }
        }
        f.coordinator.withIdleSourceChange { }
        assertEquals(ConnectionState.Disconnected, f.coordinator.state.value)
        f.coordinator.close()
    }
}
