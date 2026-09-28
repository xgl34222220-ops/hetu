package io.github.xgl34222220.hetu

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.ln

internal val HomePullOffset = SemanticsPropertyKey<Float>("HomePullOffsetDp")
internal val HomePullActive = SemanticsPropertyKey<Boolean>("HomePullActive")

/** Bounded rubber band in dp: its derivative decreases continuously, independent of density. */
internal fun homeRubberBand(rawDp: Float): Float =
    110f * (1f - exp(-rawDp.coerceIn(0f, 1140f) / 190f))

/** Presentation state. Completion comes ONLY from the caller's real refreshing flag. */
@Stable
internal class HomePullState(private val scope: CoroutineScope, private val density: Float) {
    var offsetPx by mutableFloatStateOf(0f)
        private set
    var requested by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set
    private var rawPx = 0f
    private var animation: Job? = null
    private var motion = true
    val active: Boolean get() = refreshing || requested
    val offsetDp: Float get() = offsetPx / density
    val armed: Boolean get() = offsetDp >= 65f

    fun sync(realRefreshing: Boolean, enabled: Boolean, animate: Boolean) {
        motion = animate
        val wasRefreshing = refreshing
        refreshing = realRefreshing
        if (realRefreshing) {
            requested = false
            rawPx = 0f
            settle(46f * density)
        } else if (wasRefreshing || !enabled) {
            requested = false
            rawPx = 0f
            settle(0f)
        } else if (!animate && rawPx == 0f && !requested) settle(0f)
    }

    /** Returns the consumed finger distance, not the smaller displayed displacement. */
    fun drag(deltaPx: Float): Float {
        if (active || !deltaPx.isFinite()) return 0f
        animation?.cancel()
        // A new drag catches the current spring position instead of snapping to zero.
        if (rawPx == 0f && offsetPx > 0f) {
            rawPx = (-190f * ln((1f - offsetDp / 110f).coerceIn(.0025f, 1f))) * density
        }
        val before = rawPx
        rawPx = (rawPx + deltaPx).coerceIn(0f, 1140f * density)
        offsetPx = homeRubberBand(rawPx / density) * density
        return rawPx - before
    }

    fun release(enabled: Boolean, onRefresh: () -> Unit, velocityPx: Float = 0f): Boolean {
        if (rawPx <= 0f || active) return false
        val trigger = enabled && armed
        val derivative = (110f / 190f) * (1f - offsetDp / 110f).coerceIn(0f, 1f)
        val releaseVelocity = if (velocityPx.isFinite()) (velocityPx * derivative).coerceIn(-600f * density, 600f * density) else 0f
        rawPx = 0f
        if (trigger) {
            requested = true
            settle(46f * density, releaseVelocity)
            try { onRefresh() } catch (error: Exception) {
                requested = false
                settle(0f)
                throw error
            }
        } else settle(0f, releaseVelocity)
        return true
    }

    private fun settle(target: Float, velocity: Float = 0f) {
        animation?.cancel()
        if (!motion) { offsetPx = target; return }
        animation = scope.launch {
            Animatable(offsetPx).animateTo(target, spring(dampingRatio = .84f, stiffness = 280f), initialVelocity = velocity) {
                offsetPx = value.coerceIn(0f, 110f * density)
            }
            offsetPx = target
        }
    }
    fun dispose() { animation?.cancel(); rawPx = 0f; offsetPx = 0f; requested = false }
}

@Composable
internal fun rememberHomePull(
    list: LazyListState, refreshing: Boolean, enabled: Boolean, motion: Boolean, onRefresh: () -> Unit,
): Pair<HomePullState, NestedScrollConnection> {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val state = remember(scope, density) { HomePullState(scope, density) }
    val currentEnabled by rememberUpdatedState(enabled)
    val currentRefresh by rememberUpdatedState(onRefresh)
    LaunchedEffect(refreshing, enabled, motion) { state.sync(refreshing, enabled, motion) }
    DisposableEffect(state) { onDispose { state.dispose() } }
    val connection = remember(list, state) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || !currentEnabled || available.y >= 0f || state.offsetPx <= 0f)
                    return Offset.Zero
                return Offset(0f, state.drag(available.y))
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || !currentEnabled || list.canScrollBackward || available.y <= 0f)
                    return Offset.Zero
                return Offset(0f, state.drag(available.y))
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                val pulled = state.release(currentEnabled, currentRefresh, available.y)
                return if (pulled && available.y > 0f) Velocity(0f, available.y) else Velocity.Zero
            }
        }
    }
    return state to connection
}

/** A droplet grows into two rings; there is no default gray circular Material surface. */
@Composable
internal fun HomePullIndicator(state: HomePullState, motion: Boolean, modifier: Modifier = Modifier) {
    val active = state.active
    val progress = (state.offsetDp / 65f).coerceIn(0f, 1f)
    val rotation = if (active && motion) {
        val transition = rememberInfiniteTransition(label = "pull-rings")
        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "pull-angle").value
    } else progress * 240f
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val color = if (dark) Color(0xFF93C5FD) else Color(0xFF2563EB)
    val title = if (active) "正在同步状态…" else if (state.armed) "松开立即刷新" else "下拉同步状态"
    Box(modifier.fillMaxWidth().height(state.offsetDp.dp).clip(RectangleShape)
        .testTag("home-pull-indicator").semantics {
            stateDescription = title
            this[HomePullOffset] = state.offsetDp
            this[HomePullActive] = active
        }, contentAlignment = Alignment.Center) {
        if (state.offsetDp > 1f) Row(
            Modifier.graphicsLayer { alpha = (state.offsetDp / 24f).coerceIn(0f, 1f) },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Canvas(Modifier.size(24.dp).testTag("home-pull-rings")) {
                val center = Offset(size.width / 2, size.height / 2)
                val radius = size.minDimension * (.18f + .18f * progress)
                val stretch = (1f - progress) * size.height * .2f
                drawPath(Path().apply {
                    moveTo(center.x, center.y - radius - stretch)
                    cubicTo(center.x + radius * 1.3f, center.y - radius * .3f, center.x + radius, center.y + radius, center.x, center.y + radius)
                    cubicTo(center.x - radius, center.y + radius, center.x - radius * 1.3f, center.y - radius * .3f, center.x, center.y - radius - stretch)
                    close()
                }, color.copy(alpha = .12f))
                drawCircle(color.copy(alpha = .23f), radius, center, style = Stroke(1.7.dp.toPx()))
                drawArc(color, rotation - 90f, 70f + progress * 190f, false,
                    Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2),
                    style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                if (active) drawArc(color.copy(alpha = .65f), -rotation + 90f, 130f, false,
                    Offset(center.x - radius * .6f, center.y - radius * .6f), Size(radius * 1.2f, radius * 1.2f),
                    style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round))
            }
            Text(title, color = color, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** No wrapping or ellipsis. Overflowing IPv6 stays one line and can be horizontally scrolled. */
@Composable
internal fun HomeSingleLineAddress(raw: String, color: Color, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(raw) { scroll.scrollTo(0) }
    Text(raw.ifBlank { "—" }, modifier.horizontalScroll(scroll).width(IntrinsicSize.Max),
        color = color, fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = (-.2).sp,
        maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
        style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"))
}

internal enum class HomeNoticeKind { Success, Error, Information }
internal data class HomeNotice(val id: Int, val summary: String, val raw: String, val kind: HomeNoticeKind)
internal fun homeNotice(raw: String, id: Int): HomeNotice? {
    val message = raw.trim()
    if (message.isEmpty()) return null
    val summary = HomeLifecyclePresentation.feedback(message) ?: return null
    val kind = when {
        summary == "操作未完成，请查看详情" -> HomeNoticeKind.Error
        summary in listOf("全部刷新完成", "配置已重载", "代理已停止", "代理已启动") -> HomeNoticeKind.Success
        else -> HomeNoticeKind.Information
    }
    return HomeNotice(id, summary, message, kind)
}

/** Transient notification only. The timer never changes proxy/refresh/latency state. */
@Composable
internal fun rememberHomeNotice(data: CompactHomeData): MutableState<HomeNotice?> {
    val notice = remember { mutableStateOf<HomeNotice?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(data.message, data.busy, data.operation, data.refreshing) {
        notice.value = if (data.busy || data.operation != null || data.refreshing) null
            else homeNotice(data.message, ++revision)
    }
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(notice.value?.id) {
        val current = notice.value ?: return@LaunchedEffect
        val duration = if (current.kind == HomeNoticeKind.Error) 5000L else 2200L
        delay(accessibility?.calculateRecommendedTimeoutMillis(duration,
            containsIcons = true, containsText = true, containsControls = true) ?: duration)
        if (notice.value?.id == current.id) notice.value = null
    }
    return notice
}

/** A small charcoal translucent pill under the pinned header; never a bottom black slab. */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun HomeFeedbackPill(notice: HomeNotice?, motion: Boolean, haze: HazeState,
    modifier: Modifier = Modifier, onDetails: (String) -> Unit) {
    val hardware = LocalView.current.isHardwareAccelerated
    val prefs = LocalContext.current.getSharedPreferences("hetu", 0)
    var blur by remember(prefs) { mutableStateOf(prefs.getBoolean("enableBlur", true)) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == "enableBlur") blur = p.getBoolean(key, true)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    // Retain exit text while AnimatedVisibility animates out, but never retain its click action.
    var shown by remember { mutableStateOf(notice) }
    if (notice != null) SideEffect { shown = notice }
    AnimatedVisibility(notice != null, modifier,
        enter = fadeIn(tween(if (motion) 220 else 0)) + slideInVertically(tween(if (motion) 300 else 0)) { -it / 2 } +
            scaleIn(tween(if (motion) 300 else 0), initialScale = .94f),
        exit = fadeOut(tween(if (motion) 180 else 0)) + slideOutVertically(tween(if (motion) 220 else 0)) { -it / 2 },
    ) {
        val value = notice ?: shown
        if (value != null) {
            val dot = when (value.kind) {
                HomeNoticeKind.Success -> Color(0xFF34D399)
                HomeNoticeKind.Error -> Color(0xFFFDA4AF)
                HomeNoticeKind.Information -> Color(0xFF93C5FD)
            }
            val glow = if (motion && value.kind == HomeNoticeKind.Success) {
                val pulse = rememberInfiniteTransition(label = "feedback-glow")
                pulse.animateFloat(.6f, 1f, infiniteRepeatable(tween(850, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse), label = "feedback-glow-alpha").value
            } else 1f
            Row(Modifier.widthIn(max = 320.dp).heightIn(min = 40.dp).clip(CircleShape)
                .then(if (hardware && blur) Modifier.hazeEffect(haze, HazeMaterials.ultraThin()) {
                    blurRadius = 14.dp; noiseFactor = 0f
                } else Modifier)
                .background(Color(0xFF0F172A).copy(alpha = .90f))
                .testTag("home-feedback-pill").semantics { liveRegion = LiveRegionMode.Polite }
                .nativePress(enabled = notice != null, label = "查看操作详情", motion = motion) { onDetails(value.raw) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Canvas(Modifier.size(12.dp).testTag("home-feedback-dot")) {
                    drawCircle(dot.copy(alpha = .17f * glow), size.minDimension / 2)
                    drawCircle(dot, size.minDimension / 3)
                }
                Text(value.summary, color = Color.White, fontSize = 12.sp, lineHeight = 18.sp,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
