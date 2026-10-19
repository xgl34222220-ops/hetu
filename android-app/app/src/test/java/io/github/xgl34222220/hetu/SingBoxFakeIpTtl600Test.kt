package io.github.xgl34222220.hetu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 6.0.0: sing-box answers fake-IP queries with TTL 600 s unless a rule rewrites it (checked with 1.14.3:
 * `ttl 600` without, `ttl 1` with rewrite_ttl 1). Mihomo answers 1 s, so an app never holds a fake address
 * longer than the core that issued it; converted and native sing-box configs now do the same.
 */
class SingBoxFakeIpTtl600Test {
    private fun options() = ProxyCoreConfig.Options().apply { tproxyPort = 19898; dnsPort = 11053; controllerPort = 29090; egressPort = 29150; secret = "s" }

    @Suppress("UNCHECKED_CAST")
    private fun dnsRules(config: String): List<Map<String, Any?>> =
        ((CoreJson.parseObject(config)["dns"] as Map<String, Any?>)["rules"] as List<Map<String, Any?>>)

    @Test fun convertedFakeIpAnswersLiveOneSecond() {
        val yaml = """
            proxies:
              - {name: A, type: trojan, server: a.example.com, port: 443, password: p}
            dns:
              enable: true
              enhanced-mode: fake-ip
              fake-ip-range: 198.18.0.1/16
              fake-ip-filter: ["+.lan"]
              nameserver: [223.5.5.5]
            rules:
              - MATCH,A
        """.trimIndent()
        val rules = dnsRules(ProxyCoreConfig.build(ProxyRuntimeProfile.Core.SING_BOX, yaml, options()) { null }.config)
        val fake = rules.single { it["server"] == "dns-fake" }
        assertEquals(1, (fake["rewrite_ttl"] as Number).toInt())
        // Real answers (the fake-ip-filter rule) keep the upstream TTL.
        assertFalse(rules.filter { it["server"] != "dns-fake" }.any { it.containsKey("rewrite_ttl") })
    }

    @Test fun nativeConfigFakeIpRulesGetOneSecondUnlessTheyChoseATtl() {
        val json = """
            {"outbounds":[{"type":"direct","tag":"direct"}],
             "dns":{"servers":[{"type":"udp","tag":"up","server":"223.5.5.5"},{"type":"fakeip","tag":"fake","inet4_range":"198.18.0.0/15"},
                               {"tag":"legacy","address":"fakeip"}],
                    "rules":[{"domain_suffix":["keep.example"],"server":"fake","rewrite_ttl":30},
                             {"query_type":["A","AAAA"],"action":"route","server":"fake"},
                             {"domain":["old.example"],"server":"legacy"},
                             {"domain":["real.example"],"action":"route","server":"up"},
                             {"domain":["blocked.example"],"action":"reject"}],
                    "final":"up"}}
        """.trimIndent()
        val rules = dnsRules(ProxyCoreConfig.build(ProxyRuntimeProfile.Core.SING_BOX, json, options()) { null }.config)
        assertEquals(30, (rules[0]["rewrite_ttl"] as Number).toInt())
        assertEquals(1, (rules[1]["rewrite_ttl"] as Number).toInt())
        assertEquals(1, (rules[2]["rewrite_ttl"] as Number).toInt())
        assertFalse(rules[3].containsKey("rewrite_ttl"))
        assertFalse(rules[4].containsKey("rewrite_ttl"))
    }
}
