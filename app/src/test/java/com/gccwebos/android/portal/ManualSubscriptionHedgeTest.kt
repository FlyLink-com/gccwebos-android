package com.gccwebos.android.portal

import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

private const val HEDGE_URL = "https://third-party.example/sub?token=synthetic"

/** One scripted behaviour per call, consumed in order; an exhausted script hangs forever. */
private sealed interface Script {
    object Hang : Script
    class Fail(val error: IOException = IOException("synthetic")) : Script
    class Ok(val bytes: ByteArray) : Script
    class Status(val code: Int) : Script
}

private class ScriptedCalls(private vararg val scripts: Script) : Call.Factory {
    class Handle(val call: Call, val callback: Callback, val request: Request) {
        var cancelled = false
        var closed = false
    }
    val handles = mutableListOf<Handle>()
    val calls get() = handles.size
    override fun newCall(request: Request): Call {
        val index = handles.size
        var handle: Handle? = null
        val call = object : Call {
            override fun request() = request
            override fun execute(): Response = error("Blocking calls forbidden")
            override fun enqueue(responseCallback: Callback) {
                val created = Handle(this, responseCallback, request); handle = created; handles.add(created)
                when (val script = scripts.getOrNull(index) ?: Script.Hang) {
                    Script.Hang -> Unit
                    is Script.Fail -> responseCallback.onFailure(this, script.error)
                    is Script.Ok -> respond(created, 200, script.bytes)
                    is Script.Status -> respond(created, script.code, byteArrayOf(1))
                }
            }
            override fun cancel() { handle?.cancelled = true }
            override fun isExecuted() = true
            override fun isCanceled() = handle?.cancelled == true
            override fun timeout() = Timeout.NONE
            override fun clone(): Call = newCall(request)
        }
        return call
    }
    /** Late completion of a hung call. */
    fun complete(index: Int, bytes: ByteArray) = respond(handles[index], 200, bytes)
    private fun respond(handle: Handle, code: Int, bytes: ByteArray) {
        val body = object : ResponseBody() {
            private val buffer = Buffer().write(bytes)
            override fun contentType() = null
            override fun contentLength() = bytes.size.toLong()
            override fun source() = buffer
            override fun close() { handle.closed = true; super.close() }
        }
        val response = Response.Builder().request(handle.request).protocol(Protocol.HTTP_1_1).code(code)
            .message("fixture").body(body).build()
        handle.callback.onResponse(handle.call, response)
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ManualSubscriptionHedgeTest {
    private val yaml = "proxies: []".toByteArray()

    private fun transport(primary: ScriptedCalls, hedge: ScriptedCalls = primary, after: Long = 5_000) =
        ManualSubscriptionTransport(primary, hedge, after)

    @Test fun aPromptFirstAnswerMakesExactlyOneRequest() = runTest {
        val primary = ScriptedCalls(Script.Ok(yaml))
        val hedge = ScriptedCalls(Script.Ok("other".toByteArray()))
        val result = transport(primary, hedge).read(Secret(HEDGE_URL))
        assertArrayEquals(yaml, result.bytes)
        assertEquals(1, primary.calls); assertEquals(0, hedge.calls)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun aStalledFirstRequestIsHedgedAfterTheDelayAndTheSecondWins() = runTest {
        val primary = ScriptedCalls(Script.Hang)
        val hedge = ScriptedCalls(Script.Ok(yaml))
        val result = transport(primary, hedge).read(Secret(HEDGE_URL))
        assertArrayEquals(yaml, result.bytes)
        assertEquals(5_000L, testScheduler.currentTime)
        assertEquals(1, primary.calls); assertEquals(1, hedge.calls)
        assertTrue("The stalled request must be cancelled", primary.handles[0].cancelled)
        assertEquals(HEDGE_URL, hedge.handles[0].request.url.toString())
    }

    @Test fun aNetworkErrorStartsTheSecondRequestImmediately() = runTest {
        val primary = ScriptedCalls(Script.Fail())
        val hedge = ScriptedCalls(Script.Ok(yaml))
        val result = transport(primary, hedge).read(Secret(HEDGE_URL))
        assertArrayEquals(yaml, result.bytes)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(1, primary.calls); assertEquals(1, hedge.calls)
    }

    @Test fun aDefinitiveServerAnswerIsNeverRetried() = runTest {
        for ((code, reason) in listOf(404 to PortalFailure.Reason.SUBSCRIPTION_REVOKED, 403 to PortalFailure.Reason.SUBSCRIPTION_REVOKED,
            500 to PortalFailure.Reason.SUBSCRIPTION_UNAVAILABLE)) {
            val primary = ScriptedCalls(Script.Status(code))
            val hedge = ScriptedCalls(Script.Ok(yaml))
            try { transport(primary, hedge).read(Secret(HEDGE_URL)); fail() }
            catch (failure: PortalFailure) { assertEquals(reason, failure.reason) }
            assertEquals(1, primary.calls); assertEquals(0, hedge.calls)
        }
    }

    @Test fun aDefinitiveAnswerFromTheHedgedRequestStopsTheStalledOne() = runTest {
        val primary = ScriptedCalls(Script.Hang)
        val hedge = ScriptedCalls(Script.Status(404))
        try { transport(primary, hedge).read(Secret(HEDGE_URL)); fail() }
        catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.SUBSCRIPTION_REVOKED, failure.reason) }
        assertTrue(primary.handles[0].cancelled)
    }

    @Test fun twoNetworkFailuresReportOneNetworkFailure() = runTest {
        val primary = ScriptedCalls(Script.Fail())
        val hedge = ScriptedCalls(Script.Fail())
        try { transport(primary, hedge).read(Secret(HEDGE_URL)); fail() }
        catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.NETWORK, failure.reason); assertNull(failure.cause) }
        assertEquals(1, primary.calls); assertEquals(1, hedge.calls)
    }

    @Test fun aSlowFirstRequestThatFinishesBeforeTheSecondStillWins() = runTest {
        val primary = ScriptedCalls(Script.Hang)
        val hedge = ScriptedCalls(Script.Hang)
        var result: SubscriptionRead? = null
        val job = launch { result = transport(primary, hedge).read(Secret(HEDGE_URL)) }
        advanceTimeBy(6_000); runCurrent()
        assertEquals(1, hedge.calls)
        primary.complete(0, yaml)
        job.join()
        assertArrayEquals(yaml, result!!.bytes)
        assertTrue(hedge.handles[0].cancelled)
    }

    @Test fun theLosersLateResponseIsClosedAndNeverReturned() = runTest {
        val primary = ScriptedCalls(Script.Hang)
        val hedge = ScriptedCalls(Script.Ok(yaml))
        val result = transport(primary, hedge).read(Secret(HEDGE_URL))
        assertArrayEquals(yaml, result.bytes)
        primary.complete(0, "late-loser".toByteArray())
        assertTrue(primary.handles[0].closed)
        assertArrayEquals(yaml, result.bytes)
    }

    @Test fun cancellingTheCallerCancelsBothRequests() = runTest {
        val primary = ScriptedCalls(Script.Hang)
        val hedge = ScriptedCalls(Script.Hang)
        val job = launch { transport(primary, hedge).read(Secret(HEDGE_URL)); fail("Cancelled result must not transfer") }
        advanceTimeBy(6_000); runCurrent()
        job.cancelAndJoin()
        assertTrue(primary.handles[0].cancelled); assertTrue(hedge.handles[0].cancelled)
    }

    @Test fun theTotalBoundStillApplies() = runTest {
        val primary = ScriptedCalls(Script.Hang)
        val hedge = ScriptedCalls(Script.Hang)
        try { transport(primary, hedge).read(Secret(HEDGE_URL)); fail() }
        catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.NETWORK, failure.reason) }
        assertEquals(30_000L, testScheduler.currentTime)
        assertTrue(primary.handles[0].cancelled); assertTrue(hedge.handles[0].cancelled)
    }

    @Test fun theHedgedRequestStartsOnADifferentAddressFamilyWhenOneIsAvailable() {
        val v6a = InetAddress.getByName("2606:4700:3033::6815:bab"); val v6b = InetAddress.getByName("2606:4700:3035::ac43:a6b0")
        val v4a = InetAddress.getByName("104.21.11.171"); val v4b = InetAddress.getByName("172.67.166.176")
        assertEquals(listOf(v4a, v4b, v6a, v6b), PublicSubscriptionDns.alternateOrder(listOf(v6a, v6b, v4a, v4b)))
        assertEquals(listOf(v6a, v6b, v4a, v4b), PublicSubscriptionDns.alternateOrder(listOf(v4a, v4b, v6a, v6b)))
        assertEquals(listOf(v4b, v4a), PublicSubscriptionDns.alternateOrder(listOf(v4a, v4b)))
        assertEquals(listOf(v4a), PublicSubscriptionDns.alternateOrder(listOf(v4a)))
        assertTrue(PublicSubscriptionDns.alternateOrder(listOf(v6a, v4a))[0] !is Inet6Address)
    }

    @Test fun theAlternateResolverKeepsTheSameCheckedAnswersAndRejectionRules() {
        val answers = listOf(InetAddress.getByName("2606:4700:4700::1111"), InetAddress.getByName("8.8.8.8"))
        val dns = PublicSubscriptionDns(object : Dns { override fun lookup(hostname: String) = answers }, otherFamilyFirst = true)
        assertEquals(answers.reversed(), dns.lookup("third-party.example"))
        val bad = PublicSubscriptionDns(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")) },
            otherFamilyFirst = true)
        assertThrows(java.net.UnknownHostException::class.java) { bad.lookup("third-party.example") }
    }

    @Test fun theProductionTransportUsesTwoIsolatedClientsWithTheSameGuards() {
        val primary = manualSubscriptionClient()
        val alternate = primary.newBuilder().dns(PublicSubscriptionDns(otherFamilyFirst = true)).build()
        assertEquals(primary.proxy, alternate.proxy)
        assertEquals(primary.cookieJar, alternate.cookieJar)
        assertEquals(primary.followRedirects, alternate.followRedirects)
        assertFalse(alternate.retryOnConnectionFailure)
        assertNotNull(ManualSubscriptionTransport())
    }
}

/** Import-only budget: 60 s overall, hedge at 15 s or on a network error. Connect keeps the #58 behaviour. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ManualSubscriptionImportBudgetTest {
    private val yaml = "proxies: []".toByteArray()

    private fun transport(connect: ScriptedCalls = ScriptedCalls(), connectHedge: ScriptedCalls = connect,
        import: ScriptedCalls = connect, importHedge: ScriptedCalls = import) =
        ManualSubscriptionTransport(connect, connectHedge, 5_000, import, importHedge, 15_000, 60_000)

    @Test fun importUsesItsOwnClientsAndConnectKeepsTheOriginalOnes() = runTest {
        val connect = ScriptedCalls(Script.Ok(yaml)); val import = ScriptedCalls(Script.Ok(yaml))
        val t = transport(connect, connect, import, import)
        t.readForImport(Secret(HEDGE_URL))
        assertEquals(0, connect.calls); assertEquals(1, import.calls)
        t.read(Secret(HEDGE_URL))
        assertEquals(1, connect.calls); assertEquals(1, import.calls)
        assertEquals("import", import.handles[0].request.tag(ReadLabel::class.java)!!.kind)
        assertEquals("primary", import.handles[0].request.tag(ReadLabel::class.java)!!.attempt)
        assertEquals("connect", connect.handles[0].request.tag(ReadLabel::class.java)!!.kind)
    }

    @Test fun theImportHedgeStartsAtFifteenSecondsNotFive() = runTest {
        val import = ScriptedCalls(Script.Hang); val hedge = ScriptedCalls(Script.Hang)
        val t = transport(import = import, importHedge = hedge)
        val job = launch { t.readForImport(Secret(HEDGE_URL)) }
        advanceTimeBy(14_999); runCurrent()
        assertEquals("no hedge before 15 s", 0, hedge.calls)
        advanceTimeBy(2); runCurrent()
        assertEquals(1, hedge.calls)
        assertEquals("hedge", hedge.handles[0].request.tag(ReadLabel::class.java)!!.attempt)
        job.cancelAndJoin()
    }

    @Test fun connectStillHedgesAtFiveSecondsAndGivesUpAtThirty() = runTest {
        val connect = ScriptedCalls(Script.Hang); val hedge = ScriptedCalls(Script.Hang)
        val t = transport(connect, hedge)
        try { t.read(Secret(HEDGE_URL)); fail() } catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.NETWORK, failure.reason) }
        assertEquals(30_000L, testScheduler.currentTime)
        assertEquals(1, hedge.calls)
    }

    @Test fun anImportNetworkErrorStillHedgesImmediately() = runTest {
        val import = ScriptedCalls(Script.Fail()); val hedge = ScriptedCalls(Script.Ok(yaml))
        val result = transport(import = import, importHedge = hedge).readForImport(Secret(HEDGE_URL))
        assertArrayEquals(yaml, result.bytes)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun anImportGivesUpAtSixtySecondsWithOneNetworkFailure() = runTest {
        val import = ScriptedCalls(Script.Hang); val hedge = ScriptedCalls(Script.Hang)
        try { transport(import = import, importHedge = hedge).readForImport(Secret(HEDGE_URL)); fail() }
        catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.NETWORK, failure.reason); assertNull(failure.cause) }
        assertEquals(60_000L, testScheduler.currentTime)
        assertTrue(import.handles[0].cancelled); assertTrue(hedge.handles[0].cancelled)
    }

    @Test fun aServerThatAnswersAfterFortySecondsStillImportsButWouldFailAConnect() = runTest {
        val import = ScriptedCalls(Script.Hang); val hedge = ScriptedCalls(Script.Hang)
        var result: SubscriptionRead? = null
        val job = launch { result = transport(import = import, importHedge = hedge).readForImport(Secret(HEDGE_URL)) }
        advanceTimeBy(40_000); runCurrent()
        import.complete(0, yaml)
        job.join()
        assertArrayEquals(yaml, result!!.bytes)
        assertTrue(hedge.handles[0].cancelled)
    }

    @Test fun definitiveAnswersAreNeverRetriedDuringAnImport() = runTest {
        val import = ScriptedCalls(Script.Status(404)); val hedge = ScriptedCalls(Script.Ok(yaml))
        try { transport(import = import, importHedge = hedge).readForImport(Secret(HEDGE_URL)); fail() }
        catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.SUBSCRIPTION_REVOKED, failure.reason) }
        assertEquals(0, hedge.calls)
    }

    @Test fun cancellingAnImportCancelsBothRequests() = runTest {
        val import = ScriptedCalls(Script.Hang); val hedge = ScriptedCalls(Script.Hang)
        val job = launch { transport(import = import, importHedge = hedge).readForImport(Secret(HEDGE_URL)); fail("cancelled") }
        advanceTimeBy(16_000); runCurrent()
        job.cancelAndJoin()
        assertTrue(import.handles[0].cancelled); assertTrue(hedge.handles[0].cancelled)
    }

    @Test fun aSourceWithoutAnImportPolicyReadsExactlyLikeRead() = runTest {
        val plain = SubscriptionSource { byteArrayOf(7) }
        assertArrayEquals(byteArrayOf(7), plain.readForImport(Secret(HEDGE_URL)).bytes)
    }

    @Test fun theImportClientIsTheSameIsolatedClientWithOnlyTheBudgetAndFallbackChanged() {
        val connect = manualSubscriptionClient(); val import = manualSubscriptionImportClient()
        assertEquals(connect.proxy, import.proxy); assertSame(connect.cookieJar, import.cookieJar)
        assertSame(connect.authenticator, import.authenticator); assertSame(connect.proxyAuthenticator, import.proxyAuthenticator)
        assertNull(import.cache); assertFalse(import.followRedirects); assertFalse(import.followSslRedirects)
        assertTrue(import.interceptors.isEmpty()); assertTrue(import.networkInterceptors.isEmpty())
        assertTrue(import.dns is PublicSubscriptionDns)
        // Changed on purpose, import only.
        assertTrue(import.retryOnConnectionFailure)
        assertEquals(6_000, import.connectTimeoutMillis); assertEquals(55_000, import.readTimeoutMillis); assertEquals(60_000, import.callTimeoutMillis)
        // The connect client is untouched.
        assertFalse(connect.retryOnConnectionFailure)
        assertEquals(15_000, connect.connectTimeoutMillis); assertEquals(20_000, connect.readTimeoutMillis); assertEquals(30_000, connect.callTimeoutMillis)
        assertSame(ReadTimingListener.factory, import.eventListenerFactory)
    }

    @Test fun interleavingAlternatesFamiliesKeepsEveryAddressAndEachFamiliesOrder() {
        val v6 = listOf("2606:4700:3033::6815:bab", "2606:4700:3035::ac43:a6b0", "2606:4700:4700::1111").map(InetAddress::getByName)
        val v4 = listOf("104.21.11.171", "172.67.166.176").map(InetAddress::getByName)
        assertEquals(listOf(v6[0], v4[0], v6[1], v4[1], v6[2]), PublicSubscriptionDns.interleaveFamilies(v6 + v4))
        assertEquals(listOf(v4[0], v6[0], v4[1], v6[1], v6[2]), PublicSubscriptionDns.interleaveFamilies(v4 + v6))
        assertEquals(v4, PublicSubscriptionDns.interleaveFamilies(v4))
        assertEquals((v6 + v4).toSet(), PublicSubscriptionDns.interleaveFamilies(v6 + v4).toSet())
    }

    @Test fun theImportResolverInterleavesAfterTheOtherFamilyChoiceAndStillRejectsUnsafeAnswers() {
        val v6 = listOf("2606:4700:3033::6815:bab", "2606:4700:3035::ac43:a6b0").map(InetAddress::getByName)
        val v4 = listOf("104.21.11.171", "172.67.166.176").map(InetAddress::getByName)
        val resolver = object : Dns { override fun lookup(hostname: String) = v6 + v4 }
        assertEquals(listOf(v6[0], v4[0], v6[1], v4[1]), PublicSubscriptionDns(resolver, interleave = true).lookup("third-party.example"))
        assertEquals(listOf(v4[0], v6[0], v4[1], v6[1]), PublicSubscriptionDns(resolver, otherFamilyFirst = true, interleave = true).lookup("third-party.example"))
        val bad = PublicSubscriptionDns(object : Dns { override fun lookup(hostname: String) = listOf(v4[0], InetAddress.getByName("127.0.0.1")) }, interleave = true)
        assertThrows(java.net.UnknownHostException::class.java) { bad.lookup("third-party.example") }
    }

    @Test fun debugMarksNameTheKindAndReasonAndNeverTheUrl() = runTest {
        val marks = mutableListOf<String>()
        val previous = PrepTrace.sink
        PrepTrace.sink = { marks += it }
        try {
            val import = ScriptedCalls(Script.Hang); val hedge = ScriptedCalls(Script.Ok(yaml))
            transport(import = import, importHedge = hedge).readForImport(Secret(HEDGE_URL))
            val ok = ScriptedCalls(Script.Ok(yaml))
            transport(ok).read(Secret(HEDGE_URL))
        } finally { PrepTrace.sink = previous }
        assertTrue(marks.toString(), marks.any { it.startsWith("read-hedge-start import after=") && it.endsWith("reason=slow") })
        assertTrue(marks.toString(), marks.any { it.startsWith("read-result import winner=hedge total=") })
        assertTrue(marks.toString(), marks.any { it.startsWith("read-result connect winner=primary total=") })
        assertTrue(marks.none { "third-party" in it || "token" in it || "synthetic" in it || "https" in it })
    }
}

class ReadTimingListenerTest {
    private var time = 0L
    private val lines = mutableListOf<String>()
    private val call = object : Call {
        override fun request() = Request.Builder().url("https://third-party.example/sub?token=synthetic").build()
        override fun execute(): Response = error("unused")
        override fun enqueue(responseCallback: Callback) = Unit
        override fun cancel() = Unit
        override fun isExecuted() = true
        override fun isCanceled() = false
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = this
    }
    private fun listener(label: ReadLabel? = ReadLabel("import", "primary")) = ReadTimingListener(label, { time }, { lines += it })
    private fun at(ms: Long) { time = ms }
    private val v6 = java.net.InetSocketAddress(InetAddress.getByName("2606:4700:4700::1111"), 443)
    private val v4 = java.net.InetSocketAddress(InetAddress.getByName("8.8.8.8"), 443)

    @Test fun oneLineSummarisesEveryPhaseAndAFailedFirstAddress() {
        val l = listener()
        at(1_000); l.callStart(call)
        l.dnsStart(call, "third-party.example"); at(1_040)
        l.dnsEnd(call, "third-party.example", listOf(v6.address, v4.address))
        l.connectStart(call, v6, java.net.Proxy.NO_PROXY); at(7_040)
        l.connectFailed(call, v6, java.net.Proxy.NO_PROXY, null, java.net.SocketTimeoutException("third-party.example/token"))
        l.connectStart(call, v4, java.net.Proxy.NO_PROXY)
        at(7_100); l.secureConnectStart(call); at(7_300); l.secureConnectEnd(call, null)
        at(7_320); l.connectEnd(call, v4, java.net.Proxy.NO_PROXY, null)
        at(7_330); l.requestHeadersEnd(call, call.request())
        at(17_330); l.responseHeadersStart(call); at(17_340)
        l.responseHeadersEnd(call, Response.Builder().request(call.request()).protocol(Protocol.HTTP_2).code(200).message("ok").build())
        at(17_390); l.responseBodyEnd(call, 4096); l.callEnd(call)
        assertEquals(1, lines.size)
        assertEquals("read-timing import/primary dns=40ms(2 addr v4=1,v6=1) connect=[#1 v6 6000ms fail(SocketTimeoutException), #2 v4 280ms (incl tls) ok] " +
            "tls=200ms wait=10000ms body=50ms total=16390ms result=ok", lines[0])
    }

    @Test fun aFailureNamesThePhaseAndExceptionClassOnly() {
        val l = listener(ReadLabel("connect", "hedge"))
        at(0); l.callStart(call); l.dnsStart(call, "third-party.example"); at(5)
        l.dnsEnd(call, "third-party.example", listOf(v4.address))
        l.connectStart(call, v4, java.net.Proxy.NO_PROXY); at(15_005)
        l.connectFailed(call, v4, java.net.Proxy.NO_PROXY, null, java.net.SocketTimeoutException("secret-detail"))
        l.callFailed(call, java.net.SocketTimeoutException("secret-detail"))
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].startsWith("read-timing connect/hedge dns=5ms(1 addr v4=1,v6=0) connect=[#1 v4 15000ms fail(SocketTimeoutException)] tls=- wait=- body=- total=15005ms result=failed in connect (SocketTimeoutException)"))
    }

    @Test fun aReusedConnectionHasNoDnsOrConnectPhaseAndNothingIsPrintedTwice() {
        val l = listener(null)
        at(0); l.callStart(call); at(3); l.requestHeadersEnd(call, call.request()); at(120); l.responseHeadersStart(call)
        l.callEnd(call); l.callFailed(call, java.io.IOException("Canceled"))
        assertEquals(listOf("read-timing ?/? dns=- connect=[-] tls=- wait=117ms body=- total=120ms result=ok"), lines)
    }

    @Test fun nothingSensitiveIsEverPrinted() {
        val l = listener()
        l.callStart(call); l.dnsStart(call, "third-party.example"); l.dnsEnd(call, "third-party.example", listOf(v4.address))
        l.connectStart(call, v4, java.net.Proxy.NO_PROXY)
        l.connectFailed(call, v4, java.net.Proxy.NO_PROXY, null, java.io.IOException("https://third-party.example/sub?token=synthetic 8.8.8.8"))
        l.callFailed(call, java.io.IOException("https://third-party.example/sub?token=synthetic 8.8.8.8"))
        for (text in listOf("third-party", "token", "synthetic", "8.8.8.8", "2606", "https", "/sub")) assertFalse("$text in ${lines[0]}", text in lines[0])
    }

    @Test fun releaseBuildsGetNoListenerBecauseThereIsNoSink() {
        val previous = PrepTrace.sink
        try {
            PrepTrace.sink = null
            assertSame(okhttp3.EventListener.NONE, ReadTimingListener.factory.create(call))
            PrepTrace.sink = { }
            assertTrue(ReadTimingListener.factory.create(call) is ReadTimingListener)
        } finally { PrepTrace.sink = previous }
    }
}
