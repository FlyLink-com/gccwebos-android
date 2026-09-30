package com.gccwebos.android.vpn;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;

/** A standalone test-APK process cannot borrow the target APK's Kotlin runtime.
 * Keep this helper platform-only. It transfers OS consent but never creates a TUN/socket.
 */
public final class AcceptanceRevokerActivity extends Activity {
    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!"ranchu".equals(Build.HARDWARE) && !"goldfish".equals(Build.HARDWARE)) {
            throw new IllegalStateException("Disposable emulator only");
        }
        Intent permission = VpnService.prepare(this);
        if (permission == null) finish();
        else startActivityForResult(permission, 7101);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 7101) finish();
    }
}
