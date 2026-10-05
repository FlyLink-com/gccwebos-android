package com.gccwebos.android.commerce

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.gccwebos.android.design.Brand

/** Read failures are brief feedback. This never retries or changes any transaction state. */
@Composable
internal fun CommerceReadNotice(owner: Any, error: String?, tag: String) {
    val host = remember(owner) { SnackbarHostState() }
    LaunchedEffect(host, error) {
        host.currentSnackbarData?.dismiss()
        if (!error.isNullOrBlank()) host.showSnackbar(
            error.substringBefore('，').substringBefore('；').removeSuffix("。"),
            duration = SnackbarDuration.Short,
        )
    }
    SnackbarHost(host, Modifier.fillMaxWidth()) { data ->
        Snackbar(containerColor = Brand.SurfaceRaised, contentColor = Brand.Text) {
            Text(data.visuals.message, modifier = Modifier.testTag(tag))
        }
    }
}
