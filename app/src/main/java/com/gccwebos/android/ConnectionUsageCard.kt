package com.gccwebos.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
) {
    val refreshAllowed = authenticated && !loading && refreshEnabled
    Column(modifier.fillMaxWidth().testTag("account-usage-card")) {
        if (!authenticated) ConnectionRemaining("暂未获取") else when (presentation.state) {
            AccountUsageState.Unknown -> ConnectionRemaining("暂未获取")
            AccountUsageState.NoActivePlan -> if (error != null) ConnectionRemaining("暂未获取") else
                Text("暂无生效套餐", modifier = Modifier.testTag("account-usage-no-plan"))
            AccountUsageState.Reconciliation -> Text("套餐正在核对", color = Brand.Muted,
                modifier = Modifier.testTag("account-usage-reconciliation"))
            AccountUsageState.Available -> {
                ConnectionRemaining(presentation.remainingText)
                ConnectionUsageAmounts(presentation)
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

@Composable
private fun ConnectionRemaining(value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("剩余流量", color = Brand.Muted, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val measurer = rememberTextMeasurer()
            val numeric = value.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})? GB"))
            val style = MaterialTheme.typography.headlineLarge.copy(color = Brand.Cyan)
            fun amount(size: Int) = buildAnnotatedString {
                if (numeric) {
                    withStyle(SpanStyle(fontSize = size.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-1.6).sp)) { append(value.removeSuffix(" GB")) }
                    withStyle(SpanStyle(fontSize = 19.sp, fontWeight = FontWeight.Medium)) { append(" GB") }
                } else withStyle(SpanStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold)) { append(value) }
            }
            val sizes = if (maxWidth < 300.dp) listOf(45, 37, 29, 24) else listOf(49, 45, 37, 29, 24)
            val width = with(density) { maxWidth.roundToPx() }
            val size = sizes.firstOrNull { measurer.measure(amount(it), style, softWrap = false).size.width <= width }
                ?: sizes.last()
            // One text node retains the full value for readout/tests. No ellipsis or fixed height at large font scales.
            Text(amount(size), style = style, lineHeight = if (numeric) (size * 1.15f).sp else 40.sp,
                modifier = Modifier.fillMaxWidth().testTag("account-usage-remaining"))
        }
    }
}

@Composable
private fun ConnectionUsageAmounts(presentation: AccountUsagePresentation) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        presentation.periodText?.let {
            Text(it, color = Brand.Muted, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 6.dp).testTag("account-usage-period"))
        }
        val used: @Composable () -> Unit = { ConnectionUsageAmount("已用", presentation.usedText, "account-usage-used") }
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
            LinearProgressIndicator(progress = { progress }, color = Brand.Cyan, trackColor = Brand.SurfaceRaised,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(6.dp)
                    .clip(RoundedCornerShape(3.dp)).testTag("account-usage-progress"))
        }
    }
}

@Composable
private fun ConnectionUsageAmount(label: String, value: String, tag: String) {
    Text(buildAnnotatedString {
        append("$label ")
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(value) }
    }, color = Brand.Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.testTag(tag))
}

@Composable
private fun ConnectionUsageDates(presentation: AccountUsagePresentation) {
    if (presentation.cycleText == null && presentation.expiresAtText == null) return
    var showDetails by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        HorizontalDivider(color = Brand.RowDivider)
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            presentation.cycleText?.let {
                ConnectionDateRow("统计周期", connectionCycleSummary(it), it, "account-usage-cycle")
            }
            presentation.expiresAtText?.let {
                ConnectionDateRow("套餐到期", connectionDateSummary(it), it, "account-usage-expiry") { showDetails = true }
            }
        }
        if (presentation.expiresAtText == null) {
            TextButton({ showDetails = true }, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)
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
private fun ConnectionDateRow(label: String, summary: String, full: String, tag: String, onDetails: (() -> Unit)? = null) {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = "$label $full" }) {
        val labelContent: @Composable () -> Unit = { Text(label, color = Brand.Muted, fontSize = 11.sp, lineHeight = 16.sp) }
        val valueContent: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                Text(summary, color = Brand.Muted, fontSize = 11.sp, lineHeight = 16.sp,
                    modifier = Modifier.weight(1f, fill = false))
                onDetails?.let {
                    // A full touch target preserves the preview's compact chevron without a tiny interactive area.
                    IconButton(it, modifier = Modifier.size(48.dp).testTag("account-usage-time-details")
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
