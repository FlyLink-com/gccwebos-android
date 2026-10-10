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
 * Signed-out 套餐 page. Plan names and prices come only from a server catalog read, so nothing here
 * is a price or a tier. [publicOffers] is the data-driven hook: while it is empty (no public catalog
 * yet) an honest empty state is shown; once a public read-only catalog supplies verified offers the
 * same list renderer used when signed in shows them, read-only (checkout still needs a login).
 * Importing an existing subscription needs no account.
 */
@Composable
internal fun SignedOutPlansPanel(loginEnabled: Boolean, onLogin: () -> Unit, onImport: () -> Unit,
                                 publicOffers: List<Offer> = emptyList()) {
    Column(Modifier.fillMaxWidth().testTag("commerce-signed-out"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BrandCard {
            Text("浏览全部套餐", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("anonymous-plans-title"))
            SupportingText("登录后可下单支付；已购买的会显示在「我的套餐」。", Modifier.testTag("anonymous-plans-hint"))
            BrandButton("登录后浏览全部套餐", onLogin, enabled = loginEnabled,
                modifier = Modifier.padding(top = 4.dp).testTag("anonymous-plans-login"))
        }
        if (publicOffers.isEmpty()) {
            BrandCard(Modifier.testTag("anonymous-plans-empty")) {
                Text("套餐与价格", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("价格登录后显示", style = MaterialTheme.typography.titleSmall, color = Brand.PriceAccent,
                    modifier = Modifier.testTag("anonymous-plans-empty-title"))
                SupportingText("各档套餐的流量、时长与价格会在登录后列出。")
            }
        } else {
            Column(Modifier.testTag("anonymous-plans-public")) {
                CatalogOfferList(publicOffers, selected = null, enabled = false,
                    category = publicOffers.first().category, onSelect = {})
            }
        }
        BrandCard(Modifier.testTag("anonymous-plans-import-card")) {
            Text("已有订阅链接？", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            SupportingText("导入配置不需要登录，可直接在节点页添加并连接。")
            TextButton(onClick = onImport, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("anonymous-plans-import")) { Text("去导入配置") }
        }
    }
}
