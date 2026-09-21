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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.*

/** Shared non-home geometry. Longer text grows downwards; it never recentres the title. */
internal object WorkspaceMetrics {
    val gutter = 16.dp
    val icon = 28.dp
    val iconGap = 16.dp
    val textInset = gutter + icon + iconGap
    val rowMinimum = 70.dp
    val tap = 48.dp
    val easing = CubicBezierEasing(.16f, 1f, .30f, 1f)
}

@Composable
internal fun WorkspaceSettingRow(title: String, supporting: String, icon: ImageVector? = null,
    modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    val t = LocalHetuTokens.current
    Row(modifier.fillMaxWidth().heightIn(min = WorkspaceMetrics.rowMinimum)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(horizontal = WorkspaceMetrics.gutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WorkspaceMetrics.iconGap)) {
        Box(Modifier.size(WorkspaceMetrics.icon), contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(24.dp), tint = t.textPrimary)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, Modifier.fillMaxWidth().testTag("setting-title:$title"), color = t.textPrimary,
                fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold)
            if (supporting.isNotBlank()) Text(supporting, Modifier.fillMaxWidth().testTag("setting-support:$title"),
                color = t.textSecondary, fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.widthIn(max = 96.dp).heightIn(min = WorkspaceMetrics.tap), contentAlignment = Alignment.TopEnd) { trailing() }
    }
}

@Composable
internal fun WorkspaceInsetDivider() {
    HorizontalDivider(Modifier.padding(start = WorkspaceMetrics.textInset, end = WorkspaceMetrics.gutter),
        thickness = .5.dp, color = LocalHetuTokens.current.textMuted.copy(alpha = .08f))
}

/** One 48dp action slot for every refresh state; no success/failure popup or shifting label. */
@Composable
internal fun WorkspaceRefreshAction(label: String, refreshing: Boolean, success: Boolean,
    error: Boolean = false, modifier: Modifier = Modifier, actionLabel: String = "更新订阅", onClick: () -> Unit) {
    val t = LocalHetuTokens.current
    val phase = when { refreshing -> "loading"; error -> "failed"; success -> "success"; else -> "idle" }
    val motion = LocalHetuMotionEnabled.current
    IconButton(onClick = onClick, enabled = !refreshing,
        modifier = modifier.size(48.dp).testTag("refresh:$label").semantics {
            contentDescription = "$label $actionLabel"
            stateDescription = phase
        }) {
        Box(Modifier.size(30.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .07f), CircleShape),
            contentAlignment = Alignment.Center) {
            AnimatedContent(phase, transitionSpec = {
                fadeIn(tween(if (motion) 140 else 0)).togetherWith(fadeOut(tween(if (motion) 90 else 0)))
            }, label = "refresh-phase") { state ->
                if (state == "loading") HetuBusyIndicator(Modifier.size(18.dp))
                else Icon(when(state) { "success" -> Icons.Rounded.Check; "failed" -> Icons.Rounded.ErrorOutline; else -> Icons.Rounded.Refresh },
                    null, Modifier.size(18.dp),
                    tint = when(state) { "success" -> t.success; "failed" -> t.danger; else -> MaterialTheme.colorScheme.primary })
            }
        }
    }
}

/** Spring-driven expansion. The well never hard-cuts or collapses into a 48dp strip. */
@Composable
internal fun WorkspaceAccordion(visible: Boolean, content: @Composable AnimatedVisibilityScope.() -> Unit) {
    val motion = LocalHetuMotionEnabled.current
    val springSpec = spring<IntSize>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessLow,
    )
    val fadeDuration = if (motion) 150 else 0
    key(motion) {
        AnimatedVisibility(
            visible = visible,
            enter = if (motion) {
                expandVertically(
                    animationSpec = springSpec,
                    expandFrom = Alignment.Top,
                    clip = true,
                ) + fadeIn(tween(fadeDuration))
            } else {
                expandVertically(tween(0), expandFrom = Alignment.Top, clip = true)
            },
            exit = if (motion) {
                shrinkVertically(
                    animationSpec = springSpec,
                    shrinkTowards = Alignment.Top,
                    clip = true,
                ) + fadeOut(tween(fadeDuration))
            } else {
                shrinkVertically(tween(0), shrinkTowards = Alignment.Top, clip = true)
            },
            content = content,
        )
    }
}
