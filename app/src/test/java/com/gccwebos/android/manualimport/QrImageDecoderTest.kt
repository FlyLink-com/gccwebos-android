package com.gccwebos.android.manualimport

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

/** Generated QR pixels only; the real BitmapFactory step is covered on a device (see QrImageDecoderDeviceTest). */
class QrImageDecoderTest {
    private val url = "https://sub.invalid/sub/a/synthetic?token=synthetic"
    private class Img(val w: Int, val h: Int, val px: IntArray)

    private fun qr(text: String, size: Int, margin: Int = 4): Img {
        val m = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to margin))
        return Img(size, size, IntArray(size * size) { if (m[it % size, it / size]) -0x1000000 else -1 })
    }
    private fun canvas(w: Int, h: Int, fill: Int = -1) = Img(w, h, IntArray(w * h) { fill })
    private fun Img.paste(other: Img, x0: Int, y0: Int) = apply {
        for (y in 0 until other.h) for (x in 0 until other.w) px[(y0 + y) * w + x0 + x] = other.px[y * other.w + x]
    }
    private fun Img.invert() = Img(w, h, IntArray(px.size) { 0xff000000.toInt() or (px[it].inv() and 0xffffff) })
    private fun Img.rotate90() = Img(h, w, IntArray(px.size).also { out -> for (y in 0 until h) for (x in 0 until w) out[x * h + (h - 1 - y)] = px[y * w + x] })
    private fun Img.rotate180() = Img(w, h, IntArray(px.size) { px[px.size - 1 - it] })
    /** Rotates by an arbitrary angle on a white canvas (nearest neighbour), like a tilted photo. */
    private fun Img.rotate(degrees: Double): Img {
        val size = (w * 1.5).roundToInt(); val out = IntArray(size * size) { -1 }
        val a = Math.toRadians(degrees); val c = cos(a); val s = sin(a)
        for (y in 0 until size) for (x in 0 until size) {
            val dx = x - size / 2.0; val dy = y - size / 2.0
            val sx = (dx * c + dy * s + w / 2.0).roundToInt(); val sy = (-dx * s + dy * c + h / 2.0).roundToInt()
            if (sx in 0 until w && sy in 0 until h) out[y * size + x] = px[sy * w + sx]
        }
        return Img(size, size, out)
    }
    private fun decode(img: Img) = QrImageDecoder.decodePixels(img.w, img.h, img.px)
    private fun assertFound(expected: String, result: QrImageResult) {
        assertTrue("expected a code, got $result", result is QrImageResult.Found)
        assertEquals(expected, (result as QrImageResult.Found).text)
    }

    @Test fun aValidQrDecodesToItsExactText() {
        assertFound(url, decode(qr(url, 400)))
        assertFound("proxies: []\n", decode(qr("proxies: []\n", 300)))
    }

    @Test fun aScreenshotSizedPageWithASmallCodeDecodes() {
        val page = canvas(1080, 2400, 0xfff0f0f0.toInt()).paste(qr(url, 360), 360, 900)
        assertFound(url, decode(page))
    }

    @Test fun lightOnDarkScreenshotsDecodeThroughTheInvertedPass() {
        assertFound(url, decode(qr(url, 400).invert()))
    }

    @Test fun rotatedAndTiltedCodesDecode() {
        val base = qr(url, 420)
        assertFound(url, decode(base.rotate90()))
        assertFound(url, decode(base.rotate180()))
        assertFound(url, decode(base.rotate(33.0)))
    }

    @Test fun aLargePhotoWithAHighResolutionCodeDecodesWithoutBlowingUp() {
        val big = canvas(4000, 3000, 0xffe8e8e8.toInt()).paste(qr(url, 900), 1500, 900)
        assertFound(url, decode(big))
    }

    @Test fun aHugeCodeThatNeedsDownscalingStillDecodes() {
        // 4000 px code in a 4096 px picture: the pass at full size is slow/blurry for some decoders; downscaled passes recover it.
        val big = canvas(4096, 4096).paste(qr(url, 3600, 8), 248, 248)
        assertFound(url, decode(big))
    }

    @Test fun picturesWithoutACodeReportNoQr() {
        assertSame(QrImageResult.NoQr, decode(canvas(500, 500)))
        assertSame(QrImageResult.NoQr, decode(canvas(500, 500, -0x1000000)))
        val noise = java.util.Random(7); val img = canvas(600, 600)
        for (i in img.px.indices) img.px[i] = if (noise.nextBoolean()) -1 else -0x1000000
        assertSame(QrImageResult.NoQr, decode(img))
        assertSame(QrImageResult.NoQr, decode(canvas(8, 8)))
    }

    @Test fun twoCodesAreAmbiguousAndNeverPickOne() {
        val page = canvas(900, 450).paste(qr("synthetic-one", 380), 20, 35).paste(qr("synthetic-two", 380), 480, 35)
        assertSame(QrImageResult.Multiple, decode(page))
    }

    @Test fun malformedPixelBuffersAreUnreadableNotCrashes() {
        assertSame(QrImageResult.Unreadable, QrImageDecoder.decodePixels(0, 10, IntArray(0)))
        assertSame(QrImageResult.Unreadable, QrImageDecoder.decodePixels(10, -1, IntArray(100)))
        assertSame(QrImageResult.Unreadable, QrImageDecoder.decodePixels(100, 100, IntArray(50)))
    }

    @Test fun nonUrlTextStillComesBackUntouchedBecauseValidationIsDownstream() {
        assertFound("not a url at all", decode(qr("not a url at all", 300)))
        val long = "a".repeat(2000)
        assertFound(long, decode(qr(long, 900)))
    }

    @Test fun decodingNeverModifiesTheCallersPixelsAndNeverPrintsTheText() {
        val img = qr(url, 400); val before = img.px.copyOf()
        val result = decode(img)
        assertArrayEquals(before, img.px)
        assertFalse(result.toString().contains("sub.invalid"))
        assertEquals("QrImageResult.Found([REDACTED])", result.toString())
    }

    @Test fun theDecodedTextGoesThroughTheSameByteBoundaryAsTheCamera() {
        val text = (decode(qr(url, 400)) as QrImageResult.Found).text
        assertArrayEquals(url.toByteArray(), ManualInputBytes.fromText(text))
    }
}
