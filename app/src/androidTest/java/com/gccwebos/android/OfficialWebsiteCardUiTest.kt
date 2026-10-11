package com.gccwebos.android

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.design.GlassMode
import com.gccwebos.android.design.OfficialWebsiteCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Only local rendering and injected click callbacks; no browser, network, account or VPN. */
@OptIn(ExperimentalTestApi::class)
class OfficialWebsiteCardUiTest {
    @get:Rule val compose = createComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor: Float = 0f
    })

    private class Fixture {
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        var width by mutableStateOf(360)
        var opener by mutableStateOf<(String) -> Unit>({ first.add(it) })
    }

    private fun show(fixture: Fixture, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GccTheme(materialMode = GlassMode.Solid) {
                    Column(Modifier.width(fixture.width.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        OfficialWebsiteCard(opener = fixture.opener)
                    }
                }
            }
        }
    }

    @Test fun renderAndRecompositionOpenNothingUntilTheFixedWebsiteIsClicked() {
        val fixture = Fixture()
        show(fixture)
        compose.runOnIdle { assertEquals(emptyList<String>(), fixture.first); fixture.width = 340 }
        compose.onNodeWithTag("official-website-title", useUnmergedTree = true).assertTextEquals("其他平台如何使用我们的订阅")
        compose.onNodeWithTag("official-website-action", useUnmergedTree = true).assertTextEquals("查看多端导入教程")
        compose.onNodeWithTag("official-website-devices", useUnmergedTree = true).assertExists()
        // Compact single row: no duplicate mascot and no bare domain line; same fixed target.
        compose.onNodeWithTag("official-website-robot", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("official-website-domain", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("official-website-open").assertHeightIsAtLeast(48.dp)
        assertTrue(compose.onNodeWithTag("official-website-card").fetchSemanticsNode().size.height <=
            with(compose.density) { 80.dp.roundToPx() })
        compose.onNodeWithTag("official-website-globe", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), fixture.first) }
        compose.onNodeWithTag("official-website-open").performClick()
        compose.runOnIdle { assertEquals(listOf("https://gccwebos.com"), fixture.first) }
    }

    @Test fun theBreathingGlowIsASeparateOverlayThatFillsTheCardAndNeverTakesTouches() {
        val fixture = Fixture()
        show(fixture)
        val card = compose.onNodeWithTag("official-website-card").fetchSemanticsNode().size
        val glow = compose.onNodeWithTag("official-website-glow", useUnmergedTree = true).fetchSemanticsNode().size
        assertEquals(card, glow)
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNodeWithTag("official-website-open").performClick()
        compose.runOnIdle { assertEquals(listOf("https://gccwebos.com"), fixture.first) }
    }

    @Test fun eachExplicitClickUsesTheLatestOpenerWithoutAddingAnotherTouchTarget() {
        val fixture = Fixture()
        show(fixture)
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNodeWithTag("official-website-open")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { fixture.opener = { fixture.second.add(it) } }
        compose.runOnIdle { assertEquals(1, fixture.first.size); assertEquals(0, fixture.second.size) }
        compose.onNodeWithTag("official-website-open").performClick().performClick()
        compose.runOnIdle {
            assertEquals(listOf("https://gccwebos.com"), fixture.first)
            assertEquals(listOf("https://gccwebos.com", "https://gccwebos.com"), fixture.second)
        }
    }

    @Test fun missingBrowserGivesStaticFeedbackAndOnlyAnotherClickRetries() {
        val fixture = Fixture()
        var missing = true
        fixture.opener = { url ->
            fixture.first.add(url)
            if (missing) throw ActivityNotFoundException("Synthetic missing browser")
        }
        show(fixture)
        compose.onNodeWithTag("official-website-error").assertDoesNotExist()
        compose.onNodeWithTag("official-website-open").performClick()
        compose.onNodeWithTag("official-website-error").assertTextEquals("暂时无法打开官网")
        compose.mainClock.advanceTimeBy(8_000)
        compose.runOnIdle { assertEquals(1, fixture.first.size); missing = false }
        compose.onNodeWithTag("official-website-open").assertIsEnabled().performClick()
        compose.onNodeWithTag("official-website-error").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("https://gccwebos.com", "https://gccwebos.com"), fixture.first) }
    }

    @Test fun narrowLargeTextKeepsTheActionReadableAndWholeCardReachable() {
        val fixture = Fixture().apply { width = 240 }
        show(fixture, fontScale = 2f)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("official-website-action", useUnmergedTree = true)
            .assertTextEquals("查看多端导入教程").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val metrics = buildString {
            appendLine("Synthetic website card: widthDp=240, fontScale=2")
            appendLine("size=${layout.size}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}")
            appendLine("overflowWidth=${layout.didOverflowWidth}, overflowHeight=${layout.didOverflowHeight}")
            appendLine("constraints=${layout.layoutInput.constraints}, density=${layout.layoutInput.density.density}")
            appendLine("fontSize=${layout.layoutInput.style.fontSize}, lineHeight=${layout.layoutInput.style.lineHeight}")
            repeat(layout.lineCount) { index ->
                appendLine("line=$index, chars=${layout.getLineStart(index)}..${layout.getLineEnd(index)}, " +
                    "bounds=${layout.getLineLeft(index)},${layout.getLineTop(index)},${layout.getLineRight(index)},${layout.getLineBottom(index)}")
            }
        }
        val directory = File(requireNotNull(InstrumentationRegistry.getInstrumentation()
            .targetContext.getExternalFilesDir(null)), "ui-evidence/preview-v2")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "official-website-240dp-font2-layout.txt").writeText(metrics)
        File(directory, "official-website-240dp-font2.png").outputStream().use { stream ->
            assertTrue(compose.onNodeWithTag("official-website-card", useUnmergedTree = true)
                .captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        assertFalse(metrics, layout.hasVisualOverflow)
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNodeWithTag("official-website-open").performScrollTo().assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(listOf("https://gccwebos.com"), fixture.first) }
    }

    @Test fun zeroMotionDurationLeavesTheDecorativeFrameStatic() {
        val fixture = Fixture()
        show(fixture)
        compose.waitForIdle()
        val before = compose.onNodeWithTag("official-website-devices", useUnmergedTree = true).captureToImage().toPixelMap()
        compose.mainClock.advanceTimeBy(3_500)
        val after = compose.onNodeWithTag("official-website-devices", useUnmergedTree = true).captureToImage().toPixelMap()
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        for (y in 0 until before.height) for (x in 0 until before.width) assertEquals(before[x, y], after[x, y])
        compose.runOnIdle { assertEquals(0, fixture.first.size) }
    }
}
