package io.github.xgl34222220.hetu

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal object HetuSettingsBackup {
    private const val SCHEMA = 1
    private const val MAX_BYTES = 16 * 1024 * 1024

    private val exactKeys = setOf(
        "proxyRootAutoStart",
        "enableBlur",
        "liquidGlass",
        "floatingBottomBar",
        "showPanelTab",
        "showPanelDock",
        "latencyAutoRefreshSeconds",
        "downloadMirrorEnabled",
        "downloadMirrorPrefix",
        "defaultPanelTab",
        "defaultPanelSection",
        "startOnPanel",
        "appearance",
        "enableMonet",
        "uiStyle",
        "colorStandard",
        "uiScale",
        "accentHex",
        "colorPalette",
        "pureBlackDark",
        "appLanguage",
        "predictiveBackAnimation",
        "predictiveBackFollowEdge",
        "topBarBlurStyle",
        "logAutoRefresh",
        "logCardView",
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
        "proxySelector",
        "proxyWebPanel",
        "proxyApiHistoryRetentionDays",
        "proxyApiHistoryMaxMb",
        "overview",
        "subscriptionHealth",
    )

    private val excluded = setOf(
        "proxyControllerSecret",
        "proxyCustomApiSecret",
        "proxyApiTrafficHistory",
        "proxyRootAppliedSettings",
    )

    private fun allowed(key: String): Boolean =
        key !in excluded && (key in exactKeys || prefixes.any(key::startsWith))

    // Types consumed by current production preferences. Dynamic/legacy prefix keys
    // also have to match any existing stored type; their unknown semantics are not
    // inferred from a backup. No coercion between SharedPreferences value types.
    private val knownTypes = buildMap<String, String> {
        listOf(
            "accentHex", "appLanguage", "appearance",
            "colorPalette", "colorStandard", "defaultPanelSection",
            "defaultPanelTab", "downloadMirrorPrefix", "overviewRankDimension",
            "overviewRankSort", "proxyAppScope", "proxyBaseCore",
            "proxyBaseIpv6", "proxyBaseMode", "proxyCustomApiHost",
            "proxyCustomDelayUrl", "proxySelectorDensity", "proxySelectorGroupDensity",
            "proxySelectorNameOverflow", "proxySelectorNodeSort", "proxyStatusNotificationAction1",
            "proxyStatusNotificationAction2", "proxyStatusNotificationAction3", "proxyStatusNotificationActionLabel1",
            "proxyStatusNotificationActionLabel2", "proxyStatusNotificationActionLabel3", "proxyStatusNotificationClickTarget",
            "proxyStatusNotificationError", "proxyStatusNotificationTemplate", "proxyStatusNotificationTitleTemplate",
            "proxyWebPanelLocalMode", "proxyWebPanelSelected", "proxyWebPanelsJson",
            "subscriptionHealthUrl", "topBarBlurStyle", "uiStyle",
        ).forEach { put(it, "s") }
        listOf(
            "downloadMirrorEnabled", "enableBlur", "enableMonet",
            "floatingBottomBar", "liquidGlass", "logAutoRefresh",
            "logCardView", "predictiveBackAnimation", "predictiveBackFollowEdge",
            "proxyApiHistoryEnabled", "proxyBaseAutoOverwrite", "proxyCnIpDirect",
            "proxyCustomApiEnabled", "proxyCustomDelayUrlEnabled", "proxyRootAutoStart",
            "proxySelectorCollapsePrevious", "proxySelectorDetectIpv6", "proxySelectorDisconnectOnSelect",
            "proxySelectorExpandSelectedInSheet", "proxySelectorGroupByProvider", "proxySelectorShowGlobalByMode",
            "proxySelectorShowHidden", "proxySelectorSortDescending", "proxySharedNetwork",
            "proxyStatusNotificationEnabled", "pureBlackDark", "showPanelDock",
            "showPanelTab", "startOnPanel",
        ).forEach { put(it, "b") }
        listOf(
            "latencyAutoRefreshSeconds", "proxyApiHistoryMaxMb", "proxyApiHistoryRetentionDays",
            "proxyCustomApiPort", "proxySelectorGroupColumns", "proxySelectorNodeColumns",
            "proxyStatusNotificationRefreshSeconds", "subscriptionHealthInterval", "subscriptionHealthTimeout",
            "subscriptionHealthTolerance",
        ).forEach { put(it, "i") }
        listOf(
            "uiScale",
        ).forEach { put(it, "f") }
        listOf(
            "proxyAppPackages", "proxyBypassCidrs", "proxyBypassInterfaces",
            "proxySharedBypassMacs",
        ).forEach { put(it, "ss") }
    }

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
            decodeUtf8(out.toByteArray())
        } ?: error("无法读取备份文件")

        // Validate the entire document before constructing the library (which can migrate
        // legacy storage), writing preferences, or importing a single configuration.
        val backup = validate(JSONObject(raw))
        val prefs = app.getSharedPreferences("hetu", 0)
        validatePreferenceState(backup, prefs.all)
        val library = ProxyConfigLibrary(app)
        library.restoreBatch(backup.configs, backup.selections) { selections ->
            validatePreferenceState(backup, prefs.all)
            val values = LinkedHashMap(backup.settings)
            selections.forEach { (core, name) -> values["proxySelectedConfig.${core.id}"] = name }
            commitSettings(prefs, values)
        }
        backup.configs.size
    }

    private data class ValidatedBackup(
        val settings: Map<String, Any>,
        val configs: List<ProxyConfigLibrary.RestoreItem>,
        val selections: Map<ProxyRuntimeProfile.Core, String>,
    )

    private fun validate(root: JSONObject): ValidatedBackup {
        require(root.get("schema") == SCHEMA) { "不支持的河图备份版本" }
        val settings = root.getJSONObject("settings")
        val values = linkedMapOf<String, Any>()
        val selections = linkedMapOf<ProxyRuntimeProfile.Core, String>()
        val keys = settings.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            // These keys are intentionally outside the backup's restore contract.
            if (!allowed(key)) continue
            val item = settings.getJSONObject(key)
            val value = item.get("v")
            val decoded: Any = when (item.getString("t")) {
                "s" -> { require(value is String) { "备份设置 $key 不是文本" }; value }
                "b" -> { require(value is Boolean) { "备份设置 $key 不是布尔值" }; value }
                "i" -> {
                    require(value is Int || value is Long) { "备份设置 $key 不是整数" }
                    val number = (value as Number).toLong()
                    require(number in Int.MIN_VALUE..Int.MAX_VALUE) { "备份设置 $key 超出整数范围" }
                    number.toInt()
                }
                "l" -> {
                    require(value is Int || value is Long) { "备份设置 $key 不是长整数" }
                    (value as Number).toLong()
                }
                "f" -> {
                    require(value is Number && value.toDouble().isFinite() && value.toFloat().isFinite()) {
                        "备份设置 $key 不是有效浮点数"
                    }
                    value.toFloat()
                }
                "ss" -> {
                    require(value is JSONArray) { "备份设置 $key 不是文本集合" }
                    linkedSetOf<String>().apply {
                        for (i in 0 until value.length()) {
                            val entry = value.get(i)
                            require(entry is String) { "备份设置 $key 含非文本值" }
                            add(entry)
                        }
                    }
                }
                else -> error("备份设置 $key 的类型不受支持")
            }
            if (key.startsWith("proxySelectedConfig.")) {
                val coreId = key.removePrefix("proxySelectedConfig.")
                val core = ProxyRuntimeProfile.Core.values().firstOrNull { it.id == coreId }
                    ?: error("备份选择了未知核心")
                require(decoded is String) { "备份中的已选配置名称无效" }
                if (decoded.isNotEmpty()) validateName(core, decoded)
                selections[core] = decoded
            } else {
                values[key] = decoded
            }
        }
        val array = root.getJSONArray("configs")
        val configs = ArrayList<ProxyConfigLibrary.RestoreItem>(array.length())
        val seen = hashSetOf<Pair<ProxyRuntimeProfile.Core, String>>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val coreId = item.get("core")
            val core = ProxyRuntimeProfile.Core.values().firstOrNull { it.id == coreId }
                ?: error("备份配置 ${i + 1} 的核心不受支持")
            val name = item.get("name")
            require(name is String) { "备份配置 ${i + 1} 的名称无效" }
            validateName(core, name)
            require(seen.add(core to name)) { "备份包含重复配置：$name" }
            val data = item.get("data")
            require(data is String) { "备份配置 $name 的内容无效" }
            val bytes = Base64.decode(data, Base64.DEFAULT)
            require(bytes.isNotEmpty() && bytes.size <= 4 * 1024 * 1024) { "备份配置 $name 的大小无效" }
            require(decodeUtf8(bytes).isNotBlank()) { "备份配置 $name 不能为空" }
            configs += ProxyConfigLibrary.RestoreItem(core, name, bytes)
        }
        selections.forEach { (core, name) ->
            require(name.isEmpty() || core to name in seen) { "备份缺少已选配置：$name" }
        }
        return ValidatedBackup(values, configs, selections)
    }

    private fun valueType(value: Any?): String? = when (value) {
        is String -> "s"
        is Boolean -> "b"
        is Int -> "i"
        is Long -> "l"
        is Float -> "f"
        is Set<*> -> "ss"
        else -> null
    }

    private fun validatePreferenceState(backup: ValidatedBackup, current: Map<String, *>) {
        val values = LinkedHashMap(backup.settings)
        backup.selections.forEach { (core, name) -> values["proxySelectedConfig.${core.id}"] = name }
        values.forEach { (key, value) ->
            val expected = if (key.startsWith("proxySelectedConfig.")) "s" else knownTypes[key]
            val actual = valueType(value)
            require(expected == null || actual == expected) { "备份设置 $key 的类型与当前应用不兼容" }
            require(!current.containsKey(key) || valueType(current[key]) == actual) {
                "备份设置 $key 的类型与已有设置不兼容"
            }
        }
        // Custom API secrets are deliberately excluded from backups. Do not bind a
        // retained credential to another destination, including while API is disabled.
        val endpointKeys = setOf("proxyCustomApiHost", "proxyCustomApiPort", "proxyCustomApiEnabled")
        if (values.keys.none { it in endpointKeys }) return
        val secret = current["proxyCustomApiSecret"]
        require(secret == null || secret is String) { "请先检查 API 连接设置后再恢复备份" }
        if (secret !is String || secret.isBlank()) return
        fun endpoint(state: Map<String, *>): Pair<String, Int> {
            val host = state["proxyCustomApiHost"] ?: "127.0.0.1"
            val port = state["proxyCustomApiPort"] ?: 9090
            require(host is String && port is Int) { "请先检查 API 连接设置后再恢复备份" }
            val trimmed = host.trim()
            require(trimmed.isNotEmpty() && trimmed.length <= 253 && trimmed.matches(Regex("[A-Za-z0-9.-]+"))) {
                "请先检查 API 连接设置后再恢复备份"
            }
            return trimmed.lowercase(java.util.Locale.ROOT) to port.takeIf { it in 1024..65535 }.let { it ?: 9090 }
        }
        require(endpoint(current) == endpoint(current + values)) {
            "备份中的 API 地址或端口与现有 Secret 不匹配，请先检查 API 连接设置后再恢复备份"
        }
    }

    private fun validateName(core: ProxyRuntimeProfile.Core, name: String) {
        require(ProxyConfigLibrary.safeName(name) == name && ProxyConfigLibrary.coreAccepts(core, name)) {
            "备份配置名称或格式无效：$name"
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private fun SharedPreferences.Editor.putValue(key: String, value: Any?) {
        when (value) {
            null -> remove(key)
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> error("不支持的设置值")
        }
    }

    private fun commitSettings(prefs: SharedPreferences, values: Map<String, Any>) {
        if (values.isEmpty()) return
        val before = prefs.all
        val editor = prefs.edit()
        values.forEach { (key, value) -> editor.putValue(key, value) }
        // commit reports disk failures; apply would report success before persistence.
        val failure = try {
            if (editor.commit()) return
            IOException("无法保存备份设置")
        } catch (error: RuntimeException) {
            IOException("无法保存备份设置", error)
        }
        val recovered = try {
            val current = prefs.all
            val rollback = prefs.edit()
            values.forEach { (key, value) ->
                // SharedPreferences has no compare-and-set API. Preserve any newer
                // value already visible here; ordinary config selection uses WRITE_LOCK.
                if (current[key] == value) rollback.putValue(key, before[key])
            }
            rollback.commit()
        } catch (error: RuntimeException) {
            failure.addSuppressed(error)
            false
        }
        if (!recovered) throw ProxyConfigLibrary.RestoreCommitUncertain(
            "无法保存备份设置，设置回退未能确认；原有配置与恢复副本均已保留", failure,
        )
        throw IOException("无法保存备份设置，已回退未变化的设置；原有配置文件已保留", failure)
    }
}
