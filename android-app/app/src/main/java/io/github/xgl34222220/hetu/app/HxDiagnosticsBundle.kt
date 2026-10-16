package io.github.xgl34222220.hetu

import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * func-400 D1: 工具 › 诊断与维护 › 导出诊断包.
 *
 * One text file with everything a network-loss / start-failure report needs: the existing
 * 消息与网络诊断 report (already redacted), plus the self-heal state the watchdog keeps under
 * /data/adb/hetu/run (watchdog.log, selfheal/egress/cgroup-escape state, Wi-Fi heal count), the
 * policy-routing / iptables state and the core processes. Read-only: the Root command only reads
 * files and lists rules; nothing is started, stopped or rewritten. Controller secret and full URLs
 * are redacted with the same [DiagnosticReport.redact] as the on-screen report.
 */
internal object HxDiagnosticsBundle {
    const val MIME = "text/plain"
    private const val RUN = "/data/adb/hetu/run"
    private const val ROOT_TIMEOUT_MS = 15_000L
    private const val SECTION_LIMIT = 400_000

    fun fileName(now: Long = System.currentTimeMillis()): String =
        "hetu-diagnostics-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now)) + ".txt"

    /** The read-only Root script; every step tolerates a missing file or tool. */
    val ROOT_COMMAND: String = listOf(
        "R=$RUN",
        "for f in start-state last-start-error last-crash selfheal-state egress-state cgroup-escape rollback-state " +
            "network-repair-at sysctl-tune.state state/selfheal-budget state/wifi-reconnects state/core.kind; do " +
            "echo \"--- \$f ---\"; cat \"\$R/\$f\" 2>/dev/null; echo; done",
        "echo '--- watchdog.log (last 300 lines) ---'; tail -n 300 \"\$R/watchdog.log\" 2>/dev/null",
        "echo '--- network-repair.log ---'; tail -n 60 \"\$R/network-repair.log\" 2>/dev/null",
        "echo '--- ebpf.log ---'; tail -n 40 \"\$R/ebpf.log\" 2>/dev/null",
        "echo '--- share.log ---'; tail -n 30 \"\$R/share.log\" 2>/dev/null",
        "echo '--- core.log (last 200 lines) ---'; tail -n 200 \"\$R/core.log\" 2>/dev/null",
        "echo '--- processes ---'; ps -A -o PID,PPID,STAT,NAME 2>/dev/null | grep -iE 'mihomo|clash|sing-box|xray|v2ray|hysteria|hetu' | grep -v grep",
        "echo '--- ip rule ---'; ip rule show 2>/dev/null; echo '--- ip -6 rule ---'; ip -6 rule show 2>/dev/null",
        "echo '--- ip route (policy tables) ---'; ip route show table all 2>/dev/null | grep -vE '^(local|broadcast) ' | head -n 120",
        "echo '--- iptables mangle ---'; iptables-save -t mangle 2>/dev/null | head -n 260",
        "echo '--- iptables nat ---'; iptables-save -t nat 2>/dev/null | head -n 200",
        "echo '--- iptables filter (Hetu) ---'; iptables-save -t filter 2>/dev/null | grep -E 'HETU|BICHEN|^\\*|^:' | head -n 160",
        "echo '--- ip6tables mangle ---'; ip6tables-save -t mangle 2>/dev/null | head -n 200",
        "echo '--- ip6tables nat ---'; ip6tables-save -t nat 2>/dev/null | head -n 120",
        "echo '--- connectivity ---'; dumpsys connectivity 2>/dev/null | grep -E 'NetworkAgentInfo|VALIDATED|PARTIAL_CONNECTIVITY|CAPTIVE|Active default network' | head -n 40",
        "true",
    ).joinToString("\n")

    /** Device / App header; no identifiers beyond model and versions. */
    fun header(context: Context, now: Long = System.currentTimeMillis()): String {
        val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        return buildString {
            append("河图诊断包 / Hetu diagnostics\n")
            append("time=").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(now))).append('\n')
            append("app=").append(version).append('\n')
            append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("android=").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
            append("abis=").append(Build.SUPPORTED_ABIS.joinToString(",")).append('\n')
            append("note=密钥与完整 URL 已脱敏；包含本机 IP 与路由信息，分享前请确认\n")
        }
    }

    /**
     * Builds the bundle. [report] is the existing on-screen diagnostics; [rootRead] runs [ROOT_COMMAND]
     * (replaceable in tests). A failing part is written as its error text, never aborting the bundle.
     */
    suspend fun build(
        context: Context,
        report: suspend () -> String,
        rootRead: (String) -> String = { command -> readRoot(context, command) },
    ): String = withContext(Dispatchers.IO) {
        val secret = context.getSharedPreferences("hetu", Context.MODE_PRIVATE).getString("proxyControllerSecret", "").orEmpty()
        val diagnostics = try { report() } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) {
            "读取失败：" + hxErrorMessage(error, "读取失败")
        }
        val selfHeal = try { rootRead(ROOT_COMMAND) } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) {
            "读取失败：" + hxErrorMessage(error, "读取失败")
        }
        buildString {
            append(header(context)).append('\n')
            append("===== 消息与网络诊断 =====\n")
            append(DiagnosticReport.redact(diagnostics, secret).take(SECTION_LIMIT)).append("\n\n")
            append("===== 看门狗 / 自愈 / 路由与防火墙状态 =====\n")
            append(DiagnosticReport.redact(selfHeal, secret).take(SECTION_LIMIT)).append('\n')
        }
    }

    private fun readRoot(context: Context, command: String): String {
        val result = RootBridge.rootShell(context, command, ROOT_TIMEOUT_MS)
        return if (result.ok()) result.output else "exit=${result.code}\n${result.output}"
    }

    /** Writes [text] to the document the user picked. */
    suspend fun write(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("无法写入导出文件")
        output.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}
