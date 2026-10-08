package io.github.xgl34222220.hetu
internal data class ProxyNodeUi(
    val name: String,
    val type: String = "",
    val udp: Boolean = false,
    val lastDelay: Long? = null,
    val provider: String = "",
    val lastDelayAt: Long = 0L,
)

internal data class ProxyGroupUi(
    val name: String,
    val type: String,
    val now: String,
    val nodes: List<ProxyNodeUi>,
    val iconUrl: String = "",
    val iconPath: String = "",
    val hidden: Boolean = false,
)


internal object MihomoControllerClient { const val IPV6_DELAY_URL="https://[2606:4700:4700::1111]/cdn-cgi/trace" }
