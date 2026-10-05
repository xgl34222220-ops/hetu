package io.github.xgl34222220.hetu.tools

import io.github.xgl34222220.hetu.ui.ht
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import io.github.xgl34222220.hetu.hxPressScale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.xgl34222220.hetu.tools.ToolsButton as HomeButton
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.tools.ToolsDesignDims as HomeDims
import io.github.xgl34222220.hetu.home.HomeHaptic
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeSpinner
import io.github.xgl34222220.hetu.home.HomeTextField
import io.github.xgl34222220.hetu.tools.ToolsTopBar as HomeTopBar
import io.github.xgl34222220.hetu.tools.ToolsTypography as HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeHaptics

/* ------------------------------------------------------------------ */
/*  Tools-only tokens (everything else comes from HomeTokens)           */
/* ------------------------------------------------------------------ */

internal object ToolsDims {
    val dialogShape = RoundedCornerShape(20.dp)
    val menuItemShape = RoundedCornerShape(10.dp)
    val keyShape = RoundedCornerShape(8.dp)
    val dialogMaxWidth = 288.dp
    val menuMinWidth = 154.dp
    val menuMaxWidth = 280.dp
}

internal object ToolsType {
    val dialogTitle = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
    val dialogText = TextStyle(fontSize = 14.sp, lineHeight = 21.sp)
    val menuItem = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    val emptyTitle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val readOnlyValue = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    val url = HomeType.rowSub.copy(fontSize = 12.sp, lineHeight = 18.sp)
}

/* ------------------------------------------------------------------ */
/*  Page frame                                                          */
/* ------------------------------------------------------------------ */

/** Sub-page frame: home top bar over a scrolling column with the standard 16 dp gutter. */
@Composable
internal fun ToolsPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalHomeColors.current
    Column(modifier.fillMaxSize().background(c.bg)) {
        HomeTopBar(title = title, onBack = onBack, subtitle = subtitle, actions = actions)
        ToolsPullRefresh(refreshing, onRefresh, Modifier.fillMaxSize()) {
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
}

/** Secondary sentence under a top bar. */
@Composable
internal fun ToolsLead(text: String, modifier: Modifier = Modifier) {
    Text(ht(text), modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp), color = LocalHomeColors.current.t2, style = HomeType.note)
}

/** Tertiary footnote, optionally led by a 14 dp info glyph. */
@Composable
internal fun ToolsNote(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = LocalHomeColors.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) Icon(icon, null, Modifier.padding(top = 1.dp).size(14.dp), tint = c.t3)
        Text(ht(text), color = c.t3, style = HomeType.caption)
    }
}

/* ------------------------------------------------------------------ */
/*  Rows                                                                */
/* ------------------------------------------------------------------ */

/** Title row at the top of a card: icon, title, optional one-line caption and a trailing action. */
@Composable
internal fun ToolsCardHeader(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = LocalHomeColors.current
    Row(
        modifier.fillMaxWidth().heightIn(min = HomeDims.rowMinHeightSmall).padding(start = 16.dp, end = if (trailing == null) 16.dp else 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(26.dp), tint = c.t1)
        Column(Modifier.weight(1f)) {
            Text(ht(title), color = c.t1, style = HomeType.rowTitle, maxLines = 1)
            if (caption != null) Text(ht(caption), color = c.t3, style = HomeType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing()
    }
}

/**
 * List row: 20 dp leading icon, title with an optional second line, trailing slot.
 * 56 dp tall by default, 48 dp when [compact].
 *
 * @param title an [AnnotatedString] so search hits can be tinted.
 * @param selected paints the accent-soft fill used for the current config.
 */
/** Titles/subtitles here are raw by default; UI callers explicitly pass ht-resolved labels. */
@Composable
internal fun ToolsRow(
    title: AnnotatedString,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    subtitleStyle: TextStyle = HomeType.rowSub,
    subtitleColor: Color = LocalHomeColors.current.t2,
    titleColor: Color = LocalHomeColors.current.t1,
    iconTint: Color = LocalHomeColors.current.t2,
    selected: Boolean = false,
    compact: Boolean = false,
    enabled: Boolean = true,
    endPadding: Dp = 16.dp,
    subtitleMaxLines: Int = 2,
    onClick: (() -> Unit)? = null,
    gap: Dp = 18.dp,
    minHeight: Dp? = null,
    verticalPadding: Dp? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val base = modifier.fillMaxWidth().then(if (selected) Modifier.clip(HomeDims.controlShape) else Modifier).background(if (selected) c.accentSoft else Color.Transparent)
    val source = remember { MutableInteractionSource() }
    val interactive = if (onClick == null) base else base.hxPressScale(source, .985f).clickable(enabled = enabled, interactionSource = source, indication = null, role = Role.Button) { haptics(HomeHaptic.Tap); onClick() }
    Row(
        interactive
            .heightIn(min = minHeight ?: if (compact) HomeDims.rowMinHeightSmall else HomeDims.rowMinHeight)
            .padding(start = 16.dp, end = endPadding, top = verticalPadding ?: 8.dp, bottom = verticalPadding ?: 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(26.dp), tint = iconTint)
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor, style = HomeType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = subtitleColor, style = subtitleStyle, maxLines = subtitleMaxLines, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { trailing() }
    }
}

@Composable
internal fun ToolsChevron(modifier: Modifier = Modifier) {
    Icon(HomeIcons.ChevronRight, null, modifier.size(20.dp), tint = LocalHomeColors.current.t3)
}

/** Solid accent disc with a check: marks the current config. */
@Composable
internal fun ToolsCheckBadge(modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    Box(modifier.size(22.dp).background(c.accent, CircleShape).semantics { contentDescription = "当前配置" }, contentAlignment = Alignment.Center) {
        Icon(HomeIcons.Check, null, Modifier.size(12.dp), tint = c.onAccent)
    }
}

/** [text] with the characters in [range] tinted accent. */
@Composable
internal fun toolsHighlighted(text: String, range: IntRange?): AnnotatedString {
    val accent = LocalHomeColors.current.accent
    return remember(text, range, accent) {
        val builder = AnnotatedString.Builder(text)
        if (range != null && range.first >= 0 && range.last < text.length) {
            builder.addStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold), range.first, range.last + 1)
        }
        builder.toAnnotatedString()
    }
}

/* ------------------------------------------------------------------ */
/*  Fields                                                              */
/* ------------------------------------------------------------------ */

/**
 * Form field: the home text field plus the two lines the home module did not need, an error
 * (red, with icon) and a hint (tertiary). [readOnly] renders the value as plain 17 sp text.
 */
@Composable
internal fun ToolsField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    error: String? = null,
    hint: String? = null,
    monospace: Boolean = false,
    readOnly: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    compact: Boolean = false,
    clearable: Boolean = false,
    errorAfterHint: Boolean = false,
    readOnlyValueStyle: TextStyle? = null,
    hintStyle: TextStyle? = null,
    inputMinHeight: Dp? = null,
    inputVerticalPadding: Dp? = null,
    errorStyle: TextStyle? = null,
    errorSpacing: Dp? = null,
) {
    val c = LocalHomeColors.current
    Column(modifier.fillMaxWidth(), verticalArrangement = if (errorSpacing == null) Arrangement.spacedBy(8.dp) else Arrangement.Top) {
        if (readOnly) {
            Text(ht(label), Modifier.padding(horizontal = 2.dp), color = c.t1, style = HomeType.label.copy(fontWeight = FontWeight.SemiBold))
            if (errorSpacing != null) Spacer(Modifier.height(8.dp))
            Text(value, Modifier.padding(horizontal = 2.dp), color = c.t1, style = readOnlyValueStyle ?: ToolsType.readOnlyValue, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Text(ht(label), color = c.t1, style = if (compact) HomeType.caption else HomeType.label.copy(fontWeight = FontWeight.SemiBold))
            if (errorSpacing != null) Spacer(Modifier.height(8.dp))
            BasicTextField(
                value = value, onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                textStyle = HomeType.body.copy(color = c.t1, fontWeight = FontWeight.Medium,
                    fontFamily = if (monospace && keyboardType != KeyboardType.Uri) FontFamily.Monospace else FontFamily.Default),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType), cursorBrush = SolidColor(c.accent),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().heightIn(min = inputMinHeight ?: if (compact) 42.dp else 48.dp)
                        .clip(HomeDims.controlShape).background(c.sunken)
                        .padding(horizontal = 14.dp, vertical = inputVerticalPadding ?: 10.dp), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(ht(placeholder), color = c.t3, style = HomeType.body)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { inner() }
                            if (clearable && value.isNotEmpty()) Box(Modifier.size(20.dp).clip(CircleShape)
                                .background(c.t3.copy(alpha = .14f)).clickable(role = Role.Button, onClickLabel = ht("清除输入")) { onValueChange("") },
                                contentAlignment = Alignment.Center) { Icon(HomeIcons.X, null, Modifier.size(12.dp), tint = c.t3) }
                        }
                    }
                },
            )
        }
        if (errorAfterHint && hint != null) {
            if (errorSpacing != null) Spacer(Modifier.height(8.dp))
            Text(ht(hint), Modifier.padding(horizontal = 2.dp), color = c.t3, style = hintStyle ?: HomeType.caption)
        }
        if (error != null) {
            if (errorSpacing != null) Spacer(Modifier.height(errorSpacing))
            Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(HomeIcons.CircleAlert, null, Modifier.size(13.dp), tint = c.bad)
                Text(error, color = c.bad, style = errorStyle ?: HomeType.caption)
            }
        }
        if (!errorAfterHint && hint != null) {
            if (errorSpacing != null) Spacer(Modifier.height(8.dp))
            Text(ht(hint), Modifier.padding(horizontal = 2.dp), color = c.t3, style = hintStyle ?: HomeType.caption)
        }
    }
}

/** Search-only surface pill with a leading glyph and a clear button; takes focus when it appears. */
@Composable
internal fun ToolsSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    val style = HomeType.body.copy(color = c.t1)
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
                Modifier.fillMaxWidth().height(HomeDims.touch).clip(CircleShape).background(c.surface).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(ToolsIcons.Search, null, Modifier.size(18.dp), tint = c.t3)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(ht(placeholder), color = c.t3, style = style.copy(color = c.t3), maxLines = 1)
                    inner()
                }
                if (value.isNotEmpty()) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).clickable(onClickLabel = clearLabel, role = Role.Button) { haptics(HomeHaptic.Tap); onValueChange("") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(18.dp).background(c.t3, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(HomeIcons.X, null, Modifier.size(12.dp), tint = Color.White)
                        }
                    }
                }
            }
        },
    )
}

/* ------------------------------------------------------------------ */
/*  Empty state                                                         */
/* ------------------------------------------------------------------ */

@Composable
internal fun ToolsEmpty(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconSize: Dp = 32.dp,
    action: (@Composable () -> Unit)? = null,
) {
    val c = LocalHomeColors.current
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.padding(bottom = 12.dp).size(iconSize), tint = c.t3)
        Text(ht(title), color = c.t1, style = ToolsType.emptyTitle, textAlign = TextAlign.Center)
        if (subtitle != null) Text(subtitle, Modifier.padding(top = 4.dp), color = c.t2, style = HomeType.note, textAlign = TextAlign.Center)
        if (action != null) Box(Modifier.padding(top = 16.dp)) { action() }
    }
}

/* ------------------------------------------------------------------ */
/*  Buttons the home module does not have                               */
/* ------------------------------------------------------------------ */

/** Destructive twin of `HomeButton`: solid red, or the soft red tint when [soft]. */
@Composable
internal fun ToolsDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    soft: Boolean = false,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val content = when {
        soft -> c.bad
        c.dark -> Color(0xFF1B0808)
        else -> Color.White
    }
    Row(
        modifier
            .height(HomeDims.touch)
            .alpha(if (enabled && !loading) 1f else .45f)
            .clip(HomeDims.controlShape)
            .background(if (soft) c.badSoft else c.bad)
            .clickable(enabled = enabled && !loading, role = Role.Button) { haptics(HomeHaptic.Confirm); onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (loading) HomeSpinner(size = 16.dp, color = content)
        Text(ht(text), color = content, style = HomeType.button, maxLines = 1)
    }
}

/* ------------------------------------------------------------------ */
/*  Dialog                                                              */
/* ------------------------------------------------------------------ */

internal enum class ToolsConfirmKind { Primary, Danger, DangerSoft }
internal enum class ToolsDialogActions { Filled, ConfigGrid, Stacked }

/**
 * The inside of a confirmation dialog: centred title and message, optional form content,
 * then 取消 and the confirm button side by side. Kept free of the Dialog window so the same
 * card renders in static previews; at runtime wrap it in [ToolsDialog].
 */
@Composable
internal fun ToolsDialogCard(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    confirmKind: ToolsConfirmKind = ToolsConfirmKind.Primary,
    actions: ToolsDialogActions = ToolsDialogActions.Filled,
    cancelLabel: String = "取消",
    confirmLoading: Boolean = false,
    neutralCancel: Boolean = false,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = LocalHomeColors.current
    Column(modifier.widthIn(max = ToolsDims.dialogMaxWidth).fillMaxWidth()
        .shadow(24.dp, ToolsDims.dialogShape).clip(ToolsDims.dialogShape).background(c.surface)) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp,
            bottom = if (actions == ToolsDialogActions.ConfigGrid) 20.dp else 0.dp)) {
            Text(ht(title), Modifier.fillMaxWidth(), color = c.t1, style = ToolsType.dialogTitle, textAlign = TextAlign.Center)
            if (text != null) Text(ht(text), Modifier.fillMaxWidth().padding(top = 8.dp), color = c.t2, style = ToolsType.dialogText, textAlign = TextAlign.Center)
            if (content != null) Column(Modifier.fillMaxWidth().padding(top = 16.dp), content = content)
        }
        if (actions == ToolsDialogActions.ConfigGrid) {
            ToolsHairline()
            Row(Modifier.fillMaxWidth().height(56.dp)) {
                @Composable fun action(label: String, tint: Color, callback: () -> Unit) {
                    Box(Modifier.weight(1f).fillMaxHeight().clickable(enabled = !confirmLoading, role = Role.Button, onClick = callback), contentAlignment = Alignment.Center) {
                        if (confirmLoading && label == confirmLabel) HomeSpinner(size = 18.dp, color = tint)
                        else Text(ht(label), color = tint, style = HomeType.button)
                    }
                }
                action(cancelLabel, if (content == null) c.accent else c.t2, onCancel)
                Box(Modifier.width(.5.dp).fillMaxHeight().background(c.line.copy(alpha = .5f)))
                action(confirmLabel, if (confirmKind == ToolsConfirmKind.Primary) c.accent else c.bad, onConfirm)
            }
        } else if (actions == ToolsDialogActions.Stacked) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeButton(cancelLabel, onCancel, Modifier.fillMaxWidth(), kind = HomeButtonKind.Soft, enabled = !confirmLoading)
                ToolsDangerButton(confirmLabel, onConfirm, Modifier.fillMaxWidth(), soft = confirmKind == ToolsConfirmKind.DangerSoft, loading = confirmLoading)
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeButton(cancelLabel, onCancel, Modifier.weight(1f), kind = HomeButtonKind.Soft, neutral = neutralCancel, enabled = !confirmLoading)
                when (confirmKind) {
                    ToolsConfirmKind.Primary -> HomeButton(confirmLabel, onConfirm, Modifier.weight(1f), kind = HomeButtonKind.Primary, loading = confirmLoading)
                    ToolsConfirmKind.Danger -> ToolsDangerButton(confirmLabel, onConfirm, Modifier.weight(1f), loading = confirmLoading)
                    ToolsConfirmKind.DangerSoft -> ToolsDangerButton(confirmLabel, onConfirm, Modifier.weight(1f), soft = true, loading = confirmLoading)
                }
            }
        }
    }
}

/** Dialog window around a [ToolsDialogCard]; back press and outside taps call [onDismiss]. */
@Composable
internal fun ToolsDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 28.dp), contentAlignment = Alignment.Center) { content() }
    }
}

/* ------------------------------------------------------------------ */
/*  Anchored menu                                                       */
/* ------------------------------------------------------------------ */

internal class ToolsMenuEntry(val label: String, val icon: ImageVector, val danger: Boolean = false, val onClick: () -> Unit)

/** The menu surface itself: optional caption line, then 40 dp items. Preview-friendly. */
@Composable
internal fun ToolsMenuCard(entries: List<ToolsMenuEntry>, modifier: Modifier = Modifier, title: String? = null, captionTitle: Boolean = false) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    Column(
        modifier
            .width(if (captionTitle) ToolsDims.menuMinWidth else ToolsDims.menuMinWidth + 10.dp)
            .shadow(16.dp, HomeDims.menuShape)
            .clip(HomeDims.menuShape)
            .background(c.surface)
            .border(1.dp, c.line, HomeDims.menuShape)
            .padding(4.dp),
    ) {
        if (title != null) {
            Text(title, Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp), color = if (captionTitle) c.t3 else c.t1, style = if (captionTitle) HomeType.caption else HomeType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        entries.forEach { entry ->
            val tint = if (entry.danger) c.bad else c.t1
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (captionTitle) 32.dp else 40.dp)
                    .clip(ToolsDims.menuItemShape)
                    .clickable(role = Role.Button) { haptics(HomeHaptic.Tap); entry.onClick() }
                    .padding(horizontal = 12.dp, vertical = if (captionTitle) 4.dp else 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(entry.icon, null, Modifier.size(17.dp), tint = if (entry.danger) c.bad else c.t2)
                Text(ht(entry.label), color = tint, style = ToolsType.menuItem, maxLines = 1)
            }
        }
    }
}

/**
 * Popup that hangs a [ToolsMenuCard] below the end edge of its parent box.
 * Place it inside the Box that wraps the «⋯» button.
 */
@Composable
internal fun ToolsMenuPopup(onDismiss: () -> Unit, offsetY: Int, reference: ToolsAppsMenuReference? = null, content: @Composable () -> Unit) {
    if (reference != null) {
        val c = LocalHomeColors.current
        val density = LocalDensity.current
        var menuSize by remember { mutableStateOf(IntSize.Zero) }
        val dismissSource = remember { MutableInteractionSource() }
        val dismissLabel = ht("关闭菜单")
        Popup(
            popupPositionProvider = ToolsAppsFullWindowPosition,
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true, clippingEnabled = false),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val windowWidth = with(density) { maxWidth.roundToPx() }
                val windowHeight = with(density) { maxHeight.roundToPx() }
                val margin = with(density) { 8.dp.roundToPx() }
                val endInset = with(density) { reference.endInset.roundToPx() }
                val left = (windowWidth - endInset - menuSize.width).coerceIn(margin, (windowWidth - menuSize.width - margin).coerceAtLeast(margin))
                val pointerHeight = with(density) { 7.dp.roundToPx() }
                val requestedTop = (reference.anchor?.top?.toInt() ?: 0) + offsetY - pointerHeight
                val top = requestedTop.coerceIn(margin, (windowHeight - menuSize.height - margin).coerceAtLeast(margin))
                val maxMenuWidth = (maxWidth - 16.dp).coerceAtLeast(1.dp)
                val availableHeight = with(density) { (windowHeight - top - margin).coerceAtLeast(1).toDp() }
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = reference.scrimAlpha))
                    .clickable(interactionSource = dismissSource, indication = null, onClickLabel = dismissLabel, onClick = onDismiss))
                Column(Modifier.offset { IntOffset(left, top) }.width(IntrinsicSize.Max)
                    .widthIn(max = maxMenuWidth).heightIn(max = availableHeight)
                    .onSizeChanged { menuSize = it }.verticalScroll(rememberScrollState())) {
                    Box(Modifier.fillMaxWidth().height(7.dp).drawBehind {
                        val edge = 7.dp.toPx()
                        val tip = ((reference.anchor?.center?.x ?: (left + size.width / 2f)) - left)
                            .coerceIn(edge, (size.width - edge).coerceAtLeast(edge))
                        val pointer = Path().apply {
                            moveTo(tip - edge, size.height)
                            lineTo(tip, 0f)
                            lineTo(tip + edge, size.height)
                            close()
                        }
                        drawPath(pointer, c.surface)
                    })
                    content()
                }
            }
        }
        return
    }
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, offsetY),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
        content = content,
    )
}

/** Opt-in presentation measured from PDF03A pages 29/30; other tools keep the legacy popup. */
internal data class ToolsAppsMenuReference(val anchor: Rect?, val endInset: Dp, val scrimAlpha: Float)

private object ToolsAppsFullWindowPosition : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = IntOffset.Zero
}
