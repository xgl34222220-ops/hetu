package io.github.xgl34222220.hetu

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Resource rendering only: no app launch, Root, networking, or launcher preference changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = Application::class, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun icon() = app.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable

    @Test fun existingLauncherIdentitiesResolveToTheApprovedAdaptiveIcon() {
        assertEquals(R.mipmap.ic_launcher, app.applicationInfo.icon)
        for (alias in listOf("LauncherOfficial", "LauncherClassic")) {
            val info = app.packageManager.getActivityInfo(ComponentName(app.packageName, app.packageName + "." + alias),
                PackageManager.MATCH_DISABLED_COMPONENTS)
            assertEquals(R.mipmap.ic_launcher, info.icon)
        }
        assertNotNull(icon().foreground)
        assertNotNull(icon().background)
        if (Build.VERSION.SDK_INT >= 33) assertNotNull(icon().monochrome)
    }

    @Test fun themedSilhouetteFitsTheSafeCircleAtEveryLauncherSize() {
        for (size in listOf(32, 48, 72, 192)) {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val foreground = requireNotNull(app.getDrawable(R.drawable.hetu_launcher_monochrome))
            foreground.setBounds(-size / 4, -size / 4, size * 5 / 4, size * 5 / 4)
            foreground.draw(Canvas(bitmap))
            var visible = 0
            for (y in 0 until size) for (x in 0 until size) {
                if (Color.alpha(bitmap.getPixel(x, y)) < 16) continue
                visible++
                val dx = x + .5 - size / 2.0
                val dy = y + .5 - size / 2.0
                // 66dp safe circle inside the 72dp masked viewport, plus one raster AA pixel.
                val safeRadius = size * 33.0 / 72.0 + 1.0
                assertTrue("glass clipped at $size px: $x,$y", dx * dx + dy * dy <= safeRadius * safeRadius)
            }
            assertTrue("foreground must remain legible at $size px", visible > size * size / 8)
            bitmap.recycle()
        }
    }

    @Test fun nativeResourceLayersRenderCircleSquareAndThemedMasksWithoutWhiteMatte() {
        for (size in listOf(48, 192)) for (mask in listOf("circle", "square", "themed")) {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val path = Path().apply {
                if (mask == "circle") addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
                else addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * .2f, size * .2f, Path.Direction.CW)
            }
            canvas.clipPath(path)
            val drawable = icon()
            if (mask == "themed" && Build.VERSION.SDK_INT >= 33) {
                canvas.drawColor(Color.rgb(216, 229, 255))
                requireNotNull(drawable.monochrome).mutate().also {
                    it.setTint(Color.rgb(25, 62, 112))
                    it.setBounds(-size / 4, -size / 4, size * 5 / 4, size * 5 / 4)
                    it.draw(canvas)
                }
            } else {
                for (layer in listOf(drawable.background, drawable.foreground)) {
                    layer.setBounds(-size / 4, -size / 4, size * 5 / 4, size * 5 / 4)
                    layer.draw(canvas)
                }
                val edge = bitmap.getPixel(size / 2, 1)
                assertTrue("outer tile must stay blue", Color.blue(edge) > Color.red(edge) + 15)
            }
            val file = File("build/outputs/hetu-concept-root/icon-api${Build.VERSION.SDK_INT}-$size-$mask.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
