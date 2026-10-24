package io.github.xgl34222220.hetu.tools

import io.github.xgl34222220.hetu.ui.translateHetuText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 7.0.0 import fix: picker names, the core check reason in a toast, and the new settings entry's words. */
class ToolsImportFix700Test {
    @Test fun pickedNamesBecomeLibraryNames() {
        assertEquals("自用_tproxy.yaml", toolsImportFileName("自用_tproxy.yaml"))
        assertEquals("自用_tproxy.YML", toolsImportFileName(" 自用_tproxy.YML "))
        assertEquals("document:1234.yaml", toolsImportFileName("document:1234"))
        assertEquals("config.txt.yaml", toolsImportFileName("config.txt"))
        assertEquals("config.yaml", toolsImportFileName(""))
        assertEquals("config.yaml", toolsImportFileName(".yaml"))
        val long = toolsImportFileName("长".repeat(200) + ".yaml")
        assertEquals(120, long.length)
        assertEquals(true, long.endsWith(".yaml"))
    }

    @Test fun coreCheckKeepsTheErrorLine() {
        val output = "YAML 校验失败：time=\"2026-10-24T12:00:00+08:00\" level=info msg=\"Start initial configuration in progress\"\n" +
            "time=\"2026-10-24T12:00:00+08:00\" level=error msg=\"Parse config error: proxy 3: missing type\"\n" +
            "configuration file /data/x.yaml test failed"
        assertEquals("Parse config error: proxy 3: missing type", toolsCoreCheckReason(output))
        assertEquals("Sing-Box 配置校验失败：bad", toolsCoreCheckReason("Sing-Box 配置校验失败：bad"))
        assertEquals("未知原因", toolsCoreCheckReason(""))
    }

    @Test fun settingsImportEntryIsTranslated() {
        listOf("从文件或链接导入配置，导入后设为当前配置", "请先选择配置文件").forEach { word ->
            for (language in listOf("en", "zh-TW", "ru")) assertNotEquals(word, word, translateHetuText(word, language))
        }
    }
}
