package com.gccwebos.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.gccwebos.android.portal.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** In-memory UI contracts only: no HTTP, email, real registration, login, orders or VPN. */
class RegistrationUiTest {
    @get:Rule val compose = createComposeRule()
    private companion object {
        const val EMAIL = "registration-ui@example.invalid"
        const val PASSWORD = "Fixture123!"
        const val CODE = "123456"
    }
    private class OfflineGateway(private val holdVerification: Boolean, private val holdRegistration: Boolean) : RegistrationGateway {
        val verificationCalls = AtomicInteger()
        val registrationCalls = AtomicInteger()
        val requestedEmails = CopyOnWriteArrayList<String>()
        val submissions = CopyOnWriteArrayList<RegistrationSubmission>()
        val verificationRelease = CompletableDeferred<Unit>()
        val registrationRelease = CompletableDeferred<Unit>()
        override suspend fun requestEmailVerification(email: String): EmailVerificationReceipt {
            val number = verificationCalls.incrementAndGet()
            requestedEmails += email
            if (holdVerification) verificationRelease.await()
            return EmailVerificationReceipt("verification_ui_fixture_$number", 600L)
        }
        override suspend fun register(input: RegistrationSubmission): RegistrationReceipt {
            registrationCalls.incrementAndGet()
            submissions += input
            if (holdRegistration) registrationRelease.await()
            return RegistrationReceipt("user_ui_fixture")
        }
    }
    private class OfflineAccount {
        val questionCalls = AtomicInteger()
        val unexpectedRequests = CopyOnWriteArrayList<String>()
        fun controller(scope: CoroutineScope): AccountController {
            val store = object : DeliveryStore {
                override fun read(): DeliveryRecord? = null
                override fun save(record: DeliveryRecord) = error("Registration UI cannot save delivery")
                override fun clear() = Unit
            }
            val transport = object : PortalTransport {
                override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                    if (path != "/api/portal/human-challenges") {
                        unexpectedRequests += path
                        error("Registration UI refuses authentication and every other Portal request")
                    }
                    assertNull(session)
                    val input = JSONObject(requireNotNull(body))
                    assertEquals(setOf("scope"), input.keys().asSequence().toSet())
                    assertEquals("portal_login", input.getString("scope"))
                    val number = questionCalls.incrementAndGet()
                    val challenge = JSONObject().put("challengeRef", "human_registration_ui_$number")
                        .put("type", "math").put("prompt", "离线登录题目 $number：2 + 3 = ?")
                        .put("expiresInSeconds", 180)
                    return WireResponse(201, JSONObject().put("challenge", challenge).toString())
                }
            }
            return AccountController(PortalRepository(transport, store,
                OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L },
                subscription = SubscriptionSource { error("Registration UI cannot request a subscription") }), scope)
        }
    }
    private class Screen(val account: AccountController, val registration: RegistrationController,
                         val gateway: OfflineGateway, val accountFixture: OfflineAccount,
                         val documents: CopyOnWriteArrayList<String>)
    private fun fixtureLegal() = RegistrationLegalPolicy("https://portal.invalid/terms", "https://portal.invalid/privacy",
        "fixture-terms-v1", "fixture-privacy-v1")
    private fun launch(legal: RegistrationLegalPolicy? = null, fontScale: Float = 1f,
                       holdVerification: Boolean = false, holdRegistration: Boolean = false): Screen {
        val accountFixture = OfflineAccount()
        val gateway = OfflineGateway(holdVerification, holdRegistration)
        val documents = CopyOnWriteArrayList<String>()
        lateinit var screen: Screen
        compose.setContent {
            val density = LocalDensity.current
            val scope = rememberCoroutineScope()
            val account = remember { accountFixture.controller(scope) }
            val registration = remember {
                RegistrationController(scope, gateway, legal, elapsed = { 0L },
                    authGeneration = { account.authSessionGeneration },
                    registrationAllowed = { !account.authenticated && account.factorReference == null })
            }
            screen = Screen(account, registration, gateway, accountFixture, documents)
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Box(Modifier.width(320.dp).fillMaxHeight()) {
                    AppShell(account, autoLoadCommerce = false, registrationOverride = registration,
                        registrationOpener = { documents += it })
                }
            }
        }
        compose.onNodeWithTag("nav-Profile").performClick()
        compose.waitUntil(5_000) { accountFixture.questionCalls.get() == 1 && !screen.account.challengeLoading }
        compose.onNodeWithTag("login-register").performScrollTo().performClick()
        compose.onNodeWithTag("registration-form").assertIsDisplayed()
        return screen
    }
    private fun fillFields() {
        compose.onNodeWithTag("registration-email").performScrollTo().performTextReplacement(EMAIL)
        compose.onNodeWithTag("registration-password").performScrollTo().performTextReplacement(PASSWORD)
        compose.onNodeWithTag("registration-confirm-password").performScrollTo().performTextReplacement(PASSWORD)
    }
    private fun acceptConfiguredLegal() {
        compose.onNodeWithTag("registration-legal").performScrollTo().assertIsEnabled().performClick().assertIsOn()
    }
    private fun assertNoAccountAuthentication(screen: Screen) {
        compose.runOnIdle {
            assertFalse(screen.account.authenticated)
            assertNull(screen.account.authenticatedAccountId)
            assertTrue(screen.accountFixture.unexpectedRequests.isEmpty())
            assertEquals(1, screen.accountFixture.questionCalls.get())
        }
        compose.onNodeWithTag("nav-Connect").assertExists()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "ui-evidence")
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun defaultRegistrationHasNecessaryFieldsAndMissingLegalSendsNothing() {
        val screen = launch()
        listOf("registration-email", "registration-password", "registration-confirm-password", "registration-code",
            "registration-legal", "registration-send-code", "registration-submit", "registration-login").forEach {
            compose.onNodeWithTag(it).assertIsDisplayed()
        }
        compose.onNodeWithTag("registration-password").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithTag("registration-confirm-password").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithTag("registration-legal").assertIsNotEnabled().assertIsOff()
        compose.onNodeWithText("服务条款暂不可用").assertIsDisplayed()
        compose.onNodeWithTag("login-answer").assertDoesNotExist()
        compose.onNodeWithTag("login-challenge").assertDoesNotExist()
        compose.onNodeWithTag("login-challenge-prompt").assertDoesNotExist()
        capture("registration-default-missing-legal")
        fillFields()
        compose.onNodeWithTag("registration-code").performScrollTo().performTextReplacement(CODE)
        compose.onNodeWithTag("registration-send-code").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("registration-error").performScrollTo()
            .assertTextEquals("注册条款尚未配置，暂不可发送验证码或注册。")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithTag("registration-submit").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(0, screen.gateway.verificationCalls.get())
            assertEquals(0, screen.gateway.registrationCalls.get())
            assertNull(screen.registration.receipt)
        }
        assertNoAccountAuthentication(screen)
        capture("registration-missing-legal-local-error")
    }
    @Test fun registrationAtNarrowWidthAndDoubledFontKeepsNecessaryActionsReachable() {
        val screen = launch(fontScale = 2f)
        listOf("registration-email", "registration-password", "registration-confirm-password", "registration-code",
            "registration-send-code", "registration-legal", "registration-submit", "registration-login").forEach {
            compose.onNodeWithTag(it).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("registration-legal").assertIsNotEnabled()
        compose.onNodeWithTag("login-challenge").assertDoesNotExist()
        capture("registration-narrow-font-200-actions")
        compose.onNodeWithTag("registration-email").performScrollTo().performClick().assertIsFocused()
        capture("registration-narrow-font-200-fields")
        compose.runOnIdle {
            assertEquals(0, screen.gateway.verificationCalls.get())
            assertEquals(0, screen.gateway.registrationCalls.get())
        }
        assertNoAccountAuthentication(screen)
    }
    @Test fun configuredFixtureGetsOneOtpAndChangingEmailDoesNotSendAnother() {
        val screen = launch(legal = fixtureLegal(), holdVerification = true)
        fillFields()
        acceptConfiguredLegal()
        compose.onNodeWithTag("registration-send-code").performScrollTo().performClick()
        compose.waitUntil(5_000) { screen.gateway.verificationCalls.get() == 1 && screen.registration.verificationLoading }
        compose.onNodeWithTag("registration-send-code").assertTextEquals("获取").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithTag("registration-submit").assertTextEquals("注册").assertIsNotEnabled()
        compose.onNodeWithTag("registration-loading").assertDoesNotExist()
        compose.onNodeWithText("发送中…").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, screen.gateway.verificationCalls.get())
            assertEquals(listOf(EMAIL), screen.gateway.requestedEmails)
            screen.gateway.verificationRelease.complete(Unit)
        }
        compose.waitUntil(5_000) { !screen.registration.verificationLoading && screen.registration.verificationRemainingSeconds == 600L }
        compose.onNodeWithTag("registration-code").performScrollTo().performTextReplacement(CODE)
        capture("registration-synthetic-otp-ready")
        compose.onNodeWithTag("registration-email").performScrollTo().performTextReplacement("another-ui@example.invalid")
        compose.runOnIdle {
            assertEquals("", screen.registration.verificationCode)
            assertEquals(0L, screen.registration.verificationRemainingSeconds)
            assertEquals(1, screen.gateway.verificationCalls.get())
            assertEquals(0, screen.gateway.registrationCalls.get())
        }
        assertNoAccountAuthentication(screen)
    }
    @Test fun configuredFixtureReceiptDoesNotAuthenticateOrReplayRegistration() {
        val legal = fixtureLegal()
        val screen = launch(legal = legal, holdRegistration = true)
        fillFields()
        acceptConfiguredLegal()
        compose.onNodeWithText("服务条款").performScrollTo().performClick()
        compose.onNodeWithText("隐私政策").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(legal.termsUrl, legal.privacyUrl), screen.documents) }
        compose.onNodeWithTag("registration-send-code").performScrollTo().performClick()
        compose.waitUntil(5_000) { !screen.registration.verificationLoading && screen.registration.verificationRemainingSeconds > 0 }
        compose.onNodeWithTag("registration-code").performScrollTo().performTextReplacement(CODE)
        compose.onNodeWithTag("registration-submit").performScrollTo().performClick()
        compose.waitUntil(5_000) { screen.gateway.registrationCalls.get() == 1 && screen.registration.registering }
        compose.onNodeWithTag("registration-submit").assertTextEquals("注册").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithTag("registration-loading").assertDoesNotExist()
        compose.onNodeWithText("提交中…").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, screen.gateway.registrationCalls.get())
            val submitted = screen.gateway.submissions.single()
            assertEquals(EMAIL, submitted.email); assertEquals(PASSWORD, submitted.password)
            assertSame(legal, submitted.legalPolicy)
            assertEquals("verification_ui_fixture_1", submitted.verificationRef); assertEquals(CODE, submitted.code)
            assertEquals("", screen.registration.password); assertEquals("", screen.registration.confirmPassword)
            assertEquals("", screen.registration.verificationCode)
            screen.gateway.registrationRelease.complete(Unit)
        }
        compose.waitUntil(5_000) { screen.registration.receipt != null && !screen.registration.registering }
        compose.onNodeWithTag("registration-success").assertTextEquals("注册成功，请登录")
        assertNoAccountAuthentication(screen)
        capture("registration-synthetic-receipt-not-authenticated")
        compose.onNodeWithTag("registration-login").performScrollTo().performClick()
        compose.waitUntil(5_000) { screen.accountFixture.questionCalls.get() == 2 && !screen.account.challengeLoading && screen.account.challenge != null }
        compose.onNodeWithTag("login-account").performScrollTo().assertTextContains(EMAIL)
        compose.runOnIdle {
            assertEquals(1, screen.gateway.registrationCalls.get())
            assertEquals(1, screen.gateway.verificationCalls.get())
            assertFalse(screen.account.authenticated); assertNull(screen.account.authenticatedAccountId)
            assertTrue(screen.accountFixture.unexpectedRequests.isEmpty())
            assertEquals("", screen.account.password); assertNull(screen.registration.receipt)
        }
    }
    @Test fun switchingBackKeepsEmailClearsRegistrationSecretsAndGetsOneNewQuestion() {
        val screen = launch(legal = fixtureLegal())
        fillFields()
        compose.onNodeWithTag("registration-code").performScrollTo().performTextReplacement(CODE)
        acceptConfiguredLegal()
        compose.onNodeWithTag("registration-login").performScrollTo().performClick()
        compose.waitUntil(5_000) { screen.accountFixture.questionCalls.get() == 2 && !screen.account.challengeLoading && screen.account.challenge != null }
        compose.onNodeWithTag("registration-form").assertDoesNotExist()
        compose.onNodeWithTag("login-account").performScrollTo().assertTextContains(EMAIL)
        compose.runOnIdle {
            assertEquals(2, screen.accountFixture.questionCalls.get())
            assertEquals(EMAIL, screen.registration.email)
            assertEquals("", screen.registration.password); assertEquals("", screen.registration.confirmPassword)
            assertEquals("", screen.registration.verificationCode); assertFalse(screen.registration.legalAccepted)
            assertEquals("", screen.account.password); assertEquals("", screen.account.answer)
            assertEquals(0, screen.gateway.verificationCalls.get()); assertEquals(0, screen.gateway.registrationCalls.get())
            assertFalse(screen.account.authenticated); assertTrue(screen.accountFixture.unexpectedRequests.isEmpty())
        }
        compose.onNodeWithTag("login-register").performScrollTo().performClick()
        compose.onNodeWithTag("registration-legal").performScrollTo().assertIsOff()
        compose.runOnIdle {
            assertEquals("", screen.registration.password); assertEquals("", screen.registration.verificationCode)
            assertEquals(2, screen.accountFixture.questionCalls.get())
        }
    }
}
