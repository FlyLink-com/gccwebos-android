package com.gccwebos.android.vpn

import com.gccwebos.android.core.ConnectionTransportLease
import com.gccwebos.android.core.PreparationFailure
import com.gccwebos.android.core.PreparationRejected
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidConfigAddressPreparerTest {
    private val host = "synthetic.fixture.invalid"
    private val raw = """
        proxies:
          - name: synthetic node
            type: hysteria2
            server: synthetic.fixture.invalid
            port: 18443
            password: synthetic-only
            sni: synthetic.fixture.invalid
    """.trimIndent().toByteArray()
    private class Lease : ConnectionTransportLease {
        override val networkHandle = 17L
        override val validUntilElapsedMillis = 30_000L
        var current = true
        var closes = 0
        override fun isCurrent() = current && closes == 0
        override fun close() { closes++; current = false }
    }
    private class Network : AddressPreparationNetwork {
        override val lease = Lease()
        var readback = true
        var calls = 0
        var lookup: suspend () -> List<String> = { listOf("192.0.2.19") }
        override fun readbackCurrent() = readback
        override suspend fun resolveA(hostname: String): List<String> { calls++; return lookup() }
    }
    private suspend fun failure(expected: PreparationFailure, block: suspend () -> Unit) {
        try { block(); fail("Preparation must fail closed") }
        catch (actual: PreparationRejected) {
            assertEquals(expected, actual.reason)
            assertEquals(expected.name, actual.message)
            assertNull(actual.cause)
        }
    }
    @Test fun hostnameProjectsOnlySelectedAddressAndTransfersOneLease() = runTest {
        val network = Network(); var reservations = 0
        val original = raw.copyOf()
        val result = AndroidConfigAddressPreparer(reserveNetwork = { reservations++; network }).prepare(raw, 0)
        val bytes = result.bytes
        try {
            val node = JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("proxies").getJSONObject(0)
            assertTrue("Synthetic resolved address changed", node.getString("server") == "192.0.2.19")
            assertTrue("TLS identity changed", node.getString("sni") == host)
            assertTrue("Synthetic credential changed", node.getString("password") == "synthetic-only")
            assertArrayEquals(original, raw)
            assertSame(network.lease, result.lease)
            assertEquals(1, reservations); assertEquals(1, network.calls); assertEquals(0, network.lease.closes)
        } finally { result.close() }
        assertTrue(bytes.all { it == 0.toByte() }); assertEquals(1, network.lease.closes)
    }
    @Test fun literalStillReservesAndTransfersPhysicalLeaseWithoutDns() = runTest {
        val network = Network(); var reservations = 0
        AndroidConfigAddressPreparer(reserveNetwork = { reservations++; network }).prepare(
            String(raw).replace(host, "192.0.2.19").replace("sni: 192.0.2.19", "sni: $host").toByteArray(), 0).use {
            assertSame(network.lease, it.lease); assertEquals(1, reservations); assertEquals(0, network.calls)
        }
        assertEquals(1, network.lease.closes)
    }
    @Test fun invalidOtherNodeBlocksBeforeNetworkReservation() = runTest {
        var reservations = 0
        val second = String(raw).substringAfter("proxies:\n") + "\n    hop-interval: 10"
        try {
            AndroidConfigAddressPreparer(reserveNetwork = { reservations++; Network() }).prepare((String(raw) + "\n" + second).toByteArray(), 0)
            fail("Other incompatible node must block before reservation")
        } catch (_: UnsafeProxyConfiguration) { }
        assertEquals(0, reservations)
    }
    @Test fun noAOrOnlyInvalidAResultsCannotReturnPreparedMaterial() = runTest {
        for (answers in listOf(emptyList(), listOf("0.0.0.0", "224.0.0.1", "01.2.3.4", "2001:db8::1"))) {
            val network = Network().apply { lookup = { answers } }
            failure(PreparationFailure.NETWORK) { AndroidConfigAddressPreparer(reserveNetwork = { network }).prepare(raw, 0) }
            assertEquals(1, network.calls); assertEquals(1, network.lease.closes)
        }
    }
    @Test fun staleBeforeDnsClosesReservationWithoutLookup() = runTest {
        val network = Network().apply { lease.current = false }
        failure(PreparationFailure.STALE) { AndroidConfigAddressPreparer(reserveNetwork = { network }).prepare(raw, 0) }
        assertEquals(0, network.calls); assertEquals(1, network.lease.closes)
    }
    @Test fun networkSwitchDuringLookupDiscardsAnswerAndClosesLease() = runTest {
        val network = Network().apply { lookup = { readback = false; listOf("192.0.2.19") } }
        failure(PreparationFailure.STALE) { AndroidConfigAddressPreparer(reserveNetwork = { network }).prepare(raw, 0) }
        assertEquals(1, network.lease.closes)
    }
    @Test fun leaseExpiryDuringLookupDiscardsAnswer() = runTest {
        val network = Network().apply { lookup = { lease.current = false; listOf("192.0.2.19") } }
        failure(PreparationFailure.STALE) { AndroidConfigAddressPreparer(reserveNetwork = { network }).prepare(raw, 0) }
        assertEquals(1, network.lease.closes)
    }
    @Test fun timeoutCancelsLookupAndClosesLeaseWithFixedFailure() = runTest {
        val pending = CompletableDeferred<List<String>>()
        var returned = false
        val network = Network().apply { lookup = { try { pending.await() } finally { returned = true } } }
        failure(PreparationFailure.TIMEOUT) { AndroidConfigAddressPreparer(reserveNetwork = { network }).prepare(raw, 0) }
        assertTrue(returned); assertEquals(1, network.lease.closes)
    }
    @Test fun cancellationCannotReturnLateAnswerOrRetainLease() = runTest {
        val pending = CompletableDeferred<List<String>>()
        val network = Network().apply { lookup = { pending.await() } }
        val work = async { AndroidConfigAddressPreparer(reserveNetwork = { network }).prepare(raw, 0) }
        runCurrent(); work.cancel(); work.join(); pending.complete(listOf("192.0.2.19")); runCurrent()
        assertTrue(work.isCancelled); assertEquals(1, network.lease.closes)
    }
    @Test fun resolverExceptionDoesNotExposeOriginalMessageOrCause() = runTest {
        val network = Network().apply { lookup = { error("synthetic-private-resolution-marker") } }
        failure(PreparationFailure.NETWORK) { AndroidConfigAddressPreparer(reserveNetwork = { network }).prepare(raw, 0) }
        assertEquals(1, network.lease.closes)
    }
}
