package com.gccwebos.android.portal

import com.gccwebos.android.core.*
import com.gccwebos.android.vpn.SafeProxyConfig
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** C3: the user's node choice survives an app restart by re-matching a one-way key, never by handle or name. */
class NodeChoicePersistenceTest {
    private val url = "https://subscription.example/sub/synthetic"
    private data class Node(val name: String, val host: String)
    private val realNodes = listOf(Node("Synthetic 01", "192.0.2.11"), Node("Synthetic 02", "192.0.2.12"),
        Node("Synthetic 03", "192.0.2.13"))

    private fun yaml(nodes: List<Node>): ByteArray {
        val entries = nodes.map { node ->
            listOf("name: \"${node.name}\"", "type: vless", "server: ${node.host}", "port: 443",
                "uuid: 11111111-2222-4333-8444-555555555555", "udp: true", "tls: true", "network: tcp",
                "servername: www.example.com", "client-fingerprint: chrome", "flow: xtls-rprx-vision",
                "encryption: none", "skip-cert-verify: false",
                "reality-opts: {public-key: AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA, short-id: 0a1b}")
                .joinToString(", ", "  - {", "}")
        }
        return ("mixed-port: 7890\nproxies:\n" + entries.joinToString("\n") + "\n").toByteArray()
    }

    /** Everything that outlives the process: manual-source library and the node-choice sidecar. */
    private inner class Device {
        val now = Instant.parse("2026-10-09T00:00:00Z")
        var nodes = realNodes
        val sources = MemoryManualSourceStore()
        val choices = MemoryNodeChoiceStore()
        val delivery = object : DeliveryStore {
            override fun read(): DeliveryRecord? = null
            override fun save(record: DeliveryRecord) = error("External import must not create delivery")
            override fun clear() = Unit
        }
        val source = object : SubscriptionSource {
            override suspend fun download(url: Secret) = read(url).bytes
            override suspend fun read(url: Secret) = SubscriptionRead(yaml(nodes), now.plusSeconds(86_400))
        }
        val files = PreparedConfigStore { }
        /** A fresh process: new authority (empty memory), same stores. */
        fun launch(store: NodeChoiceStore? = choices) = LocalSubscriptionManager(delivery, MemoryLocalProfileStore(), source,
            OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { now },
            LocalSubscriptionAuthority(), manualSources = sources, externalSource = source, nodeChoices = store)
    }

    private fun LocalSubscriptionManager.named(name: String) = nodeSelectionSnapshot().choices.single { it.name == name }
    private fun LocalSubscriptionManager.selectedName() =
        nodeSelectionSnapshot().let { s -> s.choices.firstOrNull { it.selection === s.selected }?.name }

    @Test fun choiceSurvivesProcessDeathAndIsRematchedAgainstTheLocalLibrary() = runTest {
        val device = Device()
        val first = device.launch()
        first.importManual(url.toByteArray()) { it() }
        assertNull(first.selectedName())
        assertTrue(first.selectNode(first.named("Synthetic 02").selection))

        val second = device.launch() // upgrade / process death: nothing in memory
        assertNull(second.nodeSelectionSnapshot().selected)
        assertEquals(LocalSubscriptionStatus.READY, second.restore().status)
        assertEquals("Synthetic 02", second.selectedName())
        assertFalse(second.nodeSelectionSnapshot().selectionLost)
        // The restored handle is the ordinary registry object: full admission still runs and succeeds.
        val ready = second.prepare(SelectionIntent.Selected(second.named("Synthetic 02").selection), device.files)
        assertTrue(ready is ConfigPreparation.Ready); (ready as ConfigPreparation.Ready).config.close()
    }

    @Test fun missingNodeFallsBackToUnselectedWithALostHintAndNeverPicksAnother() = runTest {
        val device = Device()
        val first = device.launch()
        first.importManual(url.toByteArray()) { it() }
        assertTrue(first.selectNode(first.named("Synthetic 02").selection))

        device.nodes = realNodes.filterNot { it.name == "Synthetic 02" }
        val second = device.launch()
        second.refresh()
        val snapshot = second.nodeSelectionSnapshot()
        assertNull(snapshot.selected)
        assertTrue(snapshot.selectionLost)
        assertTrue(second.prepare(SelectionIntent.Choose, device.files) is ConfigPreparation.SelectionRequired)
        // A new explicit choice clears the hint and is remembered in turn.
        assertTrue(second.selectNode(second.named("Synthetic 03").selection))
        assertFalse(second.nodeSelectionSnapshot().selectionLost)
        assertEquals("Synthetic 03", device.launch().also { it.restore() }.selectedName())
    }

    @Test fun editedNodeIsADifferentNodeSoItIsNotRestored() = runTest {
        val device = Device()
        val first = device.launch()
        first.importManual(url.toByteArray()) { it() }
        assertTrue(first.selectNode(first.named("Synthetic 01").selection))
        device.nodes = realNodes.map { if (it.name == "Synthetic 01") it.copy(host = "192.0.2.21") else it }
        val second = device.launch(); second.refresh()
        assertNull(second.nodeSelectionSnapshot().selected)
        assertTrue(second.nodeSelectionSnapshot().selectionLost)
    }

    @Test fun duplicateNodesAreAmbiguousAndFailClosed() = runTest {
        val device = Device()
        val first = device.launch()
        first.importManual(url.toByteArray()) { it() }
        assertTrue(first.selectNode(first.named("Synthetic 03").selection))
        device.nodes = realNodes + realNodes.last()
        val second = device.launch(); second.refresh()
        assertNull(second.nodeSelectionSnapshot().selected)
        assertTrue(second.nodeSelectionSnapshot().selectionLost)
    }

    @Test fun nothingRememberedMeansNoSelectionAndNoHint() = runTest {
        val device = Device()
        val first = device.launch()
        first.importManual(url.toByteArray()) { it() }
        val second = device.launch(); second.restore()
        val snapshot = second.nodeSelectionSnapshot()
        assertNull(snapshot.selected); assertFalse(snapshot.selectionLost)
    }

    @Test fun withoutAStoreSelectionStaysMemoryOnly() = runTest {
        val device = Device()
        val first = device.launch(store = null)
        first.importManual(url.toByteArray()) { it() }
        assertTrue(first.selectNode(first.named("Synthetic 02").selection))
        assertNull(device.launch(store = null).also { it.restore() }.nodeSelectionSnapshot().selected)
    }

    @Test fun storedValueIsAOneWayKeyWithoutNameHandleOrCredential() = runTest {
        val device = Device()
        val first = device.launch()
        first.importManual(url.toByteArray()) { it() }
        assertTrue(first.selectNode(first.named("Synthetic 02").selection))
        val stored = device.choices.read()
        assertEquals(1, stored.size)
        val (scope, key) = stored.entries.single()
        assertTrue(scope.startsWith("entry:"))
        val memoryKeys = SafeProxyConfig.selectionKeys(yaml(realNodes))
        assertFalse(memoryKeys.contains(key)) // never the process-only key itself
        assertNotEquals(memoryKeys[1], key)
        assertFalse(key.contains("Synthetic")); assertFalse(scope.contains("192.0.2"))
        assertEquals(NodeChoiceKey.persisted(memoryKeys[1]), key)
    }

    @Test fun oneClickAfterRestartUsesTheRememberedNodeNotARandomOne() = runTest {
        val device = Device()
        val first = device.launch()
        first.importManual(url.toByteArray()) { it() }
        assertTrue(first.selectNode(first.named("Synthetic 02").selection))

        val second = device.launch()
        second.restore()
        second.invalidateNodeSelection() // e.g. login revoked every opaque handle: registry empty again
        assertNull(second.nodeSelectionSnapshot().selected)
        val gateway = OneClickConfigGateway(second, device.files, chooseUnselected = { choices ->
            choices.filterNot { it.info }.first() })
        val result = gateway.prepare(SelectionIntent.Choose)
        assertTrue(result is ConfigPreparation.Ready)
        (result as ConfigPreparation.Ready).config.close()
        assertEquals("Synthetic 02", result.node?.name)
        assertEquals("Synthetic 02", second.selectedName())
    }

    /** N1: a login / session refresh revokes every opaque handle while connected; the local rebuild keeps the choice. */
    @Test fun handleRevocationThenLocalRebuildRestoresTheSameNode() = runTest {
        val device = Device()
        val manager = device.launch()
        manager.importManual(url.toByteArray()) { it() }
        assertTrue(manager.selectNode(manager.named("Synthetic 02").selection))
        val before = manager.named("Synthetic 02").selection
        manager.invalidateNodeSelection()
        assertTrue(manager.nodeSelectionSnapshot().choices.isEmpty())
        assertTrue(manager.rebuildNodeSelectionFromLocal())
        val after = manager.nodeSelectionSnapshot()
        assertNotSame(before, manager.named("Synthetic 02").selection) // fresh opaque identity
        assertEquals("Synthetic 02", manager.selectedName())
        assertEquals(3, after.choices.count { !it.info })
        assertFalse(after.selectionLost)
    }

    @Test fun clearingTheAppForgetsTheChoice() = runTest {
        val device = Device()
        val first = device.launch()
        first.importManual(url.toByteArray()) { it() }
        assertTrue(first.selectNode(first.named("Synthetic 02").selection))
        assertEquals(1, device.choices.read().size)
        first.unlinkAndAwait({ }, { it() })
        assertTrue(device.choices.read().isEmpty())
    }
}
