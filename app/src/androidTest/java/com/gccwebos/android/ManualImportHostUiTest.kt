package com.gccwebos.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.manualimport.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
    private var beginAction: suspend () -> Any? = { Any().also(tickets::add) }
    @androidx.compose.runtime.Composable
    private fun Content() {
        GccTheme {
            ManualImportHost(visible, busy, null, canUseAccount = true, canRemove = true,
                onDismiss = { visible = false }, onBegin = { beginAction() },
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
            assertSame(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
            starts += kind; callbacks += result
            return ManualInputCancellation { cancels++ }
        }
    }
    @Test fun preparingDisablesDuplicateLaunchAndCallsPlatformOnlyAfterMainThreadTicketPublication() {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var starts = 0
        beginAction = {
            starts++; entered.complete(Unit); release.await()
            assertSame(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
            Any().also(tickets::add)
        }
        launch()
        try {
            compose.onNodeWithTag("manual-import-scan").performClick()
            compose.waitUntil(5000) { entered.isCompleted }
            listOf("scan", "document", "link").forEach {
                compose.onNodeWithTag("manual-import-$it").assertIsNotEnabled()
            }
            compose.runOnIdle { assertEquals(1, starts); assertTrue(platform.starts.isEmpty()); assertTrue(tickets.isEmpty()) }
            compose.runOnIdle { release.complete(Unit) }
            compose.waitUntil(5000) { platform.starts.size == 1 }
            compose.runOnIdle { assertEquals(listOf(ManualImportInputKind.Scan), platform.starts); assertEquals(1, tickets.size) }
        } finally { release.complete(Unit) }
    }

    @Test fun dismissWhilePreparingDoesNotLaunchPlatformOrAdoptLateUncooperativeTicket() {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        beginAction = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await(); Any().also(tickets::add) }
        }
        launch()
        try {
            compose.onNodeWithTag("manual-import-scan").performClick()
            compose.waitUntil(5000) { entered.isCompleted }
            compose.onNodeWithTag("manual-import-cancel").performClick()
            compose.runOnIdle { release.complete(Unit) }
            compose.waitForIdle()
            compose.onNodeWithTag("manual-import-host").assertDoesNotExist()
            compose.runOnIdle {
                assertTrue(platform.starts.isEmpty()); assertTrue(submissions.isEmpty())
                assertTrue(tickets.all { ticket -> canceled.any { it === ticket } })
            }
        } finally { release.complete(Unit) }
    }

    @Test fun removingHostWhilePreparingCannotLaunchPlatformAfterTheHostHasClosed() {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        beginAction = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await(); Any().also(tickets::add) }
        }
        launch()
        try {
            compose.onNodeWithTag("manual-import-document").performClick()
            compose.waitUntil(5000) { entered.isCompleted }
            compose.runOnIdle { visible = false }
            compose.onNodeWithTag("manual-import-host").assertDoesNotExist()
            compose.runOnIdle { release.complete(Unit) }
            compose.waitForIdle()
            compose.runOnIdle {
                assertTrue(platform.starts.isEmpty()); assertTrue(submissions.isEmpty())
                assertTrue(tickets.all { ticket -> canceled.any { it === ticket } })
            }
        } finally { release.complete(Unit) }
    }
    @Test fun allImportEntriesAreExplicitAndLinkSubmitsOnlyOnceWithTheIssuedTicket() {
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

    private fun clipboard(text: String?) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val manager = instrumentation.targetContext.getSystemService(android.content.ClipboardManager::class.java)
            if (text == null) manager.clearPrimaryClip()
            else manager.setPrimaryClip(android.content.ClipData.newPlainText("synthetic", text))
        }
    }

    @Test fun linkFieldDoesNotTakeFocusAndPasteFillsItOnlyOnTap() {
        clipboard("  https://sub.invalid/sub/a/synthetic\n")
        launch()
        compose.onNodeWithTag("manual-import-link").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("manual-link-input").assertIsNotFocused()
        compose.onNodeWithTag("manual-import-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("manual-link-paste").assertIsEnabled().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("manual-import-confirm").fetchSemanticsNodes()
            .singleOrNull()?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == false }
        compose.onNodeWithTag("manual-link-input").assertIsNotFocused()
        compose.onNodeWithTag("manual-import-confirm").performClick()
        compose.runOnIdle { assertEquals(listOf(ManualImportInputKind.Link), submissions) }
    }

    @Test fun pastingAnEmptyClipboardShowsAHintAndLeavesTheFieldEmpty() {
        clipboard(null)
        launch()
        compose.onNodeWithTag("manual-import-link").performClick()
        compose.onNodeWithTag("manual-link-paste").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("manual-link-paste-hint").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("manual-import-confirm").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(submissions.isEmpty()) }
    }

    @Test fun pasteIsATrailingIconInsideTheFieldAndTheMenuHasNoClipboardEntry() {
        launch()
        compose.onNodeWithText("从剪贴板导入").assertDoesNotExist()
        compose.onNodeWithTag("manual-import-link").performClick()
        compose.onNodeWithText("粘贴").assertDoesNotExist()
        compose.onNodeWithContentDescription("从剪贴板粘贴").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("manual-link-paste", useUnmergedTree = true).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val field = compose.onNodeWithTag("manual-link-input").fetchSemanticsNode().boundsInRoot
        val icon = compose.onNodeWithTag("manual-link-paste", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("paste icon must sit inside the field", field.contains(icon.center))
        compose.runOnIdle { assertTrue(submissions.isEmpty()) }
    }
}
