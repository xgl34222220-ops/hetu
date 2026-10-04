package io.github.xgl34222220.hetu.tools

import java.util.Locale

/* ------------------------------------------------------------------ */
/*  State model. Pure Kotlin: no Compose or Android types in here.     */
/*  Covers 03A 工具上册 pages 1–25 (prototype states C01–C25).          */
/* ------------------------------------------------------------------ */

/* ---------------------------- 工具 (tab root) ---------------------------- */

/**
 * The 14 entries of the tools tab.
 *
 * @param summary line under the title on the root list (concept page 1).
 * @param brief shorter line shown in search results (concept page 3).
 * @param keywords extra words the search matches but never shows.
 */
internal enum class ToolsEntry(val title: String, val summary: String, val brief: String, val keywords: String) {
    Files("文件管理", "查看与处理应用文件", "文件与目录", "运行文件 目录 file"),
    Scripts("脚本", "运行与管理服务脚本", "启动与停止脚本", "hook shell sh"),
    Logs("日志文件", "查看与导出运行日志", "运行日志", "log 排查"),
    Apps("应用管理", "管理应用代理与直连规则", "代理与直连", "网络 黑名单 白名单 app"),
    NetMatch("网络匹配", "设置网络匹配后要执行的操作", "自动切换", "wi-fi wifi ssid bssid 移动数据"),
    Share("共享网络", "管理共享网络转发相关设置", "热点与代理", "热点 usb 局域网"),
    Bypass("绕过规则", "管理本地 CIDR 与接口规则", "网段与接口", "网络 排除 直连"),
    Configs("配置管理", "导入与编辑配置文件", "配置与订阅", "yaml 订阅 proxy-providers"),
    SubStore("Sub-Store", "订阅处理", "订阅处理", "substore"),
    CnIp("CNIP", "规则集管理", "中国大陆 IP 直连", "国内 分流 geoip"),
    Cores("核心管理", "下载与更新", "下载与更新", "内核 mihomo xray sing-box"),
    Adblock("广告过滤", "规则与屏蔽", "规则与屏蔽", "去广告 adguard 拦截"),
    Diag("诊断工具", "网络与环境", "网络与环境", "预检 恢复网络 排查"),
    WebUi("WebUI", "外部面板", "外部面板", "zashboard 面板 dashboard"),
}

/** One card on the root list. [title] is null for the concept's untitled stacks. */
internal data class ToolsGroupSpec(val title: String?, val entries: List<ToolsEntry>)

internal object ToolsCatalog {
    /** Concept layout (03A page 1): six untitled cards. This is the default. */
    val concept: List<ToolsGroupSpec> = listOf(
        ToolsGroupSpec(null, listOf(ToolsEntry.Files, ToolsEntry.Scripts)),
        ToolsGroupSpec(null, listOf(ToolsEntry.Logs)),
        ToolsGroupSpec(null, listOf(ToolsEntry.Apps)),
        ToolsGroupSpec(null, listOf(ToolsEntry.NetMatch, ToolsEntry.Share, ToolsEntry.Bypass)),
        ToolsGroupSpec(null, listOf(ToolsEntry.Configs, ToolsEntry.SubStore, ToolsEntry.CnIp)),
        ToolsGroupSpec(null, listOf(ToolsEntry.Cores, ToolsEntry.Adblock, ToolsEntry.Diag, ToolsEntry.WebUi)),
    )

    /** Prototype layout (artifact «工具»): the same 14 entries in five titled groups. */
    val titled: List<ToolsGroupSpec> = listOf(
        ToolsGroupSpec("配置与订阅", listOf(ToolsEntry.Configs, ToolsEntry.SubStore)),
        ToolsGroupSpec("分流与过滤", listOf(ToolsEntry.Apps, ToolsEntry.Adblock, ToolsEntry.Bypass, ToolsEntry.CnIp)),
        ToolsGroupSpec("网络", listOf(ToolsEntry.NetMatch, ToolsEntry.Share)),
        ToolsGroupSpec("核心与面板", listOf(ToolsEntry.Cores, ToolsEntry.WebUi)),
        ToolsGroupSpec("文件与维护", listOf(ToolsEntry.Files, ToolsEntry.Scripts, ToolsEntry.Logs, ToolsEntry.Diag)),
    )

    fun matches(entry: ToolsEntry, query: String): Boolean {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return true
        return listOf(entry.title, entry.summary, entry.brief, entry.keywords).any { it.lowercase(Locale.ROOT).contains(q) }
    }

    /** Keeps the cards of [groups] but only the rows that match; cards left empty are dropped. */
    fun search(groups: List<ToolsGroupSpec>, query: String): List<ToolsGroupSpec> =
        groups.mapNotNull { group ->
            val hits = group.entries.filter { matches(it, query) }
            if (hits.isEmpty()) null else ToolsGroupSpec(null, hits)
        }

    /** First case-insensitive occurrence of [query] in [text], for highlighting; null when absent. */
    fun highlight(text: String, query: String): IntRange? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val at = text.lowercase(Locale.ROOT).indexOf(q.lowercase(Locale.ROOT))
        return if (at < 0) null else at until at + q.length
    }
}

internal data class ToolsRootState(
    val searching: Boolean = false,
    val query: String = "",
    val groups: List<ToolsGroupSpec> = ToolsCatalog.concept,
) {
    /** Non-null while a query is typed: the filtered cards (possibly empty → empty state). */
    val results: List<ToolsGroupSpec>? get() = if (searching && query.isNotBlank()) ToolsCatalog.search(groups, query) else null
}

/* ---------------------------- 配置与订阅 ---------------------------- */

internal enum class ToolsConfigKind { Local, Bundled }

internal data class ToolsConfig(val name: String, val kind: ToolsConfigKind = ToolsConfigKind.Local, val current: Boolean = false) {
    /** Second line of a config row (concept page 4). */
    val caption: String
        get() = when {
            current -> "当前配置"
            kind == ToolsConfigKind.Bundled -> "本地配置 / 内置模板 · 需要填写订阅"
            else -> "本地配置"
        }
}

/** Entries of the «⋯» menu; which ones appear depends on the row (concept pages 6–8). */
internal enum class ToolsConfigMenuItem(val label: String, val danger: Boolean = false) {
    SetCurrent("设为当前配置"), Export("导出配置"), Rename("重命名"), Delete("删除配置", danger = true)
}

internal fun ToolsConfig.menuItems(): List<ToolsConfigMenuItem> = buildList {
    if (!current) add(ToolsConfigMenuItem.SetCurrent)
    add(ToolsConfigMenuItem.Export)
    if (kind != ToolsConfigKind.Bundled) {
        add(ToolsConfigMenuItem.Rename)
        add(ToolsConfigMenuItem.Delete)
    }
}

/** A `proxy-providers` entry of the current config. [placeholder]: the bundled template's unfilled URL. */
internal data class ToolsSubscription(val name: String, val url: String, val placeholder: Boolean = false)

internal sealed interface ToolsLoad {
    data object Loading : ToolsLoad
    data object Ready : ToolsLoad
    data class Failed(val message: String) : ToolsLoad
}

/** What the host returns for the 配置与订阅 page. */
internal data class ToolsConfigSnapshot(
    val configs: List<ToolsConfig> = emptyList(),
    val subscriptions: List<ToolsSubscription> = emptyList(),
)

internal data class ToolsConfigState(
    val load: ToolsLoad = ToolsLoad.Loading,
    val configs: List<ToolsConfig> = emptyList(),
    val subscriptions: List<ToolsSubscription> = emptyList(),
    /** A select / rename / delete is in flight; rows stop reacting until it lands. */
    val busy: Boolean = false,
) {
    val current: ToolsConfig? get() = configs.firstOrNull { it.current }
}

/** Everything that can float above the 配置与订阅 page. At most one at a time. */
internal sealed interface ToolsConfigOverlay {
    /** Page 6 / 7 / 8. */
    data class Menu(val config: String) : ToolsConfigOverlay

    /** Page 9. */
    data class Rename(val config: String, val draft: String, val error: String? = null, val saving: Boolean = false) : ToolsConfigOverlay

    /** Page 10. [isCurrent] adds the “会切换回内置模板” line. */
    data class DeleteConfig(val config: String, val isCurrent: Boolean) : ToolsConfigOverlay

    /** Page 11. */
    data class DeleteSubscription(val config: String, val subscription: String) : ToolsConfigOverlay
}

/* ---------------------------- 添加 / 编辑订阅 ---------------------------- */

internal sealed interface ToolsSubscriptionMode {
    data object Add : ToolsSubscriptionMode

    /** The name is read-only while editing: policy groups reference it. */
    data class Edit(val name: String) : ToolsSubscriptionMode
}

internal data class ToolsSubscriptionForm(
    val mode: ToolsSubscriptionMode = ToolsSubscriptionMode.Add,
    val name: String = "",
    val url: String = "",
    val nameError: String? = null,
    val urlError: String? = null,
    val saving: Boolean = false,
    /** Values the form was opened with; used by the leave guard. */
    val initialName: String = "",
    val initialUrl: String = "",
) {
    val dirty: Boolean get() = name != initialName || url != initialUrl

    companion object {
        fun add(): ToolsSubscriptionForm = ToolsSubscriptionForm()
        fun edit(item: ToolsSubscription): ToolsSubscriptionForm {
            val url = if (item.placeholder) "" else item.url
            return ToolsSubscriptionForm(ToolsSubscriptionMode.Edit(item.name), item.name, url, initialName = item.name, initialUrl = url)
        }
    }
}

/* ---------------------------- 导入配置 ---------------------------- */

internal enum class ToolsImportTab(val label: String) { File("从文件导入"), Link("从链接导入") }

/** A document the user picked in the system file picker. [token] is the host's handle (a Uri). */
internal class ToolsPickedFile(val name: String, val detail: String = "", val token: Any? = null)

internal data class ToolsImportForm(
    val tab: ToolsImportTab = ToolsImportTab.Link,
    val url: String = "",
    val name: String = "",
    val urlError: String? = null,
    val importing: Boolean = false,
) {
    fun dirty(picked: ToolsPickedFile?): Boolean = url.isNotEmpty() || name.isNotEmpty() || picked != null
    fun canImport(picked: ToolsPickedFile?): Boolean = !importing && (tab == ToolsImportTab.Link || picked != null)
}

/* ---------------------------- 配置编辑 ---------------------------- */

/** The source text as it was read, plus the config it came from; handed back on save. */
internal data class ToolsConfigDocument(val name: String, val text: String)

internal sealed interface ToolsSaveResult {
    data object Saved : ToolsSaveResult

    /** Validation or write failure; shown in the red banner (page 15). */
    data class Invalid(val message: String) : ToolsSaveResult

    /** Another config was selected while editing (page 14). */
    data class SelectionChanged(val current: String) : ToolsSaveResult

    /** The source file changed on disk while editing (page 17). */
    data object SourceChanged : ToolsSaveResult
}

internal data class ToolsEditorState(
    val load: ToolsLoad = ToolsLoad.Loading,
    val fileName: String = "",
    val dirty: Boolean = false,
    /** Red banner under the toolbar, e.g. “配置无效：mixed-port 必须为数字”. */
    val banner: String? = null,
    /** 1-based line the banner refers to, tinted in the editor. */
    val errorLine: Int? = null,
    val saving: Boolean = false,
    val validating: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
) {
    val ready: Boolean get() = load is ToolsLoad.Ready
    val failed: Boolean get() = load is ToolsLoad.Failed
    val title: String get() = if (failed || fileName.isBlank()) "读取配置" else fileName
    val caption: String get() = if (ready && dirty) "未保存 · 草稿仅保留在本页" else "源配置 · 保存前自动校验"
}

internal data class ToolsOutlineItem(val title: String, val line: Int, val level: Int = 0)

internal sealed interface ToolsEditorOverlay {
    /** Page 13. */
    data class Outline(val items: List<ToolsOutlineItem>) : ToolsEditorOverlay

    /** Page 14. */
    data class SaveConflict(val current: String) : ToolsEditorOverlay

    /** Page 17. */
    data object SourceChanged : ToolsEditorOverlay

    /** Page 18. */
    data object ConfirmReload : ToolsEditorOverlay

    /** Page 19. */
    data object ConfirmDiscard : ToolsEditorOverlay
}

/* ------------------------------------------------------------------ */
/*  Validation                                                          */
/* ------------------------------------------------------------------ */

internal object ToolsRules {
    const val UrlError = "请输入有效的 http/https 链接"
    private val httpUrl = Regex("^https?://[^\\s/?#]+\\S*$", RegexOption.IGNORE_CASE)

    fun isHttpUrl(raw: String): Boolean = httpUrl.matches(raw.trim())

    fun urlError(raw: String): String? = if (isHttpUrl(raw)) null else UrlError

    fun subscriptionNameError(name: String, existing: Collection<String>): String? {
        val value = name.trim()
        return when {
            value.isEmpty() -> "名称不能为空"
            existing.any { it == value } -> "已存在同名订阅"
            else -> null
        }
    }

    /**
     * Name a link import is stored under: the typed [name], else the last path segment of
     * [url], else `config.yaml`; `.yaml` is appended when the extension is missing.
     */
    fun importName(url: String, name: String): String {
        val typed = name.trim()
        val path = url.trim().substringBefore('#').substringBefore('?').substringAfter("://", "").substringAfter('/', "")
        val fromUrl = path.trimEnd('/').substringAfterLast('/')
        val base = (if (typed.isNotEmpty()) typed else fromUrl).replace('/', '_').replace('\\', '_').ifEmpty { "config.yaml" }
        return if (base.endsWith(".yaml", ignoreCase = true) || base.endsWith(".yml", ignoreCase = true)) base else "$base.yaml"
    }

    /** Mirrors `ProxyConfigLibrary.safeName` plus the YAML extension rule of the config library. */
    fun configNameError(name: String, renaming: String, existing: Collection<String>): String? {
        val value = name.trim()
        return when {
            value.isEmpty() -> "名称不能为空"
            !value.endsWith(".yaml", ignoreCase = true) && !value.endsWith(".yml", ignoreCase = true) -> "名称需以 .yaml 或 .yml 结尾"
            value.contains('/') || value.contains('\\') -> "名称不能包含 / 或 \\"
            value.length > 120 -> "名称过长（最多 120 个字符）"
            value != renaming && existing.any { it == value } -> "已存在同名配置"
            else -> null
        }
    }
}

/* ------------------------------------------------------------------ */
/*  YAML helpers (highlighting, outline, quick lint)                    */
/* ------------------------------------------------------------------ */

internal enum class ToolsYamlTokenKind { Key, Bool, Number, Comment }

/** Half-open character range `[start, end)` in the whole text. */
internal data class ToolsYamlToken(val start: Int, val end: Int, val kind: ToolsYamlTokenKind)

internal data class ToolsYamlIssue(val line: Int, val message: String)

internal object ToolsYaml {
    /** Accessory keys under the editor; the first one inserts a two-space indent. */
    val symbols: List<String> = listOf("  ", ":", "-", "[", "]", "{", "}", "#", "'")

    fun symbolLabel(symbol: String): String = if (symbol == "  ") "␣␣" else symbol

    /** What a key actually inserts: `:`, `-` and `#` bring their trailing space. */
    fun symbolInsertion(symbol: String): String = when (symbol) {
        ":" -> ": "
        "-" -> "- "
        "#" -> "# "
        else -> symbol
    }

    private val mapping = Regex("^(\\s*(?:-\\s+)?)([\\w\\u4e00-\\u9fff.-]+)(:)(?:(\\s+)(.*))?$")
    private val bool = Regex("^(?:true|false|null)$")
    private val number = Regex("^-?\\d[\\d.]*$")

    /** Tokens for syntax colouring: keys (with their colon), booleans, numbers and comment lines. */
    fun tokens(text: String): List<ToolsYamlToken> {
        val out = ArrayList<ToolsYamlToken>()
        var offset = 0
        for (line in text.split('\n')) {
            if (line.trimStart().startsWith("#")) {
                out += ToolsYamlToken(offset, offset + line.length, ToolsYamlTokenKind.Comment)
            } else {
                val match = mapping.find(line)
                if (match != null) {
                    val keyStart = offset + match.groupValues[1].length
                    val keyEnd = keyStart + match.groupValues[2].length + 1
                    out += ToolsYamlToken(keyStart, keyEnd, ToolsYamlTokenKind.Key)
                    val value = match.groupValues[5].trimEnd()
                    if (value.isNotEmpty()) {
                        val valueStart = keyEnd + match.groupValues[4].length
                        when {
                            bool.matches(value) -> out += ToolsYamlToken(valueStart, valueStart + value.length, ToolsYamlTokenKind.Bool)
                            number.matches(value) -> out += ToolsYamlToken(valueStart, valueStart + value.length, ToolsYamlTokenKind.Number)
                        }
                    }
                }
            }
            offset += line.length + 1
        }
        return out
    }

    private val topKey = Regex("^([\\w-]+):")
    private val providerKey = Regex("^ {2}([\\w\\u4e00-\\u9fff.-]+):")
    private val groupName = Regex("^ {2}- name:\\s*(.+)$")

    /** Top-level keys, plus the names under `proxy-providers` and `proxy-groups` one level in. */
    fun outline(text: String): List<ToolsOutlineItem> {
        val out = ArrayList<ToolsOutlineItem>()
        var section = ""
        text.split('\n').forEachIndexed { index, line ->
            val top = topKey.find(line)
            when {
                top != null -> { section = top.groupValues[1]; out += ToolsOutlineItem(section, index + 1, 0) }
                section == "proxy-providers" -> providerKey.find(line)?.let { out += ToolsOutlineItem(it.groupValues[1], index + 1, 1) }
                section == "proxy-groups" -> groupName.find(line)?.let { out += ToolsOutlineItem(it.groupValues[1].trim().trim('"', '\''), index + 1, 1) }
            }
        }
        return out
    }

    private val portKey = Regex("^(mixed-port|port|socks-port|redir-port|tproxy-port):\\s*(.*)$")

    /**
     * Cheap checks that run before the host's real validation, so obvious slips are reported
     * instantly and with a line number: tabs in indentation and non-numeric port values.
     */
    fun lint(text: String): ToolsYamlIssue? {
        if (text.isBlank()) return ToolsYamlIssue(1, "配置不能为空")
        text.split('\n').forEachIndexed { index, raw ->
            if (raw.isBlank()) return@forEachIndexed
            if ('\t' in raw.takeWhile { it == ' ' || it == '\t' }) return ToolsYamlIssue(index + 1, "缩进包含 Tab，请改用空格")
            val port = portKey.find(raw)
            if (port != null) {
                val value = port.groupValues[2].substringBefore('#').trim()
                if (value.isNotEmpty() && value.toIntOrNull() == null) return ToolsYamlIssue(index + 1, "${port.groupValues[1]} 必须为数字")
            }
        }
        return null
    }

    private val errorLinePatterns = listOf(Regex("(?i)line[:=]?\\s*(\\d+)"), Regex("第\\s*(\\d+)\\s*行"))

    /** Pulls a 1-based line number out of a validator message such as “yaml: line 18: …”. */
    fun errorLine(message: String): Int? =
        errorLinePatterns.firstNotNullOfOrNull { it.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull() }?.takeIf { it > 0 }

    fun lineCount(text: String): Int = text.count { it == '\n' } + 1

    /** Character offset of the first column of the 1-based [line], clamped to the text. */
    fun lineStart(text: String, line: Int): Int {
        var remaining = line.coerceAtLeast(1) - 1
        var index = 0
        while (remaining > 0) {
            val next = text.indexOf('\n', index)
            if (next < 0) return index
            index = next + 1
            remaining--
        }
        return index
    }

    fun gutter(lines: Int): String = buildString {
        for (line in 1..lines.coerceAtLeast(1)) {
            if (line > 1) append('\n')
            append(line)
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Callbacks                                                           */
/* ------------------------------------------------------------------ */

/**
 * Everything the tools module asks its host to do. Suspend lambdas report failure by throwing;
 * the message of the exception is shown to the user. Defaults are no-ops so previews need nothing.
 */
internal class ToolsActions(
    /** Any root entry other than 配置管理 (those pages belong to Part 2 / 03B). */
    val onOpenEntry: (ToolsEntry) -> Unit = {},
    val loadConfigs: suspend () -> ToolsConfigSnapshot = { ToolsConfigSnapshot() },
    val selectConfig: suspend (name: String) -> Unit = {},
    /** Starts the system “save as” flow for [name]; the host reports the outcome itself. */
    val exportConfig: (name: String) -> Unit = {},
    val renameConfig: suspend (from: String, to: String) -> Unit = { _, _ -> },
    val deleteConfig: suspend (name: String) -> Unit = {},
    val addSubscription: suspend (name: String, url: String) -> Unit = { _, _ -> },
    val updateSubscription: suspend (name: String, url: String) -> Unit = { _, _ -> },
    val deleteSubscription: suspend (name: String) -> Unit = {},
    /** Downloads [url] and stores it as a new config; returns the name it was saved under. */
    val importFromUrl: suspend (url: String, name: String) -> String = { _, name -> name },
    /** Opens the system file picker; the pick comes back through `ToolsRoute(pickedFile = …)`. */
    val onPickImportFile: () -> Unit = {},
    val onClearPickedFile: () -> Unit = {},
    val importFile: suspend (file: ToolsPickedFile) -> String = { it.name },
    val readConfig: suspend () -> ToolsConfigDocument = { ToolsConfigDocument("", "") },
    /** Full validation by the core; throws with the reason when the text is not a valid config. */
    val validateConfig: suspend (text: String) -> Unit = {},
    val saveConfig: suspend (source: ToolsConfigDocument, text: String) -> ToolsSaveResult = { _, _ -> ToolsSaveResult.Saved },
    /** Short confirmations (“已切换到 …”, “校验通过”). The host decides how to show them. */
    val onMessage: (String) -> Unit = {},
)
