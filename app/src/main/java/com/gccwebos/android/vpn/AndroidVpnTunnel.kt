package com.gccwebos.android.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.Build
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.gccwebos.android.BuildConfig
import com.gccwebos.android.core.AuthorizedConfig
import com.gccwebos.android.core.LifecycleTunnel
import com.gccwebos.android.core.TrafficProbe
import com.gccwebos.android.core.TunnelPreparation
import com.gccwebos.android.core.TunnelTerminal
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal fun interface VpnNetworkProbe { fun check(network: Network): Boolean }

/** Connections and DNS are explicitly bound to the new VPN Network. No direct fallback. */
internal class HttpsVpnNetworkProbe : VpnNetworkProbe {
    override fun check(network: Network): Boolean {
        val url = URL("https://www.gstatic.com/generate_204?gccvpn=" + UUID.randomUUID())
        val connection = network.openConnection(url) as HttpsURLConnection
        return try {
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.responseCode == 204
        } finally { connection.disconnect() }
    }
}

/** One remote incarnation at a time. Timeout retains both binding and executing-call debt. */
internal class AndroidVpnTunnel(private val context: Context, private val ownerIdentity: IBinder,
    private val networkProbe: VpnNetworkProbe = HttpsVpnNetworkProbe(),
    private val clearPreparedMaterial: () -> Unit = {}) : LifecycleTunnel, TunnelPreparation, TrafficProbe {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private fun trace(phase: String) { if (BuildConfig.DEBUG) Log.i("GCCConnectionEngine", "phase=$phase") }
    private val lock = Any()
    private var endpoint: Endpoint? = null
    private var sequence = 0L

    private inner class Endpoint {
        val receipt = VpnTransportReceipt<IBinder>()
        val ready get() = receipt.ready
        val dead get() = receipt.dead
        val ownerToken = ownerIdentity
        val calls = mutableListOf<BoundedVpnCall<*>>()
        val priorVpnNetworks = vpnNetworks().toSet()
        val binder get() = receipt.binder()
        var bound = false
        val stopping get() = receipt.isStopping()
        var terminal: ((TunnelTerminal) -> Unit)? = null
        var expiry: java.time.Instant? = null
        val recipient = IBinder.DeathRecipient { died() }
        fun died() {
            val notify = synchronized(lock) {
                receipt.binderDied()
                if (endpoint === this && !stopping) terminal else null
            }
            notify?.invoke(TunnelTerminal.ENGINE_DIED)
        }
        fun lost() {
            val notify = synchronized(lock) {
                receipt.transportLost()
                if (endpoint === this && !stopping) terminal else null
            }
            notify?.invoke(TunnelTerminal.ENGINE_DIED)
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                synchronized(lock) {
                    if (endpoint !== this@Endpoint) return
                    // A cancelled bind still needs this exact death witness for cleanup.
                    try { if (!receipt.capture(service) { it.linkToDeath(recipient, 0) }) lost() }
                    catch (_: Exception) { lost() }
                }
            }
            override fun onServiceDisconnected(name: ComponentName) = lost()
            override fun onBindingDied(name: ComponentName) = lost()
            override fun onNullBinding(name: ComponentName) {
                ready.completeExceptionally(IllegalStateException("VPN binding refused")); lost()
            }
        }
    }

    /** Called after consent, outside authority/command fences and before synchronous accept. */
    override suspend fun prepareTransport(): Boolean {
        val next = synchronized(lock) {
            check(endpoint == null) { "Previous VPN incarnation requires cleanup" }
            Endpoint().also { endpoint = it }
        }
        return try {
            withContext(Dispatchers.Main.immediate) {
                next.bound = context.bindService(Intent(context, ProductionVpnService::class.java)
                    .setAction(VpnControl.ACTION_BIND), next.connection, Context.BIND_AUTO_CREATE)
                check(next.bound)
            }
            withTimeout(5_000) { next.ready.await() }
            true
        } catch (_: Exception) {
            // A partial binding is a real resource, even though no TUN was accepted yet.
            withContext(NonCancellable) { stop() }
            false
        }
    }

    override fun accept(config: AuthorizedConfig, terminal: (TunnelTerminal) -> Unit): Boolean {
        val next: Endpoint
        val generation: Long
        synchronized(lock) {
            next = endpoint ?: return false
            if (!next.receipt.admit()) { trace("sender-admission-rejected"); return false }
            next.terminal = terminal
            next.expiry = config.validUntil
            generation = ++sequence
        }
        val material = config.copyMaterial()
        val admissionDeadline = SystemClock.elapsedRealtime() + 1_500
        val call = BoundedVpnCall {
            try {
                transact(next, VpnControl.COPY_START) {
                    writeStrongBinder(next.ownerToken)
                    writeLong(generation)
                    writeLong(config.validUntil?.toEpochMilli() ?: 0)
                    writeLong(admissionDeadline)
                    writeByteArray(material)
                } == 1
            } finally { material.fill(0) }
        }
        synchronized(lock) { next.calls.add(call) }
        return try {
            call.await(1_500) && synchronized(lock) {
                endpoint === next && next.receipt.isCurrent()
            }
        } catch (failure: Exception) { trace("sender-copy-failed-${failure.javaClass.simpleName}-${failure.cause?.javaClass?.simpleName ?: "none"}"); false }
    }

    private fun transact(next: Endpoint, code: Int, fill: Parcel.() -> Unit = {}): Int {
        val input = Parcel.obtain()
        val output = Parcel.obtain()
        return try {
            input.writeInterfaceToken(VpnControl.DESCRIPTOR)
            input.fill()
            check(requireNotNull(next.binder).transact(code, input, output, 0))
            output.readException()
            output.readInt()
        } finally { input.recycle(); output.recycle() }
    }

    override suspend fun isUsable(): Boolean = withContext(Dispatchers.IO) {
        val next = synchronized(lock) { endpoint } ?: return@withContext false
        withTimeout(14_000) {
            while (true) {
                if (!current(next)) return@withTimeout false
                val call = BoundedVpnCall { transact(next, VpnControl.STATE) }
                synchronized(lock) { next.calls.add(call) }
                val state = try { call.await(1_000) } catch (_: Exception) { return@withTimeout false }
                if (state == VpnControl.RUNNING) {
                    val network = vpnNetworks().firstOrNull { it !in next.priorVpnNetworks }
                    if (network != null) {
                        val usable = try { networkProbe.check(network) } catch (_: Exception) { false }
                        return@withTimeout usable && current(next) && network in vpnNetworks()
                    }
                } else if (state != VpnControl.STARTING) return@withTimeout false
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") false
        }
    }

    private fun current(next: Endpoint): Boolean = synchronized(lock) {
        endpoint === next && next.receipt.isCurrent() &&
            next.expiry?.let { it > java.time.Instant.now() } != false
    }
    private fun vpnNetworks(): List<Network> = if (Build.VERSION.SDK_INT < 30) emptyList() else
        connectivity.allNetworks.filter {
            val capabilities = connectivity.getNetworkCapabilities(it)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                capabilities.ownerUid == Process.myUid()
        }

    override suspend fun stop() {
        val next = synchronized(lock) { endpoint?.also { it.receipt.beginStop(); it.terminal = null } } ?: return
        next.receipt.stopAndReleaseBinding(next.bound, {
            val stopCall = BoundedVpnCall { transact(next, VpnControl.STOP) }
            synchronized(lock) { next.calls.add(stopCall) }
            // Stop is an independent emergency command; it never authorizes another generation.
            try { stopCall.await(500) } catch (_: Exception) { }
        }, {
            withContext(Dispatchers.Main.immediate) {
                if (next.bound) { context.unbindService(next.connection); next.bound = false }
            }
        })
        // Binder death is separate from caller return. Never cancel Future to fake cleanup.
        withTimeout(500) {
            while (synchronized(lock) { next.calls.any { !it.returned } }) delay(10)
        }
        next.binder?.let { runCatching { it.unlinkToDeath(next.recipient, 0) } }
        clearPreparedMaterial() // A failed credential deletion remains cleanup debt as well.
        synchronized(lock) { if (endpoint === next) endpoint = null }
    }
}
