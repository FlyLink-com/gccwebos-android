package com.gccwebos.android

import android.content.ComponentName
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.gccwebos.android.manualimport.ManualQrScannerActivity
import com.gccwebos.android.manualimport.manualDocumentIntent
import org.junit.Assert.*
import org.junit.Test

/** Packaged contracts only; real camera and SAF interactions remain separate device acceptance. */
class ManualImportPlatformContractTest {
    @Test fun filePickerRequestsOnlyOneOpenableDocumentWithoutPersistentOrBroadWriteGrant() {
        val intent = manualDocumentIntent()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("*/*", intent.type)
        assertFalse(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, true))
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        assertNull(intent.data)
    }
    @Test fun onlyThePrivateScannerIsPackagedAsTheCameraResultBoundary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val activity = context.packageManager.getActivityInfo(ComponentName(context, ManualQrScannerActivity::class.java), 0)
        assertFalse(activity.exported)
        try {
            @Suppress("DEPRECATION")
            context.packageManager.getActivityInfo(ComponentName(context.packageName, "com.journeyapps.barcodescanner.CaptureActivity"), 0)
            fail("The library's unrestricted result Activity must not be registered")
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) { }
    }
}
