package com.gccwebos.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gccwebos.android.design.AccountUsageState
import com.gccwebos.android.design.GlassSurface
import java.math.BigDecimal
import java.math.RoundingMode

/** Connection (home) page spacing. Side padding (16 dp) is owned by the page column in MainActivity. */
internal object HomeSpacing {
    val SidePadding: Dp = 16.dp
    val CardGap: Dp = 12.dp
    val CardPadding: Dp = 16.dp
    val SectionGap: Dp = 12.dp
    /** Minimum clearance between the last home card and the floating bottom navigation. */
    val BottomInset: Dp = 20.dp
    /** The page column already pads its bottom by this much (compact pages, MainActivity). */
    val PageBottomPadding: Dp = 12.dp
}

/** Extra space the home column adds after its last card so the total clearance reaches [HomeSpacing.BottomInset]. */
internal fun homeBottomSpacer(pageBottomPadding: Dp = HomeSpacing.PageBottomPadding,
                              required: Dp = HomeSpacing.BottomInset): Dp =
    if (pageBottomPadding >= required) 0.dp else required - pageBottomPadding

/** Deep slate wash (~75%) over the shared glass material, so the backdrop keeps only its colour, not its detail. */
internal val HomeCardFill = Color(0xFF0B1426).copy(alpha = .75f)
internal val HomeCardEdge = Color.White.copy(alpha = .12f)

/** One home zone: opaque-ish dark card, subtle 1 dp rim, consistent padding and gaps. Static (no blur, no motion). */
@Composable
internal fun HomeCard(modifier: Modifier = Modifier, edge: Color = HomeCardEdge,
                      content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(modifier.fillMaxWidth(), edge = BorderStroke(1.dp, edge), tint = SolidColor(HomeCardFill)) {
        Column(Modifier.fillMaxWidth().padding(HomeSpacing.CardPadding),
            verticalArrangement = Arrangement.spacedBy(HomeSpacing.SectionGap), content = content)
    }
}

/**
 * Right-hand usage summary of the plan card. A confirmed progress fraction is shown as a percentage
 * (the used GB stays in the spoken description); without one the existing used amount is shown as-is,
 * including "暂未获取". Display-only.
 */
internal fun connectionUsedSummary(usedText: String, progress: Float?): String =
    progress?.takeIf { it.isFinite() }?.let { "已用 ${connectionUsedPercent(it)}" } ?: "已用 $usedText"

/** 0.762f -> "76.2%"; a non-zero amount below 0.05% is "<0.1%" rather than a misleading "0%". */
internal fun connectionUsedPercent(progress: Float): String {
    val fraction = progress.coerceIn(0f, 1f)
    // Through the float's decimal string so 0.762f is 76.2, not 76.19999.
    val percent = BigDecimal(fraction.toString()).movePointRight(2).setScale(1, RoundingMode.HALF_UP)
    if (fraction > 0f && percent.signum() == 0) return "<0.1%"
    return percent.stripTrailingZeros().toPlainString() + "%"
}

internal fun connectionUsedDescription(usedText: String, progress: Float?): String =
    progress?.takeIf { it.isFinite() }?.let { "已用 $usedText，${connectionUsedPercent(it)}" } ?: "已用 $usedText"

/** "续费" only routes to the existing 套餐 tab, and only for a verified signed-in account plan (never an imported source). */
internal fun connectionRenewVisible(authenticated: Boolean, manualSource: Boolean, state: AccountUsageState,
                                    hasPlansRoute: Boolean = true): Boolean =
    hasPlansRoute && authenticated && !manualSource && state == AccountUsageState.Available

/** Node info is muted until a tunnel is established; connecting/disconnecting keep it readable but quiet. */
internal fun connectionNodeEmphasized(phase: ConnectionPhase): Boolean = phase == ConnectionPhase.Connected
