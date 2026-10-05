package com.gccwebos.android.vpn

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.annotation.RequiresApi
import com.gccwebos.android.portal.NodeLatencyObservation
import com.gccwebos.android.portal.NodeLatencyProbe
import com.gccwebos.android.portal.NodeProbeTarget
import com.gccwebos.android.portal.NodeProbeTransport
import java.io.FileDescriptor
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Endpoint transport RTT only. No credentials, proxy request, native engine or VPN startup. */
internal class AndroidNodeLatencyProbe(
    context: Context,
    private val icmp: NodeLatencyProbe = AndroidNodePingProbe(context),
    private val reserveNetwork: (Context) -> NodePingNetwork = {
        AndroidNodePingNetwork(AndroidPhysicalNetworkLease.reserve(it))
    },
) : NodeLatencyProbe {
    private val context = context.applicationContext
    private val random = SecureRandom()
    override fun currentNetworkKey(): Long? = icmp.currentNetworkKey()

    override suspend fun measure(target: NodeProbeTarget): NodeLatencyObservation {
        if (target.transport == NodeProbeTransport.Icmp) return icmp.measure(target)
        if (Build.VERSION.SDK_INT < 30 || target.port !in 1..65535) return NodeLatencyObservation.Unavailable
        return withContext(Dispatchers.IO) {
            var network: NodePingNetwork? = null
            try {
                withTimeout(3_500) {
                    currentCoroutineContext().ensureActive()
                    val held = reserveNetwork(context).also { network = it }
                    checkRoute(held)
                    val addressText = if (AndroidConfigAddressPreparer.canonicalIpv4(target.endpoint)) target.endpoint
                        else held.resolveA(target.endpoint).firstOrNull(AndroidConfigAddressPreparer::canonicalIpv4)
                            ?: return@withTimeout NodeLatencyObservation.Unavailable
                    checkRoute(held)
                    // Numeric construction cannot escape to the default network's DNS resolver.
                    val address = InetAddress.getByAddress(addressText.split('.').map { it.toInt().toByte() }.toByteArray())
                    measureTransport(held, address, target)
                }
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                NodeLatencyObservation.NoReply
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: RouteChanged) { NodeLatencyObservation.NetworkChanged }
            catch (error: ErrnoException) {
                if (error.errno in listOf(OsConstants.ECONNREFUSED, OsConstants.ETIMEDOUT,
                        OsConstants.EHOSTUNREACH, OsConstants.ENETUNREACH)) NodeLatencyObservation.NoReply
                else NodeLatencyObservation.Unavailable
            } catch (_: Exception) { NodeLatencyObservation.Unavailable }
            finally { network?.close() }
        }
    }

    private suspend fun checkRoute(network: NodePingNetwork) {
        currentCoroutineContext().ensureActive()
        if (!network.readbackCurrent()) throw RouteChanged()
        currentCoroutineContext().ensureActive()
    }

    @RequiresApi(30)
    private suspend fun measureTransport(network: NodePingNetwork, address: InetAddress,
        target: NodeProbeTarget): NodeLatencyObservation {
        if (target.transport == NodeProbeTransport.Tcp) return tcp(network, address, target.port)
        var fd: FileDescriptor? = null
        try {
            val socket = Os.socket(OsConstants.AF_INET,
                OsConstants.SOCK_DGRAM or OsConstants.SOCK_CLOEXEC or OsConstants.SOCK_NONBLOCK,
                OsConstants.IPPROTO_UDP).also { fd = it }
            network.bindSocket(socket) // Binding failure must never fall back to the default route.
            checkRoute(network)
            return quic(network, socket, address, target.port)
        } finally { fd?.let { runCatching { Os.close(it) } } }
    }

    private suspend fun tcp(network: NodePingNetwork, address: InetAddress, port: Int): NodeLatencyObservation {
        return SocketChannel.open().use { channel ->
            channel.configureBlocking(false)
            // Keep setup/binding outside the connect error path: a binding failure is Unavailable.
            network.bindSocket(channel.socket())
            checkRoute(network)
            Selector.open().use { selector ->
                channel.register(selector, SelectionKey.OP_CONNECT)
                val deadline = SystemClock.elapsedRealtime() + ReplyTimeoutMillis
                val start = SystemClock.elapsedRealtimeNanos()
                try {
                    var connected = channel.connect(InetSocketAddress(address, port))
                    while (!connected) {
                        currentCoroutineContext().ensureActive()
                        if (!network.isCurrent()) throw RouteChanged()
                        val remaining = deadline - SystemClock.elapsedRealtime()
                        if (remaining <= 0) return NodeLatencyObservation.NoReply
                        if (selector.select(minOf(remaining, 75)) <= 0) continue
                        currentCoroutineContext().ensureActive()
                        if (!network.isCurrent()) throw RouteChanged()
                        val ready = selector.selectedKeys().iterator()
                        while (ready.hasNext()) {
                            val key = ready.next()
                            ready.remove()
                            // Connect readiness includes refused connections; finishConnect must succeed.
                            if (key.isValid && key.isConnectable) connected = channel.finishConnect()
                        }
                    }
                } catch (_: IOException) {
                    checkRoute(network)
                    return NodeLatencyObservation.NoReply
                }
                success(network, start, deadline)
            }
        }
    }

    private suspend fun quic(network: NodePingNetwork, socket: FileDescriptor,
        address: InetAddress, port: Int): NodeLatencyObservation {
        val destinationId = ByteArray(QuicVersionProbePacket.ConnectionIdBytes).also(random::nextBytes)
        val sourceId = ByteArray(QuicVersionProbePacket.ConnectionIdBytes).also(random::nextBytes)
        val request = QuicVersionProbePacket.request(destinationId, sourceId)
        // IPv4 UDP payload cannot exceed 65,507 bytes. Never accept a truncated VN list.
        val response = ByteArray(65_535)
        try {
            Os.connect(socket, address, port) // A connected UDP socket also filters reply source/port.
            checkRoute(network)
            val deadline = SystemClock.elapsedRealtime() + ReplyTimeoutMillis
            val start = SystemClock.elapsedRealtimeNanos()
            if (Os.write(socket, request, 0, request.size) != request.size) return NodeLatencyObservation.Unavailable
            while (true) {
                val events = await(network, socket, OsConstants.POLLIN, deadline)
                if (events == 0 || events and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0)
                    return NodeLatencyObservation.NoReply
                if (events and OsConstants.POLLIN == 0) continue
                val count = try { Os.read(socket, response, 0, response.size) }
                    catch (error: ErrnoException) {
                        if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR) continue
                        throw error
                    }
                if (QuicVersionProbePacket.matchesReply(response, count, destinationId, sourceId))
                    return success(network, start, deadline)
            }
        } finally { destinationId.fill(0); sourceId.fill(0); request.fill(0); response.fill(0) }
    }

    private suspend fun await(network: NodePingNetwork, socket: FileDescriptor, events: Int, deadline: Long): Int {
        val poll = StructPollfd().apply { fd = socket; this.events = events.toShort() }
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!network.isCurrent()) throw RouteChanged()
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return 0
            poll.revents = 0
            val ready = try { Os.poll(arrayOf(poll), minOf(remaining, 75).toInt()) }
                catch (error: ErrnoException) { if (error.errno == OsConstants.EINTR) continue else throw error }
            if (ready > 0) {
                currentCoroutineContext().ensureActive()
                if (!network.isCurrent()) throw RouteChanged()
                return if (SystemClock.elapsedRealtime() >= deadline) 0 else poll.revents.toInt()
            }
        }
    }

    private suspend fun success(network: NodePingNetwork, start: Long, deadline: Long): NodeLatencyObservation {
        val elapsed = SystemClock.elapsedRealtimeNanos() - start
        checkRoute(network)
        return if (elapsed < 0) NodeLatencyObservation.Unavailable
            else if (SystemClock.elapsedRealtime() >= deadline) NodeLatencyObservation.NoReply
            else NodeLatencyObservation.Success(((elapsed + 999_999) / 1_000_000).coerceAtLeast(1), network.networkHandle)
    }

    private class RouteChanged : Exception("Physical route changed")
    private companion object { const val ReplyTimeoutMillis = 1_500L }
}
