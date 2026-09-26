package io.github.xgl34222220.hetu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import io.github.xgl34222220.hetu.ui.HetuTheme
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

/** Native Skia host snapshots draw into a software Bitmap Canvas. Layout and input
 * use production components; only GPU squircle shaders use their documented fallback. */
@Composable
fun RasterHetuTheme(content: @Composable () -> Unit) {
    HetuTheme { CompositionLocalProvider(LocalSquircleEnabled provides false, content = content) }
}
