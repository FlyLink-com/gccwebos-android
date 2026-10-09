package com.gccwebos.android

import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gccwebos.android.design.AccountUsagePresentation
import com.gccwebos.android.design.AccountUsageState
import com.gccwebos.android.design.Brand
import com.gccwebos.android.design.SubscriptionUsagePresentation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import java.time.Instant

/** Connection-page layout only. The host still owns account verification and every explicit action. */
@Composable
internal fun ConnectionUsageCard(
    presentation: AccountUsagePresentation,
    authenticated: Boolean,
    onRefresh: () -> Unit,
    loading: Boolean,
    error: String?,
    refreshEnabled: Boolean,
    primaryLabel: String,
    primaryEnabled: Boolean,
    primaryHint: String?,
    showPrimary: Boolean,
    onPrimaryAction: () -> Unit,
    modifier: Modifier = Modifier,
    /** The selected configuration's own reported usage. The host decides when it replaces account values. */
    subscriptionUsage: SubscriptionUsagePresentation? = null,
    /** Display-only: an established tunnel enables the breathing glow. Never read back as connection state. */
    connected: Boolean = false,
    /** Clock for the expiring-soon emphasis; null disables only that emphasis. */
    now: Instant? = null,
) {
    val refreshAllowed = authenticated && !loading && refreshEnabled
    val motion = rememberUsageMotion(connected)
    Column(modifier.fillMaxWidth().testTag("account-usage-card")) {
        if (subscriptionUsage != null) ConnectionSubscriptionUsage(subscriptionUsage, motion, now)
        else if (!authenticated) ConnectionRemaining("暂未获取", motion) else when (presentation.state) {
            AccountUsageState.Unknown -> ConnectionRemaining("暂未获取", motion)
            AccountUsageState.NoActivePlan -> if (error != null) ConnectionRemaining("暂未获取", motion) else
                Text("暂无生效套餐", modifier = Modifier.testTag("account-usage-no-plan"))
            AccountUsageState.Reconciliation -> Text("套餐正在核对", color = Brand.Muted,
                modifier = Modifier.testTag("account-usage-reconciliation"))
            AccountUsageState.Available -> {
                ConnectionRemaining(presentation.remainingText, motion)
                ConnectionUsageAmounts(presentation, motion, now)
                ConnectionUsageDates(presentation)
                if (presentation.serviceExpired) Text("已到期", color = Brand.Amber,
                    modifier = Modifier.padding(top = 10.dp).testTag("account-usage-service-expired"))
                presentation.collectionText?.let {
                    Text(it, color = Brand.Muted, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 10.dp).testTag("account-usage-collection"))
                }
            }
        }
        if (showPrimary) {
            val retry = authenticated && (error != null || presentation.state == AccountUsageState.Unknown ||
                presentation.state == AccountUsageState.Reconciliation)
            val label = if (retry || primaryLabel in setOf("重试", "准备中")) "一键连接" else primaryLabel
            val enabled = if (retry) refreshAllowed else primaryEnabled && !loading
            ConnectionActionButton(label, { if (enabled) { if (retry) onRefresh() else onPrimaryAction() } },
                Modifier.padding(top = 12.dp).testTag("vpn-connect"), enabled)
        }
    }
}

/** Same layout as account usage, but always labelled as subscription-provided. Primary action logic is unchanged. */
@Composable
private fun ConnectionSubscriptionUsage(usage: SubscriptionUsagePresentation, motion: UsageMotion, now: Instant?) {
    val display = usage.asCardPresentation()
    ConnectionRemaining(display.remainingText, motion)
    ConnectionUsageAmounts(display, motion, now)
    ConnectionUsageDates(display)
    if (display.serviceExpired) Text("已到期", color = Brand.Amber,
        modifier = Modifier.padding(top = 10.dp).testTag("account-usage-service-expired"))
    Text(usage.sourceText, color = Brand.Muted, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 10.dp).testTag("subscription-usage-source"))
}

@Composable
private fun ConnectionRemaining(value: String, motion: UsageMotion) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("剩余流量", color = Brand.Muted, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val measurer = rememberTextMeasurer()
            val style = MaterialTheme.typography.headlineLarge.copy(color = Brand.Cyan)
            val sizes = if (maxWidth < 300.dp) listOf(45, 37, 29, 24) else listOf(49, 45, 37, 29, 24)
            val width = with(density) { maxWidth.roundToPx() }
            RollingUsageValue(value, motion, Modifier.fillMaxWidth()) { text, textModifier, ghost ->
                val numeric = text.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})? GB"))
                fun amount(size: Int) = buildAnnotatedString {
                    if (numeric) {
                        withStyle(SpanStyle(fontSize = size.sp, fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-1.6).sp)) { append(text.removeSuffix(" GB")) }
                        withStyle(SpanStyle(fontSize = 19.sp, fontWeight = FontWeight.Medium)) { append(" GB") }
                    } else withStyle(SpanStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold)) { append(text) }
                }
                val size = remember(text, width, style, measurer) {
                    sizes.firstOrNull { measurer.measure(amount(it), style, softWrap = false).size.width <= width }
                        ?: sizes.last()
                }
                // One text node retains the full value for readout/tests. No ellipsis or fixed height at large font scales.
                // The outgoing odometer copy is decorative only and carries no semantics.
                Text(amount(size), style = style, lineHeight = if (numeric) (size * 1.15f).sp else 40.sp,
                    softWrap = !ghost, modifier = if (ghost) textModifier
                    else textModifier.fillMaxWidth().testTag("account-usage-remaining"))
            }
        }
    }
}

@Composable
private fun ConnectionUsageAmounts(presentation: AccountUsagePresentation, motion: UsageMotion, now: Instant?) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        presentation.periodText?.let {
            Text(it, color = Brand.Muted, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 6.dp).testTag("account-usage-period"))
        }
        val used: @Composable () -> Unit = {
            RollingUsageValue(presentation.usedText, motion) { text, textModifier, ghost ->
                ConnectionUsageAmount("已用", text, "account-usage-used".takeUnless { ghost }, textModifier)
            }
        }
        val total: @Composable () -> Unit = { ConnectionUsageAmount("总量", presentation.totalText, "account-usage-total") }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.5f) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { used(); total() }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { used() }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { total() }
            }
        }
        presentation.progress?.let { progress ->
            val severity = usageSeverity(progress, presentation.expiresAt, presentation.serviceExpired, now)
            ConnectionUsageProgress(progress, severity, motion)
        }
    }
}

@Composable
private fun ConnectionUsageAmount(label: String, value: String, tag: String?, modifier: Modifier = Modifier) {
    Text(buildAnnotatedString {
        append("$label ")
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(value) }
    }, color = Brand.Muted, fontSize = 12.sp, lineHeight = 18.sp, softWrap = tag != null,
        modifier = if (tag != null) modifier.testTag(tag) else modifier)
}

@Composable
private fun ConnectionUsageDates(presentation: AccountUsagePresentation) {
    if (presentation.cycleText == null && presentation.expiresAtText == null) return
    var showDetails by remember { mutableStateOf(false) }
    // The detail tap already exists; it only gains a light press scale and one haptic tick.
    val haptic = LocalHapticFeedback.current
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val motion = rememberUsageMotion(connected = false)
    val pressScale by animateFloatAsState(if (pressed && motion.enabled) .98f else 1f,
        if (motion.enabled) spring(dampingRatio = .6f, stiffness = Spring.StiffnessMedium) else snap(),
        label = "usage-detail-press")
    val openDetails = { haptic.performHapticFeedback(HapticFeedbackType.ContextClick); showDetails = true }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).graphicsLayer { scaleX = pressScale; scaleY = pressScale }) {
        HorizontalDivider(color = Brand.RowDivider)
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            presentation.cycleText?.let {
                ConnectionDateRow("统计周期", connectionCycleSummary(it), it, "account-usage-cycle")
            }
            presentation.expiresAtText?.let {
                ConnectionDateRow("套餐到期", connectionDateSummary(it), it, "account-usage-expiry", press, openDetails)
            }
        }
        if (presentation.expiresAtText == null) {
            TextButton(openDetails, interactionSource = press, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)
                .testTag("account-usage-time-details")) { Text("查看完整时间 ›", fontSize = 11.sp) }
        }
    }
    if (showDetails) AlertDialog(onDismissRequest = { showDetails = false },
        title = { Text("套餐时间") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                presentation.cycleText?.let { Text("统计周期\n$it") }
                presentation.expiresAtText?.let { Text("套餐到期\n$it") }
            }
        }, confirmButton = { TextButton({ showDetails = false }) { Text("知道了") } })
}

@Composable
private fun ConnectionDateRow(label: String, summary: String, full: String, tag: String,
                              interactionSource: MutableInteractionSource? = null, onDetails: (() -> Unit)? = null) {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = "$label $full" }) {
        val labelContent: @Composable () -> Unit = { Text(label, color = Brand.Muted, fontSize = 11.sp, lineHeight = 16.sp) }
        val valueContent: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                Text(summary, color = Brand.Muted, fontSize = 11.sp, lineHeight = 16.sp,
                    modifier = Modifier.weight(1f, fill = false))
                onDetails?.let {
                    // A full touch target preserves the preview's compact chevron without a tiny interactive area.
                    IconButton(it, interactionSource = interactionSource, modifier = Modifier.size(48.dp).testTag("account-usage-time-details")
                        .semantics { contentDescription = "查看完整时间" }) { Text("›", fontSize = 18.sp) }
                }
            }
        }
        if (maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.5f) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) { labelContent(); valueContent() }
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            labelContent()
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { valueContent() }
        }
    }
}

@Composable
internal fun ConnectionActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                                    enabled: Boolean = true, connected: Boolean = false) {
    val shape = RoundedCornerShape(18.dp)
    val color = if (enabled) Brand.Text else Brand.DisabledText
    Button(onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape)
            .background(Brush.horizontalGradient(when {
                connected && enabled -> listOf(Color(0xFF087F69), Color(0xFF116747))
                connected -> listOf(Color(0xFF164B40), Color(0xFF183E34))
                enabled -> listOf(Brand.ActionStart, Brand.ActionMiddle, Brand.Blue)
                else -> listOf(Brand.Disabled, Brand.Disabled)
            })),
        shape = shape, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent,
            contentColor = color, disabledContainerColor = Color.Transparent, disabledContentColor = color),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
        Canvas(Modifier.size(20.dp)) {
            val stroke = 1.6.dp.toPx()
            drawArc(color, -48f, 276f, false, topLeft = Offset(size.width * .16f, size.height * .16f),
                size = Size(size.width * .68f, size.height * .68f), style = Stroke(stroke, cap = StrokeCap.Round))
            drawLine(color, Offset(size.width / 2, size.height * .07f), Offset(size.width / 2, size.height * .48f),
                strokeWidth = stroke, cap = StrokeCap.Round)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Lifecycle/settings gates for the card's motion; read in composition, never per frame. */
internal data class UsageMotion(val enabled: Boolean, val breathing: Boolean, val connected: Boolean)

@Composable
private fun rememberUsageMotion(connected: Boolean): UsageMotion {
    val context = LocalContext.current
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = state.isAtLeast(Lifecycle.State.RESUMED)
    // Re-read on each lifecycle transition, so toggling "remove animations" applies when the user returns.
    val scale = remember(state, context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }
    val enabled = usageMotionEnabled(scale)
    return UsageMotion(enabled, usageBreathingActive(connected, resumed, enabled), connected)
}

private val UsageEaseOutExpo = CubicBezierEasing(.16f, 1f, .3f, 1f)
private val UsageWarningColor = Color(0xFFFF9F43)
private val UsageCriticalColor = Color(0xFFFF5D5D)

private fun UsageSeverity.accent(): Color = when (this) {
    UsageSeverity.Normal -> Brand.Cyan
    UsageSeverity.Warning -> UsageWarningColor
    UsageSeverity.Critical -> UsageCriticalColor
}

/**
 * Confirmed usage only (the caller has a real progress value). Sweeps from 0 on first show, then follows
 * changes; the end glow breathes only while connected, resumed and motion is allowed. Every per-frame value is
 * read in the draw phase, so animation frames never recompose the card.
 */
@Composable
private fun ConnectionUsageProgress(progress: Float, severity: UsageSeverity, motion: UsageMotion) {
    val target = progress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    val shown = remember { Animatable(if (motion.enabled) 0f else target) }
    var swept by remember { mutableStateOf(false) }
    LaunchedEffect(target, motion.enabled) {
        when {
            !motion.enabled -> shown.snapTo(target)
            !swept -> { shown.snapTo(0f); shown.animateTo(target, tween(900, easing = UsageEaseOutExpo)) }
            else -> shown.animateTo(target, spring(dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow))
        }
        swept = true
    }
    val accent by animateColorAsState(severity.accent(),
        if (motion.enabled) tween(700, easing = FastOutSlowInEasing) else snap(), label = "usage-severity")
    val breath = remember { Animatable(0f) }
    LaunchedEffect(motion.breathing) {
        if (!motion.breathing) { breath.snapTo(0f); return@LaunchedEffect }
        breath.snapTo(.14f)
        // ~2.6 s per cycle; Animatable runs it as an infinite animation (paused by tests' infinite policy).
        breath.animateTo(.42f, infiniteRepeatable(tween(1_300, easing = FastOutSlowInEasing), RepeatMode.Reverse))
    }
    // Static (non-animated) emphasis for warnings, or for an established tunnel when motion is off/paused.
    val resting = when {
        severity != UsageSeverity.Normal -> .24f
        motion.connected -> .2f
        else -> 0f
    }
    Box(Modifier.padding(top = 8.dp).fillMaxWidth().drawBehind {
        val fraction = shown.value
        val alpha = maxOf(breath.value, resting)
        if (alpha <= 0f || fraction <= 0f) return@drawBehind
        val radius = 11.dp.toPx()
        val center = Offset(size.width * fraction, size.height / 2)
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = alpha), Color.Transparent), center, radius),
            radius, center)
    }) {
        LinearProgressIndicator(progress = { shown.value }, color = accent, trackColor = Brand.SurfaceRaised,
            modifier = Modifier.fillMaxWidth().height(6.dp)
                .clip(RoundedCornerShape(3.dp)).testTag("account-usage-progress"))
    }
}

/**
 * Odometer-style roll between two confirmed GB values. The real text node always carries the current value
 * immediately (semantics/tests unchanged); only a decorative outgoing copy moves. Unknown values never roll,
 * and rolls are throttled so rapid updates snap instead of flickering.
 */
@Composable
private fun RollingUsageValue(value: String, motion: UsageMotion, modifier: Modifier = Modifier,
                              content: @Composable (text: String, modifier: Modifier, ghost: Boolean) -> Unit) {
    var settled by remember { mutableStateOf(value) }
    var from by remember { mutableStateOf<String?>(null) }
    var direction by remember { mutableIntStateOf(0) }
    var lastRollAt by remember { mutableStateOf<Long?>(null) }
    val roll = remember { Animatable(1f) }
    val pending = if (motion.enabled && value != settled) usageRollDirection(settled, value) else 0
    LaunchedEffect(value, motion.enabled) {
        val old = settled
        val dir = if (motion.enabled) usageRollDirection(old, value) else 0
        val now = SystemClock.uptimeMillis()
        settled = value
        if (dir != 0 && usageRollAllowed(lastRollAt, now)) {
            lastRollAt = now; from = old; direction = dir
            roll.snapTo(0f)
            try { roll.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) } finally { from = null }
        } else { from = null; roll.snapTo(1f) }
    }
    val ghostText = if (pending != 0) settled else from
    val dir = if (pending != 0) pending else direction
    val rolling = ghostText != null && ghostText != value
    Box(modifier.then(if (rolling) Modifier.drawWithContent {
        val pad = 4.dp.toPx()
        clipRect(left = -size.width, top = -pad, right = size.width * 2, bottom = size.height + pad) {
            this@drawWithContent.drawContent()
        }
    } else Modifier)) {
        content(value, Modifier.graphicsLayer {
            val p = if (pending != 0) 0f else roll.value
            translationY = (1f - p) * size.height * dir
            alpha = p
        }, false)
        if (rolling) content(requireNotNull(ghostText), Modifier.matchParentSize()
            .wrapContentSize(Alignment.TopStart, unbounded = true).clearAndSetSemantics { }.graphicsLayer {
                val p = if (pending != 0) 0f else roll.value
                translationY = -p * size.height * dir
                alpha = 1f - p
            }, true)
    }
}
