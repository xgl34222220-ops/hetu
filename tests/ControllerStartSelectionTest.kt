package io.github.xgl34222220.hetu

import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking

internal class StartPrefs : SharedPreferences {
    val values = linkedMapOf<String, Any>()
    override fun getString(key: String, fallback: String?) = values[key] as? String ?: fallback
    override fun getBoolean(key: String, fallback: Boolean) = values[key] as? Boolean ?: fallback
    override fun edit() = object : SharedPreferences.Editor {
        private val changes = linkedMapOf<String, String>()
        override fun putString(key: String, value: String) = apply { changes[key] = value }
        override fun apply() { values.putAll(changes) }
    }
}

internal data class StartEntry(val name: String)
internal class StartConfigs {
    var selectedCore: ProxyRuntimeProfile.Core? = null
    var present = true
    var configured = true
    fun selected(core: ProxyRuntimeProfile.Core): StartEntry? {
        selectedCore = core
        return if (present) StartEntry("synthetic-${core.id}.yaml") else null
    }
    fun hasConfiguredSubscription(entry: StartEntry) = configured
}
internal class StartRoot {
    val starts = mutableListOf<ProxyRuntimeProfile>()
    fun startManual(profile: ProxyRuntimeProfile, progress: (String) -> Unit): Boolean {
        starts += profile
        progress("fixture: accepted exact requested profile")
        return true
    }
}
internal class StartContext(core: ProxyRuntimeProfile.Core, mode: ProxyRuntimeProfile.Mode) {
    val prefs = StartPrefs().apply {
        values["proxyBaseCore"] = core.id
        values["proxyBaseMode"] = mode.id
        values["proxyRootRuntimeRunning"] = true
        values["proxyRootWanted"] = true
        values["proxySelectedConfig.${core.id}"] = "keep-source"
    }
    val installed = mutableSetOf<ProxyRuntimeProfile.Core>()
    val configs = StartConfigs()
    val root = StartRoot()
    val hooks = mutableListOf<String>()
    var migrations = 0
}
internal object LegacyAppMigrator {
    fun migrateIfNeeded(app: StartContext) { app.migrations++ }
}
internal class ProxyCoreStore(private val app: StartContext) {
    fun installed(core: ProxyRuntimeProfile.Core) = core in app.installed
}
internal object ProxyScriptHooks {
    fun run(app: StartContext, name: String, mode: String, config: String) {
        app.hooks += "$name:$mode:$config"
    }
}

private var checks = 0
private fun verify(ok: Boolean, message: String) { check(ok) { message }; checks++ }

fun main(args: Array<String>) = runBlocking {
    if (args.single() == "old") {
        val app = StartContext(ProxyRuntimeProfile.Core.SING_BOX, ProxyRuntimeProfile.Mode.TUN)
        val before = app.prefs.values.toMap()
        ExtractedStartup(app).start()
        verify(app.prefs.getString("proxyBaseCore", "") == "mihomo", "Old code did not reproduce core rewrite")
        verify(app.prefs.getString("proxyBaseMode", "") == "tproxy", "Old code did not reproduce mode rewrite")
        verify(app.root.starts.single().core == ProxyRuntimeProfile.Core.MIHOMO, "Old code did not dispatch a different core")
        verify(before["proxySelectedConfig.sing-box"] == app.prefs.values["proxySelectedConfig.sing-box"], "Source marker changed")
        println("REPRODUCED: requested sing-box/TUN, rewrote prefs and dispatched Mihomo/TPROXY ($checks checks)")
        return@runBlocking
    }
    val unsupported = ProxyRuntimeProfile.Core.values().filter {
        it != ProxyRuntimeProfile.Core.MIHOMO && it != ProxyRuntimeProfile.Core.MIHOMO_SMART
    }
    for (core in unsupported) for (mode in ProxyRuntimeProfile.Mode.values()) {
        val app = StartContext(core, mode)
        app.installed += core
        val before = app.prefs.values.toMap()
        val result = runCatching { ExtractedStartup(app).start() }
        verify(result.isFailure, "$core/$mode must fail before startup")
        verify(result.exceptionOrNull()?.message?.contains(core.label) == true, "Failure must identify requested core")
        verify(app.prefs.values == before, "Unsupported selection must not change any preference")
        verify(app.root.starts.isEmpty() && app.hooks.isEmpty(), "Unsupported selection reached Root or hooks")
        verify(app.configs.selectedCore == null, "Unsupported core must not redirect config selection")
        verify(app.migrations == 1, "Existing migration path must still run")
    }
    for (core in listOf(ProxyRuntimeProfile.Core.MIHOMO, ProxyRuntimeProfile.Core.MIHOMO_SMART)) {
        val app = StartContext(core, ProxyRuntimeProfile.Mode.MIXED)
        app.installed += core
        val before = app.prefs.values.toMap()
        verify(runCatching { ExtractedStartup(app).start() }.isFailure, "Unsupported mode must fail")
        verify(app.prefs.values == before && app.root.starts.isEmpty() && app.hooks.isEmpty(), "Mode refusal changed state")
    }
    val missing = StartContext(ProxyRuntimeProfile.Core.MIHOMO_SMART, ProxyRuntimeProfile.Mode.TPROXY)
    val missingBefore = missing.prefs.values.toMap()
    val missingResult = runCatching { ExtractedStartup(missing).start() }
    verify(missingResult.isFailure, "Missing Smart must not substitute bundled Mihomo")
    verify(missingResult.exceptionOrNull()?.message?.contains("Mihomo Smart") == true, "Name the missing core")
    verify(missing.prefs.values == missingBefore && missing.root.starts.isEmpty() && missing.hooks.isEmpty(), "Missing core changed state")
    for (core in listOf(ProxyRuntimeProfile.Core.MIHOMO, ProxyRuntimeProfile.Core.MIHOMO_SMART)) {
        for (mode in ProxyRuntimeProfile.Mode.values().filter { it != ProxyRuntimeProfile.Mode.MIXED }) {
            val app = StartContext(core, mode)
            if (core == ProxyRuntimeProfile.Core.MIHOMO_SMART) app.installed += core
            val before = app.prefs.values.toMap()
            verify(ExtractedStartup(app).start(), "Supported fixture did not start")
            val actual = app.root.starts.single()
            verify(actual.core == core && actual.mode == mode, "Supported request changed identity")
            verify(app.prefs.values == before, "Supported start unexpectedly rewrote prefs")
            verify(app.configs.selectedCore == core && app.hooks.size == 1, "Wrong configuration/hook dispatch")
        }
    }
    for (noSource in listOf(true, false)) {
        val app = StartContext(ProxyRuntimeProfile.Core.MIHOMO, ProxyRuntimeProfile.Mode.TPROXY)
        if (noSource) app.configs.present = false else app.configs.configured = false
        val before = app.prefs.values.toMap()
        verify(runCatching { ExtractedStartup(app).start() }.isFailure, "Config guard was bypassed")
        verify(app.prefs.values == before && app.root.starts.isEmpty() && app.hooks.isEmpty(), "Config refusal mutated runtime")
    }
    println("PASS: $checks startup selection checks; real production method/profile, isolated IO")
}
