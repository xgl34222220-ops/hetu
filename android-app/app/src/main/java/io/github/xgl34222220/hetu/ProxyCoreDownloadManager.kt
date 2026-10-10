package io.github.xgl34222220.hetu

import android.content.Context
import android.net.Uri
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

/** Presentation of the actual transfer callback; an unknown total stays unknown. */
internal fun coreDownloadProgressText(done: Long, total: Long): String {
    fun bytes(value: Long): String = when {
        value >= 1_000_000L -> "%.1f MB".format(Locale.ROOT, value / 1_000_000.0)
        value >= 1_000L -> "%.1f KB".format(Locale.ROOT, value / 1_000.0)
        else -> "$value B"
    }
    val transferred = bytes(done.coerceAtLeast(0L))
    return "正在下载 · " + if (total > 0L) "$transferred / ${bytes(total)}" else transferred
}

/**
 * What each downloadable core can do in Hetu's Root runtime.
 *
 * Every core runs behind the same Root TPROXY / Redirect capture (hetu-root.sh launches it with its own
 * CLI from run/state/core.kind; ProxyCoreConfig writes its native config or converts the Clash/Mihomo
 * profile). Features that need Mihomo's Clash controller degrade per core with an explicit
 * "此核心不支持" state instead of a connection error:
 *  - panel / policy groups / node switching / latency test: Mihomo, Mihomo Smart, sing-box (clash_api);
 *  - hot reload and live adblock refresh: Mihomo family only (others apply changes on restart);
 *  - rule routing and the adblock chain: every core except Hysteria (single-server client).
 */
internal object ProxyCoreSupport {
    private fun mihomo(core: ProxyRuntimeProfile.Core) =
        core == ProxyRuntimeProfile.Core.MIHOMO || core == ProxyRuntimeProfile.Core.MIHOMO_SMART

    @JvmStatic fun runtimeSupported(core: ProxyRuntimeProfile.Core): Boolean = true
    @JvmStatic fun clashApi(core: ProxyRuntimeProfile.Core): Boolean = ProxyCoreConfig.clashApi(core)
    @JvmStatic fun panel(core: ProxyRuntimeProfile.Core): Boolean = clashApi(core)
    @JvmStatic fun latencyTest(core: ProxyRuntimeProfile.Core): Boolean = clashApi(core)
    @JvmStatic fun hotReload(core: ProxyRuntimeProfile.Core): Boolean = mihomo(core)
    @JvmStatic fun adblockLiveRefresh(core: ProxyRuntimeProfile.Core): Boolean = mihomo(core)
    @JvmStatic fun ruleRouting(core: ProxyRuntimeProfile.Core): Boolean = core != ProxyRuntimeProfile.Core.HYSTERIA
    @JvmStatic fun adblock(core: ProxyRuntimeProfile.Core): Boolean = core != ProxyRuntimeProfile.Core.HYSTERIA
    @JvmStatic fun singleServer(core: ProxyRuntimeProfile.Core): Boolean = core == ProxyRuntimeProfile.Core.HYSTERIA

    /** The explicit degraded-state text shown instead of an error. */
    @JvmStatic fun unsupported(core: ProxyRuntimeProfile.Core, feature: String): String = "此核心不支持$feature（${core.label}）"

    /** Kept for callers of the previous download policy: every core runs, so there is no reason. */
    @JvmStatic fun unsupportedReason(core: ProxyRuntimeProfile.Core): String = ""

    /** One line for the core picker: modes and which panel features work. */
    @JvmStatic fun featureSummary(core: ProxyRuntimeProfile.Core): String = when {
        mihomo(core) -> ""
        core == ProxyRuntimeProfile.Core.HYSTERIA -> "TPROXY / Redirect · 单服务器（仅用一个 Hysteria 2 节点）· 无规则分流、面板与测速"
        clashApi(core) -> "TPROXY / Redirect · 面板与测速经 clash_api · 不支持热重载"
        else -> "TPROXY / Redirect · 规则分流 · 无 Clash 控制接口：面板、测速与热重载不可用"
    }
}

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
    private val uiPrefs = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
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
            ProxyRuntimeProfile.Core.SING_BOX_REF1ND,
            "reF1nd/sing-box-releases",
            "https://api.github.com/repos/reF1nd/sing-box-releases/releases/latest",
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
            "apernet/hysteria",
            "https://api.github.com/repos/apernet/hysteria/releases/latest",
            Archive.RAW,
            "hysteria",
        ),
    )

    suspend fun statuses(forceNetwork: Boolean = true): List<ProxyCoreRemoteStatus> = withContext(Dispatchers.IO) {
        coroutineScope {
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
            message = when {
                core == ProxyRuntimeProfile.Core.MIHOMO && !downloaded -> "当前使用 App 内置 Mihomo，可在线下载最新版覆盖；删除下载版会自动回退内置核心"
                asset.name.lowercase(Locale.ROOT).contains("-linux-") -> "该架构没有官方 Android 构建，使用 Linux 静态构建（河图已为其配置内置 DNS）"
                else -> local.message
            },
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
            onProgress(coreDownloadProgressText(done, total))
        }
        val extracted = File(app.cacheDir, "proxy-core-${core.id}-${System.nanoTime()}.bin")
        // Xray / V2Fly ship geoip.dat + geosite.dat in the same zip (geoip:/geosite: rules).
        val geo = GEO_ASSETS.associateWith { File(app.cacheDir, "proxy-core-${core.id}-${System.nanoTime()}-$it") }
        try {
            onProgress("校验并解压 ${core.label}…")
            extract(asset, archive, extracted, geo)
            verifyElf(extracted)
            FileInputStream(extracted).use { store.importCore(core, it) }
            installGeoAssets(core, geo)
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
            geo.values.forEach { it.delete() }
        }
        status(core, false).copy(
            latestVersion = asset.version,
            updateAvailable = false,
            canDownload = true,
            source = asset.source.repo,
            message = ProxyCoreSupport.featureSummary(core).let { if (it.isEmpty()) "已就绪" else "已就绪 · $it" },
        )
    }

    suspend fun importFromUri(
        core: ProxyRuntimeProfile.Core,
        uri: Uri,
        displayName: String,
    ): ProxyCoreRemoteStatus = withContext(Dispatchers.IO) {
        val temp = File(app.cacheDir, "proxy-core-import-${core.id}-${System.nanoTime()}.bin")
        try {
            val input = app.contentResolver.openInputStream(uri) ?: throw IOException("无法读取核心文件")
            input.use { writeLimited(it, temp) }
            verifyElf(temp)
            FileInputStream(temp).use { store.importCore(core, it) }
            prefs.edit()
                .putString("version_${core.id}", "本地导入")
                .putString("source_${core.id}", "local")
                .putString("asset_${core.id}", displayName.ifBlank { uri.lastPathSegment ?: "本地文件" })
                .putLong("updated_${core.id}", System.currentTimeMillis())
                .apply()
            localStatus(core, "已从文件导入")
        } finally {
            temp.delete()
        }
    }

    suspend fun removeDownloaded(core: ProxyRuntimeProfile.Core): ProxyCoreRemoteStatus = withContext(Dispatchers.IO) {
        if (store.installed(core)) store.remove(core)
        GEO_ASSETS.forEach { geoFile(core, it).delete() }
        prefs.edit()
            .remove("version_${core.id}")
            .remove("source_${core.id}")
            .remove("asset_${core.id}")
            .remove("updated_${core.id}")
            .apply()
        localStatus(core, if (core == ProxyRuntimeProfile.Core.MIHOMO) "已恢复使用 App 内置 Mihomo" else "已删除下载核心")
    }

    private fun localStatus(core: ProxyRuntimeProfile.Core, message: String = ""): ProxyCoreRemoteStatus {
        val bundled = core == ProxyRuntimeProfile.Core.MIHOMO
        val downloaded = store.installed(core)
        val installed = when {
            downloaded -> installedVersion(core).ifBlank { "已下载" }
            bundled -> "App 内置版本"
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

    private fun runtimeReady(core: ProxyRuntimeProfile.Core): Boolean = ProxyCoreSupport.runtimeSupported(core)

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
        val abi = abiTag(Build.SUPPORTED_ABIS)
        val candidates = ArrayList<JSONObject>()
        for (i in 0 until assets.length()) {
            val item = assets.optJSONObject(i) ?: continue
            if (matchesAsset(source.core, item.optString("name", ""), abi)) candidates += item
        }
        val chosen = candidates.minByOrNull { assetRank(source.core, it.optString("name", "")) } ?: return null
        val url = chosen.optString("browser_download_url", "")
        if (!url.startsWith("https://")) return null
        return Asset(
            version = assetVersion(source.core, tag, chosen.optString("name", "")),
            name = chosen.optString("name", "core"),
            url = url,
            digest = chosen.optString("digest", ""),
            size = chosen.optLong("size", 0L),
            source = source,
        )
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
        val connection = open(assetDownloadUrl(asset.url))
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

    private fun extract(asset: Asset, archive: File, out: File, geo: Map<String, File> = emptyMap()) {
        when (asset.source.archive) {
            Archive.RAW -> FileInputStream(archive).use { input -> writeLimited(input, out) }
            Archive.GZIP -> FileInputStream(archive).use { raw -> GZIPInputStream(BufferedInputStream(raw)).use { writeLimited(it, out) } }
            Archive.ZIP -> extractZip(archive, out, asset.source.executableName, geo)
            Archive.TAR_GZIP -> extractTarGzip(archive, out, asset.source.executableName)
        }
    }

    private fun geoFile(core: ProxyRuntimeProfile.Core, name: String): File = File(store.file(core).parentFile, "${core.id}.$name")

    /** Kept beside the binary; RootProxyManager deploys them to /data/adb/hetu/bin/assets. */
    private fun installGeoAssets(core: ProxyRuntimeProfile.Core, geo: Map<String, File>) {
        for ((name, temp) in geo) {
            val target = geoFile(core, name)
            if (temp.isFile && temp.length() > 0L) {
                if (!temp.renameTo(target)) { temp.copyTo(target, overwrite = true); temp.delete() }
            } else target.delete()
        }
    }

    private fun extractZip(archive: File, out: File, executable: String, geo: Map<String, File> = emptyMap()) {
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            var fallback: ByteArray? = null
            var found = false
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val base = entry.name.substringAfterLast('/').lowercase(Locale.ROOT)
                if (!found && (base == executable.lowercase(Locale.ROOT) || base == "$executable.exe".lowercase(Locale.ROOT))) {
                    writeLimited(zip, out)
                    found = true
                    continue
                }
                val geoTarget = geo[base]
                if (geoTarget != null && !entry.name.contains('/')) {
                    writeLimited(zip, geoTarget)
                    continue
                }
                if (found) continue
                if (fallback == null && !base.endsWith(".dat") && !base.endsWith(".json") && !base.endsWith(".txt")) {
                    val bytes = readLimited(zip, 4L * 1024L * 1024L)
                    if (bytes.size >= 4 && bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte()) fallback = bytes
                }
            }
            if (found) return
            fallback?.let {
                FileOutputStream(out, false).use { stream -> stream.write(it); stream.fd.sync() }
                return
            }
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
            if (
                input.read(magic) != 4 ||
                magic[0] != 0x7f.toByte() ||
                magic[1] != 'E'.code.toByte() ||
                magic[2] != 'L'.code.toByte() ||
                magic[3] != 'F'.code.toByte()
            ) {
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

    private fun assetDownloadUrl(url: String): String {
        if (!uiPrefs.getBoolean("downloadMirrorEnabled", false)) return url
        if (!url.startsWith("https://github.com/")) return url
        val prefix = uiPrefs.getString("downloadMirrorPrefix", "").orEmpty().trim()
        if (!prefix.startsWith("https://") || prefix.length > 512) return url
        val candidate = if ("{url}" in prefix) {
            prefix.replace("{url}", url)
        } else {
            prefix.trimEnd('/') + "/" + url
        }
        return candidate.takeIf { it.startsWith("https://") && it.length <= 4096 } ?: url
    }

    private fun open(url: String): HttpsURLConnection {
        if (!url.startsWith("https://")) throw IOException("只允许 HTTPS 下载源")
        val connection = URL(url).openConnection() as? HttpsURLConnection ?: throw IOException("无效下载源")
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 8_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", "Hetu/${BuildConfig.VERSION_NAME} Android")
        return connection
    }

    companion object {
        /** Release asset naming per core, for one ABI tag (see [abiTag]). Pure, so it is unit tested. */
        internal fun matchesAsset(core: ProxyRuntimeProfile.Core, rawName: String, abi: String): Boolean {
            val name = rawName.lowercase(Locale.ROOT)
            if (abi !in ABI_TAGS) return false
            return when (core) {
                ProxyRuntimeProfile.Core.MIHOMO,
                ProxyRuntimeProfile.Core.MIHOMO_SMART -> {
                    // mihomo-android-arm64-v8-v1.19.32.gz, mihomo-android-armv7-alpha-smart-8d4c8c7.gz …
                    name.startsWith("mihomo-android-$abi-") && name.endsWith(".gz")
                }
                ProxyRuntimeProfile.Core.SING_BOX,
                ProxyRuntimeProfile.Core.SING_BOX_REF1ND -> {
                    // sing-box-1.14.3-android-arm64.tar.gz; ARMv7 is published as "-android-arm".
                    name.startsWith("sing-box-") && name.endsWith("-android-${singBoxArch(abi)}.tar.gz")
                }
                ProxyRuntimeProfile.Core.XRAY -> name in goZipNames("xray", abi)
                ProxyRuntimeProfile.Core.V2FLY -> name in goZipNames("v2ray", abi)
                ProxyRuntimeProfile.Core.HYSTERIA -> name == "hysteria-android-$abi"
            }
        }

        private val ABI_TAGS = setOf("arm64", "armv7", "amd64", "386")

        private fun singBoxArch(abi: String) = if (abi == "armv7") "arm" else abi

        /** Android build first; the static Linux build only where no Android build is published. */
        private fun goZipNames(prefix: String, abi: String): List<String> = when (abi) {
            "arm64" -> listOf("$prefix-android-arm64-v8a.zip", "$prefix-linux-arm64-v8a.zip")
            "armv7" -> listOf("$prefix-android-arm32-v7a.zip", "$prefix-linux-arm32-v7a.zip")
            "amd64" -> listOf("$prefix-android-amd64.zip", "$prefix-linux-64.zip")
            "386" -> listOf("$prefix-android-386.zip", "$prefix-linux-32.zip")
            else -> emptyList()
        }

        /** Prefer the most compatible build when a release offers several for one ABI. */
        internal fun assetRank(core: ProxyRuntimeProfile.Core, rawName: String): Int {
            val name = rawName.lowercase(Locale.ROOT)
            if (core == ProxyRuntimeProfile.Core.MIHOMO || core == ProxyRuntimeProfile.Core.MIHOMO_SMART) {
                return when {
                    name.contains("compatible") -> 0
                    name.contains("-v1-") -> 1
                    name.contains("-v8-") -> 2
                    else -> 3
                }
            }
            return if (name.contains("-linux-")) 1 else 0
        }

        /** Release ABI tag of the first supported device ABI: arm64, armv7, amd64, 386 or unknown. */
        internal fun abiTag(supportedAbis: Array<String>): String {
            for (raw in supportedAbis) {
                when (raw.lowercase(Locale.ROOT)) {
                    "arm64-v8a" -> return "arm64"
                    "armeabi-v7a" -> return "armv7"
                    "x86_64" -> return "amd64"
                    "x86" -> return "386"
                }
            }
            return "unknown"
        }

        /**
         * The version recorded for an installed download. Mihomo Smart publishes every build under the
         * same tag (Prerelease-Alpha), so its build id from the asset name is part of the version;
         * otherwise a new Smart build would never show as an update.
         */
        internal fun assetVersion(core: ProxyRuntimeProfile.Core, tag: String, assetName: String): String {
            // apernet/hysteria tags its app releases "app/v2.13.0".
            if (core == ProxyRuntimeProfile.Core.HYSTERIA) return tag.removePrefix("app/")
            if (core != ProxyRuntimeProfile.Core.MIHOMO_SMART) return tag
            val build = assetName.lowercase(Locale.ROOT).removeSuffix(".gz")
                .replace(Regex("^mihomo-android-(arm64|armv7|amd64|386)-"), "")
                .replace(Regex("^(v8|v1|v2|v3|compatible)-"), "")
            return if (build.isBlank() || build == tag.lowercase(Locale.ROOT)) tag else "$tag · $build"
        }

        /** Geo databases extracted with Xray / V2Fly. */
        internal val GEO_ASSETS = listOf("geoip.dat", "geosite.dat")
        private const val PREFS = "hetu_core_updates"
        private const val MAX_DOWNLOAD = 128L * 1024L * 1024L
        private const val MAX_EXTRACTED = 128L * 1024L * 1024L
    }
}
