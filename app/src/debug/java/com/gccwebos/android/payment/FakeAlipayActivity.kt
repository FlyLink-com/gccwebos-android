package com.gccwebos.android.payment

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView

/** DEBUG-ONLY stand-in for the Alipay app: shows the link it was handed so the hand-off can be seen on a device. */
class FakeAlipayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent.dataString
        Log.i("FakeAlipay", "received scheme=${intent.data?.scheme} package=${intent.`package`}")
        setContentView(TextView(this).apply { text = "FAKE ALIPAY received:\n$data"; textSize = 18f; setPadding(48, 160, 48, 48) })
    }
}
