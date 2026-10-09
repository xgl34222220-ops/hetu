package io.github.xgl34222220.hetu

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.MarkedYAMLException

/*
 * 基础代理配置 › 编辑 / 导入.
 *
 * Every write of a source config goes through [ConfigTransaction]: strict decoding and a YAML
 * pre-check, the core's own `-t` validation, a backup of the replaced text, an atomic write
 * (ProxyConfigLibrary), a hot reload when the proxy runs, and a rollback of the source when the
 * running core refuses the new config. The storage format of the config library is unchanged;
 * backups live in their own directory.
 */

/* ------------------------------ 文本与预检 ------------------------------ */

internal data class ConfigCheck(
    val error: String?,
    val warnings: List<String>,
    val proxies: Int,
    val providers: Int,
    val groups: Int,
    val rules: Int,
    val lines: Int,
    val bytes: Int,
) {
    val ok: Boolean get() = error == null
}

internal object ConfigText {
    const val LIMIT = 4 * 1024 * 1024

    /** Strict UTF-8 (BOM dropped); rejects empty, binary and oversize input. */
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) throw IOException("配置为空")
        if (bytes.size > LIMIT) throw IOException("配置超过 4 MiB")
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw IOException("配置必须是 UTF-8 文本")
        }
        val body = text.removePrefix("\uFEFF")
        if (body.indexOf('\u0000') >= 0) throw IOException("这不是文本配置文件")
        if (body.isBlank()) throw IOException("配置为空")
        return body
    }

    /** Reads at most [LIMIT] + 1 bytes so an oversize source is refused without loading it all. */
    fun readLimited(input: InputStream): ByteArray = input.use { stream ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            total += n
            if (total > LIMIT) throw IOException("配置超过 4 MiB")
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    }

    private val base64Line = Regex("^[A-Za-z0-9+/=_-]{40,}$")

    /** Local YAML pre-check; the core's `-t` remains the authority. */
    fun inspect(text: String): ConfigCheck {
        val bytes = text.toByteArray(StandardCharsets.UTF_8).size
        val lines = text.count { it == '\n' } + 1
        fun fail(message: String) = ConfigCheck(message, emptyList(), 0, 0, 0, 0, lines, bytes)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return fail("配置为空")
        if (!trimmed.contains(':') && trimmed.lineSequence().all { it.isBlank() || base64Line.matches(it.trim()) })
            return fail("这是 Base64 节点订阅，不是 Mihomo YAML 配置；请在「配置与订阅」里作为订阅链接添加")
        if (trimmed.startsWith("<")) return fail("这是网页内容，不是 YAML 配置")
        val root = try {
            val options = LoaderOptions().apply {
                codePointLimit = LIMIT * 2
                maxAliasesForCollections = 200
                isAllowDuplicateKeys = true
            }
            Yaml(options).load<Any?>(text)
        } catch (marked: MarkedYAMLException) {
            val line = marked.problemMark?.line?.plus(1)
            return fail("第 ${line ?: "?"} 行 YAML 语法错误：${marked.problem ?: marked.message.orEmpty().lineSequence().firstOrNull().orEmpty()}")
        } catch (error: Exception) {
            return fail("YAML 无法解析：${error.message?.lineSequence()?.firstOrNull() ?: error.javaClass.simpleName}")
        }
        if (root !is Map<*, *>) return fail("顶层必须是键值映射（例如 proxies: / rules:）")
        fun size(key: String): Int = when (val value = root[key]) {
            is Collection<*> -> value.size
            is Map<*, *> -> value.size
            else -> 0
        }
        val proxies = size("proxies")
        val providers = size("proxy-providers")
        val warnings = ArrayList<String>()
        if (proxies == 0 && providers == 0) warnings += "没有节点（proxies）也没有订阅（proxy-providers）"
        if (size("proxy-groups") == 0) warnings += "没有策略组（proxy-groups）"
        if (size("rules") == 0 && root["rules"] !is String) warnings += "没有分流规则（rules），流量全部走默认"
        return ConfigCheck(null, warnings, proxies, providers, size("proxy-groups"), size("rules"), lines, bytes)
    }

    /** A library file name for an import: keeps a given name, otherwise one from the URL path. */
    fun importName(raw: String?, fallback: String = "导入配置"): String {
        var name = raw.orEmpty().substringAfterLast('/').substringBefore('?').substringBefore('#').trim()
        name = try { URLDecoder.decode(name, "UTF-8") } catch (_: Exception) { name }
        name = name.replace(Regex("[\\\\/\\u0000-\\u001f]"), "_").trim().take(100)
        if (name.isBlank() || name == "." || name == "..") name = fallback
        val lower = name.lowercase(Locale.ROOT)
        if (!lower.endsWith(".yaml") && !lower.endsWith(".yml")) name += ".yaml"
        if (name.length < 3) name = "$fallback.yaml"
        return name
    }

    /** Downloads a config over HTTP(S); bounded in size and time. Runs on IO. */
    suspend fun download(url: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        if (!(trimmed.startsWith("https://", true) || trimmed.startsWith("http://", true)) || trimmed.length > 4096)
            throw IOException("请输入有效的 http/https 链接")
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(trimmed).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 10_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", "clash.meta (Hetu)")
                setRequestProperty("Accept", "*/*")
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("下载失败：HTTP $code")
            val declared = connection.contentLengthLong
            if (declared > LIMIT) throw IOException("配置超过 4 MiB")
            val text = decode(readLimited(connection.inputStream))
            val header = connection.getHeaderField("Content-Disposition").orEmpty()
            val named = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)").find(header)?.groupValues?.get(1)
            importName(named ?: connection.url.path) to text
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }
}

/* ------------------------------ 差异预览 ------------------------------ */

internal data class DiffLine(val kind: Char, val text: String)

internal data class ConfigDiff(val added: Int, val removed: Int, val lines: List<DiffLine>, val truncated: Boolean) {
    val identical: Boolean get() = added == 0 && removed == 0
}

internal object ConfigDiffer {
    /** Cells above this fall back to a block replace (still correct, not minimal). */
    private const val MAX_CELLS = 1_000_000L

    /**
     * Line diff with [context] unchanged lines around each change; at most [maxLines] lines are
     * returned. `' '` unchanged, `'+'` added, `'-'` removed, `'…'` skipped unchanged lines.
     */
    fun diff(old: String, new: String, context: Int = 2, maxLines: Int = 400): ConfigDiff {
        val a = old.split('\n')
        val b = new.split('\n')
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++
        val midA = a.subList(prefix, a.size - suffix)
        val midB = b.subList(prefix, b.size - suffix)
        val ops = ArrayList<DiffLine>(a.size + 8)
        for (i in 0 until prefix) ops += DiffLine(' ', a[i])
        if (midA.size.toLong() * midB.size.toLong() <= MAX_CELLS) ops += lcs(midA, midB)
        else { midA.forEach { ops += DiffLine('-', it) }; midB.forEach { ops += DiffLine('+', it) } }
        for (i in a.size - suffix until a.size) ops += DiffLine(' ', a[i])
        val added = ops.count { it.kind == '+' }
        val removed = ops.count { it.kind == '-' }
        // Keep changes plus [context] lines around them.
        val keep = BooleanArray(ops.size)
        ops.forEachIndexed { index, line ->
            if (line.kind != ' ') for (k in (index - context).coerceAtLeast(0)..(index + context).coerceAtMost(ops.size - 1)) keep[k] = true
        }
        val out = ArrayList<DiffLine>()
        var skipped = 0
        var truncated = false
        for (index in ops.indices) {
            if (!keep[index]) { skipped++; continue }
            if (skipped > 0) { out += DiffLine('…', "$skipped"); skipped = 0 }
            if (out.size >= maxLines) { truncated = true; break }
            out += ops[index]
        }
        if (!truncated && skipped > 0 && out.isNotEmpty()) out += DiffLine('…', "$skipped")
        return ConfigDiff(added, removed, out, truncated)
    }

    private fun lcs(a: List<String>, b: List<String>): List<DiffLine> {
        val n = a.size
        val m = b.size
        if (n == 0) return b.map { DiffLine('+', it) }
        if (m == 0) return a.map { DiffLine('-', it) }
        val width = m + 1
        val table = IntArray((n + 1) * width)
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            table[i * width + j] = if (a[i] == b[j]) table[(i + 1) * width + j + 1] + 1
                else maxOf(table[(i + 1) * width + j], table[i * width + j + 1])
        }
        val out = ArrayList<DiffLine>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out += DiffLine(' ', a[i]); i++; j++ }
                table[(i + 1) * width + j] >= table[i * width + j + 1] -> { out += DiffLine('-', a[i]); i++ }
                else -> { out += DiffLine('+', b[j]); j++ }
            }
        }
        while (i < n) out += DiffLine('-', a[i++])
        while (j < m) out += DiffLine('+', b[j++])
        return out
    }
}

/* ------------------------------ 备份 ------------------------------ */

internal data class ConfigBackup(val file: File, val configName: String, val createdAt: Long, val reason: String, val size: Long)

/** Copies of replaced source text, newest first, [keep] per config. */
internal class ConfigBackups(private val root: File, private val keep: Int = 10) {
    private fun dir(configName: String) = File(root, URLEncoder.encode(configName, "UTF-8"))

    fun save(configName: String, text: String, reason: String, now: Long = System.currentTimeMillis()): ConfigBackup {
        val dir = dir(configName)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建配置备份目录")
        val safeReason = reason.filter { it.isLetterOrDigit() }.take(16).ifEmpty { "edit" }
        var stamp = now
        var target = File(dir, "$stamp-$safeReason.yaml")
        while (target.exists()) { stamp++; target = File(dir, "$stamp-$safeReason.yaml") }
        val temp = File(dir, target.name + ".new")
        try {
            FileOutputStream(temp, false).use { out -> out.write(text.toByteArray(StandardCharsets.UTF_8)); out.fd.sync() }
            if (!temp.renameTo(target)) throw IOException("无法保存配置备份")
        } catch (error: IOException) {
            temp.delete()
            throw error
        }
        prune(configName)
        return ConfigBackup(target, configName, stamp, safeReason, target.length())
    }

    fun list(configName: String? = null): List<ConfigBackup> {
        val dirs = if (configName != null) listOf(dir(configName)) else root.listFiles { file -> file.isDirectory }?.toList().orEmpty()
        return dirs.flatMap { dir ->
            val name = try { URLDecoder.decode(dir.name, "UTF-8") } catch (_: Exception) { dir.name }
            dir.listFiles { file -> file.isFile && file.name.endsWith(".yaml") }.orEmpty().mapNotNull { file ->
                val parts = file.name.removeSuffix(".yaml").split('-', limit = 2)
                val at = parts.firstOrNull()?.toLongOrNull() ?: return@mapNotNull null
                ConfigBackup(file, name, at, parts.getOrNull(1).orEmpty(), file.length())
            }
        }.sortedByDescending { it.createdAt }
    }

    fun read(backup: ConfigBackup): String = ConfigText.decode(backup.file.readBytes())

    private fun prune(configName: String) {
        list(configName).drop(keep).forEach { it.file.delete() }
    }
}

/* ------------------------------ 事务 ------------------------------ */

/** The config library as the transaction sees it (current core only). */
internal interface ConfigStore {
    fun selectedName(): String?
    fun names(): List<String>
    fun read(name: String): String
    fun write(name: String, text: String)
    /** Adds a new file (unique name) and makes it current; returns its name. */
    fun create(requestedName: String, text: String): String
    fun select(name: String)
}

internal sealed interface ConfigApplyResult {
    val message: String
    /** Saved and, when the proxy runs, hot-reloaded into the core. */
    data class Applied(override val message: String) : ConfigApplyResult
    /** Saved; network-layer changes or a busy proxy need an explicit restart. */
    data class NeedsRestart(override val message: String) : ConfigApplyResult
    /** The core refused it while running; the source was restored. */
    data class RolledBack(override val message: String) : ConfigApplyResult
}

internal class ConfigConflictException(message: String) : IOException(message)

internal class ConfigTransaction(
    private val store: ConfigStore,
    private val backups: ConfigBackups,
    private val validate: suspend (String) -> Unit,
    /** The proxy runs and nothing else is operating on it. Null: it runs but is busy. */
    private val runningIdle: () -> Boolean?,
    private val reload: suspend () -> String,
) {
    companion object {
        /** RootProxyManager.reloadCurrentConfig: the change touches routing/DNS layers. */
        fun needsRestart(error: Throwable): Boolean = error.message.orEmpty().contains("请使用「重启」")

        /** The core stopped between the state read and the reload. */
        fun notRunning(error: Throwable): Boolean = error.message.orEmpty().contains("代理未运行")
    }

    /** Overwrites [name] with [text]. [expected] guards against a concurrent edit of the file. */
    suspend fun save(name: String, text: String, expected: String? = null): ConfigApplyResult {
        val check = ConfigText.inspect(text)
        check.error?.let { throw IOException(it) }
        validate(text)
        return withContext(NonCancellable + Dispatchers.IO) {
            val old = withContext(Dispatchers.IO) { store.read(name) }
            if (expected != null && old != expected) throw ConfigConflictException("文件已在其他位置修改，未保存")
            if (old == text) return@withContext ConfigApplyResult.Applied("内容没有变化")
            withContext(Dispatchers.IO) {
                backups.save(name, old, "edit")
                store.write(name, text)
            }
            if (store.selectedName() != name) return@withContext ConfigApplyResult.Applied("已保存（不是当前配置，切换后生效）")
            applyOrRollback(
                applied = "已保存并热重载",
                rollback = {
                    if (store.read(name) == text) store.write(name, old)
                },
            )
        }
    }

    /** Adds [text] as a new config and makes it current; the previous selection returns on failure. */
    suspend fun import(requestedName: String, text: String): Pair<String, ConfigApplyResult> {
        val check = ConfigText.inspect(text)
        check.error?.let { throw IOException(it) }
        validate(text)
        return withContext(NonCancellable + Dispatchers.IO) {
            val previous = store.selectedName()
            previous?.let { name ->
                withContext(Dispatchers.IO) { backups.save(name, store.read(name), "import") }
            }
            val created = withContext(Dispatchers.IO) { store.create(requestedName, text) }
            val result = applyOrRollback(
                applied = "已导入「$created」并设为当前",
                rollback = { if (previous != null) store.select(previous) },
            )
            created to result
        }
    }

    /** Replaces the current config with a backup (it is itself backed up first). */
    suspend fun restore(backup: ConfigBackup): ConfigApplyResult {
        val text = withContext(Dispatchers.IO) { backups.read(backup) }
        if (backup.configName !in withContext(Dispatchers.IO) { store.names() }) {
            return import(backup.configName, text).second
        }
        return save(backup.configName, text)
    }

    private suspend fun applyOrRollback(applied: String, rollback: () -> Unit): ConfigApplyResult {
        val state = runningIdle()
        if (state == false) return ConfigApplyResult.Applied(applied.replace("并热重载", "") + "，下次启动代理时生效")
        if (state == null) return ConfigApplyResult.NeedsRestart(applied.replace("并热重载", "") + "；代理正忙，稍后请手动重载")
        return try {
            val note = reload()
            ConfigApplyResult.Applied(if (note.isBlank()) applied else "$applied · $note")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            if (notRunning(error)) {
                ConfigApplyResult.Applied(applied.replace("并热重载", "") + "，下次启动代理时生效")
            } else if (needsRestart(error)) {
                ConfigApplyResult.NeedsRestart(applied.replace("并热重载", "") + "；涉及网络层设置，需「重启」代理生效")
            } else {
                withContext(Dispatchers.IO) { rollback() }
                // The core already fell back to its previous runtime copy; reload once more so the
                // restored source and the running core agree again.
                try { reload() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) {}
                ConfigApplyResult.RolledBack("核心拒绝了新配置，已恢复原配置：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }
}

/** [ConfigStore] over the real library for the currently selected core. */
internal class LibraryConfigStore(context: Context) : ConfigStore {
    private val app = context.applicationContext
    private val library = ProxyConfigLibrary(app)
    private val prefs = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
    private fun core() = ProxyRuntimeProfile.load(prefs).core
    private fun entry(name: String) = library.list(core()).firstOrNull { it.name == name } ?: throw IOException("配置不存在：$name")

    override fun selectedName(): String? = library.selected(core())?.name
    override fun names(): List<String> = library.list(core()).map { it.name }
    override fun read(name: String): String = library.read(entry(name))
    override fun write(name: String, text: String) = library.write(entry(name), text)
    override fun create(requestedName: String, text: String): String =
        library.importConfig(core(), requestedName, text.toByteArray(StandardCharsets.UTF_8).inputStream()).name
    override fun select(name: String) = library.select(core(), name)

    companion object {
        fun backups(context: Context) = ConfigBackups(File(context.applicationContext.filesDir, "hetu-config-backups"))

        /** The transaction the app uses: core validation, hot reload through the controller. */
        fun transaction(context: Context, vm: HetuViewModel): ConfigTransaction {
            val app = context.applicationContext
            return ConfigTransaction(
                store = LibraryConfigStore(app),
                backups = backups(app),
                validate = { text -> vm.controller.validateConfigText(text) },
                runningIdle = { if (!vm.state.running) false else if (vm.operation != null) null else true },
                reload = { vm.controller.reload() },
            )
        }
    }
}
