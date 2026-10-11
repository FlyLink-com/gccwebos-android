package com.gccwebos.android

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.design.LocalAnimatorScaleOverride
import com.gccwebos.android.manualimport.ImportStage
import com.gccwebos.android.manualimport.ManualImportProgress
import org.junit.Rule
import org.junit.Test

/** Staged import progress: indeterminate while reading, steps 2 and 3 after, still bar with animations off. */
class ManualImportProgressUiTest {
    @get:Rule val compose = createComposeRule()

    private fun show(stage: ImportStage?, scale: Float? = null, slowHintMillis: Long = 60_000) = compose.setContent {
        CompositionLocalProvider(LocalAnimatorScaleOverride provides scale) {
            GccTheme { ManualImportProgress(stage, slowHintMillis) }
        }
    }

    @Test fun readingShowsAnIndeterminateBarAndStepOneOfThree() {
        show(ImportStage.Reading)
        compose.onNodeWithTag("manual-import-stage").assertTextContains("正在读取订阅", substring = true)
            .assertTextContains("第 1/3 步", substring = true)
        compose.onNodeWithTag("manual-import-bar-indeterminate").assertExists()
        compose.onNodeWithTag("manual-import-slow").assertDoesNotExist()
    }

    @Test fun parsingAndSavingShowADeterminateStepBar() {
        show(ImportStage.Parsing)
        compose.onNodeWithTag("manual-import-stage").assertTextContains("正在解析配置", substring = true)
        compose.onNodeWithTag("manual-import-bar").assertExists()
        compose.onNodeWithTag("manual-import-bar-indeterminate").assertDoesNotExist()
    }

    @Test fun slowReadShowsTheTimeoutHint() {
        show(ImportStage.Reading, slowHintMillis = 300)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("manual-import-slow").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("manual-import-slow").assertTextContains("最长等待约 30 秒", substring = true)
    }

    @Test fun withAnimationsOffTheReadingBarDoesNotAnimate() {
        show(ImportStage.Reading, scale = 0f)
        compose.onNodeWithTag("manual-import-bar").assertExists()
        compose.onNodeWithTag("manual-import-bar-indeterminate").assertDoesNotExist()
    }

    @Test fun beforeAnyStageIsReportedTheBarIsStillIndeterminate() {
        show(null)
        compose.onNodeWithTag("manual-import-stage").assertTextContains("正在准备", substring = true)
        compose.onNodeWithTag("manual-import-bar-indeterminate").assertExists()
    }
}
