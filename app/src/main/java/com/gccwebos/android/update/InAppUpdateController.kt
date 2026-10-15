package com.gccwebos.android.update

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Device facts and side effects, injected so the whole flow runs on the JVM. */
interface UpdateEnvironment {
    fun isMetered(): Boolean
    fun canRequestInstall(): Boolean
    /** Opens this app's "install unknown apps" setting. False when no settings screen could be opened. */
    fun openInstallSettings(): Boolean
    /** Hands a verified, app-private file to the system installer. False when it could not be started. */
    fun launchInstaller(file: File): Boolean
}

enum class InAppFailure(val retryable: Boolean) {
    NETWORK(true), HTTP(true), REJECTED(false), SIZE_MISMATCH(true), STORAGE_FULL(true), TOO_LARGE(false), HASH_MISMATCH(true),
    STORAGE(true), UNREADABLE(true), WRONG_PACKAGE(false), NOT_NEWER(false), WRONG_VERSION(false), SIGNER_MISMATCH(false),
    INSTALLER_UNAVAILABLE(false),
}

sealed interface InAppUpdateState {
    data object Idle : InAppUpdateState
    data object Preparing : InAppUpdateState
    data class ConfirmMetered(val sizeBytes: Long?) : InAppUpdateState
    data class Downloading(val received: Long, val total: Long?, val resumedFrom: Long) : InAppUpdateState
    data object Verifying : InAppUpdateState
    data class NeedsInstallPermission(val file: File) : InAppUpdateState
    data class ReadyToInstall(val file: File) : InAppUpdateState
    data object InstallerLaunched : InAppUpdateState
    data class Failed(val failure: InAppFailure, val httpCode: Int? = null, val resumable: Boolean = false) : InAppUpdateState
}

/**
 * Drives one in-app update: warn on metered data, download (resumable), verify SHA-256, package, version
 * and signing certificate, then ask Android's own installer. Nothing is installed silently and nothing is
 * handed to the installer before every check passed. UI-thread confined.
 */
class InAppUpdateController(
    private val scope: CoroutineScope,
    private val downloader: ApkDownloader,
    private val inspector: ApkInspector,
    private val environment: UpdateEnvironment,
    private val expectedPackage: String = OfficialReleases.RELEASE_APPLICATION_ID,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableState = MutableStateFlow<InAppUpdateState>(InAppUpdateState.Idle)
    val state: StateFlow<InAppUpdateState> = mutableState.asStateFlow()
    private var release: ReleaseManifest.Available? = null
    private var job: Job? = null
    private var generation = 0L
    private var meteredAccepted = false

    val busy: Boolean get() = when (mutableState.value) {
        InAppUpdateState.Preparing, is InAppUpdateState.Downloading, InAppUpdateState.Verifying -> true
        else -> false
    }

    fun start(target: ReleaseManifest.Available) {
        if (busy) return
        release = target
        meteredAccepted = false
        begin(target)
    }

    /** Explicit user consent after the metered-network warning. */
    fun continueOnMetered() {
        val target = release ?: return
        if (mutableState.value !is InAppUpdateState.ConfirmMetered) return
        meteredAccepted = true
        begin(target)
    }

    fun retry() {
        val target = release ?: return
        if (busy || mutableState.value !is InAppUpdateState.Failed) return
        begin(target)
    }

    private fun begin(target: ReleaseManifest.Available) {
        val expected = ++generation
        job?.cancel()
        mutableState.value = InAppUpdateState.Preparing
        job = scope.launch {
            try {
                if (environment.isMetered() && !meteredAccepted) {
                    val size = downloader.probe(target)
                    if (generation == expected) mutableState.value = InAppUpdateState.ConfirmMetered(size)
                    return@launch
                }
                run(target, expected)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == expected) mutableState.value = InAppUpdateState.Failed(InAppFailure.NETWORK) }
        }
    }

    private suspend fun run(target: ReleaseManifest.Available, expected: Long) {
        val resumed = downloader.keptBytes(target)
        mutableState.value = InAppUpdateState.Downloading(resumed, null, resumed)
        val file = try {
            downloader.download(target) { received, total ->
                if (generation == expected) mutableState.value = InAppUpdateState.Downloading(received, total, resumed)
            }
        } catch (failure: DownloadFailure) {
            if (generation == expected) mutableState.value = failed(failure)
            return
        }
        if (generation != expected) return
        mutableState.value = InAppUpdateState.Verifying
        val check = withContext(ioDispatcher) { checkCandidate(inspector.archive(file), inspector.installed(), target, expectedPackage) }
        if (generation != expected) return
        if (check != CandidateCheck.OK) {
            downloader.delete(target)
            mutableState.value = InAppUpdateState.Failed(when (check) {
                CandidateCheck.UNREADABLE -> InAppFailure.UNREADABLE
                CandidateCheck.WRONG_PACKAGE -> InAppFailure.WRONG_PACKAGE
                CandidateCheck.NOT_NEWER -> InAppFailure.NOT_NEWER
                CandidateCheck.WRONG_VERSION -> InAppFailure.WRONG_VERSION
                else -> InAppFailure.SIGNER_MISMATCH
            })
            return
        }
        mutableState.value = if (environment.canRequestInstall()) InAppUpdateState.ReadyToInstall(file)
        else InAppUpdateState.NeedsInstallPermission(file)
    }

    private fun failed(failure: DownloadFailure) = InAppUpdateState.Failed(when (failure.kind) {
        DownloadFailureKind.NETWORK -> InAppFailure.NETWORK
        DownloadFailureKind.HTTP -> InAppFailure.HTTP
        DownloadFailureKind.REJECTED -> InAppFailure.REJECTED
        DownloadFailureKind.SIZE_MISMATCH -> InAppFailure.SIZE_MISMATCH
        DownloadFailureKind.INSUFFICIENT_STORAGE -> InAppFailure.STORAGE_FULL
        DownloadFailureKind.TOO_LARGE -> InAppFailure.TOO_LARGE
        DownloadFailureKind.HASH_MISMATCH -> InAppFailure.HASH_MISMATCH
        DownloadFailureKind.STORAGE -> InAppFailure.STORAGE
    }, failure.httpCode, failure.partial)

    /** The user came back from the unknown-apps setting (or any foreground return). */
    fun onForeground() {
        val current = mutableState.value
        if (current is InAppUpdateState.NeedsInstallPermission && environment.canRequestInstall()) {
            mutableState.value = InAppUpdateState.ReadyToInstall(current.file)
        }
    }

    fun openInstallSettings() { environment.openInstallSettings() }

    fun install() {
        val target = release ?: return
        val current = mutableState.value as? InAppUpdateState.ReadyToInstall ?: return
        if (!environment.canRequestInstall()) { mutableState.value = InAppUpdateState.NeedsInstallPermission(current.file); return }
        if (!current.file.isFile) { mutableState.value = InAppUpdateState.Failed(InAppFailure.UNREADABLE); return }
        if (environment.launchInstaller(current.file)) {
            mutableState.value = InAppUpdateState.InstallerLaunched
        } else {
            downloader.delete(target)
            mutableState.value = InAppUpdateState.Failed(InAppFailure.INSTALLER_UNAVAILABLE)
        }
    }

    /**
     * Leaves the flow. An interrupted download keeps its partial bytes so a later start resumes; every
     * finished-but-not-installed or failed file is deleted. After the installer was launched the file stays
     * until the next app start, because Android may still be reading it.
     */
    fun dismiss() {
        generation++
        job?.cancel()
        job = null
        val target = release
        when (mutableState.value) {
            InAppUpdateState.InstallerLaunched, InAppUpdateState.Idle, InAppUpdateState.Preparing,
            is InAppUpdateState.ConfirmMetered, is InAppUpdateState.Downloading -> Unit
            else -> target?.let(downloader::delete)
        }
        mutableState.value = InAppUpdateState.Idle
    }
}
