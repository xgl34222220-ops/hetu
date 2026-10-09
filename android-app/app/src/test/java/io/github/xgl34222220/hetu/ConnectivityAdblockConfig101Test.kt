package io.github.xgl34222220.hetu

import android.app.Application
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 多平台网络测试、去广告运行链核查与拦截实测判定、基础代理配置导入/编辑事务。
 * 全部在主机回环或内存夹具中执行：不启动核心、不访问外网、不需要 Root。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConnectivityAdblockConfig101Test {
    private val servers = mutableListOf<MockWebServer>()
    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("hetu-101").toFile() }
    @After fun tearDown() { servers.forEach { it.shutdown() }; dir.deleteRecursively() }

    private fun server(vararg responses: MockResponse) = MockWebServer().also { s ->
        responses.forEach(s::enqueue); s.start(); servers += s
    }
    private fun proxyOf(s: MockWebServer) = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", s.port))
    private fun target(id: String, url: String = "http://$id.example.invalid/") = PlatformTarget(id, id, url, PlatformGroup.Global)

    /* ------------------------------ 多平台连通性 ------------------------------ */

    @Test fun platformRoundRunsConcurrentlyBoundedAndStreamsInCompletionOrder() = runBlocking {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val delays = mapOf("slow" to 300L, "mid" to 150L, "fast" to 10L, "a" to 40L, "b" to 60L)
        val targets = delays.keys.map { target(it) }
        val results = PlatformProbe.run(targets, proxy = null, concurrency = 3) { t ->
            val now = inFlight.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            delay(delays.getValue(t.id))
            inFlight.decrementAndGet()
            PlatformOutcome.Reachable(delays.getValue(t.id), 204)
        }.toList()
        val finals = results.filter { it.outcome is PlatformOutcome.Reachable }.map { it.target.id }
        assertEquals("every target reports exactly once", delays.keys, finals.toSet())
        assertEquals(5, finals.size)
        assertTrue("concurrency bound respected: ${peak.get()}", peak.get() <= 3)
        assertTrue("more than one in flight at once", peak.get() >= 2)
        assertTrue("fast finishes before slow", finals.indexOf("fast") < finals.indexOf("slow"))
        assertEquals(5, results.count { it.outcome == PlatformOutcome.Running })
        val summary = PlatformProbe.summarize(finals.map { PlatformOutcome.Reachable(1, 204) })
        assertTrue(summary.done)
    }

    @Test fun platformProbeGoesThroughLocalListenerAndClassifiesStatus() = runBlocking {
        val proxy = server(
            MockResponse().setResponseCode(204),
            MockResponse().setResponseCode(403),
            MockResponse().setResponseCode(503),
            MockResponse().setResponseCode(401),
        )
        val ok = PlatformProbe.probe(target("google", "http://www.google.com/generate_204"), proxyOf(proxy), 3_000)
        assertTrue(ok is PlatformOutcome.Reachable && ok.code == 204 && ok.latencyMs >= 1)
        val recorded = proxy.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("request entered the listener with the target host", "www.google.com", recorded.getHeader("Host"))
        val region = PlatformProbe.probe(target("chatgpt"), proxyOf(proxy), 3_000)
        assertTrue(region is PlatformOutcome.Restricted && region.code == 403)
        val broken = PlatformProbe.probe(target("netflix"), proxyOf(proxy), 3_000)
        assertTrue(broken is PlatformOutcome.Failed && broken.reason.contains("503"))
        val api = PlatformProbe.probe(target("openai-api"), proxyOf(proxy), 3_000)
        assertTrue("401 from an API proves reachability", api is PlatformOutcome.Reachable)
    }

    @Test fun platformProbeFailuresAreBoundedAndNamed() = runBlocking {
        val closed = ServerSocket(0).use { it.localPort }
        val refused = PlatformProbe.probe(target("x"), Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", closed)), 2_000)
        assertTrue(refused is PlatformOutcome.Failed)
        val slow = server(MockResponse().setResponseCode(204).setHeadersDelay(3, TimeUnit.SECONDS))
        val started = System.nanoTime()
        val timedOut = PlatformProbe.probe(target("tiktok"), proxyOf(slow), 400)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(PlatformOutcome.Failed("超时"), timedOut)
        assertTrue("hard deadline honoured: $elapsedMs", elapsedMs < 2_500)
        assertTrue(PlatformProbe.outcomeFor(451, 10) is PlatformOutcome.Restricted)
        assertTrue(PlatformProbe.outcomeFor(429, 10) is PlatformOutcome.Restricted)
        assertTrue(PlatformProbe.outcomeFor(302, 10) is PlatformOutcome.Reachable)
        assertTrue(PlatformCatalog.targets.map { it.id }.toSet().size == PlatformCatalog.targets.size)
        assertTrue(PlatformCatalog.targets.size >= 17)
    }

    /* ------------------------------ 去广告核查 ------------------------------ */

    private val injectedStartup = """
        dns:
          enable: true
          enhanced-mode: fake-ip
        rule-providers:
          hetu-adblock-allow:
            type: file
          hetu-adblock:
            type: file
        rules:
          - AND,((RULE-SET,hetu-adblock),(NOT,((RULE-SET,hetu-adblock-allow)))),REJECT
          - MATCH,Proxy
        sniffer:
          enable: true
    """.trimIndent()

    /** Exactly what Mihomo's controller reports for the injected AND rule. */
    private fun coreRule(proxy: String = "REJECT", disabled: Boolean = false, hits: Long = 7) =
        AdRuleRow("AND", "((RuleSet,hetu-adblock) && (NOT,(!(RuleSet,hetu-adblock-allow))))", proxy, disabled, hits)
    private fun matchRule() = AdRuleRow("Match", "", "Proxy", false, null)
    private val goodProviders = mapOf(
        "hetu-adblock" to AdProviderInfo("hetu-adblock", 120, "File"),
        "hetu-adblock-allow" to AdProviderInfo("hetu-adblock-allow", 3, "File"),
    )
    private fun input(
        rules: List<AdRuleRow>? = listOf(AdRuleRow("DomainSuffix", "bank.example", "DIRECT", false, null), coreRule(), matchRule()),
        providers: Map<String, AdProviderInfo>? = goodProviders,
        mode: String? = "rule",
        running: Boolean = true,
        enabled: Boolean = true,
        startup: String? = injectedStartup,
        lastError: String = "",
    ) = AdblockAuditInput(enabled, running, mode, startup, rules, providers, 120, lastError)

    @Test fun auditAcceptsOnlyTheRealRejectChainAsCoreReportsIt() {
        val good = AdblockAuditor.audit(input())
        assertEquals(AdVerdict.Effective, good.verdict)
        assertTrue(good.coreAnswered && good.coreChainOk)
        assertEquals(1, good.ruleIndex)
        assertEquals(7L, good.ruleHits)
        assertEquals(120, good.coreRuleCount)

        // A rule that merely names the provider is not the blocking chain.
        val allowOnly = AdblockAuditor.audit(input(rules = listOf(AdRuleRow("RuleSet", "hetu-adblock-allow", "DIRECT", false, null), matchRule())))
        assertEquals(AdVerdict.NotEffective, allowOnly.verdict)
        assertFalse(allowOnly.coreChainOk)
        val disabled = AdblockAuditor.audit(input(rules = listOf(coreRule(disabled = true), matchRule())))
        assertTrue(disabled.checks.first { it.key == "core" }.detail.contains("禁用"))
        val wrongPolicy = AdblockAuditor.audit(input(rules = listOf(coreRule(proxy = "DIRECT"), matchRule())))
        assertEquals(AdVerdict.NotEffective, wrongPolicy.verdict)
        val afterMatch = AdblockAuditor.audit(input(rules = listOf(matchRule(), coreRule())))
        assertEquals(AdVerdict.NotEffective, afterMatch.verdict)
        assertTrue(afterMatch.checks.first { it.key == "core" }.detail.contains("MATCH"))
        val shadowed = AdblockAuditor.audit(input(rules = listOf(AdRuleRow("GeoSite", "google", "Proxy", false, null), coreRule(), matchRule())))
        assertEquals(AdCheckState.Warn, shadowed.checks.first { it.key == "core" }.state)
        assertEquals(AdVerdict.Effective, shadowed.verdict)
    }

    @Test fun auditReportsEmptyProvidersModeDegradationAndUnknowns() {
        val empty = AdblockAuditor.audit(input(providers = goodProviders + ("hetu-adblock" to AdProviderInfo("hetu-adblock", 0, "File"))))
        assertEquals(AdVerdict.NotEffective, empty.verdict)
        assertFalse(empty.coreChainOk)
        val global = AdblockAuditor.audit(input(mode = "global"))
        assertEquals(AdVerdict.NotEffective, global.verdict)
        assertTrue(global.headline.contains("全局"))
        val degraded = AdblockAuditor.audit(input(startup = "rules:\n  - MATCH,DIRECT\n", lastError = "代理串联去广告需要普通 rules: 列表"))
        assertEquals(AdVerdict.NotEffective, degraded.verdict)
        assertTrue(degraded.checks.first { it.key == "startup" }.detail.contains("降级"))
        val silent = AdblockAuditor.audit(input(rules = null, providers = null, mode = null))
        assertEquals(AdVerdict.Unknown, silent.verdict)
        assertFalse(silent.coreAnswered)
        assertEquals(AdVerdict.Waiting, AdblockAuditor.audit(input(running = false)).verdict)
        assertEquals(AdVerdict.Off, AdblockAuditor.audit(input(enabled = false)).verdict)
        val remote = AdblockAuditor.audit(input(providers = goodProviders + ("reject-list" to AdProviderInfo("reject-list", 0, "HTTP"))))
        assertEquals(listOf("reject-list"), remote.emptyRemoteProviders)
        assertEquals(AdVerdict.Effective, remote.verdict)
    }

    @Test fun controllerJsonSnifferAndDnsAreParsed() {
        val rules = AdblockAuditor.parseRules(JSONObject().put("rules", JSONArray()
            .put(JSONObject().put("type", "AND").put("payload", "x").put("proxy", "REJECT").put("extra", JSONObject().put("disabled", true).put("hitCount", 9)))
            .put(JSONObject().put("type", "Match").put("payload", "").put("proxy", "DIRECT"))))!!
        assertEquals(2, rules.size)
        assertTrue(rules[0].disabled)
        assertEquals(9L, rules[0].hitCount)
        assertNull(rules[1].hitCount)
        val providers = AdblockAuditor.parseProviders(JSONObject().put("providers", JSONObject()
            .put("hetu-adblock", JSONObject().put("ruleCount", 42).put("vehicleType", "File"))))!!
        assertEquals(42, providers.getValue("hetu-adblock").ruleCount)
        assertNull(AdblockAuditor.parseRules(JSONObject()))
        assertEquals(SnifferState.Enabled, AdblockAuditor.sniffer(injectedStartup))
        assertEquals(SnifferState.Disabled, AdblockAuditor.sniffer("sniffer:\n  enable: false\n  sniff:\n    TLS:\n      ports: [443]\n"))
        assertEquals(SnifferState.Absent, AdblockAuditor.sniffer("dns:\n  enable: true\n"))
        assertEquals("fake-ip", AdblockAuditor.enhancedMode(injectedStartup))
        assertEquals(AdCheckState.Warn, AdblockAuditor.audit(input(startup = injectedStartup.replace("sniffer:\n  enable: true", "sniffer:\n  enable: false"))).checks.first { it.key == "sniffer" }.state)
    }

    @Test fun probePicksCoveredAdHostsAndReadsTheCoreVerdict() {
        val block = setOf("doubleclick.net", "googlesyndication.com", "pos.baidu.com")
        assertTrue(AdblockProbe.covered("ad.doubleclick.net", block))
        assertFalse(AdblockProbe.covered("baidu.com", block))
        assertEquals(listOf("doubleclick.net", "googlesyndication.com"), AdblockProbe.pick(block, allow = setOf("pos.baidu.com")).take(2))
        assertFalse("allow-listed host is not probed", "pos.baidu.com" in AdblockProbe.pick(block, setOf("baidu.com")))

        val log = """
            time="2026-10-10T04:00:00" level=info msg="[TCP] 127.0.0.1:40000 --> doubleclick.net:80 match RuleSet(hetu-adblock) using DIRECT"
            time="2026-10-10T04:00:01" level=info msg="[TCP] 127.0.0.1:40001 --> doubleclick.net:80 match AND((RuleSet,hetu-adblock) && (NOT,(!(RuleSet,hetu-adblock-allow)))) using REJECT"
            time="2026-10-10T04:00:01" level=info msg="[TCP] 127.0.0.1:40002 --> googlesyndication.com:80 match GeoSite(category-ads-all) using REJECT"
            time="2026-10-10T04:00:01" level=info msg="[TCP] 127.0.0.1:40003 --> connectivitycheck.gstatic.com:80 match GeoSite(google) using HK 01[Proxy]"
            time="2026-10-10T04:00:01" level=info msg="[TCP] 127.0.0.1:40004 --> app-measurement.com:80 doesn't match any rule using DIRECT"
            time="2026-10-10T04:00:01" level=info msg="[TCP] 127.0.0.1:40005 --> doubleclick.net.evil:80 match Match() using DIRECT"
        """.trimIndent()
        val newest = AdblockProbe.findDecision(log, "doubleclick.net")!!
        assertEquals("REJECT", newest.policy)
        assertEquals(AdProbeVerdict.Blocked, AdblockProbe.classify("doubleclick.net", true, newest, null, "连接被中断").verdict)
        val other = AdblockProbe.classify("googlesyndication.com", true, AdblockProbe.findDecision(log, "googlesyndication.com"), null, null)
        assertEquals(AdProbeVerdict.BlockedByOther, other.verdict)
        assertTrue(AdblockProbe.asExpected(other))
        val control = AdblockProbe.classify(AdblockProbe.CONTROL, false, AdblockProbe.findDecision(log, AdblockProbe.CONTROL), 204, null)
        assertEquals(AdProbeVerdict.Allowed, control.verdict)
        assertTrue(control.detail.contains("HK 01[Proxy]"))
        assertTrue(AdblockProbe.asExpected(control))
        val unmatched = AdblockProbe.findDecision(log, "app-measurement.com")!!
        assertEquals("", unmatched.rule)
        assertEquals("DIRECT", unmatched.policy)
        assertNull(AdblockProbe.findDecision(log, "nothing.example"))
        val unknown = AdblockProbe.classify("mobads.baidu.com", true, null, null, "超时")
        assertEquals(AdProbeVerdict.Unknown, unknown.verdict)
        assertFalse(AdblockProbe.asExpected(unknown))
        assertEquals(AdProbeVerdict.Allowed, AdblockProbe.classify("ads.tiktok.com", true, null, 200, null).verdict)
    }

    /* ------------------------------ 配置导入 / 编辑 ------------------------------ */

    private val validYaml = "proxies:\n  - {name: a, type: ss, server: 1.1.1.1, port: 1, cipher: aes-128-gcm, password: x}\nproxy-groups:\n  - {name: P, type: select, proxies: [a]}\nrules:\n  - MATCH,P\n"

    @Test fun configTextIsDecodedStrictlyAndPrechecked() {
        assertEquals("a: 1\n", ConfigText.decode("\uFEFFa: 1\n".toByteArray()))
        listOf(byteArrayOf(), byteArrayOf(0xC3.toByte(), 0x28), "a: 1\u0000".toByteArray(), "   \n".toByteArray()).forEach { bytes ->
            try { ConfigText.decode(bytes); fail("must reject ${bytes.size} bytes") } catch (_: IOException) {}
        }
        try { ConfigText.readLimited(ByteArray(ConfigText.LIMIT + 1).inputStream()); fail("oversize") } catch (_: IOException) {}
        val ok = ConfigText.inspect(validYaml)
        assertTrue(ok.ok)
        assertEquals(1, ok.proxies); assertEquals(1, ok.groups); assertEquals(1, ok.rules)
        assertTrue(ok.warnings.isEmpty())
        val syntax = ConfigText.inspect("proxies:\n  - [unclosed\nrules: x\n")
        assertFalse(syntax.ok)
        assertTrue(syntax.error!!, syntax.error!!.contains("行"))
        assertTrue(ConfigText.inspect("c3M6Ly9ZV1Z6TFRJMU5pMW5ZMjA2Y0dGemMzZHZjbVE9QDEuMi4zLjQ6ODM4OCNub2Rl\n").error!!.contains("Base64"))
        assertTrue(ConfigText.inspect("- a\n- b\n").error!!.contains("顶层"))
        assertTrue(ConfigText.inspect("mode: rule\n").warnings.any { it.contains("节点") })
        assertEquals("my.yaml", ConfigText.importName("https://x.example/dl/my.yaml?token=1"))
        assertEquals("sub.yaml", ConfigText.importName("/path/sub"))
        assertEquals("导入配置.yaml", ConfigText.importName(""))
    }

    @Test fun configDiffShowsChangesWithContextAndStaysBounded() {
        val old = (1..50).joinToString("\n") { "line $it" }
        val new = old.replace("line 25", "line 25 changed") + "\nline 51"
        val diff = ConfigDiffer.diff(old, new)
        assertEquals(2, diff.added)
        assertEquals(1, diff.removed)
        assertTrue(diff.lines.any { it.kind == '-' && it.text == "line 25" })
        assertTrue(diff.lines.any { it.kind == '+' && it.text == "line 25 changed" })
        assertTrue("unchanged runs are folded", diff.lines.any { it.kind == '…' })
        assertTrue(diff.lines.size < 20)
        assertTrue(ConfigDiffer.diff(old, old).identical)
        val big = (1..3000).joinToString("\n") { "a$it" }
        val other = (1..3000).joinToString("\n") { "b$it" }
        val bounded = ConfigDiffer.diff(big, other, maxLines = 100)
        assertEquals(3000, bounded.added); assertEquals(3000, bounded.removed)
        assertTrue(bounded.truncated)
        assertTrue(bounded.lines.size <= 101)
    }

    private class FakeStore(var selected: String?, val files: MutableMap<String, String>) : ConfigStore {
        override fun selectedName() = selected
        override fun names() = files.keys.toList()
        override fun read(name: String) = files[name] ?: throw IOException("missing $name")
        override fun write(name: String, text: String) { if (name !in files) throw IOException("missing"); files[name] = text }
        override fun create(requestedName: String, text: String): String {
            var name = requestedName; var i = 2
            while (name in files) name = requestedName.removeSuffix(".yaml") + " ($i).yaml".also { i++ }
            files[name] = text; selected = name; return name
        }
        override fun select(name: String) { if (name !in files) throw IOException("missing"); selected = name }
    }

    private fun transaction(store: FakeStore, running: Boolean?, reload: suspend () -> String, validate: suspend (String) -> Unit = {}) =
        ConfigTransaction(store, ConfigBackups(File(dir, "backups"), keep = 3), validate, { running }, reload)

    @Test fun editSaveBacksUpWritesAndReloads() = runBlocking {
        val store = FakeStore("main.yaml", mutableMapOf("main.yaml" to "old: 1\n"))
        val reloads = AtomicInteger()
        val result = transaction(store, true, { reloads.incrementAndGet(); "运行配置已热重载" }).save("main.yaml", validYaml, expected = "old: 1\n")
        assertTrue(result is ConfigApplyResult.Applied)
        assertEquals(validYaml, store.files["main.yaml"])
        assertEquals(1, reloads.get())
        val backups = ConfigBackups(File(dir, "backups")).list("main.yaml")
        assertEquals(1, backups.size)
        assertEquals("old: 1\n", ConfigBackups(File(dir, "backups")).read(backups.single()))
        // A concurrent change of the file is refused without writing.
        try { transaction(store, true, { "" }).save("main.yaml", validYaml + "#2\n", expected = "stale"); fail("conflict") }
        catch (_: ConfigConflictException) {}
        assertEquals(validYaml, store.files["main.yaml"])
    }

    @Test fun coreRefusalRollsBackWhileRestartOnlyChangesAreKept() = runBlocking {
        val store = FakeStore("main.yaml", mutableMapOf("main.yaml" to "old: 1\n"))
        val calls = AtomicInteger()
        val refused = transaction(store, true, {
            if (calls.incrementAndGet() == 1) throw IOException("Controller 尚未加载河图广告拦截规则") else "restored"
        }).save("main.yaml", validYaml)
        assertTrue(refused is ConfigApplyResult.RolledBack)
        assertEquals("source restored", "old: 1\n", store.files["main.yaml"])
        assertEquals("reloaded again after rollback", 2, calls.get())

        val restart = transaction(store, true, { throw IOException("运行模式、应用范围、DNS…已变化，请使用「重启」应用这些网络层设置") }).save("main.yaml", validYaml)
        assertTrue(restart is ConfigApplyResult.NeedsRestart)
        assertEquals(validYaml, store.files["main.yaml"])

        val idle = FakeStore("main.yaml", mutableMapOf("main.yaml" to "old: 1\n"))
        assertTrue(transaction(idle, false, { fail("not running: no reload"); "" }).save("main.yaml", validYaml) is ConfigApplyResult.Applied)
        assertTrue(transaction(FakeStore("main.yaml", mutableMapOf("main.yaml" to "x: 1\n")), null, { fail("busy: no reload"); "" })
            .save("main.yaml", validYaml) is ConfigApplyResult.NeedsRestart)

        val invalid = FakeStore("main.yaml", mutableMapOf("main.yaml" to "old: 1\n"))
        try { transaction(invalid, true, { "" }, validate = { throw IOException("YAML 校验失败") }).save("main.yaml", validYaml); fail("invalid") }
        catch (error: IOException) { assertTrue(error.message!!.contains("校验失败")) }
        assertEquals("nothing written when the core rejects validation", "old: 1\n", invalid.files["main.yaml"])
        assertTrue(ConfigBackups(File(dir, "backups")).list().isNotEmpty())
    }

    @Test fun importSelectsNewConfigAndRestoresPreviousSelectionOnFailure() = runBlocking {
        val store = FakeStore("main.yaml", mutableMapOf("main.yaml" to "old: 1\n"))
        val (name, ok) = transaction(store, true, { "ok" }).import("sub.yaml", validYaml)
        assertEquals("sub.yaml", name)
        assertTrue(ok is ConfigApplyResult.Applied)
        assertEquals("sub.yaml", store.selected)

        val calls = AtomicInteger()
        val (second, failed) = transaction(store, true, { if (calls.incrementAndGet() == 1) throw IOException("配置无效") else "" }).import("sub.yaml", validYaml)
        assertEquals("sub (2).yaml", second)
        assertTrue(failed is ConfigApplyResult.RolledBack)
        assertEquals("previous selection restored", "sub.yaml", store.selected)
        assertEquals("old: 1\n", store.files["main.yaml"])

        // Backups keep the newest [keep] per config and restore through the same transaction.
        val backups = ConfigBackups(File(dir, "keep"), keep = 2)
        repeat(4) { backups.save("main.yaml", "v$it: 1\n", "edit", now = 1_000L + it) }
        val listed = backups.list("main.yaml")
        assertEquals(2, listed.size)
        assertEquals("v3: 1\n", backups.read(listed.first()))
        val restoreStore = FakeStore("main.yaml", mutableMapOf("main.yaml" to "now: 1\n"))
        val restored = ConfigTransaction(restoreStore, backups, {}, { false }, { "" }).restore(listed.last())
        assertTrue(restored is ConfigApplyResult.Applied)
        assertEquals("v2: 1\n", restoreStore.files["main.yaml"])
    }
}
