package com.gccwebos.android

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.gccwebos.android.design.GccTheme
import com.gccwebos.android.portal.*
import com.gccwebos.android.renewal.*
import kotlinx.coroutines.CoroutineScope
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * PR #55 on-device evidence. Synthetic in-memory account (no network, no real account). It does use the
 * side-by-side .debug package's own SharedPreferences, AlarmManager and NotificationManager, and wipes them.
 */
class RenewalReminderDeviceEvidenceTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = SharedRenewalPrefs(context)
    private val alarms = AndroidRenewalAlarms(context)

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString() }
    }
    private fun wipe() {
        prefs.clear(); alarms.cancel(); alarms.dismissPosted()
    }
    @Before fun before() { wipe() }
    @After fun after() { wipe() }

    private fun expiring(scope: CoroutineScope, days: Long, usage: Usage? = null) = offlineUiAccount(scope).apply {
        val now = Instant.now()
        // Exactly `days` days: daysLeftCeil rounds up, so an extra hour would make days=3 render
        // as 4 (the old `+ 3600` here). The fixture's `days` must mean the rendered count.
        resource = Resource.Available("resource_" + "a".repeat(64), "synthetic", now.plusSeconds(days * 86400), false,
            usage ?: Usage(100L shl 30, 20L shl 30, 80L shl 30, "measured", now), 30, 100.0)
    }

    private fun screenshot(name: String) {
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "ui-evidence/pr55")
        check(directory.isDirectory || directory.mkdirs())
        val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { assertTrue(shot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun rootCrop(heightPx: Int): Bitmap {
        val full = compose.onRoot().captureToImage().asAndroidBitmap()
        return Bitmap.createBitmap(full, 0, 0, full.width, heightPx.coerceAtMost(full.height))
    }
    private fun save(bitmap: Bitmap, name: String) {
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "ui-evidence/pr55")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun differs(a: Bitmap, b: Bitmap): Boolean {
        for (y in 0 until a.height step 2) for (x in 0 until a.width step 3) if (a.getPixel(x, y) != b.getPixel(x, y)) return true
        return false
    }
    private fun launchShell(days: Long, autoAdvance: Boolean = true, animatorScale: Float? = null) {
        compose.mainClock.autoAdvance = autoAdvance
        compose.setContent {
            val scope = rememberCoroutineScope()
            // This phone refuses `settings put` from adb, so "remove animations" is simulated through the test seam.
            androidx.compose.runtime.CompositionLocalProvider(com.gccwebos.android.design.LocalAnimatorScaleOverride provides animatorScale) {
                GccTheme { AppShell(remember { expiring(scope, days) }, autoLoadCommerce = false, renewalReminders = true) }
            }
        }
    }

    @Test fun farFromExpiryShowsNothingAndNeverAsksForNotificationPermission() {
        launchShell(days = 60)
        compose.waitForIdle()
        compose.onNodeWithTag("renewal-popup").assertDoesNotExist()
        compose.onNodeWithTag("renewal-banner").assertDoesNotExist()
        assertFalse(prefs.permissionAsked)
        screenshot("00-far-from-expiry-no-reminder")
    }

    @Test fun popupAsksPermissionOnceThenBannerThenDismissedForTheDay() {
        // Skip when this side-by-side package already holds the notification permission (the prompt would not appear).
        assumeTrue("notification permission already granted", context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED)
        assertFalse(prefs.permissionAsked)
        launchShell(days = 3)
        // The system prompt may stop the host activity, so poll plain state here instead of Compose.
        val deadline = System.currentTimeMillis() + 15_000
        while (!prefs.permissionAsked && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue("permission is requested exactly when the popup first appears", prefs.permissionAsked)
        Thread.sleep(1500)
        screenshot("01-popup-with-system-permission-prompt")
        // Dismiss the system permission prompt (deny) if it is in front; otherwise leave the popup alone.
        // Allow it through the real system prompt (this is the synthetic .debug package), so the notification can post later.
        val root = instrumentation.uiAutomation.rootInActiveWindow
        val front = root?.packageName?.toString()
        if (root != null && front != null && front != context.packageName) {
            val allow = listOf("允许", "Allow").flatMap { root.findAccessibilityNodeInfosByText(it) }
                .firstOrNull { it.text?.toString() == "允许" || it.text?.toString() == "Allow" }
            if (allow != null) allow.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) else shell("input keyevent KEYCODE_BACK")
            Thread.sleep(2500)
        }
        compose.waitUntil(15_000) { runCatching { compose.onAllNodesWithTag("renewal-popup").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        compose.onNodeWithTag("renewal-popup-body").assertTextContains("剩余 3 天", substring = true)
        screenshot("02-popup-after-permission-prompt")
        compose.onNodeWithTag("renewal-popup-close").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("renewal-banner").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("renewal-popup").assertDoesNotExist()
        Thread.sleep(800) // let the dialog's fade-out finish before the evidence shot
        screenshot("03-top-gradient-banner")
        assertEquals(DayStage.PopupDismissed, prefs.dayState.stage)
        compose.onNodeWithTag("renewal-banner-close").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("renewal-banner").fetchSemanticsNodes().isEmpty() }
        screenshot("04-banner-dismissed-for-today")
        assertEquals(DayStage.BannerDismissed, prefs.dayState.stage)
        // The permission question is never asked a second time.
        assertFalse(RenewalReminderCoordinator(prefs, alarms).takePermissionRequest())
    }

    @Test fun dismissedForTheDayStaysDismissedOnTheNextLaunchOfTheSameDay() {
        prefs.permissionAsked = true
        prefs.dayState = DayState(java.time.LocalDate.now(ZoneId.systemDefault()), DayStage.BannerDismissed)
        launchShell(days = 3)
        compose.waitForIdle()
        compose.onNodeWithTag("renewal-popup").assertDoesNotExist()
        compose.onNodeWithTag("renewal-banner").assertDoesNotExist()
        screenshot("05-same-day-relaunch-stays-dismissed")
    }

    @Test fun topBannerOnTheConnectPageAfterThePopupWasDismissed() {
        prefs.permissionAsked = true
        prefs.dayState = DayState(java.time.LocalDate.now(ZoneId.systemDefault()), DayStage.PopupDismissed)
        launchShell(days = 3)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("renewal-banner").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("renewal-banner-open").assertContentDescriptionContains("打开套餐页", substring = true)
        compose.onNodeWithTag("renewal-banner-close").assertContentDescriptionContains("今天不再显示", substring = true)
        Thread.sleep(500)
        screenshot("03b-top-gradient-banner-clean")
    }

    @Test fun bannerLightFlowsWhenMotionIsOn() {
        prefs.permissionAsked = true
        prefs.dayState = DayState(java.time.LocalDate.now(ZoneId.systemDefault()), DayStage.PopupDismissed)
        launchShell(days = 3, autoAdvance = false, animatorScale = 1f)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("renewal-banner").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(200); val first = rootCrop(220)
        compose.mainClock.advanceTimeBy(1000); val second = rootCrop(220)
        save(first, "06-banner-motion-on-t0"); save(second, "06-banner-motion-on-t1")
        assertTrue("banner gradient should move with animations on", differs(first, second))
    }

    @Test fun bannerIsStaticWhenAnimationsAreRemoved() {
        prefs.permissionAsked = true
        prefs.dayState = DayState(java.time.LocalDate.now(ZoneId.systemDefault()), DayStage.PopupDismissed)
        launchShell(days = 3, autoAdvance = false, animatorScale = 0f)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("renewal-banner").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(200); val first = rootCrop(220)
        compose.mainClock.advanceTimeBy(1000); val second = rootCrop(220)
        save(first, "07-banner-reduce-motion-t0"); save(second, "07-banner-reduce-motion-t1")
        assertFalse("banner must be static when animations are removed", differs(first, second))
    }

    @Test fun tappingTheBannerOrPopupRenewButtonOpensThePlansPage() {
        prefs.permissionAsked = true
        prefs.dayState = DayState(java.time.LocalDate.now(ZoneId.systemDefault()), DayStage.PopupDismissed)
        launchShell(days = 3)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("renewal-banner-open").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("renewal-banner-open").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("page-Plans").fetchSemanticsNodes().isNotEmpty() }
        screenshot("08-banner-click-opens-plans")
    }

    @Test fun anOpenPlansRequestFromTheNotificationSelectsThePlansPage() {
        compose.setContent {
            val scope = rememberCoroutineScope()
            var request by remember { mutableStateOf(0) }
            GccTheme {
                AppShell(remember { expiring(scope, 60) }, autoLoadCommerce = false, renewalReminders = false, openPlansRequest = request)
                androidx.compose.runtime.LaunchedEffect(Unit) { request = 1 }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("page-Plans").fetchSemanticsNodes().isNotEmpty() }
    }
}

/** Alarm + notification side, on the real AlarmManager / NotificationManager of the .debug package. */
class RenewalNotificationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = SharedRenewalPrefs(context)
    private val alarms = AndroidRenewalAlarms(context)
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString() }
    }
    private fun evidence(name: String, text: String) {
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "ui-evidence/pr55")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, name).writeText(text)
    }
    private fun ourAlarmLines(): List<String> = shell("dumpsys alarm").lines().filter {
        it.contains(context.packageName) && (it.contains("RENEWAL_ALARM") || it.contains("RenewalReminderReceiver"))
    }
    @Before fun before() {
        // Granted earlier through the real system prompt (popup test); adb cannot grant it on this phone.
        assumeTrue("notification permission not granted yet (run the popup test first)",
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        prefs.clear(); alarms.cancel(); alarms.dismissPosted()
    }
    @After fun after() { prefs.clear(); alarms.cancel(); alarms.dismissPosted() }

    private fun available(now: Instant, days: Long) = Resource.Available("resource_" + "a".repeat(64), "synthetic",
        now.plusSeconds(days * 86400), false, Usage(100L shl 30, 20L shl 30, 80L shl 30, "measured", now), 30, 100.0)

    @Test fun dailyTwentyHundredBeijingAlarmIsScheduledPostedOnceAndClearedOnSignOut() {
        val now = Instant.now()
        val coordinator = RenewalReminderCoordinator(prefs, alarms)
        coordinator.onResource(available(now, 3), now)
        val next = requireNotNull(nextNotificationTime(requireNotNull(prefs.validUntil), now))
        val beijing = next.atZone(ZoneId.of("Asia/Shanghai"))
        assertEquals(20, beijing.hour); assertEquals(0, beijing.minute)
        Thread.sleep(500)
        val armed = ourAlarmLines()
        evidence("alarm-armed.txt", "expected next fire (Beijing): $beijing\nutc: $next\n\n" + armed.joinToString("\n") +
            "\n\n--- full dumpsys alarm lines mentioning package ---\n" + shell("dumpsys alarm").lines().filter { it.contains(context.packageName) }.joinToString("\n"))
        assertTrue("alarm for the .debug package must be pending", armed.isNotEmpty())

        // The alarm fires (receiver invoked directly with the real intent): exactly one notification, once per Beijing day.
        val receiver = RenewalReminderReceiver()
        val intent = Intent(context, RenewalReminderReceiver::class.java).setAction(AndroidRenewalAlarms.ACTION_ALARM)
        val before = prefs.lastNotifiedDay
        assertNull(before)
        receiver.onReceive(context, intent)
        val posted = manager.activeNotifications.filter { it.id == RenewalSpec.NOTIFICATION_ID }
        assertEquals(1, posted.size)
        val notification = posted.single().notification
        assertEquals(NOTIFICATION_TITLE, notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        assertNotNull("tap target (open plans) must be set", notification.contentIntent)
        assertNotNull(prefs.lastNotifiedDay)
        evidence("notification-posted.txt", "title=${notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)}\n" +
            "text=${notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)}\nlastNotifiedDay=${prefs.lastNotifiedDay}\n\n" +
            shell("dumpsys notification --noredact").lines().filter { it.contains(context.packageName) || it.contains("contentIntent") }.joinToString("\n"))
        // Second alarm in the same Beijing day: no duplicate.
        receiver.onReceive(context, intent)
        assertEquals(1, manager.activeNotifications.count { it.id == RenewalSpec.NOTIFICATION_ID })

        // Rebooting / updating the app re-arms from the stored expiry.
        alarms.cancel(); assertTrue(ourAlarmLines().isEmpty())
        coordinator.onRestore(now); Thread.sleep(500)
        assertTrue(ourAlarmLines().isNotEmpty())

        // Sign-out or 退出登录 removes expiry, alarm and the posted notification.
        coordinator.onSignedOut(); Thread.sleep(500)
        assertTrue(ourAlarmLines().isEmpty())
        assertEquals(0, manager.activeNotifications.count { it.id == RenewalSpec.NOTIFICATION_ID })
        assertNull(prefs.validUntil)
        evidence("alarm-cleared-after-signout.txt", ourAlarmLines().joinToString("\n").ifEmpty { "(no pending alarm for the .debug package)" })
    }

    @Test fun outsideTheSevenDayWindowTheFirstAlarmWaitsForTheWindowAndNothingIsPosted() {
        val now = Instant.now()
        val coordinator = RenewalReminderCoordinator(prefs, alarms)
        coordinator.onResource(available(now, 20), now)
        val validUntil = requireNotNull(prefs.validUntil)
        val next = requireNotNull(nextNotificationTime(validUntil, now))
        assertTrue(next >= validUntil.minusSeconds(7 * 86400))
        RenewalReminderReceiver().onReceive(context, Intent(context, RenewalReminderReceiver::class.java).setAction(AndroidRenewalAlarms.ACTION_ALARM))
        assertEquals(0, manager.activeNotifications.count { it.id == RenewalSpec.NOTIFICATION_ID })
        evidence("alarm-20-days-out.txt", "validUntil=$validUntil\nfirst alarm=${next.atZone(ZoneId.of("Asia/Shanghai"))} (Beijing)\n" + ourAlarmLines().joinToString("\n"))
    }
}
