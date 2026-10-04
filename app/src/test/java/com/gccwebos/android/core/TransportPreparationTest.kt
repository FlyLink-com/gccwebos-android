package com.gccwebos.android.core

import java.io.Closeable
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TransportPreparationTest {
    @Test fun cancelAfterSuccessfulTransportBeforeAcceptanceCleansAndAllowsFreshAdmission() = runTest {
        var freshReads = 0
        var activations = 0
        var transports = 0
        var allocated = false
        var stops = 0
        var accepts = 0
        var afterPreparation: (() -> Unit)? = null
        val gateway = object : LocalConfigGateway {
            override suspend fun prepare(intent: SelectionIntent): ConfigPreparation {
                freshReads++
                return ConfigPreparation.Ready(AuthorizedConfig("synthetic", byteArrayOf(7), activationGuard = { action ->
                    activations++; action()
                }))
            }
            override fun invalidatePending() = Unit
            override fun observeClear(onClear: () -> Unit) = Closeable { }
        }
        val tunnel = object : LifecycleTunnel, TunnelPreparation {
            override suspend fun prepareTransport(): Boolean {
                transports++; allocated = true
                afterPreparation?.invoke()
                return true
            }
            override fun accept(config: AuthorizedConfig, terminal: (TunnelTerminal) -> Unit): Boolean {
                accepts++
                return true
            }
            override suspend fun stop() { stops++; allocated = false }
        }
        val owner = ConnectionCoordinator(gateway, object : PermissionGateway {
            override suspend fun hasVpnPermission() = true
        }, tunnel, object : TrafficProbe {
            override suspend fun isUsable() = true
        }, backgroundScope)
        try {
            val first = owner.start()
            afterPreparation = first::cancel
            assertEquals(ConnectionState.Disconnected, first.await())
            assertEquals(1, transports)
            assertEquals(1, stops)
            assertFalse(allocated)
            assertEquals(0, activations)
            assertEquals(0, accepts)

            afterPreparation = null
            assertEquals(ConnectionState.Connected("synthetic"), owner.start().await())
            assertEquals(2, freshReads)
            assertEquals(2, transports)
            assertEquals(1, activations)
            assertEquals(1, accepts)
            assertTrue(allocated)
        } finally { owner.close() }
    }
}
