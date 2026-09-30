package com.gccwebos.android.vpn

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.net.VpnService
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.gccwebos.android.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Opt-in disposable-emulator acceptance, not part of normal instrumentation or production APKs.
 * Sockets use Android's actual per-app TUN routes. The SS server only answers locally; it cannot
 * forward traffic. No credentials, fixture input, or subscription bytes enter evidence/logs.
 */
@android.annotation.TargetApi(33)
class RealTunAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val states = CopyOnWriteArrayList<String>()
    private val evidence = JSONArray()
    private val output by lazy { File(context.getExternalFilesDir(null), "real-tun-evidence").apply { mkdirs() } }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra("state")?.let(states::add)
        }
    }

    @Test fun realTunTcpUdpDnsStopRevokeLatePermissionAndProcessCleanup() {
        assertTrue(BuildConfig.DEBUG && BuildConfig.NATIVE_VPN_LAB && BuildConfig.NATIVE_TUN_ACCEPTANCE)
        assertEquals("true", InstrumentationRegistry.getArguments().getString("gccRealTunAcceptance"))
        assertEquals(33, Build.VERSION.SDK_INT)
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(LabVpnService.ACTION_STATE),
            "${context.packageName}.permission.VPN_LAB_STATE", null, ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            assertNull(vpnNetwork()); assertNull(enginePid())
            grantTargetPermission(cancelBeforeGrant = false)
            ActivityScenario.launch(NativeVpnLabActivity::class.java).use {
                var firstPid = -1
                SyntheticSsFixture().use { fixture ->
                    firstPid = startAndProve(fixture, "first-start")
                    context.startService(Intent(context, LabVpnService::class.java).setAction(LabVpnService.ACTION_STOP))
                    awaitStopped(); record("explicit-stop", JSONObject().put("tunGone", true).put("processGone", true))
                }
                SyntheticSsFixture().use { fixture ->
                    val second = startAndProve(fixture, "fresh-process-restart")
                    assertNotEquals(firstPid, second)
                    // Same app UID only; verify the exact isolated process before terminating it.
                    assertEquals(second, enginePid()); assertNotEquals(Process.myPid(), second)
                    Process.killProcess(second)
                    awaitStopped()
                    val quietStart = SystemClock.elapsedRealtime()
                    while (SystemClock.elapsedRealtime() - quietStart < 10_000) {
                        assertNull("No delayed service restart", enginePid()); assertNull(vpnNetwork()); Thread.sleep(100)
                    }
                    val serviceState = shell("dumpsys activity services ${context.packageName}")
                    assertTrue("Valid Android service dump required", serviceState.contains("ACTIVITY MANAGER SERVICES"))
                    assertFalse("Service dump must not be an error", serviceState.contains("Permission Denial", true) || serviceState.contains("Error:", true))
                    assertFalse("No pending platform restart", serviceState.contains("Restarting services:"))
                    File(output, "after-process-death-services.txt").writeText(serviceState)
                    record("process-death", JSONObject().put("tunGone", true).put("noAutomaticRestart", true)
                        .put("quietMillis", SystemClock.elapsedRealtime() - quietStart).put("pendingRestart", false))
                }
                SyntheticSsFixture().use { fixture ->
                    startAndProve(fixture, "before-platform-revocation")
                    states.clear()
                    transferPermissionToRevoker()
                    await("platform onRevoke callback") { "REVOKED" in states }
                    awaitStopped(); record("platform-revoke", JSONObject().put("onRevoke", true).put("tunGone", true))
                }
                // Reset ONLY this disposable emulator test app's prior VPN authorization to denied.
                // Never grant through appops; actual positive authorization uses Android's dialog.
                shell("appops set --user 0 ${context.packageName} ACTIVATE_VPN ignore")
                assertNotNull(VpnService.prepare(context))
                grantTargetPermission(cancelBeforeGrant = true)
                assertNull(vpnNetwork()); assertNull(enginePid())
                record("late-permission", JSONObject().put("grantedAfterCancel", true).put("noAutomaticStart", true))
                // Leave neither a tunnel nor the target app's VPN authorization in the disposable AVD.
                shell("appops set --user 0 ${context.packageName} ACTIVATE_VPN ignore")
                assertNotNull(VpnService.prepare(context))
            }
        } finally {
            if (enginePid() != null) runCatching { context.startService(Intent(context, LabVpnService::class.java).setAction(LabVpnService.ACTION_STOP)) }
            File(context.noBackupFilesDir, LabAcceptanceConfig.FILE).delete()
            context.unregisterReceiver(receiver)
            writeEvidence()
        }
    }

    private fun startAndProve(fixture: SyntheticSsFixture, label: String): Int {
        assertNull(vpnNetwork()); assertNull(enginePid())
        val file = File(context.noBackupFilesDir, LabAcceptanceConfig.FILE)
        val staged = File(context.noBackupFilesDir, "${LabAcceptanceConfig.FILE}.new")
        val bytes = JSONObject().put("port", fixture.port).put("password", fixture.password)
            .put("deadlineElapsed", SystemClock.elapsedRealtime() + 60_000).toString().toByteArray()
        try {
            FileOutputStream(staged).use { it.write(bytes); it.fd.sync() }
            check(staged.setReadable(false, false) && staged.setWritable(false, false))
            check(staged.setReadable(true, true) && staged.setWritable(true, true))
            Files.move(staged.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { bytes.fill(0); staged.delete() }
        states.clear()
        instrumentation.runOnMainSync {
            context.startForegroundService(Intent(context, LabVpnService::class.java).setAction(LabVpnService.ACTION_START))
        }
        await("native TUN ready") { "ENGINE_RUNNING_UNVERIFIED" in states && vpnNetwork() != null }
        assertFalse("Ephemeral input must be consumed once", file.exists())
        val pid = requireNotNull(enginePid())
        val network = requireNotNull(vpnNetwork())
        val link = requireNotNull(connectivity.getLinkProperties(network))
        assertTrue(link.routes.any { it.destination.toString() == "198.18.0.10/32" })
        assertTrue(link.routes.any { it.destination.toString() == "172.19.0.2/32" })
        assertFalse("No global/default forwarding route", link.routes.any { it.isDefaultRoute && it.type == RouteInfo.RTN_UNICAST })
        assertTrue(link.dnsServers.any { it.hostAddress == SyntheticSsFixture.DNS })
        Socket().use { socket ->
            socket.soTimeout = 8000
            socket.connect(InetSocketAddress(SyntheticSsFixture.DESTINATION, SyntheticSsFixture.TCP_PORT), 8000)
            socket.getOutputStream().write("GET /${fixture.token} HTTP/1.1\r\nHost: fixture.invalid\r\nConnection: close\r\n\r\n".toByteArray())
            val response = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(512)
            while (!response.toString("US-ASCII").endsWith(fixture.tcpProof)) {
                require(response.size() < 2048)
                val count = socket.getInputStream().read(buffer)
                check(count > 0) { "Missing TCP proof" }; response.write(buffer, 0, count)
            }
            assertTrue(response.toString("US-ASCII").startsWith("HTTP/1.1 200 OK"))
        }
        assertArrayEquals(fixture.udpProof, udp(SyntheticSsFixture.DESTINATION, SyntheticSsFixture.UDP_PORT, fixture.udpRequest))
        val dns = udp(SyntheticSsFixture.DNS, 53, fixture.dnsQuestion())
        assertArrayEquals(byteArrayOf(0x12, 0x34), dns.copyOfRange(0, 2))
        assertArrayEquals(byteArrayOf(198.toByte(), 18, 0, 10), dns.takeLast(4).toByteArray())
        assertTrue(fixture.tcpRequests.get() > 0 && fixture.udpRequests.get() > 0 && fixture.dnsRequests.get() > 0)
        screenshot(label)
        record(label, JSONObject().put("pid", pid).put("tcpProof", true).put("udpProof", true).put("dnsThroughProxy", true)
            .put("tcpRequests", fixture.tcpRequests.get()).put("udpRequests", fixture.udpRequests.get())
            .put("dnsRequests", fixture.dnsRequests.get()).put("privateDnsProbesRejected", fixture.privateDnsProbesRejected.get())
            .put("unexpectedErrors", JSONArray(fixture.errors.toList())).put("routes", JSONArray(link.routes.map { it.toString() })))
        assertTrue("Fixture rejected unexpected traffic: ${fixture.errors}", fixture.errors.isEmpty())
        return pid
    }

    private fun grantTargetPermission(cancelBeforeGrant: Boolean) {
        ActivityScenario.launch(NativeVpnLabActivity::class.java).use { scenario ->
            if (VpnService.prepare(context) != null) {
                clickText("申请 VPN 权限")
                await("Android VPN consent") { automation.rootInActiveWindow?.packageName?.toString() == "com.android.vpndialogs" }
                if (cancelBeforeGrant) scenario.onActivity { it.stopLab() }
                screenshot(if (cancelBeforeGrant) "late-consent-before-grant" else "vpn-consent-before-grant")
                clickSystemConsent(context.applicationInfo.loadLabel(context.packageManager).toString())
            } else check(!cancelBeforeGrant) { "Late-permission case must exercise a real dialog" }
            await("target permission granted") { VpnService.prepare(context) == null }
            Thread.sleep(400)
            assertNull("Permission result never starts service", enginePid())
            assertNull(vpnNetwork())
        }
    }

    private fun transferPermissionToRevoker() {
        states.clear()
        val testPackage = instrumentation.context.packageName
        context.startActivity(Intent().setComponent(ComponentName(testPackage, AcceptanceRevokerActivity::class.java.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("separate test APK consent or completed transfer") {
            automation.rootInActiveWindow?.packageName?.toString() == "com.android.vpndialogs" ||
                ("REVOKED" in states && vpnNetwork() == null)
        }
        if (automation.rootInActiveWindow?.packageName?.toString() == "com.android.vpndialogs") {
            screenshot("platform-revocation-consent"); clickSystemConsent("GCC isolated revoker")
        }
        await("revoker activity finished") { automation.rootInActiveWindow?.packageName?.toString() != "com.android.vpndialogs" }
    }

    private fun clickSystemConsent(expectedApp: String) {
        await("enabled Android consent for expected test APK") {
            val root = automation.rootInActiveWindow ?: return@await false
            if (root.packageName?.toString() != "com.android.vpndialogs") return@await false
            if (root.findAccessibilityNodeInfosByText(expectedApp).isEmpty()) return@await false
            val accept = root.findAccessibilityNodeInfosByViewId("android:id/button1").singleOrNull()
                ?: return@await false
            accept.isEnabled && accept.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }
    private fun clickText(text: String) {
        await("visible $text") {
            // Compose virtual nodes do not implement provider-side text search. Walk the
            // actual accessibility children, as UIAutomator does, instead of guessing coordinates.
            val matches = visibleNodes().filter { it.text?.toString() == text || it.contentDescription?.toString() == text }
            matches.any { found ->
                var node: AccessibilityNodeInfo? = found
                while (node != null && !node.isClickable) node = node.parent
                node?.takeIf { it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
        }
    }
    private fun visibleNodes(): List<AccessibilityNodeInfo> {
        val root = automation.rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 20 || result.size >= 500) return
            result += node
            for (index in 0 until node.childCount) {
                if (result.size >= 500) break
                node.getChild(index)?.let { visit(it, depth + 1) }
            }
        }
        visit(root, 0); return result
    }
    private fun udp(host: String, port: Int, bytes: ByteArray): ByteArray = DatagramSocket().use { socket ->
        socket.soTimeout = 8000
        socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(host), port))
        val response = DatagramPacket(ByteArray(2048), 2048); socket.receive(response)
        response.data.copyOf(response.length)
    }
    @Suppress("DEPRECATION")
    private fun vpnNetwork(): Network? = connectivity.allNetworks.firstOrNull {
        connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }
    private fun enginePid(): Int? = context.getSystemService(ActivityManager::class.java).runningAppProcesses
        ?.singleOrNull { it.processName == "${context.packageName}:vpn" }?.pid
    private fun awaitStopped() {
        await("TUN and isolated process gone") { vpnNetwork() == null && enginePid() == null }
        Thread.sleep(500); assertNull(vpnNetwork()); assertNull(enginePid())
    }
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        screenshot("failure"); throw AssertionError("Timed out: $label; states=$states")
    }
    private fun screenshot(label: String) {
        // Only the isolated engineering screen/system consent is used here; no account UI.
        val nodes = JSONArray(visibleNodes().map { node -> JSONObject()
            .put("package", node.packageName?.toString()).put("text", node.text?.toString()?.take(400))
            .put("description", node.contentDescription?.toString()?.take(400))
            .put("viewId", node.viewIdResourceName).put("clickable", node.isClickable).put("enabled", node.isEnabled) })
        File(output, "$label-accessibility.json").writeText(nodes.toString(2))
        automation.takeScreenshot()?.let { bitmap ->
            FileOutputStream(File(output, "$label.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun shell(command: String): String = automation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
    }
    private fun record(case: String, value: JSONObject) { evidence.put(value.put("case", case)); writeEvidence() }
    private fun writeEvidence() {
        File(output, "result.json").writeText(JSONObject().put("scope", "disposable API33 emulator; test-app synthetic /32 routes only")
            .put("cases", evidence).put("productionVpnEnabled", false).put("otherProtocolsTested", false).toString(2))
    }
}
