package io.github.xgl34222220.hetu.ui

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Actual outer dock height, including its own already-applied navigation inset. */
val LocalHetuDockHeight = staticCompositionLocalOf { 0.dp }
val LocalHetuMotionEnabled = staticCompositionLocalOf { true }
/** One policy for the dock's renderer and its backdrop producer. */
internal val LocalHetuGlassEffectsEnabled = staticCompositionLocalOf { true }

@Composable
internal fun rememberHetuGlassEnabled(): Boolean {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("hetu", 0) }
    fun readPreference() = prefs.getBoolean("enableBlur", true) && prefs.getBoolean("liquidGlass", true)
    var enabled by remember(prefs) { mutableStateOf(readPreference()) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "enableBlur" || key == "liquidGlass") enabled = readPreference()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled && LocalHetuGlassEffectsEnabled.current
}

/** Low-memory and battery-saver devices keep the static material without offscreen glass. */
@Composable
internal fun rememberHetuPowerConstrained(): Boolean {
    val context = LocalContext.current
    val power = remember(context) { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    val lowMemory = remember(context) { (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true }
    var saving by remember(power) { mutableStateOf(power?.isPowerSaveMode == true) }
    DisposableEffect(context, power) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { saving = power?.isPowerSaveMode == true }
        }
        val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return lowMemory || saving
}

@Composable
fun rememberHetuMotionEnabled(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val prefs = remember(context) { context.getSharedPreferences("hetu", 0) }
    var enabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    var preference by remember(prefs) { mutableStateOf(prefs.getBoolean("enableAnimations", true)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { enabled = ValueAnimator.areAnimatorsEnabled() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { shared, key ->
            if (key == "enableAnimations") preference = shared.getBoolean(key, true)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled && preference
}

@Composable
fun hetuContentBottomPadding(): Dp {
    val dock = LocalHetuDockHeight.current
    val system = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Dock is measured including its navigation inset and floating bottom gap.
    // Only add the design-system content gap; never count navigationBars twice.
    return if (dock > 0.dp) dock + HetuBottomBarMetrics.ContentGap else system + HetuBottomBarMetrics.ContentGap
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
        style = style.copy(fontFeatureSettings = "tnum", fontFamily = io.github.xgl34222220.hetu.ui.HetuSystemFontFamily,
            platformStyle = PlatformTextStyle(includeFontPadding = true),
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)),
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
    Box(modifier.size(28.dp),
        contentAlignment = Alignment.Center) {
        Icon(baiZeLineIcon(lineIcon), null, Modifier.size(24.dp), tint = LocalHetuTokens.current.textPrimary)
    }
}

@Composable
internal fun Modifier.hetuTopBarBackdrop(): Modifier {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("hetu", 0)
    val enabled = prefs.getBoolean("enableBlur", true)
    if (!enabled) return this

    val style = prefs.getString("topBarBlurStyle", "progressive").orEmpty().ifBlank { "progressive" }
    val tokens = LocalHetuTokens.current
    val shape = RoundedCornerShape(22.dp)
    val base = crystalMaterial(
        shape = shape,
        depth = if (style == "gaussian") CrystalDepth.Popover else CrystalDepth.InsetItem,
    )
    if (style == "gaussian") return this.then(base)

    return this.then(base).drawWithCache {
        val radius = 22.dp.toPx()
        val fade = Brush.verticalGradient(
            colors = listOf(
                tokens.elevatedCardBackground.copy(alpha = .18f),
                tokens.cardBackground.copy(alpha = .08f),
                Color.Transparent,
            ),
        )
        onDrawWithContent {
            drawContent()
            drawRoundRect(
                brush = fade,
                cornerRadius = CornerRadius(radius, radius),
            )
        }
    }
}

@Composable
fun HetuPageHeader(title: String, onBack: () -> Unit, subtitle: String = "", actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = HetuPageMetrics.ToolbarHeight).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HetuLuoShuHeaderAction(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack,
            contentColor = LocalHetuTokens.current.textPrimary)
        Column(Modifier.weight(1f)) {
            Text(ht(title), style = MaterialTheme.typography.titleLarge.copy(fontSize = 16.5.sp, lineHeight = 22.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(ht(subtitle), style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp, lineHeight = 14.sp),
                color = LocalHetuTokens.current.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}
