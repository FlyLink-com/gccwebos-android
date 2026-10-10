package com.gccwebos.android.commerce

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gccwebos.android.R
import com.gccwebos.android.design.Brand
import com.gccwebos.android.design.GccShapes
import com.gccwebos.android.design.GccSpacing
import com.gccwebos.android.design.GccType
import com.gccwebos.android.design.GlassSurface

/** The one configured official channel. The Mine rows derive their icon and handle from it. */
internal const val MINE_OFFICIAL_CHANNEL_URL = "https://t.me/flylinevpn"

/** Header identity text. [title] is never an invented nickname; [idLine] only accompanies a known email. */
internal data class MineIdentityText(val title: String, val idLine: String?)

/**
 * Signed-in identity: masked email when this process saw the login email, otherwise the shortened
 * server account ID, otherwise a neutral "我的账户". The email is not persisted across restarts and the
 * Portal has no profile-email field, so a restored session shows the ID form.
 */
internal fun mineIdentityText(email: String?, accountId: String?): MineIdentityText {
    val masked = email?.trim()?.takeIf { it.isNotEmpty() }?.let(::maskAccountEmail)
    val id = accountId?.let(::displayAccountId)?.let { "ID $it" }
    return MineIdentityText(masked ?: id ?: "我的账户", if (masked != null) id else null)
}

/**
 * Short, display-only form of the server account ID (the full ID is what [复制 ID] copies).
 * An email-shaped ID is masked like an email; short IDs are shown whole.
 */
internal fun displayAccountId(accountId: String): String? {
    val value = accountId.trim()
    if (value.isEmpty() || value.any { it.isISOControl() || it.isWhitespace() }) return null
    if ('@' in value) return maskAccountEmail(value)
    if (value.length <= 14) return value
    val underscore = value.indexOf('_').takeIf { it in 1..8 }
    val head = if (underscore != null) value.substring(0, underscore + 1) else value.take(4)
    return head + "…" + value.takeLast(6)
}

/** The copied value: the exact server account ID, or null when it is unusable. */
internal fun copyableAccountId(accountId: String?): String? =
    accountId?.takeIf { displayAccountId(it) != null }?.trim()

/** First letter/digit of the email's local part, upper-cased; null means draw the generic person glyph. */
internal fun mineAvatarInitial(email: String?): String? {
    val local = email?.trim()?.substringBefore('@').orEmpty()
    val first = local.firstOrNull() ?: return null
    return if (first.isLetterOrDigit() && first.code < 0x0250) first.uppercaseChar().toString() else null
}

/** Only https://t.me/<handle> (or telegram.me) counts as a Telegram channel. */
internal fun isTelegramChannel(url: String): Boolean = telegramHandle(url) != null

internal fun telegramHandle(url: String): String? {
    val match = Regex("^https://(?:t|telegram)\\.me/([A-Za-z][A-Za-z0-9_]{3,31})/?$").matchEntire(url.trim()) ?: return null
    return "@" + match.groupValues[1]
}

/** "当前版本 v0.4.2" from BuildConfig.VERSION_NAME; a blank name shows no version. */
internal fun mineVersionLabel(versionName: String?): String? =
    versionName?.trim()?.takeIf { it.isNotEmpty() }?.let { "当前版本 v" + it.removePrefix("v") }

/**
 * Clean page title for the 我的 tab, on a glass strip so the backdrop artwork's own neon wordmark
 * no longer reads as a ghosted second logo behind the header.
 */
@Composable
internal fun MineTopBar(title: String = "我的") {
    GlassSurface(Modifier.fillMaxWidth().testTag("mine-topbar"), shape = GccShapes.card,
        edge = MineStyle.Edge, tint = Brush.verticalGradient(listOf(Brand.Background.copy(alpha = .55f), Brand.Background.copy(alpha = .35f)))) {
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Brand.Text,
                textAlign = TextAlign.Center, maxLines = 1,
                modifier = Modifier.semantics { heading() }.testTag("mine-topbar-title"))
        }
    }
}

/** Drawn default avatar: initial or person glyph on a cyan ring. Decorative; no upload. */
@Composable
internal fun MineAvatar(initial: String?, enabled: Boolean, size: Dp = 64.dp) {
    val ring = if (enabled) listOf(Brand.Cyan, Brand.Blue.copy(alpha = .8f), Brand.Cyan.copy(alpha = .6f))
        else listOf(Brand.Muted.copy(alpha = .5f), Brand.Muted.copy(alpha = .25f))
    Box(Modifier.size(size).testTag("mine-avatar").drawBehind {
        val r = this.size.minDimension / 2
        if (enabled) drawCircle(Brush.radialGradient(listOf(Brand.Cyan.copy(alpha = .28f), Color.Transparent), center, r))
        drawCircle(Brand.SurfaceRaised.copy(alpha = .92f), r * .80f)
        drawCircle(Brush.sweepGradient(ring + ring.first(), center), r * .80f, style = Stroke(width = 2.dp.toPx()))
    }, contentAlignment = Alignment.Center) {
        val tint = if (enabled) Brand.Cyan else Brand.Muted
        if (initial != null) Text(initial, color = tint, style = GccType.avatarGlyph(size),
            modifier = Modifier.testTag("mine-avatar-initial"))
        else Icon(painterResource(R.drawable.brand_user), null, tint = tint, modifier = Modifier.size(size * .42f))
    }
}

/** Small outlined pill that copies the full account ID; shows 已复制 once used. */
@Composable
internal fun MineCopyIdButton(accountId: String, copyOverride: ((String) -> Unit)?) {
    val context = LocalContext.current
    var copied by remember(accountId) { mutableStateOf(false) }
    TextButton(onClick = {
        copied = try {
            (copyOverride ?: { value: String ->
                (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("账户 ID", value))
            })(accountId)
            true
        } catch (_: Exception) { false }
    }, shape = GccShapes.pill, contentPadding = PaddingValues(horizontal = GccSpacing.Medium, vertical = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Brand.Cyan.copy(alpha = .45f)),
        modifier = Modifier.heightIn(min = 48.dp).testTag("mine-copy-id").semantics { contentDescription = if (copied) "账户 ID 已复制" else "复制账户 ID" }) {
        Text(if (copied) "已复制" else "复制 ID", style = MaterialTheme.typography.labelMedium, color = Brand.Cyan)
    }
}

/** Light "续费 ›" link that opens the existing 套餐 tab; it never starts a purchase. */
@Composable
internal fun MineRenewLink(enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = GccSpacing.Small, vertical = 0.dp),
        modifier = Modifier.heightIn(min = 48.dp).testTag("mine-renew")) {
        Text("续费 ›", style = MaterialTheme.typography.labelLarge, color = if (enabled) Brand.Cyan else Brand.Muted)
    }
}

/** Small group label above a grouped glass card. */
@Composable
internal fun MineGroupLabel(title: String, tag: String) {
    // A small dark glass plate keeps the label readable over the busy backdrop artwork (review M4).
    Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Brand.Text,
        modifier = Modifier.padding(start = GccSpacing.ExtraSmall, top = GccSpacing.ExtraSmall).semantics { heading() }.testTag(tag)
            .background(Brand.Background.copy(alpha = .78f), GccShapes.medium)
            .padding(horizontal = GccSpacing.Medium, vertical = GccSpacing.ExtraSmall))
}

/** 官方频道 row: Telegram paper plane + @handle for a Telegram URL, otherwise a neutral link row. */
@Composable
internal fun MineOfficialChannelRow(url: String, opener: ((String) -> Unit)?, defaultOpener: (String) -> Unit) {
    var unavailable by remember { mutableStateOf(false) }
    val handle = telegramHandle(url)
    MineMenuRow("官方频道", if (handle != null) "mine-telegram" else "mine-channel", subtitle = handle) {
        unavailable = false
        try { (opener ?: defaultOpener)(url) } catch (_: Exception) { unavailable = true }
    }
    if (unavailable) com.gccwebos.android.design.SupportingText(
        if (handle != null) "暂时无法打开 Telegram 频道，请稍后重试。" else "暂时无法打开官方频道，请稍后重试。")
}
