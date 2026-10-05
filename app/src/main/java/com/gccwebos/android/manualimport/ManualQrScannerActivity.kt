package com.gccwebos.android.manualimport

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DecoderFactory

/** Private bundled camera only. Text, frames and decoder results never enter saved state or an Intent. */
class ManualQrScannerActivity : ComponentActivity() {
    private var barcode: BarcodeView? = null
    private var receipt: String? = null
    private var finished = false
    private var resumed = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!finished) {
            if (granted) startCamera() else reject(ManualImportFailure.CameraPermission)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        receipt = intent.getStringExtra(Receipt)
        if (savedInstanceState != null || receipt?.let(ManualScanMailbox::exists) != true) {
            reject(ManualImportFailure.Cancelled); return
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = reject(ManualImportFailure.Cancelled)
        })
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            reject(ManualImportFailure.CameraUnavailable); return
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            isSaveEnabled = false
            fitsSystemWindows = true
        }
        content.addView(TextView(this).apply {
            text = "扫描配置二维码"; textSize = 18f; setTextColor(Color.WHITE)
            val space = (16 * resources.displayMetrics.density).toInt()
            setPadding(space, space, space, space)
            isSaveEnabled = false
        })
        barcode = BarcodeView(this).also { view ->
            view.isSaveEnabled = false
            view.isSaveFromParentEnabled = false
            view.decoderFactory = DecoderFactory { SingleQrDecoder {
                runOnUiThread { reject(ManualImportFailure.InvalidInput) }
            } }
            view.addStateListener(object : CameraPreview.StateListener {
                override fun previewSized() = Unit
                override fun previewStarted() = Unit
                override fun previewStopped() = Unit
                override fun cameraClosed() = Unit
                override fun cameraError(error: Exception?) = reject(ManualImportFailure.CameraUnavailable)
            })
            view.decodeSingle(object : BarcodeCallback {
                override fun barcodeResult(result: BarcodeResult?) {
                    if (finished || !resumed || result == null) return
                    val bytes = try { ManualInputBytes.fromText(result.text ?: "") }
                        catch (failure: ManualInputException) { reject(failure.failure); return }
                    val id = receipt
                    if (id == null || !ManualScanMailbox.publish(id, bytes)) {
                        bytes.fill(0); reject(ManualImportFailure.Cancelled); return
                    }
                    finished = true
                    view.stopDecoding(); view.pause()
                    setResult(RESULT_OK, Intent().putExtra(Receipt, id))
                    finish()
                }
            })
            content.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        content.addView(Button(this).apply {
            text = "取消"
            minHeight = (48 * resources.displayMetrics.density).toInt()
            isSaveEnabled = false
            setOnClickListener { reject(ManualImportFailure.Cancelled) }
        })
        setContentView(content)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() { super.onResume(); resumed = true; startCamera() }
    private fun startCamera() {
        if (finished || !resumed || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (receipt?.let(ManualScanMailbox::exists) != true) { reject(ManualImportFailure.Cancelled); return }
        try { barcode?.resume() } catch (_: Exception) { reject(ManualImportFailure.CameraUnavailable) }
    }
    override fun onPause() { resumed = false; barcode?.pause(); super.onPause() }
    override fun onDestroy() {
        barcode?.stopDecoding(); barcode?.pause(); barcode = null
        if (!finished) receipt?.let(ManualScanMailbox::cancel)
        super.onDestroy()
    }
    private fun reject(reason: ManualImportFailure) {
        if (finished) return
        finished = true
        receipt?.let(ManualScanMailbox::cancel)
        barcode?.stopDecoding(); barcode?.pause()
        setResult(RESULT_CANCELED, Intent().putExtra(Failure, reason.name))
        finish()
    }
    companion object {
        internal const val Receipt = "manual.scan.receipt"
        internal const val Failure = "manual.scan.failure"
    }
}
