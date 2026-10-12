package io.github.xgl34222220.hetu

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.representer.Representer
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * JavaScript override scripts ("覆写脚本"): before a start, every enabled script runs in order as
 * `main(config, profile)` and returns the configuration object the next one receives. Pure JVM
 * (no Android API) so ordering, fallback and deadlines are host tested with a fake engine; the
 * shipped engine is [WebViewOverrideEngine] (Android System WebView, a platform component).
 */
internal data class OverrideScript(val id: String, val name: String, val enabled: Boolean, val source: String = "")

internal class OverrideScriptException(message: String) : IOException(message)

/** One JS evaluation. Implementations must honour [timeoutMs]; the pipeline also enforces it. */
internal interface OverrideScriptEngine : AutoCloseable {
    /** Runs `main(config, profile)` of [source]; returns the result object as JSON text. */
    @Throws(Exception::class)
    fun evaluate(name: String, source: String, configJson: String, profileJson: String, timeoutMs: Long, maxResultChars: Int): Evaluation
    override fun close() {}

    data class Evaluation(val json: String, val logs: List<String> = emptyList())
}

internal object OverrideScriptLimits {
    const val SCRIPT_TIMEOUT_MS = 3_000L
    /** Engine start (WebView creation) plus marshalling, on top of the script's own budget. */
    const val ENGINE_GRACE_MS = 6_000L
    const val MAX_SCRIPT_CHARS = 64 * 1024
    const val MAX_SCRIPTS = 32
    /** Input and result size cap (the source config itself is limited to 4 MiB). */
    const val MAX_CONFIG_CHARS = 8 * 1024 * 1024
    const val MAX_LOG_LINES = 200
}

internal enum class OverrideFormat(val id: String) { YAML("yaml"), JSON("json") }

internal data class OverrideStepResult(val id: String, val name: String, val ok: Boolean, val millis: Long, val detail: String, val logs: List<String> = emptyList())

internal data class OverrideOutcome(
    /** The text the start continues with: the scripts' result, or the unmodified source on failure. */
    val text: String,
    val applied: Boolean,
    val fallback: Boolean,
    val steps: List<OverrideStepResult>,
) {
    val enabled get() = steps.size
    val failed get() = steps.count { !it.ok }
    /** Shown on the start result and in the scripts page. Empty when nothing ran. */
    val notice: String get() = when {
        steps.isEmpty() -> ""
        fallback -> {
            val bad = steps.firstOrNull { !it.ok }
            "覆写脚本失败，已使用未修改的配置启动：" + (bad?.let { it.name + "：" + it.detail } ?: "未知错误")
        }
        else -> "覆写脚本：已启用 $enabled 个，已应用 ${steps.count { it.ok }} 个"
    }
}

internal object OverrideScriptPipeline {
    /**
     * Applies [scripts] (already filtered to the enabled ones, in order) to [source].
     * Any failure — syntax error, exception, timeout, wrong result type or size — discards every
     * script's change: the start continues with the unmodified [source] and [OverrideOutcome.fallback].
     * An empty [source] is an empty configuration (no profile selected; the scripts generate it).
     */
    fun apply(
        format: OverrideFormat,
        source: String,
        profile: Map<String, Any?>?,
        scripts: List<OverrideScript>,
        engine: OverrideScriptEngine,
        timeoutMs: Long = OverrideScriptLimits.SCRIPT_TIMEOUT_MS,
        graceMs: Long = OverrideScriptLimits.ENGINE_GRACE_MS,
        clock: () -> Long = System::currentTimeMillis,
    ): OverrideOutcome {
        if (scripts.isEmpty()) return OverrideOutcome(source, applied = false, fallback = false, steps = emptyList())
        val steps = ArrayList<OverrideStepResult>()
        fun fail(script: OverrideScript, started: Long, detail: String, logs: List<String> = emptyList()): OverrideOutcome {
            steps += OverrideStepResult(script.id, script.name, false, clock() - started, detail, logs)
            return OverrideOutcome(source, applied = false, fallback = true, steps = steps)
        }
        var configJson = try {
            toJson(format, source)
        } catch (failure: IOException) {
            return fail(scripts.first(), clock(), "配置无法转换为对象：" + (failure.message ?: "解析失败"))
        }
        val profileJson = if (profile == null) "null" else CoreJson.write(profile).trim()
        // One worker thread owns the engine; a stuck engine is closed and the start goes on.
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "hetu-override-js").apply { isDaemon = true } }
        try {
            for (script in scripts) {
                val started = clock()
                if (script.source.length > OverrideScriptLimits.MAX_SCRIPT_CHARS) return fail(script, started, "脚本超过 64 KiB")
                if (configJson.length > OverrideScriptLimits.MAX_CONFIG_CHARS) return fail(script, started, "配置过大（超过 8 MiB）")
                val input = configJson
                val future = executor.submit(Callable {
                    engine.evaluate(script.name, script.source, input, profileJson, timeoutMs, OverrideScriptLimits.MAX_CONFIG_CHARS)
                })
                val evaluation = try {
                    future.get(timeoutMs + graceMs, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    future.cancel(true)
                    runCatching { engine.close() }
                    return fail(script, started, "脚本执行超时（${timeoutMs} ms），已终止")
                } catch (failure: ExecutionException) {
                    val cause = failure.cause ?: failure
                    return fail(script, started, cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName)
                } catch (interrupted: InterruptedException) {
                    future.cancel(true)
                    Thread.currentThread().interrupt()
                    throw IOException("启动已取消", interrupted)
                }
                val logs = evaluation.logs.take(OverrideScriptLimits.MAX_LOG_LINES)
                if (evaluation.json.length > OverrideScriptLimits.MAX_CONFIG_CHARS) return fail(script, started, "返回的配置过大（超过 8 MiB）", logs)
                val result = try { CoreJson.parse(evaluation.json) } catch (failure: IOException) {
                    return fail(script, started, "返回值不是有效 JSON：" + (failure.message ?: ""), logs)
                }
                if (result !is Map<*, *>) return fail(script, started, "main 必须返回配置对象（键值映射）", logs)
                configJson = evaluation.json
                steps += OverrideStepResult(script.id, script.name, true, clock() - started, "已应用", logs)
            }
            val text = try { fromJson(format, configJson) } catch (failure: IOException) {
                steps[steps.lastIndex] = steps.last().copy(ok = false, detail = "结果无法写回 ${format.id.uppercase()}：" + (failure.message ?: ""))
                return OverrideOutcome(source, applied = false, fallback = true, steps = steps)
            }
            return OverrideOutcome(text, applied = true, fallback = false, steps = steps)
        } finally {
            executor.shutdownNow()
        }
    }

    // ------------------------------------------------------------------ YAML/JSON <-> object

    fun toJson(format: OverrideFormat, source: String): String {
        if (source.isBlank()) return "{}"
        val root: Any? = when (format) {
            OverrideFormat.JSON -> CoreJson.parse(source)
            OverrideFormat.YAML -> try {
                val options = LoaderOptions().apply {
                    codePointLimit = 16 * 1024 * 1024
                    maxAliasesForCollections = 4096
                    nestingDepthLimit = 60
                }
                Yaml(SafeConstructor(options)).load<Any?>(StringReader(source))
            } catch (invalid: YAMLException) {
                throw IOException("YAML 语法或结构解析失败")
            }
        }
        if (root == null) return "{}"
        if (root !is Map<*, *>) throw IOException("配置根节点应为键值映射")
        return CoreJson.write(normalize(root, 0))
    }

    fun fromJson(format: OverrideFormat, json: String): String {
        val root = CoreJson.parse(json) as? Map<*, *> ?: throw IOException("配置根节点应为键值映射")
        return when (format) {
            OverrideFormat.JSON -> CoreJson.write(root)
            OverrideFormat.YAML -> {
                val options = DumperOptions().apply {
                    defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
                    isAllowUnicode = true
                    splitLines = false
                    width = Int.MAX_VALUE
                    indent = 2
                    indicatorIndent = 0
                }
                Yaml(Representer(options), options).dump(root)
            }
        }
    }

    /** YAML scalars JSON has no type for (timestamps, binary) become strings; keys become strings. */
    private fun normalize(value: Any?, depth: Int): Any? {
        if (depth > CoreJson.MAX_DEPTH) throw IOException("配置嵌套过深")
        return when (value) {
            null, is String, is Boolean -> value
            is Number -> if (value is Double && (value.isNaN() || value.isInfinite())) value.toString()
                else if (value is Float && (value.isNaN() || value.isInfinite())) value.toString() else value
            is Map<*, *> -> LinkedHashMap<String, Any?>().also { out -> value.forEach { (k, v) -> out[k.toString()] = normalize(v, depth + 1) } }
            is Collection<*> -> value.map { normalize(it, depth + 1) }
            is ByteArray -> String(value, StandardCharsets.UTF_8)
            is java.util.Date -> java.time.format.DateTimeFormatter.ISO_INSTANT.format(value.toInstant())
            else -> value.toString()
        }
    }
}

/**
 * Scripts live in the App's private files (`files/override-scripts`): one `<id>.js` per script and
 * `scripts.json` holding name, enabled flag and order. Nothing here needs Root.
 */
internal class OverrideScriptStore(val dir: File) {
    private val manifest get() = File(dir, "scripts.json")
    private val lastRun get() = File(dir, "last-run.json")

    @Synchronized fun list(): List<OverrideScript> {
        val file = manifest
        if (!file.isFile) return emptyList()
        val root = runCatching { CoreJson.parse(file.readText()) }.getOrNull() as? Map<*, *> ?: return emptyList()
        val items = root["scripts"] as? List<*> ?: return emptyList()
        return items.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val id = (m["id"] as? String)?.takeIf(::validId) ?: return@mapNotNull null
            OverrideScript(id, (m["name"] as? String)?.ifBlank { null } ?: id, m["enabled"] != false)
        }.distinctBy { it.id }
    }

    @Synchronized fun read(id: String): String {
        require(validId(id)) { "脚本标识无效" }
        val file = File(dir, "$id.js")
        return if (file.isFile) file.readText() else ""
    }

    /** Enabled scripts in order, with their sources. */
    @Synchronized fun enabled(): List<OverrideScript> = list().filter { it.enabled }.map { it.copy(source = read(it.id)) }

    @Synchronized fun hasEnabled(): Boolean = list().any { it.enabled }

    @Synchronized fun add(name: String, source: String, enabled: Boolean = true): OverrideScript {
        val scripts = list()
        require(scripts.size < OverrideScriptLimits.MAX_SCRIPTS) { "最多 ${OverrideScriptLimits.MAX_SCRIPTS} 个覆写脚本" }
        checkSource(source)
        var id: String
        do { id = "s" + java.lang.Long.toHexString(System.nanoTime() xor (Math.random() * Long.MAX_VALUE).toLong()).takeLast(10) } while (scripts.any { it.id == id })
        val script = OverrideScript(id, cleanName(name), enabled)
        writeAtomic(File(dir, "$id.js"), source)
        save(scripts + script)
        return script
    }

    @Synchronized fun update(id: String, name: String, source: String) {
        checkSource(source)
        val scripts = list()
        require(scripts.any { it.id == id }) { "脚本不存在" }
        writeAtomic(File(dir, "$id.js"), source)
        save(scripts.map { if (it.id == id) it.copy(name = cleanName(name)) else it })
    }

    @Synchronized fun setEnabled(id: String, enabled: Boolean) = save(list().map { if (it.id == id) it.copy(enabled = enabled) else it })

    /** Moves the script by [delta] positions (negative = earlier); clamped to the list. */
    @Synchronized fun move(id: String, delta: Int) {
        val scripts = list().toMutableList()
        val from = scripts.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, scripts.lastIndex)
        if (to == from) return
        scripts.add(to, scripts.removeAt(from))
        save(scripts)
    }

    @Synchronized fun delete(id: String) {
        require(validId(id)) { "脚本标识无效" }
        save(list().filterNot { it.id == id })
        File(dir, "$id.js").delete()
    }

    @Synchronized fun recordRun(outcome: OverrideOutcome, at: Long = System.currentTimeMillis()) {
        if (outcome.steps.isEmpty()) return
        val steps = outcome.steps.map { linkedMapOf<String, Any?>("id" to it.id, "name" to it.name, "ok" to it.ok, "millis" to it.millis, "detail" to it.detail, "logs" to it.logs) }
        writeAtomic(lastRun, CoreJson.write(linkedMapOf("at" to at, "fallback" to outcome.fallback, "notice" to outcome.notice, "steps" to steps)))
    }

    /** The last start's outcome: (time, notice, fallback, per-script results). */
    @Synchronized fun lastRun(): LastRun? {
        val root = runCatching { CoreJson.parse(lastRun.readText()) }.getOrNull() as? Map<*, *> ?: return null
        val steps = (root["steps"] as? List<*>).orEmpty().mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            OverrideStepResult(m["id"] as? String ?: "", m["name"] as? String ?: "", m["ok"] == true,
                (m["millis"] as? Number)?.toLong() ?: 0L, m["detail"] as? String ?: "",
                (m["logs"] as? List<*>).orEmpty().map { it.toString() })
        }
        return LastRun((root["at"] as? Number)?.toLong() ?: 0L, root["notice"] as? String ?: "", root["fallback"] == true, steps)
    }

    data class LastRun(val at: Long, val notice: String, val fallback: Boolean, val steps: List<OverrideStepResult>)

    private fun save(scripts: List<OverrideScript>) {
        val items = scripts.map { linkedMapOf<String, Any?>("id" to it.id, "name" to it.name, "enabled" to it.enabled) }
        writeAtomic(manifest, CoreJson.write(linkedMapOf("schema" to 1, "scripts" to items)))
    }

    private fun writeAtomic(target: File, text: String) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建覆写脚本目录")
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) throw IOException("覆写脚本保存失败") }
    }

    companion object {
        fun validId(id: String) = id.matches(Regex("[a-z0-9]{1,24}"))
        fun cleanName(name: String): String = name.replace(Regex("[\\r\\n\\t]"), " ").trim().take(64).ifBlank { "未命名脚本" }
        fun checkSource(source: String) {
            require(source.length <= OverrideScriptLimits.MAX_SCRIPT_CHARS) { "脚本最多 64 KiB" }
        }
    }
}

/** Starter scripts offered when adding a new one. Original Hetu examples. */
internal object OverrideScriptExamples {
    data class Example(val title: String, val source: String)

    val BLANK = Example("空白脚本", """
        // 覆写脚本：启动前按顺序执行，返回修改后的配置对象。
        // config  当前配置（Mihomo YAML 或 sing-box JSON 转成的对象）
        // profile 当前配置文件信息 { name, core, format }；未选择配置文件时为 null
        function main(config, profile) {
          return config;
        }
    """.trimIndent() + "\n")

    val ADD_RULE = Example("添加规则", """
        // 在规则最前面加入自定义规则（Mihomo）。
        function main(config, profile) {
          const mine = [
            "DOMAIN-SUFFIX,example.com,DIRECT",
            "DOMAIN-KEYWORD,tracker,REJECT",
          ];
          config.rules = mine.concat(Array.isArray(config.rules) ? config.rules : []);
          return config;
        }
    """.trimIndent() + "\n")

    val SET_DNS = Example("设置 DNS", """
        // 统一 DNS（Mihomo）：fake-ip 模式，国内/国外分别解析。
        function main(config, profile) {
          config.dns = Object.assign({}, config.dns, {
            enable: true,
            ipv6: false,
            "enhanced-mode": "fake-ip",
            "default-nameserver": ["223.5.5.5", "119.29.29.29"],
            nameserver: ["https://dns.alidns.com/dns-query", "https://doh.pub/dns-query"],
            fallback: ["https://1.1.1.1/dns-query", "https://dns.google/dns-query"],
          });
          return config;
        }
    """.trimIndent() + "\n")

    val MERGE_PROXIES = Example("合并节点", """
        // 追加自建节点并放进新的选择组（Mihomo）；同名节点不会重复添加。
        function main(config, profile) {
          const extra = [
            { name: "自建-SS", type: "ss", server: "203.0.113.10", port: 8388, cipher: "aes-128-gcm", password: "change-me" },
          ];
          const proxies = Array.isArray(config.proxies) ? config.proxies : [];
          const names = new Set(proxies.map(p => p.name));
          for (const p of extra) if (!names.has(p.name)) proxies.push(p);
          config.proxies = proxies;
          const groups = Array.isArray(config["proxy-groups"]) ? config["proxy-groups"] : [];
          if (!groups.some(g => g.name === "自建")) {
            groups.unshift({ name: "自建", type: "select", proxies: extra.map(p => p.name).concat(["DIRECT"]) });
          }
          config["proxy-groups"] = groups;
          return config;
        }
    """.trimIndent() + "\n")

    val SING_BOX_RULE = Example("sing-box 路由规则", """
        // sing-box：在 route.rules 最前面加入直连规则。
        function main(config, profile) {
          if (!profile || profile.format !== "json") return config;
          config.route = config.route || {};
          const rules = Array.isArray(config.route.rules) ? config.route.rules : [];
          rules.unshift({ domain_suffix: ["example.com"], outbound: "direct" });
          config.route.rules = rules;
          return config;
        }
    """.trimIndent() + "\n")

    val ALL = listOf(BLANK, ADD_RULE, SET_DNS, MERGE_PROXIES, SING_BOX_RULE)
}
