package io.github.xgl34222220.hetu

import io.github.xgl34222220.hetu.ui.HetuHaptic
import io.github.xgl34222220.hetu.ui.rememberHetuHaptics
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import dev.chrisbanes.haze.rememberHazeState

/* ------------------------------------------------------------------ */
/*  Page scaffold                                                      */
/* ------------------------------------------------------------------ */

internal val HxTopBarHeight = 56.dp

/**
 * Every screen uses this. A large left-aligned title scrolls with the content; once it
 * leaves the viewport the same title appears centred in the pinned bar. Title position
 * never animates sideways, so there is no half-way "slightly left" state.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)
@Composable
internal fun HxPage(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    bottomPadding: Dp = 0.dp,
    listState: LazyListState = rememberLazyListState(),
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    scrollToTopSignal: Int = 0,
    content: LazyListScope.() -> Unit,
) {
    // Re-tapping the current dock item scrolls the page back to the top.
    val initialSignal = remember { scrollToTopSignal }
    LaunchedEffect(scrollToTopSignal) {
        if (scrollToTopSignal != initialSignal) listState.animateScrollToItem(0)
    }
    val c = Hx.colors
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val thresholdPx = with(density) { 44.dp.toPx() }
    val collapsed by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > thresholdPx }
    }
    val barAlpha by animateFloatAsState(if (collapsed) 1f else 0f, tween(HxMotion.Short), label = "barAlpha")

    val pageHaze = rememberHazeState()
    Box(Modifier.fillMaxSize().background(c.canvas)) {
        val list: @Composable () -> Unit = {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().hazeSource(pageHaze),
                contentPadding = PaddingValues(
                    top = statusTop + HxTopBarHeight,
                    bottom = (if (bottomPadding > 0.dp) bottomPadding else navInset) + 24.dp,
                ),
            ) {
                item(key = "hx-page-header") {
                    Column(Modifier.fillMaxWidth().padding(start = Hx.gutter, end = Hx.gutter, bottom = 14.dp)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = (-0.6).sp),
                            color = c.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                content()
            }
        }
        if (onRefresh != null) {
            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { if (!refreshing) onRefresh() },
                modifier = Modifier.fillMaxSize(),
                state = pullState,
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullState,
                        isRefreshing = refreshing,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = statusTop + HxTopBarHeight - 8.dp),
                        containerColor = c.surface,
                        color = c.accent,
                    )
                },
            ) { list() }
        } else {
            list()
        }

        // Pinned bar: frosted glass over the scrolling content once the large title is gone.
        val blur = LocalHxBlur.current
        Box(Modifier.fillMaxWidth().align(Alignment.TopCenter).height(statusTop + HxTopBarHeight)) {
            // Glass layer covers the status bar and the bar itself.
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = barAlpha }
                    .then(
                        if (blur) Modifier.hazeEffect(state = pageHaze, style = HazeMaterials.ultraThin()) {
                            blurRadius = 22.dp
                            noiseFactor = .01f
                        } else Modifier,
                    )
                    .background(c.canvas.copy(alpha = if (blur) .70f else 1f)),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = statusTop)
                    .height(HxTopBarHeight),
            ) {
                Text(
                    title,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 96.dp)
                        .graphicsLayer {
                            alpha = barAlpha
                            translationY = (1f - barAlpha) * 8.dp.toPx()
                            val s = .94f + .06f * barAlpha
                            scaleX = s
                            scaleY = s
                        },
                    style = MaterialTheme.typography.titleMedium,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = c.text)
                    }
                }
                Row(
                    Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
            HorizontalDivider(Modifier.align(Alignment.BottomCenter), color = c.line.copy(alpha = barAlpha), thickness = 0.5.dp)
        }
        overlay()
    }
}

/* ------------------------------------------------------------------ */
/*  Surfaces                                                            */
/* ------------------------------------------------------------------ */

/** Press feedback shared by every tappable surface: a small, quick scale. */
@Composable
internal fun Modifier.hxPressScale(source: MutableInteractionSource, pressedScale: Float = .975f): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, HxMotion.press(), label = "press")
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
internal fun HxCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = Hx.colors.surface,
    padding: PaddingValues = PaddingValues(16.dp),
    brush: androidx.compose.ui.graphics.Brush? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val c = Hx.colors
    Surface(
        modifier = modifier.then(if (onClick != null) Modifier.hxPressScale(source) else Modifier).hxSoftShadow(Hx.cardShape),
        shape = Hx.cardShape,
        color = color,
        border = if (c.dark) BorderStroke(0.5.dp, c.line) else null,
    ) {
        Column(
            Modifier
                .then(if (brush != null) Modifier.background(brush) else Modifier)
                .then(if (onClick != null) Modifier.clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick) else Modifier)
                .padding(padding),
            content = content,
        )
    }
}

/** A titled group of rows. */
@Composable
internal fun HxSection(
    title: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = Hx.gutter).padding(bottom = 16.dp)) {
        if (title != null || trailing != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 3.dp, end = 0.dp, bottom = 7.dp).heightIn(min = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title.orEmpty(),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, letterSpacing = 0.15.sp),
                    color = Hx.colors.textMuted,
                    modifier = Modifier.weight(1f),
                )
                if (trailing != null) trailing()
            }
        }
        content()
    }
}

/** Rows stacked in one card with inset hairlines. */
@Composable
internal fun HxGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Hx.colors
    Surface(
        modifier = modifier.fillMaxWidth().hxSoftShadow(Hx.cardShape),
        shape = Hx.cardShape,
        color = c.surface,
        border = if (c.dark) BorderStroke(0.5.dp, c.line) else null,
    ) {
        Column(Modifier.padding(vertical = 2.dp), content = content)
    }
}

@Composable
internal fun HxDivider(inset: Dp = 60.dp) {
    HorizontalDivider(Modifier.padding(start = inset), thickness = 0.5.dp, color = Hx.colors.line.copy(alpha = .78f))
}

@Composable
internal fun HxIconBadge(icon: ImageVector, tint: Color = Hx.colors.accent, size: Dp = 38.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(tint.copy(alpha = if (Hx.colors.dark) .18f else .12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

/**
 * The single list-row primitive. Title, optional supporting line, optional trailing
 * slot; whole row tappable when [onClick] is set.
 */
@Composable
internal fun HxRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Hx.colors.accent,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val c = Hx.colors
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 58.dp)
            .padding(horizontal = 15.dp, vertical = 10.dp)
            .graphicsLayer { alpha = if (enabled) 1f else .45f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            HxIconBadge(icon, if (danger) c.bad else iconTint)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (danger) c.bad else c.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
internal fun HxChevron() {
    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Hx.colors.textFaint, modifier = Modifier.size(20.dp))
}

@Composable
internal fun HxNavRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Hx.colors.accent,
    value: String? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    HxRow(title, subtitle = subtitle, icon = icon, iconTint = iconTint, enabled = enabled, danger = danger, onClick = onClick) {
        if (!value.isNullOrBlank()) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = Hx.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp),
            )
            Spacer(Modifier.width(2.dp))
        }
        HxChevron()
    }
}

@Composable
internal fun HxSwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Hx.colors.accent,
    enabled: Boolean = true,
) {
    val haptics = rememberHetuHaptics()
    HxRow(title, subtitle = subtitle, icon = icon, iconTint = iconTint, enabled = enabled, onClick = {
        haptics.perform(if (!checked) HetuHaptic.ToggleOn else HetuHaptic.ToggleOff)
        onChange(!checked)
    }) {
        HxSwitch(checked = checked, onChange = onChange, enabled = enabled)
    }
}

@Composable
internal fun HxSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = Hx.colors
    val haptics = rememberHetuHaptics()
    Switch(
        checked = checked,
        onCheckedChange = { on -> haptics.perform(if (on) HetuHaptic.ToggleOn else HetuHaptic.ToggleOff); onChange(on) },
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = c.onAccent,
            checkedTrackColor = c.accent,
            checkedBorderColor = c.accent,
            uncheckedThumbColor = c.textFaint,
            uncheckedTrackColor = c.surfaceMuted,
            uncheckedBorderColor = c.line,
        ),
    )
}

/* ------------------------------------------------------------------ */
/*  Small pieces                                                        */
/* ------------------------------------------------------------------ */

internal enum class HxTone { Neutral, Accent, Good, Warn, Bad }

@Composable
internal fun HxTone.fg(): Color = when (this) {
    HxTone.Neutral -> Hx.colors.textMuted
    HxTone.Accent -> Hx.colors.accent
    HxTone.Good -> Hx.colors.good
    HxTone.Warn -> Hx.colors.warn
    HxTone.Bad -> Hx.colors.bad
}

@Composable
internal fun HxTone.bg(): Color = when (this) {
    HxTone.Neutral -> Hx.colors.surfaceMuted
    HxTone.Accent -> Hx.colors.accentSoft
    HxTone.Good -> Hx.colors.goodSoft
    HxTone.Warn -> Hx.colors.warnSoft
    HxTone.Bad -> Hx.colors.badSoft
}

@Composable
internal fun HxPill(text: String, tone: HxTone = HxTone.Neutral, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(Hx.pillShape).background(tone.bg()).padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = tone.fg(), maxLines = 1)
    }
}

@Composable
internal fun HxDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
internal fun HxSpinner(size: Dp = 18.dp, color: Color = Hx.colors.accent) {
    CircularProgressIndicator(modifier = Modifier.size(size), color = color, strokeWidth = 2.dp)
}

/** Inline status message with optional action. */
@Composable
internal fun HxBanner(
    text: String,
    tone: HxTone = HxTone.Accent,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().clip(Hx.rowShape).background(tone.bg()).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (tone == HxTone.Bad || tone == HxTone.Warn) Icons.Rounded.ErrorOutline else Icons.Rounded.Info,
            contentDescription = null,
            tint = tone.fg(),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Hx.colors.text, modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel, color = tone.fg(), fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
internal fun HxEmpty(icon: ImageVector, title: String, description: String? = null, action: (@Composable () -> Unit)? = null) {
    val c = Hx.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val float = androidx.compose.animation.core.rememberInfiniteTransition(label = "emptyFloat")
        val dy by float.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(tween(2200), androidx.compose.animation.core.RepeatMode.Reverse),
            label = "emptyDy",
        )
        Box(
            Modifier.size(64.dp).graphicsLayer { translationY = -dy * 4.dp.toPx() }.clip(CircleShape).background(c.surfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = c.textFaint, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = c.text, textAlign = TextAlign.Center)
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = c.textMuted, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

/** Pill-shaped segmented control with a sliding indicator. */
@Composable
internal fun HxSegmented(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = Hx.colors
    val haptics = rememberHetuHaptics()
    var widthPx by remember { mutableStateOf(0) }
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val density = LocalDensity.current
    val segment = if (options.isEmpty()) 0.dp else with(density) { (widthPx / options.size).toDp() }
    val offset by animateDpAsState(
        segment * index,
        androidx.compose.animation.core.spring(dampingRatio = .8f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
        label = "segment",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(Hx.pillShape)
            .background(c.surfaceMuted)
            .padding(3.dp)
            .onSizeChanged { widthPx = it.width },
    ) {
        if (widthPx > 0 && options.isNotEmpty()) {
            Box(
                Modifier
                    .offset(x = offset)
                    .width(segment)
                    .height(34.dp)
                    .clip(Hx.pillShape)
                    .background(c.surface)
                    .then(if (c.dark) Modifier.border(0.5.dp, c.line, Hx.pillShape) else Modifier),
            )
        }
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, (id, label) ->
                val active = i == index
                val color by animateColorAsState(if (active) c.text else c.textMuted, tween(HxMotion.Short), label = "segText")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clip(Hx.pillShape)
                        .clickable(enabled = enabled && !active) { haptics.perform(HetuHaptic.Tick); onSelect(id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun HxSearchField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val c = Hx.colors
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder, color = c.textFaint) },
        leadingIcon = { Icon(Icons.Rounded.Search, null, tint = c.textMuted) },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, "清除", tint = c.textMuted) }
        },
        shape = Hx.pillShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = c.surface,
            unfocusedContainerColor = c.surface,
            focusedBorderColor = c.accent,
            unfocusedBorderColor = c.line,
        ),
    )
}

/** A metric with a small caption. */
@Composable
internal fun HxMetric(label: String, value: String, modifier: Modifier = Modifier, unit: String? = null, valueColor: Color = Hx.colors.text) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Hx.colors.textMuted, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium.merge(HxNumberStyle),
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!unit.isNullOrBlank()) {
                Spacer(Modifier.width(3.dp))
                Text(unit, style = MaterialTheme.typography.labelSmall, color = Hx.colors.textMuted, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Sheets and dialogs                                                  */
/* ------------------------------------------------------------------ */

internal fun hxCopy(context: Context, label: String, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HxSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Hx.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.surface,
        contentColor = c.text,
        scrimColor = Color.Black.copy(alpha = .38f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = c.text,
                    modifier = Modifier.padding(horizontal = 22.dp).padding(bottom = 10.dp),
                )
            }
            content()
        }
    }
}

/** Monospace text viewer (logs, diagnostics, generated config). */
@Composable
internal fun HxTextSheet(title: String, text: String, onDismiss: () -> Unit, onRefresh: (() -> Unit)? = null) {
    val context = LocalContext.current
    val c = Hx.colors
    HxSheet(onDismiss = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.weight(1f))
            if (onRefresh != null) TextButton(onClick = onRefresh) { Text("刷新") }
            IconButton(onClick = { hxCopy(context, title, text) }) { Icon(Icons.Rounded.ContentCopy, "复制", tint = c.textMuted) }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .padding(horizontal = 16.dp)
                .clip(Hx.rowShape)
                .background(c.surfaceMuted)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(14.dp),
        ) {
            Text(
                text.ifBlank { "（空）" },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = c.text,
            )
        }
    }
}

internal data class HxChoice(val id: String, val label: String, val description: String? = null, val enabled: Boolean = true)

@Composable
internal fun HxChoiceSheet(
    title: String,
    choices: List<HxChoice>,
    selected: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    footer: String? = null,
) {
    val c = Hx.colors
    HxSheet(onDismiss = onDismiss, title = title) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            choices.forEach { choice ->
                val active = choice.id == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(Hx.rowShape)
                        .background(if (active) c.accentSoft else Color.Transparent)
                        .clickable(enabled = choice.enabled) { onPick(choice.id) }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .graphicsLayer { alpha = if (choice.enabled) 1f else .4f },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(choice.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = c.text)
                        if (!choice.description.isNullOrBlank()) {
                            Text(choice.description, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                        }
                    }
                    if (active) Icon(Icons.Rounded.Check, null, tint = c.accent, modifier = Modifier.size(20.dp))
                }
            }
            if (!footer.isNullOrBlank()) {
                Text(footer, style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.padding(14.dp))
            }
        }
    }
}

internal data class HxField(
    val label: String,
    val initial: String = "",
    val placeholder: String = "",
    val singleLine: Boolean = true,
    val number: Boolean = false,
)

/** Generic form dialog: returns the entered values in field order. */
@Composable
internal fun HxFormDialog(
    title: String,
    fields: List<HxField>,
    confirmLabel: String = "保存",
    message: String? = null,
    validate: (List<String>) -> String? = { null },
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Hx.colors
    val values = remember { fields.map { mutableStateOf(it.initial) } }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!message.isNullOrBlank()) Text(message, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                fields.forEachIndexed { index, field ->
                    OutlinedTextField(
                        value = values[index].value,
                        onValueChange = { values[index].value = it; error = null },
                        label = { Text(field.label) },
                        placeholder = { if (field.placeholder.isNotBlank()) Text(field.placeholder, color = c.textFaint) },
                        singleLine = field.singleLine,
                        maxLines = if (field.singleLine) 1 else 6,
                        keyboardOptions = if (field.number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                        modifier = Modifier.fillMaxWidth(),
                        shape = Hx.rowShape,
                    )
                }
                AnimatedVisibility(error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Text(error.orEmpty(), color = c.bad, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val result = values.map { it.value.trim() }
                val problem = validate(result)
                if (problem != null) error = problem else onConfirm(result)
            }) { Text(confirmLabel, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = c.textMuted) } },
    )
}

@Composable
internal fun HxConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "确定",
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Hx.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium, color = c.textMuted) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (danger) c.bad else c.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = c.textMuted) } },
    )
}

/** Primary filled button in the app's pill shape. */
@Composable
internal fun HxButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    tone: HxTone = HxTone.Accent,
    filled: Boolean = true,
) {
    val c = Hx.colors
    val haptics = rememberHetuHaptics()
    val source = remember { MutableInteractionSource() }
    val bg = if (filled) tone.fg() else tone.bg()
    val fg = if (filled) (if (tone == HxTone.Accent) c.onAccent else Color.White) else tone.fg()
    Row(
        modifier
            .hxPressScale(source, .97f)
            .heightIn(min = 44.dp)
            .clip(Hx.pillShape)
            .background(if (enabled) bg else c.surfaceMuted)
            .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled && !busy, onClick = { haptics.perform(HetuHaptic.Tap); onClick() })
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (busy) {
            HxSpinner(16.dp, fg)
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(icon, null, tint = if (enabled) fg else c.textFaint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) fg else c.textFaint, maxLines = 1)
    }
}

/** Small circular icon action used in top bars. */
@Composable
internal fun HxBarAction(icon: ImageVector, description: String, onClick: () -> Unit, busy: Boolean = false, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled && !busy) {
        if (busy) HxSpinner(18.dp) else Icon(icon, description, tint = if (enabled) Hx.colors.text else Hx.colors.textFaint)
    }
}

/** Thin rounded progress bar with an animated fill. */
@Composable
internal fun HxProgressBar(ratio: Float, color: Color = Hx.colors.accent, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val animated by animateFloatAsState(ratio.coerceIn(0f, 1f), tween(HxMotion.Long, easing = HxMotion.Emphasized), label = "progress")
    Box(modifier.fillMaxWidth().height(height).clip(Hx.pillShape).background(Hx.colors.surfaceMuted)) {
        Box(Modifier.fillMaxWidth(animated).height(height).clip(Hx.pillShape).background(color))
    }
}