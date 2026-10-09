package com.gccwebos.android.commerce

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gccwebos.android.design.Brand

/**
 * Opaque list container for order/ledger screens so city backdrop text does not bleed through.
 * Solid Brand.Surface only — no extra blur, scoped to history screens.
 */
@Composable
internal fun HistoryListCard(modifier: Modifier = Modifier,
                             contentPadding: PaddingValues = PaddingValues(14.dp),
                             content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(), shape = Brand.Shape, color = Brand.Surface,
        border = BorderStroke(1.dp, Brand.GlassBorder.copy(alpha = .38f))) {
        Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
