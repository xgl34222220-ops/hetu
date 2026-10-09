package io.github.xgl34222220.hetu

import android.app.Application
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 网络测试并发流式结果、去广告运行链的严格核查与实测判定、基础配置导入/编辑的校验-写入-应用-回滚事务。
 * 只用回环夹具；不启动核心、不访问外网。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NetAdblockConfig100Test {
    private val servers = mutableListOf<MockWebServer>()

    @After fun finish() { servers.forEach { it.shutdown() } }

    /* ------------------------------ 网络测试 ------------------------------ */

    @Test fun networkTestRunsConcurrentlyAndStreamsInFinishOrder() = runBlocking {
        val server = MockWebServer().also { servers += it }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/slow" -> MockResponse().setResponseCode(204).setHeadersDelay(900, TimeUnit.MILLISECONDS)
                "/mid" -> MockResponse().setResponseCode(403).setHeadersDelay(450, TimeUnit.MILLISECONDS)
                "/fast" -> MockResponse().setResponseCode(204)
                else -> MockResponse().setResponseCode(503)
            }
        }
        server.start()
        val base = "http://127.0.0.1:${server.port}"
        fun site(id: String, path: String) = NetSite(id, id, NetCategory.Tools, "$base/$path", trace = false)
        val sites = listOf(site("Slow", "slow"), site("Mid", "mid"), site("Fast", "fast"), site("Broken", "broken"))
        // Robolectric's SystemClock is virtual; wall time proves the rows overlap.
        val started = System.nanoTime()
        val results = NetworkTest.run(null, sites).toList()
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertEquals(4, results.size)
        assertEquals("Slow is emitted last, after every faster row", "Slow", results.last().id)
        assertTrue("Fast must not wait for Mid", results.indexOfFirst { it.id == "Fast" } < results.indexOfFirst { it.id == "Mid" })
        assertTrue("All rows run at once, not one after another: $elapsed ms", elapsed < 900 + 450 - 100)
        val byId = results.associateBy { it.id }
        assertTrue(byId.getValue("Slow").ok)
        assertTrue("A CDN 403 is still a reachable path", byId.getValue("Mid").ok)
        assertTrue("…but the service refused this exit: 未解锁", byId.getValue("Mid").blocked)
        assertFalse("A 5xx is a failed path", byId.getValue("Broken").ok)
        assertEquals("HTTP 503", byId.getValue("Broken").error)
        assertTrue(byId.getValue("Slow").millis >= 1)
        assertNull("No trace, no route: the region stays unknown, never guessed", byId.getValue("Fast").region)
    }

    @Test fun networkTestReportsUnreachableTargetsWithoutThrowing() = runBlocking {
        val port = MockWebServer().let { it.start(); val bound = it.port; it.shutdown(); bound }
        val result = NetworkTest.run(null, listOf(NetSite("Down", "Down", NetCategory.Tools, "http://127.0.0.1:$port/", trace = false))).toList().single()
        assertFalse(result.ok)
        assertEquals(-1L, result.millis)
        assertTrue(result.error.isNotBlank())
        val names = NetworkTest.sites.map { it.name }
        listOf("ChatGPT", "Claude", "Doubao", "Gemini", "Grok", "Discord", "Douyin", "Reddit", "TikTok", "X", "Telegram",
            "Apple TV+", "Bilibili", "Disney+", "Hulu", "Netflix", "Spotify", "Twitch", "YouTube",
            "Alibaba", "Apple", "Cloudflare", "GitHub", "NetEase", "PayPal", "Steam", "Tencent", "Wikipedia", "Google", "Baidu")
            .forEach { assertTrue("missing $it", it in names) }
        assertEquals("ids are unique", NetworkTest.sites.size, NetworkTest.sites.map { it.id }.toSet().size)
        NetworkTest.sites.mapNotNull { it.brand }.forEach { assertNotNull("bundled mark $it", NetBrandIcons.vector(it)) }
    }

    @Test fun regionComesOnlyFromRealTraceRouteOrDirectExit() = runBlocking {
        val server = MockWebServer().also { servers += it }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/cdn-cgi/trace" -> MockResponse().setBody("fl=123\nh=example\nip=203.0.113.9\ncolo=NRT\nloc=JP\ntls=TLSv1.3\n")
                else -> MockResponse().setResponseCode(200).setBody("ok")
            }
        }
        server.start()
        val base = "http://127.0.0.1:${server.port}"
        val traced = NetworkTest.run(null, listOf(NetSite("cf", "CF", NetCategory.Ai, "$base/cdn-cgi/trace", aiRegion = true))).toList().single()
        assertEquals("JP", traced.region)
        assertEquals(NetRegionSource.Trace, traced.regionSource)
        assertFalse(traced.blocked)

        assertEquals("JP", NetworkTest.parseTrace("fl=1\ncolo=NRT\nloc=jp"))
        assertNull("A page that merely contains loc= is not a trace", NetworkTest.parseTrace("<html>loc=JP</html>"))
        assertNull(NetworkTest.parseTrace("colo=NRT\nloc=XX"))

        val log = """
            time="t" level=info msg="[TCP] 127.0.0.1:40000 --> chatgpt.com:443 match DomainSuffix(chatgpt.com) using 节点选择[🇯🇵 日本 01]"
            time="t" level=info msg="[TCP] 127.0.0.1:40001 --> www.netflix.com:443 match GeoSite(netflix) using 流媒体[🇯🇵 日本 01]"
            time="t" level=info msg="[TCP] 127.0.0.1:40002 --> www.reddit.com:443 match Match using 兜底[🇺🇸 美国 02]"
            time="t" level=info msg="[TCP] 127.0.0.1:40003 --> www.baidu.com:443 match GeoSite(cn) using DIRECT"
        """.trimIndent()
        val hosts = mapOf("chatgpt" to "chatgpt.com", "netflix" to "www.netflix.com", "reddit" to "www.reddit.com", "baidu" to "www.baidu.com", "x" to "x.com")
        val routes = NetworkTest.parseRoutes(log, hosts.values)
        assertEquals("节点选择[🇯🇵 日本 01]", routes["chatgpt.com"])
        assertEquals("🇯🇵 日本 01", NetworkTest.leafOf(routes.getValue("www.netflix.com")))
        assertTrue(NetworkTest.isDirect(routes.getValue("www.baidu.com")))
        val rows = listOf(
            NetSiteResult("chatgpt", true, 300, 200, region = "JP", regionSource = NetRegionSource.Trace),
            NetSiteResult("netflix", true, 400, 200), NetSiteResult("reddit", true, 500, 200),
            NetSiteResult("baidu", true, 40, 200), NetSiteResult("x", true, 350, 200),
        )
        val resolved = NetworkTest.resolve(rows, routes, hosts, directRegion = "CN", viaProxy = true).associateBy { it.id }
        assertEquals("Same leaf node as a traced row", "JP", resolved.getValue("netflix").region)
        assertEquals(NetRegionSource.SameNode, resolved.getValue("netflix").regionSource)
        assertNull("A node nobody traced stays unknown", resolved.getValue("reddit").region)
        assertEquals("DIRECT leaves from the device's own exit", "CN", resolved.getValue("baidu").region)
        assertNull("No log line for the host: unknown", resolved.getValue("x").region)
        val direct = NetworkTest.resolve(rows, emptyMap(), hosts, directRegion = "CN", viaProxy = false).associateBy { it.id }
        assertEquals("Proxy off: every row exits directly", "CN", direct.getValue("reddit").region)
        assertEquals("…except a row that traced its own exit", "JP", direct.getValue("chatgpt").region)

        val chatgpt = NetworkTest.sites.first { it.id == "chatgpt" }
        assertTrue("An AI service at an exit it does not serve is 未解锁",
            NetworkTest.blocked(chatgpt, NetSiteResult("chatgpt", true, 200, 200, region = "HK")))
        assertFalse(NetworkTest.blocked(chatgpt, NetSiteResult("chatgpt", true, 200, 200, region = "JP")))
        assertEquals("🇯🇵", NetworkTest.flag("JP"))
        assertEquals("", NetworkTest.flag(null))
    }

    @Test fun speedTestStreamsLiveRatesAndFinishesBothDirections() = runBlocking {
        val server = MockWebServer().also { servers += it }
        val chunk = ByteArray(256 * 1024)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/meta" -> MockResponse().setBody("""{"clientIp":"203.0.113.7","country":"jp","city":"Tokyo","colo":"NRT","asOrganization":"Example"}""")
                    path == "/__down?bytes=0" -> MockResponse().setBody("")
                    path.startsWith("/__down") -> MockResponse().setBody(okio.Buffer().write(chunk))
                    path == "/__up" -> MockResponse().setResponseCode(200).setHeadersDelay(25, TimeUnit.MILLISECONDS)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val events = SpeedTest.run(null, "http://127.0.0.1:${server.port}", phaseMs = 700, streams = 2, upChunk = 64 * 1024).toList()
        val meta = events.filterIsInstance<SpeedEvent.Meta>().single().meta
        assertEquals("203.0.113.7", meta?.ip)
        assertEquals("JP", meta?.country)
        assertTrue(events.filterIsInstance<SpeedEvent.Latency>().single().millis >= 0)
        assertTrue("live samples stream while a phase runs", events.filterIsInstance<SpeedEvent.Live>().isNotEmpty())
        val finished = events.filterIsInstance<SpeedEvent.Finished>()
        assertEquals(listOf(false, true), finished.map { it.upload })
        assertTrue(finished.all { it.bytes > 0 && it.mbps > 0 })
        assertEquals(SpeedPhase.Done, (events.last() as SpeedEvent.Phase).phase)
        assertNull(events.firstOrNull { it is SpeedEvent.Failed })
        assertEquals(12.5, SpeedTest.mbps(12_500_000 / 8 * 8, 8_000), 0.01)
        assertEquals(20L, SpeedTest.latency(listOf(90L, 30L, 20L, 10L)))
        assertNull(SpeedTest.parseMeta("not json"))
    }

    @Test fun speedTestCancelsPromptlyWhenThePageGoesAway() = runBlocking {
        val server = MockWebServer().also { servers += it }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/meta" -> MockResponse().setBody("""{"clientIp":"203.0.113.7","country":"JP"}""")
                request.path == "/__down?bytes=0" -> MockResponse().setBody("")
                else -> MockResponse().setBody(okio.Buffer().write(ByteArray(64 * 1024))).throttleBody(16 * 1024, 1, TimeUnit.SECONDS)
            }
        }
        server.start()
        val started = System.nanoTime()
        val seen = mutableListOf<SpeedEvent>()
        kotlinx.coroutines.withTimeoutOrNull(1_500) {
            SpeedTest.run(null, "http://127.0.0.1:${server.port}", phaseMs = 30_000, streams = 2).collect { seen += it }
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertTrue("cancellation closes the sockets instead of waiting for the phase: $elapsed ms", elapsed < 4_000)
        assertTrue(seen.none { it is SpeedEvent.Finished })
    }

    /* ------------------------------ 去广告核查 ------------------------------ */

    private val adPayload = "((RuleSet,hetu-adblock),(NOT,((RuleSet,hetu-adblock-allow))))"
    private fun rule(i: Int, type: String, payload: String, proxy: String, disabled: Boolean = false) = ProxyRuleUi(i, type, payload, proxy, disabled = disabled)
    private fun providers(block: Int, allow: Int, vararg remote: Pair<String, Int>) =
        listOf(ProxyRuleProviderUi("hetu-adblock", "Domain", block, "Rule", "File", ""),
            ProxyRuleProviderUi("hetu-adblock-allow", "Domain", allow, "Rule", "File", "")) +
            remote.map { ProxyRuleProviderUi(it.first, "Domain", it.second, "Rule", "HTTP", "") }

    @Test fun strictChainRequiresRejectRuleBeforeRoutingWithLoadedProviders() {
        val good = AdblockVerification.inspect("rule", listOf(
            rule(0, "ProcessName", "com.tencent.mm", "DIRECT"),
            rule(1, "AND", adPayload, "REJECT"),
            rule(2, "GeoSite", "google", "节点选择"),
            rule(3, "Match", "", "节点选择"),
        ), providers(120_000, 3))
        assertTrue(good.loaded && good.ordered && good.providersLoaded && good.ruleMode && good.effective)
        assertEquals(1, good.blockingIndex)
        assertTrue(good.detail().contains("第 2 条 REJECT"))

        val late = AdblockVerification.inspect("rule", listOf(
            rule(0, "GeoSite", "google", "节点选择"), rule(1, "AND", adPayload, "REJECT"), rule(2, "Match", "", "DIRECT"),
        ), providers(10, 0))
        assertTrue(late.loaded)
        assertFalse("A REJECT behind proxy routing never sees routed ad traffic", late.ordered)
        assertFalse(late.effective)

        // The old check accepted any rule mentioning hetu-adblock; the allow provider or a disabled rule is not blocking.
        val loose = AdblockVerification.inspect("rule", listOf(
            rule(0, "RuleSet", "hetu-adblock-allow", "DIRECT"), rule(1, "AND", adPayload, "REJECT", disabled = true),
        ), providers(10, 0))
        assertFalse(loose.loaded)

        val empty = AdblockVerification.inspect("rule", listOf(rule(0, "AND", adPayload, "REJECT")), providers(0, 0, "广告规则" to 0, "proxy" to 50))
        assertFalse("An empty hetu-adblock provider blocks nothing", empty.providersLoaded)
        assertEquals(listOf("广告规则"), empty.emptyRemoteProviders)
        assertTrue(empty.detail().contains("远程规则集为空"))

        val global = AdblockVerification.inspect("Global", listOf(rule(0, "AND", adPayload, "REJECT")), providers(5, 0))
        assertFalse(global.ruleMode)
        assertFalse(global.effective)
    }

    @Test fun probeDomainFollowsSuffixSemanticsAndAllowList() {
        assertTrue(AdblockVerification.covered("pagead2.googlesyndication.com", setOf("googlesyndication.com")))
        assertFalse(AdblockVerification.covered("notgooglesyndication.com", setOf("googlesyndication.com")))
        assertEquals("doubleclick.net", AdblockVerification.pickProbeDomain(setOf("doubleclick.net", "x.example"), emptySet()))
        assertEquals("An allowlisted host is never used as the probe", "googlesyndication.com",
            AdblockVerification.pickProbeDomain(setOf("googlesyndication.com", "doubleclick.net"), setOf("doubleclick.net")))
        assertEquals("ads.example.net", AdblockVerification.pickProbeDomain(setOf("ads.example.net"), emptySet()))
        assertNull(AdblockVerification.pickProbeDomain(setOf("ads.example.net"), setOf("example.net")))
        assertNull(AdblockVerification.pickProbeDomain(emptySet(), emptySet()))
    }

    @Test fun probeVerdictComesFromTheCoreDecisionLine() {
        val rejected = """time="2026-10-10T04:00:00+08:00" level=info msg="[TCP] 127.0.0.1:40112(io.github.xgl34222220.hetu) --> doubleclick.net:80 match AND((RuleSet(hetu-adblock)),(NOT,((RuleSet(hetu-adblock-allow))))) using REJECT""""
        assertEquals(true, AdblockVerification.parseProbeLog(rejected, "doubleclick.net")?.ok)
        val routed = """level=info msg="[TCP] 127.0.0.1:40113 --> doubleclick.net:80 match Match() using 节点选择[香港 01]""""
        val verdict = AdblockVerification.parseProbeLog(routed, "doubleclick.net")
        assertEquals(false, verdict?.ok)
        assertTrue(verdict!!.detail.contains("节点选择[香港 01]"))
        assertNull(AdblockVerification.parseProbeLog("level=info msg=\"[TCP] 1.1.1.1:1 --> other.net:80 match Match() using DIRECT\"", "doubleclick.net"))
    }

    /* ------------------------------ 基础配置事务 ------------------------------ */

    @Test fun importDecodeRejectsNonConfigInputBeforeAnythingIsStored() {
        val yaml = "\uFEFFmixed-port: 7890\nproxies: []\nrules:\n  - MATCH,DIRECT\n"
        assertEquals(yaml.removePrefix("\uFEFF"), HxConfigTransaction.decode(yaml.toByteArray()))
        for (bad in listOf(ByteArray(0), "   \n".toByteArray(), byteArrayOf(0xC3.toByte(), 0x28),
                "c3M6Ly9ZV1Z6TFRJMU5pMW5ZMjA2Y0dGemMzZHZjbVE9QGV4YW1wbGUuY29tOjg0NDM=".toByteArray(),
                "proxies: []\u0000".toByteArray(), ByteArray(HxConfigTransaction.LIMIT + 1) { 'a'.code.toByte() })) {
            assertThrows(IOException::class.java) { HxConfigTransaction.decode(bad) }
        }
    }

    @Test fun applyRollsBackOnlyWhenTheRunningCoreRejectsTheChange() = runBlocking {
        var rollbacks = 0
        val rollback: suspend () -> Unit = { rollbacks++ }
        assertEquals(HxApplyOutcome.Deferred, HxConfigTransaction.apply(false, { error("not called") }, rollback))
        assertEquals(HxApplyOutcome.Applied("运行配置已热重载"), HxConfigTransaction.apply(true, { "运行配置已热重载" }, rollback))
        assertEquals(0, rollbacks)

        val rejected = HxConfigTransaction.apply(true, { throw IOException("Controller 尚未加载河图广告拦截规则") }, rollback)
        assertTrue(rejected is HxApplyOutcome.RolledBack)
        assertEquals(1, rollbacks)
        assertTrue(HxConfigTransaction.describe(rejected, "配置已保存").startsWith("应用失败，已回滚"))

        val restart = HxConfigTransaction.apply(true, { throw IOException("运行模式、应用范围、DNS 已变化，请使用「重启」应用这些网络层设置") }, rollback)
        assertTrue("A valid network-layer edit is kept for a restart, never rolled back", restart is HxApplyOutcome.NeedsRestart)
        assertEquals(1, rollbacks)

        val stuck = HxConfigTransaction.apply(true, { throw IOException("bad") }) { throw IOException("磁盘只读") }
        assertEquals(HxApplyOutcome.RollbackFailed("bad", "磁盘只读"), stuck)
    }
}
