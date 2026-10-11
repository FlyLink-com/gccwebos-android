package com.gccwebos.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.design.GlassMode
import com.gccwebos.android.portal.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The signed-in account's load state on the nodes page. Display-only synthetic handles; no repository, network or VPN owner. */
class AccountLoadNoticeUiTest {
    @get:Rule val compose = createComposeRule()
    private val owner = Any()
    private val account = ConfigurationHandle(owner, "synthetic-revision", null, 0)
    private var state by mutableStateOf(AccountLoadState.Idle)
    private var accountItem by mutableStateOf(ConfigurationItem(account, "账户订阅", ManualSourceKind.LegacyRemote, emptyList(),
        LocalSubscriptionStatus.MISSING, active = true))
    private var retries = 0

    private fun launch() {
        compose.setContent {
            GccTheme(materialMode = GlassMode.Solid) {
                Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    ConfigurationLibraryContent(ConfigurationLibrarySnapshot(listOf(accountItem)), busy = false, error = null,
                        canMeasure = false, onMeasure = {}, onImport = {}, onActivate = {}, onRename = { _, _ -> },
                        onRemove = {}, onReplace = {}, accountLoad = state, onRetryAccount = { retries++ },
                        activeContent = { _, _ -> Text("active-content", Modifier.testTag("library-active-fixture")) })
                }
            }
        }
    }

    @Test fun nothingIsShownWhileTheLoadIsIdle() {
        launch()
        compose.onNodeWithTag("account-load-notice").assertDoesNotExist()
        compose.onNodeWithTag("configuration-status-0").assertTextEquals("0 条 · 账户订阅 · 待加载")
    }

    @Test fun loadingIsAPlainLineWithNoRetry() {
        state = AccountLoadState.Loading
        launch()
        compose.onNodeWithTag("account-load-loading").assertIsDisplayed().assertTextEquals("正在加载账户订阅…")
        compose.onNodeWithTag("account-load-retry").assertDoesNotExist()
        compose.onNodeWithTag("configuration-status-0").assertTextEquals("0 条 · 账户订阅 · 加载中…")
    }

    @Test fun aFailedLoadSaysSoAndTheNoticeAndTheSourceRowBothRetry() {
        state = AccountLoadState.Failed
        launch()
        compose.onNodeWithTag("account-load-failed", useUnmergedTree = true).assertIsDisplayed().assertTextEquals("账户订阅加载失败")
        compose.onNodeWithText("点此重试").assertIsDisplayed()
        compose.onNodeWithTag("configuration-status-0", useUnmergedTree = true).assertTextEquals("0 条 · 账户订阅 · 加载失败，点此重试")
        compose.onNodeWithTag("account-load-retry").assertHeightIsAtLeast(48.dp).assertHasClickAction().performClick()
        assertEquals(1, retries)
        compose.onNodeWithTag("configuration-group-0").assertHasClickAction().performClick()
        assertEquals(2, retries)
    }

    @Test fun theNoticeDisappearsOnceTheAccountHasItsNodes() {
        state = AccountLoadState.Failed
        launch()
        compose.onNodeWithTag("account-load-notice").assertExists()
        compose.runOnIdle {
            accountItem = ConfigurationItem(account, "账户订阅", ManualSourceKind.LegacyRemote, emptyList(), LocalSubscriptionStatus.READY, active = true)
        }
        compose.onNodeWithTag("account-load-notice").assertDoesNotExist()
    }
}
