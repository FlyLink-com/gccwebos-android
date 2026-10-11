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
import androidx.compose.foundation.border
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
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.animation.core.keyframes
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.delay
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
import com.gccwebos.android.design.GccBottomSheet
import com.gccwebos.android.design.UiHaptic
import com.gccwebos.android.design.pressFeedback
import com.gccwebos.android.design.rememberPressFeedback
import com.gccwebos.android.design.rememberTapHaptic
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
    /** Verified plan name badge (account plans only); shown on the same row as the expiry date. */
    planText: String? = null,
    activePlan: Boolean = false,
    /** Navigation-only "续费" link to the existing 套餐 tab; null hides it. */
    onRenew: (() -> Unit)? = null,
) {
    // Drawn motion runs only while this card is actually on screen (and resumed, and motion is allowed).
    var shown by remember { mutableStateOf(true) }
    val gate = rememberUsageMotion(connected, shown)
    val shownProgress = when {
        subscriptionUsage != null -> subscriptionUsage.asCardPresentation().progress
        authenticated && presentation.state == AccountUsageState.Available -> presentation.progress
        else -> null
    }
    val cardState = usageCardState(shownProgress)
    val pulse = rememberUsagePulse(cardState, gate)
    val motion = remember(gate, cardState, pulse) { gate.copy(cardState = cardState, pulse = pulse) }
    Column(modifier.fillMaxWidth().onVisibilityChanged(minFractionVisible = .05f) { shown = it }
        .testTag("account-usage-card")) {
        if (subscriptionUsage != null) ConnectionSubscriptionUsage(subscriptionUsage, motion, now, planText, activePlan)
        else if (!authenticated) ConnectionRemaining("暂未获取", motion) else when (presentation.state) {
            AccountUsageState.Unknown -> ConnectionRemaining("暂未获取", motion)
            AccountUsageState.NoActivePlan -> if (error != null) ConnectionRemaining("暂未获取", motion) else
                Text("暂无生效套餐", modifier = Modifier.testTag("account-usage-no-plan"))
            AccountUsageState.Reconciliation -> Text("套餐正在核对", color = Brand.Muted,
                modifier = Modifier.testTag("account-usage-reconciliation"))
            AccountUsageState.Available -> {
                ConnectionUsageDates(presentation, planText, activePlan, onRenew)
                ConnectionRemaining(presentation.remainingText, motion, presentation)
                ConnectionUsageAmounts(presentation, motion, now)
                if (presentation.serviceExpired) Text("已到期", color = Brand.Amber,
                    modifier = Modifier.padding(top = 8.dp).testTag("account-usage-service-expired"))
                presentation.collectionText?.let {
                    Text(it, color = Brand.Muted, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp).testTag("account-usage-collection"))
                }
            }
        }
        if (showPrimary) ConnectionSetupButton(presentation, authenticated, onRefresh, loading, error, refreshEnabled,
            primaryLabel, primaryEnabled, onPrimaryAction, Modifier.padding(top = 12.dp))
    }
}

/** The setup/one-tap primary action (moved out so the home Hero card can host it). Logic is unchanged. */
@Composable
internal fun ConnectionSetupButton(
    presentation: AccountUsagePresentation,
    authenticated: Boolean,
    onRefresh: () -> Unit,
    loading: Boolean,
    error: String?,
    refreshEnabled: Boolean,
    primaryLabel: String,
    primaryEnabled: Boolean,
    onPrimaryAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val refreshAllowed = authenticated && !loading && refreshEnabled
    val retry = authenticated && (error != null || presentation.state == AccountUsageState.Unknown ||
        presentation.state == AccountUsageState.Reconciliation)
    val label = if (retry || primaryLabel in setOf("重试", "准备中")) "一键连接" else primaryLabel
    val enabled = if (retry) refreshAllowed else primaryEnabled && !loading
    ConnectionActionButton(label, { if (enabled) { if (retry) onRefresh() else onPrimaryAction() } },
        modifier.testTag("vpn-connect"), enabled)
}

/** Same layout as account usage, but always labelled as subscription-provided. Primary action logic is unchanged. */
@Composable
private fun ConnectionSubscriptionUsage(usage: SubscriptionUsagePresentation, motion: UsageMotion, now: Instant?,
                                        planText: String?, activePlan: Boolean) {
    val display = usage.asCardPresentation()
    ConnectionUsageDates(display, planText, activePlan, null)
    ConnectionRemaining(display.remainingText, motion, display)
    ConnectionUsageAmounts(display, motion, now)
    if (display.serviceExpired) Text("已到期", color = Brand.Amber,
        modifier = Modifier.padding(top = 8.dp).testTag("account-usage-service-expired"))
    // Subdued provenance line: kept (not lost), just quieter than the amounts.
    Text(usage.sourceText, color = Brand.Muted.copy(alpha = .72f), style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = 8.dp).testTag("subscription-usage-source"))
}

@Composable
private fun ConnectionRemaining(value: String, motion: UsageMotion, amounts: AccountUsagePresentation? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("剩余流量", color = Brand.Muted, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f))
            // The used share now sits right under the bar it describes (see ConnectionUsageAmounts), so the
            // headline (remaining) and the bar (used) are no longer read as one measure.
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val measurer = rememberTextMeasurer()
            val low = motion.cardState == UsageCardState.Low
            // Low traffic: the number turns amber (contrast stays far above 4.5:1 on the navy card) and glows slowly.
            val style = MaterialTheme.typography.headlineLarge.copy(color = if (low) UsageWarningColor else Brand.Cyan)
            val lowGlow = if (low) Modifier.drawBehind {
                val alpha = usageLerp(UsagePulseAlphaMin, UsagePulseAlphaMax, motion.pulse())
                val center = Offset(size.width * .18f, size.height / 2)
                val radius = size.height * 1.5f
                drawCircle(Brush.radialGradient(listOf(UsageWarningColor.copy(alpha = alpha * .6f), Color.Transparent), center, radius),
                    radius, center)
            } else Modifier
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
                    else textModifier.then(lowGlow).fillMaxWidth().testTag("account-usage-remaining"))
            }
        }
    }
}

@Composable
private fun ConnectionUsageAmounts(presentation: AccountUsagePresentation, motion: UsageMotion, now: Instant?) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        presentation.progress?.let { progress ->
            val severity = usageSeverity(progress, presentation.expiresAt, presentation.serviceExpired, now)
            ConnectionUsageProgress(progress, severity, motion)
        }
        // The bar fills with what has been used; say so right under it, next to its own number.
        Text(connectionUsedSummary(presentation.usedText, presentation.progress), color = Brand.Muted, fontSize = 12.sp,
            lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 6.dp).testTag("account-usage-used")
                .semantics { contentDescription = connectionUsedDescription(presentation.usedText, presentation.progress) })
        // One quiet meta row under the bar: the period label and the plan total (kept, never dropped).
        val period: @Composable () -> Unit = {
            presentation.periodText?.let {
                Text(it, color = Brand.Muted, fontSize = 11.sp, lineHeight = 16.sp,
                    modifier = Modifier.testTag("account-usage-period"))
            }
        }
        val total: @Composable () -> Unit = { ConnectionUsageAmount("总量", presentation.totalText, "account-usage-total") }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            if (maxWidth < 240.dp || LocalDensity.current.fontScale >= 1.5f) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { period(); total() }
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { period() }
                total()
            }
        }
        presentation.cycleText?.let {
            Text("统计周期 ${connectionCycleSummary(it)}", color = Brand.Muted.copy(alpha = .72f),
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp)
                    .testTag("account-usage-cycle").semantics { contentDescription = "统计周期 $it" })
        }
    }
}

@Composable
private fun ConnectionUsageAmount(label: String, value: String, tag: String?, modifier: Modifier = Modifier) {
    Text(buildAnnotatedString {
        append("$label ")
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(value) }
    }, color = Brand.Muted, fontSize = 11.sp, lineHeight = 16.sp, softWrap = tag != null,
        modifier = if (tag != null) modifier.testTag(tag) else modifier)
}

/** Plan header: name badge, expiry date (tap for full times) and the optional 续费 link, on one row when it fits. */
@Composable
private fun ConnectionUsageDates(presentation: AccountUsagePresentation, planText: String?, activePlan: Boolean,
                                 onRenew: (() -> Unit)?) {
    if (planText == null && onRenew == null && presentation.cycleText == null && presentation.expiresAtText == null) return
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
    val badge: @Composable () -> Unit = { planText?.let { ConnectionPlanBadge(it, activePlan) } }
    val expiry: @Composable () -> Unit = {
        val expiresAt = presentation.expiresAtText
        if (expiresAt != null) ConnectionDateRow(connectionDateSummary(expiresAt), expiresAt, "account-usage-expiry",
            press, openDetails)
        else if (presentation.cycleText != null) TextButton(openDetails, interactionSource = press,
            modifier = Modifier.heightIn(min = 48.dp).testTag("account-usage-time-details")) {
            Text("查看完整时间 ›", fontSize = 11.sp)
        }
    }
    val renew: @Composable () -> Unit = {
        onRenew?.let {
            TextButton(it, contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.heightIn(min = 48.dp).testTag("connection-renew")) {
                Text("续费", color = Brand.Cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 4.dp)
        .graphicsLayer { scaleX = pressScale; scaleY = pressScale }) {
        if (maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.3f) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { badge() }
                    renew()
                }
                expiry()
            }
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.weight(1f)) { badge() }
            expiry()
            renew()
        }
    }
    // Read-only details: a swipe-dismissable sheet instead of a centred dialog; same texts and 知道了 action.
    if (showDetails) GccBottomSheet(onDismissRequest = { showDetails = false }, title = "套餐时间") { close ->
        presentation.cycleText?.let { Text("统计周期\n$it") }
        presentation.expiresAtText?.let { Text("套餐到期\n$it") }
        TextButton(close, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text("知道了") }
    }
}

@Composable
private fun ConnectionPlanBadge(text: String, active: Boolean) {
    Text(text, color = if (active) Brand.Green else Brand.Muted, fontSize = 11.sp, lineHeight = 14.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = .3.sp,
        modifier = Modifier.background(if (active) Brand.Green.copy(alpha = .15f)
            else Brand.SurfaceRaised.copy(alpha = .45f), RoundedCornerShape(8.dp))
            .border(1.dp, if (active) Brand.Green.copy(alpha = .45f) else Brand.RowDivider, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .testTag("account-usage-plan"))
}

/** "2026.10.12 到期" with the existing full-time chevron (48 dp target). The full timestamp stays in the description. */
@Composable
private fun ConnectionDateRow(summary: String, full: String, tag: String,
                              interactionSource: MutableInteractionSource? = null, onDetails: (() -> Unit)? = null) {
    Row(Modifier.testTag(tag).semantics { contentDescription = "套餐到期 $full" },
        verticalAlignment = Alignment.CenterVertically) {
        Text("$summary 到期", color = Brand.Muted, fontSize = 12.sp, lineHeight = 16.sp,
            modifier = Modifier.weight(1f, fill = false))
        onDetails?.let {
            // A full touch target preserves the compact chevron without a tiny interactive area.
            // Labelled so the destination is clear: it opens the full cycle / expiry times.
            TextButton(it, interactionSource = interactionSource, contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.heightIn(min = 48.dp).testTag("account-usage-time-details")
                    .semantics { contentDescription = "查看完整时间" }) { Text("详情 ›", fontSize = 11.sp, color = Brand.Cyan) }
        }
    }
}

@Composable
internal fun ConnectionActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                                    enabled: Boolean = true, connected: Boolean = false) {
    val shape = RoundedCornerShape(18.dp)
    val color = if (enabled) Brand.Text else Brand.DisabledText
    // Press scale/alpha plus one light tick, only from the user's own tap (Button never clicks programmatically).
    val press = rememberPressFeedback(enabled)
    val tick = rememberTapHaptic()
    Button({ tick(UiHaptic.Primary); onClick() }, enabled = enabled, interactionSource = press.interactionSource,
        modifier = modifier.pressFeedback(press).fillMaxWidth().heightIn(min = 56.dp).clip(shape)
            .background(Brush.horizontalGradient(when {
                // Disconnect: a low-saturation rose, clearly "stop" without an alarming red.
                connected && enabled -> listOf(Color(0xFF8A3B52), Color(0xFF5E2F4E))
                connected -> listOf(Color(0xFF4A2A3C), Color(0xFF3A2536))
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
internal data class UsageMotion(val enabled: Boolean, val breathing: Boolean, val connected: Boolean,
                                /** Shown on screen, resumed and motion allowed: the gate for every looping animation. */
                                val live: Boolean = breathing,
                                val cardState: UsageCardState = UsageCardState.Normal,
                                /** 0..1 position of the low-traffic pulse; read only in draw/graphicsLayer scopes. */
                                val pulse: () -> Float = { 0f })

@Composable
internal fun rememberUsageMotion(connected: Boolean, shown: Boolean = true): UsageMotion {
    val context = LocalContext.current
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = state.isAtLeast(Lifecycle.State.RESUMED)
    // Re-read on each lifecycle transition, so toggling "remove animations" applies when the user returns.
    val scale = remember(state, context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }
    val enabled = usageMotionEnabled(scale)
    val live = resumed && shown && enabled
    return UsageMotion(enabled, usageBreathingActive(connected, resumed && shown, enabled), connected, live)
}

private const val UsagePulseStaticFraction = (UsagePulseAlphaStatic - UsagePulseAlphaMin) / (UsagePulseAlphaMax - UsagePulseAlphaMin)

/** One slow pulse shared by the bar and the remaining number while traffic is low; static when paused or motion is off. */
@Composable
private fun rememberUsagePulse(state: UsageCardState, motion: UsageMotion): () -> Float {
    val pulse = remember { Animatable(UsagePulseStaticFraction) }
    val active = usagePulseActive(state, motion.live, motion.enabled)
    LaunchedEffect(active) {
        if (!active) { pulse.snapTo(UsagePulseStaticFraction); return@LaunchedEffect }
        pulse.snapTo(0f)
        pulse.animateTo(1f, infiniteRepeatable(tween(2_000, easing = FastOutSlowInEasing), RepeatMode.Reverse))
    }
    return remember(pulse) { { pulse.value } }
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
 * changes. States (display-only):
 *  - normal: after the one-time fill, a single soft highlight passes over the filled bar to its leading edge;
 *  - low (<10% left): amber bar with a slow pulse (shared with the remaining number), only while shown + resumed;
 *  - exhausted: static red bar with one short horizontal shake once the fill has landed.
 * Reduce motion: no fill, shimmer, pulse or shake; low shows a static amber glow. Every per-frame value is read
 * in the draw/graphicsLayer phase, so animation frames never recompose the card.
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
    val state = motion.cardState
    val accent by animateColorAsState(when (state) {
        UsageCardState.Exhausted -> UsageCriticalColor
        UsageCardState.Low -> UsageWarningColor
        UsageCardState.Normal -> severity.accent()
    }, if (motion.enabled) tween(700, easing = FastOutSlowInEasing) else snap(), label = "usage-severity")
    // One-shot highlight pass for the normal state: starts once the fill has landed, never loops.
    val shimmer = remember { Animatable(-1f) }
    var shimmered by remember { mutableStateOf(false) }
    LaunchedEffect(swept, state, motion.enabled) {
        if (!swept || shimmered || state != UsageCardState.Normal || !motion.enabled || target <= 0f) return@LaunchedEffect
        shimmered = true
        delay(260)
        shimmer.snapTo(0f)
        shimmer.animateTo(1f, tween(640, easing = CubicBezierEasing(.65f, 0f, .35f, 1f)))
        shimmer.snapTo(-1f)
    }
    // One short shake (280 ms, +-3 dp, three half cycles) when an exhausted bar appears.
    val shake = remember { Animatable(0f) }
    var shook by remember { mutableStateOf(false) }
    LaunchedEffect(swept, state, motion.enabled) {
        if (!swept || shook || state != UsageCardState.Exhausted || !motion.enabled) return@LaunchedEffect
        shook = true
        shake.snapTo(0f)
        shake.animateTo(0f, keyframes { durationMillis = 280; 3f at 70; -3f at 140; 3f at 210; 0f at 280 })
    }
    // Static (non-animated) emphasis for warnings; the connected glow now lives on the card rim.
    val resting = if (severity != UsageSeverity.Normal || state != UsageCardState.Normal) .24f else 0f
    Box(Modifier.padding(top = 8.dp).fillMaxWidth()
        .graphicsLayer { translationX = shake.value * density }
        .drawWithContent {
            val fraction = shown.value
            if (fraction > 0f) {
                val low = state == UsageCardState.Low
                val alpha = if (low) usageLerp(UsagePulseAlphaMin, UsagePulseAlphaMax, motion.pulse()) else resting
                if (alpha > 0f) {
                    val radius = (if (low) 14.dp else 11.dp).toPx()
                    val center = Offset(size.width * fraction, size.height / 2)
                    drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = alpha), Color.Transparent), center, radius),
                        radius, center)
                }
            }
            drawContent()
            val sweep = shimmer.value
            if (sweep >= 0f && fraction > 0f) {
                val filled = size.width * fraction
                val band = 24.dp.toPx()
                val x = filled * sweep
                // Peak 0.28 alpha at the band centre; clipped to the filled part so it never lights the empty track.
                clipRect(0f, 0f, filled, size.height) {
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .28f), Color.Transparent),
                        startX = x - band, endX = x + band), topLeft = Offset(x - band, 0f), size = Size(band * 2, size.height))
                }
            }
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
