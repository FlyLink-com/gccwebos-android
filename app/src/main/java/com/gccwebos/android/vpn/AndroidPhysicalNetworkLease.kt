package com.gccwebos.android.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.gccwebos.android.core.ConnectionTransportLease
import com.gccwebos.android.core.PreparationFailure
import com.gccwebos.android.core.PreparationRejected
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A callback owner per process; closing one owner cannot close the service's independent owner. */
internal class AndroidPhysicalNetworkMonitor(private val connectivity: ConnectivityManager,
    val network: Network, private val onRevoked: () -> Unit = {}) : Closeable {
    private val closed = AtomicBoolean(false)
    private val revoked = AtomicBoolean(false)
    private val bestMatches = AtomicBoolean(false)
    private val bestInitialized = CountDownLatch(if (Build.VERSION.SDK_INT >= 31) 1 else 0)
    private val registrations = mutableListOf<ConnectivityManager.NetworkCallback>()
    private val registrationLock = Any()
    fun currentFast(): Boolean = !closed.get() && !revoked.get()
    private fun revoke() { if (!closed.get() && revoked.compareAndSet(false, true)) onRevoked() }
    fun start(): Boolean = try {
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        val allObserver = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(value: Network) { if (value == network) revoke() }
            override fun onCapabilitiesChanged(value: Network, caps: NetworkCapabilities) {
                if (value == network && !physical(caps)) revoke()
                if (Build.VERSION.SDK_INT < 31 && value != network && physical(caps)) revoke()
            }
        }
        register(allObserver) { connectivity.registerNetworkCallback(request, allObserver) }
        if (Build.VERSION.SDK_INT >= 31) {
            val best = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(value: Network) {
                    if (currentFast()) {
                        if (value != network) revoke() else bestMatches.set(true)
                    }
                    bestInitialized.countDown()
                }
                override fun onLost(value: Network) { if (value == network) revoke() }
            }
            register(best) { connectivity.registerBestMatchingNetworkCallback(request, best, Handler(Looper.getMainLooper())) }
        }
        val defaults = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(value: Network, caps: NetworkCapabilities) {
                // Establishing this app's VPN changes the app default but not the pinned physical route.
                if (physical(caps) && value != network) revoke()
            }
        }
        register(defaults) { connectivity.registerDefaultNetworkCallback(defaults) }
        readbackCurrent()
    } catch (_: Exception) { revoke(); close(); false }
    private fun register(callback: ConnectivityManager.NetworkCallback, action: () -> Unit) = synchronized(registrationLock) {
        check(!closed.get())
        action()
        registrations.add(callback)
    }
    fun readbackCurrent(): Boolean {
        if (!currentFast()) return false
        val valid = try { selectionCurrent(connectivity, network, bestMatches.get()) } catch (_: Exception) { false }
        // A newly registered best callback is asynchronous. Unknown evidence never permits a
        // multi-network VPN default, but it must not irreversibly revoke before initialization.
        val awaitingBest = bestInitialized.count != 0L && try {
            physical(connectivity.getNetworkCapabilities(network)) && connectivity.activeNetwork?.let {
                connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
        } catch (_: Exception) { false }
        if (!valid && !awaitingBest) revoke()
        return valid && currentFast()
    }
    /** IO/worker only; the caller supplies the existing operation deadline, never a renewed one. */
    fun awaitInitialBest(deadlineElapsedMillis: Long): Boolean {
        if (!currentFast()) return false
        val remaining = deadlineElapsedMillis - SystemClock.elapsedRealtime()
        if (remaining <= 0) return false
        return try {
            bestInitialized.await(remaining, TimeUnit.MILLISECONDS) && readbackCurrent()
        } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        bestInitialized.countDown()
        synchronized(registrationLock) {
            registrations.forEach { runCatching { connectivity.unregisterNetworkCallback(it) } }
            registrations.clear()
        }
    }
    companion object {
        fun physical(caps: NetworkCapabilities?): Boolean = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        fun selectionCurrent(connectivity: ConnectivityManager, selected: Network, bestMatches: Boolean = false): Boolean {
            if (!physical(connectivity.getNetworkCapabilities(selected))) return false
            val active = connectivity.activeNetwork ?: return false
            val caps = connectivity.getNetworkCapabilities(active) ?: return false
            if (physical(caps)) return active == selected
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
            val candidates = connectivity.allNetworks.filter { physical(connectivity.getNetworkCapabilities(it)) }
            return (Build.VERSION.SDK_INT >= 31 && bestMatches) || candidates.singleOrNull() == selected
        }
        fun select(connectivity: ConnectivityManager): Network? {
            val active = connectivity.activeNetwork ?: return null
            val caps = connectivity.getNetworkCapabilities(active) ?: return null
            val candidates = connectivity.allNetworks.filter { physical(connectivity.getNetworkCapabilities(it)) }
            // API30 has no best-physical callback; its conservative path requires a unique physical route.
            if (Build.VERSION.SDK_INT < 31 && candidates.size != 1) return null
            return if (physical(caps)) active else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) candidates.singleOrNull() else null
        }
    }
}

internal class AndroidPhysicalNetworkLease private constructor(val network: Network,
    private val monitor: AndroidPhysicalNetworkMonitor, private val created: Long,
    override val validUntilElapsedMillis: Long) : ConnectionTransportLease {
    private val closed = AtomicBoolean(false)
    override val networkHandle: Long get() = network.networkHandle
    override fun isCurrent(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return !closed.get() && now >= created && now < validUntilElapsedMillis && monitor.currentFast()
    }
    fun readbackCurrent(): Boolean = isCurrent() && monitor.readbackCurrent()
    override fun close() { if (closed.compareAndSet(false, true)) monitor.close() }
    override fun toString() = "AndroidPhysicalNetworkLease(<opaque>)"
    companion object {
        fun reserve(context: Context): AndroidPhysicalNetworkLease {
            if (Build.VERSION.SDK_INT < 30) throw PreparationRejected(PreparationFailure.NETWORK)
            var monitor: AndroidPhysicalNetworkMonitor? = null
            try {
                val connectivity = context.getSystemService(ConnectivityManager::class.java)
                val selected = AndroidPhysicalNetworkMonitor.select(connectivity) ?: throw PreparationRejected(PreparationFailure.NETWORK)
                val now = SystemClock.elapsedRealtime()
                if (now > Long.MAX_VALUE - VpnControl.MAX_TRANSPORT_LEASE_MILLIS) throw PreparationRejected(PreparationFailure.NETWORK)
                val deadline = now + VpnControl.MAX_TRANSPORT_LEASE_MILLIS
                monitor = AndroidPhysicalNetworkMonitor(connectivity, selected)
                if (!monitor.start()) throw PreparationRejected(PreparationFailure.NETWORK)
                if (!monitor.awaitInitialBest(minOf(deadline, now + 2_000))) throw PreparationRejected(PreparationFailure.NETWORK)
                return AndroidPhysicalNetworkLease(selected, monitor, now, deadline)
            } catch (failure: Exception) {
                monitor?.close()
                if (failure is PreparationRejected) throw failure
                throw PreparationRejected(PreparationFailure.NETWORK)
            }
        }
    }
}
