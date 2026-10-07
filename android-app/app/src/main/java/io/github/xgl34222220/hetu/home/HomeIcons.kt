// Geometry from Lucide (ISC); same construction as ui/HetuLucideIcons.kt.
package io.github.xgl34222220.hetu.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Icons used by the home module only. Stroke 1.75 on a 24 grid, tinted by the caller. */
internal object HomeIcons {
    private fun vector(name: String, vararg paths: String): ImageVector = ImageVector.Builder(
        name = "HomeLucide.$name", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        paths.forEach { data ->
            addPath(pathData = PathParser().parsePathString(data).toNodes(), fill = null,
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()

    val Server: ImageVector by lazy { vector("Server",
        "M4 2H20a2 2 0 0 1 2 2V8a2 2 0 0 1 -2 2H4a2 2 0 0 1 -2 -2V4a2 2 0 0 1 2 -2Z",
        "M4 14H20a2 2 0 0 1 2 2V20a2 2 0 0 1 -2 2H4a2 2 0 0 1 -2 -2V16a2 2 0 0 1 2 -2Z",
        "M6 6L6.01 6",
        "M6 18L6.01 18",
    ) }

    val SlidersHorizontal: ImageVector by lazy { vector("SlidersHorizontal",
        "M21 4L14 4",
        "M10 4L3 4",
        "M21 12L12 12",
        "M8 12L3 12",
        "M21 20L16 20",
        "M12 20L3 20",
        "M14 2L14 6",
        "M8 10L8 14",
        "M16 18L16 22",
    ) }

    val RefreshCw: ImageVector by lazy { vector("RefreshCw",
        "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8",
        "M21 3v5h-5",
        "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16",
        "M8 16H3v5",
    ) }

    val Repeat2: ImageVector by lazy { vector("Repeat2",
        "m2 9 3-3 3 3",
        "M13 18H7a2 2 0 0 1-2-2V6",
        "m22 15-3 3-3-3",
        "M11 6h6a2 2 0 0 1 2 2v10",
    ) }

    val ChevronRight: ImageVector by lazy { vector("ChevronRight",
        "m9 18 6-6-6-6",
    ) }

    val ChevronLeft: ImageVector by lazy { vector("ChevronLeft",
        "m15 18-6-6 6-6",
    ) }

    val Power: ImageVector by lazy { vector("Power",
        "M12 2v10",
        "M18.4 6.6a9 9 0 1 1-12.77.04",
    ) }

    val TriangleAlert: ImageVector by lazy { vector("TriangleAlert",
        "m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3",
        "M12 9v4",
        "M12 17h.01",
    ) }

    val Copy: ImageVector by lazy { vector("Copy",
        "M10 8H20a2 2 0 0 1 2 2V20a2 2 0 0 1 -2 2H10a2 2 0 0 1 -2 -2V10a2 2 0 0 1 2 -2Z",
        "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2",
    ) }

    val CircleAlert: ImageVector by lazy { vector("CircleAlert",
        "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z",
        "M12 8L12 12",
        "M12 16L12.01 16",
    ) }

    val CircleCheck: ImageVector by lazy { vector("CircleCheck",
        "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z",
        "m9 12 2 2 4-4",
    ) }

    val RotateCcw: ImageVector by lazy { vector("RotateCcw",
        "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8",
        "M3 3v5h5",
    ) }

    val Save: ImageVector by lazy { vector("Save",
        "M15.2 3a2 2 0 0 1 1.4.6l3.8 3.8a2 2 0 0 1 .6 1.4V19a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
        "M17 21v-7a1 1 0 0 0-1-1H8a1 1 0 0 0-1 1v7",
        "M7 3v4a1 1 0 0 0 1 1h7",
    ) }

    val Timer: ImageVector by lazy { vector("Timer",
        "M10 2L14 2",
        "M12 14L15 11",
        "M4 14a8 8 0 1 0 16 0a8 8 0 1 0 -16 0Z",
    ) }

    val ChevronsUpDown: ImageVector by lazy { vector("ChevronsUpDown",
        "m7 15 5 5 5-5",
        "m7 9 5-5 5 5",
    ) }

    val X: ImageVector by lazy { vector("X",
        "M18 6 6 18",
        "m6 6 12 12",
    ) }

    val Check: ImageVector by lazy { vector("Check",
        "M20 6 9 17l-5-5",
    ) }

    val Info: ImageVector by lazy { vector("Info",
        "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z",
        "M12 16v-4",
        "M12 8h.01",
    ) }

    val Hourglass: ImageVector by lazy { vector("Hourglass",
        "M5 22h14",
        "M5 2h14",
        "M17 22v-4.172a2 2 0 0 0-.586-1.414L12 12l-4.414 4.414A2 2 0 0 0 7 17.828V22",
        "M7 2v4.172a2 2 0 0 0 .586 1.414L12 12l4.414-4.414A2 2 0 0 0 17 6.172V2",
    ) }

    val ArrowUp: ImageVector by lazy { vector("ArrowUp",
        "m5 12 7-7 7 7",
        "M12 19V5",
    ) }

    val ArrowDown: ImageVector by lazy { vector("ArrowDown",
        "M12 5v14",
        "m19 12-7 7-7-7",
    ) }

    val Activity: ImageVector by lazy { vector("Activity",
        "M22 12h-2.48a2 2 0 0 0-1.93 1.46l-2.35 8.36a.25.25 0 0 1-.48 0L9.24 2.18a.25.25 0 0 0-.48 0l-2.35 8.36A2 2 0 0 1 4.49 12H2",
    ) }

    val Clock: ImageVector by lazy { vector("Clock",
        "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z",
        "M12 6v6l4 2",
    ) }
}
