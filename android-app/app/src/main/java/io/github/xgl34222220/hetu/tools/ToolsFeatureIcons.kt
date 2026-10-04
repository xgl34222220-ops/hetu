// Geometry from Lucide (ISC); same construction as ToolsIcons.kt.
package io.github.xgl34222220.hetu.tools

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Icons added for pages 26–49. Everything else comes from `ToolsIcons` and `HomeIcons`. */
internal object ToolsFeatureIcons {
    private fun vector(name: String, vararg paths: String): ImageVector = ImageVector.Builder(
        name = "ToolsLucide.$name", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        paths.forEach { data ->
            addPath(pathData = PathParser().parsePathString(data).toNodes(), fill = null,
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()

    val ArrowUpDown: ImageVector by lazy { vector("ArrowUpDown",
        "m21 16-4 4-4-4",
        "M17 20V4",
        "m3 8 4-4 4 4",
        "M7 4v16",
    ) }

    val EllipsisVertical: ImageVector by lazy { vector("EllipsisVertical",
        "M11 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0Z",
        "M11 5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0Z",
        "M11 19a1 1 0 1 0 2 0a1 1 0 1 0 -2 0Z",
    ) }

    val CheckCheck: ImageVector by lazy { vector("CheckCheck",
        "M18 6 7 17l-5-5",
        "m22 10-7.5 7.5L13 16",
    ) }

    val Eraser: ImageVector by lazy { vector("Eraser",
        "m7 21-4.3-4.3c-1-1-1-2.5 0-3.4l9.6-9.6c1-1 2.5-1 3.4 0l5.6 5.6c1 1 1 2.5 0 3.4L13 21",
        "M22 21H7",
        "m5 11 9 9",
    ) }

    val Cat: ImageVector by lazy { vector("Cat",
        "M12 5c.67 0 1.35.09 2 .26 1.78-2 5.03-2.84 6.42-2.26 1.4.58-.42 7-.42 7 .57 1.07 1 2.24 1 3.44C21 17.9 16.97 21 12 21s-9-3-9-7.56c0-1.25.5-2.4 1-3.44 0 0-1.89-6.42-.5-7 1.39-.58 4.72.23 6.5 2.23A9.04 9.04 0 0 1 12 5Z",
        "M8 14v.5",
        "M16 14v.5",
        "M11.25 16.25h1.5L12 17l-.75-.75Z",
    ) }

    val Box: ImageVector by lazy { vector("Box",
        "M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z",
        "m3.3 7 8.7 5 8.7-5",
        "M12 22V12",
    ) }

    val Globe: ImageVector by lazy { vector("Globe",
        "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z",
        "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20",
        "M2 12h20",
    ) }

    val Laptop: ImageVector by lazy { vector("Laptop",
        "M20 16V7a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v9m16 0H4m16 0 1.28 2.55a1 1 0 0 1-.9 1.45H3.62a1 1 0 0 1-.9-1.45L4 16",
    ) }

    val ShieldPlus: ImageVector by lazy { vector("ShieldPlus",
        "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z",
        "M9 12h6",
        "M12 9v6",
    ) }

    val ShieldCheck: ImageVector by lazy { vector("ShieldCheck",
        "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z",
        "m9 12 2 2 4-4",
    ) }

    val ShieldAlert: ImageVector by lazy { vector("ShieldAlert",
        "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z",
        "M12 8v4",
        "M12 16h.01",
    ) }

    val ShieldOff: ImageVector by lazy { vector("ShieldOff",
        "m2 2 20 20",
        "M5 5a1 1 0 0 0-1 1v7c0 5 3.5 7.5 7.67 8.94a1 1 0 0 0 .67.01c2.35-.82 4.48-1.97 5.9-3.71",
        "M9.309 3.652A12.252 12.252 0 0 0 11.24 2.28a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1v7a9.784 9.784 0 0 1-.08 1.264",
    ) }

    val Shield: ImageVector by lazy { vector("Shield",
        "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z",
    ) }

    val Router: ImageVector by lazy { vector("Router",
        "M4 14H20a2 2 0 0 1 2 2V20a2 2 0 0 1 -2 2H4a2 2 0 0 1 -2 -2V16a2 2 0 0 1 2 -2Z",
        "M6.01 18H6",
        "M10.01 18H10",
        "M15 10v4",
        "M17.84 7.17a4 4 0 0 0-5.66 0",
        "M20.66 4.34a8 8 0 0 0-11.31 0",
    ) }

    val Siren: ImageVector by lazy { vector("Siren",
        "M7 18v-6a5 5 0 1 1 10 0v6",
        "M5 21a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-1a2 2 0 0 0-2-2H7a2 2 0 0 0-2 2z",
        "M21 12h1",
        "M18.5 4.5 18 5",
        "M2 12h1",
        "M12 2v1",
        "m4.929 4.929.707.707",
        "M12 12v6",
    ) }

    val CircleHelp: ImageVector by lazy { vector("CircleHelp",
        "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z",
        "M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3",
        "M12 17h.01",
    ) }

    val CircleDashed: ImageVector by lazy { vector("CircleDashed",
        "M10.1 2.182a10 10 0 0 1 3.8 0",
        "M13.9 21.818a10 10 0 0 1-3.8 0",
        "M17.609 3.721a10 10 0 0 1 2.69 2.7",
        "M2.182 13.9a10 10 0 0 1 0-3.8",
        "M20.279 17.609a10 10 0 0 1-2.7 2.69",
        "M21.818 10.1a10 10 0 0 1 0 3.8",
        "M3.721 6.391a10 10 0 0 1 2.7-2.69",
        "M6.391 20.279a10 10 0 0 1-2.69-2.7",
    ) }

    val Ban: ImageVector by lazy { vector("Ban",
        "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z",
        "m4.9 4.9 14.2 14.2",
    ) }

    val CircleMinus: ImageVector by lazy { vector("CircleMinus",
        "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z",
        "M8 12h8",
    ) }

    val ArrowUpNarrowWide: ImageVector by lazy { vector("ArrowUpNarrowWide",
        "m3 8 4-4 4 4",
        "M7 4v16",
        "M11 12h4",
        "M11 16h7",
        "M11 20h10",
    ) }

    val ArrowDownWideNarrow: ImageVector by lazy { vector("ArrowDownWideNarrow",
        "m3 16 4 4 4-4",
        "M7 20V4",
        "M11 4h10",
        "M11 8h7",
        "M11 12h4",
    ) }
}
