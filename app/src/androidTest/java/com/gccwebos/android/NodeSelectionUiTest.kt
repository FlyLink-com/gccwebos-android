package com.gccwebos.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.portal.*
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
    private fun launch() {
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
            LaunchedEffect(owner) { owner.refresh() } // Explicit synthetic fixture setup only.
            GccTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    NodeSelectionPanel(owner, manager.snapshot().nodes, resource, hasCapability, false) {}
                }
            }
        }
        compose.waitUntil(5000) { ::state.isInitialized && !state.busy && reads.get() > 0 }
    }

    @Test fun duplicateChoicesStartUnselectedAndClickUsesTheCorrectOpaqueObjectWithoutReading() {
        launch()
        val second = state.snapshot.choices[1].selection
        compose.onNodeWithTag("node-choice-0").assertIsNotSelected()
        compose.onNodeWithTag("node-choice-1").assertIsNotSelected().performClick()
        compose.onNodeWithTag("node-choice-1").assertIsSelected()
        compose.onNodeWithTag("node-choice-0").assertIsNotSelected()
        compose.runOnIdle { assertSame(second, state.snapshot.selected); assertEquals(1, reads.get()) }
        compose.onNodeWithTag("nodes-unavailable").assertTextEquals("连接暂未开放")
    }

    @Test fun busyRefreshDisablesSelectionAndCancelIsReachableWithoutAuthenticationActions() {
        launch()
        compose.runOnIdle { pending = CompletableDeferred() }
        compose.onNodeWithTag("nodes-refresh").performClick()
        compose.waitUntil(5000) { state.busy }
        compose.onNodeWithTag("node-choice-0").assertIsNotEnabled()
        compose.onNodeWithTag("nodes-cancel").performScrollTo().performClick()
        compose.onNodeWithTag("node-choice-0").assertDoesNotExist()
        compose.runOnIdle { assertFalse(state.busy); assertNull(state.snapshot.selected); pending!!.complete(Unit) }
    }

    @Test fun unsupportedMixedArtifactAndExpiryExposeNoSelectableRows() {
        launch()
        compose.runOnIdle { bytes = bytes.toString(Charsets.UTF_8).replaceFirst("type: ss", "type: vmess").toByteArray() }
        compose.onNodeWithTag("nodes-refresh").performClick()
        compose.waitUntil(5000) { !state.busy && state.snapshot.status == LocalSubscriptionStatus.UNSUPPORTED }
        compose.onNodeWithTag("node-choice-0").assertDoesNotExist()
        compose.onNodeWithTag("node-selection-status").assertTextEquals("配置暂不受支持")
        compose.runOnIdle { bytes = bytes.toString(Charsets.UTF_8).replace("type: vmess", "type: ss").toByteArray() }
        compose.onNodeWithTag("nodes-refresh").performClick()
        compose.waitUntil(5000) { !state.busy && state.snapshot.status == LocalSubscriptionStatus.READY }
        compose.runOnIdle { time = expiry; state.sync() }
        compose.onNodeWithTag("node-choice-0").assertDoesNotExist()
        compose.onNodeWithTag("node-selection-status").assertTextEquals("订阅已到期")
        compose.runOnIdle { manager.unlink(); resource = Resource.None; hasCapability = false; state.sync() }
        compose.onNodeWithTag("node-choice-0").assertDoesNotExist()
        compose.onNodeWithTag("node-selection-status").assertTextEquals("暂无套餐")
        compose.onNodeWithTag("nodes-refresh").assertDoesNotExist()
    }
}
