package io.github.xgl34222220.hetu

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.xgl34222220.hetu.ui.translateHetuText
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.yaml.snakeyaml.Yaml
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReferenceParityTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun reset() { app.getSharedPreferences("hetu", 0).edit().clear().commit() }
    private fun group(name: String, hidden: Boolean = false) = ProxyGroupUi(name, "Selector", "", emptyList(), hidden = hidden)
    @Test fun hiddenAndGlobalGroupsRespectModeWithoutReordering() {
        val all = listOf(group("B"), group("GLOBAL"), group("A", true))
        assertEquals(listOf("B"), SelectorPresentation.visibleGroups(all, false, true, "rule").map { it.name })
        assertEquals(listOf("B", "GLOBAL", "A"), SelectorPresentation.visibleGroups(all, true, true, "global").map { it.name })
        assertEquals(listOf("B", "GLOBAL"), SelectorPresentation.visibleGroups(all, false, false, "direct").map { it.name })
    }
    @Test fun accordionSupportsIndependentExpansionAndCollapsePreference() {
        assertEquals(listOf("A", "B"), SelectorPresentation.toggleExpanded(listOf("A"), "B", false))
        assertEquals(listOf("B"), SelectorPresentation.toggleExpanded(listOf("A"), "B", true))
        assertEquals(listOf("A"), SelectorPresentation.toggleExpanded(listOf("A", "B"), "B", false))
    }
    @Test fun nodeOrderingUsesRealLatencyAndProviderNames() {
        val group = group("A").copy(nodes = listOf(ProxyNodeUi("unknown"), ProxyNodeUi("slow", lastDelay = 120), ProxyNodeUi("fast", lastDelay = 20)))
        val ordered = SelectorPresentation.nodes(group, "latency", false, mapOf("slow" to 10), mapOf("fast" to "provider"))
        assertEquals(listOf("slow", "fast", "unknown"), ordered.map { it.name })
        assertEquals("provider", ordered[1].provider)
        assertEquals(group.nodes.map { it.name }, SelectorPresentation.nodes(group, "config", false, emptyMap()).map { it.name })
    }
    @Test fun filenamesAndTextDecoderProtectBinaryFiles() {
        listOf("../x", "..", ".", "a/b", "a\\b", "a\nb", "").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { RuntimeFilesRepository.child(RuntimeFilesRepository.ROOT, name) }
        }
        assertEquals("/data/adb/hetu/中文.yaml", RuntimeFilesRepository.child(RuntimeFilesRepository.ROOT, "中文.yaml"))
        assertNull(RuntimeFilesRepository.utf8(byteArrayOf(0, 65)))
        assertNull(RuntimeFilesRepository.utf8(byteArrayOf(0xc3.toByte(), 0x28)))
        assertEquals("中文", RuntimeFilesRepository.utf8("中文".toByteArray()))
        assertNotEquals(RuntimeFilesRepository.digest("before".toByteArray()), RuntimeFilesRepository.digest("after".toByteArray()))
    }
    @Test fun secondaryUserPackagesCannotResolveToPrimaryUserUid() {
        val output = "package:org.test.app uid:1010123\npackage:org.primary.app uid:10124\npackage:org.invalid.app uid:999999999999\n"
        assertEquals(listOf("org.test.app" to 1010123), ProxyUserAppsRepository.parse(output, 10))
        assertEquals(listOf("org.primary.app" to 10124), ProxyUserAppsRepository.parse(output, 0))
    }
    @Test fun historyUpsertsConnectionTotalsAndHonorsRetentionAndOptOut() = runBlocking {
        val prefs = app.getSharedPreferences("hetu", 0)
        ProxyApiHistoryStore.clear(app)
        prefs.edit().putBoolean("proxyApiHistoryEnabled", true).putInt("proxyApiHistoryRetentionDays", 1).commit()
        val first = ProxyConnectionUi("id", "example.org", "MATCH", "", "PROXY", 100, 200, appName = "Test")
        val now = 2_000_000_000_000L
        ProxyApiHistoryStore.record(app, 10, 20, listOf(first), now)
        ProxyApiHistoryStore.record(app, 20, 30, listOf(first.copy(upload = 200, download = 500)), now + 1000)
        val row = ProxyApiHistoryStore.ranking(app, "host", "traffic", now - 1).single()
        assertEquals(1, row.connections); assertEquals(200L, row.upload); assertEquals(500L, row.download)
        assertEquals(2, ProxyApiHistoryStore.recent(app, 60_000, now + 1000).size)
        ProxyApiHistoryStore.record(app, 1, 2, emptyList(), now + 86_500_000)
        assertTrue(ProxyApiHistoryStore.ranking(app, "host", "traffic", 0).isEmpty())
        prefs.edit().putBoolean("proxyApiHistoryEnabled", false).commit()
        assertTrue(ProxyApiHistoryStore.recent(app, Long.MAX_VALUE / 2).isEmpty())
        ProxyApiHistoryStore.clear(app)
    }
    @Test fun controllerModeAndIpv6ProbeUseExactEndpointsWithoutFallback() {
        MockWebServer().use { server ->
            server.start()
            app.getSharedPreferences("hetu", 0).edit().putBoolean("proxyCustomApiEnabled", true).putString("proxyCustomApiHost", "127.0.0.1").putInt("proxyCustomApiPort", server.port).commit()
            val api = MihomoControllerClient(app)
            server.enqueue(MockResponse().setResponseCode(204))
            api.setTrafficMode("global")
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("PATCH", request.method); assertEquals("/configs", request.path)
            assertEquals("global", JSONObject(request.body.readUtf8()).getString("mode"))
            assertThrows(IllegalArgumentException::class.java) { api.setTrafficMode("invalid") }
            server.enqueue(MockResponse().setResponseCode(503))
            assertThrows(Exception::class.java) { api.delayIpv6("节点 / A") }
            val probe = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("https://[2606:4700:4700::1111]/cdn-cgi/trace", probe.requestUrl!!.queryParameter("url"))
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun healthSettingsKeepCommentsAnchorsAndUnrelatedConfig() {
        val source = """
            # user's comment 🌍
            base: &base
              type: http
              interval: 86400
            proxy-providers:
              original:
                <<: *base
                url: 'https://example.org/sub?token=secret'
                health-check: {enable: false, url: 'https://old.example'}
              second:
                type: http
                url: 'https://example.org/two'
            proxy-groups:
              - name: Auto
                type: url-test
                use: [original, second]
            rules:
              - MATCH,Auto
        """.trimIndent() + "\n"
        val result = rewriteSubscriptionHealth(source, SubscriptionHealth("https://example.org/check", 120, 2000, 30))
        assertTrue(result.startsWith("# user's comment 🌍\nbase: &base"))
        assertTrue(result.contains("<<: *base")); assertTrue(result.contains("url: 'https://example.org/sub?token=secret'"))
        assertTrue(result.endsWith("rules:\n  - MATCH,Auto\n"))
        val config = Yaml().load<Map<String, Any>>(result)
        val providers = config["proxy-providers"] as Map<*, *>
        providers.values.forEach { provider ->
            val check = (provider as Map<*, *>)["health-check"] as Map<*, *>
            assertEquals(true, check["enable"]); assertEquals(2000, check["timeout"])
        }
        assertEquals(30, ((config["proxy-groups"] as List<*>).single() as Map<*, *>)["tolerance"])
    }
    @Test fun healthSettingsRejectInvalidValuesAndAmbiguousFlowEdits() {
        assertThrows(IllegalArgumentException::class.java) { SubscriptionHealth("file:///x", 1, 0, -1).check() }
        assertThrows(IllegalArgumentException::class.java) { rewriteSubscriptionHealth("proxy-providers: {p: {type: http}}", SubscriptionHealth("https://example.org", 60, 2000, 50)) }
    }
    @Test fun jsonSyntaxIsStrictAndOfficialSchemaRejectsWrongTypes() = runBlocking {
        assertThrows(Exception::class.java) { RuntimeSchemaRepository.parse("{\"x\":1,\"x\":2}") }
        assertThrows(Exception::class.java) { RuntimeSchemaRepository.parse("{x: 1}") }
        assertThrows(Exception::class.java) { RuntimeSchemaRepository.parse("{} {}") }
        RuntimeSchemaRepository.validate(app, "{\"log\":{\"level\":\"info\"}}")
        var rejected = false
        try { RuntimeSchemaRepository.validate(app, "{\"log\":{\"level\":12}}") } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }
    @Test fun webPanelValidationAndLanguageFallbackAreExplicit() {
        assertTrue(HetuWebPanels.validUrl("https://example.org/panel#settings"))
        listOf("https://", "https://user:secret@example.org", "http://example.org", "javascript:alert(1)").forEach { assertFalse(HetuWebPanels.validUrl(it)) }
        assertEquals("Settings", translateHetuText("设置", "en"))
        assertEquals("設定", translateHetuText("设置", "zh-TW"))
        assertEquals("Настройки", translateHetuText("设置", "ru"))
        assertEquals("用户内容", translateHetuText("用户内容", "en"))
    }
    @Test fun dashboardArchiveSupportsWrapperAndRejectsTraversal() {
        fun zip(vararg files: Pair<String, String>): ByteArray {
            val bytes = java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(bytes).use { out -> files.forEach { (name, text) ->
                out.putNextEntry(java.util.zip.ZipEntry(name)); out.write(text.toByteArray()); out.closeEntry()
            } }
            return bytes.toByteArray()
        }
        val folder = java.io.File(app.cacheDir, "panel-test-${System.nanoTime()}")
        try {
            val panel = WebPanelAssets.extract(zip("dist/index.html" to "<html></html>", "dist/assets/a.js" to "export {};").inputStream(), folder)
            assertEquals("dist", panel.name)
            assertTrue(java.io.File(panel, "assets/a.js").isFile)
            assertThrows(IllegalArgumentException::class.java) { WebPanelAssets.extract(zip("../escape" to "bad").inputStream(), folder) }
            assertFalse(java.io.File(folder.parentFile, "escape").exists())
        } finally { folder.deleteRecursively() }
    }
}
