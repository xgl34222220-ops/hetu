package io.github.xgl34222220.hetu.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.HomeContinuousShape
import io.github.xgl34222220.hetu.Hx
import io.github.xgl34222220.hetu.hxPressScale
import io.github.xgl34222220.hetu.home.*

/** Tools-only dimensions from the supplied 03A/03B concepts. Home and panel keep their tokens. */
internal object ToolsDesignDims {
    val gutter = 14.dp
    val gap = 14.dp
    val cardPadding = 16.dp
    val rowMinHeight = 64.dp
    val rowMinHeightSmall = 56.dp
    val touch = 48.dp
    val barHeight = 64.dp
    val dockClearance = 116.dp
    val cardShape = HomeContinuousShape(24.dp)
    val controlShape = HomeContinuousShape(14.dp)
    val segmentShape = HomeContinuousShape(13.dp)
    val badgeShape = RoundedCornerShape(10.dp)
    val chipShape = RoundedCornerShape(8.dp)
    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val menuShape = HomeContinuousShape(20.dp)
}

internal object ToolsTypography {
    val largeTitle = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
    val barTitle = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
    val barSubtitle = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
    val sheetTitle = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
    val heroStatus = TextStyle(fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold)
    val rowTitle = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 16.sp, lineHeight = 23.sp)
    val bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 21.sp)
    val rowSub = TextStyle(fontSize = 14.sp, lineHeight = 19.sp)
    val section = TextStyle(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold)
    val caption = TextStyle(fontSize = 14.sp, lineHeight = 19.sp)
    val note = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val noteStrong = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val label = TextStyle(fontSize = 16.sp, lineHeight = 22.sp)
    val badge = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    val value = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val metric = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val metricLarge = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val delay = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
    val button = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val buttonSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val mono = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontFamily = FontFamily.Monospace)
}

/** Use the actual app palette, including live Monet/custom accents, with the concept's flat canvas. */
@Composable
internal fun ToolsConceptTheme(content: @Composable () -> Unit) {
    val base = LocalHomeColors.current
    val current = Hx.colors
    val colors = base.copy(
        bg = if (current.dark) current.canvas else Color(0xFFECEEFB),
        surface = if (current.dark) current.surface else Color(0xFFF8F7FD),
        sunken = current.surfaceMuted,
        t1 = current.text,
        t2 = current.textMuted,
        t3 = current.textFaint,
        accent = current.accent,
        onAccent = current.onAccent,
        accentSoft = current.accentSoft,
        dark = current.dark,
    )
    CompositionLocalProvider(LocalHomeColors provides colors, content = content)
}

/** A real refresh gesture, with the same action exposed to keyboard and accessibility users. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolsPullRefresh(
    refreshing: Boolean,
    onRefresh: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (onRefresh == null) Box(modifier) { content() }
    else PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh,
        modifier = modifier.semantics {
            customActions = listOf(CustomAccessibilityAction("刷新") { if (!refreshing) onRefresh(); true })
        }) { content() }
}

@Composable
internal fun ToolsSurfaceCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    clickLabel: String? = null,
    background: Color = LocalHomeColors.current.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val haptics = LocalHomeHaptics.current
    val source = remember { MutableInteractionSource() }
    val shaped = modifier.clip(ToolsDesignDims.cardShape).background(background)
    val interactive = if (onClick == null) shaped else shaped.hxPressScale(source, .985f)
        .clickable(interactionSource = source, indication = null, onClickLabel = clickLabel, role = Role.Button) {
            haptics(HomeHaptic.Tap); onClick()
        }
    Column(interactive, content = content)
}

@Composable
internal fun ToolsHairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(.5.dp).background(LocalHomeColors.current.line.copy(alpha = .45f)))
}

@Composable
internal fun ToolsIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    tint: Color = LocalHomeColors.current.t1,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val source = remember { MutableInteractionSource() }
    Box(modifier.size(48.dp).clip(ToolsDesignDims.controlShape).hxPressScale(source, .94f)
        .clickable(enabled = enabled && !loading, interactionSource = source, indication = null,
            onClickLabel = label, role = Role.Button) { haptics(HomeHaptic.Tap); onClick() }
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        if (loading) HomeSpinner(size = 18.dp, color = c.t2)
        else Icon(icon, null, Modifier.size(26.dp), tint = if (enabled) tint else c.t3)
    }
}

@Composable
internal fun ToolsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: HomeButtonKind = HomeButtonKind.Secondary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val source = remember { MutableInteractionSource() }
    val (fill, foreground) = when (kind) {
        HomeButtonKind.Primary -> c.accent to c.onAccent
        HomeButtonKind.Secondary -> c.surface to c.t1
        HomeButtonKind.Soft -> c.accentSoft to c.accent
        HomeButtonKind.Ghost -> Color.Transparent to c.accent
    }
    Row(modifier.heightIn(min = 48.dp).alpha(if (enabled) 1f else .45f)
        .clip(ToolsDesignDims.controlShape).background(fill)
        .then(if (kind == HomeButtonKind.Secondary) Modifier.border(.75.dp, c.line2, ToolsDesignDims.controlShape) else Modifier)
        .hxPressScale(source, .97f).clickable(enabled = enabled && !loading, interactionSource = source, indication = null,
            role = Role.Button) { haptics(HomeHaptic.Tap); onClick() }
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        if (loading) HomeSpinner(size = 18.dp, color = foreground)
        else if (icon != null) Icon(icon, null, Modifier.size(22.dp), tint = foreground)
        Text(text, color = foreground, style = ToolsTypography.button, maxLines = 1)
    }
}

@Composable
internal fun ToolsTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = LocalHomeColors.current
    Box(modifier.fillMaxWidth().background(c.bg).windowInsetsPadding(WindowInsets.statusBars)
        .height(if (subtitle == null) ToolsDesignDims.barHeight else 78.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            ToolsIconButton(HomeIcons.ChevronLeft, "返回", onBack)
            Spacer(Modifier.weight(1f))
            actions()
        }
        Column(Modifier.align(Alignment.Center).padding(horizontal = 72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = c.t1, style = ToolsTypography.barTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = c.t3, style = ToolsTypography.barSubtitle,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun <T> ToolsSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    Row(modifier.fillMaxWidth().clip(ToolsDesignDims.cardShape).background(c.surface).padding(8.dp).selectableGroup()) {
        options.forEach { (key, label) ->
            Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(ToolsDesignDims.segmentShape)
                .background(if (selected == key) c.accentSoft else Color.Transparent)
                .selectable(selected = selected == key, enabled = enabled, role = Role.Tab) { haptics(HomeHaptic.Tick); onSelect(key) }
                .padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                Text(label, color = if (selected == key) c.accent else c.t2, style = ToolsTypography.rowTitle, maxLines = 1)
            }
        }
    }
}
