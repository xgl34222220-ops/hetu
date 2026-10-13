package io.github.xgl34222220.hetu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml

/** WeChat message latency (2026-10-13): fake-ip-filter, DIRECT rules, kernel bypass opt-in, hints. */
class WechatLatency107Test {
    private val base = "mode: rule\nproxies: []\nproxy-groups:\n  - name: P\n    type: select\n    proxies: [DIRECT]\n"

    @Suppress("UNCHECKED_CAST")
    private fun dns(yaml: String): Map<String, Any?> = (Yaml().load<Map<String, Any?>>(yaml)["dns"] as Map<String, Any?>)

    @Suppress("UNCHECKED_CAST")
    private fun rules(yaml: String): List<String> = Yaml().load<Map<String, Any?>>(yaml)["rules"] as List<String>

    private fun profile(cn: Boolean = false, adblock: Boolean = false) = ProxyRuntimeProfile(
        ProxyRuntimeProfile.Core.MIHOMO, ProxyRuntimeProfile.Mode.TPROXY, ProxyRuntimeProfile.Ipv6.DISABLE,
        ProxyRuntimeProfile.AppScope.CORE, ProxyRuntimeProfile.DnsHijack.TPROXY,
        true, true, true, false, cn, adblock)

    private fun gen(source: String, wechat: Boolean, cn: Boolean = false): String =
        MihomoStartupConfig.generate(source, profile(cn), "secret", MihomoStartupConfig.CONTROLLER_PORT,
            ProxyRuntimeProfile.AppScope.CORE, emptySet(), emptySet(), "", false, wechat).yaml

    @Test
    fun fakeIpBlacklistGetsWechatRealIpEntriesAndKeepsUserEntries() {
        val out = WechatTransport.ensureFakeIpFilter("dns:\n  enable: true\n  enhanced-mode: fake-ip\n  fake-ip-filter:\n    - '*.lan'\n")
        assertEquals("filter", out.state)
        assertEquals(listOf("+.weixin.qq.com", "+.qq.com", "+.wechat.com", "*.lan"), dns(out.yaml)["fake-ip-filter"])
    }

    @Test
    fun fakeIpFilterCreatedWithMihomoDefaultsAndRuleModeGoesFirst() {
        val created = WechatTransport.ensureFakeIpFilter("dns:\n  enable: true\n  enhanced-mode: fake-ip\n")
        assertEquals(WechatTransport.FAKE_IP_FILTER + AdblockDnsPolicy.MIHOMO_DEFAULT_FAKE_IP_FILTER, dns(created.yaml)["fake-ip-filter"])
        val rule = WechatTransport.ensureFakeIpFilter("dns:\n  enhanced-mode: fake-ip\n  fake-ip-filter-mode: rule\n  fake-ip-filter:\n  - GEOSITE,cn,real-ip\n  - MATCH,fake-ip\n")
        assertEquals("rule", rule.state)
        assertEquals(WechatTransport.FAKE_IP_RULES + listOf("GEOSITE,cn,real-ip", "MATCH,fake-ip"), dns(rule.yaml)["fake-ip-filter"])
        val empty = WechatTransport.ensureFakeIpFilter("dns:\n  enhanced-mode: fake-ip\n  fake-ip-filter: []\n")
        assertEquals(WechatTransport.FAKE_IP_FILTER, dns(empty.yaml)["fake-ip-filter"])
    }

    @Test
    fun fakeIpFilterRespectsWhitelistRedirHostInlineAndUserEntries() {
        val white = "dns:\n  enhanced-mode: fake-ip\n  fake-ip-filter-mode: whitelist\n  fake-ip-filter:\n    - '+.google.com'\n"
        assertEquals("whitelist-kept", WechatTransport.ensureFakeIpFilter(white).state)
        assertEquals(white, WechatTransport.ensureFakeIpFilter(white).yaml)
        assertEquals("not-fake-ip", WechatTransport.ensureFakeIpFilter("dns:\n  enhanced-mode: redir-host\n").state)
        assertEquals("no-dns", WechatTransport.ensureFakeIpFilter("mode: rule\n").state)
        assertEquals("inline-skipped", WechatTransport.ensureFakeIpFilter("dns:\n  enhanced-mode: fake-ip\n  fake-ip-filter: ['+.lan']\n").state)
        val user = "dns:\n  enhanced-mode: fake-ip\n  fake-ip-filter:\n    - '+.qq.com'\n"
        assertEquals("user-entry", WechatTransport.ensureFakeIpFilter(user).state)
        assertEquals(user, WechatTransport.ensureFakeIpFilter(user).yaml)
    }

    @Test
    fun directRulesGoRightBeforeFinalMatchAndNeverOverrideWechatPolicy() {
        val out = WechatTransport.insertDirectRules(base + "rules:\n  - GEOIP,CN,DIRECT\n  - MATCH,P\n")
        assertEquals("inserted", out.state)
        assertEquals(listOf("GEOIP,CN,DIRECT") + WechatTransport.DIRECT_RULES + "MATCH,P", rules(out.yaml))
        for (own in listOf("DOMAIN-SUFFIX,weixin.qq.com,P", "PROCESS-NAME,com.tencent.mm,P", "RULE-SET,WeChat,P", "DOMAIN-SUFFIX,qq.com,DIRECT")) {
            val src = base + "rules:\n  - $own\n  - MATCH,P\n"
            val kept = WechatTransport.insertDirectRules(src)
            assertEquals(own, "user-policy", kept.state)
            assertEquals(src, kept.yaml)
        }
        assertEquals("inline-skipped", WechatTransport.insertDirectRules("rules: ['MATCH,DIRECT']\n").state)
        assertEquals("no-rules", WechatTransport.insertDirectRules(base).state)
        val unindented = WechatTransport.insertDirectRules("rules:\n- MATCH,P\ndns:\n  enable: true\n")
        assertEquals(WechatTransport.DIRECT_RULES + "MATCH,P", rules(unindented.yaml))
        val noMatch = WechatTransport.insertDirectRules("rules:\n  - GEOIP,CN,DIRECT\n")
        assertEquals(listOf("GEOIP,CN,DIRECT") + WechatTransport.DIRECT_RULES, rules(noMatch.yaml))
    }

    @Test
    fun startupCopyAppliesWechatTransportOnlyWhenAsked() {
        val source = base + "dns:\n  enable: true\n  enhanced-mode: fake-ip\n  nameserver:\n    - 223.5.5.5\nrules:\n  - MATCH,P\n"
        val off = gen(source, false)
        assertFalse(off.contains("hetu-wechat-transport"))
        assertFalse(off.contains("weixin.qq.com"))
        val on = gen(source, true, cn = true)
        assertTrue(on, on.contains("# hetu-wechat-transport: fake-ip=filter rules=inserted"))
        assertTrue((dns(on)["fake-ip-filter"] as List<*>).containsAll(WechatTransport.FAKE_IP_FILTER))
        val r = rules(on)
        // Source order is kept: CNIP rules and WeChat rules both sit before the final MATCH.
        assertEquals("MATCH,P", r.last())
        assertTrue(r.indexOf("DOMAIN-SUFFIX,weixin.qq.com,DIRECT") in 0 until r.size - 1)
        assertTrue(r.contains("RULE-SET,hetu-cn-v4,DIRECT,no-resolve"))
    }

    @Test
    fun kernelBypassIsOptInAndNeverOverridesSelectedAppsOnly() {
        assertEquals(emptySet<String>(), WechatTransport.kernelDirectPackages(false, "core"))
        assertEquals(setOf("com.tencent.mm"), WechatTransport.kernelDirectPackages(true, "core"))
        assertEquals(setOf("com.tencent.mm"), WechatTransport.kernelDirectPackages(true, "blacklist"))
        assertEquals(emptySet<String>(), WechatTransport.kernelDirectPackages(true, "whitelist"))
    }

    @Test
    fun messageTransportExceptionsCoverHongKongHostsOnlyUnderWeixin() {
        val exceptions = MessagingFilterPolicy.subscriptionExceptions(emptySet())
        for (host in listOf("long.weixin.qq.com", "hklong.weixin.qq.com", "hkshort.weixin.qq.com", "hkextshort.weixin.qq.com"))
            assertTrue(host, exceptions.contains(host))
        assertTrue(exceptions.all { it.endsWith(".weixin.qq.com") })
        assertTrue(MessagingFilterPolicy.subscriptionExceptions(setOf("weixin.qq.com")).isEmpty())
        assertFalse(MessagingFilterPolicy.subscriptionExceptions(setOf("hklong.weixin.qq.com")).contains("hklong.weixin.qq.com"))
    }

    @Test
    fun diagnosticHintsNameBatteryProxiedFlowsAndKeepAlive() {
        assertNull(WechatTransport.batteryHint(true, true))
        assertNull(WechatTransport.batteryHint(false, false))
        assertNull(WechatTransport.batteryHint(true, null))
        val hints = WechatTransport.hints(true, false, 3, 1, false, false, "core", true)
        assertTrue(hints[0].contains("电池优化"))
        assertTrue(hints.any { it.contains("3 条微信连接经代理节点") })
        assertTrue(hints.any { it.contains("微信直连（绕过核心）") })
        assertTrue(hints.any { it.contains("disable-keep-alive") })
        val clean = WechatTransport.hints(true, true, 0, 2, true, false, "core", false)
        assertEquals(listOf("当前识别到的微信连接都是直连"), clean)
        assertEquals(listOf("未检测到微信（com.tencent.mm）"), WechatTransport.hints(false, null, 0, 0, false, false, "core", false))
    }
}
