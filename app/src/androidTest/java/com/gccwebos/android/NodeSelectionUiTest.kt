package com.gccwebos.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.design.GlassMode
import com.gccwebos.android.portal.*
import com.gccwebos.android.vpn.SafeProxyConfig
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Only in-memory fixtures. No Portal, credentials, delivery mutation or engine adapter. */
class NodeSelectionUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var state: NodeSelectionController
    private lateinit var manager: LocalSubscriptionManager
    private val reads = AtomicInteger()
    private var time = Instant.parse("2026-10-01T00:00:00Z")
    private var expiry = time.plusSeconds(3600)
    private var bytes = """proxies:
  - name: 重复节点
    type: ss
    server: 192.0.2.10
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
  - name: 重复节点
    type: ss
    server: 192.0.2.11
    port: 443
    cipher: aes-128-gcm
    password: synthetic-only
""".toByteArray()
    private var pending: CompletableDeferred<Unit>? = null
    private var resource by mutableStateOf<Resource?>(null)
    private var hasCapability by mutableStateOf(true)
    private var connectionControl by mutableStateOf(ConnectionControlState())
    private var inspectedNodes by mutableStateOf<List<SafeProxyConfig.NodeSummary>>(emptyList())
    private var selectionAllowed = true
    private val navigationCalls = AtomicInteger()
    private fun launch(initialRead: Boolean = true, width: Int = 360, fontScale: Float = 1f) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val owner = remember {
                val receipt = object : DeliveryStore {
                    override fun read() = DeliveryRecord("fixture", "fixture", "fixture", "fixture",
                        Secret("https://sub.invalid/sub/a/fixture"), null, false)
                    override fun save(record: DeliveryRecord) = error("No delivery writes")
                    override fun clear() = Unit
                }
                val source = object : SubscriptionSource {
                    override suspend fun download(url: Secret) = read(url).bytes
                    override suspend fun read(url: Secret): SubscriptionRead {
                        reads.incrementAndGet(); pending?.await()
                        return SubscriptionRead(bytes.copyOf(), expiry)
                    }
                }
                manager = LocalSubscriptionManager(receipt, MemoryLocalProfileStore(), source,
                    OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")), { time }, LocalSubscriptionAuthority())
                NodeSelectionController(manager, scope, { 0L }, allowed = { selectionAllowed }, onSnapshot = { inspectedNodes = it.nodes })
                    .also { state = it }
            }
            LaunchedEffect(owner) { if (initialRead) owner.refresh() } // Explicit synthetic fixture setup only.
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GccTheme(materialMode = GlassMode.Solid) {
                    Column(Modifier.width(width.dp).fillMaxHeight().testTag("nodes-fixture-host")
                        .verticalScroll(rememberScrollState())) {
                        NodeSelectionPanel(owner, inspectedNodes, resource, hasCapability, false,
                            connectionControl = connectionControl, onSelected = { navigationCalls.incrementAndGet() }) {}
                    }
                }
            }
        }
        compose.waitUntil(5000) { ::state.isInitialized && !state.busy && (!initialRead || reads.get() > 0) }
    }

    @Test fun acceptedSelectionReturnsToConnectOnlyAfterControllerAcceptedIt() {
        launch()
        val second = state.snapshot.choices[1].selection
        compose.onNodeWithTag("node-choice-1").performClick()
        compose.runOnIdle {
            assertSame(second, state.snapshot.selected)
            assertEquals(1, navigationCalls.get())
            assertEquals(1, reads.get())
            selectionAllowed = false
        }
        compose.onNodeWithTag("node-choice-1").performClick()
        compose.runOnIdle {
            assertEquals(1, navigationCalls.get())
            assertEquals(1, reads.get())
        }
    }

    @Test fun duplicateChoicesStartUnselectedAndClickUsesTheCorrectOpaqueObjectWithoutReading() {
        launch()
        val second = state.snapshot.choices[1].selection
        compose.onNodeWithTag("node-choice-0").assertIsNotSelected()
        compose.onNodeWithTag("node-choice-1").assertIsNotSelected().performClick()
        compose.onNodeWithTag("node-choice-1").assertIsSelected()
        compose.onNodeWithTag("node-choice-0").assertIsNotSelected()
        compose.runOnIdle { assertSame(second, state.snapshot.selected); assertEquals(1, reads.get()) }
        compose.onNodeWithTag("nodes-refresh").assertDoesNotExist()
        compose.onNodeWithTag("nodes-connection-status").assertDoesNotExist()
    }

    @Test fun busyRefreshDisablesSelectionAndControllerCancellationRevokesOldChoices() {
        launch()
        // A normal ready list has no manual reload button. Prepare only the synthetic busy state.
        compose.runOnIdle { pending = CompletableDeferred(); state.refresh() }
        compose.waitUntil(5000) { state.busy && reads.get() == 2 }
        compose.onNodeWithTag("node-choice-0").assertIsNotEnabled()
        compose.runOnIdle { state.cancel() }
        compose.onNodeWithTag("node-choice-0").assertDoesNotExist()
        compose.onNodeWithTag("nodes-refresh").assertDoesNotExist()
        compose.onNodeWithTag("nodes-cancel").assertDoesNotExist()
        compose.runOnIdle { assertFalse(state.busy); assertNull(state.snapshot.selected); pending!!.complete(Unit) }
        compose.onNodeWithTag("node-choice-0").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, reads.get()); assertEquals(0, navigationCalls.get()) }
    }

    @Test fun unsupportedMixedArtifactAndExpiryExposeNoSelectableRows() {
        launch()
        val original = state.snapshot.choices[0].selection
        compose.runOnIdle {
            bytes = bytes.toString(Charsets.UTF_8).replaceFirst("type: ss", "type: vmess").toByteArray()
            state.refresh() // Explicit fixture state setup, never an automatic production reload.
        }
        compose.waitUntil(5000) { !state.busy && state.snapshot.status == LocalSubscriptionStatus.UNSUPPORTED }
        compose.onNodeWithTag("node-choice-0").assertDoesNotExist()
        compose.onNodeWithTag("node-choice-1").assertDoesNotExist()
        compose.onNodeWithTag("subscription-node-0").assertHasNoClickAction()
        compose.onNodeWithTag("subscription-node-1").assertHasNoClickAction()
        compose.runOnIdle {
            assertTrue(state.snapshot.choices.isEmpty())
            assertFalse(state.select(original))
            bytes = bytes.toString(Charsets.UTF_8).replace("type: vmess", "type: ss").toByteArray()
            state.refresh()
        }
        compose.waitUntil(5000) { !state.busy && state.snapshot.status == LocalSubscriptionStatus.READY }
        compose.onNodeWithTag("nodes-refresh").assertDoesNotExist()
        val restored = state.snapshot.choices[0].selection
        compose.runOnIdle { assertTrue(state.select(restored)); time = expiry; state.sync() }
        compose.onNodeWithTag("node-choice-0").assertDoesNotExist()
        compose.onNodeWithTag("node-choice-1").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(LocalSubscriptionStatus.EXPIRED, state.snapshot.status)
            assertNull(state.snapshot.selected)
            assertFalse(state.select(restored))
        }
        compose.runOnIdle { manager.unlink(); resource = Resource.None; hasCapability = false; state.sync() }
        compose.onNodeWithTag("node-choice-0").assertDoesNotExist()
        compose.onNodeWithTag("nodes-account").assertIsEnabled()
        compose.onNodeWithTag("nodes-refresh").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(LocalSubscriptionStatus.MISSING, state.snapshot.status)
            assertTrue(state.snapshot.choices.isEmpty())
            assertEquals(3, reads.get()); assertEquals(0, navigationCalls.get())
        }
    }

    @Test fun firstControllerReadAndCancellationKeepThePageQuietWithoutSelectingOrReloading() {
        launch(initialRead = false, width = 320, fontScale = 2f)
        compose.onNodeWithTag("node-selection-status").assertDoesNotExist()
        compose.onNodeWithTag("nodes-refresh").assertDoesNotExist()
        compose.onNodeWithTag("node-choice-0").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(LocalSubscriptionStatus.MISSING, state.snapshot.status)
            assertEquals(0, reads.get()); pending = CompletableDeferred(); state.refresh()
        }
        compose.waitUntil(5000) { state.busy && reads.get() == 1 }
        compose.onNodeWithTag("nodes-refresh").assertDoesNotExist()
        compose.onNodeWithTag("node-choice-0").assertDoesNotExist()
        compose.runOnIdle { state.cancel() }
        compose.onNodeWithTag("nodes-cancel").assertDoesNotExist()
        compose.onNodeWithTag("nodes-refresh").assertDoesNotExist()
        compose.runOnIdle { assertFalse(state.busy); assertNull(state.snapshot.selected); pending!!.complete(Unit) }
        compose.onNodeWithTag("node-choice-0").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, reads.get()); state.refresh() }
        compose.waitUntil(5000) { !state.busy && state.snapshot.status == LocalSubscriptionStatus.READY }
        compose.onNodeWithTag("nodes-refresh").assertDoesNotExist()
        compose.onNodeWithTag("node-choice-0").assertIsNotSelected()
        compose.runOnIdle { assertEquals(2, reads.get()); assertNull(state.snapshot.selected); assertEquals(0, navigationCalls.get()) }
    }

    @Test fun doubledFontNarrowGlassChoicesKeepOpaqueIdentityAndDoNotReloadOnSelection() {
        launch(width = 320, fontScale = 2f)
        val second = state.snapshot.choices[1].selection
        compose.onNodeWithTag("nodes-refresh").assertDoesNotExist()
        val host = compose.onNodeWithTag("nodes-fixture-host").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("nodes-search").assertDoesNotExist()
        compose.onNodeWithTag("nodes-filter-all").assertDoesNotExist()
        val row = compose.onNodeWithTag("node-choice-1")
        row.performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(64.dp)
        val bounds = row.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= host.left); assertTrue(bounds.right <= host.right)
        row.performClick().assertIsSelected()
        compose.onNodeWithTag("node-choice-0").assertIsNotSelected()
        compose.runOnIdle { assertSame(second, state.snapshot.selected); assertEquals(1, reads.get()) }
        compose.onNodeWithTag("nodes-refresh").assertDoesNotExist()
        compose.onNodeWithTag("nodes-connection-status").assertDoesNotExist()
    }

    @Test fun selectionSummaryTracksTheOriginalChoiceWithoutSearchFiltersOrAnotherRead() {
        appendVlessNode()
        launch()
        val third = state.snapshot.choices[2].selection
        compose.onNodeWithTag("nodes-selected-name").assertDoesNotExist()
        compose.onNodeWithTag("nodes-selection-summary").assertDoesNotExist()
        compose.onNodeWithTag("nodes-search").assertDoesNotExist()
        compose.onNodeWithTag("nodes-filter-all").assertDoesNotExist()
        compose.onNodeWithTag("nodes-count", useUnmergedTree = true).assertTextEquals("3 条")
        compose.onNodeWithTag("node-choice-2").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("nodes-selected-name").assertDoesNotExist()
        compose.onNodeWithTag("node-choice-0").assertIsNotSelected()
        compose.runOnIdle { assertSame(third, state.snapshot.selected); assertEquals(1, reads.get()) }
    }

    @Test fun collapsingTheConfigurationPreservesSelectionWithoutReadingOrNavigating() {
        launch()
        val second = state.snapshot.choices[1].selection
        compose.onNodeWithTag("node-choice-1").performClick()
        compose.onNodeWithTag("nodes-group-toggle").assertContentDescriptionEquals("收起配置节点").performClick()
        compose.onNodeWithTag("node-choice-0").assertDoesNotExist()
        compose.onNodeWithTag("node-choice-1").assertDoesNotExist()
        compose.onNodeWithTag("nodes-count", useUnmergedTree = true).assertTextEquals("2 条")
        compose.runOnIdle {
            assertSame(second, state.snapshot.selected)
            assertEquals(1, reads.get()); assertEquals(1, navigationCalls.get())
        }
        compose.onNodeWithTag("nodes-group-toggle").assertContentDescriptionEquals("展开配置节点").performClick()
        compose.onNodeWithTag("node-choice-1").assertIsSelected()
        compose.onNodeWithTag("nodes-selected-name").assertDoesNotExist()
        compose.runOnIdle { assertSame(second, state.snapshot.selected); assertEquals(1, reads.get()) }
    }

    @Test fun ordinaryRowsUseTwoCompactLinesAndKeepLongNamesInsideTheViewport() {
        bytes = bytes.toString(Charsets.UTF_8).replace("重复节点", "Synthetic long node name with a protocol and region label").toByteArray()
        launch(width = 320)
        val host = compose.onNodeWithTag("nodes-fixture-host").fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithTag("node-choice-1").performScrollTo().assertIsDisplayed()
        row.assertHeightIsAtLeast(48.dp)
        val unclipped = row.getUnclippedBoundsInRoot()
        assertTrue(unclipped.bottom - unclipped.top <= 64.dp)
        val bounds = row.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= host.left); assertTrue(bounds.right <= host.right)
        row.performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(1, reads.get()); assertEquals(1, navigationCalls.get()) }
    }

    private fun appendVlessNode() {
        bytes += """  - name: Fixture Alpha
    type: vless
    server: 192.0.2.12
    port: 443
    uuid: 11111111-1111-4111-8111-111111111111
    tls: true
    servername: tls.fixture.invalid
""".toByteArray()
    }

    @Test fun ownerStateChangesKeepTheNodePageQuietWithoutStartingAConnectionOrReloading() {
        connectionControl = ConnectionControlState(ConnectionPhase.Disconnected, canConnect = true)
        launch()
        compose.onNodeWithTag("nodes-unavailable").assertDoesNotExist()
        compose.onNodeWithTag("nodes-connection-status").assertDoesNotExist()
        compose.runOnIdle { connectionControl = ConnectionControlState(ConnectionPhase.Connecting, canCancel = true) }
        compose.onNodeWithTag("nodes-connection-status").assertDoesNotExist()
        compose.runOnIdle { connectionControl = ConnectionControlState(ConnectionPhase.Connected, canDisconnect = true) }
        compose.onNodeWithTag("nodes-connection-status").assertDoesNotExist()
        compose.runOnIdle { connectionControl = ConnectionControlState(ConnectionPhase.Disconnecting) }
        compose.onNodeWithTag("nodes-connection-status").assertDoesNotExist()
        compose.runOnIdle { connectionControl = ConnectionControlState(ConnectionPhase.Failed, canConnect = true) }
        compose.onNodeWithTag("nodes-connection-status").assertDoesNotExist()
        compose.onNodeWithTag("node-choice-0").assertIsNotSelected()
        compose.onNodeWithTag("node-choice-1").assertIsNotSelected()
        compose.runOnIdle { assertEquals(1, reads.get()); assertNull(state.snapshot.selected); assertEquals(0, navigationCalls.get()) }
    }
}
