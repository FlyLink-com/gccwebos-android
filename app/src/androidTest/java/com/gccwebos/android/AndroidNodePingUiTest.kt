package com.gccwebos.android

import android.content.Context
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import com.gccwebos.android.core.ConnectionSelection
import com.gccwebos.android.portal.NodeLatencyObservation
import com.gccwebos.android.portal.NodeProbeTarget
import com.gccwebos.android.vpn.AndroidNodePingNetwork
import com.gccwebos.android.vpn.AndroidNodePingProbe
import com.gccwebos.android.vpn.AndroidPhysicalNetworkLease
import com.gccwebos.android.vpn.NodePingNetwork
import java.io.FileDescriptor
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** App-process ICMP and owned-resource checks only: no account, configuration or VPN service. */
class AndroidNodePingUiTest {
    private fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(Build.VERSION.SDK_INT >= 30) { "NODE_PING_TEST_REQUIRES_API_30" }
    }

    private fun loopback() = NodeProbeTarget(ConnectionSelection(), "127.0.0.1", "ss")

    @Test fun realApplicationSocketReceivesLoopbackEchoThroughPhysicalNetwork() = runBlocking {
        val result = withTimeout(6_000) { AndroidNodePingProbe(context()).measure(loopback()) }
        assertTrue("Real physical-network ICMP echo must succeed", result is NodeLatencyObservation.Success)
        val success = result as NodeLatencyObservation.Success
        assertTrue("RTT must be positive and bounded", success.milliseconds in 1..3_500)
    }

    @Test fun repeatedRealEchoClosesEveryOwnedSocketAndPhysicalLease() = runBlocking {
        val owned = CopyOnWriteArrayList<RecordingNetwork>()
        val probe = AndroidNodePingProbe(context()) { ctx -> RecordingNetwork(ctx).also(owned::add) }
        repeat(3) {
            val result = withTimeout(6_000) { probe.measure(loopback()) }
            assertTrue("Each real echo must succeed", result is NodeLatencyObservation.Success)
            assertTrue("Observation must retain the reserved network identity",
                (result as NodeLatencyObservation.Success).networkKey == owned.last().networkHandle)
            owned.last().assertClosed()
        }
        assertEquals(3, owned.size)
        owned.forEach { it.assertClosed() }
    }

    @Test fun cancellationAfterSocketBindingClosesSocketAndLeaseWithoutPublishingResult() = runBlocking {
        val bound = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val returned = AtomicBoolean(false)
        val owned = CopyOnWriteArrayList<RecordingNetwork>()
        val probe = AndroidNodePingProbe(context()) { ctx ->
            RecordingNetwork(ctx, afterBind = {
                bound.complete(Unit)
                check(release.await(2, TimeUnit.SECONDS)) { "NODE_PING_TEST_GATE_TIMEOUT" }
            }).also(owned::add)
        }
        val work = async {
            probe.measure(loopback())
            returned.set(true)
        }
        try {
            withTimeout(5_000) { bound.await() }
            work.cancel()
            release.countDown()
            withTimeout(2_000) { work.join() }
            assertTrue("Caller cancellation must propagate", work.isCancelled)
            assertFalse("Canceled work must not publish a result", returned.get())
            assertEquals(1, owned.size)
            owned.single().assertClosed()
        } finally {
            release.countDown()
            work.cancelAndJoin()
        }
    }

    @Test fun physicalNetworkRevokedAfterBindingFailsClosedAndReleasesResources() = runBlocking {
        val revoked = AtomicBoolean(false)
        val owned = CopyOnWriteArrayList<RecordingNetwork>()
        val probe = AndroidNodePingProbe(context()) { ctx ->
            RecordingNetwork(ctx, revoked = revoked, afterBind = { revoked.set(true) }).also(owned::add)
        }
        val result = withTimeout(6_000) { probe.measure(loopback()) }
        assertTrue("Revoked route cannot publish an RTT", result === NodeLatencyObservation.NetworkChanged)
        assertEquals(1, owned.size)
        owned.single().assertClosed()
    }

    @Test fun bindingFailureCannotFallBackToDefaultRouteAndClosesResources() = runBlocking {
        val owned = CopyOnWriteArrayList<RecordingNetwork>()
        val probe = AndroidNodePingProbe(context()) { ctx ->
            RecordingNetwork(ctx, rejectBinding = true).also(owned::add)
        }
        val result = withTimeout(6_000) { probe.measure(loopback()) }
        assertTrue("Failed physical binding must remain unavailable", result === NodeLatencyObservation.Unavailable)
        assertEquals(1, owned.size)
        owned.single().assertClosed()
    }

    private class RecordingNetwork(
        context: Context,
        private val revoked: AtomicBoolean = AtomicBoolean(false),
        private val rejectBinding: Boolean = false,
        private val afterBind: () -> Unit = {},
    ) : NodePingNetwork {
        private val held = AndroidPhysicalNetworkLease.reserve(context)
        private val delegate = AndroidNodePingNetwork(held)
        private val sockets = CopyOnWriteArrayList<FileDescriptor>()
        private val closes = AtomicInteger()
        override val networkHandle: Long get() = delegate.networkHandle
        override fun isCurrent(): Boolean = !revoked.get() && delegate.isCurrent()
        override fun readbackCurrent(): Boolean = !revoked.get() && delegate.readbackCurrent()
        override suspend fun resolveA(host: String): List<String> = delegate.resolveA(host)
        override fun bindSocket(socket: FileDescriptor) {
            sockets.add(socket)
            if (rejectBinding) throw IOException("SYNTHETIC_BIND_REJECTED")
            delegate.bindSocket(socket)
            afterBind()
        }
        override fun close() {
            closes.incrementAndGet()
            delegate.close()
        }
        fun assertClosed() {
            assertEquals("Exactly one socket must be owned", 1, sockets.size)
            assertEquals("Exactly one lease close must occur", 1, closes.get())
            assertFalse("Closed physical lease must stay invalid", held.isCurrent())
            assertFalse("Closed physical callback owner must stay invalid", held.readbackCurrent())
            for (socket in sockets) {
                assertFalse("Owned socket descriptor must be invalidated", socket.valid())
                try {
                    Os.fstat(socket)
                    fail("Owned socket must be closed at the OS boundary")
                } catch (error: ErrnoException) {
                    assertTrue("Closed socket must report a bad descriptor", error.errno == OsConstants.EBADF)
                }
            }
        }
    }
}
