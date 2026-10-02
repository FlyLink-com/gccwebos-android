package com.gccwebos.android.core

import java.io.Closeable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PreparedMaterialLifecycleTest {
    private class PreparedGateway : LocalConfigGateway {
        var retained = false
        var reads = 0
        var clears = 0
        var rejectAfterWrite = false
        var rejectClear = false
        override suspend fun prepare(intent: SelectionIntent): ConfigPreparation {
            reads++; retained = true
            if (rejectAfterWrite) error("Synthetic write completed before preparation failure")
            return ConfigPreparation.Ready(AuthorizedConfig("synthetic", byteArrayOf(1), activationGuard = { it() }))
        }
        override fun invalidatePending() = Unit
        override fun observeClear(onClear: () -> Unit) = Closeable { }
        override fun clearPreparedMaterial() {
            clears++
            if (rejectClear) error("Synthetic deletion failure")
            retained = false
        }
    }
    private class UntouchedTunnel : LifecycleTunnel {
        var starts = 0
        override fun accept(config: AuthorizedConfig, terminal: (TunnelTerminal) -> Unit): Boolean {
            starts++; return true
        }
        override suspend fun stop() = Unit
    }
    @Test fun permissionDenialClearsPreparedMaterialWithoutStartingTunnel() = runTest {
        val gateway = PreparedGateway(); val tunnel = UntouchedTunnel()
        val owner = ConnectionCoordinator(gateway, object : PermissionGateway {
            override suspend fun hasVpnPermission() = false
        }, tunnel, object : TrafficProbe { override suspend fun isUsable() = true }, backgroundScope)
        try {
            assertEquals(ConnectionState.Failed(Failure.PERMISSION_DENIED), owner.start().await())
            assertFalse("Denied permission cannot retain prepared credentials", gateway.retained)
            assertEquals(0, tunnel.starts)
        } finally { owner.close() }
    }
    @Test fun cancelledPermissionClearsPreparedMaterialBeforeDisconnected() = runTest {
        val gateway = PreparedGateway(); val tunnel = UntouchedTunnel()
        val entered = CompletableDeferred<Unit>(); val consent = CompletableDeferred<Boolean>()
        val owner = ConnectionCoordinator(gateway, object : PermissionGateway {
            override suspend fun hasVpnPermission(): Boolean { entered.complete(Unit); return consent.await() }
        }, tunnel, object : TrafficProbe { override suspend fun isUsable() = true }, backgroundScope)
        try {
            val command = owner.start(); runCurrent(); entered.await()
            command.cancel()
            assertEquals(ConnectionState.Disconnected, command.await())
            assertFalse("Cancellation must delete even before transport exists", gateway.retained)
            assertEquals(0, tunnel.starts)
        } finally { owner.close() }
    }
    @Test fun partialPreparationFailureAlsoDeletesAndDeletionDebtBlocksFreshRead() = runTest {
        val gateway = PreparedGateway().apply { rejectAfterWrite = true; rejectClear = true }
        val owner = ConnectionCoordinator(gateway, object : PermissionGateway {
            override suspend fun hasVpnPermission() = true
        }, UntouchedTunnel(), object : TrafficProbe { override suspend fun isUsable() = true }, backgroundScope)
        try {
            assertEquals(ConnectionState.Failed(Failure.CLEANUP_FAILED), owner.start().await())
            assertTrue(gateway.retained)
            assertEquals(ConnectionState.Failed(Failure.CLEANUP_FAILED), owner.start().await())
            assertEquals("Unresolved deletion cannot admit fresh preparation", 1, gateway.reads)
            gateway.rejectClear = false
            assertEquals(ConnectionState.Disconnected, owner.stop().await())
            assertFalse(gateway.retained)
        } finally { owner.close() }
    }
}
