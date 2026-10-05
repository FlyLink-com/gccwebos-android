package com.gccwebos.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import com.gccwebos.android.commerce.*
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.portal.*
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** Synthetic memory sessions are restored through the real verifier; no actual HTTP or storage. */
class CatalogDisplayCacheUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var owner: AccountController
    private lateinit var state: CommerceController
    private var visible by mutableStateOf(true)
    private var visit by mutableStateOf(0)
    private var accountNumber by mutableStateOf(0)
    private val catalogCalls = mutableListOf<String>()
    private val recoveryReads = mutableListOf<String>()
    private val offer = Offer("synthetic_month", "synthetic_month_v1", LocalizedText("合成套餐", "Synthetic"),
        LocalizedText("", ""), LocalizedNotices(emptyList(), emptyList()), 30, 1000,
        listOf("paid_coins"), 30, 100, 0)
    private val recovery = object : CommerceRecovery {
        override fun read(binding: String, kind: String): PendingCommerce? { recoveryReads += kind; return null }
        override fun save(binding: String, value: PendingCommerce) = error("No recovery writes")
        override fun clear(binding: String, kind: String) = error("No recovery writes")
    }

    private fun account(scope: CoroutineScope, number: Int): AccountController {
        val now = Instant.parse("2026-10-04T00:00:00Z")
        val saved = MemorySessionStore().apply {
            save(SavedSession(Secret("native_catalog_fixture_$number"), "account_catalog_fixture_$number",
                now.plusSeconds(1800), 1800000, now, 0))
        }
        return AccountController(PortalRepository(object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                assertEquals("/api/portal/resources/current/overview", path)
                assertNull(body); assertNotNull(session)
                return WireResponse(200, """{"resource":{"state":"none"}}""")
            }
        }, object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = error("No delivery writes")
            override fun clear() = error("No delivery clears")
        }, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { 0L }, { now },
            subscription = SubscriptionSource { error("No subscription download") }, sessions = saved,
            profiles = MemoryLocalProfileStore()), scope)
    }

    private fun launch(autoLoad: Boolean = false) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val currentOwner = remember(accountNumber) { account(scope, accountNumber) }
            owner = currentOwner
            LaunchedEffect(currentOwner) { currentOwner.restore() }
            val identity = currentOwner.commerceSessionIdentity
            val cache = remember(currentOwner, identity) { CatalogDisplayCache(identity) }
            if (identity != null && currentOwner.authenticated && !currentOwner.busy) {
                val controller = remember(currentOwner, identity, visit) {
                    val repo = if (!autoLoad) null else CommerceRepository({ path, body, key ->
                        assertNull(body); assertNull(key); catalogCalls += path
                        when (path) {
                            "/api/portal/commerce/model" -> WireResponse(200, """{"commerceModel":"legacy"}""")
                            CommerceModel.LEGACY.offersPath -> WireResponse(200, """{"offers":[]}""")
                            else -> error("Catalog used an unrelated endpoint")
                        }
                    }, recovery, "synthetic_account")
                    CommerceController(currentOwner, scope, recovery, initialRepository = repo).apply {
                        if (!autoLoad) {
                            if (visit == 0 && accountNumber == 0) {
                                commerceModel = CommerceModel.LEGACY; catalogLoaded = true; offers = listOf(offer)
                            } else busy = true
                        }
                    }
                }
                state = controller
                GccTheme {
                    if (visible) Column(Modifier.verticalScroll(rememberScrollState())) {
                        CommercePanel(currentOwner, CommerceMode.Plans, stateOverride = controller, autoLoad = autoLoad,
                            catalogDisplayCache = cache)
                    }
                }
            }
        }
        compose.waitUntil(10_000) { ::state.isInitialized && owner.authenticated && !owner.busy }
        compose.waitForIdle()
    }

    @Test fun returningToPlansDisplaysLastCatalogBeforeRefreshWithoutEnablingPurchase() {
        launch()
        compose.onNodeWithTag("plan-offer-synthetic_month").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { visible = false }
        compose.runOnIdle { visit++; visible = true }
        compose.onNodeWithTag("plan-offer-synthetic_month").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("plan-continue").assertDoesNotExist()
        compose.onNodeWithTag("purchase-confirm").assertDoesNotExist()
        compose.runOnIdle { state.busy = false; state.error = "合成目录读取失败" }
        compose.onNodeWithTag("plan-offer-synthetic_month").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("plan-continue").assertDoesNotExist()
    }

    @Test fun differentVerifiedSessionNeverShowsPreviousCatalog() {
        launch()
        compose.onNodeWithTag("plan-offer-synthetic_month").performScrollTo().assertIsDisplayed()
        val previous = owner
        compose.runOnIdle { accountNumber++ }
        compose.waitUntil(10_000) { owner !== previous && owner.authenticated && !owner.busy }
        compose.onNodeWithTag("plan-offer-synthetic_month").assertDoesNotExist()
        compose.onNodeWithTag("plan-continue").assertDoesNotExist()
    }

    @Test fun resumedPlansReadOncePerVisitNeverPollRechargeAndRefreshAfterRealBackgroundReturn() {
        launch(autoLoad = true)
        val expected = listOf("/api/portal/commerce/model", CommerceModel.LEGACY.offersPath)
        compose.runOnIdle { assertEquals(expected, catalogCalls); assertFalse(recoveryReads.contains("recharge")) }
        compose.mainClock.advanceTimeBy(30_000)
        compose.runOnIdle { assertEquals(expected, catalogCalls); assertFalse(recoveryReads.contains("recharge")) }
        compose.runOnIdle { visible = false }
        compose.runOnIdle { visit++; visible = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(expected + expected, catalogCalls) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(expected + expected + expected, catalogCalls); assertFalse(recoveryReads.contains("recharge")) }
    }
}
