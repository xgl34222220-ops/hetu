package io.github.xgl34222220.hetu

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
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
