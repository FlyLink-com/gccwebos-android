package com.gccwebos.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.gccwebos.android.core.ConnectionSelection
import com.gccwebos.android.portal.NodeLatencyObservation
import com.gccwebos.android.portal.NodeProbeTarget
import com.gccwebos.android.portal.NodeProbeTransport
import com.gccwebos.android.vpn.AndroidNodeLatencyProbe
import com.gccwebos.android.vpn.AndroidNodePingNetwork
import com.gccwebos.android.vpn.AndroidPhysicalNetworkLease
import com.gccwebos.android.vpn.AndroidPhysicalNetworkMonitor
import com.gccwebos.android.vpn.NodePingNetwork
import java.io.FileDescriptor
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName

/** App-process, owned loopback servers only; never account/configuration/Internet/VPN activity. */
class AndroidNodeLatencyProbeTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val testName = TestName()
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(Build.VERSION.SDK_INT >= 30) { "TRANSPORT_PROBE_REQUIRES_API30" }
    }

    @Before fun foregroundEmptyFixtureAndCheckPhysicalRoute() {
        // Launch only the Compose fixture Activity, never the portal or its account/VPN work.
        compose.setContent { }
        compose.waitForIdle()
        // Invalid input must be rejected even when no physical route is available.
        if (testName.methodName == "invalidPortNeverReservesNetworkOrCreatesSocket") return
        val connectivity = context().getSystemService(ConnectivityManager::class.java)
        val networks = connectivity.allNetworks
        val physicalCount = networks.count {
            AndroidPhysicalNetworkMonitor.physical(connectivity.getNetworkCapabilities(it))
        }
        val activeVpn = connectivity.activeNetwork?.let {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } == true
        val selected = AndroidPhysicalNetworkMonitor.select(connectivity) != null
        assertTrue("TRANSPORT_PROBE_PHYSICAL_ROUTE total=${networks.size} physical=$physicalCount activeVpn=$activeVpn selected=$selected",
            selected)
    }
    private fun target(port: Int, transport: NodeProbeTransport) =
        NodeProbeTarget(ConnectionSelection(), "127.0.0.1", if (transport == NodeProbeTransport.Tcp) "ss" else "tuic", port, transport)
    private fun success(value: NodeLatencyObservation) {
        assertTrue("Owned endpoint must produce a transport RTT", value is NodeLatencyObservation.Success)
        assertTrue((value as NodeLatencyObservation.Success).milliseconds in 1..1_500)
    }

    @Test(timeout = 20_000) fun realTcpHandshakeUsesTheConfiguredPortAndClosesSocketAndLease() = runBlocking {
        val networks = CopyOnWriteArrayList<RecordingNetwork>()
        val probe = AndroidNodeLatencyProbe(context(), reserveNetwork = { RecordingNetwork(it).also(networks::add) })
        ServerSocket(0, 8, loopback).use { server ->
            val result = withTimeout(6_000) { probe.measure(target(server.localPort, NodeProbeTransport.Tcp)) }
            assertTrue("TRANSPORT_PROBE_RESULT reserved=${networks.size} observation=${result::class.java.simpleName} bind=${networks.firstOrNull()?.bindingStage}", result is NodeLatencyObservation.Success)
            success(result)
            assertEquals(networks.single().networkHandle, (result as NodeLatencyObservation.Success).networkKey)
        }
        networks.single().assertClosed()
    }

    @Test(timeout = 20_000) fun closedTcpPortIsNeverReportedAsSuccessfulLatency() = runBlocking {
        val networks = CopyOnWriteArrayList<RecordingNetwork>()
        val probe = AndroidNodeLatencyProbe(context(), reserveNetwork = { RecordingNetwork(it).also(networks::add) })
        val port = ServerSocket(0, 8, loopback).use { it.localPort }
        assertSame(NodeLatencyObservation.NoReply,
            withTimeout(6_000) { probe.measure(target(port, NodeProbeTransport.Tcp)) })
        networks.single().assertClosed()
    }

    @Test(timeout = 20_000) fun quicRequiresReversedMatchingConnectionIdsBeforePublishingRtt() = runBlocking {
        val networks = CopyOnWriteArrayList<RecordingNetwork>()
        DatagramSocket(InetSocketAddress(loopback, 0)).use { server ->
            server.soTimeout = 5_000
            val responder = async(Dispatchers.IO) {
                val bytes = ByteArray(1500)
                val received = DatagramPacket(bytes, bytes.size)
                server.receive(received)
                assertEquals(1200, received.length)
                assertEquals(16, bytes[5].toInt())
                assertEquals(16, bytes[22].toInt())
                val response = ByteArray(43)
                response[0] = 0x80.toByte(); response[5] = 16
                bytes.copyInto(response, 6, 23, 39)
                response[22] = 16
                bytes.copyInto(response, 23, 6, 22)
                response[42] = 1 // Offer QUIC v1.
                val wrong = response.copyOf().also { it[6] = (it[6].toInt() xor 1).toByte() }
                server.send(DatagramPacket(wrong, wrong.size, received.socketAddress))
                server.send(DatagramPacket(response, response.size, received.socketAddress))
                bytes.fill(0); wrong.fill(0); response.fill(0)
            }
            val probe = AndroidNodeLatencyProbe(context(), reserveNetwork = { RecordingNetwork(it).also(networks::add) })
            try {
                success(withTimeout(6_000) { probe.measure(target(server.localPort, NodeProbeTransport.Quic)) })
                withTimeout(6_000) { responder.await() }
            } finally {
                // Coroutine cancellation alone cannot unblock a native DatagramSocket.receive.
                server.close()
                responder.cancel()
            }
        }
        networks.single().assertClosed()
    }

    @Test(timeout = 20_000) fun unrelatedUdpPayloadNeverBecomesAMillisecondValue() = runBlocking {
        DatagramSocket(InetSocketAddress(loopback, 0)).use { server ->
            server.soTimeout = 5_000
            val responder = async(Dispatchers.IO) {
                val request = DatagramPacket(ByteArray(1500), 1500)
                server.receive(request)
                val unrelated = byteArrayOf(1, 2, 3, 4)
                server.send(DatagramPacket(unrelated, unrelated.size, request.socketAddress))
                request.data.fill(0)
            }
            try {
                assertSame(NodeLatencyObservation.NoReply,
                    withTimeout(6_000) { AndroidNodeLatencyProbe(context()).measure(target(server.localPort, NodeProbeTransport.Quic)) })
                withTimeout(6_000) { responder.await() }
            } finally {
                server.close()
                responder.cancel()
            }
        }
    }

    @Test(timeout = 20_000) fun cancellationAfterBindingClosesOwnedSocketWithoutPublication() = runBlocking {
        for (transport in listOf(NodeProbeTransport.Tcp, NodeProbeTransport.Quic)) {
            val bound = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
            val published = AtomicBoolean(false)
            val networks = CopyOnWriteArrayList<RecordingNetwork>()
            val probe = AndroidNodeLatencyProbe(context(), reserveNetwork = { ctx ->
                RecordingNetwork(ctx, afterBind = { bound.complete(Unit); check(release.await(4, TimeUnit.SECONDS)) })
                    .also(networks::add)
            })
            val work = async { probe.measure(target(9, transport)); published.set(true) }
            try {
                withTimeout(5_000) { bound.await() }; work.cancel(); release.countDown()
                withTimeout(2_000) { work.join() }
                assertFalse(published.get()); networks.single().assertClosed()
            } finally { release.countDown(); work.cancelAndJoin() }
        }
    }

    @Test(timeout = 20_000) fun routeRevocationCannotPublishAndBindingFailureCannotFallBack() = runBlocking {
        for (transport in listOf(NodeProbeTransport.Tcp, NodeProbeTransport.Quic)) {
            for (rejectBinding in listOf(false, true)) {
                val revoked = AtomicBoolean(false)
                val networks = CopyOnWriteArrayList<RecordingNetwork>()
                val probe = AndroidNodeLatencyProbe(context(), reserveNetwork = { ctx ->
                    RecordingNetwork(ctx, revoked, rejectBinding, { revoked.set(true) }).also(networks::add)
                })
                val observation = probe.measure(target(9, transport))
                assertSame(if (rejectBinding) NodeLatencyObservation.Unavailable else NodeLatencyObservation.NetworkChanged, observation)
                networks.single().assertClosed()
            }
        }
    }

    @Test(timeout = 20_000) fun tcpFinalRouteReadbackBeyondDeadlineCannotPublishAnEarlierSuccessfulHandshake() = runBlocking {
        val delayed = AtomicBoolean(false)
        val networks = CopyOnWriteArrayList<RecordingNetwork>()
        val probe = AndroidNodeLatencyProbe(context(), reserveNetwork = { ctx ->
            RecordingNetwork(ctx, beforeReadback = { network ->
                if (network.hasConnectedJavaSocket() && delayed.compareAndSet(false, true)) SystemClock.sleep(1_600)
            }).also(networks::add)
        })
        ServerSocket(0, 8, loopback).use { server ->
            assertSame(NodeLatencyObservation.NoReply,
                withTimeout(6_000) { probe.measure(target(server.localPort, NodeProbeTransport.Tcp)) })
        }
        assertTrue("The final route readback must actually cross the transport deadline", delayed.get())
        networks.single().assertClosed()
    }

    @Test(timeout = 20_000) fun quicFinalRouteReadbackBeyondDeadlineCannotPublishAnEarlierMatchingReply() = runBlocking {
        val replySent = AtomicBoolean(false)
        val delayed = AtomicBoolean(false)
        val networks = CopyOnWriteArrayList<RecordingNetwork>()
        val probe = AndroidNodeLatencyProbe(context(), reserveNetwork = { ctx ->
            RecordingNetwork(ctx, beforeReadback = {
                if (replySent.get() && delayed.compareAndSet(false, true)) SystemClock.sleep(1_600)
            }).also(networks::add)
        })
        DatagramSocket(InetSocketAddress(loopback, 0)).use { server ->
            server.soTimeout = 5_000
            val responder = async(Dispatchers.IO) {
                val bytes = ByteArray(1500)
                val received = DatagramPacket(bytes, bytes.size)
                server.receive(received)
                assertEquals(1200, received.length)
                val response = ByteArray(43)
                response[0] = 0x80.toByte(); response[5] = 16; response[22] = 16
                bytes.copyInto(response, 6, 23, 39)
                bytes.copyInto(response, 23, 6, 22)
                response[42] = 1
                replySent.set(true)
                server.send(DatagramPacket(response, response.size, received.socketAddress))
                bytes.fill(0); response.fill(0)
            }
            try {
                assertSame(NodeLatencyObservation.NoReply,
                    withTimeout(6_000) { probe.measure(target(server.localPort, NodeProbeTransport.Quic)) })
                withTimeout(6_000) { responder.await() }
            } finally {
                server.close()
                responder.cancel()
            }
        }
        assertTrue("The matching reply must reach the final route readback", delayed.get())
        networks.single().assertClosed()
    }

    @Test(timeout = 20_000) fun invalidPortNeverReservesNetworkOrCreatesSocket() = runBlocking {
        var reserves = 0
        val probe = AndroidNodeLatencyProbe(context(), reserveNetwork = { reserves++; error("No reservation") })
        for (port in listOf(0, -1, 65536))
            assertSame(NodeLatencyObservation.Unavailable, probe.measure(target(port, NodeProbeTransport.Quic)))
        assertEquals(0, reserves)
    }

    private class RecordingNetwork(context: Context, private val revoked: AtomicBoolean = AtomicBoolean(false),
        private val rejectBinding: Boolean = false, private val afterBind: () -> Unit = {},
        private val beforeReadback: (RecordingNetwork) -> Unit = {}) : NodePingNetwork {
        private val held = AndroidPhysicalNetworkLease.reserve(context)
        private val delegate = AndroidNodePingNetwork(held)
        private val sockets = CopyOnWriteArrayList<FileDescriptor>()
        private val javaSockets = CopyOnWriteArrayList<Socket>()
        private var closes = 0
        var bindingStage = "NOT_REACHED"
            private set
        override val networkHandle get() = delegate.networkHandle
        override fun isCurrent() = !revoked.get() && delegate.isCurrent()
        override fun readbackCurrent(): Boolean {
            beforeReadback(this)
            return !revoked.get() && delegate.readbackCurrent()
        }
        override suspend fun resolveA(host: String) = delegate.resolveA(host)
        override fun bindSocket(socket: FileDescriptor) {
            sockets += socket
            if (rejectBinding) throw IOException("SYNTHETIC_BIND_REJECTED")
            try { delegate.bindSocket(socket); bindingStage = "BOUND" }
            catch (error: Exception) {
                val errno = (error as? ErrnoException)?.errno ?: (error.cause as? ErrnoException)?.errno
                bindingStage = "FAILED_${error::class.java.simpleName}_errno_$errno"
                throw error
            }
            afterBind()
        }
        override fun bindSocket(socket: Socket) {
            javaSockets += socket
            if (rejectBinding) throw IOException("SYNTHETIC_BIND_REJECTED")
            try { delegate.bindSocket(socket); bindingStage = "BOUND" }
            catch (error: Exception) {
                val errno = (error as? ErrnoException)?.errno ?: (error.cause as? ErrnoException)?.errno
                bindingStage = "FAILED_${error::class.java.simpleName}_errno_$errno"
                throw error
            }
            afterBind()
        }
        fun hasConnectedJavaSocket() = javaSockets.any { it.isConnected }
        override fun close() { closes++; delegate.close() }
        fun assertClosed() {
            assertEquals(1, closes); assertEquals(1, sockets.size + javaSockets.size)
            assertFalse(held.isCurrent()); assertFalse(held.readbackCurrent())
            sockets.forEach { socket ->
                assertFalse(socket.valid())
                try { Os.fstat(socket); fail("Owned socket still open") }
                catch (error: ErrnoException) { assertEquals(OsConstants.EBADF, error.errno) }
            }
            javaSockets.forEach { socket ->
                assertTrue("Owned Java socket still open", socket.isClosed)
                val channel = socket.channel ?: throw AssertionError("Missing owned channel")
                assertFalse("Owned channel still open", channel.isOpen)
            }
        }
    }
}
