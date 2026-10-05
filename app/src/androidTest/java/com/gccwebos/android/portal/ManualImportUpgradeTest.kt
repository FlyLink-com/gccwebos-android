package com.gccwebos.android.portal

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.gccwebos.android.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in profile-preserving v3 library acceptance; not a downgrade preparation fixture. */
class ManualImportUpgradeTest {
    @Test fun verifyUpgradePreservesProfileAndConfigurationLibrary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Owned upgrade acceptance only", args.getString("manualUpgradeStage") == "candidate")
        assertTrue("Uniquely owned package required before store access", BuildConfig.DEBUG &&
            context.packageName == OWNED_PACKAGE && BuildConfig.APPLICATION_ID == OWNED_PACKAGE)
        val expected = args.getString("expectedV1Sha256")
        assertTrue("Expected baseline ciphertext identity is required", expected?.matches(Regex("[a-f0-9]{64}")) == true)
        try { runBlocking { verify(context, requireNotNull(expected)) } }
        catch (_: Exception) { throw AssertionError("Owned synthetic upgrade fixture failed") }
    }

    private suspend fun verify(context: Context, expected: String) {
        val file = File(context.noBackupFilesDir, "portal-profile.bin")
        fun unchanged() { assertTrue("Original V1 ciphertext changed", sha(file) == expected); assertNoOwner(context) }
        unchanged()
        val sidecar = EncryptedManualSourceStore(context)
        assertNull("First upgrade must not have a selector", sidecar.read())
        var fakeDownloads = 0
        val manager = LocalSubscriptionManager(EncryptedDeliveryStore(context), EncryptedLocalProfileStore(context),
            object : SubscriptionSource {
                override suspend fun download(url: Secret) = read(url).bytes
                override suspend fun read(url: Secret): SubscriptionRead {
                    assertTrue("Only synthetic URL reaches the injected downloader", url.value == SYNTHETIC_URL.value)
                    fakeDownloads++
                    return SubscriptionRead(yaml("Synthetic imported remote", "192.0.2.12"), UNTIL)
                }
            }, OriginPolicy("https://portal.gccwebos.com", setOf("https://sub.gccwebos.com")),
            { Instant.parse("2026-10-04T00:00:00Z") }, LocalSubscriptionAuthority(), manualSources = sidecar)
        assertTrue("First upgrade must atomically choose the legacy source", manager.sourceKind() == ManualSourceKind.LegacyRemote)
        unchanged()
        val raw = yaml("Synthetic imported local", "192.0.2.11")
        try {
            val imported = manager.importManual(raw) { action -> assertNoOwner(context); action() }
            assertTrue("Local source must have typed local validity", imported.sourceKind == ManualSourceKind.ImportedLocal &&
                imported.validity == LocalSourceValidity.LocalNoServerExpiry && imported.validUntil == null)
        } finally { raw.fill(0) }
        unchanged(); assertTrue("Local import must not download", fakeDownloads == 0)
        val localId = requireNotNull(sidecar.readLibrary()).use { library ->
            assertTrue("First import must create exactly one local entry", library.entries.size == 1)
            val entry = library.entries.single()
            entry.copySource().use { assertTrue("First entry must be local", it.kind == ManualSourceKind.ImportedLocal) }
            assertTrue("First import must select its entry", library.selector == ConfigurationSelector.Entry &&
                library.selectedEntryId == entry.id)
            entry.id
        }
        val reopened = LocalSubscriptionManager(EncryptedDeliveryStore(context), EncryptedLocalProfileStore(context),
            SubscriptionSource { throw AssertionError("Local restore must not download") },
            OriginPolicy("https://portal.gccwebos.com", setOf("https://sub.gccwebos.com")),
            { Instant.parse("2026-10-04T00:00:00Z") }, LocalSubscriptionAuthority(), manualSources = EncryptedManualSourceStore(context))
        assertTrue("Recreated manager must restore only local source", reopened.restore().sourceKind == ManualSourceKind.ImportedLocal)
        unchanged()
        val link = SYNTHETIC_URL.value.toByteArray()
        try {
            val imported = manager.importManual(link) { action -> assertNoOwner(context); action() }
            assertTrue("Explicit synthetic link must choose its remote envelope", imported.sourceKind == ManualSourceKind.ImportedRemote && imported.validUntil == UNTIL)
        } finally { link.fill(0) }
        unchanged(); assertTrue("Only one injected read is expected", fakeDownloads == 1)
        requireNotNull(sidecar.readLibrary()).use { library ->
            assertTrue("Second import must append instead of replacing local configuration", library.entries.size == 2)
            val localEntry = library.entries.single { it.id == localId }
            localEntry.copySource().use { assertTrue("Original local entry must remain local", it.kind == ManualSourceKind.ImportedLocal) }
            val remoteEntry = library.entries.single { it.id != localId }
            remoteEntry.copySource().use { assertTrue("New entry must be the synthetic remote", it.kind == ManualSourceKind.ImportedRemote) }
            assertTrue("Second import must select only the new remote entry", library.selector == ConfigurationSelector.Entry &&
                library.selectedEntryId == remoteEntry.id)
        }
        manager.removeManualSource { action -> assertNoOwner(context); action() }
        sidecar.read()!!.use {
            assertTrue("Removing the active entry must leave no selected source", it.kind == ManualSourceKind.None && !it.clearPending &&
                it.copyBytes() == null && it.url == null && it.validUntil == null)
        }
        requireNotNull(sidecar.readLibrary()).use { library ->
            assertTrue("Removal must keep the unselected local entry in v3", library.entries.size == 1 &&
                library.selector == ConfigurationSelector.None && library.selectedEntryId == null && !library.clearPending)
            val retained = library.entries.single()
            assertTrue("Removal must preserve the original local entry ID", retained.id == localId)
            retained.copySource().use { source ->
                assertTrue("Retained entry must still be local", source.kind == ManualSourceKind.ImportedLocal)
                val bytes = requireNotNull(source.copyBytes())
                val expectedLocal = yaml("Synthetic imported local", "192.0.2.11")
                try { assertArrayEquals("Retained local configuration must not change", expectedLocal, bytes) }
                finally { bytes.fill(0); expectedLocal.fill(0) }
            }
        }
        assertTrue("None must not fall back to legacy profile", manager.restore().status == LocalSubscriptionStatus.MISSING)
        unchanged()
        assertTrue("No real HTTP or implicit connection was performed", fakeDownloads == 1)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("manualV1Sha256", expected)
            putString("manualSidecarSha256", sha(File(context.noBackupFilesDir, "manual-source.bin")))
            putString("manualClearPending", "false")
            putString("manualSchemaVersion", "3")
            putString("manualRetainedEntries", "1")
            putString("manualSelector", "None")
            putString("downgradeReady", "false")
            putString("manualCandidate", "verified-preserved-profile-v1-library-v3-retained-local-no-http-no-vpn")
        })
    }

    @Suppress("DEPRECATION") private fun assertNoOwner(context: Context) {
        val services = context.getSystemService(ActivityManager::class.java).getRunningServices(64)
        assertTrue("Fixture must not start a connection owner or tunnel", services.none {
            it.service.packageName == context.packageName && (it.service.className.endsWith("ProductionVpnService") || it.service.className.endsWith("ConnectionOwnerService"))
        })
    }
    private fun sha(file: File): String {
        assertTrue("Synthetic encrypted file is absent", file.isFile && file.length() in 29..131356)
        return MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    }
    private fun yaml(name: String, host: String) = """proxies:
  - name: $name
    type: ss
    server: $host
    port: 9
    cipher: aes-128-gcm
    password: synthetic-upgrade-fixture-only
""".toByteArray()
    private companion object {
        const val OWNED_PACKAGE = "com.gccwebos.android.candidate.acceptance.manual20261004"
        val SYNTHETIC_URL = Secret("https://sub.gccwebos.com/sub/a/manual-import-synthetic-only.yaml")
        val UNTIL = Instant.parse("2099-01-01T00:00:00Z")
    }
}
