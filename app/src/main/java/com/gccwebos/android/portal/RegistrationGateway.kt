package com.gccwebos.android.portal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.util.concurrent.TimeUnit

internal interface RegistrationGateway {
    suspend fun requestEmailVerification(email: String): EmailVerificationReceipt
    suspend fun register(input: RegistrationSubmission): RegistrationReceipt
}
internal fun interface RegistrationTransport {
    suspend fun post(path: String, body: String): WireResponse
}
internal class AnonymousRegistrationGateway(private val transport: RegistrationTransport) : RegistrationGateway {
    override suspend fun requestEmailVerification(email: String) = RegistrationJson.verification(
        transport.post(RegistrationJson.VERIFICATION_PATH, RegistrationJson.verificationBody(email)))
    override suspend fun register(input: RegistrationSubmission) = RegistrationJson.registration(
        transport.post(RegistrationJson.REGISTRATION_PATH, RegistrationJson.registrationBody(input)))
}
/** No Cookie, browser marker/context, native session, referral body or human-challenge fields. */
internal fun portalRegistrationRequest(policy: OriginPolicy, path: String, body: String): Request {
    require(RegistrationJson.validRequest(path, body))
    return Request.Builder().url(policy.apiUrl(path)).header("Accept", "application/json")
        .header("Cache-Control", "no-store").header("Origin", policy.api.toString()).header("Host", policy.api.host)
        .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
}
/** Construction is inert; the controller's missing legal policy blocks both production actions. */
internal fun productionRegistrationGateway(): RegistrationGateway = AnonymousRegistrationGateway(
    HttpsRegistrationTransport(OriginPolicy("https://portal.gccwebos.com", emptySet())))

internal class HttpsRegistrationTransport(private val policy: OriginPolicy) : RegistrationTransport {
    private val client = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).cache(null)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    override suspend fun post(path: String, body: String): WireResponse = withContext(Dispatchers.IO) {
        val request = portalRegistrationRequest(policy, path, body)
        try {
            client.newCall(request).execute().use { response ->
                if (response.code in 300..399) throw RegistrationFailure(RegistrationFailure.Kind.PROTOCOL, true)
                val input = response.body ?: return@withContext WireResponse(response.code, "")
                if (input.contentLength() > 65536) throw RegistrationFailure(RegistrationFailure.Kind.PROTOCOL, true)
                input.byteStream().use { stream ->
                    val bytes = ByteArray(65537)
                    try {
                        var size = 0
                        while (size < bytes.size) {
                            val count = stream.read(bytes, size, bytes.size - size)
                            if (count < 0) break
                            size += count
                        }
                        if (size > 65536) throw RegistrationFailure(RegistrationFailure.Kind.PROTOCOL, true)
                        WireResponse(response.code, String(bytes, 0, size, Charsets.UTF_8))
                    } finally { bytes.fill(0) }
                }
            }
        } catch (_: IOException) { throw RegistrationFailure(RegistrationFailure.Kind.UNKNOWN, true) }
    }
}
