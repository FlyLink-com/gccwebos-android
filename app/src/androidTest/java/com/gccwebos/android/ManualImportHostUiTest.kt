package com.gccwebos.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.manualimport.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ManualImportHostUiTest {
    @get:Rule val compose = createComposeRule()
    private var visible by mutableStateOf(true)
    private var busy by mutableStateOf(false)
    private val platform = FakePlatform()
    private val tickets = mutableListOf<Any>()
    private val canceled = mutableListOf<Any>()
    private val submissions = mutableListOf<ManualImportInputKind>()
    private val failures = mutableListOf<ManualImportFailure>()
    private var accountActions = 0
    private var removeActions = 0
    @androidx.compose.runtime.Composable
    private fun Content() {
        GccTheme {
            ManualImportHost(visible, busy, null, canUseAccount = true, canRemove = true,
                onDismiss = { visible = false }, onBegin = { Any().also(tickets::add) },
                onSubmit = { ticket, bytes, kind ->
                    assertSame(tickets.last(), ticket)
                    assertTrue(bytes.isNotEmpty())
                    submissions += kind
                    bytes.fill(0)
                }, onCancel = { canceled += it },
                onUseAccount = { accountActions++ }, onRemove = { removeActions++ },
                onFailure = { _, reason -> failures += reason }, platformOverride = platform)
        }
    }
    private fun launch() { compose.setContent { Content() } }
    private class FakePlatform : ManualImportPlatform {
        val starts = mutableListOf<ManualImportInputKind>()
        val callbacks = mutableListOf<(ManualInputResult) -> Unit>()
        var cancels = 0
        override fun launch(kind: ManualImportInputKind, result: (ManualInputResult) -> Unit): ManualInputCancellation {
            starts += kind; callbacks += result
            return ManualInputCancellation { cancels++ }
        }
    }
    @Test fun allThreeEntriesAreExplicitAndLinkSubmitsOnlyOnceWithTheIssuedTicket() {
        launch()
        listOf("scan", "document", "link").forEach {
            compose.onNodeWithTag("manual-import-$it").assertIsDisplayed().assertIsEnabled()
        }
        compose.runOnIdle { assertTrue(platform.starts.isEmpty()); assertTrue(submissions.isEmpty()); assertTrue(tickets.isEmpty()) }
        compose.onNodeWithTag("manual-import-link").performClick()
        compose.onNodeWithTag("manual-link-input").performTextInput("https://sub.invalid/sub/a/synthetic")
        compose.runOnIdle { busy = true }
        compose.onNodeWithTag("manual-import-confirm").assertIsNotEnabled()
        compose.runOnIdle { busy = false }
        compose.onNodeWithTag("manual-import-confirm").performClick().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf(ManualImportInputKind.Link), submissions); assertTrue(platform.starts.isEmpty()) }
    }
    @Test fun documentAndScanStageWithoutRenderingPayloadOrAutomaticallyImporting() {
        launch()
        for ((index, kind) in listOf(ManualImportInputKind.Document, ManualImportInputKind.Scan).withIndex()) {
            if (index > 0) compose.runOnIdle { visible = true }
            compose.onNodeWithTag("manual-import-${if (kind == ManualImportInputKind.Document) "document" else "scan"}").performClick()
            val bytes = "synthetic-content-never-displayed".toByteArray()
            compose.runOnIdle { platform.callbacks.last()(ManualInputResult.Content(bytes)); assertEquals(index, submissions.size) }
            compose.onNodeWithText("synthetic-content-never-displayed", substring = true).assertDoesNotExist()
            compose.onNodeWithTag("manual-import-confirm").performClick()
            compose.runOnIdle { assertEquals(kind, submissions.last()); assertTrue(bytes.all { it == 0.toByte() }) }
            compose.onNodeWithTag("manual-import-cancel").performClick()
        }
        compose.runOnIdle { assertEquals(listOf(ManualImportInputKind.Document, ManualImportInputKind.Scan), platform.starts) }
    }
    @Test fun dismissDiscardsLateResultAndRecreationClearsStagedSecretAndTicket() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Content() }
        compose.onNodeWithTag("manual-import-scan").performClick()
        compose.onNodeWithTag("manual-import-cancel").performClick()
        val late = byteArrayOf(1, 2, 3)
        compose.runOnIdle {
            platform.callbacks.single()(ManualInputResult.Content(late))
            assertTrue(late.all { it == 0.toByte() }); assertEquals(1, canceled.size)
            visible = true
        }
        compose.onNodeWithTag("manual-import-document").performClick()
        val staged = byteArrayOf(4, 5, 6)
        compose.runOnIdle { platform.callbacks.last()(ManualInputResult.Content(staged)) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("manual-import-link").assertExists()
        compose.onNodeWithTag("manual-import-confirm").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(staged.all { it == 0.toByte() }); assertEquals(2, canceled.size)
            assertEquals(2, platform.cancels); assertTrue(submissions.isEmpty())
        }
    }
    @Test fun platformFailureIsFixedAndSourceRemovalNeedsItsOwnExplicitConfirmation() {
        launch()
        compose.onNodeWithTag("manual-remove").performClick()
        compose.runOnIdle { assertEquals(0, removeActions); assertEquals(0, accountActions) }
        compose.onNodeWithTag("manual-remove-confirm").performClick()
        compose.runOnIdle { assertEquals(1, removeActions); assertTrue(submissions.isEmpty()) }
        compose.onNodeWithTag("manual-import-cancel").performClick()
        compose.runOnIdle { visible = true }
        compose.onNodeWithTag("manual-import-scan").performClick()
        compose.runOnIdle { platform.callbacks.single()(ManualInputResult.Failure(ManualImportFailure.CameraPermission)) }
        compose.onNodeWithTag("manual-import-error").assertTextEquals("未允许相机权限")
        compose.runOnIdle { assertEquals(listOf(ManualImportFailure.CameraPermission), failures); assertTrue(submissions.isEmpty()) }
    }
}
