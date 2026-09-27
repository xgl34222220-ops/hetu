package io.github.xgl34222220.hetu

import java.util.Locale

/**
 * First layer of the BoxProxy-style rule inspection feature.
 *
 * This keeps rule diagnosis separate from live traffic handling. It only reports
 * what the local Mihomo configuration exposes and never pretends to know a
 * connection's actual path without runtime evidence.
 */
internal object ProxyRuleInspector {
    internal data class Result(
        val target: String,
        val normalized: String,
        val matches: List<String>,
        val finalPolicy: String,
    )

    fun inspect(target: String, yaml: String): Result {
        val normalized = target.trim().lowercase(Locale.US)
        if (normalized.isBlank()) {
            return Result(target, normalized, emptyList(), "未知")
        }
        val hits = yaml.lineSequence()
            .map { it.trim() }
            .filter { it.contains("RULE-SET", true) || it.contains("DOMAIN", true) || it.contains("MATCH", true) }
            .filter { line -> line.contains(normalized, true) || line.contains("MATCH", true) }
            .take(20)
            .toList()
        val policy = hits.lastOrNull()
            ?.substringAfterLast(',')
            ?.trim()
            ?.ifBlank { "未解析" }
            ?: "未命中"
        return Result(target, normalized, hits, policy)
    }
}
