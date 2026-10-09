package com.gccwebos.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic regression for snakeyaml-engine's chunked StreamReader: a supplementary character
 * (surrogate pair, e.g. a 4-byte flag emoji) that straddles a read-buffer boundary used to throw
 * IndexOutOfBoundsException, which surfaced as "配置格式或协议不受支持".
 */
class SafeProxyEmojiBoundaryTest {
    private val names = listOf("\uD83C\uDDFA\uD83C\uDDF8 United States 01", "\uD83C\uDDEF\uD83C\uDDF5 Japan 02",
        "\uD83C\uDDF8\uD83C\uDDEC Singapore 03", "\uD83C\uDDED\uD83C\uDDF0 Hong Kong 04",
        "\uD83C\uDDE9\uD83C\uDDEA Germany 05", "\uD83C\uDDEC\uD83C\uDDE7 United Kingdom 06",
        "\uD83C\uDDF0\uD83C\uDDF7 Korea 07", "\uD83D\uDE80 剩余流量：12.34 GB")

    private fun config(padding: Int): ByteArray = buildString {
        append("# ").append("x".repeat(padding)).append('\n')
        append("proxies:\n")
        names.forEachIndexed { index, name ->
            append("  - name: \"").append(name).append("\"\n")
            append("    type: ss\n    server: 192.0.2.").append(index + 10).append('\n')
            append("    port: 18443\n    cipher: aes-128-gcm\n    password: synthetic-secret-").append(index).append('\n')
        }
    }.toByteArray(Charsets.UTF_8)

    /** Every padding in 0..2047 shifts each emoji through every position relative to the 1024-char chunks. */
    @Test fun emojiNamesParseAtEveryOffsetAcrossBufferBoundaries() {
        val failures = mutableListOf<Int>()
        for (padding in 0 until 2048) {
            val raw = config(padding)
            try {
                val nodes = SafeProxyConfig.inspect(raw)
                assertEquals(names, nodes.map { it.name })
                assertTrue(nodes.all { it.supported })
                assertEquals(names.size, SafeProxyConfig.validateForPreparation(raw).size)
            } catch (_: UnsafeProxyConfiguration) { failures += padding }
        }
        assertTrue("Emoji at a buffer boundary rejected for ${failures.size} paddings, first ${failures.take(5)}",
            failures.isEmpty())
    }

    @Test fun inputNearTheByteLimitWithTrailingEmojiStillParses() {
        val node = String(config(0), Charsets.UTF_8)
        val padding = SafeProxyConfig.MAX_BYTES - node.toByteArray(Charsets.UTF_8).size - 8
        val raw = (node.replaceFirst("# \n", "# " + "x".repeat(padding) + "\n")).toByteArray(Charsets.UTF_8)
        assertTrue(raw.size <= SafeProxyConfig.MAX_BYTES)
        assertEquals(names, SafeProxyConfig.inspect(raw).map { it.name })
    }
}
