package com.gccwebos.android

import com.gccwebos.android.portal.*
import com.gccwebos.android.vpn.SafeProxyConfig
import kotlinx.coroutines.CoroutineScope
import java.time.Instant

/** UI-only fixture; its transport fails any attempted request rather than touching production. */
internal fun offlineUiAccount(scope: CoroutineScope): AccountController {
    val store = object : DeliveryStore {
        override fun read(): DeliveryRecord? = null
        override fun save(record: DeliveryRecord) = Unit
        override fun clear() = Unit
    }
    val wire = object : PortalTransport {
        override suspend fun request(path: String, body: String?, session: Secret?): WireResponse = error("Offline UI fixture cannot request")
    }
    return AccountController(PortalRepository(wire, store, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), {0}), scope).apply {
        authenticated = true; account = "fixture@example.invalid"
        resource = Resource.Available("resource_" + "a".repeat(64), "synthetic", Instant.now().plusSeconds(47 * 86400), false,
            Usage(100L * 1024 * 1024 * 1024, 20L * 1024 * 1024 * 1024, 80L * 1024 * 1024 * 1024, "measured", Instant.now()), 365, 100.0)
        importStatus = ImportStatus.IMPORTED
        nodes = listOf(SafeProxyConfig.NodeSummary(0, "合成验收线路 Alpha", "ss", true), SafeProxyConfig.NodeSummary(1, "合成验收线路 Beta", "vless", true))
    }
}
