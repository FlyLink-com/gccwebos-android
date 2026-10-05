package com.gccwebos.android.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gccwebos.android.R

/** Device emoji only; this badge describes the name's label, not measured endpoint geography. */
@Composable
internal fun NodeRegionIcon(name: String? = null, modifier: Modifier = Modifier,
    size: Dp = 30.dp, emphasized: Boolean = false) {
    val region = remember(name) { name?.let(::nodeRegion) }
    val shape = RoundedCornerShape(if (emphasized) 16.dp else 13.dp)
    val glyphSize = size * .64f
    val fontSize = with(LocalDensity.current) { glyphSize.toSp() }
    Box(modifier.size(size)
        .background(Brand.Cyan.copy(alpha = if (emphasized) .10f else .07f), shape)
        .then(if (emphasized) Modifier.border(1.dp, Brand.Cyan.copy(alpha = .26f), shape) else Modifier)
        .clearAndSetSemantics { contentDescription = region?.let { "节点标注地区：${it.displayName}" } ?: "地区未标注" },
        contentAlignment = Alignment.Center) {
        if (region == null) Icon(painterResource(R.drawable.brand_globe), contentDescription = null,
            modifier = Modifier.size(glyphSize), tint = if (emphasized) Brand.Cyan else Brand.Muted)
        else Text(region.emoji, fontSize = fontSize, lineHeight = fontSize)
    }
}
