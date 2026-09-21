package io.github.xgl34222220.hetu

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.*

/** Shared non-home geometry. Longer text grows downwards; it never recentres the title. */
internal object WorkspaceMetrics {
    val gutter = 16.dp
    val icon = 36.dp
    val iconGap = 12.dp
    val textInset = gutter + icon + iconGap
    val rowMinimum = 76.dp
    val tap = 48.dp
    val easing = CubicBezierEasing(.16f, 1f, .30f, 1f)
}

@Composable
internal fun WorkspaceSettingRow(title: String, supporting: String, icon: ImageVector? = null,
    modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    val t = LocalHetuTokens.current
    Row(modifier.fillMaxWidth().heightIn(min = WorkspaceMetrics.rowMinimum)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(horizontal = WorkspaceMetrics.gutter, vertical = 14.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(WorkspaceMetrics.iconGap)) {
        Box(Modifier.size(WorkspaceMetrics.icon).padding(top = 2.dp)) {
            if (icon != null) HetuListIcon(icon)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, Modifier.fillMaxWidth().testTag("setting-title:$title"), color = t.textPrimary,
                fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
            if (supporting.isNotBlank()) Text(supporting, Modifier.fillMaxWidth().testTag("setting-support:$title"),
                color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Box(Modifier.widthIn(max = 96.dp).heightIn(min = WorkspaceMetrics.tap), contentAlignment = Alignment.TopEnd) { trailing() }
    }
}

@Composable
internal fun WorkspaceInsetDivider() {
    HorizontalDivider(Modifier.padding(start = WorkspaceMetrics.textInset, end = WorkspaceMetrics.gutter),
        thickness = .5.dp, color = LocalHetuTokens.current.textMuted.copy(alpha = .14f))
}

/** One 48dp action slot for every refresh state; no success/failure popup or shifting label. */
@Composable
internal fun WorkspaceRefreshAction(label: String, refreshing: Boolean, success: Boolean,
    error: Boolean = false, modifier: Modifier = Modifier, actionLabel: String = "更新订阅", onClick: () -> Unit) {
    val t = LocalHetuTokens.current
    val phase = when { refreshing -> "loading"; error -> "failed"; success -> "success"; else -> "idle" }
    val motion = LocalHetuMotionEnabled.current
    IconButton(onClick = onClick, enabled = !refreshing,
        modifier = modifier.size(48.dp).testTag("refresh:$label").semantics { stateDescription = phase }) {
        Box(Modifier.size(30.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .07f), CircleShape),
            contentAlignment = Alignment.Center) {
            AnimatedContent(phase, transitionSpec = {
                fadeIn(tween(if (motion) 140 else 0)).togetherWith(fadeOut(tween(if (motion) 90 else 0)))
            }, label = "refresh-phase") { state ->
                if (state == "loading") HetuBusyIndicator(Modifier.size(18.dp))
                else Icon(when(state) { "success" -> Icons.Rounded.Check; "failed" -> Icons.Rounded.ErrorOutline; else -> Icons.Rounded.Refresh },
                    "$label ${if(state == "success") "更新完成" else actionLabel}", Modifier.size(18.dp),
                    tint = when(state) { "success" -> t.success; "failed" -> t.danger; else -> MaterialTheme.colorScheme.primary })
            }
        }
    }
}

/** Single height transition, clipped to its slot; retaining exit content is the caller's job. */
@Composable
internal fun WorkspaceAccordion(visible: Boolean, content: @Composable AnimatedVisibilityScope.() -> Unit) {
    val duration = if (LocalHetuMotionEnabled.current) 300 else 0
    AnimatedVisibility(visible,
        enter = expandVertically(tween(duration, easing = WorkspaceMetrics.easing), expandFrom = Alignment.Top, clip = true) + fadeIn(tween(duration / 2)),
        exit = shrinkVertically(tween(duration, easing = WorkspaceMetrics.easing), shrinkTowards = Alignment.Top, clip = true) + fadeOut(tween(duration / 2)),
        content = content)
}
