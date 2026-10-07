package io.github.xgl34222220.hetu.tools

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.home.HomeCardTitle
import io.github.xgl34222220.hetu.home.HomeCheckMark
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeHaptic
import io.github.xgl34222220.hetu.home.HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeMenuDivider
import io.github.xgl34222220.hetu.home.HomeMenuItem
import io.github.xgl34222220.hetu.home.HomeMenuSurface
import io.github.xgl34222220.hetu.home.HomeMotion
import io.github.xgl34222220.hetu.home.HomeRowDims
import io.github.xgl34222220.hetu.home.HomeRowSubStyle
import io.github.xgl34222220.hetu.home.HomeSkeleton
import io.github.xgl34222220.hetu.home.HomeSpinner
import io.github.xgl34222220.hetu.home.HomeSwitch
import io.github.xgl34222220.hetu.home.HomeSwitchRow
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeHaptics
import io.github.xgl34222220.hetu.home.LocalHomeMotionEnabled
import io.github.xgl34222220.hetu.home.homeTap
import io.github.xgl34222220.hetu.ui.ht

/* ------------------------------------------------------------------ */
/*  Bar pieces                                                          */
/* ------------------------------------------------------------------ */

/**
 * Bar content for pages with more actions than a centred title leaves room for (应用管理 has
 * four). The title is centred in the space between the back button and the actions instead of
 * on the screen, so it can never slide under them. Transparent; the page draws the backdrop.
 */
@Composable
internal fun ToolsWideBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
) {
    val c = LocalHomeColors.current
    Row(
        modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(HomeDims.barHeight).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeIconButton(HomeIcons.ChevronLeft, "返回", onBack, glyph = 26.dp)
        Text(
            ht(title), Modifier.weight(1f).padding(horizontal = 6.dp).semantics { heading() },
            color = c.t1, style = HomeType.barTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
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
    val tint by animateColorAsState(if (enabled) c.accent else c.t3, HomeMotion.fade(LocalHomeMotionEnabled.current), label = "tools-bar-text")
    Row(
        modifier
            .heightIn(min = HomeDims.touch)
            .clip(HomeDims.controlShape)
            .homeTap(enabled = enabled && !loading, role = Role.Button) { haptics(HomeHaptic.Confirm); onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (loading) HomeSpinner(size = 18.dp, color = tint) else Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Text(ht(text), color = tint, style = HomeType.button.copy(fontWeight = FontWeight.Bold), maxLines = 1)
    }
}

/* ------------------------------------------------------------------ */
/*  Switches                                                            */
/* ------------------------------------------------------------------ */

/**
 * A switch that stands on its own (the master switch of 广告过滤). With a null
 * [onCheckedChange] it is only the picture of the state and the enclosing row handles the tap.
 */
@Composable
internal fun ToolsSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
) {
    if (onCheckedChange == null) {
        HomeSwitch(checked, modifier, enabled)
        return
    }
    val haptics = LocalHomeHaptics.current
    val source = remember { MutableInteractionSource() }
    val spoken = label?.let { ht(it) }
    Box(
        modifier
            .heightIn(min = HomeDims.touch)
            .toggleable(value = checked, interactionSource = source, indication = null, enabled = enabled, role = Role.Switch) { on ->
                haptics(HomeHaptic.Tick)
                onCheckedChange(on)
            }
            .semantics { if (spoken != null) contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) { HomeSwitch(checked, enabled = enabled) }
}

/** A [ToolsRow] with a switch at the end; the whole row toggles and is announced once. */
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
    rawContent: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    HomeSwitchRow(
        title = AnnotatedString(if (rawContent) title else ht(title)),
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        subtitle = subtitle?.let { if (rawContent) it else ht(it) },
        icon = icon,
        subtitleStyle = if (monospaceSubtitle) ToolsType.url else HomeRowSubStyle,
        enabled = enabled,
        leading = leading,
    )
}

/* ------------------------------------------------------------------ */
/*  Cards and small pieces                                              */
/* ------------------------------------------------------------------ */

/** Tinted notice at the top of a settings page: info glyph and one or two sentences. */
@Composable
internal fun ToolsInfoCard(text: String, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    Row(
        modifier.fillMaxWidth().clip(HomeDims.cardShape).background(c.hero).padding(horizontal = HomeRowDims.start, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(HomeIcons.Info, null, Modifier.padding(top = 1.dp).size(22.dp), tint = c.accent)
        Text(ht(text), Modifier.weight(1f), color = c.t1, style = HomeType.body.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium))
    }
}

/** Title line inside a card (“运行预检”, “规则源”…), optionally with something on the right. */
@Composable
internal fun ToolsCardTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) =
    HomeCardTitle(ht(text), modifier, trailing)

/** Round mark at the end of an app row: a ring that fills with the accent and a check when chosen. */
@Composable
internal fun ToolsCheckCircle(checked: Boolean, modifier: Modifier = Modifier, size: Dp = 26.dp) {
    val c = LocalHomeColors.current
    val ring by animateColorAsState(if (checked) Color.Transparent else c.t3, HomeMotion.fade(LocalHomeMotionEnabled.current), label = "tools-check-ring")
    Box(modifier.size(size).border(1.8.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
        HomeCheckMark(checked, size = size)
    }
}

/** Rounded tile with the first character of [label]; stands in for an app icon. */
@Composable
internal fun ToolsAvatar(label: String, modifier: Modifier = Modifier, size: Dp = 46.dp) {
    val c = LocalHomeColors.current
    Box(modifier.size(size).clip(RoundedCornerShape(size * .28f)).background(c.sunken), contentAlignment = Alignment.Center) {
        Text(label.trim().take(1).uppercase(), color = c.t2, style = HomeType.rowTitle)
    }
}

/** Rounded tile around a glyph: the core cards and the adblock status card use it. */
@Composable
internal fun ToolsGlyphTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = LocalHomeColors.current.t1,
    background: Color = LocalHomeColors.current.sunken,
    size: Dp = 60.dp,
    glyph: Dp = size * .56f,
) {
    Box(modifier.size(size).clip(RoundedCornerShape(size * .30f)).background(background), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(glyph), tint = tint)
    }
}

/* ------------------------------------------------------------------ */
/*  Editable list                                                       */
/* ------------------------------------------------------------------ */

/**
 * Rows of inline inputs with a delete button each and “+ 添加一项” underneath: the CIDR,
 * interface and MAC lists. Every row is a tinted tile carrying its small [label] above the value.
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
    val tile = if (c.dark) c.sunken else c.bg
    val style = ToolsTypography.mono.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = c.t1)
    val removeLabel = ht("删除")
    Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEachIndexed { index, value ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(ToolsDims.tileShape).background(tile).padding(start = 16.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
                    Text(ht(label), color = c.t2, style = HomeType.caption.copy(fontWeight = FontWeight.Medium))
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
                                if (value.isEmpty()) Text(ht(placeholder), color = c.t3, style = style.copy(color = c.t3, fontWeight = FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                inner()
                            }
                        },
                    )
                }
                HomeIconButton(ToolsIcons.Trash2, "$removeLabel ${value.ifBlank { ht(label) }}", { onRemove(index) }, enabled = enabled, tint = c.t2, glyph = 22.dp)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .alpha(if (enabled) 1f else .5f)
                .homeTap(enabled = enabled, role = Role.Button) { haptics(HomeHaptic.Tap); onAdd() }
                .clip(ToolsDims.tileShape)
                .background(tile),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            Icon(ToolsIcons.Plus, null, Modifier.size(20.dp), tint = c.accent)
            Text(ht("添加一项"), color = c.accent, style = HomeType.button)
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
    val removeLabel = ht("移除") + " " + text
    Row(
        modifier.height(40.dp).clip(CircleShape).background(if (c.dark) c.sunken else c.bg).padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.widthIn(max = 220.dp), color = c.t1, style = HomeType.bodySmall.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable(enabled = enabled, onClickLabel = removeLabel, role = Role.Button) { haptics(HomeHaptic.Tap); onRemove() }
                .semantics { contentDescription = removeLabel },
            contentAlignment = Alignment.Center,
        ) { Icon(HomeIcons.X, null, Modifier.size(16.dp), tint = c.t2) }
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
    HomeMenuSurface(modifier, minWidth = 190.dp) {
        options.forEach { option ->
            if (option.dividerBefore) HomeMenuDivider()
            HomeMenuItem(ht(option.label), option.onClick, enabled = option.enabled, checked = option.checked)
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Read-only text, loading, footer                                     */
/* ------------------------------------------------------------------ */

/**
 * Monospace block for the two diagnostic sheets. Scrolls both ways inside [maxHeight] so long
 * reports stay inside the sheet; lines never wrap, which keeps YAML indentation readable.
 */
@Composable
internal fun ToolsCodeBox(text: AnnotatedString, modifier: Modifier = Modifier, maxHeight: Dp = 420.dp) {
    val c = LocalHomeColors.current
    Box(modifier.fillMaxWidth().heightIn(max = maxHeight).clip(HomeDims.innerShape).background(if (c.dark) c.sunken else c.bg)) {
        Box(Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
            Text(text, Modifier.padding(horizontal = 16.dp, vertical = 14.dp), color = c.t1, style = ToolsEditorType.code.copy(fontSize = 14.sp, lineHeight = 21.sp), softWrap = false)
        }
    }
}

/** First paint of a page whose data is still on its way: three cards with a slow light sweep. */
@Composable
internal fun ToolsLoading(modifier: Modifier = Modifier, cards: Int = 3) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HomeDims.gap)) {
        repeat(cards) { index ->
            HomeSkeleton(Modifier.fillMaxWidth().height(if (index == 0) 132.dp else 96.dp), HomeDims.cardShape)
        }
    }
}

/** The line of a footer card (“已选 N 个 · …”); the page pins the card above the navigation bar. */
@Composable
internal fun ToolsFooterBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val c = LocalHomeColors.current
    Row(
        modifier.fillMaxWidth().heightIn(min = 54.dp).clip(HomeDims.cardShape).background(c.surface).padding(horizontal = HomeRowDims.start, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
