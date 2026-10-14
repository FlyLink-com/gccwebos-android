package com.gccwebos.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import com.gccwebos.android.BuildConfig
import com.gccwebos.android.portal.DebugLog
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One-shot :vpn owner of native/TUN resources. Binder admission never executes JNI.
 * Full IPv4 routing; all IPv6 is captured and rejected by the independently fixed native rules.
 */
class ProductionVpnService : VpnService(), SocketProtector {
    private fun trace(phase: String) = DebugLog.i("GCCConnectionEngine", "phase=$phase")
    private val commands = Executors.newSingleThreadExecutor()
    private val watchdog = Executors.newScheduledThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val admission = ProductionVpnAdmission()
    private val admissionLock = Any()
    private val stopping = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val state = AtomicInteger(VpnControl.STARTING)
    @Volatile private var isolatedProcess = false
    @Volatile private var ready = false
    @Volatile private var underlying: Network? = null
    @Volatile private var ticket: ProductionVpnAdmission.Ticket? = null
    @Volatile private var session: NativeVpnSession? = null
    private var networkObserver: ConnectivityManager.NetworkCallback? = null
    private var pinnedObserver: AndroidPhysicalNetworkMonitor? = null
    private var transportPin: VpnTransportPin? = null
    private var idleWatchdog: ScheduledFuture<*>? = null
    private var startWatchdog: ScheduledFuture<*>? = null
    private var ownerBinder: IBinder? = null
    private var ownerDeath: IBinder.DeathRecipient? = null
    @Volatile private var ownerHold: ServiceConnection? = null

    override fun onCreate() {
        super.onCreate()
        val name = if (Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
            else runCatching { java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrNull()
        isolatedProcess = name == "$packageName:vpn"
        trace(if (isolatedProcess) "process-ready" else "process-rejected")
        if (isolatedProcess) idleWatchdog = watchdog.schedule({
            if (ticket == null) terminateSelf()
        }, 15, TimeUnit.SECONDS)
    }

    override fun onBind(intent: Intent?): IBinder? = when (intent?.action) {
        SERVICE_INTERFACE -> super.onBind(intent)
        VpnControl.ACTION_BIND -> if (isolatedProcess) {
            if (!prepareTransport(intent)) failTransport()
            // Even rejected transport binds publish the exact Binder for STOP/death cleanup.
            control
        } else null
        else -> null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == VpnControl.ACTION_STOP) stopSession()
        return START_NOT_STICKY // Intent can never carry configuration or start an engine.
    }

    private fun physical(connectivity: ConnectivityManager, network: Network): Boolean {
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }

    private fun prepareTransport(intent: Intent): Boolean {
        if (!isolatedProcess || stopping.get() || failed.get() || prepare(this) != null) { trace("transport-precondition-rejected"); return false }
        try {
            val hasHandle = intent.hasExtra(VpnControl.EXTRA_NETWORK_HANDLE)
            val hasDeadline = intent.hasExtra(VpnControl.EXTRA_LEASE_DEADLINE)
            if (hasHandle != hasDeadline) return false
            val pin = if (hasHandle) VpnTransportPin(intent.getLongExtra(VpnControl.EXTRA_NETWORK_HANDLE, 0),
                intent.getLongExtra(VpnControl.EXTRA_LEASE_DEADLINE, 0)) else VpnTransportPin(0, 0)
            if ((hasHandle && !pin.leased) || !pin.permitsStart(android.os.SystemClock.elapsedRealtime())) return false
            if (!hasHandle && BuildConfig.CONNECTION_ENDPOINT_HOSTNAME && !BuildConfig.CONNECTION_SYNTHETIC_ACCEPTANCE) return false
            if (ready) return transportPin?.let {
                it.networkHandle == pin.networkHandle && it.validUntilElapsedMillis == pin.validUntilElapsedMillis
            } == true
            val connectivity = getSystemService(ConnectivityManager::class.java)
            val selected = if (pin.leased) {
                if (Build.VERSION.SDK_INT < 30) return false
                Network.fromNetworkHandle(pin.networkHandle).takeIf {
                    physical(connectivity, it) && AndroidPhysicalNetworkMonitor.selectionCurrent(connectivity, it)
                }
            } else {
                connectivity.activeNetwork?.takeIf { physical(connectivity, it) }
                    ?: connectivity.allNetworks.firstOrNull { physical(connectivity, it) }
                    ?: run { trace("transport-no-physical-network"); return false }
            }
            if (selected == null) return false
            underlying = selected
            transportPin = pin
            if (pin.leased) {
                val observer = AndroidPhysicalNetworkMonitor(connectivity, selected) { failTransport() }
                pinnedObserver = observer
                if (!observer.start() || !observer.readbackCurrent()) return false
            } else {
            val observer = object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) { if (network == selected) stopSession() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (network == selected && (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))) stopSession()
                }
            }
            networkObserver = observer
            connectivity.registerNetworkCallback(NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), observer)
            }
            foreground()
            ready = !stopping.get() && !failed.get()
            trace("transport-ready")
            return ready
        } catch (failure: Exception) { trace("transport-failed-${failure.javaClass.simpleName}"); return false }
    }

    private fun failTransport() = synchronized(admissionLock) {
        if (ticket != null) { stopSession(true); return }
        ready = false
        failed.set(true)
        state.set(VpnControl.FAILED)
        pinnedObserver?.close()
        pinnedObserver = null
        networkObserver?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        networkObserver = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Preserve the unpublished exact Binder until STOP or the existing idle watchdog.
    }

    private val control = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (Binder.getCallingUid() != Process.myUid()) { trace("caller-uid-rejected"); throw SecurityException("VPN_CALLER_UID") }
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(VpnControl.DESCRIPTOR); return true }
            trace("control-flags-$flags")
            if (reply == null || !VpnControl.isSynchronousCall(true, flags)) return false
            try { data.enforceInterface(VpnControl.DESCRIPTOR) }
            catch (failure: SecurityException) { trace("interface-rejected"); throw failure }
            when (code) {
                VpnControl.COPY_START -> {
                    trace("copy-entered")
                    var config: ByteArray? = null
                    val accepted = try {
                        if (data.dataAvail() !in 1..(VpnControl.MAX_CONFIG_BYTES + 256)) false else {
                            val owner = data.readStrongBinder()
                            val generation = data.readLong()
                            val expiry = data.readLong()
                            val admissionDeadline = data.readLong()
                            val arrayPosition = data.dataPosition()
                            val size = data.readInt()
                            if (owner == null || size !in 1..VpnControl.MAX_CONFIG_BYTES) false else {
                                data.setDataPosition(arrayPosition)
                                config = ByteArray(size)
                                data.readByteArray(requireNotNull(config))
                                val extra = data.dataAvail()
                                val hasPin = extra == 16
                                val handle = if (hasPin) data.readLong() else 0L
                                val deadline = if (hasPin) data.readLong() else 0L
                                (extra == 0 || hasPin) && data.dataAvail() == 0 && copyStart(owner, generation, expiry,
                                    admissionDeadline, requireNotNull(config), hasPin, handle, deadline)
                            }
                        }
                    } catch (failure: Exception) { trace("copy-parse-failed-${failure.javaClass.simpleName}"); false } finally { config?.fill(0) }
                    trace(if (accepted) "copy-accepted" else "copy-rejected")
                    reply.writeNoException(); reply.writeInt(if (accepted) 1 else 0)
                }
                VpnControl.STOP -> {
                    if (data.dataAvail() != 0) throw IllegalArgumentException("VPN_STOP_FIELDS")
                    stopSession(); reply.writeNoException(); reply.writeInt(1)
                }
                VpnControl.STATE -> {
                    if (data.dataAvail() != 0) throw IllegalArgumentException("VPN_STATE_FIELDS")
                    reply.writeNoException(); reply.writeInt(state.get())
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
            return true
        }
    }

    private fun copyStart(owner: IBinder, generation: Long, expiry: Long,
        admissionDeadline: Long, config: ByteArray, hasPin: Boolean, handle: Long, deadline: Long): Boolean =
        synchronized(admissionLock) {
            if (!ready || failed.get() || stopping.get() || ticket != null || !owner.isBinderAlive) { trace("copy-state-rejected"); return false }
            if (transportPin?.permitsCopy(hasPin, handle, deadline, android.os.SystemClock.elapsedRealtime()) != true ||
                !networkCurrent()) { stopSession(); return false }
            if (!ProductionVpnAdmission.validAdmissionDeadline(admissionDeadline,
                    android.os.SystemClock.elapsedRealtime())) {
                stopSession(); return false
            }
            val death = IBinder.DeathRecipient { stopSession() }
            try { owner.linkToDeath(death, 0) } catch (_: Exception) { return false }
            ownerBinder = owner; ownerDeath = death
            val hold = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    if (service != owner || stopping.get()) { trace("owner-lease-rejected"); stopSession() }
                    else trace("owner-lease-ready")
                }
                override fun onServiceDisconnected(name: ComponentName) { stopSession() }
                override fun onBindingDied(name: ComponentName) { stopSession() }
                override fun onNullBinding(name: ComponentName) { stopSession() }
            }
            try {
                if (!bindService(Intent(this, ConnectionOwnerService::class.java)
                        .setAction(ConnectionOwnerService.ACTION_HOLD), hold, BIND_AUTO_CREATE)) {
                    stopSession(true); return false
                }
                ownerHold = hold
                if (stopping.get() || !owner.isBinderAlive) { stopSession(); return false }
                val accepted = admission.copyAndArm(generation, expiry, config,
                    System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime(),
                    admissionDeadlineElapsedMillis = admissionDeadline)
                    ?: run { stopSession(true); return false }
                ticket = accepted
                trace("copy-armed")
                idleWatchdog?.cancel(false)
                startWatchdog = watchdog.schedule({
                    if (state.get() == VpnControl.STARTING && !stopping.get()) terminateSelf()
                }, 10, TimeUnit.SECONDS)
                if (accepted.expiryEpochMillis != 0L) {
                    val remaining = maxOf(0, accepted.expiryElapsedMillis - android.os.SystemClock.elapsedRealtime())
                    watchdog.schedule({ stopSession() }, remaining, TimeUnit.MILLISECONDS)
                }
                commands.execute { startEngine(accepted) }
                !stopping.get()
            } catch (failure: Exception) { trace("copy-failed-${failure.javaClass.simpleName}"); stopSession(true); false }
        }

    private fun active(current: ProductionVpnAdmission.Ticket): Boolean = !stopping.get() &&
        admission.active(current, System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime())

    private fun networkCurrent(): Boolean = underlying?.let {
        if (transportPin?.leased == true) pinnedObserver?.readbackCurrent() == true
        else physical(getSystemService(ConnectivityManager::class.java), it)
    } == true
    private fun startNetworkCurrent(): Boolean = networkCurrent() &&
        transportPin?.permitsStart(android.os.SystemClock.elapsedRealtime()) == true

    private fun startEngine(current: ProductionVpnAdmission.Ticket) {
        val observer = pinnedObserver
        if (observer != null && !observer.awaitInitialBest(minOf(current.admissionDeadlineElapsedMillis,
                transportPin?.validUntilElapsedMillis ?: 0))) { stopSession(); return }
        val config = admission.take(current, System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime())
            ?: run { trace("engine-material-rejected"); stopSession(); return }
        try {
            if (!active(current) || !startNetworkCurrent() || !MihomoNative.load()) { trace("engine-library-rejected"); stopSession(true); return }
            val next = NativeVpnSession(object : NativeEngine {
                override fun start(config: ByteArray, tunFd: Int) {
                    check(active(current) && startNetworkCurrent())
                    val home = java.io.File(noBackupFilesDir, "native-vpn").apply { mkdirs() }
                    check(home.isDirectory)
                    val error = MihomoNative.nativeStart(String(config, Charsets.UTF_8), home.absolutePath,
                        tunFd, this@ProductionVpnService)
                    if (error != null) throw IllegalStateException("VPN_NATIVE_REJECTED")
                }
                override fun stop() { MihomoNative.nativeStop() }
            }, {
                check(active(current) && startNetworkCurrent())
                val network = requireNotNull(underlying)
                check(physical(getSystemService(ConnectivityManager::class.java), network))
                val builder = Builder().setSession("GCC VPN").setMtu(1500)
                    .addAddress("172.19.0.1", 30).addDnsServer("172.19.0.2").addRoute("0.0.0.0", 0)
                    .addAddress("fdfe:dcba:9876::1", 126).addRoute("::", 0)
                    .setUnderlyingNetworks(arrayOf(network)).setBlocking(false)
                if (BuildConfig.CONNECTION_SYNTHETIC_ACCEPTANCE) builder.addAllowedApplication(packageName)
                val descriptor = builder.establish()
                    ?: throw IllegalStateException("VPN_PERMISSION_UNAVAILABLE")
                object : OwnedTun {
                    override val fd: Int get() = descriptor.fd
                    override fun close() = descriptor.close()
                }
            }, { nativeState ->
                trace("native-$nativeState")
                when (nativeState) {
                    VpnLabState.ENGINE_RUNNING_UNVERIFIED -> {
                        if (active(current) && state.compareAndSet(VpnControl.STARTING, VpnControl.RUNNING)) {
                            startWatchdog?.cancel(false)
                        } else stopSession()
                    }
                    VpnLabState.FAILED, VpnLabState.CLEANUP_FAILED -> stopSession(true)
                    else -> Unit
                }
            })
            session = next
            if (stopping.get()) next.requestStop()
            next.start(config)
        } catch (failure: Exception) { trace("engine-failed-${failure.javaClass.simpleName}"); stopSession(true) }
        catch (_: LinkageError) { trace("engine-linkage-failed"); stopSession(true) }
        finally { config.fill(0) }
    }

    override fun protectSocket(fd: Int): Boolean {
        val current = ticket ?: return false
        if (fd < 0 || !active(current)) { stopSession(); return false }
        val network = underlying ?: return false
        return try {
            if (!networkCurrent() || !protect(fd)) { stopSession(); return false }
            ParcelFileDescriptor.fromFd(fd).use { network.bindSocket(it.fileDescriptor) }
            (active(current) && networkCurrent()).also { if (!it) stopSession() }
        } catch (_: Exception) { stopSession(); false }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (intent?.action == VpnControl.ACTION_BIND) stopSession()
        return super.onUnbind(intent)
    }
    override fun onRevoke() { stopSession(); super.onRevoke() }
    override fun onDestroy() { stopSession(); super.onDestroy() }

    private fun stopSession(failure: Boolean = false) {
        if (failure) failed.set(true)
        state.set(if (failed.get()) VpnControl.FAILED else VpnControl.STOPPING)
        if (!stopping.compareAndSet(false, true)) return
        admission.stop()
        // Independent of the serial JNI worker; coroutine/Future cancellation cannot interrupt JNI.
        watchdog.schedule({ terminateSelf() }, 4, TimeUnit.SECONDS)
        session?.requestStop()
        networkObserver?.let { observer ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(observer) }
        }
        networkObserver = null
        pinnedObserver?.close()
        pinnedObserver = null
        commands.execute {
            try { session?.stop() } finally {
                main.post {
                    ownerHold?.let { runCatching { unbindService(it) } }
                    ownerHold = null
                    ownerDeath?.let { recipient -> runCatching { ownerBinder?.unlinkToDeath(recipient, 0) } }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    terminateSelf()
                }
            }
        }
    }

    private fun terminateSelf() {
        if (isolatedProcess) Process.killProcess(Process.myPid()) // Never terminate an external PID.
    }

    private fun foreground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "GCC VPN", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0,
            Intent(this, ProductionVpnService::class.java).setAction(VpnControl.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("GCC VPN").setContentText("点停止关闭 VPN").setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(2202, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        else startForeground(2202, notification)
    }
    companion object { private const val CHANNEL = "gcc_production_vpn" }
}
