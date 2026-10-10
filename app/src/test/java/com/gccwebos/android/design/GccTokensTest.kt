package com.gccwebos.android.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gccwebos.android.commerce.MineStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class GccTokensTest {
    @Test fun spacingScaleIsFourEightTwelveSixteenTwentyFour() {
        assertEquals(4.dp, GccSpacing.ExtraSmall)
        assertEquals(8.dp, GccSpacing.Small)
        assertEquals(12.dp, GccSpacing.Medium)
        assertEquals(16.dp, GccSpacing.Large)
        assertEquals(24.dp, GccSpacing.ExtraLarge)
    }

    @Test fun shapesAreSmallEightMediumTwelveCardTwentyAndPill() {
        assertEquals(8.dp, GccShapes.SmallRadius)
        assertEquals(12.dp, GccShapes.MediumRadius)
        assertEquals(20.dp, GccShapes.CardRadius)
        assertEquals(RoundedCornerShape(8.dp), GccShapes.small)
        assertEquals(RoundedCornerShape(12.dp), GccShapes.medium)
        assertEquals(RoundedCornerShape(20.dp), GccShapes.card)
        assertEquals(RoundedCornerShape(percent = 50), GccShapes.pill)
    }

    @Test fun brandAmberAndMineGoldAreTheSameSemanticGold() {
        val gold = Color(0xFFFFCB73)
        assertEquals(gold, GccSemantic.Gold)
        assertEquals(GccSemantic.Gold, Brand.Amber)
        assertEquals(GccSemantic.Gold, MineStyle.Gold)
        assertEquals(Color(0xFFE59A2F), GccSemantic.GoldDeep)
        assertEquals(GccSemantic.GoldDeep, MineStyle.GoldDeep)
        assertEquals(Color(0xFFFF5D5D), GccSemantic.Danger)
        assertEquals(Color(0xFFFF5D5D), GccSemantic.Latency)
    }

    @Test fun spacingSnapsToTheScaleAndTiesRoundUp() {
        assertEquals(4, gccSnapSpacing(2))
        assertEquals(4, gccSnapSpacing(4))
        assertEquals(4, gccSnapSpacing(5))
        assertEquals(8, gccSnapSpacing(6))
        assertEquals(8, gccSnapSpacing(8))
        assertEquals(8, gccSnapSpacing(9))
        assertEquals(12, gccSnapSpacing(10))
        assertEquals(12, gccSnapSpacing(12))
        assertEquals(16, gccSnapSpacing(14))
        assertEquals(16, gccSnapSpacing(16))
        assertEquals(24, gccSnapSpacing(20))
        assertEquals(24, gccSnapSpacing(24))
    }

    @Test fun shapeSnapsToSmallMediumOrCardAndTiesRoundUp() {
        assertEquals(8, gccSnapShape(9))
        assertEquals(12, gccSnapShape(10))
        assertEquals(12, gccSnapShape(14))
        assertEquals(20, gccSnapShape(16))
        assertEquals(20, gccSnapShape(20))
        assertEquals(20, gccSnapShape(22))
    }

    @Test fun typeExtrasKeepCurrentMineSizes() {
        assertEquals(38.sp, GccType.Display.fontSize)
        assertEquals(46.sp, GccType.Display.lineHeight)
        assertEquals(FontWeight.ExtraBold, GccType.Display.fontWeight)
        assertEquals(30.sp, GccType.DisplayCompact.fontSize)
        assertEquals(18.sp, GccType.Identity.fontSize)
        assertEquals(24.sp, GccType.Identity.lineHeight)
        assertEquals((64f * .36f).sp, GccType.avatarGlyph(64.dp).fontSize)
    }

    @Test fun glassCardAlphasStayAtTheRaisedPolicyValues() {
        assertEquals(.84f, GlassMaterialPolicy.CardStartAlpha)
        assertEquals(.88f, GlassMaterialPolicy.CardEndAlpha)
    }
}
