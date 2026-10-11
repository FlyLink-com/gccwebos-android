package com.gccwebos.android.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/** Directory (under the app-private cache) that holds update files; exposed only through the update FileProvider. */
fun updateDirectory(context: Context) = File(context.cacheDir, "update")

class AndroidUpdateEnvironment(private val context: Context) : UpdateEnvironment {
    override fun isMetered(): Boolean = try {
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    } catch (_: Exception) { true }

    override fun canRequestInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun openInstallSettings(): Boolean = try {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .also(::keepTask))
        true
    } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }

    override fun launchInstaller(file: File): Boolean = try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updatefiles", file)
        context.startActivity(Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).also(::keepTask))
        true
    } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false } catch (_: IllegalArgumentException) { false }

    /** An Activity context starts screens inside the current task; only a non-Activity context needs a new one. */
    private fun keepTask(intent: Intent) { if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
}
