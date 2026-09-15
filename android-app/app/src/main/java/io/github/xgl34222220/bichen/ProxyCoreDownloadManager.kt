package io.github.xgl34222220.bichen

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import javax.net.ssl.HttpsURLConnection

internal data class ProxyCoreRemoteStatus(
    val id: String,
    val label: String,
    val bundled: Boolean,
    val downloaded: Boolean,
    val installedVersion: String,
    val latestVersion: String,
    val updateAvailable: Boolean,
    val canDownload: Boolean,
    val runtimeReady: Boolean,
    val source: String,
    val message: String = "",
)

internal class ProxyCoreDownloadManager(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val store = ProxyCoreStore(app)

    private data class Source(
        val core: ProxyRuntimeProfile.Core,
        val repo: String,
        val endpoint: String,
        val archive: Archive,
        val executableName: String,
    )

    private enum class Archive { GZIP, ZIP, TAR_GZIP, RAW }

    private data class Asset(
        val version: String,
        val name: String,
        val url: String,
        val digest: String,
        val size: Long,
        val source: Source,
    )

    private val sources = listOf(
        Source(
            ProxyRuntimeProfile.Core.MIHOMO,
            "MetaCubeX/mihomo",
            "https://api.github.com/repos/MetaCubeX/mihomo/releases/latest",
            Archive.GZIP,
            "mihomo",
        ),
        Source(
            ProxyRuntimeProfile.Core.MIHOMO_SMART,
            "lux5am/mihomo-smart",
            "https://api.github.com/repos/lux5am/mihomo-smart/releases",
            Archive.GZIP,
            "mihomo",
        ),
        Source(
            ProxyRuntimeProfile.Core.SING_BOX,
            "SagerNet/sing-box",
            "https://api.github.com/repos/SagerNet/sing-box/releases/latest",
            Archive.TAR_GZIP,
            "sing-box",
        ),
        Source(
            ProxyRuntimeProfile.Core.XRAY,
            "XTLS/Xray-core",
            "https://api.github.com/repos/XTLS/Xray-core/releases/latest",
            Archive.ZIP,
            "xray",
        ),
        Source(
            ProxyRuntimeProfile.Core.V2FLY,
            "v2fly/v2ray-core",
            "https://api.github.com/repos/v2fly/v2ray-core/releases/latest",
            Archive.ZIP,
            "v2ray",
        ),
        Source(
            ProxyRuntimeProfile.Core.HYSTERIA,
            "HyNetworks/hysteria",
            "https://api.github.com/repos/HyNetworks/hysteria/releases/latest",
            Archive.RAW,
            "hysteria",
        ),
    )

    suspend fun statuses(forceNetwork: Boolean = true): List<ProxyCoreRemoteStatus> = withContext(Dispatchers.IO) {
        val result = coroutineScope {
            ProxyRuntimeProfile.Core.values().map { core ->
                async {
                    try {
                        status(core, forceNetwork)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        localStatus(core, error.message ?: "检查更新失败")
                    }
                }
            }.awaitAll()
        }
        result
    }

    suspend fun status(core: ProxyRuntimeProfile.Core, network: Boolean = true): ProxyCoreRemoteStatus = withContext(Dispatchers.IO) {
        val local = localStatus(core)
        val source = sources.firstOrNull { it.core == core }
            ?: return@withContext local.copy(message = "暂未配置可信下载源")
        if (!network) return@withContext local.copy(source = source.repo)
        val asset = resolve(source)
        if (asset == null) {
            return@withContext local.copy(
                source = source.repo,
                canDownload = false,
                message = "当前设备架构 ${abiLabel()} 没找到可用 Android 构建",
            )
        }
        val installedVersion = installedVersion(core)
        val downloaded = store.installed(core)
        val update = downloaded && installedVersion.isNotBlank() && installedVersion != asset.version
        local.copy(
            latestVersion = asset.version,
            updateAvailable = update,
            canDownload = true,
            source = source.repo,
            message = if (core == ProxyRuntimeProfile.Core.MIHOMO && !downloaded) "当前使用内置 Mihomo，可在线更新" else local.message,
        )
    }

    suspend fun downloadOrUpdate(
        core: ProxyRuntimeProfile.Core,
        onProgress: (String) -> Unit = {},
    ): ProxyCoreRemoteStatus = withContext(Dispatchers.IO) {
        val source = sources.firstOrNull { it.core == core }
            ?: throw IOException("${core.label} 暂未配置下载源")
        onProgress("检查 ${core.label} 最新版本…")
        val asset = resolve(source) ?: throw IOException("没有适配 ${abiLabel()} 的 Android 核心")
        onProgress("下载 ${core.label} ${asset.version}…")
        val archive = download(asset) { done, total ->
            if (total > 0L) {
                val percent = ((done * 100L) / total).coerceIn(0L, 100L)
                onProgress("下载 ${core.label} · $percent%")
            } else {
                onProgress("下载 ${core.label} · ${human(done)}")
            }
        }
        val extracted = File(app.cacheDir, "proxy-core-${core.id}-${System.nanoTime()}.bin")
        try {
            onProgress("校验并解压 ${core.label}…")
            extract(asset, archive, extracted)
            verifyElf(extracted)
            FileInputStream(extracted).use { store.importCore(core, it) }
            prefs.edit()
                .putString("version_${core.id}", asset.version)
                .putString("source_${core.id}", asset.source.repo)
                .putString("asset_${core.id}", asset.name)
                .putLong("updated_${core.id}", System.currentTimeMillis())
                .apply()
            onProgress("${core.label} ${asset.version} 已安装")
        } finally {
            archive.delete()
            extracted.delete()
        }
        status(core, false).copy(
            latestVersion = asset.version,
            updateAvailable = false,
            canDownload = true,
            source = asset.source.repo,
            message = if (runtimeReady(core)) "已就绪" else "核心已下载；当前运行后端尚未接入",
        )
    }

    suspend fun removeDownloaded(core: ProxyRuntimeProfile.Core): ProxyCoreRemoteStatus = withContext(Dispatchers.IO) {
        if (store.installed(core)) store.remove(core)
        prefs.edit()
            .remove("version_${core.id}")
            .remove("source_${core.id}")
            .remove("asset_${core.id}")
            .remove("updated_${core.id}")
            .apply()
        localStatus(core, if (core == ProxyRuntimeProfile.Core.MIHOMO) "已恢复使用内置 Mihomo" else "已删除下载核心")
    }

    private fun localStatus(core: ProxyRuntimeProfile.Core, message: String = ""): ProxyCoreRemoteStatus {
        val bundled = core == ProxyRuntimeProfile.Core.MIHOMO
        val downloaded = store.installed(core)
        val installed = when {
            downloaded -> installedVersion(core).ifBlank { "已下载" }
            bundled -> "内置 ${BuildConfig.MIHOMO_REVISION.take(8)}"
            else -> "未安装"
        }
        val source = prefs.getString("source_${core.id}", "") ?: ""
        return ProxyCoreRemoteStatus(
            id = core.id,
            label = core.label,
            bundled = bundled,
            downloaded = downloaded,
            installedVersion = installed,
            latestVersion = "",
            updateAvailable = false,
            canDownload = sources.any { it.core == core },
            runtimeReady = runtimeReady(core),
            source = source,
            message = message,
        )
    }

    private fun installedVersion(core: ProxyRuntimeProfile.Core): String =
        prefs.getString("version_${core.id}", "") ?: ""

    private fun runtimeReady(core: ProxyRuntimeProfile.Core): Boolean =
        core == ProxyRuntimeProfile.Core.MIHOMO || core == ProxyRuntimeProfile.Core.MIHOMO_SMART

    private fun resolve(source: Source): Asset? {
        val root = readJson(source.endpoint)
        val release = when (root) {
            is JSONObject -> root
            is JSONArray -> (0 until root.length())
                .mapNotNull { root.optJSONObject(it) }
                .firstOrNull { !it.optBoolean("draft", false) }
            else -> null
        } ?: return null
        val tag = release.optString("tag_name", release.optString("name", "latest"))
        val assets = release.optJSONArray("assets") ?: return null
        val candidates = ArrayList<JSONObject>()
        for (i in 0 until assets.length()) {
            val item = assets.optJSONObject(i) ?: continue
            if (matchesAsset(source.core, item.optString("name", ""))) candidates += item
        }
        val chosen = candidates.minByOrNull { assetRank(source.core, it.optString("name", "")) } ?: return null
        val url = chosen.optString("browser_download_url", "")
        if (!url.startsWith("https://")) return null
        return Asset(
            version = tag,
            name = chosen.optString("name", "core"),
            url = url,
            digest = chosen.optString("digest", ""),
            size = chosen.optLong("size", 0L),
            source = source,
        )
    }

    private fun matchesAsset(core: ProxyRuntimeProfile.Core, rawName: String): Boolean {
        val name = rawName.lowercase(Locale.ROOT)
        val abi = primaryAbi()
        return when (core) {
            ProxyRuntimeProfile.Core.MIHOMO,
            ProxyRuntimeProfile.Core.MIHOMO_SMART -> {
                name.startsWith("mihomo-android-") && name.endsWith(".gz") && when (abi) {
                    "arm64" -> name.contains("android-arm64")
                    "armv7" -> name.contains("android-armv7")
                    "amd64" -> name.contains("android-amd64")
                    "386" -> name.contains("android-386")
                    else -> false
                }
            }
            ProxyRuntimeProfile.Core.SING_BOX -> {
                name.contains("android-$abi") && name.endsWith(".tar.gz") && !name.contains("sfa")
            }
            ProxyRuntimeProfile.Core.XRAY -> {
                name.endsWith(".zip") && !name.endsWith(".zip.dgst") && when (abi) {
                    "arm64" -> name == "xray-android-arm64-v8a.zip"
                    "armv7" -> name.contains("xray-android-arm32-v7a.zip") || name.contains("xray-android-arm32-v7.zip")
                    "amd64" -> name == "xray-android-amd64.zip"
                    "386" -> name == "xray-android-386.zip"
                    else -> false
                }
            }
            ProxyRuntimeProfile.Core.V2FLY -> {
                name.endsWith(".zip") && !name.endsWith(".zip.dgst") && when (abi) {
                    "arm64" -> name == "v2ray-android-arm64-v8a.zip"
                    "armv7" -> name.contains("v2ray-android-arm32-v7a.zip") || name.contains("v2ray-android-arm32-v7.zip")
                    "amd64" -> name == "v2ray-android-amd64.zip"
                    "386" -> name == "v2ray-android-386.zip"
                    else -> false
                }
            }
            ProxyRuntimeProfile.Core.HYSTERIA -> name == "hysteria-android-$abi"
            ProxyRuntimeProfile.Core.SING_BOX_REF1ND -> false
        }
    }

    private fun assetRank(core: ProxyRuntimeProfile.Core, rawName: String): Int {
        val name = rawName.lowercase(Locale.ROOT)
        if (core == ProxyRuntimeProfile.Core.MIHOMO || core == ProxyRuntimeProfile.Core.MIHOMO_SMART) {
            return when {
                name.contains("compatible") -> 0
                name.contains("-v1-") -> 1
                name.contains("-v8-") -> 2
                else -> 3
            }
        }
        return 0
    }

    private fun primaryAbi(): String {
        for (raw in Build.SUPPORTED_ABIS) {
            when (raw.lowercase(Locale.ROOT)) {
                "arm64-v8a" -> return "arm64"
                "armeabi-v7a" -> return "armv7"
                "x86_64" -> return "amd64"
                "x86" -> return "386"
            }
        }
        return "unknown"
    }

    private fun abiLabel(): String = Build.SUPPORTED_ABIS.joinToString("/")

    private fun readJson(url: String): Any {
        val connection = open(url)
        try {
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.connect()
            if (connection.responseCode !in 200..299) throw IOException("GitHub 返回 ${connection.responseCode}")
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val trimmed = text.trim()
            return if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
        } finally {
            connection.disconnect()
        }
    }

    private fun download(asset: Asset, progress: (Long, Long) -> Unit): File {
        if (asset.size > MAX_DOWNLOAD) throw IOException("核心文件过大")
        val target = File(app.cacheDir, "proxy-core-${asset.source.core.id}-${System.nanoTime()}.pkg")
        val connection = open(asset.url)
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 45_000
            connection.connect()
            if (connection.responseCode !in 200..299) throw IOException("下载失败：HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it > 0L } ?: asset.size
            if (total > MAX_DOWNLOAD) throw IOException("核心文件超过 128 MiB")
            var done = 0L
            connection.inputStream.use { input ->
                FileOutputStream(target, false).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw IOException("下载已取消")
                        val n = input.read(buffer)
                        if (n < 0) break
                        done += n
                        if (done > MAX_DOWNLOAD) throw IOException("核心文件超过 128 MiB")
                        output.write(buffer, 0, n)
                        progress(done, total)
                    }
                    output.fd.sync()
                }
            }
            if (done < 1024L) throw IOException("下载文件过小")
            verifyDigest(target, asset.digest)
            return target
        } catch (error: Exception) {
            target.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun extract(asset: Asset, archive: File, out: File) {
        when (asset.source.archive) {
            Archive.RAW -> FileInputStream(archive).use { input -> writeLimited(input, out) }
            Archive.GZIP -> FileInputStream(archive).use { raw -> GZIPInputStream(BufferedInputStream(raw)).use { writeLimited(it, out) } }
            Archive.ZIP -> extractZip(archive, out, asset.source.executableName)
            Archive.TAR_GZIP -> extractTarGzip(archive, out, asset.source.executableName)
        }
    }

    private fun extractZip(archive: File, out: File, executable: String) {
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            var fallback: ByteArray? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val base = entry.name.substringAfterLast('/').lowercase(Locale.ROOT)
                if (base == executable.lowercase(Locale.ROOT) || base == "$executable.exe".lowercase(Locale.ROOT)) {
                    writeLimited(zip, out)
                    return
                }
                if (fallback == null && !base.endsWith(".dat") && !base.endsWith(".json") && !base.endsWith(".txt")) {
                    val bytes = readLimited(zip, 4L * 1024L * 1024L)
                    if (bytes.size >= 4 && bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte()) fallback = bytes
                }
            }
            fallback?.let { FileOutputStream(out, false).use { stream -> stream.write(it); stream.fd.sync() }; return }
        }
        throw IOException("压缩包中没有找到 $executable")
    }

    private fun extractTarGzip(archive: File, out: File, executable: String) {
        GZIPInputStream(BufferedInputStream(FileInputStream(archive))).use { tar ->
            val header = ByteArray(512)
            while (true) {
                val got = readFullyOrEof(tar, header)
                if (got == 0) break
                if (got != 512) throw IOException("tar 头损坏")
                if (header.all { it == 0.toByte() }) break
                val name = header.copyOfRange(0, 100).toString(Charsets.UTF_8).trim('\u0000')
                val sizeText = header.copyOfRange(124, 136).toString(Charsets.US_ASCII).trim('\u0000', ' ')
                val size = sizeText.toLongOrNull(8) ?: 0L
                if (size < 0L || size > MAX_EXTRACTED) throw IOException("tar 条目大小异常")
                val base = name.substringAfterLast('/').lowercase(Locale.ROOT)
                if (base == executable.lowercase(Locale.ROOT)) {
                    copyExact(tar, out, size)
                    return
                }
                skipExact(tar, size)
                val padding = (512L - (size % 512L)) % 512L
                skipExact(tar, padding)
            }
        }
        throw IOException("压缩包中没有找到 $executable")
    }

    private fun verifyDigest(file: File, digest: String) {
        val expected = digest.removePrefix("sha256:").trim().lowercase(Locale.ROOT)
        if (expected.length != 64) return
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) throw IOException("SHA-256 校验失败")
    }

    private fun verifyElf(file: File) {
        if (!file.isFile || file.length() < 1024L) throw IOException("核心文件无效")
        FileInputStream(file).use { input ->
            val magic = ByteArray(4)
            if (input.read(magic) != 4 || magic[0] != 0x7f.toByte() || magic[1] != 'E'.code.toByte() || magic[2] != 'L'.code.toByte() || magic[3] != 'F'.code.toByte()) {
                throw IOException("下载内容不是 Android ELF 核心")
            }
        }
    }

    private fun writeLimited(input: InputStream, out: File) {
        FileOutputStream(out, false).use { output ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > MAX_EXTRACTED) throw IOException("解压后核心超过 128 MiB")
                output.write(buffer, 0, n)
            }
            output.fd.sync()
        }
    }

    private fun readLimited(input: InputStream, max: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) return ByteArray(0)
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }

    private fun copyExact(input: InputStream, out: File, count: Long) {
        FileOutputStream(out, false).use { output ->
            var remaining = count
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0L) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n < 0) throw IOException("tar 内容提前结束")
                output.write(buffer, 0, n)
                remaining -= n
            }
            output.fd.sync()
        }
    }

    private fun readFullyOrEof(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) return offset
            offset += n
        }
        return offset
    }

    private fun skipExact(input: InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(16 * 1024)
        while (remaining > 0L) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) throw IOException("压缩包提前结束")
            remaining -= n
        }
    }

    private fun open(url: String): HttpsURLConnection {
        if (!url.startsWith("https://")) throw IOException("只允许 HTTPS 下载源")
        val connection = URL(url).openConnection() as? HttpsURLConnection ?: throw IOException("无效下载源")
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 8_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", "Bichen/${BuildConfig.VERSION_NAME} Android")
        return connection
    }

    private fun human(value: Long): String = when {
        value >= 1_048_576L -> "%.1f MiB".format(Locale.ROOT, value / 1_048_576.0)
        value >= 1024L -> "%.0f KiB".format(Locale.ROOT, value / 1024.0)
        else -> "$value B"
    }

    companion object {
        private const val PREFS = "bichen_core_updates"
        private const val MAX_DOWNLOAD = 128L * 1024L * 1024L
        private const val MAX_EXTRACTED = 128L * 1024L * 1024L
    }
}
