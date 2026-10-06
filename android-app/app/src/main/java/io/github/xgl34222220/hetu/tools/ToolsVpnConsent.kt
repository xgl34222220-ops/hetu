package io.github.xgl34222220.hetu.tools

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/** Each request owns its result key, so a cancelled page cannot authorize a later request. */
internal class ToolsVpnConsent(private val registry: ActivityResultRegistry) {
    private var pending: CancellableContinuation<Boolean>? = null
    private var intentGeneration = 0L

    fun beginIntent(): Long {
        intentGeneration++
        pending?.cancel()
        pending = null
        return intentGeneration
    }

    fun isCurrent(generation: Long): Boolean = intentGeneration == generation

    suspend fun await(intent: Intent): Boolean {
        var launcher: ActivityResultLauncher<Intent>? = null
        var request: CancellableContinuation<Boolean>? = null
        return try {
            suspendCancellableCoroutine { continuation ->
                request = continuation
                pending = continuation
                launcher = registry.register(
                    "hetu-tools-dns-${UUID.randomUUID()}",
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    if (continuation.isActive) continuation.resume(result.resultCode == Activity.RESULT_OK)
                }
                launcher!!.launch(intent)
            }
        } finally {
            if (pending === request) pending = null
            launcher?.unregister()
        }
    }
}
