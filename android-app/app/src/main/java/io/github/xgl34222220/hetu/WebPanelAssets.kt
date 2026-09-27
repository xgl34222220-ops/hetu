package io.github.xgl34222220.hetu

import android.content.Context
import android.webkit.WebResourceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** App-private dashboard assets; never exposes a Javascript-to-Root bridge. */
internal object WebPanelAssets {
    const val PREFIX = "/hetu-panel/"
    private const val RELEASE = "https://github.com/Zephyruso/zashboard/releases/latest/download/dist-no-fonts.zip"
    private fun directory(context: Context) = File(context.filesDir, "web-panel/current")
    fun installed(context: Context) = File(directory(context), "index.html").isFile
    fun response(context: Context, path: String): WebResourceResponse? {
        if (!path.startsWith(PREFIX)) return null
        val root = directory(context).canonicalFile
        val relative = path.removePrefix(PREFIX).ifBlank { "index.html" }
        val file = File(root, relative).canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile)
            return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), "Not Found".byteInputStream())
        val mime = when(file.extension.lowercase()) {
            "html" -> "text/html"; "js", "mjs" -> "application/javascript"; "css" -> "text/css"; "json" -> "application/json"; "svg" -> "image/svg+xml"
            else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: "application/octet-stream"
        }
        return WebResourceResponse(mime, "UTF-8", file.inputStream())
    }
    internal fun extract(input: InputStream, folder: File): File {
        folder.mkdirs(); val root = folder.canonicalFile; var total = 0L; var count = 0
        ZipInputStream(input).use { zip ->
            val buffer = ByteArray(16384)
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= 5000) { "面板文件数量过多" }
                require(!entry.name.startsWith('/') && !entry.name.contains('\\') && !entry.name.contains('\u0000')) { "面板文件路径无效" }
                val target = File(root, entry.name).canonicalFile
                require(target.path.startsWith(root.path + File.separator)) { "面板文件超出目标目录" }
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile!!.mkdirs()
                    target.outputStream().use { out ->
                        while (true) { val n = zip.read(buffer); if (n < 0) break; total += n; require(total <= 60L * 1024 * 1024) { "面板解压体积过大" }; out.write(buffer, 0, n) }
                    }
                }
            }
        }
        val panel = if (File(root, "index.html").isFile) root else root.listFiles()?.singleOrNull { it.isDirectory && File(it, "index.html").isFile }
        return requireNotNull(panel) { "面板缺少 index.html；保留当前版本" }
    }
    suspend fun update(context: Context) = withContext(Dispatchers.IO) {
        val parent = directory(context).parentFile!!; parent.mkdirs()
        val temp = File.createTempFile("download-", ".zip", parent)
        val stage = File(parent, "stage-${System.nanoTime()}")
        try {
            var url = URL(RELEASE); var done = false
            repeat(6) {
                if (done) return@repeat
                require(url.protocol == "https" && (url.host == "github.com" || url.host.endsWith(".githubusercontent.com"))) { "面板下载来源无效" }
                val connection = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 10000; readTimeout = 20000; instanceFollowRedirects = false }
                try {
                    if (connection.responseCode in listOf(301,302,303,307,308)) url = URL(url, connection.getHeaderField("Location") ?: error("下载重定向缺失"))
                    else {
                        check(connection.responseCode == 200) { "面板下载失败：HTTP ${connection.responseCode}" }
                        connection.inputStream.use { input -> temp.outputStream().use { out ->
                            val buffer = ByteArray(16384); var total = 0
                            while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 20 * 1024 * 1024) { "面板下载过大" }; out.write(buffer, 0, n) }
                        } }; done = true
                    }
                } finally { connection.disconnect() }
            }
            check(done) { "面板下载重定向过多" }
            val unpacked = extract(temp.inputStream(), stage)
            val current = directory(context); val previous = File(parent, "previous")
            previous.deleteRecursively()
            if (current.exists()) check(current.renameTo(previous)) { "无法保留当前面板" }
            if (!unpacked.renameTo(current)) { previous.renameTo(current); error("面板安装失败；已恢复原版本") }
            previous.deleteRecursively()
        } finally { temp.delete(); stage.deleteRecursively() }
    }
}
