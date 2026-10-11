package com.gccwebos.android.update

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val apk = ByteArray(700_000) { (it * 31 + 7).toByte() }
    private fun dir() = File(tmp.root, "update")
    private fun downloader(server: FakeApkServer, free: Long = Long.MAX_VALUE) =
        ApkDownloader(server, dir(), { free }, Dispatchers.Unconfined)
    private fun failure(block: () -> Unit): DownloadFailure = try { block(); fail("expected a DownloadFailure"); error("unreachable") } catch (e: DownloadFailure) { e }

    @Test fun aCompleteDownloadIsVerifiedAndPublishedAtomically() = runBlocking {
        val server = FakeApkServer(apk); val release = releaseFor(apk); val progress = mutableListOf<Pair<Long, Long?>>()
        val file = downloader(server).download(release) { r, t -> progress += r to t }
        assertEquals(sha256Hex(apk), sha256Hex(file.readBytes()))
        assertEquals(downloader(server).targetFile(release), file)
        assertFalse(downloader(server).partFile(release).exists())
        assertEquals(apk.size.toLong() to apk.size.toLong(), progress.last())
        assertTrue(progress.map { it.first }.zipWithNext().all { (a, b) -> b >= a })
        val request = server.requests.single()
        assertEquals("https://gccwebos.com/android/releases/feilian-vpn-v0.5.0.apk", request.url.toString())
        assertEquals("identity", request.header("Accept-Encoding")); assertNull(request.header("Cookie")); assertNull(request.header("Authorization"))
    }

    @Test fun aHashMismatchDeletesEverythingAndNothingIsReturned() = runBlocking {
        val server = FakeApkServer(apk); val release = releaseFor(apk, sha = "0".repeat(64))
        val d = downloader(server)
        assertEquals(DownloadFailureKind.HASH_MISMATCH, failure { runBlocking { d.download(release) { _, _ -> } } }.kind)
        assertTrue(dir().listFiles().orEmpty().isEmpty())
    }

    @Test fun aDroppedConnectionKeepsThePartialFileAndTheRetryResumesWithARangeRequest() = runBlocking {
        val server = FakeApkServer(apk); val release = releaseFor(apk); val d = downloader(server)
        server.failAfterBytes = 250_000
        val first = failure { runBlocking { d.download(release) { _, _ -> } } }
        assertEquals(DownloadFailureKind.NETWORK, first.kind); assertTrue(first.partial)
        val kept = d.partFile(release).length()
        assertTrue("partial bytes kept: $kept", kept in 1 until apk.size)
        assertEquals(kept, d.keptBytes(release))
        val progress = mutableListOf<Long>()
        val file = d.download(release) { r, _ -> progress += r }
        assertEquals("bytes=$kept-", server.requests.last().header("Range"))
        assertEquals(kept, progress.first())
        assertEquals(sha256Hex(apk), sha256Hex(file.readBytes()))
        assertFalse(d.partFile(release).exists())
    }

    @Test fun aServerThatIgnoresRangeRestartsFromByteZeroWithoutCorruption() = runBlocking {
        val server = FakeApkServer(apk); val release = releaseFor(apk); val d = downloader(server)
        server.failAfterBytes = 200_000
        failure { runBlocking { d.download(release) { _, _ -> } } }
        server.ignoreRange = true
        val file = d.download(release) { _, _ -> }
        assertEquals(sha256Hex(apk), sha256Hex(file.readBytes()))
        assertEquals(apk.size.toLong(), file.length())
    }

    @Test fun aContentRangeThatDoesNotStartWhereWeAskedIsRejectedAndThePartialIsDropped() = runBlocking {
        val server = FakeApkServer(apk); val release = releaseFor(apk); val d = downloader(server)
        server.failAfterBytes = 200_000
        failure { runBlocking { d.download(release) { _, _ -> } } }
        server.badContentRange = "bytes 0-${apk.size - 1}/${apk.size}"
        assertEquals(DownloadFailureKind.REJECTED, failure { runBlocking { d.download(release) { _, _ -> } } }.kind)
        assertFalse(d.partFile(release).exists())
    }

    @Test fun anUnsatisfiableRangeRestartsCleanlyExactlyOnce() = runBlocking {
        val server = FakeApkServer(apk); val release = releaseFor(apk); val d = downloader(server)
        server.failAfterBytes = 200_000
        failure { runBlocking { d.download(release) { _, _ -> } } }
        server.rangeNotSatisfiableOnce = true
        val file = d.download(release) { _, _ -> }
        assertEquals(sha256Hex(apk), sha256Hex(file.readBytes()))
        assertEquals(3, server.gets)
        assertNull(server.requests.last().header("Range"))
    }

    @Test fun redirectsAndErrorStatusesAreRejectedWithoutWritingAnything() = runBlocking {
        val release = releaseFor(apk); val d0 = FakeApkServer(apk)
        d0.locationRedirect = "https://evil.example/a.apk"
        assertEquals(DownloadFailureKind.REJECTED, failure { runBlocking { downloader(d0).download(release) { _, _ -> } } }.kind)
        val d1 = FakeApkServer(apk); d1.statusOverride = 404
        val f = failure { runBlocking { downloader(d1).download(release) { _, _ -> } } }
        assertEquals(DownloadFailureKind.HTTP, f.kind); assertEquals(404, f.httpCode)
        assertTrue(dir().listFiles().orEmpty().none { it.length() > 0 })
    }

    @Test fun onlyTheExactSameOriginApkPathIsEverRequested() = runBlocking {
        val server = FakeApkServer(apk)
        for (path in listOf("/evil.apk", "/android/releases/../x.apk", "/android/releases/a.apk?x=1", "https://evil.example/a.apk", "/android/releases/a.zip")) {
            assertEquals(path, DownloadFailureKind.REJECTED, failure { runBlocking { downloader(server).download(releaseFor(apk, path = path)) { _, _ -> } } }.kind)
        }
        assertTrue(server.requests.isEmpty())
    }

    @Test fun aBodyLongerThanDeclaredIsASizeMismatchAndAShorterOneIsResumable() = runBlocking {
        val release = releaseFor(apk)
        val longer = FakeApkServer(apk); longer.declaredLengthOverride = 1000
        assertEquals(DownloadFailureKind.SIZE_MISMATCH, failure { runBlocking { downloader(longer).download(release) { _, _ -> } } }.kind)
        assertFalse(downloader(longer).partFile(release).exists())
        val shorter = FakeApkServer(apk); shorter.truncateBodyTo = 100_000
        val f = failure { runBlocking { downloader(shorter).download(release) { _, _ -> } } }
        assertEquals(DownloadFailureKind.NETWORK, f.kind); assertTrue(f.partial)
        assertEquals(100_000L, downloader(shorter).partFile(release).length())
    }

    @Test fun notEnoughFreeSpaceStopsBeforeAnyByteIsWritten() = runBlocking {
        val server = FakeApkServer(apk); val release = releaseFor(apk)
        assertEquals(DownloadFailureKind.INSUFFICIENT_STORAGE, failure { runBlocking { downloader(server, free = 1000).download(release) { _, _ -> } } }.kind)
        assertTrue(dir().listFiles().orEmpty().none { it.length() > 0 })
    }

    @Test fun anAnnouncedSizeAboveTheCeilingIsRefused() = runBlocking {
        val server = FakeApkServer(apk); server.declaredLengthOverride = UpdateLimits.MAX_APK_BYTES + 1
        assertEquals(DownloadFailureKind.TOO_LARGE, failure { runBlocking { downloader(server).download(releaseFor(apk)) { _, _ -> } } }.kind)
    }

    @Test fun anAlreadyVerifiedFileIsReusedWithoutAnyRequest() = runBlocking {
        val server = FakeApkServer(apk); val release = releaseFor(apk); val d = downloader(server)
        d.download(release) { _, _ -> }
        val before = server.requests.size
        val again = d.download(release) { _, _ -> }
        assertEquals(before, server.requests.size); assertTrue(again.isFile)
    }

    @Test fun aCorruptedLeftoverTargetIsNotTrustedAndIsReplaced() = runBlocking {
        val server = FakeApkServer(apk); val release = releaseFor(apk); val d = downloader(server)
        dir().mkdirs(); d.targetFile(release).writeBytes(byteArrayOf(1, 2, 3))
        val file = d.download(release) { _, _ -> }
        assertEquals(sha256Hex(apk), sha256Hex(file.readBytes())); assertEquals(1, server.gets)
    }

    @Test fun cancellingMidDownloadStopsPromptlyAndKeepsTheResumablePart() = runBlocking {
        val server = FakeApkServer(apk); val release = releaseFor(apk); val d = ApkDownloader(server, dir(), { Long.MAX_VALUE }, Dispatchers.IO)
        server.blockAfterBytes = 150_000
        val job = launch(Dispatchers.Default) { try { d.download(release) { _, _ -> } } catch (_: Throwable) { } }
        assertTrue(server.blocked.await(10, TimeUnit.SECONDS))
        withTimeout(10_000) { job.cancelAndJoin() }
        val part = d.partFile(release)
        assertTrue("kept ${part.length()}", part.length() in 1..apk.size.toLong() - 1)
        assertFalse(d.targetFile(release).exists())
    }

    @Test fun theProbeAsksForHeadersOnlyAndReportsTheRealSize() = runBlocking {
        val server = FakeApkServer(apk); val d = downloader(server)
        assertEquals(apk.size.toLong(), d.probe(releaseFor(apk)))
        assertEquals("HEAD", server.requests.single().method)
        server.statusOverride = 500
        assertNull(d.probe(releaseFor(apk)))
    }

    @Test fun cleanupRemovesInstalledOldUnknownAndAbandonedFilesButKeepsAFreshNewerOne() {
        val d = downloader(FakeApkServer(apk)); dir().mkdirs()
        val keep = File(dir(), "feilian-vpn-11-aaaaaaaaaaaa.apk").apply { writeText("x") }
        val installed = File(dir(), "feilian-vpn-9-bbbbbbbbbbbb.apk").apply { writeText("x") }
        val older = File(dir(), "feilian-vpn-8-cccccccccccc.apk.part").apply { writeText("x") }
        val unknown = File(dir(), "stray.bin").apply { writeText("x") }
        val abandoned = File(dir(), "feilian-vpn-12-dddddddddddd.apk.part").apply { writeText("x"); setLastModified(1_000) }
        d.cleanup(9, now = 1_000 + UpdateLimits.STALE_FILE_MILLIS + 1)
        // `keep` was written "now" by the filesystem, far newer than the fake clock's 1_000 + week: age is negative.
        assertTrue(keep.exists()); assertFalse(installed.exists()); assertFalse(older.exists()); assertFalse(unknown.exists()); assertFalse(abandoned.exists())
    }
}
