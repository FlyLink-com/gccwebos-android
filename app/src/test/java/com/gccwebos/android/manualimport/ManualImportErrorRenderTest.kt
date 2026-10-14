package com.gccwebos.android.manualimport

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.gccwebos.android.portal.ManualImportInputRejected
import com.gccwebos.android.portal.PortalFailure
import com.gccwebos.android.portal.manualImportErrorMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManualImportErrorRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun render(message: String) {
        compose.setContent {
            ManualImportHost<Unit>(visible = true, busy = false, error = message, onDismiss = {}, onBegin = { Unit },
                onSubmit = { _, _, _ -> }, onCancel = {}, onUseAccount = {}, onRemove = {})
        }
        compose.onNode(hasTestTag("manual-import-error"), useUnmergedTree = true).assertTextEquals(message)
    }

    @Test fun oversizeMessageIsShownOnTheImportSheet() =
        render(manualImportErrorMessage(ManualImportInputRejected(ManualImportInputRejected.Reason.TOO_LARGE)))

    @Test fun encodingMessageIsShownOnTheImportSheet() =
        render(manualImportErrorMessage(ManualImportInputRejected(ManualImportInputRejected.Reason.ENCODING)))

    @Test fun serverUnavailableMessageIsShownOnTheImportSheet() =
        render(manualImportErrorMessage(PortalFailure(PortalFailure.Reason.UNAVAILABLE)))
}
