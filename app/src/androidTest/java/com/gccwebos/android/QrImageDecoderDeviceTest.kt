package com.gccwebos.android

import android.graphics.Bitmap
import android.graphics.Color
import com.gccwebos.android.manualimport.QrImageDecoder
import com.gccwebos.android.manualimport.QrImageResult
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Real BitmapFactory path (PNG/JPEG bytes -> pixels -> ZXing) with generated codes only. */
class QrImageDecoderDeviceTest {
    private val url = "https://sub.invalid/sub/a/synthetic?token=synthetic"

    private fun qrBitmap(text: String, size: Int, canvas: Int = size, dark: Int = Color.BLACK, light: Int = Color.WHITE): Bitmap {
        val m = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(canvas, canvas, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(light)
        val off = (canvas - size) / 2
        for (y in 0 until size) for (x in 0 until size) if (m[x, y]) bmp.setPixel(off + x, off + y, dark)
        return bmp
    }
    private fun encode(bmp: Bitmap, format: Bitmap.CompressFormat, quality: Int = 100) =
        ByteArrayOutputStream().also { bmp.compress(format, quality, it) }.toByteArray()
    private fun decode(bytes: ByteArray) = QrImageDecoder.decodeStream(ByteArrayInputStream(bytes))

    @Test fun pngAndJpegCodesDecode() {
        for (format in listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG)) {
            val result = decode(encode(qrBitmap(url, 420), format, 85))
            assertTrue("$format -> $result", result is QrImageResult.Found)
            assertEquals(url, (result as QrImageResult.Found).text)
        }
    }

    @Test fun aLargePictureIsSampledDownBeforeDecoding() {
        val result = decode(encode(qrBitmap(url, 1500, canvas = 5000), Bitmap.CompressFormat.PNG))
        assertTrue(result.toString(), result is QrImageResult.Found)
    }

    @Test fun lightOnDarkScreenshotsDecode() {
        assertTrue(decode(encode(qrBitmap(url, 420, dark = Color.WHITE, light = Color.BLACK), Bitmap.CompressFormat.PNG)) is QrImageResult.Found)
    }

    @Test fun aPictureWithoutACodeAndNonImagesAreReportedDistinctly() {
        val blank = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.LTGRAY) }
        assertSame(QrImageResult.NoQr, decode(encode(blank, Bitmap.CompressFormat.PNG)))
        assertSame(QrImageResult.Unreadable, decode("this is not an image".toByteArray()))
        assertSame(QrImageResult.Unreadable, decode(ByteArray(0)))
        assertSame(QrImageResult.Unreadable, decode(ByteArray(2048) { 0x41 }))
    }
}
