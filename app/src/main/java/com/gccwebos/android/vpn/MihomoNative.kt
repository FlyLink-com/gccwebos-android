package com.gccwebos.android.vpn

/** JNI socket hook runs on core threads. False MUST abort socket creation, never bypass VPN. */
interface SocketProtector { fun protectSocket(fd: Int): Boolean }

object MihomoNative {
    private var loaded = false
    @Synchronized fun load(): Boolean {
        if (loaded) return true
        return try { System.loadLibrary("gccmihomo"); loaded = true; true }
        catch (_: UnsatisfiedLinkError) { false }
        catch (_: SecurityException) { false }
    }
    @JvmStatic external fun nativeStart(configYaml: String, homeDir: String, tunFd: Int, protector: SocketProtector): String?
    @JvmStatic external fun nativeStop()
    @JvmStatic external fun nativeVersion(): String
}
