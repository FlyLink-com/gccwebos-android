package com.gccwebos.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.gccwebos.android.commerce.*
import com.gccwebos.android.portal.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** Real login/Shell/commerce effects; all responses, sessions and recovery remain in memory. */
class CommerceEligibilityUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val now = Instant.parse("2026-10-01T00:00:00Z")
    private val noResource = WireResponse(200, """{"resource":{"state":"none"}}""")
    private class Recovery : CommerceRecovery {
        var pending: PendingCommerce? = null
        override fun read(binding: String, kind: String): PendingCommerce? = pending?.takeIf {
            binding == sessionBinding(Secret("eligibility_account_a")) && it.kind == kind
        }
        override fun save(binding: String, value: PendingCommerce) = throw AssertionError("No order creation")
        override fun clear(binding: String, kind: String) = throw AssertionError("No recovery deletion")
    }
    private val recovery = Recovery()
    private lateinit var account: AccountController
    private lateinit var commerce: CommerceController
    private var resourceReply = CompletableDeferred<WireResponse>()
    private var walletReply: CompletableDeferred<WireResponse>? = null
    private var resourceRequests = 0
    private var resourceResponses = 0
    private var walletResponses = 0
    private var secondFactor = false
    private var owner = "a"
    private val commerceReads = mutableListOf<Pair<String, String>>()
    private val walletPath = "/api/portal/coins/wallet"
    private val rechargeOffersPath = "/api/portal/coins/recharge/offers"
    private fun wallet(units: Int) = WireResponse(200, """{"wallet":{"status":"active","version":1,
        "balances":{"paidCoins":$units,"bonusCoins":0,"rewardCoins":0},
        "spendableUnits":$units,"heldUnits":0,"pendingUnits":0}}""")
    private fun fixtureSession() = WireResponse(200, """{"session":{"sessionRef":"eligibility_token_$owner",
        "accountId":"eligibility_account_$owner","issuedAt":"2026-10-01T00:00:00.000Z",
        "expiresAt":"2026-10-01T00:30:00.000Z"}}""")

    private fun launch(totp: Boolean = false) {
        secondFactor = totp
        val repository = PortalRepository(object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse = when (path) {
                "/api/portal/human-challenges" -> WireResponse(201, """{"challenge":{"challengeRef":"eligibility_math",
                    "type":"math","prompt":"1 + 2","expiresInSeconds":3600}}""")
                "/api/portal/password/session" -> {
                    assertNotNull(body); assertNull(session)
                    if (secondFactor) WireResponse(202, """{"challenge":{"status":"second_factor_required",
                        "challengeRef":"eligibility_totp","availableFactors":["totp"],
                        "expiresAt":"2026-10-01T00:05:00.000Z"}}""") else fixtureSession()
                }
                "/api/portal/password/second-factor" -> { assertNotNull(body); assertNull(session); fixtureSession() }
                "/api/portal/resources/current/overview" -> {
                    assertNull(body); assertNotNull(session)
                    resourceRequests++
                    // Also exercise a late transport response after cancellation/replacement.
                    val reply = resourceReply
                    withContext(NonCancellable) { reply.await().also { resourceResponses++ } }
                }
                else -> throw AssertionError("Unexpected non-commerce fixture path: $path")
            }
            override suspend fun commerce(path: String, body: String?, session: Secret, key: String?): WireResponse {
                assertNull("Automatic commerce must be read-only", body)
                assertNull("Automatic commerce must not replay an idempotency key", key)
                commerceReads += path to session.value
                return when (path) {
                    walletPath -> {
                        val reply = walletReply; walletReply = null
                        if (reply == null) wallet(if (session.value.endsWith("_b")) 40 else 20).also { walletResponses++ }
                        else withContext(NonCancellable) { reply.await().also { walletResponses++ } }
                    }
                    rechargeOffersPath, "/api/portal/catalog/offers" -> WireResponse(200, """{"offers":[]}""")
                    "/api/portal/commerce/model" -> WireResponse(200, """{"commerceModel":"legacy"}""")
                    else -> throw AssertionError("Unexpected commerce fixture path: $path")
                }
            }
        }, object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = throw AssertionError("No delivery writes")
            override fun clear() = Unit
        }, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L }, { now },
            subscription = SubscriptionSource { throw AssertionError("No subscription requests") },
            sessions = MemorySessionStore(), profiles = MemoryLocalProfileStore())
        compose.setContent {
            val scope = rememberCoroutineScope()
            val accountState = remember { AccountController(repository, scope) }
            account = accountState
            val sessionOwner = accountState.commerceSessionIdentity ?: accountState.authSessionGeneration
            val commerceState = remember(sessionOwner) { CommerceController(accountState, scope, recovery) }
            commerce = commerceState
            // Intentionally mounted BEFORE login/resource completion: no artificial !owner.busy gate.
            AppShell(accountState, commerceOverride = commerceState, commerceRecoveryOverride = recovery,
                paymentOpenerOverride = { throw AssertionError("No payment opening") })
        }
        submitLogin()
    }

    private fun submitLogin() {
        compose.onNodeWithTag("nav-Profile").performClick()
        compose.waitUntil(5000) { ::account.isInitialized && account.challenge != null && !account.challengeLoading && !account.busy }
        compose.onNodeWithTag("login-account").performTextReplacement("eligibility_$owner@example.invalid")
        compose.onNodeWithTag("login-password").performTextReplacement("synthetic-only")
        compose.onNodeWithTag("login-answer").performTextReplacement("3")
        compose.onNodeWithTag("login-submit").performScrollTo().performClick()
        if (secondFactor) {
            compose.waitUntil(5000) { account.factorReference != null && !account.busy }
            compose.onNodeWithTag("totp-code").performTextReplacement("123456")
            compose.onNodeWithText("验证并登录").performScrollTo().performClick()
        }
        compose.waitUntil(5000) { account.authenticated && account.busy && resourceRequests > resourceResponses }
    }

    private fun openWhileResourcePending(mode: CommerceMode) {
        if (mode == CommerceMode.Plans) compose.onNodeWithTag("nav-Plans").performClick()
        else {
            compose.onNodeWithTag("nav-Profile").performClick()
            // This existing entry remains available while account resource work is in progress.
            if (mode == CommerceMode.Wallet) compose.onNodeWithTag("mine-recharge").performScrollTo().performClick()
        }
        compose.runOnIdle {
            assertTrue(account.authenticated); assertTrue(account.busy)
            assertFalse(commerce.busy); assertTrue(commerceReads.isEmpty())
        }
        if (mode == CommerceMode.Plans) compose.onNodeWithTag("catalog-status").assertTextEquals("商品尚未读取")
        else compose.onNodeWithTag("wallet-balance").assertTextEquals("— Coins")
    }

    private fun expectedReads(mode: CommerceMode) = when (mode) {
        CommerceMode.Mine -> listOf(walletPath)
        CommerceMode.Wallet -> listOf(walletPath, rechargeOffersPath)
        else -> listOf(walletPath, rechargeOffersPath, "/api/portal/commerce/model", "/api/portal/catalog/offers")
    }

    private fun assertLoaded(mode: CommerceMode, units: Int = 20) {
        compose.waitUntil(5000) {
            !account.busy && !commerce.busy && if (mode == CommerceMode.Plans) commerce.catalogLoaded else commerce.walletLoaded
        }
        if (mode == CommerceMode.Plans) compose.onNodeWithTag("catalog-empty").assertTextEquals("暂无可购套餐")
        else compose.onNodeWithTag("wallet-balance").assertTextEquals("$units Coins")
        if (mode == CommerceMode.Wallet && !commerce.pendingRecharge) compose.onNodeWithTag("recharge-offers-empty").assertExists()
    }

    private fun assertSingleInitialRead(mode: CommerceMode, totp: Boolean) {
        launch(totp); openWhileResourcePending(mode)
        compose.runOnIdle { resourceReply.complete(noResource) }
        assertLoaded(mode)
        // Let ordinary busy->idle recompositions settle; they must not trigger another read.
        compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
        compose.runOnIdle { assertEquals(expectedReads(mode), commerceReads.map { it.first }) }
    }

    @Test fun passwordLoginDefersMineUntilResourceSuccess() = assertSingleInitialRead(CommerceMode.Mine, false)
    @Test fun passwordLoginDefersWalletUntilResourceSuccess() = assertSingleInitialRead(CommerceMode.Wallet, false)
    @Test fun passwordLoginDefersPlansUntilResourceSuccess() = assertSingleInitialRead(CommerceMode.Plans, false)
    @Test fun totpLoginDefersMineUntilResourceSuccess() = assertSingleInitialRead(CommerceMode.Mine, true)
    @Test fun totpLoginDefersWalletUntilResourceSuccess() = assertSingleInitialRead(CommerceMode.Wallet, true)
    @Test fun totpLoginDefersPlansUntilResourceSuccess() = assertSingleInitialRead(CommerceMode.Plans, true)

    @Test fun transientResourceFailureStillLoadsTheAuthenticatedWalletOnce() {
        launch(); openWhileResourcePending(CommerceMode.Wallet)
        compose.runOnIdle { resourceReply.complete(WireResponse(503, "")) }
        assertLoaded(CommerceMode.Wallet)
        compose.runOnIdle { assertNotNull(account.error); assertEquals(expectedReads(CommerceMode.Wallet), commerceReads.map { it.first }) }
    }

    @Test fun resourceSessionExpiryNeverStartsDeferredCommerce() {
        launch(true); openWhileResourcePending(CommerceMode.Mine)
        compose.runOnIdle { resourceReply.complete(WireResponse(401, "")) }
        compose.waitUntil(5000) { !account.authenticated && !account.busy }
        compose.onNodeWithTag("login-screen").assertExists()
        compose.runOnIdle { assertTrue(commerceReads.isEmpty()) }
    }

    @Test fun cancelledLoginDiscardsALateResourceResponseAndDeferredWalletRead() {
        launch(); openWhileResourcePending(CommerceMode.Wallet)
        compose.runOnIdle { account.cancelAuthentication() }
        compose.onNodeWithTag("nav-Profile").performClick()
        compose.onNodeWithTag("login-screen").assertExists()
        compose.runOnIdle {
            assertFalse(account.authenticated); assertTrue(account.clearing); assertTrue(commerceReads.isEmpty())
            resourceReply.complete(noResource)
        }
        compose.waitUntil(5000) { !account.busy && !account.clearing && resourceResponses == 1 }
        compose.runOnIdle { assertFalse(account.authenticated); assertTrue(commerceReads.isEmpty()) }
    }

    @Test fun replacementAccountCannotInheritTheOldDeferredRead() {
        launch(); openWhileResourcePending(CommerceMode.Wallet)
        lateinit var oldCommerce: CommerceController
        compose.runOnIdle { oldCommerce = commerce; account.cancelAuthentication(); resourceReply.complete(noResource) }
        compose.waitUntil(5000) { !account.busy && !account.clearing }
        compose.runOnIdle { owner = "b"; resourceReply = CompletableDeferred() }
        submitLogin(); openWhileResourcePending(CommerceMode.Mine)
        compose.runOnIdle {
            assertNotSame(oldCommerce, commerce); assertFalse(oldCommerce.currentSession())
            oldCommerce.loadWallet(); assertTrue(commerceReads.isEmpty())
            resourceReply.complete(noResource)
        }
        assertLoaded(CommerceMode.Mine, 40)
        compose.runOnIdle {
            oldCommerce.loadWallet()
            assertEquals(listOf(walletPath to "eligibility_token_b"), commerceReads)
            assertNull(oldCommerce.wallet)
        }
    }

    @Test fun accountWorkInterruptsAnExistingReadAndReloadsOnceAfterItFinishes() {
        launch(); openWhileResourcePending(CommerceMode.Wallet)
        val delayedWallet = CompletableDeferred<WireResponse>()
        compose.runOnIdle { walletReply = delayedWallet; resourceReply.complete(noResource) }
        compose.waitUntil(5000) { commerce.busy && commerceReads.size == 1 }
        compose.runOnIdle { resourceReply = CompletableDeferred(); account.refresh() }
        compose.waitUntil(5000) { account.busy && !commerce.busy }
        compose.runOnIdle { assertNull(commerce.wallet); delayedWallet.complete(wallet(999)) }
        compose.waitUntil(5000) { resourceRequests == 2 && walletResponses == 1 }
        compose.runOnIdle {
            assertNull(commerce.wallet); assertFalse(commerce.walletLoaded)
            assertEquals(1, commerceReads.size); resourceReply.complete(noResource)
        }
        assertLoaded(CommerceMode.Wallet)
        compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(walletPath, walletPath, rechargeOffersPath), commerceReads.map { it.first }) }
    }

    @Test fun commerceFailureAfterEligibilityReturnsDoesNotStartARetryLoop() {
        launch(); openWhileResourcePending(CommerceMode.Mine)
        compose.runOnIdle {
            walletReply = CompletableDeferred<WireResponse>().apply { complete(WireResponse(503, "")) }
            resourceReply.complete(noResource)
        }
        compose.waitUntil(5000) { !account.busy && !commerce.busy && commerce.walletError != null }
        compose.onNodeWithTag("wallet-error").assertExists()
        compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(walletPath), commerceReads.map { it.first }) }
    }

    @Test fun accountPausePreservesUnknownRecoveryWithoutReplayingCreate() {
        launch(); openWhileResourcePending(CommerceMode.Wallet)
        val pending = PendingCommerce("recharge", null, "eligibility_offer", "eligibility_unknown_key")
        compose.runOnIdle { recovery.pending = pending; resourceReply.complete(noResource) }
        assertLoaded(CommerceMode.Wallet)
        compose.runOnIdle { resourceReply = CompletableDeferred(); account.refresh() }
        compose.waitUntil(5000) { account.busy && resourceRequests == 2 }
        compose.runOnIdle {
            assertSame(pending, recovery.pending); assertNull(commerce.recharge)
            resourceReply.complete(noResource)
        }
        compose.waitUntil(5000) { commerceReads.size == 4 && !commerce.busy }
        assertLoaded(CommerceMode.Wallet)
        compose.onNodeWithTag("recharge-status").performScrollTo().assertTextEquals("提交结果尚未确认，请保留原记录。")
        compose.runOnIdle {
            assertTrue(commerce.pendingRecharge); assertSame(pending, recovery.pending)
            assertEquals(expectedReads(CommerceMode.Wallet) + expectedReads(CommerceMode.Wallet), commerceReads.map { it.first })
        }
    }
}
