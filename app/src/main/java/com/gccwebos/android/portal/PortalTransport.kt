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
class SubscriptionTransport(private val policy: OriginPolicy) {
    private val client = isolatedClient()
    suspend fun download(url: Secret): ByteArray = withContext(Dispatchers.IO) {
        val response = executeBounded(client, Request.Builder().url(policy.subscription(url.value))
            .header("Accept", "application/x-yaml").header("Cache-Control", "no-store").build(), 2 * 1024 * 1024)
        when(response.status) {
            200 -> response.body.toByteArray(Charsets.UTF_8) // Opaque YAML, NOT verified engine configuration.
            403 -> throw PortalFailure(PortalFailure.Reason.SUBSCRIPTION_UNAVAILABLE)
            404, 410 -> throw PortalFailure(PortalFailure.Reason.SUBSCRIPTION_REVOKED)
            else -> throw PortalFailure(PortalFailure.Reason.UNAVAILABLE)
        }
    }
}
private fun isolatedClient() = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
    .followRedirects(false).followSslRedirects(false).cache(null)
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
private fun executeBounded(client: OkHttpClient, request: Request, limit: Int): WireResponse {
    try {
        client.newCall(request).execute().use { response ->
            if (response.code in 300..399) throw PortalFailure(PortalFailure.Reason.PROTOCOL)
            val body = response.body ?: return WireResponse(response.code, "")
            if (body.contentLength() > limit) throw PortalFailure(PortalFailure.Reason.PROTOCOL)
            val bytes = body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= limit) {
                    val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (bytes.size > limit) throw PortalFailure(PortalFailure.Reason.PROTOCOL)
            return WireResponse(response.code, bytes.toString(Charsets.UTF_8))
        }
    } catch (_: IOException) { throw PortalFailure(PortalFailure.Reason.NETWORK) }
}
