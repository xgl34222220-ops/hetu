package io.github.xgl34222220.hetu.panel

/**
 * Which panel tabs the running core can feed. Pure: no Compose or Android types.
 *
 * Mihomo / Mihomo Smart: everything. sing-box / reF1nd (clash_api, checked against 1.14.3):
 * /proxies, /group/{name}/delay, /proxies/{name}/delay, /connections, /rules, PUT /proxies/{group}
 * and DELETE /connections work; /providers/proxies is `{}` and /providers/rules `[]` with every
 * refresh/healthcheck 404, so 订阅 and 规则集 are unsupported. Xray / V2Fly / Hysteria 2 have no
 * Clash controller at all: every controller tab is unsupported, 日志 (read from the runtime log)
 * still works. A user-configured external Clash API is not gated: it is not the local core.
 */
internal data class PanelCoreGate(
    /** Label of the running core, for 「当前核心（X）不支持此功能」. */
    val core: String = "",
    /** Unsupported tab → feature name shown on the card. */
    val features: Map<PanelTab, String> = emptyMap(),
    /** Unsupported tab → why, and what still works. */
    val details: Map<PanelTab, String> = emptyMap(),
    /** False when the core has no Clash controller: 测速 / 延迟 has nothing to ask. */
    val latency: Boolean = true,
) {
    fun supports(tab: PanelTab): Boolean = tab !in features

    /** A tab that the core does support, for the card's way out. */
    fun fallbackFor(tab: PanelTab): PanelTab? = when {
        tab == PanelTab.Subscriptions && supports(PanelTab.Groups) -> PanelTab.Groups
        tab == PanelTab.RuleSets && supports(PanelTab.Rules) -> PanelTab.Rules
        tab != PanelTab.Logs && supports(PanelTab.Logs) -> PanelTab.Logs
        else -> null
    }

    companion object {
        val Full = PanelCoreGate()

        private val featureNames = mapOf(
            PanelTab.Overview to "概览 · 流量与连接排行",
            PanelTab.Groups to "策略组 · 节点切换与测速",
            PanelTab.Subscriptions to "订阅 · 代理集合",
            PanelTab.Connections to "连接列表",
            PanelTab.Rules to "规则列表",
            PanelTab.RuleSets to "规则集",
        )

        /**
         * @param label running core label; [clashApi] / [providers] / [singleServer] from ProxyCoreSupport
         * (built by `ProxyCoreSupport.panelGate`, the core model is package-private Java).
         */
        fun of(label: String, clashApi: Boolean, providers: Boolean, singleServer: Boolean, customApi: Boolean): PanelCoreGate {
            if (customApi || providers) return Full
            if (!clashApi) {
                val why = if (singleServer)
                    "$label 是单服务器客户端，没有策略组，也没有 Clash 兼容控制接口，河图无法读取这部分数据。代理本身照常运行，日志仍可查看。"
                else
                    "$label 没有 Clash 兼容控制接口，河图无法读取这部分数据。代理与规则分流照常运行，日志仍可查看；需要面板时可在 设置 › 基础代理配置 换用 Mihomo 或 sing-box。"
                return PanelCoreGate(label, featureNames, featureNames.mapValues { why }, latency = false)
            }
            // sing-box family: the clash_api has no provider endpoints.
            val unsupported = listOf(PanelTab.Subscriptions, PanelTab.RuleSets)
            return PanelCoreGate(
                label,
                featureNames.filterKeys { it in unsupported },
                mapOf(
                    PanelTab.Subscriptions to "$label 的 clash_api 不提供代理集合（proxy providers）接口。订阅节点在启动时已展开为普通出站，可在「策略」中查看、切换与测速。",
                    PanelTab.RuleSets to "$label 的 clash_api 不提供规则集（rule providers）接口。规则集由核心自行下载并缓存，重启代理即可更新；生效的规则可在「规则」中查看。",
                ),
                latency = true,
            )
        }
    }
}
