package io.github.xgl34222220.hetu

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Audit G1 「上次可启动」: after a successful manual start or restart, the effective source of that
 * start (the selected file's text with the enabled override scripts already applied) is kept in
 * app-private storage, one copy per core, overwritten each time. When a later start fails, the
 * failure sheet can start once more from that copy. The user's own config file is never written:
 * the copy only replaces the text the start reads.
 *
 * Text: `files/hetu-last-good/<core>.src`. What it was (core, mode, config name, time, size,
 * SHA-256) sits in the `hetu` preferences under [META_PREFIX]<core>; none of these keys is part of
 * the runtime settings signature, so saving never marks settings as pending.
 */
object LastGoodConfig {
    const val MAX_BYTES = 4 * 1024 * 1024
    const val META_PREFIX = "proxyLastGoodMeta."
    /** Set while the running session was started from the copy: the session's validation fingerprint. */
    const val ACTIVE_KEY = "proxyLastGoodActive"
    private const val FOLDER = "hetu-last-good"
    /** Shown instead of a file name when override scripts generated the config without one. */
    const val SCRIPTS_ONLY = "覆写脚本生成"

    data class Info(
        val core: String,
        val mode: String,
        val config: String,
        val savedAt: Long,
        val bytes: Int,
        val sha256: String,
    ) {
        val label: String get() = config.ifBlank { SCRIPTS_ONLY }
    }

    private fun safeCore(core: String): String = core.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "core" }

    @JvmStatic fun file(context: Context, core: String): File =
        File(File(context.filesDir, FOLDER), safeCore(core) + ".src")

    @JvmStatic fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /**
     * Keeps [source] as the core's last startable version. Never throws: a failed save leaves the
     * previous copy (or none) in place and the start it follows is unaffected.
     */
    @JvmStatic @JvmOverloads fun save(context: Context, core: String, mode: String, config: String, source: String?, now: Long = System.currentTimeMillis()): Boolean {
        if (source == null || source.isBlank()) return false
        return try {
            val bytes = source.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > MAX_BYTES) return false
            val target = file(context, core)
            val folder = target.parentFile ?: return false
            if (!folder.isDirectory && !folder.mkdirs()) return false
            val temp = File(folder, target.name + ".tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                temp.delete()
                return false
            }
            val meta = JSONObject()
                .put("core", core).put("mode", mode).put("config", config)
                .put("savedAt", now).put("bytes", bytes.size).put("sha256", sha256(source))
            context.getSharedPreferences("hetu", Context.MODE_PRIVATE).edit()
                .putString(META_PREFIX + core, meta.toString())
                .remove(ACTIVE_KEY)
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    /** What the core's copy is, from preferences only (no file access); null when none was saved. */
    @JvmStatic fun info(prefs: SharedPreferences, core: String): Info? {
        val raw = prefs.getString(META_PREFIX + core, null) ?: return null
        return try {
            val o = JSONObject(raw)
            Info(o.optString("core", core), o.optString("mode", ""), o.optString("config", ""),
                o.optLong("savedAt", 0L), o.optInt("bytes", 0), o.optString("sha256", ""))
                .takeIf { it.sha256.isNotEmpty() && it.bytes > 0 }
        } catch (_: Exception) {
            null
        }
    }

    /** The copy's text, or null when it is missing, too large or no longer matches its recorded SHA-256. */
    @JvmStatic fun read(context: Context, core: String): String? {
        val info = info(context.getSharedPreferences("hetu", Context.MODE_PRIVATE), core) ?: return null
        return try {
            val target = file(context, core)
            if (!target.isFile || target.length() > MAX_BYTES) return null
            val text = target.readText(StandardCharsets.UTF_8)
            text.takeIf { it.isNotBlank() && sha256(it) == info.sha256 }
        } catch (_: Exception) {
            null
        }
    }

    /** Records that the session with [fingerprint] (proxyRootValidatedFingerprint) runs from [core]'s copy. */
    @JvmStatic fun markActive(prefs: SharedPreferences, core: String, fingerprint: String?) {
        if (fingerprint.isNullOrEmpty()) return
        prefs.edit().putString(ACTIVE_KEY, JSONObject().put("core", core).put("fingerprint", fingerprint).toString()).apply()
    }

    /**
     * The copy the running session was started from, or null. Only true for that very session:
     * any later start or restart publishes a different validation fingerprint (a normal successful
     * start also clears the record through [save]).
     */
    @JvmStatic fun active(prefs: SharedPreferences, running: Boolean): Info? {
        if (!running) return null
        val raw = prefs.getString(ACTIVE_KEY, null) ?: return null
        return try {
            val o = JSONObject(raw)
            val fingerprint = o.optString("fingerprint", "")
            if (fingerprint.isEmpty() || fingerprint != prefs.getString("proxyRootValidatedFingerprint", "")) null
            else info(prefs, o.optString("core", ""))
        } catch (_: Exception) {
            null
        }
    }
}
