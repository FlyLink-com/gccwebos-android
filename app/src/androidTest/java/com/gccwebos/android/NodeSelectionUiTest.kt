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
                NodeSelectionController(manager, scope, { 0L }).also { state = it }
            }
            LaunchedEffect(owner) { if (initialRead) owner.refresh() } // Explicit synthetic fixture setup only.
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GccTheme(materialMode = GlassMode.Solid) {
                    Column(Modifier.width(width.dp).fillMaxHeight().testTag("nodes-fixture-host")
                        .verticalScroll(rememberScrollState())) {
                        NodeSelectionPanel(owner, manager.snapshot().nodes, resource, hasCapability, false,
                            connectionControl = connectionControl) {}
                    }
                }
            }
        }
        compose.waitUntil(5000) { ::state.isInitialized && !state.busy && (!initialRead || reads.get() > 0) }
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
        compose.onNodeWithTag("nodes-connection-status").assertTextEquals("连接暂不可用")
    }

    @Test fun busyRefreshDisablesSelectionAndCancelIsReachableWithoutAuthenticationActions() {
        launch()
        // A normal ready list has no manual reload button. Prepare only the synthetic busy state.
        compose.runOnIdle { pending = CompletableDeferred(); state.refresh() }
        compose.waitUntil(5000) { state.busy }
        compose.onNodeWithTag("node-choice-0").assertIsNotEnabled()
        compose.onNodeWithTag("nodes-cancel").performScrollTo().performClick()
        compose.onNodeWithTag("node-choice-0").assertDoesNotExist()
        compose.onNodeWithTag("nodes-refresh").assertTextEquals("重试")
        compose.runOnIdle { assertFalse(state.busy); assertNull(state.snapshot.selected); pending!!.complete(Unit) }
    }

    @Test fun unsupportedMixedArtifactAndExpiryExposeNoSelectableRows() {
        launch()
        compose.runOnIdle {
            bytes = bytes.toString(Charsets.UTF_8).replaceFirst("type: ss", "type: vmess").toByteArray()
            state.refresh() // Explicit fixture state setup, never an automatic production reload.
        }
        compose.waitUntil(5000) { !state.busy && state.snapshot.status == LocalSubscriptionStatus.UNSUPPORTED }
        compose.onNodeWithTag("node-choice-0").assertDoesNotExist()
        compose.onNodeWithTag("node-selection-status").assertTextEquals("配置暂不受支持")
        compose.onNodeWithTag("nodes-refresh").assertTextEquals("重试")
        compose.runOnIdle { bytes = bytes.toString(Charsets.UTF_8).replace("type: vmess", "type: ss").toByteArray() }
        compose.onNodeWithTag("nodes-refresh").performClick()
        compose.waitUntil(5000) { !state.busy && state.snapshot.status == LocalSubscriptionStatus.READY }
        compose.onNodeWithTag("nodes-refresh").assertDoesNotExist()
        compose.runOnIdle { time = expiry; state.sync() }
        compose.onNodeWithTag("node-choice-0").assertDoesNotExist()
        compose.onNodeWithTag("node-selection-status").assertTextEquals("订阅已到期")
        compose.runOnIdle { manager.unlink(); resource = Resource.None; hasCapability = false; state.sync() }
        compose.onNodeWithTag("node-choice-0").assertDoesNotExist()
        compose.onNodeWithTag("node-selection-status").assertTextEquals("暂无套餐")
        compose.onNodeWithTag("nodes-refresh").assertDoesNotExist()
    }

    @Test fun firstReadRetryHidesDuringReadAndCancellationRestoresOnlyReadRecovery() {
        launch(initialRead = false, width = 320, fontScale = 2f)
        compose.onNodeWithTag("node-selection-status").assertTextEquals("尚无订阅配置")
        val retry = compose.onNodeWithTag("nodes-refresh")
        retry.assertTextEquals("重试").assertIsEnabled().assertWidthIsAtLeast(88.dp)
            .assertHeightIsAtLeast(48.dp).performScrollTo().assertIsDisplayed()
        val host = compose.onNodeWithTag("nodes-fixture-host").fetchSemanticsNode().boundsInRoot
        val retryBounds = retry.fetchSemanticsNode().boundsInRoot
        assertTrue(retryBounds.left >= host.left); assertTrue(retryBounds.right <= host.right)
        compose.runOnIdle { assertEquals(0, reads.get()); pending = CompletableDeferred() }
        compose.onNodeWithTag("nodes-refresh").performClick()
        compose.waitUntil(5000) { state.busy && reads.get() == 1 }
        compose.onNodeWithTag("nodes-refresh").assertDoesNotExist()
        compose.onNodeWithTag("nodes-cancel").assertHeightIsAtLeast(48.dp).performScrollTo().performClick()
        compose.onNodeWithTag("nodes-cancel").assertDoesNotExist()
        compose.onNodeWithTag("nodes-refresh").assertTextEquals("重试").assertIsEnabled()
        compose.runOnIdle { assertNull(state.snapshot.selected); pending!!.complete(Unit) }
        compose.onNodeWithTag("nodes-refresh").performClick()
        compose.waitUntil(5000) { !state.busy && state.snapshot.status == LocalSubscriptionStatus.READY }
        compose.onNodeWithTag("nodes-refresh").assertDoesNotExist()
        compose.onNodeWithTag("node-choice-0").assertIsNotSelected()
        compose.runOnIdle { assertEquals(2, reads.get()); assertNull(state.snapshot.selected) }
    }

    @Test fun doubledFontNarrowGlassChoicesKeepOpaqueIdentityAndDoNotReloadOnSelection() {
        launch(width = 320, fontScale = 2f)
        val second = state.snapshot.choices[1].selection
        compose.onNodeWithTag("nodes-refresh").assertDoesNotExist()
        val host = compose.onNodeWithTag("nodes-fixture-host").fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithTag("node-choice-1")
        row.performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(64.dp)
        val bounds = row.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= host.left); assertTrue(bounds.right <= host.right)
        row.performClick().assertIsSelected()
        compose.onNodeWithTag("node-choice-0").assertIsNotSelected()
        compose.runOnIdle { assertSame(second, state.snapshot.selected); assertEquals(1, reads.get()) }
        compose.onNodeWithTag("nodes-refresh").assertDoesNotExist()
        compose.onNodeWithTag("nodes-connection-status").assertTextEquals("连接暂不可用")
    }

    @Test fun nodeCaptionTracksActualOwnerWithoutStartingAConnectionOrReloading() {
        connectionControl = ConnectionControlState(ConnectionPhase.Disconnected, canConnect = true)
        launch()
        compose.onNodeWithTag("nodes-unavailable").assertDoesNotExist()
        compose.onNodeWithTag("nodes-connection-status").assertDoesNotExist()
        compose.runOnIdle { connectionControl = ConnectionControlState(ConnectionPhase.Connecting, canCancel = true) }
        compose.onNodeWithTag("nodes-connection-status").assertTextEquals("连接中")
        compose.runOnIdle { connectionControl = ConnectionControlState(ConnectionPhase.Connected, canDisconnect = true) }
        compose.onNodeWithTag("nodes-connection-status").assertTextEquals("已连接")
        compose.runOnIdle { connectionControl = ConnectionControlState(ConnectionPhase.Failed, canConnect = true) }
        compose.onNodeWithTag("nodes-connection-status").assertTextEquals("未连接")
        compose.runOnIdle { assertEquals(1, reads.get()); assertNull(state.snapshot.selected) }
    }
}
