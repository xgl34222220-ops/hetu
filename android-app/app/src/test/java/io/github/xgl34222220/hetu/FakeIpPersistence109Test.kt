package io.github.xgl34222220.hetu

import org.junit.Assert.assertEquals
import org.junit.Test
import org.yaml.snakeyaml.Yaml

/**
 * 2026-10 TikTok report: apps with their own DNS cache keep connecting to fake IPs handed out before a core
 * restart. The private runtime stores the fake-IP pool (profile.store-fake-ip) unless the source decides.
 */
class FakeIpPersistence109Test {
    private val base = "mode: rule\nproxies: []\nproxy-groups:\n  - name: P\n    type: select\n    proxies: [DIRECT]\n" +
        "dns:\n  enable: true\n  enhanced-mode: fake-ip\n  fake-ip-range: 198.18.0.1/16\nrules:\n  - MATCH,P\n"

    private val profile = ProxyRuntimeProfile(
        ProxyRuntimeProfile.Core.MIHOMO, ProxyRuntimeProfile.Mode.TPROXY, ProxyRuntimeProfile.Ipv6.ENABLE,
        ProxyRuntimeProfile.AppScope.CORE, ProxyRuntimeProfile.DnsHijack.TPROXY,
        true, true, true, false, false, false)

    @Suppress("UNCHECKED_CAST")
    private fun profileOf(yaml: String): Map<String, Any?> =
        Yaml().load<Map<String, Any?>>(yaml)["profile"] as Map<String, Any?>

    private fun generate(source: String) = MihomoStartupConfig.generate(source, profile, "secret").yaml

    @Test
    fun runtimeStoresFakeIpsWhenTheSourceHasNoProfile() {
        assertEquals(mapOf("store-fake-ip" to true), profileOf(generate(base)))
    }

    @Test
    fun existingProfileBlockGetsTheKeyAndKeepsItsOwnSettings() {
        val out = generate(base + "profile:\n    store-selected: true\n")
        assertEquals(mapOf("store-fake-ip" to true, "store-selected" to true), profileOf(out))
        assertEquals(1, Regex("(?m)^profile:").findAll(out).count())
    }

    @Test
    fun explicitSourceValueAndInlineProfileAreRespected() {
        assertEquals(false, profileOf(generate(base + "profile:\n  store-fake-ip: false\n"))["store-fake-ip"])
        val inline = "profile: {store-selected: true}\n"
        assertEquals(inline, MihomoStartupConfig.ensureStoreFakeIp(inline))
        assertEquals(mapOf("store-selected" to true), profileOf(generate(base + inline)))
    }
}
