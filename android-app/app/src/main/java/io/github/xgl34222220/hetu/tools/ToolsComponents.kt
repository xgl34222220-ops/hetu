package io.github.xgl34222220.hetu.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.xgl34222220.hetu.home.HomeButton
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.home.HomeCheckMark
import io.github.xgl34222220.hetu.home.HomeChevron
import io.github.xgl34222220.hetu.home.HomeDialog
import io.github.xgl34222220.hetu.home.HomeDialogCard
import io.github.xgl34222220.hetu.home.HomeDialogConfirm
import io.github.xgl34222220.hetu.home.HomeEmptyState
import io.github.xgl34222220.hetu.home.HomeFormField
import io.github.xgl34222220.hetu.home.HomeListRow
import io.github.xgl34222220.hetu.home.HomeMenuDivider
import io.github.xgl34222220.hetu.home.HomeMenuItem
import io.github.xgl34222220.hetu.home.HomeMenuSurface
import io.github.xgl34222220.hetu.home.HomeMenuTitle
import io.github.xgl34222220.hetu.home.HomePopIn
import io.github.xgl34222220.hetu.home.HomeRowDims
import io.github.xgl34222220.hetu.home.HomeRowSubStyle
import io.github.xgl34222220.hetu.home.HomeSearchField
import io.github.xgl34222220.hetu.home.HomeTone
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.ui.ht

/* ------------------------------------------------------------------ */
/*  Tools-only tokens (everything else comes from HomeTokens)           */
/* ------------------------------------------------------------------ */

internal object ToolsDims {
    val keyShape = RoundedCornerShape(10.dp)
    /** Tinted block inside a card: an editable list item, a picked file. */
    val tileShape = RoundedCornerShape(18.dp)
}

internal object ToolsType {
    val dialogText = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
    /** Second line that is data rather than prose: a URL, a package name, a MAC address. */
    val url = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
}

/* ------------------------------------------------------------------ */
/*  Text blocks                                                         */
/* ------------------------------------------------------------------ */

/** Secondary sentence under a top bar. */
@Composable
internal fun ToolsLead(text: String, modifier: Modifier = Modifier) {
    Text(ht(text), modifier.padding(horizontal = 6.dp), color = LocalHomeColors.current.t2, style = HomeType.note.copy(fontSize = 15.sp, lineHeight = 21.sp))
}

/** Footnote, optionally led by an info glyph. */
@Composable
internal fun ToolsNote(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = LocalHomeColors.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (icon != null) Icon(icon, null, Modifier.padding(top = 1.dp).size(18.dp), tint = c.t2)
        Text(ht(text), color = c.t2, style = HomeType.note.copy(fontSize = 13.5.sp))
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
        modifier.fillMaxWidth().heightIn(min = 62.dp).padding(start = HomeRowDims.start, end = if (trailing == null) HomeRowDims.end else 6.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(HomeRowDims.icon), tint = c.t1)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(ht(title), Modifier.semantics { heading() }, color = c.t1, style = HomeType.section, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (caption != null) Text(ht(caption), color = c.t2, style = HomeRowSubStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing()
    }
}

/**
 * List row of a tool page: 26 dp leading icon, title with an optional second line, trailing
 * slot. Titles and subtitles are drawn as given; callers pass `ht`-resolved UI text.
 *
 * @param title an [AnnotatedString] so search hits can be tinted.
 * @param selected paints the accent-tinted highlight of the current config.
 * @param compact the shorter row used inside dense cards (checks, pickers).
 */
@Composable
internal fun ToolsRow(
    title: AnnotatedString,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    subtitleStyle: TextStyle = HomeRowSubStyle,
    subtitleColor: Color = LocalHomeColors.current.t2,
    titleColor: Color = LocalHomeColors.current.t1,
    iconTint: Color = LocalHomeColors.current.t1,
    selected: Boolean = false,
    compact: Boolean = false,
    enabled: Boolean = true,
    endPadding: Dp = HomeRowDims.end,
    subtitleMaxLines: Int = 2,
    minHeight: Dp? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    HomeListRow(
        title = title, modifier = modifier, subtitle = subtitle, icon = icon, iconTint = iconTint,
        titleColor = titleColor, subtitleColor = subtitleColor, subtitleStyle = subtitleStyle, subtitleMaxLines = subtitleMaxLines,
        enabled = enabled, selected = selected,
        minHeight = minHeight ?: if (!compact) null else if (subtitle == null) HomeRowDims.compact else 64.dp,
        endPadding = endPadding, onClick = onClick, onClickLabel = onClickLabel, leading = leading, trailing = trailing,
    )
}

@Composable
internal fun ToolsChevron(modifier: Modifier = Modifier) = HomeChevron(modifier)

/** Solid accent disc with a check that pops in: marks the current config. */
@Composable
internal fun ToolsCheckBadge(modifier: Modifier = Modifier) {
    val label = ht("当前配置")
    HomeCheckMark(true, modifier.semantics { contentDescription = label }, size = 26.dp)
}

/** [text] with the characters in [range] tinted accent. */
@Composable
internal fun toolsHighlighted(text: String, range: IntRange?): AnnotatedString {
    val accent = LocalHomeColors.current.accent
    return remember(text, range, accent) {
        val builder = AnnotatedString.Builder(text)
        if (range != null && range.first >= 0 && range.last < text.length) {
            builder.addStyle(SpanStyle(color = accent), range.first, range.last + 1)
        }
        builder.toAnnotatedString()
    }
}

/* ------------------------------------------------------------------ */
/*  Fields                                                              */
/* ------------------------------------------------------------------ */

/** Labelled input of a tool form; see [HomeFormField]. [readOnly] renders the value as text. */
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
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    clearable: Boolean = false,
) = HomeFormField(label, value, onValueChange, modifier, placeholder, error, hint, monospace, readOnly, enabled, clearable, keyboardType)

/** Search box of a tool page; see [HomeSearchField]. */
@Composable
internal fun ToolsSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) = HomeSearchField(value, onValueChange, placeholder, modifier, autoFocus, ToolsIcons.Search)

/* ------------------------------------------------------------------ */
/*  Empty state                                                         */
/* ------------------------------------------------------------------ */

/** [title] is UI text; [subtitle] is drawn as given (an error from the host, or resolved text). */
@Composable
internal fun ToolsEmpty(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    topPadding: Dp = 44.dp,
    action: (@Composable () -> Unit)? = null,
) = HomeEmptyState(icon, title, subtitle.orEmpty(), modifier, topPadding = topPadding, verbatimSubtitle = true, action = action)

/** Destructive twin of [ToolsButton]: solid red, or the soft red tint when [soft]. */
@Composable
internal fun ToolsDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    soft: Boolean = false,
    enabled: Boolean = true,
    loading: Boolean = false,
) = HomeButton(text, onClick, modifier, kind = if (soft) HomeButtonKind.Soft else HomeButtonKind.Primary, enabled = enabled, loading = loading, danger = true)

/* ------------------------------------------------------------------ */
/*  Dialog                                                              */
/* ------------------------------------------------------------------ */

internal enum class ToolsConfirmKind { Primary, Danger, DangerSoft }

/**
 * The inside of a confirmation dialog; one shape for every question on the tool pages. Kept
 * free of the Dialog window so the same card renders in static previews; at runtime wrap it in
 * [ToolsDialog]. [title], [text] and the two button labels are UI text.
 *
 * @param icon optional glyph above the title, in a disc of [iconTone].
 * @param stacked puts the buttons on two rows, for a question that should not be answered in passing.
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
    confirmEnabled: Boolean = true,
    icon: ImageVector? = null,
    iconTone: HomeTone = HomeTone.Accent,
    stacked: Boolean = false,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) = HomeDialogCard(
    title = ht(title), confirmLabel = confirmLabel, onConfirm = onConfirm, onCancel = onCancel, modifier = modifier,
    text = text?.let { ht(it) }, icon = icon, iconTone = iconTone, cancelLabel = cancelLabel,
    confirm = when (confirmKind) {
        ToolsConfirmKind.Primary -> HomeDialogConfirm.Primary
        ToolsConfirmKind.Danger -> HomeDialogConfirm.Danger
        ToolsConfirmKind.DangerSoft -> HomeDialogConfirm.DangerSoft
    },
    confirmLoading = confirmLoading, confirmEnabled = confirmEnabled, stacked = stacked, content = content,
)

/** Dialog window around a [ToolsDialogCard]; back press and outside taps call [onDismiss]. */
@Composable
internal fun ToolsDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) = HomeDialog(onDismiss, content)

/* ------------------------------------------------------------------ */
/*  Anchored menu                                                       */
/* ------------------------------------------------------------------ */

internal class ToolsMenuEntry(val label: String, val icon: ImageVector, val danger: Boolean = false, val onClick: () -> Unit)

/** The menu surface itself: optional caption (what the menu belongs to), then the actions. */
@Composable
internal fun ToolsMenuCard(entries: List<ToolsMenuEntry>, modifier: Modifier = Modifier, title: String? = null) {
    HomeMenuSurface(modifier) {
        if (title != null) {
            HomeMenuTitle(title)
            HomeMenuDivider()
        }
        entries.forEachIndexed { index, entry ->
            // A destructive action keeps its distance from the ordinary ones.
            if (entry.danger && index > 0 && !entries[index - 1].danger) HomeMenuDivider()
            HomeMenuItem(ht(entry.label), entry.onClick, icon = entry.icon, danger = entry.danger)
        }
    }
}

/**
 * Popup that hangs a menu below the end edge of its parent box and lets it grow out of that
 * corner. Place it inside the Box that wraps the button which opens it.
 *
 * @param offsetY distance from the top of the parent box to the top of the menu.
 */
@Composable
internal fun ToolsMenuPopup(onDismiss: () -> Unit, offsetY: Dp = 46.dp, content: @Composable () -> Unit) {
    // The popup is padded so the menu's shadow is not cut off by the popup window.
    val pad = 14.dp
    val offset = with(LocalDensity.current) { IntOffset(10.dp.roundToPx(), (offsetY - pad).roundToPx()) }
    Popup(alignment = Alignment.TopEnd, offset = offset, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        HomePopIn(Modifier.padding(pad), origin = TransformOrigin(1f, 0f)) { content() }
    }
}
