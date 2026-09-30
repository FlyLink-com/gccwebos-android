package com.gccwebos.android.commerce

import com.gccwebos.android.portal.OriginPolicy
import com.gccwebos.android.vpn.SafeProxyConfig
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class MobileParityContractTest {
    @Test fun orderPaginationQueryIsNarrowAndCannotCarrySessionOrOtherOptions() {
        val policy = OriginPolicy("https://portal.invalid", setOf("https://sub.invalid"))
        assertEquals("https://portal.invalid/api/portal/commerce/orders?limit=20", policy.apiUrl("/api/portal/commerce/orders?limit=20"))
        policy.apiUrl("/api/portal/commerce/orders?limit=20&afterCreatedAt=2026-09-30T00%3A00%3A00.000Z&afterOrderRef=order_synthetic")
        for (path in listOf("/api/portal/coins/wallet?limit=20", "/api/portal/commerce/orders?limit=20&sessionRef=secret", "/api/portal/commerce/orders?limit=20&limit=20", "/api/portal/commerce/orders?limit=1000", "/api/portal/commerce/orders?limit=20&afterOrderRef=order_synthetic")) {
            assertThrows(IllegalArgumentException::class.java) { policy.apiUrl(path) }
        }
    }
    @Test fun emptyOrderResponsesAreAuthoritativeAndMalformedCursorsFail() {
        assertEquals(emptyList<PurchaseResult.Order>(), CommerceJson.orders("""{"orders":[],"nextCursor":null}""").orders)
        assertEquals(emptyList<PurchaseResult.Deferred>(), CommerceJson.transitions("""{"transitions":[]}"""))
        assertThrows(IllegalArgumentException::class.java) {
            CommerceJson.orders("""{"orders":[],"nextCursor":{"afterCreatedAt":"2026-09-30T00:00:00.000Z","afterOrderRef":"order_synthetic"}}""")
        }
    }
    @Test fun orderHistoryEnforcesOrderingUniquenessAndCursorBinding() {
        val first = JSONObject(CommerceFixtures.order).getJSONObject("order").put("orderRef", "order_z")
        val second = JSONObject(first.toString()).put("orderRef", "order_a")
        val body = JSONObject().put("orders", JSONArray().put(first).put(second)).put("nextCursor", JSONObject()
            .put("afterCreatedAt", CommerceFixtures.instant).put("afterOrderRef", "order_a"))
        val page = CommerceJson.orders(body.toString())
        assertEquals(listOf("order_z", "order_a"), page.orders.map { it.ref })
        assertEquals("order_a", page.nextCursor!!.afterOrderRef)
        body.getJSONObject("nextCursor").put("afterOrderRef", "order_z")
        assertThrows(IllegalArgumentException::class.java) { CommerceJson.orders(body.toString()) }
        body.put("nextCursor", JSONObject.NULL).put("orders", JSONArray().put(first).put(first))
        assertThrows(IllegalArgumentException::class.java) { CommerceJson.orders(body.toString()) }
        body.put("orders", JSONArray().put(second).put(first))
        assertThrows(IllegalArgumentException::class.java) { CommerceJson.orders(body.toString()) }
    }
    @Test fun ledgerHasStrictArithmeticCursorAndQueryBoundaries() {
        fun entry(ref: String = "entry_z") = JSONObject().put("entryRef", ref).put("category", "available_to_held")
            .put("coinType", "paid_coins").put("amountUnits", 25).put("availableDeltaUnits", -25)
            .put("pendingDeltaUnits", 0).put("heldDeltaUnits", 25).put("totalDeltaUnits", 0)
            .put("relatedOrderRef", JSONObject.NULL).put("occurredAt", "2026-09-30T00:00:00.000Z")
        val body = JSONObject().put("entries", JSONArray().put(entry())).put("nextCursor", JSONObject()
            .put("afterOccurredAt", "2026-09-30T00:00:00.000Z").put("afterEntryRef", "entry_z"))
        val page = CommerceJson.ledger(body.toString())
        assertEquals(25L, page.entries.single().amountUnits)
        assertEquals("entry_z", page.nextCursor!!.afterEntryRef)
        body.getJSONArray("entries").getJSONObject(0).put("heldDeltaUnits", 24)
        assertThrows(IllegalArgumentException::class.java) { CommerceJson.ledger(body.toString()) }
        body.put("entries", JSONArray().put(entry())).getJSONObject("nextCursor").put("afterEntryRef", "entry_other")
        assertThrows(IllegalArgumentException::class.java) { CommerceJson.ledger(body.toString()) }
        body.put("nextCursor", JSONObject.NULL).put("entries", JSONArray().put(entry()).put(entry()))
        assertThrows(IllegalArgumentException::class.java) { CommerceJson.ledger(body.toString()) }
        assertTrue(CommerceJson.ledger("{\"entries\":[],\"nextCursor\":null}").entries.isEmpty())
        val policy = OriginPolicy("https://portal.invalid", setOf("https://sub.invalid"))
        policy.apiUrl("/api/portal/coins/wallet/ledger?limit=20&afterOccurredAt=2026-09-30T00%3A00%3A00.000Z&afterEntryRef=entry_z")
        for (query in listOf("limit=20&afterEntryRef=entry_z", "limit=20&afterCreatedAt=2026-09-30T00%3A00%3A00.000Z&afterOrderRef=entry_z", "limit=20&limit=20", "limit=20&sessionRef=secret")) {
            assertThrows(IllegalArgumentException::class.java) { policy.apiUrl("/api/portal/coins/wallet/ledger?$query") }
        }
    }
    @Test fun displayNodesContainActualNamesAndNoEndpointOrCredentialFields() {
        val yaml = """proxies:
  - name: Synthetic accepted
    type: ss
    server: 192.0.2.10
    port: 443
    cipher: aes-128-gcm
    password: synthetic-private
  - name: Synthetic unsupported
    type: vmess
    server: example.invalid
    port: 443
""".toByteArray()
        val nodes = SafeProxyConfig.inspect(yaml)
        assertEquals(listOf("Synthetic accepted", "Synthetic unsupported"), nodes.map { it.name })
        assertTrue(nodes[0].supported); assertFalse(nodes[1].supported)
        assertFalse(nodes.toString().contains("synthetic-private")); assertFalse(nodes.toString().contains("192.0.2.10"))
        assertThrows(IllegalArgumentException::class.java) { SafeProxyConfig.project(yaml, 0) }
    }
}
