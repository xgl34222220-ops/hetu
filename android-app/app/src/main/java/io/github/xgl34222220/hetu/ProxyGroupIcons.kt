package io.github.xgl34222220.hetu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.SystemClock
import android.util.LruCache
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.*
import java.io.StringReader
import java.util.Collections
import java.util.IdentityHashMap
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.Proxy
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Read-only YAML projection. Never rewrites the selected config, order or icon URLs. */
internal object ProxyGroupIcons {
    fun parse(source: String): Map<String, String> {
        val options = LoaderOptions().apply {
            codePointLimit = 4_194_304
            maxAliasesForCollections = 4096
            nestingDepthLimit = 64
            isAllowDuplicateKeys = false
        }
        // Compose the bounded YAML graph, not the entire configuration's objects.
        // Only scalar name/icon fields and bounded merge chains are projected.
        // Unrelated provider templates must not erase all strategy icons after 64 aliases.
        val root = Yaml(SafeConstructor(options)).compose(StringReader(source)) as? MappingNode ?: return emptyMap()
        val groups = root.value.lastOrNull { (it.keyNode as? ScalarNode)?.value == "proxy-groups" }
            ?.valueNode as? SequenceNode ?: return emptyMap()
        fun field(node: Node, key: String, seen: MutableSet<Node>, depth: Int = 0): String? {
            if (depth > 12 || !seen.add(node)) return null
            val row = node as? MappingNode ?: return null
            row.value.lastOrNull { (it.keyNode as? ScalarNode)?.value == key }?.let {
                return (it.valueNode as? ScalarNode)?.value
            }
            for (entry in row.value) {
                if ((entry.keyNode as? ScalarNode)?.value != "<<") continue
                val merged = entry.valueNode
                val candidates = if (merged is SequenceNode) merged.value else listOf(merged)
                for (candidate in candidates) field(candidate, key, seen, depth + 1)?.let { return it }
            }
            return null
        }
        fun read(node: Node, key: String): String? = field(node, key,
            Collections.newSetFromMap(IdentityHashMap<Node, Boolean>()))
        val result = linkedMapOf<String, String>()
        for (entry in groups.value) {
            val name = read(entry, "name") ?: continue
            val icon = read(entry, "icon") ?: continue
            if (name.isNotBlank() && icon.isNotBlank()) result[name] = icon
        }
        return result
    }
}

internal sealed interface GroupIconLoad {
    data class Ready(val bitmap: Bitmap, val cached: Boolean) : GroupIconLoad
    data object Loading : GroupIconLoad
    data object Failed : GroupIconLoad
}

/** Process-wide, single-flight image cache. No core/controller calls belong here. */
internal class ProxyGroupIconRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val directory = File(context.cacheDir, "proxy/group-icons-v2").apply { mkdirs() }
    private val legacyDirectory = File(context.cacheDir, "proxy/group-icons")
    private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val gates = ConcurrentHashMap<String, Mutex>()
    private val retries = ConcurrentHashMap<String, Long>()
    private val slots = Semaphore(6)

    fun diskPath(url: String): String {
        if (url.isBlank()) return ""
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return listOf(File(directory, "$key.png"), File(legacyDirectory, "$key.img"))
            .firstOrNull { it.isFile && it.length() in 1..MAX_BYTES }?.absolutePath.orEmpty()
    }

    fun peek(url: String): Bitmap? = memory.get(url)

    suspend fun load(url: String, legacyPath: String = ""): GroupIconLoad = withContext(Dispatchers.IO) {
        peek(url)?.let { return@withContext GroupIconLoad.Ready(it, true) }
        gates.getOrPut(url) { Mutex() }.withLock {
            peek(url)?.let { return@withLock GroupIconLoad.Ready(it, true) }
            val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            val target = File(directory, "$key.png")
            decodeFile(target)?.let { memory.put(url, it); return@withLock GroupIconLoad.Ready(it, true) }
            if (legacyPath.isNotBlank()) decodeFile(File(legacyPath))?.let {
                memory.put(url, it); return@withLock GroupIconLoad.Ready(it, true)
            }
            if (SystemClock.elapsedRealtime() < (retries[url] ?: 0L)) return@withLock GroupIconLoad.Failed
            try {
                val bitmap = slots.withPermit { decode(fetch(url)) }
                val temporary = File(directory, "$key.new")
                try {
                    temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    if (!temporary.renameTo(target)) temporary.copyTo(target, overwrite = true)
                } finally { temporary.delete() }
                memory.put(url, bitmap)
                retries.remove(url)
                GroupIconLoad.Ready(bitmap, false)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                retries[url] = SystemClock.elapsedRealtime() + 60_000L
                GroupIconLoad.Failed
            }
        }
    }

    private fun decodeFile(file: File): Bitmap? = runCatching {
        if (!file.isFile || file.length() !in 1..MAX_BYTES) null else decode(file.readBytes())
    }.getOrNull()

    internal fun imageProxy(): Proxy {
        val prefs = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("proxyRootRuntimeRunning", false)) return Proxy.NO_PROXY
        val controller = prefs.getInt("proxyControllerPort", MihomoStartupConfig.CONTROLLER_PORT)
        val port = MihomoStartupConfig.egressProbePort(controller)
        if (port !in 1024..65535) throw IOException("图标代理端口未就绪")
        // No direct fallback when an active proxy is requested. Cached icons remain usable.
        return Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
    }

    private fun fetch(address: String): ByteArray {
        var url = URL(address)
        val deadline = SystemClock.elapsedRealtime() + 12_000L
        repeat(6) {
            if (url.protocol != "https" || url.userInfo != null) throw IOException("图标只支持无凭据 HTTPS 地址")
            val connection = url.openConnection(imageProxy()) as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 7_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "Hetu/Android")
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    url = URL(url, connection.getHeaderField("Location") ?: throw IOException("缺少跳转地址"))
                } else {
                    if (status !in 200..299 || connection.contentLengthLong > MAX_BYTES) throw IOException("图标读取失败")
                    return connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val remaining = deadline - SystemClock.elapsedRealtime()
                            if (remaining <= 0) throw IOException("图标读取超时")
                            connection.readTimeout = minOf(7_000L, remaining).toInt()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > MAX_BYTES) throw IOException("图标过大")
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                }
            } finally { connection.disconnect() }
        }
        throw IOException("图标跳转过多")
    }

    companion object {
        private const val MAX_BYTES = 1_572_864L
        @Volatile private var instance: ProxyGroupIconRepository? = null
        fun get(context: Context): ProxyGroupIconRepository = instance ?: synchronized(this) {
            instance ?: ProxyGroupIconRepository(context.applicationContext).also { instance = it }
        }
        internal fun decode(bytes: ByteArray): Bitmap {
            val prefix = bytes.take(2048).toByteArray().toString(Charsets.UTF_8)
            if (prefix.contains("<svg", ignoreCase = true)) {
                val document = bytes.toString(Charsets.UTF_8)
                if (document.contains("<!DOCTYPE", true) || document.contains("<!ENTITY", true)) throw IOException("不支持带实体声明的 SVG")
                val svg = SVG.getFromString(document)
                // AndroidSVG does not execute scripts. No external resource resolver is registered.
                val aspect = svg.documentAspectRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
                // Intrinsic dimensions need a viewBox before scaling to our raster.
                if (svg.documentViewBox == null && svg.documentWidth > 0f && svg.documentHeight > 0f) {
                    svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
                }
                svg.setDocumentWidth("100%")
                svg.setDocumentHeight("100%")
                val width = if (aspect >= 1f) 192 else (192 * aspect).toInt().coerceAtLeast(1)
                val height = if (aspect >= 1f) (192 / aspect).toInt().coerceAtLeast(1) else 192
                return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { svg.renderToCanvas(Canvas(it)) }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("未知图标格式")
            val options = BitmapFactory.Options()
            while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize.coerceAtLeast(1) > 512) {
                options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw IOException("图标解码失败")
        }
    }
}
