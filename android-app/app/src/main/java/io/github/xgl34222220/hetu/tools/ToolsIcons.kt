// Geometry from Lucide (ISC); same construction as home/HomeIcons.kt and ui/HetuLucideIcons.kt.
package io.github.xgl34222220.hetu.tools

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Icons used by the tools module that the home module does not already ship.
 * Stroke 1.75 on a 24 grid, tinted by the caller. Shared glyphs (chevrons, check, x, save,
 * refresh, info, alerts) come from `HomeIcons`.
 */
internal object ToolsIcons {
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

    val Folder: ImageVector by lazy { vector("Folder",
        "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z",
    ) }

    val SquareTerminal: ImageVector by lazy { vector("SquareTerminal",
        "m7 11 2-2-2-2",
        "M11 13h4",
        "M5 3H19a2 2 0 0 1 2 2V19a2 2 0 0 1 -2 2H5a2 2 0 0 1 -2 -2V5a2 2 0 0 1 2 -2Z",
    ) }

    val FileText: ImageVector by lazy { vector("FileText",
        "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z",
        "M14 2v4a2 2 0 0 0 2 2h4",
        "M10 9H8",
        "M16 13H8",
        "M16 17H8",
    ) }

    val LayoutGrid: ImageVector by lazy { vector("LayoutGrid",
        "M4 3H9a1 1 0 0 1 1 1V9a1 1 0 0 1 -1 1H4a1 1 0 0 1 -1 -1V4a1 1 0 0 1 1 -1Z",
        "M15 3H20a1 1 0 0 1 1 1V9a1 1 0 0 1 -1 1H15a1 1 0 0 1 -1 -1V4a1 1 0 0 1 1 -1Z",
        "M15 14H20a1 1 0 0 1 1 1V20a1 1 0 0 1 -1 1H15a1 1 0 0 1 -1 -1V15a1 1 0 0 1 1 -1Z",
        "M4 14H9a1 1 0 0 1 1 1V20a1 1 0 0 1 -1 1H4a1 1 0 0 1 -1 -1V15a1 1 0 0 1 1 -1Z",
    ) }

    val Wifi: ImageVector by lazy { vector("Wifi",
        "M12 20h.01",
        "M2 8.82a15 15 0 0 1 20 0",
        "M5 12.859a10 10 0 0 1 14 0",
        "M8.5 16.429a5 5 0 0 1 7 0",
    ) }

    val RadioTower: ImageVector by lazy { vector("RadioTower",
        "M4.9 16.1C1 12.2 1 5.8 4.9 1.9",
        "M7.8 4.7a6.14 6.14 0 0 0-.8 7.5",
        "M10 9a2 2 0 1 0 4 0a2 2 0 1 0 -4 0Z",
        "M16.2 4.8c2 2 2.26 5.11.8 7.47",
        "M19.1 1.9a9.96 9.96 0 0 1 0 14.1",
        "M9.5 18h5",
        "m8 22 4-11 4 11",
    ) }

    val Split: ImageVector by lazy { vector("Split",
        "M16 3h5v5",
        "M8 3H3v5",
        "M12 22v-8.3a4 4 0 0 0-1.172-2.872L3 3",
        "m15 9 6-6",
    ) }

    val FileCog: ImageVector by lazy { vector("FileCog",
        "M4 22h14a2 2 0 0 0 2-2V7l-5-5H6a2 2 0 0 0-2 2v2",
        "M14 2v4a2 2 0 0 0 2 2h4",
        "M3 14a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z",
        "M6 10v1",
        "M6 17v1",
        "M10 14H9",
        "M3 14H2",
        "m9 11-.88.88",
        "M3.88 16.12 3 17",
        "m9 17-.88-.88",
        "M3.88 11.88 3 11",
    ) }

    val Link: ImageVector by lazy { vector("Link",
        "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71",
        "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71",
    ) }

    val Database: ImageVector by lazy { vector("Database",
        "M3 5a9 3 0 1 0 18 0a9 3 0 1 0 -18 0Z",
        "M3 5V19A9 3 0 0 0 21 19V5",
        "M3 12A9 3 0 0 0 21 12",
    ) }

    val Cpu: ImageVector by lazy { vector("Cpu",
        "M6 4H18a2 2 0 0 1 2 2V18a2 2 0 0 1 -2 2H6a2 2 0 0 1 -2 -2V6a2 2 0 0 1 2 -2Z",
        "M10 9H14a1 1 0 0 1 1 1V14a1 1 0 0 1 -1 1H10a1 1 0 0 1 -1 -1V10a1 1 0 0 1 1 -1Z",
        "M15 2v2",
        "M15 20v2",
        "M2 15h2",
        "M2 9h2",
        "M20 15h2",
        "M20 9h2",
        "M9 2v2",
        "M9 20v2",
    ) }

    val ShieldBan: ImageVector by lazy { vector("ShieldBan",
        "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z",
        "m4.243 5.21 14.39 12.472",
    ) }

    val ShieldX: ImageVector by lazy { vector("ShieldX",
        "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z",
        "m9 9 6 6", "m15 9-6 6",
    ) }

    val Activity: ImageVector by lazy { vector("Activity",
        "M22 12h-2.48a2 2 0 0 0-1.93 1.46l-2.35 8.36a.25.25 0 0 1-.48 0L9.24 2.18a.25.25 0 0 0-.48 0l-2.35 8.36A2 2 0 0 1 4.49 12H2",
    ) }

    val Monitor: ImageVector by lazy { vector("Monitor",
        "M4 3H20a2 2 0 0 1 2 2V15a2 2 0 0 1 -2 2H4a2 2 0 0 1 -2 -2V5a2 2 0 0 1 2 -2Z",
        "M8 21L16 21",
        "M12 17L12 21",
    ) }

    val Search: ImageVector by lazy { vector("Search",
        "M3 11a8 8 0 1 0 16 0a8 8 0 1 0 -16 0Z",
        "m21 21-4.3-4.3",
    ) }

    val Plus: ImageVector by lazy { vector("Plus",
        "M5 12h14",
        "M12 5v14",
    ) }

    val File: ImageVector by lazy { vector("File",
        "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z",
        "M14 2v4a2 2 0 0 0 2 2h4",
    ) }

    val Ellipsis: ImageVector by lazy { vector("Ellipsis",
        "M11 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0Z",
        "M18 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0Z",
        "M4 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0Z",
    ) }

    val Pencil: ImageVector by lazy { vector("Pencil",
        "M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z",
        "m15 5 4 4",
    ) }

    val Trash2: ImageVector by lazy { vector("Trash2",
        "M3 6h18",
        "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6",
        "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2",
        "M10 11L10 17",
        "M14 11L14 17",
    ) }

    val Share: ImageVector by lazy { vector("Share",
        "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8",
        "M16 6 L12 2 L8 6",
        "M12 2L12 15",
    ) }

    val Undo2: ImageVector by lazy { vector("Undo2",
        "M9 14 4 9l5-5",
        "M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5v0a5.5 5.5 0 0 1-5.5 5.5H11",
    ) }

    val Redo2: ImageVector by lazy { vector("Redo2",
        "m15 14 5-5-5-5",
        "M20 9H9.5A5.5 5.5 0 0 0 4 14.5v0A5.5 5.5 0 0 0 9.5 20H13",
    ) }

    val List: ImageVector by lazy { vector("List",
        "M8 6L21 6",
        "M8 12L21 12",
        "M8 18L21 18",
        "M3 6L3.01 6",
        "M3 12L3.01 12",
        "M3 18L3.01 18",
    ) }

    val Download: ImageVector by lazy { vector("Download",
        "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4",
        "M7 10 L12 15 L17 10",
        "M12 15L12 3",
    ) }

    val FileWarning: ImageVector by lazy { vector("FileWarning",
        "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z",
        "M12 9v4",
        "M12 17h.01",
    ) }
}
