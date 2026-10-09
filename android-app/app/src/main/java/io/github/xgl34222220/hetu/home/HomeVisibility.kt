package io.github.xgl34222220.hetu.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/**
 * True while the hosting screen is at least STARTED. A composition stays alive after the
 * activity stops, so a `LaunchedEffect` polling loop keyed only on data keeps sampling (and
 * waking the CPU) in the background. Key every periodic UI loop on this as well.
 */
@Composable
internal fun rememberScreenVisible(): Boolean {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return state.isAtLeast(Lifecycle.State.STARTED)
}
