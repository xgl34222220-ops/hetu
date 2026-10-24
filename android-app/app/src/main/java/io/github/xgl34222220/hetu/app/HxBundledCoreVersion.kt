package io.github.xgl34222220.hetu

import android.content.Context
import android.content.SharedPreferences

/**
 * Audit4 U12: what 关于 › 内置核心 can say about the Mihomo inside the APK. The pinned payload carries
 * only its source revision as an asset; its version string is read from the running controller while
 * the bundled core is the one running, and kept per revision so the page still shows it when stopped.
 */
internal data class HxBundledCoreInfo(
    val revision: String = "",
    /** The selected core is Mihomo and no downloaded Mihomo replaces the bundled one. */
    val inUse: Boolean = false,
    val savedVersion: String = "",
)

internal object HxBundledCoreVersion {
    private const val PREF_PREFIX = "aboutBundledCoreVersion."

    /** Blocking (asset + file checks): call off the main thread. */
    fun read(context: Context, prefs: SharedPreferences): HxBundledCoreInfo {
        val revision = runCatching {
            context.assets.open("mihomo-revision.txt").bufferedReader().use { it.readText().trim() }
        }.getOrDefault("")
        val inUse = runCatching {
            ProxyRuntimeProfile.load(prefs).core == ProxyRuntimeProfile.Core.MIHOMO &&
                !ProxyCoreStore(context).installed(ProxyRuntimeProfile.Core.MIHOMO)
        }.getOrDefault(false)
        val saved = if (revision.isBlank()) "" else prefs.getString(PREF_PREFIX + revision, "").orEmpty()
        return HxBundledCoreInfo(revision, inUse, saved)
    }

    /** `v1.19.31 Meta` (ProxyDashboardRepository.coreVersion) → `v1.19.31`; blank stays blank. */
    fun display(version: String): String =
        version.trim().split(' ').filter { it.isNotBlank() && !it.equals("Meta", ignoreCase = true) }.joinToString(" ")

    /** The version to show: the running bundled core's, else the one remembered for this revision. */
    fun versionFor(info: HxBundledCoreInfo, runningVersion: String): String =
        display(runningVersion).takeIf { info.inUse && it.isNotBlank() } ?: display(info.savedVersion)

    /** Remembers [runningVersion] for [info]'s revision when the bundled core is what reported it. */
    fun remember(prefs: SharedPreferences, info: HxBundledCoreInfo, runningVersion: String) {
        val shown = display(runningVersion)
        if (!info.inUse || shown.isBlank() || info.revision.isBlank() || shown == info.savedVersion) return
        prefs.edit().putString(PREF_PREFIX + info.revision, shown).apply()
    }
}
