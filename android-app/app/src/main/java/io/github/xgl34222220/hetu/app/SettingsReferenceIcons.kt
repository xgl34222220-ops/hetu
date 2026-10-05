package io.github.xgl34222220.hetu

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.ui.baiZeLineIcon

/** Small, outline-only glyphs for the supplied settings reference. */
private object SettingsReferenceIcons {
    private fun line(name: String, vararg data: String): ImageVector = ImageVector.Builder(
        name = "HetuSettings.$name", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        data.forEach { addPath(PathParser().parsePathString(it).toNodes(), fill = null, stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
    }.build()
    val Script = line("Script", "M6 5 9 6 12 2 15 6 18 5v7a6 6 0 0 1-12 0V5Z", "M12 18v5", "m5 20 4 3", "m19 20-4 3")
    val Broadcast = line("Broadcast", "M12 10a2 2 0 1 1 0 4a2 2 0 1 1 0-4Z", "M8 17a6 6 0 1 1 8 0", "M5 20a10 10 0 1 1 14 0", "M12 16v7")
    val Fork = line("Fork", "M12 22v-9", "M12 15 6 9V3", "M12 15 18 9V3", "m3 6 3-3 3 3", "m15 6 3-3 3 3")
    val FilledApps = ImageVector.Builder(name = "HetuTools.Apps", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        listOf("M4 2h5a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2Z", "M15 2h5a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-5a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2Z", "M4 13h5a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2Z", "M15 13h5a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-5a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2Z").forEach {
            addPath(PathParser().parsePathString(it).toNodes(), fill = SolidColor(Color.Black))
        }
    }.build()
    val Config = line("Config", "m12 2 9 5v10l-9 5-9-5V7l9-5Z", "M16 12a4 4 0 1 1-8 0a4 4 0 1 1 8 0")
    val Download = line("Download", "M12 2v13", "m7 10 5 5 5-5", "M3 13v8h18v-8")
    val Database = line("Database", "M21 5c0 2-4 3-9 3S3 7 3 5s4-3 9-3 9 1 9 3Z", "M3 5v14c0 2 4 3 9 3s9-1 9-3V5", "M3 12c0 2 4 3 9 3s9-1 9-3")
    val Monitor = line("Monitor", "M3 3h18v14H3V3Z", "M12 17v5", "M7 22h10")
    val Grid = line("Grid", "M5 3h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z", "M16 3h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-3a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z", "M5 14h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2Z", "M16 14h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-3a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2Z")
    val Power = line("Power", "M12 2v10", "M5.6 5.6a9 9 0 1 0 12.8 0")
    val Layers = line("Layers", "m3 7 9-5 9 5-9 5-9-5Z", "m3 12 9 5 9-5", "m3 17 9 5 9-5")
    val Bell = line("Bell", "M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9Z", "M10 21h4", "M12 2V1")
    val Link = line("Link", "M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7", "M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7")
    val Refresh = line("Refresh", "M20 4v6h-6", "M4 20v-6h6", "M4.5 9a8 8 0 0 1 13.2-4.2L20 7", "M19.5 15a8 8 0 0 1-13.2 4.2L4 17")
    val CloudUp = line("CloudUp", "M6 18a5 5 0 0 1-1-9.9A7 7 0 0 1 18.8 8 5 5 0 0 1 19 18", "M12 22V12", "m8 16 4-4 4 4")
    val CloudDown = line("CloudDown", "M6 18a5 5 0 0 1-1-9.9A7 7 0 0 1 18.8 8 5 5 0 0 1 19 18", "M12 12v10", "m8 18 4 4 4-4")
    val Droplet = line("Droplet", "M12 2C10 5 4 10 4 15a8 8 0 0 0 16 0c0-5-6-10-8-13Z", "M8 15a4 4 0 0 0 4 4")
    val Dock = line("Dock", "M4 6h16a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2Z", "M6 14h12")
    val LiquidGlass = line("LiquidGlass", "M10 4 12 10 18 12 12 14 10 20 8 14 2 12 8 10 10 4Z", "M19 2 20 5 23 6 20 7 19 10 18 7 15 6 18 5 19 2Z")
    val Scale = line("Scale", "M8 3H5a2 2 0 0 0-2 2v3", "M16 3h3a2 2 0 0 1 2 2v3", "M21 16v3a2 2 0 0 1-2 2h-3", "M8 21H5a2 2 0 0 1-2-2v-3")
    val Move = line("Move", "M2 12h20", "m6 8-4 4 4 4", "m18 8 4 4-4 4")
    val Back = line("Back", "m9 4-6 6 6 6", "M3 10h12a6 6 0 0 1 0 12h-4")
    val Gauge = line("Gauge", "M3.4 18a10 10 0 1 1 17.2 0Z", "m12 13 5-5", "M12 3v3", "m5 6 2 2", "M2 12h3")
    val Window = line("Window", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z", "M3 8h18")
    val CheckBox = line("CheckBox", "M6 3h12a3 3 0 0 1 3 3v12a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3Z", "m7 12 3 3 7-7")
    val Routing = line("Routing", "M6 15a3 3 0 1 1 0 6a3 3 0 0 1 0-6Z", "M18 3a3 3 0 1 1 0 6a3 3 0 0 1 0-6Z", "M9 18h6a3 3 0 0 0 0-6H9a3 3 0 0 1 0-6h6")
    val Sun = line("Sun", "M17 12a5 5 0 1 1-10 0a5 5 0 1 1 10 0Z", "M12 2v2", "M12 20v2", "M2 12h2", "M20 12h2", "m4.9 4.9 1.4 1.4", "m17.7 17.7 1.4 1.4", "m4.9 19.1 1.4-1.4", "m17.7 6.3 1.4-1.4")
    val Moon = line("Moon", "M21 13.1A9 9 0 0 1 10.9 3 9 9 0 1 0 21 13.1Z")
    val SystemTheme = line("SystemTheme", "M12 2v2", "M2 12h2", "m4.9 4.9 1.4 1.4", "m17.7 6.3 1.4-1.4", "M16.1 8a6 6 0 0 0-8.2 8", "M9.5 19A7 7 0 0 0 21 12.8a7 7 0 0 1-8-7.7", "M3 21 21 3")
    val Blur = line("Blur", "M13.5 12a1.5 1.5 0 1 1-3 0a1.5 1.5 0 1 1 3 0Z", "M12 2v3", "M12 19v3", "M2 12h3", "M19 12h3", "m4.9 4.9 2.2 2.2", "m16.9 16.9 2.2 2.2", "m4.9 19.1 2.2-2.2", "m16.9 7.1 2.2-2.2")
}

/** File-list folder traced from PDF03B page 13 (JPEG xref 29), without affecting other Folder callers.
 * Three contours preserve the rounded outer shell and both interior openings. The 51 x 49 px
 * source ink bounds scale uniformly to 24 x 23.059 in a 24-unit viewport.
 */
internal object FileManagerReferenceIcons {
    val Folder: ImageVector by lazy {
        ImageVector.Builder(name = "HetuPdf03B13.FileManagerFolder", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f).apply {
            addPath(PathParser().parsePathString("M2.353,0.706 L2.588,0.471 L3.059,0.471 L3.529,0.471 L4,0.471 L4.471,0.471 L4.941,0.471 L5.412,0.471 L5.882,0.471 L6.353,0.471 L6.824,0.471 L7.294,0.471 L7.765,0.471 L8.235,0.471 L8.706,0.471 L9.176,0.471 L9.647,0.471 L9.882,0.706 L10.118,0.941 L10.353,1.176 L10.588,1.412 L11.059,1.412 L11.294,1.647 L11.294,2.118 L11.529,2.353 L11.765,2.588 L12,2.824 L12.235,3.059 L12.235,3.529 L12.471,3.765 L12.941,3.765 L13.412,3.765 L13.882,3.765 L14.353,3.765 L14.824,3.765 L15.294,3.765 L15.765,3.765 L16.235,3.765 L16.706,3.765 L17.176,3.765 L17.647,3.765 L18.118,3.765 L18.588,3.765 L19.059,3.765 L19.529,3.765 L20,3.765 L20.471,3.765 L20.941,3.765 L21.176,4 L21.412,4.235 L21.882,4.235 L22.118,4.471 L22.353,4.706 L22.588,4.941 L22.824,5.176 L23.059,5.412 L23.059,5.882 L23.294,6.118 L23.529,6.353 L23.529,6.824 L23.765,7.059 L24,7.294 L24,7.765 L24,8.235 L24,8.706 L24,9.176 L24,9.647 L24,10.118 L24,10.588 L24,11.059 L24,11.529 L24,12 L24,12.471 L24,12.941 L24,13.412 L24,13.882 L24,14.353 L24,14.824 L24,15.294 L24,15.765 L24,16.235 L24,16.706 L24,17.176 L24,17.647 L24,18.118 L24,18.588 L24,19.059 L24,19.529 L24,20 L23.765,20.235 L23.529,20.471 L23.529,20.941 L23.294,21.176 L23.059,21.412 L23.059,21.882 L22.824,22.118 L22.588,22.353 L22.353,22.588 L21.882,22.588 L21.647,22.824 L21.412,23.059 L20.941,23.059 L20.706,23.294 L20.471,23.529 L20,23.529 L19.529,23.529 L19.059,23.529 L18.588,23.529 L18.118,23.529 L17.647,23.529 L17.176,23.529 L16.706,23.529 L16.235,23.529 L15.765,23.529 L15.294,23.529 L14.824,23.529 L14.353,23.529 L13.882,23.529 L13.412,23.529 L12.941,23.529 L12.471,23.529 L12,23.529 L11.529,23.529 L11.059,23.529 L10.588,23.529 L10.118,23.529 L9.647,23.529 L9.176,23.529 L8.706,23.529 L8.235,23.529 L7.765,23.529 L7.294,23.529 L6.824,23.529 L6.353,23.529 L5.882,23.529 L5.412,23.529 L4.941,23.529 L4.471,23.529 L4,23.529 L3.529,23.529 L3.294,23.294 L3.059,23.059 L2.588,23.059 L2.353,22.824 L2.118,22.588 L1.647,22.588 L1.412,22.353 L1.176,22.118 L0.941,21.882 L0.706,21.647 L0.471,21.412 L0.471,20.941 L0.235,20.706 L0,20.471 L0,20 L0,19.529 L0,19.059 L0,18.588 L0,18.118 L0,17.647 L0,17.176 L0,16.706 L0,16.235 L0,15.765 L0,15.294 L0,14.824 L0,14.353 L0,13.882 L0,13.412 L0,12.941 L0,12.471 L0,12 L0,11.529 L0,11.059 L0,10.588 L0,10.118 L0,9.647 L0,9.176 L0,8.706 L0,8.235 L0,7.765 L0,7.294 L0,6.824 L0,6.353 L0,5.882 L0,5.412 L0,4.941 L0,4.471 L0,4 L0,3.529 L0.235,3.294 L0.471,3.059 L0.471,2.588 L0.706,2.353 L0.941,2.118 L0.941,1.647 L1.176,1.412 L1.647,1.412 L1.882,1.176 L2.118,0.941 Z M4.471,1.882 L4.235,2.118 L4,2.353 L3.529,2.353 L3.059,2.353 L2.824,2.588 L2.588,2.824 L2.353,3.059 L2.118,3.294 L1.882,3.529 L1.882,4 L1.882,4.471 L1.882,4.941 L1.882,5.412 L1.882,5.882 L1.882,6.353 L1.882,6.824 L1.882,7.294 L1.882,7.765 L1.882,8.235 L2.118,8.471 L2.588,8.471 L2.824,8.235 L3.059,8 L3.529,8 L4,8 L4.471,8 L4.941,8 L5.412,8 L5.882,8 L6.353,8 L6.824,8 L7.294,8 L7.765,8 L8.235,8 L8.706,8 L9.176,8 L9.647,8 L10.118,8 L10.588,8 L11.059,8 L11.529,8 L12,8 L12.471,8 L12.941,8 L13.412,8 L13.882,8 L14.353,8 L14.824,8 L15.294,8 L15.765,8 L16.235,8 L16.706,8 L17.176,8 L17.647,8 L18.118,8 L18.588,8 L19.059,8 L19.529,8 L20,8 L20.471,8 L20.941,8 L21.176,8.235 L21.412,8.471 L21.882,8.471 L22.118,8.235 L22.118,7.765 L22.118,7.294 L21.882,7.059 L21.647,6.824 L21.412,6.588 L21.176,6.353 L20.941,6.118 L20.706,5.882 L20.471,5.647 L20,5.647 L19.529,5.647 L19.059,5.647 L18.588,5.647 L18.118,5.647 L17.647,5.647 L17.176,5.647 L16.706,5.647 L16.235,5.647 L15.765,5.647 L15.294,5.647 L14.824,5.647 L14.353,5.647 L13.882,5.647 L13.412,5.647 L12.941,5.647 L12.471,5.647 L12,5.647 L11.765,5.412 L11.529,5.176 L11.294,4.941 L11.059,4.706 L10.824,4.471 L10.588,4.235 L10.353,4 L10.353,3.529 L10.118,3.294 L9.882,3.059 L9.647,2.824 L9.412,2.588 L9.176,2.353 L8.706,2.353 L8.471,2.118 L8.235,1.882 L7.765,1.882 L7.294,1.882 L6.824,1.882 L6.353,1.882 L5.882,1.882 L5.412,1.882 L4.941,1.882 Z M3.059,9.882 L2.824,10.118 L2.588,10.353 L2.353,10.588 L2.118,10.824 L1.882,11.059 L1.882,11.529 L1.882,12 L1.882,12.471 L1.882,12.941 L1.882,13.412 L1.882,13.882 L1.882,14.353 L1.882,14.824 L1.882,15.294 L1.882,15.765 L1.882,16.235 L1.882,16.706 L1.882,17.176 L1.882,17.647 L1.882,18.118 L1.882,18.588 L1.882,19.059 L1.882,19.529 L1.882,20 L2.118,20.235 L2.353,20.471 L2.588,20.706 L2.824,20.941 L3.059,21.176 L3.294,21.412 L3.529,21.647 L4,21.647 L4.471,21.647 L4.941,21.647 L5.412,21.647 L5.882,21.647 L6.353,21.647 L6.824,21.647 L7.294,21.647 L7.765,21.647 L8.235,21.647 L8.706,21.647 L9.176,21.647 L9.647,21.647 L10.118,21.647 L10.588,21.647 L11.059,21.647 L11.529,21.647 L12,21.647 L12.471,21.647 L12.941,21.647 L13.412,21.647 L13.882,21.647 L14.353,21.647 L14.824,21.647 L15.294,21.647 L15.765,21.647 L16.235,21.647 L16.706,21.647 L17.176,21.647 L17.647,21.647 L18.118,21.647 L18.588,21.647 L19.059,21.647 L19.529,21.647 L20,21.647 L20.235,21.412 L20.471,21.176 L20.941,21.176 L21.176,20.941 L21.412,20.706 L21.647,20.471 L21.647,20 L21.882,19.765 L22.118,19.529 L22.118,19.059 L22.118,18.588 L22.118,18.118 L22.118,17.647 L22.118,17.176 L22.118,16.706 L22.118,16.235 L22.118,15.765 L22.118,15.294 L22.118,14.824 L22.118,14.353 L22.118,13.882 L22.118,13.412 L22.118,12.941 L22.118,12.471 L22.118,12 L22.118,11.529 L22.118,11.059 L21.882,10.824 L21.647,10.588 L21.412,10.353 L21.176,10.118 L20.941,9.882 L20.471,9.882 L20,9.882 L19.529,9.882 L19.059,9.882 L18.588,9.882 L18.118,9.882 L17.647,9.882 L17.176,9.882 L16.706,9.882 L16.235,9.882 L15.765,9.882 L15.294,9.882 L14.824,9.882 L14.353,9.882 L13.882,9.882 L13.412,9.882 L12.941,9.882 L12.471,9.882 L12,9.882 L11.529,9.882 L11.059,9.882 L10.588,9.882 L10.118,9.882 L9.647,9.882 L9.176,9.882 L8.706,9.882 L8.235,9.882 L7.765,9.882 L7.294,9.882 L6.824,9.882 L6.353,9.882 L5.882,9.882 L5.412,9.882 L4.941,9.882 L4.471,9.882 L4,9.882 L3.529,9.882 Z").toNodes(),
                fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd)
        }.build()
    }
}

/** Dedicated motion-setting glyphs; other Dashboard/GridView/BlurOn callers keep their existing icons. */
internal object SettingsActionIcons {
    val FloatingDock: ImageVector get() = SettingsReferenceIcons.Dock
    val LiquidGlass: ImageVector get() = SettingsReferenceIcons.LiquidGlass
    val Scale: ImageVector get() = SettingsReferenceIcons.Scale
}

internal fun settingsLineIcon(icon: ImageVector): ImageVector = when (icon.name.substringAfterLast('.')) {
    "GridView", "Apps" -> SettingsReferenceIcons.Grid
    "RestartAlt" -> SettingsReferenceIcons.Power
    "Layers" -> SettingsReferenceIcons.Layers
    "Download" -> SettingsReferenceIcons.Download
    "CloudSync" -> SettingsReferenceIcons.CloudUp
    "Restore" -> SettingsReferenceIcons.CloudDown
    "Notifications" -> SettingsReferenceIcons.Bell
    "Link", "LinkOff" -> SettingsReferenceIcons.Link
    "Sync", "Refresh" -> SettingsReferenceIcons.Refresh
    "BlurOn" -> SettingsReferenceIcons.Blur
    "LightMode" -> SettingsReferenceIcons.Sun
    "DarkMode" -> SettingsReferenceIcons.Moon
    "AutoAwesome" -> SettingsReferenceIcons.SystemTheme
    "AltRoute" -> SettingsReferenceIcons.Routing
    "SwapHoriz" -> SettingsReferenceIcons.Move
    "Route" -> SettingsReferenceIcons.Back
    "Dashboard" -> SettingsReferenceIcons.Gauge
    "Inventory2" -> SettingsReferenceIcons.Window
    "Check" -> SettingsReferenceIcons.CheckBox
    else -> baiZeLineIcon(icon)
}

/** Tools keep their reference-specific glyphs instead of unrelated filled Material symbols. */
internal fun toolReferenceLineIcon(title: String, icon: ImageVector): ImageVector = when (title) {
    "配置管理" -> SettingsReferenceIcons.Config
    "Sub-Store" -> SettingsReferenceIcons.Link
    "CNIP" -> SettingsReferenceIcons.Database
    "核心管理" -> SettingsReferenceIcons.Download
    "Web面板" -> SettingsReferenceIcons.Monitor
    "应用管理" -> SettingsReferenceIcons.FilledApps
    "脚本" -> SettingsReferenceIcons.Script
    "共享网络" -> SettingsReferenceIcons.Broadcast
    "绕过规则" -> SettingsReferenceIcons.Fork
    else -> settingsLineIcon(icon)
}
