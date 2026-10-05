package com.gccwebos.android.manualimport

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal sealed interface ManualInputResult {
    /** Ownership passes to the callback, which must submit or erase these bytes. */
    class Content(val bytes: ByteArray) : ManualInputResult {
        override fun toString() = "ManualInputResult.Content([REDACTED])"
    }
    class Failure(val reason: ManualImportFailure) : ManualInputResult
}
internal fun interface ManualInputCancellation { fun cancel() }
internal interface ManualImportPlatform {
    fun launch(kind: ManualImportInputKind, result: (ManualInputResult) -> Unit): ManualInputCancellation
}

/** No extension, MIME, display name or declared length is used as admission evidence. */
internal suspend fun readManualDocument(resolver: ContentResolver, uri: Uri): ByteArray {
    if (uri.scheme != "content") throw ManualInputException(ManualImportFailure.DocumentUnavailable)
    // Also owns completion across withContext's prompt-cancellation return boundary.
    val completed = AtomicReference<ByteArray?>()
    try {
        val bytes = withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                val owned = AtomicReference<InputStream?>()
                continuation.invokeOnCancellation {
                    runCatching { signal.cancel() }
                    runCatching { owned.getAndSet(null)?.close() }
                }
                try {
                    val descriptor = resolver.openFileDescriptor(uri, "r", signal)
                        ?: throw ManualInputException(ManualImportFailure.DocumentUnavailable)
                    val stream = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                    owned.set(stream)
                    if (!continuation.isActive) return@suspendCancellableCoroutine
                    val bytes = ManualInputBytes.read(stream)
                    completed.set(bytes)
                    continuation.resume(bytes) { _, value, _ -> value.fill(0) }
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(
                        if (failure is ManualInputException) failure else ManualInputException(ManualImportFailure.DocumentUnavailable)))
                } finally { runCatching { owned.getAndSet(null)?.close() } }
            }
        }
        completed.compareAndSet(bytes, null)
        return bytes
    } finally {
        completed.getAndSet(null)?.fill(0)
    }
}

internal fun manualDocumentIntent() = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
    addCategory(Intent.CATEGORY_OPENABLE)
    type = "*/*"
    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

@Composable
internal fun rememberManualImportPlatform(): ManualImportPlatform {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val owner = remember(context, scope) { ManualPlatformOwner(context, scope) }
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult(), owner::scanResult)
    val document = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult(), owner::documentResult)
    owner.startScan = { scanner.launch(it) }
    owner.startDocument = { document.launch(it) }
    DisposableEffect(owner) { onDispose { owner.close() } }
    return owner
}

/** A canceled system picker retains its slot until its result arrives, preventing callback reassignment. */
private class ManualPlatformOwner(private val context: Context, private val scope: CoroutineScope) : ManualImportPlatform {
    private class Pending(val kind: ManualImportInputKind, var callback: ((ManualInputResult) -> Unit)?, val receipt: String? = null) {
        var canceled = false
        var waitingForActivity = true
        var job: Job? = null
    }
    var startScan: (Intent) -> Unit = {}
    var startDocument: (Intent) -> Unit = {}
    private var pending: Pending? = null
    private var closed = false
    override fun launch(kind: ManualImportInputKind, result: (ManualInputResult) -> Unit): ManualInputCancellation {
        if (closed || pending != null || kind == ManualImportInputKind.Link) {
            result(ManualInputResult.Failure(ManualImportFailure.LaunchUnavailable))
            return ManualInputCancellation {}
        }
        val operation = Pending(kind, result, if (kind == ManualImportInputKind.Scan) ManualScanMailbox.open() else null)
        pending = operation
        try {
            if (kind == ManualImportInputKind.Scan) startScan(Intent(context, ManualQrScannerActivity::class.java)
                .putExtra(ManualQrScannerActivity.Receipt, operation.receipt))
            else startDocument(manualDocumentIntent())
        } catch (_: Exception) {
            operation.waitingForActivity = false
            finish(operation, ManualInputResult.Failure(ManualImportFailure.LaunchUnavailable))
        }
        return ManualInputCancellation { cancel(operation) }
    }
    fun scanResult(result: ActivityResult) {
        val operation = pending?.takeIf { it.kind == ManualImportInputKind.Scan } ?: return
        operation.waitingForActivity = false
        val receipt = requireNotNull(operation.receipt)
        val bytes = if (result.resultCode == Activity.RESULT_OK && result.data?.getStringExtra(ManualQrScannerActivity.Receipt) == receipt)
            ManualScanMailbox.take(receipt) else null
        val reason = result.data?.getStringExtra(ManualQrScannerActivity.Failure)?.let { name ->
            ManualImportFailure.entries.firstOrNull { it.name == name }
        } ?: ManualImportFailure.Cancelled
        finish(operation, bytes?.let { ManualInputResult.Content(it) } ?: ManualInputResult.Failure(reason))
    }
    fun documentResult(result: ActivityResult) {
        val operation = pending?.takeIf { it.kind == ManualImportInputKind.Document } ?: return
        operation.waitingForActivity = false
        if (operation.canceled || closed) { finish(operation, ManualInputResult.Failure(ManualImportFailure.Cancelled)); return }
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            finish(operation, ManualInputResult.Failure(ManualImportFailure.Cancelled)); return
        }
        if (result.data?.clipData?.itemCount?.let { it > 1 } == true || uri.scheme != "content") {
            finish(operation, ManualInputResult.Failure(ManualImportFailure.DocumentUnavailable)); return
        }
        operation.job = scope.launch {
            try { finish(operation, ManualInputResult.Content(readManualDocument(context.contentResolver, uri))) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: ManualInputException) { finish(operation, ManualInputResult.Failure(failure.failure)) }
            catch (_: Exception) { finish(operation, ManualInputResult.Failure(ManualImportFailure.DocumentUnavailable)) }
        }
    }
    private fun finish(operation: Pending, result: ManualInputResult) {
        operation.receipt?.let(ManualScanMailbox::cancel)
        if (pending === operation) pending = null
        val callback = operation.callback
        operation.callback = null
        if (closed || operation.canceled || callback == null) {
            (result as? ManualInputResult.Content)?.bytes?.fill(0)
        } else callback(result)
    }
    private fun cancel(operation: Pending) {
        operation.canceled = true; operation.callback = null
        operation.job?.cancel(); operation.receipt?.let(ManualScanMailbox::cancel)
        if (!operation.waitingForActivity && pending === operation) pending = null
    }
    fun close() { closed = true; pending?.let(::cancel) }
}
