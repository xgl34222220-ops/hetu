package io.github.xgl34222220.hetu

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Audit4 G12: a snapshot taken right before 「从文件恢复」 and the matching 撤销上次恢复.
 *
 * HetuSettingsBackup.restore itself is unchanged. Around it, [restoreWithSnapshot] records, in the
 * app's private files (one copy, replaced by the next restore):
 *  - every preference the restore changed (only keys named by the backup file, plus the per-core
 *    selected-config keys the restore re-points), with its value before and after;
 *  - every config file the restore added (a copy next to a same-named file, or a new name), with
 *    its SHA-256.
 * [undo] puts back a recorded preference only while it still holds the restored value (an edit made
 * after the restore is kept), and deletes an added config only while its content is unchanged and
 * it is not the selected config. The user's own config files are never touched.
 */
internal object HxRestoreUndo {
    private const val DIR = "hetu-restore-undo"
    private const val FILE = "undo.json"
    private const val MAX_BYTES = 16 * 1024 * 1024
    private const val SELECTED_PREFIX = "proxySelectedConfig."

    data class Info(val restoredAt: Long, val settings: Int, val configs: Int)

    private class Before(val prefs: Map<String, Any?>, val configs: Map<String, Set<String>>, val backupKeys: Set<String>)

    fun file(context: Context): File = File(File(context.applicationContext.filesDir, DIR), FILE)

    /** Snapshot → existing restore → record the difference. Returns the restore's own count. */
    suspend fun restoreWithSnapshot(context: Context, uri: Uri): Int {
        val app = context.applicationContext
        val before = withContext(Dispatchers.IO) { capture(app, uri) }
        try {
            return HetuSettingsBackup.restore(app, uri)
        } finally {
            // Also after a failed restore: whatever it already wrote can still be taken back.
            withContext(Dispatchers.IO) { runCatching { record(app, before) } }
        }
    }

    fun info(context: Context): Info? = runCatching {
        val f = file(context)
        if (!f.isFile) return null
        val root = JSONObject(f.readText())
        Info(root.optLong("restoredAt"), root.optJSONArray("settings")?.length() ?: 0, root.optJSONArray("configs")?.length() ?: 0)
            .takeIf { it.settings + it.configs > 0 }
    }.getOrNull()

    /** Takes the last restore back. Returns how many settings and configs were reverted. */
    suspend fun undo(context: Context): Int = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val f = file(app)
        val root = JSONObject(f.readText())
        val prefs = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
        val current = prefs.all
        val edit = prefs.edit()
        var reverted = 0
        val settings = root.optJSONArray("settings") ?: JSONArray()
        for (i in 0 until settings.length()) {
            val item = settings.optJSONObject(i) ?: continue
            val key = item.optString("k")
            if (key.isEmpty()) continue
            // A value changed again after the restore is the user's newer choice: keep it.
            if (encode(current[key])?.toString() != item.optJSONObject("after")?.toString()) continue
            val old = item.optJSONObject("before")
            if (old == null) edit.remove(key) else put(edit, key, old)
            reverted++
        }
        check(edit.commit()) { "无法保存设置" }
        val selected = ProxyRuntimeProfile.Core.values().associate { it.id to prefs.getString(SELECTED_PREFIX + it.id, null) }
        val library = ProxyConfigLibrary(app)
        val configs = root.optJSONArray("configs") ?: JSONArray()
        for (i in 0 until configs.length()) {
            val item = configs.optJSONObject(i) ?: continue
            val core = ProxyRuntimeProfile.Core.values().firstOrNull { it.id == item.optString("core") } ?: continue
            val name = item.optString("name")
            if (selected[core.id] == name) continue
            val entry = library.list(core).firstOrNull { it.name == name } ?: continue
            if (sha256(library.read(entry)) != item.optString("sha256")) continue
            library.delete(entry)
            reverted++
        }
        f.delete()
        reverted
    }

    private fun capture(app: Context, uri: Uri): Before {
        val prefs = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
        val library = ProxyConfigLibrary(app)
        val configs = ProxyRuntimeProfile.Core.values().associate { core -> core.id to library.list(core).map { it.name }.toSet() }
        // The keys the backup can write; anything else that changes meanwhile is not the restore's doing.
        val keys = runCatching {
            val raw = app.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                require(bytes.size <= MAX_BYTES)
                bytes.toString(Charsets.UTF_8)
            }.orEmpty()
            val settings = JSONObject(raw).optJSONObject("settings") ?: JSONObject()
            settings.keys().asSequence().toSet()
        }.getOrDefault(emptySet())
        return Before(HashMap(prefs.all), configs, keys)
    }

    private fun record(app: Context, before: Before) {
        val prefs = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
        val after = prefs.all
        val keys = (before.backupKeys + ProxyRuntimeProfile.Core.values().map { SELECTED_PREFIX + it.id }).sorted()
        val settings = JSONArray()
        keys.forEach { key ->
            val old = encode(before.prefs[key])
            val new = encode(after[key])
            if (old?.toString() == new?.toString()) return@forEach
            settings.put(JSONObject().put("k", key).put("before", old ?: JSONObject.NULL).put("after", new ?: JSONObject.NULL))
        }
        val library = ProxyConfigLibrary(app)
        val configs = JSONArray()
        ProxyRuntimeProfile.Core.values().forEach { core ->
            val old = before.configs[core.id].orEmpty()
            library.list(core).filter { it.name !in old }.forEach { entry ->
                configs.put(JSONObject().put("core", core.id).put("name", entry.name).put("sha256", sha256(library.read(entry))))
            }
        }
        val f = file(app)
        if (settings.length() + configs.length() == 0) return
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeText(JSONObject().put("restoredAt", System.currentTimeMillis()).put("settings", settings).put("configs", configs).toString())
        if (!tmp.renameTo(f)) { f.delete(); check(tmp.renameTo(f)) { "无法保存恢复前快照" } }
    }

    /** Same t/v shape as the backup file; null = key absent. */
    private fun encode(value: Any?): JSONObject? = when (value) {
        null -> null
        is String -> JSONObject().put("t", "s").put("v", value)
        is Boolean -> JSONObject().put("t", "b").put("v", value)
        is Int -> JSONObject().put("t", "i").put("v", value)
        is Long -> JSONObject().put("t", "l").put("v", value)
        is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
        is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray().also { array -> value.filterIsInstance<String>().sorted().forEach { array.put(it) } })
        else -> null
    }

    private fun put(edit: SharedPreferences.Editor, key: String, item: JSONObject) {
        when (item.optString("t")) {
            "s" -> edit.putString(key, item.optString("v", ""))
            "b" -> edit.putBoolean(key, item.optBoolean("v", false))
            "i" -> edit.putInt(key, item.optInt("v", 0))
            "l" -> edit.putLong(key, item.optLong("v", 0L))
            "f" -> edit.putFloat(key, item.optDouble("v", 0.0).toFloat())
            "ss" -> {
                val array = item.optJSONArray("v") ?: JSONArray()
                edit.putStringSet(key, (0 until array.length()).map { array.optString(it) }.toSet())
            }
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
