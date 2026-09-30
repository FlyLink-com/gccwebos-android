package com.gccwebos.android.update

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

fun interface ReleaseSource { suspend fun fetch(): String }

/** A dedicated unauthenticated client: never reuse the portal's session-bearing client. */
class HttpsReleaseSource internal constructor(private val calls: Call.Factory) : ReleaseSource {
    constructor() : this(OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build())

    override suspend fun fetch(): String = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(OfficialReleases.MANIFEST_URL)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .get().build()
        val call = calls.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        // Reject every redirect and unexpected final origin; never follow a manifest-controlled URL.
                        if (it.code != 200 || it.request.url.toString() != OfficialReleases.MANIFEST_URL) throw IOException("Release response rejected")
                        val body = it.body ?: throw IOException("Empty release response")
                        if (body.contentType()?.let { type -> type.type == "application" && type.subtype == "json" } != true) throw InvalidReleaseManifest()
                        if (body.contentLength() > OfficialReleases.MAX_BYTES) throw InvalidReleaseManifest()
                        val source = body.source()
                        val bytes = source.readByteArray((OfficialReleases.MAX_BYTES + 1).toLong().let { limit ->
                            // request() buffers no more than the bounded manifest limit, including unknown-length bodies.
                            source.request(limit)
                            minOf(source.buffer.size, limit)
                        })
                        if (bytes.size > OfficialReleases.MAX_BYTES) throw InvalidReleaseManifest()
                        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                    }
                }
                continuation.resumeWith(result)
            }
        })
    }
}

class UpdateRepository(private val source: ReleaseSource = HttpsReleaseSource()) {
    suspend fun check(installedVersion: Long, applicationId: String): UpdateResult {
        if (applicationId != OfficialReleases.RELEASE_APPLICATION_ID) return UpdateResult.UnsupportedBuild
        return try {
            evaluateRelease(ReleaseManifestParser.parse(source.fetch()), installedVersion, applicationId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: InvalidReleaseManifest) {
            UpdateResult.InvalidManifest
        } catch (_: java.nio.charset.CharacterCodingException) {
            UpdateResult.InvalidManifest
        } catch (_: Exception) {
            UpdateResult.NetworkError
        }
    }
}
