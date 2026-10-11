package com.gccwebos.android.renewal

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.gccwebos.android.design.Brand
import com.gccwebos.android.design.rememberUiMotionEnabled
import com.gccwebos.android.design.shimmerActive
import com.gccwebos.android.portal.Resource
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Compose-facing holder for the reminder. All decisions are in [RenewalReminderLogic]. */
@Stable
internal class RenewalReminderState(
    private val coordinator: RenewalReminderCoordinator,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    var reminder by mutableStateOf<RenewalReminder?>(null)
        private set
    private var dayState by mutableStateOf(coordinator.dayState())
    private var today by mutableStateOf(localDay(clock(), zone()))
    private var lastResource: Resource? = null

    val surface: ReminderSurface get() = surfaceFor(reminder, dayState, today)
    val zoneId: ZoneId get() = zone()

    /** Feed the latest verified account resource (null = not known yet / not signed in). */
    fun update(resource: Resource?) {
        val now = clock()
        coordinator.onResource(resource, now)
        if (resource is Resource.Available || resource == Resource.None) lastResource = resource
        reminder = renewalReminder(resource ?: lastResource, now)
        refreshDay()
    }

    fun refreshDay() {
        val now = clock()
        today = localDay(now, zone())
        dayState = coordinator.dayState()
        // The day count changes with time; recompute from the last server-reported resource.
        reminder = renewalReminder(lastResource, now)
    }

    fun signedOut() {
        coordinator.onSignedOut()
        lastResource = null
        reminder = null
        dayState = coordinator.dayState()
    }

    fun dismissPopup() { coordinator.dismissPopup(clock(), zone()); dayState = coordinator.dayState() }
    fun dismissBanner() { coordinator.dismissBanner(clock(), zone()); dayState = coordinator.dayState() }
    fun takePermissionRequest(): Boolean = coordinator.takePermissionRequest()
    fun millisUntilNextDay(): Long = millisUntilNextDay(clock(), zone())
}

@Composable
internal fun rememberRenewalReminderState(): RenewalReminderState {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        RenewalReminderState(RenewalReminderCoordinator(SharedRenewalPrefs(context), AndroidRenewalAlarms(context)))
    }
}

/**
 * Drives [state] from the account. [resource] must already be the verified ACCOUNT resource (null while
 * unknown); [signedOut] is true only once sign-in/restore work has settled and no account is present.
 */
@Composable
internal fun RenewalReminderEffects(state: RenewalReminderState, resource: Resource?, signedOut: Boolean) {
    LaunchedEffect(resource, signedOut) {
        if (signedOut) state.signedOut() else state.update(resource)
    }
    // A mounted screen re-evaluates "today" at each local midnight.
    LaunchedEffect(state) {
        while (true) {
            delay(state.millisUntilNextDay())
            state.refreshDay()
        }
    }
}

/** The dismissible popup. The notification permission is requested here, once, the first time it is shown. */
@Composable
internal fun RenewalPopup(state: RenewalReminderState, onRenew: () -> Unit) {
    val reminder = state.reminder ?: return
    if (state.surface != ReminderSurface.Popup) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            state.takePermissionRequest()) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val notice = reminder.kind == ReminderKind.Expiring && notificationsAllowed(context)
    AlertDialog(
        onDismissRequest = state::dismissPopup,
        modifier = Modifier.testTag("renewal-popup"),
        containerColor = Brand.SurfaceRaised,
        shape = Brand.Shape,
        title = { Text(popupTitle(reminder)) },
        text = {
            Column {
                Text(popupBody(reminder, state.zoneId), modifier = Modifier.testTag("renewal-popup-body"))
                if (notice) Text("每天晚上 8 点（北京时间）会通知提醒续费，续费后自动停止。", fontSize = 12.sp,
                    color = Brand.Muted, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { state.dismissPopup(); onRenew() },
                modifier = Modifier.heightIn(min = 48.dp).testTag("renewal-popup-renew")) { Text("去续费") }
        },
        dismissButton = {
            TextButton(onClick = state::dismissPopup,
                modifier = Modifier.heightIn(min = 48.dp).testTag("renewal-popup-close")) { Text("关闭") }
        },
    )
}

private val BannerColors = listOf(Brand.ActionStart, Brand.ActionMiddle, Brand.ActionEnd, Brand.ActionMiddle, Brand.ActionStart)

/** Thin gradient strip on top of every screen. The light flows unless animations are removed/paused. */
@Composable
internal fun RenewalBanner(state: RenewalReminderState, onOpen: () -> Unit) {
    val reminder = state.reminder ?: return
    if (state.surface != ReminderSurface.Banner) return
    val motion = rememberUiMotionEnabled()
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    val flowing = shimmerActive(motion, resumed)
    val phase = if (flowing) {
        rememberInfiniteTransition(label = "renewal-banner-flow").animateFloat(0f, 1f,
            infiniteRepeatable(tween(BANNER_FLOW_MILLIS, easing = LinearEasing)), label = "renewal-banner-phase")
    } else null
    val text = bannerText(reminder)
    val openLabel = "$text。打开套餐页"
    Row(
        Modifier.fillMaxWidth().testTag("renewal-banner")
            .drawBehind {
                // Mirrored tiling makes a shift of one period seamless; the offset is read at draw time only.
                val width = size.width.coerceAtLeast(1f)
                val shift = (phase?.value ?: 0f) * width * 2f
                drawRect(Brush.horizontalGradient(BannerColors, startX = -shift, endX = width - shift, tileMode = TileMode.Mirror))
            }
            .statusBarsPadding()
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.weight(1f).heightIn(min = 40.dp).clickable(role = Role.Button, onClick = onOpen)
                .semantics { contentDescription = openLabel }.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                .testTag("renewal-banner-open"),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Start)
        }
        Box(
            Modifier.size(width = 48.dp, height = 40.dp).clickable(role = Role.Button, onClick = state::dismissBanner)
                .semantics { contentDescription = "关闭续费提示，今天不再显示" }.testTag("renewal-banner-close"),
            contentAlignment = Alignment.Center,
        ) { Text("×", color = Color.White, fontSize = 20.sp) }
    }
}

internal const val BANNER_FLOW_MILLIS = 4_000

/** Pure helper kept next to the UI so the effect inputs stay in one place (and testable). */
internal fun reminderSignedOut(authenticated: Boolean, startupReady: Boolean, restoring: Boolean, restorationPending: Boolean,
    foregroundCheckPending: Boolean, busy: Boolean, clearing: Boolean, retainingSession: Boolean): Boolean =
    startupReady && !authenticated && !restoring && !restorationPending && !foregroundCheckPending && !busy &&
        !clearing && !retainingSession
