package io.github.xgl34222220.hetu.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/* ------------------------------------------------------------------ */
/*  Haptics hook: the host maps these to HetuHaptics                    */
/* ------------------------------------------------------------------ */

internal enum class HomeHaptic { Tap, Tick, Confirm, Reject }

internal val LocalHomeHaptics = staticCompositionLocalOf<(HomeHaptic) -> Unit> { {} }

/* ------------------------------------------------------------------ */
/*  Surfaces                                                            */
/* ------------------------------------------------------------------ */

/** White card with a 1 px hairline. No elevation anywhere on resting surfaces. */
@Composable
internal fun HomeCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    clickLabel: String? = null,
    background: Color = LocalHomeColors.current.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val base = modifier
        .clip(HomeDims.cardShape)
        .background(background)
        .border(1.dp, c.line, HomeDims.cardShape)
    val interactive = if (onClick == null) base else base.clickable(onClickLabel = clickLabel, role = Role.Button) {
        haptics(HomeHaptic.Tap)
        onClick()
    }
    Column(interactive, content = content)
}

@Composable
internal fun HomeDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalHomeColors.current.line))
}

@Composable
internal fun HomeVerticalDivider(modifier: Modifier = Modifier) {
    Box(modifier.width(1.dp).background(LocalHomeColors.current.line))
}

/* ------------------------------------------------------------------ */
/*  Small pieces                                                        */
/* ------------------------------------------------------------------ */

internal enum class HomeTone { Neutral, Accent, Good, Warn, Bad }

@Composable
internal fun HomeTone.foreground(): Color = LocalHomeColors.current.let { c ->
    when (this) {
        HomeTone.Neutral -> c.t2
        HomeTone.Accent -> c.accent
        HomeTone.Good -> c.good
        HomeTone.Warn -> c.warn
        HomeTone.Bad -> c.bad
    }
}

@Composable
internal fun HomeTone.soft(): Color = LocalHomeColors.current.let { c ->
    when (this) {
        HomeTone.Neutral -> c.sunken
        HomeTone.Accent -> c.accentSoft
        HomeTone.Good -> c.goodSoft
        HomeTone.Warn -> c.warnSoft
        HomeTone.Bad -> c.badSoft
    }
}

/** 20 dp tall label. [outlined] draws a hairline instead of a fill (used for the hero tags). */
@Composable
internal fun HomeBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: HomeTone = HomeTone.Neutral,
    outlined: Boolean = false,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val shaped = modifier.height(20.dp).clip(HomeDims.badgeShape).let {
        if (outlined) it.border(1.dp, c.line2, HomeDims.badgeShape) else it.background(tone.soft())
    }
    val interactive = if (onClick == null) shaped else shaped.clickable(role = Role.Button) { haptics(HomeHaptic.Tap); onClick() }
    Row(interactive.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(12.dp), tint = tone.foreground())
        Text(text, color = tone.foreground(), style = HomeType.badge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Two-letter region code in a hairline box. Replaces flag emoji so every device renders it the same. */
@Composable
internal fun HomeRegionCode(code: String, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    val text = code.trim().uppercase().take(3)
    if (text.isEmpty()) return
    Box(
        modifier.height(16.dp).widthIn(min = 22.dp).border(1.dp, c.line2, HomeDims.chipShape).padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = c.t2, style = HomeType.regionCode, maxLines = 1) }
}

@Composable
internal fun HomeStatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).background(color, CircleShape))
}

/** Latency with semantic colour: < 150 good, < 300 warn, otherwise bad; timeout bad; unknown t3. */
@Composable
internal fun HomeDelayText(delayMs: Long?, modifier: Modifier = Modifier, style: TextStyle = HomeType.delay) {
    val c = LocalHomeColors.current
    val (text, color) = when {
        delayMs == null -> "未知" to c.t3
        delayMs < 0 -> "超时" to c.bad
        delayMs < 150 -> "$delayMs ms" to c.good
        delayMs < 300 -> "$delayMs ms" to c.warn
        else -> "$delayMs ms" to c.bad
    }
    Text(text, modifier, color = color, style = style, maxLines = 1)
}

@Composable
internal fun HomeSpinner(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = LocalHomeColors.current.accent, strokeWidth: Dp = 2.dp) {
    val transition = rememberInfiniteTransition(label = "home-spinner")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart), label = "home-spinner-angle")
    Canvas(modifier.size(size).graphicsLayer { rotationZ = angle }) {
        val stroke = Stroke(strokeWidth.toPx(), cap = StrokeCap.Round)
        val inset = strokeWidth.toPx() / 2f
        val arcSize = Size(this.size.width - inset * 2f, this.size.height - inset * 2f)
        drawArc(color.copy(alpha = color.alpha * .22f), 0f, 360f, false, Offset(inset, inset), arcSize, style = stroke)
        drawArc(color, -90f, 90f, false, Offset(inset, inset), arcSize, style = stroke)
    }
}

@Composable
internal fun HomeProgressBar(fraction: Float?, modifier: Modifier = Modifier, color: Color = LocalHomeColors.current.accent) {
    val c = LocalHomeColors.current
    Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.sunken)) {
        val value = fraction?.coerceIn(0f, 1f) ?: 0f
        if (value > 0f) Box(Modifier.fillMaxWidth(value).height(4.dp).clip(RoundedCornerShape(2.dp)).background(color))
    }
}

@Composable
internal fun HomeRadio(selected: Boolean, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    Box(
        modifier.size(20.dp).border(1.5.dp, if (selected) c.accent else c.line2, CircleShape),
        contentAlignment = Alignment.Center,
    ) { if (selected) Box(Modifier.size(10.dp).background(c.accent, CircleShape)) }
}

/* ------------------------------------------------------------------ */
/*  Buttons                                                             */
/* ------------------------------------------------------------------ */

internal enum class HomeButtonKind { Primary, Secondary, Soft, Ghost }

/** 44 dp button. Primary = accent fill; Secondary = surface with hairline; Soft = accent tint; Ghost = text only. */
@Composable
internal fun HomeButton(
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
    val (fill, content) = when (kind) {
        HomeButtonKind.Primary -> c.accent to c.onAccent
        HomeButtonKind.Secondary -> c.surface to c.t1
        HomeButtonKind.Soft -> c.accentSoft to c.accent
        HomeButtonKind.Ghost -> Color.Transparent to c.accent
    }
    val shape = HomeDims.controlShape
    Row(
        modifier
            .height(HomeDims.touch)
            .alpha(if (enabled && !loading) 1f else .45f)
            .clip(shape)
            .background(fill)
            .let { if (kind == HomeButtonKind.Secondary) it.border(1.dp, c.line2, shape) else it }
            .clickable(enabled = enabled && !loading, role = Role.Button) { haptics(HomeHaptic.Tap); onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (loading) HomeSpinner(size = 16.dp, color = content)
        else if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = content)
        Text(text, color = content, style = HomeType.button, maxLines = 1)
    }
}

/** 40 × 44 dp icon button; the glyph itself is 20 dp. */
@Composable
internal fun HomeIconButton(
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
    Box(
        modifier
            .size(40.dp, HomeDims.touch)
            .clip(HomeDims.controlShape)
            .clickable(enabled = enabled && !loading, onClickLabel = label, role = Role.Button) { haptics(HomeHaptic.Tap); onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) HomeSpinner(size = 16.dp, color = c.t2)
        else Icon(icon, null, Modifier.size(20.dp), tint = if (enabled) tint else c.t3)
    }
}

/* ------------------------------------------------------------------ */
/*  Segmented control                                                   */
/* ------------------------------------------------------------------ */

/** Sunken track, 3 dp inset; the selected segment is a surface pill with a hairline. */
@Composable
internal fun <T> HomeSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else .5f)
            .clip(HomeDims.controlShape)
            .background(c.sunken)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            val fill by animateColorAsState(if (active) c.surface else Color.Transparent, tween(HomeMotion.SwitchMs), label = "home-seg-fill")
            val text by animateColorAsState(if (active) c.t1 else c.t2, tween(HomeMotion.SwitchMs), label = "home-seg-text")
            Box(
                Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(HomeDims.segmentShape)
                    .background(fill)
                    .let { if (active) it.border(1.dp, c.line, HomeDims.segmentShape) else it }
                    .clickable(enabled = enabled && !active, role = Role.RadioButton) { haptics(HomeHaptic.Tick); onSelect(value) },
                contentAlignment = Alignment.Center,
            ) { Text(label, color = text, style = HomeType.buttonSmall, maxLines = 1) }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Banner                                                              */
/* ------------------------------------------------------------------ */

/** Inline status strip: validation results, pending restart, risk notes. */
@Composable
internal fun HomeBanner(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: HomeTone = HomeTone.Warn,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = HomeDims.touch)
            .clip(HomeDims.controlShape)
            .background(tone.soft())
            .padding(start = 14.dp, end = if (actionLabel == null) 14.dp else 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = tone.foreground())
        Spacer(Modifier.width(8.dp))
        Text(text, Modifier.weight(1f), color = tone.foreground(), style = HomeType.note)
        if (actionLabel != null && onAction != null) {
            Box(
                Modifier.clip(HomeDims.badgeShape).clickable(role = Role.Button) { haptics(HomeHaptic.Tap); onAction() }
                    .heightIn(min = 32.dp).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(actionLabel, color = c.accent, style = HomeType.noteStrong, maxLines = 1) }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Rows                                                                */
/* ------------------------------------------------------------------ */

/** Label on the left, value on the right; 48 dp tall. Used by detail lists. */
@Composable
internal fun HomeKeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    labelWidth: Dp? = null,
    unknown: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = LocalHomeColors.current
    Row(
        modifier.fillMaxWidth().heightIn(min = HomeDims.rowMinHeightSmall).padding(start = 16.dp, end = if (trailing == null) 16.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            if (labelWidth != null) Modifier.width(labelWidth) else Modifier.weight(1f),
            color = c.t2, style = HomeType.label, maxLines = 1,
        )
        if (labelWidth != null) Spacer(Modifier.width(16.dp))
        Text(
            value,
            if (labelWidth != null) Modifier.weight(1f) else Modifier,
            color = if (unknown) c.t3 else c.t1,
            style = if (unknown) HomeType.body else HomeType.rowTitle.copy(fontFeatureSettings = "tnum"),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = if (labelWidth != null) TextAlign.Start else TextAlign.End,
        )
        if (trailing != null) trailing()
    }
}

/* ------------------------------------------------------------------ */
/*  Top bars                                                            */
/* ------------------------------------------------------------------ */

/** Sub-page bar: back chevron, centred title with optional subtitle, trailing actions. 52 dp below the status bar. */
@Composable
internal fun HomeTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = LocalHomeColors.current
    Box(modifier.fillMaxWidth().background(c.bg).windowInsetsPadding(WindowInsets.statusBars).height(HomeDims.barHeight)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            HomeIconButton(HomeIcons.ChevronLeft, "返回", onBack)
            Spacer(Modifier.weight(1f))
            actions()
        }
        Column(Modifier.align(Alignment.Center).padding(horizontal = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = c.t1, style = HomeType.barTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = c.t3, style = HomeType.barSubtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Text field                                                          */
/* ------------------------------------------------------------------ */

/** Label above a 44 dp sunken field; the border turns accent on focus and red on error. */
@Composable
internal fun HomeTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String = "",
) {
    val c = LocalHomeColors.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val border = when { isError -> c.bad; focused -> c.accent; else -> Color.Transparent }
    val textStyle = (if (monospace) HomeType.mono.copy(fontSize = HomeType.bodySmall.fontSize, lineHeight = HomeType.body.lineHeight) else HomeType.body).copy(color = c.t1)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, Modifier.padding(horizontal = 2.dp), color = c.t2, style = HomeType.section)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = textStyle,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            interactionSource = source,
            cursorBrush = SolidColor(c.accent),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = HomeDims.touch)
                        .clip(HomeDims.controlShape)
                        .background(if (focused) c.surface else c.sunken)
                        .border(1.dp, border, HomeDims.controlShape)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = c.t3, style = textStyle.copy(color = c.t3), maxLines = 1)
                    inner()
                }
            },
        )
    }
}

/* ------------------------------------------------------------------ */
/*  Sparkline                                                           */
/* ------------------------------------------------------------------ */

/**
 * Smooth area chart with three hairline grid rows. Null samples break the curve (missed samples).
 * @param max top of the value axis; the curve keeps 6 dp of head room like the prototype.
 */
@Composable
internal fun HomeSparkline(
    values: List<Float?>,
    color: Color,
    max: Float,
    modifier: Modifier = Modifier,
    height: Dp = 72.dp,
    fill: Boolean = true,
) {
    val grid = LocalHomeColors.current.line
    Canvas(modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val h = size.height
        for (row in 1..3) {
            val y = h * row / 4f
            drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx())
        }
        if (values.size < 2 || max <= 0f) return@Canvas
        val head = 6.dp.toPx()
        val foot = 2.dp.toPx()
        val step = w / (values.size - 1)
        val segments = mutableListOf<MutableList<Offset>>()
        var current = mutableListOf<Offset>()
        values.forEachIndexed { index, value ->
            if (value == null) {
                if (current.isNotEmpty()) segments += current
                current = mutableListOf()
            } else {
                current += Offset(index * step, h - (value / max).coerceIn(0f, 1f) * (h - head) - foot)
            }
        }
        if (current.isNotEmpty()) segments += current
        val stroke = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (points in segments) {
            if (points.size < 2) continue
            val line = Path().apply {
                moveTo(points[0].x, points[0].y)
                for (i in 1 until points.size) {
                    val midX = (points[i - 1].x + points[i].x) / 2f
                    cubicTo(midX, points[i - 1].y, midX, points[i].y, points[i].x, points[i].y)
                }
            }
            if (fill) {
                val area = Path().apply {
                    addPath(line)
                    lineTo(points.last().x, h)
                    lineTo(points.first().x, h)
                    close()
                }
                drawPath(area, color.copy(alpha = color.alpha * .08f))
            }
            drawPath(line, color, style = stroke)
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Bottom sheet content frame                                          */
/* ------------------------------------------------------------------ */

/**
 * The inside of a bottom sheet: grab handle, title row, body, optional footer buttons.
 * Kept free of ModalBottomSheet so the same content renders in static previews.
 */
@Composable
internal fun HomeSheetContent(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClose: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalHomeColors.current
    Column(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(36.dp, 4.dp).background(c.line2, RoundedCornerShape(2.dp)))
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = c.t1, style = HomeType.sheetTitle)
                if (subtitle != null) Text(subtitle, Modifier.padding(top = 2.dp), color = c.t2, style = HomeType.rowSub)
            }
            when {
                trailing != null -> trailing()
                onClose != null -> HomeIconButton(HomeIcons.X, "关闭", onClose)
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = if (footer == null) 24.dp else 16.dp), content = body)
        if (footer != null) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = footer)
        }
    }
}

/** Monospace block used by the failure sheet and other read-only text. */
@Composable
internal fun HomeCodeBox(text: String, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    HomeCard(modifier.fillMaxWidth(), background = c.bg) {
        Text(text, Modifier.padding(horizontal = 16.dp, vertical = 14.dp), color = c.t1, style = HomeType.mono)
    }
}
