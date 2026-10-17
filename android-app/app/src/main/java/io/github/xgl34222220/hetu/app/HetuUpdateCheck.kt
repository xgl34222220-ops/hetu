package io.github.xgl34222220.hetu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Audit4 G9: 关于 › 检查更新. One manual request to the GitHub releases API (never automatic), over
 * the same HttpsURLConnection stack, timeouts and User-Agent the core downloader uses. It only
 * reports a newer version and its release page; nothing is downloaded or installed.
 */
internal object HetuUpdateCheck {
    const val LATEST_URL = "https://api.github.com/repos/xgl34222220-ops/hetu/releases/latest"
    const val RELEASES_PAGE = "https://github.com/xgl34222220-ops/hetu/releases/latest"
    private const val MAX_BYTES = 1024 * 1024

    data class Release(val tag: String, val name: String, val url: String) {
        /** “2.0.1” for the tag “v2.0.1”. */
        val version: String get() = tag.trim().removePrefix("v").removePrefix("V")
    }

    /** Thrown for a repository that has no published release yet (HTTP 404). */
    class NoRelease : IOException("还没有发布版本")

    fun parse(json: String): Release {
        val root = JSONObject(json)
        val tag = root.optString("tag_name").trim()
        if (tag.isEmpty()) throw IOException("发布信息缺少版本号")
        // Only GitHub pages are opened; anything else falls back to the repository's latest-release page.
        val url = root.optString("html_url").trim().takeIf { it.startsWith("https://github.com/") } ?: RELEASES_PAGE
        return Release(tag, root.optString("name").trim().ifEmpty { tag }, url)
    }

    /** Numeric parts of “v2.0.1-beta” → [2, 0, 1]; null when it is not a dotted number. */
    fun versionParts(version: String): List<Int>? {
        val core = version.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
        if (core.isEmpty()) return null
        return core.split('.').map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
    }

    /** True only when [remote] is a strictly higher dotted version than [local]; unparseable = false. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = versionParts(remote) ?: return false
        val l = versionParts(local) ?: return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    suspend fun fetchLatest(): Release = withContext(Dispatchers.IO) {
        val connection = URL(LATEST_URL).openConnection() as? HttpsURLConnection ?: throw IOException("无效下载源")
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "Hetu/${BuildConfig.VERSION_NAME} Android")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val code = connection.responseCode
            if (code == 404) throw NoRelease()
            if (code != 200) throw IOException("检查更新失败（HTTP $code）")
            val body = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > MAX_BYTES) throw IOException("发布信息过大")
                    out.write(buffer, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }
            parse(body)
        } finally {
            connection.disconnect()
        }
    }
}
