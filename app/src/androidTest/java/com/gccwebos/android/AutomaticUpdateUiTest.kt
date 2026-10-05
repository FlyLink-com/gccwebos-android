package com.gccwebos.android

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.update.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic metadata only; all browser callbacks are injected and no APK is downloaded. */
class AutomaticUpdateUiTest {
    @get:Rule val compose = createComposeRule()
    private val official = OfficialReleases.RELEASE_APPLICATION_ID
    private val available = """{"schemaVersion":1,"platform":"android","channel":"stable","status":"available","versionCode":8,"versionName":"0.5.0","minSupportedVersionCode":1,"publishedAt":"2026-10-05T00:00:00Z","downloadUrl":"/android/releases/synthetic.apk","sha256":"${"a".repeat(64)}","notes":{"zh":"合成更新","en":"Synthetic update"}}"""
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private class Store : AutomaticUpdateStore {
        var attempted = 0L
        var prompted = 0L
        override suspend fun tryBeginCheck(now: Long, interval: Long): Boolean {
            if (!automaticCheckDue(attempted, now, interval)) return false
            attempted = now; return true
        }
        override suspend fun wasPrompted(code: Long) = code <= prompted
        override suspend fun recordPrompt(code: Long) { prompted = maxOf(prompted, code) }
    }

    @Test fun initialResumePromptsOnlyOnceAndDismissalKeepsTheMainScreenUsable() {
        val store = Store(); var requests = 0; var actions = 0; val opened = mutableListOf<String>()
        lateinit var owner: Owner
        compose.setContent {
            val scope = rememberCoroutineScope()
            val lifecycle = remember { Owner().apply { registry.currentState = Lifecycle.State.RESUMED } }.also { owner = it }
            val state = remember { AutomaticUpdateController(scope, UpdateRepository { requests++; available }, store,
                7, official, false, clock = { 100_000L }, ioDispatcher = Dispatchers.Main.immediate) }
            GccTheme { AutomaticUpdateHostContent(state, lifecycle.lifecycle, { opened += it }) {
                TextButton({ actions++ }, Modifier.testTag("synthetic-main-action")) { Text("合成登录入口") }
            } }
        }
        compose.onNodeWithTag("official-update-prompt").assertExists()
        compose.runOnIdle { assertEquals(1, requests); assertEquals(8L, store.prompted); assertTrue(opened.isEmpty()) }
        compose.onNodeWithText("稍后").performClick()
        compose.onNodeWithTag("synthetic-main-action").performClick()
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.onNodeWithTag("official-update-prompt").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, requests); assertEquals(1, actions); assertTrue(opened.isEmpty()) }
    }

    @Test fun automaticFailureHasNoLoadingErrorOrRetryUiAndNeverBlocksMainActions() {
        var actions = 0; var requests = 0
        compose.setContent {
            val scope = rememberCoroutineScope()
            val lifecycle = remember { Owner().apply { registry.currentState = Lifecycle.State.RESUMED } }
            val state = remember { AutomaticUpdateController(scope, UpdateRepository { requests++; error("Synthetic failure") },
                Store(), 7, official, false, clock = { 100_000L }, ioDispatcher = Dispatchers.Main.immediate) }
            GccTheme { AutomaticUpdateHostContent(state, lifecycle.lifecycle, { error("No browser") }) {
                TextButton({ actions++ }, Modifier.testTag("synthetic-main-action")) { Text("合成连接入口") }
            } }
        }
        compose.onNodeWithTag("official-update-prompt").assertDoesNotExist()
        compose.onNodeWithTag("updateStatus").assertDoesNotExist()
        compose.onNodeWithTag("update-read-error").assertDoesNotExist()
        compose.onNodeWithText("正在检查…").assertDoesNotExist()
        compose.onNodeWithTag("synthetic-main-action").performClick()
        compose.runOnIdle { assertEquals(1, requests); assertEquals(1, actions) }
    }

    @Test fun backgroundCompletionCannotShowOrPermanentlyDeduplicateAnUnseenVersion() {
        val store = Store(); val release = CompletableDeferred<Unit>(); var requests = 0; var now = 100_000L
        lateinit var owner: Owner
        compose.setContent {
            val scope = rememberCoroutineScope()
            val lifecycle = remember { Owner().apply { registry.currentState = Lifecycle.State.RESUMED } }.also { owner = it }
            val state = remember { AutomaticUpdateController(scope, UpdateRepository {
                if (++requests == 1) withContext(NonCancellable) { release.await() }; available
            }, store, 7, official, false, clock = { now }, ioDispatcher = Dispatchers.Main.immediate) }
            GccTheme { AutomaticUpdateHostContent(state, lifecycle.lifecycle, { error("No browser") }) { Text("合成主页") } }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED; release.complete(Unit) }
        compose.waitForIdle()
        compose.onNodeWithTag("official-update-prompt").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0L, store.prompted)
            now += AUTOMATIC_UPDATE_INTERVAL_MILLIS
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.onNodeWithTag("official-update-prompt").assertExists()
        compose.runOnIdle { assertEquals(2, requests); assertEquals(8L, store.prompted) }
    }

    @Test fun manualCheckOwnsTheOnlyDialogAndCanExplicitlyShowTheSameVersionAgain() {
        val store = Store(); val automaticReply = CompletableDeferred<Unit>()
        var manual by mutableStateOf(false); var refresh by mutableStateOf(0); var manualReads = 0
        compose.setContent {
            val scope = rememberCoroutineScope()
            val lifecycle = remember { Owner().apply { registry.currentState = Lifecycle.State.RESUMED } }
            val automatic = remember { AutomaticUpdateController(scope, UpdateRepository { automaticReply.await(); available },
                store, 7, official, false, clock = { 100_000L }, ioDispatcher = Dispatchers.Main.immediate) }
            GccTheme { AutomaticUpdateHostContent(automatic, lifecycle.lifecycle, { error("No browser") }) {
                if (manual) {
                    val explicit = remember { UpdateController(scope, UpdateRepository { manualReads++; available }, 7, official) }
                    UpdatePanelContent(explicit, official, refreshRequest = refresh, openWebsite = { error("No browser") })
                } else Text("合成主页")
            } }
        }
        compose.runOnIdle { manual = true }
        compose.onAllNodesWithTag("official-update-prompt").assertCountEquals(1)
        compose.runOnIdle { automaticReply.complete(Unit) }
        compose.onAllNodesWithTag("official-update-prompt").assertCountEquals(1)
        compose.onNodeWithText("稍后").performClick()
        compose.runOnIdle { refresh++ }
        compose.onAllNodesWithTag("official-update-prompt").assertCountEquals(1)
        compose.runOnIdle { assertEquals(2, manualReads) }
        compose.onNodeWithText("稍后").performClick()
        compose.runOnIdle { manual = false }
        compose.onNodeWithTag("official-update-prompt").assertDoesNotExist()
    }

    @Test fun automaticPromptOpensOnlyTheValidatedUrlAfterAnExplicitClick() {
        val opened = mutableListOf<String>()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val lifecycle = remember { Owner().apply { registry.currentState = Lifecycle.State.RESUMED } }
            val state = remember { AutomaticUpdateController(scope, UpdateRepository { available }, Store(), 7, official,
                false, clock = { 100_000L }, ioDispatcher = Dispatchers.Main.immediate) }
            GccTheme { AutomaticUpdateHostContent(state, lifecycle.lifecycle, { opened += it }) { Text("合成主页") } }
        }
        compose.onNodeWithTag("official-update-prompt").assertExists()
        compose.runOnIdle { assertTrue(opened.isEmpty()) }
        compose.onNodeWithText("前往官网下载").performClick()
        compose.runOnIdle { assertEquals(listOf("https://gccwebos.com/android/releases/synthetic.apk"), opened) }
        compose.onNodeWithTag("official-update-prompt").assertDoesNotExist()
    }
}
