package com.gccwebos.android.design

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class GlassMaterialPolicyTest {
    @Test fun api26SupportsStaticSofteningButLowRamAndExplicitSolidAlwaysFallBack() {
        assertEquals(GlassMode.Softened, GlassMaterialPolicy.mode(26, false, false))
        assertEquals(GlassMode.Softened, GlassMaterialPolicy.mode(33, false, false))
        assertEquals(GlassMode.Solid, GlassMaterialPolicy.mode(25, false, false))
        assertEquals(GlassMode.Solid, GlassMaterialPolicy.mode(36, true, false))
        assertEquals(GlassMode.Solid, GlassMaterialPolicy.mode(36, false, true))
    }

    @Test fun powerOfTwoSamplingBoundsBothPortraitAndLandscapeBelow360() {
        for ((width, expected) in listOf(360 to 1, 361 to 2, 719 to 2, 720 to 2, 721 to 4)) {
            assertEquals(expected, GlassMaterialPolicy.sampleSize(width, 1))
        }
        for ((width, height) in listOf(4001 to 2001, 2001 to 4001, 360 to 360, 361 to 1, 1 to 361, Int.MAX_VALUE to 1)) {
            val sample = GlassMaterialPolicy.sampleSize(width, height)
            assertEquals(0, sample and (sample - 1))
            assertTrue((width.toLong() + sample - 1) / sample <= 360)
            assertTrue((height.toLong() + sample - 1) / sample <= 360)
        }
    }

    @Test fun samplingRejectsMissingDimensionsInsteadOfDecodingFullAsset() {
        assertThrows(IllegalArgumentException::class.java) { GlassMaterialPolicy.sampleSize(-1, 10) }
        assertThrows(IllegalArgumentException::class.java) { GlassMaterialPolicy.sampleSize(10, 0) }
        assertThrows(IllegalArgumentException::class.java) { GlassMaterialPolicy.sampleSize(10, 10, 0) }
    }

    @Test fun clearOriginalDecodeBudgetIsBoundedToTheActualBundledArtwork() {
        assertTrue(GlassMaterialPolicy.clearDecodeAllowed(853, 1844))
        assertFalse(GlassMaterialPolicy.clearDecodeAllowed(854, 1844))
        assertFalse(GlassMaterialPolicy.clearDecodeAllowed(853, 1845))
        assertFalse(GlassMaterialPolicy.clearDecodeAllowed(Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(GlassMaterialPolicy.clearDecodeAllowed(-1, 1844))
        assertFalse(GlassMaterialPolicy.clearDecodeAllowed(853, 0))
    }

    @Test fun decodeFailurePendingOrMissingGeometryNeverEnablesTransparentFrost() {
        assertTrue(GlassMaterialPolicy.frosted(GlassMode.Softened, true, true, true))
        assertFalse(GlassMaterialPolicy.frosted(GlassMode.Solid, true, true, true))
        assertFalse(GlassMaterialPolicy.frosted(GlassMode.Softened, false, true, true))
        assertFalse(GlassMaterialPolicy.frosted(GlassMode.Softened, true, false, true))
        assertFalse(GlassMaterialPolicy.frosted(GlassMode.Softened, true, true, false))
    }

    @Test fun landscapeCropUsesActualWindowOriginAndKeepsAdjacentCardsAligned() {
        val upper = GlassMaterialPolicy.placement(400, 200, 10f, 20f, 200f, 200f, 10f, 20f)
        val lower = GlassMaterialPolicy.placement(400, 200, 10f, 20f, 200f, 200f, 110f, 120f)
        assertEquals(GlassMaterialPolicy.Placement(-100, 0, 400, 200), upper)
        assertEquals(GlassMaterialPolicy.Placement(-200, -100, 400, 200), lower)
    }

    @Test fun portraitCropAndMovingImeBoundsDoNotAssumeScreenOriginZero() {
        assertEquals(GlassMaterialPolicy.Placement(0, -100, 200, 400),
            GlassMaterialPolicy.placement(200, 400, 10f, 20f, 200f, 200f, 10f, 20f))
        assertEquals(GlassMaterialPolicy.Placement(0, -100, 200, 400),
            GlassMaterialPolicy.placement(200, 400, 30f, 60f, 200f, 200f, 30f, 60f))
    }

    @Test fun originalDimensionsControlCoverGeometryDespiteOddDecodedDimensions() {
        // 4001x2001 may decode to 251x126: cover must retain the original ratio, not 251/126.
        val original = GlassMaterialPolicy.placement(4001, 2001, 0f, 0f, 200f, 200f, 0f, 0f)!!
        val sampled = GlassMaterialPolicy.placement(251, 126, 0f, 0f, 200f, 200f, 0f, 0f)!!
        assertEquals(400, original.width)
        assertNotEquals(original.width, sampled.width)
    }

    @Test fun widerNavigationOrOutsideSurfaceMustUseOpaqueFallbackNotStretchCrop() {
        assertTrue(GlassMaterialPolicy.surfaceInsideBackdrop(10f, 20f, 680f, 1000f, 10f, 20f, 680f, 1000f))
        assertFalse(GlassMaterialPolicy.surfaceInsideBackdrop(40f, 20f, 680f, 1000f, 0f, 920f, 760f, 100f))
        assertFalse(GlassMaterialPolicy.surfaceInsideBackdrop(10f, 20f, 200f, 200f, 9f, 20f, 100f, 100f))
        assertFalse(GlassMaterialPolicy.surfaceInsideBackdrop(10f, 20f, 200f, 200f, 110f, 121f, 100f, 100f))
        assertFalse(GlassMaterialPolicy.surfaceInsideBackdrop(10f, 20f, 200f, 200f, 10f, 19.9f, 100f, 100f))
        assertFalse(GlassMaterialPolicy.surfaceInsideBackdrop(10f, 20f, 200f, 200f, 110.1f, 20f, 100f, 100f))
    }

    @Test fun invalidCropBoundsFailClosed() {
        assertNull(GlassMaterialPolicy.placement(0, 200, 0f, 0f, 200f, 200f, 0f, 0f))
        assertNull(GlassMaterialPolicy.placement(400, 200, 0f, 0f, Float.NaN, 200f, 0f, 0f))
        assertNull(GlassMaterialPolicy.placement(400, 200, 0f, 0f, 200f, 200f, Float.POSITIVE_INFINITY, 0f))
        assertFalse(GlassMaterialPolicy.surfaceInsideBackdrop(0f, 0f, 200f, 200f, 0f, 0f, 0f, 10f))
    }

    @Test fun softeningPreservesOpaqueConstantColorAndDoesNotMutateInput() {
        val original = IntArray(20) { 0xFF123456.toInt() }
        val output = softenBackdropArgb(original, 5, 4)
        assertArrayEquals(original, output)
        assertNotSame(original, output)
        output[0] = 0
        assertEquals(0xFF123456.toInt(), original[0])
    }

    @Test fun edgeClampingHasExactKernelValuesWithoutBlackFringe() {
        val output = softenBackdropArgb(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 3, 1, 1, 1)
        assertArrayEquals(IntArray(3) { 0xFF555555.toInt() }, output)
        assertArrayEquals(intArrayOf(0xFF123456.toInt()), softenBackdropArgb(intArrayOf(0xFF123456.toInt()), 1, 1))
    }

    @Test fun transparentHiddenColorCannotContaminateOpaqueNeighbor() {
        val result = softenBackdropArgb(intArrayOf(0x00FF0000, 0xFF0000FF.toInt(), 0x00FF0000), 3, 1, 1, 1)
        assertArrayEquals(IntArray(3) { 0x550000FF }, result)
        assertArrayEquals(IntArray(3), softenBackdropArgb(IntArray(3) { 0x00FF0000 }, 3, 1, 1, 1))
    }

    @Test fun zeroRadiusReturnsAnIndependentCopyAndInvalidWorkloadsAreRejected() {
        val original = intArrayOf(0xFFFFFFFF.toInt())
        assertNotSame(original, softenBackdropArgb(original, 1, 1, 0))
        assertThrows(IllegalArgumentException::class.java) { softenBackdropArgb(original, 2, 1) }
        assertThrows(IllegalArgumentException::class.java) { softenBackdropArgb(original, 1, 1, 9) }
        assertThrows(IllegalArgumentException::class.java) { softenBackdropArgb(original, 1, 1, 3, 0) }
        assertThrows(IllegalArgumentException::class.java) { softenBackdropArgb(IntArray(361 * 360), 361, 360) }
    }

    @Test fun disabledAndPaymentSurfacesStayOpaqueWithReadableText() {
        for (surface in listOf(GlassMaterialPolicy.CardSolidArgb, GlassMaterialPolicy.NavigationSolidArgb,
            GlassMaterialPolicy.DisabledArgb, GlassMaterialPolicy.PaymentArgb, GlassMaterialPolicy.PaymentDisabledArgb)) {
            assertEquals(255L, surface ushr 24)
        }
        assertTrue(contrast(GlassMaterialPolicy.DisabledTextArgb, GlassMaterialPolicy.DisabledArgb) >= 4.5)
        assertTrue(contrast(GlassMaterialPolicy.PaymentDisabledTextArgb, GlassMaterialPolicy.PaymentDisabledArgb) >= 4.5)
        assertTrue(contrast(0xFFFFFFFFL, GlassMaterialPolicy.PaymentArgb) >= 4.5)
    }

    private fun contrast(a: Long, b: Long): Double {
        fun luminance(color: Long): Double {
            fun linear(shift: Int): Double {
                val channel = ((color ushr shift) and 255) / 255.0
                return if (channel <= .04045) channel / 12.92 else ((channel + .055) / 1.055).pow(2.4)
            }
            return .2126 * linear(16) + .7152 * linear(8) + .0722 * linear(0)
        }
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
    }
}
