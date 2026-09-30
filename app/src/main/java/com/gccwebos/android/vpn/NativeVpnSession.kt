package com.gccwebos.android.vpn

import java.util.concurrent.atomic.AtomicBoolean

interface OwnedTun : AutoCloseable { val fd: Int }
interface NativeEngine {
    /** Return only after the core owns its duplicated TUN; never equates to verified traffic. */
    fun start(config: ByteArray, tunFd: Int)
    /** Return only when the native TUN, goroutines and listeners have stopped. */
    fun stop()
}
enum class VpnLabState { IDLE, STARTING, ENGINE_RUNNING_UNVERIFIED, STOPPING, FAILED, CLEANUP_FAILED }

/** Serial worker confinement for start/stop. requestStop may run on Android's main thread.
 * Service supplies an independent process-kill watchdog because native calls can block forever.
 */
class NativeVpnSession(private val engine: NativeEngine, private val openTun: () -> OwnedTun,
    private val publish: (VpnLabState) -> Unit) {
    private val cancelled = AtomicBoolean(false)
    private val publication = Any()
    private var tun: OwnedTun? = null
    private var nativeMayOwnResources = false
    private var cleanupFailed = false
    private var started = false

    fun requestStop() = synchronized(publication) { cancelled.set(true); publish(VpnLabState.STOPPING) }
    fun start(config: ByteArray) {
        if (started || cleanupFailed || cancelled.get()) { config.fill(0); return }
        started = true
        publish(VpnLabState.STARTING)
        try {
            if (cancelled.get()) return
            tun = openTun()
            if (cancelled.get()) return
            nativeMayOwnResources = true // partial or throwing native starts still need stop.
            engine.start(config, requireNotNull(tun).fd)
            synchronized(publication) { if (!cancelled.get()) publish(VpnLabState.ENGINE_RUNNING_UNVERIFIED) }
        } catch (_: LinkageError) {
            cancelled.set(true)
            publish(VpnLabState.FAILED)
        } catch (_: Exception) {
            cancelled.set(true)
            publish(VpnLabState.FAILED) // never expose config/credential-bearing exception strings.
        } finally {
            config.fill(0)
            if (cancelled.get()) stop()
        }
    }
    fun stop() {
        cancelled.set(true)
        publish(VpnLabState.STOPPING)
        var failed = false
        try {
            if (nativeMayOwnResources) {
                engine.stop()
                nativeMayOwnResources = false
            }
        } catch (_: LinkageError) { failed = true }
        catch (_: Exception) { failed = true }
        finally {
            try { tun?.close(); tun = null } catch (_: Exception) { failed = true }
        }
        cleanupFailed = failed || nativeMayOwnResources || tun != null
        publish(if (cleanupFailed) VpnLabState.CLEANUP_FAILED else VpnLabState.IDLE)
    }
}
