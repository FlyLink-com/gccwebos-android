package com.gccwebos.android.manualimport

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.lifecycleScope
import com.gccwebos.android.design.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var status: TextView? = null
    private var gallery: Button? = null
    private var decoding = false
    /** System Photo Picker: no storage permission, the app only receives the one image the user selects. */
    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && !finished) decodePicked(uri)
    }
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
                    publish(bytes)
                }
            })
            content.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        status = TextView(this).apply {
            textSize = 14f; setTextColor(Brand.Text.toArgb()); visibility = View.GONE
            val space = (16 * resources.displayMetrics.density).toInt()
            setPadding(space, space / 2, space, space / 2)
            isSaveEnabled = false
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }.also { content.addView(it) }
        gallery = Button(this).apply {
            text = "从相册选择图片"
            contentDescription = "从相册选择包含二维码的图片"
            setTextColor(Brand.Background.toArgb()); isAllCaps = false
            background = GradientDrawable().apply { setColor(Brand.Cyan.toArgb()); cornerRadius = 12 * resources.displayMetrics.density }
            minHeight = (48 * resources.displayMetrics.density).toInt()
            isSaveEnabled = false
            tag = "manual-scan-gallery"
            setOnClickListener { if (!decoding && !finished) launchPicker() }
            content.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                val m = (16 * resources.displayMetrics.density).toInt(); setMargins(m, m / 2, m, m / 2)
            })
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
    /** Same hand-off for a camera frame and a picked photo: the exact bytes go to the private mailbox and the host. */
    private fun publish(bytes: ByteArray) {
        val id = receipt
        if (id == null || !ManualScanMailbox.publish(id, bytes)) {
            bytes.fill(0); reject(ManualImportFailure.Cancelled); return
        }
        finished = true
        barcode?.stopDecoding(); barcode?.pause()
        setResult(RESULT_OK, Intent().putExtra(Receipt, id))
        finish()
    }

    private fun launchPicker() {
        try { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        catch (_: Exception) { showStatus("图片无法读取") }
    }

    private fun showStatus(text: String?) {
        status?.apply { this.text = text ?: ""; visibility = if (text == null) View.GONE else View.VISIBLE }
    }

    /** Decodes off the main thread; nothing is written to disk and neither the text nor the pixels are logged. */
    private fun decodePicked(uri: android.net.Uri) {
        if (uri.scheme != "content") { showStatus("图片无法读取"); return }
        decoding = true; gallery?.isEnabled = false
        showStatus("正在识别图片中的二维码…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                try { contentResolver.openInputStream(uri)?.use(QrImageDecoder::decodeStream) ?: QrImageResult.Unreadable }
                catch (_: Exception) { QrImageResult.Unreadable }
            }
            decoding = false; gallery?.isEnabled = true
            if (finished) return@launch
            when (result) {
                is QrImageResult.Found -> {
                    val bytes = try { ManualInputBytes.fromText(result.text) }
                        catch (failure: ManualInputException) { showStatus(failure.failure.caption); return@launch }
                    showStatus(null)
                    publish(bytes)
                }
                QrImageResult.NoQr -> showStatus("图片中没有识别到二维码，请换一张清晰的图片")
                QrImageResult.Multiple -> showStatus("图片中有多个二维码，请只保留一个")
                QrImageResult.Unreadable -> showStatus("图片无法读取")
            }
        }
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
