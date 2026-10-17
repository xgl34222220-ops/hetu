package io.github.xgl34222220.hetu

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.Base64

/**
 * Golden conversion of one Clash/Mihomo profile (every node type Hetu converts, every rule kind) and
 * of one native config per core. The golden outputs were checked with the real cores:
 * `sing-box check` (1.14.3), `xray run -test` (26.10.10), `v2ray test` (5.54.2), Hysteria 2.13.0.
 * Regenerate them only together with that check.
 */
@RunWith(RobolectricTestRunner::class)
class ProxyCoreConfigGoldenTest {
    private fun resource(name: String): String =
        javaClass.classLoader!!.getResource("multicore/$name")!!.readText(Charsets.UTF_8)

    private fun options() = ProxyCoreConfig.Options().apply {
        tproxyPort = 19898; dnsPort = 11053; controllerPort = 29090; egressPort = 29150; secret = "fixture-secret"
        adblock = listOf("ads.example.net", "tracker.example.org"); adblockAllow = listOf("ok.tracker.example.org")
    }

    private fun build(core: ProxyRuntimeProfile.Core, source: String, o: ProxyCoreConfig.Options = options(),
                      fetch: (String) -> String? = { null }) =
        ProxyCoreConfig.build(core, resource(source), o) { fetch(it) }

    @Test fun convertedAndNativeConfigsMatchTheirGoldenFiles() {
        val jobs = listOf(
            Triple(ProxyRuntimeProfile.Core.SING_BOX, "mihomo-source.yaml", "singbox-converted.json"),
            Triple(ProxyRuntimeProfile.Core.SING_BOX_REF1ND, "mihomo-source.yaml", "singbox-converted.json"),
            Triple(ProxyRuntimeProfile.Core.XRAY, "mihomo-source.yaml", "xray-converted.json"),
            Triple(ProxyRuntimeProfile.Core.V2FLY, "mihomo-source.yaml", "v2ray-converted.json"),
            Triple(ProxyRuntimeProfile.Core.HYSTERIA, "mihomo-source.yaml", "hysteria-converted.yaml"),
            Triple(ProxyRuntimeProfile.Core.SING_BOX, "singbox-native.json", "singbox-native-out.json"),
            Triple(ProxyRuntimeProfile.Core.XRAY, "xray-native.json", "xray-native-out.json"),
            Triple(ProxyRuntimeProfile.Core.V2FLY, "xray-native.json", "v2ray-native-out.json"),
            Triple(ProxyRuntimeProfile.Core.HYSTERIA, "hysteria-native.yaml", "hysteria-native-out.yaml"),
        )
        for ((core, source, golden) in jobs) {
            assertEquals("$core $source", resource("golden/$golden"), build(core, source).config)
        }
    }

    @Test fun singBoxGetsEverySupportedNodeAndAWarningForTheRest() {
        val result = build(ProxyRuntimeProfile.Core.SING_BOX, "mihomo-source.yaml")
        assertFalse(result.nativeSource)
        assertEquals(9, result.nodes)
        assertTrue(result.warnings.any { it.contains("Snell") && it.contains("不受支持") })
        val root = JSONObject(result.config)
        val types = (0 until root.getJSONArray("outbounds").length()).map { root.getJSONArray("outbounds").getJSONObject(it).getString("type") }
        for (t in listOf("shadowsocks", "vmess", "vless", "trojan", "hysteria2", "tuic", "urltest", "selector", "direct")) assertTrue(t, t in types)
        val reality = (0 until root.getJSONArray("outbounds").length()).map { root.getJSONArray("outbounds").getJSONObject(it) }
            .first { it.getString("tag") == "US VLESS Reality" }.getJSONObject("tls").getJSONObject("reality")
        assertEquals("6ba85179e30d4fc2", reality.getString("short_id"))
        // Hetu's ingress on Hetu's ports; the clash_api answers on the controller port.
        val inbounds = root.getJSONArray("inbounds")
        assertEquals("tproxy", inbounds.getJSONObject(0).getString("type"))
        assertEquals(19898, inbounds.getJSONObject(0).getInt("listen_port"))
        assertEquals(11053, inbounds.getJSONObject(1).getInt("listen_port"))
        assertEquals("127.0.0.1:29090", root.getJSONObject("experimental").getJSONObject("clash_api").getString("external_controller"))
        // Loop prevention is the owner exemption in hetu-root.sh: never an SO_MARK on Android.
        assertFalse(result.config.contains("routing_mark"))
        assertFalse(result.config.contains("default_mark"))
        assertEquals("198.18.0.1/16", result.fakeIpV4)
        assertTrue(result.adblockApplied)
    }

    @Test fun xrayAndV2flyKeepWhatTheyCanRunAndSayWhatTheyDrop() {
        val xray = build(ProxyRuntimeProfile.Core.XRAY, "mihomo-source.yaml")
        assertEquals(6, xray.nodes)
        assertTrue(xray.warnings.any { it.contains("KR Hy2") })
        assertTrue(xray.warnings.any { it.contains("PROCESS-NAME") })
        val root = JSONObject(xray.config)
        val tproxy = root.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("tproxy", tproxy.getJSONObject("streamSettings").getJSONObject("sockopt").getString("tproxy"))
        assertFalse(xray.config.contains("\"mark\""))
        // Balancer selectors are prefix matches: node tags carry a fixed-width index.
        val selector = root.getJSONObject("routing").getJSONArray("balancers").getJSONObject(0).getJSONArray("selector")
        assertEquals(listOf("0001 HK SS", "0004 JP VMess WS", "0007 TW Trojan"), (0 until selector.length()).map { selector.getString(it) })
        val v2 = build(ProxyRuntimeProfile.Core.V2FLY, "mihomo-source.yaml")
        assertEquals(4, v2.nodes)
        assertTrue(v2.warnings.any { it.contains("VLESS flow") })
        assertFalse(v2.config.contains("reality"))
    }

    @Test fun hysteriaIsOneServerWithTproxyListenersAndAPreResolvedServer() {
        val o = options().apply { hostResolver = ProxyCoreConfig.HostResolver { if (it == "hy2.example.com") "203.0.113.9" else null } }
        val result = build(ProxyRuntimeProfile.Core.HYSTERIA, "mihomo-source.yaml", o)
        assertTrue(result.config.contains("server: \"203.0.113.9:8443\""))
        assertTrue(result.config.contains("sni: \"hy2.example.com\""))
        assertTrue(result.config.contains("tcpTProxy:\n  listen: :19898"))
        assertTrue(result.config.contains("udpTProxy:\n  listen: :19898"))
        assertTrue(result.warnings.first().contains("单服务器"))
        assertFalse(result.adblockApplied)
        val native = build(ProxyRuntimeProfile.Core.HYSTERIA, "hysteria-native.yaml", o)
        assertTrue(native.nativeSource)
        assertFalse(native.config.contains("socks5:"))
        assertFalse(native.config.contains("127.0.0.1:8080"))
    }

    @Test fun nativeConfigsKeepTheirOutboundsAndLoseOnlyHetuOwnedIngressAndMarks() {
        val sb = build(ProxyRuntimeProfile.Core.SING_BOX, "singbox-native.json")
        assertTrue(sb.nativeSource)
        val root = JSONObject(sb.config)
        assertEquals("proxy", root.getJSONObject("route").getString("final"))
        assertFalse(sb.config.contains("mixed-in"))
        assertFalse(sb.config.contains("routing_mark"))
        assertTrue(sb.warnings.any { it.contains("default_mark") })
        val xr = build(ProxyRuntimeProfile.Core.XRAY, "xray-native.json")
        assertTrue(xr.warnings.any { it.contains("sockopt.mark") })
        assertFalse(xr.config.contains("10808"))
        assertTrue(ProxyCoreConfig.isNativeFor(ProxyRuntimeProfile.Core.HYSTERIA, resource("hysteria-native.yaml")))
        assertFalse(ProxyCoreConfig.isNativeFor(ProxyRuntimeProfile.Core.HYSTERIA, resource("mihomo-source.yaml")))
    }

    @Test fun subscriptionProvidersAndShareLinksAreConverted() {
        val links = listOf(
            "trojan://pw@t.example.com:443?sni=t.example.com#Trojan%20One",
            "vless://6f2a1c4e-8b3d-4e5f-a6b7-c8d9e0f1a2b3@198.51.100.1:443?security=reality&pbk=key&sid=ab&sni=www.example.com&fp=chrome&flow=xtls-rprx-vision&type=tcp#Reality",
            "hysteria2://secret@hy.example.com:443?sni=hy.example.com&obfs=salamander&obfs-password=o#Hy2",
            "ss://" + Base64.getEncoder().encodeToString("aes-128-gcm:pw".toByteArray()) + "@1.2.3.4:8388#SS",
        )
        val body = Base64.getEncoder().encodeToString(links.joinToString("\n").toByteArray())
        val yaml = """
            proxy-providers:
              sub:
                type: http
                url: https://sub.example.com/link
            proxy-groups:
              - {name: Proxy, type: select, use: [sub]}
            rules:
              - MATCH,Proxy
        """.trimIndent()
        val result = ProxyCoreConfig.build(ProxyRuntimeProfile.Core.SING_BOX, yaml, options()) { url ->
            assertEquals("https://sub.example.com/link", url); body
        }
        assertEquals(4, result.nodes)
        val outbounds = JSONObject(result.config).getJSONArray("outbounds")
        val byTag = (0 until outbounds.length()).associate { outbounds.getJSONObject(it).getString("tag") to outbounds.getJSONObject(it) }
        assertEquals("trojan", byTag.getValue("Trojan One").getString("type"))
        assertEquals("key", byTag.getValue("Reality").getJSONObject("tls").getJSONObject("reality").getString("public_key"))
        assertEquals("salamander", byTag.getValue("Hy2").getJSONObject("obfs").getString("type"))
        assertEquals("aes-128-gcm", byTag.getValue("SS").getString("method"))
        assertEquals(JSONArray(listOf("Trojan One", "Reality", "Hy2", "SS")).toString(), byTag.getValue("Proxy").getJSONArray("outbounds").toString())
    }

    @Test fun unreachableProvidersAndUnsupportedRulesAreExplicitWarnings() {
        val yaml = """
            proxies:
              - {name: A, type: trojan, server: a.example.com, port: 443, password: p}
            proxy-providers:
              gone: {type: http, url: https://gone.example.com/sub}
            rules:
              - IP-ASN,13335,A
              - SUB-RULE,(NETWORK,tcp),sub
              - MATCH,A
        """.trimIndent()
        val result = ProxyCoreConfig.build(ProxyRuntimeProfile.Core.SING_BOX, yaml, options()) { null }
        assertTrue(result.warnings.any { it.contains("gone") && it.contains("无法下载") })
        assertTrue(result.warnings.any { it.contains("IP-ASN") })
        assertEquals("A", JSONObject(result.config).getJSONObject("route").getString("final"))
    }

    @Test fun nonConvertibleInputFailsWithAReason() {
        val error = runCatching {
            ProxyCoreConfig.build(ProxyRuntimeProfile.Core.XRAY, "mode: rule\n", options()) { null }
        }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is IOException)
        assertTrue(error!!.message!!.contains("Clash/Mihomo"))
        val tun = runCatching {
            ProxyCoreConfig.build(ProxyRuntimeProfile.Core.SING_BOX, resource("singbox-native.json"),
                options().apply { mode = ProxyRuntimeProfile.Mode.TUN }) { null }
        }.exceptionOrNull()
        assertTrue(tun!!.message!!.contains("TPROXY"))
    }

    @Test fun adblockAllowListIsSubtractedForXray() {
        assertEquals(listOf("ads.example.net"),
            ProxyCoreConfig.subtractAllowed(listOf("ads.example.net", "x.ok.example.org", "ok.example.org"), listOf("ok.example.org")))
        assertEquals("sing-box", ProxyCoreConfig.kind(ProxyRuntimeProfile.Core.SING_BOX_REF1ND).id)
        assertEquals("v2ray", ProxyCoreConfig.kind(ProxyRuntimeProfile.Core.V2FLY).id)
        assertTrue(ProxyCoreConfig.clashApi(ProxyRuntimeProfile.Core.SING_BOX))
        assertFalse(ProxyCoreConfig.clashApi(ProxyRuntimeProfile.Core.XRAY))
    }
}
