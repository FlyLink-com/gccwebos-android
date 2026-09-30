package com.gccwebos.android.portal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit
import java.io.IOException

class WireResponse(val status: Int, val body: String) { override fun toString() = "WireResponse(status=$status)" }
interface PortalTransport {
    suspend fun request(path: String, body: String? = null, session: Secret? = null): WireResponse
    suspend fun commerce(path: String, body: String?, session: Secret, key: String?): WireResponse =
        if (key == null) request(path, body, session) else throw PortalFailure(PortalFailure.Reason.PROTOCOL)
}
class HttpsPortalTransport(private val policy: OriginPolicy) : PortalTransport {
    private val client = isolatedClient()
    override suspend fun commerce(path: String, body: String?, session: Secret, key: String?): WireResponse = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(policy.apiUrl(path)).header("Accept", "application/json")
            .header("Cache-Control", "no-store").header("X-Portal-Session", session.value)
        if (path == "/api/portal/coins/recharge/offers") builder.header("X-Portal-Recharge-Offers-Version", "2")
        if (key != null) { require(Regex("[A-Za-z0-9._:-]{1,128}").matches(key)); builder.header("Idempotency-Key", key) }
        if (body != null) builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        executeBounded(client, builder.build(), 262144)
    }

    override suspend fun request(path: String, body: String?, session: Secret?): WireResponse = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(policy.apiUrl(path)).header("Accept", "application/json")
            .header("Cache-Control", "no-store")
        if (session != null) builder.header("X-Portal-Session", session.value)
        if (body != null) builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        executeBounded(client, builder.build(), 65536)
    }
}
/** Separate client never accepts Portal session headers or a shared CookieJar. */
class SubscriptionTransport(private val policy: OriginPolicy) : SubscriptionSource {
    private val client = isolatedClient()
    override suspend fun download(url: Secret): ByteArray = read(url).bytes
    override suspend fun read(url: Secret): SubscriptionRead = receiveSubscriptionRead {
        val response = executeBytes(client, Request.Builder().url(policy.subscription(url.value))
            .header("Accept", "application/x-yaml").header("Cache-Control", "no-store").build(), 2 * 1024 * 1024)
        decodeSubscriptionResponse(response.status, response.bytes, response.validUntil)
    }
}
/** Takes ownership of bytes on failure; successful caller owns the returned buffer. */
internal fun decodeSubscriptionResponse(status: Int, bytes: ByteArray, validUntil: String?): SubscriptionRead {
    var transferred = false
    try {
        if (status != 200) {
            val error = bytes.toString(Charsets.UTF_8).trim()
            val reason = when {
                status == 403 && Regex("\\{\\s*\"error\"\\s*:\\s*\"Subscription expired\"\\s*}").matches(error) -> PortalFailure.Reason.SUBSCRIPTION_EXPIRED
                status in setOf(401, 404, 410) || (status == 403 && Regex("\\{\\s*\"error\"\\s*:\\s*\"Subscription link expired\"\\s*}").matches(error)) -> PortalFailure.Reason.SUBSCRIPTION_REVOKED
                status == 403 -> PortalFailure.Reason.SUBSCRIPTION_UNAVAILABLE
                else -> PortalFailure.Reason.UNAVAILABLE
            }
            throw PortalFailure(reason)
        }
        val until = validUntil?.let {
            require(it.length <= 64)
            java.time.Instant.parse(it)
        }
        transferred = true
        return SubscriptionRead(bytes, until)
    } catch (failure: PortalFailure) { throw failure }
    catch (_: Exception) { throw PortalFailure(PortalFailure.Reason.PROTOCOL) }
    finally { if (!transferred) bytes.fill(0) }
}

private suspend fun receiveSubscriptionRead(read: () -> SubscriptionRead): SubscriptionRead {
    var owned: SubscriptionRead? = null
    try {
        val result = withContext(Dispatchers.IO) { read().also { owned = it } }
        owned = null
        return result
    } finally { owned?.bytes?.fill(0) }
}
/** Owns IO results even when withContext discards them after caller cancellation. */
internal suspend fun receiveSubscriptionBytes(read: () -> ByteArray): ByteArray {
    var owned: ByteArray? = null
    try {
        val result = withContext(Dispatchers.IO) { read().also { owned = it } }
        owned = null // No suspension between transfer and return.
        return result
    } finally { owned?.fill(0) }
}
private class SecretBuffer : java.io.ByteArrayOutputStream() {
    fun wipe() { buf.fill(0); reset() }
}
private fun isolatedClient() = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
    .followRedirects(false).followSslRedirects(false).cache(null)
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
private class BinaryResponse(val status: Int, val bytes: ByteArray, val validUntil: String? = null) {
    override fun toString() = "BinaryResponse(status=$status)"
}
private fun executeBounded(client: OkHttpClient, request: Request, limit: Int): WireResponse {
    val response = executeBytes(client, request, limit)
    try { return WireResponse(response.status, response.bytes.toString(Charsets.UTF_8)) }
    finally { response.bytes.fill(0) }
}
private fun executeBytes(client: OkHttpClient, request: Request, limit: Int): BinaryResponse {
    try {
        client.newCall(request).execute().use { response ->
            if (response.code in 300..399) throw PortalFailure(PortalFailure.Reason.PROTOCOL)
            val body = response.body ?: return BinaryResponse(response.code, ByteArray(0))
            if (body.contentLength() > limit) throw PortalFailure(PortalFailure.Reason.PROTOCOL)
            val bytes = body.byteStream().use { input ->
                val output = SecretBuffer()
                val buffer = ByteArray(8192)
                try {
                    while (output.size() <= limit) {
                        val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } finally { buffer.fill(0); output.wipe() }
            }
            if (bytes.size > limit) { bytes.fill(0); throw PortalFailure(PortalFailure.Reason.PROTOCOL) }
            return BinaryResponse(response.code, bytes, response.header("X-Subscription-Valid-Until"))
        }
    } catch (_: IOException) { throw PortalFailure(PortalFailure.Reason.NETWORK) }
}
