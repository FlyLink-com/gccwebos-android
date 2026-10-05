package com.gccwebos.android.vpn

import android.content.Context
import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import com.gccwebos.android.core.ConnectionTransportLease
import com.gccwebos.android.core.PreparationFailure
import com.gccwebos.android.core.PreparationRejected
import com.gccwebos.android.portal.ConfigAddressPreparer
import com.gccwebos.android.portal.PreparedAddressConfig
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

/** The injectable boundary exposes no default-network lookup or reusable DNS cache. */
internal interface AddressPreparationNetwork : Closeable {
    val lease: ConnectionTransportLease
    fun readbackCurrent(): Boolean
    suspend fun resolveA(hostname: String): List<String>
    override fun close() = lease.close()
}

class AndroidConfigAddressPreparer internal constructor(
    private val reserveNetwork: suspend () -> AddressPreparationNetwork) : ConfigAddressPreparer {
    constructor(context: Context) : this({
        var allocated: AndroidAddressPreparationNetwork? = null
        try {
            withContext(Dispatchers.IO) {
                AndroidAddressPreparationNetwork(AndroidPhysicalNetworkLease.reserve(context)).also { allocated = it }
            }
        } catch (failure: Throwable) { allocated?.close(); throw failure }
    })
    override suspend fun prepare(raw: ByteArray, index: Int): PreparedAddressConfig {
        val endpoint = SafeProxyConfig.selectedEndpoint(raw, index)
        currentCoroutineContext().ensureActive()
        var network: AddressPreparationNetwork? = null
        var projected: ByteArray? = null
        var transferred = false
        try {
            val reserved = reserveNetwork()
            network = reserved
            if (!reserved.lease.isCurrent() || !reserved.readbackCurrent()) throw PreparationRejected(PreparationFailure.STALE)
            val ipv4 = if (endpoint.any { it in 'A'..'Z' || it in 'a'..'z' }) {
                val answers = withTimeout(DNS_TIMEOUT_MILLIS) { reserved.resolveA(endpoint) }
                currentCoroutineContext().ensureActive()
                if (!reserved.lease.isCurrent() || !reserved.readbackCurrent()) throw PreparationRejected(PreparationFailure.STALE)
                answers.firstOrNull(::canonicalIpv4) ?: throw PreparationRejected(PreparationFailure.NETWORK)
            } else endpoint
            val bytes = SafeProxyConfig.projectResolved(raw, index, ipv4)
            projected = bytes
            currentCoroutineContext().ensureActive()
            if (!reserved.lease.isCurrent() || !reserved.readbackCurrent()) throw PreparationRejected(PreparationFailure.STALE)
            return PreparedAddressConfig(bytes, reserved.lease).also { transferred = true }
        } catch (_: TimeoutCancellationException) { throw PreparationRejected(PreparationFailure.TIMEOUT) }
        catch (failure: CancellationException) { throw failure }
        catch (failure: PreparationRejected) { throw failure }
        catch (failure: UnsafeProxyConfiguration) { throw failure }
        catch (_: Exception) { throw PreparationRejected(PreparationFailure.NETWORK) }
        finally {
            if (!transferred) { projected?.fill(0); network?.close() }
        }
    }
    companion object {
        internal const val DNS_TIMEOUT_MILLIS = 5_000L
        internal fun canonicalIpv4(value: String): Boolean {
            val octets = value.split('.')
            return octets.size == 4 && octets.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() in 0..255 } &&
                value != "0.0.0.0" && octets[0].toInt() !in 224..239
        }
    }
}

internal fun interface PhysicalAQuery {
    fun query(network: android.net.Network, hostname: String, nsType: Int, flags: Int,
        executor: Executor, cancellation: CancellationSignal,
        callback: DnsResolver.Callback<List<InetAddress>>)
}

internal class AndroidAddressPreparationNetwork(private val held: AndroidPhysicalNetworkLease,
    private val query: PhysicalAQuery = PhysicalAQuery { network, hostname, type, flags, executor, cancellation, callback ->
        if (Build.VERSION.SDK_INT < 29) throw PreparationRejected(PreparationFailure.NETWORK)
        DnsResolver.getInstance().query(network, hostname, type, flags, executor, cancellation, callback)
    }) : AddressPreparationNetwork {
    override val lease: ConnectionTransportLease get() = held
    override fun readbackCurrent(): Boolean = held.readbackCurrent()
    override suspend fun resolveA(hostname: String): List<String> {
        if (Build.VERSION.SDK_INT < 29) throw PreparationRejected(PreparationFailure.NETWORK)
        return suspendCancellableCoroutine { continuation ->
            val cancel = CancellationSignal()
            continuation.invokeOnCancellation { cancel.cancel() }
            try {
                query.query(held.network, hostname, DnsResolver.TYPE_A, DnsResolver.FLAG_NO_CACHE_LOOKUP,
                    Executor { it.run() }, cancel, object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                        if (!continuation.isActive) return
                        if (rcode != 0) continuation.resumeWith(Result.failure(PreparationRejected(PreparationFailure.NETWORK)))
                        else continuation.resumeWith(Result.success(answer.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }))
                    }
                    override fun onError(error: DnsResolver.DnsException) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(PreparationRejected(PreparationFailure.NETWORK)))
                    }
                })
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(PreparationRejected(PreparationFailure.NETWORK)))
            }
        }
    }
}
