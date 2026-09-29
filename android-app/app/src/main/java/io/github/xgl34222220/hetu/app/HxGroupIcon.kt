package io.github.xgl34222220.hetu

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp

/** First regional-indicator pair in a group name, e.g. "🇭🇰 香港" -> "🇭🇰". */
internal fun hxNameFlag(name: String): String? {
    val points = name.codePoints().toArray()
    for (i in 0 until points.size - 1) {
        if (points[i] in 0x1F1E6..0x1F1FF && points[i + 1] in 0x1F1E6..0x1F1FF) {
            return String(Character.toChars(points[i])) + String(Character.toChars(points[i + 1]))
        }
    }
    return null
}

/**
 * Group avatar: the icon declared in the YAML (`icon:` url, cached on disk by
 * [ProxyGroupIconRepository]), otherwise a flag from the name, otherwise the initial.
 */
@Composable
internal fun HxGroupIcon(group: ProxyGroupUi, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember(context) { ProxyGroupIconRepository.get(context) }
    val url = group.iconUrl
    val loaded by produceState<GroupIconLoad>(
        initialValue = repository.peek(url)?.let { GroupIconLoad.Ready(it, true) } ?: GroupIconLoad.Loading,
        url,
        group.iconPath,
    ) {
        if (url.isBlank() && group.iconPath.isBlank()) {
            value = GroupIconLoad.Failed
            return@produceState
        }
        value = repository.load(url.ifBlank { "local:" + group.iconPath }, group.iconPath)
    }
    val flag = remember(group.name) { hxNameFlag(group.name) }
    Box(modifier, contentAlignment = Alignment.Center) {
        val ready = loaded as? GroupIconLoad.Ready
        when {
            ready != null -> Image(ready.bitmap.asImageBitmap(), group.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            flag != null -> Text(flag, fontSize = 18.sp)
            else -> Text(
                group.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleSmall,
                color = Hx.colors.accent,
            )
        }
    }
}
