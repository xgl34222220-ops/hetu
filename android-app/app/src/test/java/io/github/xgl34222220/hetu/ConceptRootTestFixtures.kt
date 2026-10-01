package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.shadow.api.Shadow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.io.IOException
import kotlin.coroutines.Continuation

/** One bounded, test-controlled selection request. Never wait for it on the UI thread. */
internal class ConceptSelectionGate internal constructor(private val timeoutMs: Long) {
    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)
    @Volatile private var failure: Throwable? = null
    private var completed = false

    fun awaitStarted(timeoutMs: Long = 5_000L): Boolean {
        require(timeoutMs in 1L..20_000L)
        return entered.await(timeoutMs, TimeUnit.MILLISECONDS)
    }
    fun succeed() = complete(null)
    fun fail(message: String = "测试选择失败") = complete(IOException(message))
    fun cancel() = complete(CancellationException("Selection cancelled by the UI test"))

    @Synchronized private fun complete(result: Throwable?) {
        if (completed) return
        completed = true
        failure = result
        released.countDown()
    }

    internal fun awaitOutcome() {
        entered.countDown()
        if (!released.await(timeoutMs, TimeUnit.MILLISECONDS)) throw IOException("Timed out waiting for the UI test to release selection")
        failure?.let { throw it }
    }
}

internal fun blockNextConceptSelection(timeoutMs: Long = 10_000L): ConceptSelectionGate {
    require(timeoutMs in 1_000L..20_000L)
    val gate = ConceptSelectionGate(timeoutMs)
    check(ConceptTestIo.nextSelectionGate.compareAndSet(null, gate)) { "A selection gate is already armed" }
    ConceptTestIo.selectionGates += gate
    return gate
}

internal fun failNextConceptSelection(message: String = "测试选择失败") {
    check(ConceptTestIo.nextSelectionFailure.compareAndSet(null, IOException(message))) { "A one-shot selection failure is already armed" }
}

/** Hold the API read separately from the PUT acknowledgement. */
internal fun blockNextConceptProxyRead(timeoutMs: Long = 10_000L): ConceptSelectionGate {
    require(timeoutMs in 1_000L..20_000L)
    val gate = ConceptSelectionGate(timeoutMs)
    check(ConceptTestIo.nextProxyReadGate.compareAndSet(null, gate)) { "A proxy read gate is already armed" }
    ConceptTestIo.selectionGates += gate
    return gate
}

internal fun conceptSelectionRequestCount(): Int = ConceptTestIo.selectionRequests.get()

/** Explicitly enable production controller/VM read-back against the in-memory backend. */
internal fun enableConceptActionMode(vm: HetuViewModel) {
    check(Shadow.extract<Any>(vm.inspector) is ConceptRuntimeInspectorShadow) {
        "ConceptRuntimeInspectorShadow is required: real sample() can schedule external WAN IO"
    }
    check(vm.state.running) { "Set a running concept state before enabling action mode" }
    // Production state() reads icons from the selected source. Select a test-local,
    // icon-free source so a later real UI render cannot fetch bundled remote icons.
    val context = vm.getApplication<Application>()
    val core = ProxyRuntimeProfile.load(vm.prefs).core
    ProxyConfigLibrary(context).importConfig(core, "concept-action-fixture.yaml",
        "mixed-port: 7890\nproxies: []\nproxy-groups: []\nrules: [MATCH,DIRECT]\n".byteInputStream())
    ConceptTestIo.state = vm.state
    ConceptTestIo.actionMode = true
    vm.prefs.edit().putBoolean("proxyRootWanted", false).putBoolean("proxyRootRuntimeRunning", false)
        .putLong("proxyRootHealthProbeElapsed", 0L).putBoolean("proxyApiHistoryEnabled", false).commit()
}

/**
 * Test-only fixtures for the real HetuRoot and real HetuViewModel.
 *
 * Required on the test: @Config(shadows = [ConceptRootBridgeShadow::class,
 * ConceptMihomoClientShadow::class, ConceptRuntimeInspectorShadow::class]).
 * No HTTP server, socket or su process is used.
 * Call fixture/state helpers on the test UI thread. Do not call vm.onForeground().
 * Fixture data proves presentation and callback wiring, never live proxy behavior.
 */
internal fun newConceptTestVm(application: Application): HetuViewModel {
    // Both constructors are side-effect free. Verify interception before any VM IO
    // can be scheduled; a missing @Config fails here rather than attempting su/HTTP.
    val rootMarker = RootBridge::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    check(Shadow.extract<Any>(rootMarker) is ConceptRootBridgeShadow) {
        "Add ConceptRootBridgeShadow to the test's @Config before creating this fixture"
    }
    check(Shadow.extract<Any>(MihomoControllerClient(application)) is ConceptMihomoClientShadow) {
        "Add ConceptMihomoClientShadow to the test's @Config before creating this fixture"
    }
    check(Shadow.extract<Any>(ProxyRuntimeInspector(application)) is ConceptRuntimeInspectorShadow) {
        "Add ConceptRuntimeInspectorShadow to the test's @Config before creating this fixture"
    }
    ConceptTestIo.reset()
    application.getSharedPreferences("hetu", Context.MODE_PRIVATE).edit()
        .putBoolean("proxyRootWanted", false)
        .putBoolean("proxyRootRuntimeRunning", false)
        .putBoolean("proxyApiHistoryEnabled", false)
        // A missing client interception still has no token with which to send IO.
        .putBoolean("proxyCustomApiEnabled", false)
        .putString("proxyControllerSecret", "")
        .commit()
    return HetuViewModel(application).also { vm ->
        setConceptState(vm, ProxyComposeState())
        setConceptValue(vm, "rules", conceptRules())
        setConceptValue(vm, "ruleSets", conceptRuleSets())
        setConceptValue(vm, "providers", conceptProviders())
        setConceptValue(vm, "logEntries", refParseLogs19(ConceptTestIo.logText))
    }
}

internal fun setConceptState(vm: HetuViewModel, state: ProxyComposeState) =
    setConceptValue(vm, "state", state)

/** Set a Compose delegated VM property without opening production setters for tests. */
@Suppress("UNCHECKED_CAST")
internal fun <T> setConceptValue(vm: HetuViewModel, name: String, value: T) {
    val field = HetuViewModel::class.java.getDeclaredField("${name}\$delegate").apply { isAccessible = true }
    val delegate = field.get(vm) as? MutableState<T>
        ?: error("$name is not a MutableState-backed HetuViewModel property")
    delegate.value = value
    when (name) {
        "state" -> ConceptTestIo.state = value as ProxyComposeState
        "rules" -> ConceptTestIo.rules = value as List<ProxyRuleUi>
        "ruleSets" -> ConceptTestIo.ruleSets = value as List<DashboardRuleSetUi>
        "providers" -> ConceptTestIo.providers = value as List<DashboardProviderUi>
    }
}

/** Dispose the root composition first, then cancel jobs belonging to this test VM. */
internal fun closeConceptTestVm(vm: HetuViewModel) {
    vm.onBackground()
    ConceptTestIo.selectionGates.forEach { it.cancel() }
    vm.viewModelScope.cancel()
}

internal fun conceptRunningState(): ProxyComposeState {
    val nodes = listOf(
        ProxyNodeUi("香港 01", "Shadowsocks", udp = true, lastDelay = 42),
        ProxyNodeUi("日本 02", "Trojan", udp = true, lastDelay = 76),
        ProxyNodeUi("新加坡 03", "VLESS", lastDelay = null),
    )
    return ProxyComposeState(
        running = true,
        panelReady = true,
        config = "界面测试配置.yaml",
        trafficMode = "rule",
        groups = listOf(
            ProxyGroupUi("节点选择", "Selector", nodes.first().name, nodes),
            ProxyGroupUi("国外媒体", "URLTest", nodes[1].name, nodes),
            ProxyGroupUi("国内直连", "Selector", "DIRECT", listOf(ProxyNodeUi("DIRECT", "Direct"))),
            ProxyGroupUi("自动选择", "URLTest", nodes.first().name, nodes),
        ),
        connections = listOf(
            ProxyConnectionUi("concept-browser", "example.invalid", "DomainSuffix", "example.invalid", "香港 01 → 节点选择", 8192, 524288, "TCP", "TUN", process = "concept-browser", appName = "浏览器"),
            ProxyConnectionUi("concept-direct", "local.example.invalid", "IPCIDR", "192.0.2.0/24", "DIRECT", 2048, 32768, "TCP", "TUN", process = "concept-files", appName = "文件"),
            ProxyConnectionUi("concept-udp", "media.example.invalid", "Match", "", "日本 02 → 节点选择", 4096, 131072, "UDP", "TUN", process = "concept-media", appName = "媒体"),
        ),
        downloadTotal = 688128,
        uploadTotal = 14336,
    )
}

internal fun conceptRules(): List<ProxyRuleUi> = listOf(
    ProxyRuleUi(0, "DOMAIN-SUFFIX", "example.invalid", "节点选择"),
    ProxyRuleUi(1, "IP-CIDR", "192.0.2.0/24", "DIRECT"),
    ProxyRuleUi(2, "GEOIP", "CN", "DIRECT"),
    ProxyRuleUi(3, "MATCH", "", "节点选择"),
)

internal fun conceptRuleSets(): List<DashboardRuleSetUi> = listOf(
    DashboardRuleSetUi("广告过滤", "domain", "yaml", "HTTP", 124832, "2026-09-30T12:00:00Z"),
    DashboardRuleSetUi("国内直连", "ipcidr", "mrs", "HTTP", 67219, "2026-09-30T10:00:00Z"),
    DashboardRuleSetUi("局域网", "classical", "yaml", "File", 2317, ""),
)

internal fun conceptProviders(): List<DashboardProviderUi> = listOf(
    DashboardProviderUi("界面测试订阅", "HTTP", "", "", "2026-09-30T12:00:00Z",
        2L * 1024 * 1024 * 1024, 18L * 1024 * 1024 * 1024, 100L * 1024 * 1024 * 1024,
        0, setOf("香港 01", "日本 02", "新加坡 03"), true),
)

/** Observable calls are test assertions, not claims that any runtime operation ran. */
internal object ConceptTestIo {
    val calls = CopyOnWriteArrayList<String>()
    val delayFailures = ConcurrentHashMap<String, Exception>()
    val selectionRequests = AtomicInteger()
    val nextSelectionGate = AtomicReference<ConceptSelectionGate?>(null)
    val nextSelectionFailure = AtomicReference<IOException?>(null)
    val nextSelectionResultNode = AtomicReference<String?>(null)
    val nextProxyReadGate = AtomicReference<ConceptSelectionGate?>(null)
    val nextProxyReadFailure = AtomicReference<IOException?>(null)
    val nextProxyReadJson = AtomicReference<String?>(null)
    val selectionGates = CopyOnWriteArrayList<ConceptSelectionGate>()
    @Volatile var actionMode = false
    @Volatile var state = conceptRunningState()
    @Volatile var rules = conceptRules()
    @Volatile var ruleSets = conceptRuleSets()
    @Volatile var providers = conceptProviders()
    @Volatile var runtimeSample: ProxyRuntimeSnapshot? = null
    @Volatile var runtimeFailure: Exception? = null
    @Volatile var processSampleJson: String? = null
    @Volatile var diagnosticConnectionsJson: String? = null
    @Volatile var diagnosticConnectionsFailure: IOException? = null
    @Volatile var diagnosticInventoryText: String? = null
    const val logText = "[INFO] 连接已建立 example.invalid:443\n[WARN] 规则未匹配，使用 MATCH\n[ERROR] 测试连接已超时\n"

    fun reset() {
        selectionGates.forEach { it.cancel() }
        selectionGates.clear()
        nextSelectionGate.set(null)
        nextSelectionFailure.set(null)
        nextSelectionResultNode.set(null)
        nextProxyReadGate.set(null)
        nextProxyReadFailure.set(null)
        nextProxyReadJson.set(null)
        selectionRequests.set(0)
        actionMode = false
        calls.clear()
        delayFailures.clear()
        state = conceptRunningState()
        rules = conceptRules()
        ruleSets = conceptRuleSets()
        providers = conceptProviders()
        runtimeSample = null
        runtimeFailure = null
        processSampleJson = null
        diagnosticConnectionsJson = null
        diagnosticConnectionsFailure = null
        diagnosticInventoryText = null
    }

    fun stoppedRoot() = JSONObject().put("ok", true).put("running", false)
        .put("installed", false).put("rootGranted", false).put("pid", 0)
        .put("message", "Root is isolated by the UI test fixture")

    fun runtimeRoot(): JSONObject = if (!actionMode) stoppedRoot() else JSONObject()
        .put("ok", true).put("running", state.running).put("state", "running")
        .put("controllerPort", 29090).put("pid", 1234).put("runtimeSchema", 0)
        .put("dataPlaneHealthy", true).put("ipv4Rules", true).put("dnsListenerReady", true)
        .put("message", "")
}

/** No unhandled RootBridge method is allowed to call through to production. */
@Implements(value = RootBridge::class, isInAndroidSdk = false, callThroughByDefault = false)
class ConceptRootBridgeShadow {
    companion object {
        @JvmStatic @Resetter fun reset() = ConceptTestIo.reset()

        @JvmStatic @Implementation fun hasRoot(context: Context): Boolean = false
        @JvmStatic @Implementation fun status(context: Context): JSONObject = ConceptTestIo.stoppedRoot()
        @JvmStatic @Implementation fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        @JvmStatic @Implementation
        fun rootShell(context: Context, command: String, timeoutMs: Long): RootBridge.Result {
            ConceptTestIo.calls += "root:blocked"
            return when {
                command.contains("# Read-only, bounded process metadata.") -> ConceptTestIo.diagnosticInventoryText
                    ?.let { RootBridge.Result(0, it) } ?: RootBridge.Result(126, "inventory unavailable")
                command.startsWith("P=") && command.contains("/proc/uptime") -> ConceptTestIo.processSampleJson
                    ?.let { RootBridge.Result(0, it) } ?: RootBridge.Result(1, "sample unavailable")
                command.startsWith("echo '--- controller-port ---';") -> RootBridge.Result(0, ConceptTestIo.logText)
                command.startsWith("if [ -x ") && command.contains(" 'status';") ->
                    RootBridge.Result(0, ConceptTestIo.runtimeRoot().toString())
                else -> RootBridge.Result(126, "Root command blocked by the UI test fixture")
            }
        }

        @JvmStatic @Implementation
        fun run(context: Context, vararg args: String): RootBridge.Result = RootBridge.Result(126, "Root command blocked by the UI test fixture")

        @JvmStatic @Implementation
        fun run(context: Context, timeoutMs: Long, vararg args: String): RootBridge.Result = RootBridge.Result(126, "Root command blocked by the UI test fixture")
    }
}

/**
 * sample() otherwise schedules the independent ipwho.is WAN lookup after a successful
 * production refresh. Kotlin suspend methods erase to (Continuation) -> Object; these
 * ordinary methods match that exact JVM boundary and return completed values directly.
 */
@Implements(value = ProxyRuntimeInspector::class, isInAndroidSdk = false, callThroughByDefault = false)
class ConceptRuntimeInspectorShadow {
    @Implementation
    fun sample(continuation: Continuation<Any?>): Any {
        ConceptTestIo.calls += "inspector:sample"
        ConceptTestIo.runtimeFailure?.let { throw it }
        return ConceptTestIo.runtimeSample ?: ProxyRuntimeSnapshot(running = ConceptTestIo.actionMode && ConceptTestIo.state.running)
    }

    @Implementation
    fun runtimeLog(continuation: Continuation<Any?>): Any = ConceptTestIo.logText
}

/** In-memory API boundary. Unhandled methods cannot fall through to a real socket. */
@Implements(value = MihomoControllerClient::class, isInAndroidSdk = false, callThroughByDefault = false)
internal class ConceptMihomoClientShadow {
    companion object {
        // The production factory is static. Keep it inside this same socket-free
        // boundary instead of allowing an unimplemented shadow call to return null.
        @JvmStatic @Implementation
        fun forLocalRuntime(context: Context): MihomoControllerClient = MihomoControllerClient(context)
    }

    @Implementation fun proxies(): JSONObject {
        ConceptTestIo.calls += "GET /proxies"
        val gate = ConceptTestIo.nextProxyReadGate.getAndSet(null)
        try { gate?.awaitOutcome() } finally { if (gate != null) ConceptTestIo.selectionGates.remove(gate) }
        ConceptTestIo.nextProxyReadFailure.getAndSet(null)?.let { throw it }
        ConceptTestIo.nextProxyReadJson.getAndSet(null)?.let { return JSONObject(it) }
        return JSONObject().also { out ->
            ConceptTestIo.state.groups.forEach { group ->
                out.put(group.name, JSONObject().put("type", group.type).put("now", group.now)
                    .put("all", JSONArray(group.nodes.map { it.name })))
                group.nodes.forEach { node -> out.put(node.name, JSONObject().put("type", node.type).put("udp", node.udp)) }
            }
        }
    }

    @Implementation fun proxyProviders(): JSONObject = JSONObject().put("providers", JSONObject().also { out ->
        ConceptTestIo.calls += "GET /providers/proxies"
        ConceptTestIo.providers.forEach { provider ->
            out.put(provider.name, JSONObject().put("vehicleType", provider.vehicleType).put("updatedAt", provider.updatedAt)
                .put("proxies", JSONArray(provider.nodes.map { JSONObject().put("name", it).put("type", "Shadowsocks") }))
                .put("subscriptionInfo", JSONObject().put("Upload", provider.upload).put("Download", provider.download)
                    .put("Total", provider.total).put("Expire", provider.expire)))
        }
    })

    @Implementation fun ruleProviders(): JSONObject = JSONObject().put("providers", JSONObject().also { out ->
        ConceptTestIo.calls += "GET /providers/rules"
        ConceptTestIo.ruleSets.forEach { set -> out.put(set.name, JSONObject().put("behavior", set.behavior)
            .put("format", set.format).put("vehicleType", set.vehicleType).put("ruleCount", set.ruleCount).put("updatedAt", set.updatedAt)) }
    })

    @Implementation fun rules(): JSONObject = JSONObject().put("rules", JSONArray(ConceptTestIo.rules.map { rule ->
        JSONObject().put("index", rule.index).put("type", rule.type).put("payload", rule.payload).put("proxy", rule.proxy)
    }))

    @Implementation fun connections(): JSONObject {
        ConceptTestIo.calls += "GET /connections"
        ConceptTestIo.diagnosticConnectionsFailure?.let { throw it }
        ConceptTestIo.diagnosticConnectionsJson?.let { return JSONObject(it) }
        val snapshot = ConceptTestIo.state
        val entries = if (!ConceptTestIo.actionMode) emptyList() else snapshot.connections.map { connection ->
            JSONObject().put("id", connection.id).put("rule", connection.rule).put("rulePayload", connection.rulePayload)
                .put("chains", JSONArray(connection.chain.split(" → "))).put("upload", connection.upload).put("download", connection.download)
                .put("metadata", JSONObject().put("host", connection.host).put("network", connection.network)
                    .put("inboundName", connection.inbound).put("uid", 10001)
                    .put("process", connection.appName.ifBlank { connection.process.ifBlank { "concept-fixture" } }))
        }
        return JSONObject().put("connections", JSONArray(entries)).put("uploadTotal", snapshot.uploadTotal).put("downloadTotal", snapshot.downloadTotal)
    }
    @Implementation fun configs(): JSONObject = JSONObject().put("mode", ConceptTestIo.state.trafficMode.ifBlank { "rule" })
    @Implementation fun version(): JSONObject = JSONObject().put("version", "concept-ui-fixture").put("meta", true)
    @Implementation fun updateRuleProvider(name: String) { ConceptTestIo.calls += "PUT /providers/rules/$name" }
    @Implementation fun updateProxyProvider(name: String) { ConceptTestIo.calls += "PUT /providers/proxies/$name" }
    @Implementation fun closeConnection(id: String) { ConceptTestIo.calls += "DELETE /connections/$id" }
    @Implementation fun closeAll() { ConceptTestIo.calls += "DELETE /connections" }
    @Implementation fun select(group: String, node: String) {
        ConceptTestIo.calls += "PUT /proxies/$group:$node"
        ConceptTestIo.selectionRequests.incrementAndGet()
        val gate = ConceptTestIo.nextSelectionGate.getAndSet(null)
        val failure = ConceptTestIo.nextSelectionFailure.getAndSet(null)
        val actual = ConceptTestIo.nextSelectionResultNode.getAndSet(null) ?: node
        try { gate?.awaitOutcome() } finally { if (gate != null) ConceptTestIo.selectionGates.remove(gate) }
        failure?.let { throw it }
        ConceptTestIo.state = ConceptTestIo.state.copy(groups = ConceptTestIo.state.groups.map { if (it.name == group) it.copy(now = actual) else it })
    }
    @Implementation fun setTrafficMode(mode: String) {
        ConceptTestIo.calls += "PATCH /configs:$mode"
        ConceptTestIo.state = ConceptTestIo.state.copy(trafficMode = mode)
    }
    @Implementation fun delay(node: String, preferredUrl: String, expected: String): Long {
        ConceptTestIo.calls += "DELAY:$node"
        ConceptTestIo.delayFailures[node]?.let { throw it }
        return 125L
    }
    @Implementation fun providerDelay(provider: String, node: String, preferredUrl: String, expected: String): Long {
        ConceptTestIo.calls += "DELAY:$node"
        ConceptTestIo.delayFailures[node]?.let { throw it }
        return 125L
    }
    @Implementation fun groupDelay(group: String): JSONObject {
        ConceptTestIo.calls += "GROUP_DELAY:$group"
        throw AssertionError("Group health checks can clear forced selection; UI latency tests must use individual probes")
    }
}
