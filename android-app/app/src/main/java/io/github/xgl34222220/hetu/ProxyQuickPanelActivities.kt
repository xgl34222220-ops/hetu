package io.github.xgl34222220.hetu

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.rememberHazeState
import io.github.xgl34222220.hetu.ui.CrystalDepth
import io.github.xgl34222220.hetu.ui.HetuTheme
import io.github.xgl34222220.hetu.ui.crystalMaterial
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * BoxProxy-style system quick surfaces.
 *
 * They intentionally reuse the exact panel/strategy composable used by the main
 * five-tab workspace, so notification/QS entry points never drift into a second UI.
 */
class ProxySelectorSheetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        configureQuickSheetWindow()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HetuTheme {
                HetuOnboardingGate {
                    ProxyQuickPanelSheet(strategyOnly = true, onClose = { finish() })
                }
            }
        }
    }
}

class PanelSheetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        configureQuickSheetWindow()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HetuTheme {
                HetuOnboardingGate {
                    ProxyQuickPanelSheet(strategyOnly = false, onClose = { finish() })
                }
            }
        }
    }
}

private fun ComponentActivity.configureQuickSheetWindow() {
    window.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
    window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    window.attributes = window.attributes.apply { dimAmount = 0.30f }
}

@Composable
private fun ProxyQuickPanelSheet(
    strategyOnly: Boolean,
    onClose: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { ProxyDashboardRepository(context) }
    val haze = rememberHazeState()
    val delays = remember { mutableStateMapOf<String, Long>() }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ProxyComposeState()) }
    var selectedTab by rememberSaveable {
        mutableStateOf(if (strategyOnly) RefPanelTab.Groups else RefPanelTab.Overview)
    }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf("") }
    var sheetVisible by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val scrimAlpha by animateFloatAsState(
        targetValue = if (sheetVisible) 0.12f else 0f,
        animationSpec = tween(160),
        label = "quickSheetScrim",
    )

    fun requestClose() {
        if (closing) return
        closing = true
        sheetVisible = false
        scope.launch {
            delay(180)
            onClose()
        }
    }


    suspend fun refreshState() {
        try {
            val next = repo.state()
            state = next
            next.groups.flatMap { it.nodes }.forEach { node ->
                node.lastDelay?.takeIf { it > 0L }?.let { delays[node.name] = it }
            }
            loadError = ""
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            loadError = error.message ?: "状态读取失败"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        sheetVisible = true
        runCatching { repo.ensureIcons() }
        while (true) {
            refreshState()
            delay(3_000)
        }
    }

    BackHandler(onBack = ::requestClose)

    val dismissSource = remember { MutableInteractionSource() }
    val sheetSource = remember { MutableInteractionSource() }
    val sheetShape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = scrimAlpha))
            .clickable(
                interactionSource = dismissSource,
                indication = null,
                onClick = ::requestClose,
            ),
    ) {
        AnimatedVisibility(
            visible = sheetVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ) + fadeIn(animationSpec = tween(150)),
            exit = slideOutVertically(
                targetOffsetY = { it / 3 },
                animationSpec = tween(160),
            ) + fadeOut(animationSpec = tween(120)),
        ) {
            Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(if (strategyOnly) 0.88f else 0.94f)
                .clip(sheetShape)
                .crystalMaterial(sheetShape, depth = CrystalDepth.Popover)
                .clickable(
                    interactionSource = sheetSource,
                    indication = null,
                    onClick = {},
                ),
        ) {
            when {
                loading && state.groups.isEmpty() -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                loadError.isNotBlank() && state.groups.isEmpty() && !state.running -> {
                    Text(
                        text = loadError,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                else -> {
                    RefPanel(
                        state = state,
                        repo = repo,
                        delays = delays,
                        selectedTab = selectedTab,
                        onSelectedTabChange = { selectedTab = it },
                        searchRequest = 0,
                        hazeState = haze,
                        backdrop = null,
                        glassEnabled = true,
                        strategyOnly = strategyOnly,
                        onRefreshState = { refreshState() },
                        onBack = ::requestClose,
                        onOpenSettings = {
                            context.startActivity(
                                Intent(context, ReferenceProxyActivity::class.java)
                                    .putExtra(ReferenceProxyActivity.EXTRA_START_PAGE, "settings"),
                            )
                            requestClose()
                        },
                        onDetailVisibleChanged = {},
                    )
                }
            }
            }
        }
    }
}
