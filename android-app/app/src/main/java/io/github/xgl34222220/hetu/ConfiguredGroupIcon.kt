package io.github.xgl34222220.hetu

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.ui.*
import android.content.SharedPreferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

internal fun builtInBrandKey(name: String): String? {
    val lower = name.lowercase()
    return when {
        "openai" in lower || "chatgpt" in lower || "gpt" in lower || "ai 平台" in lower -> "openai"
        "google" in lower || "谷歌" in lower -> "google"
        "github" in lower -> "github"
        "telegram" in lower || "tg" == lower.trim() || "电报" in lower -> "telegram"
        "youtube" in lower || "油管" in lower -> "youtube"
        lower.trim() == "x" || "twitter" in lower || "推特" in lower -> "x"
        "netflix" in lower -> "netflix"
        "emby" in lower -> "emby"
        else -> null
    }
}

@Composable
internal fun BuiltInBrandIcon(brand: String, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val cx = size.width / 2f
        val cy = size.height / 2f
        val stroke = (s * .14f).coerceAtLeast(1f)
        when (brand) {
            "google" -> {
                val r = s * .32f
                val topLeft = Offset(cx - r, cy - r)
                val arcSize = androidx.compose.ui.geometry.Size(r * 2f, r * 2f)
                drawArc(Color(0xFF4285F4), -45f, 120f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                drawArc(Color(0xFF34A853), 75f, 92f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                drawArc(Color(0xFFFBBC05), 167f, 76f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                drawArc(Color(0xFFEA4335), 243f, 72f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                drawLine(Color(0xFF4285F4), Offset(cx, cy), Offset(cx + r, cy), strokeWidth = stroke, cap = StrokeCap.Butt)
            }
            "youtube" -> {
                drawRoundRect(
                    Color(0xFFFF0000),
                    topLeft = Offset(s * .08f, s * .18f),
                    size = androidx.compose.ui.geometry.Size(s * .84f, s * .64f),
                    cornerRadius = CornerRadius(s * .16f, s * .16f),
                )
                val p = Path().apply {
                    moveTo(s * .43f, s * .34f)
                    lineTo(s * .43f, s * .66f)
                    lineTo(s * .69f, s * .50f)
                    close()
                }
                drawPath(p, Color.White)
            }
            "telegram" -> {
                drawCircle(Color(0xFF229ED9), radius = s * .44f, center = Offset(cx, cy))
                val p = Path().apply {
                    moveTo(s * .18f, s * .48f)
                    lineTo(s * .82f, s * .24f)
                    lineTo(s * .64f, s * .78f)
                    lineTo(s * .48f, s * .60f)
                    lineTo(s * .36f, s * .70f)
                    lineTo(s * .38f, s * .56f)
                    close()
                }
                drawPath(p, Color.White)
            }
            "github" -> {
                drawCircle(Color(0xFF181717), radius = s * .44f, center = Offset(cx, cy))
                drawCircle(Color.White, radius = s * .23f, center = Offset(cx, cy + s * .02f))
                val ears = Path().apply {
                    moveTo(s * .31f, s * .39f); lineTo(s * .34f, s * .20f); lineTo(s * .46f, s * .35f); close()
                    moveTo(s * .69f, s * .39f); lineTo(s * .66f, s * .20f); lineTo(s * .54f, s * .35f); close()
                }
                drawPath(ears, Color.White)
                drawCircle(Color(0xFF181717), radius = s * .035f, center = Offset(s * .43f, s * .50f))
                drawCircle(Color(0xFF181717), radius = s * .035f, center = Offset(s * .57f, s * .50f))
            }
            "openai" -> {
                drawCircle(Color(0xFF10A37F), radius = s * .44f, center = Offset(cx, cy))
                repeat(6) { index ->
                    val a = Math.toRadians((index * 60.0) - 90.0)
                    val px = cx + kotlin.math.cos(a).toFloat() * s * .18f
                    val py = cy + kotlin.math.sin(a).toFloat() * s * .18f
                    drawCircle(Color.White, radius = s * .09f, center = Offset(px, py))
                }
                drawCircle(Color(0xFF10A37F), radius = s * .085f, center = Offset(cx, cy))
            }
            "x" -> {
                drawCircle(Color(0xFF111111), radius = s * .44f, center = Offset(cx, cy))
                drawLine(Color.White, Offset(s * .31f, s * .28f), Offset(s * .70f, s * .72f), strokeWidth = s * .09f, cap = StrokeCap.Round)
                drawLine(Color.White, Offset(s * .68f, s * .28f), Offset(s * .32f, s * .72f), strokeWidth = s * .06f, cap = StrokeCap.Round)
            }
            "netflix" -> {
                drawRoundRect(Color(0xFF111111), cornerRadius = CornerRadius(s * .10f))
                drawLine(Color(0xFFE50914), Offset(s * .33f, s * .18f), Offset(s * .33f, s * .82f), strokeWidth = s * .13f)
                drawLine(Color(0xFFE50914), Offset(s * .33f, s * .18f), Offset(s * .67f, s * .82f), strokeWidth = s * .12f)
                drawLine(Color(0xFFB20710), Offset(s * .67f, s * .18f), Offset(s * .67f, s * .82f), strokeWidth = s * .13f)
            }
            "emby" -> {
                drawCircle(Color(0xFF52B54B), radius = s * .44f, center = Offset(cx, cy))
                val p = Path().apply {
                    moveTo(s * .41f, s * .31f)
                    lineTo(s * .41f, s * .69f)
                    lineTo(s * .70f, s * .50f)
                    close()
                }
                drawPath(p, Color.White)
            }
        }
    }
}

/** The configured image always wins, including when the group name contains a flag. */
@Composable
internal fun ConfiguredGroupIcon(group: ProxyGroupUi, modifier: Modifier = Modifier.size(32.dp)) {
    val context = LocalContext.current
    val repository = remember(context) { ProxyGroupIconRepository.get(context) }
    val configured = group.iconUrl.isNotBlank() || group.iconPath.isNotBlank()
    val builtInBrand = remember(group.name) { builtInBrandKey(group.name) }
    val key = group.iconUrl.ifBlank { group.iconPath }
    val cached = remember(key) { repository.peek(key) }
    val prefs = remember(context) { context.getSharedPreferences("hetu", 0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var epoch by remember { mutableIntStateOf(0) }
    var visible by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(prefs, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            visible = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (event == Lifecycle.Event.ON_RESUME) epoch++
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == "proxyRootRuntimeRunning" || changed == "proxyControllerPort") epoch++
        }
        lifecycle.addObserver(observer)
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { lifecycle.removeObserver(observer); prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val loaded by produceState<GroupIconLoad>(cached?.let { GroupIconLoad.Ready(it, true) } ?: GroupIconLoad.Loading,
        key, group.iconPath, epoch, visible) {
        // produceState keeps its previous value when the URL key changes. Reset
        // before early returns so a removed/changed icon cannot reuse another image.
        value = repository.peek(key)?.let { GroupIconLoad.Ready(it, true) } ?: GroupIconLoad.Loading
        if (!configured || !visible) return@produceState
        // A failed first attempt must not remain stuck until the entire process dies.
        // Only visible composed cards retry; cache, per-URL single flight and bounds remain.
        repeat(3) { attempt ->
            value = repository.load(key, group.iconPath, retry = epoch > 0 || attempt > 0)
            if (value is GroupIconLoad.Ready) return@produceState
            if (attempt < 2) delay(60_000L)
        }
    }
    val ready = loaded as? GroupIconLoad.Ready
    var reveal by remember(key) { mutableStateOf(cached != null) }
    LaunchedEffect(ready) { if (ready != null) reveal = true }
    val alpha by animateFloatAsState(if (reveal) 1f else 0f,
        tween(if (LocalHetuMotionEnabled.current && ready?.cached != true) 150 else 0), label = "configuredIconFade")
    Box(modifier.testTag("group-icon-slot:${group.name}"), contentAlignment = Alignment.Center) {
        when {
            ready != null -> Image(
                ready.bitmap.asImageBitmap(),
                "${group.name} 配置图标",
                Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }.testTag("configured-icon:${group.name}"),
                contentScale = ContentScale.Fit,
            )
            builtInBrand != null -> BuiltInBrandIcon(
                builtInBrand,
                Modifier.fillMaxSize().testTag("brand-icon:${group.name}")
                    .semantics { contentDescription = "${group.name} 彩色品牌图标" },
            )
            configured -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    if (loaded is GroupIconLoad.Failed) Icons.Rounded.BrokenImage else Icons.Rounded.Image,
                    if (loaded is GroupIconLoad.Failed) "${group.name} 图标暂未加载" else "${group.name} 图标加载中",
                    Modifier.size(20.dp),
                    tint = LocalHetuTokens.current.textSecondary,
                )
            }
            else -> Text(
                group.name.filter { it.isLetterOrDigit() }.take(2).ifBlank { "?" },
                Modifier.semantics { contentDescription = "${group.name} 未配置图标" },
                color = LocalHetuTokens.current.textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
