package com.gccwebos.android.vpn

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.system.Os
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.gccwebos.android.BuildConfig
import com.gccwebos.android.ConnectionPowerAction
import com.gccwebos.android.ConnectionControlState
import com.gccwebos.android.ConnectionPhase
import com.gccwebos.android.MainActivity
import com.gccwebos.android.portal.CachedSubscription
import com.gccwebos.android.portal.DeliveryRecord
import com.gccwebos.android.portal.DeliveryStore
import com.gccwebos.android.portal.MemoryLocalProfileStore
import com.gccwebos.android.portal.OriginPolicy
import com.gccwebos.android.portal.PortalRepository
import com.gccwebos.android.portal.PortalTransport
import com.gccwebos.android.portal.Secret
import com.gccwebos.android.portal.SubscriptionRead
import com.gccwebos.android.portal.SubscriptionSource
import com.gccwebos.android.portal.WireResponse
import com.gccwebos.android.portal.sessionBinding
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.LinkOption
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.flow.StateFlow

/** Actual production Activity/owner/store/permission/remote JNI path with a loopback-only SS peer.
 * The opt-in candidate routes only its own application. No Portal request, external node, lab
 * service, configuration Intent, fake tunnel or manufactured Connected state is used.
 * Fixture credentials, nonce, URL, packets and UI contents are never included in evidence.
 */
class OneClickVpnInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val downloads = AtomicInteger()
    private val portalRequests = AtomicInteger()
    private val probes = AtomicInteger()
    private val probeFailures = AtomicInteger()
    private val probeNetworks = CopyOnWriteArrayList<Network>()
    private val witnesses = CopyOnWriteArrayList<EngineWitness>()
    private val profiles = MemoryLocalProfileStore()
    private var delivery: MemoryDelivery? = null
    private var fixture: SyntheticSsFixture? = null
    private var sourceMaterial: ByteArray? = null
    private var scenario: ActivityScenario<MainActivity>? = null
    private var ownerControls: StateFlow<ConnectionControlState>? = null
    private var guarded = false
    private var cleaned = false
    private var originalAccessibilityFlags = 0
    private val ownedDirectories get() = listOf("connection-preparation", "native-vpn")
        .map { File(context.noBackupFilesDir, it) }

    @Before fun injectSyntheticRepositoryBeforeLaunchingActualActivity() {
        assertTrue("Requires the opt-in packaged-engine candidate", BuildConfig.DEBUG &&
            BuildConfig.VPN_ENGINE_PACKAGED && BuildConfig.CONNECTION_SYNTHETIC_ACCEPTANCE)
        assertTrue("Requires an explicitly owned synthetic candidate package", context.packageName in setOf(
            "com.gccwebos.android.candidate.acceptance.address20261004v2",
            "com.gccwebos.android.candidate.acceptance.bbr20261004",
            "com.gccwebos.android.candidate.acceptance.bbr20261004v2",
            "com.gccwebos.android.candidate.acceptance.oneclick20261004v3"))
        assertFalse("This test exercises the production service", BuildConfig.NATIVE_VPN_LAB)
        assertTrue("Requires an idle candidate; never replace another VPN", vpnNetworks().isEmpty())
        assertFalse("Requires an idle candidate engine", engineProcessExists())
        assertFalse("Requires an idle candidate owner", ownerServiceExists())
        listOf("portal-session.bin", "portal-profile.bin", "portal-delivery.bin", "local-profile.bin").forEach { name ->
            listOf("", ".new", ".bak").forEach { suffix ->
                assertFalse("Requires a fresh candidate without private stores",
                    File(context.noBackupFilesDir, name + suffix).exists())
            }
        }
        ownedDirectories.forEach { assertFalse("Requires fresh test-owned resource directories", it.exists()) }
        assertTrue("Requires unused instrumentation injection slots",
            ConnectionRuntimeOverrides.repositoryFactory == null && ConnectionRuntimeOverrides.networkProbe == null)
        guarded = true // Only resources created after all guards pass may be removed by this test.
        originalAccessibilityFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val peer = SyntheticSsFixture(dnsAnswerLastOctet = 11).also { fixture = it }
        val capability = Secret("https://sub.invalid/sub/a/${peer.token}")
        val until = Instant.now().plusSeconds(3600)
        val yaml = """proxies:
  - name: Synthetic SS
    type: ss
    server: 127.0.0.1
    port: ${peer.port}
    cipher: aes-128-gcm
    password: ${peer.password}
    udp: true
""".toByteArray().also { sourceMaterial = it }
        val receipt = MemoryDelivery(DeliveryRecord("synthetic-account", "synthetic-session",
            "synthetic-resource", "synthetic-operation", capability, pendingAck = false)).also { delivery = it }
        CachedSubscription(sessionBinding(capability), yaml, until, capability).use(profiles::save)
        val repository = PortalRepository(object : PortalTransport {
            override suspend fun request(path: String, body: String?, session: Secret?): WireResponse {
                portalRequests.incrementAndGet()
                error("Synthetic connection acceptance forbids Portal requests")
            }
        }, receipt, OriginPolicy("https://portal.invalid", setOf("https://sub.invalid")),
            SystemClock::elapsedRealtime, subscription = object : SubscriptionSource {
                override suspend fun download(url: Secret): ByteArray = read(url).bytes
                override suspend fun read(url: Secret): SubscriptionRead {
                    check(url.value == capability.value) { "Synthetic capability changed" }
                    downloads.incrementAndGet()
                    return SubscriptionRead(yaml.copyOf(), until)
                }
            }, profiles = profiles)
        ConnectionRuntimeOverrides.repositoryFactory = { repository }
        ConnectionRuntimeOverrides.networkProbe = VpnNetworkProbe { network ->
            probes.incrementAndGet()
            probeNetworks += network
            try { proveTraffic(network, peer); true }
            catch (_: Exception) { probeFailures.incrementAndGet(); false }
        }
    }

    @Test fun oneClickRealNativeTrafficSurvivesNavigationAndRecreationThenReconnectsFresh() {
        var primaryFailure: Throwable? = null
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            await("configuration restored and actual power enabled") {
                acceptSystemConsentIfVisible()
                powerIs("一键连接")
            }
            ownerControls = withExistingService(ConnectionOwnerService::class.java) {
                (it as ConnectionOwnerService.LocalBinder).control
            }
            assertEquals("UI restoration is not subscription admission", 0, downloads.get())
            compose.onNodeWithTag("connection-status").assertTextEquals("未连接")
            compose.onNodeWithTag("vpn-connect").performScrollTo().assertIsEnabled().performClick()
            awaitConnected()
            val firstNetwork = probeNetworks.single()
            val firstEngine = observeRunningEngine()
            assertEquals("Single-choice connection requires two authoritative fresh reads", 2, downloads.get())
            assertPreparationFileExists()
            assertTrafficCounters()
            record("first-connected")

            compose.onNodeWithTag("nav-Lines").performClick()
            compose.onNodeWithTag("node-choice-0").performScrollTo().assertIsEnabled()
            compose.onNodeWithTag("nav-Connect").performClick()
            compose.onNodeWithTag("connection-status").assertTextEquals("已连接")
            requireNotNull(scenario).recreate()
            await("same connection published by recreated Activity") { powerIs("断开") }
            assertFalse("Activity recreation must not stop the accepted remote", firstEngine.dead.get())
            assertTrue("Original remote remains alive", firstEngine.binder.isBinderAlive)
            assertTrue("Original VPN Network remains present", firstNetwork in vpnNetworks())
            assertEquals("Navigation/recreation cannot replay admission", 2, downloads.get())
            assertEquals("Navigation/recreation cannot manufacture another traffic probe", 1, probes.get())
            // Reuse may legitimately serve the one-second DNS response from the same core's
            // cache. Fresh proxy-side DNS observation is required by both admission probes.
            proveTraffic(firstNetwork, requireNotNull(fixture), freshProxyDns = false)
            record("recreated-still-connected")

            disconnectAndAwait(firstEngine)
            assertFalse("Old endpoint cannot authorize reconnection", firstEngine.binder.isBinderAlive)
            val readsBeforeReconnect = downloads.get()
            compose.onNodeWithTag("vpn-connect").performScrollTo().assertIsEnabled().performClick()
            awaitConnected()
            val secondNetwork = probeNetworks.last()
            val secondEngine = observeRunningEngine()
            assertNotSame("Reconnection requires a fresh remote Binder incarnation", firstEngine.binder, secondEngine.binder)
            assertTrue("Reconnection requires a new VPN Network", secondNetwork != firstNetwork)
            assertEquals("Previously selected node still needs a fresh download", readsBeforeReconnect + 1, downloads.get())
            assertEquals("Each accepted connection requires verified traffic", 2, probes.get())
            assertPreparationFileExists()
            assertTrafficCounters()
            record("fresh-reconnected")
            disconnectAndAwait(secondEngine)
            assertEquals("No Portal/backend operation is part of this local path", 0, portalRequests.get())
            assertEquals("All traffic probes succeeded through the intended VPN", 0, probeFailures.get())
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try { cleanup() } catch (cleanupFailure: Throwable) {
                if (primaryFailure == null) throw cleanupFailure
                primaryFailure.addSuppressed(cleanupFailure)
            }
        }
    }

    @After fun cleanupIfSetupOrTestFailed() { if (!cleaned) cleanup() }

    private fun awaitConnected() {
        await("actual traffic-gated Connected UI") {
            acceptSystemConsentIfVisible()
            ownerControls?.value?.let { state ->
                if (state.phase == ConnectionPhase.Failed)
                    throw AssertionError("Actual owner rejected connection: ${state.failure}")
            }
            powerIs("断开")
        }
        compose.onNodeWithTag("connection-status").assertTextEquals("已连接")
        compose.onNodeWithTag("vpn-connect").assertContentDescriptionEquals("断开").assertIsEnabled()
        assertTrue("Connected requires a completed real network probe", probes.get() > 0 && probeFailures.get() == 0)
    }

    private fun disconnectAndAwait(engine: EngineWitness) {
        compose.onNodeWithTag("vpn-connect").performScrollTo().assertContentDescriptionEquals("断开")
            .assertIsEnabled().performClick()
        await("exact remote death, TUN removal and cleanup acknowledgement") {
            engine.dead.get() && vpnNetworks().isEmpty() && !engineProcessExists() && powerIs("一键连接")
        }
        assertPreparationFileAbsent()
        compose.onNodeWithTag("connection-status").assertTextEquals("未连接")
        record("disconnected")
    }

    private fun powerIs(description: String): Boolean =
        compose.onAllNodes(hasTestTag("vpn-connect") and hasContentDescription(description))
            .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1

    private fun acceptSystemConsentIfVisible() {
        val root = automation.rootInActiveWindow ?: return
        // Do not traverse, save or interact with another application's UI.
        if (root.packageName?.toString() == "com.oplus.securitypermission" &&
            root.findAccessibilityNodeInfosByText(context.packageName + ".test").isNotEmpty()) {
            root.findAccessibilityNodeInfosByText("打开").filter { it.isClickable && it.isEnabled }
                .singleOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }
        if (root.packageName?.toString() == "com.oplus.wirelesssettings" &&
            root.findAccessibilityNodeInfosByText("VPN").isNotEmpty()) {
            root.findAccessibilityNodeInfosByText("允许").filter {
                it.text?.toString() == "允许" && it.isClickable && it.isEnabled
            }.singleOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }
        if (root.packageName?.toString() != "com.android.vpndialogs") return
        val accept = root.findAccessibilityNodeInfosByViewId("android:id/button1").singleOrNull() ?: return
        if (accept.isEnabled) accept.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun proveTraffic(network: Network, peer: SyntheticSsFixture, freshProxyDns: Boolean = true) {
        check(network in vpnNetworks()) { "Probe must use the actual VPN Network" }
        val link = requireNotNull(connectivity.getLinkProperties(network))
        check(link.routes.any { it.destination.prefixLength == 0 && it.destination.address is Inet4Address })
        check(link.routes.any { it.destination.prefixLength == 0 && it.destination.address is Inet6Address })
        check(link.dnsServers.any { it.hostAddress == SyntheticSsFixture.DNS })
        val tcpBefore = peer.tcpRequests.get()
        val udpBefore = peer.udpRequests.get()
        val dnsBefore = peer.dnsRequests.get()
        network.socketFactory.createSocket().use { socket ->
            socket.soTimeout = 2_000
            socket.connect(InetSocketAddress(SyntheticSsFixture.DESTINATION, SyntheticSsFixture.TCP_PORT), 2_000)
            socket.getOutputStream().write("GET /${peer.token} HTTP/1.1\r\nHost: fixture.invalid\r\nConnection: close\r\n\r\n"
                .toByteArray(Charsets.US_ASCII))
            val response = ByteArrayOutputStream()
            val chunk = ByteArray(512)
            try {
                while (true) {
                    val count = socket.getInputStream().read(chunk)
                    if (count < 0) break
                    check(response.size() + count <= 2048) { "Oversized synthetic TCP reply" }
                    response.write(chunk, 0, count)
                }
                val reply = response.toString("US-ASCII")
                check(reply.startsWith("HTTP/1.1 200 OK\r\n") && reply.endsWith("\r\n\r\n${peer.tcpProof}")) {
                    "Missing synthetic TCP nonce proof"
                }
            } finally { chunk.fill(0); response.reset() }
        }
        val udp = udp(network, SyntheticSsFixture.DESTINATION, SyntheticSsFixture.UDP_PORT, peer.udpRequest)
        try { check(udp.contentEquals(peer.udpProof)) { "Missing synthetic UDP nonce proof" } }
        finally { udp.fill(0) }
        val question = peer.dnsQuestion()
        val dns = udp(network, SyntheticSsFixture.DNS, 53, question)
        try {
            check(dns.size >= question.size + 16 && dns.copyOfRange(0, 2).contentEquals(question.copyOfRange(0, 2)) &&
                dns.copyOfRange(12, question.size).contentEquals(question.copyOfRange(12, question.size)) &&
                dns.takeLast(4).toByteArray().contentEquals(byteArrayOf(198.toByte(), 18, 0, 11))) {
                "Missing synthetic DNS nonce proof"
            }
        } finally { question.fill(0); dns.fill(0) }
        check(peer.tcpRequests.get() > tcpBefore && peer.udpRequests.get() > udpBefore &&
            if (freshProxyDns) peer.dnsRequests.get() > dnsBefore else peer.dnsRequests.get() > 0) {
            "Nonce responses must have the required SS proxy observations"
        }
        check(peer.errors.isEmpty()) { "Synthetic peer rejected unexpected traffic" }
    }

    private fun udp(network: Network, host: String, port: Int, bytes: ByteArray): ByteArray =
        DatagramSocket().use { socket ->
            socket.soTimeout = 2_000
            network.bindSocket(socket) // Never fall back to the ordinary/default network.
            socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(host), port))
            val response = DatagramPacket(ByteArray(2048), 2048)
            socket.receive(response)
            response.data.copyOf(response.length)
        }

    private fun assertTrafficCounters() {
        val peer = requireNotNull(fixture)
        assertTrue("Proxy observed TCP, UDP and DNS", peer.tcpRequests.get() > 0 &&
            peer.udpRequests.get() > 0 && peer.dnsRequests.get() > 0)
        assertTrue("Synthetic peer has no unexpected traffic", peer.errors.isEmpty())
    }

    private fun assertPreparationFileExists() {
        val directory = ownedDirectories.first()
        val prepared = File(directory, "prepared-connection.json")
        assertTrue("Actual atomic prepared store completed", prepared.isFile &&
            prepared.length() in 1L..SafeProxyConfig.MAX_BYTES.toLong())
        assertFalse("Prepared material cannot be a symbolic link", Files.isSymbolicLink(prepared.toPath()))
        assertEquals("Prepared material permissions are exactly 0600", 0x180,
            Os.lstat(prepared.absolutePath).st_mode and 0xFFF)
        assertFalse("No staged material remains", directory.listFiles().orEmpty().any { it.name.startsWith(".prepared-") })
    }

    private fun assertPreparationFileAbsent() {
        val directory = ownedDirectories.first()
        assertFalse("Production stop cleared prepared material before cleanup acknowledgement",
            Files.exists(File(directory, "prepared-connection.json").toPath(), LinkOption.NOFOLLOW_LINKS))
        assertFalse("Production stop left no staged material",
            directory.listFiles().orEmpty().any { it.name.startsWith(".prepared-") })
    }

    private class EngineWitness(val binder: IBinder) {
        val dead = AtomicBoolean(false)
        val recipient = IBinder.DeathRecipient { dead.set(true) }
        init { binder.linkToDeath(recipient, 0) }
    }

    private fun observeRunningEngine(): EngineWitness {
        // No AUTO_CREATE, and no test binding is retained across recreation. The production
        // reverse lease alone must keep the owner alive after the old Activity unbinds.
        val witness = withExistingService(ProductionVpnService::class.java, VpnControl.ACTION_BIND) {
            EngineWitness(it).also(witnesses::add)
        }
        assertNotNull("Connected must have an existing production control Binder", witness)
        return requireNotNull(witness)
    }

    private fun <T> withExistingService(service: Class<out Service>, action: String? = null,
        inspect: (IBinder) -> T): T? {
        val ready = CountDownLatch(1)
        val value = AtomicReference<IBinder?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { value.set(binder); ready.countDown() }
            override fun onServiceDisconnected(name: ComponentName) { ready.countDown() }
            override fun onBindingDied(name: ComponentName) { ready.countDown() }
            override fun onNullBinding(name: ComponentName) { ready.countDown() }
        }
        var bound = false
        instrumentation.runOnMainSync {
            bound = context.bindService(Intent(context, service).setAction(action), connection, 0)
        }
        if (!bound) return null
        return try {
            if (ready.await(3, TimeUnit.SECONDS)) value.get()?.let(inspect) else null
        } finally { instrumentation.runOnMainSync { context.unbindService(connection) } }
    }

    @Suppress("DEPRECATION")
    private fun vpnNetworks(): List<Network> = connectivity.allNetworks.filter {
        connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }
    private fun engineProcessExists(): Boolean = context.getSystemService(ActivityManager::class.java)
        .runningAppProcesses.orEmpty().any { it.processName == "${context.packageName}:vpn" }
    @Suppress("DEPRECATION")
    private fun ownerServiceExists(): Boolean = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE).any { it.service.className == ConnectionOwnerService::class.java.name }

    private fun await(phase: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 25_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        record("timeout")
        throw AssertionError("Timed out at phase=$phase; downloads=${downloads.get()}; probes=${probes.get()}; failures=${probeFailures.get()}")
    }

    private fun record(phase: String) {
        val peer = fixture
        Log.i("GCCConnectionAcceptance", "phase=$phase downloads=${downloads.get()} probes=${probes.get()} " +
            "probeFailures=${probeFailures.get()} tcp=${peer?.tcpRequests?.get() ?: 0} " +
            "udp=${peer?.udpRequests?.get() ?: 0} dns=${peer?.dnsRequests?.get() ?: 0}")
    }

    private fun cleanup() {
        if (!guarded || cleaned) return
        try {
            withExistingService(ConnectionOwnerService::class.java) { binder ->
                instrumentation.runOnMainSync {
                    (binder as? ConnectionOwnerService.LocalBinder)?.power(ConnectionPowerAction.Disconnect)
                }
            }
            // If the UI path failed before the witness was captured, stop only an already-existing
            // endpoint. This cannot instantiate a replacement service or terminate an external PID.
            withExistingService(ProductionVpnService::class.java, VpnControl.ACTION_BIND) { binder ->
                val stop = BoundedVpnCall {
                    val request = Parcel.obtain(); val reply = Parcel.obtain()
                    try {
                        request.writeInterfaceToken(VpnControl.DESCRIPTOR)
                        check(binder.transact(VpnControl.STOP, request, reply, 0))
                        reply.readException(); check(reply.readInt() == 1)
                    } finally { request.recycle(); reply.recycle() }
                }
                runCatching { stop.await(500) } // The production stop watchdog remains independent.
            }
            scenario?.close(); scenario = null
            context.stopService(Intent(context, ConnectionOwnerService::class.java))
            await("finally all production resources stopped") {
                vpnNetworks().isEmpty() && !engineProcessExists() && !ownerServiceExists() && witnesses.all { it.dead.get() }
            }
            // Assert the production deletion before test-owned directory removal can hide it.
            assertPreparationFileAbsent()
            record("cleanup-stopped")
            ownedDirectories.forEach { directory ->
                if (directory.exists()) Files.walk(directory.toPath()).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { path ->
                        check(!Files.isSymbolicLink(path)) { "Unexpected symbolic link in test-owned directory" }
                        Files.deleteIfExists(path)
                    }
                }
            }
            cleaned = true
        } finally {
            witnesses.forEach { runCatching { it.binder.unlinkToDeath(it.recipient, 0) } }
            ConnectionRuntimeOverrides.repositoryFactory = null
            ConnectionRuntimeOverrides.networkProbe = null
            profiles.clear(); delivery?.clear()
            sourceMaterial?.fill(0); sourceMaterial = null
            fixture?.close(); fixture = null
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalAccessibilityFlags }
        }
    }

    private class MemoryDelivery(private var value: DeliveryRecord?) : DeliveryStore {
        override fun read(): DeliveryRecord? = value
        override fun save(record: DeliveryRecord) { value = record }
        override fun clear() { value = null }
    }
}
