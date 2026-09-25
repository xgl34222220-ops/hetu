package io.github.xgl34222220.hetu

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ManagedScript(val name: String, val path: String, val size: Long)

internal object ProxyScriptHooks {
    const val ROOT = "/data/adb/hetu/scripts"
    val PRE_START = ROOT + "/pre-start.sh"
    val POST_STOP = ROOT + "/post-stop.sh"
    val LOG = "/data/adb/hetu/run/scripts.log"

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun validate(path: String) {
        require(path == PRE_START || path == POST_STOP) { "未知脚本路径" }
    }
    private fun managedName(raw: String): String {
        val source = raw.substringAfterLast('/').trim().ifBlank { "script.sh" }
        val cleaned = source.replace(Regex("[^A-Za-z0-9._-]"), "_").take(72)
        val name = if (cleaned.endsWith(".sh", true)) cleaned else "$cleaned.sh"
        require(name.matches(Regex("[A-Za-z0-9._-]{1,75}"))) { "脚本文件名无效" }
        require(name != "pre-start.sh" && name != "post-stop.sh") { "不能覆盖河图固定 Hook" }
        return name
    }

    private fun managedPath(name: String): String = ROOT + "/" + managedName(name)


    suspend fun ensure(context: Context) = withContext(Dispatchers.IO) {
        val result = RootBridge.rootShell(
            context.applicationContext,
            "mkdir -p " + q(ROOT) + " /data/adb/hetu/run && chmod 700 " + q(ROOT),
            6_000L,
        )
        if (!result.ok()) {
            throw IllegalStateException(result.output.ifBlank { "无法创建脚本目录" })
        }
    }

    suspend fun read(context: Context, path: String): String = withContext(Dispatchers.IO) {
        validate(path)
        ensure(context)
        val result = RootBridge.rootShell(
            context.applicationContext,
            "if [ -f " + q(path) + " ]; then head -c 131072 " + q(path) + "; fi",
            6_000L,
        )
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本读取失败" })
        result.output
    }

    suspend fun write(context: Context, path: String, text: String) = withContext(Dispatchers.IO) {
        validate(path)
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 64 * 1024) { "脚本最多 64 KiB" }
        ensure(context)
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val tmp = path + ".new"
        val command =
            "printf '%s' " + q(encoded) +
            " | base64 -d > " + q(tmp) +
            " && chmod 700 " + q(tmp) +
            " && mv -f " + q(tmp) + " " + q(path)
        val result = RootBridge.rootShell(context.applicationContext, command, 8_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本保存失败" })
    }

    suspend fun clear(context: Context, path: String) = withContext(Dispatchers.IO) {
        validate(path)
        val result = RootBridge.rootShell(
            context.applicationContext,
            "rm -f " + q(path),
            6_000L,
        )
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本删除失败" })
    }

    suspend fun listManaged(context: Context): List<ManagedScript> = withContext(Dispatchers.IO) {
        ensure(context)
        val command = """
            for f in ${ROOT}/*.sh; do
              [ -f "${'        val result = RootBridge.rootShell(context.applicationContext, command, 6_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本列表读取失败" })
        result.output.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 2) return@mapNotNull null
            val name = runCatching { managedName(parts[0]) }.getOrNull() ?: return@mapNotNull null
            ManagedScript(name, ROOT + "/" + name, parts[1].toLongOrNull() ?: 0L)
        }.sortedBy { it.name.lowercase() }.toList()
    }

    suspend fun importManaged(context: Context, fileName: String, bytes: ByteArray): ManagedScript = withContext(Dispatchers.IO) {
        require(bytes.size <= 64 * 1024) { "脚本最多 64 KiB" }
        require(bytes.none { it.toInt() == 0 }) { "不能导入二进制脚本" }
        val text = bytes.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "脚本不是有效 UTF-8 文本" }
        val name = managedName(fileName)
        val path = managedPath(name)
        ensure(context)
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val tmp = path + ".new"
        val command =
            "printf '%s' " + q(encoded) +
            " | base64 -d > " + q(tmp) +
            " && chmod 700 " + q(tmp) +
            " && mv -f " + q(tmp) + " " + q(path)
        val result = RootBridge.rootShell(context.applicationContext, command, 8_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本导入失败" })
        ManagedScript(name, path, bytes.size.toLong())
    }

    suspend fun deleteManaged(context: Context, name: String) = withContext(Dispatchers.IO) {
        val path = managedPath(name)
        val result = RootBridge.rootShell(context.applicationContext, "rm -f " + q(path), 6_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本删除失败" })
    }

    suspend fun runManaged(context: Context, name: String): String = withContext(Dispatchers.IO) {
        val path = managedPath(name)
        ensure(context)
        val prefs = context.applicationContext.getSharedPreferences("hetu", Context.MODE_PRIVATE)
        val mode = prefs.getString("proxyUiLastMode", "").orEmpty()
        val config = prefs.getString("proxyUiLastConfig", "").orEmpty()
        val command = buildString {
            append("test -f ").append(q(path)).append(" || exit 44; ")
            append("export HETU_HOOK=manual; ")
            append("export HETU_MODE=").append(q(mode)).append("; ")
            append("export HETU_CONFIG=").append(q(config)).append("; ")
            append("export HETU_BASE=/data/adb/hetu; ")
            append("export HETU_RUN_DIR=/data/adb/hetu/run; ")
            append("export HETU_SCRIPT_DIR=").append(q(ROOT)).append("; ")
            append("{ printf '\\n'; date '+[%Y-%m-%d %H:%M:%S] manual=").append(name.replace("%", "")).append("'; ")
            append("if command -v timeout >/dev/null 2>&1; then timeout 12 sh ").append(q(path))
            append("; else sh ").append(q(path)).append("; fi; } >> ").append(q(LOG)).append(" 2>&1")
        }
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止"
                else if (result.code == 44) "脚本不存在"
                else "脚本执行失败（" + result.code + "），请查看 scripts.log",
            )
        }
        "脚本执行完成 · $name"
    }

    suspend fun run(
        context: Context,
        stage: String,
        mode: String,
        config: String,
    ): String = withContext(Dispatchers.IO) {
        val path = when (stage) {
            "pre-start" -> PRE_START
            "post-stop" -> POST_STOP
            else -> error("未知脚本阶段")
        }
        ensure(context)

        val command = buildString {
            append("if [ ! -s ").append(q(path)).append(" ]; then exit 0; fi; ")
            append("export HETU_HOOK=").append(q(stage)).append("; ")
            append("export HETU_MODE=").append(q(mode)).append("; ")
            append("export HETU_CONFIG=").append(q(config)).append("; ")
            append("export HETU_BASE=/data/adb/hetu; ")
            append("export HETU_RUN_DIR=/data/adb/hetu/run; ")
            append("export HETU_SCRIPT_DIR=").append(q(ROOT)).append("; ")
            append("{ printf '\\n'; date '+[%Y-%m-%d %H:%M:%S] hook=")
            append(stage.replace("%", "")).append(" mode=")
            append(mode.replace("%", "")).append(" config=")
            append(config.replace("%", "")).append("'; ")
            append("if command -v timeout >/dev/null 2>&1; then timeout 12 sh ")
            append(q(path)).append("; else sh ").append(q(path)).append("; fi; ")
            append("} >> ").append(q(LOG)).append(" 2>&1")
        }

        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（" + result.code + "），请查看 scripts.log",
            )
        }
        "脚本执行完成"
    }

    fun environmentText(): String = """
        HETU_HOOK       pre-start / post-stop / manual
        HETU_MODE       当前运行模式
        HETU_CONFIG     当前配置名称
        HETU_BASE       /data/adb/hetu
        HETU_RUN_DIR    /data/adb/hetu/run
        HETU_SCRIPT_DIR /data/adb/hetu/scripts

        脚本使用 /system/bin/sh 执行，最长 12 秒。
        非 0 退出码会阻止对应的手动启动/停止操作，并写入：
        /data/adb/hetu/run/scripts.log
    """.trimIndent()
}
}f" ] || continue
              n=${'        val result = RootBridge.rootShell(context.applicationContext, command, 6_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本列表读取失败" })
        result.output.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 2) return@mapNotNull null
            val name = runCatching { managedName(parts[0]) }.getOrNull() ?: return@mapNotNull null
            ManagedScript(name, ROOT + "/" + name, parts[1].toLongOrNull() ?: 0L)
        }.sortedBy { it.name.lowercase() }.toList()
    }

    suspend fun importManaged(context: Context, fileName: String, bytes: ByteArray): ManagedScript = withContext(Dispatchers.IO) {
        require(bytes.size <= 64 * 1024) { "脚本最多 64 KiB" }
        require(bytes.none { it.toInt() == 0 }) { "不能导入二进制脚本" }
        val text = bytes.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "脚本不是有效 UTF-8 文本" }
        val name = managedName(fileName)
        val path = managedPath(name)
        ensure(context)
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val tmp = path + ".new"
        val command =
            "printf '%s' " + q(encoded) +
            " | base64 -d > " + q(tmp) +
            " && chmod 700 " + q(tmp) +
            " && mv -f " + q(tmp) + " " + q(path)
        val result = RootBridge.rootShell(context.applicationContext, command, 8_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本导入失败" })
        ManagedScript(name, path, bytes.size.toLong())
    }

    suspend fun deleteManaged(context: Context, name: String) = withContext(Dispatchers.IO) {
        val path = managedPath(name)
        val result = RootBridge.rootShell(context.applicationContext, "rm -f " + q(path), 6_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本删除失败" })
    }

    suspend fun runManaged(context: Context, name: String): String = withContext(Dispatchers.IO) {
        val path = managedPath(name)
        ensure(context)
        val prefs = context.applicationContext.getSharedPreferences("hetu", Context.MODE_PRIVATE)
        val mode = prefs.getString("proxyUiLastMode", "").orEmpty()
        val config = prefs.getString("proxyUiLastConfig", "").orEmpty()
        val command = buildString {
            append("test -f ").append(q(path)).append(" || exit 44; ")
            append("export HETU_HOOK=manual; ")
            append("export HETU_MODE=").append(q(mode)).append("; ")
            append("export HETU_CONFIG=").append(q(config)).append("; ")
            append("export HETU_BASE=/data/adb/hetu; ")
            append("export HETU_RUN_DIR=/data/adb/hetu/run; ")
            append("export HETU_SCRIPT_DIR=").append(q(ROOT)).append("; ")
            append("{ printf '\\n'; date '+[%Y-%m-%d %H:%M:%S] manual=").append(name.replace("%", "")).append("'; ")
            append("if command -v timeout >/dev/null 2>&1; then timeout 12 sh ").append(q(path))
            append("; else sh ").append(q(path)).append("; fi; } >> ").append(q(LOG)).append(" 2>&1")
        }
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止"
                else if (result.code == 44) "脚本不存在"
                else "脚本执行失败（" + result.code + "），请查看 scripts.log",
            )
        }
        "脚本执行完成 · $name"
    }

    suspend fun run(
        context: Context,
        stage: String,
        mode: String,
        config: String,
    ): String = withContext(Dispatchers.IO) {
        val path = when (stage) {
            "pre-start" -> PRE_START
            "post-stop" -> POST_STOP
            else -> error("未知脚本阶段")
        }
        ensure(context)

        val command = buildString {
            append("if [ ! -s ").append(q(path)).append(" ]; then exit 0; fi; ")
            append("export HETU_HOOK=").append(q(stage)).append("; ")
            append("export HETU_MODE=").append(q(mode)).append("; ")
            append("export HETU_CONFIG=").append(q(config)).append("; ")
            append("export HETU_BASE=/data/adb/hetu; ")
            append("export HETU_RUN_DIR=/data/adb/hetu/run; ")
            append("export HETU_SCRIPT_DIR=").append(q(ROOT)).append("; ")
            append("{ printf '\\n'; date '+[%Y-%m-%d %H:%M:%S] hook=")
            append(stage.replace("%", "")).append(" mode=")
            append(mode.replace("%", "")).append(" config=")
            append(config.replace("%", "")).append("'; ")
            append("if command -v timeout >/dev/null 2>&1; then timeout 12 sh ")
            append(q(path)).append("; else sh ").append(q(path)).append("; fi; ")
            append("} >> ").append(q(LOG)).append(" 2>&1")
        }

        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（" + result.code + "），请查看 scripts.log",
            )
        }
        "脚本执行完成"
    }

    fun environmentText(): String = """
        HETU_HOOK       pre-start / post-stop / manual
        HETU_MODE       当前运行模式
        HETU_CONFIG     当前配置名称
        HETU_BASE       /data/adb/hetu
        HETU_RUN_DIR    /data/adb/hetu/run
        HETU_SCRIPT_DIR /data/adb/hetu/scripts

        脚本使用 /system/bin/sh 执行，最长 12 秒。
        非 0 退出码会阻止对应的手动启动/停止操作，并写入：
        /data/adb/hetu/run/scripts.log
    """.trimIndent()
}
}(basename "${'        val result = RootBridge.rootShell(context.applicationContext, command, 6_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本列表读取失败" })
        result.output.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 2) return@mapNotNull null
            val name = runCatching { managedName(parts[0]) }.getOrNull() ?: return@mapNotNull null
            ManagedScript(name, ROOT + "/" + name, parts[1].toLongOrNull() ?: 0L)
        }.sortedBy { it.name.lowercase() }.toList()
    }

    suspend fun importManaged(context: Context, fileName: String, bytes: ByteArray): ManagedScript = withContext(Dispatchers.IO) {
        require(bytes.size <= 64 * 1024) { "脚本最多 64 KiB" }
        require(bytes.none { it.toInt() == 0 }) { "不能导入二进制脚本" }
        val text = bytes.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "脚本不是有效 UTF-8 文本" }
        val name = managedName(fileName)
        val path = managedPath(name)
        ensure(context)
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val tmp = path + ".new"
        val command =
            "printf '%s' " + q(encoded) +
            " | base64 -d > " + q(tmp) +
            " && chmod 700 " + q(tmp) +
            " && mv -f " + q(tmp) + " " + q(path)
        val result = RootBridge.rootShell(context.applicationContext, command, 8_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本导入失败" })
        ManagedScript(name, path, bytes.size.toLong())
    }

    suspend fun deleteManaged(context: Context, name: String) = withContext(Dispatchers.IO) {
        val path = managedPath(name)
        val result = RootBridge.rootShell(context.applicationContext, "rm -f " + q(path), 6_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本删除失败" })
    }

    suspend fun runManaged(context: Context, name: String): String = withContext(Dispatchers.IO) {
        val path = managedPath(name)
        ensure(context)
        val prefs = context.applicationContext.getSharedPreferences("hetu", Context.MODE_PRIVATE)
        val mode = prefs.getString("proxyUiLastMode", "").orEmpty()
        val config = prefs.getString("proxyUiLastConfig", "").orEmpty()
        val command = buildString {
            append("test -f ").append(q(path)).append(" || exit 44; ")
            append("export HETU_HOOK=manual; ")
            append("export HETU_MODE=").append(q(mode)).append("; ")
            append("export HETU_CONFIG=").append(q(config)).append("; ")
            append("export HETU_BASE=/data/adb/hetu; ")
            append("export HETU_RUN_DIR=/data/adb/hetu/run; ")
            append("export HETU_SCRIPT_DIR=").append(q(ROOT)).append("; ")
            append("{ printf '\\n'; date '+[%Y-%m-%d %H:%M:%S] manual=").append(name.replace("%", "")).append("'; ")
            append("if command -v timeout >/dev/null 2>&1; then timeout 12 sh ").append(q(path))
            append("; else sh ").append(q(path)).append("; fi; } >> ").append(q(LOG)).append(" 2>&1")
        }
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止"
                else if (result.code == 44) "脚本不存在"
                else "脚本执行失败（" + result.code + "），请查看 scripts.log",
            )
        }
        "脚本执行完成 · $name"
    }

    suspend fun run(
        context: Context,
        stage: String,
        mode: String,
        config: String,
    ): String = withContext(Dispatchers.IO) {
        val path = when (stage) {
            "pre-start" -> PRE_START
            "post-stop" -> POST_STOP
            else -> error("未知脚本阶段")
        }
        ensure(context)

        val command = buildString {
            append("if [ ! -s ").append(q(path)).append(" ]; then exit 0; fi; ")
            append("export HETU_HOOK=").append(q(stage)).append("; ")
            append("export HETU_MODE=").append(q(mode)).append("; ")
            append("export HETU_CONFIG=").append(q(config)).append("; ")
            append("export HETU_BASE=/data/adb/hetu; ")
            append("export HETU_RUN_DIR=/data/adb/hetu/run; ")
            append("export HETU_SCRIPT_DIR=").append(q(ROOT)).append("; ")
            append("{ printf '\\n'; date '+[%Y-%m-%d %H:%M:%S] hook=")
            append(stage.replace("%", "")).append(" mode=")
            append(mode.replace("%", "")).append(" config=")
            append(config.replace("%", "")).append("'; ")
            append("if command -v timeout >/dev/null 2>&1; then timeout 12 sh ")
            append(q(path)).append("; else sh ").append(q(path)).append("; fi; ")
            append("} >> ").append(q(LOG)).append(" 2>&1")
        }

        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（" + result.code + "），请查看 scripts.log",
            )
        }
        "脚本执行完成"
    }

    fun environmentText(): String = """
        HETU_HOOK       pre-start / post-stop / manual
        HETU_MODE       当前运行模式
        HETU_CONFIG     当前配置名称
        HETU_BASE       /data/adb/hetu
        HETU_RUN_DIR    /data/adb/hetu/run
        HETU_SCRIPT_DIR /data/adb/hetu/scripts

        脚本使用 /system/bin/sh 执行，最长 12 秒。
        非 0 退出码会阻止对应的手动启动/停止操作，并写入：
        /data/adb/hetu/run/scripts.log
    """.trimIndent()
}
}f")
              [ "${'        val result = RootBridge.rootShell(context.applicationContext, command, 6_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本列表读取失败" })
        result.output.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 2) return@mapNotNull null
            val name = runCatching { managedName(parts[0]) }.getOrNull() ?: return@mapNotNull null
            ManagedScript(name, ROOT + "/" + name, parts[1].toLongOrNull() ?: 0L)
        }.sortedBy { it.name.lowercase() }.toList()
    }

    suspend fun importManaged(context: Context, fileName: String, bytes: ByteArray): ManagedScript = withContext(Dispatchers.IO) {
        require(bytes.size <= 64 * 1024) { "脚本最多 64 KiB" }
        require(bytes.none { it.toInt() == 0 }) { "不能导入二进制脚本" }
        val text = bytes.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "脚本不是有效 UTF-8 文本" }
        val name = managedName(fileName)
        val path = managedPath(name)
        ensure(context)
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val tmp = path + ".new"
        val command =
            "printf '%s' " + q(encoded) +
            " | base64 -d > " + q(tmp) +
            " && chmod 700 " + q(tmp) +
            " && mv -f " + q(tmp) + " " + q(path)
        val result = RootBridge.rootShell(context.applicationContext, command, 8_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本导入失败" })
        ManagedScript(name, path, bytes.size.toLong())
    }

    suspend fun deleteManaged(context: Context, name: String) = withContext(Dispatchers.IO) {
        val path = managedPath(name)
        val result = RootBridge.rootShell(context.applicationContext, "rm -f " + q(path), 6_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本删除失败" })
    }

    suspend fun runManaged(context: Context, name: String): String = withContext(Dispatchers.IO) {
        val path = managedPath(name)
        ensure(context)
        val prefs = context.applicationContext.getSharedPreferences("hetu", Context.MODE_PRIVATE)
        val mode = prefs.getString("proxyUiLastMode", "").orEmpty()
        val config = prefs.getString("proxyUiLastConfig", "").orEmpty()
        val command = buildString {
            append("test -f ").append(q(path)).append(" || exit 44; ")
            append("export HETU_HOOK=manual; ")
            append("export HETU_MODE=").append(q(mode)).append("; ")
            append("export HETU_CONFIG=").append(q(config)).append("; ")
            append("export HETU_BASE=/data/adb/hetu; ")
            append("export HETU_RUN_DIR=/data/adb/hetu/run; ")
            append("export HETU_SCRIPT_DIR=").append(q(ROOT)).append("; ")
            append("{ printf '\\n'; date '+[%Y-%m-%d %H:%M:%S] manual=").append(name.replace("%", "")).append("'; ")
            append("if command -v timeout >/dev/null 2>&1; then timeout 12 sh ").append(q(path))
            append("; else sh ").append(q(path)).append("; fi; } >> ").append(q(LOG)).append(" 2>&1")
        }
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止"
                else if (result.code == 44) "脚本不存在"
                else "脚本执行失败（" + result.code + "），请查看 scripts.log",
            )
        }
        "脚本执行完成 · $name"
    }

    suspend fun run(
        context: Context,
        stage: String,
        mode: String,
        config: String,
    ): String = withContext(Dispatchers.IO) {
        val path = when (stage) {
            "pre-start" -> PRE_START
            "post-stop" -> POST_STOP
            else -> error("未知脚本阶段")
        }
        ensure(context)

        val command = buildString {
            append("if [ ! -s ").append(q(path)).append(" ]; then exit 0; fi; ")
            append("export HETU_HOOK=").append(q(stage)).append("; ")
            append("export HETU_MODE=").append(q(mode)).append("; ")
            append("export HETU_CONFIG=").append(q(config)).append("; ")
            append("export HETU_BASE=/data/adb/hetu; ")
            append("export HETU_RUN_DIR=/data/adb/hetu/run; ")
            append("export HETU_SCRIPT_DIR=").append(q(ROOT)).append("; ")
            append("{ printf '\\n'; date '+[%Y-%m-%d %H:%M:%S] hook=")
            append(stage.replace("%", "")).append(" mode=")
            append(mode.replace("%", "")).append(" config=")
            append(config.replace("%", "")).append("'; ")
            append("if command -v timeout >/dev/null 2>&1; then timeout 12 sh ")
            append(q(path)).append("; else sh ").append(q(path)).append("; fi; ")
            append("} >> ").append(q(LOG)).append(" 2>&1")
        }

        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（" + result.code + "），请查看 scripts.log",
            )
        }
        "脚本执行完成"
    }

    fun environmentText(): String = """
        HETU_HOOK       pre-start / post-stop / manual
        HETU_MODE       当前运行模式
        HETU_CONFIG     当前配置名称
        HETU_BASE       /data/adb/hetu
        HETU_RUN_DIR    /data/adb/hetu/run
        HETU_SCRIPT_DIR /data/adb/hetu/scripts

        脚本使用 /system/bin/sh 执行，最长 12 秒。
        非 0 退出码会阻止对应的手动启动/停止操作，并写入：
        /data/adb/hetu/run/scripts.log
    """.trimIndent()
}
}n" = "pre-start.sh" ] && continue
              [ "${'        val result = RootBridge.rootShell(context.applicationContext, command, 6_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本列表读取失败" })
        result.output.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 2) return@mapNotNull null
            val name = runCatching { managedName(parts[0]) }.getOrNull() ?: return@mapNotNull null
            ManagedScript(name, ROOT + "/" + name, parts[1].toLongOrNull() ?: 0L)
        }.sortedBy { it.name.lowercase() }.toList()
    }

    suspend fun importManaged(context: Context, fileName: String, bytes: ByteArray): ManagedScript = withContext(Dispatchers.IO) {
        require(bytes.size <= 64 * 1024) { "脚本最多 64 KiB" }
        require(bytes.none { it.toInt() == 0 }) { "不能导入二进制脚本" }
        val text = bytes.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "脚本不是有效 UTF-8 文本" }
        val name = managedName(fileName)
        val path = managedPath(name)
        ensure(context)
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val tmp = path + ".new"
        val command =
            "printf '%s' " + q(encoded) +
            " | base64 -d > " + q(tmp) +
            " && chmod 700 " + q(tmp) +
            " && mv -f " + q(tmp) + " " + q(path)
        val result = RootBridge.rootShell(context.applicationContext, command, 8_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本导入失败" })
        ManagedScript(name, path, bytes.size.toLong())
    }

    suspend fun deleteManaged(context: Context, name: String) = withContext(Dispatchers.IO) {
        val path = managedPath(name)
        val result = RootBridge.rootShell(context.applicationContext, "rm -f " + q(path), 6_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本删除失败" })
    }

    suspend fun runManaged(context: Context, name: String): String = withContext(Dispatchers.IO) {
        val path = managedPath(name)
        ensure(context)
        val prefs = context.applicationContext.getSharedPreferences("hetu", Context.MODE_PRIVATE)
        val mode = prefs.getString("proxyUiLastMode", "").orEmpty()
        val config = prefs.getString("proxyUiLastConfig", "").orEmpty()
        val command = buildString {
            append("test -f ").append(q(path)).append(" || exit 44; ")
            append("export HETU_HOOK=manual; ")
            append("export HETU_MODE=").append(q(mode)).append("; ")
            append("export HETU_CONFIG=").append(q(config)).append("; ")
            append("export HETU_BASE=/data/adb/hetu; ")
            append("export HETU_RUN_DIR=/data/adb/hetu/run; ")
            append("export HETU_SCRIPT_DIR=").append(q(ROOT)).append("; ")
            append("{ printf '\\n'; date '+[%Y-%m-%d %H:%M:%S] manual=").append(name.replace("%", "")).append("'; ")
            append("if command -v timeout >/dev/null 2>&1; then timeout 12 sh ").append(q(path))
            append("; else sh ").append(q(path)).append("; fi; } >> ").append(q(LOG)).append(" 2>&1")
        }
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止"
                else if (result.code == 44) "脚本不存在"
                else "脚本执行失败（" + result.code + "），请查看 scripts.log",
            )
        }
        "脚本执行完成 · $name"
    }

    suspend fun run(
        context: Context,
        stage: String,
        mode: String,
        config: String,
    ): String = withContext(Dispatchers.IO) {
        val path = when (stage) {
            "pre-start" -> PRE_START
            "post-stop" -> POST_STOP
            else -> error("未知脚本阶段")
        }
        ensure(context)

        val command = buildString {
            append("if [ ! -s ").append(q(path)).append(" ]; then exit 0; fi; ")
            append("export HETU_HOOK=").append(q(stage)).append("; ")
            append("export HETU_MODE=").append(q(mode)).append("; ")
            append("export HETU_CONFIG=").append(q(config)).append("; ")
            append("export HETU_BASE=/data/adb/hetu; ")
            append("export HETU_RUN_DIR=/data/adb/hetu/run; ")
            append("export HETU_SCRIPT_DIR=").append(q(ROOT)).append("; ")
            append("{ printf '\\n'; date '+[%Y-%m-%d %H:%M:%S] hook=")
            append(stage.replace("%", "")).append(" mode=")
            append(mode.replace("%", "")).append(" config=")
            append(config.replace("%", "")).append("'; ")
            append("if command -v timeout >/dev/null 2>&1; then timeout 12 sh ")
            append(q(path)).append("; else sh ").append(q(path)).append("; fi; ")
            append("} >> ").append(q(LOG)).append(" 2>&1")
        }

        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（" + result.code + "），请查看 scripts.log",
            )
        }
        "脚本执行完成"
    }

    fun environmentText(): String = """
        HETU_HOOK       pre-start / post-stop / manual
        HETU_MODE       当前运行模式
        HETU_CONFIG     当前配置名称
        HETU_BASE       /data/adb/hetu
        HETU_RUN_DIR    /data/adb/hetu/run
        HETU_SCRIPT_DIR /data/adb/hetu/scripts

        脚本使用 /system/bin/sh 执行，最长 12 秒。
        非 0 退出码会阻止对应的手动启动/停止操作，并写入：
        /data/adb/hetu/run/scripts.log
    """.trimIndent()
}
}n" = "post-stop.sh" ] && continue
              s=${'        val result = RootBridge.rootShell(context.applicationContext, command, 6_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本列表读取失败" })
        result.output.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 2) return@mapNotNull null
            val name = runCatching { managedName(parts[0]) }.getOrNull() ?: return@mapNotNull null
            ManagedScript(name, ROOT + "/" + name, parts[1].toLongOrNull() ?: 0L)
        }.sortedBy { it.name.lowercase() }.toList()
    }

    suspend fun importManaged(context: Context, fileName: String, bytes: ByteArray): ManagedScript = withContext(Dispatchers.IO) {
        require(bytes.size <= 64 * 1024) { "脚本最多 64 KiB" }
        require(bytes.none { it.toInt() == 0 }) { "不能导入二进制脚本" }
        val text = bytes.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "脚本不是有效 UTF-8 文本" }
        val name = managedName(fileName)
        val path = managedPath(name)
        ensure(context)
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val tmp = path + ".new"
        val command =
            "printf '%s' " + q(encoded) +
            " | base64 -d > " + q(tmp) +
            " && chmod 700 " + q(tmp) +
            " && mv -f " + q(tmp) + " " + q(path)
        val result = RootBridge.rootShell(context.applicationContext, command, 8_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本导入失败" })
        ManagedScript(name, path, bytes.size.toLong())
    }

    suspend fun deleteManaged(context: Context, name: String) = withContext(Dispatchers.IO) {
        val path = managedPath(name)
        val result = RootBridge.rootShell(context.applicationContext, "rm -f " + q(path), 6_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本删除失败" })
    }

    suspend fun runManaged(context: Context, name: String): String = withContext(Dispatchers.IO) {
        val path = managedPath(name)
        ensure(context)
        val prefs = context.applicationContext.getSharedPreferences("hetu", Context.MODE_PRIVATE)
        val mode = prefs.getString("proxyUiLastMode", "").orEmpty()
        val config = prefs.getString("proxyUiLastConfig", "").orEmpty()
        val command = buildString {
            append("test -f ").append(q(path)).append(" || exit 44; ")
            append("export HETU_HOOK=manual; ")
            append("export HETU_MODE=").append(q(mode)).append("; ")
            append("export HETU_CONFIG=").append(q(config)).append("; ")
            append("export HETU_BASE=/data/adb/hetu; ")
            append("export HETU_RUN_DIR=/data/adb/hetu/run; ")
            append("export HETU_SCRIPT_DIR=").append(q(ROOT)).append("; ")
            append("{ printf '\\n'; date '+[%Y-%m-%d %H:%M:%S] manual=").append(name.replace("%", "")).append("'; ")
            append("if command -v timeout >/dev/null 2>&1; then timeout 12 sh ").append(q(path))
            append("; else sh ").append(q(path)).append("; fi; } >> ").append(q(LOG)).append(" 2>&1")
        }
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止"
                else if (result.code == 44) "脚本不存在"
                else "脚本执行失败（" + result.code + "），请查看 scripts.log",
            )
        }
        "脚本执行完成 · $name"
    }

    suspend fun run(
        context: Context,
        stage: String,
        mode: String,
        config: String,
    ): String = withContext(Dispatchers.IO) {
        val path = when (stage) {
            "pre-start" -> PRE_START
            "post-stop" -> POST_STOP
            else -> error("未知脚本阶段")
        }
        ensure(context)

        val command = buildString {
            append("if [ ! -s ").append(q(path)).append(" ]; then exit 0; fi; ")
            append("export HETU_HOOK=").append(q(stage)).append("; ")
            append("export HETU_MODE=").append(q(mode)).append("; ")
            append("export HETU_CONFIG=").append(q(config)).append("; ")
            append("export HETU_BASE=/data/adb/hetu; ")
            append("export HETU_RUN_DIR=/data/adb/hetu/run; ")
            append("export HETU_SCRIPT_DIR=").append(q(ROOT)).append("; ")
            append("{ printf '\\n'; date '+[%Y-%m-%d %H:%M:%S] hook=")
            append(stage.replace("%", "")).append(" mode=")
            append(mode.replace("%", "")).append(" config=")
            append(config.replace("%", "")).append("'; ")
            append("if command -v timeout >/dev/null 2>&1; then timeout 12 sh ")
            append(q(path)).append("; else sh ").append(q(path)).append("; fi; ")
            append("} >> ").append(q(LOG)).append(" 2>&1")
        }

        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（" + result.code + "），请查看 scripts.log",
            )
        }
        "脚本执行完成"
    }

    fun environmentText(): String = """
        HETU_HOOK       pre-start / post-stop / manual
        HETU_MODE       当前运行模式
        HETU_CONFIG     当前配置名称
        HETU_BASE       /data/adb/hetu
        HETU_RUN_DIR    /data/adb/hetu/run
        HETU_SCRIPT_DIR /data/adb/hetu/scripts

        脚本使用 /system/bin/sh 执行，最长 12 秒。
        非 0 退出码会阻止对应的手动启动/停止操作，并写入：
        /data/adb/hetu/run/scripts.log
    """.trimIndent()
}
}(wc -c < "${'        val result = RootBridge.rootShell(context.applicationContext, command, 6_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本列表读取失败" })
        result.output.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 2) return@mapNotNull null
            val name = runCatching { managedName(parts[0]) }.getOrNull() ?: return@mapNotNull null
            ManagedScript(name, ROOT + "/" + name, parts[1].toLongOrNull() ?: 0L)
        }.sortedBy { it.name.lowercase() }.toList()
    }

    suspend fun importManaged(context: Context, fileName: String, bytes: ByteArray): ManagedScript = withContext(Dispatchers.IO) {
        require(bytes.size <= 64 * 1024) { "脚本最多 64 KiB" }
        require(bytes.none { it.toInt() == 0 }) { "不能导入二进制脚本" }
        val text = bytes.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "脚本不是有效 UTF-8 文本" }
        val name = managedName(fileName)
        val path = managedPath(name)
        ensure(context)
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val tmp = path + ".new"
        val command =
            "printf '%s' " + q(encoded) +
            " | base64 -d > " + q(tmp) +
            " && chmod 700 " + q(tmp) +
            " && mv -f " + q(tmp) + " " + q(path)
        val result = RootBridge.rootShell(context.applicationContext, command, 8_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本导入失败" })
        ManagedScript(name, path, bytes.size.toLong())
    }

    suspend fun deleteManaged(context: Context, name: String) = withContext(Dispatchers.IO) {
        val path = managedPath(name)
        val result = RootBridge.rootShell(context.applicationContext, "rm -f " + q(path), 6_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本删除失败" })
    }

    suspend fun runManaged(context: Context, name: String): String = withContext(Dispatchers.IO) {
        val path = managedPath(name)
        ensure(context)
        val prefs = context.applicationContext.getSharedPreferences("hetu", Context.MODE_PRIVATE)
        val mode = prefs.getString("proxyUiLastMode", "").orEmpty()
        val config = prefs.getString("proxyUiLastConfig", "").orEmpty()
        val command = buildString {
            append("test -f ").append(q(path)).append(" || exit 44; ")
            append("export HETU_HOOK=manual; ")
            append("export HETU_MODE=").append(q(mode)).append("; ")
            append("export HETU_CONFIG=").append(q(config)).append("; ")
            append("export HETU_BASE=/data/adb/hetu; ")
            append("export HETU_RUN_DIR=/data/adb/hetu/run; ")
            append("export HETU_SCRIPT_DIR=").append(q(ROOT)).append("; ")
            append("{ printf '\\n'; date '+[%Y-%m-%d %H:%M:%S] manual=").append(name.replace("%", "")).append("'; ")
            append("if command -v timeout >/dev/null 2>&1; then timeout 12 sh ").append(q(path))
            append("; else sh ").append(q(path)).append("; fi; } >> ").append(q(LOG)).append(" 2>&1")
        }
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止"
                else if (result.code == 44) "脚本不存在"
                else "脚本执行失败（" + result.code + "），请查看 scripts.log",
            )
        }
        "脚本执行完成 · $name"
    }

    suspend fun run(
        context: Context,
        stage: String,
        mode: String,
        config: String,
    ): String = withContext(Dispatchers.IO) {
        val path = when (stage) {
            "pre-start" -> PRE_START
            "post-stop" -> POST_STOP
            else -> error("未知脚本阶段")
        }
        ensure(context)

        val command = buildString {
            append("if [ ! -s ").append(q(path)).append(" ]; then exit 0; fi; ")
            append("export HETU_HOOK=").append(q(stage)).append("; ")
            append("export HETU_MODE=").append(q(mode)).append("; ")
            append("export HETU_CONFIG=").append(q(config)).append("; ")
            append("export HETU_BASE=/data/adb/hetu; ")
            append("export HETU_RUN_DIR=/data/adb/hetu/run; ")
            append("export HETU_SCRIPT_DIR=").append(q(ROOT)).append("; ")
            append("{ printf '\\n'; date '+[%Y-%m-%d %H:%M:%S] hook=")
            append(stage.replace("%", "")).append(" mode=")
            append(mode.replace("%", "")).append(" config=")
            append(config.replace("%", "")).append("'; ")
            append("if command -v timeout >/dev/null 2>&1; then timeout 12 sh ")
            append(q(path)).append("; else sh ").append(q(path)).append("; fi; ")
            append("} >> ").append(q(LOG)).append(" 2>&1")
        }

        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（" + result.code + "），请查看 scripts.log",
            )
        }
        "脚本执行完成"
    }

    fun environmentText(): String = """
        HETU_HOOK       pre-start / post-stop / manual
        HETU_MODE       当前运行模式
        HETU_CONFIG     当前配置名称
        HETU_BASE       /data/adb/hetu
        HETU_RUN_DIR    /data/adb/hetu/run
        HETU_SCRIPT_DIR /data/adb/hetu/scripts

        脚本使用 /system/bin/sh 执行，最长 12 秒。
        非 0 退出码会阻止对应的手动启动/停止操作，并写入：
        /data/adb/hetu/run/scripts.log
    """.trimIndent()
}
}f" 2>/dev/null || echo 0)
              printf '%s\t%s\n' "${'        val result = RootBridge.rootShell(context.applicationContext, command, 6_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本列表读取失败" })
        result.output.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 2) return@mapNotNull null
            val name = runCatching { managedName(parts[0]) }.getOrNull() ?: return@mapNotNull null
            ManagedScript(name, ROOT + "/" + name, parts[1].toLongOrNull() ?: 0L)
        }.sortedBy { it.name.lowercase() }.toList()
    }

    suspend fun importManaged(context: Context, fileName: String, bytes: ByteArray): ManagedScript = withContext(Dispatchers.IO) {
        require(bytes.size <= 64 * 1024) { "脚本最多 64 KiB" }
        require(bytes.none { it.toInt() == 0 }) { "不能导入二进制脚本" }
        val text = bytes.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "脚本不是有效 UTF-8 文本" }
        val name = managedName(fileName)
        val path = managedPath(name)
        ensure(context)
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val tmp = path + ".new"
        val command =
            "printf '%s' " + q(encoded) +
            " | base64 -d > " + q(tmp) +
            " && chmod 700 " + q(tmp) +
            " && mv -f " + q(tmp) + " " + q(path)
        val result = RootBridge.rootShell(context.applicationContext, command, 8_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本导入失败" })
        ManagedScript(name, path, bytes.size.toLong())
    }

    suspend fun deleteManaged(context: Context, name: String) = withContext(Dispatchers.IO) {
        val path = managedPath(name)
        val result = RootBridge.rootShell(context.applicationContext, "rm -f " + q(path), 6_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本删除失败" })
    }

    suspend fun runManaged(context: Context, name: String): String = withContext(Dispatchers.IO) {
        val path = managedPath(name)
        ensure(context)
        val prefs = context.applicationContext.getSharedPreferences("hetu", Context.MODE_PRIVATE)
        val mode = prefs.getString("proxyUiLastMode", "").orEmpty()
        val config = prefs.getString("proxyUiLastConfig", "").orEmpty()
        val command = buildString {
            append("test -f ").append(q(path)).append(" || exit 44; ")
            append("export HETU_HOOK=manual; ")
            append("export HETU_MODE=").append(q(mode)).append("; ")
            append("export HETU_CONFIG=").append(q(config)).append("; ")
            append("export HETU_BASE=/data/adb/hetu; ")
            append("export HETU_RUN_DIR=/data/adb/hetu/run; ")
            append("export HETU_SCRIPT_DIR=").append(q(ROOT)).append("; ")
            append("{ printf '\\n'; date '+[%Y-%m-%d %H:%M:%S] manual=").append(name.replace("%", "")).append("'; ")
            append("if command -v timeout >/dev/null 2>&1; then timeout 12 sh ").append(q(path))
            append("; else sh ").append(q(path)).append("; fi; } >> ").append(q(LOG)).append(" 2>&1")
        }
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止"
                else if (result.code == 44) "脚本不存在"
                else "脚本执行失败（" + result.code + "），请查看 scripts.log",
            )
        }
        "脚本执行完成 · $name"
    }

    suspend fun run(
        context: Context,
        stage: String,
        mode: String,
        config: String,
    ): String = withContext(Dispatchers.IO) {
        val path = when (stage) {
            "pre-start" -> PRE_START
            "post-stop" -> POST_STOP
            else -> error("未知脚本阶段")
        }
        ensure(context)

        val command = buildString {
            append("if [ ! -s ").append(q(path)).append(" ]; then exit 0; fi; ")
            append("export HETU_HOOK=").append(q(stage)).append("; ")
            append("export HETU_MODE=").append(q(mode)).append("; ")
            append("export HETU_CONFIG=").append(q(config)).append("; ")
            append("export HETU_BASE=/data/adb/hetu; ")
            append("export HETU_RUN_DIR=/data/adb/hetu/run; ")
            append("export HETU_SCRIPT_DIR=").append(q(ROOT)).append("; ")
            append("{ printf '\\n'; date '+[%Y-%m-%d %H:%M:%S] hook=")
            append(stage.replace("%", "")).append(" mode=")
            append(mode.replace("%", "")).append(" config=")
            append(config.replace("%", "")).append("'; ")
            append("if command -v timeout >/dev/null 2>&1; then timeout 12 sh ")
            append(q(path)).append("; else sh ").append(q(path)).append("; fi; ")
            append("} >> ").append(q(LOG)).append(" 2>&1")
        }

        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（" + result.code + "），请查看 scripts.log",
            )
        }
        "脚本执行完成"
    }

    fun environmentText(): String = """
        HETU_HOOK       pre-start / post-stop / manual
        HETU_MODE       当前运行模式
        HETU_CONFIG     当前配置名称
        HETU_BASE       /data/adb/hetu
        HETU_RUN_DIR    /data/adb/hetu/run
        HETU_SCRIPT_DIR /data/adb/hetu/scripts

        脚本使用 /system/bin/sh 执行，最长 12 秒。
        非 0 退出码会阻止对应的手动启动/停止操作，并写入：
        /data/adb/hetu/run/scripts.log
    """.trimIndent()
}
}n" "${'        val result = RootBridge.rootShell(context.applicationContext, command, 6_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本列表读取失败" })
        result.output.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 2) return@mapNotNull null
            val name = runCatching { managedName(parts[0]) }.getOrNull() ?: return@mapNotNull null
            ManagedScript(name, ROOT + "/" + name, parts[1].toLongOrNull() ?: 0L)
        }.sortedBy { it.name.lowercase() }.toList()
    }

    suspend fun importManaged(context: Context, fileName: String, bytes: ByteArray): ManagedScript = withContext(Dispatchers.IO) {
        require(bytes.size <= 64 * 1024) { "脚本最多 64 KiB" }
        require(bytes.none { it.toInt() == 0 }) { "不能导入二进制脚本" }
        val text = bytes.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "脚本不是有效 UTF-8 文本" }
        val name = managedName(fileName)
        val path = managedPath(name)
        ensure(context)
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val tmp = path + ".new"
        val command =
            "printf '%s' " + q(encoded) +
            " | base64 -d > " + q(tmp) +
            " && chmod 700 " + q(tmp) +
            " && mv -f " + q(tmp) + " " + q(path)
        val result = RootBridge.rootShell(context.applicationContext, command, 8_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本导入失败" })
        ManagedScript(name, path, bytes.size.toLong())
    }

    suspend fun deleteManaged(context: Context, name: String) = withContext(Dispatchers.IO) {
        val path = managedPath(name)
        val result = RootBridge.rootShell(context.applicationContext, "rm -f " + q(path), 6_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本删除失败" })
    }

    suspend fun runManaged(context: Context, name: String): String = withContext(Dispatchers.IO) {
        val path = managedPath(name)
        ensure(context)
        val prefs = context.applicationContext.getSharedPreferences("hetu", Context.MODE_PRIVATE)
        val mode = prefs.getString("proxyUiLastMode", "").orEmpty()
        val config = prefs.getString("proxyUiLastConfig", "").orEmpty()
        val command = buildString {
            append("test -f ").append(q(path)).append(" || exit 44; ")
            append("export HETU_HOOK=manual; ")
            append("export HETU_MODE=").append(q(mode)).append("; ")
            append("export HETU_CONFIG=").append(q(config)).append("; ")
            append("export HETU_BASE=/data/adb/hetu; ")
            append("export HETU_RUN_DIR=/data/adb/hetu/run; ")
            append("export HETU_SCRIPT_DIR=").append(q(ROOT)).append("; ")
            append("{ printf '\\n'; date '+[%Y-%m-%d %H:%M:%S] manual=").append(name.replace("%", "")).append("'; ")
            append("if command -v timeout >/dev/null 2>&1; then timeout 12 sh ").append(q(path))
            append("; else sh ").append(q(path)).append("; fi; } >> ").append(q(LOG)).append(" 2>&1")
        }
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止"
                else if (result.code == 44) "脚本不存在"
                else "脚本执行失败（" + result.code + "），请查看 scripts.log",
            )
        }
        "脚本执行完成 · $name"
    }

    suspend fun run(
        context: Context,
        stage: String,
        mode: String,
        config: String,
    ): String = withContext(Dispatchers.IO) {
        val path = when (stage) {
            "pre-start" -> PRE_START
            "post-stop" -> POST_STOP
            else -> error("未知脚本阶段")
        }
        ensure(context)

        val command = buildString {
            append("if [ ! -s ").append(q(path)).append(" ]; then exit 0; fi; ")
            append("export HETU_HOOK=").append(q(stage)).append("; ")
            append("export HETU_MODE=").append(q(mode)).append("; ")
            append("export HETU_CONFIG=").append(q(config)).append("; ")
            append("export HETU_BASE=/data/adb/hetu; ")
            append("export HETU_RUN_DIR=/data/adb/hetu/run; ")
            append("export HETU_SCRIPT_DIR=").append(q(ROOT)).append("; ")
            append("{ printf '\\n'; date '+[%Y-%m-%d %H:%M:%S] hook=")
            append(stage.replace("%", "")).append(" mode=")
            append(mode.replace("%", "")).append(" config=")
            append(config.replace("%", "")).append("'; ")
            append("if command -v timeout >/dev/null 2>&1; then timeout 12 sh ")
            append(q(path)).append("; else sh ").append(q(path)).append("; fi; ")
            append("} >> ").append(q(LOG)).append(" 2>&1")
        }

        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（" + result.code + "），请查看 scripts.log",
            )
        }
        "脚本执行完成"
    }

    fun environmentText(): String = """
        HETU_HOOK       pre-start / post-stop / manual
        HETU_MODE       当前运行模式
        HETU_CONFIG     当前配置名称
        HETU_BASE       /data/adb/hetu
        HETU_RUN_DIR    /data/adb/hetu/run
        HETU_SCRIPT_DIR /data/adb/hetu/scripts

        脚本使用 /system/bin/sh 执行，最长 12 秒。
        非 0 退出码会阻止对应的手动启动/停止操作，并写入：
        /data/adb/hetu/run/scripts.log
    """.trimIndent()
}
}s"
            done
        """.trimIndent().replace("${ROOT}", q(ROOT))
        val result = RootBridge.rootShell(context.applicationContext, command, 6_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本列表读取失败" })
        result.output.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 2) return@mapNotNull null
            val name = runCatching { managedName(parts[0]) }.getOrNull() ?: return@mapNotNull null
            ManagedScript(name, ROOT + "/" + name, parts[1].toLongOrNull() ?: 0L)
        }.sortedBy { it.name.lowercase() }.toList()
    }

    suspend fun importManaged(context: Context, fileName: String, bytes: ByteArray): ManagedScript = withContext(Dispatchers.IO) {
        require(bytes.size <= 64 * 1024) { "脚本最多 64 KiB" }
        require(bytes.none { it.toInt() == 0 }) { "不能导入二进制脚本" }
        val text = bytes.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "脚本不是有效 UTF-8 文本" }
        val name = managedName(fileName)
        val path = managedPath(name)
        ensure(context)
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val tmp = path + ".new"
        val command =
            "printf '%s' " + q(encoded) +
            " | base64 -d > " + q(tmp) +
            " && chmod 700 " + q(tmp) +
            " && mv -f " + q(tmp) + " " + q(path)
        val result = RootBridge.rootShell(context.applicationContext, command, 8_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本导入失败" })
        ManagedScript(name, path, bytes.size.toLong())
    }

    suspend fun deleteManaged(context: Context, name: String) = withContext(Dispatchers.IO) {
        val path = managedPath(name)
        val result = RootBridge.rootShell(context.applicationContext, "rm -f " + q(path), 6_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本删除失败" })
    }

    suspend fun runManaged(context: Context, name: String): String = withContext(Dispatchers.IO) {
        val path = managedPath(name)
        ensure(context)
        val prefs = context.applicationContext.getSharedPreferences("hetu", Context.MODE_PRIVATE)
        val mode = prefs.getString("proxyUiLastMode", "").orEmpty()
        val config = prefs.getString("proxyUiLastConfig", "").orEmpty()
        val command = buildString {
            append("test -f ").append(q(path)).append(" || exit 44; ")
            append("export HETU_HOOK=manual; ")
            append("export HETU_MODE=").append(q(mode)).append("; ")
            append("export HETU_CONFIG=").append(q(config)).append("; ")
            append("export HETU_BASE=/data/adb/hetu; ")
            append("export HETU_RUN_DIR=/data/adb/hetu/run; ")
            append("export HETU_SCRIPT_DIR=").append(q(ROOT)).append("; ")
            append("{ printf '\\n'; date '+[%Y-%m-%d %H:%M:%S] manual=").append(name.replace("%", "")).append("'; ")
            append("if command -v timeout >/dev/null 2>&1; then timeout 12 sh ").append(q(path))
            append("; else sh ").append(q(path)).append("; fi; } >> ").append(q(LOG)).append(" 2>&1")
        }
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止"
                else if (result.code == 44) "脚本不存在"
                else "脚本执行失败（" + result.code + "），请查看 scripts.log",
            )
        }
        "脚本执行完成 · $name"
    }

    suspend fun run(
        context: Context,
        stage: String,
        mode: String,
        config: String,
    ): String = withContext(Dispatchers.IO) {
        val path = when (stage) {
            "pre-start" -> PRE_START
            "post-stop" -> POST_STOP
            else -> error("未知脚本阶段")
        }
        ensure(context)

        val command = buildString {
            append("if [ ! -s ").append(q(path)).append(" ]; then exit 0; fi; ")
            append("export HETU_HOOK=").append(q(stage)).append("; ")
            append("export HETU_MODE=").append(q(mode)).append("; ")
            append("export HETU_CONFIG=").append(q(config)).append("; ")
            append("export HETU_BASE=/data/adb/hetu; ")
            append("export HETU_RUN_DIR=/data/adb/hetu/run; ")
            append("export HETU_SCRIPT_DIR=").append(q(ROOT)).append("; ")
            append("{ printf '\\n'; date '+[%Y-%m-%d %H:%M:%S] hook=")
            append(stage.replace("%", "")).append(" mode=")
            append(mode.replace("%", "")).append(" config=")
            append(config.replace("%", "")).append("'; ")
            append("if command -v timeout >/dev/null 2>&1; then timeout 12 sh ")
            append(q(path)).append("; else sh ").append(q(path)).append("; fi; ")
            append("} >> ").append(q(LOG)).append(" 2>&1")
        }

        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（" + result.code + "），请查看 scripts.log",
            )
        }
        "脚本执行完成"
    }

    fun environmentText(): String = """
        HETU_HOOK       pre-start / post-stop / manual
        HETU_MODE       当前运行模式
        HETU_CONFIG     当前配置名称
        HETU_BASE       /data/adb/hetu
        HETU_RUN_DIR    /data/adb/hetu/run
        HETU_SCRIPT_DIR /data/adb/hetu/scripts

        脚本使用 /system/bin/sh 执行，最长 12 秒。
        非 0 退出码会阻止对应的手动启动/停止操作，并写入：
        /data/adb/hetu/run/scripts.log
    """.trimIndent()
}
