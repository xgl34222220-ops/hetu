package io.github.xgl34222220.hetu.panel

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.xgl34222220.hetu.home.HomeButton
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeEmptyState
import io.github.xgl34222220.hetu.home.HomeHaptic
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeMotion
import io.github.xgl34222220.hetu.home.HomeSpinner
import io.github.xgl34222220.hetu.home.HomeSwitch
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeHaptics
import io.github.xgl34222220.hetu.home.LocalHomeMotionEnabled
import io.github.xgl34222220.hetu.home.badText
import io.github.xgl34222220.hetu.home.homeTap
import io.github.xgl34222220.hetu.home.warnText
import io.github.xgl34222220.hetu.ui.ht
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/* ------------------------------------------------------------------ */
/*  Panel-only tokens (everything else comes from HomeTokens)           */
/* ------------------------------------------------------------------ */

internal object PanelDims {
    /** Height of the action row above the large title. */
    val barHeight = 56.dp
    val tabHeight = 40.dp
    /** Tab strip plus the air above and below it. */
    val tabBlock = 62.dp
    val tabShape = RoundedCornerShape(12.dp)
    val groupShape = RoundedCornerShape(19.dp)
    val nodesShape = 22.dp
    val nodeShape = RoundedCornerShape(16.dp)
    val avatarShape = RoundedCornerShape(9.dp)
    val dialogShape = RoundedCornerShape(26.dp)
    val chipShape = RoundedCornerShape(12.dp)
    val dialogWidth = 320.dp
    val menuMinWidth = 184.dp
    val menuMaxWidth = 290.dp
    /** Gap between cards of the same list. */
    val gap = 11.dp
}

/** Panel-only text styles, sized from the concept. */
internal object PanelType {
    private const val Tnum = "tnum"
    val tab = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    val groupName = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
    val groupSummary = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontFeatureSettings = Tnum)
    val groupNow = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
    val tiny = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = Tnum)
    val nodeName = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold)
    val nodeMeta = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
    val cardTitle = TextStyle(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold)
    val host = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    val stat = TextStyle(fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = Tnum)
    val statSmall = TextStyle(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = Tnum)
    val chip = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val log = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
    val monoSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontFamily = FontFamily.Monospace)
    val dialogBody = TextStyle(fontSize = 15.sp, lineHeight = 23.sp)
    val menuItem = TextStyle(fontSize = 17.sp, lineHeight = 23.sp)
    val policy = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold)
    val nodeTitle = TextStyle(fontSize = 26.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold)
}

/** Real application icons supplied by the host. [has] must be cheap; [draw] fills the given size. */
internal class PanelAppIcons(val has: (packageName: String) -> Boolean, val draw: @Composable (packageName: String, modifier: Modifier) -> Unit)

/** Optional slots the host can fill with real artwork; null falls back to the built-in marks. */
internal val LocalPanelGroupIcon = staticCompositionLocalOf<(@Composable (group: PanelGroup, modifier: Modifier) -> Unit)?> { null }
internal val LocalPanelAppIcons = staticCompositionLocalOf<PanelAppIcons?> { null }

internal fun Modifier.panelGutter(): Modifier = padding(horizontal = HomeDims.gutter)

/* ------------------------------------------------------------------ */
/*  Tabs, search, chips                                                 */
/* ------------------------------------------------------------------ */

/**
 * Horizontally scrolling tab strip. Resting tabs are outlined; the selected one sits on a white
 * tile that slides and resizes to the tab that was tapped, and the strip scrolls just enough to
 * keep that tab fully in view.
 */
@Composable
internal fun PanelTabStrip(selected: PanelTab, onSelect: (PanelTab) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val motion = LocalHomeMotionEnabled.current
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val slots = remember { mutableStateMapOf<PanelTab, Pair<Float, Float>>() }
    val left = remember { Animatable(0f) }
    val width = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    var viewport by remember { mutableIntStateOf(0) }
    val target = slots[selected]
    val gutter = with(density) { HomeDims.gutter.toPx() }
    val corner = with(density) { 12.dp.toPx() }

    LaunchedEffect(target, motion) {
        if (target == null) return@LaunchedEffect
        if (!placed || !motion) {
            left.snapTo(target.first)
            width.snapTo(target.second)
            placed = true
        } else {
            launch { left.animateTo(target.first, HomeMotion.glide(true)) }
            launch { width.animateTo(target.second, HomeMotion.glide(true)) }
        }
    }
    LaunchedEffect(selected, target, viewport) {
        if (target == null || viewport <= 0) return@LaunchedEffect
        val start = target.first + gutter
        val end = start + target.second
        val margin = gutter * 2f
        val want = when {
            start - margin < scroll.value -> start - margin
            end + margin > scroll.value + viewport -> end + margin - viewport
            else -> return@LaunchedEffect
        }.coerceIn(0f, scroll.maxValue.toFloat()).roundToInt()
        if (motion) scroll.animateScrollTo(want) else scroll.scrollTo(want)
    }

    val tile = c.surface
    Row(
        modifier
            .fillMaxWidth()
            .onSizeChanged { viewport = it.width }
            .horizontalScroll(scroll)
            .panelGutter()
            .drawBehind {
                if (placed && width.value > 0f) drawRoundRect(tile, Offset(left.value, 0f), Size(width.value, size.height), CornerRadius(corner, corner))
            }
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PanelTab.entries.forEach { tab ->
            val active = tab == selected
            val outline by animateColorAsState(if (active) Color.Transparent else c.t3.copy(alpha = .78f), HomeMotion.fade(motion), label = "panel-tab-outline")
            val text by animateColorAsState(if (active) c.t1 else c.t2, HomeMotion.fade(motion), label = "panel-tab-text")
            Box(
                Modifier
                    .onGloballyPositioned { slots[tab] = it.positionInParent().x to it.size.width.toFloat() }
                    .heightIn(min = PanelDims.tabHeight)
                    .clip(PanelDims.tabShape)
                    .border(1.2.dp, outline, PanelDims.tabShape)
                    .selectable(selected = active, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) {
                        if (!active) { haptics(HomeHaptic.Tick); onSelect(tab) }
                    }
                    .padding(horizontal = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(ht(tab.label), color = text, style = if (active) PanelType.tab.copy(fontWeight = FontWeight.Bold) else PanelType.tab, maxLines = 1)
            }
        }
    }
}

/** White search tile with a leading glass and a trailing clear button. Takes focus when it appears. */
@Composable
internal fun PanelSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, autoFocus: Boolean = true) {
    val c = LocalHomeColors.current
    val focus = remember { FocusRequester() }
    val clear = ht("清除")
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().focusRequester(focus),
        textStyle = HomeType.body.copy(fontSize = 17.sp, color = c.t1),
        singleLine = true,
        cursorBrush = SolidColor(c.accent),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(HomeDims.innerShape).background(c.surface).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(PanelIcons.Search, null, Modifier.size(22.dp), tint = c.t1)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(ht(placeholder), color = c.t3, style = HomeType.body.copy(fontSize = 17.sp), maxLines = 1)
                    inner()
                }
                if (value.isNotEmpty()) {
                    Box(Modifier.size(40.dp).clip(CircleShape).homeTap(onClickLabel = clear, role = Role.Button) { onValueChange("") }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(22.dp).background(c.t3.copy(alpha = .55f), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(HomeIcons.X, null, Modifier.size(13.dp), tint = c.surface)
                        }
                    }
                } else Spacer(Modifier.width(10.dp))
            }
        },
    )
}

/** Filter chip. Selected = tinted fill in its own colour; otherwise an outline. */
@Composable
internal fun PanelChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectedColor: Color = LocalHomeColors.current.accent,
    selectedFill: Color = LocalHomeColors.current.accentSoft,
    height: Dp = 36.dp,
    leading: (@Composable () -> Unit)? = null,
    trailingIcon: ImageVector? = null,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val motion = LocalHomeMotionEnabled.current
    val tint by animateColorAsState(if (selected) selectedColor else c.t1, HomeMotion.fade(motion), label = "panel-chip-text")
    val fill by animateColorAsState(if (selected) selectedFill else Color.Transparent, HomeMotion.fade(motion), label = "panel-chip-fill")
    val outline by animateColorAsState(if (selected) Color.Transparent else c.t3.copy(alpha = .70f), HomeMotion.fade(motion), label = "panel-chip-outline")
    Row(
        modifier
            .heightIn(min = height)
            .homeTap(role = Role.Button) { haptics(HomeHaptic.Tick); onClick() }
            .clip(PanelDims.chipShape)
            .background(fill)
            .border(1.2.dp, outline, PanelDims.chipShape)
            .padding(horizontal = if (height < 34.dp) 11.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (leading != null) leading()
        Text(ht(text), color = tint, style = if (selected) PanelType.chip.copy(fontWeight = FontWeight.SemiBold) else PanelType.chip, maxLines = 1)
        if (trailingIcon != null) Icon(trailingIcon, null, Modifier.size(16.dp), tint = tint)
    }
}

/* ------------------------------------------------------------------ */
/*  Toggles                                                             */
/* ------------------------------------------------------------------ */

/** Rounded check box of the filter menu; the fill and the tick pop in together. */
@Composable
internal fun PanelCheckbox(checked: Boolean, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    val motion = LocalHomeMotionEnabled.current
    val progress by animateFloatAsState(if (checked) 1f else 0f, HomeMotion.pop(motion), label = "panel-checkbox")
    val fill = c.accent
    val mark = c.onAccent
    val outline = c.t3
    Canvas(modifier.size(22.dp)) {
        val radius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
        val stroke = 1.6.dp.toPx()
        val p = progress.coerceIn(0f, 1f)
        if (p < 1f) drawRoundRect(outline.copy(alpha = outline.alpha * (1f - p)), Offset(stroke / 2f, stroke / 2f), Size(size.width - stroke, size.height - stroke), radius, style = Stroke(stroke))
        if (p > 0f) {
            drawRoundRect(fill.copy(alpha = fill.alpha * p), cornerRadius = radius)
            val s = size.minDimension * progress.coerceIn(0f, 1.1f)
            val cx = size.width / 2f
            val cy = size.height / 2f
            val tick = Path().apply {
                moveTo(cx - s * .24f, cy + s * .01f)
                lineTo(cx - s * .06f, cy + s * .18f)
                lineTo(cx + s * .25f, cy - s * .15f)
            }
            drawPath(tick, mark, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Title (+ optional subtitle) on the left, switch on the right; the whole row toggles. */
@Composable
internal fun PanelSwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, minHeight: Dp = HomeDims.rowMinHeightSmall) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    Row(
        modifier.fillMaxWidth().heightIn(min = if (subtitle == null) minHeight else HomeDims.rowMinHeight)
            .toggleable(value = checked, role = Role.Switch) { next -> haptics(HomeHaptic.Tick); onChange(next) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(ht(title), color = c.t1, style = HomeType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(ht(subtitle), color = c.t2, style = HomeType.rowSub)
        }
        HomeSwitch(checked)
    }
}

/* ------------------------------------------------------------------ */
/*  Small display pieces                                                */
/* ------------------------------------------------------------------ */

/** App tile. Uses the host's real icon when [LocalPanelAppIcons] has one, else the first letter. */
@Composable
internal fun PanelAvatar(name: String, packageName: String, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val c = LocalHomeColors.current
    val shape = if (size < 28.dp) RoundedCornerShape(5.dp) else PanelDims.avatarShape
    val icons = LocalPanelAppIcons.current
    Box(modifier.size(size).clip(shape), contentAlignment = Alignment.Center) {
        if (icons != null && packageName.isNotBlank() && icons.has(packageName)) {
            icons.draw(packageName, Modifier.size(size))
        } else {
            Box(Modifier.size(size).background(c.sunken), contentAlignment = Alignment.Center) {
                Text(name.trim().take(1).uppercase(), color = c.t2, style = if (size < 28.dp) HomeType.regionCode else HomeType.cardLabel, maxLines = 1)
            }
        }
    }
}

/** [text] with every case-insensitive occurrence of [query] on an amber tint. */
@Composable
internal fun panelHighlight(text: String, query: String): AnnotatedString {
    val tint = LocalHomeColors.current.warn.copy(alpha = .30f)
    if (query.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var from = 0
        while (true) {
            val hit = text.indexOf(query, from, ignoreCase = true)
            if (hit < 0) break
            append(text.substring(from, hit))
            withStyle(SpanStyle(background = tint)) { append(text.substring(hit, hit + query.length)) }
            from = hit + query.length
        }
        append(text.substring(from))
    }
}

/**
 * Latency capsule. Healthy figures use the accent, slow ones amber, very slow ones and timeouts
 * red; an unknown value is a quiet grey capsule. With [onClick] the capsule re-tests that node
 * only and gets a comfortable touch box that grows up and to the left of it.
 */
@Composable
internal fun PanelDelayLabel(delay: PanelDelay?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, onCard: Boolean = false) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val motion = LocalHomeMotionEnabled.current
    if (delay == null) return
    // label, text colour, colour the capsule fill is mixed from
    val look: Triple<String, Color, Color> = when (delay) {
        PanelDelay.Unknown -> Triple(ht("未知"), c.t2, c.t3)
        PanelDelay.Testing -> Triple(ht("测速中"), c.accent, c.accent)
        PanelDelay.Timeout -> Triple(ht("超时"), c.badText, c.bad)
        is PanelDelay.Ms -> when {
            delay.value < 300L -> Triple("${delay.value} ms", c.accent, c.accent)
            delay.value < 800L -> Triple("${delay.value} ms", c.warnText, c.warn)
            else -> Triple("${delay.value} ms", c.badText, c.bad)
        }
    }
    val text = look.first
    val content = look.second
    val base = look.third
    val tint by animateColorAsState(content, HomeMotion.fade(motion), label = "panel-delay-text")
    val fill by animateColorAsState(base.copy(alpha = if (c.dark) .22f else if (onCard) .15f else .17f), HomeMotion.fade(motion), label = "panel-delay-fill")
    val capsule: @Composable () -> Unit = {
        Row(
            Modifier.heightIn(min = 24.dp).clip(HomeDims.pillShape).background(fill).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (delay == PanelDelay.Testing) HomeSpinner(size = 13.dp, color = tint, strokeWidth = 1.8.dp)
            Text(text, color = tint, style = HomeType.delay.copy(fontWeight = FontWeight.Bold), maxLines = 1)
        }
    }
    if (onClick == null) Box(modifier) { capsule() }
    else Box(
        modifier.sizeIn(minWidth = 56.dp, minHeight = 40.dp)
            .homeTap(enabled = delay != PanelDelay.Testing, onClickLabel = ht("测速"), role = Role.Button) { haptics(HomeHaptic.Tap); onClick() },
        contentAlignment = Alignment.BottomEnd,
    ) { capsule() }
}

/** “运行概况”-style cell: a strong figure over a quiet caption. */
@Composable
internal fun RowScope.PanelStat(value: String, unit: String?, label: String, valueColor: Color = LocalHomeColors.current.t1, style: TextStyle = PanelType.stat) {
    val c = LocalHomeColors.current
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (unit == null) value else "$value $unit", color = valueColor, style = style, maxLines = 1)
        Text(ht(label), Modifier.padding(top = 2.dp), color = c.t2, style = HomeType.note, maxLines = 1)
    }
}

@Composable
internal fun PanelSectionHeader(title: String, modifier: Modifier = Modifier, count: String? = null, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val c = LocalHomeColors.current
    Row(modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(ht(title), color = c.accent, style = HomeType.rowTitle, maxLines = 1)
        Spacer(Modifier.weight(1f))
        if (count != null) Text(count, Modifier.padding(end = 4.dp), color = c.t2, style = HomeType.value.copy(fontWeight = FontWeight.Medium), maxLines = 1)
        if (trailing != null) trailing()
    }
}

@Composable
internal fun PanelEmptyState(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier, verbatimSubtitle: Boolean = false, action: (@Composable () -> Unit)? = null) {
    HomeEmptyState(icon, title, subtitle, modifier, tint = LocalHomeColors.current.t3, topPadding = 56.dp, titleStyle = HomeType.heroStatus, verbatimSubtitle = verbatimSubtitle, action = action)
}

/** Accent floating button with a soft accent shadow: round with an icon, a capsule with a label. */
@Composable
internal fun PanelFab(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, text: String? = null) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val shape = HomeDims.pillShape
    val spoken = ht(label)
    Row(
        modifier
            .homeTap(onClickLabel = spoken, role = Role.Button) { haptics(HomeHaptic.Tap); onClick() }
            .shadow(if (c.dark) 0.dp else 12.dp, shape, clip = false, ambientColor = c.accent, spotColor = c.accent)
            .heightIn(min = if (text == null) 58.dp else 52.dp).widthIn(min = 58.dp, max = 240.dp)
            .clip(shape).background(c.accent)
            .padding(horizontal = if (text == null) 0.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Icon(icon, null, Modifier.size(if (text == null) 28.dp else 22.dp), tint = c.onAccent)
        if (text != null) Text(text, color = c.onAccent, style = HomeType.button.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Destructive button. [soft] = outlined in red (断开此连接); otherwise solid red (断开全部). */
@Composable
internal fun PanelDangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, soft: Boolean = false, icon: ImageVector? = null) {
    HomeButton(text, onClick, modifier, kind = if (soft) HomeButtonKind.Secondary else HomeButtonKind.Primary, icon = icon, danger = true)
}

/* ------------------------------------------------------------------ */
/*  Menus                                                               */
/* ------------------------------------------------------------------ */

/** Anchored popover with the home tokens. Place it inside the same Box as its trigger. */
@Composable
internal fun PanelDropdown(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalHomeColors.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = HomeDims.menuShape,
        containerColor = c.raised,
        tonalElevation = 0.dp,
        shadowElevation = 14.dp,
    ) { Column(Modifier.widthIn(min = PanelDims.menuMinWidth, max = PanelDims.menuMaxWidth).padding(horizontal = 6.dp), content = content) }
}

/** The same menu body drawn in place (previews, where a popup window cannot render). */
@Composable
internal fun PanelInlineMenu(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalHomeColors.current
    Column(
        modifier.shadow(14.dp, HomeDims.menuShape).widthIn(min = PanelDims.menuMinWidth, max = PanelDims.menuMaxWidth).clip(HomeDims.menuShape).background(c.raised).padding(6.dp),
        content = content,
    )
}

@Composable
internal fun PanelMenuTitle(text: String) {
    Text(ht(text), Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp), color = LocalHomeColors.current.t2, style = HomeType.note)
}

@Composable
internal fun PanelMenuNote(text: String) {
    Text(ht(text), Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), color = LocalHomeColors.current.t3, style = HomeType.caption)
}

/**
 * One menu row. [checked] non-null draws a leading check box (multi-select menus);
 * [selected] turns the label accent and draws a trailing tick (single-select menus).
 */
@Composable
internal fun PanelMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    checked: Boolean? = null,
    selected: Boolean = false,
    danger: Boolean = false,
    subtitle: String? = null,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val color = when { danger -> c.badText; selected -> c.accent; else -> c.t1 }
    Row(
        modifier.fillMaxWidth().heightIn(min = 46.dp).clip(PanelDims.tabShape)
            .homeTap(role = if (checked != null) Role.Checkbox else Role.Button) { haptics(HomeHaptic.Tick); onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (checked != null) PanelCheckbox(checked)
        if (icon != null) Icon(icon, null, Modifier.size(22.dp), tint = if (danger) c.badText else c.t1)
        Column(Modifier.weight(1f)) {
            Text(ht(text), color = color, style = if (selected) PanelType.menuItem.copy(fontWeight = FontWeight.SemiBold) else PanelType.menuItem, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(ht(subtitle), color = c.t3, style = PanelType.tiny)
        }
        if (selected) Icon(HomeIcons.Check, null, Modifier.size(20.dp), tint = c.accent)
    }
}

/* ------------------------------------------------------------------ */
/*  Dialog                                                              */
/* ------------------------------------------------------------------ */

/** Centred confirmation card: title, message, 取消 + confirm. Used for every destructive confirmation. */
@Composable
internal fun PanelDialogCard(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = true) {
    val c = LocalHomeColors.current
    Column(
        modifier.width(PanelDims.dialogWidth).clip(PanelDims.dialogShape).background(c.raised)
            .padding(start = 18.dp, end = 18.dp, top = 26.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(ht(title), color = c.t1, style = HomeType.sheetTitle.copy(fontSize = 20.sp), textAlign = TextAlign.Center)
        Text(ht(message), Modifier.padding(top = 10.dp, start = 6.dp, end = 6.dp), color = c.t2, style = PanelType.dialogBody, textAlign = TextAlign.Center)
        Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeButton("取消", onDismiss, Modifier.weight(1f), kind = HomeButtonKind.Soft)
            HomeButton(confirm, onConfirm, Modifier.weight(1f), kind = HomeButtonKind.Primary, danger = danger)
        }
    }
}

@Composable
internal fun PanelDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) { content() }
}

/* ------------------------------------------------------------------ */
/*  Two-series trend chart                                              */
/* ------------------------------------------------------------------ */

internal class PanelTrendSeries(val values: List<Float>, val color: Color, val fill: Boolean)

/**
 * Smooth curves on one shared axis. A filled series gets a gradient that fades to nothing at
 * the baseline; every series draws itself in from the left once per [reveal] run.
 */
@Composable
internal fun PanelTrendChart(series: List<PanelTrendSeries>, modifier: Modifier = Modifier, height: Dp = 148.dp, reveal: () -> Float = { 1f }) {
    val base = LocalHomeColors.current.line2
    Canvas(modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val h = size.height
        drawLine(base, Offset(0f, h - .5f), Offset(w, h - .5f), strokeWidth = 1.dp.toPx())
        val peak = series.maxOfOrNull { s -> s.values.maxOrNull() ?: 0f } ?: 0f
        if (peak <= 0f) return@Canvas
        val max = peak * 1.18f
        val head = 6.dp.toPx()
        val foot = 3.dp.toPx()
        val stroke = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val shown = reveal().coerceIn(0f, 1f)
        clipRect(right = w * shown) {
            for (s in series) {
                if (s.values.size < 2) continue
                val step = w / (s.values.size - 1)
                val points = s.values.mapIndexed { i, v -> Offset(i * step, h - foot - (v / max).coerceIn(0f, 1f) * (h - head - foot)) }
                val line = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size) {
                        val midX = (points[i - 1].x + points[i].x) / 2f
                        cubicTo(midX, points[i - 1].y, midX, points[i].y, points[i].x, points[i].y)
                    }
                }
                if (s.fill) {
                    val area = Path().apply { addPath(line); lineTo(points.last().x, h); lineTo(points.first().x, h); close() }
                    drawPath(area, Brush.verticalGradient(listOf(s.color.copy(alpha = s.color.alpha * .30f), s.color.copy(alpha = 0f)), startY = 0f, endY = h))
                }
                drawPath(line, s.color, style = stroke)
            }
        }
    }
}
