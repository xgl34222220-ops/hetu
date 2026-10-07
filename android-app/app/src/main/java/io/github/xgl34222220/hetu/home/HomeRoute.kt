package io.github.xgl34222220.hetu.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Pages inside the home tab. Sub-pages cover the whole screen, so the host should hide the dock. */
internal enum class HomeDestination { Main, IpDetail, Targets, Resource }

/**
 * Stateful host of the home module: owns in-tab navigation, the two bottom sheets and the
 * direct-probe form. Everything else is passed straight through to the stateless screens.
 *
 * Navigation map (prototype «实现规范»):
 * - WAN / LAN card → [HomeDestination.IpDetail]
 * - 本机直测 › 滑杆图标 → [HomeDestination.Targets]
 * - 资源占用 card → [HomeDestination.Resource]
 * - 网速 card → 网速数据来源 sheet
 * - [HomeStatus.StartFailed] → 启动失败 sheet (dismiss calls [HomeActions.onDismissStartFailure])
 *
 * @param onDetailVisibleChange true while a sub-page is shown; use it to hide the dock and to let
 *        the shell's own BackHandler stand down (same idea as `panelDetailVisible` in RefProxyShell).
 */
@Composable
internal fun HomeRoute(
    state: HomeUiState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = HomeDims.dockClearance),
    motion: Boolean = true,
    onDetailVisibleChange: (Boolean) -> Unit = {},
) {
    val haptics = LocalHomeHaptics.current
    val animate = motion && LocalHomeMotionEnabled.current
    var destination by rememberSaveable { mutableStateOf(HomeDestination.Main) }
    var ipSide by rememberSaveable { mutableStateOf(HomeNetSide.Wan) }
    var speedSheet by rememberSaveable { mutableStateOf(false) }
    var targets by remember { mutableStateOf(HomeTargetsConfig()) }
    var feedback by remember { mutableStateOf<HomeTargetsFeedback?>(null) }

    LaunchedEffect(destination) { onDetailVisibleChange(destination != HomeDestination.Main) }
    BackHandler(enabled = destination != HomeDestination.Main) { destination = HomeDestination.Main }

    AnimatedContent(
        targetState = destination,
        modifier = modifier,
        transitionSpec = {
            // Push: the new page arrives from the right while the old one sinks back a little.
            // Pop: the mirror image, with the page that leaves drawn on top.
            val page = HomeMotion.PageMs
            val ease = HomeMotion.Emphasized
            when {
                !animate -> EnterTransition.None togetherWith ExitTransition.None
                targetState != HomeDestination.Main ->
                    (slideInHorizontally(tween(page, easing = ease)) { it / 5 } + fadeIn(tween(220, delayMillis = 40))) togetherWith
                        (slideOutHorizontally(tween(page, easing = ease)) { -it / 12 } + scaleOut(tween(page, easing = ease), targetScale = .97f) + fadeOut(tween(160)))
                else ->
                    ((slideInHorizontally(tween(page, easing = ease)) { -it / 12 } + scaleIn(tween(page, easing = ease), initialScale = .97f) + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(page, easing = ease)) { it / 5 } + fadeOut(tween(180)))).apply { targetContentZIndex = -1f }
            }
        },
        label = "home-route",
    ) { current ->
        when (current) {
            HomeDestination.Main -> HomeScreen(
                state = state,
                actions = actions,
                onOpenIpDetail = { ipSide = state.netSide; destination = HomeDestination.IpDetail },
                onOpenTargets = { targets = actions.loadTargets(); feedback = null; destination = HomeDestination.Targets },
                onOpenResource = { destination = HomeDestination.Resource },
                onOpenSpeedSource = { speedSheet = true },
                contentPadding = contentPadding,
                motion = animate,
            )
            HomeDestination.IpDetail -> HomeIpDetailScreen(
                state = state,
                side = ipSide,
                onSideChange = { ipSide = it },
                onBack = { destination = HomeDestination.Main },
                onRefresh = actions.onRefreshIp,
                onCopy = actions.onCopy,
            )
            HomeDestination.Targets -> HomeLatencyTargetsScreen(
                config = targets,
                feedback = feedback,
                onTargetChange = { index, target ->
                    targets = targets.copy(targets = targets.targets.mapIndexed { i, old -> if (i == index) target else old })
                    feedback = null
                },
                onAutoRefreshChange = { targets = targets.copy(autoRefreshSeconds = it); feedback = null },
                onReset = {
                    targets = actions.resetTargets()
                    feedback = HomeTargetsFeedback.Restored()
                },
                onSave = {
                    val problem = HomeTargets.validate(targets.targets)
                    if (problem != null) {
                        haptics(HomeHaptic.Reject)
                        feedback = HomeTargetsFeedback.Invalid(problem)
                    } else {
                        val cleaned = targets.copy(targets = targets.targets.map { HomeTarget(it.name.trim(), it.url.trim()) })
                        actions.saveTargets(cleaned)
                        targets = cleaned
                        haptics(HomeHaptic.Confirm)
                        feedback = HomeTargetsFeedback.Saved()
                    }
                },
                onBack = { destination = HomeDestination.Main },
            )
            HomeDestination.Resource -> HomeResourceScreen(state = state, onBack = { destination = HomeDestination.Main })
        }
    }

    if (speedSheet) {
        HomeModalSheet(onDismiss = { speedSheet = false }) {
            HomeSpeedSourceSheetContent(
                selected = state.speedSource,
                onSelect = { actions.onSpeedSourceChange(it); speedSheet = false },
                onClose = { speedSheet = false },
            )
        }
    }

    val failure = state.status as? HomeStatus.StartFailed
    if (failure != null && destination == HomeDestination.Main) {
        HomeModalSheet(onDismiss = actions.onDismissStartFailure) {
            HomeStartFailedSheetContent(
                detail = failure.detail,
                onCopy = { haptics(HomeHaptic.Confirm); actions.onCopy("诊断信息", failure.detail) },
                onViewConfig = { actions.onDismissStartFailure(); actions.onViewConfig() },
                onRetry = { actions.onDismissStartFailure(); actions.onStart() },
            )
        }
    }
}

/** Material 3 bottom sheet dressed with the home tokens: 28 dp top corners, surface fill, no drag-handle slot. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeModalSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = LocalHomeColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.homeGlassPanel(HomeDims.sheetShape, c.raised, raised = true),
        shape = HomeDims.sheetShape,
        containerColor = Color.Transparent,
        scrimColor = c.scrim,
        dragHandle = null,
    ) { content() }
}
