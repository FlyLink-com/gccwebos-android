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
