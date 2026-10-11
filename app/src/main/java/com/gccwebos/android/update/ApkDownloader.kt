package com.gccwebos.android.update

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request

enum class DownloadFailureKind { NETWORK, HTTP, REJECTED, SIZE_MISMATCH, INSUFFICIENT_STORAGE, TOO_LARGE, HASH_MISMATCH, STORAGE }

/** A download that did not produce a verified file. [partial] says whether resumable bytes were kept. */
class DownloadFailure(val kind: DownloadFailureKind, val httpCode: Int? = null, val partial: Boolean = false, cause: Throwable? = null) :
    Exception(kind.name, cause)

object UpdateLimits {
    /** Same ceiling the website build enforces for a published artifact. */
    const val MAX_APK_BYTES: Long = 512L * 1024 * 1024
    const val STORAGE_MARGIN_BYTES: Long = 16L * 1024 * 1024
    const val STALE_FILE_MILLIS: Long = 7L * 24 * 60 * 60 * 1000
}

/**
 * Downloads the one official APK named by the verified stable manifest into an app-private directory.
 * Never follows redirects, sends no cookies or credentials, never trusts a manifest-supplied host, and
 * only returns a file whose full SHA-256 equals the manifest value. A partial file is kept (resume by
 * HTTP Range) after network errors and removed after any integrity failure.
 */
class ApkDownloader internal constructor(
    private val calls: Call.Factory,
    private val dir: File,
    private val freeSpace: (File) -> Long = { it.usableSpace },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(dir: File) : this(OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(), dir)

    fun targetFile(release: ReleaseManifest.Available) = File(dir, "feilian-vpn-${release.versionCode}-${release.sha256.take(12)}.apk")
    fun partFile(release: ReleaseManifest.Available) = File(dir, targetFile(release).name + ".part")

    /** Bytes already kept for this release (verified file or resumable part), 0 if none. */
    fun keptBytes(release: ReleaseManifest.Available): Long =
        targetFile(release).takeIf { it.isFile }?.length() ?: partFile(release).takeIf { it.isFile }?.length() ?: 0L

    /** Asks only for headers so the metered-network warning can show the real size. Null when unknown. */
    suspend fun probe(release: ReleaseManifest.Available): Long? = withContext(ioDispatcher) {
        if (!OfficialReleases.isDownloadPath(release.downloadPath)) return@withContext null
        val call = calls.newCall(baseRequest(release).head().build())
        try {
            cancelling(call) {
                call.execute().use { response ->
                    if (response.code != 200 || response.request.url.toString() != release.downloadUrl) null
                    else response.header("Content-Length")?.toLongOrNull()?.takeIf { it in 1..UpdateLimits.MAX_APK_BYTES }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    /** Blocking OkHttp reads ignore coroutine cancellation, so cancelling the scope must cancel the call itself. */
    private suspend fun <T> cancelling(call: Call, block: suspend () -> T): T = coroutineScope {
        val watcher = launch { try { awaitCancellation() } finally { call.cancel() } }
        try { block() } finally { watcher.cancel() }
    }

    suspend fun download(release: ReleaseManifest.Available, onProgress: (received: Long, total: Long?) -> Unit): File =
        withContext(ioDispatcher) {
            if (!OfficialReleases.isDownloadPath(release.downloadPath) || !release.sha256.matches(HEX64)) {
                throw DownloadFailure(DownloadFailureKind.REJECTED)
            }
            try { dir.mkdirs() } catch (e: SecurityException) { throw DownloadFailure(DownloadFailureKind.STORAGE, cause = e) }
            if (!dir.isDirectory) throw DownloadFailure(DownloadFailureKind.STORAGE)
            val target = targetFile(release)
            if (target.isFile) {
                if (sha256(target) == release.sha256) { onProgress(target.length(), target.length()); return@withContext target }
                target.delete()
            }
            transfer(release, partFile(release), target, onProgress, allowRestart = true)
        }

    private suspend fun transfer(release: ReleaseManifest.Available, part: File, target: File,
        onProgress: (Long, Long?) -> Unit, allowRestart: Boolean): File {
        var offset = if (part.isFile) part.length() else 0L
        if (offset > UpdateLimits.MAX_APK_BYTES) { part.delete(); offset = 0 }
        val request = baseRequest(release).also { if (offset > 0) it.header("Range", "bytes=$offset-") }.get().build()
        val call = calls.newCall(request)
        return cancelling(call) {
            val response = try { call.execute() } catch (e: IOException) {
                throw DownloadFailure(DownloadFailureKind.NETWORK, partial = part.isFile && part.length() > 0, cause = e)
            }
            response.use {
                if (it.request.url.toString() != release.downloadUrl || it.code in 300..399) throw DownloadFailure(DownloadFailureKind.REJECTED)
                if (it.code == 416 && offset > 0) {
                    // The kept bytes no longer fit the file the server has; start clean exactly once.
                    part.delete()
                    if (!allowRestart) throw DownloadFailure(DownloadFailureKind.HTTP, 416)
                    return@cancelling transfer(release, part, target, onProgress, allowRestart = false)
                }
                val resuming = it.code == 206
                if (it.code != 200 && !resuming) throw DownloadFailure(DownloadFailureKind.HTTP, it.code, partial = part.isFile && part.length() > 0)
                val body = it.body ?: throw DownloadFailure(DownloadFailureKind.NETWORK)
                val total: Long?
                if (resuming) {
                    val range = CONTENT_RANGE.matchEntire(it.header("Content-Range").orEmpty())
                    val start = range?.groupValues?.get(1)?.toLongOrNull()
                    val last = range?.groupValues?.get(2)?.toLongOrNull()
                    val size = range?.groupValues?.get(3)?.toLongOrNull()
                    if (start != offset || last == null || size == null || last != size - 1 || size <= 0) {
                        part.delete(); throw DownloadFailure(DownloadFailureKind.REJECTED)
                    }
                    total = size
                } else {
                    // A full answer to a Range request means the server ignored it: restart from byte zero.
                    offset = 0
                    total = body.contentLength().takeIf { n -> n > 0 }
                }
                if (total != null && total > UpdateLimits.MAX_APK_BYTES) { part.delete(); throw DownloadFailure(DownloadFailureKind.TOO_LARGE) }
                val remaining = (total ?: 0L) - offset
                if (total != null && freeSpace(dir) < remaining + UpdateLimits.STORAGE_MARGIN_BYTES) {
                    throw DownloadFailure(DownloadFailureKind.INSUFFICIENT_STORAGE, partial = offset > 0)
                }
                var received = offset
                onProgress(received, total)
                try {
                    FileOutputStream(part, offset > 0).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        val source = body.byteStream()
                        var reported = received
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = try { source.read(buffer) } catch (e: IOException) {
                                throw DownloadFailure(DownloadFailureKind.NETWORK, partial = received > 0, cause = e)
                            }
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            received += n
                            if (received > UpdateLimits.MAX_APK_BYTES) { out.close(); part.delete(); throw DownloadFailure(DownloadFailureKind.TOO_LARGE) }
                            if (received - reported >= PROGRESS_STEP) { reported = received; onProgress(received, total) }
                        }
                        out.flush()
                    }
                } catch (e: DownloadFailure) { throw e }
                catch (e: IOException) { throw DownloadFailure(DownloadFailureKind.STORAGE, cause = e) }
                onProgress(received, total)
                if (total != null && received < total) throw DownloadFailure(DownloadFailureKind.NETWORK, partial = true)
                if (total != null && received > total) { part.delete(); throw DownloadFailure(DownloadFailureKind.SIZE_MISMATCH) }
            }
            if (sha256(part) != release.sha256) { part.delete(); throw DownloadFailure(DownloadFailureKind.HASH_MISMATCH) }
            if (!part.renameTo(target)) { part.delete(); throw DownloadFailure(DownloadFailureKind.STORAGE) }
            target
        }
    }

    fun delete(release: ReleaseManifest.Available) { targetFile(release).delete(); partFile(release).delete() }

    /** Drops every file for versions that are installed or older, unknown files, and anything older than a week. */
    fun cleanup(installedVersionCode: Long, now: Long) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            val match = NAME.matchEntire(file.name)
            val code = match?.groupValues?.get(1)?.toLongOrNull()
            val stale = match == null || code == null || code <= installedVersionCode ||
                now - file.lastModified() > UpdateLimits.STALE_FILE_MILLIS
            if (stale) file.delete()
        }
    }

    private fun baseRequest(release: ReleaseManifest.Available) = Request.Builder().url(release.downloadUrl)
        // Identity encoding keeps Content-Length and Range offsets byte-exact for the APK.
        .header("Accept-Encoding", "identity")
        .header("Cache-Control", "no-store")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val HEX64 = Regex("[a-f0-9]{64}")
        val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
        val NAME = Regex("feilian-vpn-(\\d+)-[a-f0-9]{12}\\.apk(?:\\.part)?")
        const val PROGRESS_STEP = 128L * 1024
    }
}
