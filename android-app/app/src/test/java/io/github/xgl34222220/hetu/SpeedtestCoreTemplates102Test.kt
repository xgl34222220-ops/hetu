package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 测速「已到时限」与非 Mihomo 内核内置模板：真实回环 HTTP 与 ProxyCoreConfig 实际启动转换。
 * 模板另经 sing-box 1.14.3 check / xray 26.3.27 run -test / v2ray 5.40.0 test / Hysteria 2.6.4 客户端解析，
 * 见 tools/qa/test_core_templates93.py。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SpeedtestCoreTemplates102Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("hetu", 0)
    private val nonMihomo = listOf(ProxyRuntimeProfile.Core.SING_BOX, ProxyRuntimeProfile.Core.SING_BOX_REF1ND,
        ProxyRuntimeProfile.Core.XRAY, ProxyRuntimeProfile.Core.V2FLY, ProxyRuntimeProfile.Core.HYSTERIA)

    @Before fun reset() {
        prefs.edit().clear().commit()
        File(context.filesDir, "hetu/configs").deleteRecursively()
    }

    /* ---------------- 测速预算 ---------------- */

    @Test fun waveBudgetKeepsTheSmallWaveFloorAndScalesWithNodeCount() {
        assertEquals(LatencyProbeBudget.DEFAULT_TIMEOUT_MS, LatencyProbeBudget.waveTimeoutMs(0))
        assertEquals(LatencyProbeBudget.DEFAULT_TIMEOUT_MS, LatencyProbeBudget.waveTimeoutMs(2))
        assertEquals(LatencyProbeBudget.DEFAULT_TIMEOUT_MS, LatencyProbeBudget.waveTimeoutMs(6))
        // 60 dead nodes, six in flight, 5 s core timeout each: the first pass alone needs ~50 s,
        // which the old fixed 20 s budget always cut into a partial 已到时限 wave.
        val sixty = LatencyProbeBudget.waveTimeoutMs(60)
        val firstPass = 10 * LatencyProbeBudget.NODE_TIMEOUT_MS
        assertTrue("budget $sixty must cover every round of dead nodes", sixty > firstPass)
        assertTrue(LatencyProbeBudget.waveTimeoutMs(30) < sixty)
        assertEquals(LatencyProbeBudget.MAX_WAVE_TIMEOUT_MS, LatencyProbeBudget.waveTimeoutMs(10_000))
        assertEquals(6, LatencyProbeBudget.WAVE_CONCURRENCY)
    }

    @Test fun wholeWaveDeadlineMessageNamesProgressAndStaysDistinctFromNodeTimeout() {
        val partial = IncompleteLatencyProbe(mapOf("a" to 20L, "b" to -1L, "c" to 31L), total = 10)
        val text = partial.message.orEmpty()
        assertTrue(text, text.contains("时限"))
        assertTrue(text, text.contains("3 / 10"))
        assertTrue(text, text.contains("部分结果未返回"))
        assertTrue(IncompleteLatencyProbe().message.orEmpty().contains("部分结果未返回"))
        // A node timeout is the node's own failure, never the wave deadline.
        val node = MihomoControllerClient.DelayFailure(true)
        assertEquals("节点测速超时", node.message)
        assertFalse(node.message.orEmpty().contains("时限"))
    }

    @Test fun onlyLostRepliesAreRetryable() {
        assertTrue(transientProbeFailure(SocketTimeoutException("read timed out")))
        assertTrue(transientProbeFailure(java.net.SocketException("reset")))
        assertTrue(transientProbeFailure(EOFException("closed")))
        assertFalse(transientProbeFailure(MihomoControllerClient.DelayFailure(true)))
        assertFalse(transientProbeFailure(MihomoControllerClient.DelayFailure(false)))
        assertFalse(transientProbeFailure(IOException("代理或控制接口已变化，请重新测速")))
        assertFalse(transientProbeFailure(IOException("测速请求已取消或达到总时限，已保留完成的结果，请重试")))
        assertFalse(transientProbeFailure(IncompleteLatencyProbe()))
    }

    private fun controller(server: MockWebServer, nodes: List<String>, delay: (String, Int) -> MockResponse): List<String> {
        val paths = Collections.synchronizedList(mutableListOf<String>())
        val counts = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val proxies = JSONObject()
        nodes.forEach { proxies.put(it, JSONObject().put("name", it).put("type", "Shadowsocks")) }
        proxies.put("Auto", JSONObject().put("name", "Auto").put("type", "URLTest").put("all", JSONArray(nodes))
            .put("now", nodes.first()).put("fixed", nodes.first()).put("testUrl", "https://www.gstatic.com/generate_204"))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                paths += path
                return when {
                    path == "/proxies" -> MockResponse().setBody(JSONObject().put("proxies", proxies).toString())
                    path == "/providers/proxies" -> MockResponse().setBody("{\"providers\":{}}")
                    path.startsWith("/proxies/") && path.endsWith("/delay") -> {
                        val node = request.requestUrl!!.pathSegments[1]
                        delay(node, counts.getOrPut(node) { AtomicInteger() }.incrementAndGet())
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        prefs.edit().putBoolean("proxyCustomApiEnabled", true).putString("proxyCustomApiHost", "127.0.0.1")
            .putInt("proxyCustomApiPort", server.port).commit()
        return paths
    }

    @Test fun lostReplyIsRetriedOnceWhileCoreTimeoutsAndFailuresAreNot() = runBlocking {
        MockWebServer().use { server ->
            val nodes = listOf("flaky", "dead", "broken", "ok-a", "ok-b", "ok-c", "ok-d", "ok-e")
            val paths = controller(server, nodes) { node, attempt ->
                when (node) {
                    "flaky" -> if (attempt == 1) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        else MockResponse().setBody("{\"delay\":88}")
                    "dead" -> MockResponse().setResponseCode(504).setBody("{\"message\":\"Timeout\"}")
                    "broken" -> MockResponse().setResponseCode(503).setBody("{\"message\":\"failed\"}")
                    else -> MockResponse().setBody("{\"delay\":40}")
                }
            }
            val landed = Collections.synchronizedMap(LinkedHashMap<String, Long>())
            val result = ProxyDashboardRepository(context).globalDelay(nodes.size) { node, value -> landed[node] = value }
            assertEquals(88L, result["flaky"])
            assertEquals(-1L, result["dead"])
            assertEquals(-2L, result["broken"])
            listOf("ok-a", "ok-b", "ok-c", "ok-d", "ok-e").forEach { assertEquals(40L, result[it]) }
            // Progressive results reached the caller as they landed, including the retried node.
            assertEquals(result, landed.toMap())
            assertEquals(2, paths.count { it == "/proxies/flaky/delay" })
            assertEquals("A core-confirmed timeout is never re-spent", 1, paths.count { it == "/proxies/dead/delay" })
        }
    }

    @Test fun unfinishedFirstPassReportsWholeWaveDeadlineWithCompletedLeaves() = runBlocking {
        MockWebServer().use { server ->
            val nodes = listOf("fast", "hang")
            controller(server, nodes) { node, _ ->
                if (node == "hang") MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                else MockResponse().setBody("{\"delay\":22}")
            }
            val partial = try {
                latencyProbeOperation(600) { ProxyDashboardRepository(context).globalDelay(nodes.size) }
                null
            } catch (error: IncompleteLatencyProbe) { error }
            assertNotNull("A wave cut by its total budget must not report full success", partial)
            assertEquals(mapOf("fast" to 22L), partial!!.results)
            assertEquals(2, partial.total)
            assertTrue(partial.message.orEmpty().contains("1 / 2"))
        }
    }

    /* ---------------- 内置模板 ---------------- */

    private fun options(mode: ProxyRuntimeProfile.Mode) = ProxyCoreConfig.Options().apply {
        this.mode = mode
        if (mode == ProxyRuntimeProfile.Mode.TPROXY) tproxyPort = MihomoStartupConfig.TPROXY_PORT
        else redirectPort = MihomoStartupConfig.REDIRECT_PORT
        dnsPort = MihomoStartupConfig.DNS_PORT; controllerPort = MihomoStartupConfig.CONTROLLER_PORT
        egressPort = MihomoStartupConfig.egressProbePort(MihomoStartupConfig.CONTROLLER_PORT); secret = "fixture-secret"
    }

    @Test fun everyNonMihomoCoreHasAParseableNativeTemplateOnHetuPorts() {
        for (core in nonMihomo) {
            val name = BundledCoreTemplates.name(core)!!
            val text = BundledCoreTemplates.text(core)!!
            assertTrue("$name extension belongs to ${core.label}", core.extensions.contains(name.substringAfterLast('.')))
            assertTrue(ProxyCoreConfig.isNativeFor(core, text))
            assertTrue(BundledCoreTemplates.hasPlaceholderNode(text))
            if (core == ProxyRuntimeProfile.Core.HYSTERIA) {
                val yaml = Yaml(SafeConstructor(LoaderOptions())).load<Map<String, Any>>(text)
                assertEquals("example.invalid:443", yaml["server"])
                assertFalse(ProxyCoreConfig.isMihomoYaml(text))
                continue
            }
            val root = JSONObject(text)
            val ports = (0 until root.getJSONArray("inbounds").length()).map { index ->
                root.getJSONArray("inbounds").getJSONObject(index).let { it.optInt("listen_port", it.optInt("port")) }
            }.toSet()
            assertEquals(setOf(MihomoStartupConfig.TPROXY_PORT, MihomoStartupConfig.REDIRECT_PORT, MihomoStartupConfig.DNS_PORT), ports)
            assertTrue("$name keeps China traffic direct", text.contains("geosite-cn") || text.contains("geosite:cn"))
        }
        val singBox = JSONObject(BundledCoreTemplates.SING_BOX)
        assertEquals("127.0.0.1:${MihomoStartupConfig.CONTROLLER_PORT}",
            singBox.getJSONObject("experimental").getJSONObject("clash_api").getString("external_controller"))
    }

    @Test fun templatesShipNoRealServerOrCredential() {
        for (core in nonMihomo) {
            val text = BundledCoreTemplates.text(core)!!
            Regex("\"(?:server|address)\"\\s*:\\s*\"([^\"]+)\"").findAll(text).forEach { match ->
                val host = match.groupValues[1]
                // "dns-direct" is a sing-box DNS rule naming its server tag, not a host.
                assertTrue("unexpected server $host", host == "example.invalid" || host in setOf("223.5.5.5", "1.1.1.1", "dns-direct"))
            }
            Regex("\"(?:uuid|id)\"\\s*:\\s*\"([^\"]+)\"").findAll(text).forEach {
                assertEquals("00000000-0000-0000-0000-000000000000", it.groupValues[1])
            }
        }
    }

    @Test fun hetuStartupConversionAcceptsEachTemplateInTproxyAndRedirect() {
        for (core in nonMihomo) for (mode in listOf(ProxyRuntimeProfile.Mode.TPROXY, ProxyRuntimeProfile.Mode.REDIRECT)) {
            val result = ProxyCoreConfig.build(core, BundledCoreTemplates.text(core)!!, options(mode)) { null }
            assertTrue("${core.label} $mode stays native", result.nativeSource)
            assertTrue(result.warnings.first().contains("占位节点"))
            val port = if (mode == ProxyRuntimeProfile.Mode.TPROXY) MihomoStartupConfig.TPROXY_PORT else MihomoStartupConfig.REDIRECT_PORT
            assertTrue("${core.label} $mode listens on $port", result.config.contains(port.toString()))
            assertTrue(result.config.contains(MihomoStartupConfig.DNS_PORT.toString()))
            if (ProxyCoreConfig.clashApi(core)) assertTrue(result.config.contains("fixture-secret"))
        }
        // The converted Mihomo built-in (placeholder subscription URLs) is not mistaken for a template.
        val converted = ProxyCoreConfig.build(ProxyRuntimeProfile.Core.SING_BOX,
            "proxies:\n  - {name: a, type: ss, server: 192.0.2.1, port: 8388, cipher: aes-128-gcm, password: p}\n" +
                "proxy-providers:\n  sub: {type: http, url: 'https://example.invalid/hetu-subscription-1', path: ./p/sub.yaml}\n" +
                "rules:\n  - MATCH,a\n", options(ProxyRuntimeProfile.Mode.TPROXY)) { null }
        assertFalse(converted.warnings.any { it.contains("占位节点") })
    }

    @Test fun libraryListsTheTemplateForItsCoreWithoutSelectingItOrTouchingMihomo() {
        val library = ProxyConfigLibrary(context)
        for (core in nonMihomo) {
            val name = BundledCoreTemplates.name(core)!!
            val entry = library.list(core).single { it.name == name }
            assertEquals(BundledCoreTemplates.text(core), library.read(entry))
            assertTrue(ProxyConfigLibrary.isBundled(name))
            // No implicit selection: the start keeps converting the selected Mihomo profile.
            assertNull(library.selected(core))
            assertEquals("", prefs.getString("proxySelectedConfig.${core.id}", ""))
            try { library.delete(entry); fail("built-in template must not be deleted") } catch (expected: IOException) { }
            try { library.rename(entry, "mine.json"); fail("built-in template must not be renamed") } catch (expected: IOException) { }
            assertTrue(entry.file.isFile)
            library.select(core, name)
            assertEquals(name, library.selected(core)!!.name)
        }
        val mihomo = library.list(ProxyRuntimeProfile.Core.MIHOMO).map { it.name }
        assertEquals(listOf(ProxyConfigLibrary.BUNDLED_NAME), mihomo)
        assertEquals(ProxyConfigLibrary.BUNDLED_NAME, library.selected(ProxyRuntimeProfile.Core.MIHOMO)!!.name)
    }

    @Test fun editedTemplateIsKeptAndLosesThePlaceholderWarning() {
        val library = ProxyConfigLibrary(context)
        val core = ProxyRuntimeProfile.Core.XRAY
        val entry = library.list(core).single { it.name == BundledCoreTemplates.XRAY_NAME }
        library.write(entry, BundledCoreTemplates.XRAY.replace("example.invalid", "node.example.com"))
        val again = library.list(core).single { it.name == BundledCoreTemplates.XRAY_NAME }
        val text = library.read(again)
        assertFalse(BundledCoreTemplates.hasPlaceholderNode(text))
        val result = ProxyCoreConfig.build(core, text, options(ProxyRuntimeProfile.Mode.TPROXY)) { null }
        assertFalse(result.warnings.any { it.contains("占位节点") })
    }
}
