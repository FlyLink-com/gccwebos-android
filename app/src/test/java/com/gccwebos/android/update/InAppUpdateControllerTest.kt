package com.gccwebos.android.update

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InAppUpdateControllerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val apk = ByteArray(400_000) { (it * 17 + 3).toByte() }
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)
    @After fun stop() { job.cancel() }

    private val official = OfficialReleases.RELEASE_APPLICATION_ID
    private val signer = "a".repeat(64)
    private val installed = PackageFacts(official, 9, setOf(signer))

    private class Env(var metered: Boolean = false, var canInstall: Boolean = true, var launchOk: Boolean = true) : UpdateEnvironment {
        val launched = mutableListOf<File>(); var settingsOpened = 0
        override fun isMetered() = metered
        override fun canRequestInstall() = canInstall
        override fun openInstallSettings(): Boolean { settingsOpened++; return true }
        override fun launchInstaller(file: File): Boolean { launched += file; return launchOk }
    }
    private class Inspector(var candidate: PackageFacts?, val installed: PackageFacts) : ApkInspector {
        val inspected = mutableListOf<File>()
        override fun archive(file: File): PackageFacts? { inspected += file; return candidate }
        override fun installed() = installed
    }

    private fun build(server: FakeApkServer, env: Env, inspector: Inspector, expected: String = official): Pair<InAppUpdateController, ApkDownloader> {
        val downloader = ApkDownloader(server, File(tmp.root, "update"), { Long.MAX_VALUE }, Dispatchers.Unconfined)
        return InAppUpdateController(scope, downloader, inspector, env, expected, Dispatchers.Unconfined) to downloader
    }
    private fun good() = PackageFacts(official, 10, setOf(signer))
    private fun state(c: InAppUpdateController) = c.state.value

    @Test fun theHappyPathVerifiesEverythingThenHandsTheFileToTheSystemInstaller() {
        val server = FakeApkServer(apk); val env = Env(); val inspector = Inspector(good(), installed)
        val (c, d) = build(server, env, inspector); val release = releaseFor(apk)
        c.start(release)
        val ready = state(c) as InAppUpdateState.ReadyToInstall
        assertEquals(d.targetFile(release), ready.file); assertEquals(listOf(ready.file), inspector.inspected)
        assertTrue("nothing is installed before the user taps install", env.launched.isEmpty())
        c.install()
        assertEquals(InAppUpdateState.InstallerLaunched, state(c)); assertEquals(listOf(ready.file), env.launched)
        c.dismiss(); assertTrue("the installer may still be reading the file", ready.file.exists())
    }

    @Test fun aMeteredNetworkAsksFirstShowsTheRealSizeAndDownloadsNothingUntilConfirmed() {
        val server = FakeApkServer(apk); val env = Env(metered = true)
        val (c, _) = build(server, env, Inspector(good(), installed))
        c.start(releaseFor(apk))
        assertEquals(InAppUpdateState.ConfirmMetered(apk.size.toLong()), state(c))
        assertEquals(0, server.gets)
        c.continueOnMetered()
        assertTrue(state(c) is InAppUpdateState.ReadyToInstall); assertEquals(1, server.gets)
    }

    @Test fun decliningTheMeteredWarningLeavesNothingBehind() {
        val server = FakeApkServer(apk); val (c, d) = build(server, Env(metered = true), Inspector(good(), installed))
        c.start(releaseFor(apk)); c.dismiss()
        assertEquals(InAppUpdateState.Idle, state(c)); assertEquals(0, server.gets); assertEquals(0L, d.keptBytes(releaseFor(apk)))
    }

    @Test fun withoutTheUnknownAppsPermissionTheUserIsGuidedToSettingsAndResumesOnReturn() {
        val server = FakeApkServer(apk); val env = Env(canInstall = false)
        val (c, _) = build(server, env, Inspector(good(), installed))
        c.start(releaseFor(apk))
        val waiting = state(c) as InAppUpdateState.NeedsInstallPermission
        c.install(); assertTrue("install is impossible before the permission", env.launched.isEmpty())
        c.openInstallSettings(); assertEquals(1, env.settingsOpened)
        c.onForeground(); assertTrue("still not granted", state(c) is InAppUpdateState.NeedsInstallPermission)
        env.canInstall = true; c.onForeground()
        assertEquals(InAppUpdateState.ReadyToInstall(waiting.file), state(c))
        c.install(); assertEquals(listOf(waiting.file), env.launched)
    }

    @Test fun aPermissionRevokedAfterVerificationIsCaughtAtInstallTime() {
        val env = Env(); val (c, _) = build(FakeApkServer(apk), env, Inspector(good(), installed))
        c.start(releaseFor(apk)); env.canInstall = false; c.install()
        assertTrue(state(c) is InAppUpdateState.NeedsInstallPermission); assertTrue(env.launched.isEmpty())
    }

    @Test fun aDifferentSigningCertificateIsRefusedDeletedAndNeverInstalled() {
        val env = Env(); val inspector = Inspector(PackageFacts(official, 10, setOf("b".repeat(64))), installed)
        val (c, d) = build(FakeApkServer(apk), env, inspector); val release = releaseFor(apk)
        c.start(release)
        assertEquals(InAppUpdateState.Failed(InAppFailure.SIGNER_MISMATCH), state(c))
        assertFalse(d.targetFile(release).exists()); assertTrue(env.launched.isEmpty())
    }

    @Test fun everyOtherCandidateProblemIsRefusedAndDeleted() {
        val cases = mapOf(
            null to InAppFailure.UNREADABLE,
            PackageFacts("com.evil", 10, setOf(signer)) to InAppFailure.WRONG_PACKAGE,
            PackageFacts(official, 9, setOf(signer)) to InAppFailure.NOT_NEWER,
            PackageFacts(official, 8, setOf(signer)) to InAppFailure.NOT_NEWER,
            PackageFacts(official, 11, setOf(signer)) to InAppFailure.WRONG_VERSION,
            PackageFacts(official, 10, emptySet()) to InAppFailure.SIGNER_MISMATCH,
            PackageFacts(official, 10, setOf(signer, "c".repeat(64))) to InAppFailure.SIGNER_MISMATCH,
        )
        for ((facts, expected) in cases) {
            val env = Env(); val (c, d) = build(FakeApkServer(apk), env, Inspector(facts, installed)); val release = releaseFor(apk)
            c.start(release)
            assertEquals(expected, (state(c) as InAppUpdateState.Failed).failure)
            assertFalse(d.targetFile(release).exists()); assertTrue(env.launched.isEmpty())
            c.dismiss()
        }
    }

    private class Switch(val bad: FakeApkServer, val good: FakeApkServer) : okhttp3.Call.Factory {
        var useGood = false
        override fun newCall(request: okhttp3.Request) = (if (useGood) good else bad).newCall(request)
    }

    @Test fun aHashMismatchIsRetryableAndTheRetryDownloadsAFreshCopy() {
        val env = Env(); val release = releaseFor(apk)
        val corrupted = apk.copyOf().also { it[1000] = (it[1000] + 1).toByte() }
        val factory = Switch(FakeApkServer(corrupted), FakeApkServer(apk))
        val downloader = ApkDownloader(factory, File(tmp.root, "update"), { Long.MAX_VALUE }, Dispatchers.Unconfined)
        val c = InAppUpdateController(scope, downloader, Inspector(good(), installed), env, official, Dispatchers.Unconfined)
        c.start(release)
        val failed = state(c) as InAppUpdateState.Failed
        assertEquals(InAppFailure.HASH_MISMATCH, failed.failure); assertTrue(failed.failure.retryable)
        assertTrue(File(tmp.root, "update").listFiles().orEmpty().isEmpty())
        factory.useGood = true
        c.retry()
        assertTrue(state(c) is InAppUpdateState.ReadyToInstall)
    }

    @Test fun anInterruptedDownloadResumesFromTheKeptBytesOnRetry() {
        val server = FakeApkServer(apk); val env = Env(); val (c, d) = build(server, env, Inspector(good(), installed)); val release = releaseFor(apk)
        server.failAfterBytes = 150_000
        c.start(release)
        val failed = state(c) as InAppUpdateState.Failed
        assertEquals(InAppFailure.NETWORK, failed.failure); assertTrue(failed.resumable)
        val kept = d.partFile(release).length(); assertTrue(kept > 0)
        c.retry()
        assertTrue(state(c) is InAppUpdateState.ReadyToInstall)
        assertEquals("bytes=$kept-", server.requests.last().header("Range"))
    }

    @Test fun leavingAFinishedButUninstalledFlowDeletesTheVerifiedFile() {
        val env = Env(canInstall = false); val (c, d) = build(FakeApkServer(apk), env, Inspector(good(), installed)); val release = releaseFor(apk)
        c.start(release); assertTrue(d.targetFile(release).exists())
        c.dismiss()
        assertEquals(InAppUpdateState.Idle, state(c)); assertFalse(d.targetFile(release).exists())
    }

    @Test fun anInstallerThatCannotStartFailsClearlyAndDeletesTheFile() {
        val env = Env(launchOk = false); val (c, d) = build(FakeApkServer(apk), env, Inspector(good(), installed)); val release = releaseFor(apk)
        c.start(release); c.install()
        assertEquals(InAppUpdateState.Failed(InAppFailure.INSTALLER_UNAVAILABLE), state(c)); assertFalse(d.targetFile(release).exists())
    }

    @Test fun aServerErrorOffersARetryAndKeepsNoFiles() {
        val server = FakeApkServer(apk); server.statusOverride = 503
        val (c, d) = build(server, Env(), Inspector(good(), installed))
        c.start(releaseFor(apk))
        val failed = state(c) as InAppUpdateState.Failed
        assertEquals(InAppFailure.HTTP, failed.failure); assertEquals(503, failed.httpCode)
        assertEquals(0L, d.keptBytes(releaseFor(apk)))
    }

    @Test fun aDebugOrCandidatePackageNeverPassesAsTheOfficialApp() {
        val facts = PackageFacts("$official.debug", 10, setOf(signer))
        assertEquals(CandidateCheck.WRONG_PACKAGE, checkCandidate(facts, facts.copy(versionCode = 9), releaseFor(apk)))
    }

    @Test fun everyFailureHasPlainChineseCopyAndPlainCopyForProgress() {
        for (failure in InAppFailure.values()) {
            val text = failureText(failure, 500, true)
            assertTrue(failure.name, text.isNotBlank() && text.any { it in '\u4e00'..'\u9fff' })
        }
        assertTrue(failureText(InAppFailure.HTTP, 503).contains("503"))
        assertEquals("正在下载\n1.0 MB / 2.0 MB（50%）", downloadingText(InAppUpdateState.Downloading(1024 * 1024, 2L * 1024 * 1024, 0)))
        assertTrue(downloadingText(InAppUpdateState.Downloading(2L * 1024 * 1024, 4L * 1024 * 1024, 1024 * 1024)).startsWith("继续下载（已保留 1.0 MB）"))
        assertEquals("正在下载\n0.5 MB", downloadingText(InAppUpdateState.Downloading(512 * 1024, null, 0)))
    }
}
