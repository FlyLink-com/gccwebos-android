package com.gccwebos.android.design

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gccwebos.android.R

/** Account-only presentation and explicit intents; the host owns all business/control state. */
@Composable
internal fun AccountUsageCard(
    presentation: AccountUsagePresentation,
    authenticated: Boolean,
    onRefresh: () -> Unit,
    onLogin: () -> Unit,
    onRegister: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    error: String? = null,
    refreshEnabled: Boolean = true,
    primaryLabel: String? = null,
    primaryEnabled: Boolean = false,
    primaryHint: String? = null,
    primaryTestTag: String = "vpn-connect",
    showPrimary: Boolean = true,
    onPrimaryAction: () -> Unit = {},
) {
    val refreshAllowed = authenticated && !loading && refreshEnabled
    BrandCard(modifier.testTag("account-usage-card")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text("账户当前套餐", Modifier.weight(1f).testTag("account-usage-title"),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (authenticated) IconButton(
                onClick = { if (refreshAllowed) onRefresh() },
                enabled = refreshAllowed,
                modifier = Modifier.size(48.dp).testTag("account-usage-refresh").semantics {
                    contentDescription = "更新账户套餐"
                    stateDescription = when {
                        loading -> "正在读取"
                        !refreshEnabled -> "账户待确认"
                        else -> "可以更新"
                    }
                },
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = Brand.Cyan, strokeWidth = 2.dp)
                else Icon(painterResource(R.drawable.brand_refresh), null, tint = Brand.Cyan)
            }
        }
        if (!authenticated) {
            UsageRemaining("暂未获取")
            if (showPrimary) UsagePrimaryAction(primaryLabel ?: "开通飞连vpn", primaryEnabled && !loading,
                primaryHint, primaryTestTag, onPrimaryAction)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onLogin, modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .testTag("account-usage-login")) { Text("登录") }
                TextButton(onRegister, modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .testTag("account-usage-register")) { Text("注册") }
            }
        } else {
            val readText = when {
                loading -> "正在读取"
                error != null && presentation.state == AccountUsageState.Available -> "读取失败 · 未更新"
                error != null -> "读取失败"
                else -> presentation.refreshText
            }
            if (readText != null) Text(readText, color = if (error != null) MaterialTheme.colorScheme.error else Brand.Muted,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("account-usage-read-status"))
            when (presentation.state) {
                AccountUsageState.Unknown -> UsageRemaining("暂未获取")
                AccountUsageState.NoActivePlan -> {
                    if (error != null) UsageRemaining("暂未获取") else {
                        Text("暂无生效套餐", modifier = Modifier.testTag("account-usage-no-plan"))
                    }
                }
                AccountUsageState.Reconciliation -> Text("套餐正在核对",
                    color = Brand.Muted, modifier = Modifier.testTag("account-usage-reconciliation"))
                AccountUsageState.Available -> {
                    presentation.planText?.let { Text(it, color = Brand.Muted,
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("account-usage-plan")) }
                    UsageRemaining(presentation.remainingText)
                    presentation.periodText?.let { Text(it, color = Brand.Muted,
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("account-usage-period")) }
                    UsageAmounts(presentation)
                    presentation.progress?.let { progress ->
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()
                            .testTag("account-usage-progress"), color = Brand.Cyan, trackColor = Brand.SurfaceRaised)
                    }
                    presentation.cycleText?.let { UsageFact("统计周期", it, "account-usage-cycle") }
                    presentation.expiresAtText?.let { UsageFact("到期", it, "account-usage-expiry") }
                    if (presentation.serviceExpired) Text("已到期", color = Brand.Amber,
                        modifier = Modifier.testTag("account-usage-service-expired"))
                    presentation.collectionText?.let { Text(it, color = Brand.Muted,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("account-usage-collection")) }
                    presentation.updatedAtText?.let { UsageFact("采集", it, "account-usage-updated-at") }
                }
            }
            if (showPrimary) when {
                error != null || presentation.state == AccountUsageState.Unknown ||
                    presentation.state == AccountUsageState.Reconciliation ->
                    UsagePrimaryAction("重试", refreshAllowed, null, primaryTestTag, onRefresh)
                presentation.state == AccountUsageState.NoActivePlan ->
                    UsagePrimaryAction(primaryLabel ?: "开通飞连vpn", primaryEnabled && !loading,
                        primaryHint, primaryTestTag, onPrimaryAction)
                presentation.state == AccountUsageState.Available -> {
                    val label = primaryLabel ?: "一键连接"
                    val hint = primaryHint ?: if (label == "一键连接" && !primaryEnabled) "连接功能暂未开放" else null
                    UsagePrimaryAction(label, primaryEnabled && !loading, hint, primaryTestTag, onPrimaryAction)
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun UsagePrimaryAction(label: String, enabled: Boolean, hint: String?, tag: String, onAction: () -> Unit) {
    BrandButton(label, { if (enabled) onAction() }, Modifier.testTag(tag), enabled = enabled)
    if (hint != null) Text(hint, color = Brand.Muted, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("account-usage-primary-hint"))
}

@Composable
private fun UsageRemaining(value: String) {
    Text("剩余流量", color = Brand.Muted, style = MaterialTheme.typography.labelLarge)
    Text(value, color = Brand.Cyan, style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("account-usage-remaining"))
}

@Composable
private fun UsageAmounts(presentation: AccountUsagePresentation) {
    val largeFont = LocalDensity.current.fontScale >= 1.5f
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (largeFont || maxWidth < 280.dp) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            UsageFact("已用", presentation.usedText, "account-usage-used")
            UsageFact("总量", presentation.totalText, "account-usage-total")
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) { UsageFact("已用", presentation.usedText, "account-usage-used") }
            Column(Modifier.weight(1f)) { UsageFact("总量", presentation.totalText, "account-usage-total") }
        }
    }
}

@Composable
private fun UsageFact(label: String, value: String, tag: String) {
    Text("$label $value", color = Brand.Muted, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().testTag(tag))
}
