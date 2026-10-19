package io.github.xgl34222220.hetu

import android.app.Application
import io.github.xgl34222220.hetu.panel.SingBoxPanelStore
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 6.0.0 sing-box 订阅 / 规则集: the record RootProxyManager keeps for the running start and the rule counts
 * of its rule-set files. The .srs fixtures were compiled by sing-box 1.14.3 (`rule-set compile`); each
 * expected count is what `sing-box rule-set decompile` lists for the same file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SingBoxPanelStore600Test {
    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** v2: domain a.com b.org, suffix c.net d.io e.cn, keyword ×2, regex ×1, ip_cidr 1.0.0.0/24 10.0.0.0/8 2001:db8::/32. */
    private val current = hex("5352530278da626460626064606060607dc0c0c0c820fd6ad5aa55ab1648a5e7e6e59714e52767a6e627ebe9e5e9e9a5a6e825257271257331333167971b3267971bb13032c715a9b0817533303030b3805820e23f0b1788c5f5ffff7f010546de1d0c48002cf01f193000020000ffffbe9723f5")
    /** v2: three rules, one of them logical (suffix, ip, or(domain, keyword)). */
    private val logical = hex("5352530278da62666062606480000728436a155b6e7eb25e05d77f0606369824230b23232384f8cfc0c8c884a44f01ca605bc50ad257f99f81819991b1ea3f0303200000ffff7e450a57")
    /** v1: domain a.com, suffix x.com y.org (stored twice in v1), 10/8 + 11/8 (one range = 10.0.0.0/7), 1.0.0.0/24, 1.0.1.0/25. */
    private val legacy = hex("5352530178da6264606260640083731046696668087f7a6e517e7eb29e5e6562859e1e2f2f1b540903130b88c5c2c8c058cfc2056271ffffffff3f03200000ffff59e60bc1")

    @Test fun binaryRuleSetsCountLikeTheDecompiler() {
        assertEquals(11, SingBoxPanelStore.srsRuleCount(current))
        assertEquals(4, SingBoxPanelStore.srsRuleCount(logical))
        assertEquals(6, SingBoxPanelStore.srsRuleCount(legacy))
        assertEquals(11, SingBoxPanelStore.ruleCount(current, "binary"))
        // Not a rule-set, cut short, or a source file read as binary: unknown, never a wrong number.
        assertEquals(-1, SingBoxPanelStore.ruleCount("SRS\u0002garbage".toByteArray(), "binary"))
        assertEquals(-1, SingBoxPanelStore.ruleCount(current.copyOf(current.size - 9), "binary"))
        assertEquals(-1, SingBoxPanelStore.ruleCount("{\"version\":3}".toByteArray(), "binary"))
        assertEquals(-1, SingBoxPanelStore.ruleCount(null, "binary"))
    }

    @Test fun sourceAndInlineRulesCountEveryEntry() {
        val source = """{"version":3,"rules":[{"domain_suffix":["x.com"]},{"ip_cidr":["1.1.1.1/32"]},
            {"type":"logical","mode":"or","rules":[{"domain":["y.com"]},{"domain_keyword":["z"]}]},{"ip_is_private":true,"invert":true}]}"""
        assertEquals(5, SingBoxPanelStore.ruleCount(source.toByteArray(), "source"))
        assertEquals(3, SingBoxPanelStore.rulesCount(JSONArray("""[{"domain_suffix":["a","b"],"invert":false},{"domain":"c"}]""")))
        assertEquals(0, SingBoxPanelStore.rulesCount(null))
    }

    @Test fun recordedStartBecomesThePanelRows() {
        val dir = Files.createTempDirectory("hetu-sb-panel").toFile()
        try {
            assertNull(SingBoxPanelStore.load(dir))
            val meta = JSONObject("""{"providers":[
                {"name":"机场","type":"http","url":"https://sub.example/a","nodes":["香港 01","日本 02"],"updatedAt":1760000000000,
                 "info":"upload=1024; download=2048; total=10737418240; expire=1798761600","fetchedNodes":-1},
                {"name":"手写","type":"inline","url":"","nodes":["x"]}],
              "ruleSets":[
                {"tag":"geosite-cn","type":"local","format":"binary","url":"https://r.example/cn.srs","path":"/data/adb/hetu/run/ruleset/sing/a.srs","rules":9304,"updatedAt":1760000000000},
                {"tag":"rp-direct","type":"inline","format":"binary","rules":3},
                {"tag":"mine","type":"local","format":"source","path":"/sdcard/mine.json","rules":-1},
                {"tag":"far","type":"remote","format":"binary","url":"https://r.example/far.srs","rules":-1}]}""")
            SingBoxPanelStore.save(dir, meta)
            assertFalse(File(dir, SingBoxPanelStore.FILE + ".tmp").exists())
            val loaded = SingBoxPanelStore.load(dir)!!

            // Only remote subscriptions are listed, like the Mihomo page.
            val providers = SingBoxPanelStore.providers(loaded)
            assertEquals(listOf("机场"), providers.map { it.name })
            val p = providers.single()
            assertEquals("HTTP", p.vehicleType)
            assertEquals(setOf("香港 01", "日本 02"), p.nodes)
            assertTrue(p.hasSubscriptionInfo)
            assertEquals(1024L, p.upload); assertEquals(2048L, p.download); assertEquals(10737418240L, p.total); assertEquals(1798761600L, p.expire)
            assertTrue(p.updatedAt.matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(Z|[+-]\d\d:\d\d)""")))

            val sets = SingBoxPanelStore.ruleSets(loaded).associateBy { it.name }
            assertEquals("HTTP", sets.getValue("geosite-cn").vehicleType)
            assertEquals("domain", sets.getValue("geosite-cn").behavior)
            assertEquals(9304, sets.getValue("geosite-cn").ruleCount)
            assertEquals("SRS", sets.getValue("geosite-cn").format)
            assertEquals("Inline", sets.getValue("rp-direct").vehicleType)
            assertEquals("INLINE", sets.getValue("rp-direct").format)
            assertEquals("Local", sets.getValue("mine").vehicleType)
            assertEquals("JSON", sets.getValue("mine").format)
            assertEquals(-1, sets.getValue("mine").ruleCount)
            assertEquals("Remote", sets.getValue("far").vehicleType)
            assertEquals("", sets.getValue("far").updatedAt)

            assertEquals("https://sub.example/a", SingBoxPanelStore.entry(loaded, "providers", "name", "机场")!!.optString("url"))
            assertNull(SingBoxPanelStore.entry(loaded, "ruleSets", "tag", "gone"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun subscriptionUserinfoHeaderIsReadLeniently() {
        assertEquals(mapOf("upload" to 1L, "download" to 2L, "total" to 10000000000L, "expire" to 4L),
            SingBoxPanelStore.userInfo(" upload=1;download=2 ; total=1e10; expire=4; extra=9"))
        assertEquals(emptyMap<String, Long>(), SingBoxPanelStore.userInfo(null))
        assertEquals(mapOf("total" to 5L), SingBoxPanelStore.userInfo("upload=; total=5; download=x"))
        assertEquals("", SingBoxPanelStore.iso(0L))
    }

    /** The converter keeps each proxy-provider with the outbound tags it added; an unreachable one stays listed, empty. */
    @Test fun convertedSubscriptionsKeepTheirNodes() {
        val body = java.util.Base64.getEncoder().encodeToString(listOf(
            "trojan://pw@t.example.com:443?sni=t.example.com#Trojan%20One",
            "ss://" + java.util.Base64.getEncoder().encodeToString("aes-128-gcm:pw".toByteArray()) + "@1.2.3.4:8388#SS",
        ).joinToString("\n").toByteArray())
        val yaml = """
            proxies:
              - {name: SS, type: trojan, server: a.example.com, port: 443, password: p}
            proxy-providers:
              sub:
                type: http
                url: https://sub.example.com/link
                override: {additional-suffix: " [订阅]"}
              gone: {type: http, url: https://gone.example.com/sub}
              hand: {type: inline, payload: [{name: H, type: trojan, server: h.example.com, port: 443, password: p}]}
            proxy-groups:
              - {name: Proxy, type: select, use: [sub, hand], proxies: [SS]}
            rules:
              - MATCH,Proxy
        """.trimIndent()
        val o = ProxyCoreConfig.Options().apply { tproxyPort = 19898; dnsPort = 11053; controllerPort = 29090; egressPort = 29150; secret = "s" }
        val result = ProxyCoreConfig.build(ProxyRuntimeProfile.Core.SING_BOX, yaml, o) { url -> if (url == "https://sub.example.com/link") body else null }
        val providers = result.providers.associateBy { it.name }
        assertEquals(listOf("sub", "gone", "hand"), result.providers.map { it.name })
        assertEquals("http", providers.getValue("sub").type)
        assertEquals("https://sub.example.com/link", providers.getValue("sub").url)
        assertEquals(listOf("Trojan One [订阅]", "SS [订阅]"), providers.getValue("sub").nodes)
        assertEquals(emptyList<String>(), providers.getValue("gone").nodes)
        assertEquals("inline", providers.getValue("hand").type)
        assertEquals(listOf("H"), providers.getValue("hand").nodes)
        // The names are the outbound tags sing-box runs with.
        val outbounds = JSONObject(result.config).getJSONArray("outbounds")
        val tags = (0 until outbounds.length()).map { outbounds.getJSONObject(it).getString("tag") }.toSet()
        assertTrue(tags.containsAll(providers.getValue("sub").nodes + "H"))
        // A rewritten result (rule-sets localized) keeps them.
        assertEquals(result.providers, result.with(result.config, listOf("more")).providers)
    }

    @Test fun startAndRefreshPathsAreWired() {
        val manager = File("src/main/java/io/github/xgl34222220/hetu/RootProxyManager.java").readText()
        assertTrue(manager.contains("if(ProxyCoreConfig.kind(profile.core)==ProxyCoreConfig.Kind.SING_BOX)recordSingBoxPanel(built.providers,built.config,ruleSetFiles);"))
        // A rule-set update replaces only the file Hetu deployed for that URL, through a checked temporary file.
        assertTrue(manager.contains("if(!name.equals(ProxyCoreConfig.ruleSetFileName(url,format)))throw new IOException(\"规则集文件位置异常，请重启代理后再试\");"))
        assertTrue(manager.contains("&& mv -f \"+RootBridge.quote(tmp)+\" \"+RootBridge.quote(path)+\" || { rm -f \"+RootBridge.quote(tmp)+\"; exit 1; }"))
        val repo = File("src/main/java/io/github/xgl34222220/hetu/ProxyDashboardRepository.kt").readText()
        assertTrue(repo.contains("if (ProxyCoreSupport.localProviders(prefs)) SingBoxPanelStore.providers(singBoxPanel())"))
        assertTrue(repo.contains("if (ProxyCoreSupport.localProviders(prefs)) SingBoxPanelStore.ruleSets(singBoxPanel())"))
    }
}
