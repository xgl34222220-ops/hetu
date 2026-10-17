package io.github.xgl34222220.hetu

/**
 * Foreground status poll cadence. Every poll of a stopped proxy is a Root `status` call (su +
 * hetu-root.sh), so a stopped, idle proxy is probed every [IdleDelayMs] instead of every 2 s. The
 * wait is taken in [WakeStepMs] steps and ends early once an operation starts or the cached running
 * hint flips (tile, boot or network-match start), so such a start still shows within about a second.
 */
internal object HxPollCadence {
    const val IdleDelayMs = 8_000L
    const val WakeStepMs = 1_000L

    /** The cadence while the proxy runs or an operation is in progress (unchanged from before). */
    fun activeDelayMs(homeOrPanel: Boolean): Long = if (homeOrPanel) 2_000L else 4_000L

    fun delayMs(homeOrPanel: Boolean, running: Boolean, operationActive: Boolean, runningHint: Boolean): Long =
        if (running || operationActive || runningHint) activeDelayMs(homeOrPanel) else IdleDelayMs
}
