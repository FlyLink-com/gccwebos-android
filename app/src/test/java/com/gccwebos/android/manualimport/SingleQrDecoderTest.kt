package com.gccwebos.android.manualimport

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import org.junit.Assert.*
import org.junit.Test

class SingleQrDecoderTest {
    private fun image(vararg values: String): RGBLuminanceSource {
        val size = 300
        val width = values.size * 400
        val pixels = IntArray(width * 400) { -1 }
        values.forEachIndexed { index, text ->
            val qr = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
            for (y in 0 until size) for (x in 0 until size)
                if (qr[x, y]) pixels[(y + 50) * width + index * 400 + x + 50] = 0xff000000.toInt()
        }
        return RGBLuminanceSource(width, 400, pixels)
    }
    @Test fun packagedDecoderRoundTripsSyntheticUrlAndYamlWithoutAnyRemoteDependency() {
        listOf("https://sub.invalid/sub/a/synthetic", "proxies: []\n").forEach { fixture ->
            assertEquals(fixture, SingleQrDecoder().decode(image(fixture))?.text)
        }
    }
    @Test fun multipleDetectedCodesAreRejectedInsteadOfChoosingEitherPayload() {
        var rejected = 0
        assertNull(SingleQrDecoder { rejected++ }.decode(image("synthetic-one", "synthetic-two")))
        assertEquals(1, rejected)
    }
    @Test fun blankFrameHasNoResultAndNoSyntheticSuccess() {
        assertNull(SingleQrDecoder().decode(RGBLuminanceSource(100, 100, IntArray(10000) { -1 })))
    }
}
