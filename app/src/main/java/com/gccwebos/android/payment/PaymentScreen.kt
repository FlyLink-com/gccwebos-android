package com.gccwebos.android.payment

import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.gccwebos.android.design.Brand
import com.gccwebos.android.design.BrandButton
import com.gccwebos.android.design.GccShapes
import com.gccwebos.android.design.GccSpacing
import com.gccwebos.android.design.SupportingText
import com.gccwebos.android.design.rememberUiMotionEnabled

/** Plain-language notices; none of them names a URL, order number or amount (no host or domain is ever shown). */
internal object PaymentCopy {
    const val Title = "支付宝付款"
    const val Loading = "正在前往支付宝…"
    const val AlipayMissing = "没有找到支付宝 App。你可以在当前页面选择其他付款方式或扫码支付，也可以先安装支付宝。"
    const val HandedOff = "已打开支付宝。付款完成后返回本应用，到账状态会自动刷新。"
    const val BlockedOrigin = "为保护支付安全，已阻止页面跳转到本次付款流程之外的网站。如果无法继续，可点右上角“浏览器打开”。"
    fun failure(kind: PaymentFailure) = when (kind) {
        PaymentFailure.Network -> "付款页面打开失败，请检查网络后重试。订单会保留，请不要重复创建充值。"
        PaymentFailure.Insecure -> "无法确认付款页面的安全性，已停止加载。请稍后重试，或返回应用查询订单。"
        PaymentFailure.Interrupted -> "付款页面意外中断。订单会保留，请重试或返回应用查询。"
        PaymentFailure.Blocked -> "付款页面要跳转到不在本次付款流程内的网站（或跳转次数过多），为保护支付安全已阻止，所以页面无法在应用内继续。订单会保留，请点右上角“浏览器打开”继续付款，付款后回到应用查询。"
        PaymentFailure.Unavailable -> "这台设备上无法使用内置付款页面。订单会保留，可点右上角“浏览器打开”继续付款。"
    }
}

@Composable
internal fun PaymentScreen(state: PaymentPageState, webView: WebView?, onClose: () -> Unit, onRetry: () -> Unit,
                           onOpenInBrowser: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Brand.Background).systemBarsPadding().testTag("payment-screen")) {
        PaymentTopBar(onClose, onOpenInBrowser)
        // Above the page, not over it, so a notice never covers the gateway's own buttons or QR code.
        Column(Modifier.fillMaxWidth().padding(horizontal = GccSpacing.Medium), verticalArrangement = Arrangement.spacedBy(GccSpacing.Small)) {
            if (state.alipayMissing) Notice(PaymentCopy.AlipayMissing, "payment-alipay-missing", Brand.Amber)
            else if (state.handedOff) Notice(PaymentCopy.HandedOff, "payment-handed-off", Brand.Green)
            if (state.blockedOrigin) Notice(PaymentCopy.BlockedOrigin, "payment-blocked", Brand.Muted)
        }
        PaymentBody(state, webView, onClose, onRetry, onOpenInBrowser, Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
private fun PaymentBody(state: PaymentPageState, webView: WebView?, onClose: () -> Unit, onRetry: () -> Unit,
                        onOpenInBrowser: () -> Unit, modifier: Modifier) {
    Box(modifier) {
            if (webView != null) AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize().testTag("payment-web"))
            val motion = rememberUiMotionEnabled()
            // Fades only when the system allows animation; otherwise it appears and disappears at once.
            AnimatedVisibility(visible = !state.pageShown && state.failure == null,
                enter = EnterTransition.None, exit = if (motion) fadeOut(tween(160)) else ExitTransition.None) {
                LoadingOverlay(state.progress)
            }
            AnimatedVisibility(visible = state.failure != null, enter = if (motion) fadeIn(tween(120)) else EnterTransition.None,
                exit = ExitTransition.None) {
                state.failure?.let { FailureOverlay(it, onRetry = onRetry, onOpenInBrowser = onOpenInBrowser, onClose = onClose) }
            }
    }
}

@Composable
private fun PaymentTopBar(onClose: () -> Unit, onOpenInBrowser: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("payment-topbar")) {
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart).size(48.dp).testTag("payment-back")
            .semantics { contentDescription = "返回应用" }) {
            Canvas(Modifier.size(18.dp)) {
                val w = size.width; val h = size.height
                val stroke = 2.2.dp.toPx()
                drawLine(Brand.Text, Offset(w * .66f, h * .14f), Offset(w * .30f, h * .5f), stroke, StrokeCap.Round)
                drawLine(Brand.Text, Offset(w * .30f, h * .5f), Offset(w * .66f, h * .86f), stroke, StrokeCap.Round)
            }
        }
        Text(PaymentCopy.Title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Brand.Text,
            textAlign = TextAlign.Center, maxLines = 1,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 96.dp).semantics { heading() }.testTag("payment-title"))
        TextButton(onClick = onOpenInBrowser, contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.align(Alignment.CenterEnd).heightIn(min = 48.dp).testTag("payment-browser")) {
            Text("浏览器打开", style = MaterialTheme.typography.labelLarge, color = Brand.Cyan)
        }
    }
}

@Composable
private fun Notice(text: String, tag: String, accent: androidx.compose.ui.graphics.Color) {
    Box(Modifier.fillMaxWidth().background(Brand.SurfaceRaised, GccShapes.medium).padding(GccSpacing.Medium)
        .semantics { liveRegion = LiveRegionMode.Polite }.testTag(tag)) {
        Text(text, color = accent, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LoadingOverlay(progress: Int) {
    Column(Modifier.fillMaxSize().background(Brand.Background).padding(GccSpacing.ExtraLarge).testTag("payment-loading"),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(PaymentCopy.Loading, color = Brand.Text, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        // Determinate, so there is no endless animation to suppress when the system removes animations.
        LinearProgressIndicator(progress = { progress.coerceIn(0, 100) / 100f }, color = Brand.Cyan, trackColor = Brand.SurfaceRaised,
            modifier = Modifier.padding(top = GccSpacing.Large).fillMaxWidth(.6f).testTag("payment-progress"))
    }
}

@Composable
private fun FailureOverlay(kind: PaymentFailure, onRetry: () -> Unit, onOpenInBrowser: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Brand.Background).padding(GccSpacing.ExtraLarge).testTag("payment-error"),
        verticalArrangement = Arrangement.spacedBy(GccSpacing.Large, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("付款页面无法打开", color = Brand.Text, style = MaterialTheme.typography.titleMedium)
        SupportingText(PaymentCopy.failure(kind))
        if (kind == PaymentFailure.Blocked) BrandButton("浏览器打开", onOpenInBrowser, Modifier.testTag("payment-error-browser"))
        else if (kind != PaymentFailure.Unavailable) BrandButton("重试", onRetry, Modifier.testTag("payment-retry"))
        TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag("payment-close")) {
            Text("返回应用", color = Brand.Cyan)
        }
    }
}
