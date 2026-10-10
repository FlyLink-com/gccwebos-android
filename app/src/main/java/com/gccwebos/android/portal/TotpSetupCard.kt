package com.gccwebos.android.portal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gccwebos.android.design.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 绑定身份验证器: scan or type the key into an authenticator app, then enter the 6-digit code.
 * The key is shown only to the signed-in owner while this card is open, is never logged, and the
 * clipboard is only ever written (flagged sensitive where the OS supports it), never read.
 */
@Composable
internal fun TotpSetupCard(state: AccountController, setup: TotpEnrollment) {
    val context = LocalContext.current
    var notice by remember(setup) { mutableStateOf<String?>(null) }
    val qr = remember(setup.otpauthUri) {
        runCatching { QRCodeWriter().encode(setup.otpauthUri, BarcodeFormat.QR_CODE, 0, 0) }.getOrNull()
    }
    SectionCard(Modifier.testTag("totp-setup"), verticalSpacing = GccSpacing.Medium) {
        Text("绑定身份验证器", style = MaterialTheme.typography.titleMedium, color = Brand.Text,
            modifier = Modifier.testTag("totp-setup-title"))
        Text("1. 在手机上打开 Google Authenticator、Microsoft Authenticator 等身份验证器，扫描下方二维码，或手动输入密钥。",
            style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
        if (qr != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(184.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(10.dp)
                    .semantics { contentDescription = "绑定二维码" }.testTag("totp-qr")) {
                    Canvas(Modifier.size(164.dp)) {
                        val n = qr.width; val cell = size.width / n
                        for (y in 0 until qr.height) for (x in 0 until n) if (qr[x, y]) {
                            drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + .5f, cell + .5f))
                        }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(GccSpacing.ExtraSmall)) {
            Text("手动输入密钥", style = MaterialTheme.typography.labelMedium, color = Brand.Muted)
            Text(setup.manualKey.chunked(4).joinToString(" "), style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace, color = Brand.Text, modifier = Modifier.testTag("totp-manual-key"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(GccSpacing.Small)) {
            TextButton(onClick = {
                notice = try {
                    val manager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("身份验证器密钥", setup.manualKey)
                    if (android.os.Build.VERSION.SDK_INT >= 33) clip.description.extras =
                        android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                    manager.setPrimaryClip(clip); "密钥已复制，请粘贴到身份验证器后清除剪贴板"
                } catch (_: Exception) { "复制失败，请手动输入密钥" }
            }, modifier = Modifier.heightIn(min = 48.dp).testTag("totp-copy-key")) { Text("复制密钥") }
            TextButton(onClick = {
                notice = try {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(setup.otpauthUri)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); null
                } catch (_: Exception) { "没有找到可用的身份验证器，请先安装后扫码或手动输入" }
            }, modifier = Modifier.heightIn(min = 48.dp).testTag("totp-open-app")) { Text("在身份验证器中打开") }
        }
        notice?.let { SupportingText(it, Modifier.testTag("totp-notice")) }
        Text("2. 输入身份验证器里显示的 6 位验证码，完成绑定。密钥 5 分钟内有效。",
            style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
        OutlinedTextField(value = state.totpCode, onValueChange = { v -> state.totpCode = v.filter(Char::isDigit).take(6) },
            label = { Text("6 位验证码") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth().testTag("totp-setup-code"))
        state.totpError?.let { SupportingText(it, Modifier.testTag("totp-setup-error")) }
        BrandButton("确认绑定", state::confirmTotpSetup, Modifier.fillMaxWidth().testTag("totp-setup-confirm"),
            enabled = !state.busy && state.totpCode.length == 6)
        TextButton(onClick = state::cancelTotpSetup, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("totp-setup-cancel")) { Text("取消") }
    }
}
