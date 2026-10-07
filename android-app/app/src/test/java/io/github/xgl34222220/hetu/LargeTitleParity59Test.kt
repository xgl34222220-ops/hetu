package io.github.xgl34222220.hetu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具 and 设置 share one large-title bar with 首页 and 面板: a 36 sp title at the left of the
 * first rows, the page's actions pinned at the right, and a compact title that takes over once
 * the list has scrolled. These gates keep both tab roots on that shared implementation.
 */
class LargeTitleParity59Test {
    private fun source(path: String) = java.io.File("src/main/java/io/github/xgl34222220/hetu/$path").readText()

    @Test fun rootToolsAndSettingsUseTheSharedLargeTitle() {
        val tokens = source("home/HomeTokens.kt")
        val kit = source("home/HomeListKit.kt")
        val components = source("app/HxComponents.kt")
        val tools = source("app/PanelToolsScreens.kt")
        val toolsScreen = source("tools/ToolsScreen.kt")
        val settings = source("app/SettingsScreen.kt")

        // One type token and one set of dimensions for every large title.
        assertTrue(tokens.contains("val largeTitle = TextStyle(fontSize = 36.sp"))
        assertTrue(kit.contains("internal object HomeLargeTitleDims"))
        assertTrue(kit.contains("internal fun BoxScope.HomeLargeTitleBar("))

        // 工具 is drawn by the tools module on the shared bar.
        assertTrue(tools.contains("tools.HetuToolsV2("))
        assertTrue(toolsScreen.contains("HomeLargeTitleBar("))

        // 设置 is an HxPage root: no back action, large title on. HxPage takes its geometry from
        // the same dimensions and type token, and hides the compact twin from accessibility.
        assertTrue(components.contains("HomeLargeTitleDims.titleTop"))
        assertTrue(components.contains("HomeLargeTitleDims.inset"))
        assertTrue(components.contains("style = HomeType.largeTitle"))
        assertTrue(components.contains("Modifier.clearAndSetSemantics { }"))
        assertTrue(settings.contains("title = ht(\"设置\")"))

        // No page positions its own large title any more.
        assertFalse(settings.contains("largeTitleTopPadding ="))
        assertFalse(settings.contains("largeTitleBottomPadding ="))
    }
}
