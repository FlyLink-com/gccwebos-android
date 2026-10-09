package com.gccwebos.android.commerce

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gccwebos.android.design.Brand
import com.gccwebos.android.design.BrandButton
import com.gccwebos.android.design.BrandCard
import com.gccwebos.android.design.SupportingText

/**
 * Signed-out 套餐 page. Plan names and prices come only from the signed-in catalog read, so nothing
 * here is a price or a tier: the grey bars are an inert layout placeholder, never data. Login gates
 * the catalog, checkout and 「我的套餐」; importing an existing subscription needs no account.
 */
@Composable
internal fun SignedOutPlansPanel(loginEnabled: Boolean, onLogin: () -> Unit, onImport: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("commerce-signed-out"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BrandCard {
            Text("浏览全部套餐", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("anonymous-plans-title"))
            SupportingText("登录后查看各档套餐、价格与差异，并可下单支付；已购买的会显示在「我的套餐」。",
                Modifier.testTag("anonymous-plans-hint"))
            BrandButton("登录后浏览全部套餐", onLogin, enabled = loginEnabled,
                modifier = Modifier.padding(top = 4.dp).testTag("anonymous-plans-login"))
        }
        BrandCard(Modifier.testTag("anonymous-plans-placeholder")) {
            Text("套餐与价格", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            repeat(2) { PlaceholderPlanRow() }
            SupportingText("登录后在这里显示各档套餐与价格")
        }
        BrandCard(Modifier.testTag("anonymous-plans-import-card")) {
            Text("已有订阅链接？", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            SupportingText("导入配置不需要登录，可直接在节点页添加并连接。")
            TextButton(onClick = onImport, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("anonymous-plans-import")) { Text("去导入配置") }
        }
    }
}

@Composable
private fun PlaceholderPlanRow() {
    val bar = Color.White.copy(alpha = .10f)
    Row(Modifier.fillMaxWidth().clearAndSetSemantics {}.background(Color.White.copy(alpha = .04f), RoundedCornerShape(14.dp))
        .padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(96.dp).height(12.dp).background(bar, RoundedCornerShape(6.dp)))
            Box(Modifier.width(150.dp).height(9.dp).background(bar.copy(alpha = .07f), RoundedCornerShape(5.dp)))
        }
        Box(Modifier.width(56.dp).height(16.dp).background(bar, RoundedCornerShape(6.dp)))
    }
}
