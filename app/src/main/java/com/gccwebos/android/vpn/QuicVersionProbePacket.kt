package com.gccwebos.android.vpn

/**
 * A reserved-version QUIC probe, not a TLS handshake or proxy authentication check.
 * RFC 9000 sections 6.3 and 15 reserve this version for exercising version negotiation:
 * https://www.rfc-editor.org/rfc/rfc9000.html#section-6.3
 * https://www.rfc-editor.org/rfc/rfc9000.html#section-15
 * The caller supplies fresh random IDs and a complete datagram from the intended UDP peer.
 */
internal object QuicVersionProbePacket {
    const val ConnectionIdBytes = 16
    const val RequestBytes = 1200
    private const val RequestedVersion = 0x0a0a0a0a
    private const val DestinationOffset = 6
    private const val SourceLengthOffset = DestinationOffset + ConnectionIdBytes
    private const val SourceOffset = SourceLengthOffset + 1
    private const val VersionsOffset = SourceOffset + ConnectionIdBytes

    fun request(destinationId: ByteArray, sourceId: ByteArray): ByteArray {
        require(destinationId.size == ConnectionIdBytes && sourceId.size == ConnectionIdBytes)
        // Unsupported-version payload is opaque (5.2.2); 1200 bytes meets the v1 minimum (14.1).
        return ByteArray(RequestBytes).also {
            it[0] = 0xc0.toByte()
            for (index in 1..4) it[index] = 0x0a
            it[5] = ConnectionIdBytes.toByte()
            destinationId.copyInto(it, DestinationOffset)
            it[SourceLengthOffset] = ConnectionIdBytes.toByte()
            sourceId.copyInto(it, SourceOffset)
        }
    }

    fun matchesReply(bytes: ByteArray, count: Int, destinationId: ByteArray, sourceId: ByteArray): Boolean {
        if (destinationId.size != ConnectionIdBytes || sourceId.size != ConnectionIdBytes ||
            count < VersionsOffset + 4 || count > bytes.size || (count - VersionsOffset) % 4 != 0
        ) return false
        // RFC 9000 17.2.1: ignore the low seven bits; VN consumes the entire UDP datagram.
        // https://www.rfc-editor.org/rfc/rfc9000.html#section-17.2.1
        if ((bytes[0].toInt() and 0x80) == 0 || readVersion(bytes, 1) != 0 ||
            bytes[5].toInt() != ConnectionIdBytes || bytes[SourceLengthOffset].toInt() != ConnectionIdBytes
        ) return false
        for (index in 0 until ConnectionIdBytes) {
            if (bytes[DestinationOffset + index] != sourceId[index] ||
                bytes[SourceOffset + index] != destinationId[index]
            ) return false
        }
        for (offset in VersionsOffset until count step 4) {
            val version = readVersion(bytes, offset)
            // 6.2 rejects the requested version; zero denotes VN rather than a usable version (15).
            if (version == 0 || version == RequestedVersion) return false
        }
        return true
    }

    private fun readVersion(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 255) shl 24) or
            ((bytes[offset + 1].toInt() and 255) shl 16) or
            ((bytes[offset + 2].toInt() and 255) shl 8) or
            (bytes[offset + 3].toInt() and 255)
}
