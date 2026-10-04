package com.gccwebos.android.portal

import android.util.Log
import com.gccwebos.android.BuildConfig

/** Diagnostic failure must never change the original storage failure or its cleanup behavior. */
internal object AndroidStorageDiagnostics {
    fun report(target: StoreTarget, operation: StoreOperation, failure: Exception) {
        if (BuildConfig.DEBUG) runCatching {
            Log.w("GCCStorageDiagnostics", StorageDiagnostics.evidence(target, operation, failure).payload())
        }
    }
}
