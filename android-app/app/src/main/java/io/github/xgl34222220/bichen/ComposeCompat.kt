package io.github.xgl34222220.bichen

import android.graphics.Bitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap as composeAsImageBitmap
import androidx.compose.ui.graphics.graphicsLayer as composeGraphicsLayer

/** Keeps the screen files concise while the Compose migration is split across Java/Kotlin sources. */
internal fun Modifier.graphicsLayer(block: GraphicsLayerScope.() -> Unit): Modifier = this.composeGraphicsLayer(block = block)

typealias Path = androidx.compose.ui.graphics.Path
typealias Stroke = androidx.compose.ui.graphics.drawscope.Stroke

@Suppress("FunctionName")
fun Path(): Path = androidx.compose.ui.graphics.Path()

fun Bitmap.asImageBitmap(): ImageBitmap = this.composeAsImageBitmap()
