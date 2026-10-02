package com.gccwebos.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gccwebos.android.commerce.*
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.portal.*
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Offline My/referral fixtures: local 320dp/2f layout, memory link/copy handlers, no real clients. */
class MineReferralUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var account: AccountController
    private lateinit var commerce: CommerceController
    private lateinit var referral: ReferralController
    private val opened = mutableListOf<String>()
    private val copied = mutableListOf<String>()
    private val requests = mutableListOf<Pair<String, String?>>()
    private var density = 1f
    private val path = "/r/" + "b".repeat(32)
    private fun owner(scope: CoroutineScope) = AccountController(PortalRepository(
        object : PortalTransport { override suspend fun request(path: String, body: String?, session: Secret?): WireResponse = throw AssertionError("No Portal requests") },
        object : DeliveryStore { override fun read(): DeliveryRecord? = null; override fun save(record: DeliveryRecord) = throw AssertionError("No delivery writes"); override fun clear() = Unit },
        OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), {0L},
        subscription = SubscriptionSource { throw AssertionError("No subscriptions") }, sessions = MemorySessionStore(), profiles = MemoryLocalProfileStore()), scope)
        .apply { authenticated = true; account = "opaque_ui_account_fixture"; resource = Resource.None }
    private val recovery = object : CommerceRecovery {
        override fun read(binding: String, kind: String): PendingCommerce? = throw AssertionError("No recovery reads")
        override fun save(binding: String, value: PendingCommerce) = throw AssertionError("No orders")
        override fun clear(binding: String, kind: String) = throw AssertionError("No recovery clear")
    }
    private fun launchMine(font: Float = 1f) {
        compose.setContent {
            val scope = rememberCoroutineScope(); val owner = remember { owner(scope) }; account = owner
            val state = remember { CommerceController(owner, scope, recovery) }; commerce = state
            val actual = LocalDensity.current; density = actual.density
            CompositionLocalProvider(LocalDensity provides Density(actual.density, font)) {
                GccTheme { Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    MinePanel(owner, autoLoad = false, stateOverride = state, telegramOpener = { opened += it })
                } }
            }
        }
    }
    private fun launchReferral(profile: () -> String, font: Float = 1f, unknownGeneration: Boolean = false) {
        compose.setContent {
            val scope = rememberCoroutineScope(); val owner = remember { owner(scope) }; account = owner
            val state = remember { ReferralController(owner, scope, { copied += it }, ReferralRepository { route, body ->
                requests += route to body
                if (route == ReferralJson.LINK) {
                    check(unknownGeneration) { "This fixture does not allow link generation" }
                    assertEquals("{}", body)
                    throw PortalFailure(PortalFailure.Reason.NETWORK)
                }
                assertNull(body)
                WireResponse(200, if (route == ReferralJson.PROFILE) profile() else """{"performance":{"registrations":4,"firstRechargeConversions":1,"creditedFirstRechargeCoins":17,"pendingRewardCoins":3,"availableRewardCoins":5,"reversedRewardCoins":2,"conversionRate":0.25,"reconciliationRequired":false}}""")
            }) }; referral = state
            val actual = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(actual.density, font)) {
                GccTheme { Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    ReferralPanel(owner, autoLoad = true, stateOverride = state, copyOverride = { copied += it })
                } }
            }
        }
        compose.waitUntil(5_000) { referral.profile != null && referral.performance != null }
    }
    @Test fun oldSessionIsNeutralAndUnknownBalanceCannotBecomeResourceDerivedZero() {
        launchMine()
        compose.onNodeWithTag("mine-account-identity").assertTextEquals("账户")
        compose.onNodeWithTag("wallet-balance").assertTextEquals("— Coins")
        compose.runOnIdle { commerce.walletError = "合成余额读取失败" }
        compose.onNodeWithTag("wallet-error").assertTextEquals("合成余额读取失败")
        compose.onNodeWithTag("wallet-balance").assertTextEquals("— Coins")
        compose.runOnIdle { commerce.wallet = Wallet("active", 1, 43, 0, 0, 43, 7, 9); commerce.walletLoaded = true; commerce.walletError = null }
        compose.onNodeWithTag("wallet-balance").assertTextEquals("43 Coins")
        compose.runOnIdle { assertTrue(opened.isEmpty()); assertTrue(copied.isEmpty()); assertTrue(requests.isEmpty()) }
    }
    @Test fun largeFontMenusKeepMinimumTouchAndTelegramUsesOnlyTheExactChannelOnClick() {
        launchMine(font = 2f)
        for (tag in listOf("mine-wallet", "mine-security", "mine-referral", "mine-telegram", "mine-signout")) {
            val node = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().fetchSemanticsNode()
            assertTrue(node.boundsInRoot.height / density >= 48f)
        }
        compose.runOnIdle { assertTrue(opened.isEmpty()) }
        compose.onNodeWithTag("mine-telegram").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("https://t.me/flylinevpn"), opened); assertTrue(requests.isEmpty()); assertTrue(copied.isEmpty()) }
    }
    @Test fun dynamicPolicyReadAndUserCopyDoNotGenerateLinksOrChangeWallet() {
        launchReferral({ """{"profile":{"eligible":true,"linkPath":"$path","policy":{"policyRef":"referral_ui_policy","version":3,"effectiveAt":"2026-09-30T00:00:00.000Z","rateBps":735,"rounding":"floor","attributionDays":47,"confirmationDays":9},"attributionDays":47}}""" })
        compose.onNodeWithTag("referral-policy-rate").assertTextEquals("当前奖励比例 7.35%")
        compose.onNodeWithTag("referral-available-reward").performScrollTo().assertTextEquals("已转可用奖励 5 Coins")
        compose.runOnIdle { assertTrue(copied.isEmpty()); assertEquals(listOf(ReferralJson.PROFILE, ReferralJson.PERFORMANCE), requests.map { it.first }) }
        compose.onNodeWithTag("referral-copy").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("https://portal.gccwebos.com$path"), copied); assertEquals(2, requests.size); assertTrue(requests.all { it.second == null }); assertTrue(opened.isEmpty()) }
    }
    @Test fun unknownDistributionWindowHasNoDefaultRateAndKeepsGenerateDisabledAtLargeFont() {
        launchReferral({ """{"profile":{"eligible":true,"linkPath":null,"policy":null}}""" }, font = 2f)
        compose.onNodeWithTag("referral-policy-rate").assertDoesNotExist()
        compose.onNodeWithTag("referral-generate").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("推荐归因期尚未确认，暂不能生成或复制链接。").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertTrue(copied.isEmpty()); assertEquals(2, requests.size); assertTrue(requests.all { it.second == null }) }
    }

    @Test fun pendingRestoringAndSignedOutWithholdPreviouslyReadReferralDataAndActions() {
        launchReferral({ """{"profile":{"eligible":true,"linkPath":"$path","policy":{"policyRef":"referral_ui_policy","version":3,"effectiveAt":"2026-09-30T00:00:00.000Z","rateBps":735,"rounding":"floor","attributionDays":47,"confirmationDays":9},"attributionDays":47}}""" })
        val original = referral
        compose.onNodeWithTag("referral-policy-rate").assertExists()
        compose.onNodeWithTag("referral-available-reward").performScrollTo().assertExists()
        for (gate in listOf("foreground", "restoring", "signed-out")) {
            compose.runOnIdle {
                account.foregroundCheckPending = gate == "foreground"
                account.restoring = gate == "restoring"
                account.authenticated = gate != "signed-out"
            }
            compose.onNodeWithTag("referral-account-confirmation").assertExists()
            listOf("referral-profile", "referral-performance", "referral-policy-rate", "referral-available-reward",
                "referral-copy", "referral-generate").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
            compose.runOnIdle {
                assertSame(original, referral)
                assertNotNull(referral.profile); assertNotNull(referral.performance)
                assertFalse(referral.canCopy); assertFalse(referral.canGenerate)
                referral.copyLink(); referral.generateLink()
                assertTrue(copied.isEmpty())
                assertTrue(requests.none { it.first == ReferralJson.LINK })
            }
        }
    }

    @Test fun sameSessionForegroundConfirmationKeepsUnknownGenerationAndNeverReplaysPost() {
        launchReferral({ """{"profile":{"eligible":true,"linkPath":null,"policy":{"policyRef":"referral_ui_policy","version":3,"effectiveAt":"2026-09-30T00:00:00.000Z","rateBps":735,"rounding":"floor","attributionDays":47,"confirmationDays":9},"attributionDays":47}}""" }, unknownGeneration = true)
        val original = referral
        val originalGuard = account.referralGenerationGuard
        val originalEpoch = account.authSessionGeneration
        compose.onNodeWithTag("referral-generate").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { !referral.busy && account.referralGenerationGuard.unknown }
        compose.runOnIdle {
            assertEquals(1, requests.count { it.first == ReferralJson.LINK })
            account.foregroundCheckPending = true; account.restoring = true; account.authenticated = false
        }
        compose.onNodeWithTag("referral-account-confirmation").assertExists()
        compose.onNodeWithTag("referral-performance").assertDoesNotExist()
        compose.onNodeWithTag("referral-copy").assertDoesNotExist()
        compose.onNodeWithTag("referral-generate").assertDoesNotExist()
        val readsBeforeResume = requests.count { it.first != ReferralJson.LINK }
        compose.runOnIdle {
            assertSame(original, referral); assertSame(originalGuard, account.referralGenerationGuard)
            assertTrue(originalGuard.unknown)
            account.authenticated = true; account.restoring = false; account.foregroundCheckPending = false
        }
        compose.waitUntil(5_000) { !referral.busy && requests.count { it.first != ReferralJson.LINK } > readsBeforeResume }
        compose.onNodeWithTag("referral-available-reward").performScrollTo().assertExists()
        compose.onNodeWithTag("referral-generate").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            assertSame(original, referral); assertSame(originalGuard, account.referralGenerationGuard)
            assertEquals(originalEpoch, account.authSessionGeneration)
            referral.generateLink(); referral.copyLink()
            assertTrue(originalGuard.unknown)
            assertEquals(1, requests.count { it.first == ReferralJson.LINK })
            assertTrue(copied.isEmpty())
        }
    }
}
