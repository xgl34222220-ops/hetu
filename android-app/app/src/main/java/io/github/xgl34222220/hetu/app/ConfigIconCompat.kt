package io.github.xgl34222220.hetu.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Keeps late-stage generated screens source-compatible when a concept pass
 * references the rounded Description icon without adding the icon import.
 */
val Icons.Rounded.Description: ImageVector
    get() = Icons.Rounded.InsertDriveFile
