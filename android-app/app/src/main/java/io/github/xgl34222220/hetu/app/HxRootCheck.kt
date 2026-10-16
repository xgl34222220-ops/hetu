package io.github.xgl34222220.hetu

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * func-400 E1: a failed start on a phone where Root is not granted (denied in the Root manager,
 * no su, a pending grant prompt that timed out) gets one clear instruction instead of a page of
 * diagnostics that all failed for the same reason.
 */
internal object HxRootCheck {
    const val NO_ROOT = "未获得 Root 权限，河图无法接管网络。请在 Magisk / KernelSU / APatch 中允许河图后重试。"

    /** Runs `id -u` through su (≤5 s); any failure counts as not granted. */
    suspend fun granted(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            RootBridge.hasRoot(context)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            false
        }
    }

    /** The start-failure text: the instruction first, the original reason as detail. */
    fun message(reason: String): String = if (reason.isBlank()) NO_ROOT else "$NO_ROOT\n\n$reason"
}
