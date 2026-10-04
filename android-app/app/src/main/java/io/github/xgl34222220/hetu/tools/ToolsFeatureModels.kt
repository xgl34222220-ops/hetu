package io.github.xgl34222220.hetu.tools

import java.util.Locale

/* ------------------------------------------------------------------ */
/*  State models for 03A 工具上册 pages 28–49. Pure Kotlin.             */
/*  Pages 26 and 27 reuse the Part 1 models (subscription / import).   */
/* ------------------------------------------------------------------ */

/* ---------------------------- 应用管理 (28–33) ---------------------------- */

internal enum class ToolsAppScope(val label: String, val tip: String) {
    Blacklist("黑名单", "名单内应用直连，其余应用由配置规则决定。"),
    Whitelist("白名单", "仅名单内应用进入代理。"),
    Core("核心", "核心模式不按 Android UID 区分；名单会保留但暂不生效。"),
}

internal enum class ToolsAppSort(val label: String) { Name("按名称"), Uid("按 UID"), Package("按包名") }

/**
 * One installed app. [key] is what the lists store (the package name, or `user:package` for
 * other users). [icon] is an opaque handle for the host's icon slot.
 */
internal class ToolsApp(val key: String, val label: String, val packageName: String, val uid: Int, val system: Boolean = false, val icon: Any? = null)

/** What the host returns for 应用管理: the apps, the active scope and both saved lists. */
internal class ToolsAppsSnapshot(
    val apps: List<ToolsApp> = emptyList(),
    val scope: ToolsAppScope = ToolsAppScope.Blacklist,
    val blacklist: Set<String> = emptySet(),
    val whitelist: Set<String> = emptySet(),
)

internal data class ToolsAppsState(
    val load: ToolsLoad = ToolsLoad.Loading,
    val apps: List<ToolsApp> = emptyList(),
    val scope: ToolsAppScope = ToolsAppScope.Blacklist,
    val blacklist: Set<String> = emptySet(),
    val whitelist: Set<String> = emptySet(),
    val searching: Boolean = false,
    val query: String = "",
    val sort: ToolsAppSort = ToolsAppSort.Name,
    val descending: Boolean = false,
    val showSystem: Boolean = false,
    val refreshing: Boolean = false,
) {
    /** The list the current tab edits; core mode has none. */
    val selected: Set<String>
        get() = when (scope) {
            ToolsAppScope.Blacklist -> blacklist
            ToolsAppScope.Whitelist -> whitelist
            ToolsAppScope.Core -> emptySet()
        }

    /** Count for the footer. Core mode reports the lists it keeps but does not apply. */
    val selectedCount: Int get() = if (scope == ToolsAppScope.Core) maxOf(blacklist.size, whitelist.size) else selected.size

    /** Rows after the system filter, the search and the sort. Selected system apps stay visible. */
    val visible: List<ToolsApp>
        get() {
            val q = if (searching) query.trim().lowercase(Locale.ROOT) else ""
            val chosen = selected
            val rows = apps.filter { app ->
                (showSystem || !app.system || app.key in chosen) &&
                    (q.isEmpty() || app.label.lowercase(Locale.ROOT).contains(q) || app.packageName.lowercase(Locale.ROOT).contains(q))
            }
            val sorted = when (sort) {
                ToolsAppSort.Name -> rows.sortedWith(compareBy(java.text.Collator.getInstance(Locale.CHINA)) { it.label })
                ToolsAppSort.Uid -> rows.sortedBy { it.uid }
                ToolsAppSort.Package -> rows.sortedBy { it.packageName }
            }
            return if (descending) sorted.asReversed() else sorted
        }

    fun withSelected(keys: Set<String>): ToolsAppsState = when (scope) {
        ToolsAppScope.Blacklist -> copy(blacklist = keys)
        ToolsAppScope.Whitelist -> copy(whitelist = keys)
        ToolsAppScope.Core -> this
    }
}

internal enum class ToolsAppsMenu { Sort, More }

/* ---------------------------- 核心管理 (34–35) ---------------------------- */

internal data class ToolsCore(
    val id: String,
    val name: String,
    /** “内置版本”, “未安装”, “v1.19.1（下载版）”… shown after “当前”. */
    val installed: String,
    /** Shown after “最新” when not blank. */
    val latest: String = "",
    val updateAvailable: Boolean = false,
    /** Ships inside the APK: a downloaded copy can be removed to fall back to it. */
    val bundled: Boolean = false,
    val downloaded: Boolean = false,
    /** False for cores whose runtime backend is not wired up: “仅下载管理”. */
    val runnable: Boolean = true,
    val canDownload: Boolean = true,
) {
    val versionLine: String get() = "当前 $installed" + if (latest.isNotBlank() && updateAvailable) " · 最新 $latest" else ""
}

internal enum class ToolsCoreAction(val label: String) { Update("更新"), Download("下载"), Restore("恢复内置"), Remove("删除") }

/** The left button of a core card. Null when there is nothing to do (bundled and up to date). */
internal fun ToolsCore.primaryAction(): ToolsCoreAction? = when {
    updateAvailable && (bundled || downloaded) && canDownload -> ToolsCoreAction.Update
    downloaded && bundled -> ToolsCoreAction.Restore
    downloaded -> ToolsCoreAction.Remove
    canDownload && !bundled -> ToolsCoreAction.Download
    else -> null
}

internal class ToolsCoresSnapshot(val running: String = "", val cores: List<ToolsCore> = emptyList())

internal data class ToolsCoresState(
    val load: ToolsLoad = ToolsLoad.Loading,
    /** Label of the core that is running, for the bar subtitle. */
    val running: String = "",
    val cores: List<ToolsCore> = emptyList(),
    /** Id of the core being downloaded, imported or removed; every button is inert meanwhile. */
    val busyId: String? = null,
    /** Progress line under the busy card, e.g. “正在下载 · 6.4 MB / 12.8 MB”. */
    val progress: String = "",
    val checking: Boolean = false,
)

/* ---------------------------- 绕过规则 (36–37) ---------------------------- */

internal data class ToolsBypassRules(val cidrs: List<String> = emptyList(), val interfaces: List<String> = emptyList()) {
    /** Trimmed, without blank rows and duplicates: what gets saved and compared. */
    fun normalized(): ToolsBypassRules = ToolsBypassRules(cidrs.clean(), interfaces.clean())
}

internal data class ToolsBypassState(
    val load: ToolsLoad = ToolsLoad.Loading,
    val saved: ToolsBypassRules = ToolsBypassRules(),
    val draft: ToolsBypassRules = ToolsBypassRules(),
    val saving: Boolean = false,
) {
    val dirty: Boolean get() = draft.normalized() != saved.normalized()
}

/* ---------------------------- 共享网络 (38) ---------------------------- */

internal data class ToolsShareInterface(val name: String, val state: String = "")
internal data class ToolsShareClient(val ip: String, val mac: String, val iface: String = "", val state: String = "")

internal data class ToolsShareSettings(
    val enabled: Boolean = false,
    /** Shared interfaces that bypass the proxy. */
    val directInterfaces: Set<String> = emptySet(),
    /** Downstream devices that bypass the proxy, lower-case MACs. */
    val macs: List<String> = emptyList(),
) {
    fun normalized(): ToolsShareSettings = copy(macs = macs.map { it.trim().lowercase(Locale.ROOT) }.clean())
}

internal class ToolsShareSnapshot(
    val settings: ToolsShareSettings = ToolsShareSettings(),
    val interfaces: List<ToolsShareInterface> = emptyList(),
    val clients: List<ToolsShareClient> = emptyList(),
    /** Why the live part is empty (no root, tethering off…); blank when fine. */
    val note: String = "",
)

internal data class ToolsShareState(
    val load: ToolsLoad = ToolsLoad.Loading,
    val saved: ToolsShareSettings = ToolsShareSettings(),
    val draft: ToolsShareSettings = ToolsShareSettings(),
    val interfaces: List<ToolsShareInterface> = emptyList(),
    val clients: List<ToolsShareClient> = emptyList(),
    val note: String = "",
    val refreshing: Boolean = false,
    val saving: Boolean = false,
) {
    val dirty: Boolean get() = draft.normalized() != saved.normalized()
}

/* ---------------------------- CNIP (39) ---------------------------- */

internal data class ToolsCnIpState(val load: ToolsLoad = ToolsLoad.Loading, val enabled: Boolean = false)

/* ---------------------------- 诊断与维护 (40–43) ---------------------------- */

internal sealed interface ToolsPreflight {
    data object Idle : ToolsPreflight
    data object Running : ToolsPreflight
    data class Passed(val message: String = "预检通过：当前设备支持这组 Root 代理设置。") : ToolsPreflight
    data class Failed(val message: String) : ToolsPreflight
}

/** A read-only text panel: loading, then the text or the reason it could not be read. */
internal data class ToolsDiagText(val loading: Boolean = true, val text: String = "", val error: String? = null)

internal sealed interface ToolsDiagOverlay {
    /** Page 41. */
    data class StartupConfig(val content: ToolsDiagText = ToolsDiagText()) : ToolsDiagOverlay

    /** Page 43. */
    data class Report(val content: ToolsDiagText = ToolsDiagText()) : ToolsDiagOverlay

    /** Page 42. */
    data class ConfirmRestore(val running: Boolean = false) : ToolsDiagOverlay
}

internal data class ToolsDiagState(val preflight: ToolsPreflight = ToolsPreflight.Idle)

/* ---------------------------- 广告过滤 (44–49) ---------------------------- */

internal enum class ToolsAdLevel(val id: String, val label: String, val note: String) {
    Light("lite", "轻量", "仅中文纯广告规则，误拦最少。"),
    Balanced("balanced", "均衡", "中文广告 + AdGuard DNS，推荐日常使用。"),
    Strong("enhanced", "加强", "AdGuard DNS + HaGeZi + 隐私 / 追踪增强，可能误拦。"),
}

/** [meta]: “使用内置快照”, “更新于 10 分钟前”, or the last error. */
internal data class ToolsAdSource(val id: String, val name: String, val count: Int, val meta: String, val enabled: Boolean)

internal enum class ToolsAdStatus { Protecting, WrongMode, Waiting, Off }

internal data class ToolsAdblockState(
    val load: ToolsLoad = ToolsLoad.Loading,
    val enabled: Boolean = true,
    val proxyRunning: Boolean = false,
    /** False while the core runs in global or direct mode, where rules are skipped. */
    val ruleMode: Boolean = true,
    /** “全局” or “直连”, for the warning headline. */
    val modeLabel: String = "",
    val ruleCount: Int = 0,
    val hits: Long = 0,
    val allow: List<String> = emptyList(),
    val block: List<String> = emptyList(),
    val sources: List<ToolsAdSource> = emptyList(),
    /** Null when the enabled sources match none of the three presets. */
    val level: ToolsAdLevel? = ToolsAdLevel.Balanced,
    /** Overrides the built-in description of [level] when the host knows better. */
    val levelNote: String? = null,
    val recent: List<String> = emptyList(),
    val startupInjected: Boolean = false,
    val controllerLoaded: Boolean = false,
    val standaloneDns: Boolean = false,
    val cnameProtection: Boolean = true,
    val updating: Boolean = false,
    /** A toggle is being applied; switches and chips are inert meanwhile. */
    val busy: Boolean = false,
) {
    val status: ToolsAdStatus
        get() = when {
            !enabled -> ToolsAdStatus.Off
            proxyRunning && !ruleMode -> ToolsAdStatus.WrongMode
            !proxyRunning -> ToolsAdStatus.Waiting
            else -> ToolsAdStatus.Protecting
        }

    val effective: Boolean get() = status == ToolsAdStatus.Protecting
    val shownHits: Long get() = if (effective) hits else 0
    val note: String get() = levelNote ?: level?.note ?: "自定义规则源组合。"
}

/** What the host returns for 广告过滤; copied into [ToolsAdblockState] as is. */
internal class ToolsAdblockSnapshot(val state: ToolsAdblockState = ToolsAdblockState())

internal sealed interface ToolsAdOverlay {
    /** Page 46. */
    data object Help : ToolsAdOverlay

    /** Pages 47 and 48. */
    data class AddDomain(val allow: Boolean, val draft: String = "", val error: String? = null, val saving: Boolean = false) : ToolsAdOverlay

    /** Page 49. */
    data class ConfirmAllow(val domain: String, val saving: Boolean = false) : ToolsAdOverlay
}

/* ------------------------------------------------------------------ */
/*  Validation                                                          */
/* ------------------------------------------------------------------ */

private fun List<String>.clean(): List<String> = map { it.trim() }.filter { it.isNotEmpty() }.distinct()

internal object ToolsFeatureRules {
    private val cidr = Regex("^[0-9a-fA-F:.]+/\\d{1,3}$")
    private val iface = Regex("^[A-Za-z0-9_.:@+-]{1,32}$")
    private val mac = Regex("^[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}$")
    private val domain = Regex("^([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z][a-z0-9-]*[a-z0-9]$")
    const val MaxMacs = 64

    /** First problem in [rules] (already normalized), or null. */
    fun bypassError(rules: ToolsBypassRules): String? {
        rules.cidrs.firstOrNull { !isCidr(it) }?.let { return "CIDR 格式无效：$it" }
        if (rules.interfaces.any { it == "lo" }) return "接口不能填写 lo"
        rules.interfaces.firstOrNull { !iface.matches(it) }?.let { return "接口名无效：$it" }
        return null
    }

    fun isCidr(value: String): Boolean {
        if (!cidr.matches(value)) return false
        val bits = value.substringAfter('/').toIntOrNull() ?: return false
        return if (value.contains(':')) bits <= 128 else bits <= 32 && value.substringBefore('/').count { it == '.' } == 3
    }

    fun shareError(settings: ToolsShareSettings): String? {
        settings.macs.firstOrNull { !mac.matches(it) }?.let { return "MAC 格式无效：$it" }
        if (settings.macs.size > MaxMacs) return "最多 $MaxMacs 个 MAC"
        return null
    }

    /** Lower-cases, strips a scheme, a path and a leading `*.` so pasted URLs work. */
    fun cleanDomain(raw: String): String =
        raw.trim().lowercase(Locale.ROOT).substringAfter("://").substringBefore('/').removePrefix("*.").removePrefix("+.").trimEnd('.')

    fun domainError(raw: String, existing: Collection<String>): String? {
        val value = cleanDomain(raw)
        return when {
            value.isEmpty() -> "请输入域名"
            !domain.matches(value) -> "请输入有效域名"
            value in existing -> "已在名单中"
            else -> null
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Callbacks                                                           */
/* ------------------------------------------------------------------ */

/**
 * Everything pages 28–49 ask of the host. Suspend lambdas report failure by throwing; the
 * message of the exception is shown to the user. A page whose loader is left null is not
 * hosted in the module: its entry falls through to `ToolsActions.onOpenEntry`.
 */
internal class ToolsFeatureActions(
    /* 应用管理 */
    val loadApps: (suspend (refresh: Boolean) -> ToolsAppsSnapshot)? = null,
    val setAppScope: suspend (ToolsAppScope) -> Unit = {},
    val setAppList: suspend (scope: ToolsAppScope, keys: Set<String>) -> Unit = { _, _ -> },

    /* 核心管理 */
    val loadCores: (suspend (network: Boolean) -> ToolsCoresSnapshot)? = null,
    val downloadCore: suspend (id: String, onProgress: (String) -> Unit) -> Unit = { _, _ -> },
    val removeCore: suspend (id: String) -> Unit = {},
    /** Opens the system file picker for an ELF core; call [onDone] with the outcome message. */
    val importCore: (id: String, onDone: (message: String?) -> Unit) -> Unit = { _, onDone -> onDone(null) },

    /* 绕过规则 */
    val loadBypass: (suspend () -> ToolsBypassRules)? = null,
    val saveBypass: suspend (ToolsBypassRules) -> Unit = {},

    /* 共享网络 */
    val loadShare: (suspend () -> ToolsShareSnapshot)? = null,
    val saveShare: suspend (ToolsShareSettings) -> Unit = {},

    /* CNIP */
    val loadCnIp: (suspend () -> Boolean)? = null,
    val setCnIp: suspend (Boolean) -> Unit = {},

    /* 诊断与维护 */
    val runPreflight: (suspend () -> ToolsPreflight)? = null,
    val startupConfig: suspend () -> String = { "" },
    val diagnostics: suspend () -> String = { "" },
    /** Stops the proxy and rolls back the rules it added; returns the confirmation text. */
    val restoreNetwork: suspend () -> String = { "已停止代理并回滚网络规则" },
    val onCopy: (label: String, text: String) -> Unit = { _, _ -> },

    /* 广告过滤 */
    val loadAdblock: (suspend () -> ToolsAdblockSnapshot)? = null,
    /** Each setter may return a note to show (“已保存，下次启动代理时生效”), or blank. */
    val setAdblockEnabled: suspend (Boolean) -> String = { "" },
    val setAdLevel: suspend (ToolsAdLevel) -> String = { "" },
    val setAdSource: suspend (id: String, enabled: Boolean) -> String = { _, _ -> "" },
    val updateAdRules: suspend () -> String = { "规则源已是最新" },
    val changeAdDomain: suspend (domain: String, allow: Boolean, add: Boolean) -> String = { _, _, _ -> "" },
    val setStandaloneDns: suspend (Boolean) -> String = { "" },
    val setCnameProtection: suspend (Boolean) -> Unit = {},
    /** “切到规则” on the wrong-mode warning; null hides the button. */
    val switchToRuleMode: (suspend () -> Unit)? = null,

    val onMessage: (String) -> Unit = {},
) {
    /** Entries this instance can host inside the module. */
    fun hosts(entry: ToolsEntry): Boolean = when (entry) {
        ToolsEntry.Apps -> loadApps != null
        ToolsEntry.Cores -> loadCores != null
        ToolsEntry.Bypass -> loadBypass != null
        ToolsEntry.Share -> loadShare != null
        ToolsEntry.CnIp -> loadCnIp != null
        ToolsEntry.Diag -> runPreflight != null
        ToolsEntry.Adblock -> loadAdblock != null
        else -> false
    }
}
