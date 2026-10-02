package com.gccwebos.android.portal

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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
    // Keep the controller and its session-owned unknown-generation latch composed while
    // account validation is pending. Only the private presentation is withheld.
    if (!account.authenticated || account.restoring || account.foregroundCheckPending) {
        BrandCard(Modifier.testTag("referral-account-confirmation")) {
            Text(if (account.restoring || account.foregroundCheckPending) "正在确认账户" else "登录后查看推荐",
                modifier = Modifier.testTag("referral-account-confirmation-status"))
        }
        return
    }
    BrandCard(Modifier.testTag("referral-profile")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("推荐奖励", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            // Unknown generation can coexist with two loaded GET results. Recovery remains a read,
            // never another POST; a failed copy instead keeps its existing copy action.
            if (showReadRetry(state.busy, true, state.profileError != null || state.performanceError != null,
                    state.profile == null || state.performance == null,
                    (account.referralGenerationGuard.unknown && state.profile?.linkPath == null) ||
                        (state.profile?.eligible == true && state.profile?.attributionDays == null)))
                BrandButton("重试", state::load, modifier = Modifier.width(88.dp).testTag("referral-refresh"))
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("referral-loading"), color = Brand.Cyan)
        state.profileError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("referral-profile-error")) }
        val profile = state.profile
        when {
            profile == null -> Text(if (state.busy) "正在读取推荐资格" else "推荐资格尚未读取", modifier = Modifier.testTag("referral-profile-status"))
            !profile.eligible -> Text("当前账户暂不具备推荐资格", modifier = Modifier.testTag("referral-profile-status"))
            else -> {
                if (state.profileError != null) SupportingText("上次读取的资料")
                profile.policy?.let { policy ->
                    val percent = BigDecimal.valueOf(policy.rateBps).movePointLeft(2).stripTrailingZeros().toPlainString()
                    Text("当前奖励比例 $percent%", modifier = Modifier.testTag("referral-policy-rate"))
                    SupportingText("归因期 ${policy.attributionDays} 天 · 确认期 ${policy.confirmationDays} 天")
                } ?: SupportingText("奖励政策待确认")
                if (profile.attributionDays == null) SupportingText("归因期待确认")
                if (profile.linkPath != null) BrandButton(if (state.copied) "已复制推荐链接" else "复制推荐链接", state::copyLink,
                    enabled = state.canCopy, modifier = Modifier.testTag("referral-copy"))
                else BrandButton("生成推荐链接", state::generateLink, enabled = state.canGenerate,
                    modifier = Modifier.testTag("referral-generate"))
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("referral-error")) }
    }
    BrandCard(Modifier.testTag("referral-performance")) {
        Text("推荐记录", style = MaterialTheme.typography.titleMedium)
        state.performanceError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("referral-performance-error")) }
        state.performance?.let { p ->
            if (state.performanceError != null) SupportingText("上次查询记录")
            Text("注册 ${p.registrations} · 首次充值 ${p.firstRechargeConversions}")
            Text("已转可用奖励 ${formatCoins(p.availableRewardCoins)}", modifier = Modifier.testTag("referral-available-reward"))
            SupportingText("待确认 ${formatCoins(p.pendingRewardCoins)} · 已撤回 ${formatCoins(p.reversedRewardCoins)}")
            if (p.reconciliationRequired) SupportingText("奖励正在对账，请稍后查询。")
            SupportingText("推荐奖励记录 · 非钱包余额")
        } ?: Text(if (state.busy) "正在读取推荐记录" else "推荐记录尚未读取", modifier = Modifier.testTag("referral-performance-status"))
    }
}
