package io.github.xgl34222220.hetu

import java.math.BigInteger
import java.util.Locale

/**
 * 高级代理配置 › 资源限制, checked by the rules hetu-root.sh applies when the proxy starts
 * (`cpu_mask`, `mem_bytes`, `tuning_valid`), so a value the script would refuse is caught while
 * typing instead of at the next start. An empty value means "no limit" there, as in the script.
 */
internal object HxResourceLimits {
    const val CPU_ERROR = "CPU 核心分配格式无效：请填写 0-7 或 0,2,4-6 这样的核心编号（0-23）"
    const val MEMORY_ERROR = "内存限制格式无效：请填写 128M、1G 这样的值，且不低于 32M"
    const val MEMORY_MAX_ERROR = "内存限制不能超过 16G"
    const val IO_ERROR = "磁盘 I/O 权重应为 0-7"
    const val CPU_BEYOND_DEVICE = "本机可用 %s 个 CPU（0-%s），超出的编号不会生效"

    private val MIN_MEMORY = BigInteger.valueOf(33_554_432L)
    private val MAX_MEMORY = BigInteger.valueOf(17_179_869_184L)

    /** The affinity mask `cpu_mask` would print, or null where it fails. */
    fun cpuMask(value: String): Long? {
        if (value.isEmpty() || value.any { it !in '0'..'9' && it != ',' && it != '-' }) return null
        // `IFS=, set -- $1` drops one trailing empty field ("0," is "0"); any other empty part fails.
        val parts = value.split(',').let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }
        var mask = 0L
        for (part in parts) {
            if (part.isEmpty() || part.startsWith('-') || part.endsWith('-') || part.count { it == '-' } > 1) return null
            val a = part.substringBefore('-')
            val b = part.substringAfter('-', a)
            if (listOf(a, b).any { (it.length > 1 && it[0] == '0') || it.length > 2 }) return null
            val from = a.toInt()
            val to = b.toInt()
            if (from > to || to > 23) return null
            for (cpu in from..to) mask = mask or (1L shl cpu)
        }
        return mask.takeIf { it > 0L }
    }

    /** Bytes `mem_bytes` would print (32M to 16G, K/M/G with an optional B or iB), or null where it fails. */
    fun memoryBytes(value: String): Long? = memoryValue(value)?.takeIf { it >= MIN_MEMORY && it <= MAX_MEMORY }?.toLong()

    private fun memoryValue(value: String): BigInteger? {
        val upper = value.uppercase(Locale.ROOT).replace(Regex("I?B$"), "")
        val match = Regex("^([0-9]+)([KMG]?)$").matchEntire(upper) ?: return null
        val unit = when (match.groupValues[2]) { "K" -> 1_024L; "M" -> 1_048_576L; "G" -> 1_073_741_824L; else -> 1L }
        return BigInteger(match.groupValues[1]).multiply(BigInteger.valueOf(unit))
    }

    fun cpuError(value: String): String? = if (value.isEmpty() || cpuMask(value) != null) null else CPU_ERROR

    fun memoryError(value: String): String? {
        if (value.isEmpty()) return null
        val bytes = memoryValue(value) ?: return MEMORY_ERROR
        return when {
            bytes < MIN_MEMORY -> MEMORY_ERROR
            bytes > MAX_MEMORY -> MEMORY_MAX_ERROR
            else -> null
        }
    }

    fun ioError(value: String): String? = if (value.isEmpty() || (value.length == 1 && value[0] in '0'..'7')) null else IO_ERROR

    /** Highest CPU the value names when it is past this device's last one; the script still accepts it. */
    fun cpuBeyondDevice(value: String, available: Int = Runtime.getRuntime().availableProcessors()): Int? {
        val mask = cpuMask(value) ?: return null
        val highest = 63 - java.lang.Long.numberOfLeadingZeros(mask)
        return highest.takeIf { available > 0 && it >= available }
    }
}

/** What the notification shows for [template]: every `{name}` the status service fills, replaced the same way. */
internal fun hxNotificationPreview(template: String, values: Map<String, String>): String {
    var text = template
    values.forEach { (key, value) -> text = text.replace("{$key}", value) }
    return text
}

/** Stand-in values for the notification preview, in the formats the status service uses. */
internal fun hxNotificationPreviewValues(): Map<String, String> = mapOf(
    "status" to "运行中",
    "uptime" to HxFormat.duration(5_025L),
    "upload" to HxFormat.speed(48_000L),
    "download" to HxFormat.speed(1_260_000L),
    "upload_total" to HxFormat.bytes(86_000_000L),
    "download_total" to HxFormat.bytes(1_420_000_000L),
    "cpu" to String.format(Locale.US, "%.1f%%", 3.2f),
    "memory" to HxFormat.bytes(62_000_000L),
    "connections" to "42",
    "config" to "config.yaml",
    "core" to "mihomo",
    "mode" to "rule",
)
