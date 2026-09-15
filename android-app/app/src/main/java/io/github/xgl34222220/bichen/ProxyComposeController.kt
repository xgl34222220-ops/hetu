package io.github.xgl34222220.bichen

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class ProxyGroupUi(val name: String, val type: String, val now: String, val nodes: List<String>)
internal data class ProxyConnectionUi(val id: String, val host: String, val rule: String, val chain: String, val upload: Long, val download: Long)
internal data class ProxyComposeState(
    val running: Boolean = false,
    val core: String = "Mihomo",
    val mode: String = "TPROXY",
    val ipv6: String = "enable",
    val autoOverwrite: Boolean = true,
    val config: String = "尚未选择配置",
    val message: String = "",
    val panelReady: Boolean = false,
    val groups: List<ProxyGroupUi> = emptyList(),
    val connections: List<ProxyConnectionUi> = emptyList(),
    val downloadTotal: Long = 0,
    val uploadTotal: Long = 0,
)

internal class ProxyComposeController(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("bichen", Context.MODE_PRIVATE)
    private val root = RootProxyManager(app)
    private val configs = ProxyConfigLibrary(app)
    private val api = MihomoControllerClient(app)

    suspend fun state(): ProxyComposeState = withContext(Dispatchers.IO) {
        val profile = ProxyRuntimeProfile.load(prefs)
        val status = runCatching { root.status() }.getOrElse { JSONObject().put("running", false).put("message", it.message ?: "状态读取失败") }
        val running = status.optBoolean("running", false)
        var groups = emptyList<ProxyGroupUi>()
        var connections = emptyList<ProxyConnectionUi>()
        var panelReady = false
        var down = 0L
        var up = 0L
        if (running) {
            runCatching {
                val rawGroups = api.proxies()
                groups = parseGroups(rawGroups)
                val rawConnections = api.connections()
                panelReady = true
                down = rawConnections.optLong("downloadTotal", 0)
                up = rawConnections.optLong("uploadTotal", 0)
                connections = parseConnections(rawConnections)
            }
        }
        ProxyComposeState(
            running = running,
            core = profile.core.label,
            mode = profile.mode.label,
            ipv6 = profile.ipv6.id,
            autoOverwrite = profile.autoOverwrite,
            config = configs.selected(profile.core)?.name ?: "尚未选择配置",
            message = status.optString("message", ""),
            panelReady = panelReady,
            groups = groups,
            connections = connections,
            downloadTotal = down,
            uploadTotal = up,
        )
    }

    suspend fun start(onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        root.start(ProxyRuntimeProfile.load(prefs)) { onProgress(it) }
    }

    suspend fun stop(onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) { root.stop { onProgress(it) } }

    suspend fun select(group: String, node: String) = withContext(Dispatchers.IO) { api.select(group, node) }
    suspend fun delay(node: String): Long = withContext(Dispatchers.IO) { api.delay(node) }
    suspend fun closeAll() = withContext(Dispatchers.IO) { api.closeAll() }
    suspend fun diagnostics(): String = withContext(Dispatchers.IO) { root.diagnostics() }
    suspend fun startupConfig(): String = withContext(Dispatchers.IO) { root.prepare(ProxyRuntimeProfile.load(prefs)).startup }

    fun cores(): List<Pair<String, String>> = ProxyRuntimeProfile.Core.values().map { it.id to it.label }
    fun modes(): List<Pair<String, String>> = ProxyRuntimeProfile.Mode.values().filter { ProxyRuntimeProfile.capability(ProxyRuntimeProfile.load(prefs).core, it).available }.map { it.id to it.label }
    fun ipv6Modes(): List<Pair<String, String>> = listOf("enable" to "启用 IPv6", "bypass" to "IPv6 不进核心", "disable" to "禁用系统 IPv6")

    fun setCore(id: String) { prefs.edit().putString("proxyBaseCore", id).apply() }
    fun setMode(id: String) { prefs.edit().putString("proxyBaseMode", id).apply() }
    fun setIpv6(id: String) { prefs.edit().putString("proxyBaseIpv6", id).apply() }
    fun setAutoOverwrite(value: Boolean) { prefs.edit().putBoolean("proxyBaseAutoOverwrite", value).apply() }

    fun configs(): List<String> {
        val profile = ProxyRuntimeProfile.load(prefs)
        return configs.list(profile.core).map { it.name }
    }

    suspend fun selectConfig(name: String) = withContext(Dispatchers.IO) {
        configs.select(ProxyRuntimeProfile.load(prefs).core, name)
    }

    suspend fun importConfig(uri: Uri, displayName: String) = withContext(Dispatchers.IO) {
        val input = app.contentResolver.openInputStream(uri) ?: error("无法读取配置文件")
        configs.importConfig(ProxyRuntimeProfile.load(prefs).core, displayName, input)
    }

    private fun parseGroups(root: JSONObject): List<ProxyGroupUi> {
        val result = ArrayList<ProxyGroupUi>()
        val it = root.keys()
        while (it.hasNext()) {
            val name = it.next()
            if (name == "GLOBAL") continue
            val g = root.optJSONObject(name) ?: continue
            val all = g.optJSONArray("all") ?: continue
            val nodes = ArrayList<String>()
            for (i in 0 until all.length()) nodes += all.optString(i)
            result += ProxyGroupUi(name, g.optString("type", "Group"), g.optString("now", "未选择"), nodes)
        }
        return result.sortedBy { it.name.lowercase() }
    }

    private fun parseConnections(root: JSONObject): List<ProxyConnectionUi> {
        val a = root.optJSONArray("connections") ?: JSONArray()
        val out = ArrayList<ProxyConnectionUi>()
        for (i in 0 until a.length()) {
            val c = a.optJSONObject(i) ?: continue
            val meta = c.optJSONObject("metadata") ?: JSONObject()
            val chains = c.optJSONArray("chains") ?: JSONArray()
            val chain = (0 until chains.length()).joinToString(" → ") { chains.optString(it) }
            out += ProxyConnectionUi(
                id = c.optString("id", i.toString()),
                host = meta.optString("host", meta.optString("destinationIP", "未知目标")),
                rule = c.optString("rule", ""),
                chain = chain,
                upload = c.optLong("upload", 0),
                download = c.optLong("download", 0),
            )
        }
        return out
    }
}
