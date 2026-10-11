package com.gccwebos.android.manualimport

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import java.io.InputStream

/** Outcome of reading one QR code from a picked photo or screenshot. Never carries or prints the decoded text elsewhere. */
internal sealed interface QrImageResult {
    /** Ownership of the text passes to the caller, who must submit it through the same path as a camera scan. */
    class Found(val text: String) : QrImageResult { override fun toString() = "QrImageResult.Found([REDACTED])" }
    /** The picture decoded but holds no QR code. */
    object NoQr : QrImageResult
    /** More than one QR code: like the camera, an ambiguous picture never chooses one at random. */
    object Multiple : QrImageResult
    /** The file is not an image, is too large, or could not be read. */
    object Unreadable : QrImageResult
}

/**
 * Decodes one QR from an image the user picked in the system Photo Picker. Pure ZXing, offline, no GMS, no disk copy:
 * the picked bytes live only in memory and every buffer holding image data is wiped when decoding ends.
 *
 * Tries, cheaply and in order: the image as is (≤ [MaxSide] px), inverted (light-on-dark screenshots), then a 2x and 4x
 * box-downscale of both (very large photos where the code is only a few pixels per module). QR detection itself is
 * rotation invariant, so a rotated photo needs no extra pass.
 */
internal object QrImageDecoder {
    /** Longest side handed to the decoder; larger pictures are decoded with BitmapFactory sampling. */
    const val MaxSide = 2048
    /** A picked file larger than this is refused before decoding (decoded pixels are bounded by [MaxSide] anyway). */
    const val MaxFileBytes = 32 * 1024 * 1024
    /** Refuse absurd pixel counts before allocating (a 100 MP file would still sample down, but may be hostile). */
    const val MaxSourcePixels = 120_000_000L

    fun decodeStream(stream: InputStream): QrImageResult {
        val bytes = try { readBounded(stream) } catch (_: Exception) { return QrImageResult.Unreadable } ?: return QrImageResult.Unreadable
        try { return decodeEncoded(bytes) } finally { bytes.fill(0) }
    }

    private fun readBounded(stream: InputStream): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        try {
            var total = 0
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                total += read
                if (total > MaxFileBytes) return null
                buffer.write(chunk, 0, read)
            }
            return buffer.toByteArray().takeIf { it.isNotEmpty() }
        } finally { chunk.fill(0); buffer.reset() }
    }

    internal fun decodeEncoded(bytes: ByteArray): QrImageResult {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > MaxSourcePixels)
            return QrImageResult.Unreadable
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MaxSide) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) } catch (_: OutOfMemoryError) { null }
            ?: return QrImageResult.Unreadable
        val width = bitmap.width; val height = bitmap.height
        val pixels = IntArray(width * height)
        try {
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            bitmap.recycle()
            return decodePixels(width, height, pixels)
        } finally { pixels.fill(0) }
    }

    /** Pure decode of ARGB pixels. Does not keep or modify [pixels]. */
    fun decodePixels(width: Int, height: Int, pixels: IntArray): QrImageResult {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return QrImageResult.Unreadable
        var w = width; var h = height; var current = pixels
        var scaled: IntArray? = null
        try {
            for (pass in 0..2) {
                for (invert in listOf(false, true)) {
                    when (val result = detect(w, h, current, invert)) {
                        is QrImageResult.Found, QrImageResult.Multiple -> return result
                        else -> Unit
                    }
                }
                if (minOf(w, h) < 160) break
                val next = halve(w, h, current)
                scaled?.fill(0)
                scaled = next.third; w = next.first; h = next.second; current = next.third
                // 2x then 4x (a pass here halves the previous result).
            }
            return QrImageResult.NoQr
        } finally { scaled?.fill(0) }
    }

    private fun detect(width: Int, height: Int, pixels: IntArray, invert: Boolean): QrImageResult {
        val base: LuminanceSource = RGBLuminanceSource(width, height, pixels)
        val source = if (invert) base.invert() else base
        val results = try {
            QRCodeMultiReader().decodeMultiple(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true))
        } catch (_: Exception) { return QrImageResult.NoQr }
        return when {
            results.size == 1 -> QrImageResult.Found(results.single().text ?: "")
            results.size > 1 -> QrImageResult.Multiple
            else -> QrImageResult.NoQr
        }
    }

    private fun halve(width: Int, height: Int, pixels: IntArray): Triple<Int, Int, IntArray> {
        val w = width / 2; val h = height / 2
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0; var g = 0; var b = 0
            for (dy in 0..1) for (dx in 0..1) {
                val p = pixels[(2 * y + dy) * width + 2 * x + dx]
                r += (p shr 16) and 0xff; g += (p shr 8) and 0xff; b += p and 0xff
            }
            out[y * w + x] = (0xff shl 24) or ((r / 4) shl 16) or ((g / 4) shl 8) or (b / 4)
        }
        return Triple(w, h, out)
    }
}
