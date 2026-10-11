package com.gccwebos.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.gccwebos.android.portal.RememberAccountRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The 「记住账号」 row: ticked by default, a real 48 dp checkbox, states readable by TalkBack, plain copy. */
class LoginRememberUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rowIsAToggleableCheckboxThatTogglesAndSaysItDoesNotKeepThePassword() {
        var checked by mutableStateOf(true)
        compose.setContent { RememberAccountRow(checked, enabled = true, onChange = { checked = it }) }
        compose.onNodeWithTag("login-remember").assertIsOn().assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role,
                androidx.compose.ui.semantics.Role.Checkbox))
        compose.onNodeWithText("记住账号", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("（不保存密码）", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("login-remember").performClick().assertIsOff()
        assertEquals(false, checked)
        compose.onNodeWithTag("login-remember").performClick().assertIsOn()
    }

    @Test fun disabledWhileSigningInAndNeverToggles() {
        var checked by mutableStateOf(true)
        compose.setContent { RememberAccountRow(checked, enabled = false, onChange = { checked = it }) }
        compose.onNodeWithTag("login-remember").assertIsNotEnabled().performClick()
        assertEquals(true, checked)
    }
}
