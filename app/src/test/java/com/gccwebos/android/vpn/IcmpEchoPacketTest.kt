package com.gccwebos.android.vpn

import org.junit.Assert.*
import org.junit.Test

class IcmpEchoPacketTest {
    private val nonce = ByteArray(24) { (it * 11).toByte() }
    private fun reply() = IcmpEchoPacket.request(nonce).also { it[0] = 0; it[4] = 75; it[5] = 13 }

    @Test fun requestHasEchoSequenceChecksumAndExactNonceWithoutMutatingInput() {
        val copy = nonce.copyOf()
        val request = IcmpEchoPacket.request(nonce)
        assertEquals(32, request.size); assertEquals(8, request[0].toInt()); assertEquals(1, request[7].toInt())
        assertArrayEquals(copy, nonce); assertArrayEquals(copy, request.copyOfRange(8, 32))
        var sum = request.indices.step(2).sumOf { ((request[it].toInt() and 255) shl 8) + (request[it + 1].toInt() and 255) }
        while (sum ushr 16 != 0) sum = (sum and 65535) + (sum ushr 16)
        assertEquals(65535, sum)
    }
    @Test fun kernelAssignedIdentifierDoesNotReplaceThePayloadAndSequenceChecks() {
        assertTrue(IcmpEchoPacket.matchesReply(reply(), 32, nonce))
        for (index in listOf(0, 1, 6, 7, 8, 31)) {
            val changed = reply().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertFalse(IcmpEchoPacket.matchesReply(changed, 32, nonce))
        }
    }
    @Test fun otherSocketNonceWrongLengthAndRawIpHeaderCannotProduceAMeasurement() {
        assertFalse(IcmpEchoPacket.matchesReply(reply(), 31, nonce))
        assertFalse(IcmpEchoPacket.matchesReply(reply(), 33, nonce))
        assertFalse(IcmpEchoPacket.matchesReply(ByteArray(4), 32, nonce))
        assertFalse(IcmpEchoPacket.matchesReply(reply(), 32, nonce.copyOf().also { it[0]++ }))
        assertFalse(IcmpEchoPacket.matchesReply(ByteArray(20) + reply(), 52, nonce))
    }
}
