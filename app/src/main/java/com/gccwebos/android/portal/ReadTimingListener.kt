package com.gccwebos.android.portal

import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/** Which read an HTTP call belongs to; carried as a request tag so the timing line says import/connect and primary/hedge. */
internal class ReadLabel(val kind: String, val attempt: String)

/**
 * Debug-only per-phase timing of one subscription HTTP call, emitted as ONE line when the call ends:
 * `read-timing import/primary dns=… connect=[#1 v6 6001ms fail, #2 v4 120ms ok] tls=… wait=… body=… total=… result=…`.
 * Durations are milliseconds; `-` means the phase did not happen (for example a reused connection has no dns).
 * It never receives or prints the URL, host name, addresses, headers or body: only counts, address family
 * (v4/v6), durations and the failure's exception class name.
 */
internal class ReadTimingListener(private val label: ReadLabel?, private val now: () -> Long,
    private val emit: (String) -> Unit) : EventListener() {
    private var start = 0L
    private var dnsStart = -1L; private var dnsMs = -1L; private var addresses = 0; private var families = ""
    private var connectStart = -1L
    private var tlsStart = -1L; private var tlsMs = -1L
    private val connects = mutableListOf<String>()
    private var sent = -1L; private var firstByte = -1L; private var headersEnd = -1L; private var bodyMs = -1L
    private var phase = "start"
    private var done = false

    private fun since(from: Long) = if (from < 0) "-" else "${now() - from}ms"
    private fun family(address: InetSocketAddress?) = if (address?.address is Inet6Address) "v6" else "v4"

    override fun callStart(call: Call) { start = now(); phase = "dns" }
    override fun dnsStart(call: Call, domainName: String) { dnsStart = now(); phase = "dns" }
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        dnsMs = now() - dnsStart; addresses = inetAddressList.size
        families = "v4=${inetAddressList.count { it !is Inet6Address }},v6=${inetAddressList.count { it is Inet6Address }}"
        phase = "connect"
    }
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) { connectStart = now(); phase = "connect" }
    override fun secureConnectStart(call: Call) { tlsStart = now(); phase = "tls" }
    override fun secureConnectEnd(call: Call, handshake: Handshake?) { tlsMs = now() - tlsStart; phase = "connect" }
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        connects += "#${connects.size + 1} ${family(inetSocketAddress)} ${since(connectStart)}${if (tlsMs >= 0) " (incl tls)" else ""} ok"
        phase = "send"
    }
    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
        connects += "#${connects.size + 1} ${family(inetSocketAddress)} ${since(connectStart)} fail(${ioe.javaClass.simpleName})"
        tlsStart = -1L
    }
    override fun requestHeadersEnd(call: Call, request: Request) { sent = now(); phase = "wait" }
    override fun responseHeadersStart(call: Call) { firstByte = now() }
    override fun responseHeadersEnd(call: Call, response: Response) { headersEnd = now(); phase = "body" }
    override fun responseBodyEnd(call: Call, byteCount: Long) { bodyMs = now() - headersEnd; phase = "done" }
    override fun callEnd(call: Call) = finish("ok")
    override fun callFailed(call: Call, ioe: IOException) = finish("failed in $phase (${ioe.javaClass.simpleName})")

    private fun finish(result: String) {
        if (done) return
        done = true
        val wait = if (sent >= 0 && firstByte >= 0) "${firstByte - sent}ms" else "-"
        val body = if (bodyMs >= 0) "${bodyMs}ms" else "-"
        val dns = if (dnsMs >= 0) "${dnsMs}ms($addresses addr $families)" else "-"
        val tls = if (tlsMs >= 0) "${tlsMs}ms" else "-"
        emit("read-timing ${label?.kind ?: "?"}/${label?.attempt ?: "?"} dns=$dns connect=[${connects.joinToString(", ").ifEmpty { "-" }}] " +
            "tls=$tls wait=$wait body=$body total=${now() - start}ms result=$result")
    }

    internal companion object {
        /** Installed on the subscription clients; a call gets the real listener only while a debug sink exists. */
        val factory = EventListener.Factory { call ->
            val sink = PrepTrace.sink
            if (sink == null) NONE
            else ReadTimingListener(call.request().tag(ReadLabel::class.java), { System.nanoTime() / 1_000_000 }, sink)
        }
    }
}
