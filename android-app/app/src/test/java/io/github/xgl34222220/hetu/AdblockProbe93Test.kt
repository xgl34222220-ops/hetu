package io.github.xgl34222220.hetu

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 拦截实测 by controller counters, DNS 接管实测, DNS 路径 and the page hints. */
class AdblockProbe93Test {
    private val adPayload = "((RuleSet,hetu-adblock) && (NOT,(!(RuleSet,hetu-adblock-allow))))"
    private fun rules(adHits: Long, adMiss: Long = 0, routeHits: Long = 0) = listOf(
        ProxyRuleUi(0, "ProcessName", "com.example", "DIRECT", hitCount = 0),
        ProxyRuleUi(1, "AND", adPayload, "REJECT", hitCount = adHits, missCount = adMiss),
        ProxyRuleUi(2, "Match", "", "节点选择", hitCount = routeHits),
    )

    private fun chain(
        running: Boolean = true, hits: Long = 0, miss: Long = 0, fromController: Boolean = true, dnsLayer: Boolean = true,
        privateMode: String = "off", privateHost: String = "", sniffer: String = "enabled", cnip: String = "off",
        tcpOnly: Boolean = false, sourceHasDns: Boolean = true, scope: String = "core", scoped: Int = 0,
    ) = ToolsAdblockChain(
        running = running, mode = "rule", ruleMode = true, startupInjected = true, controllerLoaded = true,
        hits = hits, recent = emptyList(), missCount = miss, hitsFromController = fromController,
        report = AdblockVerification.inspect("rule", rules(hits, miss), emptyList()),
        dnsLayer = dnsLayer, enhancedMode = "redir-host", snifferState = sniffer, privateDnsMode = privateMode,
        privateDnsHost = privateHost, systemDns = "captured", dnsHijackMode = "tproxy", dotGuard = true,
        dnsIpv6Rule = true, cnip = cnip, cnipTcpOnly = tcpOnly, sourceHasDns = sourceHasDns, appScope = scope, scopedApps = scoped,
    )

    @Test fun rejectHitCountDeltaIsTheVerdict() {
        val verdict = AdblockVerification.verdictFromCounters(rules(4), rules(5), "doubleclick.net")
        assertEquals(true, verdict?.ok)
        assertTrue(verdict!!.detail.contains("hitCount +1"))
    }

    @Test fun anotherRuleMovingMeansNotBlocked() {
        val verdict = AdblockVerification.verdictFromCounters(rules(4, routeHits = 9), rules(4, routeHits = 10), "doubleclick.net")
        assertEquals(false, verdict?.ok)
        assertTrue(verdict!!.detail.contains("节点选择"))
        assertNull(AdblockVerification.verdictFromCounters(rules(4), rules(4), "doubleclick.net"))
    }

    @Test fun connectRequestIsAnHttpsTunnel() {
        val text = String(AdblockVerification.connectRequest("ads.example.net"), Charsets.US_ASCII)
        assertTrue(text.startsWith("CONNECT ads.example.net:443 HTTP/1.1\r\nHost: ads.example.net:443\r\n"))
        assertTrue(text.endsWith("\r\n\r\n"))
    }

    @Test fun inspectCarriesControllerCounters() {
        val report = AdblockVerification.inspect("rule", rules(7, 120), emptyList())
        assertEquals(7L, report.hitCount); assertEquals(120L, report.missCount); assertEquals(1, report.rulesBefore)
    }

    @Test fun dnsTakeoverVerdicts() {
        val real = listOf(InetAddress.getByAddress("ad.test", byteArrayOf(142.toByte(), 250.toByte(), 1, 1)))
        val fake = listOf(InetAddress.getByAddress("ad.test", byteArrayOf(198.toByte(), 18, 0, 9)))
        assertEquals(true, AdblockVerification.dnsVerdict("ad.test", emptyList(), dnsLayer = true, privateDnsHost = null).ok)
        assertNull(AdblockVerification.dnsVerdict("ad.test", emptyList(), dnsLayer = false, privateDnsHost = null).ok)
        assertEquals(true, AdblockVerification.dnsVerdict("ad.test", fake, dnsLayer = true, privateDnsHost = null).ok)
        val bypass = AdblockVerification.dnsVerdict("ad.test", real, dnsLayer = true, privateDnsHost = "dns.alidns.com")
        assertEquals(false, bypass.ok)
        assertTrue(bypass.detail.contains("dns.alidns.com"))
        assertNull(AdblockVerification.dnsVerdict("ad.test", real, dnsLayer = false, privateDnsHost = null).ok)
    }

    @Test fun dnsPathRowNamesEveryHop() {
        val (ok, line) = AdblockVerification.dnsPath(chain(cnip = "ipset", tcpOnly = true))
        assertTrue(ok)
        listOf("redir-host", "系统 DNS 已接管", "私人 DNS 关闭", "IPv6 DNS 已劫持", "CNIP 内核旁路（仅 TCP）", "DNS 层拦截已启用").forEach {
            assertTrue(line, line.contains(it))
        }
        assertFalse(AdblockVerification.dnsPath(chain(privateMode = "hostname", privateHost = "dot.pub")).first)
        assertFalse(AdblockVerification.dnsPath(chain(dnsLayer = false)).first)
    }

    @Test fun hintsExplainWhyAdsCanPass() {
        assertTrue(AdblockVerification.hints(chain(hits = 3, miss = 10)).isEmpty())
        val privateDns = AdblockVerification.hints(chain(hits = 3, privateMode = "hostname", privateHost = "dot.pub"))
        assertTrue(privateDns.single().contains("dot.pub") && privateDns.single().contains("不会自动修改"))
        assertTrue(AdblockVerification.hints(chain(hits = 3, sniffer = "disabled")).single().contains("嗅探"))
        assertTrue(AdblockVerification.hints(chain(hits = 3, cnip = "ipset")).single().contains("仅 TCP 旁路"))
        assertTrue(AdblockVerification.hints(chain(hits = 3, cnip = "ipset", dnsLayer = false)).single().contains("不会被拦截"))
        assertTrue(AdblockVerification.hints(chain(hits = 3, sourceHasDns = false)).single().contains("河图推荐 DNS"))
        assertTrue(AdblockVerification.hints(chain(hits = 3, scope = "blacklist", scoped = 4)).single().startsWith("4 个应用"))
        assertTrue(AdblockVerification.hints(chain(hits = 3, scope = "whitelist", scoped = 2)).single().contains("2 个应用"))
        assertTrue(AdblockVerification.hints(chain(hits = 0, miss = 0)).single().contains("还没有检查过任何连接"))
        assertTrue(AdblockVerification.hints(chain(hits = 0, miss = 42)).single().contains("已检查 42 个连接"))
        assertTrue(AdblockVerification.hints(chain(hits = 0, fromController = false)).isEmpty())
    }
}
