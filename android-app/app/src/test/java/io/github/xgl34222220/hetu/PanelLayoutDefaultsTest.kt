package io.github.xgl34222220.hetu

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PanelLayoutDefaultsTest {
    private fun preferences() = ApplicationProvider.getApplicationContext<Application>()
        .getSharedPreferences("layout-defaults-test", 0).also { it.edit().clear().commit() }

    @Test fun missingPreferencesUseReferenceCompactTwoColumnLayout() {
        val options = PanelOptions11.read(preferences())
        assertTrue(options.compact)
        assertTrue(options.groupCompact)
        assertEquals(2, options.columns)
        assertEquals(2, options.groupColumns)
    }

    @Test fun explicitStandardLayoutSurvivesReadingAndOtherPresentationEdits() {
        val prefs = preferences()
        prefs.edit().putString("proxySelectorDensity", "standard")
            .putString("proxySelectorGroupDensity", "standard").commit()
        val selected = PanelOptions11.read(prefs)
        assertFalse(selected.compact)
        assertFalse(selected.groupCompact)
        selected.copy(sort = "delay").save(prefs)
        val restored = PanelOptions11.read(prefs)
        assertFalse(restored.compact)
        assertFalse(restored.groupCompact)
        assertEquals("delay", restored.sort)
    }
}
