package com.gccwebos.android.manualimport

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class ManualInputBoundaryTest {
    @Test fun exactLimitAcceptedButOneMoreByteRejectedRegardlessOfAvailableHint() {
        val exact = ByteArray(ManualInputBytes.Limit) { 'a'.code.toByte() }
        assertArrayEquals(exact, ManualInputBytes.read(ByteArrayInputStream(exact)))
        var reads = 0
        val oversized = object : InputStream() {
            override fun available() = 0
            override fun read(): Int { reads++; return 'a'.code }
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                target.fill('a'.code.toByte(), offset, offset + length); reads += length; return length
            }
        }
        try { ManualInputBytes.read(oversized); fail("Oversized input must be rejected") }
        catch (failure: ManualInputException) { assertEquals(ManualImportFailure.TooLarge, failure.failure) }
        assertEquals(ManualInputBytes.Limit + 1, reads)
    }

    @Test fun emptyMalformedUtf8AndUnpairedSurrogateHaveOnlyFixedFailures() {
        for (raw in listOf(byteArrayOf(), byteArrayOf(0xc0.toByte(), 0x80.toByte()), byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()))) {
            try { ManualInputBytes.read(ByteArrayInputStream(raw)); fail("Invalid input must fail") }
            catch (failure: ManualInputException) { assertEquals(ManualImportFailure.InvalidInput, failure.failure) }
        }
        try { ManualInputBytes.fromText("\uD800"); fail("Unpaired surrogate must fail") }
        catch (failure: ManualInputException) { assertEquals(ManualImportFailure.InvalidInput, failure.failure) }
    }

    @Test fun localYamlAndUrlRemainOwnedBytesWithoutClassificationOrNetwork() {
        listOf("proxies: []\n", "https://sub.invalid/sub/a/synthetic").forEach { input ->
            val raw = ManualInputBytes.fromText(input)
            try { assertArrayEquals(input.toByteArray(), ManualInputBytes.read(ByteArrayInputStream(raw))) }
            finally { raw.fill(0) }
        }
    }

    @Test fun canceledOrConsumedScannerReceiptRejectsLateAndRepeatedPayloadsAndWipesThem() {
        val canceled = ManualScanMailbox.open()
        ManualScanMailbox.cancel(canceled)
        val late = byteArrayOf(1, 2, 3)
        assertFalse(ManualScanMailbox.publish(canceled, late)); assertTrue(late.all { it == 0.toByte() })
        val receipt = ManualScanMailbox.open()
        val payload = byteArrayOf(4, 5, 6)
        assertTrue(ManualScanMailbox.publish(receipt, payload))
        val duplicate = byteArrayOf(7, 8)
        assertFalse(ManualScanMailbox.publish(receipt, duplicate)); assertTrue(duplicate.all { it == 0.toByte() })
        assertSame(payload, ManualScanMailbox.take(receipt)); assertNull(ManualScanMailbox.take(receipt))
        payload.fill(0)
        val wipe = ManualScanMailbox.open()
        val staged = byteArrayOf(9)
        assertTrue(ManualScanMailbox.publish(wipe, staged)); ManualScanMailbox.cancel(wipe)
        assertEquals(0, staged[0].toInt()); assertNull(ManualScanMailbox.take(wipe))
    }
}
