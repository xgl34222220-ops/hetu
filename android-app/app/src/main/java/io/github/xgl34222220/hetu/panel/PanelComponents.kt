package io.github.xgl34222220.hetu.panel

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
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
import io.github.xgl34222220.hetu.home.HomeHaptic
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeMotion
import io.github.xgl34222220.hetu.home.HomeSpinner
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeHaptics
import io.github.xgl34222220.hetu.home.LocalHomeMotionEnabled
import io.github.xgl34222220.hetu.home.homeTap

/* ------------------------------------------------------------------ */
/*  Panel-only tokens (everything else comes from HomeTokens)           */
/* ------------------------------------------------------------------ */

internal object PanelDims {
    val tabShape = RoundedCornerShape(10.dp)
    val nodeShape = RoundedCornerShape(12.dp)
    val tileShape = RoundedCornerShape(8.dp)
    val avatarShape = RoundedCornerShape(10.dp)
    val dialogShape = RoundedCornerShape(20.dp)
    val chipShape = RoundedCornerShape(15.dp)
    val fabShape = RoundedCornerShape(22.dp)
    val dialogWidth = 316.dp
    val menuMinWidth = 168.dp
    val menuMaxWidth = 280.dp
}

/** Panel-only text styles; sizes follow the prototype's CSS. */
internal object PanelType {
    private const val Tnum = "tnum"
    val tab = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val groupName = TextStyle(fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val tiny = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp, fontFeatureSettings = Tnum)
    val nodeName = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
    val cardTitle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val host = TextStyle(fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val stat = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.22).sp, fontFeatureSettings = Tnum)
    val statSmall = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = Tnum)
    val chip = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
    val log = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace)
    val monoSmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace)
    val dialogBody = TextStyle(fontSize = 14.sp, lineHeight = 21.sp)
    val menuItem = TextStyle(fontSize = 14.5.sp, lineHeight = 20.sp)
    val policy = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val nodeTitle = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    val tileCode = TextStyle(fontSize = 10.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
}

/** Real application icons supplied by the host. [has] must be cheap; [draw] fills the given size. */
internal class PanelAppIcons(val has: (packageName: String) -> Boolean, val draw: @Composable (packageName: String, modifier: Modifier) -> Unit)

/** Optional slots the host can fill with real artwork; null falls back to the prototype's placeholders. */
internal val LocalPanelGroupIcon = staticCompositionLocalOf<(@Composable (group: PanelGroup, modifier: Modifier) -> Unit)?> { null }
internal val LocalPanelAppIcons = staticCompositionLocalOf<PanelAppIcons?> { null }

internal fun Modifier.panelGutter(): Modifier = padding(horizontal = HomeDims.gutter)

/* ------------------------------------------------------------------ */
/*  Lazy “card pieces”: one visual card spread over many list items     */
/* ------------------------------------------------------------------ */

internal enum class PiecePosition {
    First, Middle, Last, Only;

    companion object {
        fun of(index: Int, count: Int): PiecePosition = when {
            count <= 1 -> Only
            index == 0 -> First
            index == count - 1 -> Last
            else -> Middle
        }
    }
}

private fun pieceShape(position: PiecePosition): Shape = when (position) {
    PiecePosition.Only -> HomeDims.cardShape
    PiecePosition.First -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    PiecePosition.Last -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
    PiecePosition.Middle -> RectangleShape
}

/**
 * Surface + hairline for one row of a long card. Every piece except the last reports 1 px less
 * height, so the next piece overlaps it and the two borders collapse into a single divider.
 */
@Composable
internal fun Modifier.cardPiece(position: PiecePosition): Modifier {
    val c = LocalHomeColors.current
    val shape = pieceShape(position)
    val overlap = position == PiecePosition.First || position == PiecePosition.Middle
    return this
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val cut = if (overlap) 1.dp.roundToPx() else 0
            layout(placeable.width, (placeable.height - cut).coerceAtLeast(0)) { placeable.place(0, 0) }
        }
        .clip(shape)
        .background(c.surface)
        .border(1.dp, c.line, shape)
}

/* ------------------------------------------------------------------ */
/*  Tabs, search, chips                                                 */
/* ------------------------------------------------------------------ */

/** Horizontally scrolling tab strip; the selected tab is a surface pill with a hairline. */
@Composable
internal fun PanelTabStrip(selected: PanelTab, onSelect: (PanelTab) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).panelGutter(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PanelTab.entries.forEach { tab ->
            val active = tab == selected
            Box(
                Modifier
                    .height(32.dp)
                    .clip(PanelDims.tabShape)
                    .background(if (active) c.surface else Color.Transparent)
                    .let { if (active) it.border(1.dp, c.line2, PanelDims.tabShape) else it }
                    .clickable(enabled = !active, role = Role.Tab) { haptics(HomeHaptic.Tick); onSelect(tab) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(tab.label, color = if (active) c.t1 else c.t2, style = PanelType.tab, maxLines = 1) }
        }
    }
}

/** 44 dp sunken search field with a leading glass and a trailing clear button. Takes focus when it appears. */
@Composable
internal fun PanelSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, autoFocus: Boolean = true) {
    val c = LocalHomeColors.current
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().focusRequester(focus),
        textStyle = HomeType.body.copy(color = c.t1),
        singleLine = true,
        cursorBrush = SolidColor(c.accent),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().height(HomeDims.touch).clip(HomeDims.controlShape).background(c.sunken).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(PanelIcons.Search, null, Modifier.size(18.dp), tint = c.t3)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, color = c.t3, style = HomeType.body, maxLines = 1)
                    inner()
                }
                if (value.isNotEmpty()) {
                    Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClickLabel = "清除", role = Role.Button) { onValueChange("") }, contentAlignment = Alignment.Center) {
                        Icon(PanelIcons.CircleX, null, Modifier.size(18.dp), tint = c.t3)
                    }
                } else Spacer(Modifier.width(8.dp))
            }
        },
    )
}

/** 30 dp pill. Selected = accent tint; otherwise a hairline outline. */
@Composable
internal fun PanelChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectedColor: Color = LocalHomeColors.current.accent,
    selectedFill: Color = LocalHomeColors.current.accentSoft,
    height: Dp = 30.dp,
    leading: (@Composable () -> Unit)? = null,
    trailingIcon: ImageVector? = null,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val tint = if (selected) selectedColor else c.t2
    Row(
        modifier
            .height(height)
            .clip(PanelDims.chipShape)
            .let { if (selected) it.background(selectedFill) else it.border(1.dp, c.line2, PanelDims.chipShape) }
            .clickable(role = Role.Button) { haptics(HomeHaptic.Tick); onClick() }
            .padding(horizontal = if (height < 30.dp) 10.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (leading != null) leading()
        Text(text, color = tint, style = PanelType.chip, maxLines = 1)
        if (trailingIcon != null) Icon(trailingIcon, null, Modifier.size(14.dp), tint = tint)
    }
}

/* ------------------------------------------------------------------ */
/*  Toggles                                                             */
/* ------------------------------------------------------------------ */

/** 44 × 26 switch. Purely visual: the enclosing row owns the click. */
@Composable
internal fun PanelSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    val thumb by animateDpAsState(if (checked) 21.dp else 3.dp, tween(if (LocalHomeMotionEnabled.current) HomeMotion.SwitchMs else 0, easing = HomeMotion.Emphasized), label = "panel-switch")
    Box(modifier.size(44.dp, 26.dp).background(if (checked) c.accent else c.line2, RoundedCornerShape(13.dp))) {
        Box(Modifier.offset(x = thumb, y = 3.dp).size(20.dp).background(Color.White, CircleShape))
    }
}

@Composable
internal fun PanelCheckbox(checked: Boolean, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier.size(20.dp).clip(shape).let { if (checked) it.background(c.accent) else it.border(1.5.dp, c.line2, shape) },
        contentAlignment = Alignment.Center,
    ) { if (checked) Icon(HomeIcons.Check, null, Modifier.size(14.dp), tint = c.onAccent) }
}

/** Title (+ optional subtitle) on the left, switch on the right; the whole row toggles. */
@Composable
internal fun PanelSwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, minHeight: Dp = HomeDims.rowMinHeightSmall) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    Row(
        modifier.fillMaxWidth().heightIn(min = if (subtitle == null) minHeight else HomeDims.rowMinHeight)
            .toggleable(value = checked, role = Role.Switch) { next -> haptics(HomeHaptic.Tick); onChange(next) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.t1, style = HomeType.rowTitle, maxLines = 1)
            if (subtitle != null) Text(subtitle, color = c.t2, style = HomeType.rowSub)
        }
        PanelSwitch(checked)
    }
}

/* ------------------------------------------------------------------ */
/*  Small display pieces                                                */
/* ------------------------------------------------------------------ */

/** App tile. Uses the host's real icon when [LocalPanelAppIcons] has one, else the first letter. */
@Composable
internal fun PanelAvatar(name: String, packageName: String, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val c = LocalHomeColors.current
    val shape = if (size < 28.dp) RoundedCornerShape(6.dp) else PanelDims.avatarShape
    val icons = LocalPanelAppIcons.current
    Box(modifier.size(size).clip(shape), contentAlignment = Alignment.Center) {
        if (icons != null && packageName.isNotBlank() && icons.has(packageName)) {
            icons.draw(packageName, Modifier.size(size))
        } else {
            Box(Modifier.size(size).background(c.sunken).border(1.dp, c.line, shape), contentAlignment = Alignment.Center) {
                Text(name.take(1), color = c.t2, style = if (size < 28.dp) HomeType.regionCode else PanelType.groupName, maxLines = 1)
            }
        }
    }
}

/** [text] with every case-insensitive occurrence of [query] on an amber tint. */
@Composable
internal fun panelHighlight(text: String, query: String): AnnotatedString {
    val tint = LocalHomeColors.current.warn.copy(alpha = .28f)
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

/** Latency label. Tapping (when [onClick] is set) re-tests that node only. */
@Composable
internal fun PanelDelayLabel(delay: PanelDelay?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    if (delay == null) return
    val (text, color) = when (delay) {
        PanelDelay.Unknown -> "未知" to c.t3
        PanelDelay.Testing -> "测速中" to c.t2
        PanelDelay.Timeout -> "超时" to c.bad
        is PanelDelay.Ms -> "${delay.value} ms" to when {
            delay.value < 150 -> c.good
            delay.value < 300 -> c.warn
            else -> c.bad
        }
    }
    Row(
        modifier.heightIn(min = if (onClick != null) 48.dp else 20.dp).let {
            if (onClick != null && delay != PanelDelay.Testing) it.homeTap(onClickLabel = "测速", role = Role.Button) { haptics(HomeHaptic.Tap); onClick() } else it
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (delay == PanelDelay.Testing) HomeSpinner(size = 11.dp, color = color, strokeWidth = 1.5.dp)
        Text(text, color = color, style = HomeType.delay, maxLines = 1)
    }
}

/** “运行概况”-style cell: big number over a small caption. */
@Composable
internal fun RowScope.PanelStat(value: String, unit: String?, label: String, valueColor: Color = LocalHomeColors.current.t1, style: TextStyle = PanelType.stat) {
    val c = LocalHomeColors.current
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Row {
            Text(value, Modifier.alignByBaseline(), color = valueColor, style = style, maxLines = 1)
            if (unit != null) Text(unit, Modifier.alignByBaseline().padding(start = 2.dp), color = if (valueColor == c.t1) c.t3 else valueColor, style = HomeType.note, maxLines = 1)
        }
        Text(label, color = c.t3, style = HomeType.caption, maxLines = 1)
    }
}

@Composable
internal fun PanelSectionHeader(title: String, modifier: Modifier = Modifier, count: String? = null, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val c = LocalHomeColors.current
    Row(modifier.fillMaxWidth().heightIn(min = 20.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = c.t2, style = HomeType.section, maxLines = 1)
        if (count != null) Text(count, Modifier.padding(start = 8.dp), color = c.t3, style = HomeType.section.copy(fontFeatureSettings = "tnum"), maxLines = 1)
        Spacer(Modifier.weight(1f))
        if (trailing != null) trailing()
    }
}

@Composable
internal fun PanelEmptyState(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val c = LocalHomeColors.current
    Column(modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 96.dp, bottom = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(32.dp), tint = c.t3)
        Spacer(Modifier.height(16.dp))
        Text(title, color = c.t1, style = PanelType.cardTitle, textAlign = TextAlign.Center)
        Text(subtitle, Modifier.padding(top = 4.dp), color = c.t2, style = HomeType.note, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(16.dp)); action() }
    }
}

/** Accent floating button: round when it only has an icon, a pill when it has a label. */
@Composable
internal fun PanelFab(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, text: String? = null) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    Row(
        modifier
            .height(HomeDims.touch).widthIn(min = HomeDims.touch)
            .clip(PanelDims.fabShape).background(c.accent)
            .clickable(onClickLabel = label, role = Role.Button) { haptics(HomeHaptic.Tap); onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = c.onAccent)
        if (text != null) Text(text, color = c.onAccent, style = PanelType.tab, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 44 dp destructive button. [soft] = red tint with red text; otherwise solid red. */
@Composable
internal fun PanelDangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, soft: Boolean = false, icon: ImageVector? = null) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val content = if (soft) c.bad else if (c.dark) Color(0xFF1B0808) else Color.White
    Row(
        modifier.height(HomeDims.touch).clip(HomeDims.controlShape).background(if (soft) c.badSoft else c.bad)
            .clickable(role = Role.Button) { haptics(HomeHaptic.Confirm); onClick() }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = content)
        Text(text, color = content, style = HomeType.button, maxLines = 1)
    }
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
        containerColor = c.surface,
        border = BorderStroke(1.dp, c.line),
    ) { Column(Modifier.widthIn(min = PanelDims.menuMinWidth, max = PanelDims.menuMaxWidth).padding(horizontal = 4.dp), content = content) }
}

/** The same menu body drawn in place (previews, where a popup window cannot render). */
@Composable
internal fun PanelInlineMenu(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalHomeColors.current
    Column(
        modifier.widthIn(min = PanelDims.menuMinWidth, max = PanelDims.menuMaxWidth).clip(HomeDims.menuShape).background(c.surface)
            .border(1.dp, c.line, HomeDims.menuShape).padding(4.dp),
        content = content,
    )
}

@Composable
internal fun PanelMenuTitle(text: String) {
    Text(text, Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp), color = LocalHomeColors.current.t3, style = HomeType.caption)
}

@Composable
internal fun PanelMenuNote(text: String) {
    Text(text, Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp), color = LocalHomeColors.current.t3, style = PanelType.tiny)
}

/**
 * One menu row. [checked] non-null draws a leading check box (multi-select menus that stay open);
 * [selected] draws a trailing tick in accent (single-select menus).
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
    val color = when { danger -> c.bad; selected -> c.accent; else -> c.t1 }
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp).clip(PanelDims.tabShape)
            .clickable(role = if (checked != null) Role.Checkbox else Role.Button) { haptics(HomeHaptic.Tick); onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (checked != null) PanelCheckbox(checked)
        if (icon != null) Icon(icon, null, Modifier.size(17.dp), tint = if (danger) c.bad else c.t2)
        Column(Modifier.weight(1f)) {
            Text(text, color = color, style = PanelType.menuItem, maxLines = 1)
            if (subtitle != null) Text(subtitle, color = c.t3, style = PanelType.tiny)
        }
        if (selected) Icon(HomeIcons.Check, null, Modifier.size(17.dp), tint = c.accent)
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
        modifier.width(PanelDims.dialogWidth).clip(PanelDims.dialogShape).background(c.surface).border(1.dp, c.line, PanelDims.dialogShape)
            .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = c.t1, style = HomeType.barTitle, textAlign = TextAlign.Center)
        Text(message, Modifier.padding(top = 8.dp), color = c.t2, style = PanelType.dialogBody, textAlign = TextAlign.Center)
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeButton("取消", onDismiss, Modifier.weight(1f))
            if (danger) PanelDangerButton(confirm, onConfirm, Modifier.weight(1f))
            else HomeButton(confirm, onConfirm, Modifier.weight(1f), kind = HomeButtonKind.Primary)
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

/** Smooth lines over three hairline grid rows; all series share one vertical axis. */
@Composable
internal fun PanelTrendChart(series: List<PanelTrendSeries>, modifier: Modifier = Modifier, height: Dp = 112.dp) {
    val grid = LocalHomeColors.current.line
    Canvas(modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val h = size.height
        for (row in 1..3) { val y = h * row / 4f; drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx()) }
        val peak = series.maxOfOrNull { s -> s.values.maxOrNull() ?: 0f } ?: 0f
        if (peak <= 0f) return@Canvas
        val max = peak * 1.15f
        val head = 6.dp.toPx()
        val foot = 2.dp.toPx()
        val stroke = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (s in series) {
            if (s.values.size < 2) continue
            val step = w / (s.values.size - 1)
            val points = s.values.mapIndexed { i, v -> Offset(i * step, h - (v / max).coerceIn(0f, 1f) * (h - head) - foot) }
            val line = Path().apply {
                moveTo(points[0].x, points[0].y)
                for (i in 1 until points.size) {
                    val midX = (points[i - 1].x + points[i].x) / 2f
                    cubicTo(midX, points[i - 1].y, midX, points[i].y, points[i].x, points[i].y)
                }
            }
            if (s.fill) {
                val area = Path().apply { addPath(line); lineTo(points.last().x, h); lineTo(points.first().x, h); close() }
                drawPath(area, s.color.copy(alpha = s.color.alpha * .08f))
            }
            drawPath(line, s.color, style = stroke)
        }
    }
}
