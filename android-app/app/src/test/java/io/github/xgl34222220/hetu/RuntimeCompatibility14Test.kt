package io.github.xgl34222220.hetu

import org.junit.Assert.*
import org.junit.Test
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.util.Properties

class RuntimeCompatibility14Test {
    private fun profile(mode: ProxyRuntimeProfile.Mode = ProxyRuntimeProfile.Mode.TPROXY,
        scope: ProxyRuntimeProfile.AppScope = ProxyRuntimeProfile.AppScope.CORE) = ProxyRuntimeProfile(
        ProxyRuntimeProfile.Core.MIHOMO, mode, ProxyRuntimeProfile.Ipv6.BYPASS, scope,
        ProxyRuntimeProfile.DnsHijack.TPROXY, true, true, true, false, false, false)
    private fun generate(text: String) = MihomoStartupConfig.generate(text, profile()).yaml
    private fun parse(text: String): Map<String, Any?> = Yaml().load(text)
    @Suppress("UNCHECKED_CAST")
    private fun proxies(text: String) = parse(text)["proxies"] as List<Map<String, Any?>>
    private fun source(nodes: String) = "global-client-fingerprint: chrome\nmode: rule\nproxies:\n$nodes\nrules:\n  - MATCH,DIRECT\n"

    @Test fun removedGlobalFingerprintMigratesToMissingPerNodeField() {
        val raw = source("  - name: A\n    type: vless\n    server: example.test\n    port: 443\n    uuid: abc")
        val unchanged = raw.toByteArray().copyOf()
        val result = generate(raw)
        assertEquals("chrome", proxies(result)[0]["client-fingerprint"])
        assertFalse(parse(result).containsKey("global-client-fingerprint"))
        assertArrayEquals(unchanged, raw.toByteArray())
        assertEquals("example.test", proxies(result)[0]["server"])
    }
    @Test fun explicitPerNodeFingerprintIsNeverOverwritten() {
        val raw = source("  - {name: A, type: trojan, client-fingerprint: firefox, server: example.test}")
        assertEquals("firefox", proxies(generate(raw))[0]["client-fingerprint"])
    }
    @Test fun blockAndFlowNodesHandleNonBmpUnicodeOffsets() {
        val raw = source("  - name: '🇸🇬 新加坡'\n    type: vmess\n    server: a.test\n  - {name: '🇯🇵 东京', type: vless, server: b.test}")
        val result = proxies(generate(raw))
        assertEquals(listOf("🇸🇬 新加坡", "🇯🇵 东京"), result.map { it["name"] })
        assertTrue(result.all { it["client-fingerprint"] == "chrome" })
    }
    @Test fun fingerprintsInheritedFromYamlMergeArePreserved() {
        val raw = "base: &base {client-fingerprint: safari, type: vless}\n" + source("  - name: A\n    <<: *base\n    server: example.test")
        assertEquals("safari", proxies(generate(raw))[0]["client-fingerprint"])
    }
    @Test fun inheritedProtocolWithoutFingerprintGetsMigrated() {
        val raw = "base: &base {type: trojan}\n" + source("  - name: A\n    <<: *base\n    server: example.test")
        assertEquals("chrome", proxies(generate(raw))[0]["client-fingerprint"])
    }
    @Test fun noGlobalFingerprintDoesNotInventOne() {
        val raw = "proxies:\n  - {name: A, type: vless, server: a.test}\nrules: ['MATCH,DIRECT']\n"
        assertEquals(raw, RuntimeCompatibility14.migrateFingerprint(raw))
        assertFalse(proxies(generate(raw))[0].containsKey("client-fingerprint"))
    }
    @Test fun nonTlsProtocolsAreNotChanged() {
        val raw = source("  - {name: A, type: ss, server: a.test}\n  - {name: B, type: socks5, server: b.test}")
        assertTrue(proxies(generate(raw)).none { it.containsKey("client-fingerprint") })
    }
    @Test fun remoteProviderOverrideDoesNotClobberItsExplicitNodes() {
        val raw = source("  - {name: A, type: vless}") + "proxy-providers:\n  remote:\n    type: http\n    url: https://example.test/private?token=secret\n    override: {client-fingerprint: safari}\n"
        val before = parse(raw)["proxy-providers"]
        assertEquals(before, parse(generate(raw))["proxy-providers"])
    }
    @Test fun inlineProviderPayloadIsMigratedWithoutProviderWideOverride() {
        val raw = "global-client-fingerprint: chrome\nproxy-providers:\n  inline:\n    type: inline\n    payload:\n      - {name: A, type: vless, server: a.test}\nrules: ['MATCH,DIRECT']\n"
        val result = RuntimeCompatibility14.migrateFingerprint(raw)
        assertTrue(result.contains("client-fingerprint: 'chrome'"))
        assertFalse(result.contains("override:"))
        parse(result)
    }
    @Test fun explicitDisableAndKeepaliveIntervalsRemainExactlyAsConfigured() {
        val raw = source("  - {name: A, type: vless}") + "disable-keep-alive: true\nkeep-alive-idle: 120\nkeep-alive-interval: 60\n"
        val output = parse(generate(raw))
        assertEquals(true, output["disable-keep-alive"])
        assertEquals(120, output["keep-alive-idle"])
        assertEquals(60, output["keep-alive-interval"])
    }
    @Test fun fakeIpRuleModeDoesNotTreatRuleExpressionsAsDomainPatterns() {
        val raw = "mode: rule\ndns:\n  enhanced-mode: fake-ip\n  fake-ip-filter-mode: rule\n  fake-ip-filter:\n    - 'RULE-SET,My Rules,real-ip'\n    - 'DOMAIN-SUFFIX,qq.com,real-ip'\n    - 'MATCH,fake-ip'\nrules:\n  - MATCH,DIRECT\n"
        val result = generate(raw)
        assertTrue(result.contains("    - 'RULE-SET,My Rules,real-ip'"))
        assertTrue(result.contains("    - 'MATCH,fake-ip'"))
        assertFalse(result.contains("skipped invalid domain entry"))
    }
    @Test fun blacklistDomainSanitizationStillRemovesInvalidHumanLabels() {
        val raw = "dns:\n  fake-ip-filter:\n    - Mijia Cloud\n    - '+.local'\nrules:\n  - MATCH,DIRECT\n"
        val result = generate(raw)
        assertTrue(result.contains("# Hetu 1.19.30 compatibility: skipped invalid domain entry: Mijia Cloud"))
        assertFalse(result.contains("\n    - Mijia Cloud\n"))
        assertTrue(result.contains("    - '+.local'"))
    }
    @Test fun tunCoreScopeIgnoresStaleApplicationExclusionsButRetainsController() {
        val p = profile(ProxyRuntimeProfile.Mode.TUN)
        val out = MihomoStartupConfig.generate("mode: rule\nproxies: []\nrules: ['MATCH,DIRECT']\n", p,
            "test-secret", 29090, ProxyRuntimeProfile.AppScope.CORE, setOf("com.example.stale"),
            setOf("io.github.xgl34222220.hetu"), "").yaml
        assertFalse(out.contains("com.example.stale"))
        assertTrue(out.contains("io.github.xgl34222220.hetu"))
    }
    @Test fun tunBlacklistScopeStillHonorsExplicitUserApplications() {
        val p = profile(ProxyRuntimeProfile.Mode.TUN, ProxyRuntimeProfile.AppScope.BLACKLIST)
        val out = MihomoStartupConfig.generate("proxies: []\nrules: ['MATCH,DIRECT']\n", p,
            "test-secret", 29090, p.appScope, setOf("com.example.bypass"), emptySet(), "").yaml
        assertTrue(out.contains("com.example.bypass"))
    }
    @Test fun validSnapshotPairIsReusableAndCorruptionIsDetectedEvenAtSameSize() {
        val dir = Files.createTempDirectory("hetu14-cache").toFile()
        try {
            val block = dir.resolve("block").apply { writeText("+.ads.test\n") }
            val allow = dir.resolve("allow").apply { writeText("+.allow.test\n") }
            val meta = Properties().apply {
                setProperty("blockSha256", RuntimeCompatibility14.sha256(block))
                setProperty("allowSha256", RuntimeCompatibility14.sha256(allow))
            }
            assertTrue(RuntimeCompatibility14.cachedPairValid(meta,block,allow))
            block.writeText("+.bad.test\n")
            assertFalse(RuntimeCompatibility14.cachedPairValid(meta,block,allow))
            block.writeText("+.ads.test\n"); allow.writeText("")
            assertFalse(RuntimeCompatibility14.cachedPairValid(meta,block,allow))
            allow.delete()
            assertFalse(RuntimeCompatibility14.cachedPairValid(meta,block,allow))
        } finally { dir.deleteRecursively() }
    }
    @Test fun oldRevisionOnlyMetadataCannotAuthorizeUnverifiedCache() {
        val dir = Files.createTempDirectory("hetu14-legacy").toFile()
        try {
            val block=dir.resolve("block").apply { writeText("+.ads.test\n") }
            val allow=dir.resolve("allow").apply { writeText("") }
            val meta=Properties().apply { setProperty("revision","same");setProperty("count","1") }
            assertFalse(RuntimeCompatibility14.cachedPairValid(meta,block,allow))
        } finally { dir.deleteRecursively() }
    }
    @Test fun filterSuccessMessageDistinguishesLoadedFromActuallyParticipating() {
        assertTrue(RuntimeCompatibility14.filterReloadMessage("rule",123).contains("核心已加载"))
        assertTrue(RuntimeCompatibility14.filterReloadMessage("global",123).contains("不参与分流"))
        assertTrue(RuntimeCompatibility14.filterReloadMessage("direct",123).contains("不参与分流"))
        assertTrue(RuntimeCompatibility14.filterReloadMessage("rule",0).contains("为空"))
    }
    @Test fun diagnosticSummaryDoesNotExposeUrlsSecretsNodeNamesOrKeys() {
        val input="mode: rule\nsecret: dont-share\nglobal-client-fingerprint: chrome\ndisable-keep-alive: true\nproxy-providers:\n  private: {url: 'https://example.test/?token=private'}\ndns:\n  enhanced-mode: fake-ip\n  nameserver: ['https://dns.test/secret-path']\n"
        val summary=RuntimeCompatibility14.safeConfigSummary(input)
        assertTrue(summary.contains("dns.enhanced-mode=fake-ip"))
        assertTrue(summary.contains("禁用了 TCP 保活"))
        assertFalse(summary.contains("dont-share"));assertFalse(summary.contains("https://"))
        assertFalse(summary.contains("secret-path"));assertFalse(summary.contains("token="))
    }
    @Test fun invalidLegacyFingerprintFailsBeforeAnySourceRewrite() {
        val raw="global-client-fingerprint: 'chrome; secret'\nproxies: []\n"
        try { RuntimeCompatibility14.migrateFingerprint(raw); fail("invalid fingerprint accepted") }
        catch (expected: java.io.IOException) { assertFalse(expected.message.orEmpty().contains("secret")) }
        assertTrue(raw.contains("chrome; secret"))
    }
    @Test fun nodeTitleProjectionNeverInventsGeographyOrChangesRawIdentity() {
        val raw="[IPLC-移动优化-新加坡-VLESS] 2x [永久]"
        val label=nodeLabel14(raw)
        assertEquals("移动优化 · 新加坡",label.title)
        assertEquals(listOf("IPLC","VLESS","2x","永久"),label.tags)
        assertEquals("Node 001",nodeLabel14("Node 001").title)
        assertEquals("VLESS",nodeLabel14("VLESS").title)
        assertEquals(raw,"[IPLC-移动优化-新加坡-VLESS] 2x [永久]")
    }
    @Test fun latencyBoundariesStayReadableWithoutInventingMeasurements() {
        assertEquals(DelayTone14.Good,delayTone14(99))
        assertEquals(DelayTone14.Medium,delayTone14(100))
        assertEquals(DelayTone14.Medium,delayTone14(299))
        assertEquals(DelayTone14.Slow,delayTone14(300))
        assertEquals(DelayTone14.Failed,delayTone14(-1))
        assertEquals("未测",delayText14(null));assertEquals("失败",delayText14(-2))
    }
}
