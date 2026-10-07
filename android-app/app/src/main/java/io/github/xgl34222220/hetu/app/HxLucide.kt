// Geometry from Lucide (ISC); see assets/licenses/lucide.txt. Same construction as home/HomeIcons.kt.
package io.github.xgl34222220.hetu

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.panel.PanelIcons
import io.github.xgl34222220.hetu.tools.ToolsFeatureIcons
import io.github.xgl34222220.hetu.tools.ToolsIcons

/**
 * Line icons of the 设置 pages and of the tool pages outside the tools module. Stroke 1.75 on a
 * 24 grid, tinted by the caller: the same family as the home, panel and tools icons, so the
 * four tabs draw every glyph with one pen.
 */
internal object HxIcons {
    private fun vector(name: String, vararg paths: String): ImageVector = ImageVector.Builder(
        name = "HxLucide.$name", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        paths.forEach { data ->
            addPath(pathData = PathParser().parsePathString(data).toNodes(), fill = null,
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()

    val Palette: ImageVector by lazy { vector("Palette",
        "M12 22a1 1 0 0 1 0-20 10 9 0 0 1 10 9 5 5 0 0 1-5 5h-2.25a1.75 1.75 0 0 0-1.4 2.8l.3.4a1.75 1.75 0 0 1-1.4 2.8z",
        "M13 6.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0Z",
        "M17 10.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0Z",
        "M6 12.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0Z",
        "M8 7.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0Z",
    ) }

    val Signal: ImageVector by lazy { vector("Signal",
        "M2 20h.01",
        "M7 20v-4",
        "M12 20v-8",
        "M17 20V8",
        "M22 4v16",
    ) }

    val CloudUpload: ImageVector by lazy { vector("CloudUpload",
        "M12 13v8",
        "M4 14.899A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242",
        "m8 17 4-4 4 4",
    ) }

    val CloudDownload: ImageVector by lazy { vector("CloudDownload",
        "M12 13v8l-4-4",
        "m12 21 4-4",
        "M4.393 15.269A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.436 8.284",
    ) }

    val TextWrap: ImageVector by lazy { vector("TextWrap",
        "m16 16-3 3 3 3",
        "M3 12h14.5a1 1 0 0 1 0 7H13",
        "M3 19h6",
        "M3 5h18",
    ) }

    val ListOrdered: ImageVector by lazy { vector("ListOrdered",
        "M11 5h10",
        "M11 12h10",
        "M11 19h10",
        "M4 4h1v5",
        "M4 9h2",
        "M6.5 20H3.4c0-1 2.6-1.925 2.6-3.5a1.5 1.5 0 0 0-2.6-1.02",
    ) }

    val Heart: ImageVector by lazy { vector("Heart",
        "M2 9.5a5.5 5.5 0 0 1 9.591-3.676.56.56 0 0 0 .818 0A5.49 5.49 0 0 1 22 9.5c0 2.29-1.5 4-3 5.5l-5.492 5.313a2 2 0 0 1-3 .019L5 15c-1.5-1.5-3-3.2-3-5.5",
    ) }

    val ListChecks: ImageVector by lazy { vector("ListChecks",
        "M13 5h8",
        "M13 12h8",
        "M13 19h8",
        "m3 17 2 2 4-4",
        "m3 7 2 2 4-4",
    ) }

    val Sparkles: ImageVector by lazy { vector("Sparkles",
        "M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z",
        "M20 2v4",
        "M22 4h-4",
        "M2 20a2 2 0 1 0 4 0a2 2 0 1 0 -4 0Z",
    ) }

    val Columns2: ImageVector by lazy { vector("Columns2",
        "M5 3H19a2 2 0 0 1 2 2V19a2 2 0 0 1 -2 2H5a2 2 0 0 1 -2 -2V5a2 2 0 0 1 2 -2Z",
        "M12 3v18",
    ) }

    val Upload: ImageVector by lazy { vector("Upload",
        "M12 3v12",
        "m17 8-5-5-5 5",
        "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4",
    ) }

    val Type: ImageVector by lazy { vector("Type",
        "M12 4v16",
        "M4 7V5a1 1 0 0 1 1-1h14a1 1 0 0 1 1 1v2",
        "M9 20h6",
    ) }

    val MoveHorizontal: ImageVector by lazy { vector("MoveHorizontal",
        "m18 8 4 4-4 4",
        "M2 12h20",
        "m6 8-4 4 4 4",
    ) }

    val CircleStop: ImageVector by lazy { vector("CircleStop",
        "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z",
        "M10 9H14a1 1 0 0 1 1 1V14a1 1 0 0 1 -1 1H10a1 1 0 0 1 -1 -1V10a1 1 0 0 1 1 -1Z",
    ) }

    val Radio: ImageVector by lazy { vector("Radio",
        "M16.247 7.761a6 6 0 0 1 0 8.478",
        "M19.075 4.933a10 10 0 0 1 0 14.134",
        "M4.925 19.067a10 10 0 0 1 0-14.134",
        "M7.753 16.239a6 6 0 0 1 0-8.478",
        "M10 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0Z",
    ) }

    val CirclePlay: ImageVector by lazy { vector("CirclePlay",
        "M9 9.003a1 1 0 0 1 1.517-.859l4.997 2.997a1 1 0 0 1 0 1.718l-4.997 2.997A1 1 0 0 1 9 14.996z",
        "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z",
    ) }

    val Play: ImageVector by lazy { vector("Play",
        "M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z",
    ) }

    val Smartphone: ImageVector by lazy { vector("Smartphone",
        "M7 2H17a2 2 0 0 1 2 2V20a2 2 0 0 1 -2 2H7a2 2 0 0 1 -2 -2V4a2 2 0 0 1 2 -2Z",
        "M12 18h.01",
    ) }

    val ExternalLink: ImageVector by lazy { vector("ExternalLink",
        "M15 3h6v6",
        "M10 14 21 3",
        "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6",
    ) }

    val Bell: ImageVector by lazy { vector("Bell",
        "M10.268 21a2 2 0 0 0 3.464 0",
        "M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326",
    ) }

    val FilePlus: ImageVector by lazy { vector("FilePlus",
        "M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z",
        "M14 2v5a1 1 0 0 0 1 1h5",
        "M9 15h6",
        "M12 18v-6",
    ) }

    val Sun: ImageVector by lazy { vector("Sun",
        "M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0Z",
        "M12 2v2",
        "M12 20v2",
        "m4.93 4.93 1.41 1.41",
        "m17.66 17.66 1.41 1.41",
        "M2 12h2",
        "M20 12h2",
        "m6.34 17.66-1.41 1.41",
        "m19.07 4.93-1.41 1.41",
    ) }

    val Moon: ImageVector by lazy { vector("Moon",
        "M20.985 12.486a9 9 0 1 1-9.473-9.472c.405-.022.617.46.402.803a6 6 0 0 0 8.268 8.268c.344-.215.825-.004.803.401",
    ) }

    val Contrast: ImageVector by lazy { vector("Contrast",
        "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z",
        "M12 18a6 6 0 0 0 0-12v12z",
    ) }

    val BookOpen: ImageVector by lazy { vector("BookOpen",
        "M12 5v16",
        "M20.001 19A2 2 0 0022 17V5a2 2 0 00-1.999-2L16 3.002A5 5 0 0012 5a5 5 0 00-4-2H4a2 2 0 00-2 2v12a2 2 0 001.999 2H8a5 5 0 014 2 5 5 0 014-2z",
    ) }

    val FolderOpen: ImageVector by lazy { vector("FolderOpen",
        "m6 14 1.5-2.9A2 2 0 0 1 9.24 10H20a2 2 0 0 1 1.94 2.5l-1.54 6a2 2 0 0 1-1.95 1.5H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H18a2 2 0 0 1 2 2v2",
    ) }

    val TextCursorInput: ImageVector by lazy { vector("TextCursorInput",
        "M12 20h-1a2 2 0 0 1-2-2 2 2 0 0 1-2 2H6",
        "M13 8h7a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2h-7",
        "M5 16H4a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2h1",
        "M6 4h1a2 2 0 0 1 2 2 2 2 0 0 1 2-2h1",
        "M9 6v12",
    ) }

    val MonitorSmartphone: ImageVector by lazy { vector("MonitorSmartphone",
        "M18 8V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h8",
        "M10 19v-3.96 3.15",
        "M7 19h5",
        "M18 12H20a2 2 0 0 1 2 2V20a2 2 0 0 1 -2 2H18a2 2 0 0 1 -2 -2V14a2 2 0 0 1 2 -2Z",
    ) }

    val Rows3: ImageVector by lazy { vector("Rows3",
        "M5 3H19a2 2 0 0 1 2 2V19a2 2 0 0 1 -2 2H5a2 2 0 0 1 -2 -2V5a2 2 0 0 1 2 -2Z",
        "M21 9H3",
        "M21 15H3",
    ) }

    val FolderPlus: ImageVector by lazy { vector("FolderPlus",
        "M12 10v6",
        "M9 13h6",
        "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z",
    ) }

    val Code: ImageVector by lazy { vector("Code",
        "m16 18 6-6-6-6",
        "m8 6-6 6 6 6",
    ) }

    val Droplet: ImageVector by lazy { vector("Droplet",
        "M12 22a7 7 0 0 0 7-7c0-2-1-3.9-3-5.5s-3.5-4-4-6.5c-.5 2.5-2 4.9-4 6.5C6 11.1 5 13 5 15a7 7 0 0 0 7 7z",
    ) }

    val Circle: ImageVector by lazy { vector("Circle",
        "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z",
    ) }

    val AppWindow: ImageVector by lazy { vector("AppWindow",
        "M4 4H20a2 2 0 0 1 2 2V18a2 2 0 0 1 -2 2H4a2 2 0 0 1 -2 -2V6a2 2 0 0 1 2 -2Z",
        "M10 4v4",
        "M2 8h20",
        "M6 4v4",
    ) }

    val Cable: ImageVector by lazy { vector("Cable",
        "M17 19a1 1 0 0 1-1-1v-2a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v2a1 1 0 0 1-1 1z",
        "M17 21v-2",
        "M19 14V6.5a1 1 0 0 0-7 0v11a1 1 0 0 1-7 0V10",
        "M21 21v-2",
        "M3 5V3",
        "M4 10a2 2 0 0 1-2-2V6a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2a2 2 0 0 1-2 2z",
        "M7 5V3",
    ) }

    val Package: ImageVector by lazy { vector("Package",
        "M11 21.73a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73z",
        "M12 22V12",
        "M3.29 7L12 12L20.71 7",
        "m7.5 4.27 9 5.15",
    ) }

    val HardDrive: ImageVector by lazy { vector("HardDrive",
        "M10 16h.01",
        "M2.212 11.577a2 2 0 0 0-.212.896V18a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-5.527a2 2 0 0 0-.212-.896L18.55 5.11A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z",
        "M21.946 12.013H2.054",
        "M6 16h.01",
    ) }

    val MemoryStick: ImageVector by lazy { vector("MemoryStick",
        "M12 12v-2",
        "M12 18v-2",
        "M16 12v-2",
        "M16 18v-2",
        "M2 11h1.5",
        "M20 18v-2",
        "M20.5 11H22",
        "M4 18v-2",
        "M8 12v-2",
        "M8 18v-2",
        "M4 6H20a2 2 0 0 1 2 2V14a2 2 0 0 1 -2 2H4a2 2 0 0 1 -2 -2V8a2 2 0 0 1 2 -2Z",
    ) }

    val SunMoon: ImageVector by lazy { vector("SunMoon",
        "M12 2v2",
        "M14.837 16.385a6 6 0 1 1-7.223-7.222c.624-.147.97.66.715 1.248a4 4 0 0 0 5.26 5.259c.589-.255 1.396.09 1.248.715",
        "M16 12a4 4 0 0 0-4-4",
        "m19 5-1.256 1.256",
        "M20 12h2",
    ) }

    val Blend: ImageVector by lazy { vector("Blend",
        "M8 9a7 7 0 1 0 14 0a7 7 0 1 0 -14 0Z",
        "M2 15a7 7 0 1 0 14 0a7 7 0 1 0 -14 0Z",
    ) }

    val Scaling: ImageVector by lazy { vector("Scaling",
        "M12 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7",
        "M14 15H9v-5",
        "M16 3h5v5",
        "M21 3 9 15",
    ) }

    val PanelBottom: ImageVector by lazy { vector("PanelBottom",
        "M5 3H19a2 2 0 0 1 2 2V19a2 2 0 0 1 -2 2H5a2 2 0 0 1 -2 -2V5a2 2 0 0 1 2 -2Z",
        "M3 15h18",
    ) }

    val ArrowLeftRight: ImageVector by lazy { vector("ArrowLeftRight",
        "M8 3 4 7l4 4",
        "M4 7h16",
        "m16 21 4-4-4-4",
        "M20 17H4",
    ) }

    val Settings: ImageVector by lazy { vector("Settings",
        "M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915",
        "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z",
    ) }

    val Languages: ImageVector by lazy { vector("Languages",
        "m5 8 6 6",
        "m4 14 6-6 2-3",
        "M2 5h12",
        "M7 2h1",
        "m22 22-5-10-5 10",
        "M14 18h6",
    ) }

    val SquareCheckBig: ImageVector by lazy { vector("SquareCheckBig",
        "M21 10.656V19a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h12.344",
        "m9 11 3 3L22 4",
    ) }

    val SquarePen: ImageVector by lazy { vector("SquarePen",
        "M12 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7",
        "M18.375 2.625a1 1 0 0 1 3 3l-9.013 9.014a2 2 0 0 1-.853.505l-2.873.84a.5.5 0 0 1-.62-.62l.84-2.873a2 2 0 0 1 .506-.852z",
    ) }

    val CornerDownLeft: ImageVector by lazy { vector("CornerDownLeft",
        "M20 4v7a4 4 0 0 1-4 4H4",
        "m9 10-5 5 5 5",
    ) }

    val FileUp: ImageVector by lazy { vector("FileUp",
        "M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z",
        "M14 2v5a1 1 0 0 0 1 1h5",
        "M12 12v6",
        "m15 15-3-3-3 3",
    ) }

    val Scale: ImageVector by lazy { vector("Scale",
        "M12 3v18",
        "m19 8 3 8a5 5 0 0 1-6 0zV7",
        "M3 7h1a17 17 0 0 0 8-2 17 17 0 0 0 8 2h1",
        "m5 8 3 8a5 5 0 0 1-6 0zV7",
        "M7 21h10",
    ) }

    val FileTerminal: ImageVector by lazy { vector("FileTerminal",
        "M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z",
        "M14 2v5a1 1 0 0 0 1 1h5",
        "m8 16 2-2-2-2",
        "M12 18h4",
    ) }

    val Terminal: ImageVector by lazy { vector("Terminal",
        "M12 19h8",
        "m4 17 6-6-6-6",
    ) }

    val BrushCleaning: ImageVector by lazy { vector("BrushCleaning",
        "m16 22-1-4",
        "M19 14a1 1 0 0 0 1-1v-1a2 2 0 0 0-2-2h-3a1 1 0 0 1-1-1V4a2 2 0 0 0-4 0v5a1 1 0 0 1-1 1H6a2 2 0 0 0-2 2v1a1 1 0 0 0 1 1",
        "M19 14H5l-1.973 6.767A1 1 0 0 0 4 22h16a1 1 0 0 0 .973-1.233z",
        "m8 22 1-4",
    ) }

    val Eraser: ImageVector by lazy { vector("Eraser",
        "M21 21H8a2 2 0 0 1-1.42-.587l-3.994-3.999a2 2 0 0 1 0-2.828l10-10a2 2 0 0 1 2.829 0l5.999 6a2 2 0 0 1 0 2.828L12.834 21",
        "m5.082 11.09 8.828 8.828",
    ) }

    val KeyRound: ImageVector by lazy { vector("KeyRound",
        "M2.586 17.414A2 2 0 0 0 2 18.828V21a1 1 0 0 0 1 1h3a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h1a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h.172a2 2 0 0 0 1.414-.586l.814-.814a6.5 6.5 0 1 0-4-4z",
        "M16 7.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0Z",
    ) }

    val Waypoints: ImageVector by lazy { vector("Waypoints",
        "m10.586 5.414-5.172 5.172",
        "m18.586 13.414-5.172 5.172",
        "M6 12h12",
        "M10 20a2 2 0 1 0 4 0a2 2 0 1 0 -4 0Z",
        "M10 4a2 2 0 1 0 4 0a2 2 0 1 0 -4 0Z",
        "M18 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0Z",
        "M2 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0Z",
    ) }

    val LayoutPanelTop: ImageVector by lazy { vector("LayoutPanelTop",
        "M4 3H20a1 1 0 0 1 1 1V9a1 1 0 0 1 -1 1H4a1 1 0 0 1 -1 -1V4a1 1 0 0 1 1 -1Z",
        "M4 14H9a1 1 0 0 1 1 1V20a1 1 0 0 1 -1 1H4a1 1 0 0 1 -1 -1V15a1 1 0 0 1 1 -1Z",
        "M15 14H20a1 1 0 0 1 1 1V20a1 1 0 0 1 -1 1H15a1 1 0 0 1 -1 -1V15a1 1 0 0 1 1 -1Z",
    ) }

    val PencilLine: ImageVector by lazy { vector("PencilLine",
        "M13 21h8",
        "m15 5 4 4",
        "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z",
    ) }

    val ArrowUpFromLine: ImageVector by lazy { vector("ArrowUpFromLine",
        "m18 9-6-6-6 6",
        "M12 3v14",
        "M5 21h14",
    ) }

    val ArrowDownToLine: ImageVector by lazy { vector("ArrowDownToLine",
        "M12 17V3",
        "m6 11 6 6 6-6",
        "M19 21H5",
    ) }

    val Maximize: ImageVector by lazy { vector("Maximize",
        "M8 3H5a2 2 0 0 0-2 2v3",
        "M21 8V5a2 2 0 0 0-2-2h-3",
        "M3 16v3a2 2 0 0 0 2 2h3",
        "M16 21h3a2 2 0 0 0 2-2v-3",
    ) }

    val BellRing: ImageVector by lazy { vector("BellRing",
        "M10.268 21a2 2 0 0 0 3.464 0",
        "M22 8c0-2.3-.8-4.3-2-6",
        "M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326",
        "M4 2C2.8 3.7 2 5.7 2 8",
    ) }

    val Image: ImageVector by lazy { vector("Image",
        "M5 3H19a2 2 0 0 1 2 2V19a2 2 0 0 1 -2 2H5a2 2 0 0 1 -2 -2V5a2 2 0 0 1 2 -2Z",
        "M7 9a2 2 0 1 0 4 0a2 2 0 1 0 -4 0Z",
        "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21",
    ) }
}

/**
 * The line icon that stands for a Material icon. Pages written against the Material set keep
 * passing `Icons.Rounded.X`; every row, bar and menu resolves it here, so nothing filled or
 * differently weighted reaches the screen. Icons that are already line icons pass through.
 */
internal fun hxLineIcon(icon: ImageVector): ImageVector {
    val name = icon.name
    val material = name.startsWith("Rounded.") || name.startsWith("Outlined.") || name.startsWith("Filled.") ||
        name.startsWith("AutoMirrored.") || name.startsWith("Sharp.") || name.startsWith("TwoTone.")
    if (!material) return icon
    return when (name.substringAfterLast('.')) {
        "Refresh", "Sync", "Cached", "Autorenew" -> HomeIcons.RefreshCw
        "Check", "Done" -> HomeIcons.Check
        "Description", "Article", "Notes", "Subject" -> ToolsIcons.FileText
        "Info" -> HomeIcons.Info
        "Delete", "DeleteOutline", "DeleteSweep" -> ToolsIcons.Trash2
        "CheckCircle", "CheckCircleOutline", "TaskAlt" -> HomeIcons.CircleCheck
        "Search" -> ToolsIcons.Search
        "SearchOff" -> PanelIcons.SearchX
        "Add" -> ToolsIcons.Plus
        "Folder" -> ToolsIcons.Folder
        "FolderOpen", "FileOpen" -> HxIcons.FolderOpen
        "CreateNewFolder" -> HxIcons.FolderPlus
        "NoteAdd" -> HxIcons.FilePlus
        "ChevronRight", "KeyboardArrowRight", "NavigateNext" -> HomeIcons.ChevronRight
        "ArrowBack", "ArrowBackIosNew", "ChevronLeft", "KeyboardArrowLeft" -> HomeIcons.ChevronLeft
        "KeyboardArrowUp", "ExpandLess" -> PanelIcons.ChevronUp
        "KeyboardArrowDown", "ExpandMore", "ArrowDropDown" -> PanelIcons.ChevronDown
        "UnfoldMore" -> HomeIcons.ChevronsUpDown
        "Public", "Language" -> ToolsFeatureIcons.Globe
        "MoreHoriz" -> ToolsIcons.Ellipsis
        "MoreVert" -> ToolsFeatureIcons.EllipsisVertical
        "ContentCopy", "CopyAll" -> HomeIcons.Copy
        "Close", "Cancel", "Clear" -> HomeIcons.X
        "Router" -> ToolsFeatureIcons.Router
        "Palette", "ColorLens", "FormatPaint" -> HxIcons.Palette
        "Memory" -> ToolsIcons.Cpu
        "GridView", "Apps" -> ToolsIcons.LayoutGrid
        "ErrorOutline", "Error", "ReportProblem" -> HomeIcons.CircleAlert
        "Warning", "WarningAmber" -> HomeIcons.TriangleAlert
        "Wifi" -> ToolsIcons.Wifi
        "SignalCellularAlt" -> HxIcons.Signal
        "WifiTethering", "CellTower" -> ToolsIcons.RadioTower
        "Sensors" -> HxIcons.Radio
        "Shield", "Security", "VerifiedUser" -> ToolsFeatureIcons.Shield
        "HealthAndSafety" -> ToolsFeatureIcons.ShieldPlus
        "FactCheck" -> ToolsFeatureIcons.ShieldCheck
        "Rule" -> HxIcons.ListChecks
        "Save" -> HomeIcons.Save
        "RestartAlt", "PowerSettingsNew" -> HomeIcons.Power
        "Link" -> ToolsIcons.Link
        "LinkOff" -> PanelIcons.Unlink
        "IosShare", "Share" -> ToolsIcons.Share
        "Inventory2" -> HxIcons.AppWindow
        "CloudSync", "CloudUpload", "Backup" -> HxIcons.CloudUpload
        "CloudDownload", "Restore" -> HxIcons.CloudDownload
        "WrapText" -> HxIcons.TextWrap
        "Undo" -> ToolsIcons.Undo2
        "Redo" -> ToolsIcons.Redo2
        "Route" -> ToolsIcons.Undo2
        "SystemUpdateAlt", "SaveAlt", "FileDownload", "Download" -> ToolsIcons.Download
        "UploadFile", "FileUpload", "Upload" -> HxIcons.Upload
        "Sort" -> ToolsFeatureIcons.ArrowUpDown
        "FormatListNumbered" -> HxIcons.ListOrdered
        "FormatListBulleted", "List" -> ToolsIcons.List
        "Favorite", "FavoriteBorder" -> HxIcons.Heart
        "Edit", "ModeEdit" -> ToolsIcons.Pencil
        "DriveFileRenameOutline" -> HxIcons.TextCursorInput
        "TextFields" -> HxIcons.Type
        "Dns", "Storage" -> HomeIcons.Server
        "Cable" -> HxIcons.Cable
        "AutoAwesome" -> HxIcons.Sparkles
        "ViewColumn" -> HxIcons.Columns2
        "DensityMedium" -> HxIcons.Rows3
        "Tune" -> HomeIcons.SlidersHorizontal
        "Settings" -> HxIcons.Settings
        "TouchApp" -> PanelIcons.Pointer
        "SwapHoriz" -> HxIcons.MoveHorizontal
        "StopCircle" -> HxIcons.CircleStop
        "PlayCircleOutline", "PlayCircle" -> HxIcons.CirclePlay
        "PlayArrow" -> HxIcons.Play
        "RadioButtonUnchecked" -> HxIcons.Circle
        "QueryStats", "Insights" -> HomeIcons.Activity
        "PhoneAndroid", "Smartphone" -> HxIcons.Smartphone
        "Devices" -> HxIcons.MonitorSmartphone
        "OpenInNew", "Launch" -> HxIcons.ExternalLink
        "Notifications", "NotificationsNone" -> HxIcons.Bell
        "LightMode" -> HxIcons.Sun
        "DarkMode" -> HxIcons.Moon
        "Contrast" -> HxIcons.Contrast
        "BlurOn", "WaterDrop" -> HxIcons.Droplet
        "LibraryBooks", "MenuBook" -> HxIcons.BookOpen
        "Layers" -> PanelIcons.Layers
        "Image" -> PanelIcons.Image
        "History", "Schedule" -> HomeIcons.Clock
        "HelpOutline", "Help" -> ToolsFeatureIcons.CircleHelp
        "FilterAlt", "FilterList" -> PanelIcons.Funnel
        "Dashboard" -> PanelIcons.Gauge
        "Code" -> HxIcons.Code
        "Terminal" -> ToolsIcons.SquareTerminal
        "Bolt" -> PanelIcons.Zap
        "Block" -> ToolsFeatureIcons.Ban
        "AltRoute" -> PanelIcons.Route
        "Home" -> io.github.xgl34222220.hetu.ui.HetuLucideIcons.House
        else -> icon
    }
}
