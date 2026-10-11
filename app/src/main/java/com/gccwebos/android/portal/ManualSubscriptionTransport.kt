package com.gccwebos.android.portal

import com.gccwebos.android.vpn.SafeProxyConfig
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

/** These failures never contain URL material, remote response text, or an underlying cause. */
internal class ManualSubscriptionRedirect : Exception("Subscription redirect unsupported")
internal class ManualSubscriptionMetadataFailure : Exception("Invalid subscription metadata")

/** User-selected remote authority, independent of the account's origin/capability policy. */
internal object ManualSubscriptionUrlPolicy {
    private const val MAX_URL_BYTES = 8 * 1024
    private val label = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")

    /** Returns the original string: path/query bytes and parameter ordering remain secret and intact. */
    fun validate(url: String): String {
        try {
            require(url.length in 1..MAX_URL_BYTES && url.all { it.code in 0x21..0x7e })
            require('\\' !in url)
            val uri = URI(url)
            require(uri.scheme == "https" && uri.rawUserInfo == null && uri.rawFragment == null)
            require(uri.port == -1 || uri.port == 443)
            val host = requireNotNull(uri.host)
            // Reject alternate textual ports and authority forms that HTTP parsers could reinterpret.
            require(uri.rawAuthority == host + if (uri.port == 443) ":443" else "")
            val plainHost = host.removeSurrounding("[", "]")
            if (':' in plainHost) {
                require(plainHost.all { it in "0123456789abcdefABCDEF:." })
                val address = InetAddress.getByName(plainHost) // The colon/character check makes this literal-only.
                require(address is Inet6Address && PublicSubscriptionAddress.allowed(address))
            } else if (plainHost.all { it in '0'..'9' || it == '.' }) {
                val parts = plainHost.split('.')
                require(parts.size == 4 && parts.all { Regex("0|[1-9][0-9]{0,2}").matches(it) && it.toInt() <= 255 })
                require(PublicSubscriptionAddress.allowed(InetAddress.getByAddress(parts.map { it.toInt().toByte() }.toByteArray())))
            } else {
                require(plainHost.length <= 253)
                val labels = plainHost.split('.')
                require(labels.size >= 2 && labels.all(label::matches))
                require(labels.last().any { it in 'a'..'z' || it in 'A'..'Z' })
                require(labels.last().lowercase(Locale.ROOT) !in setOf("localhost", "local", "internal", "home", "lan", "onion", "invalid", "test"))
            }
            for (escape in Regex("%([0-9A-Fa-f]{2})").findAll(url)) {
                val byte = escape.groupValues[1].toInt(16)
                require(byte !in 0..31 && byte != 127 && byte != '\\'.code)
            }
            val http = url.toHttpUrl()
            require(http.encodedPath == uri.rawPath.ifEmpty { "/" } && http.encodedQuery == uri.rawQuery)
            if (':' !in plainHost) require(http.host.equals(plainHost, ignoreCase = true))
            return url
        } catch (_: Exception) { throw UnsupportedManualSubscriptionLink() }
    }
}

/** Conservative public-unicast policy; special-purpose ranges are not subscription servers.
 * References: https://www.iana.org/assignments/iana-ipv4-special-registry/
 * and https://www.iana.org/assignments/iana-ipv6-special-registry/ .
 * IPv6 is limited to global-unicast 2000::/3, excluding protocol, documentation and transition ranges.
 */
private object PublicSubscriptionAddress {
    fun allowed(address: InetAddress): Boolean {
        val bytes = address.address.map { it.toInt() and 0xff }
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        if (bytes.size == 4) {
            val a = bytes[0]; val b = bytes[1]; val c = bytes[2]
            return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
                (a == 100 && b in 64..127) || (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) ||
                (a == 192 && ((b == 0 && c in setOf(0, 2)) || (b == 31 && c == 196) ||
                    (b == 52 && c == 193) || (b == 88 && c == 99) || b == 168 || (b == 175 && c == 48))) ||
                (a == 198 && (b in 18..19 || (b == 51 && c == 100))) ||
                (a == 203 && b == 0 && c == 113))
        }
        if (bytes.size != 16 || (address is Inet6Address && address.scopeId != 0)) return false
        if (bytes[0] !in 0x20..0x3f) return false // Also excludes IPv4-mapped, NAT64, ULA and unallocated space.
        if (bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] in 0..1) return false // 2001::/23.
        if (bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8) return false
        if (bytes[0] == 0x20 && bytes[1] == 0x02) return false // Embedded IPv4 through 6to4.
        if (bytes.take(6) == listOf(0x26, 0x20, 0x00, 0x4f, 0x80, 0x00)) return false // AS112.
        if (bytes[0] == 0x3f && bytes[1] == 0xff && bytes[2] < 0x10) return false // 3fff::/20.
        return true
    }
}

/**
 * OkHttp uses these very addresses to connect; there is no separate preflight DNS lookup.
 * [otherFamilyFirst] reorders the same checked answers for a hedged second attempt so that it does
 * not begin with the address (and address family) the first attempt is already stuck on.
 */
internal class PublicSubscriptionDns(private val resolver: Dns = Dns.SYSTEM,
    private val otherFamilyFirst: Boolean = false, private val interleave: Boolean = false) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val answers = try {
            val found = resolver.lookup(hostname).toList()
            if (found.isEmpty() || found.size > 64 || !found.all(PublicSubscriptionAddress::allowed))
                throw UnknownHostException()
            found
        } catch (_: Exception) { throw UnknownHostException("Subscription DNS unavailable") }
        val ordered = if (otherFamilyFirst) alternateOrder(answers) else answers
        return if (interleave) interleaveFamilies(ordered) else ordered
    }

    internal companion object {
        /**
         * Alternates address families starting with the first answer's family (v6, v4, v6, v4, ...), keeping each
         * family's own order. With a short per-address connect timeout, a dead first family costs one attempt instead
         * of all of its addresses before the other family is tried. Same addresses, only reordered.
         */
        fun interleaveFamilies(answers: List<InetAddress>): List<InetAddress> {
            if (answers.size < 2) return answers
            val firstIsV6 = answers[0] is Inet6Address
            val same = answers.filter { (it is Inet6Address) == firstIsV6 }
            val other = answers.filter { (it is Inet6Address) != firstIsV6 }
            if (other.isEmpty()) return answers
            return buildList(answers.size) {
                for (index in 0 until maxOf(same.size, other.size)) { same.getOrNull(index)?.let(::add); other.getOrNull(index)?.let(::add) }
            }
        }

        /** Other address family first when the answers mix families; otherwise rotated by one. */
        fun alternateOrder(answers: List<InetAddress>): List<InetAddress> {
            if (answers.size < 2) return answers
            val firstIsV6 = answers[0] is Inet6Address
            val (same, other) = answers.partition { (it is Inet6Address) == firstIsV6 }
            return if (other.isNotEmpty()) other + same else answers.drop(1) + answers[0]
        }
    }
}

internal fun manualSubscriptionClient(resolver: Dns = Dns.SYSTEM): OkHttpClient = OkHttpClient.Builder()
    .dns(PublicSubscriptionDns(resolver))
    .proxy(Proxy.NO_PROXY)
    .cookieJar(CookieJar.NO_COOKIES)
    .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
    .cache(null).followRedirects(false).followSslRedirects(false)
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
    .eventListenerFactory(ReadTimingListener.factory)
    .retryOnConnectionFailure(false).build()

/**
 * The client for an import the user is watching. Everything isolating the connect client stays (no proxy, cookies,
 * cache, redirects, credentials; public-address DNS) and the budget is longer: 60 s overall, 55 s of server silence.
 *
 * Two deliberate differences, both import-only:
 *  - [retryOnConnectionFailure] is on. OkHttp only moves on to the next resolved address when it is, and with it off
 *    (the connect client) a dead first address fails the whole attempt; the second hedged request is then the only
 *    fallback. It also retries a stale pooled connection. A read timeout after the request was sent is never retried.
 *  - connect timeout is 6 s per address (instead of 15 s) and the addresses are interleaved v6/v4, so a black-holed
 *    address or family costs 6 s, not 15 s, before the next one is tried.
 */
internal fun manualSubscriptionImportClient(resolver: Dns = Dns.SYSTEM, otherFamilyFirst: Boolean = false): OkHttpClient =
    manualSubscriptionClient(resolver).newBuilder()
        .dns(PublicSubscriptionDns(resolver, otherFamilyFirst = otherFamilyFirst, interleave = true))
        .retryOnConnectionFailure(true)
        .connectTimeout(ManualSubscriptionTransport.IMPORT_CONNECT_MILLIS, TimeUnit.MILLISECONDS)
        .readTimeout(ManualSubscriptionTransport.IMPORT_OVERALL_MILLIS - 5_000L, TimeUnit.MILLISECONDS)
        .callTimeout(ManualSubscriptionTransport.IMPORT_OVERALL_MILLIS, TimeUnit.MILLISECONDS).build()

/**
 * A new, credential-free transport only for explicitly selected manual remote subscriptions.
 *
 * A connection attempt needs a fresh read and its latency is outside the app's control: a stalled
 * connection or a slow answer made one-click connect fail even though an immediate retry worked. The
 * read is therefore hedged: if the first request has not finished after [hedgeAfterMillis] (or it
 * failed with a network error) one independent second request is made, preferring a different
 * address. The first successful response wins and the other request is cancelled and wiped. A
 * definitive server answer (revoked, unavailable, protocol) is never retried.
 */
class ManualSubscriptionTransport internal constructor(private val calls: Call.Factory,
    private val hedgeCalls: Call.Factory = calls,
    private val hedgeAfterMillis: Long = HEDGE_AFTER_MILLIS,
    private val importCalls: Call.Factory = calls,
    private val importHedgeCalls: Call.Factory = hedgeCalls,
    private val importHedgeAfterMillis: Long = IMPORT_HEDGE_AFTER_MILLIS,
    private val importOverallMillis: Long = IMPORT_OVERALL_MILLIS) : SubscriptionSource {
    constructor() : this(manualSubscriptionClient().let { primary ->
        listOf(primary, primary.newBuilder().dns(PublicSubscriptionDns(otherFamilyFirst = true)).build(),
            manualSubscriptionImportClient(), manualSubscriptionImportClient(otherFamilyFirst = true))
    })
    private constructor(clients: List<OkHttpClient>) : this(clients[0], clients[1], HEDGE_AFTER_MILLIS, clients[2], clients[3])

    internal companion object {
        /** Connect (one-click connect, refresh): unchanged since #58. */
        const val HEDGE_AFTER_MILLIS = 5_000L
        const val OVERALL_MILLIS = 30_000L
        /** Import: the user watches a progress bar and can cancel, so the read may take longer; hedge later. */
        const val IMPORT_OVERALL_MILLIS = 60_000L
        const val IMPORT_HEDGE_AFTER_MILLIS = 15_000L
        const val IMPORT_CONNECT_MILLIS = 6_000L
    }

    private sealed interface Attempt {
        class Done(val read: SubscriptionRead) : Attempt
        class Failed(val failure: Exception) : Attempt
    }
    private suspend fun attempt(url: Secret, factory: Call.Factory, label: ReadLabel): Attempt = try {
        Attempt.Done(readOnce(url, factory, label))
    } catch (cancel: CancellationException) { throw cancel }
    catch (failure: Exception) { Attempt.Failed(failure) }
    private fun retryable(failure: Exception) = failure is PortalFailure && failure.reason == PortalFailure.Reason.NETWORK
    private fun discard(attempt: Attempt?) { (attempt as? Attempt.Done)?.read?.bytes?.fill(0) }

    private suspend fun hedgedRead(url: Secret, kind: String, firstCalls: Call.Factory, secondCalls: Call.Factory,
        hedgeAfter: Long): SubscriptionRead = coroutineScope {
        val began = System.nanoTime()
        fun elapsed() = (System.nanoTime() - began) / 1_000_000
        val first = async { attempt(url, firstCalls, ReadLabel(kind, "primary")) }
        val early = withTimeoutOrNull(hedgeAfter) { first.await() }
        if (early is Attempt.Done) { PrepTrace.markLazy { "read-result $kind winner=primary total=${elapsed()}ms" }; return@coroutineScope early.read }
        if (early is Attempt.Failed && !retryable(early.failure)) throw early.failure
        PrepTrace.markLazy { "read-hedge-start $kind after=${elapsed()}ms reason=${if (early == null) "slow" else "network-error"}" }
        val second = async { attempt(url, secondCalls, ReadLabel(kind, "hedge")) }
        var firstOutcome: Attempt? = early
        var secondOutcome: Attempt? = null
        while (true) {
            val settled = select<Pair<Boolean, Attempt>> {
                if (firstOutcome == null) first.onAwait { Pair(true, it) }
                if (secondOutcome == null) second.onAwait { Pair(false, it) }
            }
            if (settled.first) firstOutcome = settled.second else secondOutcome = settled.second
            val outcome = settled.second
            if (outcome is Attempt.Done) {
                // The loser is cancelled; bytes it already produced are wiped, never returned or kept.
                val loser = if (settled.first) second else first
                loser.cancel()
                if (loser.isCompleted && !loser.isCancelled) discard(loser.getCompleted())
                PrepTrace.markLazy { "read-result $kind winner=${if (settled.first) "primary" else "hedge"} total=${elapsed()}ms" }
                return@coroutineScope outcome.read
            }
            outcome as Attempt.Failed
            if (!retryable(outcome.failure)) {
                first.cancel(); second.cancel()
                if (first.isCompleted && !first.isCancelled) discard(first.getCompleted())
                if (second.isCompleted && !second.isCancelled) discard(second.getCompleted())
                throw outcome.failure
            }
            if (firstOutcome != null && secondOutcome != null) {
                PrepTrace.markLazy { "read-result $kind winner=none (both attempts failed) total=${elapsed()}ms" }
                throw PortalFailure(PortalFailure.Reason.NETWORK)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("Both attempts settle before the loop exits")
    }

    override suspend fun download(url: Secret): ByteArray = read(url).bytes

    override suspend fun read(url: Secret): SubscriptionRead =
        readWithin(url, "connect", OVERALL_MILLIS, calls, hedgeCalls, hedgeAfterMillis)

    /** Import budget: [importOverallMillis] overall, hedge after [importHedgeAfterMillis] or on a network error. */
    override suspend fun readForImport(url: Secret): SubscriptionRead =
        readWithin(url, "import", importOverallMillis, importCalls, importHedgeCalls, importHedgeAfterMillis)

    private suspend fun readWithin(url: Secret, kind: String, overall: Long, firstCalls: Call.Factory, secondCalls: Call.Factory,
        hedgeAfter: Long): SubscriptionRead {
        var owned: SubscriptionRead? = null
        try {
            // Caller cancellation also bounds a blocking platform DNS lookup whose callback may arrive late.
            val result = withTimeout(overall) { hedgedRead(url, kind, firstCalls, secondCalls, hedgeAfter).also { owned = it } }
            owned = null // No suspension between transfer and return.
            return result
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive() // Preserve an outer caller's cancellation.
            throw PortalFailure(PortalFailure.Reason.NETWORK)
        } finally { owned?.bytes?.fill(0) }
    }

    private suspend fun readOnce(url: Secret, calls: Call.Factory, label: ReadLabel): SubscriptionRead {
        val accepted = ManualSubscriptionUrlPolicy.validate(url.value)
        val request = Request.Builder().url(accepted).tag(ReadLabel::class.java, label)
            .header("User-Agent", "FeilianVPN/0.4 (Mihomo-compatible)")
            .header("Accept", "application/yaml, application/x-yaml, text/yaml, text/plain")
            .header("Cache-Control", "no-store").get().build()
        return suspendCancellableCoroutine { continuation ->
            val call = try { calls.newCall(request) }
            catch (_: Exception) {
                continuation.resumeWith(Result.failure(PortalFailure(PortalFailure.Reason.NETWORK)))
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { call.cancel() }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            try {
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(PortalFailure(PortalFailure.Reason.NETWORK)))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        var owned: ByteArray? = null
                        try {
                            response.use {
                                try {
                                    if (!continuation.isActive) { call.cancel(); return }
                                    if (it.code in 300..399) throw ManualSubscriptionRedirect()
                                    if (it.request.url != request.url || it.priorResponse != null)
                                        throw PortalFailure(PortalFailure.Reason.PROTOCOL)
                                    // Error response bodies are never consumed or included in a diagnostic.
                                    if (it.code in setOf(401, 403, 404, 410)) throw PortalFailure(PortalFailure.Reason.SUBSCRIPTION_REVOKED)
                                    if (it.code != 200) throw PortalFailure(PortalFailure.Reason.SUBSCRIPTION_UNAVAILABLE)
                                    val metadata = manualSubscriptionMetadata(it.headers)
                                    owned = boundedManualSubscriptionBody(it.body ?: throw PortalFailure(PortalFailure.Reason.PROTOCOL)) {
                                        continuation.isActive && !call.isCanceled()
                                    }
                                    val bytes = requireNotNull(owned)
                                    PrepTrace.markLazy {
                                        ReadSummary.line(label, it.code, it.body?.contentType(), bytes,
                                            (it.networkResponse ?: it).header("Content-Encoding")?.contains("gzip", ignoreCase = true) == true)
                                    }
                                    val result = SubscriptionRead(bytes, metadata.expiry, metadata.usage)
                                    // Ownership transfers to the continuation; prompt cancellation wipes undelivered bytes.
                                    continuation.resume(result) { _, rejected, _ -> rejected.bytes.fill(0) }
                                    owned = null
                                } catch (failure: Exception) {
                                    // Abort the socket before close can try draining a hostile body for connection reuse.
                                    call.cancel()
                                    throw failure
                                }
                            }
                        } catch (failure: Exception) {
                            if (continuation.isActive) continuation.resumeWith(Result.failure(when (failure) {
                                is ManualSubscriptionRedirect, is ManualSubscriptionMetadataFailure, is PortalFailure -> failure
                                else -> PortalFailure(PortalFailure.Reason.NETWORK)
                            }))
                        } finally { owned?.fill(0) }
                    }
                })
            } catch (_: Exception) {
                call.cancel()
                if (continuation.isActive) continuation.resumeWith(Result.failure(PortalFailure(PortalFailure.Reason.NETWORK)))
            }
        }
    }
}

/** OkHttp transparently decompresses gzip before this boundary; the cap is on decoded bytes. */
private fun boundedManualSubscriptionBody(body: ResponseBody, active: () -> Boolean): ByteArray {
    val limit = SafeProxyConfig.MAX_BYTES
    if (body.contentLength() > limit || body.contentLength() < -1) throw PortalFailure(PortalFailure.Reason.PROTOCOL)
    val owned = ByteArray(limit + 1)
    try {
        var count = 0
        // The response owner closes this stream after cancelling any rejected/oversized call.
        val input = body.byteStream()
        while (count < owned.size) {
            if (!active()) throw CancellationException("Subscription request cancelled")
            val read = input.read(owned, count, owned.size - count)
            if (read == -1) break
            count += read
        }
        if (!active()) throw CancellationException("Subscription request cancelled")
        if (count !in 1..limit) throw PortalFailure(PortalFailure.Reason.PROTOCOL)
        return owned.copyOf(count)
    } finally { owned.fill(0) }
}

/** Metadata from one third-party download. Usage describes only this source, never the account. */
internal class ManualSubscriptionMetadata(val expiry: Instant?, val usage: SubscriptionUsageReport?) {
    override fun toString() = "ManualSubscriptionMetadata([REDACTED])"
}

/** Unknown expiry remains null. Account identity/plan are never inferred from these headers. */
internal fun manualSubscriptionExpiry(headers: Headers): Instant? = manualSubscriptionMetadata(headers).expiry

/** Strict: malformed or duplicate metadata rejects the download. Unknown usage is null, never zero. */
internal fun manualSubscriptionMetadata(headers: Headers): ManualSubscriptionMetadata {
    try {
        fun single(name: String): String? {
            val values = headers.values(name)
            require(values.size <= 1)
            return values.singleOrNull()?.also { require(it.length in 1..SubscriptionUserinfoParser.MAX_HEADER_CHARS && it.all { ch -> ch.code in 0x20..0x7e }) }
        }
        val userinfo = single("Subscription-Userinfo")?.let(SubscriptionUserinfoParser::parse)
        val explicit = single("X-Subscription-Valid-Until")
        val explicitExpiry = explicit?.let {
            require(it.length <= 64 && Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z").matches(it))
            OffsetDateTime.parse(it).toInstant().also { parsed -> require(parsed.epochSecond in 0..SubscriptionUserinfoParser.MAX_UNIX_SECONDS) }
        }
        return ManualSubscriptionMetadata(listOfNotNull(userinfo?.expiry, explicitExpiry).minOrNull(), userinfo?.usage)
    } catch (_: Exception) { throw ManualSubscriptionMetadataFailure() }
}
