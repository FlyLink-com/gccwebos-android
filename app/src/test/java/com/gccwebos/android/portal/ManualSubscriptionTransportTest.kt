package com.gccwebos.android.portal

import com.gccwebos.android.vpn.SafeProxyConfig
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.time.Instant
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

private const val EXTERNAL_URL = "https://third-party.example/sub?token=synthetic%2Fvalue&target=clash&token=second+value"
private fun fakeDns(resolve: (String) -> List<InetAddress>) = object : Dns {
    override fun lookup(hostname: String) = resolve(hostname)
}

class ManualSubscriptionUrlPolicyTest {
    @Test fun validQueryAndPathBytesAreRetainedWithoutReordering() {
        for (url in listOf(EXTERNAL_URL, "https://Third-Party.example:443/a%2fb?x=%2f&x=+&empty=", "https://third-party.example?", "https://8.8.8.8/sub", "https://[2606:4700:4700::1111]/sub")) {
            assertEquals(url, ManualSubscriptionUrlPolicy.validate(url))
        }
    }

    @Test fun rejectsAmbiguousOrUnsafeUrlsWithNoInputInFailure() {
        for (url in listOf("http://third-party.example/sub", "https://user:synthetic@third-party.example/sub", "https://third-party.example/sub#fragment", "https://third-party.example:8443/sub", "https://third-party.example:0443/sub", "https://third-party.example./sub", "https://localhost/sub", "https://a.local/sub", "https://singlelabel/sub", "https://third_party.example/sub", "https://third-party.example/a/../sub", "https://third-party.example/%2e%2e/sub", "https://third-party.example/\\sub", "https://third-party.example/sub\n", "https://third-party.example/sub%0a", "https://third-party.example/sub%5c", "https://127.1/sub", "https://2130706433/sub", "https://0177.0.0.1/sub", "https://0x7f.0.0.1/sub", "https://[::ffff:8.8.8.8]/sub", "https://[2606:4700::1%25eth0]/sub", "https://third-party.example/" + "a".repeat(8192))) {
            val error = assertThrows(UnsupportedManualSubscriptionLink::class.java) { ManualSubscriptionUrlPolicy.validate(url) }
            assertEquals("Unsupported manual subscription link", error.message)
            assertNull(error.cause)
        }
    }

    @Test fun rejectsEveryNonPublicLiteralRange() {
        for (host in listOf("0.1.2.3", "10.2.3.4", "100.64.0.1", "100.127.255.255", "127.0.0.1", "169.254.169.254", "172.16.0.1", "172.31.255.255", "192.0.0.9", "192.0.2.1", "192.88.99.1", "192.168.0.1", "198.18.0.1", "198.19.255.255", "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1", "255.255.255.255", "[::]", "[::1]", "[fc00::1]", "[fe80::1]", "[ff02::1]", "[64:ff9b::808:808]", "[2001::1]", "[2001:db8::1]", "[2002:808:808::1]", "[3fff::1]")) {
            assertThrows(UnsupportedManualSubscriptionLink::class.java) { ManualSubscriptionUrlPolicy.validate("https://$host/sub") }
        }
    }
}

class ManualSubscriptionDnsTest {
    @Test fun theExactCheckedAnswersAreReturnedToTheConnectionResolver() {
        val addresses = listOf(address("8.8.8.8"), address("2606:4700:4700::1111"))
        var lookups = 0
        val guarded = PublicSubscriptionDns(fakeDns { hostname -> assertEquals("third-party.example", hostname); lookups++; addresses })
        val selected = guarded.lookup("third-party.example")
        assertEquals(1, lookups)
        assertEquals(addresses, selected)
        assertSame(addresses[0], selected[0])
    }

    @Test fun mixedAnswersAndRebindingAreRejectedWithoutLeakingResolverDetails() {
        var lookups = 0
        val guarded = PublicSubscriptionDns(fakeDns { if (++lookups == 1) listOf(address("8.8.8.8")) else listOf(address("8.8.8.8"), address("127.0.0.1")) })
        assertEquals(1, guarded.lookup("third-party.example").size)
        assertThrows(UnknownHostException::class.java) { guarded.lookup("third-party.example") }
        val failure = assertThrows(UnknownHostException::class.java) { PublicSubscriptionDns(fakeDns { throw UnknownHostException("synthetic-sensitive-value") }).lookup("third-party.example") }
        assertEquals("Subscription DNS unavailable", failure.message)
        assertNull(failure.cause)
        assertThrows(UnknownHostException::class.java) { PublicSubscriptionDns(fakeDns { emptyList() }).lookup("third-party.example") }
    }

    @Test fun mappedIpv6AndReservedAnswersAreRejected() {
        val mapped = ByteArray(16).also { it[10] = -1; it[11] = -1; it[12] = 8; it[13] = 8; it[14] = 8; it[15] = 8 }
        for (bad in listOf(Inet6Address.getByAddress(null, mapped, -1), address("192.168.0.1"), address("2001:db8::1"))) {
            assertThrows(UnknownHostException::class.java) { PublicSubscriptionDns(fakeDns { listOf(bad) }).lookup("third-party.example") }
        }
    }

    @Test fun isolatedProductionClientHasNoAmbientCredentialsProxyOrCache() {
        val client = manualSubscriptionClient(fakeDns { listOf(address("8.8.8.8")) })
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertSame(Authenticator.NONE, client.authenticator)
        assertSame(Authenticator.NONE, client.proxyAuthenticator)
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertNull(client.cache)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertTrue(client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty())
        assertTrue(client.dns is PublicSubscriptionDns)
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(20_000, client.readTimeoutMillis)
        assertEquals(30_000, client.callTimeoutMillis)
    }

    private fun address(literal: String) = InetAddress.getByName(literal)
}

class ManualSubscriptionMetadataTest {
    @Test fun missingAndZeroAreUnreportedAndDoNotInventAnExpiry() {
        assertNull(manualSubscriptionExpiry(Headers.Builder().build()))
        assertNull(manualSubscriptionExpiry(headers("Subscription-Userinfo" to "upload=1; download=2; total=3")))
        assertNull(manualSubscriptionExpiry(headers("Subscription-Userinfo" to "expire=0")))
    }

    @Test fun parsesUnixExpiryAndUsesEarlierOfIndependentHeaders() {
        val early = Instant.parse("2026-11-01T00:00:00Z")
        val later = early.plusSeconds(100)
        assertEquals(early, manualSubscriptionExpiry(headers("Subscription-Userinfo" to "upload=0; download=0; total=0; expire=${early.epochSecond}")))
        assertEquals(early, manualSubscriptionExpiry(headers("Subscription-Userinfo" to "expire=${later.epochSecond}", "X-Subscription-Valid-Until" to early.toString())))
        assertEquals(early, manualSubscriptionExpiry(headers("Subscription-Userinfo" to "expire=${early.epochSecond}", "X-Subscription-Valid-Until" to later.toString())))
        assertEquals(early, manualSubscriptionExpiry(headers("Subscription-Userinfo" to "expire=0", "X-Subscription-Valid-Until" to early.toString())))
    }

    @Test fun malformedDuplicateAndOutOfRangeMetadataIsRejected() {
        for (value in listOf("", "expire", "expire=", "expire=-1", "expire=1.0", "expire=1e9", "expire=+1", "expire=01", "expire=999999999999999999999999999", "expire=253402300800", "expire=1; expire=1", "expire=1; Expire=2", "upload=bad; expire=1", "total=18446744073709551616", "expire=1, expire=2", "a=1;".repeat(30), "x=" + "a".repeat(2048))) {
            val error = assertThrows(ManualSubscriptionMetadataFailure::class.java) { manualSubscriptionExpiry(headers("Subscription-Userinfo" to value)) }
            assertEquals("Invalid subscription metadata", error.message)
            assertNull(error.cause)
        }
        for (values in listOf(listOf("Subscription-Userinfo" to "expire=1", "Subscription-Userinfo" to "expire=1"), listOf("X-Subscription-Valid-Until" to "2026-02-30T00:00:00Z"), listOf("X-Subscription-Valid-Until" to ""), listOf("X-Subscription-Valid-Until" to "2026-11-01T00:00:00Z", "X-Subscription-Valid-Until" to "2026-11-01T00:00:00Z"))) {
            assertThrows(ManualSubscriptionMetadataFailure::class.java) { manualSubscriptionExpiry(headers(*values.toTypedArray())) }
        }
    }

    private fun headers(vararg pairs: Pair<String, String>) = Headers.Builder().apply { pairs.forEach { add(it.first, it.second) } }.build()
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ManualSubscriptionTransportTest {
    @Test fun requestRetainsQueryAndHasOnlyFixedUnauthenticatedHeaders() = runTest {
        val fake = SubscriptionCalls("proxies: []".toByteArray())
        val result = ManualSubscriptionTransport(fake).read(Secret(EXTERNAL_URL))
        assertArrayEquals("proxies: []".toByteArray(), result.bytes)
        assertNull(result.validUntil)
        assertEquals(EXTERNAL_URL, fake.request!!.url.toString())
        for (key in listOf("Authorization", "Cookie", "X-Portal-Session", "Origin", "Referer", "Host", "Proxy-Authorization")) assertNull(fake.request!!.header(key))
        assertEquals("FeilianVPN/0.4 (Mihomo-compatible)", fake.request!!.header("User-Agent"))
        assertEquals("no-store", fake.request!!.header("Cache-Control"))
        assertEquals(1, fake.calls)
        assertTrue(fake.closed)
        result.bytes.fill(0)
    }

    @Test fun reportedUsageAndExpiryTravelWithTheDownloadAndZeroTotalIsUnknown() = runTest {
        val reported = SubscriptionCalls("proxies: []".toByteArray(), headers = Headers.headersOf("Subscription-Userinfo",
            "upload=1; download=2; total=30; expire=1798761600"))
        val result = ManualSubscriptionTransport(reported).read(Secret(EXTERNAL_URL))
        assertEquals(SubscriptionUsageReport(1, 2, 30), result.usage)
        assertEquals(Instant.ofEpochSecond(1798761600), result.validUntil)
        result.bytes.fill(0)
        val zero = SubscriptionCalls("proxies: []".toByteArray(), headers = Headers.headersOf("Subscription-Userinfo",
            "upload=0; download=0; total=0; expire=0"))
        val unknown = ManualSubscriptionTransport(zero).read(Secret(EXTERNAL_URL))
        assertNull(unknown.usage); assertNull(unknown.validUntil)
        unknown.bytes.fill(0)
        val malformed = SubscriptionCalls("proxies: []".toByteArray(), headers = Headers.headersOf("Subscription-Userinfo", "total=bad"))
        try { ManualSubscriptionTransport(malformed).read(Secret(EXTERNAL_URL)); fail() }
        catch (_: ManualSubscriptionMetadataFailure) { }
        assertTrue(malformed.closed)
    }

    @Test fun unsafeInputDoesNotCreateAnyNetworkCall() = runTest {
        val fake = SubscriptionCalls(byteArrayOf(1))
        try { ManualSubscriptionTransport(fake).read(Secret("https://127.0.0.1/sub")); fail() }
        catch (_: UnsupportedManualSubscriptionLink) { }
        assertEquals(0, fake.calls)
    }

    @Test fun everyRedirectIsRejectedWithNoFollowupAndResponseClosed() = runTest {
        for (status in listOf(300, 301, 302, 303, 304, 307, 308, 399)) {
            val fake = SubscriptionCalls(byteArrayOf(1), status = status)
            try { ManualSubscriptionTransport(fake).read(Secret(EXTERNAL_URL)); fail() }
            catch (failure: ManualSubscriptionRedirect) {
                assertFixedFailureChain(failure, ManualSubscriptionRedirect::class.java, "Subscription redirect unsupported")
            }
            assertEquals(1, fake.calls)
            assertTrue(fake.closed)
            assertTrue(fake.cancelledBeforeClose)
        }
    }

    @Test fun revokedStatusesAreIndependentOfUntrustedErrorBodies() = runTest {
        for (status in listOf(401, 403, 404, 410)) {
            val fake = SubscriptionCalls("synthetic-private-error".toByteArray(), status = status)
            try { ManualSubscriptionTransport(fake).read(Secret(EXTERNAL_URL)); fail() }
            catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.SUBSCRIPTION_REVOKED, failure.reason); assertNull(failure.cause) }
            assertTrue(fake.closed)
            assertTrue(fake.cancelledBeforeClose)
        }
    }

    @Test fun byteLimitAppliesToUnknownAndDecodedLengthAndAlwaysClosesBody() = runTest {
        for (size in listOf(SafeProxyConfig.MAX_BYTES, SafeProxyConfig.MAX_BYTES + 1)) {
            val fake = SubscriptionCalls(ByteArray(size) { 65 }, reportedLength = -1)
            try {
                val result = ManualSubscriptionTransport(fake).read(Secret(EXTERNAL_URL))
                assertEquals(SafeProxyConfig.MAX_BYTES, size)
                assertEquals(size, result.bytes.size)
                result.bytes.fill(0)
            } catch (failure: PortalFailure) { assertEquals(SafeProxyConfig.MAX_BYTES + 1, size); assertEquals(PortalFailure.Reason.PROTOCOL, failure.reason) }
            assertTrue(fake.closed)
            if (size > SafeProxyConfig.MAX_BYTES) assertTrue(fake.cancelledBeforeClose)
        }
        val oversize = SubscriptionCalls(ByteArray(2), reportedLength = SafeProxyConfig.MAX_BYTES.toLong() + 1)
        try { ManualSubscriptionTransport(oversize).read(Secret(EXTERNAL_URL)); fail() }
        catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.PROTOCOL, failure.reason) }
        assertTrue(oversize.closed)
        assertTrue(oversize.cancelledBeforeClose)
    }

    @Test fun wrongFinalUrlAndEmptyBodiesAreRejected() = runTest {
        for (fake in listOf(SubscriptionCalls(byteArrayOf(1), finalUrl = "https://other.example/sub"), SubscriptionCalls(byteArrayOf()))) {
            try { ManualSubscriptionTransport(fake).read(Secret(EXTERNAL_URL)); fail() }
            catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.PROTOCOL, failure.reason) }
            assertTrue(fake.closed)
        }
    }

    @Test fun cancellationCancelsHttpAndLateResponsesAreClosed() = runTest {
        val fake = SubscriptionCalls(byteArrayOf(1), delayed = true)
        val job = launch { ManualSubscriptionTransport(fake).read(Secret(EXTERNAL_URL)); fail("Cancelled result must not transfer") }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(fake.cancelled)
        fake.respond()
        assertTrue(fake.closed)
    }

    @Test fun totalTimeoutCancelsEvenWhenTheNetworkCallbackNeverArrives() = runTest {
        val fake = SubscriptionCalls(byteArrayOf(1), delayed = true)
        try { ManualSubscriptionTransport(fake).read(Secret(EXTERNAL_URL)); fail() }
        catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.NETWORK, failure.reason) }
        assertEquals(30_000L, testScheduler.currentTime)
        assertTrue(fake.cancelled)
        fake.respond()
        assertTrue(fake.closed)
    }

    @Test fun anOuterTimeoutKeepsItsCancellationMeaning() = runTest {
        val fake = SubscriptionCalls(byteArrayOf(1), delayed = true)
        try { withTimeout(10) { ManualSubscriptionTransport(fake).read(Secret(EXTERNAL_URL)) }; fail() }
        catch (_: TimeoutCancellationException) { }
        assertEquals(10L, testScheduler.currentTime)
        assertTrue(fake.cancelled)
    }

    @Test fun tlsTimeoutAndDnsFailuresAreSanitized() = runTest {
        for (failure in listOf(IOException(EXTERNAL_URL), javax.net.ssl.SSLHandshakeException(EXTERNAL_URL), java.net.SocketTimeoutException(EXTERNAL_URL), UnknownHostException(EXTERNAL_URL))) {
            val fake = SubscriptionCalls(byteArrayOf(1), failure = failure)
            try { ManualSubscriptionTransport(fake).read(Secret(EXTERNAL_URL)); fail() }
            catch (actual: PortalFailure) { assertEquals(PortalFailure.Reason.NETWORK, actual.reason); assertNull(actual.cause) }
        }
    }
}

/** Coroutines may add an identical exception copy for stack recovery; every cause must remain fixed. */
private fun assertFixedFailureChain(failure: Throwable, expectedClass: Class<out Throwable>, message: String) {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = failure
    while (current != null) {
        assertTrue("Failure cause chain must not contain a cycle", seen.add(current))
        assertEquals(expectedClass, current.javaClass)
        assertEquals(message, current.message)
        assertEquals("${expectedClass.name}: $message", current.toString())
        assertTrue("Suppressed failures could contain request or response material", current.suppressed.isEmpty())
        current = current.cause
    }
}

private class SubscriptionCalls(private val bytes: ByteArray, private val status: Int = 200,
    private val reportedLength: Long = bytes.size.toLong(), private val finalUrl: String? = null,
    private val delayed: Boolean = false, private val failure: IOException? = null,
    private val headers: Headers = Headers.headersOf()) : Call.Factory {
    var request: Request? = null
    var calls = 0
    var cancelled = false
    var closed = false
    var cancelledBeforeClose = false
    private var callback: Callback? = null
    private lateinit var call: Call
    override fun newCall(request: Request): Call {
        this.request = request; calls++
        return object : Call {
            override fun request() = request
            override fun execute(): Response = error("Blocking calls forbidden")
            override fun enqueue(responseCallback: Callback) { callback = responseCallback; if (!delayed) respond() }
            override fun cancel() { cancelled = true }
            override fun isExecuted() = true
            override fun isCanceled() = cancelled
            override fun timeout() = Timeout.NONE
            override fun clone(): Call = newCall(request)
        }.also { call = it }
    }
    fun respond() {
        if (failure != null) { callback!!.onFailure(call, failure); return }
        val body = object : ResponseBody() {
            private val buffer = Buffer().write(bytes)
            override fun contentType() = null
            override fun contentLength() = reportedLength
            override fun source() = buffer
            override fun close() { closed = true; cancelledBeforeClose = cancelled; super.close() }
        }
        val response = Response.Builder().request(if (finalUrl == null) request!! else request!!.newBuilder().url(finalUrl).build())
            .protocol(Protocol.HTTP_1_1).code(status).message("fixture").headers(headers).body(body).build()
        callback!!.onResponse(call, response)
    }
}
