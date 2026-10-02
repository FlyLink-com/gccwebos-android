package com.gccwebos.android

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.gccwebos.android.design.*
import com.gccwebos.android.portal.Resource
import com.gccwebos.android.portal.Usage
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Approved card labels + synthetic intent counters. No controller, account, transport or VPN. */
class AccountUsageCardUiTest {
    @get:Rule val compose = createComposeRule()
    private val refreshes = AtomicInteger()
    private val logins = AtomicInteger()
    private val registrations = AtomicInteger()
    private val primaryActions = AtomicInteger()
    private val returns = AtomicInteger()
    private val now = Instant.parse("2026-10-02T00:10:00Z")
    private val sample = Instant.parse("2026-10-02T00:01:00Z")
    private val gib = 1_073_741_824L

    private class Fixture(presentation: AccountUsagePresentation, authenticated: Boolean) {
        var presentation by mutableStateOf(presentation)
        var authenticated by mutableStateOf(authenticated)
        var loading by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var refreshEnabled by mutableStateOf(true)
        var primaryLabel by mutableStateOf<String?>(null)
        var primaryEnabled by mutableStateOf(false)
        var showPrimary by mutableStateOf(true)
    }

    private fun presentation(resource: Resource? = null, failed: Boolean = false) =
        accountUsagePresentation(resource, now, failed, ZoneId.of("UTC"))

    private fun measured() = Resource.Available("resource_" + "a".repeat(64), "synthetic_usage_plan",
        Instant.parse("2026-11-02T00:00:00Z"), true,
        Usage(50 * gib, 3 * gib, 47 * gib, "measured", sample,
            Instant.parse("2026-10-02T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z")),
        durationDays = 30, monthlyTrafficGb = 50.0)

    private fun launch(fixture: Fixture, width: Int = 360, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GccTheme { Surface {
                    Column(Modifier.width(width.dp).fillMaxHeight().testTag("account-usage-fixture-host")
                        .verticalScroll(rememberScrollState()).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AccountUsageCard(fixture.presentation, fixture.authenticated,
                            onRefresh = { refreshes.incrementAndGet(); fixture.loading = true },
                            onLogin = { logins.incrementAndGet() },
                            onRegister = { registrations.incrementAndGet() },
                            loading = fixture.loading, error = fixture.error, refreshEnabled = fixture.refreshEnabled,
                            primaryLabel = fixture.primaryLabel, primaryEnabled = fixture.primaryEnabled,
                            primaryTestTag = "account-usage-primary", showPrimary = fixture.showPrimary,
                            onPrimaryAction = { primaryActions.incrementAndGet() })
                        BrandButton("返回", { returns.incrementAndGet() }, Modifier.testTag("account-usage-fixture-return"))
                    }
                } }
            }
        }
    }

    @Test fun guestOnboardingAndConfirmedNoneRequireExplicitAccountAndPlanIntents() {
        // Even a mistakenly retained presentation cannot leak metrics to a signed-out card.
        val fixture = Fixture(presentation(measured()), authenticated = false).apply { primaryEnabled = true }
        launch(fixture)
        compose.onNodeWithTag("account-usage-title").assertTextEquals("账户当前套餐")
        compose.onNodeWithTag("account-usage-remaining").assertTextEquals("暂未获取")
        compose.onNodeWithTag("account-usage-refresh").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-used").assertDoesNotExist()
        assertEquals(0, logins.get()); assertEquals(0, registrations.get()); assertEquals(0, primaryActions.get())
        compose.onNodeWithTag("account-usage-primary").assertTextEquals("开通飞连vpn")
            .assertHeightIsAtLeast(48.dp).performScrollTo().performClick()
        assertEquals(1, primaryActions.get()); assertEquals(0, logins.get()); assertEquals(0, registrations.get())
        compose.onNodeWithTag("account-usage-login").assertHeightIsAtLeast(48.dp).performScrollTo().performClick()
        compose.onNodeWithTag("account-usage-register").assertHeightIsAtLeast(48.dp).performScrollTo().performClick()
        assertEquals(1, logins.get()); assertEquals(1, registrations.get())
        assertEquals(0, refreshes.get()); assertEquals(1, primaryActions.get())
        checkpoint("guest-explicit-account-intent")
        compose.runOnIdle {
            fixture.authenticated = true
            fixture.presentation = presentation(Resource.None)
            fixture.primaryEnabled = false
        }
        compose.onNodeWithTag("account-usage-no-plan").assertTextEquals("暂无生效套餐")
        compose.onNodeWithTag("account-usage-login").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-register").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-primary").assertTextEquals("开通飞连vpn").assertIsNotEnabled()
        compose.runOnIdle { fixture.primaryEnabled = true }
        compose.onNodeWithTag("account-usage-primary").assertHeightIsAtLeast(48.dp).performScrollTo().performClick()
        assertEquals(2, primaryActions.get()); assertEquals(0, refreshes.get())
        checkpoint("none-explicit-plans-intent")
        compose.runOnIdle {
            fixture.error = "synthetic_first_read_failure"
        }
        compose.onNodeWithTag("account-usage-read-status").assertTextEquals("读取失败")
        compose.onNodeWithTag("account-usage-remaining").assertTextEquals("暂未获取")
        compose.onNodeWithTag("account-usage-primary").assertTextEquals("重试").assertIsEnabled()
        compose.onNodeWithTag("account-usage-no-plan").assertDoesNotExist()
        compose.runOnIdle { fixture.presentation = presentation() }
        compose.onNodeWithTag("account-usage-remaining").assertTextEquals("暂未获取")
        compose.onNodeWithTag("account-usage-primary").assertTextEquals("重试").assertIsEnabled()
        compose.onNodeWithText("0 GB").assertDoesNotExist()
        checkpoint("initial-failure-retry-no-false-none")
        compose.runOnIdle { fixture.error = null; fixture.refreshEnabled = false }
        compose.onNodeWithTag("account-usage-primary").assertTextEquals("重试").assertIsNotEnabled()
            .performScrollTo().performTouchInput { click() }
        compose.onNodeWithTag("account-usage-refresh").assertIsNotEnabled()
        assertEquals(0, refreshes.get()); assertEquals(2, primaryActions.get())
    }

    @Test fun measuredUpdateAndRetryNeverConnectAndKeepSampleUnchanged() {
        val fixture = Fixture(presentation(measured()), authenticated = true)
        launch(fixture)
        compose.onNodeWithTag("account-usage-remaining").assertTextEquals("47 GB")
        compose.onNodeWithTag("account-usage-plan").assertTextEquals("30天套餐")
        compose.onNodeWithTag("account-usage-used").assertTextEquals("已用 3 GB")
        compose.onNodeWithTag("account-usage-total").assertTextEquals("总量 50 GB")
        compose.onNodeWithTag("account-usage-period").assertTextEquals("本期用量")
        compose.onNodeWithTag("account-usage-updated-at").assertTextEquals("采集 2026-10-02 00:01:00 Z")
        compose.onNodeWithTag("account-usage-service-expired").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-primary").assertTextEquals("一键连接").assertIsNotEnabled()
            .performScrollTo().performTouchInput { click() }
        compose.onNodeWithTag("account-usage-primary-hint").assertTextEquals("连接功能暂未开放")
        assertEquals(0, refreshes.get()); assertEquals(0, primaryActions.get())
        checkpoint("measured-account-snapshot")
        compose.onNodeWithTag("account-usage-refresh").assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp).performScrollTo().performClick()
        compose.onNodeWithTag("account-usage-refresh").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithTag("account-usage-read-status").assertTextEquals("正在读取")
        compose.onNodeWithTag("account-usage-primary").performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        assertEquals(1, refreshes.get()); assertEquals(0, primaryActions.get())
        checkpoint("readonly-loading-no-repeat-or-connection")
        compose.runOnIdle {
            fixture.loading = false
            fixture.error = "synthetic_offline_failure"
            fixture.presentation = presentation(measured(), failed = true)
        }
        compose.onNodeWithTag("account-usage-read-status").assertTextEquals("读取失败 · 未更新")
        compose.onNodeWithTag("account-usage-remaining").assertTextEquals("47 GB")
        compose.onNodeWithTag("account-usage-updated-at").assertTextEquals("采集 2026-10-02 00:01:00 Z")
        compose.onNodeWithTag("account-usage-primary").assertTextEquals("重试").assertIsEnabled()
        assertEquals(1, refreshes.get()); assertEquals(0, primaryActions.get())
        checkpoint("failed-read-keeps-old-sample-retry-only")
        compose.onNodeWithTag("account-usage-primary").performScrollTo().performClick()
        compose.onNodeWithTag("account-usage-refresh").assertIsNotEnabled()
        compose.onNodeWithTag("account-usage-primary").assertTextEquals("重试").assertIsNotEnabled().performTouchInput { click() }
        assertEquals(2, refreshes.get()); assertEquals(0, primaryActions.get())
        compose.runOnIdle { fixture.loading = false; fixture.error = null; fixture.presentation = presentation(measured()) }
        compose.onNodeWithTag("account-usage-primary").performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        assertEquals(0, primaryActions.get()); assertEquals(2, refreshes.get())
        assertEquals(0, logins.get()); assertEquals(0, registrations.get())
        compose.onNodeWithTag("account-usage-primary-hint").assertTextEquals("连接功能暂未开放")
        compose.runOnIdle { fixture.showPrimary = false }
        compose.onNodeWithTag("account-usage-primary").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-primary-hint").assertDoesNotExist()
        compose.onNodeWithTag("account-usage-refresh").assertIsEnabled()
        assertEquals(0, primaryActions.get())
    }

    @Test fun narrowDoubledFontKeepsUpdateUnwiredPrimaryAndHostReturnReachable() {
        val fixture = Fixture(presentation(measured()), authenticated = true)
        launch(fixture, width = 320, fontScale = 2f)
        val host = compose.onNodeWithTag("account-usage-fixture-host").fetchSemanticsNode().boundsInRoot
        val refresh = compose.onNodeWithTag("account-usage-refresh")
        refresh.performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        val refreshBounds = refresh.fetchSemanticsNode().boundsInRoot
        assertTrue(refreshBounds.left >= host.left); assertTrue(refreshBounds.right <= host.right)
        refresh.performClick()
        compose.onNodeWithTag("account-usage-read-status").assertTextEquals("正在读取")
        assertEquals(1, refreshes.get()); assertEquals(0, primaryActions.get())
        checkpoint("narrow-font2-update-reachable")
        compose.runOnIdle { fixture.loading = false }
        val primary = compose.onNodeWithTag("account-usage-primary")
        primary.performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            .assertTextEquals("一键连接").assertIsNotEnabled()
        val primaryBounds = primary.fetchSemanticsNode().boundsInRoot
        assertTrue(primaryBounds.left >= host.left); assertTrue(primaryBounds.right <= host.right)
        compose.onNodeWithTag("account-usage-used").assertTextEquals("已用 3 GB")
        compose.onNodeWithTag("account-usage-total").assertTextEquals("总量 50 GB")
        primary.performTouchInput { click() }
        assertEquals(0, primaryActions.get()); assertEquals(1, refreshes.get())
        compose.onNodeWithTag("account-usage-primary-hint").performScrollTo().assertIsDisplayed()
            .assertTextEquals("连接功能暂未开放")
        checkpoint("narrow-font2-unwired-primary-reachable")
        compose.onNodeWithTag("account-usage-fixture-return").performScrollTo()
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, returns.get()); assertEquals(0, primaryActions.get()); assertEquals(1, refreshes.get())
        checkpoint("narrow-font2-host-return-reachable")
    }

    /** Opt in with -e accountUsageScreenshots 1; no directories or screenshot writes by default. */
    private fun checkpoint(name: String) {
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.getString("accountUsageScreenshots") != "1") return
        require(name in CHECKPOINTS)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.filesDir,
            "feilian-qa/account-usage-card-v1/$evidenceRun")
        check(directory.mkdirs() || directory.isDirectory)
        val file = File(directory, "$name.png")
        check(!file.exists()) { "Preserve the earlier checkpoint; use a new accountUsageEvidenceRun" }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        Log.i("FeilianUsageQA", "checkpoint=$name file=${file.absolutePath}")
    }

    private val evidenceRun: String by lazy {
        (InstrumentationRegistry.getArguments().getString("accountUsageEvidenceRun")
            ?: "run-${System.currentTimeMillis()}").also {
            require(Regex("[A-Za-z0-9_-]{1,64}").matches(it))
        }
    }

    private companion object {
        val CHECKPOINTS = setOf(
            "guest-explicit-account-intent", "none-explicit-plans-intent", "initial-failure-retry-no-false-none",
            "measured-account-snapshot", "readonly-loading-no-repeat-or-connection",
            "failed-read-keeps-old-sample-retry-only", "narrow-font2-update-reachable",
            "narrow-font2-unwired-primary-reachable", "narrow-font2-host-return-reachable",
        )
    }
}
