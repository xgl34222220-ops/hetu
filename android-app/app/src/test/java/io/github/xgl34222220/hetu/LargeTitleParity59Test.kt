package io.github.xgl34222220.hetu

import org.junit.Assert.assertTrue
import org.junit.Test

class LargeTitleParity59Test {
    @Test fun rootToolsAndSettingsUseExpandedHeaderActions() {
        val components = java.io.File("src/main/java/io/github/xgl34222220/hetu/app/HxComponents.kt").readText()
        val tools = java.io.File("src/main/java/io/github/xgl34222220/hetu/app/PanelToolsScreens.kt").readText()
        val settings = java.io.File("src/main/java/io/github/xgl34222220/hetu/app/SettingsScreen.kt").readText()
        assertTrue(components.contains("largeTitleTopPadding: Dp = 0.dp"))
        assertTrue(components.contains("if (!collapsed && onBack == null)"))
        assertTrue(components.contains("if (!largeTitle || collapsed || onBack != null)"))
        assertTrue(tools.contains("tools.HetuToolsV2("))
        val toolsScreen = java.io.File("src/main/java/io/github/xgl34222220/hetu/tools/ToolsScreen.kt").readText()
        val toolsDesign = java.io.File("src/main/java/io/github/xgl34222220/hetu/tools/ToolsDesign.kt").readText()
        assertTrue(toolsScreen.contains("top = 26.dp"))
        assertTrue(toolsDesign.contains("fontSize = 36.sp"))
        assertTrue(settings.contains("largeTitleTopPadding = 26.dp"))
    }
}
