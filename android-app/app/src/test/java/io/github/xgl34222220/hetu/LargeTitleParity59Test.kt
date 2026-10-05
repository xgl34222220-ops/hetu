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
        // PDF03A/1 and04/1 have the same visible title alignment. HxPage
        // already reserves its collapsed toolbar, so its local padding differs.
        assertTrue(toolsScreen.contains("top = 66.dp"))
        assertTrue(toolsDesign.contains("fontSize = 36.sp"))
        assertTrue(settings.contains("largeTitleTopPadding = 16.dp"))
        assertTrue(settings.contains("largeTitleBottomPadding = 12.dp"))
    }
}
