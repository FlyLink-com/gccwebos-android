package com.gccwebos.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.ParcelFileDescriptor
import com.gccwebos.android.BuildConfig
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Only present in debug APKs and disabled unless built with -PnativeVpnLab=true.
 * Runs in a dedicated process so a hung native stop cannot keep a TUN alive indefinitely.
 */
class LabVpnService : VpnService(), SocketProtector {
    private val commands = Executors.newSingleThreadExecutor()
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val stopping = AtomicBoolean(false)
    private var admitted = false
    private var isolatedProcess = false
    @Volatile private var underlying: Network? = null
    private var networkObserver: ConnectivityManager.NetworkCallback? = null
    override fun onCreate() {
        super.onCreate()
        val name = if (Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
            else runCatching { java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrNull()
        isolatedProcess = name == "$packageName:vpn"
    }
    @Volatile private var session: NativeVpnSession? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isolatedProcess || !BuildConfig.DEBUG || !BuildConfig.NATIVE_VPN_LAB || intent?.action != ACTION_START) {
            stopSession(); return START_NOT_STICKY
        }
        if (admitted || stopping.get()) return START_NOT_STICKY
        admitted = true
        if (prepare(this) != null || !MihomoNative.load()) {
            announce("NATIVE_OR_PERMISSION_UNAVAILABLE"); stopSession(); return START_NOT_STICKY
        }
        try {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            val physical = connectivity.activeNetwork ?: throw IllegalStateException("No underlying network")
            val capabilities = connectivity.getNetworkCapabilities(physical)
                ?: throw IllegalStateException("No underlying capabilities")
            check(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
            underlying = physical
            val observer = object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) { if (network == underlying) main.post { stopSession() } }
            }
            networkObserver = observer
            connectivity.registerNetworkCallback(NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), observer)
        } catch (_: Exception) { announce("UNDERLYING_NETWORK_UNAVAILABLE"); stopSession(); return START_NOT_STICKY }
        try { foreground() } catch (_: Exception) { announce("FOREGROUND_FAILED"); stopSession(); return START_NOT_STICKY }
        val next = NativeVpnSession(object : NativeEngine {
            override fun start(config: ByteArray, tunFd: Int) {
                val home = java.io.File(noBackupFilesDir, "native-lab").apply { mkdirs() }
                val error = MihomoNative.nativeStart(String(config, Charsets.UTF_8), home.absolutePath, tunFd, this@LabVpnService)
                if (error != null) throw IllegalStateException("Native startup rejected")
            }
            override fun stop() { MihomoNative.nativeStop() }
        }, {
            val descriptor = Builder().setSession("GCC isolated native lab")
                .setMtu(1500).addAddress("172.19.0.1", 30).addDnsServer("172.19.0.2")
                .addRoute("0.0.0.0", 0).addAddress("fdfe:dcba:9876::1", 126).addRoute("::", 0)
                .addAllowedApplication(packageName) // Never capture other applications in this milestone.
                .setUnderlyingNetworks(arrayOf(requireNotNull(underlying)))
                .setBlocking(false).establish() ?: throw IllegalStateException("VPN permission absent")
            object : OwnedTun {
                override val fd: Int get() = descriptor.fd
                override fun close() = descriptor.close()
            }
        }, { state ->
            if (!stopping.get() || state != VpnLabState.ENGINE_RUNNING_UNVERIFIED) announce(state.name)
            if (state == VpnLabState.CLEANUP_FAILED || state == VpnLabState.FAILED) main.post { stopSession() }
        })
        session = next
        // Service accepts no credentials/URLs/config from Intent, storage, IPC, account or production.
        commands.execute {
            try {
                val yaml = LAB_YAML.toByteArray(Charsets.UTF_8)
                val config = try { SafeProxyConfig.project(yaml, 0) } finally { yaml.fill(0) }
                next.start(config)
            } catch (_: Exception) { announce("LAB_START_FAILED"); main.post { stopSession() } }
        }
        return START_NOT_STICKY
    }

    override fun protectSocket(fd: Int): Boolean {
        if (stopping.get() || fd < 0) return false
        val physical = underlying ?: return false
        return try {
            if (!protect(fd)) return false
            // Bind the same socket via a temporary dup; never adopt/close the core's descriptor.
            ParcelFileDescriptor.fromFd(fd).use { physical.bindSocket(it.fileDescriptor) }
            !stopping.get()
        } catch (_: Exception) { false }
    }
    override fun onRevoke() { stopSession(); super.onRevoke() }
    override fun onTaskRemoved(rootIntent: Intent?) { stopSession(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() { stopSession(); super.onDestroy() }

    private fun stopSession() {
        if (!stopping.compareAndSet(false, true)) return
        networkObserver?.let { observer ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(observer) }
        }
        networkObserver = null
        session?.requestStop()
        announce("STOPPING")
        // Do not rely on coroutine cancellation to interrupt a blocking JNI call.
        watchdog.schedule({ terminateEngineProcess() }, 4, TimeUnit.SECONDS)
        commands.execute {
            try { session?.stop() } finally {
                main.post {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    terminateEngineProcess() // Also wipes process-owned native credential memory.
                }
            }
        }
    }
    private fun terminateEngineProcess() {
        // Identity verified before any native work; never terminate the UI process.
        if (isolatedProcess) Process.killProcess(Process.myPid())
    }
    private fun announce(state: String) {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra("state", state), "$packageName.permission.VPN_LAB_STATE")
    }
    private fun foreground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Native VPN lab", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, LabVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("GCC 原生 VPN 隔离实验")
            .setContentText("仅接管本应用流量；未验证真实服务。点停止恢复。")
            .setOngoing(true).addAction(Notification.Action.Builder(null, "停止实验", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(2201, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(2201, notification)
    }
    companion object {
        const val ACTION_START = "com.gccwebos.android.vpn.LAB_START"
        const val ACTION_STOP = "com.gccwebos.android.vpn.LAB_STOP"
        const val ACTION_STATE = "com.gccwebos.android.vpn.LAB_STATE"
        private const val CHANNEL = "gcc_native_vpn_lab"
        // Loopback-only synthetic endpoint. No actual subscriber credential or production route.
        private val LAB_YAML = """
            proxies:
              - name: isolated-loopback
                type: ss
                server: 127.0.0.1
                port: 18388
                cipher: aes-128-gcm
                password: lab-only-password
                udp: true
        """.trimIndent()
    }
}
