package io.github.xgl34222220.hetu

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What happened to a saved base-config change on the running core. */
internal sealed interface HxApplyOutcome {
    /** The core hot-reloaded the new source. */
    data class Applied(val message: String) : HxApplyOutcome
    /** The proxy is not running: the saved source is used at the next start. */
    data object Deferred : HxApplyOutcome
    /** Saved, but the core asked for a full restart (network-layer change) or was busy; nothing was rolled back. */
    data class NeedsRestart(val reason: String) : HxApplyOutcome
    /** The core rejected the change; the source was restored, so file and running copy agree again. */
    data class RolledBack(val reason: String) : HxApplyOutcome
    /** The core rejected the change and restoring the source failed as well. */
    data class RollbackFailed(val reason: String, val rollback: String) : HxApplyOutcome
}

/**
 * The base-config transaction shared by 编辑配置 and 导入配置:
 * strict decode → Mihomo validation → atomic write (ProxyConfigLibrary) → hot reload → roll the source
 * back when the running core rejects it. The running copy itself is already restored by
 * `RootProxyManager.reloadCurrentConfig` when its reload fails.
 */
internal object HxConfigTransaction {
    const val LIMIT = 4 * 1024 * 1024

    private val configKeys = Regex("^(proxies|proxy-providers|proxy-groups|rules)\\s*:", RegexOption.MULTILINE)

    /** True when [text] has at least one top-level Clash/Mihomo section a usable config needs. */
    fun looksLikeConfig(text: String): Boolean = configKeys.containsMatchIn(text)

    /** Strict UTF-8 text of an imported config; rejects empty, oversize, binary and non-config input. */
    @Throws(IOException::class)
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) throw IOException("配置为空")
        if (bytes.size > LIMIT) throw IOException("配置超过 4 MiB")
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw IOException("配置不是 UTF-8 文本")
        }
        val body = text.removePrefix("\uFEFF")
        if (body.isBlank()) throw IOException("配置为空")
        if (body.indexOf('\u0000') >= 0) throw IOException("配置不是文本文件")
        if (!looksLikeConfig(body)) throw IOException("内容不是 Clash/Mihomo YAML 配置（可能是 Base64 节点列表，请改用「添加订阅」）")
        return body
    }

    /** Core answers that mean “saved, apply later” rather than “this config is wrong”. */
    fun isDeferral(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return message.contains("请使用「重启」") || message.contains("代理未运行") || message.contains("正在执行其他操作")
    }

    /**
     * Applies an already validated and saved change. [reload] hot-reloads the running core; when it
     * rejects the change, [rollback] restores the previous source. Callers run this NonCancellable so
     * leaving the page cannot strand a half-applied change.
     */
    suspend fun apply(running: Boolean, reload: suspend () -> String, rollback: suspend () -> Unit): HxApplyOutcome {
        if (!running) return HxApplyOutcome.Deferred
        val failure = try {
            return HxApplyOutcome.Applied(reload())
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            error
        }
        val reason = failure.message?.takeIf { it.isNotBlank() } ?: "重载失败"
        if (isDeferral(failure)) return HxApplyOutcome.NeedsRestart(reason)
        return try {
            rollback()
            HxApplyOutcome.RolledBack(reason)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            HxApplyOutcome.RollbackFailed(reason, error.message?.takeIf { it.isNotBlank() } ?: "回滚失败")
        }
    }

    /** One line for a toast or banner; [saved] names what was written. */
    fun describe(outcome: HxApplyOutcome, saved: String): String = when (outcome) {
        is HxApplyOutcome.Applied -> "$saved，已热重载生效"
        HxApplyOutcome.Deferred -> "$saved，下次启动生效"
        is HxApplyOutcome.NeedsRestart -> "$saved；未热重载：${outcome.reason}"
        is HxApplyOutcome.RolledBack -> "应用失败，已回滚到修改前的配置：${outcome.reason}"
        is HxApplyOutcome.RollbackFailed -> "应用失败：${outcome.reason}；回滚也失败：${outcome.rollback}"
    }

    /** Reads at most [LIMIT] bytes; closes [input]. */
    @Throws(IOException::class)
    fun readBounded(input: InputStream): ByteArray = input.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (output.size() + read > LIMIT) throw IOException("配置超过 4 MiB")
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }

    suspend fun readUri(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        readBounded(context.contentResolver.openInputStream(uri) ?: throw IOException("无法读取配置文件"))
    }

    /** Downloads a full YAML config. Returns the body and the file name the server suggested (may be blank). */
    suspend fun download(url: String): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as? HttpURLConnection) ?: throw IOException("请输入有效的 http/https 链接")
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "clash.meta")
            connection.setRequestProperty("Accept", "*/*")
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("下载失败：HTTP $code")
            val disposition = connection.getHeaderField("Content-Disposition").orEmpty()
            val suggested = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
                .find(disposition)?.groupValues?.getOrNull(1)?.let { Uri.decode(it) }.orEmpty()
            readBounded(connection.inputStream) to suggested
        } finally {
            connection.disconnect()
        }
    }
}
