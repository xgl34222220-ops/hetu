// Geometry from Lucide (ISC); same construction as ui/HetuLucideIcons.kt and home/HomeIcons.kt.
package io.github.xgl34222220.hetu.panel

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Icons used only by the panel module; shared ones come from HomeIcons. Stroke 1.75 on a 24 grid. */
internal object PanelIcons {
    private fun vector(name: String, vararg paths: String): ImageVector = ImageVector.Builder(
        name = "PanelLucide.$name", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        paths.forEach { data ->
            addPath(pathData = PathParser().parsePathString(data).toNodes(), fill = null,
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()

    val Search: ImageVector by lazy { vector("Search",
        "M3 11a8 8 0 1 0 16 0a8 8 0 1 0 -16 0Z",
        "m21 21-4.3-4.3",
    ) }

    val ListFilter: ImageVector by lazy { vector("ListFilter",
        "M3 6h18",
        "M7 12h10",
        "M10 18h4",
    ) }

    val ArrowDownWideNarrow: ImageVector by lazy { vector("ArrowDownWideNarrow",
        "m3 16 4 4 4-4",
        "M7 20V4",
        "M11 4h10",
        "M11 8h7",
        "M11 12h4",
    ) }

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

    val Ellipsis: ImageVector by lazy { vector("Ellipsis",
        "M11 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0Z",
        "M18 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0Z",
        "M4 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0Z",
    ) }

    val Download: ImageVector by lazy { vector("Download",
        "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4",
        "M7 10 L12 15 L17 10",
        "M12 15L12 3",
    ) }

    val Zap: ImageVector by lazy { vector("Zap",
        "M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z",
    ) }

    val Pointer: ImageVector by lazy { vector("Pointer",
        "M22 14a8 8 0 0 1-8 8",
        "M18 11v-1a2 2 0 0 0-2-2a2 2 0 0 0-2 2",
        "M14 10V9a2 2 0 0 0-2-2a2 2 0 0 0-2 2v1",
        "M10 9.5V4a2 2 0 0 0-2-2a2 2 0 0 0-2 2v10",
        "M18 11a2 2 0 1 1 4 0v3a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15",
    ) }

    val ShieldCheck: ImageVector by lazy { vector("ShieldCheck",
        "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z",
        "m9 12 2 2 4-4",
    ) }

    val Gauge: ImageVector by lazy { vector("Gauge",
        "m12 14 4-4",
        "M3.34 19a10 10 0 1 1 17.32 0",
    ) }

    val LocateFixed: ImageVector by lazy { vector("LocateFixed",
        "M2 12L5 12",
        "M19 12L22 12",
        "M12 2L12 5",
        "M12 19L12 22",
        "M5 12a7 7 0 1 0 14 0a7 7 0 1 0 -14 0Z",
        "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z",
    ) }

    val ChevronDown: ImageVector by lazy { vector("ChevronDown",
        "m6 9 6 6 6-6",
    ) }

    val ChevronUp: ImageVector by lazy { vector("ChevronUp",
        "m18 15-6-6-6 6",
    ) }

    val ArrowUp: ImageVector by lazy { vector("ArrowUp",
        "m5 12 7-7 7 7",
        "M12 19V5",
    ) }

    val ArrowDown: ImageVector by lazy { vector("ArrowDown",
        "M12 5v14",
        "m19 12-7 7-7-7",
    ) }

    val Unlink: ImageVector by lazy { vector("Unlink",
        "m18.84 12.25 1.72-1.71h-.02a5.004 5.004 0 0 0-.12-7.07 5.006 5.006 0 0 0-6.95 0l-1.72 1.71",
        "m5.17 11.75-1.71 1.71a5.004 5.004 0 0 0 .12 7.07 5.006 5.006 0 0 0 6.95 0l1.71-1.71",
        "M8 2L8 5",
        "M2 8L5 8",
        "M16 19L16 22",
        "M19 16L22 16",
    ) }

    val LayoutList: ImageVector by lazy { vector("LayoutList",
        "M4 3H9a1 1 0 0 1 1 1V9a1 1 0 0 1 -1 1H4a1 1 0 0 1 -1 -1V4a1 1 0 0 1 1 -1Z",
        "M4 14H9a1 1 0 0 1 1 1V20a1 1 0 0 1 -1 1H4a1 1 0 0 1 -1 -1V15a1 1 0 0 1 1 -1Z",
        "M14 4h7",
        "M14 9h7",
        "M14 15h7",
        "M14 20h7",
    ) }

    val Image: ImageVector by lazy { vector("Image",
        "M5 3H19a2 2 0 0 1 2 2V19a2 2 0 0 1 -2 2H5a2 2 0 0 1 -2 -2V5a2 2 0 0 1 2 -2Z",
        "M7 9a2 2 0 1 0 4 0a2 2 0 1 0 -4 0Z",
        "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21",
    ) }

    val ServerOff: ImageVector by lazy { vector("ServerOff",
        "M7 2h13a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2h-5",
        "M10 10 2.5 2.5C2 2 2 2.5 2 5v3a2 2 0 0 0 2 2h6z",
        "M22 17v-1a2 2 0 0 0-2-2h-1",
        "M4 14a2 2 0 0 0-2 2v4a2 2 0 0 0 2 2h16.5l1-.5.5.5-8-8H4z",
        "M6 18h.01",
        "m2 2 20 20",
    ) }

    val CircleX: ImageVector by lazy { vector("CircleX",
        "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z",
        "m15 9-6 6",
        "m9 9 6 6",
    ) }

    val Globe: ImageVector by lazy { vector("Globe",
        "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z",
        "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20",
        "M2 12h20",
    ) }

    val Power: ImageVector by lazy { vector("Power",
        "M12 2v10",
        "M18.4 6.6a9 9 0 1 1-12.77.04",
    ) }

    val Funnel: ImageVector by lazy { vector("Funnel",
        "M10 20a1 1 0 0 0 .553.895l2 1A1 1 0 0 0 14 21v-7a2 2 0 0 1 .517-1.341L21.74 4.67A1 1 0 0 0 21 3H3a1 1 0 0 0-.742 1.67l7.225 7.989A2 2 0 0 1 10 14z",
    ) }

    val CircleEllipsis: ImageVector by lazy { vector("CircleEllipsis",
        "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z",
        "M17 12h.01",
        "M12 12h.01",
        "M7 12h.01",
    ) }

    val SearchX: ImageVector by lazy { vector("SearchX",
        "m13.5 8.5-5 5",
        "m8.5 8.5 5 5",
        "M3 11a8 8 0 1 0 16 0a8 8 0 1 0 -16 0Z",
        "m21 21-4.3-4.3",
    ) }

    val Inbox: ImageVector by lazy { vector("Inbox",
        "M22 12L16 12L14 15L10 15L8 12L2 12",
        "M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z",
    ) }

    val Layers: ImageVector by lazy { vector("Layers",
        "M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83z",
        "M2 12a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 12",
        "M2 17a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 17",
    ) }

    val Route: ImageVector by lazy { vector("Route",
        "M3 19a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z",
        "M9 19h8.5a3.5 3.5 0 0 0 0-7h-11a3.5 3.5 0 0 1 0-7H15",
        "M15 5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z",
    ) }

    val Link: ImageVector by lazy { vector("Link",
        "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71",
        "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71",
    ) }

    val RotateCw: ImageVector by lazy { vector("RotateCw",
        "M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8",
        "M21 3v5h-5",
    ) }

    val ScrollText: ImageVector by lazy { vector("ScrollText",
        "M15 12h-5",
        "M15 8h-5",
        "M19 17V5a2 2 0 0 0-2-2H4",
        "M8 21h12a2 2 0 0 0 2-2v-1a1 1 0 0 0-1-1H11a1 1 0 0 0-1 1v1a2 2 0 1 1-4 0V5a2 2 0 1 0-4 0v2a1 1 0 0 0 1 1h3",
    ) }

    val ListTree: ImageVector by lazy { vector("ListTree",
        "M8 5h13",
        "M13 12h8",
        "M13 19h8",
        "M3 10a2 2 0 0 0 2 2h3",
        "M3 5v12a2 2 0 0 0 2 2h3",
    ) }
}
