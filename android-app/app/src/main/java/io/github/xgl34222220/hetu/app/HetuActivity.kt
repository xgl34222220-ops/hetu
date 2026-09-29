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
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Settings
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
            HetuAppTheme(appearance = vm.appearance, dynamic = vm.dynamicColor, accentHex = vm.accentHex) {
                HetuRoot(vm)
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
            "settings", "tools" -> vm.tab = HxTab.Settings
            "panel", "strategy", "proxies", "panelsheet", "strategysheet" -> vm.tab = HxTab.Proxies
            "connections" -> vm.tab = HxTab.Connections
            "rules" -> vm.tab = HxTab.Rules
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
    data object Apps : HxRoute("apps")
    data object Cores : HxRoute("cores")
    data object About : HxRoute("about")
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
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val c = Hx.colors

    LaunchedEffect(vm) {
        vm.messages.collectLatest { snackbar.showSnackbar(it) }
    }

    // Predictive back: the page follows the finger (shrinks toward the swipe edge with
    // rounded corners); releasing completes the normal return transition from there.
    val backProgress = remember { Animatable(0f) }
    var gestureRoute by remember { mutableStateOf<HxRoute?>(null) }
    var edgeLeft by remember { mutableStateOf(true) }
    LaunchedEffect(nav.current) {
        if (nav.forward) {
            gestureRoute = null
            backProgress.snapTo(0f)
        }
    }
    PredictiveBackHandler(enabled = nav.stack.size > 1) { events ->
        gestureRoute = nav.current
        try {
            events.collect { event ->
                edgeLeft = event.swipeEdge == BackEventCompat.EDGE_LEFT
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
    BackHandler(enabled = nav.stack.size == 1 && vm.tab != HxTab.Home) { vm.tab = HxTab.Home }

    CompositionLocalProvider(LocalNav provides nav, LocalHxBlur provides vm.blurEnabled) {
        Box(Modifier.fillMaxSize().background(c.canvas)) {
            AnimatedContent(
                targetState = nav.current,
                transitionSpec = { routeTransition(nav.forward) },
                label = "route",
            ) { route ->
                val density = LocalDensity.current
                Box(
                    Modifier
                        .fillMaxSize()
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
                            HxRoute.Apps -> AppListScreen(vm)
                            HxRoute.Cores -> CoresScreen(vm)
                            HxRoute.About -> AboutScreen(vm)
                        }
                    }
                }
            }

            val bottomOffset: Dp = if (nav.current == HxRoute.Main) HxDockHeight + 22.dp else 12.dp
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = bottomOffset, start = 16.dp, end = 16.dp),
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = RoundedCornerShape(16.dp),
                    containerColor = c.text,
                    contentColor = c.canvas,
                )
            }
        }

        vm.startupError?.let { text ->
            HxTextSheet(title = "启动失败", text = text, onDismiss = { vm.startupError = null })
        }
    }
}

private fun routeTransition(forward: Boolean): ContentTransform {
    val duration = HxMotion.Long
    return if (forward) {
        (slideInHorizontally(tween(duration, easing = HxMotion.Emphasized)) { it / 3 } + fadeIn(tween(HxMotion.Medium, easing = HxMotion.Emphasized)))
            .togetherWith(
                slideOutHorizontally(tween(duration, easing = HxMotion.Emphasized)) { -it / 8 } +
                    fadeOut(tween(HxMotion.Medium)) +
                    scaleOut(tween(duration, easing = HxMotion.Emphasized), targetScale = .96f),
            )
            .apply { targetContentZIndex = 1f }
    } else {
        (slideInHorizontally(tween(duration, easing = HxMotion.Emphasized)) { -it / 8 } +
            fadeIn(tween(HxMotion.Medium)) +
            scaleIn(tween(duration, easing = HxMotion.Emphasized), initialScale = .96f))
            .togetherWith(slideOutHorizontally(tween(duration, easing = HxMotion.Emphasized)) { it / 3 } + fadeOut(tween(HxMotion.Medium)))
            .apply { targetContentZIndex = -1f }
    }
}

@Composable
private fun MainTabs(vm: HetuViewModel) {
    val c = Hx.colors
    val tabHolder = rememberSaveableStateHolder()
    val dockHaze = rememberHazeState()
    val items = remember {
        listOf(
            HxDockItem(HxTab.Home.name, HxTab.Home.label, Icons.Rounded.Home),
            HxDockItem(HxTab.Proxies.name, HxTab.Proxies.label, Icons.Rounded.Hub),
            HxDockItem(HxTab.Connections.name, HxTab.Connections.label, Icons.Rounded.SwapVert),
            HxDockItem(HxTab.Rules.name, HxTab.Rules.label, Icons.Rounded.Rule),
            HxDockItem(HxTab.Settings.name, HxTab.Settings.label, Icons.Rounded.Settings),
        )
    }
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottom = HxDockHeight + 10.dp + navInset + 14.dp
    Box(Modifier.fillMaxSize().background(c.canvas)) {
        Box(Modifier.fillMaxSize().hazeSource(dockHaze)) {
            AnimatedContent(
                targetState = vm.tab,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    (fadeIn(tween(HxMotion.Medium, delayMillis = 30, easing = HxMotion.Emphasized)) +
                        slideInHorizontally(tween(HxMotion.Long, easing = HxMotion.Emphasized)) { w -> (if (forward) w else -w) / 14 })
                        .togetherWith(
                            fadeOut(tween(110)) +
                                slideOutHorizontally(tween(HxMotion.Long, easing = HxMotion.Emphasized)) { w -> (if (forward) -w else w) / 20 },
                        )
                },
                label = "tab",
            ) { tab ->
                tabHolder.SaveableStateProvider("tab-${tab.name}") {
                    when (tab) {
                        HxTab.Home -> HomeScreen(vm, bottom)
                        HxTab.Proxies -> ProxiesScreen(vm, bottom)
                        HxTab.Connections -> ConnectionsScreen(vm, bottom)
                        HxTab.Rules -> RulesScreen(vm, bottom)
                        HxTab.Settings -> SettingsScreen(vm, bottom)
                    }
                }
            }
        }
        HxDock(
            items = items,
            selected = vm.tab.name,
            onSelect = { key -> if (key == vm.tab.name) { vm.reselect++ } else { vm.tab = HxTab.valueOf(key) } },
            hazeState = dockHaze,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
