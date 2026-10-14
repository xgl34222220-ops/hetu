package io.github.xgl34222220.hetu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml

/** Network-loss fix (2026-10-14): validation probe hosts bypass the core, ad-block never blocks them, 256M default. */
class NetlossSelfHeal108Test {
    private val base = "mode: rule\nproxies: []\nproxy-groups:\n  - name: P\n    type: select\n    proxies: [DIRECT]\n"
    private val probes = CaptivePortalBypass.DEFAULT_HOSTS

    @Suppress("UNCHECKED_CAST")
    private fun dns(yaml: String): Map<String, Any?> = (Yaml().load<Map<String, Any?>>(yaml)["dns"] as Map<String, Any?>)

    @Suppress("UNCHECKED_CAST")
    private fun rules(yaml: String): List<String> = Yaml().load<Map<String, Any?>>(yaml)["rules"] as List<String>

    private fun profile(adblock: Boolean) = ProxyRuntimeProfile(
        ProxyRuntimeProfile.Core.MIHOMO, ProxyRuntimeProfile.Mode.TPROXY, ProxyRuntimeProfile.Ipv6.DISABLE,
        ProxyRuntimeProfile.AppScope.CORE, ProxyRuntimeProfile.DnsHijack.TPROXY,
        true, true, true, false, false, adblock)

    @Test
    fun hostsAreDefaultsPlusCaptivePortalSettingsWithoutGoogleSearchHosts() {
        assertEquals(listOf("connectivitycheck.gstatic.com", "connect.rom.miui.com", "www.qualcomm.cn"), CaptivePortalBypass.hosts(null))
        val hosts = CaptivePortalBypass.hosts(listOf(
            "http://connect.rom.miui.com/generate_204", "https://www.google.com/generate_204",
            "http://wifi.vivo.com.cn:80/generate_204,https://clients3.google.com/generate_204", null, "",
            "ftp://x.example/a", "http://10.0.0.1/generate_204", "http://user@evil.example/"))
        assertEquals(probes + "wifi.vivo.com.cn", hosts)
        assertNull(CaptivePortalBypass.host("https://www.google.com/generate_204"))
        assertNull(CaptivePortalBypass.host("http://connectivitycheck.android.com/generate_204"))
        assertEquals("captive.example.cn", CaptivePortalBypass.host("HTTPS://Captive.Example.CN./x?y"))
    }

    @Test
    fun fakeIpFilterGetsRealAddressesForProbeHostsInEveryFakeIpMode() {
        val created = CaptivePortalBypass.ensureFakeIpFilter("dns:\n  enable: true\n  enhanced-mode: fake-ip\n", probes)
        assertEquals("filter", created.state)
        assertEquals(probes + AdblockDnsPolicy.MIHOMO_DEFAULT_FAKE_IP_FILTER, dns(created.yaml)["fake-ip-filter"])
        val kept = CaptivePortalBypass.ensureFakeIpFilter("dns:\n  enhanced-mode: fake-ip\n  fake-ip-filter:\n    - '*.lan'\n    - connect.rom.miui.com\n", probes)
        assertEquals(listOf("connectivitycheck.gstatic.com", "www.qualcomm.cn", "*.lan", "connect.rom.miui.com"), dns(kept.yaml)["fake-ip-filter"])
        val rule = CaptivePortalBypass.ensureFakeIpFilter("dns:\n  enhanced-mode: fake-ip\n  fake-ip-filter-mode: rule\n  fake-ip-filter:\n  - MATCH,fake-ip\n", probes)
        assertEquals("rule", rule.state)
        assertEquals(probes.map { "DOMAIN,$it,real-ip" } + "MATCH,fake-ip", dns(rule.yaml)["fake-ip-filter"])
        assertEquals("whitelist-kept", CaptivePortalBypass.ensureFakeIpFilter("dns:\n  enhanced-mode: fake-ip\n  fake-ip-filter-mode: whitelist\n", probes).state)
        assertEquals("not-fake-ip", CaptivePortalBypass.ensureFakeIpFilter("dns:\n  enhanced-mode: redir-host\n", probes).state)
        assertEquals("no-dns", CaptivePortalBypass.ensureFakeIpFilter(base, probes).state)
    }

    @Test
    fun directRulesGoFirstAndEmptyHostsLeaveTheCopyUntouched() {
        val out = CaptivePortalBypass.insertDirectRules(base + "rules:\n  - RULE-SET,ads,REJECT\n  - MATCH,P\n", probes)
        assertEquals("inserted", out.state)
        assertEquals(probes.map { "DOMAIN,$it,DIRECT" } + listOf("RULE-SET,ads,REJECT", "MATCH,P"), rules(out.yaml))
        val unindented = CaptivePortalBypass.insertDirectRules("rules:\n- MATCH,P\n", probes)
        assertEquals(probes.map { "DOMAIN,$it,DIRECT" } + "MATCH,P", rules(unindented.yaml))
        assertEquals("inline-skipped", CaptivePortalBypass.insertDirectRules("rules: ['MATCH,DIRECT']\n", probes).state)
        val src = base + "rules:\n  - MATCH,P\n"
        assertEquals(src, CaptivePortalBypass.apply(src, emptyList()).yaml)
    }

    @Test
    fun rootStartupCopyPutsProbeHostsBeforeAdblockAndOldOverloadIsUnchanged() {
        val source = base + "dns:\n  enable: true\n  enhanced-mode: fake-ip\n  nameserver:\n    - 223.5.5.5\nrules:\n  - MATCH,P\n"
        val on = MihomoStartupConfig.generate(source, profile(true), "secret", MihomoStartupConfig.CONTROLLER_PORT,
            ProxyRuntimeProfile.AppScope.CORE, emptySet(), emptySet(), "", false, true, probes).yaml
        assertTrue(on, on.contains("# hetu-validation-bypass: fake-ip=filter rules=inserted"))
        val r = rules(on)
        assertEquals(probes.map { "DOMAIN,$it,DIRECT" }, r.take(probes.size))
        assertTrue(r.drop(probes.size).any { it.contains("REJECT") || it.contains("hetu-adblock") })
        assertTrue((dns(on)["fake-ip-filter"] as List<*>).containsAll(probes))
        val old = MihomoStartupConfig.generate(source, profile(true), "secret", MihomoStartupConfig.CONTROLLER_PORT,
            ProxyRuntimeProfile.AppScope.CORE, emptySet(), emptySet(), "", false, true).yaml
        assertFalse(old.contains("hetu-validation-bypass"))
        assertFalse(rules(old).contains("DOMAIN,connect.rom.miui.com,DIRECT"))
    }

    @Test
    fun dnsLayerAdblockNeverAnswersProbeHostsOrTheirParentsEmpty() {
        val dns = AdblockDnsPolicy.dnsBlockDomains(
            listOf("connect.rom.miui.com", "miui.com", "www.qualcomm.cn", "gstatic.com", "ads.example", "ad.miui.com"), emptyList())
        assertEquals(listOf("ad.miui.com", "ads.example"), dns)
    }

    @Test
    fun memoryLimitDefaultIs256M() {
        assertEquals("256M", RootProxyManager.DEFAULT_MEMORY_LIMIT)
        assertNull(HxResourceLimits.memoryError(RootProxyManager.DEFAULT_MEMORY_LIMIT))
    }
}
