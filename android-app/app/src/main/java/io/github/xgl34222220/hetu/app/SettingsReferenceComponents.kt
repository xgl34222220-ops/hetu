package io.github.xgl34222220.hetu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeCardTitle
import io.github.xgl34222220.hetu.home.HomeDialog
import io.github.xgl34222220.hetu.home.HomeDialogCard
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeFormField
import io.github.xgl34222220.hetu.home.HomeHaptic
import io.github.xgl34222220.hetu.home.HomeRowDims
import io.github.xgl34222220.hetu.home.HomeRowDivider
import io.github.xgl34222220.hetu.home.HomeRowLayout
import io.github.xgl34222220.hetu.home.HomeSwitchRow
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeHaptics
import io.github.xgl34222220.hetu.home.homeRowHighlight
import io.github.xgl34222220.hetu.ui.ht

/*
 * The 设置 pages are written against these names. They are the app's shared rows, cards, fields
 * and dialog under a settings vocabulary, so 设置 and 工具 are built from the same parts.
 * Parameters that only nudged one page's pixels are still accepted, and ignored.
 */

/**
 * One card of rows. With a [title] (drawn as given) the card is headed by it. The card is one group for
 * accessibility traversal, so its heading and its rows are read (and found) together.
 */
@Composable
internal fun SettingsGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().semantics { isTraversalGroup = true }.clip(HomeDims.cardShape).background(LocalHomeColors.current.surface)
            .padding(top = if (title == null) 4.dp else 0.dp, bottom = 4.dp),
    ) {
        if (title != null) HomeCardTitle(title)
        content()
    }
}

/** A block of the page: the gutters, the gap to the next block, and the page's entrance. */
@Composable
internal fun SettingsSection(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().hxPageEnter().padding(horizontal = HomeDims.gutter).padding(bottom = HomeDims.gap), content = content)
}

/** Hairline between two rows that both carry an icon: it starts under the text. */
@Composable
internal fun SettingsDivider() = HomeRowDivider(start = HomeRowDims.textStart)

/**
 * A row of a settings card. Tappable with [onClick]; a menu opened from it grows out of it.
 *
 * @param compact a denser row, for long lists of similar switches.
 */
@Composable
internal fun SettingsRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = LocalHomeColors.current.t1,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") rootReference: Boolean = false,
    @Suppress("UNUSED_PARAMETER") rowMinHeight: Dp? = null,
    @Suppress("UNUSED_PARAMETER") subtitleFontSizeSp: Float? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val source = remember { MutableInteractionSource() }
    val line = subtitle?.takeIf { it.isNotBlank() }
    val danger = iconTint == c.bad || iconTint == Hx.colors.bad
    HomeRowLayout(
        title = AnnotatedString(title),
        modifier = modifier.hxAnchorSource().homeRowHighlight(source).then(
            if (onClick == null) Modifier
            else Modifier.clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button) { haptics(HomeHaptic.Tap); onClick() },
        ),
        subtitle = line,
        icon = icon?.let(::hxLineIcon),
        iconTint = if (danger) c.bad else c.t1,
        titleMaxLines = 2,
        subtitleMaxLines = 3,
        enabled = enabled,
        minHeight = if (compact) HomeRowDims.compact else null,
        trailing = trailing,
    )
}

/** A row that opens a page, or a picker when [dropdown]; [value] is the current choice. */
@Composable
internal fun SettingsNavRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = LocalHomeColors.current.t1,
    value: String? = null,
    dropdown: Boolean = false,
    compact: Boolean = false,
    rootReference: Boolean = false,
    rowMinHeight: Dp? = null,
    onClick: () -> Unit,
) {
    SettingsRow(title, subtitle, icon, iconTint, onClick = onClick, compact = compact, rootReference = rootReference, rowMinHeight = rowMinHeight) {
        HxRowValue(value, dropdown)
    }
}

/** One labelled switch: the whole row toggles and is announced once. */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    @Suppress("UNUSED_PARAMETER") iconTint: Color = LocalHomeColors.current.t1,
    enabled: Boolean = true,
    compact: Boolean = false,
    @Suppress("UNUSED_PARAMETER") rowMinHeight: Dp? = null,
    @Suppress("UNUSED_PARAMETER") subtitleFontSizeSp: Float? = null,
) {
    HomeSwitchRow(
        title = AnnotatedString(title), checked = checked, onCheckedChange = onChange,
        subtitle = subtitle?.takeIf { it.isNotBlank() }, icon = icon?.let(::hxLineIcon), subtitleMaxLines = 3,
        enabled = enabled, minHeight = if (compact) HomeRowDims.compact else null,
    )
}

/**
 * The app's outlined field. [label] sits above it when given; [error] turns the outline red.
 * A disabled field shows its value and cannot be edited.
 */
@Composable
internal fun SettingsInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String? = null,
    placeholder: String = "",
    singleLine: Boolean = true,
    enabled: Boolean = true,
    error: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") referenceMirror: Boolean = false,
) {
    HomeFormField(
        label = label.orEmpty(), value = value, onValueChange = onValueChange, modifier = modifier,
        placeholder = placeholder, error = if (error) "" else null, enabled = enabled,
        singleLine = singleLine, keyboardOptions = keyboardOptions,
    )
}

/** The app's dialog card with free content between the title and 取消 / [confirmLabel]. */
@Composable
internal fun SettingsDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    @Suppress("UNUSED_PARAMETER") pillButtons: Boolean = false,
    @Suppress("UNUSED_PARAMETER") titleFontSizeSp: Float? = null,
    @Suppress("UNUSED_PARAMETER") titleLineHeightSp: Float? = null,
    @Suppress("UNUSED_PARAMETER") referenceMirrorNormalSpacing: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    HomeDialog(onDismiss) {
        HomeDialogCard(title = title, confirmLabel = confirmLabel, onConfirm = onConfirm, onCancel = onDismiss, content = content)
    }
}

/** 加速下载: the mirror prefix put in front of download addresses. Blank switches it off. */
@Composable
internal fun SettingsMirrorDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    var error by rememberSaveable { mutableStateOf(false) }
    val problem = ht("请填写 http/https 地址")
    SettingsDialog(ht("加速下载"), onDismiss, "保存", onConfirm = {
        val next = value.trim()
        if (!settingsMirrorPrefixValid(next)) error = true
        else onSave(next)
    }) {
        HomeFormField(
            label = "镜像前缀", value = value, onValueChange = { value = it; error = false },
            placeholder = "https://", error = if (error) problem else null,
            hint = if (error) null else "留空则直接下载。", keyboardType = KeyboardType.Uri, clearable = true,
        )
    }
}

/** Blank disables the prefix; a configured prefix must identify an HTTP(S) host. */
internal fun settingsMirrorPrefixValid(value: String): Boolean {
    if (value.isBlank()) return true
    if (value.any { it.isWhitespace() || it.isISOControl() }) return false
    return runCatching {
        val uri = java.net.URI(value)
        (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65535)
    }.getOrDefault(false)
}
