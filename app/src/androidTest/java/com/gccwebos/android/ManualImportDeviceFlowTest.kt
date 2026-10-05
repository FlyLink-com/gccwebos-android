package com.gccwebos.android

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.manualimport.ManualImportFailure
import com.gccwebos.android.manualimport.ManualImportHost
import com.gccwebos.android.manualimport.ManualImportInputKind
import com.gccwebos.android.portal.*
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in hardware/picker acceptance. Only a fixed synthetic payload may enter the in-memory source. */
class ManualImportDeviceFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun realCameraStagesSyntheticQrThenExplicitConfirmationImportsRemote() =
        exercise("camera", ManualImportInputKind.Scan, ManualSourceKind.ImportedRemote)

    @Test fun realDocumentPickerStagesOwnedYamlThenExplicitConfirmationImportsLocal() =
        exercise("document", ManualImportInputKind.Document, ManualSourceKind.ImportedLocal)

    @Test fun realDocumentPickerStagesOwnedLinkThenExplicitConfirmationImportsRemote() =
        exercise("document-link", ManualImportInputKind.Document, ManualSourceKind.ImportedRemote)

    @Test fun realCameraPermissionDenialReturnsFixedFailureWithoutImport() =
        exercise("camera-denied", ManualImportInputKind.Scan, ManualSourceKind.None)

    private fun exercise(path: String, kind: ManualImportInputKind, expectedKind: ManualSourceKind) {
        assumeTrue("Explicit physical import path required", InstrumentationRegistry.getArguments().getString("manualDevicePath") == path)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Only uniquely owned UI fixture may run physical import", BuildConfig.DEBUG &&
            BuildConfig.APPLICATION_ID == OWNED_PACKAGE && context.packageName == OWNED_PACKAGE)
        val denial = path == "camera-denied"
        if (denial) assertTrue("Camera denial fixture requires permission revoked before launch",
            context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
        val store = if (denial) ManualSourceRecord(kind = ManualSourceKind.None).use { MemoryManualSourceStore(it) }
            else MemoryManualSourceStore()
        val profiles = MemoryLocalProfileStore()
        val downloads = AtomicInteger()
        val commits = AtomicInteger()
        val submitted = AtomicReference<ManualImportInputKind?>()
        val imported = AtomicReference<LocalSubscriptionSnapshot?>()
        val platformFailure = AtomicReference<ManualImportFailure?>()
        val inputMismatch = AtomicReference(false)
        val manager = LocalSubscriptionManager(object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) { throw AssertionError("Device import must not write legacy delivery") }
            override fun clear() { throw AssertionError("Device import must not clear legacy delivery") }
        }, profiles, object : SubscriptionSource {
            override suspend fun download(url: Secret) = read(url).bytes
            override suspend fun read(url: Secret): SubscriptionRead {
                assertTrue("Only the exact owned synthetic URL is allowed",
                    path in setOf("camera", "document-link") && url.value == CAMERA_PAYLOAD)
                downloads.incrementAndGet()
                return SubscriptionRead(cameraYaml(), UNTIL)
            }
        }, OriginPolicy("https://portal.gccwebos.com", setOf("https://sub.gccwebos.com")),
            { Instant.parse("2026-10-04T00:00:00Z") }, LocalSubscriptionAuthority(), manualSources = store)
        lateinit var controller: ManualImportController
        compose.setContent {
            val scope = rememberCoroutineScope()
            controller = remember {
                ManualImportController(manager, scope, allowed = { true }, commitFence = { action ->
                    assertNoVpn(context)
                    action()
                    commits.incrementAndGet()
                }, onSnapshot = imported::set).also { it.open() }
            }
            GccTheme {
                ManualImportHost(visible = controller.visible, busy = controller.busy, error = controller.error,
                    onDismiss = controller::cancel, onBegin = controller::begin,
                    onSubmit = { ticket, bytes, receivedKind ->
                        val expected = if (path in setOf("camera", "document-link")) CAMERA_PAYLOAD.toByteArray() else documentYaml()
                        val matches = try { receivedKind == kind && bytes.contentEquals(expected) } finally { expected.fill(0) }
                        if (!matches) {
                            bytes.fill(0); inputMismatch.set(true); controller.cancel(ticket)
                        } else {
                            submitted.set(receivedKind)
                            controller.submit(ticket, bytes, receivedKind)
                        }
                    }, onCancel = controller::cancel, onUseAccount = controller::useAccount, onRemove = controller::remove,
                    onFailure = { ticket, reason -> platformFailure.set(reason); controller.failure(ticket, reason) })
                // Intentionally no platformOverride: ActivityResult, bundled scanner and SAF are real.
            }
        }
        val tag = if (kind == ManualImportInputKind.Scan) "manual-import-scan" else "manual-import-document"
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("manualDeviceWaiting", when (path) {
                "camera" -> "physical-camera-synthetic-qr"
                "camera-denied" -> "camera-permission-denial"
                "document-link" -> "saf-owned-link-selection"
                else -> "saf-owned-yaml-selection"
            })
        })
        try {
            compose.waitUntil(180_000) {
                platformFailure.get() != null || compose.onAllNodesWithTag("manual-import-confirm")
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
            if (denial) {
                assertTrue("Camera denial must use only the fixed permission failure",
                    platformFailure.get() == ManualImportFailure.CameraPermission)
                compose.onNodeWithTag("manual-import-confirm").assertDoesNotExist()
                compose.onNodeWithTag("manual-import-error").assertTextEquals(ManualImportFailure.CameraPermission.caption)
                compose.runOnIdle {
                    assertNull(submitted.get()); assertNull(imported.get()); assertFalse(inputMismatch.get())
                    assertEquals(0, commits.get()); assertEquals(0, downloads.get())
                    assertNull(profiles.read())
                    store.read()!!.use { assertEquals(ManualSourceKind.None, it.kind); assertNull(it.copyBytes()) }
                    assertNoVpn(context)
                }
                InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                    putString("manualDevicePath", path)
                    putString("manualDeviceResult", "real-camera-permission-denied-no-import-no-http-no-vpn")
                })
                return
            }
            assertTrue("Platform import returned a fixed failure", platformFailure.get() == null)
            compose.onNodeWithTag("manual-import-confirm").assertIsDisplayed().assertIsEnabled()
            compose.runOnIdle {
                assertNull("Camera/SAF return must only stage bytes", submitted.get())
                assertNull("No source may commit before confirmation", imported.get())
                assertTrue("No import/download before confirmation", commits.get() == 0 && downloads.get() == 0)
                store.read()!!.use { assertTrue("Original selector must still be active", it.kind == ManualSourceKind.LegacyRemote) }
                assertNoVpn(context)
            }
            compose.onNodeWithTag("manual-import-confirm").performClick()
            compose.waitUntil(10_000) { inputMismatch.get() || imported.get() != null || controller.error != null }
            assertFalse("Selected input differs from the owned synthetic fixture", inputMismatch.get())
            compose.runOnIdle {
                val result = imported.get() ?: throw AssertionError("Explicit import did not complete")
                assertTrue("Import path kind changed", submitted.get() == kind && result.sourceKind == expectedKind)
                assertTrue("Exactly one explicit source commit is required", commits.get() == 1)
                assertTrue("Source must contain one validated synthetic node", result.status == LocalSubscriptionStatus.READY && result.nodes.size == 1)
                if (expectedKind == ManualSourceKind.ImportedLocal) {
                    assertTrue("Local source must have no borrowed server expiry", result.validUntil == null && result.validity == LocalSourceValidity.LocalNoServerExpiry)
                    assertTrue("SAF YAML must never call the downloader", downloads.get() == 0)
                } else {
                    assertTrue("Imported link uses only the injected bounded source", downloads.get() == 1)
                    assertTrue("Remote source keeps its explicit expiry", result.validUntil == UNTIL && result.validity == LocalSourceValidity.RemoteExpiry(UNTIL))
                }
                assertNull("Import must not write original profile", profiles.read())
                store.read()!!.use { record ->
                    assertTrue("Selected envelope differs from imported source", record.kind == expectedKind && !record.clearPending)
                    val bytes = record.copyBytes()!!
                    val expected = if (expectedKind == ManualSourceKind.ImportedRemote) cameraYaml() else documentYaml()
                    try { assertTrue("Synthetic imported material changed", bytes.contentEquals(expected)) }
                    finally { bytes.fill(0); expected.fill(0) }
                }
                assertNoVpn(context)
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("manualDevicePath", path)
                putString("manualDeviceResult", "real-platform-explicit-confirm-single-import-no-http-no-vpn")
            })
        } finally {
            compose.runOnUiThread { controller.cancel() }
            ManualSourceRecord(kind = ManualSourceKind.None).use(store::save)
        }
    }

    @Suppress("DEPRECATION") private fun assertNoVpn(context: Context) {
        val services = context.getSystemService(ActivityManager::class.java).getRunningServices(64)
        assertTrue("Import must not start a tunnel or connection owner", services.none {
            it.service.packageName == OWNED_PACKAGE && (it.service.className.endsWith("ProductionVpnService") || it.service.className.endsWith("ConnectionOwnerService"))
        })
    }
    private fun cameraYaml() = yaml("Synthetic camera import", "192.0.2.21")
    private fun documentYaml() = yaml("Synthetic document import", "192.0.2.22")
    private fun yaml(name: String, host: String) = """proxies:
  - name: $name
    type: ss
    server: $host
    port: 9
    cipher: aes-128-gcm
    password: synthetic-device-import-only
""".toByteArray()
    private companion object {
        const val OWNED_PACKAGE = "com.gccwebos.android.candidate.acceptance.manualui20261004"
        const val CAMERA_PAYLOAD = "https://sub.gccwebos.com/sub/a/synthetic-camera-oct4"
        val UNTIL = Instant.parse("2099-01-01T00:00:00Z")
    }
}
