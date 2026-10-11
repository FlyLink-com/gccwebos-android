package com.gccwebos.android.design.dialog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gccwebos.android.design.Brand
import com.gccwebos.android.design.GccSemantic

/**
 * The shared dropdown: raised navy surface with a glass rim and 14 dp corners instead of Material's default grey.
 * Fill it with [GccMenuItem] rows.
 */
@Composable
internal fun GccMenu(expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier,
                     content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(expanded, onDismissRequest, modifier = modifier,
        shape = RoundedCornerShape(14.dp), containerColor = Brand.SurfaceRaised,
        tonalElevation = 0.dp, shadowElevation = 8.dp,
        border = BorderStroke(1.dp, Brand.GlassBorder.copy(alpha = .30f)), content = content)
}

/**
 * One 48 dp menu row. [destructive] items (删除, 移除) use the danger red for text and icon. [leadingIcon] is any
 * composable (keep it ~20 dp and tint it with the colour you are given by the caller).
 */
@Composable
internal fun GccMenuItem(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                         destructive: Boolean = false, enabled: Boolean = true, testTag: String? = null,
                         leadingIcon: (@Composable () -> Unit)? = null) {
    val color = if (destructive) GccSemantic.Danger else Brand.Text
    DropdownMenuItem(text = { Text(label, fontWeight = FontWeight.Medium) }, onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        enabled = enabled, leadingIcon = leadingIcon,
        colors = MenuDefaults.itemColors(textColor = color, leadingIconColor = color,
            disabledTextColor = Brand.DisabledText, disabledLeadingIconColor = Brand.DisabledText))
}
