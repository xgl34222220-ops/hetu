package io.github.xgl34222220.hetu.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.xgl34222220.hetu.ui.ht

/* ------------------------------------------------------------------ */
/*  List rows: the one row every 工具 and 设置 page is built from       */
/* ------------------------------------------------------------------ */

/**
 * Row metrics measured from the 03 and 04 concepts: a 26 dp line icon 18 dp from the card
 * edge, the text column 68 dp in, 74 dp for a two-line row and 60 dp for a single line.
 */
internal object HomeRowDims {
    val icon = 26.dp
    val start = 18.dp
    val iconGap = 24.dp
    val end = 16.dp
    val twoLine = 74.dp
    val oneLine = 60.dp
    val compact = 54.dp
    /** Where the text column starts when the row has an icon. */
    val textStart = start + icon + iconGap
}

internal val HomeRowSubStyle = TextStyle(fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)

/**
 * Pressed and selected feedback of a row: a rounded tint that sits inside the card's corners,
 * fading in at once and out slowly. Drawn behind the content, so nothing is laid out again.
 */
@Composable
internal fun Modifier.homeRowHighlight(source: InteractionSource, selected: Boolean = false): Modifier {
    val c = LocalHomeColors.current
    val motion = LocalHomeMotionEnabled.current
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(
        if (pressed) 1f else 0f,
        if (!motion) HomeMotion.fade(false) else tween(if (pressed) 70 else 280, easing = HomeMotion.Emphasized),
        label = "home-row-press",
    )
    val chosen by animateFloatAsState(if (selected) 1f else 0f, HomeMotion.fade(motion, 220), label = "home-row-selected")
    val pressTint = c.t1.copy(alpha = if (c.dark) .09f else .055f)
    val chosenTint = c.accentSoft
    return drawBehind {
        val insetX = 6.dp.toPx()
        val insetY = 3.dp.toPx()
        val area = Size(size.width - insetX * 2f, size.height - insetY * 2f)
        if (area.width <= 0f || area.height <= 0f) return@drawBehind
        val radius = CornerRadius(18.dp.toPx(), 18.dp.toPx())
        if (chosen > 0f) drawRoundRect(chosenTint.copy(alpha = chosenTint.alpha * chosen), Offset(insetX, insetY), area, radius)
        if (press > 0f) drawRoundRect(pressTint.copy(alpha = pressTint.alpha * press), Offset(insetX, insetY), area, radius)
    }
}

/**
 * Layout of a row without any behaviour: optional leading glyph, title with an optional second
 * line, trailing slot. [HomeListRow] and [HomeSwitchRow] add the interaction; pages with their
 * own gesture (long press, menu anchor) wrap this directly.
 *
 * Titles and subtitles are drawn as given; callers resolve UI text through `ht` themselves, so
 * file names, hosts and other user content are never translated by accident.
 */
@Composable
internal fun HomeRowLayout(
    title: AnnotatedString,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = LocalHomeColors.current.t1,
    titleColor: Color = LocalHomeColors.current.t1,
    subtitleColor: Color = LocalHomeColors.current.t2,
    titleStyle: TextStyle = HomeType.rowTitle,
    subtitleStyle: TextStyle = HomeRowSubStyle,
    titleMaxLines: Int = 1,
    subtitleMaxLines: Int = 2,
    enabled: Boolean = true,
    minHeight: Dp? = null,
    startPadding: Dp = HomeRowDims.start,
    endPadding: Dp = HomeRowDims.end,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val height = minHeight ?: if (subtitle == null) HomeRowDims.oneLine else HomeRowDims.twoLine
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .alpha(if (enabled) 1f else .45f)
            .padding(start = startPadding, end = endPadding, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leading != null -> { leading(); Spacer(Modifier.width(16.dp)) }
            icon != null -> {
                Icon(icon, null, Modifier.size(HomeRowDims.icon), tint = iconTint)
                Spacer(Modifier.width(HomeRowDims.iconGap))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = titleColor, style = titleStyle, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = subtitleColor, style = subtitleStyle, maxLines = subtitleMaxLines, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = trailing)
        }
    }
}

/** A [HomeRowLayout] that is a button when [onClick] is set and plain content otherwise. */
@Composable
internal fun HomeListRow(
    title: AnnotatedString,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = LocalHomeColors.current.t1,
    titleColor: Color = LocalHomeColors.current.t1,
    subtitleColor: Color = LocalHomeColors.current.t2,
    titleStyle: TextStyle = HomeType.rowTitle,
    subtitleStyle: TextStyle = HomeRowSubStyle,
    titleMaxLines: Int = 1,
    subtitleMaxLines: Int = 2,
    enabled: Boolean = true,
    selected: Boolean = false,
    minHeight: Dp? = null,
    startPadding: Dp = HomeRowDims.start,
    endPadding: Dp = HomeRowDims.end,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    role: Role = Role.Button,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val haptics = LocalHomeHaptics.current
    val source = remember { MutableInteractionSource() }
    val interactive = if (onClick == null) modifier.homeRowHighlight(source, selected) else modifier
        .homeRowHighlight(source, selected)
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClickLabel = onClickLabel, role = role) {
            haptics(HomeHaptic.Tap)
            onClick()
        }
    HomeRowLayout(
        title, interactive, subtitle, icon, iconTint, titleColor, subtitleColor, titleStyle, subtitleStyle,
        titleMaxLines, subtitleMaxLines, enabled, minHeight, startPadding, endPadding, leading, trailing,
    )
}

/**
 * A row that is one labelled switch: the whole row toggles and is announced once, with its
 * title as the label. The 50 × 30 dp switch at the end is only the picture of the state.
 */
@Composable
internal fun HomeSwitchRow(
    title: AnnotatedString,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = LocalHomeColors.current.t1,
    subtitleStyle: TextStyle = HomeRowSubStyle,
    subtitleMaxLines: Int = 2,
    enabled: Boolean = true,
    minHeight: Dp? = null,
    startPadding: Dp = HomeRowDims.start,
    endPadding: Dp = HomeRowDims.end,
    leading: (@Composable () -> Unit)? = null,
) {
    val haptics = LocalHomeHaptics.current
    val source = remember { MutableInteractionSource() }
    HomeRowLayout(
        title,
        modifier
            .homeRowHighlight(source)
            .toggleable(value = checked, interactionSource = source, indication = null, enabled = enabled, role = Role.Switch) { on ->
                haptics(HomeHaptic.Tick)
                onCheckedChange(on)
            },
        subtitle = subtitle, icon = icon, iconTint = iconTint, subtitleStyle = subtitleStyle, subtitleMaxLines = subtitleMaxLines,
        enabled = enabled, minHeight = minHeight, startPadding = startPadding, endPadding = endPadding, leading = leading,
        trailing = { HomeSwitch(checked) },
    )
}

@Composable
internal fun HomeChevron(modifier: Modifier = Modifier, dropdown: Boolean = false) {
    Icon(if (dropdown) HomeIcons.ChevronsUpDown else HomeIcons.ChevronRight, null, modifier.size(20.dp), tint = LocalHomeColors.current.t3)
}

/** The current value at the end of a row (“Mihomo”, “3 秒”), followed by the row's chevron. */
@Composable
internal fun RowScope.HomeRowValue(value: String?, dropdown: Boolean = false, maxWidth: Dp = 150.dp) {
    val c = LocalHomeColors.current
    if (!value.isNullOrBlank()) {
        HomeRollingText(value, c.t2, HomeType.label.copy(fontWeight = FontWeight.Medium), Modifier.widthIn(max = maxWidth), alignment = Alignment.CenterEnd)
    }
    HomeChevron(dropdown = dropdown)
}

/** Heading of a card (“代理能力”, “规则源”), optionally with an action on the right. */
@Composable
internal fun HomeCardTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 54.dp).padding(start = HomeRowDims.start, end = if (trailing == null) HomeRowDims.end else 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f).semantics { heading() }, color = LocalHomeColors.current.t1, style = HomeType.section, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) trailing()
    }
}

/** Hairline between two rows of a card, starting under the text column. */
@Composable
internal fun HomeRowDivider(modifier: Modifier = Modifier, start: Dp = HomeRowDims.start, end: Dp = HomeRowDims.end) {
    Box(modifier.fillMaxWidth().padding(start = start, end = end).height(1.dp).background(LocalHomeColors.current.line))
}

/* ------------------------------------------------------------------ */
/*  Form field                                                          */
/* ------------------------------------------------------------------ */

/**
 * Labelled input of a form: the 52 dp outlined field of [HomeTextField] with the two lines a
 * form needs under it, a hint and a validation error. With [readOnly] the value is plain text.
 *
 * @param label and [hint] are UI text and go through `ht`; [error] is shown as given. An empty
 *   label leaves the field on its own, for a card whose title already names it.
 * @param singleLine false gives a taller box for a few lines of text (a notification template).
 * @param keyboardOptions overrides [keyboardType] when the caller needs more than the type.
 */
@Composable
internal fun HomeFormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    error: String? = null,
    hint: String? = null,
    monospace: Boolean = false,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    clearable: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    labelStyle: TextStyle = HomeType.cardLabel,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions? = null,
) {
    val c = LocalHomeColors.current
    val motion = LocalHomeMotionEnabled.current
    val haptics = LocalHomeHaptics.current
    Column(modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) {
            Text(ht(label), Modifier.padding(horizontal = 2.dp), color = c.t1, style = labelStyle)
            Spacer(Modifier.height(8.dp))
        }
        if (readOnly) {
            Text(value, Modifier.padding(horizontal = 2.dp), color = c.t1, style = HomeType.sheetTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            val source = remember { MutableInteractionSource() }
            val focused by source.collectIsFocusedAsState()
            val outline by animateColorAsState(
                when { error != null -> c.bad; focused -> c.accent; else -> c.line2 },
                HomeMotion.fade(motion), label = "home-form-outline",
            )
            val textStyle = HomeType.body.copy(
                color = if (enabled) c.t1 else c.t2,
                fontWeight = FontWeight.Medium,
                fontFamily = if (monospace && keyboardType != KeyboardType.Uri) FontFamily.Monospace else FontFamily.Default,
            )
            val clearLabel = ht("清除输入")
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                textStyle = textStyle,
                singleLine = singleLine,
                maxLines = if (singleLine) 1 else 6,
                keyboardOptions = keyboardOptions ?: KeyboardOptions(keyboardType = keyboardType),
                interactionSource = source,
                cursorBrush = SolidColor(c.accent),
                decorationBox = { inner ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = if (singleLine) 52.dp else 96.dp)
                            .clip(HomeDims.controlShape)
                            .background(if (enabled) c.surface else c.sunken)
                            .border(if (focused || error != null) 1.5.dp else 1.dp, outline, HomeDims.controlShape)
                            .padding(start = 14.dp, end = if (clearable && value.isNotEmpty()) 6.dp else 14.dp)
                            .padding(vertical = if (singleLine) 0.dp else 13.dp),
                        verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                    ) {
                        Box(Modifier.weight(1f), contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) {
                            if (value.isEmpty() && placeholder.isNotEmpty()) Text(ht(placeholder), color = c.t3, style = textStyle.copy(color = c.t3, fontWeight = FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            inner()
                        }
                        if (clearable && value.isNotEmpty()) {
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = clearLabel, role = Role.Button) { haptics(HomeHaptic.Tap); onValueChange("") },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(Modifier.size(20.dp).background(c.t3.copy(alpha = .26f), CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(HomeIcons.X, null, Modifier.size(12.dp), tint = c.t2)
                                }
                            }
                        }
                    }
                },
            )
        }
        // An empty error marks the field (red outline) without a line of its own.
        HomeReveal(!error.isNullOrEmpty()) {
            Row(Modifier.padding(start = 2.dp, end = 2.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(HomeIcons.CircleAlert, null, Modifier.size(16.dp), tint = c.bad)
                Text(error.orEmpty(), color = c.badText, style = HomeType.note.copy(fontWeight = FontWeight.Medium))
            }
        }
        if (hint != null) Text(ht(hint), Modifier.padding(start = 2.dp, end = 2.dp, top = 8.dp), color = c.t2, style = HomeType.note)
    }
}

/**
 * Search box of a page: a card-coloured pill with a leading glyph and a clear button that pops
 * in once there is text. It grows with the font instead of clipping it.
 *
 * @param placeholder UI text; goes through `ht`.
 * @param autoFocus take the keyboard as soon as the field appears.
 */
@Composable
internal fun HomeSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
    icon: ImageVector? = null,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val style = HomeType.body.copy(fontSize = 17.sp, color = c.t1, fontWeight = FontWeight.Medium)
    val clearLabel = ht("清除")
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().focusRequester(focus),
        textStyle = style,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        cursorBrush = SolidColor(c.accent),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(HomeDims.cardShape).background(c.surface).padding(start = HomeRowDims.start, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon ?: HomeSearchGlyph, null, Modifier.size(22.dp), tint = c.t2)
                Spacer(Modifier.width(14.dp))
                Box(Modifier.weight(1f).padding(vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(ht(placeholder), color = c.t3, style = style.copy(color = c.t3, fontWeight = FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
                Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                    HomePop(value.isNotEmpty()) {
                        Box(
                            Modifier.size(46.dp).clip(CircleShape).clickable(onClickLabel = clearLabel, role = Role.Button) { haptics(HomeHaptic.Tap); onValueChange("") }
                                .semantics { contentDescription = clearLabel },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(22.dp).background(c.t3.copy(alpha = .30f), CircleShape), contentAlignment = Alignment.Center) {
                                Icon(HomeIcons.X, null, Modifier.size(13.dp), tint = c.t2)
                            }
                        }
                    }
                }
            }
        },
    )
}

/** Lucide `search`; kept here so the field needs nothing from the tools or panel icon sets. */
private val HomeSearchGlyph: ImageVector by lazy {
    ImageVector.Builder(name = "HomeLucide.Search", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        listOf("m21 21-4.34-4.34", "M3 11a8 8 0 1 0 16 0a8 8 0 1 0 -16 0Z").forEach { data ->
            addPath(pathData = PathParser().parsePathString(data).toNodes(), fill = null, stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()
}

/* ------------------------------------------------------------------ */
/*  Dialog                                                              */
/* ------------------------------------------------------------------ */

/**
 * Dialog window whose card settles in with a short scale and fade. Back and outside taps call
 * [onDismiss]; the card itself comes from [HomeDialogCard] so it also renders in previews.
 */
@Composable
internal fun HomeDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val reveal = rememberHomeReveal(Unit, 260)
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp).imePadding().graphicsLayer {
                val p = reveal()
                alpha = p
                val scale = .93f + .07f * p
                scaleX = scale
                scaleY = scale
            },
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/** How the confirming button of a dialog reads: the accent, solid red, or a quiet red tint. */
internal enum class HomeDialogConfirm { Primary, Danger, DangerSoft }

/**
 * The card of a dialog: centred title, optional message and form content, then 取消 and the
 * confirming button. One shape for every confirmation in the app, whichever page it is on.
 *
 * [title] and [text] are drawn as given; the two button labels are UI text and go through `ht`.
 *
 * @param stacked puts the buttons on two rows, for a question that should not be answered in passing.
 */
@Composable
internal fun HomeDialogCard(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    icon: ImageVector? = null,
    iconTone: HomeTone = HomeTone.Accent,
    cancelLabel: String = "取消",
    confirm: HomeDialogConfirm = HomeDialogConfirm.Primary,
    confirmLoading: Boolean = false,
    confirmEnabled: Boolean = true,
    stacked: Boolean = false,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = LocalHomeColors.current
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier
            .widthIn(max = 340.dp)
            .fillMaxWidth()
            .homeGlassPanel(shape, c.raised, raised = true)
            .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 18.dp),
    ) {
        if (icon != null) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(52.dp).background(iconTone.soft(), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(26.dp), tint = iconTone.foreground())
            }
            Spacer(Modifier.height(14.dp))
        }
        Text(title, Modifier.fillMaxWidth().semantics { heading() }, color = c.t1, style = HomeType.sheetTitle, textAlign = TextAlign.Center)
        if (text != null) Text(text, Modifier.fillMaxWidth().padding(top = 10.dp), color = c.t2, style = HomeType.body.copy(fontSize = 15.sp, lineHeight = 22.sp), textAlign = TextAlign.Center)
        if (content != null) Column(Modifier.fillMaxWidth().padding(top = 16.dp), content = content)
        Spacer(Modifier.height(22.dp))
        val confirmKind = if (confirm == HomeDialogConfirm.DangerSoft) HomeButtonKind.Soft else HomeButtonKind.Primary
        val danger = confirm != HomeDialogConfirm.Primary
        if (stacked) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeButton(confirmLabel, onConfirm, Modifier.fillMaxWidth(), kind = confirmKind, danger = danger, loading = confirmLoading, enabled = confirmEnabled)
                HomeButton(cancelLabel, onCancel, Modifier.fillMaxWidth(), kind = HomeButtonKind.Soft, enabled = !confirmLoading)
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeButton(cancelLabel, onCancel, Modifier.weight(1f), kind = HomeButtonKind.Soft, enabled = !confirmLoading)
                HomeButton(confirmLabel, onConfirm, Modifier.weight(1f), kind = confirmKind, danger = danger, loading = confirmLoading, enabled = confirmEnabled)
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Menus                                                               */
/* ------------------------------------------------------------------ */

/** Floating glass menu: 20 dp corners, an opaque readable fill and a pixel edge. */
@Composable
internal fun HomeMenuSurface(
    modifier: Modifier = Modifier,
    minWidth: Dp = 176.dp,
    maxWidth: Dp = 300.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalHomeColors.current
    Column(
        modifier
            .widthIn(min = minWidth, max = maxWidth)
            .width(IntrinsicSize.Max)
            .homeGlassPanel(HomeDims.menuShape, c.raised, raised = true)
            .padding(6.dp),
        content = content,
    )
}

/** Caption at the top of a menu: the thing the menu belongs to (a file name, a setting). */
@Composable
internal fun HomeMenuTitle(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = LocalHomeColors.current
    Row(modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = c.t2)
        Text(text, color = c.t2, style = HomeType.caption.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun HomeMenuDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).height(1.dp).background(LocalHomeColors.current.line))
}

/**
 * One line of a menu. [checked] null = an action; true or false = an option, which turns accent
 * and gets a check mark while it is the chosen one. [label] and [description] are drawn as given.
 */
@Composable
internal fun HomeMenuItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    description: String? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
    checked: Boolean? = null,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val source = remember { MutableInteractionSource() }
    val on = checked == true
    val tint = when { danger -> c.badText; on -> c.accent; else -> c.t1 }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .alpha(if (enabled) 1f else .4f)
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) c.accentSoft.copy(alpha = c.accentSoft.alpha * .7f) else Color.Transparent)
            .homeRowPressTint(source)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button) { haptics(HomeHaptic.Tick); onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp), tint = if (danger) c.bad else if (on) c.accent else c.t2)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, color = tint, style = HomeType.label.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!description.isNullOrBlank()) Text(description, Modifier.padding(top = 1.dp), color = c.t2, style = HomeType.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (checked != null) {
            Spacer(Modifier.width(14.dp))
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                HomePop(on) { Icon(HomeIcons.Check, null, Modifier.size(20.dp), tint = c.accent) }
            }
        }
    }
}

/** Full-bleed pressed tint for elements that are already clipped to their own shape. */
@Composable
internal fun Modifier.homeRowPressTint(source: InteractionSource): Modifier {
    val c = LocalHomeColors.current
    val motion = LocalHomeMotionEnabled.current
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(
        if (pressed) 1f else 0f,
        if (!motion) HomeMotion.fade(false) else tween(if (pressed) 70 else 260, easing = HomeMotion.Emphasized),
        label = "home-press-tint",
    )
    val tint = c.t1.copy(alpha = if (c.dark) .09f else .055f)
    return drawBehind { if (press > 0f) drawRect(tint.copy(alpha = tint.alpha * press)) }
}

/**
 * Entrance of a popup: grows out of [origin] with a touch of overshoot while it fades in.
 * The popup window itself is created by the caller.
 */
@Composable
internal fun HomePopIn(modifier: Modifier = Modifier, origin: TransformOrigin = TransformOrigin(1f, 0f), content: @Composable () -> Unit) {
    val motion = LocalHomeMotionEnabled.current
    val reveal = rememberHomeReveal(Unit, 220)
    val scale by animateFloatAsState(1f, HomeMotion.pop(motion), label = "home-pop-in")
    Box(modifier.graphicsLayer {
        val p = reveal()
        alpha = p
        val s = (.86f + .14f * p) * scale
        scaleX = s
        scaleY = s
        transformOrigin = origin
    }) { content() }
}

/* ------------------------------------------------------------------ */
/*  Page scaffolds                                                      */
/* ------------------------------------------------------------------ */

/**
 * Sub-page frame for content that scrolls under a glass bar. The page owns its scrolling;
 * [content] receives the height the bar covers so it can pad its first item, and the glass
 * state so it can mark what scrolls beneath the bar with `homeGlassSource`.
 *
 * @param lifted true once the content has scrolled away from the top.
 * @param verbatimTitle true when [title] is data (a file name) rather than UI text.
 * @param titleInset room kept free on each side of the centred title for the bar's buttons.
 */
@Composable
internal fun HomeBarScaffold(
    title: String,
    onBack: () -> Unit,
    lifted: Boolean,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    verbatimTitle: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    titleInset: Dp = 104.dp,
    content: @Composable BoxScope.(top: Dp, glass: HomeBarGlass) -> Unit,
) {
    val c = LocalHomeColors.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val barHeight = HomeDims.barHeight + if (subtitle != null) 14.dp else 0.dp
    val glass = rememberHomeBarGlass(lifted)
    Column(modifier.fillMaxSize().homeDiffuseCanvas().imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            content(statusTop + barHeight, glass)
            HomeBarBackdrop(glass, Modifier.fillMaxWidth().height(statusTop + barHeight))
            HomeBarContent(title, onBack, subtitle, verbatimTitle, titleInset, actions)
        }
        if (footer != null) {
            Column(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(start = HomeDims.gutter, end = HomeDims.gutter, top = 8.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = footer,
            )
        }
    }
}

/** Back chevron, centred title with an optional line under it, trailing actions. Transparent. */
@Composable
internal fun HomeBarContent(
    title: String,
    onBack: (() -> Unit)?,
    subtitle: String? = null,
    verbatimTitle: Boolean = false,
    titleInset: Dp = 104.dp,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = LocalHomeColors.current
    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).heightIn(min = HomeDims.barHeight)) {
        Row(Modifier.fillMaxWidth().height(HomeDims.barHeight).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) HomeIconButton(HomeIcons.ChevronLeft, "返回", onBack, glyph = 26.dp)
            Spacer(Modifier.weight(1f))
            actions()
        }
        Column(Modifier.align(Alignment.TopCenter).padding(horizontal = titleInset), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.height(if (subtitle == null) HomeDims.barHeight else HomeDims.barHeight - 8.dp), contentAlignment = if (subtitle == null) Alignment.Center else Alignment.BottomCenter) {
                Text(if (verbatimTitle) title else ht(title), color = c.t1, style = HomeType.barTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (subtitle != null) {
            Text(
                ht(subtitle), Modifier.align(Alignment.TopCenter).padding(top = HomeDims.barHeight - 6.dp, start = 28.dp, end = 28.dp),
                color = c.t2, style = HomeType.barSubtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Top of a tab root (工具, 设置): the 36 sp title on the left with the page's actions pinned at
 * the right. As the page scrolls the large title slides up and fades while a compact one appears
 * centred in the glass bar; the actions never move.
 *
 * @param collapse 0 with the large title in place, 1 once it has scrolled away. Read in draw.
 * @param largeTitleHeight the height the caller reserved for the large title in its content.
 */
@Composable
internal fun BoxScope.HomeLargeTitleBar(
    title: String,
    collapse: () -> Float,
    glass: HomeBarGlass,
    largeTitleHeight: Dp,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = LocalHomeColors.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    HomeBarBackdrop(glass, Modifier.fillMaxWidth().height(statusTop + HomeLargeTitleDims.bar))
    // Large title: follows the content up, never intercepts touches.
    Text(
        ht(title),
        Modifier
            .align(Alignment.TopStart)
            .padding(top = statusTop + HomeLargeTitleDims.titleTop, start = HomeLargeTitleDims.inset, end = 72.dp)
            .semantics { heading() }
            .graphicsLayer {
                val p = collapse().coerceIn(0f, 1f)
                translationY = -p * largeTitleHeight.toPx()
                alpha = (1f - p * 1.6f).coerceIn(0f, 1f)
                val s = 1f - .06f * p
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0f, 1f)
            },
        color = c.t1, style = HomeType.largeTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
    Box(Modifier.fillMaxWidth().padding(top = statusTop).height(HomeLargeTitleDims.bar)) {
        Text(
            ht(title),
            // The same words as the large title: drawn for the eye, announced once.
            Modifier.align(Alignment.Center).padding(horizontal = 104.dp).clearAndSetSemantics { }.graphicsLayer {
                val p = ((collapse() - .55f) / .45f).coerceIn(0f, 1f)
                alpha = p
                translationY = (1f - p) * 8.dp.toPx()
            },
            color = c.t1, style = HomeType.barTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

internal object HomeLargeTitleDims {
    /** Pinned row with the page's actions; the compact title lands in it. */
    val bar = 56.dp
    /** Where the large title starts below the status bar: it shares its first lines with the action row. */
    val titleTop = 38.dp
    /** The concept sets the large title 30 dp from the screen edge. */
    val inset = 30.dp
    /** Space the large title takes in the scrolling content at font scale 1. */
    val title = 62.dp
}

/** Padding for a tab root's scrolling content: clears the action row, the large title and the dock. */
@Composable
internal fun homeLargeTitlePadding(contentPadding: PaddingValues, largeTitleHeight: Dp): PaddingValues {
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    return PaddingValues(
        start = HomeDims.gutter, end = HomeDims.gutter,
        top = statusTop + HomeLargeTitleDims.titleTop + largeTitleHeight + contentPadding.calculateTopPadding(),
        bottom = contentPadding.calculateBottomPadding(),
    )
}
