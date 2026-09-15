package io.github.xgl34222220.bichen

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

internal data class ProxyNodeUi(
    val name: String,
    val type: String = "",
    val udp: Boolean = false,
    val lastDelay: Long? = null,
)

internal data class ProxyGroupUi(
    val name: String,
    val type: String,
    val now: String,
    val nodes: List<ProxyNodeUi>,
    val iconUrl: String = "",
    val iconPath: String = "",
)

internal data class ProxyConnectionUi(
    val id: String,
    val host: String,
    val rule: String,
    val chain: String,
    val upload: Long,
    val download: Long,
)

internal data class ProxySubscriptionUi(val name: String, val url: String, val placeholder: Boolean)

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
    val memoryBytes: Long = 0,
    val corePid: Int = 0,
)

internal class ProxyComposeController(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("bichen", Context.MODE_PRIVATE)
    private val root = RootProxyManager(app)
    private val configs = ProxyConfigLibrary(app)
    private val api = MihomoControllerClient(app)
    private val icons = ProxyIconStore(app)

    suspend fun state(): ProxyComposeState = withContext(Dispatchers.IO) {
        val profile = ProxyRuntimeProfile.load(prefs)
        val selected = configs.selected(profile.core)
        val iconMap = try {
            if (selected == null) emptyMap() else parseGroupIcons(configs.read(selected))
        } catch (_: Exception) { emptyMap() }

        val status = try {
            root.status()
        } catch (error: Exception) {
            JSONObject().put("running", false).put("message", error.message ?: "状态读取失败")
        }
        val running = status.optBoolean("running", false)
        var groups = emptyList<ProxyGroupUi>()
        var connections = emptyList<ProxyConnectionUi>()
        var panelReady = false
        var down = 0L
        var up = 0L
        var memory = 0L

        if (running) {
            try {
                groups = parseGroups(api.proxies(), iconMap)
                panelReady = true
            } catch (_: Exception) { }
            try {
                val rawConnections = api.connections()
                down = rawConnections.optLong("downloadTotal", 0L)
                up = rawConnections.optLong("uploadTotal", 0L)
                memory = rawConnections.optLong("memory", 0L)
                connections = parseConnections(rawConnections)
                panelReady = true
            } catch (_: Exception) { }
        }
        ProxyComposeState(
            running = running,
            core = profile.core.label,
            mode = profile.mode.label,
            ipv6 = profile.ipv6.id,
            autoOverwrite = profile.autoOverwrite,
            config = selected?.name ?: "尚未选择配置",
            message = status.optString("message", ""),
            panelReady = panelReady,
            groups = groups,
            connections = connections,
            downloadTotal = down,
            uploadTotal = up,
            memoryBytes = memory,
            corePid = 0,
        )
    }

    suspend fun start(onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        val profile = ProxyRuntimeProfile.load(prefs)
        val selected = configs.selected(profile.core) ?: error("尚未选择配置")
        if (!configs.hasConfiguredSubscription(selected)) {
            error("默认配置不内置私人订阅，请先到「订阅」添加或编辑自己的订阅")
        }
        root.start(profile) { onProgress(it) }
    }

    suspend fun stop(onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) { root.stop { onProgress(it) } }

    suspend fun select(group: String, node: String) = withContext(Dispatchers.IO) { api.select(group, node) }
    suspend fun delay(node: String): Long = withContext(Dispatchers.IO) { api.delay(node) }
    suspend fun closeAll() = withContext(Dispatchers.IO) { api.closeAll() }
    suspend fun diagnostics(): String = withContext(Dispatchers.IO) { root.diagnostics() }
    suspend fun startupConfig(): String = withContext(Dispatchers.IO) { root.prepare(ProxyRuntimeProfile.load(prefs)).startup }

    /**
     * Prefer Mihomo's native group URLTest for global testing. It performs the fan-out inside the
     * core instead of opening a burst of controller sockets from the UI. Any uncovered leaf nodes
     * fall back to the individual endpoint with the controller's own small concurrency gate.
     */
    suspend fun globalDelay(): Map<String, Long> = withContext(Dispatchers.IO) {
        val raw = api.proxies()
        val leaves = LinkedHashSet<String>()
        val iterator = raw.keys()
        while (iterator.hasNext()) {
            val name = iterator.next()
            if (name == "GLOBAL") continue
            val item = raw.optJSONObject(name) ?: continue
            if (item.optJSONArray("all") != null) continue
            val type = item.optString("type", "").lowercase(Locale.ROOT)
            if (type in setOf("direct", "reject", "rejectdrop", "pass", "compatible")) continue
            leaves += name
        }

        val result = LinkedHashMap<String, Long>()
        val pending = LinkedHashSet(leaves)
        val testedGroups = HashSet<String>()

        while (pending.size > 1) {
            var bestGroup: String? = null
            var bestCoverage = 0
            val groups = raw.keys()
            while (groups.hasNext()) {
                val groupName = groups.next()
                if (groupName in testedGroups) continue
                val all = raw.optJSONObject(groupName)?.optJSONArray("all") ?: continue
                var coverage = 0
                for (i in 0 until all.length()) {
                    if (all.optString(i) in pending) coverage++
                }
                if (coverage > bestCoverage) {
                    bestCoverage = coverage
                    bestGroup = groupName
                }
            }
            if (bestGroup == null || bestCoverage < 2) break
            testedGroups += bestGroup
            val before = pending.size
            try {
                val measured = api.groupDelay(bestGroup)
                val names = measured.keys()
                while (names.hasNext()) {
                    val name = names.next()
                    val value = measured.optLong(name, -1L)
                    if (value > 0L && name in leaves) {
                        result[name] = value
                        pending.remove(name)
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) { }
            if (pending.size == before && testedGroups.size > 6) break
        }

        for (chunk in pending.toList().chunked(3)) {
            val part = coroutineScope {
                chunk.map { node ->
                    async {
                        val value = try {
                            api.delay(node)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            -1L
                        }
                        node to value
                    }
                }.awaitAll()
            }
            for ((name, value) in part) result[name] = value
        }

        for (name in leaves) if (name !in result) result[name] = -1L
        result
    }

    /** Cache config-declared group icons. Cached files are never re-downloaded on ordinary refresh. */
    suspend fun ensureIcons(): Int = withContext(Dispatchers.IO) {
        val profile = ProxyRuntimeProfile.load(prefs)
        val entry = configs.selected(profile.core) ?: return@withContext 0
        val urls = parseGroupIcons(configs.read(entry)).values.filter { it.startsWith("https://") }.distinct()
        var downloaded = 0
        for (chunk in urls.chunked(6)) {
            val part = coroutineScope {
                chunk.map { url ->
                    async {
                        if (icons.cached(url) != null) return@async false
                        try {
                            icons.fetchMissing(url) != null
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            false
                        }
                    }
                }.awaitAll()
            }
            downloaded += part.count { it }
        }
        downloaded
    }

    fun cores(): List<Pair<String, String>> = ProxyRuntimeProfile.Core.values().map { it.id to it.label }
    fun modes(): List<Pair<String, String>> = ProxyRuntimeProfile.Mode.values()
        .filter { ProxyRuntimeProfile.capability(ProxyRuntimeProfile.load(prefs).core, it).available }
        .map { it.id to it.label }
    fun ipv6Modes(): List<Pair<String, String>> = listOf(
        "enable" to "启用 IPv6",
        "bypass" to "IPv6 不进核心",
        "disable" to "禁用系统 IPv6",
    )

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

    suspend fun subscriptions(): List<ProxySubscriptionUi> = withContext(Dispatchers.IO) {
        val profile = ProxyRuntimeProfile.load(prefs)
        val entry = configs.selected(profile.core) ?: return@withContext emptyList()
        configs.subscriptions(entry).map { ProxySubscriptionUi(it.name, it.url, it.placeholder) }
    }

    suspend fun updateSubscription(name: String, url: String) = withContext(Dispatchers.IO) {
        val profile = ProxyRuntimeProfile.load(prefs)
        val entry = configs.selected(profile.core) ?: error("尚未选择配置")
        configs.updateSubscription(entry, name, url)
    }

    suspend fun addSubscription(name: String, url: String) = withContext(Dispatchers.IO) {
        val profile = ProxyRuntimeProfile.load(prefs)
        val entry = configs.selected(profile.core) ?: error("尚未选择配置")
        configs.addSubscription(entry, name, url)
    }

    suspend fun deleteSubscription(name: String) = withContext(Dispatchers.IO) {
        val profile = ProxyRuntimeProfile.load(prefs)
        val entry = configs.selected(profile.core) ?: error("尚未选择配置")
        configs.deleteSubscription(entry, name)
    }

    suspend fun configText(): String = withContext(Dispatchers.IO) {
        val profile = ProxyRuntimeProfile.load(prefs)
        val entry = configs.selected(profile.core) ?: error("尚未选择配置")
        configs.read(entry)
    }

    suspend fun saveConfigText(text: String) = withContext(Dispatchers.IO) {
        val profile = ProxyRuntimeProfile.load(prefs)
        val entry = configs.selected(profile.core) ?: error("尚未选择配置")
        configs.write(entry, text)
    }

    private fun parseGroups(root: JSONObject, iconMap: Map<String, String>): List<ProxyGroupUi> {
        val result = ArrayList<ProxyGroupUi>()
        val iterator = root.keys()
        while (iterator.hasNext()) {
            val name = iterator.next()
            if (name == "GLOBAL") continue
            val group = root.optJSONObject(name) ?: continue
            val all = group.optJSONArray("all") ?: continue
            val nodes = ArrayList<ProxyNodeUi>()
            for (i in 0 until all.length()) {
                val node = all.optString(i)
                if (node.isBlank()) continue
                val nodeInfo = root.optJSONObject(node)
                val history = nodeInfo?.optJSONArray("history")
                var lastDelay: Long? = null
                if (history != null) {
                    for (historyIndex in history.length() - 1 downTo 0) {
                        val value = history.optJSONObject(historyIndex)?.optLong("delay", -1L) ?: -1L
                        if (value > 0L) {
                            lastDelay = value
                            break
                        }
                    }
                }
                nodes += ProxyNodeUi(
                    name = node,
                    type = nodeInfo?.optString("type", "") ?: "",
                    udp = nodeInfo?.optBoolean("udp", false) ?: false,
                    lastDelay = lastDelay,
                )
            }
            val iconUrl = iconMap[name].orEmpty()
            result += ProxyGroupUi(
                name = name,
                type = group.optString("type", "Group"),
                now = group.optString("now", "未选择"),
                nodes = nodes,
                iconUrl = iconUrl,
                iconPath = icons.cached(iconUrl)?.absolutePath.orEmpty(),
            )
        }
        return result.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    private fun parseConnections(root: JSONObject): List<ProxyConnectionUi> {
        val array = root.optJSONArray("connections") ?: JSONArray()
        val out = ArrayList<ProxyConnectionUi>()
        for (i in 0 until array.length()) {
            val c = array.optJSONObject(i) ?: continue
            val meta = c.optJSONObject("metadata") ?: JSONObject()
            val chains = c.optJSONArray("chains") ?: JSONArray()
            val chain = (0 until chains.length()).joinToString(" → ") { chains.optString(it) }
            out += ProxyConnectionUi(
                id = c.optString("id", i.toString()),
                host = meta.optString("host", meta.optString("destinationIP", "未知目标")),
                rule = c.optString("rule", ""),
                chain = chain,
                upload = c.optLong("upload", 0L),
                download = c.optLong("download", 0L),
            )
        }
        return out
    }

    private fun parseGroupIcons(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var inGroups = false
        var name: String? = null
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').lines()) {
            val trimmed = raw.trim()
            val indent = raw.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) raw.length else it }
            if (!inGroups) {
                if (indent == 0 && trimmed.startsWith("proxy-groups:")) inGroups = true
                continue
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (indent == 0) break
            if (trimmed.startsWith("- name:")) {
                name = yamlScalar(trimmed.substringAfter("- name:"))
            } else if (name != null && trimmed.startsWith("icon:")) {
                val value = yamlScalar(trimmed.substringAfter("icon:"))
                if (value.startsWith("https://")) out[name] = value
            }
        }
        return out
    }

    private fun yamlScalar(value: String): String {
        val s = value.trim()
        if (s.isEmpty()) return ""
        var quote = '\u0000'
        var cut = s.length
        for (i in s.indices) {
            val c = s[i]
            if ((c == '\'' || c == '"')) {
                if (quote == '\u0000') quote = c else if (quote == c) quote = '\u0000'
            } else if (c == '#' && quote == '\u0000') {
                cut = i; break
            }
        }
        var out = s.substring(0, cut).trim()
        if (out.length >= 2 && ((out.first() == '\'' && out.last() == '\'') || (out.first() == '"' && out.last() == '"'))) {
            out = out.substring(1, out.length - 1)
        }
        return out.replace("''", "'")
    }
}

private class ProxyIconStore(context: Context) {
    private val dir = File(context.cacheDir, "proxy/group-icons").apply { mkdirs() }

    fun cached(url: String): File? {
        if (!url.startsWith("https://")) return null
        val file = File(dir, key(url) + ".img")
        return file.takeIf { it.isFile && it.length() in 1..MAX_BYTES && BitmapFactory.decodeFile(it.absolutePath) != null }
    }

    fun fetchMissing(url: String): File? {
        if (!url.startsWith("https://")) return null
        val target = File(dir, key(url) + ".img")
        cached(url)?.let { return it }
        val tmp = File(dir, target.name + ".new")
        val connection = (URL(url).openConnection() as? HttpsURLConnection) ?: return null
        connection.connectTimeout = 5_000
        connection.readTimeout = 7_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Bichen/Android")
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) return cached(url)
            val declared = connection.contentLengthLong
            if (declared > MAX_BYTES) return cached(url)
            connection.inputStream.use { input ->
                FileOutputStream(tmp, false).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IOException("图标文件过大")
                        output.write(buffer, 0, n)
                    }
                    output.fd.sync()
                }
            }
            if (BitmapFactory.decodeFile(tmp.absolutePath) == null) throw IOException("图标格式无效")
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            return target
        } catch (cancel: CancellationException) {
            tmp.delete(); throw cancel
        } catch (_: Exception) {
            tmp.delete(); return cached(url)
        } finally {
            connection.disconnect()
        }
    }

    private fun key(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object { const val MAX_BYTES = 1_572_864L }
}
