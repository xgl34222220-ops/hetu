package io.github.xgl34222220.hetu.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import dev.chrisbanes.haze.rememberHazeState
import io.github.xgl34222220.hetu.ui.ht
import kotlin.math.abs
import kotlin.math.min

/* ------------------------------------------------------------------ */
/*  Haptics hook: the host maps these to HetuHaptics                    */
/* ------------------------------------------------------------------ */

internal enum class HomeHaptic { Tap, Tick, Confirm, Reject }

internal val LocalHomeHaptics = staticCompositionLocalOf<(HomeHaptic) -> Unit> { {} }

/* ------------------------------------------------------------------ */
/*  Surfaces                                                            */
/* ------------------------------------------------------------------ */

/**
 * Near-white card on the lavender canvas: 24 dp continuous corners, no border and no shadow.
 * A clickable card dips as a whole (surface included) while pressed.
 */
@Composable
internal fun HomeCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    clickLabel: String? = null,
    background: Color = LocalHomeColors.current.surface,
    shape: Shape = HomeDims.cardShape,
    content: @Composable ColumnScope.() -> Unit,
) {
    val haptics = LocalHomeHaptics.current
    val label = clickLabel?.let { ht(it) }
    val tappable = if (onClick == null) modifier else modifier.homeTap(onClickLabel = label, role = Role.Button) {
        haptics(HomeHaptic.Tap)
        onClick()
    }
    Column(tappable.clip(shape).background(background), content = content)
}

/** Hairline between rows of one card. [inset] keeps it clear of the card's rounded edges. */
@Composable
internal fun HomeDivider(modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    Box(modifier.fillMaxWidth().padding(horizontal = inset).height(1.dp).background(LocalHomeColors.current.line))
}

@Composable
internal fun HomeVerticalDivider(modifier: Modifier = Modifier) {
    Box(modifier.width(1.dp).background(LocalHomeColors.current.line))
}

/* ------------------------------------------------------------------ */
/*  Tones                                                               */
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

/** The tone as text on its own soft fill or on a card: deep enough to read at small sizes. */
@Composable
internal fun HomeTone.text(): Color = LocalHomeColors.current.let { c ->
    when (this) {
        HomeTone.Neutral -> c.t2
        HomeTone.Accent -> c.accent
        HomeTone.Good -> c.goodText
        HomeTone.Warn -> c.warnText
        HomeTone.Bad -> c.badText
    }
}

/* ------------------------------------------------------------------ */
/*  Shared with 工具: signatures and geometry stay as they were         */
/* ------------------------------------------------------------------ */

private val SharedBadgeShape = RoundedCornerShape(6.dp)
private val SharedBannerShape = RoundedCornerShape(12.dp)
private val SharedBadgeText = TextStyle(fontSize = 11.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
private val SharedNoteText = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
private val SharedNoteStrongText = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)

/** 20 dp tall label. [outlined] draws a hairline instead of a fill. 工具 pages use it as it is. */
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
    val shaped = modifier.height(20.dp).clip(SharedBadgeShape).let {
        if (outlined) it.border(1.dp, c.line2, SharedBadgeShape) else it.background(tone.soft())
    }
    val interactive = if (onClick == null) shaped else shaped.homeTap(role = Role.Button) { haptics(HomeHaptic.Tap); onClick() }
    Row(interactive.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(12.dp), tint = tone.foreground())
        Text(text, color = tone.foreground(), style = SharedBadgeText, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Inline status strip used by the 工具 pages: validation results and risk notes. */
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
            .heightIn(min = 44.dp)
            .clip(SharedBannerShape)
            .background(tone.soft())
            .padding(start = 14.dp, end = if (actionLabel == null) 14.dp else 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = tone.foreground())
        Spacer(Modifier.width(8.dp))
        Text(text, Modifier.weight(1f), color = tone.foreground(), style = SharedNoteText)
        if (actionLabel != null && onAction != null) {
            Box(
                Modifier.clip(SharedBadgeShape).clickable(role = Role.Button) { haptics(HomeHaptic.Tap); onAction() }
                    .heightIn(min = 32.dp).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(actionLabel, color = c.accent, style = SharedNoteStrongText, maxLines = 1) }
        }
    }
}

@Composable
internal fun HomeSpinner(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = LocalHomeColors.current.accent, strokeWidth: Dp = 2.dp) {
    val angle = if (LocalHomeMotionEnabled.current) {
        val transition = rememberInfiniteTransition(label = "home-spinner")
        val animated by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart), label = "home-spinner-angle")
        animated
    } else 0f
    Canvas(modifier.size(size).graphicsLayer { rotationZ = angle }) {
        val stroke = Stroke(strokeWidth.toPx(), cap = StrokeCap.Round)
        val inset = strokeWidth.toPx() / 2f
        val arcSize = Size(this.size.width - inset * 2f, this.size.height - inset * 2f)
        drawArc(color.copy(alpha = color.alpha * .22f), 0f, 360f, false, Offset(inset, inset), arcSize, style = stroke)
        drawArc(color, -90f, 90f, false, Offset(inset, inset), arcSize, style = stroke)
    }
}

/* ------------------------------------------------------------------ */
/*  Small pieces                                                        */
/* ------------------------------------------------------------------ */

/**
 * Capsule label of the concept: 「详情」「已选择」「80%」「INFO」. Soft fill of its tone with the
 * tone's readable text colour; both cross-fade when the tone changes. With [onClick] it is a button.
 */
@Composable
internal fun HomePill(
    text: String,
    modifier: Modifier = Modifier,
    tone: HomeTone = HomeTone.Accent,
    icon: ImageVector? = null,
    height: Dp = 24.dp,
    style: TextStyle = HomeType.badge,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val haptics = LocalHomeHaptics.current
    val fill by animateColorAsState(tone.soft(), HomeMotion.fade(LocalHomeMotionEnabled.current), label = "home-pill-fill")
    val content by animateColorAsState(tone.text(), HomeMotion.fade(LocalHomeMotionEnabled.current), label = "home-pill-text")
    val interactive = if (onClick == null) modifier else modifier.homeTap(role = Role.Button) { haptics(HomeHaptic.Tap); onClick() }
    Row(
        interactive.heightIn(min = height).clip(HomeDims.pillShape).background(fill).padding(horizontal = if (height < 24.dp) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (leading != null) leading()
        if (icon != null) Icon(icon, null, Modifier.size(13.dp), tint = content)
        Text(text, color = content, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Two- or three-letter region code in a hairline box, for regions [HomeFlag] cannot draw. */
@Composable
internal fun HomeRegionCode(code: String, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    val text = code.trim().uppercase().take(3)
    if (text.isEmpty()) return
    Box(
        modifier.height(17.dp).widthIn(min = 24.dp).border(1.dp, c.line2, RoundedCornerShape(4.dp)).padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = c.t2, style = HomeType.regionCode, maxLines = 1) }
}

/**
 * Status dot. With [pulse] a ring keeps leaving it, which reads as “live” without drawing the eye.
 * The pulse is drawn outside the dot's own bounds, so it never changes layout.
 */
@Composable
internal fun HomeStatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp, pulse: Boolean = false) {
    val tint by animateColorAsState(color, HomeMotion.fade(LocalHomeMotionEnabled.current, 320), label = "home-dot")
    val wave = if (pulse && LocalHomeMotionEnabled.current) {
        val transition = rememberInfiniteTransition(label = "home-dot-pulse")
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(HomeMotion.BreathMs, easing = HomeMotion.Decelerate), RepeatMode.Restart), label = "home-dot-wave")
    } else null
    Canvas(modifier.size(size)) {
        val radius = this.size.minDimension / 2f
        val phase = wave?.value
        if (phase != null) drawCircle(tint.copy(alpha = tint.alpha * .34f * (1f - phase)), radius * (1f + 1.5f * phase))
        drawCircle(tint, radius)
    }
}

/** Colour of a latency figure: accent while healthy, amber when slow, red when very slow. */
internal fun HomeColors.delayColor(ms: Long): Color = when {
    ms < 300L -> accent
    ms < 800L -> warnText
    else -> badText
}

/** “28 ms” as plain coloured text; null = unknown, negative = timed out. */
@Composable
internal fun HomeDelayText(delayMs: Long?, modifier: Modifier = Modifier, style: TextStyle = HomeType.metric) {
    val c = LocalHomeColors.current
    val (text, color) = when {
        delayMs == null -> ht("未知") to c.t3
        delayMs < 0 -> ht("超时") to c.badText
        else -> "$delayMs ms" to c.delayColor(delayMs)
    }
    HomeRollingText(text, color, style, modifier)
}

/** Thin rounded bar; the fill eases to its new length. Null [fraction] shows the empty track. */
@Composable
internal fun HomeProgressBar(
    fraction: Float?,
    modifier: Modifier = Modifier,
    color: Color = LocalHomeColors.current.accent,
    track: Color = LocalHomeColors.current.accentSoft,
    height: Dp = 5.dp,
) {
    val value = homeAnimatedFraction(fraction ?: 0f, "home-progress")
    Canvas(modifier.fillMaxWidth().height(height)) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(track, cornerRadius = radius)
        if (value > 0f) drawRoundRect(color, size = Size((size.width * value).coerceAtLeast(size.height), size.height), cornerRadius = radius)
    }
}

/** Radio mark of the concept: a ring that fills with the accent and grows a white centre. */
@Composable
internal fun HomeRadio(selected: Boolean, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    val motion = LocalHomeMotionEnabled.current
    val progress by animateFloatAsState(if (selected) 1f else 0f, HomeMotion.pop(motion), label = "home-radio")
    val ring by animateColorAsState(if (selected) c.accent else c.t3, HomeMotion.fade(motion), label = "home-radio-ring")
    val knob = c.onAccent
    Canvas(modifier.size(22.dp)) {
        val radius = size.minDimension / 2f
        val stroke = 2.dp.toPx()
        val p = progress.coerceIn(0f, 1.2f)
        drawCircle(ring, radius - stroke / 2f, style = Stroke(stroke))
        if (p > 0f) {
            drawCircle(ring, (radius - stroke) * min(p, 1f))
            drawCircle(knob, radius * .34f * p)
        }
    }
}

/** Filled accent disc with a check that pops in; used for the selected node and for menu ticks. */
@Composable
internal fun HomeCheckMark(checked: Boolean, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val c = LocalHomeColors.current
    val progress by animateFloatAsState(if (checked) 1f else 0f, HomeMotion.pop(LocalHomeMotionEnabled.current), label = "home-check")
    val fill = c.accent
    val mark = c.onAccent
    Canvas(modifier.size(size)) {
        val p = progress
        if (p <= 0.01f) return@Canvas
        val radius = this.size.minDimension / 2f
        val centre = Offset(this.size.width / 2f, this.size.height / 2f)
        drawCircle(fill.copy(alpha = fill.alpha * p.coerceIn(0f, 1f)), radius * p.coerceIn(0f, 1.12f), centre)
        val s = radius * p.coerceIn(0f, 1f)
        val path = Path().apply {
            moveTo(centre.x - s * .46f, centre.y + s * .02f)
            lineTo(centre.x - s * .12f, centre.y + s * .36f)
            lineTo(centre.x + s * .48f, centre.y - s * .30f)
        }
        drawPath(path, mark, style = Stroke(radius * .22f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * Switch of the concept, 50 × 30 dp. Purely visual: the enclosing row owns the click and the
 * toggle semantics. The knob travels on a spring and stretches slightly mid-way.
 */
@Composable
internal fun HomeSwitch(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = LocalHomeColors.current
    val motion = LocalHomeMotionEnabled.current
    val position by animateFloatAsState(if (checked) 1f else 0f, HomeMotion.glide(motion), label = "home-switch")
    val on = c.accent
    val off = if (c.dark) Color.White.copy(alpha = .22f) else Color(0xFFC6C9D8)
    Canvas(modifier.size(50.dp, 30.dp).alpha(if (enabled) 1f else .45f)) {
        val t = position.coerceIn(0f, 1f)
        val half = size.height / 2f
        drawRoundRect(lerp(off, on, t), cornerRadius = CornerRadius(half, half))
        val knob = half - 3.dp.toPx()
        val stretch = (1f - abs(t - .5f) * 2f) * 4.dp.toPx()
        val centre = half + (size.width - size.height) * position
        drawRoundRect(Color.Black.copy(alpha = .10f), Offset(centre - knob - stretch / 2f, half - knob + 1.dp.toPx()), Size(knob * 2f + stretch, knob * 2f), CornerRadius(knob, knob))
        drawRoundRect(Color.White, Offset(centre - knob - stretch / 2f, half - knob), Size(knob * 2f + stretch, knob * 2f), CornerRadius(knob, knob))
    }
}

/* ------------------------------------------------------------------ */
/*  Buttons                                                             */
/* ------------------------------------------------------------------ */

/**
 * Primary = accent fill; Secondary = outlined in the accent (恢复默认, 检测);
 * Soft = neutral tint with dark text (取消); Ghost = text only.
 */
internal enum class HomeButtonKind { Primary, Secondary, Soft, Ghost }

/** 50 dp button with 16 dp corners. [danger] swaps the accent for red in every kind. */
@Composable
internal fun HomeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: HomeButtonKind = HomeButtonKind.Secondary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    danger: Boolean = false,
    height: Dp = 50.dp,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val lead = if (danger) c.bad else c.accent
    val leadText = if (danger) c.badText else c.accent
    val onLead = if (danger) (if (c.dark) Color(0xFF1B0808) else Color.White) else c.onAccent
    val (fill, content) = when (kind) {
        HomeButtonKind.Primary -> lead to onLead
        HomeButtonKind.Secondary -> c.surface to leadText
        HomeButtonKind.Soft -> (if (danger) c.badSoft else c.sunken) to (if (danger) c.badText else c.t1)
        HomeButtonKind.Ghost -> Color.Transparent to leadText
    }
    val shape = HomeDims.controlShape
    val active = enabled && !loading
    Row(
        modifier
            .heightIn(min = height)
            .alpha(if (active) 1f else .5f)
            .homeTap(enabled = active, role = Role.Button) { haptics(if (kind == HomeButtonKind.Primary) HomeHaptic.Confirm else HomeHaptic.Tap); onClick() }
            .clip(shape)
            .background(fill)
            .let { if (kind == HomeButtonKind.Secondary) it.border(1.5.dp, lead.copy(alpha = if (c.dark) .70f else .62f), shape) else it }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (loading) HomeSpinner(size = 18.dp, color = content)
        else if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = content)
        Text(ht(text), color = content, style = HomeType.button, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 48 dp round touch target around a 24 dp glyph. [label] is spoken and never drawn. */
@Composable
internal fun HomeIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    tint: Color = LocalHomeColors.current.t1,
    spinning: Boolean = false,
    glyph: Dp = 24.dp,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val spoken = ht(label)
    val angle = homeSpinAngle(spinning)
    Box(
        modifier
            .size(HomeDims.touch)
            .clip(CircleShape)
            .homeTap(enabled = enabled && !loading, onClickLabel = spoken, role = Role.Button) { haptics(HomeHaptic.Tap); onClick() }
            .semantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) HomeSpinner(size = 20.dp, color = c.t2)
        else Icon(icon, null, Modifier.size(glyph).graphicsLayer { rotationZ = angle }, tint = if (enabled) tint else c.t3)
    }
}

/* ------------------------------------------------------------------ */
/*  Segmented control                                                   */
/* ------------------------------------------------------------------ */

/**
 * Soft = accent-tinted thumb with accent text (首页模式); Solid = accent thumb with white text
 * (详情、设置面板); Raised = card-coloured thumb with dark text on a sunken track (inline filters).
 */
internal enum class HomeSegmentStyle { Soft, Solid, Raised }

/**
 * Segmented control whose thumb slides between segments on a spring instead of cross-fading.
 * The thumb and the separators are drawn behind the labels, so only the draw phase animates.
 */
@Composable
internal fun <T> HomeSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: HomeSegmentStyle = HomeSegmentStyle.Solid,
    track: Color = LocalHomeColors.current.sunken,
    height: Dp = 40.dp,
    corner: Dp = 12.dp,
    textStyle: TextStyle = HomeType.buttonSmall,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val motion = LocalHomeMotionEnabled.current
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val position = animateFloatAsState(index.toFloat(), HomeMotion.glide(motion), label = "home-seg-thumb")
    val thumb = when (style) {
        HomeSegmentStyle.Solid -> c.accent
        HomeSegmentStyle.Soft -> c.accentSoft
        HomeSegmentStyle.Raised -> c.surface
    }
    val separator = c.line2
    val count = options.size
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .alpha(if (enabled) 1f else .5f)
            .clip(RoundedCornerShape(corner))
            .background(track)
            .drawBehind {
                if (count == 0) return@drawBehind
                val inset = 3.dp.toPx()
                val cell = (size.width - inset * 2f) / count
                val at = position.value
                val radius = (corner.toPx() - inset).coerceAtLeast(0f)
                // Separators only between two resting segments; they fade as the thumb passes.
                for (edge in 1 until count) {
                    val distance = min(abs(edge - at), abs(edge - 1f - at)).coerceIn(0f, 1f)
                    if (distance > 0f) {
                        val x = inset + cell * edge
                        drawLine(separator.copy(alpha = separator.alpha * distance), Offset(x, size.height * .30f), Offset(x, size.height * .70f), 1.dp.toPx())
                    }
                }
                drawRoundRect(thumb, Offset(inset + cell * at, inset), Size(cell, size.height - inset * 2f), CornerRadius(radius, radius))
            }
            .padding(3.dp)
            .selectableGroup(),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            val source = remember { MutableInteractionSource() }
            val color by animateColorAsState(
                when {
                    !active -> c.t2
                    style == HomeSegmentStyle.Solid -> c.onAccent
                    style == HomeSegmentStyle.Raised -> c.t1
                    else -> c.accent
                },
                HomeMotion.fade(motion), label = "home-seg-text",
            )
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = height - 6.dp)
                    .selectable(selected = active, interactionSource = source, indication = null, enabled = enabled, role = Role.RadioButton) {
                        if (!active) { haptics(HomeHaptic.Tick); onSelect(value) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    ht(label), color = color,
                    style = if (active) textStyle.copy(fontWeight = FontWeight.Bold) else textStyle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Notice                                                              */
/* ------------------------------------------------------------------ */

/** Inline strip of the concept: 「设置已修改，重启后生效」, form results. One tone, optional action. */
@Composable
internal fun HomeNotice(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: HomeTone = HomeTone.Warn,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    filled: Boolean = true,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (filled) 44.dp else 28.dp)
            .clip(HomeDims.controlShape)
            .let { if (filled) it.background(tone.soft()) else it }
            .padding(start = if (filled) 14.dp else 4.dp, end = if (actionLabel == null) 14.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = tone.foreground())
        Spacer(Modifier.width(8.dp))
        Text(text, Modifier.weight(1f).padding(vertical = 6.dp), color = tone.text(), style = HomeType.noteStrong)
        if (actionLabel != null && onAction != null) {
            Box(
                Modifier.heightIn(min = 40.dp).clip(HomeDims.badgeShape).homeTap(role = Role.Button) { haptics(HomeHaptic.Tap); onAction() }.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(ht(actionLabel), color = c.accent, style = HomeType.noteStrong.copy(fontWeight = FontWeight.Bold), maxLines = 1) }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Rows                                                                */
/* ------------------------------------------------------------------ */

/**
 * Label and value on one line. With [labelWidth] the value starts in a fixed column (IP details);
 * without it the value hugs the right edge (resource details). Unknown values recede.
 */
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
        modifier.fillMaxWidth().heightIn(min = HomeDims.rowMinHeightSmall).padding(start = HomeDims.cardPadding, end = if (trailing == null) HomeDims.cardPadding else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            if (labelWidth != null) Modifier.width(labelWidth) else Modifier.weight(1f),
            color = c.t2, style = HomeType.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (labelWidth != null) Spacer(Modifier.width(12.dp))
        Text(
            value,
            (if (labelWidth != null) Modifier.weight(1f) else Modifier.widthIn(max = 220.dp)).padding(vertical = 8.dp),
            color = if (unknown) c.t3 else c.t1,
            style = if (unknown) HomeType.value.copy(fontWeight = FontWeight.Medium) else HomeType.value,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = if (labelWidth != null) TextAlign.Start else TextAlign.End,
        )
        if (trailing != null) trailing()
    }
}

/* ------------------------------------------------------------------ */
/*  Bars and page scaffold                                              */
/* ------------------------------------------------------------------ */

/**
 * Glass state of a pinned bar: how far it has faded in, and the blur source when the user's
 * 「模糊效果」switch is on. The blur source is attached to the page only while the bar is
 * actually visible, so a page resting at the top pays nothing for it.
 */
@Stable
internal class HomeBarGlass internal constructor(
    internal val haze: HazeState?,
    private val progress: State<Float>,
    private val activeState: State<Boolean>,
) {
    /** 0 at the top of the page, 1 once content has scrolled beneath the bar. Read in draw. */
    val shown: Float get() = progress.value
    val active: Boolean get() = activeState.value
}

@Composable
internal fun rememberHomeBarGlass(lifted: Boolean): HomeBarGlass {
    val haze = if (LocalHomeBlur.current) rememberHazeState() else null
    val progress = animateFloatAsState(if (lifted) 1f else 0f, HomeMotion.fade(LocalHomeMotionEnabled.current, 220), label = "home-bar-glass")
    val active = remember(lifted) { derivedStateOf { lifted || progress.value > .01f } }
    return remember(haze, progress, active) { HomeBarGlass(haze, progress, active) }
}

/** Put this on the scrolling content that passes beneath the bar. */
internal fun Modifier.homeGlassSource(glass: HomeBarGlass): Modifier {
    val haze = glass.haze
    return if (haze != null && glass.active) hazeSource(haze) else this
}

/**
 * Background of a pinned bar. Invisible at the top of the page; once content scrolls beneath it
 * the bar turns to frosted glass (real blur when allowed, a near-opaque canvas tint otherwise)
 * with a hairline along its lower edge.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun HomeBarBackdrop(glass: HomeBarGlass, modifier: Modifier = Modifier) {
    if (!glass.active) return
    val c = LocalHomeColors.current
    val line = c.line
    val haze = glass.haze
    val surface = if (haze != null) {
        Modifier
            .hazeEffect(state = haze, style = HazeMaterials.ultraThin()) {
                blurRadius = 26.dp
                noiseFactor = .008f
            }
            .background(Brush.verticalGradient(listOf(c.bg.copy(alpha = .52f), c.bg.copy(alpha = .30f))))
    } else Modifier.background(c.bg.copy(alpha = .965f))
    Box(
        modifier
            .graphicsLayer { alpha = glass.shown }
            .then(surface)
            .drawBehind { drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) },
    )
}

/** Sub-page bar content: back chevron, centred title with optional subtitle, trailing actions. Transparent. */
@Composable
internal fun HomeTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = LocalHomeColors.current
    Box(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).heightIn(min = HomeDims.barHeight)) {
        Row(Modifier.fillMaxWidth().height(HomeDims.barHeight).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            HomeIconButton(HomeIcons.ChevronLeft, "返回", onBack, glyph = 26.dp)
            Spacer(Modifier.weight(1f))
            actions()
        }
        // The title stays on the bar's centre line; a subtitle hangs just below it.
        Column(Modifier.align(Alignment.TopCenter).padding(horizontal = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.height(HomeDims.barHeight), contentAlignment = Alignment.Center) {
                Text(ht(title), color = c.t1, style = HomeType.barTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (subtitle != null) Text(ht(subtitle), Modifier.offset(y = (-12).dp), color = c.t2, style = HomeType.barSubtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Full-screen sub-page: scrolling content under a glass bar, optional pinned footer.
 * Content gets the standard gutters and the 14 dp rhythm between cards.
 */
@Composable
internal fun HomeSubPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalHomeColors.current
    val scroll = rememberScrollState()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val barHeight = HomeDims.barHeight + if (subtitle != null) 12.dp else 0.dp
    val lifted by remember(scroll) { derivedStateOf { scroll.value > 6 } }
    val glass = rememberHomeBarGlass(lifted)
    Column(modifier.fillMaxSize().background(c.bg).imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .homeGlassSource(glass)
                    .verticalScroll(scroll)
                    .let { if (footer == null) it.windowInsetsPadding(WindowInsets.navigationBars) else it }
                    .padding(start = HomeDims.gutter, end = HomeDims.gutter, top = statusTop + barHeight + 6.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(HomeDims.gap),
                content = content,
            )
            HomeBarBackdrop(glass, Modifier.fillMaxWidth().height(statusTop + barHeight))
            HomeTopBar(title, onBack, subtitle = subtitle, actions = actions)
        }
        if (footer != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = HomeDims.gutter, end = HomeDims.gutter, top = 8.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = footer,
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Text field                                                          */
/* ------------------------------------------------------------------ */

/** Label above a 52 dp outlined field. The outline thickens and turns accent on focus, red on error. */
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
    val motion = LocalHomeMotionEnabled.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val outline by animateColorAsState(
        when { isError -> c.bad; focused -> c.accent; else -> c.line2 },
        HomeMotion.fade(motion), label = "home-field-outline",
    )
    val textStyle = (if (monospace) HomeType.mono.copy(fontSize = 15.sp, lineHeight = 22.sp) else HomeType.body).copy(color = c.t1)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(ht(label), Modifier.padding(horizontal = 2.dp), color = c.t2, style = HomeType.note.copy(fontWeight = FontWeight.Medium))
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
                        .heightIn(min = 52.dp)
                        .clip(HomeDims.controlShape)
                        .background(c.surface)
                        .border(if (focused || isError) 1.5.dp else 1.dp, outline, HomeDims.controlShape)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) Text(ht(placeholder), color = c.t3, style = textStyle.copy(color = c.t3), maxLines = 1)
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
 * Smooth area chart. Null samples break the curve (missed samples are never interpolated).
 * The line draws itself in from the left once per [reveal] run and ends in a small live dot.
 *
 * @param max top of the value axis; the curve keeps a little head room.
 * @param reveal 0 → 1 progress of the entrance, read in the draw phase.
 */
@Composable
internal fun HomeSparkline(
    values: List<Float?>,
    color: Color,
    max: Float,
    modifier: Modifier = Modifier,
    height: Dp = 76.dp,
    fill: Boolean = true,
    grid: Boolean = false,
    reveal: () -> Float = { 1f },
) {
    val gridColor = LocalHomeColors.current.line
    val surface = LocalHomeColors.current.surface
    Canvas(modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val h = size.height
        if (grid) for (row in 1..3) {
            val y = h * row / 4f
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx())
        }
        if (values.size < 2 || max <= 0f) return@Canvas
        val head = 8.dp.toPx()
        val foot = 3.dp.toPx()
        val step = w / (values.size - 1)
        val segments = mutableListOf<MutableList<Offset>>()
        var current = mutableListOf<Offset>()
        values.forEachIndexed { index, value ->
            if (value == null) {
                if (current.isNotEmpty()) segments.add(current)
                current = mutableListOf()
            } else {
                current.add(Offset(index * step, h - foot - (value / max).coerceIn(0f, 1f) * (h - head - foot)))
            }
        }
        if (current.isNotEmpty()) segments.add(current)
        val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val shown = reveal().coerceIn(0f, 1f)
        clipRect(right = w * shown + 4.dp.toPx()) {
            for (points in segments) {
                if (points.size < 2) {
                    points.firstOrNull()?.let { drawCircle(color, 1.5.dp.toPx(), it) }
                    continue
                }
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
                    drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = color.alpha * .26f), color.copy(alpha = 0f)), startY = 0f, endY = h))
                }
                drawPath(line, color, style = stroke)
            }
        }
        // Live end point, once the entrance has finished and the newest sample exists.
        val last = segments.lastOrNull()?.lastOrNull()
        if (shown >= 1f && last != null && values.last() != null) {
            drawCircle(surface, 4.5.dp.toPx(), last)
            drawCircle(color, 3.dp.toPx(), last)
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Bottom sheet content frame                                          */
/* ------------------------------------------------------------------ */

/**
 * The inside of a bottom sheet: grab handle, title row, scrolling body, optional pinned footer.
 * Kept free of ModalBottomSheet so the same content renders in static previews.
 * @param verbatimTitle true when [title] is data (a host name) rather than UI text.
 */
@Composable
internal fun HomeSheetContent(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClose: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    verbatimTitle: Boolean = false,
    body: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalHomeColors.current
    Column(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).imePadding()) {
        Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(40.dp, 5.dp).background(if (c.dark) c.line2 else Color(0xFFC9CCDA), HomeDims.pillShape))
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).heightIn(min = HomeDims.touch), verticalArrangement = Arrangement.Center) {
                Text(if (verbatimTitle) title else ht(title), color = c.t1, style = HomeType.sheetTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(ht(subtitle), Modifier.padding(top = 3.dp), color = c.t2, style = HomeType.rowSub)
            }
            when {
                trailing != null -> trailing()
                onClose != null -> HomeIconButton(HomeIcons.X, "关闭", onClose)
            }
        }
        Column(
            Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, bottom = if (footer == null) 22.dp else 12.dp),
            content = body,
        )
        if (footer != null) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), content = footer)
        }
    }
}

/** Tinted group inside a sheet: the concept's rounded blocks that hold a few related rows. */
@Composable
internal fun HomeSheetGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalHomeColors.current
    Column(modifier.fillMaxWidth().clip(HomeDims.innerShape).background(if (c.dark) c.sunken else c.bg), content = content)
}

/** Monospace block used by the failure sheet and other read-only text. */
@Composable
internal fun HomeCodeBox(text: String, modifier: Modifier = Modifier, caption: String? = null) {
    val c = LocalHomeColors.current
    HomeSheetGroup(modifier) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (caption != null) Text(ht(caption), color = c.t3, style = HomeType.caption)
            Text(text, color = c.t1, style = HomeType.body.copy(lineHeight = 26.sp))
        }
    }
}

/**
 * Centred placeholder: artwork that floats gently, a title, one line of help, optional action.
 * @param verbatimSubtitle true when [subtitle] is data (an error from the core) rather than UI text.
 */
@Composable
internal fun HomeEmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    tint: Color = LocalHomeColors.current.t3,
    topPadding: Dp = 72.dp,
    titleStyle: TextStyle = HomeType.sheetTitle,
    verbatimSubtitle: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val c = LocalHomeColors.current
    val reveal = rememberHomeReveal(title, 360)
    Column(
        modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = topPadding, bottom = 40.dp).graphicsLayer {
            val p = reveal()
            alpha = p
            translationY = (1f - p) * 10.dp.toPx()
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(64.dp).homeFloat(), tint = tint)
        Spacer(Modifier.height(18.dp))
        Text(ht(title), color = c.t1, style = titleStyle, textAlign = TextAlign.Center)
        // Diagnostics from the core are shown as they came, never run through the vocabulary.
        Text(if (verbatimSubtitle) subtitle else ht(subtitle), Modifier.padding(top = 6.dp), color = c.t2, style = HomeType.body, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(20.dp)); action() }
    }
}
