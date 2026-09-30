package com.gccwebos.android.update

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

private const val APP = "com.gccwebos.android"
private val unavailable = """{"schemaVersion":1,"platform":"android","channel":"stable","status":"unavailable"}"""
private fun available(code: Long = 3, minimum: Long = 1) = """{"schemaVersion":1,"platform":"android","channel":"stable","status":"available","versionCode":$code,"versionName":"1.2.3","minSupportedVersionCode":$minimum,"publishedAt":"2026-09-29T00:00:00Z","downloadUrl":"/android/releases/gccwebos-1.2.3.apk","sha256":"${"a".repeat(64)}","notes":{"zh":"更新说明","en":"Release notes"}}"""

class ReleaseManifestTests {
    @Test fun unavailableHasNoDownload() { assertEquals(ReleaseManifest.Unavailable, ReleaseManifestParser.parse(unavailable)) }
    @Test fun newerCodePrompts() { assertTrue(evaluateRelease(ReleaseManifestParser.parse(available()), 2, APP) is UpdateResult.NewVersion) }
    @Test fun minimumSupportWarning() {
        val result = evaluateRelease(ReleaseManifestParser.parse(available(3, 3)), 2, APP) as UpdateResult.NewVersion
        assertTrue(result.belowMinimum)
        assertEquals("https://gccwebos.com/android/releases/gccwebos-1.2.3.apk", result.release.downloadUrl)
    }
    @Test fun minimumEqualStillSupported() { assertFalse((evaluateRelease(ReleaseManifestParser.parse(available(3, 2)), 2, APP) as UpdateResult.NewVersion).belowMinimum) }
    @Test fun equalAndOlderDoNotDowngrade() { for (code in listOf(1L, 2L)) assertEquals(UpdateResult.UpToDate, evaluateRelease(ReleaseManifestParser.parse(available(code)), 2, APP)) }
    @Test fun debugIsNotAnUpgradeCandidate() { assertEquals(UpdateResult.UnsupportedBuild, evaluateRelease(ReleaseManifestParser.parse(available()), 2, "$APP.debug")) }
    @Test fun unsafeDownloadPathsRejected() {
        for (path in listOf("https://evil.example/a.apk", "http://gccwebos.com/android/releases/a.apk", "//evil.example/a.apk", "/android/releases/../a.apk", "/android/releases/a..apk", "/android/releases/a.apk?x=1", "/android/releases/a.apk#fragment", "/android/releases/%2e%2e/a.apk", "/android/releases/a/b.apk", "/android/releases/a.APK", "/android/releases/\\\\evil.apk")) {
            invalid(available().replace("/android/releases/gccwebos-1.2.3.apk", path))
        }
    }
    @Test fun invalidFieldsRejected() {
        for ((old, new) in listOf("\"schemaVersion\":1" to "\"schemaVersion\":2", "\"platform\":\"android\"" to "\"platform\":\"ios\"", "\"stable\"" to "\"beta\"", "\"versionCode\":3" to "\"versionCode\":0", "\"versionCode\":3" to "\"versionCode\":2147483648", "\"versionCode\":3" to "\"versionCode\":\"3\"", "\"versionCode\":3" to "\"versionCode\":3.0", "\"versionCode\":3" to "\"versionCode\":3e0", "\"versionCode\":3" to "\"versionCode\":03", "\"minSupportedVersionCode\":1" to "\"minSupportedVersionCode\":4", "1.2.3" to "1.2.3-beta", "2026-09-29" to "2026-02-30", "更新说明" to " ", "${"a".repeat(64)}" to "${"A".repeat(64)}")) invalid(available().replace(old, new))
    }
    @Test fun strictJsonRejectsMalformedDuplicateAndExtraFields() {
        for (json in listOf("[]", "null", "{", "$unavailable trailing", unavailable.replace("\"stable\"", "'stable'"), unavailable.replace("1", "1,\"schemaVersion\":1"), unavailable.replace("}", ",}"), unavailable.replace("}", ",\"minSdk\":26}"), unavailable.replace("}", ",\"packageName\":\"com.bad\"}"), unavailable.replace("\"unavailable\"", "true"))) invalid(json)
    }
    @Test fun sizeAndDepthAreBounded() { invalid(" ".repeat(OfficialReleases.MAX_BYTES) + unavailable); invalid("""{"a":{"b":{"c":{}}}}""") }
    @Test fun validEscapesAndUnicodeSurrogates() { val result = ReleaseManifestParser.parse(available().replace("Release notes", "Line\\n\\uD83D\\uDE00")) as ReleaseManifest.Available; assertEquals("Line\n😀", result.notesEn) }
    @Test fun invalidUnicodeRejected() { invalid(available().replace("Release notes", "\\uD800")); invalid(available().replace("Release notes", "bad\nline")) }
    private fun invalid(json: String) { assertThrows(json.take(100), InvalidReleaseManifest::class.java) { ReleaseManifestParser.parse(json) } }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UpdateRepositoryTests {
    @Test fun unavailableAndNetworkAndInvalidAreDistinct() = runTest {
        assertEquals(UpdateResult.Unavailable, UpdateRepository { unavailable }.check(2, APP))
        assertEquals(UpdateResult.NetworkError, UpdateRepository { throw SocketTimeoutException() }.check(2, APP))
        assertEquals(UpdateResult.InvalidManifest, UpdateRepository { "not json" }.check(2, APP))
    }
    @Test fun debugDoesNotRequestNetwork() = runTest { assertEquals(UpdateResult.UnsupportedBuild, UpdateRepository { error("Must not fetch") }.check(2, "$APP.debug")) }
    @Test fun repeatedChecksAreDeduplicated() = runTest {
        var calls = 0
        val completion = CompletableDeferred<String>()
        val controller = UpdateController(this, UpdateRepository { calls++; completion.await() }, 2, APP)
        controller.check(); controller.check(); runCurrent()
        assertEquals(1, calls)
        completion.complete(unavailable); runCurrent()
        assertEquals(UpdateState.Finished(UpdateResult.Unavailable), controller.state.value)
    }
    @Test fun cancelledOldResultCannotReopenDialog() = runTest {
        val old = CompletableDeferred<String>(); var calls = 0
        val controller = UpdateController(this, UpdateRepository { if (++calls == 1) withContext(NonCancellable) { old.await() } else unavailable }, 2, APP)
        controller.check(); runCurrent(); controller.dismiss(); controller.check(); runCurrent()
        old.complete(available()); runCurrent()
        assertEquals(UpdateState.Finished(UpdateResult.Unavailable), controller.state.value)
        controller.close(); controller.check(); runCurrent()
        assertEquals(UpdateState.Idle, controller.state.value)
        assertEquals(2, calls)
    }
    @Test fun cancellingFetchCancelsHttpCall() = runTest {
        val fake = FakeCallFactory(null)
        val job = launch { HttpsReleaseSource(fake).fetch() }
        runCurrent(); job.cancelAndJoin()
        assertTrue(fake.cancelled)
    }
    @Test fun httpsRequestIsUnauthenticatedAndFixed() = runTest {
        val fake = FakeCallFactory(unavailable)
        assertEquals(unavailable, HttpsReleaseSource(fake).fetch())
        assertEquals(OfficialReleases.MANIFEST_URL, fake.request!!.url.toString())
        assertNull(fake.request!!.header("Authorization")); assertNull(fake.request!!.header("Cookie")); assertNull(fake.request!!.header("X-Portal-Session"))
    }
    @Test fun redirectsRejectedIncludingSameOrigin() = runTest { for (status in listOf(301, 302, 307, 308)) { val fake = FakeCallFactory(unavailable, status); assertEquals(UpdateResult.NetworkError, UpdateRepository(HttpsReleaseSource(fake)).check(2, APP)) } }
    @Test fun malformedUtf8IsInvalid() = runTest {
        val bytes = byteArrayOf(0xc3.toByte(), 0x28)
        val fake = FakeCallFactory("ignored", overrideBody = bytes.toResponseBody("application/json".toMediaType()))
        assertEquals(UpdateResult.InvalidManifest, UpdateRepository(HttpsReleaseSource(fake)).check(2, APP))
    }
    @Test fun unknownLengthStillHasHardSizeBound() = runTest {
        val unknown = object : ResponseBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = -1L
            override fun source() = Buffer().writeUtf8(" ".repeat(OfficialReleases.MAX_BYTES + 1))
        }
        assertEquals(UpdateResult.InvalidManifest, UpdateRepository(HttpsReleaseSource(FakeCallFactory("ignored", overrideBody = unknown))).check(2, APP))
    }
    @Test fun unexpectedFinalUrlIsRejected() = runTest {
        assertEquals(UpdateResult.NetworkError, UpdateRepository(HttpsReleaseSource(FakeCallFactory(unavailable, finalUrl = "https://evil.example/stable.json"))).check(2, APP))
    }
    @Test fun sourceFailureMapsToNetworkFailure() = runTest {
        assertEquals(UpdateResult.NetworkError, UpdateRepository { throw IOException("fixture") }.check(2, APP))
    }
    @Test fun oversizedOrHtmlBodiesRejected() = runTest {
        assertEquals(UpdateResult.InvalidManifest, UpdateRepository(HttpsReleaseSource(FakeCallFactory(" ".repeat(OfficialReleases.MAX_BYTES + 1)))).check(2, APP))
        assertEquals(UpdateResult.InvalidManifest, UpdateRepository(HttpsReleaseSource(FakeCallFactory(unavailable, type = "text/html"))).check(2, APP))
    }
}

private class FakeCallFactory(private val body: String?, private val status: Int = 200, private val type: String = "application/json", private val overrideBody: ResponseBody? = null, private val finalUrl: String? = null) : Call.Factory {
    var cancelled = false
    var request: Request? = null
    override fun newCall(request: Request): Call {
        this.request = request
        return object : Call {
            override fun request() = request
            override fun execute(): Response = error("Synchronous network is forbidden")
            override fun enqueue(responseCallback: Callback) {
                if (body != null) responseCallback.onResponse(this, Response.Builder().request(if (finalUrl == null) request else request.newBuilder().url(finalUrl).build()).protocol(Protocol.HTTP_1_1).code(status).message("fixture").body(overrideBody ?: body.toResponseBody(type.toMediaType())).build())
            }
            override fun cancel() { cancelled = true }
            override fun isExecuted() = true
            override fun isCanceled() = cancelled
            override fun timeout() = Timeout.NONE
            override fun clone(): Call = newCall(request)
        }
    }
}
