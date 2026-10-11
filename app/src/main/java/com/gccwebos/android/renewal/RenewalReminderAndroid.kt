package com.gccwebos.android.renewal

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.Notification
import com.gccwebos.android.MainActivity
import com.gccwebos.android.R
import java.time.Instant

/** AlarmManager + notification side of the reminder. */
internal class AndroidRenewalAlarms(context: Context) : RenewalAlarms {
    private val app = context.applicationContext

    override fun arm(at: Instant) {
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        // Inexact and Doze-friendly: no SCHEDULE_EXACT_ALARM permission, no wake lock, one alarm per day at most.
        manager.setAndAllowWhileIdle(AlarmManager.RTC, at.toEpochMilli(), alarmIntent(app))
    }

    override fun cancel() {
        app.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(app))
    }

    override fun dismissPosted() {
        app.getSystemService(NotificationManager::class.java)?.cancel(RenewalSpec.NOTIFICATION_ID)
    }

    companion object {
        const val ACTION_ALARM = "com.gccwebos.android.action.RENEWAL_ALARM"

        fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 0,
            Intent(context, RenewalReminderReceiver::class.java).setAction(ACTION_ALARM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        /** Tap target: bring the app to the plans page. */
        fun openPlansIntent(context: Context): PendingIntent = PendingIntent.getActivity(context, 1,
            openPlansActivityIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun openPlansActivityIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
            .setAction(RenewalSpec.ACTION_OPEN_PLANS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(RenewalSpec.CHANNEL_ID, "续费提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "套餐临近到期时，每天晚上 8 点（北京时间）提醒续费"
                })
        }
    }
}

/** True only when the user has allowed notifications (Android 13+ runtime permission or the app-level switch). */
internal fun notificationsAllowed(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true

/**
 * Receives the daily alarm, plus boot and package-replace (alarms do not survive either). It works from the
 * stored expiry only: no network call, no session access, no foreground service, no wake lock.
 */
class RenewalReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val coordinator = RenewalReminderCoordinator(SharedRenewalPrefs(app), AndroidRenewalAlarms(app))
        when (intent.action) {
            AndroidRenewalAlarms.ACTION_ALARM -> {
                val now = Instant.now()
                val post = coordinator.onAlarm(now)
                val validUntil = SharedRenewalPrefs(app).validUntil
                if (post && validUntil != null && notificationsAllowed(app)) {
                    AndroidRenewalAlarms.ensureChannel(app)
                    val notification = Notification.Builder(app, RenewalSpec.CHANNEL_ID)
                        .setSmallIcon(R.drawable.brand_lightning)
                        .setContentTitle(NOTIFICATION_TITLE)
                        .setContentText(notificationText(validUntil))
                        .setStyle(Notification.BigTextStyle().bigText(notificationText(validUntil)))
                        .setContentIntent(AndroidRenewalAlarms.openPlansIntent(app))
                        .setAutoCancel(true)
                        .setCategory(Notification.CATEGORY_REMINDER)
                        .setVisibility(Notification.VISIBILITY_PRIVATE)
                        .build()
                    app.getSystemService(NotificationManager::class.java)?.notify(RenewalSpec.NOTIFICATION_ID, notification)
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> coordinator.onRestore(Instant.now())
        }
    }
}
