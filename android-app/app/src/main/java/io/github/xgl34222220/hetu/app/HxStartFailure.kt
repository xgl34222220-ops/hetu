package io.github.xgl34222220.hetu.app

import io.github.xgl34222220.hetu.ProxyStartProblem

/** How a failed manual start is handled; decided by the exception type, never by its wording. */
internal enum class HxStartFailure {
    /** No configuration selected, or the built-in placeholder: shown at once, no settle / diagnostics. */
    Config,
    /** 「仅所选应用代理」 without a usable app: shown at once with the app-list guidance. */
    AppScope,
    /** Anything raised after Root was asked to start: settle, then diagnostics. */
    Runtime,
}

internal fun hxStartFailure(error: Throwable): HxStartFailure {
    var cause: Throwable? = error
    var depth = 0
    while (cause != null && depth < 6) {
        when (cause) {
            is ProxyStartProblem.AppScope -> return HxStartFailure.AppScope
            is ProxyStartProblem -> return HxStartFailure.Config
        }
        cause = cause.cause?.takeIf { it !== cause }
        depth++
    }
    return HxStartFailure.Runtime
}
