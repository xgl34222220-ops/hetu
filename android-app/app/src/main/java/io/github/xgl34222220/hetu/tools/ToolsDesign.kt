package io.github.xgl34222220.hetu.tools

import io.github.xgl34222220.hetu.ui.ht
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.unit.Dp
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
    val rowTitle = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
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
    val refreshLabel = ht("刷新")
    if (onRefresh == null) Box(modifier) { content() }
    else PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh,
        modifier = modifier.semantics {
            customActions = listOf(CustomAccessibilityAction(refreshLabel) { if (!refreshing) onRefresh(); true })
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
    val localizedClickLabel = clickLabel?.let { ht(it) }
    val shaped = modifier.clip(ToolsDesignDims.cardShape).background(background)
    val interactive = if (onClick == null) shaped else shaped.hxPressScale(source, .985f)
        .clickable(interactionSource = source, indication = null, onClickLabel = localizedClickLabel, role = Role.Button) {
            haptics(HomeHaptic.Tap); onClick()
        }
    Column(interactive, content = content)
}

@Composable
internal fun ToolsHairline(modifier: Modifier = Modifier) {
    val line = LocalHomeColors.current.line
    Box(modifier.fillMaxWidth().height(.5.dp).background(line.copy(alpha = line.alpha * .7f)))
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
    val localizedLabel = ht(label)
    Box(modifier.size(48.dp).clip(ToolsDesignDims.controlShape).hxPressScale(source, .94f)
        .clickable(enabled = enabled && !loading, interactionSource = source, indication = null,
            onClickLabel = localizedLabel, role = Role.Button) { haptics(HomeHaptic.Tap); onClick() }
        .semantics { contentDescription = localizedLabel }, contentAlignment = Alignment.Center) {
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
    neutral: Boolean = false,
    spinnerSize: Dp = 18.dp,
    visualHeight: Dp? = null,
    textStyle: TextStyle? = null,
    horizontalPadding: Dp? = null,
    visualCornerRadius: Dp? = null,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val source = remember { MutableInteractionSource() }
    val (fill, foreground) = when (kind) {
        HomeButtonKind.Primary -> c.accent to c.onAccent
        HomeButtonKind.Secondary -> c.surface to c.t1
        HomeButtonKind.Soft -> if (neutral) c.sunken to c.t1 else c.accentSoft to c.accent
        HomeButtonKind.Ghost -> Color.Transparent to c.accent
    }
    val backgroundModifier = if (visualHeight == null) Modifier
        .clip(if (kind == HomeButtonKind.Primary) RoundedCornerShape(24.dp) else ToolsDesignDims.controlShape).background(fill)
        .then(if (kind == HomeButtonKind.Secondary) Modifier.border(.75.dp, c.line2, ToolsDesignDims.controlShape) else Modifier)
    else Modifier
        .drawBehind {
            val height = visualHeight.toPx().coerceIn(0f, size.height)
            val top = (size.height - height) / 2f
            val radius = minOf((visualCornerRadius ?: if (kind == HomeButtonKind.Primary) 24.dp else 14.dp).toPx(), height / 2f, size.width / 2f)
            drawRoundRect(fill, Offset(0f, top), Size(size.width, height), CornerRadius(radius, radius))
            if (kind == HomeButtonKind.Secondary) {
                val stroke = .75.dp.toPx()
                val borderRadius = (radius - stroke / 2f).coerceAtLeast(0f)
                drawRoundRect(c.line2, Offset(stroke / 2f, top + stroke / 2f),
                    Size((size.width - stroke).coerceAtLeast(0f), (height - stroke).coerceAtLeast(0f)),
                    CornerRadius(borderRadius, borderRadius), style = Stroke(stroke))
            }
        }
    Row(modifier.heightIn(min = 48.dp).alpha(if (enabled) 1f else .45f)
        .then(backgroundModifier)
        .hxPressScale(source, .97f).clickable(enabled = enabled && !loading, interactionSource = source, indication = null,
            role = Role.Button) { haptics(HomeHaptic.Tap); onClick() }
        .padding(horizontal = horizontalPadding ?: 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        if (loading) HomeSpinner(size = spinnerSize, color = foreground)
        else if (icon != null) Icon(icon, null, Modifier.size(22.dp), tint = foreground)
        Text(ht(text), color = foreground, style = textStyle ?: ToolsTypography.button, maxLines = 1)
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
            Text(ht(title), color = c.t1, style = ToolsTypography.barTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(ht(subtitle), color = c.t3, style = ToolsTypography.barSubtitle,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Only this frame's UI labels are localized; its caller owns verbatim document/report bodies. */
@Composable
internal fun ToolsSheetContent(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClose: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    compactHeader: Boolean = false,
    body: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalHomeColors.current
    Column(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
        Box(Modifier.fillMaxWidth().padding(top = if (compactHeader) 0.dp else 8.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(36.dp, 4.dp).background(c.line2, RoundedCornerShape(2.dp)))
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp,
            top = if (compactHeader) 0.dp else 8.dp, bottom = if (compactHeader) 0.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ht(title), color = c.t1, style = ToolsTypography.sheetTitle)
                if (subtitle != null) Text(ht(subtitle), Modifier.padding(top = 2.dp), color = c.t2, style = ToolsTypography.rowSub)
            }
            when {
                trailing != null -> trailing()
                onClose != null -> ToolsIconButton(HomeIcons.X, "关闭", onClose)
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = if (footer == null) 24.dp else 16.dp), content = body)
        if (footer != null) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), content = footer)
    }
}

@Composable
internal fun <T> ToolsSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icons: Map<T, ImageVector> = emptyMap(),
    referenceVisualInset: Dp? = null,
    referenceOuterPadding: Dp? = null,
    referenceVisualHeight: Dp? = null,
    referenceTouchHeight: Dp? = null,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val outerModifier = if (referenceOuterPadding == null)
        modifier.fillMaxWidth().clip(ToolsDesignDims.cardShape).background(c.surface).padding(8.dp).selectableGroup()
    else modifier.fillMaxWidth().clip(ToolsDesignDims.cardShape).background(c.surface).padding(referenceOuterPadding).selectableGroup()
    val optionContent: @Composable RowScope.() -> Unit = {
        options.forEach { (key, label) ->
            val itemModifier = if (referenceVisualInset == null) Modifier.weight(1f).heightIn(min = 44.dp).clip(ToolsDesignDims.segmentShape)
                .background(if (selected == key) c.accentSoft else Color.Transparent)
                .selectable(selected = selected == key, enabled = enabled, role = Role.Tab) { haptics(HomeHaptic.Tick); onSelect(key) }
                .padding(vertical = 9.dp)
            else {
                val inset = referenceVisualInset.coerceIn(0.dp, 9.dp)
                Modifier.weight(1f).heightIn(min = 44.dp)
                    .selectable(selected = selected == key, enabled = enabled, role = Role.Tab) { haptics(HomeHaptic.Tick); onSelect(key) }
                    .padding(vertical = inset)
                    .clip(ToolsDesignDims.segmentShape)
                    .background(if (selected == key) c.accentSoft else Color.Transparent)
                    .padding(vertical = 9.dp - inset)
            }
            Box(itemModifier, contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    icons[key]?.let { Icon(it, null, Modifier.size(22.dp), tint = if (selected == key) c.accent else c.t1) }
                    Text(ht(label), color = if (selected == key) c.accent else c.t1, style = ToolsTypography.rowTitle, maxLines = 1)
                }
            }
        }
    }
    if (referenceVisualHeight == null) {
        Row(outerModifier, content = optionContent)
    } else {
        // Keep the PDF-sized track separate from the unchanged larger selectable row.
        Box(
            modifier.fillMaxWidth().height(referenceVisualHeight)
                .background(c.surface, ToolsDesignDims.cardShape).selectableGroup(),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier.fillMaxWidth().requiredHeight(referenceTouchHeight ?: 44.dp)
                    .padding(horizontal = referenceOuterPadding ?: 8.dp),
                content = optionContent,
            )
        }
    }
}
