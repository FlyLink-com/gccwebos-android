package com.gccwebos.android.portal

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.background
import androidx.core.net.toUri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gccwebos.android.design.*

internal enum class ReferralShareTarget { Telegram, X, System }

@Composable
internal fun ReferralPanel(account: AccountController, autoLoad: Boolean = true,
    stateOverride: ReferralController? = null, copyOverride: ((String) -> Unit)? = null, refreshRequest: Int = 0,
    shareOverride: ((ReferralShareTarget, String) -> Unit)? = null) {
    val scope = rememberCoroutineScope(); val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
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
    val share: (ReferralShareTarget, String) -> Unit = shareOverride ?: { target, url ->
        val intent = when (target) {
            ReferralShareTarget.Telegram -> Intent(Intent.ACTION_VIEW, ReferralPresentation.telegramShareUrl(url).toUri())
                .addCategory(Intent.CATEGORY_BROWSABLE)
            ReferralShareTarget.X -> Intent(Intent.ACTION_VIEW, ReferralPresentation.xShareUrl(url).toUri())
                .addCategory(Intent.CATEGORY_BROWSABLE)
            ReferralShareTarget.System -> Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, ReferralPresentation.shareMessage(url)), "分享邀请链接")
        }
        context.startActivity(intent)
    }
    var shareError by remember(state) { mutableStateOf<String?>(null) }
    fun openShare(target: ReferralShareTarget) {
        val url = state.shareUrl() ?: return
        shareError = try { share(target, url); null } catch (_: ActivityNotFoundException) { "未找到可用于分享的应用" }
    }

    val profile = state.profile
    val policy = profile?.policy?.takeIf { profile.eligible }
    Text("邀请返利", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { heading() }.testTag("referral-title"))
    TransientReadError(listOfNotNull(state.profileError, state.performanceError).distinct()
        .takeIf { it.isNotEmpty() }?.let { "推荐信息暂不可用" }, Modifier.testTag("referral-read-error"))

    ReferralHero(policy)

    BrandCard(Modifier.testTag("referral-profile")) {
        Text("我的专属邀请", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        when {
            // First profile read in flight: a placeholder for the link field and copy button.
            profile == null && state.busy && state.profileError == null ->
                SkeletonCards(count = 2, cardHeight = 48.dp, spacing = 8.dp, shape = RoundedCornerShape(14.dp), lines = 0,
                    modifier = Modifier.testTag("referral-profile-skeleton"))
            profile == null -> Spacer(Modifier.height(8.dp))
            !profile.eligible -> Text("当前账户暂不具备推荐资格", modifier = Modifier.testTag("referral-profile-status"))
            else -> {
                if (state.profileError != null) SupportingText("未更新")
                if (profile.attributionDays == null) SupportingText("推荐暂不可用",
                    Modifier.testTag("referral-distribution-unavailable"))
                val url = profile.linkPath?.let { runCatching { ReferralJson.shareUrl(it) }.getOrNull() }
                if (profile.linkPath != null) {
                    url?.let { LinkField(ReferralPresentation.maskedLink(it)) }
                    BrandButton(if (state.copied) "已复制推荐链接" else "复制推荐链接", state::copyLink,
                        enabled = state.canCopy, modifier = Modifier.testTag("referral-copy"))
                    Row(Modifier.fillMaxWidth().testTag("referral-share-row"), horizontalArrangement = Arrangement.SpaceBetween) {
                        val enabled = state.canCopy
                        ShareAction(if (state.copiedMessage) "已复制" else "复制文案", ReferralGlyph.Text, enabled,
                            "referral-share-text", state::copyShareMessage)
                        ShareAction("Telegram", ReferralGlyph.Telegram, enabled, "referral-share-telegram") { openShare(ReferralShareTarget.Telegram) }
                        ShareAction("X", ReferralGlyph.X, enabled, "referral-share-x") { openShare(ReferralShareTarget.X) }
                        ShareAction("更多", ReferralGlyph.Share, enabled, "referral-share-system") { openShare(ReferralShareTarget.System) }
                    }
                    shareError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("referral-share-error")) }
                } else BrandButton("生成推荐链接", state::generateLink, enabled = state.canGenerate,
                    modifier = Modifier.testTag("referral-generate"))
            }
        }
        state.error?.let { Text(if (account.referralGenerationGuard.unknown) "推荐链接生成结果未确认" else it,
            color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("referral-error")) }
    }

    BrandCard(Modifier.testTag("referral-performance")) {
        Text("推广收益看板", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        state.performance?.let { p ->
            if (state.performanceError != null) SupportingText("未更新")
            val stats = ReferralPresentation.stats(p)
            stats.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { StatCell(it, Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        } ?: Spacer(Modifier.height(8.dp))
    }

    if (profile?.eligible == true) {
        if (policy != null) BrandCard(Modifier.testTag("referral-steps")) {
            Text("如何获得奖励", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            ReferralPresentation.steps(policy).forEachIndexed { index, step -> StepRow(index + 1, step) }
        }
        val rules = ReferralPresentation.rules(profile)
        if (rules.isNotEmpty()) BrandCard(Modifier.testTag("referral-rules")) {
            Text("规则说明", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            rules.forEach { rule ->
                Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("referral-rule-${rule.key}"),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(rule.title, color = Brand.Cyan, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    SupportingText(rule.body)
                }
            }
        }
    }
}

@Composable
private fun ReferralHero(policy: ReferralPolicy?) {
    val hero = ReferralPresentation.hero(policy)
    val shape = RoundedCornerShape(22.dp)
    Box(Modifier.fillMaxWidth().clip(shape)
        .background(Brush.linearGradient(listOf(Color(0xFF0F2B57), Color(0xFF1B1A4A), Color(0xFF2A1458))))
        .border(1.dp, Brush.linearGradient(listOf(Brand.Cyan.copy(alpha = .7f), Brand.ActionEnd.copy(alpha = .5f))), shape)
        .testTag("referral-hero")) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(hero.title, color = Brand.Text, fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("referral-hero-title"))
                policy?.let {
                    Text(ReferralPresentation.rateLabel(it), color = Brand.Background, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Brand.Cyan)
                            .padding(horizontal = 10.dp, vertical = 3.dp).testTag("referral-policy-rate"))
                }
                Text(hero.subtitle, color = Brand.Muted, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("referral-hero-subtitle"))
            }
            ReferralGiftArt(Modifier.size(92.dp).clearAndSetSemantics { })
        }
    }
}

@Composable
private fun LinkField(masked: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = .25f))
        .border(1.dp, Brand.GlassBorder.copy(alpha = .18f), RoundedCornerShape(12.dp))
        .padding(horizontal = 12.dp, vertical = 10.dp)
        .semantics(mergeDescendants = true) { contentDescription = "专属链接 $masked" }.testTag("referral-link"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReferralGlyphIcon(ReferralGlyph.Copy, Brand.Cyan, Modifier.size(16.dp))
        Text(masked, color = Brand.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).testTag("referral-link-masked"))
    }
}

@Composable
private fun ShareAction(label: String, glyph: ReferralGlyph, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Column(Modifier.widthIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
        .alpha(if (enabled) 1f else .45f).padding(vertical = 4.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Brand.SurfaceRaised)
            .border(1.dp, Brand.Cyan.copy(alpha = .55f), CircleShape), contentAlignment = Alignment.Center) {
            ReferralGlyphIcon(glyph, Brand.Cyan, Modifier.size(20.dp))
        }
        Text(label, color = Brand.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun StatCell(stat: ReferralPresentation.Stat, modifier: Modifier) {
    val tag = if (stat.key == "available") "referral-available-reward" else "referral-stat-${stat.key}"
    val accent = if (stat.key == "available") Brand.Amber else Brand.Cyan
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .04f))
        .border(1.dp, Brand.GlassBorder.copy(alpha = .14f), RoundedCornerShape(14.dp))
        .padding(10.dp).semantics(mergeDescendants = true) {}.testTag(tag),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stat.label, color = Brand.Muted, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stat.value, color = accent, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp,
                modifier = Modifier.alignByBaseline())
            Text(stat.unit, color = Brand.Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.alignByBaseline())
        }
        Text(stat.note, color = Brand.Muted.copy(alpha = .8f), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun StepRow(number: Int, step: ReferralPresentation.Step) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("referral-step-$number"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(32.dp).clip(CircleShape)
            .background(Brush.linearGradient(listOf(Brand.ActionStart, Brand.ActionEnd)))
            .border(1.dp, Brand.Cyan.copy(alpha = .7f), CircleShape), contentAlignment = Alignment.Center) {
            Text("$number", color = Brand.Text, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text(step.title, color = Brand.Text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
            SupportingText(step.body)
        }
    }
}
