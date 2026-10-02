package com.gccwebos.android.vpn

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.gccwebos.android.BuildConfig
import com.gccwebos.android.ConnectionControlState
import com.gccwebos.android.ConnectionFailure
import com.gccwebos.android.ConnectionPhase
import com.gccwebos.android.ConnectionPowerAction
import com.gccwebos.android.core.ConfigPreparation
import com.gccwebos.android.core.ConnectionCoordinator
import com.gccwebos.android.core.ConnectionState
import com.gccwebos.android.core.ConnectionTimeouts
import com.gccwebos.android.core.Failure
import com.gccwebos.android.core.LocalConfigGateway
import com.gccwebos.android.core.SelectionIntent
import com.gccwebos.android.portal.AtomicPreparedConfigStore
import com.gccwebos.android.portal.OneClickConfigGateway
import com.gccwebos.android.portal.PortalRepository
import com.gccwebos.android.portal.PortalFailure
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The only subscription/coordinator owner. UI and engine bindings share this incarnation. */
internal class ConnectionOwnerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: PortalRepository
    private lateinit var permission: ActivityVpnPermission
    private lateinit var tunnel: AndroidVpnTunnel
    private lateinit var coordinator: ConnectionCoordinator
    private var expiryTask: Job? = null
    private val publication = Any()
    private var revision = 0L
    private var commandEpoch = 0L
    private var materialClearFailed = false
    private val controls = MutableStateFlow(ConnectionControlState())
    private val binder = LocalBinder()

    internal inner class LocalBinder : Binder() {
        val repository: PortalRepository get() = this@ConnectionOwnerService.repository
        val permission: ActivityVpnPermission get() = this@ConnectionOwnerService.permission
        val control: StateFlow<ConnectionControlState> get() = controls
        fun power(action: ConnectionPowerAction) = submit(action)
    }

    override fun onCreate() {
        super.onCreate()
        repository = connectionRepository(applicationContext)
        permission = ActivityVpnPermission(applicationContext)
        val directory = File(noBackupFilesDir, "connection-preparation")
        check(directory.isDirectory || directory.mkdirs())
        val files = AtomicPreparedConfigStore(directory)
        tunnel = AndroidVpnTunnel(applicationContext, binder, connectionNetworkProbe(), files::clear)
        val gateway = OneClickConfigGateway(repository.localSubscriptions, files)
        val ownedGateway = object : LocalConfigGateway {
            override suspend fun prepare(intent: SelectionIntent): ConfigPreparation {
                val epoch = synchronized(publication) { commandEpoch }
                val result = gateway.prepare(intent)
                synchronized(publication) { if (result is ConfigPreparation.Ready && commandEpoch == epoch) {
                    expiryTask?.cancel()
                    result.config.validUntil?.let { until ->
                        expiryTask = scope.launch {
                            // Recheck wall clock so forward jumps and delayed wakeups cannot renew authority.
                            while (Instant.now() < until) delay(1_000)
                            synchronized(publication) { if (commandEpoch == epoch) coordinator.stop() }
                        }
                    }
                } }
                return result
            }
            override fun invalidatePending() = gateway.invalidatePending()
            override fun clearPreparedMaterial() = gateway.clearPreparedMaterial()
            override fun observeClear(onClear: () -> Unit) = gateway.observeClear {
                synchronized(publication) { commandEpoch++; expiryTask?.cancel(); expiryTask = null }
                try {
                    files.clear()
                    synchronized(publication) { materialClearFailed = false }
                } catch (_: Exception) {
                    synchronized(publication) { materialClearFailed = true }
                    throw PortalFailure(PortalFailure.Reason.STORAGE)
                } finally { onClear() }
            }
        }
        coordinator = ConnectionCoordinator(ownedGateway, permission,
            tunnel, tunnel, scope, ConnectionTimeouts(cleanupMillis = 6_500))
        scope.launch {
            coordinator.state.collect { state ->
                synchronized(publication) {
                    if (coordinator.state.value != state) return@collect
                    if (BuildConfig.DEBUG) Log.i("GCCConnectionEngine", "phase=owner-" + when (state) {
                        is ConnectionState.Failed -> "failed-${state.reason}"
                        is ConnectionState.Working -> "working-${state.step}"
                        else -> state.javaClass.simpleName
                    })
                    controls.value = if (BuildConfig.VPN_ENGINE_PACKAGED && Build.VERSION.SDK_INT >= 30)
                        connectionControls(if (materialClearFailed) ConnectionState.Failed(Failure.CLEANUP_FAILED) else state, ++revision)
                        else ConnectionControlState(revision = ++revision)
                    if (state == ConnectionState.Disconnected || state is ConnectionState.Failed || state is ConnectionState.Blocked) {
                        expiryTask?.cancel(); expiryTask = null
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun submit(action: ConnectionPowerAction) {
        synchronized(publication) {
            // A rejection is also an acknowledgement: pending UI slots must always resolve.
            controls.value = controls.value.copy(revision = ++revision)
            when (action) {
                ConnectionPowerAction.Connect -> {
                    if (!controls.value.canConnect) return
                    commandEpoch++; expiryTask?.cancel(); expiryTask = null
                    try {
                        startService(Intent(this, ConnectionOwnerService::class.java))
                        val selected = repository.localSubscriptions.nodeSelectionSnapshot().selected
                        coordinator.start(selected?.let { SelectionIntent.Selected(it) } ?: SelectionIntent.Choose)
                    } catch (_: Exception) {
                        controls.value = connectionControls(ConnectionState.Failed(Failure.TUNNEL_FAILED), ++revision)
                        stopSelf()
                    }
                }
                ConnectionPowerAction.Cancel, ConnectionPowerAction.Disconnect -> {
                    commandEpoch++; expiryTask?.cancel(); expiryTask = null
                    coordinator.stop()
                }
                ConnectionPowerAction.None -> Unit
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onDestroy() {
        expiryTask?.cancel()
        scope.launch { try { coordinator.close() } finally { scope.coroutineContext[Job]?.cancel() } }
        super.onDestroy()
    }
    companion object { const val ACTION_HOLD = "com.gccwebos.android.vpn.OWNER_HOLD" }
}

internal fun connectionControls(state: ConnectionState, revision: Long): ConnectionControlState = when (state) {
    ConnectionState.Disconnected -> ConnectionControlState(ConnectionPhase.Disconnected, canConnect = true, revision = revision)
    ConnectionState.Disconnecting -> ConnectionControlState(ConnectionPhase.Disconnecting, revision = revision)
    is ConnectionState.Working -> ConnectionControlState(ConnectionPhase.Connecting, canCancel = true, revision = revision)
    is ConnectionState.Connected -> ConnectionControlState(ConnectionPhase.Connected, canDisconnect = true, revision = revision)
    is ConnectionState.Blocked -> ConnectionControlState(ConnectionPhase.Failed, ConnectionFailure.ResourceUnavailable,
        canConnect = true, revision = revision)
    is ConnectionState.Failed -> ConnectionControlState(ConnectionPhase.Failed,
        when (state.reason) {
            Failure.CONFIG_UNAVAILABLE, Failure.PREPARATION_FAILED -> ConnectionFailure.ReadFailed
            Failure.SELECTION_REQUIRED, Failure.NO_LINE -> ConnectionFailure.ResourceUnavailable
            Failure.TRAFFIC_FAILED -> ConnectionFailure.NetworkUnavailable
            else -> ConnectionFailure.Other
        }, canConnect = state.reason != Failure.CLEANUP_FAILED, revision = revision)
}
