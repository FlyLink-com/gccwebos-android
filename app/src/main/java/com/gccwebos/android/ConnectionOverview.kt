package com.gccwebos.android

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gccwebos.android.design.Brand
import com.gccwebos.android.design.SupportingText
import com.gccwebos.android.design.remainingValidity
import com.gccwebos.android.portal.AccountController
import com.gccwebos.android.portal.LocalSubscriptionStatus
import com.gccwebos.android.portal.LocalSubscriptionSnapshot
import java.time.Instant
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

internal enum class ConnectionVisualTone { Neutral, Available, Expired }
internal data class ConnectionOverviewPresentation(val status: LocalSubscriptionStatus, val title: String,
    val tone: ConnectionVisualTone, val countdown: String?, val usageText: String = "暂不可用")

/** An account overview is deliberately absent: it cannot prove usage belongs to this local capability. */
internal fun connectionOverviewPresentation(snapshot: LocalSubscriptionSnapshot, now: Instant): ConnectionOverviewPresentation {
    val status = if (snapshot.status == LocalSubscriptionStatus.READY && snapshot.hasProfile && snapshot.hasCapability &&
        snapshot.validUntil?.let { it <= now } == true)
        LocalSubscriptionStatus.EXPIRED else snapshot.status
    val confirmedReady = status == LocalSubscriptionStatus.READY && snapshot.hasProfile && snapshot.hasCapability &&
        snapshot.validUntil?.let { it > now } == true
    val tone = when {
        status == LocalSubscriptionStatus.EXPIRED -> ConnectionVisualTone.Expired
        confirmedReady -> ConnectionVisualTone.Available
        else -> ConnectionVisualTone.Neutral
    }
    val title = when (status) {
        LocalSubscriptionStatus.MISSING -> "暂无订阅"
        LocalSubscriptionStatus.READY -> if (confirmedReady) "订阅可用" else "订阅待确认"
        LocalSubscriptionStatus.EXPIRED -> "订阅已过期"
        LocalSubscriptionStatus.RETRY_REQUIRED -> "读取失败"
        LocalSubscriptionStatus.INVALID_LINK -> "订阅资源不可用"
        LocalSubscriptionStatus.UNSUPPORTED -> "配置暂不支持"
        LocalSubscriptionStatus.STORAGE_ERROR -> "本地存储异常"
    }
    val countdown = when {
        status == LocalSubscriptionStatus.EXPIRED -> "已到期"
        confirmedReady -> snapshot.validUntil?.let { remainingValidity(it, now) }
        else -> null
    }
    return ConnectionOverviewPresentation(status, title, tone, countdown)
}

/** Presentation of the saved capability only. This component never starts a VPN or reads usage. */
@Composable
internal fun ConnectionOverview(account: AccountController, openAccount: () -> Unit,
    openPlans: () -> Unit, openNodes: () -> Unit) {
    val snapshot = account.localSubscription
    val now by produceState(Instant.now(), snapshot.validUntil) {
        while (true) { value = Instant.now(); delay(1_000) }
    }
    val presentation = connectionOverviewPresentation(snapshot, now)
    val accent = when (presentation.tone) {
        ConnectionVisualTone.Expired -> Color(0xFFFF658E)
        ConnectionVisualTone.Available -> Brand.Cyan
        ConnectionVisualTone.Neutral -> Brand.Muted
    }
    val motionAllowed = rememberSystemMotionAllowed()
    val phase = if (motionAllowed && presentation.tone == ConnectionVisualTone.Available) {
        val transition = rememberInfiniteTransition(label = "subscription-water")
        val progress by transition.animateFloat(0f, 1f,
            animationSpec = infiniteRepeatable(tween(4_000, easing = LinearEasing)), label = "water-phase")
        progress
    } else 0f
    val operable = !account.busy && !account.restoring && !account.foregroundCheckPending && !account.clearing
    Column(Modifier.fillMaxWidth().testTag("connection-overview"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 350.dp).testTag("subscription-waterdrop"),
            contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                val bounds = Rect(size.width * .035f, 8.dp.toPx(), size.width * .965f, size.height - 8.dp.toPx())
                val drop = Path().apply { addOval(bounds) }
                clipPath(drop) {
                    drawRect(Brush.verticalGradient(listOf(accent.copy(alpha = .04f), accent.copy(alpha = .22f))))
                    for (layer in 0..2) {
                        val water = Path()
                        val level = size.height * (.48f + layer * .07f)
                        water.moveTo(0f, level)
                        for (step in 0..60) {
                            val x = size.width * step / 60f
                            val y = level + sin((step / 60f * 2f + phase + layer * .2f) * PI * 2).toFloat() * size.height * .016f
                            water.lineTo(x, y)
                        }
                        water.lineTo(size.width, size.height); water.lineTo(0f, size.height); water.close()
                        drawPath(water, accent.copy(alpha = .07f + layer * .035f))
                    }
                }
                drawOval(accent.copy(alpha = .45f), Offset(bounds.left, bounds.top), Size(bounds.width, bounds.height),
                    style = Stroke(1.dp.toPx()))
            }
            Column(Modifier.padding(horizontal = 42.dp, vertical = 55.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(presentation.title, color = accent, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("local-subscription-title"))
                SupportingText("剩余时间")
                Text(presentation.countdown?.removePrefix("剩余 ") ?: "暂不可用", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("subscription-validity-countdown"))
                SupportingText("剩余流量")
                Text(presentation.usageText, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("subscription-usage-unavailable"))
            }
        }
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text("未连接", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("connection-status"))
            SupportingText(" · ")
            SupportingText("连接暂不可用", Modifier.testTag("connection-unavailable"))
        }
        Button(onClick = {}, enabled = false, shape = RoundedCornerShape(50),
            modifier = Modifier.heightIn(min = 56.dp).fillMaxWidth().background(
                Brush.horizontalGradient(listOf(Brand.ActionStart.copy(alpha = .4f), Brand.ActionEnd.copy(alpha = .45f))),
                RoundedCornerShape(50)).testTag("vpn-connect"),
            colors = ButtonDefaults.buttonColors(disabledContainerColor = Color.Transparent, disabledContentColor = Brand.Text)) {
            Text("连接", style = MaterialTheme.typography.titleLarge)
        }
        val nodes: @Composable (Modifier) -> Unit = { modifier ->
            TextButton(onClick = openNodes, modifier = modifier.heightIn(min = 48.dp).testTag("connection-nodes")) { Text("节点 ›") }
        }
        val subscriptionAction: @Composable (Modifier) -> Unit = { modifier ->
            val action: Triple<String, String, () -> Unit> = when {
                presentation.status == LocalSubscriptionStatus.EXPIRED -> Triple("续费", "expired-subscription-renew", openPlans)
                presentation.status == LocalSubscriptionStatus.INVALID_LINK -> Triple("登录恢复订阅", "open-account", openAccount)
                snapshot.hasCapability -> Triple("刷新订阅", "local-subscription-refresh", account::refreshSubscription)
                account.authenticated -> Triple("领取／同步订阅", "local-subscription-sync", account::refresh)
                else -> Triple("登录领取订阅", "open-account", openAccount)
            }
            TextButton(onClick = action.third, enabled = operable,
                modifier = modifier.heightIn(min = 48.dp).testTag(action.second)) { Text(action.first) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 300.dp || LocalDensity.current.fontScale > 1.3f) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    nodes(Modifier.fillMaxWidth()); subscriptionAction(Modifier.fillMaxWidth())
                }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                nodes(Modifier.weight(1f)); subscriptionAction(Modifier.weight(1f))
            }
        }
    }
}

/** Read-only system preference. A zero or unreadable animation scale removes the repeating animation. */
@Composable
private fun rememberSystemMotionAllowed(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun readAllowed(): Boolean = try {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f) > 0f
    } catch (_: SecurityException) { false }
    var allowed by remember(resolver) { mutableStateOf(readAllowed()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { allowed = readAllowed() }
        }
        var registered = false
        try {
            resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
            registered = true
        }
        catch (_: SecurityException) { allowed = false }
        onDispose { if (registered) resolver.unregisterContentObserver(observer) }
    }
    return allowed
}
