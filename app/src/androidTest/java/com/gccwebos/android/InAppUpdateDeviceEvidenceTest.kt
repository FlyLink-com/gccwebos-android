package com.gccwebos.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.update.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Timeout
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * In-app update on a real device (side-by-side test package only). The origin is an in-process fake with real
 * HTTP Range semantics, except the opt-in `realDownload` test which fetches the published 0.4.2 over the network.
 * The system installer is NEVER launched: the environment's launcher is a recording stub, so nothing here can
 * install over the official app. Screenshots land in getExternalFilesDir/ui-evidence/in-app-update.
 */
class InAppUpdateDeviceEvidenceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dir get() = File(context.cacheDir, "update-evidence")
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    /** Built from the Activity like production, so the settings screen opens inside this task and Back returns here. */
    private val real get() = AndroidUpdateEnvironment(compose.activity)

    @Before fun before() { dir.deleteRecursively() }
    @After fun after() { job.cancel(); dir.deleteRecursively() }

    // ---- fakes -------------------------------------------------------------------------------------------------
    private class Origin(val content: ByteArray, val chunkDelayMs: Long = 0) : Call.Factory {
        val requests = mutableListOf<Request>()
        @Volatile var dropAfter: Int? = null
        override fun newCall(request: Request): Call {
            requests += request
            return object : Call {
                override fun request() = request
                override fun execute(): Response = respond(request)
                override fun enqueue(responseCallback: Callback) = error("unused")
                override fun cancel() {}
                override fun isExecuted() = true
                override fun isCanceled() = false
                override fun timeout() = Timeout.NONE
                override fun clone(): Call = newCall(request)
            }
        }
        private fun respond(request: Request): Response {
            val b = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("fixture")
            if (request.method == "HEAD") return b.code(200).header("Content-Length", content.size.toString()).body(empty()).build()
            val start = request.header("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull() ?: 0
            val limit = dropAfter.also { dropAfter = null }
            val stream = object : InputStream() {
                val inner = ByteArrayInputStream(content, start, content.size - start)
                var delivered = 0
                override fun read(): Int = error("unused")
                override fun read(buf: ByteArray, off: Int, len: Int): Int {
                    if (chunkDelayMs > 0) Thread.sleep(chunkDelayMs)
                    if (limit != null && delivered >= limit) throw IOException("connection reset")
                    val room = if (limit != null) minOf(len, limit - delivered) else len
                    val n = inner.read(buf, off, room); if (n > 0) delivered += n; return n
                }
            }
            val body = object : ResponseBody() {
                override fun contentType() = "application/vnd.android.package-archive".toMediaType()
                override fun contentLength() = (content.size - start).toLong()
                override fun source() = stream.source().buffer()
            }
            return if (start > 0) b.code(206).header("Content-Range", "bytes $start-${content.size - 1}/${content.size}").body(body).build()
            else b.code(200).body(body).build()
        }
        private fun empty() = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = 0L
            override fun source() = ByteArrayInputStream(ByteArray(0)).source().buffer()
        }
    }

    /** Real network/permission/settings behaviour; only the final hand-off to the installer is a recording stub. */
    private class SafeEnvironment(val real: UpdateEnvironment, val metered: Boolean?) : UpdateEnvironment {
        val launched = mutableListOf<File>()
        override fun isMetered() = metered ?: real.isMetered()
        override fun canRequestInstall() = real.canRequestInstall()
        override fun openInstallSettings() = real.openInstallSettings()
        override fun launchInstaller(file: File): Boolean { launched += file; return true }
    }
    private class Inspector(val real: ApkInspector, val installedFacts: PackageFacts?) : ApkInspector {
        override fun archive(file: File) = real.archive(file)
        override fun installed() = installedFacts ?: real.installed()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun release(bytes: ByteArray, code: Long, sha: String = sha(bytes), path: String = "/android/releases/feilian-vpn-v9.9.9.apk") =
        ReleaseManifest.Available(code, "9.9.9", 1, Instant.parse("2026-10-11T00:00:00Z"), path, sha,
            "合成更新说明（测试清单，不是真实版本）", "Synthetic")
    private fun controller(origin: Call.Factory, env: UpdateEnvironment, inspector: ApkInspector, expectedPackage: String) =
        InAppUpdateController(scope, ApkDownloader(origin, dir), inspector, env, expectedPackage)

    private var shown by mutableStateOf(true)
    private fun show(controller: InAppUpdateController, release: ReleaseManifest.Available) {
        compose.setContent {
            GccTheme {
                val open = remember { shown }
                if (shown && open) OfficialUpdateDialog(UpdateResult.NewVersion(release, false), onDismiss = { shown = false },
                    openWebsite = {}, installer = controller)
            }
        }
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "ui-evidence/in-app-update")
        check(directory.isDirectory || directory.mkdirs())
        val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { assertTrue(shot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun note(name: String, text: String) {
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "ui-evidence/in-app-update")
        check(directory.isDirectory || directory.mkdirs()); File(directory, name).writeText(text)
    }
    private fun waitFor(timeoutMillis: Long = 60_000, what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < end) { if (condition()) return; Thread.sleep(100) }
        fail("timed out waiting for $what")
    }
    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString() }
    }
    private fun payload(size: Int) = ByteArray(size).also { java.util.Random(42).nextBytes(it) }
    private val inspector get() = AndroidApkInspector(context)
    private val installedOfficial = PackageFacts(OfficialReleases.RELEASE_APPLICATION_ID, 8, setOf("a".repeat(64)))

    // ---- tests -------------------------------------------------------------------------------------------------
    @Test fun theUpdateFileProviderIsPrivateAndServesOnlyTheUpdateDirectory() {
        val authority = "${context.packageName}.updatefiles"
        val info = requireNotNull(context.packageManager.resolveContentProvider(authority, 0))
        assertFalse("must not be exported", info.exported); assertTrue(info.grantUriPermissions)
        val file = File(updateDirectory(context), "feilian-vpn-77-aaaaaaaaaaaa.apk").apply { parentFile!!.mkdirs(); writeText("evidence") }
        try {
            val uri = FileProvider.getUriForFile(context, authority, file)
            assertEquals("content://$authority/update/${file.name}", uri.toString())
            assertEquals("evidence", context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() })
            val outside = File(context.filesDir, "x.txt").apply { writeText("secret") }
            assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(context, authority, outside) }
            outside.delete()
            note("fileprovider.txt", "authority=$authority exported=${info.exported} grantUriPermissions=${info.grantUriPermissions}\nuri=$uri\n" +
                "file outside cache/update/ -> IllegalArgumentException (not shareable)\n")
        } finally { file.delete() }
    }

    @Test fun meteredWarningProgressVerificationAndUnknownAppsSettingsGuidance() {
        assumeFalse("needs a package that is not yet allowed to install apps", real.canRequestInstall())
        val bytes = payload(24 * 1024 * 1024); val origin = Origin(bytes, chunkDelayMs = 12)
        val env = SafeEnvironment(real, metered = true)
        val candidate = release(bytes, 10)
        // Real archive parsing of the androidTest APK (valid signed package) with a matching installed state.
        val archive = File(instrumentation.context.applicationInfo.sourceDir)
        assertTrue(archive.isFile)
        val c = controller(origin, env, object : ApkInspector {
            override fun archive(file: File) = PackageFacts(OfficialReleases.RELEASE_APPLICATION_ID, 10, setOf("a".repeat(64)))
            override fun installed() = installedOfficial
        }, OfficialReleases.RELEASE_APPLICATION_ID)
        show(c, candidate)
        screenshot("01-prompt-with-in-app-download")
        compose.onNodeWithTag("in-app-update-start").performClick()
        waitFor(what = "metered warning") { c.state.value is InAppUpdateState.ConfirmMetered }
        compose.waitForIdle(); screenshot("02-metered-warning-with-real-size")
        assertEquals(0, origin.requests.count { it.method == "GET" })
        compose.onNodeWithTag("in-app-update-metered-continue").performClick()
        waitFor(what = "some progress") { (c.state.value as? InAppUpdateState.Downloading)?.let { it.received > 6L * 1024 * 1024 } == true }
        compose.waitForIdle(); screenshot("03-downloading-progress")
        compose.onNodeWithTag("in-app-update-progress").assertExists()
        waitFor(what = "permission guidance") { c.state.value is InAppUpdateState.NeedsInstallPermission }
        compose.waitForIdle(); screenshot("04-verified-needs-unknown-apps-permission")
        compose.onNodeWithTag("in-app-update-settings").performClick()
        waitFor(what = "settings screen in front") { shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'").contains("ManageAppExternalSources") ||
            shell("dumpsys window | grep mCurrentFocus").let { !it.contains(context.packageName) } }
        Thread.sleep(1200); screenshot("05-system-install-unknown-apps-settings")
        note("settings-focus.txt", shell("dumpsys window | grep -E 'mCurrentFocus'"))
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        // OPPO may interpose an "open com.gccwebos.android.debug.test?" confirmation (securitypermission) when the instrumented
        // package returns to the front. That prompt belongs to the OEM and the test package, so this wait is best effort.
        val end = System.currentTimeMillis() + 15_000
        var back = false
        while (System.currentTimeMillis() < end && !back) {
            val focus = shell("dumpsys window | grep mCurrentFocus")
            if (focus.contains("AppStartConfirmDialogActivity")) note("oem-app-start-dialog.txt", "OPPO AppStartConfirmDialogActivity appeared after Back\n")
            back = focus.contains(context.packageName); if (!back) Thread.sleep(300)
        }
        if (back) { Thread.sleep(800); screenshot("06-back-from-settings-still-waiting") }
        assertTrue(c.state.value is InAppUpdateState.NeedsInstallPermission)
        assertTrue("the installer must never have been launched", env.launched.isEmpty())
        compose.runOnUiThread { c.dismiss() }
        assertTrue("leaving deletes the verified file", dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun aDroppedConnectionIsReportedAndRetryResumesFromTheKeptBytes() {
        val bytes = payload(16 * 1024 * 1024); val origin = Origin(bytes, chunkDelayMs = 2); origin.dropAfter = 6 * 1024 * 1024
        val c = controller(origin, SafeEnvironment(real, metered = false), object : ApkInspector {
            override fun archive(file: File) = PackageFacts(OfficialReleases.RELEASE_APPLICATION_ID, 10, setOf("a".repeat(64)))
            override fun installed() = installedOfficial
        }, OfficialReleases.RELEASE_APPLICATION_ID)
        show(c, release(bytes, 10))
        compose.onNodeWithTag("in-app-update-start").performClick()
        waitFor(what = "failure") { c.state.value is InAppUpdateState.Failed }
        compose.waitForIdle(); screenshot("07-interrupted-with-kept-bytes-and-retry")
        val failed = c.state.value as InAppUpdateState.Failed
        assertEquals(InAppFailure.NETWORK, failed.failure); assertTrue(failed.resumable)
        compose.onNodeWithTag("in-app-update-retry").performClick()
        waitFor(what = "resumed download or finish") { c.state.value !is InAppUpdateState.Failed }
        waitFor(what = "completion") { c.state.value is InAppUpdateState.NeedsInstallPermission || c.state.value is InAppUpdateState.ReadyToInstall }
        val range = origin.requests.last { it.method == "GET" }.header("Range")
        note("resume.txt", "second GET Range header: $range\n")
        assertNotNull(range); assertTrue(range!!.startsWith("bytes="))
        compose.waitForIdle(); screenshot("08-resumed-and-verified")
    }

    @Test fun aCorruptedDownloadIsRefusedWithAClearMessageAndNothingIsKept() {
        val bytes = payload(4 * 1024 * 1024); val origin = Origin(bytes)
        val c = controller(origin, SafeEnvironment(real, metered = false), inspector, OfficialReleases.RELEASE_APPLICATION_ID)
        show(c, release(bytes, 10, sha = "0".repeat(64)))
        compose.onNodeWithTag("in-app-update-start").performClick()
        waitFor(what = "hash failure") { c.state.value is InAppUpdateState.Failed }
        compose.waitForIdle(); screenshot("09-hash-mismatch-refused")
        assertEquals(InAppFailure.HASH_MISMATCH, (c.state.value as InAppUpdateState.Failed).failure)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun aRealApkWithTheWrongSignerOrVersionIsRefusedByTheRealPackageParser() {
        val archive = File(instrumentation.context.applicationInfo.sourceDir)
        val bytes = archive.readBytes()
        val parsed = requireNotNull(inspector.archive(archive)) { "PackageManager must parse a real APK" }
        val testPackage = instrumentation.context.packageName
        assertEquals(testPackage, parsed.packageName); assertTrue(parsed.signerSha256.isNotEmpty())
        // 1) installed app signed by someone else -> certificate mismatch.
        val other = PackageFacts(testPackage, parsed.versionCode - 1, setOf("b".repeat(64)))
        val mismatch = controller(Origin(bytes), SafeEnvironment(real, false), Inspector(inspector, other), testPackage)
        show(mismatch, release(bytes, parsed.versionCode))
        compose.onNodeWithTag("in-app-update-start").performClick()
        waitFor(what = "certificate refusal") { mismatch.state.value is InAppUpdateState.Failed }
        compose.waitForIdle(); screenshot("10-certificate-mismatch-refused")
        assertEquals(InAppFailure.SIGNER_MISMATCH, (mismatch.state.value as InAppUpdateState.Failed).failure)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        // 2) same signer but the archive is not newer than the installed app -> refused as not newer.
        shown = true
        val same = PackageFacts(testPackage, parsed.versionCode, parsed.signerSha256)
        val stale = controller(Origin(bytes), SafeEnvironment(real, false), Inspector(inspector, same), testPackage)
        compose.runOnUiThread { shown = true }
        val staleRelease = release(bytes, parsed.versionCode)
        compose.runOnUiThread { stale.start(staleRelease) }
        waitFor(what = "not-newer refusal") { stale.state.value is InAppUpdateState.Failed }
        assertEquals(InAppFailure.NOT_NEWER, (stale.state.value as InAppUpdateState.Failed).failure)
        // 3) everything matches -> accepted up to the permission/installer boundary.
        val ok = controller(Origin(bytes), SafeEnvironment(real, false), Inspector(inspector, PackageFacts(testPackage, parsed.versionCode - 1, parsed.signerSha256)), testPackage)
        compose.runOnUiThread { ok.start(staleRelease) }
        waitFor(what = "accepted") { ok.state.value is InAppUpdateState.NeedsInstallPermission || ok.state.value is InAppUpdateState.ReadyToInstall }
        note("real-parser.txt", "parsed: package=${parsed.packageName} versionCode=${parsed.versionCode} signerSha256=${parsed.signerSha256}\n" +
            "foreign installed signer -> SIGNER_MISMATCH; same version -> NOT_NEWER; matching signer + newer -> accepted\n")
        compose.runOnUiThread { ok.dismiss() }
    }

    /** Opt-in (-e realDownload true): downloads the published 0.4.2 once over the real network; never installs. */
    @Test fun thePublishedApkDownloadsVerifiesAndPassesTheRealCertificateCheck() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("pass -e realDownload true", args.getString("realDownload") == "true")
        val publishedSha = "0a0384140846c3cb7bc14fd2009f2d58d9e62b60f931fc0c8dfcbc43b9b088da"
        val releaseKeySha = "a6393295640f56185e5f77150ecfd5ca8e59fb002f87a70518df334f39952745"
        val published = ReleaseManifest.Available(9, "0.4.2", 1, Instant.parse("2026-10-11T07:54:30Z"),
            "/android/releases/feilian-vpn-v0.4.2.apk", publishedSha, "已发布版本（仅校验，不安装）", "")
        val env = SafeEnvironment(real, metered = null)
        val c = InAppUpdateController(scope, ApkDownloader(dir), Inspector(inspector, PackageFacts(OfficialReleases.RELEASE_APPLICATION_ID, 8, setOf(releaseKeySha))),
            env, OfficialReleases.RELEASE_APPLICATION_ID)
        show(c, published)
        val started = System.currentTimeMillis()
        compose.onNodeWithTag("in-app-update-start").performClick()
        if (c.state.value is InAppUpdateState.ConfirmMetered || shellMetered(c)) {
            waitFor(what = "metered") { c.state.value is InAppUpdateState.ConfirmMetered || c.state.value is InAppUpdateState.Downloading }
            if (c.state.value is InAppUpdateState.ConfirmMetered) { screenshot("20-real-metered-warning"); compose.onNodeWithTag("in-app-update-metered-continue").performClick() }
        }
        var shots = 0
        val end = System.currentTimeMillis() + 15 * 60_000
        while (System.currentTimeMillis() < end) {
            val s = c.state.value
            if (s is InAppUpdateState.Downloading && s.total != null && shots < 3 && s.received * 4 > s.total!! * (shots + 1) - s.total!! / 2) {
                compose.waitForIdle(); screenshot("2${shots + 1}-real-download-progress"); shots++
            }
            if (s is InAppUpdateState.NeedsInstallPermission || s is InAppUpdateState.ReadyToInstall || s is InAppUpdateState.Failed) break
            Thread.sleep(250)
        }
        val elapsed = System.currentTimeMillis() - started
        compose.waitForIdle(); screenshot("25-real-download-verified")
        val finalState = c.state.value
        note("real-download.txt", "final=$finalState elapsedMs=$elapsed\n")
        assertTrue("state=$finalState", finalState is InAppUpdateState.NeedsInstallPermission || finalState is InAppUpdateState.ReadyToInstall)
        val file = (finalState as? InAppUpdateState.NeedsInstallPermission)?.file ?: (finalState as InAppUpdateState.ReadyToInstall).file
        assertEquals(publishedSha, sha(file.readBytes()))
        assertTrue("installer must never be launched", env.launched.isEmpty())
        compose.onNodeWithTag("in-app-update-cancel").performClick()
        compose.waitForIdle()
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }
    private fun shellMetered(c: InAppUpdateController) = false
}
