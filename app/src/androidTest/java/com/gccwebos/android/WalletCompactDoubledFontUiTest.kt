package com.gccwebos.android

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.gccwebos.android.commerce.*
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.portal.*
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * A test activity whose window configuration carries fontScale 2.0, the way a real
 * device with doubled system font presents it. The balance-details bottom sheet is a
 * separate window composition: a subtree LocalDensity override (the harness in
 * WalletCompactUiTest) never reaches it — measured in run 43003132701, the sheet laid
 * the six balance figures out in 3 columns at the system font scale while the subtree
 * simulated 2.0 — so the doubled-font arrangement contract can only be exercised
 * through the window configuration itself. Declared in src/acceptanceTest's manifest.
 */
class DoubledFontComponentActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        config.fontScale = 2f
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }
}

/** Offline DTO fixtures and local callbacks only. No account, recharge, or payment request may escape. */
class WalletCompactDoubledFontUiTest {
    @get:Rule val compose = createAndroidComposeRule<DoubledFontComponentActivity>()
    private lateinit var state: CommerceController
    private lateinit var owner: AccountController
    private var rechargeNavigations = 0
    private var ledgerNavigations = 0
    private val calls = mutableListOf<String>()
    private val opened = mutableListOf<String>()
    private val tags = listOf("wallet-detail-total", "wallet-paid", "wallet-bonus", "wallet-reward", "wallet-held", "wallet-pending")
    private val confirmed = Wallet("active", 1, 17, 9, 5, 31, 7, 11)
    private val offer = RechargeOffer("compact_fixture", 9, 73, 2345, "CNY", 2, listOf("alipay"))

    private fun account(scope: CoroutineScope) = AccountController(PortalRepository(
        object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse =
                throw AssertionError("No Portal requests in compact wallet fixtures")
        },
        object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = throw AssertionError("No delivery writes")
            override fun clear() = Unit
        },
        OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L },
        subscription = SubscriptionSource { throw AssertionError("No subscription requests") },
        sessions = MemorySessionStore(), profiles = MemoryLocalProfileStore()), scope).apply { authenticated = true }

    private fun launch(width: Int = 320, setup: CommerceController.() -> Unit = {}) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val account = remember { account(scope) }
            owner = account
            val controller = remember {
                val recovery = object : CommerceRecovery {
                    override fun read(binding: String, kind: String): PendingCommerce? = null
                    override fun save(binding: String, value: PendingCommerce) = throw AssertionError("No order creation")
                    override fun clear(binding: String, kind: String) = throw AssertionError("No recovery clears")
                }
                val repo = CommerceRepository({ path, _, _ -> calls += path; throw AssertionError("No commerce transport") },
                    recovery, "compact_ui_account")
                CommerceController(account, scope, recovery, initialRepository = repo).apply(setup)
            }
            state = controller
            GccTheme {
                Column(Modifier.width(width.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    WalletPanel(account, controller, CommerceMode.Wallet, Instant.parse("2026-10-01T00:00:00Z"),
                        paymentOpener = { opened += it },
                        onRecharge = { rechargeNavigations++ }, onLedger = { ledgerNavigations++ })
                }
            }
        }
    }

    private fun openDetails() { compose.onNodeWithTag("wallet-balance-details").performScrollTo().performClick() }
    private fun closeDetails() { compose.onNodeWithTag("wallet-balance-details-close").performClick() }
    private fun assertNoSideEffects() = compose.runOnIdle {
        assertTrue(calls.isEmpty()); assertTrue(opened.isEmpty()); assertNull(state.recharge); assertFalse(state.pendingRecharge)
    }

    @Test fun narrowDoubledFontReflowsAndKeepsAmountConfirmationAndCancelReachable() {
        launch(width = 320) {
            wallet = confirmed; walletLoaded = true; rechargeOffers = listOf(offer); rechargeOffersLoaded = true
        }
        compose.onNodeWithTag("wallet-balance").performScrollTo().assertIsDisplayed().assertTextEquals("31 Coins")
        openDetails()
        tags.zip(listOf(31, 17, 9, 5, 7, 11)).forEach { (tag, amount) ->
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertTextEquals("$amount Coins")
        }
        compose.onNodeWithTag(tags[0]).performScrollTo()
        val first = compose.onNodeWithTag(tags[0]).fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag(tags[1]).fetchSemanticsNode().boundsInRoot
        assertTrue(second.top > first.bottom)
        assertEquals(first.left, second.left, 1f)
        closeDetails()
        compose.onNodeWithTag("recharge-select-${offer.offerRef}").performScrollTo().performClick()
        compose.onNodeWithTag("recharge-confirm").assertDoesNotExist()
        // The checkout bar itself is the confirmation: amount and 立即充值 are on screen, and there is no dialog step.
        compose.onNodeWithTag("recharge-selected-pay").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("recharge-payable-amount").assertTextEquals("应付金额：￥23.45")
        compose.onNodeWithText("确认充值金额").assertDoesNotExist()
        compose.onNodeWithTag("commerce-refresh").assertDoesNotExist()
        assertNoSideEffects()
    }
}
