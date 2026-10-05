package com.gccwebos.android.vpn

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class QuicVersionProbePacketTest {
    private val destinationId = ByteArray(16) { (it * 7 + 3).toByte() }
    private val sourceId = ByteArray(16) { (it * 11 + 129).toByte() }

    @Test fun requestUsesReservedVersionAndBothCallerIdsInA1200ByteDatagram() {
        val destinationCopy = destinationId.copyOf()
        val sourceCopy = sourceId.copyOf()
        val request = QuicVersionProbePacket.request(destinationId, sourceId)

        assertEquals(16, QuicVersionProbePacket.ConnectionIdBytes)
        assertEquals(1200, QuicVersionProbePacket.RequestBytes)
        assertEquals(1200, request.size)
        assertEquals(0xc0, request[0].toInt() and 255)
        assertArrayEquals(byteArrayOf(10, 10, 10, 10), request.copyOfRange(1, 5))
        assertEquals(16, request[5].toInt())
        assertArrayEquals(destinationCopy, request.copyOfRange(6, 22))
        assertEquals(16, request[22].toInt())
        assertArrayEquals(sourceCopy, request.copyOfRange(23, 39))
        assertTrue(request.copyOfRange(39, request.size).all { it == 0.toByte() })
        assertArrayEquals(destinationCopy, destinationId)
        assertArrayEquals(sourceCopy, sourceId)

        destinationId.fill(0)
        sourceId.fill(0)
        assertArrayEquals(destinationCopy, request.copyOfRange(6, 22))
        assertArrayEquals(sourceCopy, request.copyOfRange(23, 39))
    }

    @Test fun requestRejectsConnectionIdsOutsideTheProbeContract() {
        for (length in listOf(0, 1, 15, 17, 20, 255)) {
            assertThrows(IllegalArgumentException::class.java) {
                QuicVersionProbePacket.request(ByteArray(length), sourceId)
            }
            assertThrows(IllegalArgumentException::class.java) {
                QuicVersionProbePacket.request(destinationId, ByteArray(length))
            }
        }
    }

    @Test fun acceptsReversedIdsAndAllUnusedHeaderBitValues() {
        for (unusedBits in 0..127) {
            val packet = reply(1, 0x6b3343cf, 0x8a8a8a8a.toInt()).also {
                it[0] = (0x80 or unusedBits).toByte()
            }
            assertTrue(matches(packet))
        }
    }

    @Test fun rejectsEveryTruncatedPrefixAndInvalidReceiveCountWithoutThrowing() {
        val packet = reply(1)
        for (count in 0 until packet.size) {
            assertFalse(QuicVersionProbePacket.matchesReply(packet, count, destinationId, sourceId))
            assertFalse(matches(packet.copyOf(count)))
        }
        for (count in listOf(Int.MIN_VALUE, -1, packet.size + 1, Int.MAX_VALUE)) {
            assertFalse(QuicVersionProbePacket.matchesReply(packet, count, destinationId, sourceId))
        }
        assertFalse(QuicVersionProbePacket.matchesReply(ByteArray(4), packet.size, destinationId, sourceId))
    }

    @Test fun onlyBytesWithinTheReceivedDatagramAreExaminedAndInputsAreNotMutated() {
        val packet = reply(1, 2)
        val receiveBuffer = packet + ByteArray(64) { 0x55 }
        val bufferCopy = receiveBuffer.copyOf()
        val destinationCopy = destinationId.copyOf()
        val sourceCopy = sourceId.copyOf()
        assertTrue(QuicVersionProbePacket.matchesReply(receiveBuffer, packet.size, destinationId, sourceId))
        assertArrayEquals(bufferCopy, receiveBuffer)
        assertArrayEquals(destinationCopy, destinationId)
        assertArrayEquals(sourceCopy, sourceId)
    }

    @Test fun eitherWrongConnectionIdOrTheUnreversedPairIsRejected() {
        for (index in (6 until 22) + (23 until 39)) {
            val changed = reply(1).also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertFalse(matches(changed))
        }
        val unreversed = reply(1).also {
            destinationId.copyInto(it, 6)
            sourceId.copyInto(it, 23)
        }
        assertFalse(matches(unreversed))
        assertFalse(QuicVersionProbePacket.matchesReply(reply(1), 43, sourceId, destinationId))
    }

    @Test fun malformedConnectionIdLengthsAndInvalidExpectedIdsAreRejected() {
        for (length in listOf(0, 1, 15, 17, 20, 255)) {
            for (index in listOf(5, 22)) {
                assertFalse(matches(reply(1).also { it[index] = length.toByte() }))
            }
            assertFalse(QuicVersionProbePacket.matchesReply(reply(1), 43, ByteArray(length), sourceId))
            assertFalse(QuicVersionProbePacket.matchesReply(reply(1), 43, destinationId, ByteArray(length)))
        }
    }

    @Test fun nonNegotiationPacketsAndTheRequestEchoAreRejected() {
        assertFalse(matches(reply(1).also { it[0] = 0x7f }))
        for (index in 1..4) {
            assertFalse(matches(reply(1).also { it[index] = 1 }))
        }
        assertFalse(matches(QuicVersionProbePacket.request(destinationId, sourceId)))
        assertFalse(matches(ByteArray(1200)))
    }

    @Test fun versionTableRequiresWholeEntriesAndRejectsZeroOrRequestedVersionAnywhere() {
        assertFalse(matches(reply()))
        for (suffixLength in 1..3) {
            assertFalse(matches(reply() + ByteArray(suffixLength) { 1 }))
            assertFalse(matches(reply(1) + ByteArray(suffixLength) { 1 }))
        }
        for (invalid in listOf(0, 0x0a0a0a0a)) {
            assertFalse(matches(reply(invalid)))
            assertFalse(matches(reply(invalid, 1)))
            assertFalse(matches(reply(1, invalid)))
            assertFalse(matches(reply(1, invalid, 2)))
        }
        assertTrue(matches(reply(1)))
        assertTrue(matches(reply(1, 2)))
        assertTrue(matches(reply(0xffffffff.toInt(), 0xfafafafa.toInt())))
    }

    @Test fun plausibleRandomRepliesFromAnotherProbeCannotProduceAMatch() {
        val random = Random(82741L)
        repeat(256) {
            val packet = reply(1)
            val unrelatedIds = ByteArray(32).also { random.nextBytes(it) }
            unrelatedIds.copyInto(packet, 6, 0, 16)
            unrelatedIds.copyInto(packet, 23, 16, 32)
            assertFalse(matches(packet))
        }
    }

    private fun matches(packet: ByteArray): Boolean =
        QuicVersionProbePacket.matchesReply(packet, packet.size, destinationId, sourceId)

    // Independent wire fixture: VN has no payload length or packet number after its reversed CIDs.
    private fun reply(vararg versions: Int): ByteArray = ByteArray(39 + versions.size * 4).also {
        it[0] = 0x80.toByte()
        it[5] = 16
        sourceId.copyInto(it, 6)
        it[22] = 16
        destinationId.copyInto(it, 23)
        versions.forEachIndexed { index, version ->
            val offset = 39 + index * 4
            it[offset] = (version ushr 24).toByte()
            it[offset + 1] = (version ushr 16).toByte()
            it[offset + 2] = (version ushr 8).toByte()
            it[offset + 3] = version.toByte()
        }
    }
}
