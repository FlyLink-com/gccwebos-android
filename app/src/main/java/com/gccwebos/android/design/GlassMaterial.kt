package com.gccwebos.android.design

import android.app.ActivityManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gccwebos.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

internal enum class GlassMode { Softened, Solid }
internal enum class GlassSurfaceKind { Card, Navigation }

/** Pure rules; no device, preference, account or network state is changed. */
internal object GlassMaterialPolicy {
    const val MaxBackdropDimension = 360
    // The bundled brand_lounge.webp is 853x1844 (1,572,932 px); never silently decode a larger replacement.
    const val MaxClearBackdropDimension = 1844
    const val MaxClearBackdropPixels = 1_572_932L
    const val CardStartArgb = 0xFF112540L
    const val CardEndArgb = 0xFF181F3AL
    const val CardSolidArgb = 0xFF162A46L
    const val NavigationStartArgb = 0xFF182A43L
    const val NavigationEndArgb = 0xFF1E223FL
    const val NavigationSolidArgb = 0xFF172B48L
    const val CardStartAlpha = .72f
    const val CardEndAlpha = .76f
    const val NavigationStartAlpha = .76f
    const val NavigationEndAlpha = .78f
    const val DisabledArgb = 0xFF263B58L
    const val DisabledTextArgb = 0xFFBDCADCL
    const val PaymentArgb = 0xFF086CC4L
    const val PaymentDisabledArgb = 0xFF21486CL
    const val PaymentDisabledTextArgb = 0xFFBED0E3L

    fun mode(apiLevel: Int, lowRam: Boolean, forceSolid: Boolean): GlassMode =
        if (apiLevel < 26 || lowRam || forceSolid) GlassMode.Solid else GlassMode.Softened

    fun clearDecodeAllowed(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width <= MaxClearBackdropDimension && height <= MaxClearBackdropDimension &&
            width.toLong() * height <= MaxClearBackdropPixels

    fun frosted(mode: GlassMode, clearReady: Boolean, softenedReady: Boolean, insideBounds: Boolean): Boolean =
        mode == GlassMode.Softened && clearReady && softenedReady && insideBounds

    fun sampleSize(width: Int, height: Int, maximum: Int = MaxBackdropDimension): Int {
        require(width > 0 && height > 0 && maximum > 0)
        var sample = 1
        while ((max(width, height).toLong() + sample - 1) / sample > maximum) {
            require(sample <= Int.MAX_VALUE / 2)
            sample *= 2
        }
        return sample
    }

    data class Placement(val left: Int, val top: Int, val width: Int, val height: Int)

    fun surfaceInsideBackdrop(backdropLeft: Float, backdropTop: Float, backdropWidth: Float,
                              backdropHeight: Float, surfaceLeft: Float, surfaceTop: Float,
                              surfaceWidth: Float, surfaceHeight: Float): Boolean =
        listOf(backdropLeft, backdropTop, backdropWidth, backdropHeight, surfaceLeft, surfaceTop,
            surfaceWidth, surfaceHeight).all { it.isFinite() } &&
            backdropWidth > 0 && backdropHeight > 0 && surfaceWidth > 0 && surfaceHeight > 0 &&
            surfaceLeft >= backdropLeft && surfaceTop >= backdropTop &&
            surfaceLeft + surfaceWidth <= backdropLeft + backdropWidth &&
            surfaceTop + surfaceHeight <= backdropTop + backdropHeight

    /** The same centred cover crop as BrandBackdrop, translated into the surface's coordinates. */
    fun placement(imageWidth: Int, imageHeight: Int, backdropLeft: Float, backdropTop: Float,
                  backdropWidth: Float, backdropHeight: Float, surfaceLeft: Float, surfaceTop: Float): Placement? {
        if (imageWidth <= 0 || imageHeight <= 0 || backdropWidth <= 0 || backdropHeight <= 0 ||
            !listOf(backdropLeft, backdropTop, backdropWidth, backdropHeight, surfaceLeft, surfaceTop).all { it.isFinite() }) return null
        val scale = max(backdropWidth / imageWidth, backdropHeight / imageHeight)
        val width = imageWidth * scale
        val height = imageHeight * scale
        if (!width.isFinite() || !height.isFinite() || width < 1 || height < 1) return null
        return Placement(
            (backdropLeft + (backdropWidth - width) / 2 - surfaceLeft).roundToInt(),
            (backdropTop + (backdropHeight - height) / 2 - surfaceTop).roundToInt(),
            width.roundToInt(), height.roundToInt())
    }
}

/** Bounded two-axis box filter for a static bundled image, never a screenshot of app content. */
internal fun softenBackdropArgb(input: IntArray, width: Int, height: Int,
                                radius: Int = 3, passes: Int = 2): IntArray {
    require(width > 0 && height > 0 && width.toLong() * height == input.size.toLong())
    require(input.size <= GlassMaterialPolicy.MaxBackdropDimension * GlassMaterialPolicy.MaxBackdropDimension)
    require(radius in 0..8 && passes in 1..3)
    var pixels = input.copyOf()
    if (radius == 0) return pixels
    fun filter(source: IntArray, horizontal: Boolean): IntArray {
        val output = IntArray(source.size)
        val span = radius * 2 + 1
        for (y in 0 until height) for (x in 0 until width) {
            var a = 0; var r = 0; var g = 0; var b = 0
            for (delta in -radius..radius) {
                val sx = if (horizontal) (x + delta).coerceIn(0, width - 1) else x
                val sy = if (horizontal) y else (y + delta).coerceIn(0, height - 1)
                val pixel = source[sy * width + sx]
                val alpha = pixel ushr 24
                a += alpha; r += ((pixel ushr 16) and 255) * alpha
                g += ((pixel ushr 8) and 255) * alpha; b += (pixel and 255) * alpha
            }
            // Average premultiplied channels; fully transparent hidden RGB cannot create a fringe.
            output[y * width + x] = if (a == 0) 0 else ((a / span) shl 24) or
                ((r / a) shl 16) or ((g / a) shl 8) or (b / a)
        }
        return output
    }
    repeat(passes) { pixels = filter(filter(pixels, true), false) }
    return pixels
}

internal class GlassBackdropState {
    var bounds by mutableStateOf<Rect?>(null)
    var original by mutableStateOf<ImageBitmap?>(null)
    var softened by mutableStateOf<SoftBackdrop?>(null)
    var unavailable by mutableStateOf(false)
}

internal data class SoftBackdrop(val image: ImageBitmap, val originalWidth: Int, val originalHeight: Int)
private data class StaticBackdropAsset(val original: ImageBitmap, val softened: SoftBackdrop?)

internal val LocalGlassMode = staticCompositionLocalOf { GlassMode.Solid }
private val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdropState?> { null }

@Composable
internal fun ProvideGlassMaterial(mode: GlassMode?, content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val lowRam = remember(context) {
        // Unknown device capabilities take the inexpensive opaque path.
        context.getSystemService(ActivityManager::class.java)?.isLowRamDevice ?: true
    }
    val selected = GlassMaterialPolicy.mode(Build.VERSION.SDK_INT, lowRam, mode == GlassMode.Solid)
    val state = remember { GlassBackdropState() }
    CompositionLocalProvider(LocalGlassMode provides selected, LocalGlassBackdrop provides state, content = content)
}

/** One process-local original/softened pair (~6 MiB for the original), never per-card or unbounded history. */
private object SoftBackdropCache {
    private val mutex = Mutex()
    private var attempted = false
    private var image: StaticBackdropAsset? = null

    suspend fun get(resources: Resources): StaticBackdropAsset? = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (!attempted) {
                attempted = true
                image = try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
                    BitmapFactory.decodeResource(resources, R.drawable.brand_lounge, bounds)
                    if (!GlassMaterialPolicy.clearDecodeAllowed(bounds.outWidth, bounds.outHeight)) return@withLock null
                    val original = BitmapFactory.decodeResource(resources, R.drawable.brand_lounge,
                        BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 })
                        ?: return@withLock null
                    var originalPublished = false
                    try {
                        if (!GlassMaterialPolicy.clearDecodeAllowed(original.width, original.height)) return@withLock null
                        val softened = try {
                            val options = BitmapFactory.Options().apply {
                                inSampleSize = GlassMaterialPolicy.sampleSize(bounds.outWidth, bounds.outHeight)
                                inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888
                            }
                            val decoded = BitmapFactory.decodeResource(resources, R.drawable.brand_lounge, options)
                            if (decoded == null) null else try {
                                if (decoded.width > GlassMaterialPolicy.MaxBackdropDimension || decoded.height > GlassMaterialPolicy.MaxBackdropDimension) null
                                else {
                                    val pixels = IntArray(decoded.width * decoded.height)
                                    decoded.getPixels(pixels, 0, decoded.width, 0, 0, decoded.width, decoded.height)
                                    SoftBackdrop(Bitmap.createBitmap(softenBackdropArgb(pixels, decoded.width, decoded.height),
                                        decoded.width, decoded.height, Bitmap.Config.ARGB_8888).asImageBitmap(),
                                        bounds.outWidth, bounds.outHeight)
                                }
                            } finally { decoded.recycle() }
                        } catch (_: RuntimeException) { null } catch (_: OutOfMemoryError) { null }
                        StaticBackdropAsset(original.asImageBitmap(), softened).also { originalPublished = true }
                    } finally { if (!originalPublished) original.recycle() }
                } catch (_: RuntimeException) { null } catch (_: OutOfMemoryError) { null }
            }
            image
        }
    }
}

/** Clear original artwork outside surfaces; only GlassSurface uses the softened, aligned copy. */
@Composable
internal fun BrandBackdrop(modifier: Modifier = Modifier, loginPage: Boolean = false) {
    val context = LocalContext.current
    val mode = LocalGlassMode.current
    val state = LocalGlassBackdrop.current
    LaunchedEffect(state, mode) {
        if (state != null && mode == GlassMode.Softened) {
            val image = SoftBackdropCache.get(context.resources)
            state.original = image?.original
            state.softened = image?.softened
            state.unavailable = image == null
        }
    }
    val original = state?.original
    val solid = mode == GlassMode.Solid || state?.unavailable == true || original == null
    Box(modifier.onGloballyPositioned { state?.bounds = it.boundsInWindow() }) {
        if (!solid && original != null) Image(original, contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            drawRect(Brand.Background.copy(alpha = if (solid) 1f else if (loginPage) .18f else .10f))
        }
    }
}

/** Static frosted-image approximation. It does not blur arbitrary live content behind a surface. */
@Composable
internal fun GlassSurface(modifier: Modifier = Modifier, shape: Shape = Brand.Shape,
                          kind: GlassSurfaceKind = GlassSurfaceKind.Card, selected: Boolean = false,
                          edge: BorderStroke? = null, tint: Brush? = null,
                          content: @Composable () -> Unit) {
    val state = LocalGlassBackdrop.current
    val requested = LocalGlassMode.current
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val softened = state?.softened
    val image = softened?.image
    val backdrop = state?.bounds
    val surface = bounds
    val contained = backdrop != null && surface != null && GlassMaterialPolicy.surfaceInsideBackdrop(
        backdrop.left, backdrop.top, backdrop.width, backdrop.height,
        surface.left, surface.top, surface.width, surface.height)
    val placement = if (softened != null && backdrop != null && surface != null && contained) {
        // Preserve the ORIGINAL artwork's cover geometry; sampled odd dimensions may have a slightly different ratio.
        GlassMaterialPolicy.placement(softened.originalWidth, softened.originalHeight, backdrop.left, backdrop.top,
            backdrop.width, backdrop.height, surface.left, surface.top)
    } else null
    val frosted = state?.unavailable != true && GlassMaterialPolicy.frosted(requested,
        state?.original != null, image != null, placement != null)
    val brush = when {
        selected -> Brush.linearGradient(listOf(Brand.ActionStart.copy(alpha = if (frosted) .90f else 1f),
            Brand.ActionEnd.copy(alpha = if (frosted) .90f else 1f)))
        kind == GlassSurfaceKind.Navigation -> Brush.linearGradient(listOf(
            Color(if (frosted) GlassMaterialPolicy.NavigationStartArgb else GlassMaterialPolicy.NavigationSolidArgb)
                .copy(alpha = if (frosted) GlassMaterialPolicy.NavigationStartAlpha else 1f),
            Color(if (frosted) GlassMaterialPolicy.NavigationEndArgb else GlassMaterialPolicy.NavigationSolidArgb)
                .copy(alpha = if (frosted) GlassMaterialPolicy.NavigationEndAlpha else 1f)))
        else -> Brush.linearGradient(listOf(
            Color(if (frosted) GlassMaterialPolicy.CardStartArgb else GlassMaterialPolicy.CardSolidArgb)
                .copy(alpha = if (frosted) GlassMaterialPolicy.CardStartAlpha else 1f),
            Color(if (frosted) GlassMaterialPolicy.CardEndArgb else GlassMaterialPolicy.CardSolidArgb)
                .copy(alpha = if (frosted) GlassMaterialPolicy.CardEndAlpha else 1f)))
    }
    Surface(modifier.onGloballyPositioned {
        // A scrolling card can extend past the viewport. Its clipped visible bounds would
        // misalign the sampled backdrop and leave a sharp transparent edge inside a tall card.
        bounds = Rect(it.positionInWindow(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
    }, shape = shape, color = Color.Transparent,
        // An explicit edge replaces the default rim; unchanged for every existing caller.
        border = edge ?: BorderStroke(if (selected) 2.dp else 1.dp, Brand.GlassBorder.copy(alpha = if (selected) .72f else if (frosted) .44f else .38f)),
        shadowElevation = 8.dp) {
        Box(Modifier.drawWithCache {
            val reflection = Brush.verticalGradient(listOf(Color.White.copy(alpha = .12f), Color.Transparent),
                endY = minOf(48.dp.toPx(), size.height))
            onDrawBehind {
                if (frosted && image != null && placement != null) drawImage(image,
                    srcSize = IntSize(image.width, image.height),
                    dstOffset = IntOffset(placement.left, placement.top),
                    dstSize = IntSize(placement.width, placement.height))
                drawRect(brush)
                // Optional static gradient wash (no blur, no animation) drawn over the shared material.
                tint?.let { drawRect(it) }
                drawRect(reflection)
            }
        }) { content() }
    }
}
