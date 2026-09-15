package io.github.xgl34222220.bichen

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer as composeGraphicsLayer

/** Keeps the screen files concise while the Compose migration is split across Java/Kotlin sources. */
internal fun Modifier.graphicsLayer(block: GraphicsLayerScope.() -> Unit): Modifier = this.composeGraphicsLayer(block = block)
