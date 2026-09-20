package io.github.xgl34222220.hetu

/** Preserve exact ordering, including duplicate rules. */
internal fun instrumentRuleBatches(rules: List<ProxyRuleUi>): List<List<ProxyRuleUi>> = rules.chunked(15)
