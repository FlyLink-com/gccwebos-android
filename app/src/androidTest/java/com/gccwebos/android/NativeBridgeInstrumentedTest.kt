package com.gccwebos.android

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import com.gccwebos.android.vpn.MihomoNative
import com.gccwebos.android.vpn.SocketProtector
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** JNI invocation on an Android runtime, not a device VPN packet-path acceptance test. */
class NativeBridgeInstrumentedTest {
    @Test fun vpnLabManifestIsPrivateAndExplicitlyGated() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val service = context.packageManager.getServiceInfo(ComponentName(context.packageName,
            "com.gccwebos.android.vpn.LabVpnService"), PackageManager.MATCH_DISABLED_COMPONENTS)
        assertFalse(service.exported)
        assertEquals("android.permission.BIND_VPN_SERVICE", service.permission)
        assertEquals(context.packageName + ":vpn", service.processName)
        assertEquals(BuildConfig.NATIVE_VPN_LAB, service.enabled)
    }
    @Test fun actualJniLibraryLoadsAndRejectsUnsafeInputWithoutTakingCallerFd() {
        assumeTrue(BuildConfig.NATIVE_VPN_LAB)
        assertTrue("Actual packaged native library must load", MihomoNative.load())
        assertTrue(MihomoNative.nativeVersion().contains("v1.19.31@ab405bad5beeeac8b003bb01f60f134f6df54471"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val descriptors = ParcelFileDescriptor.createPipe()
        try {
            val protector = object : SocketProtector { override fun protectSocket(fd: Int): Boolean = false }
            val result = MihomoNative.nativeStart("{\"external-controller\":\"0.0.0.0:9090\"}",
                context.noBackupFilesDir.absolutePath, descriptors[0].fd, protector)
            assertEquals("CONFIG_REJECTED", result)
            assertNotNull(Os.fstat(descriptors[0].fileDescriptor))
            MihomoNative.nativeStop()
            assertNotNull(Os.fstat(descriptors[0].fileDescriptor))
        } finally { descriptors.forEach { it.close() } }
    }
}
