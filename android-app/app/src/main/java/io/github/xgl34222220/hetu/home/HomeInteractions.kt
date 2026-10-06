package io.github.xgl34222220.hetu.home

import android.animation.ValueAnimator
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled
import io.github.xgl34222220.hetu.ui.hetuPressScale

/** Shared with the panel so pressed, route and list animations respect the same switch. */
internal val LocalHomeMotionEnabled = staticCompositionLocalOf { true }

@Composable
internal fun homeMotionEnabled(): Boolean = LocalHetuMotionEnabled.current && ValueAnimator.areAnimatorsEnabled()

/** Uses the existing interaction-driven dip; scroll cancellation returns to the resting size. */
@Composable
internal fun Modifier.homeTap(
    enabled: Boolean = true,
    role: Role = Role.Button,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    return hetuPressScale(source, enabled, pressedScale = .975f, motion = LocalHomeMotionEnabled.current)
        .clickable(source, indication = null, enabled = enabled, role = role, onClickLabel = onClickLabel, onClick = onClick)
}

/** A real nested-scroll refresh gesture; the caller owns its request and completion state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeRefreshBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    indicatorPadding: PaddingValues = PaddingValues(),
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    val colors = LocalHomeColors.current
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { if (!refreshing) onRefresh() },
        state = state,
        modifier = modifier.testTag("refresh:$label").semantics {
            customActions = listOf(CustomAccessibilityAction(label) {
                if (!refreshing) onRefresh()
                true
            })
        },
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter).padding(indicatorPadding),
                containerColor = colors.surface,
                color = colors.accent,
            )
        },
        content = content,
    )
}
