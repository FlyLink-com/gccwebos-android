package com.gccwebos.android.commerce

import com.gccwebos.android.portal.WireResponse
import com.gccwebos.android.portal.PortalFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CommerceRepositoryTest {
    private val now = Instant.parse("2026-09-30T00:00:00Z")
    private class Store : CommerceRecovery {
        val values = mutableMapOf<String, PendingCommerce>()
        var failSave = false
        var failRead = false
        override fun read(binding: String, kind: String): PendingCommerce? { if(failRead) error("unreadable"); return values["$binding:$kind"] }
        override fun save(binding: String, value: PendingCommerce) { if(failSave) error("storage failed"); values["$binding:${value.kind}"] = value }
        override fun clear(binding: String, kind: String) { values.remove("$binding:$kind") }
    }
    private class Wire {
        data class Call(val path: String, val body: String?, val key: String?)
        val calls = mutableListOf<Call>()
        val replies = ArrayDeque<WireResponse>()
        var observer: ((Call) -> Unit)? = null
        suspend fun request(path: String, body: String?, key: String?): WireResponse {
            val call = Call(path, body, key); calls += call; observer?.invoke(call)
            return replies.removeFirst()
        }
    }
    private fun repository(wire: Wire, store: Store, binding: String = "account_a") =
        CommerceRepository(wire::request,store,binding,{now},{"operation_fixture"})
    private suspend fun load(repo: CommerceRepository, wire: Wire, deferred: Boolean = false) {
        wire.replies += WireResponse(200, if(deferred) CommerceFixtures.walletFirstModel else CommerceFixtures.legacyModel)
        repo.loadModel()
    }
    private val quote get() = CommerceJson.quote(CommerceFixtures.quote, CommerceModel.LEGACY)
    private val topup get() = CommerceJson.rechargeOffers(CommerceFixtures.rechargeOffers).single()
    @Test fun automaticRechargeReadNeverReplaysUnknownSubmissionAndStopsTerminalPolling() = runTest {
        val wire = Wire(); val store = Store(); val repo = repository(wire, store)
        store.save("account_a", PendingCommerce("recharge", null, "offer_fixture", "key_fixture"))
        assertNull(repo.currentRecharge()); assertTrue(wire.calls.isEmpty())
        val order = CommerceJson.rechargeOrder(CommerceFixtures.rechargeOrder)
        for (status in listOf("credited", "failed_no_charge", "expired", "reconciliation_required")) assertFalse(order.copy(status = status).awaitingUpdate)
        for (status in listOf("entry_ready", "pending", "payment_verified")) assertTrue(order.copy(status = status).awaitingUpdate)
    }
    @Test fun historyUsesOnlyTheLoadedModelRouteAndNeverFallsBack() = runTest {
        for (deferred in listOf(false, true)) {
            val wire = Wire(); val repo = repository(wire, Store()); load(repo, wire, deferred)
            wire.replies += WireResponse(200, if (deferred) "{\"transitions\":[]}" else "{\"orders\":[],\"nextCursor\":null}")
            val selected = if (deferred) CommerceModel.WALLET_FIRST else CommerceModel.LEGACY
            val result = repo.purchaseHistory(selected)
            assertEquals(listOf("/api/portal/commerce/model", if (deferred) "/api/portal/commerce/deferred/transitions" else "/api/portal/commerce/orders?limit=20"), wire.calls.map { it.path })
            assertEquals(deferred, result.first == null); assertEquals(!deferred, result.second == null)
            val wrong = if (deferred) CommerceModel.LEGACY else CommerceModel.WALLET_FIRST
            try { repo.purchaseHistory(wrong); fail() } catch (_: IllegalArgumentException) { }
            assertEquals(2, wire.calls.size)
        }
        val wire = Wire(); val repo = repository(wire, Store())
        try { repo.purchaseHistory(CommerceModel.LEGACY); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(wire.calls.isEmpty())
    }
    @Test fun purchaseKeyIsDurableBeforeMutationAndInHeaderOnly() = runTest {
        val wire=Wire(); val store=Store(); val repo=repository(wire,store); load(repo,wire)
        wire.replies += WireResponse(201,CommerceFixtures.order)
        wire.observer = { if(it.body!=null) assertNotNull(store.read("account_a","purchase")) }
        val result=repo.purchase(quote)
        assertEquals("order_fixture_plan",result.ref)
        val call=wire.calls.last(); assertEquals("operation_fixture",call.key)
        assertEquals(setOf("quoteRef"),JSONObject(call.body!!).keys().asSequence().toSet())
        assertEquals(result.ref,repo.pending("purchase")!!.resultRef)
    }
    @Test fun storageFailurePreventsPurchasePost() = runTest {
        val wire=Wire(); val store=Store(); val repo=repository(wire,store); load(repo,wire); store.failSave=true
        try{repo.purchase(quote);fail()}catch(_:IllegalStateException){}
        assertEquals(1,wire.calls.size)
    }
    @Test fun unknownPurchaseReusesSameDurableKeyAcrossNewRepository() = runTest {
        val wire=Wire(); val store=Store(); var repo=repository(wire,store); load(repo,wire)
        wire.replies += WireResponse(503,"{}")
        try{repo.purchase(quote);fail()}catch(_:CommerceFailure){}
        assertNull(repo.pending("purchase")!!.resultRef)
        repo=repository(wire,store);load(repo,wire)
        assertNull(repo.currentPurchase()) // Resume NEVER replays a mutation.
        wire.replies += WireResponse(201,CommerceFixtures.order); repo.retryPurchase()
        val calls=wire.calls.filter{it.body!=null};assertEquals(2,calls.size);assertEquals(calls[0].key,calls[1].key)
    }
    @Test fun cancellationAfterSubmitKeepsPendingKey() = runTest {
        val wire=Wire();val store=Store();val repo=repository(wire,store);load(repo,wire)
        wire.observer={if(it.body!=null)throw CancellationException("cancelled")}
        try{repo.purchase(quote);fail()}catch(_:CancellationException){}
        assertEquals("operation_fixture",repo.pending("purchase")!!.key)
    }
    @Test fun existingPendingBlocksNewPurchase() = runTest {
        val wire=Wire();val store=Store();val repo=repository(wire,store);load(repo,wire)
        store.save("account_a",PendingCommerce("purchase",CommerceModel.LEGACY,"quote_old","key_old"))
        try{repo.purchase(quote);fail()}catch(_:IllegalArgumentException){}
        assertEquals(1,wire.calls.size)
    }
    @Test fun accountRecoveryDoesNotCrossAccount() = runTest {
        val wire=Wire();val store=Store();store.save("account_a",PendingCommerce("recharge",null,"offer_old","key_old"))
        assertNull(repository(wire,store,"account_b").pending("recharge"))
    }
    @Test fun changedServerModelCannotReplayLegacyMutation() = runTest {
        val wire=Wire();val store=Store();val repo=repository(wire,store);load(repo,wire,true)
        store.save("account_a",PendingCommerce("purchase",CommerceModel.LEGACY,"quote_old","key_old"))
        try{repo.retryPurchase();fail()}catch(_:IllegalArgumentException){}
        assertEquals(1,wire.calls.size);assertNotNull(repo.pending("purchase"))
    }
    @Test fun deferredUsesDedicatedTransitionPath() = runTest {
        val wire=Wire();val store=Store();val repo=repository(wire,store);load(repo,wire,true)
        wire.replies+=WireResponse(201,CommerceFixtures.deferred)
        repo.purchase(CommerceJson.quote(CommerceFixtures.deferredQuote,CommerceModel.WALLET_FIRST))
        assertEquals("/api/portal/commerce/deferred/transitions",wire.calls.last().path)
    }
    @Test fun expiredQuoteNeverCreatesOrder() = runTest {
        val wire=Wire();val store=Store();val repo=repository(wire,store);load(repo,wire)
        try{repo.purchase(quote.copy(expiresAt=now));fail()}catch(_:IllegalArgumentException){}
        assertEquals(1,wire.calls.size);assertNull(repo.pending("purchase"))
    }
    @Test fun deterministicRejectedQuoteCanBeRequoted() = runTest {
        for((status,code) in listOf(409 to "quote_expired",409 to "quote_stale",409 to "offer_unavailable",423 to "insufficient_coins",423 to "wallet_not_active")) {
            val wire=Wire();val store=Store();val repo=repository(wire,store);load(repo,wire)
            wire.replies+=WireResponse(status,"""{"error":{"code":"$code","retryable":false}}""")
            try{repo.purchase(quote);fail()}catch(_:CommerceFailure){}
            assertNull("$status $code",repo.pending("purchase"))
        }
    }
    @Test fun misleadingErrorOrReconciliationNeverDropsRecovery() = runTest {
        for((status,code) in listOf(503 to "quote_expired",409 to "idempotency_key_reused",423 to "reconciliation_required")) {
            val wire=Wire();val store=Store();val repo=repository(wire,store);load(repo,wire)
            wire.replies+=WireResponse(status,"""{"error":{"code":"$code","retryable":true}}""")
            try{repo.purchase(quote);fail()}catch(_:CommerceFailure){}
            assertNotNull(repo.pending("purchase"))
        }
    }
    @Test fun rechargeBodyKeyAndPersistedOrderAreCorrect() = runTest {
        val wire=Wire();val store=Store();val repo=repository(wire,store)
        wire.observer={assertNotNull(repo.pending("recharge"))}
        wire.replies+=WireResponse(201,CommerceFixtures.rechargeOrder);repo.recharge(topup)
        val call=wire.calls.last();assertNull(call.key)
        assertEquals(setOf("offerRef","idempotencyKey"),JSONObject(call.body!!).keys().asSequence().toSet())
        assertEquals("operation_fixture",JSONObject(call.body).getString("idempotencyKey"))
    }
    @Test fun resumeOnlyReadsKnownRechargeAndNeverPaysOrCaptures() = runTest {
        val wire=Wire();val store=Store();val repo=repository(wire,store)
        store.save("account_a",PendingCommerce("recharge",null,topup.offerRef,"key_old","recharge_fixture_order"))
        wire.replies+=WireResponse(200,CommerceFixtures.rechargeOrder);repo.currentRecharge()
        assertTrue(wire.calls.all{it.body==null && !it.path.endsWith("capture")})
    }
    @Test fun paymentVerifiedCannotFinishAsCredited() = runTest {
        val wire=Wire();val store=Store();val repo=repository(wire,store)
        store.save("account_a",PendingCommerce("recharge",null,topup.offerRef,"key_old","recharge_fixture_order"))
        val body=JSONObject(CommerceFixtures.rechargeOrder);body.getJSONObject("recharge").getJSONObject("order").put("status","payment_verified").put("nextAction",JSONObject.NULL)
        wire.replies+=WireResponse(200,body.toString())
        try{repo.finishRecharge();fail()}catch(_:IllegalArgumentException){}
        assertNotNull(repo.pending("recharge"))
    }
    @Test fun session401NeverBecomesZeroBalance() = runTest {
        val wire=Wire();val store=Store();val repo=repository(wire,store);wire.replies+=WireResponse(401,"{}")
        try{repo.wallet();fail()}catch(f:PortalFailure){assertEquals(PortalFailure.Reason.SESSION_EXPIRED,f.reason)}
    }

    @Test fun resultPersistenceFailureKeepsOriginalKeyForReplay() = runTest {
        val wire=Wire();val store=Store();var repo=repository(wire,store);load(repo,wire)
        wire.replies+=WireResponse(201,CommerceFixtures.order)
        wire.observer={if(it.body!=null)store.failSave=true}
        try{repo.purchase(quote);fail()}catch(_:IllegalStateException){}
        assertEquals("operation_fixture",repo.pending("purchase")!!.key)
        assertNull(repo.pending("purchase")!!.resultRef)
        store.failSave=false;wire.observer=null;repo=repository(wire,store);load(repo,wire)
        wire.replies+=WireResponse(201,CommerceFixtures.order);repo.retryPurchase()
        assertEquals(listOf("operation_fixture","operation_fixture"),wire.calls.filter{it.body!=null}.map{it.key})
    }
    @Test fun rechargeTimeoutAndCancellationReuseBodyKey() = runTest {
        for(cancel in listOf(false,true)) {
            val wire=Wire();val store=Store();var repo=repository(wire,store)
            if(cancel)wire.observer={throw CancellationException("cancel")}
            else wire.replies+=WireResponse(503,"{}")
            try{repo.recharge(topup);fail()}catch(_:CancellationException){}catch(_:CommerceFailure){}
            assertNull(repo.pending("recharge")!!.resultRef)
            wire.observer=null;repo=repository(wire,store)
            assertNull(repo.currentRecharge())
            wire.replies+=WireResponse(201,CommerceFixtures.rechargeOrder);repo.retryRecharge()
            assertEquals(listOf("operation_fixture","operation_fixture"),wire.calls.map{JSONObject(it.body!!).getString("idempotencyKey")})
        }
    }
    @Test fun unreadableRecoveryBlocksBothPurchaseAndRechargePosts() = runTest {
        val wire=Wire();val store=Store();val repo=repository(wire,store);load(repo,wire);store.failRead=true
        try{repo.purchase(quote);fail()}catch(_:IllegalStateException){}
        try{repo.recharge(topup);fail()}catch(_:IllegalStateException){}
        assertEquals(1,wire.calls.size)
    }
    @Test fun unsupportedPaymentMethodCannotCreateRecharge() = runTest {
        val wire=Wire();val repo=repository(wire,Store())
        try{repo.recharge(topup.copy(paymentMethods=listOf("paypal")));fail()}catch(_:IllegalArgumentException){}
        assertTrue(wire.calls.isEmpty())
    }

    private val planState = """{"current":{"offerRef":"offer_fixture_plan","validUntil":"2026-10-30T00:00:00.000Z"},"pendingSwitch":null,"offers":[
        {"offerRef":"offer_fixture_plan","action":"current_renew","chargeUnits":10,"creditUnits":0,"effectiveAt":null,"reasonCode":null}]}"""
    private fun optionalRepository(wire: Wire, optional: suspend (String) -> WireResponse, store: Store = Store()) =
        CommerceRepository(wire::request, store, "account_a", {now}, {"operation_fixture"}, optional)

    @Test fun planChangeStateIsReadOnlyForTheLegacyModelAndNeverThrowsForADisabledFeature() = runTest {
        val wire = Wire(); val paths = mutableListOf<String>()
        val answers = ArrayDeque(listOf(WireResponse(200, planState), WireResponse(404, "{}"), WireResponse(401, "{}"),
            WireResponse(409, """{"error":{"code":"plan_switch_precheck_unavailable"}}"""), WireResponse(200, "not json"),
            WireResponse(503, "")))
        val repo = optionalRepository(wire, { path -> paths += path; answers.removeFirst() })
        assertNull(repo.planSwitchState()) // the model is not loaded yet
        load(repo, wire)
        assertEquals("offer_fixture_plan", repo.planSwitchState()!!.current!!.offerRef)
        repeat(5) { assertNull(repo.planSwitchState()) } // 404, a protected 401, 409, garbage and 503 all mean "no plan change"
        assertEquals(List(6) { "/api/portal/commerce/plan-switch/state" }, paths)
        assertEquals(1, wire.calls.size) // only the model read went through the ordinary (session-ending) request path
    }

    @Test fun theWalletFirstModelAndAHostWithoutTheOptionalReadNeverAskForPlanChanges() = runTest {
        val wire = Wire(); var asked = false
        val deferred = optionalRepository(wire, { asked = true; WireResponse(200, planState) })
        load(deferred, wire, deferred = true)
        assertNull(deferred.planSwitchState()); assertFalse(asked)
        val plain = repository(Wire(), Store())
        assertNull(plain.planSwitchState())
    }

    @Test fun everyPlanChangeRefusalClearsTheDurableKeyBecauseNoOrderWasCreated() = runTest {
        for (code in PlanSwitchRejections) {
            val wire = Wire(); val store = Store(); val repo = repository(wire, store); load(repo, wire)
            wire.replies += WireResponse(409, """{"error":{"code":"$code"}}""")
            try { repo.purchase(quote); fail(code) } catch (failure: CommerceFailure) { assertEquals(code, failure.code); assertEquals(409, failure.status) }
            assertNull(code, repo.pending("purchase"))
        }
        // An unknown 409 on the create stays ambiguous and keeps the key for the user-triggered query.
        val wire = Wire(); val store = Store(); val repo = repository(wire, store); load(repo, wire)
        wire.replies += WireResponse(409, """{"error":{"code":"something_new"}}""")
        try { repo.purchase(quote); fail() } catch (_: CommerceFailure) { }
        assertNotNull(repo.pending("purchase"))
    }

    @Test fun aPlanChangeQuoteIsConfirmedWithTheSameQuoteReferenceAndKey() = runTest {
        val wire = Wire(); val store = Store(); val repo = repository(wire, store); load(repo, wire)
        val upgrade = quote.copy(planSwitch = PlanSwitch(PlanSwitchKind.UpgradeInPlace, 0, 10, null, false))
        wire.replies += WireResponse(201, CommerceFixtures.order)
        repo.purchase(upgrade)
        assertEquals("/api/portal/commerce/orders", wire.calls.last().path)
        assertEquals("operation_fixture", wire.calls.last().key)
        assertEquals("quote_fixture_plan", JSONObject(wire.calls.last().body!!).getString("quoteRef"))
    }
}
