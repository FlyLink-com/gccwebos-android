package com.gccwebos.android.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.gccwebos.android.portal.NodeLatencyObservation
import com.gccwebos.android.portal.NodeLatencyProbe
import com.gccwebos.android.portal.NodeProbeTarget
import java.io.Closeable
import java.io.FileDescriptor
import java.net.InetAddress
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Host RTT, not a proxy handshake. No native start, shell command, credentials or third-party URL. */
internal class AndroidNodePingProbe(
    context: Context,
    private val reserveNetwork: (Context) -> NodePingNetwork = {
        AndroidNodePingNetwork(AndroidPhysicalNetworkLease.reserve(it))
    },
) : NodeLatencyProbe {
    private val context = context.applicationContext
    private val connectivity = this.context.getSystemService(ConnectivityManager::class.java)
    private val random = SecureRandom()
    private var keyReadAt: Long? = null
    private var displayKey: Long? = null

    // Shared by all visible rows; this short display-only cache avoids a Binder call for every row.
    // Admission below always reserves and verifies its own live physical network.
    @Synchronized override fun currentNetworkKey(): Long? {
        val now = SystemClock.elapsedRealtime()
        if (keyReadAt?.let { now - it in 0..250 } == true) return displayKey
        displayKey = try { AndroidPhysicalNetworkMonitor.select(connectivity)?.networkHandle } catch (_: Exception) { null }
        keyReadAt = now
        return displayKey
    }

    override suspend fun measure(target: NodeProbeTarget): NodeLatencyObservation = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 30) return@withContext NodeLatencyObservation.Unavailable
        var held: NodePingNetwork? = null
        try {
            withTimeout(3_500) {
                currentCoroutineContext().ensureActive()
                val network = reserveNetwork(context).also { held = it }
                currentCoroutineContext().ensureActive()
                if (!network.readbackCurrent()) return@withTimeout NodeLatencyObservation.NetworkChanged
                val endpoint = target.endpoint
                val ipv4 = if (AndroidConfigAddressPreparer.canonicalIpv4(endpoint)) endpoint else {
                    network.resolveA(endpoint)
                        .firstOrNull(AndroidConfigAddressPreparer::canonicalIpv4)
                        ?: return@withTimeout NodeLatencyObservation.Unavailable
                }
                currentCoroutineContext().ensureActive()
                if (!network.readbackCurrent()) return@withTimeout NodeLatencyObservation.NetworkChanged
                // getByAddress cannot perform a default-network DNS lookup.
                val address = InetAddress.getByAddress(ipv4.split('.').map { it.toInt().toByte() }.toByteArray())
                echo(network, address)
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            NodeLatencyObservation.NoReply
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { NodeLatencyObservation.Unavailable }
        finally { held?.close() }
    }

    @androidx.annotation.RequiresApi(30)
    private suspend fun echo(network: NodePingNetwork, address: InetAddress): NodeLatencyObservation {
        var fd: FileDescriptor? = null
        val nonce = ByteArray(IcmpEchoPacket.NonceBytes).also(random::nextBytes)
        val request = IcmpEchoPacket.request(nonce)
        val reply = ByteArray(512)
        try {
            val socket = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM or OsConstants.SOCK_CLOEXEC or
                OsConstants.SOCK_NONBLOCK, OsConstants.IPPROTO_ICMP).also { fd = it }
            network.bindSocket(socket) // Never fall back to the default or the VPN route.
            currentCoroutineContext().ensureActive()
            if (!network.readbackCurrent()) return NodeLatencyObservation.NetworkChanged
            Os.connect(socket, address, 0) // The connected socket also restricts the reply source.
            currentCoroutineContext().ensureActive()
            if (!network.readbackCurrent()) return NodeLatencyObservation.NetworkChanged
            val start = SystemClock.elapsedRealtimeNanos()
            if (Os.write(socket, request, 0, request.size) != request.size) return NodeLatencyObservation.Unavailable
            val deadline = SystemClock.elapsedRealtime() + 1_500
            val poll = StructPollfd().apply { this.fd = socket; events = OsConstants.POLLIN.toShort() }
            while (SystemClock.elapsedRealtime() < deadline) {
                currentCoroutineContext().ensureActive()
                if (!network.isCurrent()) return NodeLatencyObservation.NetworkChanged
                val remaining = (deadline - SystemClock.elapsedRealtime()).coerceIn(1, 75).toInt()
                poll.revents = 0
                val ready = try { Os.poll(arrayOf(poll), remaining) }
                    catch (error: ErrnoException) {
                        if (error.errno == OsConstants.EINTR) continue
                        throw error
                    }
                if (ready <= 0) continue
                val events = poll.revents.toInt()
                if (events and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0)
                    return NodeLatencyObservation.NoReply
                if (events and OsConstants.POLLIN == 0) continue
                val count = try { Os.read(socket, reply, 0, reply.size) }
                    catch (error: ErrnoException) {
                        if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR) continue
                        throw error
                    }
                if (!IcmpEchoPacket.matchesReply(reply, count, nonce)) continue
                val elapsed = SystemClock.elapsedRealtimeNanos() - start
                currentCoroutineContext().ensureActive()
                if (!network.readbackCurrent()) return NodeLatencyObservation.NetworkChanged
                if (elapsed < 0) return NodeLatencyObservation.Unavailable
                return NodeLatencyObservation.Success(((elapsed + 999_999) / 1_000_000).coerceAtLeast(1), network.networkHandle)
            }
            return NodeLatencyObservation.NoReply
        } finally {
            // Poll is at most 75 ms; coroutine cancellation always reaches the socket owner's close.
            fd?.let { runCatching { Os.close(it) } }
            request.fill(0); reply.fill(0); nonce.fill(0)
        }
    }
}

/** One owned physical route for DNS and the socket; neither operation may use a default route. */
internal interface NodePingNetwork : Closeable {
    val networkHandle: Long
    fun isCurrent(): Boolean
    fun readbackCurrent(): Boolean
    suspend fun resolveA(host: String): List<String>
    fun bindSocket(socket: FileDescriptor)
}

internal class AndroidNodePingNetwork(private val held: AndroidPhysicalNetworkLease) : NodePingNetwork {
    override val networkHandle: Long get() = held.networkHandle
    override fun isCurrent(): Boolean = held.isCurrent()
    override fun readbackCurrent(): Boolean = held.readbackCurrent()
    override suspend fun resolveA(host: String): List<String> = AndroidAddressPreparationNetwork(held).resolveA(host)
    override fun bindSocket(socket: FileDescriptor) = held.network.bindSocket(socket)
    override fun close() = held.close()
    override fun toString() = "AndroidNodePingNetwork(<opaque>)"
}
