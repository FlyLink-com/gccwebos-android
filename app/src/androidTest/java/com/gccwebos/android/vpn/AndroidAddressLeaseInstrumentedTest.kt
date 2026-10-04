package com.gccwebos.android.vpn

import android.content.ComponentName
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.DnsResolver
import android.os.Binder
import android.os.Build
import android.os.CancellationSignal
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.gccwebos.android.core.PreparationFailure
import com.gccwebos.android.core.PreparationRejected
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Synthetic queries plus one fixed public DNS lookup. Never inspect accounts or private stores. */
@SdkSuppress(minSdkVersion = 30)
class AndroidAddressLeaseInstrumentedTest {
    private fun ownedContext(): Context {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.gccwebos.android.debug" ||
            context.packageName == "com.gccwebos.android.candidate.acceptance.address20261004v2") {
            "ADDRESS_TEST_PACKAGE_REJECTED"
        }
        check(Build.VERSION.SDK_INT >= 30) { "ADDRESS_TEST_API_REJECTED" }
        return context
    }
    private fun answer() = listOf(InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 19)))

    @Test fun physicalLeaseHasFiniteAdmissionAndCloseCannotReviveIt() {
        val held = AndroidPhysicalNetworkLease.reserve(ownedContext())
        try {
            assertTrue("Physical lease must be current", held.isCurrent() && held.readbackCurrent())
            assertTrue("Pinned identity must match", held.networkHandle == held.network.networkHandle)
            val remaining = held.validUntilElapsedMillis - SystemClock.elapsedRealtime()
            assertTrue("Preparation budget must be finite", remaining in 1..VpnControl.MAX_TRANSPORT_LEASE_MILLIS)
        } finally { held.close(); held.close() }
        assertFalse("Closed lease cannot authorize admission", held.isCurrent())
        assertFalse("Closed callback owner cannot revive", held.readbackCurrent())
    }

    @Test fun queryUsesPinnedPhysicalNetworkTypeAAndFreshLookupFlag() = runBlocking {
        val held = AndroidPhysicalNetworkLease.reserve(ownedContext())
        var calls = 0
        val lookup = AndroidAddressPreparationNetwork(held, PhysicalAQuery { network, _, type, flags, executor, _, callback ->
            calls++
            assertTrue("Query must use lease identity", network == held.network)
            assertEquals(DnsResolver.TYPE_A, type)
            assertEquals(DnsResolver.FLAG_NO_CACHE_LOOKUP, flags)
            executor.execute { callback.onAnswer(answer(), 0) }
        })
        try {
            val addresses = withTimeout(5_000) { lookup.resolveA("synthetic.fixture.invalid") }
            assertTrue("Only the synthetic A answer may return", addresses == listOf("192.0.2.19"))
            assertEquals(1, calls)
        } finally { lookup.close() }
        assertFalse(held.isCurrent())
    }

    @Test fun cancellationCancelsPlatformSignalAndIgnoresLateAnswer() = runBlocking {
        val held = AndroidPhysicalNetworkLease.reserve(ownedContext())
        var signal: CancellationSignal? = null
        var callback: DnsResolver.Callback<List<InetAddress>>? = null
        val lookup = AndroidAddressPreparationNetwork(held, PhysicalAQuery { _, _, _, _, _, cancellation, listener ->
            signal = cancellation; callback = listener
        })
        try {
            val work = async(start = CoroutineStart.UNDISPATCHED) { lookup.resolveA("synthetic.fixture.invalid") }
            assertNotNull(signal)
            work.cancelAndJoin()
            assertTrue("Actual Android signal must cancel", signal?.isCanceled == true)
            requireNotNull(callback).onAnswer(answer(), 0)
            assertTrue("Late callback cannot restore canceled work", work.isCancelled)
        } finally { lookup.close() }
    }

    @Test fun platformFailureReturnsFixedReasonWithoutCause() = runBlocking {
        val held = AndroidPhysicalNetworkLease.reserve(ownedContext())
        val lookup = AndroidAddressPreparationNetwork(held, PhysicalAQuery { _, _, _, _, executor, _, callback ->
            executor.execute { callback.onError(DnsResolver.DnsException(DnsResolver.ERROR_SYSTEM,
                IllegalStateException("synthetic-private-dns-marker"))) }
        })
        try {
            try { withTimeout(5_000) { lookup.resolveA("synthetic.fixture.invalid") }; fail("Lookup must fail closed") }
            catch (failure: PreparationRejected) {
                assertEquals(PreparationFailure.NETWORK, failure.reason)
                assertEquals(PreparationFailure.NETWORK.name, failure.message)
                assertNull(failure.cause)
            }
        } finally { lookup.close() }
    }

    @Test fun noAAnswerFailsPreparationWithFixedReasonAndReleasesLease() = runBlocking {
        val held = AndroidPhysicalNetworkLease.reserve(ownedContext())
        val lookup = AndroidAddressPreparationNetwork(held, PhysicalAQuery { _, _, _, _, executor, _, callback ->
            executor.execute { callback.onAnswer(emptyList(), 0) }
        })
        val raw = """
            proxies:
              - name: synthetic node
                type: hysteria2
                server: synthetic.fixture.invalid
                port: 18443
                password: synthetic-only
                sni: synthetic.fixture.invalid
        """.trimIndent().toByteArray()
        try {
            try { AndroidConfigAddressPreparer { lookup }.prepare(raw, 0).close(); fail("No A answer must reject preparation") }
            catch (failure: PreparationRejected) {
                assertEquals(PreparationFailure.NETWORK, failure.reason)
                assertEquals(PreparationFailure.NETWORK.name, failure.message)
                assertNull(failure.cause)
            }
            assertFalse("Rejected preparation must close its lease", held.isCurrent())
        } finally { raw.fill(0); lookup.close() }
    }

    @Test fun fixedPublicAQueryUsesActualResolverWithoutRecordingAddresses() = runBlocking {
        val lookup = AndroidAddressPreparationNetwork(AndroidPhysicalNetworkLease.reserve(ownedContext()))
        try {
            val addresses = withTimeout(AndroidConfigAddressPreparer.DNS_TIMEOUT_MILLIS) {
                lookup.resolveA("www.example.org")
            }
            assertTrue("Fixed public lookup must return a usable A answer",
                addresses.any { AndroidConfigAddressPreparer.canonicalIpv4(it) })
            assertTrue("Public answer must retain physical identity", lookup.lease.isCurrent() && lookup.readbackCurrent())
        } finally { lookup.close() }
    }

    @Test fun rejectedTransportStillPublishesExactBinderForStopAndDeath() {
        val context = ownedContext()
        check(context.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty()
            .none { it.processName == context.packageName + ":vpn" }) { "ADDRESS_TEST_REQUIRES_IDLE_ENGINE" }
        val received = CompletableFuture<IBinder>()
        val exactDeath = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { received.complete(service) }
            override fun onServiceDisconnected(name: ComponentName) = Unit // Never a death witness.
            override fun onNullBinding(name: ComponentName) { received.completeExceptionally(IllegalStateException("NULL_BINDER")) }
        }
        var bound = false
        var binder: IBinder? = null
        val recipient = IBinder.DeathRecipient { exactDeath.countDown() }
        try {
            bound = context.bindService(Intent(context, ProductionVpnService::class.java).setAction(VpnControl.ACTION_BIND)
                .putExtra(VpnControl.EXTRA_NETWORK_HANDLE, 1L)
                .putExtra(VpnControl.EXTRA_LEASE_DEADLINE, SystemClock.elapsedRealtime() - 1), connection, Context.BIND_AUTO_CREATE)
            assertTrue("Rejected preparation still needs an owned binding", bound)
            val control = received.get(5, TimeUnit.SECONDS)
            binder = control
            control.linkToDeath(recipient, 0)
            assertEquals(VpnControl.FAILED, transact(control, VpnControl.STATE))
            assertEquals(0, transact(control, VpnControl.COPY_START) {
                writeStrongBinder(Binder()); writeLong(1); writeLong(0)
                writeLong(SystemClock.elapsedRealtime() + VpnControl.MAX_ADMISSION_MILLIS)
                writeByteArray("synthetic-rejected-material".toByteArray())
            })
            assertEquals(1, transact(control, VpnControl.STOP))
            context.unbindService(connection); bound = false
            assertTrue("Only the captured Binder death proves retirement", exactDeath.await(6, TimeUnit.SECONDS))
        } finally {
            binder?.let { runCatching { transact(it, VpnControl.STOP) } }
            if (bound) context.unbindService(connection)
            binder?.let { runCatching { it.unlinkToDeath(recipient, 0) } }
        }
    }

    private fun transact(binder: IBinder, code: Int, fields: Parcel.() -> Unit = {}): Int {
        val input = Parcel.obtain(); val output = Parcel.obtain()
        return try {
            input.writeInterfaceToken(VpnControl.DESCRIPTOR); input.fields()
            check(binder.transact(code, input, output, 0)) { "CONTROL_TRANSACTION_REJECTED" }
            output.readException(); output.readInt()
        } finally { input.recycle(); output.recycle() }
    }
}
