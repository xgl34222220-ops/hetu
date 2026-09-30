package io.github.xgl34222220.hetu

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import java.io.File

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
 * Policy avatar. Coil owns network / disk decoding and SVG support, while the flag/initial stays
 * rendered underneath as an immediate zero-jank fallback. The old repository remains available
 * to the runtime importer, but Compose no longer needs its own image loading coroutine per card.
 */
@Composable
internal fun HxGroupIcon(group: ProxyGroupUi, modifier: Modifier = Modifier) {
    val flag = remember(group.name) { hxNameFlag(group.name) }
    val model: Any? = remember(group.iconUrl, group.iconPath) {
        when {
            group.iconUrl.isNotBlank() -> group.iconUrl
            group.iconPath.isNotBlank() -> File(group.iconPath)
            else -> null
        }
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val glyph = with(LocalDensity.current) { (maxWidth * .62f).toSp() }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (flag != null) {
                Text(flag, fontSize = glyph)
            } else {
                val c = Hx.colors
                val type = group.type.lowercase()
                val icon = when (type) {
                    "selector" -> Icons.Rounded.SwapHoriz
                    "urltest", "url-test" -> Icons.Rounded.Speed
                    "fallback" -> Icons.Rounded.Sync
                    "direct" -> Icons.Rounded.Public
                    "reject", "rejectdrop" -> Icons.Rounded.Block
                    else -> Icons.Rounded.Hub
                }
                val tint = when (type) { "direct" -> c.good; "urltest", "url-test" -> c.warn; else -> c.accent }
                Icon(icon, null, tint = tint, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = .10f)).padding(6.dp))
            }
            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = group.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}
