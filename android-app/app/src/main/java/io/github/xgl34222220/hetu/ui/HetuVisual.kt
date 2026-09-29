package io.github.xgl34222220.hetu.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * V19.3 row icon (reference style): a monochrome line icon, no tinted tile.
 * Keeps the old tile's footprint so every row keeps its alignment.
 */
@Composable
internal fun HetuLineIcon(icon: ImageVector, modifier: Modifier = Modifier, boxSize: Dp = 38.dp) {
    val t = LocalHetuTokens.current
    Box(modifier.size(boxSize), contentAlignment = Alignment.Center) {
        Icon(baiZeLineIcon(icon), null, Modifier.size(22.dp), tint = t.textPrimary.copy(alpha = .82f))
    }
}
