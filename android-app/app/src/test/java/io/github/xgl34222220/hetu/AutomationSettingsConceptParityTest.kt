package io.github.xgl34222220.hetu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural acceptance gates for the Settings concept pages 15-33.
 * The production tree is reconstructed by CI before these tests run.
 */
class AutomationSettingsConceptParityTest {
    private fun source(path: String) =
        File("src/main/java/io/github/xgl34222220/hetu/$path").readText()

    @Test fun basicProxyPickersCoverConceptOptions() {
        val s = source("RootTproxyActivity.java")
        val profile = source("ProxyRuntimeProfile.java")
        listOf("Mihomo", "Mihomo Smart").forEach { assertTrue("missing core option $it", profile.contains(it)) }
        listOf("TUN", "TPROXY", "eBPF", "Redirect", "Enhance").forEach {
            assertTrue("missing run mode $it", s.contains(it, ignoreCase = true))
        }
        listOf("启用", "不进核心", "严格防泄漏", "禁用系统 IPv6").forEach {
            assertTrue("missing IPv6 choice $it", s.contains(it))
        }
        assertTrue(s.contains("查看启动配置"))
        assertTrue(s.contains("当前配置"))
        assertTrue(s.contains("立即重启"))
    }

    @Test fun themeAndDefaultPanelPickersCoverConceptOptions() {
        val s = source("app/SettingsScreen.kt")
        listOf("overview", "proxies", "providers", "conn", "rules", "sets", "logs").forEach {
            assertTrue("missing default panel value $it", s.contains("HxChoice(\"$it\""))
        }
        listOf("跟随系统", "简体中文", "繁體中文", "English", "Русский").forEach {
            assertTrue("missing language option $it", s.contains(it))
        }
        listOf("渐进式模糊", "高斯模糊").forEach {
            assertTrue("missing blur option $it", s.contains(it))
        }
        listOf(".8f", ".9f", "1f", "1.1f", "1.2f").forEach {
            assertTrue("missing scale option $it", s.contains(it))
        }
    }

    @Test fun notificationPickersCoverConceptOptions() {
        val s = source("app/ToolScreens.kt")
        assertTrue(s.contains("listOf(2, 3, 5, 10, 30, 60)"))
        listOf("首页", "面板", "策略", "面板浮窗", "策略浮窗", "工具", "设置").forEach {
            assertTrue("missing notification target $it", s.contains(it))
        }
        listOf("重载", "重启", "停止", "隐藏通知", "无").forEach {
            assertTrue("missing notification action $it", s.contains(it))
        }
        assertTrue("three quick action controls required", s.contains("(0 until 3).forEach"))
    }

    @Test fun advancedDnsPickerMatchesConcept() {
        val s = source("ProxyAdvancedSettingsActivity.kt")
        listOf("TPROXY", "REDIRECT", "关闭").forEach {
            assertTrue("missing DNS hijack choice $it", s.contains(it))
        }
    }

    @Test fun restoreAndMirrorValidationMatchConcept() {
        val settings = source("app/SettingsScreen.kt")
        val components = source("app/SettingsReferenceComponents.kt")
        assertTrue(settings.contains("恢复备份？"))
        assertTrue(settings.contains("正在运行的代理不会自动重启"))
        assertTrue(components.contains("加速下载"))
        assertTrue(components.contains("请填写 http/https 地址"))
        assertTrue(components.contains("镜像前缀"))
    }

    @Test fun licensesAndMockupBoundaryMatchConcept() {
        val about = source("app/MiscScreens.kt")
        assertTrue(about.contains("Mihomo"))
        assertTrue(about.contains("AdGuard DNS Filter"))
        assertTrue(about.contains("Lucide Icons"))
        assertTrue(about.contains("MIHOMO-LICENSE"))
        assertTrue(about.contains("ADGUARD-LICENSE"))
        assertTrue(about.contains("licenses/lucide.txt"))

        val root = File("src/main/java/io/github/xgl34222220/hetu")
        val production = root.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .map { it.readText() }
            .toList()
        assertFalse("mockup-only label must not be rendered by production UI", production.any { it.contains("示例数据") })
    }
}
