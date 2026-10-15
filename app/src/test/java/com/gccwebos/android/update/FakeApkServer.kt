package com.gccwebos.android.update

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Timeout
import okio.buffer
import okio.source
import java.time.Instant

internal fun sha256Hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun releaseFor(bytes: ByteArray, versionCode: Long = 10, path: String = "/android/releases/feilian-vpn-v0.5.0.apk",
    sha: String = sha256Hex(bytes)) = ReleaseManifest.Available(versionCode, "0.5.0", 1, Instant.parse("2026-10-11T00:00:00Z"), path, sha, "合成更新", "Synthetic")

/** An in-memory origin with HTTP Range, mid-stream failures and misbehaviour switches. Blocking, like OkHttp's execute(). */
internal class FakeApkServer(var content: ByteArray) : Call.Factory {
    val requests = mutableListOf<Request>()
    /** Close the connection (IOException) after this many body bytes of the next GET, once. */
    var failAfterBytes: Int? = null
    var ignoreRange = false
    var statusOverride: Int? = null
    var locationRedirect: String? = null
    var declaredLengthOverride: Long? = null
    var truncateBodyTo: Int? = null
    var badContentRange: String? = null
    var rangeNotSatisfiableOnce = false
    /** When set, the body blocks after [blockAfterBytes] bytes until the call is cancelled. */
    var blockAfterBytes: Int? = null
    val blocked = CountDownLatch(1)
    private val released = CountDownLatch(1)
    val gets get() = requests.count { it.method == "GET" }

    override fun newCall(request: Request): Call {
        requests.add(request)
        return object : Call {
            override fun request() = request
            override fun execute(): Response = respond(request)
            override fun enqueue(responseCallback: Callback) = error("unused")
            override fun cancel() { released.countDown() }
            override fun isExecuted() = true
            override fun isCanceled() = released.count == 0L
            override fun timeout() = Timeout.NONE
            override fun clone(): Call = newCall(request)
        }
    }

    private fun respond(request: Request): Response {
        val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("fixture")
        locationRedirect?.let { return builder.code(302).header("Location", it).body(emptyBody()).build() }
        statusOverride?.let { return builder.code(it).body(emptyBody()).build() }
        if (request.method == "HEAD") {
            return builder.code(200).header("Content-Length", content.size.toString()).body(emptyBody()).build()
        }
        val range = request.header("Range")?.takeIf { !ignoreRange }
        if (rangeNotSatisfiableOnce && range != null) {
            rangeNotSatisfiableOnce = false
            return builder.code(416).header("Content-Range", "bytes */${content.size}").body(emptyBody()).build()
        }
        val start = range?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull() ?: 0
        val slice = content.copyOfRange(start, content.size).let { s -> truncateBodyTo?.let { s.copyOf(minOf(it, s.size)) } ?: s }
        val stream = failAfterBytes.also { failAfterBytes = null }.let { limit -> breaking(slice, limit) }
        val length = declaredLengthOverride ?: if (range != null) slice.size.toLong().let { content.size - start.toLong() } else content.size.toLong()
        val body = object : ResponseBody() {
            override fun contentType() = "application/vnd.android.package-archive".toMediaType()
            override fun contentLength() = length
            override fun source() = stream.source().buffer()
        }
        return if (range != null) builder.code(206).header("Content-Range", badContentRange ?: "bytes $start-${content.size - 1}/${content.size}").body(body).build()
        else builder.code(200).body(body).build()
    }

    private fun emptyBody() = object : ResponseBody() {
        override fun contentType() = null
        override fun contentLength() = 0L
        override fun source() = ByteArrayInputStream(ByteArray(0)).source().buffer()
    }

    private fun breaking(bytes: ByteArray, failAfter: Int?): InputStream {
        val inner = ByteArrayInputStream(bytes)
        var delivered = 0
        return object : InputStream() {
            override fun read(): Int = error("unused")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                blockAfterBytes?.let { limit ->
                    if (delivered >= limit) {
                        blocked.countDown()
                        released.await()
                        throw IOException("canceled")
                    }
                }
                if (failAfter != null && delivered >= failAfter) throw IOException("connection reset")
                val room = if (failAfter != null) minOf(len, failAfter - delivered) else len
                val allowed = blockAfterBytes?.let { minOf(room, it - delivered) } ?: room
                val n = inner.read(b, off, maxOf(1, allowed))
                if (n > 0) delivered += n
                return n
            }
        }
    }
}
