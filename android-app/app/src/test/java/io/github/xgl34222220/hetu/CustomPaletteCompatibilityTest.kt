package io.github.xgl34222220.hetu

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.ui.HetuTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** Exercise the production shared theme, including preference recomposition. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomPaletteCompatibilityTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val prefs get() = ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("hetu", 0)
    private var observed: ColorScheme? = null
    private val palettes = listOf("TonalSpot", "Neutral", "Vibrant", "Expressive", "Rainbow", "FruitSalad", "Monochrome", "Fidelity")

    @Before fun prepare() {
        prefs.edit().clear().putString("appLanguage", "zh-CN").putString("appearance", "light")
            .putBoolean("enableMonet", false).putBoolean("enableAnimations", false).commit()
        rule.mainClock.autoAdvance = false
    }

    @After fun finish() {
        rule.runOnUiThread { rule.activity.setContent {} }
        frames()
    }

    private fun frames() {
        repeat(8) {
            rule.mainClock.advanceTimeBy(200)
            rule.runOnUiThread { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
        }
    }

    private fun show() {
        rule.runOnUiThread {
            rule.activity.setContent {
                HetuTheme {
                    val scheme = MaterialTheme.colorScheme
                    SideEffect { observed = scheme }
                    Text("服务启动前")
                }
            }
        }
        frames()
    }

    private fun select(accent: String, palette: String, appearance: String = "light") {
        rule.runOnUiThread {
            prefs.edit().putString("accentHex", accent).putString("colorPalette", palette)
                .putString("appearance", appearance).commit()
        }
        frames()
    }

    private fun assertReadable(dark: Boolean) {
        val scheme = checkNotNull(observed)
        assertNotEquals(Color.Unspecified, scheme.primary)
        val surface = scheme.surface.luminance()
        val text = scheme.onSurface.luminance()
        assertTrue("Surface appearance must follow the saved preference", if (dark) surface < .1f else surface > .5f)
        assertTrue("Generated text must remain readable", (maxOf(surface, text) + .05f) / (minOf(surface, text) + .05f) >= 4.5f)
    }

    private fun ColorScheme.sample() = listOf(primary, onPrimary, primaryContainer, secondary, tertiary, surface, onSurface)

    @Test fun customizedAccentGeneratesEverySupportedPaletteInBothAppearances() {
        select("#EF4444", "TonalSpot")
        show()
        for (appearance in listOf("light", "dark")) for (palette in palettes) {
            select("#EF4444", palette, appearance)
            assertReadable(appearance == "dark")
        }
    }

    @Test fun defaultAccentSupportsNondefaultPalettesAndReturnsToBaseTheme() {
        show()
        val baseline = checkNotNull(observed)
        for (palette in palettes.drop(1)) {
            select("#2A62E8", palette)
            assertReadable(false)
        }
        assertNotEquals("The saved palette must produce its own scheme", baseline.primary, observed!!.primary)
        select("#2A62E8", "TonalSpot")
        assertEquals(baseline.sample(), observed!!.sample())
    }

    @Test fun systemMonetKeepsItsPriorityOverSavedSeedAndPalette() {
        prefs.edit().putBoolean("enableMonet", true).commit()
        show()
        val systemScheme = checkNotNull(observed)
        select("#EF4444", "Expressive")
        assertEquals(systemScheme.sample(), observed!!.sample())
        rule.runOnUiThread { prefs.edit().putBoolean("enableMonet", false).commit() }
        frames()
        assertReadable(false)
        assertNotEquals(systemScheme.primary, observed!!.primary)
    }

    @Test fun customizedSeedsSurviveDisposalAndFreshThemeComposition() {
        val schemes = mutableListOf<Color>()
        for (seed in listOf("#22C55E", "#A855F7", "#F97316")) {
            select(seed, "Fidelity", "dark")
            show(); assertReadable(true)
            val original = observed!!.sample()
            schemes += observed!!.primary
            rule.runOnUiThread { rule.activity.setContent {} }; frames()
            show(); assertReadable(true)
            assertEquals("Stored preferences must reconstruct the same scheme", original, observed!!.sample())
        }
        assertEquals(3, schemes.toSet().size)
    }

    @Test fun monetCanBeRestoredAfterCustomizedDarkPaletteRecomposition() {
        prefs.edit().putBoolean("enableMonet", true).commit(); show()
        val original = observed!!.sample()
        rule.runOnUiThread { prefs.edit().putBoolean("enableMonet", false).commit() }
        select("#22C55E", "FruitSalad", "dark"); assertReadable(true)
        assertNotEquals(original, observed!!.sample())
        rule.runOnUiThread { prefs.edit().putString("appearance", "light").putBoolean("enableMonet", true).commit() }
        frames(); assertEquals(original, observed!!.sample())
    }
}
