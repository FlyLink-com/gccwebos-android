package com.gccwebos.android.portal

import com.gccwebos.android.vpn.SafeProxyConfig
import com.gccwebos.android.vpn.UnsafeProxyConfiguration
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Timeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReadSummaryTest {
    @After fun clear() { PrepTrace.sink = null }

    private fun cls(text: String) = ReadSummary.firstByteClass(text.toByteArray())

    @Test fun firstByteClassesAreCoarseAndCoverEveryShape() {
        assertEquals("json-object", cls("  \n{\"a\":1}"))
        assertEquals("json-array", cls("[1,2]"))
        assertEquals("xml/html", cls("<!DOCTYPE html><html>"))
        assertEquals("yaml-like", cls("proxies:\n  - name: x"))
        assertEquals("yaml-like", cls("# comment\nport: 7890"))
        assertEquals("yaml-like", cls("---\nproxies: []"))
        assertEquals("yaml-like", cls("mixed-port: 7890"))
        assertEquals("uri-list", cls("vless://abc@host:443?x=1#n\nhy2://z"))
        assertEquals("base64-like", cls("dmxlc3M6Ly9hYmNAaG9zdDo0NDM/eD0xI24K\ndGVzdA=="))
        assertEquals("other", cls("Too many requests, try again later"))
        assertEquals("empty", cls("  \n\t "))
        assertEquals("empty", ReadSummary.firstByteClass(ByteArray(0)))
        assertEquals("yaml-like", ReadSummary.firstByteClass(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "proxies: []".toByteArray()))
    }

    @Test fun contentTypeIsTypeAndSubtypeOnly() {
        assertEquals("text/plain", ReadSummary.contentType("text/plain; charset=utf-8; token=SECRET".toMediaTypeOrNull()))
        assertEquals("application/x-yaml", ReadSummary.contentType("Application/X-YAML".toMediaTypeOrNull()))
        assertEquals("none", ReadSummary.contentType(null))
    }

    @Test fun rejectionNamesOnlyTheClassAndEnumNeverTheMessage() {
        assertEquals("import-reject class=UnsafeProxyConfiguration reason=Protocol",
            ReadSummary.rejection(UnsafeProxyConfiguration(SafeProxyConfig.RejectionReason.Protocol)))
        assertEquals("import-reject class=PortalFailure reason=PROTOCOL",
            ReadSummary.rejection(PortalFailure(PortalFailure.Reason.PROTOCOL)))
        val other = ReadSummary.rejection(IllegalStateException("token=SECRET-MESSAGE"))
        assertEquals("import-reject class=IllegalStateException", other)
        assertFalse(other.contains("SECRET"))
    }

    @Test fun nothingIsBuiltWhenNoDebugSinkIsInstalled() {
        PrepTrace.sink = null
        var built = false
        PrepTrace.markLazy { built = true; "x" }
        assertFalse(built)
    }

    private class OneResponse(private val bytes: ByteArray, private val type: String, private val encoding: String?) : Call.Factory {
        override fun newCall(request: Request): Call = object : Call {
            override fun request() = request
            override fun execute(): Response = error("no")
            override fun enqueue(responseCallback: Callback) {
                val body = object : ResponseBody() {
                    private val buffer = Buffer().write(bytes)
                    override fun contentType() = type.toMediaTypeOrNull()
                    override fun contentLength() = bytes.size.toLong()
                    override fun source() = buffer
                }
                val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("ok").body(body)
                if (encoding != null) builder.header("Content-Encoding", encoding)
                responseCallback.onResponse(this, builder.build())
            }
            override fun cancel() = Unit
            override fun isExecuted() = true
            override fun isCanceled() = false
            override fun timeout() = Timeout.NONE
            override fun clone(): Call = this
        }
    }

    @Test fun theSummaryLineCarriesOnlyTheAgreedFieldsAndNothingSensitive() = runTest {
        val lines = mutableListOf<String>()
        PrepTrace.sink = { lines += it }
        val body = "SECRET-BODY-FRAGMENT: proxies".toByteArray()
        val calls = OneResponse(body, "text/plain; charset=utf-8; token=SECRET-PARAM", "gzip")
        val url = "https://third-party.example/sub?token=SECRET-URL-TOKEN"
        ManualSubscriptionTransport(calls).readForImport(Secret(url))
        val summary = lines.single { it.startsWith("read-summary") }
        assertEquals("read-summary import/primary http=200 type=text/plain bytes=${body.size} gzip=yes first=yaml-like", summary)
        lines.forEach { line ->
            listOf("SECRET", "third-party", "token", "example", "charset", "proxies").forEach { assertFalse("$line leaks $it", line.contains(it, true)) }
        }
    }

    @Test fun aPlainResponseReportsNoGzip() = runTest {
        val lines = mutableListOf<String>()
        PrepTrace.sink = { lines += it }
        ManualSubscriptionTransport(OneResponse("vless://a@b:1".toByteArray(), "text/html", null)).readForImport(Secret("https://third-party.example/s"))
        assertTrue(lines.single { it.startsWith("read-summary") }.endsWith("type=text/html bytes=13 gzip=no first=uri-list"))
    }
}
