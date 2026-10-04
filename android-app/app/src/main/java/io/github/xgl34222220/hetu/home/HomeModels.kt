package io.github.xgl34222220.hetu.home

import java.util.Locale

/* ------------------------------------------------------------------ */
/*  State model. Pure Kotlin: no Compose or Android types in here.     */
/* ------------------------------------------------------------------ */

/** Lifecycle of the Root proxy as the home page presents it. */
internal sealed interface HomeStatus {
    /** Running normally. */
    data class Running(val uptimeSeconds: Long) : HomeStatus

    /** Running, but saved settings differ from the effective ones: shows the restart banner. */
    data class PendingRestart(val uptimeSeconds: Long) : HomeStatus

    data object NotRunning : HomeStatus
    data object Starting : HomeStatus
    data object Restarting : HomeStatus
    data object Stopping : HomeStatus

    /** The last start attempt failed; [detail] is shown in the failure sheet. */
    data class StartFailed(val detail: String) : HomeStatus
}

/** Live data (node, WAN, speed, resources) is meaningful in these states. */
internal val HomeStatus.isLive: Boolean
    get() = this is HomeStatus.Running || this is HomeStatus.PendingRestart || this is HomeStatus.Restarting

/** A transition is in flight; all run controls are disabled. */
internal val HomeStatus.isBusy: Boolean
    get() = this is HomeStatus.Starting || this is HomeStatus.Restarting || this is HomeStatus.Stopping

internal val HomeStatus.uptimeSeconds: Long?
    get() = when (this) {
        is HomeStatus.Running -> uptimeSeconds
        is HomeStatus.PendingRestart -> uptimeSeconds
        else -> null
    }

internal enum class HomeProxyMode(val id: String, val label: String) {
    Rule("rule", "规则"), Global("global", "全局"), Direct("direct", "直连");

    companion object {
        fun fromId(id: String?): HomeProxyMode = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: Rule
    }
}

internal enum class HomeNetSide(val label: String) { Wan("WAN"), Lan("LAN") }

internal enum class HomeSpeedSource(val id: String, val short: String, val title: String, val description: String) {
    Api("api", "API", "API 模式", "读取 Mihomo 控制器流量，适合查看代理核心吞吐"),
    Local("local", "本地", "本地模式", "读取设备本地总流量，适合查看当前网络实际吞吐");

    companion object {
        fun fromId(id: String?): HomeSpeedSource = entries.firstOrNull { it.id == id } ?: Api
    }
}

/** Current exit node. [delayMs]: null = unknown, [HomeDelay.Timeout] = timed out. */
internal data class HomeNode(val group: String, val name: String, val delayMs: Long? = null)

/** One direct-probe target result. [delayMs]: null = not measured, [HomeDelay.Timeout] = timed out. */
internal data class HomeProbe(val name: String, val delayMs: Long? = null)

internal object HomeDelay {
    const val Timeout = -1L
}

/** Public exit address as seen through the core. Null fields render as “未知”. */
internal data class HomeWan(
    val ip: String? = null,
    val countryCode: String = "",
    val region: String? = null,
    val isp: String? = null,
    val asn: String? = null,
    val city: String? = null,
    val organization: String? = null,
    val ipType: String? = null,
    val timezone: String? = null,
    val coordinates: String? = null,
)

internal data class HomeLan(val ip: String? = null, val iface: String? = null)

internal data class HomeSubscription(val usedBytes: Long, val totalBytes: Long) {
    val usedFraction: Float? get() = if (totalBytes <= 0L) null else (usedBytes.coerceAtLeast(0L).toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
    val remainingPercent: Int? get() = usedFraction?.let { ((1f - it) * 100f).toInt().coerceIn(0, 100) }
}

/**
 * Core process resources. History lists hold the samples of this viewing session, oldest first;
 * a null entry is a missed sample and breaks the curve.
 */
internal data class HomeResource(
    val memoryBytes: Long? = null,
    val cpuPercent: Float? = null,
    val pid: Int? = null,
    val coreVersion: String = "",
    val cpuAffinity: String? = null,
    val currentCpu: Int? = null,
    val connections: Int? = null,
    val cpuHistory: List<Float?> = emptyList(),
    val memoryHistoryMb: List<Float?> = emptyList(),
) {
    val hasGap: Boolean get() = cpuHistory.any { it == null } || memoryHistoryMb.any { it == null }
}

internal data class HomeUiState(
    val status: HomeStatus = HomeStatus.NotRunning,
    val core: String = "Mihomo",
    val runMode: String = "TPROXY",
    val config: String = "",
    val proxyMode: HomeProxyMode = HomeProxyMode.Rule,
    val node: HomeNode? = null,
    val probes: List<HomeProbe> = emptyList(),
    val probing: Boolean = false,
    val netSide: HomeNetSide = HomeNetSide.Wan,
    val wan: HomeWan = HomeWan(),
    val lan: HomeLan = HomeLan(),
    val ipRefreshing: Boolean = false,
    val speedSource: HomeSpeedSource = HomeSpeedSource.Api,
    val uploadBytesPerSecond: Long? = null,
    val downloadBytesPerSecond: Long? = null,
    val subscription: HomeSubscription? = null,
    val resource: HomeResource = HomeResource(),
)

/* ------------------------------------------------------------------ */
/*  Direct-probe targets form                                           */
/* ------------------------------------------------------------------ */

internal data class HomeTarget(val name: String, val url: String)

internal data class HomeTargetsConfig(
    val targets: List<HomeTarget> = HomeTargets.defaults,
    /** 0 = off, otherwise seconds between automatic probes while the home page is visible. */
    val autoRefreshSeconds: Int = 0,
)

internal sealed interface HomeTargetsFeedback {
    val message: String

    data class Saved(override val message: String = "测速目标已保存；返回首页后立即生效") : HomeTargetsFeedback
    data class Restored(override val message: String = "已恢复默认测速目标") : HomeTargetsFeedback
    data class Invalid(override val message: String) : HomeTargetsFeedback
}

internal object HomeTargets {
    const val Count = 3
    val autoRefreshChoices = listOf(0, 30, 60)

    val defaults = listOf(
        HomeTarget("Baidu", "https://www.baidu.com/"),
        HomeTarget("Cloudflare", "https://cp.cloudflare.com/generate_204"),
        HomeTarget("Google", "https://www.gstatic.com/generate_204"),
    )

    fun autoRefreshLabel(seconds: Int): String = if (seconds <= 0) "关闭" else "$seconds 秒"

    /** Returns the first problem, or null when the three targets can be saved. */
    fun validate(targets: List<HomeTarget>): String? {
        val names = targets.map { it.name.trim() }
        if (names.any { it.isEmpty() }) return "测速目标名称不能为空"
        if (names.map { it.lowercase(Locale.ROOT) }.toSet().size < names.size) return "三个测速目标名称不能重复"
        if (targets.any { !isHttpUrl(it.url) }) return "请填写有效的 http/https 测速地址"
        return null
    }

    fun isHttpUrl(raw: String): Boolean {
        val url = raw.trim()
        val rest = when {
            url.startsWith("https://", ignoreCase = true) -> url.substring(8)
            url.startsWith("http://", ignoreCase = true) -> url.substring(7)
            else -> return false
        }
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        return host.isNotEmpty() && host.none { it.isWhitespace() } && !url.any { it.isWhitespace() }
    }
}

/* ------------------------------------------------------------------ */
/*  Callbacks                                                           */
/* ------------------------------------------------------------------ */

/** Everything the home module asks its host to do. Defaults are no-ops so previews need nothing. */
internal class HomeActions(
    val onStart: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onReload: () -> Unit = {},
    val onRestart: () -> Unit = {},
    val onProxyModeChange: (HomeProxyMode) -> Unit = {},
    /** Opens 面板 › 策略 with the current group expanded. */
    val onOpenNode: () -> Unit = {},
    val onProbe: () -> Unit = {},
    val onNetSideChange: (HomeNetSide) -> Unit = {},
    val onSpeedSourceChange: (HomeSpeedSource) -> Unit = {},
    val onRefreshIp: () -> Unit = {},
    /** Opens 面板 › 订阅. */
    val onOpenSubscription: () -> Unit = {},
    /** Core and run-mode tags: opens 设置 › 基础代理配置. */
    val onOpenBasicSettings: () -> Unit = {},
    /** Config tag: opens 工具 › 配置管理. */
    val onOpenConfigs: () -> Unit = {},
    /** “查看配置” in the start-failure sheet: opens the YAML editor at the reported line when known. */
    val onViewConfig: () -> Unit = {},
    val onDismissStartFailure: () -> Unit = {},
    val onCopy: (label: String, text: String) -> Unit = { _, _ -> },
    val loadTargets: () -> HomeTargetsConfig = { HomeTargetsConfig() },
    val saveTargets: (HomeTargetsConfig) -> Unit = {},
    val resetTargets: () -> HomeTargetsConfig = { HomeTargetsConfig() },
)

/* ------------------------------------------------------------------ */
/*  Formatting                                                          */
/* ------------------------------------------------------------------ */

internal object HomeFormat {
    const val Dash = "—"
    private val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")

    /** 128 KB, 2.8 MB, 20 GB: one decimal below 100, none above, no trailing “.0”. */
    fun bytes(value: Long?): String {
        if (value == null || value < 0L) return Dash
        var number = value.toDouble()
        var unit = 0
        while (number >= 1024.0 && unit < units.lastIndex) { number /= 1024.0; unit++ }
        return "${trim(number, unit == 0)} ${units[unit]}"
    }

    fun speed(bytesPerSecond: Long?): String = if (bytesPerSecond == null || bytesPerSecond < 0L) Dash else "${bytes(bytesPerSecond)}/s"

    fun percent(value: Float?): String = if (value == null || value.isNaN()) Dash else String.format(Locale.US, "%.1f%%", value)

    fun uptime(seconds: Long): String = when {
        seconds < 60 -> "少于 1 分钟"
        seconds < 3600 -> "${seconds / 60} 分钟"
        seconds < 86400 -> "${seconds / 3600} 小时 ${seconds % 3600 / 60} 分钟"
        else -> "${seconds / 86400} 天 ${seconds % 86400 / 3600} 小时"
    }

    private fun trim(number: Double, integerOnly: Boolean): String {
        if (integerOnly || number >= 100.0) return Math.round(number).toString()
        val text = String.format(Locale.US, "%.1f", number)
        return if (text.endsWith(".0")) text.dropLast(2) else text
    }
}

/* ------------------------------------------------------------------ */
/*  Sample data for previews (mirrors the prototype’s 示例数据)          */
/* ------------------------------------------------------------------ */

internal object HomeSamples {
    private const val KB = 1024L
    private const val MB = 1024L * KB
    private const val GB = 1024L * MB

    private val cpuHistory: List<Float?> = listOf(
        2f, 3.4f, 2.2f, 4.1f, 2.6f, 3.8f, 2.1f, 2.4f, 3.9f, 2.8f, 2.2f, 3.1f, 2.5f, 2.0f, null, null,
        2.2f, 3.0f, 2.6f, 3.6f, 3.2f, 4.4f, 3.0f, 2.8f, 3.4f, 2.4f, 2.9f, 2.2f, 2.6f, 2.0f,
    )
    private val memoryHistory: List<Float?> = listOf(
        70f, 71f, 73f, 72f, 76f, 75f, 78f, 76f, 79f, 77f, 80f, 78f, 77f, 79f, 82f, 80f,
        86f, 88f, 84f, 85f, 83f, 84f, 82f, 83f, 81f, 84f, 82f, 83f, 81f, 82f,
    )

    val running = HomeUiState(
        status = HomeStatus.Running(uptimeSeconds = 32 * 60L),
        core = "Mihomo",
        runMode = "TPROXY",
        config = "日常.yaml",
        proxyMode = HomeProxyMode.Rule,
        node = HomeNode("节点选择", "香港 01", 28),
        probes = listOf(HomeProbe("Baidu", 18), HomeProbe("Cloudflare", 42), HomeProbe("Google", 56)),
        wan = HomeWan(
            ip = "203.0.113.24", countryCode = "HK", region = "香港",
            isp = "Example Network", asn = "AS64500", city = "香港",
        ),
        lan = HomeLan("192.168.1.12", "wlan0"),
        uploadBytesPerSecond = 128 * KB,
        downloadBytesPerSecond = (2.8 * MB).toLong(),
        subscription = HomeSubscription(20 * GB, 100 * GB),
        resource = HomeResource(
            memoryBytes = 82 * MB, cpuPercent = 2.0f, pid = 1842, coreVersion = "Mihomo v1.19.0",
            cpuAffinity = "0–7", currentCpu = 2, connections = 24,
            cpuHistory = cpuHistory, memoryHistoryMb = memoryHistory,
        ),
    )

    val modeSwitch = running.copy(proxyMode = HomeProxyMode.Global, node = HomeNode("GLOBAL", "香港 01", 28))

    val notRunning = running.copy(
        status = HomeStatus.NotRunning,
        node = null,
        probes = running.probes.map { it.copy(delayMs = null) },
        wan = HomeWan(),
        uploadBytesPerSecond = null,
        downloadBytesPerSecond = null,
        resource = HomeResource(coreVersion = "Mihomo v1.19.0"),
    )

    val pendingRestartLan = running.copy(status = HomeStatus.PendingRestart(32 * 60L), netSide = HomeNetSide.Lan)

    val starting = notRunning.copy(status = HomeStatus.Starting)

    val startFailed = notRunning.copy(
        status = HomeStatus.StartFailed("配置加载失败\n日常.yaml\n第 18 行：缩进无效\n请检查配置后重新启动"),
    )

    val restarting = running.copy(status = HomeStatus.Restarting)

    val stopping = running.copy(status = HomeStatus.Stopping)

    val exampleTargets = listOf(
        HomeTarget("示例 A", "https://example.com/204"),
        HomeTarget("示例 B", "https://example.net/204"),
        HomeTarget("示例 C", "https://example.org/204"),
    )
    val duplicateTargets = exampleTargets.mapIndexed { index, target -> if (index == 1) target.copy(name = "示例 A") else target }
}
