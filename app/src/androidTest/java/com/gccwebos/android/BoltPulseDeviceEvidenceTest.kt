package com.gccwebos.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.design.GlassMode
import com.gccwebos.android.portal.*
import com.gccwebos.android.vpn.SafeProxyConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * PR #54 on-device evidence: the node-page lightning icon while a (synthetic, gated) latency test is running.
 * Holds the busy state for `holdMs` (instrumentation arg, default 6000) so the screen can be recorded, and samples
 * the icon's rendered brightness from real screenshots. Set the system animation scale before running to compare.
 */
class BoltPulseDeviceEvidenceTest {
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

    private var scenario: ActivityScenario<ComponentActivity>? = null
    private fun launch(withLatency: Boolean = true, width: Int = 360, fontScale: Float = 1f) {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario!!.onActivity { activity -> activity.setContent {
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
                androidx.compose.runtime.CompositionLocalProvider(com.gccwebos.android.design.LocalAnimatorScaleOverride provides
                    InstrumentationRegistry.getArguments().getString("animatorScale")?.toFloat()) {
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
        } }
    }
    @After fun closeScenario() { scenario?.close() }


    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun directory(): File = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "ui-evidence/pr54")
        .also { check(it.isDirectory || it.mkdirs()) }

    private fun brightness(shot: Bitmap, left: Int, top: Int, size: Int): Double {
        var total = 0.0; var count = 0
        for (y in top until (top + size).coerceAtMost(shot.height)) for (x in left until (left + size).coerceAtMost(shot.width)) {
            val p = shot.getPixel(x, y); total += (((p shr 16) and 255) + ((p shr 8) and 255) + (p and 255)) / 3.0; count++
        }
        return if (count == 0) 0.0 else total / count
    }

    private fun until(timeoutMs: Long, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(50)
        assertTrue("condition not reached in ${timeoutMs}ms", condition())
    }

    @Test fun lightningWhileTheLatencyTestIsRunning() {
        val gate = CompletableDeferred<Unit>()
        val holdMs = InstrumentationRegistry.getArguments().getString("holdMs")?.toLong() ?: 6000L
        val tag = InstrumentationRegistry.getArguments().getString("tag") ?: "run"
        response = { gate.await(); NodeLatencyObservation.Success(27L, 7L) }
        launch()
        // The automatic first measurement is also gated: it is the busy state we record. Real frame clock, no test rule.
        until(10_000) { ::latency.isInitialized && measurements.get() >= 1 && latency.busy }
        Thread.sleep(700)
        val ui = instrumentation.uiAutomation
        val nodes = ArrayList<android.view.accessibility.AccessibilityNodeInfo>()
        fun collect(n: android.view.accessibility.AccessibilityNodeInfo?) {
            if (n == null) return
            val label = (n.contentDescription ?: n.text)?.toString().orEmpty()
            if (label.contains("延迟测试") || label.contains("节点延迟")) nodes.add(n)
            for (i in 0 until n.childCount) collect(n.getChild(i))
        }
        collect(ui.rootInActiveWindow)
        val dump = StringBuilder()
        nodes.forEach { n -> val r = android.graphics.Rect(); n.getBoundsInScreen(r); dump.append("${n.contentDescription ?: n.text} $r clickable=${n.isClickable} enabled=${n.isEnabled}\n") }
        File(directory(), "$tag-bolt-nodes.txt").writeText(dump.toString())
        val boltNode = requireNotNull(nodes.firstOrNull { (it.contentDescription ?: it.text)?.toString()?.startsWith("延迟测试") == true && it.isClickable }
            ?: nodes.firstOrNull { (it.contentDescription ?: it.text)?.toString()?.startsWith("延迟测试") == true }) { "bolt node not found: $dump" }
        val bounds = android.graphics.Rect(); boltNode.getBoundsInScreen(bounds)
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val box = (14 * density).toInt()
        val left = bounds.centerX() - box / 2; val top = bounds.centerY() - box / 2
        val csv = StringBuilder("t_ms,brightness\n")
        val begin = System.currentTimeMillis(); var index = 0
        val half = (80 * density).toInt()
        while (System.currentTimeMillis() - begin < holdMs) {
            val shot = requireNotNull(ui.takeScreenshot())
            csv.append("${System.currentTimeMillis() - begin},${"%.2f".format(brightness(shot, left, top, box))}\n")
            val x0 = (bounds.centerX() - half).coerceAtLeast(0); val y0 = (bounds.centerY() - half).coerceAtLeast(0)
            val w = (2 * half).coerceAtMost(shot.width - x0); val h = (2 * half).coerceAtMost(shot.height - y0)
            File(directory(), "$tag-frame-${"%03d".format(index)}.png").outputStream().use {
                Bitmap.createBitmap(shot, x0, y0, w, h).compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            if (index == 0) File(directory(), "$tag-fullscreen.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            index++
        }
        File(directory(), "$tag-brightness.csv").writeText(csv.toString())
        gate.complete(Unit)
        until(10_000) { !latency.busy }
        val values = csv.lines().drop(1).filter { it.isNotBlank() }.map { it.substringAfter(',').toDouble() }
        val spread = (values.maxOrNull() ?: 0.0) - (values.minOrNull() ?: 0.0)
        File(directory(), "$tag-summary.txt").writeText("samples=${values.size} min=${values.minOrNull()} max=${values.maxOrNull()} spread=$spread\n")
        val scale = InstrumentationRegistry.getArguments().getString("animatorScale")
        if (scale == "0") assertTrue("bolt must be static with animations removed (spread=$spread)", spread < 3.0)
        else assertTrue("bolt brightness must pulse while busy (spread=$spread)", spread > 15.0)
    }
}
