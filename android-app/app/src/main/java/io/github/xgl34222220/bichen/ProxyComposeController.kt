package io.github.xgl34222220.bichen

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal data class ProxyGroupUi(val name: String, val type: String, val now: String, val nodes: List<String>)
internal data class ProxyConnectionUi(val id: String, val host: String, val rule: String, val chain: String, val upload: Long, val download: Long)
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
        val profile = ProxyRuntimeProfile.load(prefs)
        val selected = configs.selected(profile.core) ?: error("尚未选择配置")
        if (!configs.hasConfiguredSubscription(selected)) error("内置配置还没有可用订阅，请先到「设置 → 订阅管理」添加或编辑订阅")
        root.start(profile) { onProgress(it) }
    }

    suspend fun stop(onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) { root.stop { onProgress(it) } }

    suspend fun select(group: String, node: String) = withContext(Dispatchers.IO) {
        api.select(stripVisualPrefix(group), stripVisualPrefix(node))
    }
    suspend fun delay(node: String): Long = withContext(Dispatchers.IO) { api.delay(stripVisualPrefix(node)) }
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

    private fun parseGroups(root: JSONObject): List<ProxyGroupUi> {
        val result = ArrayList<ProxyGroupUi>()
        val it = root.keys()
        while (it.hasNext()) {
            val rawName = it.next()
            if (rawName == "GLOBAL") continue
            val g = root.optJSONObject(rawName) ?: continue
            val all = g.optJSONArray("all") ?: continue
            val groupType = g.optString("type", "Group")
            val nodes = ArrayList<String>()
            for (i in 0 until all.length()) {
                val rawNode = all.optString(i)
                if (rawNode.isBlank()) continue
                val nodeType = root.optJSONObject(rawNode)?.optString("type", "") ?: ""
                nodes += decorateNode(rawNode, nodeType)
            }
            val rawNow = g.optString("now", "未选择")
            val nowType = root.optJSONObject(rawNow)?.optString("type", "") ?: ""
            result += ProxyGroupUi(
                decorateGroup(rawName, groupType),
                groupType,
                decorateNode(rawNow, nowType),
                nodes,
            )
        }
        return result.sortedBy { stripVisualPrefix(it.name).lowercase(Locale.ROOT) }
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

    private fun decorateGroup(name: String, type: String): String {
        if (name.isBlank() || hasVisualPrefix(name)) return name
        val icon = when (type.lowercase(Locale.ROOT)) {
            "selector" -> "🎯"
            "urltest", "url-test" -> "⚡"
            "fallback" -> "🛟"
            "loadbalance", "load-balance" -> "⚖️"
            "relay" -> "🔗"
            else -> "🧭"
        }
        return "$icon$VISUAL_SEPARATOR$name"
    }

    private fun decorateNode(name: String, type: String): String {
        if (name.isBlank() || hasVisualPrefix(name) || KNOWN_FLAGS.any(name::contains)) return name
        val icon = countryFlag(name) ?: when (type.lowercase(Locale.ROOT)) {
            "direct" -> "🌐"
            "reject", "rejectdrop" -> "⛔"
            "wireguard" -> "🔗"
            "tuic", "hysteria", "hysteria2" -> "⚡"
            "trojan", "vmess", "vless", "ss", "shadowsocks", "ssr" -> "🔒"
            "socks5", "http" -> "🌍"
            "selector" -> "🎯"
            "urltest", "url-test" -> "⚡"
            else -> "☁️"
        }
        return "$icon$VISUAL_SEPARATOR$name"
    }

    private fun countryFlag(name: String): String? {
        val n = name.lowercase(Locale.ROOT)
        return when {
            containsAny(n, "香港", "hong kong", "hongkong", " hk ", "hk-", "hk_") -> "🇭🇰"
            containsAny(n, "台湾", "臺灣", "taiwan", " tw ", "tw-", "tw_") -> "🇹🇼"
            containsAny(n, "日本", "东京", "東京", "大阪", "japan", "tokyo", "osaka", " jp ", "jp-", "jp_") -> "🇯🇵"
            containsAny(n, "新加坡", "狮城", "獅城", "singapore", " sg ", "sg-", "sg_") -> "🇸🇬"
            containsAny(n, "美国", "美國", "洛杉矶", "洛杉磯", "圣何塞", "聖何塞", "西雅图", "西雅圖", "usa", "united states", "los angeles", "san jose", "seattle", " us ", "us-", "us_") -> "🇺🇸"
            containsAny(n, "韩国", "韓國", "首尔", "首爾", "korea", "seoul", " kr ", "kr-", "kr_") -> "🇰🇷"
            containsAny(n, "英国", "英國", "伦敦", "倫敦", "united kingdom", "london", " uk ", "uk-", "uk_") -> "🇬🇧"
            containsAny(n, "德国", "德國", "法兰克福", "法蘭克福", "germany", "frankfurt", " de ", "de-", "de_") -> "🇩🇪"
            containsAny(n, "法国", "法國", "巴黎", "france", "paris", " fr ", "fr-", "fr_") -> "🇫🇷"
            containsAny(n, "加拿大", "多伦多", "多倫多", "canada", "toronto", " ca ", "ca-", "ca_") -> "🇨🇦"
            containsAny(n, "澳大利亚", "澳大利亞", "澳洲", "悉尼", "australia", "sydney", " au ", "au-", "au_") -> "🇦🇺"
            containsAny(n, "俄罗斯", "俄羅斯", "莫斯科", "russia", "moscow", " ru ", "ru-", "ru_") -> "🇷🇺"
            containsAny(n, "印度", "孟买", "孟買", "india", "mumbai", " in ", "in-", "in_") -> "🇮🇳"
            containsAny(n, "荷兰", "荷蘭", "阿姆斯特丹", "netherlands", "amsterdam", " nl ", "nl-", "nl_") -> "🇳🇱"
            containsAny(n, "土耳其", "伊斯坦布尔", "伊斯坦布爾", "turkey", "istanbul", " tr ", "tr-", "tr_") -> "🇹🇷"
            else -> null
        }
    }

    private fun containsAny(value: String, vararg keys: String): Boolean = keys.any(value::contains)

    private fun hasVisualPrefix(value: String): Boolean {
        val cut = value.indexOf(VISUAL_SEPARATOR)
        return cut in 1..6 && VISUAL_ICONS.contains(value.substring(0, cut))
    }

    private fun stripVisualPrefix(value: String): String {
        val cut = value.indexOf(VISUAL_SEPARATOR)
        return if (cut in 1..6 && VISUAL_ICONS.contains(value.substring(0, cut))) value.substring(cut + VISUAL_SEPARATOR.length) else value
    }

    private companion object {
        private const val VISUAL_SEPARATOR = "\u2009"
        private val KNOWN_FLAGS = setOf("🇭🇰", "🇹🇼", "🇯🇵", "🇸🇬", "🇺🇸", "🇰🇷", "🇬🇧", "🇩🇪", "🇫🇷", "🇨🇦", "🇦🇺", "🇷🇺", "🇮🇳", "🇳🇱", "🇹🇷")
        private val VISUAL_ICONS = KNOWN_FLAGS + setOf("🎯", "⚡", "🛟", "⚖️", "🔗", "🧭", "🌐", "⛔", "🔒", "🌍", "☁️")
    }
}
