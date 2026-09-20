package io.github.xgl34222220.hetu.ui

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DashboardCustomize

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Actual outer dock height, including its own already-applied navigation inset. */
val LocalHetuDockHeight = staticCompositionLocalOf { 0.dp }
val LocalHetuMotionEnabled = staticCompositionLocalOf { true }

@Composable
fun rememberHetuMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var enabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { enabled = ValueAnimator.areAnimatorsEnabled() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}

@Composable
fun hetuContentBottomPadding(): Dp {
    val dock = LocalHetuDockHeight.current
    val system = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Dock is measured including inset. Do not add navigationBars for a second time.
    return if (dock > 0.dp) dock + 30.dp else system + 16.dp
}

@Composable
fun hetuCompactColumns(availableWidth: Dp, minimumCell: Dp = 172.dp): Int {
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    return if (availableWidth < minimumCell * (2f * scale) + 12.dp) 1 else 2
}

@Composable
fun HetuNumber(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalHetuTokens.current.textPrimary,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    monospaced: Boolean = false,
) {
    // Full text is never silently clipped or ellipsized. Width/layout adapts instead.
    Text(text, modifier, color = color,
        style = style.copy(fontFeatureSettings = "tnum", fontFamily = if (monospaced) FontFamily.Monospace else FontFamily.SansSerif),
        softWrap = true, overflow = TextOverflow.Visible)
}

@Composable
fun HetuReadOnlyProgress(progress: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val track = LocalHetuTokens.current.outline.copy(alpha = .6f)
    Box(modifier.fillMaxWidth().height(4.dp).background(track, CircleShape)) {
        if (progress > 0f) Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(color, CircleShape))
    }
}

@Composable
fun HetuBusyIndicator(modifier: Modifier = Modifier.size(18.dp), color: Color = MaterialTheme.colorScheme.primary) {
    if (LocalHetuMotionEnabled.current) CircularProgressIndicator(modifier, color = color, strokeWidth = 2.dp)
    else Icon(Icons.Rounded.HourglassEmpty, "处理中", modifier, tint = color)
}

@Composable
fun HetuListIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    val lineIcon = when (icon.name.substringAfterLast('.')) {
        "Memory" -> Icons.Outlined.Memory
        "Settings" -> Icons.Outlined.Settings
        "Shield" -> Icons.Outlined.Shield
        "Tune" -> Icons.Outlined.Tune
        "Public" -> Icons.Outlined.Public
        "Speed" -> Icons.Outlined.Speed
        "Article" -> Icons.Outlined.Article
        "Terminal" -> Icons.Outlined.Terminal
        "Apps" -> Icons.Outlined.Apps
        "Wifi" -> Icons.Outlined.Wifi
        "WifiTethering" -> Icons.Outlined.WifiTethering
        "AltRoute" -> Icons.Outlined.AltRoute
        "CloudDownload" -> Icons.Outlined.CloudDownload
        "Palette" -> Icons.Outlined.Palette
        "BlurOn" -> Icons.Outlined.BlurOn
        "Bolt" -> Icons.Outlined.Bolt
        "Hub" -> Icons.Outlined.Hub
        "DarkMode" -> Icons.Outlined.DarkMode
        "DashboardCustomize" -> Icons.Outlined.DashboardCustomize
        else -> icon
    }
    Box(modifier.size(36.dp).background(LocalHetuTokens.current.controlBackground, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center) {
        Icon(lineIcon, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun HetuPageHeader(title: String, onBack: () -> Unit, subtitle: String = "", actions: @Composable RowScope.() -> Unit = {}) {
    val t = LocalHetuTokens.current
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack, Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = t.textPrimary) }
        Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = t.textPrimary, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
            if (subtitle.isNotBlank()) Text(subtitle, color = t.textOnPage, fontSize = 12.sp, lineHeight = 17.sp)
        }
        actions()
    }
}
