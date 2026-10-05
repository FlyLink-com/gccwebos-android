package com.gccwebos.android

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.portal.*
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

/** Offline native UI smoke tests. No login, production requests or VPN permissions. */
class BrandUiTest {
    @get:Rule val compose = createComposeRule()
    private companion object {
        const val INITIAL_PROMPT = "离线题目 A：2 + 3 = ?"
        const val REFRESHED_PROMPT = "离线题目 B：8 − 1 = ?"
    }
    /** Only this contract-shaped in-memory challenge is allowed; every other request fails closed. */
    private class OfflineLoginChallengeFixture(private val holdRefresh: Boolean = false) {
        val requests = AtomicInteger()
        val elapsed = AtomicLong()
        var nextFailure: PortalFailure.Reason? = null
        val unexpectedRequests = CopyOnWriteArrayList<String>()
        val refreshRelease = CompletableDeferred<Unit>()
        lateinit var registration: RegistrationController
            private set
        fun registration(scope: CoroutineScope, account: AccountController): RegistrationController {
            val gateway = object : RegistrationGateway {
                override suspend fun requestEmailVerification(email: String): EmailVerificationReceipt {
                    unexpectedRequests += "registration/email-verification"
                    error("Missing legal policy must not dispatch even a synthetic email request")
                }
                override suspend fun register(input: RegistrationSubmission): RegistrationReceipt {
                    unexpectedRequests += "registration"
                    error("Missing legal policy must not dispatch a registration")
                }
            }
            return RegistrationController(scope, gateway, elapsed = { 0L },
                authGeneration = { account.authSessionGeneration },
                registrationAllowed = { !account.authenticated && account.factorReference == null }).also { registration = it }
        }
        fun account(scope: CoroutineScope): AccountController {
            val store = object : DeliveryStore {
                override fun read(): DeliveryRecord? = null
                override fun save(record: DeliveryRecord) = error("Offline challenge cannot save a delivery")
                override fun clear() = Unit
            }
            val wire = object : PortalTransport {
                override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                    if (path != "/api/portal/human-challenges") {
                        unexpectedRequests += path
                        error("Offline login fixture refuses authentication, email, commerce and every other route")
                    }
                    assertNull(session)
                    val submitted = JSONObject(requireNotNull(body))
                    assertEquals(setOf("scope"), submitted.keys().asSequence().toSet())
                    assertEquals("portal_login", submitted.getString("scope"))
                    val number = requests.incrementAndGet()
                    if (holdRefresh && number > 1) refreshRelease.await()
                    nextFailure?.let { reason ->
                        nextFailure = null
                        throw PortalFailure(reason)
                    }
                    val challenge = JSONObject().put("challengeRef", "human_fixture_$number")
                        .put("type", "math").put("prompt", if (number == 1) INITIAL_PROMPT else REFRESHED_PROMPT)
                        .put("expiresInSeconds", 180)
                    return WireResponse(201, JSONObject().put("challenge", challenge).toString())
                }
            }
            return AccountController(PortalRepository(wire, store,
                OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { elapsed.get() },
                subscription = SubscriptionSource { error("Offline login fixture cannot request a subscription") }), scope)
        }
    }
    private fun launch(fontScale: Float = 1f, signedIn: Boolean = true, width: Dp? = null,
                       challengeFixture: OfflineLoginChallengeFixture = OfflineLoginChallengeFixture(),
                       setup: AccountController.() -> Unit = {}): AccountController {
        lateinit var controller: AccountController
        compose.setContent {
            val density = LocalDensity.current
            val scope = rememberCoroutineScope()
            val account = remember {
                (if (signedIn) offlineUiAccount(scope) else challengeFixture.account(scope))
                    .apply {
                        authenticated = signedIn
                        // This display-only fixture owns no admitted node choices. Repository
                        // recreation shares the process registry, so revoke earlier test handles.
                        nodeSelection.invalidate()
                    }.apply(setup)
            }
            controller = account
            val registration = remember { challengeFixture.registration(scope, account) }
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                if (width != null) Box(Modifier.width(width).fillMaxHeight()) {
                    AppShell(account, autoLoadCommerce = false, registrationOverride = registration,
                        connectionControlState = ConnectionControlState(phase = ConnectionPhase.Unavailable))
                } else AppShell(account, autoLoadCommerce = false, registrationOverride = registration,
                    connectionControlState = ConnectionControlState(phase = ConnectionPhase.Unavailable))
            }
        }
        if (!signedIn) compose.onNodeWithTag("nav-Profile").performClick()
        return controller
    }
    private fun awaitReadOnlyConfiguration(state: AccountController) {
        // The library now reads its active group on IO; Compose idleness alone does not await it.
        compose.waitUntil(5_000) {
            !state.configurationLibrary.busy && !state.nodeSelection.busy &&
                state.configurationLibrary.snapshot.items.any { it.active }
        }
        compose.runOnIdle {
            val active = state.configurationLibrary.snapshot.items.single { it.active }
            assertEquals(ManualSourceKind.LegacyRemote, active.kind)
            assertTrue("Display summaries must not create node authority", state.nodeSelection.snapshot.choices.isEmpty())
            assertNull(state.nodeSelection.snapshot.selected)
        }
    }
    private fun assertQuestionPresentation(expected: String, enabled: Boolean = true) {
        compose.onAllNodesWithTag("login-challenge-prompt", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag("login-challenge-prompt", useUnmergedTree = true).assertTextEquals(expected)
        compose.onNodeWithTag("login-challenge-status", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("login-challenge-refresh", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("login-challenge-error", useUnmergedTree = true).assertDoesNotExist()
        listOf(INITIAL_PROMPT, REFRESHED_PROMPT, "验证题", "无网络", "换一道题").forEach { display ->
            compose.onAllNodesWithText(display, useUnmergedTree = true)
                .assertCountEquals(if (display == expected) 1 else 0)
        }
        listOf("机器人验证", "过期刷新重试", "刷新", "加载中…", "刷新重试", "正在处理，请稍候").forEach { caption ->
            compose.onAllNodesWithText(caption, useUnmergedTree = true).assertCountEquals(0)
        }
        assertQuestionButton(expected, enabled)
    }
    private fun assertQuestionButton(expected: String, enabled: Boolean = true) {
        val button = compose.onNodeWithTag("login-challenge")
            .assertContentDescriptionEquals(if (enabled) "$expected，点击更换验证题" else expected)
            .assertTextEquals(expected).assertHasClickAction().assertHeightIsAtLeast(48.dp)
        if (enabled) button.assertIsEnabled() else button.assertIsNotEnabled()
    }
    private fun assertCompactLoginHasNoMoreOrReset() {
        listOf("login-more", "account-reset", "reset-confirm", "reset-cancel").forEach { tag ->
            compose.onNodeWithTag(tag).assertDoesNotExist()
        }
        listOf("⋯", "清除本地订阅并重置登录",
            "不保存密码。退出账户保留本地订阅；清除仅影响本机，不撤销服务器凭据。").forEach { text ->
            compose.onAllNodesWithText(text, useUnmergedTree = true).assertCountEquals(0)
        }
    }
    private fun awaitAutomaticQuestion(state: AccountController, fixture: OfflineLoginChallengeFixture) {
        compose.waitUntil(5_000) { state.challenge?.prompt == INITIAL_PROMPT && !state.challengeLoading }
        assertQuestionPresentation(INITIAL_PROMPT)
        compose.runOnIdle {
            assertEquals(1, fixture.requests.get())
            assertTrue(fixture.unexpectedRequests.isEmpty())
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "ui-evidence")
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use { stream ->
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }
    @Test fun fourPrimaryDestinationsAndDisabledProductionVpn() {
        launch()
        compose.onNodeWithTag("vpn-connect").assertIsNotEnabled()
        capture("connect")
        listOf("Lines", "Plans", "Profile").forEach { page ->
            compose.onNodeWithTag("nav-$page").performClick()
            compose.onNodeWithTag("page-$page").assertIsDisplayed()
            capture(page.lowercase())
        }
        compose.onNodeWithTag("mine-security").performScrollTo().performClick()
        compose.onNodeWithTag("account-reset").performScrollTo().assertIsDisplayed()
    }
    @Test fun signedOutGateOmitsMoreAndResetAndRetainsTypedAccount() {
        val fixture = OfflineLoginChallengeFixture()
        val state = launch(signedIn = false, challengeFixture = fixture)
        awaitAutomaticQuestion(state, fixture)
        compose.onNodeWithTag("login-screen").assertIsDisplayed()
        compose.onNodeWithTag("nav-Connect").assertExists()
        compose.onNodeWithTag("login-account").performScrollTo().performTextClearance()
        compose.onNodeWithTag("login-account").performTextInput("ui-fixture@example.invalid")
        capture("login")
        assertCompactLoginHasNoMoreOrReset()
        compose.onNodeWithTag("login-account").performScrollTo().assertTextContains("ui-fixture@example.invalid")
        compose.runOnIdle {
            assertEquals("ui-fixture@example.invalid", state.account)
            assertEquals(1, fixture.requests.get())
            assertTrue(fixture.unexpectedRequests.isEmpty())
        }
    }
    @Test fun compactLoginKeepsQuestionVisibleAndRegistrationSwitchesInsideApp() {
        val fixture = OfflineLoginChallengeFixture()
        val state = launch(signedIn = false, width = 360.dp, challengeFixture = fixture)
        awaitAutomaticQuestion(state, fixture)
        compose.onNodeWithTag("plain-login-layout").assertIsDisplayed()
        compose.onNodeWithTag("login-welcome").assertTextEquals("欢迎回来").assertIsDisplayed()
        compose.onAllNodesWithText("邮箱登录", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("login-hero").assertDoesNotExist()
        compose.onNodeWithTag("login-robot").assertDoesNotExist()
        listOf("邮箱", "密码", "输入算术题答案").forEach { placeholder ->
            compose.onAllNodesWithText(placeholder, useUnmergedTree = true).assertCountEquals(1)
        }
        listOf("login-account", "login-password", "login-answer").forEach { tag ->
            compose.onNodeWithTag(tag).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))
        }
        compose.onNodeWithTag("login-register").assertTextEquals("注册").assertIsDisplayed()
        listOf("Google", "Apple", "Facebook", "微信登录").forEach { provider ->
            compose.onAllNodesWithText(provider, substring = true).assertCountEquals(0)
        }
        compose.onNodeWithTag("account-panel").assertDoesNotExist()
        compose.onNodeWithTag("login-email-hint").assertDoesNotExist()
        compose.onNodeWithTag("login-password-hint").assertDoesNotExist()
        compose.onAllNodesWithText("获取题目", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("login-challenge-prompt", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("login-answer").assertContentDescriptionEquals("输入算术题答案")
            .assertIsEnabled().assertIsDisplayed()
        compose.onNodeWithTag("login-challenge").assertIsEnabled().assertIsDisplayed()
        val answerBounds = compose.onNodeWithTag("login-answer").fetchSemanticsNode().boundsInRoot
        listOf("login-challenge-prompt", "login-challenge").forEach { tag ->
            val rightBounds = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("The question button stays right of the answer input", rightBounds.left >= answerBounds.right)
        }
        compose.onNodeWithTag("login-submit").assertIsNotEnabled().assertIsDisplayed()
        val submitBounds = compose.onNodeWithTag("login-submit").fetchSemanticsNode().boundsInRoot
        val registerBounds = compose.onNodeWithTag("login-register").fetchSemanticsNode().boundsInRoot
        assertTrue("Register stays below the login CTA at its right edge", registerBounds.top >= submitBounds.bottom &&
            registerBounds.right >= submitBounds.right - 1f && registerBounds.left > submitBounds.center.x)
        assertCompactLoginHasNoMoreOrReset()
        capture("compact-login-auto-question")
        compose.runOnIdle {
            assertFalse("The synthetic question cannot authenticate", state.authenticated)
            assertFalse("No saved-session restoration was requested", state.restoring)
            assertFalse(state.foregroundCheckPending)
            assertNull(state.factorReference)
            assertTrue(fixture.unexpectedRequests.isEmpty())
        }
        compose.onNodeWithTag("login-screen").assertIsDisplayed()
        compose.onNodeWithTag("registration-form").assertDoesNotExist()
        compose.onNodeWithTag("login-account").performScrollTo().assertIsDisplayed().performTextClearance()
        compose.onNodeWithTag("login-account").performTextInput("ui-fixture@example.invalid")
        compose.onNodeWithTag("login-password").performTextInput("offline-fixture-password")
        compose.onNodeWithTag("login-password").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        // Filling a field removes its placeholder, while its accessible name remains.
        compose.onNodeWithTag("login-answer").performTextReplacement("未提交的合成答案")
        listOf("邮箱", "密码", "输入算术题答案", "登录邮箱", "验证答案", "邮箱登录").forEach { label ->
            compose.onAllNodesWithText(label, useUnmergedTree = true).assertCountEquals(0)
        }
        compose.onNodeWithTag("login-account").assertContentDescriptionEquals("邮箱")
        compose.onNodeWithTag("login-password").assertContentDescriptionEquals("密码")
        compose.onNodeWithTag("login-answer").assertContentDescriptionEquals("输入算术题答案")
        compose.onNodeWithTag("login-register").performScrollTo().performClick()
        compose.onNodeWithTag("registration-form").assertIsDisplayed()
        compose.onNodeWithTag("login-welcome").assertDoesNotExist()
        compose.onNodeWithTag("login-challenge").assertDoesNotExist()
        compose.onNodeWithTag("registration-email").assertTextContains("ui-fixture@example.invalid")
        compose.onNodeWithTag("registration-password").performScrollTo().performTextInput("RegisterFixture123!")
        compose.onNodeWithTag("registration-confirm-password").performScrollTo().performTextInput("RegisterFixture123!")
        compose.onNodeWithTag("registration-code").performScrollTo().performTextInput("123456")
        compose.onNodeWithTag("registration-login").performScrollTo().performClick()
        compose.waitUntil(5_000) { state.challenge?.prompt == REFRESHED_PROMPT && !state.challengeLoading }
        compose.onNodeWithTag("registration-form").assertDoesNotExist()
        assertQuestionPresentation(REFRESHED_PROMPT)
        compose.onNodeWithTag("login-account").performScrollTo().assertTextContains("ui-fixture@example.invalid")
        compose.onNodeWithTag("login-submit").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(2, fixture.requests.get())
            assertTrue(fixture.unexpectedRequests.isEmpty())
            assertEquals("", state.password)
            assertEquals("ui-fixture@example.invalid", fixture.registration.email)
            assertEquals("", fixture.registration.password)
            assertEquals("", fixture.registration.confirmPassword)
            assertEquals("", fixture.registration.verificationCode)
            assertTrue(!fixture.registration.legalAccepted)
            assertTrue(!state.authenticated)
        }
        capture("compact-login-app-registration-return")
    }
    @Test fun compactLoginRefreshClearsOldAnswerAndKeepsOnlyOnePendingQuestionRequest() {
        val fixture = OfflineLoginChallengeFixture(holdRefresh = true)
        val state = launch(signedIn = false, width = 320.dp, challengeFixture = fixture)
        awaitAutomaticQuestion(state, fixture)
        compose.onNodeWithTag("login-account").performTextReplacement("ui-fixture@example.invalid")
        compose.onNodeWithTag("login-password").performTextReplacement("offline-fixture-password")
        // This deliberately arbitrary synthetic input is never submitted as a login answer.
        compose.onNodeWithTag("login-answer").performTextReplacement("未提交的旧答案")
        compose.onNodeWithTag("login-challenge").performScrollTo().performClick()
        compose.waitUntil(5_000) { fixture.requests.get() == 2 && state.challengeLoading }
        assertQuestionPresentation("验证题", enabled = false)
        compose.onNodeWithTag("login-challenge-prompt", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithTag("login-challenge").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithTag("login-answer").assertIsNotEnabled()
        compose.onNodeWithTag("login-submit").assertIsNotEnabled()
        compose.runOnIdle {
            assertNull(state.challenge)
            assertEquals("", state.answer)
            assertEquals("ui-fixture@example.invalid", state.account)
            assertEquals("offline-fixture-password", state.password)
            assertEquals(2, fixture.requests.get())
            assertTrue(fixture.unexpectedRequests.isEmpty())
            fixture.refreshRelease.complete(Unit)
        }
        compose.waitUntil(5_000) { state.challenge?.prompt == REFRESHED_PROMPT && !state.challengeLoading }
        assertQuestionPresentation(REFRESHED_PROMPT)
        compose.onNodeWithTag("login-answer").assertIsEnabled()
        compose.onNodeWithTag("login-challenge").assertIsEnabled()
        compose.onNodeWithTag("login-submit").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals("", state.answer)
            assertEquals(2, fixture.requests.get())
            assertTrue(fixture.unexpectedRequests.isEmpty())
        }
        capture("compact-login-refreshed-question")
    }
    @Test fun minimalLoginQuestionShowsOneDynamicLabelAndSupportsRetry() {
        val fixture = OfflineLoginChallengeFixture()
        val state = launch(signedIn = false, width = 360.dp, challengeFixture = fixture)
        awaitAutomaticQuestion(state, fixture)
        compose.runOnIdle {
            fixture.elapsed.set(requireNotNull(state.challenge).expiresElapsed + 1)
            state.onForeground() // Component-local fake clock check, not a phone foreground action.
            assertTrue(state.challengeExpired)
            assertEquals(1, fixture.requests.get())
        }
        assertQuestionPresentation("换一道题")
        compose.onNodeWithTag("login-challenge").assertIsEnabled()
        var expectedRequests = 1
        val failures = listOf(
            PortalFailure.Reason.NETWORK to "无网络",
            PortalFailure.Reason.PROTOCOL to "换一道题",
            PortalFailure.Reason.RATE_LIMITED to "换一道题",
            PortalFailure.Reason.UNAVAILABLE to "换一道题",
        )
        for ((reason, expected) in failures) {
            compose.runOnIdle { fixture.nextFailure = reason }
            compose.onNodeWithTag("login-challenge").performScrollTo().performClick()
            expectedRequests++
            compose.waitUntil(5_000) {
                fixture.requests.get() == expectedRequests && !state.challengeLoading && state.challengeFailureReason == reason
            }
            assertQuestionPresentation(expected)
            compose.onNodeWithTag("login-challenge").assertIsEnabled()
            compose.onNodeWithTag("login-answer").assertIsNotEnabled()
        }
        compose.onNodeWithTag("login-challenge").performScrollTo().performClick()
        expectedRequests++
        compose.waitUntil(5_000) { fixture.requests.get() == expectedRequests && !state.challengeLoading && state.challenge != null }
        assertQuestionPresentation(REFRESHED_PROMPT)
        compose.onNodeWithTag("login-answer").assertIsEnabled()
        compose.runOnIdle {
            assertTrue(fixture.unexpectedRequests.isEmpty())
            assertTrue(!state.authenticated)
            assertEquals("", state.answer)
        }
    }
    @Test fun compactLoginKeepsRawPasswordAndUsesOnlyServerNonemptyRule() {
        val fixture = OfflineLoginChallengeFixture()
        val state = launch(signedIn = false, challengeFixture = fixture)
        awaitAutomaticQuestion(state, fixture)
        compose.runOnIdle {
            state.account = "ui-fixture@example.invalid"
            // Only an arbitrary synthetic field value is tested; login is never clicked.
            state.answer = "未提交的合成答案"
        }
        val examples = listOf("" to false, " \t" to false, "\uFEFF\u00A0" to false,
            "a" to true, "abcdefg" to true, "x".repeat(161) to true,
            " \uFEFFinside value\uFEFF " to true, "\u001C\u001F" to true)
        for ((password, enabled) in examples) {
            compose.onNodeWithTag("login-password").performScrollTo().performTextReplacement(password)
            compose.runOnIdle { assertEquals(password, state.password) }
            if (enabled) compose.onNodeWithTag("login-submit").assertIsEnabled()
            else compose.onNodeWithTag("login-submit").assertIsNotEnabled()
        }
        compose.runOnIdle {
            assertEquals(1, fixture.requests.get())
            assertTrue(fixture.unexpectedRequests.isEmpty())
        }
    }
    @Test fun compactLoginAtNarrowWidthAndDoubledFontKeepsActionsReachable() {
        val fixture = OfflineLoginChallengeFixture()
        val state = launch(fontScale = 2f, signedIn = false, width = 320.dp,
            challengeFixture = fixture)
        awaitAutomaticQuestion(state, fixture)
        compose.onNodeWithTag("account-panel").assertDoesNotExist()
        compose.onAllNodesWithText("获取题目", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("login-challenge-prompt", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("login-account").performScrollTo().performClick().assertIsFocused()
        compose.onNodeWithTag("login-password").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("login-answer").performScrollTo().assertIsEnabled().assertIsDisplayed()
        compose.onNodeWithTag("login-challenge").performScrollTo().assertIsDisplayed()
        assertQuestionButton(INITIAL_PROMPT)
        compose.onNodeWithTag("login-submit").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("login-register").performScrollTo().assertIsDisplayed()
        capture("compact-login-auto-question-narrow-font-200-actions")
        assertCompactLoginHasNoMoreOrReset()
        compose.onNodeWithTag("login-account").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, fixture.requests.get())
            assertTrue(fixture.unexpectedRequests.isEmpty())
        }
        capture("compact-login-auto-question-narrow-font-200")
    }
    @Test fun doubledFontKeepsNavigationAndFormActionsReachable() {
        launch(2f)
        compose.onNodeWithTag("vpn-connect").performScrollTo().assertIsDisplayed().assertTextEquals("一键连接").assertIsNotEnabled()
        compose.onNodeWithTag("account-usage-remaining").assertTextEquals("暂未获取")
        capture("connect-font-200")
        compose.onNodeWithTag("nav-Profile").performClick()
        compose.onNodeWithTag("mine-security").performScrollTo().performClick()
        compose.onNodeWithTag("account-panel").performScrollTo().assertIsDisplayed()
        capture("profile-font-200")
        compose.onNodeWithTag("account-reset").performScrollTo().performClick()
        compose.onNodeWithTag("reset-cancel").assertIsDisplayed().performClick()
        compose.onNodeWithTag("nav-Connect").performClick()
        compose.onNodeWithTag("page-Connect").assertIsDisplayed()
    }
    @Test fun smallerFontStillRendersFourAccessibleTabs() {
        launch(.85f)
        compose.onNodeWithTag("vpn-connect").assertTextEquals("一键连接").assertIsNotEnabled()
        compose.onNodeWithTag("account-usage-remaining").assertTextEquals("暂未获取")
        capture("connect-font-85")
        listOf("Connect", "Lines", "Plans", "Profile").forEach { page ->
            compose.onNodeWithTag("nav-$page").assertIsDisplayed()
        }
    }
    @Test fun compactConnectionOmitsWalletAndKeepsTruthfulDisabledNodes() {
        val state = launch()
        compose.onNodeWithTag("connection-status").assertTextEquals("未连接")
        // This old rendering fixture has no accepted usage owner; it must remain unknown.
        compose.onNodeWithTag("vpn-connect").assertTextEquals("一键连接").assertIsNotEnabled()
        compose.onNodeWithTag("account-usage-remaining").assertTextEquals("暂未获取")
        compose.onNodeWithTag("connection-power").assertDoesNotExist()
        compose.onNodeWithTag("connection-welcome").assertExists()
        compose.onNodeWithTag("account-usage-card").assertExists()
        compose.onNodeWithTag("subscription-waterdrop").assertDoesNotExist()
        compose.onNodeWithTag("resource-card").assertDoesNotExist()
        compose.onNodeWithTag("wallet-balance").assertDoesNotExist()
        compose.onNodeWithTag("local-subscription-title").assertDoesNotExist()
        capture("compact-connect")
        compose.onNodeWithTag("connection-nodes").performScrollTo().performClick()
        awaitReadOnlyConfiguration(state)
        compose.onNodeWithTag("subscription-node-name-0").assertTextEquals("合成验收线路 Alpha")
        compose.onNodeWithTag("subscription-node-details-0").assertTextEquals("SS")
        compose.runOnIdle { assertEquals("ss", state.nodes[0].protocol); assertTrue(state.nodes[0].supported) }
        compose.onNodeWithTag("subscription-node-0").assert(SemanticsMatcher("Node row has no production click action") {
            !it.config.contains(SemanticsActions.OnClick)
        })
        compose.onNodeWithTag("nodes-unavailable").assertDoesNotExist()
        compose.onNodeWithTag("nodes-connection-status").assertDoesNotExist()
        compose.onNodeWithText("已连接", substring = false).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(state.nodeSelection.snapshot.choices.isEmpty())
            assertNull(state.nodeSelection.snapshot.selected)
        }
        capture("compact-nodes")
    }
    @Test fun unverifiedReadAndStorageFailuresCannotBecomeExpiryOrEnableConnectionWhileBusy() {
        lateinit var state: AccountController
        launch(setup = {
            state = this
            localSubscription = LocalSubscriptionSnapshot(LocalSubscriptionStatus.RETRY_REQUIRED)
            resource = (resource as Resource.Available).copy(validUntil = java.time.Instant.EPOCH)
        })
        compose.onNodeWithTag("local-subscription-title").assertDoesNotExist()
        compose.onNodeWithTag("vpn-connect").performScrollTo().assertTextEquals("一键连接").assertIsNotEnabled()
        compose.onNodeWithTag("account-usage-remaining").assertTextEquals("暂未获取")
        compose.runOnIdle { assertEquals(LocalSubscriptionStatus.RETRY_REQUIRED, state.localSubscription.status) }
        compose.onNodeWithTag("expired-subscription-renew").assertDoesNotExist()
        compose.onNodeWithTag("portal-expired-renew").assertDoesNotExist()
        compose.runOnIdle { state.localSubscription = LocalSubscriptionSnapshot(LocalSubscriptionStatus.STORAGE_ERROR) }
        compose.onNodeWithTag("local-subscription-title").assertDoesNotExist()
        compose.onNodeWithTag("vpn-connect").assertTextEquals("一键连接").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(LocalSubscriptionStatus.STORAGE_ERROR, state.localSubscription.status) }
        compose.onNodeWithTag("expired-subscription-renew").assertDoesNotExist()
        compose.onNodeWithTag("portal-expired-renew").assertDoesNotExist()
        compose.runOnIdle {
            state.nodes = emptyList()
            state.busy = true
            state.localSubscription = LocalSubscriptionSnapshot(LocalSubscriptionStatus.RETRY_REQUIRED, hasCapability = true)
        }
        compose.onNodeWithTag("local-subscription-refresh").assertDoesNotExist()
        compose.onNodeWithTag("vpn-connect").assertIsNotEnabled()
        compose.onNodeWithTag("connection-nodes").performScrollTo().performClick()
        compose.onNodeWithTag("node-selection-status").assertDoesNotExist()
        compose.onNodeWithTag("nodes-refresh").assertDoesNotExist()
        compose.onNodeWithTag("subscription-node-0").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(state.busy)
            assertTrue(state.nodeSelection.snapshot.choices.isEmpty())
            assertNull(state.nodeSelection.snapshot.selected)
        }
    }
    @Test fun narrowDoubledFontKeepsCircleAndLongDuplicateNodesReachable() {
        val name = "合成验收重复长名称节点：仅用于离线界面可达性"
        val state = launch(fontScale = 2f, width = 320.dp, setup = {
            nodes = listOf(
                com.gccwebos.android.vpn.SafeProxyConfig.NodeSummary(0, name, "ss", true),
                com.gccwebos.android.vpn.SafeProxyConfig.NodeSummary(1, name, "vmess", false),
            )
        })
        compose.onNodeWithTag("vpn-connect").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("local-subscription-title").assertDoesNotExist()
        capture("compact-connect-narrow-font-200")
        compose.onNodeWithTag("connection-nodes").performScrollTo().performClick()
        awaitReadOnlyConfiguration(state)
        compose.onNodeWithTag("subscription-node-name-0").performScrollTo().assertTextEquals(name)
        compose.onNodeWithTag("subscription-node-name-1").performScrollTo().assertTextEquals(name)
        compose.onNodeWithTag("subscription-node-details-1").performScrollTo().assertTextEquals("VMESS")
        compose.runOnIdle { assertEquals("vmess", state.nodes[1].protocol); assertFalse(state.nodes[1].supported) }
        compose.onNodeWithTag("subscription-node-1").assert(SemanticsMatcher("Unsupported row has no production click action") {
            !it.config.contains(SemanticsActions.OnClick)
        })
        compose.onNodeWithTag("nodes-unavailable").assertDoesNotExist()
        compose.onNodeWithTag("nodes-connection-status").assertDoesNotExist()
        compose.onNodeWithText("已连接", substring = false).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(state.nodeSelection.snapshot.choices.isEmpty())
            assertNull(state.nodeSelection.snapshot.selected)
        }
        capture("compact-nodes-narrow-font-200")
    }
    @Test fun totpCancelKeepsSubscriptionAndResetStillRequiresConfirmation() {
        class MemoryStore : DeliveryStore {
            var value: DeliveryRecord? = DeliveryRecord("fixture", "binding", "resource", "operation")
            override fun read() = value
            override fun save(record: DeliveryRecord) { value = record }
            override fun clear() { value = null }
        }
        val store = MemoryStore()
        lateinit var state: AccountController
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember {
                val wire = object : PortalTransport {
                    override suspend fun request(path: String, body: String?, session: Secret?): WireResponse =
                        throw AssertionError("Offline UI test must not send a request")
                }
                AccountController(PortalRepository(wire, store,
                    OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L }), scope).apply {
                    factorReference = "fixture-totp"
                }
            }
            state = controller
            GccTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AccountPanel(controller) } }
        }
        compose.onNodeWithTag("account-status").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.runOnIdle { state.error = "离线测试错误" }
        compose.onNodeWithTag("account-error").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onAllNodesWithText("离线测试错误").assertCountEquals(1)
        compose.onNodeWithTag("totp-cancel").performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertNull(state.factorReference); assertNotNull(store.value) }
        compose.onNodeWithTag("account-reset").performScrollTo().performClick()
        compose.onNodeWithTag("reset-cancel").performClick()
        compose.runOnIdle { assertNotNull(store.value) }
        compose.onNodeWithTag("account-reset").performScrollTo().performClick()
        compose.onNodeWithTag("reset-confirm").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertNull(store.value) }
    }

}
