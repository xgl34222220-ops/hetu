package io.github.xgl34222220.hetu

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.baiZeLineIcon
import io.github.xgl34222220.hetu.ui.ht
import io.github.xgl34222220.hetu.ui.HetuHaptic
import io.github.xgl34222220.hetu.ui.rememberHetuHaptics

/** Settings-only dimensions, measured from the supplied 04 concept sheets.
 * These do not change the denser controls used by the proxy dashboard. */
@Composable
internal fun SettingsGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Hx.colors.surface)
            .padding(vertical = 4.dp),
    ) {
        if (title != null) Text(
            title, color = Hx.colors.text, fontSize = 20.sp, lineHeight = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() }.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
        )
        content()
    }
}

@Composable
internal fun SettingsSection(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 14.dp), content = content)
}

@Composable
internal fun SettingsDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(.5.dp).background(Hx.colors.line.copy(alpha = .34f)))
}

@Composable
internal fun SettingsRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Hx.colors.text,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = Hx.colors
    val source = remember { MutableInteractionSource() }
    Row(
        modifier.fillMaxWidth().hxAnchorSource()
            .then(if (onClick != null) Modifier.hxPressScale(source, .99f)
                .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick) else Modifier)
            .heightIn(min = if (compact) 54.dp else if (subtitle.isNullOrBlank()) 68.dp else 78.dp)
            .padding(horizontal = 16.dp, vertical = if (compact) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(settingsLineIcon(icon), null, tint = iconTint.copy(alpha = if (enabled) 1f else .45f), modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 18.sp, lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold, color = c.text.copy(alpha = if (enabled) 1f else .45f),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 14.sp, lineHeight = 18.sp,
                color = c.textMuted.copy(alpha = if (enabled) 1f else .45f), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
internal fun SettingsNavRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Hx.colors.text,
    value: String? = null,
    dropdown: Boolean = false,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    SettingsRow(title, subtitle, icon, iconTint, onClick = onClick, compact = compact) {
        if (!value.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(value, color = Hx.colors.textMuted, fontSize = 15.sp, lineHeight = 20.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 136.dp))
        }
        Spacer(Modifier.width(5.dp))
        Icon(if (dropdown) Icons.Rounded.UnfoldMore else Icons.Rounded.ChevronRight,
            null, tint = Hx.colors.textMuted, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Hx.colors.text,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHetuHaptics()
    SettingsRow(title, subtitle, icon, iconTint, enabled, compact = compact,
        modifier = Modifier.hxPressScale(source, .99f).semantics(mergeDescendants = true) {}
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, interactionSource = source, indication = null) { on ->
                haptics.perform(if (on) HetuHaptic.ToggleOn else HetuHaptic.ToggleOff)
                onChange(on)
            }) {
        Spacer(Modifier.width(8.dp))
        // The entire row, including the animated thumb, is one labeled switch.
        SettingsToggleIndicator(checked, enabled)
    }
}

@Composable
private fun SettingsToggleIndicator(checked: Boolean, enabled: Boolean) {
    val c = Hx.colors
    val offset by animateDpAsState(if (checked) 25.dp else 3.dp, tween(HxMotion.Short), label = "settingsSwitchThumb")
    val track by animateColorAsState(if (checked) c.accent else if (c.dark) c.surfaceMuted else Color(0xFFC8C9D9),
        tween(HxMotion.Short), label = "settingsSwitchTrack")
    val thumb by animateColorAsState(if (checked) c.onAccent else if (c.dark) c.textMuted else Color(0xFF858798),
        tween(HxMotion.Short), label = "settingsSwitchThumbColor")
    Box(Modifier.size(width = 50.dp, height = 48.dp).graphicsLayer { alpha = if (enabled) 1f else .4f },
        contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(28.dp).clip(Hx.pillShape).background(track))
        Box(Modifier.offset(x = offset).size(22.dp).clip(Hx.pillShape).background(thumb))
    }
}

/** Filled inset with a label above the actual editable surface, as in sheets 11/12. */
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
) {
    val c = Hx.colors
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(if (c.dark) c.surfaceMuted else Color(0xFFE8E5FF))
        .then(if (error) Modifier.border(1.dp, c.bad, RoundedCornerShape(12.dp)) else Modifier)
        .padding(horizontal = 10.dp, vertical = if (label == null) 9.dp else 8.dp)) {
        if (label != null) {
            Text(label, color = c.textMuted, fontSize = 11.5.sp, lineHeight = 15.sp)
            Spacer(Modifier.height(4.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            keyboardOptions = keyboardOptions,
            textStyle = TextStyle(color = if (enabled) c.text else c.textMuted, fontSize = 15.sp, lineHeight = 19.sp),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .then(if (label != null) Modifier.background(c.surface.copy(alpha = .9f)).padding(horizontal = 10.dp, vertical = 8.dp) else Modifier.padding(horizontal = 5.dp)),
            decorationBox = { field ->
                Box {
                    if (value.isBlank() && placeholder.isNotBlank()) Text(placeholder, color = c.textFaint, fontSize = 15.sp, lineHeight = 19.sp)
                    field()
                }
            },
        )
    }
}

@Composable
internal fun SettingsDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    HxReferenceDialog(onDismiss = onDismiss) {
            Text(title, color = Hx.colors.text, fontSize = 24.sp, lineHeight = 30.sp,
                fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp))
            content()
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsDialogButton(ht("取消"), false, onDismiss, Modifier.weight(1f))
                SettingsDialogButton(confirmLabel, true, onConfirm, Modifier.weight(1f))
            }
    }
}

@Composable
private fun SettingsDialogButton(label: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val source = remember { MutableInteractionSource() }
    Box(modifier.heightIn(min = 48.dp).hxPressScale(source, .97f).clip(RoundedCornerShape(16.dp))
        .background(if (primary) {
            if (Hx.colors.accent == Color(0xFF0A62E8)) Color(0xFF004EE4) else Hx.colors.accent
        } else Hx.colors.accentSoft)
        .clickable(interactionSource = source, indication = null, onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (primary) Hx.colors.onAccent else Hx.colors.textMuted,
            fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun SettingsMirrorDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(initial) }
    var error by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    SettingsDialog(ht("加速下载"), onDismiss, ht("保存"), onConfirm = {
        val next = value.trim()
        if (!settingsMirrorPrefixValid(next)) error = true
        else onSave(next)
    }) {
        Text(ht("镜像前缀"), color = Hx.colors.textMuted, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        SettingsInput(value, { value = it; error = false }, error = error)
        if (error) Text(ht("请填写 http/https 地址"), color = Hx.colors.bad, fontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp, top = 5.dp))
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
