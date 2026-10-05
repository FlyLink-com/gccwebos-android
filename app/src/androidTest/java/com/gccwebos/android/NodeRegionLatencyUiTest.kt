package com.gccwebos.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.design.GlassMode
import com.gccwebos.android.portal.*
import com.gccwebos.android.vpn.SafeProxyConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Memory-only config and fake Ping: no Android probe, Portal transport, tunnel or persistent store. */
class NodeRegionLatencyUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var state: NodeSelectionController
    private lateinit var latency: NodeLatencyController
    private val reads = AtomicInteger()
    private val measurements = AtomicInteger()
    private val navigationCalls = AtomicInteger()
    private val elapsed = AtomicLong(10_000L)
    private val now = Instant.parse("2026-10-04T00:00:00Z")
    private var accountBusy by mutableStateOf(false)
    private var showPanel by mutableStateOf(true)
    private var inspectedNodes by mutableStateOf<List<SafeProxyConfig.NodeSummary>>(emptyList())
    private var response: suspend (NodeProbeTarget) -> NodeLatencyObservation = {
        NodeLatencyObservation.Success(27L, 7L)
    }
    private var bytes = """proxies:
  - name: "🇯🇵 Synthetic Japan"
    type: ss
    server: 192.0.2.10
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
  - name: Synthetic Relay
    type: ss
    server: 192.0.2.11
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""".toByteArray()

    private fun launch(withLatency: Boolean = true, width: Int = 360, fontScale: Float = 1f) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val owner = remember {
                val delivery = object : DeliveryStore {
                    override fun read() = DeliveryRecord("synthetic", "synthetic", "synthetic", "synthetic",
                        Secret("https://sub.invalid/sub/a/synthetic"), null, false)
                    override fun save(record: DeliveryRecord) = error("No delivery writes")
                    override fun clear() = Unit
                }
                val source = object : SubscriptionSource {
                    override suspend fun download(url: Secret) = read(url).bytes
                    override suspend fun read(url: Secret): SubscriptionRead {
                        reads.incrementAndGet()
                        return SubscriptionRead(bytes.copyOf(), now.plusSeconds(3600))
                    }
                }
                val manager = LocalSubscriptionManager(delivery, MemoryLocalProfileStore(), source,
                    OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")),
                    { now }, LocalSubscriptionAuthority())
                val probe = object : NodeLatencyProbe {
                    override fun currentNetworkKey() = 7L
                    override suspend fun measure(target: NodeProbeTarget): NodeLatencyObservation {
                        measurements.incrementAndGet()
                        return response(target)
                    }
                }
                latency = NodeLatencyController(manager, scope, probe, { 0L }, allowed = { !accountBusy },
                    elapsedMillis = { elapsed.get() }, ioDispatcher = Dispatchers.Main.immediate)
                NodeSelectionController(manager, scope, { 0L }, allowed = { !accountBusy },
                    onSnapshot = { inspectedNodes = it.nodes }).also { state = it }
            }
            LaunchedEffect(owner) { owner.refresh() } // Explicit synthetic fixture seed, one local read.
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GccTheme(materialMode = GlassMode.Solid) {
                    Column(Modifier.width(width.dp).fillMaxHeight().testTag("region-latency-host")
                        .verticalScroll(rememberScrollState())) {
                        if (showPanel) NodeSelectionPanel(owner, inspectedNodes, null, true, accountBusy,
                            onSelected = { navigationCalls.incrementAndGet() },
                            latency = latency.takeIf { withLatency }) {}
                    }
                }
            }
        }
        compose.waitUntil(5000) { ::state.isInitialized && !state.busy && reads.get() == 1 }
    }

    private fun badge(index: Int) = compose.onNodeWithTag("node-latency-$index", useUnmergedTree = true)
    private fun region(index: Int) = compose.onNodeWithTag("node-region-$index", useUnmergedTree = true)
    private fun awaitObservation(index: Int) {
        compose.waitUntil(5000) { latency.reading(state.snapshot.choices[index].selection) != null }
    }

    private fun capture(name: String) {
        compose.onNodeWithTag("node-choice-0").performScrollTo().assertIsDisplayed()
        region(0).assertIsDisplayed()
        badge(0).assertIsDisplayed().assertTextEquals("27 ms")
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "ui-evidence/preview-v2")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use { stream ->
            assertTrue(compose.onNodeWithTag("region-latency-host").captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }

    @Test fun labelsRecognizeExplicitRegionAndLeaveUnknownNeutralWithUnknownLatency() {
        launch(withLatency = false)
        region(0).assertContentDescriptionEquals("节点标注地区：日本")
        region(1).assertContentDescriptionEquals("地区未标注")
        badge(0).assertTextEquals("— ms")
        badge(1).assertTextEquals("— ms")
        compose.onNodeWithTag("nodes-selected-region").assertContentDescriptionEquals("地区未标注")
        compose.onNodeWithTag("nodes-selected-latency").assertTextEquals("— ms")
        compose.onNodeWithTag("nodes-latency-refresh").assertDoesNotExist()
        compose.onNodeWithTag("node-choice-0").performScrollTo().performClick()
        compose.onNodeWithTag("nodes-selected-region").assertContentDescriptionEquals("节点标注地区：日本")
        compose.runOnIdle { assertEquals(0, measurements.get()); assertEquals(1, reads.get()) }
    }

    @Test fun automaticMeasurementRetainsSelectionAndCompletedReadingsAcrossPageReturn() {
        val gate = CompletableDeferred<Unit>()
        response = { gate.await(); NodeLatencyObservation.Success(27L, 7L) }
        launch(width = 320)
        compose.waitUntil(5000) { measurements.get() == 2 }
        badge(1).assertTextEquals("— ms")
        compose.runOnIdle { assertTrue(latency.busy) }
        compose.onNodeWithTag("nodes-latency-refresh").assertIsNotEnabled()
        val selected = state.snapshot.choices[1].selection
        compose.onNodeWithTag("node-choice-1").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertSame(selected, state.snapshot.selected); gate.complete(Unit) }
        awaitObservation(1)
        badge(1).assertTextEquals("27 ms")
        compose.onNodeWithTag("nodes-selected-latency").assertTextEquals("27 ms")
        compose.runOnIdle { assertFalse(latency.busy) }
        compose.runOnIdle { showPanel = false }
        compose.runOnIdle { assertFalse(latency.busy); showPanel = true }
        compose.onNodeWithTag("node-choice-1").assertIsSelected()
        badge(1).assertTextEquals("27 ms")
        compose.runOnIdle {
            assertSame(selected, state.snapshot.selected)
            assertEquals(2, measurements.get())
            assertEquals(1, navigationCalls.get())
            assertEquals(1, reads.get())
        }
        capture("node-region-320-font100")
    }

    @Test fun lightningRemeasuresWithoutReloadingConfigOrChangingSelection() {
        response = { NodeLatencyObservation.NoReply }
        launch()
        awaitObservation(0)
        badge(0).assertTextEquals("— ms")
        compose.onNodeWithTag("nodes-latency-hint").assertDoesNotExist()
        val selected = state.snapshot.choices[0].selection
        compose.runOnIdle { assertSame(NodeLatencyObservation.NoReply, latency.reading(selected)) }
        compose.onNodeWithTag("node-choice-0").performScrollTo().assertIsEnabled().performClick().assertIsSelected()
        compose.runOnIdle {
            assertSame(selected, state.snapshot.selected)
            elapsed.addAndGet(30_000L) // Explicit refresh cooldown has elapsed on this fake clock.
            response = { NodeLatencyObservation.Success(19L, 7L) }
        }
        compose.onNodeWithTag("nodes-latency-refresh")
            .assertContentDescriptionEquals("测试节点延迟")
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            .assertIsEnabled().performClick()
        compose.waitUntil(5000) {
            latency.reading(selected) == NodeLatencyObservation.Success(19L, 7L) && !latency.busy
        }
        badge(0).assertTextEquals("19 ms")
        compose.runOnIdle {
            assertSame(selected, state.snapshot.selected)
            assertEquals(4, measurements.get()); assertEquals(1, reads.get())
        }
        // A second click during the controller cooldown cannot multiply probes.
        compose.onNodeWithTag("nodes-latency-refresh").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(4, measurements.get()); assertEquals(1, reads.get()) }
        compose.onNodeWithText("测试节点延迟").assertDoesNotExist()
    }

    @Test fun accountBusyCancelsPendingMeasurementsWithoutTurningPingIntoSelectionAuthorization() {
        val gate = CompletableDeferred<Unit>()
        response = { gate.await(); NodeLatencyObservation.Success(27L, 7L) }
        launch()
        compose.waitUntil(5000) { measurements.get() == 2 }
        compose.runOnIdle { accountBusy = true }
        compose.waitUntil(5000) { !latency.busy }
        compose.onNodeWithTag("nodes-latency-refresh").assertIsNotEnabled()
        compose.onNodeWithTag("node-choice-0").assertIsNotEnabled()
        compose.runOnIdle { gate.complete(Unit) }
        badge(0).assertTextEquals("— ms")
        compose.runOnIdle { accountBusy = false }
        awaitObservation(0)
        compose.onNodeWithTag("node-choice-0").assertIsEnabled()
        compose.runOnIdle { assertNull(state.snapshot.selected); assertEquals(1, reads.get()) }
    }

    @Test fun elapsedResultsHideThenRemeasureWithoutReloadingOrChangingSelection() {
        launch()
        awaitObservation(0)
        compose.onNodeWithTag("node-choice-0").performScrollTo().performClick()
        val selected = state.snapshot.selected
        badge(0).assertTextEquals("27 ms")
        val gate = CompletableDeferred<Unit>()
        compose.runOnIdle {
            response = { gate.await(); NodeLatencyObservation.Success(19L, 7L) }
            elapsed.addAndGet(120_000L)
        }
        compose.waitUntil(5000) {
            measurements.get() == 4 && compose.onAllNodes(hasTestTag("node-latency-0") and hasText("— ms"), useUnmergedTree = true)
                .fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("nodes-selected-latency").assertTextEquals("— ms")
        compose.runOnIdle {
            assertSame(selected, state.snapshot.selected)
            assertEquals(4, measurements.get()); assertEquals(1, reads.get()); gate.complete(Unit)
        }
        compose.waitUntil(5000) { latency.reading(selected) == NodeLatencyObservation.Success(19L, 7L) }
        compose.onNodeWithTag("nodes-selected-latency").assertTextEquals("19 ms")
    }

    @Test fun backgroundSuspensionRejectsRecompositionUntilForegroundReturns() {
        val gate = CompletableDeferred<Unit>()
        response = { gate.await(); NodeLatencyObservation.Success(27L, 7L) }
        launch()
        compose.waitUntil(5000) { measurements.get() == 2 }
        compose.runOnIdle { latency.setForeground(false) }
        compose.waitUntil(5000) { !latency.busy }
        compose.runOnIdle { accountBusy = true }
        compose.runOnIdle { accountBusy = false }
        compose.onNodeWithTag("nodes-latency-refresh").assertIsNotEnabled()
        badge(0).assertTextEquals("— ms")
        compose.runOnIdle { assertEquals(2, measurements.get()); latency.setForeground(true) }
        compose.waitUntil(5000) { measurements.get() == 4 }
        compose.runOnIdle { gate.complete(Unit) }
        awaitObservation(0)
        badge(0).assertTextEquals("27 ms")
        compose.runOnIdle { assertNull(state.snapshot.selected); assertEquals(1, reads.get()) }
    }

    @Test fun readOnlyInspectedNodesShowRegionWithoutMeasuringOrExposingChoice() {
        bytes = bytes.toString(Charsets.UTF_8).replaceFirst("type: ss", "type: vmess").toByteArray()
        launch()
        compose.onNodeWithTag("subscription-node-region-0", useUnmergedTree = true)
            .assertContentDescriptionEquals("节点标注地区：日本")
        compose.onNodeWithTag("subscription-node-latency-0", useUnmergedTree = true).assertTextEquals("— ms")
        compose.onNodeWithTag("node-choice-0").assertDoesNotExist()
        compose.onNodeWithTag("nodes-latency-refresh").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(LocalSubscriptionStatus.UNSUPPORTED, state.snapshot.status)
            assertTrue(state.snapshot.choices.isEmpty())
            assertNull(state.snapshot.selected)
            assertEquals(0, measurements.get()); assertEquals(1, reads.get())
        }
    }

    @Test fun narrowDoubledFontKeepsRegionLatencyAndSelectionInsideTheViewport() {
        launch(width = 320, fontScale = 2f)
        awaitObservation(1)
        val host = compose.onNodeWithTag("region-latency-host").fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithTag("node-choice-1")
        row.performScrollTo().assertIsDisplayed().assertIsEnabled()
        listOf(row, region(1), badge(1)).forEach { node ->
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= host.left); assertTrue(bounds.right <= host.right)
        }
        badge(1).assertTextEquals("27 ms")
        row.performClick().assertIsSelected()
        compose.onNodeWithTag("nodes-latency-refresh").assertIsEnabled()
        compose.onNodeWithTag("nodes-latency-hint").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, reads.get()); assertEquals(2, measurements.get()) }
        capture("node-region-320-font200")
    }
}
