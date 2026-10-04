package io.github.xgl34222220.hetu.tools

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeCard
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeDivider
import io.github.xgl34222220.hetu.home.HomeHaptic
import io.github.xgl34222220.hetu.home.HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeMotion
import io.github.xgl34222220.hetu.home.HomeSpinner
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeHaptics

/* ------------------------------------------------------------------ */
/*  Bars                                                                */
/* ------------------------------------------------------------------ */

/**
 * Top bar for pages with more actions than `HomeTopBar` has room for (应用管理 has four) or a
 * text action (绕过规则 has “✓ 保存”). The title is centred in the space between the back
 * button and the actions instead of on the screen, so it can never slide under them.
 */
@Composable
internal fun ToolsWideBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit,
) {
    val c = LocalHomeColors.current
    Row(
        modifier.fillMaxWidth().background(c.bg).windowInsetsPadding(WindowInsets.statusBars).height(HomeDims.barHeight).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeIconButton(HomeIcons.ChevronLeft, "返回", onBack)
        Column(Modifier.weight(1f).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = c.t1, style = HomeType.barTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = c.t3, style = HomeType.barSubtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

/** “✓ 保存” in a top bar: accent while there is something to save, dim otherwise. */
@Composable
internal fun ToolsBarTextButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val tint = if (enabled) c.accent else c.t3
    Row(
        modifier
            .height(HomeDims.touch)
            .clip(HomeDims.controlShape)
            .clickable(enabled = enabled && !loading, role = Role.Button) { haptics(HomeHaptic.Confirm); onClick() }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (loading) HomeSpinner(size = 16.dp, color = tint) else Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Text(text, color = tint, style = HomeType.buttonSmall, maxLines = 1)
    }
}

/* ------------------------------------------------------------------ */
/*  Switch                                                              */
/* ------------------------------------------------------------------ */

/** 44 × 26 dp switch. With a null [onCheckedChange] it is display-only (the row handles the tap). */
@Composable
internal fun ToolsSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val position by animateFloatAsState(if (checked) 1f else 0f, tween(HomeMotion.SwitchMs), label = "tools-switch")
    val track = if (checked) c.accent else c.line2
    val base = modifier
        .size(44.dp, 26.dp)
        .alpha(if (enabled) 1f else .45f)
        .clip(CircleShape)
        .background(track)
    val interactive = if (onCheckedChange == null) base else base
        .clickable(enabled = enabled, role = Role.Switch) { haptics(HomeHaptic.Tick); onCheckedChange(!checked) }
        .semantics { if (label != null) contentDescription = label }
    Box(interactive.padding(3.dp)) {
        Box(Modifier.offset(x = 18.dp * position).size(20.dp).background(Color.White, CircleShape))
    }
}

/* ------------------------------------------------------------------ */
/*  Cards and rows                                                      */
/* ------------------------------------------------------------------ */

/** Tinted notice at the top of a settings page: info glyph and one or two sentences. */
@Composable
internal fun ToolsInfoCard(text: String, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    Row(
        modifier.fillMaxWidth().clip(HomeDims.cardShape).background(c.accentSoft).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(HomeIcons.Info, null, Modifier.padding(top = 1.dp).size(18.dp), tint = c.accent)
        Text(text, Modifier.weight(1f), color = c.t1, style = HomeType.bodySmall)
    }
}

/** Title line inside a card (“运行预检”, “规则源”…), optionally with something on the right. */
@Composable
internal fun ToolsCardTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val c = LocalHomeColors.current
    Row(
        modifier.fillMaxWidth().heightIn(min = HomeDims.touch).padding(start = 16.dp, end = if (trailing == null) 16.dp else 6.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), color = c.t1, style = HomeType.rowTitle.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        if (trailing != null) trailing()
    }
}

/** A [ToolsRow] with a switch at the end; the whole row toggles. */
@Composable
internal fun ToolsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
    monospaceSubtitle: Boolean = false,
) {
    val c = LocalHomeColors.current
    ToolsRow(
        title = AnnotatedString(title),
        modifier = modifier.alpha(if (enabled) 1f else .5f),
        icon = icon,
        iconTint = c.t1,
        subtitle = subtitle,
        subtitleStyle = if (monospaceSubtitle) ToolsType.url else HomeType.rowSub,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        trailing = { ToolsSwitch(checked, null) },
    )
}

/** Round check mark at the end of an app row: filled accent when [checked], hairline ring otherwise. */
@Composable
internal fun ToolsCheckCircle(checked: Boolean, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    Box(
        modifier.size(22.dp).let { if (checked) it.background(c.accent, CircleShape) else it.border(1.5.dp, c.t3, CircleShape) },
        contentAlignment = Alignment.Center,
    ) { if (checked) Icon(HomeIcons.Check, null, Modifier.size(14.dp), tint = c.onAccent) }
}

/** Rounded tile with the first character of [label]; stands in for an app icon. */
@Composable
internal fun ToolsAvatar(label: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val c = LocalHomeColors.current
    Box(modifier.size(size).clip(RoundedCornerShape(size * .26f)).background(c.sunken), contentAlignment = Alignment.Center) {
        Text(label.trim().take(1).uppercase(), color = c.t2, style = HomeType.rowTitle.copy(fontWeight = FontWeight.SemiBold))
    }
}

/** Rounded tile around a glyph: the core cards and the adblock status card use it. */
@Composable
internal fun ToolsGlyphTile(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = LocalHomeColors.current.t1, background: Color = LocalHomeColors.current.sunken, size: Dp = 44.dp) {
    Box(modifier.size(size).clip(RoundedCornerShape(12.dp)).background(background), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * .5f), tint = tint)
    }
}

/* ------------------------------------------------------------------ */
/*  Editable list                                                       */
/* ------------------------------------------------------------------ */

/**
 * Rows of inline inputs with a delete button each and “+ 添加一项” underneath: the CIDR,
 * interface and MAC lists. Every row carries its small [label] above the value.
 */
@Composable
internal fun ColumnScope.ToolsListEditor(
    label: String,
    values: List<String>,
    onChange: (index: Int, value: String) -> Unit,
    onRemove: (index: Int) -> Unit,
    onAdd: () -> Unit,
    placeholder: String,
    enabled: Boolean = true,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val style = HomeType.mono.copy(color = c.t1)
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEachIndexed { index, value ->
            Row(
                Modifier.fillMaxWidth().clip(HomeDims.controlShape).background(c.bg).padding(start = 12.dp, end = 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(label, color = c.t3, style = HomeType.caption)
                    BasicTextField(
                        value = value,
                        onValueChange = { onChange(index, it) },
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        enabled = enabled,
                        textStyle = style,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        cursorBrush = SolidColor(c.accent),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (value.isEmpty()) Text(placeholder, color = c.t3, style = style.copy(color = c.t3), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                inner()
                            }
                        },
                    )
                }
                HomeIconButton(ToolsIcons.Trash2, "删除", { onRemove(index) }, enabled = enabled, tint = c.t2)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(HomeDims.touch)
                .clip(HomeDims.controlShape)
                .background(c.bg)
                .clickable(enabled = enabled, role = Role.Button) { haptics(HomeHaptic.Tap); onAdd() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            Icon(ToolsIcons.Plus, null, Modifier.size(16.dp), tint = c.accent)
            Text("添加一项", color = c.accent, style = HomeType.buttonSmall)
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Chips                                                               */
/* ------------------------------------------------------------------ */

/** Children laid out left to right, wrapping to a new line when the row is full. */
@Composable
internal fun ToolsWrap(modifier: Modifier = Modifier, spacing: Dp = 8.dp, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val xs = IntArray(placeables.size)
        val ys = IntArray(placeables.size)
        var x = 0
        var y = 0
        var rowHeight = 0
        var widest = 0
        placeables.forEachIndexed { index, placeable ->
            if (x > 0 && x + placeable.width > constraints.maxWidth) {
                x = 0
                y += rowHeight + gap
                rowHeight = 0
            }
            xs[index] = x
            ys[index] = y
            x += placeable.width + gap
            rowHeight = maxOf(rowHeight, placeable.height)
            widest = maxOf(widest, x - gap)
        }
        layout(widest.coerceIn(constraints.minWidth, constraints.maxWidth), (y + rowHeight).coerceAtLeast(constraints.minHeight)) {
            placeables.forEachIndexed { index, placeable -> placeable.placeRelative(xs[index], ys[index]) }
        }
    }
}

/** Domain chip with a remove button. */
@Composable
internal fun ToolsChip(text: String, onRemove: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    Row(
        modifier.height(32.dp).clip(CircleShape).background(c.sunken).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.widthIn(max = 220.dp), color = c.t1, style = HomeType.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(
            Modifier.size(32.dp).clip(CircleShape).clickable(enabled = enabled, onClickLabel = "移除 $text", role = Role.Button) { haptics(HomeHaptic.Tap); onRemove() },
            contentAlignment = Alignment.Center,
        ) { Icon(HomeIcons.X, null, Modifier.size(13.dp), tint = c.t2) }
    }
}

/* ------------------------------------------------------------------ */
/*  Menus with a check mark                                             */
/* ------------------------------------------------------------------ */

/** [checked]: null = plain item, true / false = item with a check slot (ticked or empty). */
internal class ToolsMenuOption(val label: String, val checked: Boolean? = null, val enabled: Boolean = true, val dividerBefore: Boolean = false, val onClick: () -> Unit)

/** Menu of options; the chosen ones turn accent and get a trailing check (pages 29, 30). */
@Composable
internal fun ToolsOptionMenuCard(options: List<ToolsMenuOption>, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    Column(
        modifier
            .widthIn(min = ToolsDims.menuMinWidth, max = ToolsDims.menuMaxWidth)
            .shadow(16.dp, HomeDims.menuShape)
            .clip(HomeDims.menuShape)
            .background(c.surface)
            .border(1.dp, c.line, HomeDims.menuShape)
            .padding(4.dp),
    ) {
        options.forEach { option ->
            if (option.dividerBefore) HomeDivider(Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            val on = option.checked == true
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .alpha(if (option.enabled) 1f else .4f)
                    .clip(ToolsDims.menuItemShape)
                    .clickable(enabled = option.enabled, role = Role.Button) { haptics(HomeHaptic.Tap); option.onClick() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(option.label, Modifier.weight(1f), color = if (on) c.accent else c.t1, style = ToolsType.menuItem.copy(fontWeight = if (on) FontWeight.SemiBold else null), maxLines = 1)
                if (option.checked != null) Box(Modifier.size(16.dp)) { if (on) Icon(HomeIcons.Check, null, Modifier.size(16.dp), tint = c.accent) }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Read-only text                                                      */
/* ------------------------------------------------------------------ */

/**
 * Monospace block for the two diagnostic sheets. Scrolls both ways inside [maxHeight] so long
 * reports stay inside the sheet; lines never wrap, which keeps YAML indentation readable.
 */
@Composable
internal fun ToolsCodeBox(text: AnnotatedString, modifier: Modifier = Modifier, maxHeight: Dp = 420.dp) {
    val c = LocalHomeColors.current
    HomeCard(modifier.fillMaxWidth().heightIn(max = maxHeight), background = c.bg) {
        Box(Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
            Text(text, Modifier.padding(horizontal = 16.dp, vertical = 14.dp), color = c.t1, style = ToolsEditorType.code, softWrap = false)
        }
    }
}

/** Centered spinner filling its parent: the loading state of every page in this file's family. */
@Composable
internal fun ToolsLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { HomeSpinner(size = 20.dp, color = LocalHomeColors.current.t3) }
}

/** Fixed strip above the navigation bar: the “已选 N 个 · …” line of 应用管理. */
@Composable
internal fun ToolsFooterBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val c = LocalHomeColors.current
    Box(modifier.fillMaxWidth().background(c.bg).windowInsetsPadding(WindowInsets.navigationBars).padding(start = HomeDims.gutter, end = HomeDims.gutter, top = 8.dp, bottom = 8.dp)) {
        HomeCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().heightIn(min = HomeDims.touch).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}

/** Page frame for [ToolsWideBar] pages: bar, then a scrolling column with the standard gutter. */
@Composable
internal fun ToolsWidePage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalHomeColors.current
    Column(modifier.fillMaxSize().background(c.bg).imePadding()) {
        ToolsWideBar(title, onBack, subtitle = subtitle, actions = actions)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = HomeDims.gutter, end = HomeDims.gutter, bottom = 32.dp),
            content = content,
        )
    }
}
