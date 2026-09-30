package io.github.xgl34222220.hetu

import io.github.xgl34222220.hetu.ui.HetuHaptic
import io.github.xgl34222220.hetu.ui.rememberHetuHaptics
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WorkOutline
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import io.github.xgl34222220.hetu.ui.DockItem
import io.github.xgl34222220.hetu.ui.HetuGlassDock
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.collectLatest

/**
 * The only launcher activity. Tabs and sub-pages are Compose destinations inside it,
 * so navigation, transitions and state are consistent everywhere.
 */
class HetuActivity : ComponentActivity() {
    private lateinit var vm: HetuViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm = ViewModelProvider(this)[HetuViewModel::class.java]
        if (savedInstanceState == null) applyStartPage(intent)
        setContent {
            HetuAppTheme(appearance = vm.appearance, dynamic = vm.dynamicColor, accentHex = vm.accentHex, pureBlack = vm.pureBlack) {
                val revision = vm.settingsRevision
                val baseDensity = LocalDensity.current
                val uiScale = vm.prefs.getFloat("uiScale", 1f).coerceIn(.8f, 1.2f)
                val scaledDensity = remember(baseDensity, uiScale, revision) { Density(baseDensity.density * uiScale, baseDensity.fontScale) }
                CompositionLocalProvider(LocalDensity provides scaledDensity) { HetuRoot(vm) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyStartPage(intent)
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onStop() {
        vm.onBackground()
        super.onStop()
    }

    private fun applyStartPage(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_START_PAGE)?.lowercase()) {
            "settings" -> vm.tab = HxTab.Settings
            "tools" -> vm.tab = HxTab.Tools
            "panel", "panelsheet" -> vm.tab = HxTab.Panel
            "strategy", "proxies", "strategysheet" -> vm.openPanel("proxies")
            "connections" -> vm.openPanel("conn")
            "rules" -> vm.openPanel("rules")
            "home" -> vm.tab = HxTab.Home
        }
    }

    companion object {
        const val EXTRA_START_PAGE = "io.github.xgl34222220.hetu.START_PAGE"
    }
}

/* ------------------------------------------------------------------ */
/*  Navigation                                                          */
/* ------------------------------------------------------------------ */

internal sealed class HxRoute(val key: String) {
    data object Main : HxRoute("main")
    data object Configs : HxRoute("configs")
    data object ConfigEditor : HxRoute("config-editor")
    data object Providers : HxRoute("providers")
    data object Adblock : HxRoute("adblock")
    data object Network : HxRoute("network")
    data object AdvancedNetwork : HxRoute("advanced-network")
    data object Apps : HxRoute("apps")
    data object Cores : HxRoute("cores")
    data object About : HxRoute("about")
    data object Bypass : HxRoute("bypass")
    data object Files : HxRoute("files")
    data object SharedNet : HxRoute("shared-net")
    data object CnIp : HxRoute("cnip")
    data object Logs : HxRoute("logs")
    data object NetMatch : HxRoute("net-match")
    data object Diagnostics : HxRoute("diagnostics")
    data object Notifications : HxRoute("notifications")
    data object Theme : HxRoute("theme")
    data object PublicIp : HxRoute("public-ip")
    data object Resources : HxRoute("resources")
    data object Backup : HxRoute("backup")
    data object Startup : HxRoute("startup")
    data object PanelPreferences : HxRoute("panel-preferences")
}

internal class HxNav {
    val stack = mutableStateListOf<HxRoute>(HxRoute.Main)
    var forward by mutableStateOf(true)
        private set

    val current: HxRoute get() = stack.last()

    fun push(route: HxRoute) {
        if (stack.last() == route) return
        forward = true
        stack.add(route)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        forward = false
        stack.removeAt(stack.lastIndex)
        return true
    }
}

internal val LocalNav = staticCompositionLocalOf<HxNav> { error("HxNav not provided") }

@Composable
internal fun HetuRoot(vm: HetuViewModel) {
    val nav = remember { HxNav() }
    val routeHolder = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val c = Hx.colors
    val motion = LocalHxMotionEnabled.current

    // Predictive back: the page follows the finger (shrinks toward the swipe edge with
    // rounded corners); releasing completes the normal return transition from there.
    val settingsRevision = vm.settingsRevision
    val predictiveBackEnabled = motion && remember(settingsRevision) { vm.prefs.getBoolean("predictiveBackAnimation", true) }
    val predictiveBackFollowEdge = remember(settingsRevision) { vm.prefs.getBoolean("predictiveBackFollowEdge", true) }
    val backProgress = remember { Animatable(0f) }
    var gestureRoute by remember { mutableStateOf<HxRoute?>(null) }
    var edgeLeft by remember { mutableStateOf(true) }
    LaunchedEffect(nav.current) {
        if (nav.forward) {
            gestureRoute = null
            backProgress.snapTo(0f)
        }
    }
    PredictiveBackHandler(enabled = nav.stack.size > 1 && predictiveBackEnabled) { events ->
        gestureRoute = nav.current
        try {
            events.collect { event ->
                edgeLeft = if (predictiveBackFollowEdge) event.swipeEdge == BackEventCompat.EDGE_LEFT else true
                backProgress.snapTo(event.progress)
            }
            nav.pop()
        } catch (cancel: CancellationException) {
            scope.launch {
                backProgress.animateTo(0f, spring(dampingRatio = .8f, stiffness = Spring.StiffnessMediumLow))
                gestureRoute = null
            }
            throw cancel
        }
    }
    BackHandler(enabled = nav.stack.size > 1 && !predictiveBackEnabled) { nav.pop() }
    BackHandler(enabled = nav.stack.size == 1 && vm.tab != HxTab.Home) { vm.tab = HxTab.Home }

    CompositionLocalProvider(LocalNav provides nav, LocalHxBlur provides vm.blurEnabled, io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled provides motion) {
        Box(Modifier.fillMaxSize().background(c.canvas)) {
            AnimatedContent(
                targetState = nav.current,
                transitionSpec = { routeTransition(nav.forward, motion) },
                label = "route",
            ) { route ->
                val density = LocalDensity.current
                // The page underneath dims while the new one slides over it (and brightens on return).
                val dim by transition.animateFloat(
                    transitionSpec = { tween(HxMotion.Route, easing = HxMotion.Emphasized) },
                    label = "routeDim",
                ) { phase ->
                    when (phase) {
                        EnterExitState.Visible -> 0f
                        EnterExitState.PostExit -> if (nav.forward) .26f else 0f
                        EnterExitState.PreEnter -> if (nav.forward) 0f else .26f
                    }
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag("hetu-route-${route.key}")
                        .drawWithContent {
                            drawContent()
                            if (dim > 0f) drawRect(androidx.compose.ui.graphics.Color.Black.copy(alpha = dim))
                        }
                        .then(
                            if (route == gestureRoute) Modifier.graphicsLayer {
                                val p = backProgress.value
                                val scale = 1f - .1f * p
                                scaleX = scale
                                scaleY = scale
                                translationX = (if (edgeLeft) 1f else -1f) * p * with(density) { 28.dp.toPx() }
                                shape = RoundedCornerShape(32.dp * p)
                                clip = p > 0f
                            } else Modifier,
                        ),
                ) {
                    routeHolder.SaveableStateProvider(route.key) {
                        when (route) {
                            HxRoute.Main -> MainTabs(vm)
                            HxRoute.Configs -> ConfigsScreen(vm)
                            HxRoute.ConfigEditor -> ConfigEditorScreen(vm)
                            HxRoute.Providers -> ProvidersScreen(vm)
                            HxRoute.Adblock -> AdblockScreen(vm)
                            HxRoute.Network -> NetworkSettingsScreen(vm)
                            HxRoute.AdvancedNetwork -> OtherProxySettingsPage { nav.pop() }
                            HxRoute.Apps -> AppListScreen(vm)
                            HxRoute.Cores -> CoresScreen(vm)
                            HxRoute.About -> AboutScreen(vm)
                            HxRoute.Bypass -> BypassRulesScreen(vm) { nav.pop() }
                            HxRoute.Files -> FileManagerScreen(vm) { nav.pop() }
                            HxRoute.SharedNet -> SharedNetworkScreen(vm) { nav.pop() }
                            HxRoute.CnIp -> CnIpScreen(vm) { nav.pop() }
                            HxRoute.Logs -> HxLogFilesScreen(vm) { nav.pop() }
                            HxRoute.NetMatch -> HxNetworkMatchScreen(vm) { nav.pop() }
                            HxRoute.Diagnostics -> DiagnosticsScreen(vm) { nav.pop() }
                            HxRoute.Notifications -> NotificationSettingsScreen(vm) { nav.pop() }
                            HxRoute.Theme -> HxThemeLabScreen(vm) { nav.pop() }
                            HxRoute.PublicIp -> PublicIpDetails(vm) { nav.pop() }
                            HxRoute.Resources -> CoreDetails(vm) { nav.pop() }
                            HxRoute.Backup -> HxBackupScreen(vm) { nav.pop() }
                            HxRoute.Startup -> HxStartupSettingsScreen(vm) { nav.pop() }
                            HxRoute.PanelPreferences -> HxPanelPreferencesScreen(vm) { nav.pop() }
                        }
                    }
                }
            }

            // Toast floats just above the dock (or the bottom edge on sub-pages).
            HxToastHost(vm, extraBottom = if (nav.stack.size == 1) HxDockHeight + 22.dp else 24.dp)
        }

        vm.startupError?.let { text ->
            HxTextSheet(title = "启动失败", text = text, onDismiss = { vm.startupError = null })
        }
    }
}

private fun routeTransition(forward: Boolean, enabled: Boolean): ContentTransform {
    if (!enabled) return androidx.compose.animation.EnterTransition.None.togetherWith(androidx.compose.animation.ExitTransition.None)
    val duration = HxMotion.Route
    val ease = HxMotion.Emphasized
    // Card-stack navigation: the new page covers the old one from the right edge; the old
    // page recedes a little (parallax + slight scale) and dims instead of fading away.
    return if (forward) {
        slideInHorizontally(tween(duration, easing = ease)) { it }
            .togetherWith(
                slideOutHorizontally(tween(duration, easing = ease)) { -it / 4 } +
                    scaleOut(tween(duration, easing = ease), targetScale = .95f),
            )
            .apply { targetContentZIndex = 1f }
    } else {
        (slideInHorizontally(tween(duration, easing = ease)) { -it / 4 } +
            scaleIn(tween(duration, easing = ease), initialScale = .95f))
            .togetherWith(slideOutHorizontally(tween(duration, easing = ease)) { it })
            .apply { targetContentZIndex = -1f }
    }
}

@Composable
private fun MainTabs(vm: HetuViewModel) {
    val c = Hx.colors
    val tabHolder = rememberSaveableStateHolder()
    val dockHaze = rememberHazeState()
    val liquidBackdrop = rememberLayerBackdrop()
    val runtimeLiquid = isRuntimeShaderSupported()
    val settingsTick = vm.settingsRevision
    val showPanelDock = remember(settingsTick) { vm.prefs.getBoolean("showPanelDock", true) }
    val dockTabs = remember(settingsTick, vm.tab) {
        buildList {
            add(HxTab.Home)
            if (showPanelDock || vm.tab == HxTab.Panel) add(HxTab.Panel)
            add(HxTab.Tools)
            add(HxTab.Settings)
        }
    }
    val items = remember(dockTabs) {
        dockTabs.map { tab ->
            when (tab) {
                HxTab.Home -> DockItem(tab.label, Icons.Rounded.Home, .94f)
                HxTab.Panel -> DockItem(tab.label, Icons.Rounded.Dashboard, .94f)
                HxTab.Tools -> DockItem(tab.label, Icons.Rounded.WorkOutline, .94f)
                HxTab.Settings -> DockItem(tab.label, Icons.Rounded.Settings, .94f)
            }
        }
    }
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottom = HxDockHeight + 10.dp + navInset + 14.dp
    // The dock tucks away while reading down a list and returns on any upward scroll.
    var dockVisible by remember { mutableStateOf(true) }
    val page = vm.tab.name
    LaunchedEffect(page) { dockVisible = true }
    val dockScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (consumed.y < -18f) dockVisible = false
                else if (consumed.y > 12f || available.y > 0f) dockVisible = true
                return Offset.Zero
            }
        }
    }
    Box(Modifier.fillMaxSize().background(c.canvas)) {
        Box(
            Modifier.fillMaxSize()
                .then(if (!runtimeLiquid) Modifier.hazeSource(dockHaze) else Modifier)
                .then(if (runtimeLiquid) Modifier.layerBackdrop(liquidBackdrop) else Modifier)
                .nestedScroll(dockScroll),
        ) {
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val order = listOf("Home", "Panel", "Tools", "Settings")
                    val forward = order.indexOf(targetState) > order.indexOf(initialState)
                    (fadeIn(tween(HxMotion.Medium, delayMillis = 30, easing = HxMotion.Emphasized)) +
                        slideInHorizontally(tween(HxMotion.Long, easing = HxMotion.Emphasized)) { w -> (if (forward) w else -w) / 14 })
                        .togetherWith(
                            fadeOut(tween(110)) +
                                slideOutHorizontally(tween(HxMotion.Long, easing = HxMotion.Emphasized)) { w -> (if (forward) -w else w) / 20 },
                        )
                },
                label = "tab",
            ) { tab ->
                tabHolder.SaveableStateProvider("tab-$tab") {
                    when (tab) {
                        "Home" -> HomeScreen(vm, bottom)
                        "Panel" -> PanelScreen(vm, bottom)
                        "Tools" -> ToolsScreen(vm, bottom)
                        else -> SettingsScreen(vm, bottom)
                    }
                }
            }
        }
        val selectedIndex = dockTabs.indexOf(vm.tab).coerceAtLeast(0)
        val dockShift by animateDpAsState(
            if (dockVisible) 0.dp else 92.dp,
            spring(dampingRatio = .88f, stiffness = 420f),
            label = "dockShift",
        )
        HetuGlassDock(
            items = items,
            selected = selectedIndex,
            onSelect = { index ->
                val next = dockTabs[index.coerceIn(0, dockTabs.lastIndex)]
                if (next == vm.tab) vm.reselect++ else vm.tab = next
            },
            hazeState = dockHaze,
            backdrop = liquidBackdrop.takeIf { runtimeLiquid },
            modifier = Modifier.align(Alignment.BottomCenter).offset(y = dockShift).testTag("hetu-dock"),
        )
    }
}

/** Bottom toast pill for [vm]'s messages; used by the main activity and hosted tool pages. */
@Composable
internal fun BoxScope.HxToastHost(vm: HetuViewModel, extraBottom: Dp = 24.dp) {
    var floatingMessage by remember { mutableStateOf<String?>(null) }
    var shownMessage by remember { mutableStateOf("") }
    val haptics = rememberHetuHaptics()
    LaunchedEffect(vm) {
        vm.messages.collectLatest { message ->
            floatingMessage = message
            shownMessage = message
            if (hxToastTone(message) == HxTone.Bad) haptics.perform(HetuHaptic.Reject)
            // Longer messages stay a little longer so they can actually be read.
            delay((1500L + message.length * 45L).coerceAtMost(4200L))
            floatingMessage = null
        }
    }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    AnimatedVisibility(
        visible = floatingMessage != null,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = navBottom + extraBottom, start = 24.dp, end = 24.dp),
        enter = slideInVertically(spring(dampingRatio = .72f, stiffness = 480f)) { it / 2 } +
            fadeIn(tween(160)) +
            scaleIn(spring(dampingRatio = .66f, stiffness = 480f), initialScale = .82f),
        exit = slideOutVertically(tween(180, easing = HxMotion.Exit)) { it / 3 } +
            fadeOut(tween(150)) +
            scaleOut(tween(180), targetScale = .9f),
    ) {
        HxToast(shownMessage, onDismiss = { floatingMessage = null })
    }
}

/**
 * Hosts an Hx screen inside a standalone activity (old entry points, notification and
 * shortcut targets) with the app theme, blur setting and toast host.
 */
internal fun ComponentActivity.hxHost(content: @Composable (HetuViewModel) -> Unit) {
    enableEdgeToEdge()
    val vm = ViewModelProvider(this)[HetuViewModel::class.java]
    setContent {
        HetuAppTheme(appearance = vm.appearance, dynamic = vm.dynamicColor, accentHex = vm.accentHex, pureBlack = vm.pureBlack) {
            CompositionLocalProvider(LocalHxBlur provides vm.blurEnabled) {
                Box(Modifier.fillMaxSize().background(Hx.colors.canvas)) {
                    content(vm)
                    HxToastHost(vm)
                }
            }
        }
    }
}

private fun hxToastTone(message: String): HxTone = when {
    listOf("失败", "错误", "无效", "无法", "异常", "未获得", "超时").any { message.contains(it) } -> HxTone.Bad
    listOf("注意", "需要", "请先", "重启代理后").any { message.contains(it) } -> HxTone.Warn
    else -> HxTone.Good
}

/** Floating top toast: tone icon, tap or flick up to dismiss. */
@Composable
private fun HxToast(message: String, onDismiss: () -> Unit) {
    val c = Hx.colors
    val tone = hxToastTone(message)
    var drag by remember { mutableStateOf(0f) }
    Surface(
        modifier = Modifier
            .graphicsLayer {
                translationY = drag
                alpha = (1f - drag / 160f).coerceIn(0f, 1f)
            }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> drag = (drag + delta).coerceAtLeast(-16f) },
                onDragStopped = { velocity ->
                    if (drag > 36f || velocity > 900f) {
                        onDismiss()
                    } else {
                        drag = 0f
                    }
                },
            )
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        shape = RoundedCornerShape(50),
        color = c.surface,
        contentColor = c.text,
        shadowElevation = 14.dp,
        tonalElevation = 0.dp,
        border = if (c.dark) androidx.compose.foundation.BorderStroke(0.5.dp, c.line) else null,
    ) {
        Row(Modifier.padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(tone.bg()), contentAlignment = Alignment.Center) {
                Icon(
                    when (tone) {
                        HxTone.Bad -> Icons.Rounded.Close
                        HxTone.Warn -> Icons.Rounded.PriorityHigh
                        else -> Icons.Rounded.Check
                    },
                    null,
                    tint = tone.fg(),
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(9.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = c.text, maxLines = 2)
        }
    }
}
