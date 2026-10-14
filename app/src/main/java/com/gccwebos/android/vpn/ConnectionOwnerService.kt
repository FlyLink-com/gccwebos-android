package com.gccwebos.android.vpn

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.gccwebos.android.BuildConfig
import com.gccwebos.android.ConnectionControlState
import com.gccwebos.android.ConnectionFailure
import com.gccwebos.android.ConnectionPhase
import com.gccwebos.android.ConnectionPowerAction
import com.gccwebos.android.core.ConfigPreparation
import com.gccwebos.android.core.ConnectionCoordinator
import com.gccwebos.android.core.ConnectionOwnerAvailability
import com.gccwebos.android.core.ConnectionOwnerHandoff
import com.gccwebos.android.core.ConnectionState
import com.gccwebos.android.core.ConnectionTimeouts
import com.gccwebos.android.core.Failure
import com.gccwebos.android.core.LocalConfigGateway
import com.gccwebos.android.core.SelectionIntent
import com.gccwebos.android.portal.AtomicPreparedConfigStore
import com.gccwebos.android.portal.ManualSubscriptionTransport
import com.gccwebos.android.portal.OneClickConfigGateway
import com.gccwebos.android.portal.PrepTrace
import com.gccwebos.android.portal.DebugLog
import com.gccwebos.android.portal.LiteralConfigAddressPreparer
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** The only subscription/coordinator owner. UI and engine bindings share this incarnation. */
internal class ConnectionOwnerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: PortalRepository
    private lateinit var permission: ActivityVpnPermission
    private lateinit var tunnel: AndroidVpnTunnel
    private lateinit var handoff: ConnectionOwnerHandoff
    private lateinit var preparedFiles: AtomicPreparedConfigStore
    private var expiryTask: Job? = null
    private val publication = Any()
    private var revision = 0L
    private var commandEpoch = 0L
    private var materialClearFailed = false
    /** Display-only label of the last Ready preparation for the current command epoch. */
    private var preparedNode: com.gccwebos.android.core.PreparedNodeLabel? = null
    private var destroyed = false
    private val controls = MutableStateFlow(ConnectionControlState())
    private val binder = LocalBinder()

    internal inner class LocalBinder : Binder() {
        val repository: PortalRepository get() = this@ConnectionOwnerService.repository
        val permission: ActivityVpnPermission get() = this@ConnectionOwnerService.permission
        val control: StateFlow<ConnectionControlState> get() = controls
        fun power(action: ConnectionPowerAction) = submit(action)
        suspend fun logoutClearingAccountSubscription() = repository.logoutClearingAccountSubscription(ownerCleanup = {
            val coordinator = synchronized(publication) {
                check(!destroyed) { "Connection owner is unavailable" }
                (handoff.state.value as? ConnectionOwnerAvailability.Ready)?.owner
                    ?: error("Connection owner is unavailable")
            }
            try { coordinator.stopAndAwaitClean() }
            catch (_: Exception) { throw PortalFailure(PortalFailure.Reason.STORAGE) }
        }, commitFence = ::commitSourceChange)
        fun commitSourceChange(action: () -> Unit) = synchronized(publication) {
            check(!destroyed && !materialClearFailed && scope.coroutineContext[Job]?.isActive == true) {
                "Connection owner is unavailable"
            }
            val coordinator = (handoff.state.value as? ConnectionOwnerAvailability.Ready)?.owner
                ?: error("Connection owner is unavailable")
            coordinator.withIdleSourceChange {
                // A prior process may have died after writing scratch. In-memory idle alone
                // cannot attest disk absence; only this claimed owner may clear its writer.
                try { preparedFiles.clear() }
                catch (_: Exception) {
                    materialClearFailed = true
                    controls.value = connectionControls(ConnectionState.Failed(Failure.CLEANUP_FAILED), ++revision)
                    throw PortalFailure(PortalFailure.Reason.STORAGE)
                }
                action()
                commandEpoch++
                expiryTask?.cancel(); expiryTask = null
                controls.value = controls.value.copy(revision = ++revision)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) PrepTrace.sink = { DebugLog.i("GCCPrep", it) }
        repository = connectionRepository(applicationContext)
        permission = ActivityVpnPermission(applicationContext)
        val directory = File(noBackupFilesDir, "connection-preparation")
        check(directory.isDirectory || directory.mkdirs())
        val files = AtomicPreparedConfigStore(directory)
        preparedFiles = files
        tunnel = AndroidVpnTunnel(applicationContext, binder, connectionNetworkProbe(), files::clear)
        val addresses = if (BuildConfig.CONNECTION_ENDPOINT_HOSTNAME) AndroidConfigAddressPreparer(applicationContext)
            else LiteralConfigAddressPreparer
        val gateway = OneClickConfigGateway(repository.localSubscriptions, files, addresses)
        val ownedGateway = object : LocalConfigGateway {
            override suspend fun prepare(intent: SelectionIntent): ConfigPreparation {
                val epoch = synchronized(publication) { commandEpoch }
                val result = gateway.prepare(intent)
                synchronized(publication) { if (!destroyed && result is ConfigPreparation.Ready && commandEpoch == epoch) {
                    preparedNode = result.node
                    expiryTask?.cancel()
                    result.config.validUntil?.let { until ->
                        expiryTask = scope.launch {
                            // Recheck wall clock so forward jumps and delayed wakeups cannot renew authority.
                            while (Instant.now() < until) delay(1_000)
                            synchronized(publication) { if (!destroyed && commandEpoch == epoch) {
                                (handoff.state.value as? ConnectionOwnerAvailability.Ready)?.owner?.stop()
                            } }
                        }
                    }
                } }
                return result
            }
            override fun invalidatePending() = gateway.invalidatePending()
            override fun clearPreparedMaterial() = gateway.clearPreparedMaterial()
            override fun observeClear(onClear: () -> Unit) = gateway.observeClear {
                synchronized(publication) { commandEpoch++; expiryTask?.cancel(); expiryTask = null; preparedNode = null }
                try {
                    files.clear()
                    synchronized(publication) { materialClearFailed = false }
                } catch (_: Exception) {
                    synchronized(publication) { materialClearFailed = true }
                    throw PortalFailure(PortalFailure.Reason.STORAGE)
                } finally { onClear() }
            }
        }
        handoff = ConnectionOwnerHandoff(scope,
            awaitClaim = { factory -> repository.localSubscriptions.awaitConnectionOwner(factory) },
            factory = { ConnectionCoordinator(ownedGateway, permission,
                tunnel, tunnel, scope, ConnectionTimeouts(cleanupMillis = 6_500, configMillis = CONFIG_STEP_MILLIS)) })
        scope.launch {
            handoff.state.collectLatest { available ->
                if (available !is ConnectionOwnerAvailability.Ready) {
                    synchronized(publication) {
                        if (!destroyed) controls.value = ConnectionControlState(revision = ++revision)
                    }
                    return@collectLatest
                }
                val coordinator = available.owner
                coordinator.state.collect { state ->
                    synchronized(publication) {
                        if (destroyed || scope.coroutineContext[Job]?.isActive != true ||
                            handoff.state.value != available || coordinator.state.value != state) return@collect
                        DebugLog.lazy("GCCConnectionEngine") { "phase=owner-" + when (state) {
                            is ConnectionState.Failed -> "failed-${state.reason}"
                            is ConnectionState.Working -> "working-${state.step}"
                            else -> state.javaClass.simpleName
                        } }
                        controls.value = if (BuildConfig.VPN_ENGINE_PACKAGED && Build.VERSION.SDK_INT >= 30)
                            connectionControls(if (materialClearFailed) ConnectionState.Failed(Failure.CLEANUP_FAILED) else state, ++revision,
                                preparedNode)
                            else ConnectionControlState(revision = ++revision)
                        if (state == ConnectionState.Disconnected || state is ConnectionState.Failed || state is ConnectionState.Blocked) {
                            preparedNode = null
                            expiryTask?.cancel(); expiryTask = null
                            stopSelf()
                        }
                    }
                }
            }
        }
        handoff.start()
    }

    private fun submit(action: ConnectionPowerAction) {
        synchronized(publication) {
            // A rejection is also an acknowledgement: pending UI slots must always resolve.
            controls.value = controls.value.copy(revision = ++revision)
            if (destroyed || scope.coroutineContext[Job]?.isActive != true) return
            val coordinator = (handoff.state.value as? ConnectionOwnerAvailability.Ready)?.owner ?: return
            when (action) {
                ConnectionPowerAction.Connect -> {
                    if (!controls.value.canConnect) return
                    commandEpoch++; expiryTask?.cancel(); expiryTask = null; preparedNode = null
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
        val retirement = synchronized(publication) {
            destroyed = true
            commandEpoch++
            expiryTask?.cancel(); expiryTask = null
            controls.value = ConnectionControlState(revision = ++revision)
            handoff.retire() // Synchronously fences old commands; lease is retained through cleanup.
        }
        scope.launch {
            try { retirement.await() }
            catch (_: Exception) {
                // Already unavailable/closed. Never turn a cleanup exception into process death.
                DebugLog.i("GCCConnectionEngine", "phase=owner-cleanup-unavailable")
            }
            finally { scope.coroutineContext[Job]?.cancel() }
        }
        super.onDestroy()
    }
    companion object {
        const val ACTION_HOLD = "com.gccwebos.android.vpn.OWNER_HOLD"
        /** Fresh subscription read (its own 30 s bound) plus the 2 s network lease and 5 s DNS bound. */
        const val CONFIG_STEP_MILLIS = ManualSubscriptionTransport.OVERALL_MILLIS + 8_000L
    }
}

/** [activeNode] is attached only to Connected; every other phase drops it so stale labels never show. */
internal fun connectionControls(state: ConnectionState, revision: Long,
    activeNode: com.gccwebos.android.core.PreparedNodeLabel? = null): ConnectionControlState = when (state) {
    ConnectionState.Disconnected -> ConnectionControlState(ConnectionPhase.Disconnected, canConnect = true, revision = revision)
    ConnectionState.Disconnecting -> ConnectionControlState(ConnectionPhase.Disconnecting, revision = revision)
    is ConnectionState.Working -> ConnectionControlState(ConnectionPhase.Connecting, canCancel = true, revision = revision)
    is ConnectionState.Connected -> ConnectionControlState(ConnectionPhase.Connected, canDisconnect = true, revision = revision,
        activeNode = activeNode)
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
