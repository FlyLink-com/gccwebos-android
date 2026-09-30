package com.gccwebos.android.vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle

/** Runs as the separate instrumentation APK UID. Real Android permission transfer revokes the
 * target VPN; this component NEVER establishes a TUN or opens a network socket.
 */
class AcceptanceRevokerActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val permission = VpnService.prepare(this)
        if (permission == null) finish()
        else @Suppress("DEPRECATION") startActivityForResult(permission, 7101)
    }
    @Deprecated("Test-only platform callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7101) finish()
    }
}
class AcceptanceRevokerService : VpnService() // Declared for platform preparation; never started.
