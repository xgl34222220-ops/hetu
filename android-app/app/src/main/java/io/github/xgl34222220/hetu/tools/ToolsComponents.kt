package io.github.xgl34222220.hetu.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import io.github.xgl34222220.hetu.hxPressScale
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
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
    val dialogMaxWidth = 340.dp
    val menuMinWidth = 168.dp
    val menuMaxWidth = 280.dp
}

internal object ToolsType {
    val dialogTitle = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    val dialogText = TextStyle(fontSize = 14.sp, lineHeight = 21.sp)
    val menuItem = TextStyle(fontSize = 14.5.sp, lineHeight = 20.sp)
    val emptyTitle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val readOnlyValue = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    val url = HomeType.rowSub.copy(fontSize = 14.sp, lineHeight = 19.sp)
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
    Text(text, modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp), color = LocalHomeColors.current.t2, style = HomeType.note)
}

/** Tertiary footnote, optionally led by a 14 dp info glyph. */
@Composable
internal fun ToolsNote(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = LocalHomeColors.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) Icon(icon, null, Modifier.padding(top = 1.dp).size(14.dp), tint = c.t3)
        Text(text, color = c.t3, style = HomeType.caption)
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
            Text(title, color = c.t1, style = HomeType.section, maxLines = 1)
            if (caption != null) Text(caption, color = c.t3, style = HomeType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val base = modifier.fillMaxWidth().background(if (selected) c.accentSoft else Color.Transparent)
    val source = remember { MutableInteractionSource() }
    val interactive = if (onClick == null) base else base.hxPressScale(source, .985f).clickable(enabled = enabled, interactionSource = source, indication = null, role = Role.Button) { haptics(HomeHaptic.Tap); onClick() }
    Row(
        interactive
            .heightIn(min = if (compact) HomeDims.rowMinHeightSmall else HomeDims.rowMinHeight)
            .padding(start = 16.dp, end = endPadding, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(26.dp), tint = iconTint)
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor, style = HomeType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = subtitleColor, style = subtitleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing()
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
) {
    val c = LocalHomeColors.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (readOnly) {
            Text(label, Modifier.padding(horizontal = 2.dp), color = c.t2, style = HomeType.section)
            Text(value, Modifier.padding(horizontal = 2.dp), color = c.t1, style = ToolsType.readOnlyValue, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            HomeTextField(label, value, onValueChange, monospace = monospace, isError = error != null, keyboardType = keyboardType, placeholder = placeholder)
        }
        if (error != null) {
            Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(HomeIcons.CircleAlert, null, Modifier.size(13.dp), tint = c.bad)
                Text(error, color = c.bad, style = HomeType.caption)
            }
        }
        if (hint != null) Text(hint, Modifier.padding(horizontal = 2.dp), color = c.t3, style = HomeType.caption)
    }
}

/** 44 dp sunken search box with a leading glyph and a clear button; takes focus when it appears. */
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
                Modifier.fillMaxWidth().height(HomeDims.touch).clip(HomeDims.controlShape).background(c.sunken).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(ToolsIcons.Search, null, Modifier.size(18.dp), tint = c.t3)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, color = c.t3, style = style.copy(color = c.t3), maxLines = 1)
                    inner()
                }
                if (value.isNotEmpty()) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).clickable(onClickLabel = "清除", role = Role.Button) { haptics(HomeHaptic.Tap); onValueChange("") },
                        contentAlignment = Alignment.Center,
                    ) { Icon(HomeIcons.X, null, Modifier.size(16.dp), tint = c.t3) }
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
    action: (@Composable () -> Unit)? = null,
) {
    val c = LocalHomeColors.current
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.padding(bottom = 12.dp).size(32.dp), tint = c.t3)
        Text(title, color = c.t1, style = ToolsType.emptyTitle, textAlign = TextAlign.Center)
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
        Text(text, color = content, style = HomeType.button, maxLines = 1)
    }
}

/* ------------------------------------------------------------------ */
/*  Dialog                                                              */
/* ------------------------------------------------------------------ */

internal enum class ToolsConfirmKind { Primary, Danger, DangerSoft }

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
    cancelLabel: String = "取消",
    confirmLoading: Boolean = false,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = LocalHomeColors.current
    Column(
        modifier
            .widthIn(max = ToolsDims.dialogMaxWidth)
            .fillMaxWidth()
            .shadow(24.dp, ToolsDims.dialogShape)
            .clip(ToolsDims.dialogShape)
            .background(c.surface)
            .border(1.dp, c.line, ToolsDims.dialogShape)
            .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp),
    ) {
        Text(title, Modifier.fillMaxWidth(), color = c.t1, style = ToolsType.dialogTitle, textAlign = TextAlign.Center)
        if (text != null) Text(text, Modifier.fillMaxWidth().padding(top = 8.dp), color = c.t2, style = ToolsType.dialogText, textAlign = TextAlign.Center)
        if (content != null) Column(Modifier.fillMaxWidth().padding(top = 16.dp), content = content)
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeButton(cancelLabel, onCancel, Modifier.weight(1f), enabled = !confirmLoading)
            when (confirmKind) {
                ToolsConfirmKind.Primary -> HomeButton(confirmLabel, onConfirm, Modifier.weight(1f), kind = HomeButtonKind.Primary, loading = confirmLoading)
                ToolsConfirmKind.Danger -> ToolsDangerButton(confirmLabel, onConfirm, Modifier.weight(1f), loading = confirmLoading)
                ToolsConfirmKind.DangerSoft -> ToolsDangerButton(confirmLabel, onConfirm, Modifier.weight(1f), soft = true, loading = confirmLoading)
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
internal fun ToolsMenuCard(entries: List<ToolsMenuEntry>, modifier: Modifier = Modifier, title: String? = null) {
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
        if (title != null) {
            Text(title, Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp), color = c.t3, style = HomeType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        entries.forEach { entry ->
            val tint = if (entry.danger) c.bad else c.t1
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .clip(ToolsDims.menuItemShape)
                    .clickable(role = Role.Button) { haptics(HomeHaptic.Tap); entry.onClick() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(entry.icon, null, Modifier.size(17.dp), tint = if (entry.danger) c.bad else c.t2)
                Text(entry.label, color = tint, style = ToolsType.menuItem, maxLines = 1)
            }
        }
    }
}

/**
 * Popup that hangs a [ToolsMenuCard] below the end edge of its parent box.
 * Place it inside the Box that wraps the «⋯» button.
 */
@Composable
internal fun ToolsMenuPopup(onDismiss: () -> Unit, offsetY: Int, content: @Composable () -> Unit) {
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, offsetY),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
        content = content,
    )
}
