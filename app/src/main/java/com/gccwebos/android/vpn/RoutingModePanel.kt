package com.gccwebos.android.vpn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gccwebos.android.design.BrandCard
import com.gccwebos.android.design.SupportingText

/** Minimal product control: 全球 (default) vs 规则 (LAN/private DIRECT). */
@Composable
internal fun RoutingModePanel(
    current: RoutingMode,
    onSelect: (RoutingMode) -> Unit,
    connected: Boolean = false,
) {
    var selected by remember(current) { mutableStateOf(current) }
    val cardModifier = Modifier.testTag("routing-mode-panel")
    BrandCard(cardModifier) {
        Text("路由模式", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        SupportingText("全球：全部流量经所选节点。规则：局域网/私网直连，其余经所选节点。")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selected == RoutingMode.Global,
                onClick = {
                    selected = RoutingMode.Global
                    onSelect(RoutingMode.Global)
                },
                label = { Text("全球") },
                modifier = Modifier.heightIn(min = 48.dp).testTag("routing-mode-global"),
            )
            FilterChip(
                selected = selected == RoutingMode.Rule,
                onClick = {
                    selected = RoutingMode.Rule
                    onSelect(RoutingMode.Rule)
                },
                label = { Text("规则") },
                modifier = Modifier.heightIn(min = 48.dp).testTag("routing-mode-rule"),
            )
        }
        if (connected) SupportingText("切换后将重建连接以应用新规则。")
    }
}
