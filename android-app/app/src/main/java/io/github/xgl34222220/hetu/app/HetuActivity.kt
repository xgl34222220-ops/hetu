package io.github.xgl34222220.hetu

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
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
            HetuAppTheme(appearance = vm.appearance, dynamic = vm.dynamicColor) {
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
    val c = Hx.colors

    LaunchedEffect(vm) {
        vm.messages.collectLatest { snackbar.showSnackbar(it) }
    }

    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    BackHandler(enabled = nav.stack.size == 1 && vm.tab != HxTab.Home) { vm.tab = HxTab.Home }

    androidx.compose.runtime.CompositionLocalProvider(LocalNav provides nav) {
        Box(Modifier.fillMaxSize().background(c.canvas)) {
            AnimatedContent(
                targetState = nav.current,
                transitionSpec = { routeTransition(nav.forward) },
                label = "route",
            ) { route ->
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

            val bottomOffset: Dp = if (nav.current == HxRoute.Main) 88.dp else 12.dp
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = bottomOffset, start = 12.dp, end = 12.dp),
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = RoundedCornerShape(14.dp),
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
            .togetherWith(slideOutHorizontally(tween(duration, easing = HxMotion.Emphasized)) { -it / 8 } + fadeOut(tween(HxMotion.Short)))
            .apply { targetContentZIndex = 1f }
    } else {
        (slideInHorizontally(tween(duration, easing = HxMotion.Emphasized)) { -it / 8 } + fadeIn(tween(HxMotion.Medium)))
            .togetherWith(slideOutHorizontally(tween(duration, easing = HxMotion.Emphasized)) { it / 3 } + fadeOut(tween(HxMotion.Short)))
            .apply { targetContentZIndex = -1f }
    }
}

private data class HxTabSpec(val tab: HxTab, val icon: ImageVector)

@Composable
private fun MainTabs(vm: HetuViewModel) {
    val c = Hx.colors
    val tabHolder = rememberSaveableStateHolder()
    val tabs = remember {
        listOf(
            HxTabSpec(HxTab.Home, Icons.Rounded.Home),
            HxTabSpec(HxTab.Proxies, Icons.Rounded.Hub),
            HxTabSpec(HxTab.Connections, Icons.Rounded.SwapVert),
            HxTabSpec(HxTab.Rules, Icons.Rounded.Rule),
            HxTabSpec(HxTab.Settings, Icons.Rounded.Settings),
        )
    }
    Scaffold(
        containerColor = c.canvas,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Column(Modifier.fillMaxWidth()) {
                HorizontalDivider(thickness = 0.5.dp, color = c.line)
                NavigationBar(containerColor = c.surface, tonalElevation = 0.dp) {
                    tabs.forEach { spec ->
                        NavigationBarItem(
                            selected = vm.tab == spec.tab,
                            onClick = { vm.tab = spec.tab },
                            icon = { Icon(spec.icon, contentDescription = spec.tab.label) },
                            label = { Text(spec.tab.label, style = MaterialTheme.typography.labelMedium) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = c.accent,
                                selectedTextColor = c.accent,
                                indicatorColor = c.accentSoft,
                                unselectedIconColor = c.textMuted,
                                unselectedTextColor = c.textMuted,
                            ),
                        )
                    }
                }
            }
        },
    ) { inner ->
        val bottom = inner.calculateBottomPadding()
        AnimatedContent(
            targetState = vm.tab,
            transitionSpec = {
                (fadeIn(tween(HxMotion.Medium, delayMillis = 40, easing = HxMotion.Emphasized)) +
                    scaleIn(tween(HxMotion.Medium, delayMillis = 40, easing = HxMotion.Emphasized), initialScale = .985f))
                    .togetherWith(fadeOut(tween(90)))
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
}
