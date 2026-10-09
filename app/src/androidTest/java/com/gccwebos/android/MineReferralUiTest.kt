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
import kotlinx.coroutines.CompletableDeferred
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
    private var referralVisit by mutableStateOf(0)
    private val shared = mutableListOf<Pair<ReferralShareTarget, String>>()
    private var referralRefreshRequest by mutableStateOf(0)
    private var referralAutoLoad by mutableStateOf(true)
    private var referralReadGate: CompletableDeferred<Unit>? = null
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
    private fun launchReferral(profile: () -> String, font: Float = 1f, unknownGeneration: Boolean = false,
        expectProfile: Boolean = true) {
        compose.setContent {
            val scope = rememberCoroutineScope(); val owner = remember { owner(scope) }; account = owner
            // A new key mirrors leaving and re-entering the destination, disposing its read owner.
            key(referralVisit) {
            val state = remember { ReferralController(owner, scope, { copied += it }, ReferralRepository { route, body ->
                requests += route to body
                if (route == ReferralJson.LINK) {
                    check(unknownGeneration) { "This fixture does not allow link generation" }
                    assertEquals("{}", body)
                    throw PortalFailure(PortalFailure.Reason.NETWORK)
                }
                assertNull(body)
                referralReadGate?.await()
                WireResponse(200, if (route == ReferralJson.PROFILE) profile() else """{"performance":{"registrations":4,"firstRechargeConversions":1,"creditedFirstRechargeCoins":17,"pendingRewardCoins":3,"availableRewardCoins":5,"reversedRewardCoins":2,"conversionRate":0.25,"reconciliationRequired":false}}""")
            }) }; referral = state
            val actual = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(actual.density, font)) {
                GccTheme { Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    ReferralPanel(owner, autoLoad = referralAutoLoad, stateOverride = state,
                        copyOverride = { copied += it }, refreshRequest = referralRefreshRequest,
                        shareOverride = { target, url -> shared += target to url })
                } }
            }
            }
        }
        compose.waitUntil(5_000) { !referral.busy && referral.performance != null &&
            (if (expectProfile) referral.profile != null else referral.profileError != null) }
    }
    @Test fun mineSectionsShareOneSurfaceAndDoNotOfferAPurchaseReturnShortcut() {
        launchMine()
        listOf("mine-welcome", "wallet-card", "mine-menu", "mine-referral", "mine-functions").forEach { tag ->
            compose.onNodeWithTag(tag).assert(hasAnyAncestor(hasTestTag("mine-surface")))
        }
        compose.runOnIdle { account.purchaseReturnRef = "synthetic_retained_offer" }
        compose.onNodeWithTag("mine-return-purchase").assertDoesNotExist()
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
        for (tag in listOf("mine-wallet", "mine-security", "mine-referral", "mine-more", "mine-telegram", "mine-signout")) {
            val node = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().fetchSemanticsNode()
            assertTrue(node.boundsInRoot.height / density >= 48f)
        }
        compose.runOnIdle { assertTrue(opened.isEmpty()) }
        compose.onNodeWithTag("mine-telegram").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("https://t.me/flylinevpn"), opened); assertTrue(requests.isEmpty()); assertTrue(copied.isEmpty()) }
    }
    @Test fun aboutEntryBelongsToFunctionsAndNavigationDoesNotChangeAccountOrRequestData() {
        launchMine(font = 2f)
        val wallet = Wallet("active", 1, 43, 0, 0, 43, 7, 9)
        val before = compose.runOnIdle {
            commerce.wallet = wallet; commerce.walletLoaded = true
            Triple(account.authSessionGeneration, account.resource, account.localSubscription)
        }
        repeat(2) {
            compose.onNodeWithTag("mine-more").assert(hasAnyAncestor(hasTestTag("mine-functions")))
                .performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithTag("mine-about").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("mine-more").assertDoesNotExist()
            compose.onNodeWithTag("mine-functions").assertDoesNotExist()
            compose.onNodeWithTag("checkUpdate").assertDoesNotExist()
            compose.onNodeWithTag("updateStatus").assertDoesNotExist()
            compose.onNodeWithTag("mine-back").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithTag("mine-about").assertDoesNotExist()
            compose.onNodeWithTag("mine-back").assertDoesNotExist()
            compose.onNodeWithTag("mine-more").assert(hasAnyAncestor(hasTestTag("mine-functions")))
            compose.onNodeWithTag("wallet-balance").assertTextEquals("43 Coins")
        }
        compose.runOnIdle {
            assertTrue(account.authenticated); assertEquals(before.first, account.authSessionGeneration)
            assertSame(before.second, account.resource); assertSame(before.third, account.localSubscription)
            assertEquals("opaque_ui_account_fixture", account.account)
            assertSame(wallet, commerce.wallet); assertFalse(account.busy); assertFalse(commerce.busy)
            assertTrue(opened.isEmpty()); assertTrue(copied.isEmpty()); assertTrue(requests.isEmpty())
        }
    }
    @Test fun dynamicPolicyReadAndUserCopyDoNotGenerateLinksOrChangeWallet() {
        launchReferral({ """{"profile":{"eligible":true,"linkPath":"$path","policy":{"policyRef":"referral_ui_policy","version":3,"effectiveAt":"2026-09-30T00:00:00.000Z","rateBps":735,"rounding":"floor","attributionDays":47,"confirmationDays":9},"attributionDays":47}}""" })
        compose.onNodeWithTag("referral-policy-rate").assertTextEquals("当前奖励比例 7.35%")
        compose.onNodeWithTag("referral-refresh").assertDoesNotExist()
        compose.onNodeWithTag("referral-available-reward").performScrollTo().assertTextEquals("可用奖励", "5", "Coins", "已到账，尚未使用")
        compose.onNodeWithTag("referral-stat-invites").performScrollTo().assertTextEquals("累计邀请", "4", "人", "首次充值 1 人")
        compose.onNodeWithTag("referral-stat-pending").performScrollTo().assertTextContains("3")
        compose.onNodeWithTag("referral-stat-reversed").performScrollTo().assertTextContains("2")
        compose.onNodeWithTag("referral-hero-title").assertTextEquals("邀请好友 · 赚 7.35% 首充奖励")
        compose.onNodeWithTag("referral-rule-attribution").performScrollTo().assertTextContains("47 天有效追踪")
        compose.onNodeWithTag("referral-rule-confirmation").performScrollTo().assertTextContains("9 天确认期")
        compose.onNodeWithTag("referral-link-masked").performScrollTo().assertTextEquals("portal.gccwebos.com/r/" + path.removePrefix("/r/").take(4) + "…" + path.takeLast(4))
        compose.runOnIdle { assertTrue(copied.isEmpty()); assertEquals(listOf(ReferralJson.PROFILE, ReferralJson.PERFORMANCE), requests.map { it.first }) }
        compose.onNodeWithTag("referral-copy").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("https://portal.gccwebos.com$path"), copied); assertEquals(2, requests.size); assertTrue(requests.all { it.second == null }); assertTrue(opened.isEmpty()) }
    }
    @Test fun shareRowUsesTheConfirmedLinkWithoutExtraRequests() {
        launchReferral({ """{"profile":{"eligible":true,"linkPath":"$path","policy":null,"attributionDays":30}}""" }, font = 2f)
        val url = "https://portal.gccwebos.com$path"
        compose.onNodeWithTag("referral-policy-rate").assertDoesNotExist()
        compose.onNodeWithTag("referral-rule-confirmation").assertDoesNotExist()
        compose.onNodeWithTag("referral-share-telegram").performScrollTo().performClick()
        compose.onNodeWithTag("referral-share-x").performScrollTo().performClick()
        compose.onNodeWithTag("referral-share-system").performScrollTo().performClick()
        compose.onNodeWithTag("referral-share-text").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(ReferralShareTarget.Telegram to url, ReferralShareTarget.X to url, ReferralShareTarget.System to url), shared)
            assertEquals(listOf(ReferralPresentation.shareMessage(url)), copied)
            assertEquals(2, requests.size); assertTrue(requests.none { it.first == ReferralJson.LINK })
        }
    }
    @Test fun unknownDistributionWindowHasNoDefaultRateAndKeepsGenerateDisabledAtLargeFont() {
        launchReferral({ """{"profile":{"eligible":true,"linkPath":null,"policy":null}}""" }, font = 2f)
        compose.onNodeWithTag("referral-policy-rate").assertDoesNotExist()
        compose.onNodeWithTag("referral-generate").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("referral-distribution-unavailable").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("referral-refresh").assertDoesNotExist()
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
        val readsBeforeRetry = requests.count { it.first != ReferralJson.LINK }
        compose.onNodeWithTag("referral-refresh").assertDoesNotExist()
        compose.runOnIdle { referralVisit++ }
        compose.waitUntil(5_000) { !referral.busy && requests.count { it.first != ReferralJson.LINK } >= readsBeforeRetry + 2 }
        compose.onNodeWithTag("referral-generate").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            assertNotSame(original, referral); assertSame(originalGuard, account.referralGenerationGuard)
            assertEquals(originalEpoch, account.authSessionGeneration)
            referral.generateLink(); referral.copyLink()
            assertTrue(originalGuard.unknown)
            assertEquals(1, requests.count { it.first == ReferralJson.LINK })
            assertTrue(copied.isEmpty())
        }
    }

    @Test fun failedReferralReadShowsTransientFeedbackAndReentryRetriesOnlyReads() {
        var unavailable = true
        launchReferral({
            if (unavailable) throw PortalFailure(PortalFailure.Reason.NETWORK)
            """{"profile":{"eligible":false,"linkPath":null,"policy":null}}"""
        }, expectProfile = false)
        compose.onNodeWithText("推荐信息暂不可用").assertExists()
        compose.onNodeWithTag("referral-refresh").assertDoesNotExist()
        compose.onNodeWithTag("referral-profile-status").assertDoesNotExist()
        val first = referral
        compose.runOnIdle { unavailable = false; referralVisit++ }
        compose.waitUntil(5_000) { referral !== first && !referral.busy && referral.profile != null }
        compose.onNodeWithTag("referral-profile-status").assertTextEquals("当前账户暂不具备推荐资格")
        compose.runOnIdle {
            assertEquals(4, requests.size)
            assertTrue(requests.all { it.second == null && it.first != ReferralJson.LINK })
            assertTrue(copied.isEmpty())
        }
    }

    @Test fun sameTabRefreshKeepsItsReadOwnerAndCoalescesBusyTokensWithoutGeneratingALink() {
        launchReferral({ """{"profile":{"eligible":false,"linkPath":null,"policy":null}}""" })
        val original = referral
        val release = CompletableDeferred<Unit>()
        compose.runOnIdle { referralReadGate = release; referralRefreshRequest++ }
        compose.waitUntil(5_000) { referral.busy && requests.size == 3 }
        repeat(2) { compose.runOnIdle { referralRefreshRequest++ }; compose.waitForIdle() }
        compose.runOnIdle { assertSame(original, referral); assertEquals(3, requests.size); release.complete(Unit) }
        compose.waitUntil(5_000) { !referral.busy && requests.size == 4 }
        compose.runOnIdle { referralAutoLoad = false; referralRefreshRequest++ }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(4, requests.size); assertSame(original, referral)
            assertTrue(requests.all { it.second == null && it.first != ReferralJson.LINK })
            assertTrue(copied.isEmpty())
        }
    }
}
