package com.gccwebos.android.portal

import com.gccwebos.android.core.*
import com.gccwebos.android.vpn.SafeProxyConfig
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Providers may reorder keys and rewrite notice entries on every download (synthetic material only). */
class StableNodeSelectionTest {
    private val url = "https://subscription.example/sub/synthetic"
    private data class Node(val name: String, val host: String)
    private val realNodes = listOf(Node("Synthetic 01", "192.0.2.11"), Node("Synthetic 02", "192.0.2.12"),
        Node("Synthetic 03", "192.0.2.13"))

    /** Same proxies, but key order rotates and the traffic notice changes with every [read]. */
    private fun yaml(read: Int, nodes: List<Node> = realNodes): ByteArray {
        val notices = listOf(Node("🇦🇱 剩余流量：${246 - read}.85 GB", "192.0.2.99"), Node("🇦🇱 套餐到期：2026-10-12", "192.0.2.99"))
        val entries = (notices + nodes).map { node ->
            val fields = listOf("name: \"${node.name}\"", "type: vless", "server: ${node.host}", "port: 443",
                "uuid: 11111111-2222-4333-8444-555555555555", "udp: true", "tls: true", "network: tcp",
                "servername: www.example.com", "client-fingerprint: chrome", "flow: xtls-rprx-vision",
                "encryption: none", "skip-cert-verify: false",
                "reality-opts: {public-key: AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA, short-id: 0a1b}")
            val shift = read % fields.size
            (fields.drop(shift) + fields.take(shift)).joinToString(", ", "  - {", "}")
        }
        return ("mixed-port: 7890\nproxies:\n" + entries.joinToString("\n") + "\n").toByteArray()
    }

    private inner class Fixture {
        val now = Instant.parse("2026-10-09T00:00:00Z")
        var reads = 0
        var nodes = realNodes
        val delivery = object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = error("External import must not create delivery")
            override fun clear() = Unit
        }
        val source = object : SubscriptionSource {
            override suspend fun download(url: Secret) = read(url).bytes
            override suspend fun read(url: Secret) = SubscriptionRead(yaml(reads++, nodes), now.plusSeconds(86_400))
        }
        val manager = LocalSubscriptionManager(delivery, MemoryLocalProfileStore(), source,
            OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { now },
            LocalSubscriptionAuthority(), manualSources = MemoryManualSourceStore(), externalSource = source)
        var writes = 0
        val files = PreparedConfigStore { writes++ }
        suspend fun import() = manager.importManual(url.toByteArray()) { it() }
        fun choice(name: String) = manager.nodeSelectionSnapshot().choices.single { it.name == name }
    }

    @Test fun providerBodiesReallyDifferBetweenReads() {
        assertFalse(yaml(0).contentEquals(yaml(1)))
        assertEquals(SafeProxyConfig.selectionKeys(yaml(0)).drop(2), SafeProxyConfig.selectionKeys(yaml(1)).drop(2))
        assertNotEquals(SafeProxyConfig.selectionKeys(yaml(0))[0], SafeProxyConfig.selectionKeys(yaml(1))[0])
    }

    @Test fun selectedNodeSurvivesShuffledKeysAndChangingNotice() = runTest {
        val f = Fixture(); f.import()
        val chosen = f.choice("Synthetic 02")
        assertTrue(f.manager.selectNode(chosen.selection))
        val ready = f.manager.prepare(SelectionIntent.Selected(chosen.selection), f.files)
        assertTrue(ready is ConfigPreparation.Ready); (ready as ConfigPreparation.Ready).config.close()
        assertEquals(2, f.reads)
        assertSame(chosen.selection, f.manager.nodeSelectionSnapshot().selected)
        assertSame(chosen.selection, f.choice("Synthetic 02").selection)
        // The notice is a new entry each read, but never selected.
        assertTrue(f.manager.nodeSelectionSnapshot().choices.take(2).all { it.info })
        assertTrue(f.manager.refresh().status == LocalSubscriptionStatus.READY)
        assertSame(chosen.selection, f.manager.nodeSelectionSnapshot().selected)
    }

    @Test fun oneClickRandomPickAndChoiceWorkAcrossRedownloads() = runTest {
        val f = Fixture(); f.import()
        val picked = mutableListOf<List<ConnectionChoice>>()
        val gateway = OneClickConfigGateway(f.manager, f.files, chooseUnselected = { choices ->
            picked += choices; choices.filterNot { it.info }.last() })
        val first = gateway.prepare(SelectionIntent.Choose)
        assertTrue(first is ConfigPreparation.Ready); (first as ConfigPreparation.Ready).config.close()
        assertEquals("Synthetic 03", f.manager.nodeSelectionSnapshot().let { s -> s.choices.single { it.selection === s.selected }.name })
        val again = gateway.prepare(SelectionIntent.Choose)
        assertTrue(again is ConfigPreparation.Ready); (again as ConfigPreparation.Ready).config.close()
        assertEquals(1, picked.size)
    }

    @Test fun defaultRandomPickNeverChoosesNotice() = runTest {
        repeat(20) {
            val f = Fixture(); f.import()
            val result = OneClickConfigGateway(f.manager, f.files).prepare(SelectionIntent.Choose)
            assertTrue(result is ConfigPreparation.Ready); (result as ConfigPreparation.Ready).config.close()
            val s = f.manager.nodeSelectionSnapshot()
            assertFalse(s.choices.single { it.selection === s.selected }.info)
        }
    }

    @Test fun noticeIsNotSelectableOrPreparedOrProbed() = runTest {
        val f = Fixture(); f.import()
        val notice = f.manager.nodeSelectionSnapshot().choices.first()
        assertTrue(notice.info)
        assertFalse(f.manager.selectNode(notice.selection))
        val result = f.manager.prepare(SelectionIntent.Selected(notice.selection), f.files)
        assertTrue(result is ConfigPreparation.SelectionRequired)
        assertEquals(0, f.writes)
        val targets = f.manager.captureNodeProbeTargets()
        assertEquals(listOf("Synthetic 01", "Synthetic 02", "Synthetic 03"), targets.map { t ->
            f.manager.nodeSelectionSnapshot().choices.single { it.selection === t.selection }.name })
        // The notice stays in the saved configuration and the displayed list.
        assertEquals(5, f.manager.snapshot().nodes.size)
        assertEquals(2, f.manager.snapshot().nodes.count { it.info })
    }

    @Test fun removedSelectedNodeRequiresNewSelection() = runTest {
        val f = Fixture(); f.import()
        val chosen = f.choice("Synthetic 02"); val kept = f.choice("Synthetic 03")
        assertTrue(f.manager.selectNode(chosen.selection))
        f.nodes = realNodes.filterNot { it.name == "Synthetic 02" }
        val result = f.manager.prepare(SelectionIntent.Selected(chosen.selection), f.files)
        assertTrue(result is ConfigPreparation.SelectionRequired)
        assertNull(f.manager.nodeSelectionSnapshot().selected)
        assertSame(kept.selection, f.choice("Synthetic 03").selection)
        assertEquals(0, f.writes)
    }

    @Test fun changedEndpointIsANewNode() = runTest {
        val f = Fixture(); f.import()
        val chosen = f.choice("Synthetic 01")
        assertTrue(f.manager.selectNode(chosen.selection))
        f.nodes = realNodes.map { if (it.name == "Synthetic 01") it.copy(host = "192.0.2.21") else it }
        assertTrue(f.manager.prepare(SelectionIntent.Selected(chosen.selection), f.files) is ConfigPreparation.SelectionRequired)
        assertNotSame(chosen.selection, f.choice("Synthetic 01").selection)
    }

    @Test fun infoMatcherIsConservative() {
        for (name in listOf("🇦🇱 剩余流量：246.85 GB", "套餐到期：2026-10-12", "官网：example.com", "剩余流量: 10 GB",
            "到期时间：2026-12-01", "客服：@support", "Traffic: 10 GB", "Expire: 2026-10-12", "距离下次重置剩余：3 天".replace("剩余", "")))
            assertTrue(name, SafeProxyConfig.isInfoNodeName(name))
        for (name in listOf("🇭🇰 Hong Kong 06", "香港 01 流量倍率 1x", "官网专线 01", "US-专线优化-vless", "Expired-Relay 01",
            "日本 | 到期续费节点", "Taiwan 03", "流量 0.5x 新加坡"))
            assertFalse(name, SafeProxyConfig.isInfoNodeName(name))
    }

    @Test fun defaultNamesContinueAfterHighestNumber() {
        assertEquals("配置 1", nextConfigurationName(emptyList()))
        assertEquals("配置 3", nextConfigurationName(listOf("配置 1", "配置 2")))
        assertEquals("配置 6", nextConfigurationName(listOf("配置 2", "配置 5")))
        assertEquals("配置 3", nextConfigurationName(listOf("配置 2")))
        assertEquals("配置 3", nextConfigurationName(listOf("Kitty", "配置 2")))
        assertEquals("配置 3", nextConfigurationName(listOf("Kitty", "家里")))
        assertEquals("配置 2", nextConfigurationName(listOf("配置 1x", "配置 01", "配置 1")))
    }
}
