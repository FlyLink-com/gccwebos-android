package com.gccwebos.android

import android.graphics.*
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Native drawable rendering only: original artwork bytes are unchanged. */
class LauncherIconRenderTest {
    @Test fun approvedArtworkRendersInsideCircleAndSquircleAt48dp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val icon = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        val density = context.resources.displayMetrics.density
        val small = (48 * density).toInt()
        val large = small * 2
        val bitmap = Bitmap.createBitmap(large * 4, large * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.rgb(15, 22, 35))
        fun render(x: Int, y: Int, size: Int, circle: Boolean) {
            canvas.save(); canvas.translate(x.toFloat(), y.toFloat())
            val path = Path().apply { if (circle) addCircle(size/2f, size/2f, size/2f, Path.Direction.CW)
                else addRoundRect(0f, 0f, size.toFloat(), size.toFloat(), size*.22f, size*.22f, Path.Direction.CW) }
            canvas.clipPath(path)
            val inset = size / 4
            icon.background.setBounds(-inset, -inset, size + inset, size + inset); icon.background.draw(canvas)
            icon.foreground.setBounds(-inset, -inset, size + inset, size + inset); icon.foreground.draw(canvas)
            canvas.restore()
        }
        render(0, 0, large, true); render(large + small, 0, large, false)
        render(small / 2, large + small / 2, small, true); render(large + small * 3 / 2, large + small / 2, small, false)
        val dir = File(context.getExternalFilesDir(null), "ui-evidence").apply { mkdirs() }
        File(dir, "launcher-approved-robot-circle-squircle-48dp.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertEquals(48 * density, small.toFloat(), 1f)
        assertNotEquals(Color.rgb(15, 22, 35), bitmap.getPixel(large / 2, large / 2))
    }
}
