package com.gccwebos.android.vpn

/** IPv4 datagram ICMP only. The kernel supplies the socket identifier and verifies checksums. */
internal object IcmpEchoPacket {
    const val HeaderBytes = 8
    const val NonceBytes = 24
    fun request(nonce: ByteArray): ByteArray {
        require(nonce.size == NonceBytes)
        return ByteArray(HeaderBytes + NonceBytes).also {
            it[0] = 8 // Echo request.
            it[7] = 1 // Sequence 1; the payload additionally identifies this one-shot socket.
            nonce.copyInto(it, HeaderBytes)
            val checksum = checksum(it)
            it[2] = (checksum ushr 8).toByte(); it[3] = checksum.toByte()
        }
    }
    fun matchesReply(bytes: ByteArray, count: Int, nonce: ByteArray): Boolean =
        nonce.size == NonceBytes && count == HeaderBytes + NonceBytes && count <= bytes.size &&
            bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[6] == 0.toByte() && bytes[7] == 1.toByte() &&
            nonce.indices.all { bytes[HeaderBytes + it] == nonce[it] }

    private fun checksum(bytes: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i < bytes.size) {
            sum += (bytes[i].toInt() and 255) shl 8
            if (i + 1 < bytes.size) sum += bytes[i + 1].toInt() and 255
            i += 2
        }
        while (sum ushr 16 != 0) sum = (sum and 65535) + (sum ushr 16)
        return sum.inv() and 65535
    }
}
