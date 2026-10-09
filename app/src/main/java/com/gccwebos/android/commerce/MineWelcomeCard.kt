package com.gccwebos.android.commerce

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gccwebos.android.R
import com.gccwebos.android.connectionDateSummary
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.gccwebos.android.design.Brand
import com.gccwebos.android.design.BrandCard
import com.gccwebos.android.design.accountUsagePresentation
import com.gccwebos.android.design.subscriptionTitle
import com.gccwebos.android.portal.AccountController
import java.time.Instant

/** Reuses the verified overview; opening Mine never starts another account read. */
@Composable
internal fun MineWelcomeCard(account: AccountController, embedded: Boolean = false, onLogin: (() -> Unit)? = null) {
    val signedOut = onLogin != null && !account.authenticated
    val usage by account.accountUsage.state.collectAsState()
    val verifying = account.restoring || account.foregroundCheckPending || usage.verifying
    val verified = account.authenticated && !verifying && !account.clearing && usage.authenticated
    val resource = if (verified) usage.resource else null
    val display = accountUsagePresentation(resource, Instant.now(), usage.notUpdated)
    val title = subscriptionTitle(resource) ?: display.planText ?: "我的账户"
    val status = when {
        verifying -> "—"
        account.authenticated && !account.clearing -> "已登录"
        else -> "未登录"
    }
    val content: @Composable ColumnScope.() -> Unit = {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.5f
            val greeting: @Composable (Modifier) -> Unit = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(title, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("mine-welcome-title"))
                    if (!signedOut) Text(if (account.authenticated) account.displayEmail ?: "账户" else "账户未登录",
                        color = Brand.Muted, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("mine-account-identity"))
                    Surface(shape = RoundedCornerShape(9.dp), color = Brand.Cyan.copy(alpha = .09f)) {
                        Text(status, color = Brand.Cyan, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp).testTag("mine-login-status"))
                    }
                }
            }
            val robot: @Composable (Modifier) -> Unit = { modifier ->
                Image(painterResource(R.drawable.brand_robot_welcome), contentDescription = null,
                    modifier = modifier.size(64.dp))
            }
            if (compact) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                greeting(Modifier.fillMaxWidth())
                robot(Modifier.align(Alignment.End))
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                greeting(Modifier.weight(1f))
                robot(Modifier)
            }
        }
        if (signedOut) com.gccwebos.android.design.BrandButton("去登录", { onLogin?.invoke() },
            enabled = !account.busy && !account.restoring && !account.foregroundCheckPending,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("mine-go-login"))
        else BoxWithConstraints(Modifier.fillMaxWidth()) {
            val plan = when {
                !account.authenticated -> "登录后查看套餐"
                verifying -> "—"
                else -> display.planText ?: display.statusText ?: "—"
            }
            val expiry = display.expiresAtText?.let {
                if (display.serviceExpired) "已到期 ${connectionDateSummary(it)}" else "到期 ${connectionDateSummary(it)}"
            } ?: "—"
            val planText: @Composable (Modifier) -> Unit = { modifier ->
                Text(plan, color = Brand.Muted, style = MaterialTheme.typography.bodySmall,
                    modifier = modifier.testTag("mine-current-plan"))
            }
            val expiryText: @Composable (Modifier) -> Unit = { modifier ->
                Text(expiry, color = Brand.Muted, style = MaterialTheme.typography.bodySmall,
                    modifier = modifier.testTag("mine-plan-expiry").semantics {
                        contentDescription = display.expiresAtText?.let { if (display.serviceExpired) "已到期 $it" else "到期 $it" } ?: expiry
                    })
            }
            if (maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.3f) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    planText(Modifier.fillMaxWidth())
                    expiryText(Modifier.fillMaxWidth())
                }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                planText(Modifier.weight(1f))
                expiryText(Modifier.weight(1.5f))
            }
        }
        if (verified && usage.notUpdated) Text("套餐信息未更新", color = Brand.Muted,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("mine-plan-not-updated"))
    }
    if (embedded) Column(Modifier.fillMaxWidth().testTag("mine-welcome"),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    else BrandCard(Modifier.testTag("mine-welcome"), content = content)
}
