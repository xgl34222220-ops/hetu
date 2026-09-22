package io.github.xgl34222220.hetu

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

internal object HetuSettingsBackup {
    private const val SCHEMA = 1
    private const val MAX_BYTES = 16 * 1024 * 1024

    private val exactKeys = setOf(
        "proxyRootAutoStart",
        "enableBlur",
        "liquidGlass",
        "floatingBottomBar",
        "showPanelTab",
        "latencyAutoRefreshSeconds",
        "downloadMirrorEnabled",
        "downloadMirrorPrefix",
        "defaultPanelTab",
        "startOnPanel",
        "appearance",
        "enableMonet",
        "uiStyle",
        "colorStandard",
        "uiScale",
        "accentHex",
        "colorPalette",
        "pureBlackDark",
    )

    private val prefixes = listOf(
        "proxyBase",
        "proxySelectedConfig.",
        "proxyApp",
        "proxyBypass",
        "proxyShared",
        "proxyCnIp",
        "proxyCustomApiEnabled",
        "proxyCustomApiHost",
        "proxyCustomApiPort",
        "proxyCustomDelay",
        "proxyApiHistoryEnabled",
        "latency",
        "notification",
        "proxyStatus",
    )

    private val excluded = setOf(
        "proxyControllerSecret",
        "proxyCustomApiSecret",
        "proxyApiTrafficHistory",
        "proxyRootAppliedSettings",
    )

    private fun allowed(key: String): Boolean =
        key !in excluded && (key in exactKeys || prefixes.any(key::startsWith))

    suspend fun export(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("hetu", 0)
        val root = JSONObject()
            .put("schema", SCHEMA)
            .put("app", "河图")
            .put("version", BuildConfig.VERSION_NAME)
            .put("createdAt", System.currentTimeMillis())

        val settings = JSONObject()
        prefs.all.toSortedMap().forEach { (key, value) ->
            if (!allowed(key)) return@forEach
            when (value) {
                is String -> settings.put(key, JSONObject().put("t", "s").put("v", value))
                is Boolean -> settings.put(key, JSONObject().put("t", "b").put("v", value))
                is Int -> settings.put(key, JSONObject().put("t", "i").put("v", value))
                is Long -> settings.put(key, JSONObject().put("t", "l").put("v", value))
                is Float -> settings.put(key, JSONObject().put("t", "f").put("v", value.toDouble()))
                is Set<*> -> {
                    val array = JSONArray()
                    value.filterIsInstance<String>().sorted().forEach(array::put)
                    settings.put(key, JSONObject().put("t", "ss").put("v", array))
                }
            }
        }
        root.put("settings", settings)

        val library = ProxyConfigLibrary(app)
        val configs = JSONArray()
        ProxyRuntimeProfile.Core.values().forEach { core ->
            library.list(core).forEach { entry ->
                val bytes = library.read(entry).toByteArray(Charsets.UTF_8)
                configs.put(
                    JSONObject()
                        .put("core", core.id)
                        .put("name", entry.name)
                        .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)),
                )
            }
        }
        root.put("configs", configs)

        val data = root.toString(2).toByteArray(Charsets.UTF_8)
        require(data.size <= MAX_BYTES) { "备份超过 16 MiB，请先清理配置库" }
        app.contentResolver.openOutputStream(uri, "w")?.use { it.write(data) }
            ?: error("无法创建备份文件")
        configs.length()
    }

    suspend fun restore(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val raw = app.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                require(total <= MAX_BYTES) { "备份文件超过 16 MiB" }
                out.write(buffer, 0, n)
            }
            out.toString(Charsets.UTF_8.name())
        } ?: error("无法读取备份文件")

        val root = JSONObject(raw)
        require(root.optInt("schema", -1) == SCHEMA) { "不支持的河图备份版本" }
        val prefs = app.getSharedPreferences("hetu", 0)
        val settings = root.optJSONObject("settings") ?: JSONObject()
        val editor = prefs.edit()
        val keys = settings.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!allowed(key)) continue
            val item = settings.optJSONObject(key) ?: continue
            when (item.optString("t")) {
                "s" -> editor.putString(key, item.optString("v", ""))
                "b" -> editor.putBoolean(key, item.optBoolean("v", false))
                "i" -> editor.putInt(key, item.optInt("v", 0))
                "l" -> editor.putLong(key, item.optLong("v", 0L))
                "f" -> editor.putFloat(key, item.optDouble("v", 0.0).toFloat())
                "ss" -> {
                    val array = item.optJSONArray("v") ?: JSONArray()
                    val values = LinkedHashSet<String>()
                    for (i in 0 until array.length()) {
                        array.optString(i).takeIf(String::isNotBlank)?.let(values::add)
                    }
                    editor.putStringSet(key, values)
                }
            }
        }
        editor.apply()

        val library = ProxyConfigLibrary(app)
        val configs = root.optJSONArray("configs") ?: JSONArray()
        var restored = 0
        for (i in 0 until configs.length()) {
            val item = configs.optJSONObject(i) ?: continue
            val coreId = item.optString("core")
            val core = ProxyRuntimeProfile.Core.values().firstOrNull { it.id == coreId } ?: continue
            val name = item.optString("name")
            if (!runCatching { ProxyConfigLibrary.safeName(name) }.isSuccess) continue
            val bytes = runCatching { Base64.decode(item.optString("data"), Base64.DEFAULT) }.getOrNull() ?: continue
            if (bytes.isEmpty() || bytes.size > 4 * 1024 * 1024) continue
            val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull() ?: continue
            val existing = library.list(core).firstOrNull { it.name == name }
            if (existing != null) {
                library.write(existing, text)
            } else {
                library.importConfig(core, name, ByteArrayInputStream(bytes))
            }
            restored++
        }
        restored
    }
}
