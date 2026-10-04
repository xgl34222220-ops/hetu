package io.github.xgl34222220.hetu

import org.junit.Assert.assertTrue
import org.junit.Test

/** V20.58 settings overlay acceptance derived from concept pages 15-30. */
class SettingsPickerParity58Test {
    @Test fun rootSettingsChoiceWidthsStayCompact() {
        val source = java.io.File("src/main/java/io/github/xgl34222220/hetu/RootTproxyActivity.java").readText()
        assertTrue(source.contains("selected,174,index"))
        assertTrue(source.contains("enabled,selected,184,index"))
        assertTrue(source.contains("ordinal(),184,index"))
        assertTrue(source.contains("FLAG_DIM_BEHIND"))
        assertTrue(source.contains("lp.dimAmount=0f"))
    }

    @Test fun composeChoiceMenusDimThePageByDefault() {
        val source = java.io.File("src/main/java/io/github/xgl34222220/hetu/app/HxComponents.kt").readText()
        assertTrue(source.contains("dimBehind: Boolean = true"))
    }
}
