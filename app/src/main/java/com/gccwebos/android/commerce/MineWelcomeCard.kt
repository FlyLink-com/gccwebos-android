package com.gccwebos.android.commerce

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gccwebos.android.connectionDateSummary
import com.gccwebos.android.design.Brand
import com.gccwebos.android.design.GccShapes
import com.gccwebos.android.design.GccSpacing
import com.gccwebos.android.design.SectionCard
import com.gccwebos.android.design.accountUsagePresentation
import com.gccwebos.android.design.subscriptionTitle
import com.gccwebos.android.portal.AccountController
import java.time.Instant

/**
 * Reuses the verified overview; opening Mine never starts another account read.
 * Signed in: masked email (or the shortened account ID) + 复制 ID + plan capsule + expiry line + 续费 link,
 * with a drawn default avatar in place of the mascot.
 * Signed out (onLogin set): 我的账户 / 未登录 / 去登录, unchanged from #35.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MineWelcomeCard(account: AccountController, embedded: Boolean = false, onLogin: (() -> Unit)? = null,
    onRenew: (() -> Unit)? = null, copyOverride: ((String) -> Unit)? = null) {
    val signedOut = onLogin != null && !account.authenticated
    val usage by account.accountUsage.state.collectAsState()
    val verifying = account.restoring || account.foregroundCheckPending || usage.verifying
    val verified = account.authenticated && !verifying && !account.clearing && usage.authenticated
    val resource = if (verified) usage.resource else null
    val display = accountUsagePresentation(resource, Instant.now(), usage.notUpdated)
    val signedIn = account.authenticated && !account.clearing
    val identity = mineIdentityText(account.displayEmail, account.authenticatedAccountId)
    val copyId = if (signedIn && !verifying) copyableAccountId(account.authenticatedAccountId) else null
    val content: @Composable ColumnScope.() -> Unit = {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.5f
            val details: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(GccSpacing.Small)) {
                    if (signedIn) {
                        val plan = if (verifying) "—" else display.planText ?: subscriptionTitle(resource) ?: display.statusText ?: "—"
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(GccSpacing.Small), verticalArrangement = Arrangement.spacedBy(GccSpacing.Small),
                            itemVerticalAlignment = Alignment.CenterVertically) {
                            Text(identity.title, style = MaterialTheme.typography.displaySmall, color = Brand.Text,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("mine-account-identity"))
                            // The ID is the title itself (no known email): the square copy button sits right beside it.
                            if (identity.idLine == null) copyId?.let { MineCopyIdButton(it, copyOverride) }
                            PlanCapsule(plan)
                        }
                        // With a masked email as the title, the ID has its own line and the button follows it.
                        if (identity.idLine != null) Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(GccSpacing.Small)) {
                            Text(identity.idLine, color = Brand.Muted, style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).testTag("mine-account-id"))
                            copyId?.let { MineCopyIdButton(it, copyOverride) }
                        }
                        val expiry = display.expiresAtText
                        val line = when {
                            verifying || expiry == null -> "会员有效期至：—"
                            display.serviceExpired -> "会员已到期：${connectionDateSummary(expiry)}"
                            else -> "会员有效期至：${connectionDateSummary(expiry)}"
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(line, color = Brand.Muted, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f, fill = false).testTag("mine-plan-expiry").semantics {
                                contentDescription = when {
                                    verifying || expiry == null -> line
                                    display.serviceExpired -> "会员已到期 $expiry"
                                    else -> "会员有效期至 $expiry"
                                }
                            })
                        // Opens the existing 套餐 tab only; never a retained quote or a purchase.
                        if (onRenew != null) MineRenewLink(!account.busy && !verifying, onRenew)
                        }
                    } else {
                        Text(if (signedOut) "我的账户" else "账户未登录",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.testTag("mine-welcome-title"))
                        Surface(shape = GccShapes.small, color = Brand.Cyan.copy(alpha = .09f)) {
                            Text("未登录", color = Brand.Cyan, style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = GccSpacing.Small, vertical = GccSpacing.ExtraSmall)
                                    .testTag("mine-login-status"))
                        }
                    }
                }
            }
            val avatar: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier) { MineAvatar(if (signedIn) mineAvatarInitial(account.displayEmail) else null, enabled = signedIn) }
            }
            if (compact) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GccSpacing.Small)) {
                details(Modifier.fillMaxWidth())
                avatar(Modifier.align(Alignment.End))
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GccSpacing.Medium)) {
                details(Modifier.weight(1f))
                avatar(Modifier)
            }
        }
        if (signedOut) com.gccwebos.android.design.BrandButton("去登录", { onLogin?.invoke() },
            enabled = !account.busy && !account.restoring && !account.foregroundCheckPending,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mine-go-login"))
        if (verified && usage.notUpdated) Text("套餐信息未更新", color = Brand.Muted,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("mine-plan-not-updated"))
    }
    if (embedded) Column(Modifier.fillMaxWidth().testTag("mine-welcome"),
        verticalArrangement = Arrangement.spacedBy(GccSpacing.Small), content = content)
    else SectionCard(Modifier.testTag("mine-welcome"), contentPadding = PaddingValues(GccSpacing.Large), content = content)
}

/** Highlighted plan name from the verified overview; never a hard-coded tier. */
@Composable
private fun PlanCapsule(plan: String) {
    Surface(shape = GccShapes.pill, color = Brand.Cyan.copy(alpha = .14f),
        border = BorderStroke(1.dp, Brush.horizontalGradient(listOf(Brand.Cyan.copy(alpha = .75f), Brand.Blue.copy(alpha = .55f))))) {
        Text(plan, color = Brand.Cyan, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = GccSpacing.Medium, vertical = GccSpacing.ExtraSmall).testTag("mine-current-plan"))
    }
}
