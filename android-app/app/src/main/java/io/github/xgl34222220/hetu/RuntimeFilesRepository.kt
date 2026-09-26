package io.github.xgl34222220.hetu

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

internal data class RuntimeFileEntry(val name: String, val path: String, val directory: Boolean, val size: Long, val modified: Long = 0)
internal data class RuntimeFileContent(val text: String, val digest: String, val editable: Boolean, val description: String)

/** All Root paths are canonicalized by Root, including symlink targets. Sources are never overwritten on import. */
internal class RuntimeFilesRepository(private val context: Context) {
    companion object {
        const val ROOT = "/data/adb/hetu"
        const val TEXT_LIMIT = 2 * 1024 * 1024
        const val TRANSFER_LIMIT = 32 * 1024 * 1024
        fun child(directory: String, name: String): String {
            require(name.isNotBlank() && name !in listOf(".", "..") && name.none { it == '/' || it == '\\' || it.code < 32 }) { "名称不能包含路径分隔符或控制字符" }
            require(name.toByteArray().size <= 240) { "名称过长" }
            return "$directory/$name"
        }
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun utf8(bytes: ByteArray): String? = try {
            if (bytes.any { it == 0.toByte() }) null else Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { null }
    }
    private val prefs = context.getSharedPreferences("hetu", 0)
    private fun q(value: String) = RootBridge.quote(value)
    private fun guard(path: String, allowRoot: Boolean = false): String {
        require(path == ROOT || path.startsWith("$ROOT/")) { "路径不在河图运行目录内" }
        require(path.none { it.code < 32 }) { "路径无效" }
        return "p=\$(readlink -f ${q(path)}); case \"\$p\" in ${if (allowRoot) q(ROOT) + "|" else ""}${q(ROOT + "/")}*) ;; *) echo '不允许操作运行目录之外的路径'; exit 1;; esac; "
    }
    private fun shell(command: String, timeout: Long = 12000): String {
        val result = RootBridge.rootShell(context.applicationContext, "set -e; $command", timeout)
        check(result.ok()) { result.output.ifBlank { "操作失败，请检查 Root 授权" } }
        return result.output
    }
    suspend fun list(path: String): List<RuntimeFileEntry> = withContext(Dispatchers.IO) {
        val output = shell(guard(path, true) + "test -d \"\$p\"; for f in \"\$p\"/* \"\$p\"/.[!.]* \"\$p\"/..?*; do [ -e \"\$f\" ] || continue; n=\$(basename \"\$f\"); t=F; [ ! -d \"\$f\" ] || t=D; printf '%s\\t%s\\t%s\\t%s\\n' \"\$t\" \"\$n\" \"\$(stat -c %s \"\$f\")\" \"\$(stat -c %Y \"\$f\")\"; done")
        output.lineSequence().mapNotNull { line ->
            val v = line.split('\t')
            if (v.size != 4 || v[0] !in listOf("D", "F")) null else RuntimeFileEntry(v[1], child(path, v[1]), v[0] == "D", v[2].toLongOrNull() ?: 0, v[3].toLongOrNull() ?: 0)
        }.toList()
    }
    private fun rootCopy(path: String, limit: Int): File {
        val temp = File.createTempFile("hetu-read-", ".tmp", context.cacheDir)
        try {
            shell(guard(path) + "test -f \"\$p\"; [ \$(wc -c < \"\$p\") -le $limit ] || { echo '文件超过大小限制'; exit 1; }; cat \"\$p\" > ${q(temp.absolutePath)}")
            return temp
        } catch (failure: Exception) { temp.delete(); throw failure }
    }
    suspend fun read(path: String): RuntimeFileContent = withContext(Dispatchers.IO) {
        val file = rootCopy(path, TEXT_LIMIT)
        try {
            val bytes = file.readBytes()
            val text = utf8(bytes)
            if (text != null) RuntimeFileContent(text, digest(bytes), true, "UTF-8 · ${bytes.size} B")
            else if (path.endsWith(".gz", true)) {
                val decoded = GZIPInputStream(bytes.inputStream()).use { boundedRead(it, TEXT_LIMIT) }
                RuntimeFileContent(utf8(decoded) ?: hex(decoded), digest(bytes), false, "GZIP 解码预览 · 原文件保持不变")
            } else RuntimeFileContent(hex(bytes), digest(bytes), false, "二进制预览 · 前 512 字节")
        } finally { file.delete() }
    }
    private fun hex(bytes: ByteArray) = bytes.take(512).chunked(16).mapIndexed { row, data ->
        "%08x  ".format(row * 16) + data.joinToString(" ") { "%02x".format(it) }
    }.joinToString("\n")
    suspend fun save(path: String, text: String, expected: String) = withContext(Dispatchers.IO) {
        val bytes = text.toByteArray()
        require(bytes.size <= TEXT_LIMIT) { "文本超过 2 MiB" }
        require(expected.matches(Regex("[a-f0-9]{64}"))) { "缺少文件版本，请重新读取" }
        val tmp = File.createTempFile("hetu-edit-", ".tmp", context.cacheDir)
        try {
            tmp.writeBytes(bytes)
            shell(guard(path) + "test -f \"\$p\"; actual=\$(sha256sum \"\$p\"); [ \"\${actual%% *}\" = ${q(expected)} ] || { echo '文件已被其他操作更新，请保留修改并重新读取'; exit 1; }; dest=\"\$p.hetu-edit-\$\$\"; trap 'rm -f \"\$dest\"' EXIT; cp -p \"\$p\" \"\$dest\"; cat ${q(tmp.absolutePath)} > \"\$dest\"; mv -f \"\$dest\" \"\$p\"")
        } finally { tmp.delete() }
    }
    suspend fun create(directory: String, name: String, folder: Boolean) = withContext(Dispatchers.IO) {
        child(directory, name)
        shell(guard(directory, true) + "target=\"\$p/\"${q(name)}; [ ! -e \"\$target\" ] || { echo '同名文件已存在'; exit 1; }; " + if (folder) "mkdir \"\$target\"" else "(set -C; : > \"\$target\")")
    }
    suspend fun rename(entry: RuntimeFileEntry, name: String) = withContext(Dispatchers.IO) {
        child(File(entry.path).parent!!, name)
        shell(guard(entry.path) + "[ ! -L ${q(entry.path)} ] || { echo '请进入实际目录后重命名符号链接目标'; exit 1; }; parent=\$(dirname \"\$p\"); target=\"\$parent/\"${q(name)}; [ ! -e \"\$target\" ] && [ ! -L \"\$target\" ] || { echo '同名文件已存在'; exit 1; }; mv \"\$p\" \"\$target\"")
        val previous = source(entry.path)
        prefs.edit().remove("runtimeDownload." + entry.path).apply()
        if (previous.isNotBlank()) prefs.edit().putString("runtimeDownload." + child(File(entry.path).parent!!, name), previous).apply()
    }
    suspend fun delete(entry: RuntimeFileEntry) = withContext(Dispatchers.IO) {
        shell(guard(entry.path) + "[ ! -L ${q(entry.path)} ] || { echo '请进入实际目录后删除符号链接目标'; exit 1; }; rm -rf \"\$p\"")
        prefs.edit().remove("runtimeDownload." + entry.path).apply()
    }
    private fun install(directory: String, name: String, temp: File): String {
        child(directory, name)
        val output = shell(guard(directory, true) + "target=\"\$p/\"${q(name)}; i=1; while [ -e \"\$target\" ] || [ -L \"\$target\" ]; do target=\"\$p/\"${q(File(name).nameWithoutExtension)}\" (\$i)\"${q(if (File(name).extension.isBlank()) "" else "." + File(name).extension)}; i=\$((i+1)); done; (set -C; cat ${q(temp.absolutePath)} > \"\$target\"); chmod 644 \"\$target\"; printf '%s' \"\$target\"")
        return output.trim()
    }
    suspend fun importFile(directory: String, name: String, uri: Uri): String = withContext(Dispatchers.IO) {
        val temp = File.createTempFile("hetu-import-", ".tmp", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法打开所选文件" }; temp.outputStream().use { output -> copyBounded(input, output, TRANSFER_LIMIT) }
            }
            install(directory, name, temp)
        } finally { temp.delete() }
    }
    suspend fun export(entry: RuntimeFileEntry, uri: Uri) = withContext(Dispatchers.IO) {
        val temp = rootCopy(entry.path, TRANSFER_LIMIT)
        try { context.contentResolver.openOutputStream(uri, "wt").use { output -> requireNotNull(output) { "无法写入目标位置" }; temp.inputStream().use { it.copyTo(output) } } }
        finally { temp.delete() }
    }
    fun source(path: String): String = prefs.getString("runtimeDownload.$path", "").orEmpty()
    suspend fun download(directory: String, name: String, address: String, agent: String, replace: RuntimeFileEntry? = null): String = withContext(Dispatchers.IO) {
        var url = URL(address.trim())
        require(agent.none { it == '\r' || it == '\n' }) { "User-Agent 无效" }
        val temp = File.createTempFile("hetu-download-", ".tmp", context.cacheDir)
        try {
            var downloaded = false
            repeat(6) {
                if (downloaded) return@repeat
                require(url.protocol == "https" || (url.protocol == "http" && url.host in listOf("127.0.0.1", "localhost", "::1"))) { "请使用 HTTPS 下载地址" }
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false; connectTimeout = 10000; readTimeout = 15000
                    setRequestProperty("User-Agent", agent.ifBlank { "Hetu-Android" })
                }
                try {
                    val code = connection.responseCode
                    if (code in listOf(301, 302, 303, 307, 308)) url = URL(url, connection.getHeaderField("Location") ?: error("重定向地址缺失"))
                    else {
                        check(code in 200..299) { "下载失败：HTTP $code" }
                        check(connection.contentLengthLong <= TRANSFER_LIMIT) { "文件超过 32 MiB" }
                        connection.inputStream.use { input -> temp.outputStream().use { copyBounded(input, it, TRANSFER_LIMIT) } }
                        downloaded = true
                    }
                } finally { connection.disconnect() }
            }
            check(downloaded) { "重定向次数过多" }
            val installed = if (replace == null) install(directory, name, temp) else {
                shell(guard(replace.path) + "dest=\"\$p.hetu-download-\$\$\"; trap 'rm -f \"\$dest\"' EXIT; cp -p \"\$p\" \"\$dest\"; cat ${q(temp.absolutePath)} > \"\$dest\"; mv -f \"\$dest\" \"\$p\"")
                replace.path
            }
            prefs.edit().putString("runtimeDownload.$installed", address.trim()).apply()
            installed
        } finally { temp.delete() }
    }
    private fun boundedRead(input: InputStream, limit: Int): ByteArray = java.io.ByteArrayOutputStream().use { out -> copyBounded(input, out, limit); out.toByteArray() }
    private fun copyBounded(input: InputStream, output: java.io.OutputStream, limit: Int) {
        val buffer = ByteArray(16384); var total = 0
        while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= limit) { "文件超过大小限制" }; output.write(buffer, 0, count) }
    }
}
