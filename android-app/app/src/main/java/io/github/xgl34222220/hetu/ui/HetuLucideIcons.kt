// Geometry from Lucide; see assets/licenses/lucide.txt and design/vendor/lucide/sources.json.
package io.github.xgl34222220.hetu.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Only the six used icons are bundled. No icon font or runtime SVG/network loading. */
internal object HetuLucideIcons {
    private fun vector(name: String, vararg paths: String): ImageVector = ImageVector.Builder(
        name = "Lucide.$name", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        paths.forEach { data ->
            addPath(pathData = PathParser().parsePathString(data).toNodes(), fill = null,
                stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()

    val House: ImageVector by lazy { vector("House",
        "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8",
        "M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
    ) }

    val LayoutDashboard: ImageVector by lazy { vector("LayoutDashboard",
        "M4 3H9a1 1 0 0 1 1 1V11a1 1 0 0 1 -1 1H4a1 1 0 0 1 -1 -1V4a1 1 0 0 1 1 -1Z",
        "M15 3H20a1 1 0 0 1 1 1V7a1 1 0 0 1 -1 1H15a1 1 0 0 1 -1 -1V4a1 1 0 0 1 1 -1Z",
        "M15 12H20a1 1 0 0 1 1 1V20a1 1 0 0 1 -1 1H15a1 1 0 0 1 -1 -1V13a1 1 0 0 1 1 -1Z",
        "M4 16H9a1 1 0 0 1 1 1V20a1 1 0 0 1 -1 1H4a1 1 0 0 1 -1 -1V17a1 1 0 0 1 1 -1Z",
    ) }

    val Settings2: ImageVector by lazy { vector("Settings2",
        "M14 17H5",
        "M19 7h-9",
        "M14 17a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z",
        "M4 7a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z",
    ) }

    val Globe: ImageVector by lazy { vector("Globe",
        "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z",
        "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20",
        "M2 12h20",
    ) }

    val Network: ImageVector by lazy { vector("Network",
        "M17 16H21a1 1 0 0 1 1 1V21a1 1 0 0 1 -1 1H17a1 1 0 0 1 -1 -1V17a1 1 0 0 1 1 -1Z",
        "M3 16H7a1 1 0 0 1 1 1V21a1 1 0 0 1 -1 1H3a1 1 0 0 1 -1 -1V17a1 1 0 0 1 1 -1Z",
        "M10 2H14a1 1 0 0 1 1 1V7a1 1 0 0 1 -1 1H10a1 1 0 0 1 -1 -1V3a1 1 0 0 1 1 -1Z",
        "M5 16v-3a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1v3",
        "M12 12V8",
    ) }

    val Cpu: ImageVector by lazy { vector("Cpu",
        "M12 20v2",
        "M12 2v2",
        "M17 20v2",
        "M17 2v2",
        "M2 12h2",
        "M2 17h2",
        "M2 7h2",
        "M20 12h2",
        "M20 17h2",
        "M20 7h2",
        "M7 20v2",
        "M7 2v2",
        "M6 4H18a2 2 0 0 1 2 2V18a2 2 0 0 1 -2 2H6a2 2 0 0 1 -2 -2V6a2 2 0 0 1 2 -2Z",
        "M9 8H15a1 1 0 0 1 1 1V15a1 1 0 0 1 -1 1H9a1 1 0 0 1 -1 -1V9a1 1 0 0 1 1 -1Z",
    ) }
}

/** Retain BaiZe art for other categories and original app/configured group logos. */
internal fun hetuLucideIcon(icon: ImageVector): ImageVector? {
    if (icon.name.startsWith("Lucide.")) return icon
    return when (icon.name.substringAfterLast('.')) {
        "Home" -> HetuLucideIcons.House
        "Dashboard", "DashboardCustomize", "SpaceDashboard", "ViewModule", "GridView", "Apps" -> HetuLucideIcons.LayoutDashboard
        "Settings", "SettingsSuggest", "Tune", "SettingsEthernet" -> HetuLucideIcons.Settings2
        "Public", "Web" -> HetuLucideIcons.Globe
        "Link", "Hub", "AltRoute", "Route" -> HetuLucideIcons.Network
        "Memory" -> HetuLucideIcons.Cpu
        else -> null
    }
}
