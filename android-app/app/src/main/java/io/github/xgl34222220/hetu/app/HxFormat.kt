package io.github.xgl34222220.hetu

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object HxFormat {
    fun bytes(value: Long): String {
        val v = value.coerceAtLeast(0L).toDouble()
        return when {
            v < 1024 -> "${v.toLong()} B"
            v < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", v / 1024)
            v < 1024.0 * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", v / 1024 / 1024)
            v < 1024.0 * 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", v / 1024 / 1024 / 1024)
            else -> String.format(Locale.US, "%.2f TB", v / 1024 / 1024 / 1024 / 1024)
        }
    }

    /** Splits "12.3 MB/s" into number and unit so the number can be styled larger. */
    fun speedParts(bytesPerSecond: Long): Pair<String, String> {
        val text = bytes(bytesPerSecond)
        val space = text.lastIndexOf(' ')
        return if (space > 0) text.substring(0, space) to (text.substring(space + 1) + "/s") else text to "/s"
    }

    fun speed(bytesPerSecond: Long): String = bytes(bytesPerSecond) + "/s"

    fun duration(seconds: Long): String {
        if (seconds <= 0L) return "—"
        val d = seconds / 86_400
        val h = (seconds % 86_400) / 3_600
        val m = (seconds % 3_600) / 60
        val s = seconds % 60
        return when {
            d > 0 -> "${d}天 ${h}时"
            h > 0 -> "${h}时 ${m}分"
            m > 0 -> "${m}分 ${s}秒"
            else -> "${s}秒"
        }
    }

    /** Core delay semantics: >0 ms, -1 timeout, -2 failed, null untested. */
    fun delay(value: Long?): String = when {
        value == null -> "—"
        value == -1L -> "超时"
        value < 0L -> "失败"
        value == 0L -> "—"
        else -> "$value"
    }

    @Composable
    fun delayColor(value: Long?): Color {
        val c = Hx.colors
        return when {
            value == null || value == 0L -> c.textFaint
            value < 0L -> c.bad
            value < 800L -> c.accent
            else -> c.warn
        }
    }

    fun expireDays(epochSeconds: Long): Long? {
        if (epochSeconds <= 0L) return null
        val millis = if (epochSeconds > 100_000_000_000L) epochSeconds else epochSeconds * 1000L
        return ((millis - System.currentTimeMillis()) / 86_400_000L)
    }

    fun expireLabel(epochSeconds: Long): String {
        val days = expireDays(epochSeconds) ?: return "长期有效"
        val millis = if (epochSeconds > 100_000_000_000L) epochSeconds else epochSeconds * 1000L
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))
        return when {
            days < 0 -> "已过期 · $date"
            days == 0L -> "今天到期"
            else -> "剩余 $days 天 · $date"
        }
    }

    fun ago(epochMillis: Long): String {
        if (epochMillis <= 0L) return "从未"
        val diff = (System.currentTimeMillis() - epochMillis).coerceAtLeast(0L) / 1000L
        return when {
            diff < 60 -> "刚刚"
            diff < 3_600 -> "${diff / 60} 分钟前"
            diff < 86_400 -> "${diff / 3_600} 小时前"
            else -> "${diff / 86_400} 天前"
        }
    }

    /** Parses Mihomo RFC3339 timestamps ("2026-09-28T10:11:12.123+08:00"). */
    fun isoAgo(raw: String): String {
        val text = raw.trim()
        if (text.isBlank()) return "未更新"
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss")
        val cleaned = text.replace(Regex("\\.(\\d{3})\\d+"), ".$1")
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    if (!pattern.endsWith("XXX")) timeZone = TimeZone.getDefault()
                }.parse(cleaned)
            }.getOrNull()
            if (parsed != null) return ago(parsed.time)
        }
        return text.take(19).replace('T', ' ')
    }

    fun time(epochMillis: Long): String =
        if (epochMillis <= 0L) "—" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))

    fun count(value: Long): String = when {
        value < 10_000 -> value.toString()
        value < 100_000_000 -> String.format(Locale.US, "%.1f万", value / 10_000.0)
        else -> String.format(Locale.US, "%.1f亿", value / 100_000_000.0)
    }

    fun flag(countryCode: String): String {
        val code = countryCode.trim().uppercase(Locale.ROOT)
        if (code.length != 2 || !code.all { it in 'A'..'Z' }) return ""
        val first = Character.codePointAt(code, 0) - 'A'.code + 0x1F1E6
        val second = Character.codePointAt(code, 1) - 'A'.code + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    fun groupType(type: String): String = when (type.lowercase(Locale.ROOT)) {
        "selector" -> "手动选择"
        "urltest" -> "自动测速"
        "fallback" -> "故障转移"
        "loadbalance" -> "负载均衡"
        "relay" -> "链式代理"
        "smart" -> "智能"
        else -> type
    }

    /** Mihomo accepts PUT on Selector and pins URLTest/Fallback; LoadBalance/Relay cannot be pinned. */
    fun isSelectable(type: String): Boolean = type.lowercase(Locale.ROOT) in setOf("selector", "urltest", "fallback", "smart")
}
