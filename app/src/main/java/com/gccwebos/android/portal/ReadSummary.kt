package com.gccwebos.android.portal

import okhttp3.MediaType

/**
 * Debug-only, non-secret description of one subscription response, logged on GCCPrep through [PrepTrace]
 * (release builds install no sink, so nothing is built or logged):
 * `read-summary import/primary http=200 type=text/plain bytes=1234 gzip=no first=yaml-like`.
 * It carries only the status code, the media type and subtype (never parameters), the decoded byte count,
 * whether the server used gzip, and a coarse class of the first non-blank bytes. It never receives or prints
 * the URL, host, headers, tokens or any fragment of the body.
 */
internal object ReadSummary {
    private val mediaType = Regex("[a-z0-9][a-z0-9.+-]{0,30}/[a-z0-9][a-z0-9.+-]{0,40}")

    /** type/subtype only, lower-cased and shape-checked; anything else is reported as `other`. */
    fun contentType(type: MediaType?): String {
        val text = type?.let { "${it.type}/${it.subtype}".lowercase() } ?: return "none"
        return if (mediaType.matches(text)) text else "other"
    }

    /** One of json-object, json-array, xml/html, uri-list, yaml-like, base64-like, other, empty. */
    fun firstByteClass(bytes: ByteArray): String {
        var i = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) i = 3
        while (i < bytes.size && isBlank(bytes[i])) i++
        if (i >= bytes.size) return "empty"
        when (bytes[i].toInt().toChar()) {
            '{' -> return "json-object"
            '[' -> return "json-array"
            '<' -> return "xml/html"
        }
        val window = minOf(bytes.size, i + 256)
        // scheme://  (a plain list of proxy links such as vless://…): letters then "://" at the start of the text.
        var j = i
        while (j < window && isAlpha(bytes[j])) j++
        if (j > i && j + 2 < bytes.size && bytes[j] == ':'.code.toByte() && bytes[j + 1] == '/'.code.toByte() && bytes[j + 2] == '/'.code.toByte()) return "uri-list"
        // YAML: a comment, document marker, list item, or `key:` at the start of the first line.
        if (bytes[i] == '#'.code.toByte() || bytes[i] == '%'.code.toByte() || startsWith(bytes, i, "---") ||
            (bytes[i] == '-'.code.toByte() && i + 1 < bytes.size && bytes[i + 1] == ' '.code.toByte())) return "yaml-like"
        var k = i
        while (k < window && (isAlpha(bytes[k]) || isDigit(bytes[k]) || bytes[k] == '_'.code.toByte() || bytes[k] == '-'.code.toByte())) k++
        if (k > i && k < bytes.size && bytes[k] == ':'.code.toByte()) return "yaml-like"
        // base64: the whole look-ahead (at least 16 chars) is base64/url-safe alphabet, ignoring line breaks.
        var count = 0
        for (n in i until window) {
            val b = bytes[n]
            if (isBlank(b)) continue
            if (isAlpha(b) || isDigit(b) || b == '+'.code.toByte() || b == '/'.code.toByte() || b == '='.code.toByte() ||
                b == '-'.code.toByte() || b == '_'.code.toByte()) count++ else return "other"
        }
        return if (count >= 16 || (count > 0 && window == bytes.size)) "base64-like" else "other"
    }

    fun line(label: ReadLabel?, status: Int, type: MediaType?, bytes: ByteArray, gzip: Boolean): String =
        "read-summary ${label?.kind ?: "?"}/${label?.attempt ?: "?"} http=$status type=${contentType(type)} bytes=${bytes.size} " +
            "gzip=${if (gzip) "yes" else "no"} first=${firstByteClass(bytes)}"

    /** The parser's rejection as enum/class names only; the exception message is never used (it could carry content). */
    fun rejection(failure: Throwable): String = when (failure) {
        is com.gccwebos.android.vpn.UnsafeProxyConfiguration -> "import-reject class=UnsafeProxyConfiguration reason=${failure.reason.name}"
        is PortalFailure -> "import-reject class=PortalFailure reason=${failure.reason.name}"
        else -> "import-reject class=${failure.javaClass.simpleName.take(60)}"
    }

    private fun isBlank(b: Byte) = b == ' '.code.toByte() || b == '\n'.code.toByte() || b == '\r'.code.toByte() || b == '\t'.code.toByte()
    private fun isAlpha(b: Byte) = (b in 'a'.code.toByte()..'z'.code.toByte()) || (b in 'A'.code.toByte()..'Z'.code.toByte())
    private fun isDigit(b: Byte) = b in '0'.code.toByte()..'9'.code.toByte()
    private fun startsWith(bytes: ByteArray, at: Int, text: String) =
        at + text.length <= bytes.size && text.indices.all { bytes[at + it] == text[it].code.toByte() }
}
