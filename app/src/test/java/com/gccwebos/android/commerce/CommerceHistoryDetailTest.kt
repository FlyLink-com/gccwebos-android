package com.gccwebos.android.commerce

import com.gccwebos.android.portal.PortalFailure
import com.gccwebos.android.portal.WireResponse
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Public synthetic history GETs only; even reading durable recovery fails this fixture. */
class CommerceHistoryDetailTest {
    private class NoRecovery : CommerceRecovery {
        override fun read(binding: String, kind: String): PendingCommerce? = throw AssertionError("No recovery read")
        override fun save(binding: String, value: PendingCommerce) = throw AssertionError("No recovery write")
        override fun clear(binding: String, kind: String) = throw AssertionError("No recovery clear")
    }
    private class Wire {
        val paths = mutableListOf<String>()
        val responses = ArrayDeque<WireResponse>()
        suspend fun request(path: String, body: String?, key: String?): WireResponse {
            assertNull(body); assertNull(key); paths += path
            return responses.removeFirst()
        }
    }
    private fun repository(wire: Wire) = CommerceRepository(wire::request, NoRecovery(), "synthetic_account",
        newKey = { throw AssertionError("No mutation key") })

    @Test fun threeHistoryDetailRoutesAreGetOnlyAndNeverUseRecovery() = runTest {
        for (model in CommerceModel.entries) {
            val wire = Wire(); val repo = repository(wire)
            wire.responses += WireResponse(200, if (model == CommerceModel.LEGACY) CommerceFixtures.legacyModel else CommerceFixtures.walletFirstModel)
            repo.loadModel()
            val body = if (model == CommerceModel.LEGACY) CommerceFixtures.order else CommerceFixtures.deferred
            val ref = if (model == CommerceModel.LEGACY) "order_fixture_plan" else "transition_fixture_plan"
            wire.responses += WireResponse(200, body)
            assertEquals(ref, repo.purchaseHistoryDetail(model, ref).ref)
            assertEquals(listOf("/api/portal/commerce/model", model.purchasePath + "/" + ref), wire.paths)
        }
        val wire = Wire(); val repo = repository(wire)
        val inner = JSONObject(CommerceFixtures.rechargeOrder).getJSONObject("recharge").getJSONObject("order")
        inner.put("nextAction", JSONObject.NULL)
        wire.responses += WireResponse(200, JSONObject().put("recharge", JSONObject().put("order", inner)).toString())
        assertEquals("recharge_fixture_order", repo.rechargeHistoryDetail("recharge_fixture_order").orderRef)
        assertEquals(listOf("/api/portal/coins/recharge/orders/recharge_fixture_order"), wire.paths)
    }

    @Test fun unknownOrDifferentModelAndUnsafeReferencesNeverDispatch() = runTest {
        val wire = Wire(); val repo = repository(wire)
        try { repo.purchaseHistoryDetail(CommerceModel.LEGACY, "order_fixture_plan"); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(wire.paths.isEmpty())
        wire.responses += WireResponse(200, CommerceFixtures.walletFirstModel); repo.loadModel()
        try { repo.purchaseHistoryDetail(CommerceModel.LEGACY, "order_fixture_plan"); fail() } catch (_: IllegalArgumentException) { }
        for (ref in listOf("../order_fixture", "order_fixture?x=1", "ORDER_fixture")) {
            try { repo.purchaseHistoryDetail(CommerceModel.WALLET_FIRST, ref); fail() } catch (_: IllegalArgumentException) { }
        }
        for (ref in listOf("../recharge_fixture", "recharge_fixture?x=1", "")) {
            try { repo.rechargeHistoryDetail(ref); fail() } catch (_: IllegalArgumentException) { }
        }
        assertEquals(listOf("/api/portal/commerce/model"), wire.paths)
    }

    @Test fun wrongWrapperOrReferenceAndHttpFailuresNeverBecomeEmptyDetail() = runTest {
        val wire = Wire(); val repo = repository(wire)
        wire.responses += WireResponse(200, CommerceFixtures.legacyModel); repo.loadModel()
        for (body in listOf(CommerceFixtures.deferred, CommerceFixtures.order.replace("order_fixture_plan", "order_fixture_other"), "{}")) {
            wire.responses += WireResponse(200, body)
            try { repo.purchaseHistoryDetail(CommerceModel.LEGACY, "order_fixture_plan"); fail() } catch (_: IllegalArgumentException) { }
        }
        wire.responses += WireResponse(404, """{"error":{"code":"order_not_found"}}""")
        try { repo.purchaseHistoryDetail(CommerceModel.LEGACY, "order_fixture_plan"); fail() }
        catch (failure: CommerceFailure) { assertEquals(404, failure.status) }
        wire.responses += WireResponse(401, "{}")
        try { repo.rechargeHistoryDetail("recharge_fixture_order"); fail() }
        catch (failure: PortalFailure) { assertEquals(PortalFailure.Reason.SESSION_EXPIRED, failure.reason) }
        assertEquals(6, wire.paths.size)
    }

    @Test fun verifiedPaymentAndNullableSettlementRemainSeparateServerStates() = runTest {
        val wire = Wire(); val repo = repository(wire)
        wire.responses += WireResponse(200, CommerceFixtures.walletFirstModel); repo.loadModel()
        wire.responses += WireResponse(200, CommerceFixtures.deferred.replace("\"state\":\"reserved\"", "\"state\":\"settled\""))
        val transition = repo.purchaseHistoryDetail(CommerceModel.WALLET_FIRST, "transition_fixture_plan") as PurchaseResult.Deferred
        assertEquals("settled", transition.state); assertNull(transition.settledAt)
        val recharge = JSONObject(CommerceFixtures.rechargeOrder).getJSONObject("recharge").getJSONObject("order")
        recharge.put("nextAction", JSONObject.NULL)
        for (status in listOf("payment_verified", "credited")) {
            recharge.put("status", status)
            wire.responses += WireResponse(200, JSONObject().put("recharge", JSONObject().put("order", recharge)).toString())
            val value = repo.rechargeHistoryDetail("recharge_fixture_order")
            assertEquals(status, value.status); assertNull(value.walletOperationRef); assertNull(value.walletLotRef)
        }
    }
}
