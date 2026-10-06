package com.gccwebos.android.update

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.gccwebos.android.BuildConfig
import com.gccwebos.android.design.GccTheme

internal val LocalAutomaticUpdateController = staticCompositionLocalOf<AutomaticUpdateController?> { null }

/** Installed only in the activity, outside account/service and navigation ownership. */
@Composable
internal fun AutomaticUpdateHost(content: @Composable () -> Unit) {
    if (BuildConfig.DEBUG || BuildConfig.APPLICATION_ID != OfficialReleases.RELEASE_APPLICATION_ID) {
        content()
        return
    }
    val context = LocalContext.current
    val lifecycle = (LocalActivity.current as? LifecycleOwner)?.lifecycle
    if (lifecycle == null) { content(); return }
    val scope = rememberCoroutineScope()
    val controller = remember(scope) {
        AutomaticUpdateController(scope, UpdateRepository(), AndroidAutomaticUpdateStore(context),
            BuildConfig.VERSION_CODE.toLong(), BuildConfig.APPLICATION_ID, BuildConfig.DEBUG)
    }
    AutomaticUpdateHostContent(controller, lifecycle, openWebsite = { url ->
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    }, content = content)
}

@Composable
internal fun AutomaticUpdateHostContent(controller: AutomaticUpdateController, lifecycle: Lifecycle,
    openWebsite: (String) -> Unit, content: @Composable () -> Unit) {
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.onForeground()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> controller.onBackground()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.close() }
    }
    CompositionLocalProvider(LocalAutomaticUpdateController provides controller) { content() }
    val prompt by controller.prompt.collectAsState()
    prompt?.let { result ->
        GccTheme {
            OfficialUpdateDialog(result, onDismiss = controller::dismiss, openWebsite = openWebsite,
                onShown = { controller.promptShown(result.release.versionCode) })
        }
    }
}
