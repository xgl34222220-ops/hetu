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
        val targets = listOf(
            NetTestTarget("Slow", "$base/slow"), NetTestTarget("Mid", "$base/mid"),
            NetTestTarget("Fast", "$base/fast"), NetTestTarget("Broken", "$base/broken"),
        )
        // Robolectric's SystemClock is virtual; wall time proves the targets overlap.
        val started = System.nanoTime()
        val results = NetworkTest.run(null, targets).toList()
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertEquals(4, results.size)
        assertEquals("Slow is emitted last, after every faster row", "Slow", results.last().name)
        assertTrue("Fast must not wait for Mid", results.indexOfFirst { it.name == "Fast" } < results.indexOfFirst { it.name == "Mid" })
        assertTrue("All targets run at once, not one after another: $elapsed ms", elapsed < 900 + 450 - 100)
        val byName = results.associateBy { it.name }
        assertTrue(byName.getValue("Slow").ok)
        assertTrue("A CDN 403 is still a reachable path", byName.getValue("Mid").ok)
        assertEquals(403, byName.getValue("Mid").code)
        assertFalse("A 5xx is a failed path", byName.getValue("Broken").ok)
        assertEquals("HTTP 503", byName.getValue("Broken").error)
        assertTrue(byName.getValue("Slow").millis >= 1)
        assertEquals("3/4 可达", NetworkTest.summary(results, 4).substringBefore(" ·"))
    }

    @Test fun networkTestReportsUnreachableTargetsWithoutThrowing() = runBlocking {
        val port = MockWebServer().let { it.start(); val bound = it.port; it.shutdown(); bound }
        val result = NetworkTest.run(null, listOf(NetTestTarget("Down", "http://127.0.0.1:$port/"))).toList().single()
        assertFalse(result.ok)
        assertEquals(-1L, result.millis)
        assertTrue(result.error.isNotBlank())
        assertEquals("", NetworkTest.summary(emptyList()))
        assertTrue(NetworkTest.targets.map { it.name }.containsAll(listOf("Google", "YouTube", "GitHub", "Telegram", "ChatGPT", "Netflix", "Cloudflare", "百度")))
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
