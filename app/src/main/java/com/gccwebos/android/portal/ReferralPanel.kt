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
    stateOverride: ReferralController? = null, copyOverride: ((String) -> Unit)? = null, refreshRequest: Int = 0) {
    val scope = rememberCoroutineScope(); val clipboard = LocalClipboardManager.current
    val currentCopy by rememberUpdatedState(copyOverride ?: { value: String -> clipboard.setText(AnnotatedString(value)) })
    val state = stateOverride ?: remember(account, account.authSessionGeneration) { ReferralController(account, scope, { currentCopy(it) }) }
    DisposableEffect(state) { onDispose { state.dispose() } }
    LaunchedEffect(state, autoLoad, account.authenticated, account.restoring, account.foregroundCheckPending, refreshRequest) {
        if (autoLoad && account.authenticated && !account.restoring && !account.foregroundCheckPending) state.load()
    }
    // Keep the controller and its session-owned unknown-generation latch composed while
    // account validation is pending. Only the private presentation is withheld.
    if (!account.authenticated || account.restoring || account.foregroundCheckPending) {
        BrandCard(Modifier.testTag("referral-account-confirmation")) {
            Text(if (account.restoring || account.foregroundCheckPending) "—" else "登录后查看推荐",
                modifier = Modifier.testTag("referral-account-confirmation-status"))
        }
        return
    }
    TransientReadError(listOfNotNull(state.profileError, state.performanceError).distinct()
        .takeIf { it.isNotEmpty() }?.let { "推荐信息暂不可用" }, Modifier.testTag("referral-read-error"))
    BrandCard(Modifier.testTag("referral-profile")) {
        Text("推荐奖励", style = MaterialTheme.typography.titleMedium)
        val profile = state.profile
        when {
            profile == null -> Spacer(Modifier.height(8.dp))
            !profile.eligible -> Text("当前账户暂不具备推荐资格", modifier = Modifier.testTag("referral-profile-status"))
            else -> {
                if (state.profileError != null) SupportingText("未更新")
                profile.policy?.let { policy ->
                    val percent = BigDecimal.valueOf(policy.rateBps).movePointLeft(2).stripTrailingZeros().toPlainString()
                    Text("当前奖励比例 $percent%", modifier = Modifier.testTag("referral-policy-rate"))
                    SupportingText("归因期 ${policy.attributionDays} 天 · 确认期 ${policy.confirmationDays} 天")
                }
                if (profile.attributionDays == null) SupportingText("推荐暂不可用",
                    Modifier.testTag("referral-distribution-unavailable"))
                if (profile.linkPath != null) BrandButton(if (state.copied) "已复制推荐链接" else "复制推荐链接", state::copyLink,
                    enabled = state.canCopy, modifier = Modifier.testTag("referral-copy"))
                else BrandButton("生成推荐链接", state::generateLink, enabled = state.canGenerate,
                    modifier = Modifier.testTag("referral-generate"))
            }
        }
        state.error?.let { Text(if (account.referralGenerationGuard.unknown) "推荐链接生成结果未确认" else it,
            color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("referral-error")) }
    }
    BrandCard(Modifier.testTag("referral-performance")) {
        Text("推荐记录", style = MaterialTheme.typography.titleMedium)
        state.performance?.let { p ->
            if (state.performanceError != null) SupportingText("未更新")
            Text("注册 ${p.registrations} · 首次充值 ${p.firstRechargeConversions}")
            Text("已转可用奖励 ${formatCoins(p.availableRewardCoins)}", modifier = Modifier.testTag("referral-available-reward"))
            SupportingText("待确认 ${formatCoins(p.pendingRewardCoins)} · 已撤回 ${formatCoins(p.reversedRewardCoins)}")
            if (p.reconciliationRequired) SupportingText("奖励对账中")
            SupportingText("推荐奖励记录 · 非钱包余额")
        } ?: Spacer(Modifier.height(8.dp))
    }
}
