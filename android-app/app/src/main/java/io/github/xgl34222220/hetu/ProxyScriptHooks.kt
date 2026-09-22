package io.github.xgl34222220.hetu

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object ProxyScriptHooks {
    const val ROOT = "/data/adb/hetu/scripts"
    const val PRE_START = "$ROOT/pre-start.sh"
    const val POST_STOP = "$ROOT/post-stop.sh"
    const val LOG = "/data/adb/hetu/run/scripts.log"

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    suspend fun ensure(context: Context) = withContext(Dispatchers.IO) {
        RootBridge.rootShell(
            context.applicationContext,
            "mkdir -p ${q(ROOT)} /data/adb/hetu/run && chmod 700 ${q(ROOT)}",
            6_000L,
        )
    }

    suspend fun read(context: Context, path: String): String = withContext(Dispatchers.IO) {
        require(path == PRE_START || path == POST_STOP)
        ensure(context)
        val result = RootBridge.rootShell(
            context.applicationContext,
            "if [ -f ${q(path)} ]; then head -c 131072 ${q(path)}; fi",
            6_000L,
        )
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本读取失败" })
        result.output
    }

    suspend fun write(context: Context, path: String, text: String) = withContext(Dispatchers.IO) {
        require(path == PRE_START || path == POST_STOP)
        require(text.length <= 64 * 1024) { "脚本最多 64 KiB" }
        ensure(context)
        val encoded = android.util.Base64.encodeToString(
            text.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
        val command = buildString {
            append("TMP=").append(q("$path.new.\$\$")).append("; ")
            append("printf '%s' ").append(q(encoded))
            append(" | base64 -d > \"\$TMP\" || exit 1; ")
            append("chmod 700 \"\$TMP\" && mv -f \"\$TMP\" ").append(q(path))
        }
        val result = RootBridge.rootShell(context.applicationContext, command, 8_000L)
        if (!result.ok()) throw IllegalStateException(result.output.ifBlank { "脚本保存失败" })
    }

    suspend fun clear(context: Context, path: String) = withContext(Dispatchers.IO) {
        require(path == PRE_START || path == POST_STOP)
        val result = RootBridge.rootShell(
            context.applicationContext,
            "rm -f ${q(path)}",
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
        val command = """
            FILE=${q(path)}
            [ -s "${'
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "${'
              else
                sh "${'
              fi
              RC=${'
              printf '[hook-exit] %s\n' "${'
              exit "${'
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_HOOK" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_MODE" "${'
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
}FILE" ] || exit 0
            export HETU_HOOK=${q(stage)}
            export HETU_MODE=${q(mode)}
            export HETU_CONFIG=${q(config)}
            export HETU_BASE=/data/adb/hetu
            export HETU_RUN_DIR=/data/adb/hetu/run
            export HETU_SCRIPT_DIR=${q(ROOT)}
            {
              printf '\n[%s] hook=%s mode=%s config=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$HETU_HOOK" "$HETU_MODE" "$HETU_CONFIG"
              if command -v timeout >/dev/null 2>&1; then
                timeout 12 sh "$FILE"
              else
                sh "$FILE"
              fi
              RC=$?
              printf '[hook-exit] %s\n' "$RC"
              exit "$RC"
            } >> ${q(LOG)} 2>&1
        """.trimIndent()
        val result = RootBridge.rootShell(context.applicationContext, command, 18_000L)
        if (!result.ok()) {
            throw IllegalStateException(
                if (result.code == 124) "脚本执行超时，已停止本次操作"
                else "脚本执行失败（${result.code}），请查看 scripts.log",
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
