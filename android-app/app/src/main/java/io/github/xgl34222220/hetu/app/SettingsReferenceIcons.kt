package io.github.xgl34222220.hetu

import androidx.compose.ui.graphics.vector.ImageVector
import io.github.xgl34222220.hetu.tools.ToolsIcons

/*
 * Names the pages already use for their icons. Every one of them now resolves to the shared
 * line-icon family (see HxLucide.kt); nothing here draws a glyph of its own any more.
 */

/** Folder of the file manager list. */
internal object FileManagerReferenceIcons {
    val Folder: ImageVector get() = ToolsIcons.Folder
}

/** Glyphs of the motion settings that have no Material counterpart. */
internal object SettingsActionIcons {
    val FloatingDock: ImageVector get() = HxIcons.PanelBottom
    val LiquidGlass: ImageVector get() = HxIcons.Sparkles
    val Scale: ImageVector get() = HxIcons.Scaling
}

/** The line icon for a Material icon passed to a settings row. */
internal fun settingsLineIcon(icon: ImageVector): ImageVector = hxLineIcon(icon)

/** Kept for hosts that still pair a tool title with an icon; the title no longer changes the glyph. */
@Suppress("UNUSED_PARAMETER")
internal fun toolReferenceLineIcon(title: String, icon: ImageVector): ImageVector = hxLineIcon(icon)
