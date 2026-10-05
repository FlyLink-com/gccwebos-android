package com.gccwebos.android

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.update.*
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Fixed synthetic manifests and injected browser callbacks; no network, downloads or installer. */
class UpdateUiTest {
    @get:Rule val compose = createComposeRule()
    private val official = "com.gccwebos.android"
    private val unavailable = """{"schemaVersion":1,"platform":"android","channel":"stable","status":"unavailable"}"""
    private val available = """{"schemaVersion":1,"platform":"android","channel":"stable","status":"available","versionCode":3,"versionName":"1.2.3","minSupportedVersionCode":1,"publishedAt":"2026-09-29T00:00:00Z","downloadUrl":"/android/releases/gccwebos-1.2.3.apk","sha256":"${"a".repeat(64)}","notes":{"zh":"合成版本说明","en":"Synthetic notes"}}"""

    @Test fun navigationRefreshCoalescesBusyChecksAndEntryRecoveryRemainsReadOnly() {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        var visit by mutableStateOf(0)
        var decoration by mutableStateOf(0)
        var refreshRequest by mutableStateOf(0)
        var autoCheck by mutableStateOf(true)
        lateinit var currentController: UpdateController
        val opened = mutableListOf<String>()
        val repository = UpdateRepository {
            if (++calls == 1) { release.await(); throw IOException("Synthetic unavailable") }
            unavailable
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            GccTheme {
                Column {
                    Text("Synthetic $decoration")
                    key(visit) {
                        val controller = remember { UpdateController(scope, repository, 2, official) }
                        currentController = controller
                        UpdatePanelContent(controller, official, autoCheck = autoCheck,
                            refreshRequest = refreshRequest, openWebsite = { opened += it })
                    }
                }
            }
        }
        compose.waitUntil(5_000) { calls == 1 }
        compose.onNodeWithTag("checkUpdate").assertDoesNotExist()
        compose.onNodeWithText("正在检查…").assertDoesNotExist()
        compose.onNodeWithText("取消检查").assertDoesNotExist()
        compose.runOnIdle { decoration++ }
        val original = currentController
        repeat(2) { compose.runOnIdle { refreshRequest++ }; compose.waitForIdle() }
        compose.runOnIdle { assertEquals(1, calls); assertTrue(opened.isEmpty()); assertSame(original, currentController) }
        compose.runOnIdle { release.complete(Unit) }
        compose.onNodeWithText("版本信息暂不可用").assertExists()
        compose.runOnIdle { decoration++ }
        compose.runOnIdle { assertEquals(1, calls); refreshRequest++ }
        compose.waitUntil(5_000) { calls == 2 }
        compose.onNodeWithTag("updateStatus").assertTextEquals("官网暂未发布正式 APK")
        compose.onNodeWithText("前往官网下载").assertDoesNotExist()
        compose.runOnIdle { assertTrue(opened.isEmpty()); assertSame(original, currentController); visit++ }
        compose.waitUntil(5_000) { calls == 3 }
        compose.runOnIdle { autoCheck = false; refreshRequest++ }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(3, calls); assertTrue(opened.isEmpty()) }
    }

    @Test fun availableVersionOnlyOpensItsValidatedOfficialUrlAfterExplicitConfirmation() {
        var calls = 0
        val opened = mutableListOf<String>()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { UpdateController(scope, UpdateRepository { calls++; available }, 2, official) }
            GccTheme { UpdatePanelContent(controller, official, openWebsite = { opened += it }) }
        }
        compose.onNodeWithText("发现新版本 1.2.3").assertExists()
        compose.runOnIdle { assertEquals(1, calls); assertTrue(opened.isEmpty()) }
        compose.onNodeWithText("此处展示清单值，未验证下载文件。", substring = true).assertExists()
        compose.onNodeWithText("前往官网下载").performClick()
        compose.runOnIdle {
            assertEquals(listOf("https://gccwebos.com/android/releases/gccwebos-1.2.3.apk"), opened)
            assertEquals(1, calls)
        }
        compose.onNodeWithText("前往官网下载").assertDoesNotExist()
    }

    @Test fun testPackageEntryNeverFetchesOrOffersAProductionUpgrade() {
        var calls = 0
        val opened = mutableListOf<String>()
        val testPackage = "$official.synthetic"
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { UpdateController(scope, UpdateRepository { calls++; available }, 2, testPackage) }
            GccTheme { UpdatePanelContent(controller, testPackage, openWebsite = { opened += it }) }
        }
        compose.onNodeWithText("测试包不提供正式版覆盖升级").assertExists()
        compose.onNodeWithTag("checkUpdate").assertDoesNotExist()
        compose.onNodeWithText("前往官网下载").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, calls); assertTrue(opened.isEmpty()) }
    }
}
