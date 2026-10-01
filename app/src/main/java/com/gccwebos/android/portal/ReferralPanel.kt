package com.gccwebos.android.portal

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.gccwebos.android.commerce.formatCoins
import com.gccwebos.android.design.*
import java.math.BigDecimal

@Composable
internal fun ReferralPanel(account: AccountController, autoLoad: Boolean = true,
    stateOverride: ReferralController? = null, copyOverride: ((String) -> Unit)? = null) {
    val scope = rememberCoroutineScope(); val clipboard = LocalClipboardManager.current
    val currentCopy by rememberUpdatedState(copyOverride ?: { value: String -> clipboard.setText(AnnotatedString(value)) })
    val state = stateOverride ?: remember(account, account.authSessionGeneration) { ReferralController(account, scope, { currentCopy(it) }) }
    DisposableEffect(state) { onDispose { state.dispose() } }
    LaunchedEffect(state, autoLoad, account.authenticated, account.restoring, account.foregroundCheckPending) {
        if (autoLoad && account.authenticated && !account.restoring && !account.foregroundCheckPending) state.load()
    }
    BrandCard(Modifier.testTag("referral-profile")) {
        Row(Modifier.fillMaxWidth()) {
            Text("推荐奖励", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = state::load, enabled = !state.busy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("referral-refresh")) { Text("刷新") }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("referral-loading"), color = Brand.Cyan)
        state.profileError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("referral-profile-error")) }
        val profile = state.profile
        when {
            profile == null -> Text(if (state.busy) "正在读取推荐资格" else "推荐资格尚未读取", modifier = Modifier.testTag("referral-profile-status"))
            !profile.eligible -> Text("当前账户暂不具备推荐资格", modifier = Modifier.testTag("referral-profile-status"))
            else -> {
                if (state.profileError != null) SupportingText("本次未能更新，以下为上次读取的资料。")
                profile.policy?.let { policy ->
                    val percent = BigDecimal.valueOf(policy.rateBps).movePointLeft(2).stripTrailingZeros().toPlainString()
                    Text("当前奖励比例 $percent%", modifier = Modifier.testTag("referral-policy-rate"))
                    SupportingText("归因期 ${policy.attributionDays} 天 · 确认期 ${policy.confirmationDays} 天")
                } ?: SupportingText("当前奖励政策尚未读取，不预估收益。")
                if (profile.attributionDays == null) SupportingText("推荐归因期尚未确认，暂不能生成或复制链接。")
                if (profile.linkPath != null) BrandButton(if (state.copied) "已复制推荐链接" else "复制推荐链接", state::copyLink,
                    enabled = state.canCopy, modifier = Modifier.testTag("referral-copy"))
                else BrandButton("生成推荐链接", state::generateLink, enabled = state.canGenerate,
                    modifier = Modifier.testTag("referral-generate"))
                SupportingText("推荐记录和奖励以服务器核对结果为准。")
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("referral-error")) }
    }
    BrandCard(Modifier.testTag("referral-performance")) {
        Text("推荐记录", style = MaterialTheme.typography.titleMedium)
        state.performanceError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("referral-performance-error")) }
        state.performance?.let { p ->
            if (state.performanceError != null) SupportingText("本次未能更新，以下为上次查询记录。")
            Text("注册 ${p.registrations} · 首次充值 ${p.firstRechargeConversions}")
            Text("已转可用奖励 ${formatCoins(p.availableRewardCoins)}", modifier = Modifier.testTag("referral-available-reward"))
            SupportingText("待确认 ${formatCoins(p.pendingRewardCoins)} · 已撤回 ${formatCoins(p.reversedRewardCoins)}")
            if (p.reconciliationRequired) SupportingText("奖励正在对账，请稍后查询。")
            SupportingText("此处为推荐奖励记录，不作为当前钱包可花余额。")
        } ?: Text(if (state.busy) "正在读取推荐记录" else "推荐记录尚未读取", modifier = Modifier.testTag("referral-performance-status"))
    }
}
