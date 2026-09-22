package io.github.xgl34222220.hetu

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object ProxyScriptHooks {
    const val ROOT = "/data/adb/hetu/scripts"
    const val PRE_START = "$ROOT/pre-start.sh"
    const val POST_STOP = "$ROOT/post-stop.sh"
    const val LOG = "/data/adb/hetu/run/scripts.log"

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun validate(path: String) {
        require(path == PRE_START || path == POST_STOP) { "未知脚本路径" }
    }

    suspend fun ensure(context: Context) = withContext(Dispatchers.IO) {
        val result = RootBridge.rootShell(
            context.applicationContext,
            "mkdir -p " + q(ROOT) + " /data/adb/hetu/run && chmod 700 " + q(ROOT),
            6_000L,
        )
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "无法创建脚本目录" })
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
        require(text.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "脚本最多 64 KiB" }
        ensure(context)
        val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val tmp = path + ".new"
        val command = "printf '%s' " + q(encoded) +
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
            append("{ printf '\\n[%s] hook=%s mode=%s config=%s\\n' ")
            append("\"\\$(date '+%Y-%m-%d %H:%M:%S')\" ")
            append(q(stage)).append(" ").append(q(mode)).append(" ").append(q(config)).append("; ")
            append("if command -v timeout >/dev/null 2>&1; then timeout 12 sh ")
            append(q(path)).append("; else sh ").append(q(path)).append("; fi; ")
            append("RC=\\$?; printf '[hook-exit] %s\\n' \"\\$RC\"; exit \"\\$RC\"; ")
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
        HETU_HOOK       pre-start / post-stop
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
