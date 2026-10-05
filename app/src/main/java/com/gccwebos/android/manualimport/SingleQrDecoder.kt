package com.gccwebos.android.manualimport

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.Decoder

/** One detected QR per frame; ambiguous frames never choose an arbitrary configuration. */
internal class SingleQrDecoder(private val onMultiple: () -> Unit = {}) : Decoder(QRCodeReader()) {
    override fun decode(source: LuminanceSource): Result? = try {
        val results = QRCodeMultiReader().decodeMultiple(BinaryBitmap(HybridBinarizer(source)),
            mapOf(DecodeHintType.TRY_HARDER to true))
        if (results.size == 1) results.single() else { if (results.size > 1) onMultiple(); null }
    } catch (_: Exception) { null }
}
