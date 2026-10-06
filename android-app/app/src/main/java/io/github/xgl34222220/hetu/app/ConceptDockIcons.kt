package io.github.xgl34222220.hetu

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Small outline primitives matching the dock's chain/grid/hexagon silhouettes. */
internal object ConceptDockIcons {
    private fun outline(name: String, vararg paths: String) = ImageVector.Builder(
        name = "HetuConcept.$name", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        paths.forEach { addPath(PathParser().parsePathString(it).toNodes(), fill = null,
            stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
    }.build()
    val Grid by lazy { outline("Grid",
        "M4 3H8Q9 3 9 4V8Q9 9 8 9H4Q3 9 3 8V4Q3 3 4 3Z",
        "M16 3H20Q21 3 21 4V8Q21 9 20 9H16Q15 9 15 8V4Q15 3 16 3Z",
        "M4 15H8Q9 15 9 16V20Q9 21 8 21H4Q3 21 3 20V16Q3 15 4 15Z",
        "M16 15H20Q21 15 21 16V20Q21 21 20 21H16Q15 21 15 20V16Q15 15 16 15Z") }
    val Settings by lazy { outline("Settings", "M12 2L21 7V17L12 22L3 17V7Z", "M8 12a4 4 0 1 0 8 0a4 4 0 1 0-8 0") }
    val Chain by lazy { outline("Chain", "M10 8L12 5a5 5 0 0 1 8 6l-2 3a5 5 0 0 1-7 1", "M14 16l-2 3a5 5 0 0 1-8-6l2-3a5 5 0 0 1 7-1", "M9 15l6-6") }
}
