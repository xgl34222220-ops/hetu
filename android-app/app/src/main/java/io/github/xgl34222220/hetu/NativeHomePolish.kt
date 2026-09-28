package io.github.xgl34222220.hetu

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled
import io.github.xgl34222220.hetu.ui.LocalHetuTokens
import io.github.xgl34222220.hetu.ui.hetuPressScale
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos

/** The UI command is independent of low-level progress text and never starts a service itself. */
internal enum class HomeOperation(val title: String) {
    Start("正在启动"), Stop("正在停止"), Reload("正在重载"), Restart("正在重启"), Sync("正在确认状态")
}
internal enum class HomePhase { Running, Processing, Stopped }
internal object HomeLifecyclePresentation {
    fun phase(data: CompactHomeData): HomePhase = when {
        data.busy || data.operation != null -> HomePhase.Processing
        data.running -> HomePhase.Running
        else -> HomePhase.Stopped
    }
    fun operationTitle(data: CompactHomeData): String = data.operation?.title ?: when {
        data.message.startsWith("正在重载") -> HomeOperation.Reload.title
        data.message.startsWith("正在重启") -> HomeOperation.Restart.title
        data.message.startsWith("正在停止") -> HomeOperation.Stop.title
        data.message.startsWith("正在启动") -> HomeOperation.Start.title
        else -> HomeOperation.Sync.title
    }
    fun feedback(raw: String): String? {
        val message = raw.trim()
        if (message.isEmpty()) return null
        if (listOf("失败", "错误", "异常", "error", "exception", "denied").any { message.contains(it, true) })
            return "操作未完成，请查看详情"
        return when (message) {
            "全部刷新完成" -> message
            "重载成功", "配置已重载" -> "配置已重载"
            "代理已停止", "停止完成" -> "代理已停止"
            "启动成功", "代理已启动" -> "代理已启动"
            else -> "操作反馈已更新"
        }
    }
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.activity()
    else -> null
}

/** Paints the actual window, including system-bar regions. No inset rounded outer frame. */
@Composable
internal fun ImmersiveUiHost(content: @Composable () -> Unit) {
    val background = LocalHetuTokens.current.pageBackground
    val activity = LocalContext.current.activity()
    DisposableEffect(activity, background) {
        activity?.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(background.toArgb()))
            if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 35) {
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
            }
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = background.luminance() > .5f
                isAppearanceLightNavigationBars = background.luminance() > .5f
            }
        }
        onDispose { }
    }
    Box(Modifier.fillMaxSize().background(background).testTag("immersive-window")) {
        ModalBackdropHost12(content)
    }
}

@Composable
internal fun Modifier.nativePress(enabled: Boolean = true, label: String? = null,
    motion: Boolean = LocalHetuMotionEnabled.current, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val haptics = io.github.xgl34222220.hetu.ui.rememberHetuHaptics()
    // V18.3: interaction-driven dip (.97) with a soft rubber return; quick taps stay visible.
    return hetuPressScale(source, enabled, pressedScale = .97f, motion = motion)
        .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClickLabel = label) {
            haptics.perform(io.github.xgl34222220.hetu.ui.HetuHaptic.Tick)
            onClick()
        }
}

internal fun Modifier.diffuseCardShadow(shape: Shape): Modifier =
    shadow(4.dp, shape, clip = false, ambientColor = Color(0xFF0F172A).copy(alpha = .02f),
        spotColor = Color(0xFF0F172A).copy(alpha = .02f))
        .shadow(10.dp, shape, clip = false, ambientColor = Color(0xFF0F172A).copy(alpha = .05f),
            spotColor = Color(0xFF0F172A).copy(alpha = .05f))

@Composable
internal fun NativeStatusHero(data: CompactHomeData, toggle: () -> Unit, reload: () -> Unit,
    restart: () -> Unit, settings: () -> Unit, motion: Boolean, quickActions: (@Composable () -> Unit)? = null) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val phase = HomeLifecyclePresentation.phase(data)
    val processing = phase == HomePhase.Processing
    // V18.3: haptics describe the OUTCOME of a start/stop/reload, not just the tap.
    val haptics = io.github.xgl34222220.hetu.ui.rememberHetuHaptics()
    var previousPhase by remember { mutableStateOf(phase) }
    LaunchedEffect(phase) {
        if (previousPhase == HomePhase.Processing && phase != HomePhase.Processing) {
            val failed = HomeLifecyclePresentation.feedback(data.message) == "操作未完成，请查看详情"
            haptics.perform(when {
                failed -> io.github.xgl34222220.hetu.ui.HetuHaptic.Reject
                phase == HomePhase.Running -> io.github.xgl34222220.hetu.ui.HetuHaptic.Confirm
                else -> io.github.xgl34222220.hetu.ui.HetuHaptic.ToggleOff
            })
        }
        previousPhase = phase
    }
    val title = when (phase) {
        HomePhase.Running -> "运行中"
        HomePhase.Stopped -> "已停止"
        HomePhase.Processing -> HomeLifecyclePresentation.operationTitle(data)
    }
    val colors = when (phase) {
        HomePhase.Running -> if (dark) listOf(Color(0xFF17243F), Color(0xFF262C4E), Color(0xFF8AB4FF))
            else listOf(Color(0xFFEEF2FF), Color(0xFFDCE6FC), Color(0xFF2563EB))
        HomePhase.Processing -> if (dark) listOf(Color(0xFF392B16), Color(0xFF483515), Color(0xFFFBBF24))
            else listOf(Color(0xFFFEF3C7), Color(0xFFFDE68A), Color(0xFFD97706))
        HomePhase.Stopped -> if (dark) listOf(Color(0xFF1B2330), Color(0xFF222C3A), Color(0xFF9AA9BD))
            else listOf(Color(0xFFF8FAFC), Color(0xFFF1F5F9), Color(0xFF64748B))
    }
    val top by animateColorAsState(colors[0], tween(if (motion) 400 else 0, easing = FastOutSlowInEasing), label = "hero-top")
    val bottom by animateColorAsState(colors[1], tween(if (motion) 400 else 0, easing = FastOutSlowInEasing), label = "hero-bottom")
    val accent by animateColorAsState(colors[2], tween(if (motion) 400 else 0), label = "hero-accent")
    val text = if (dark) Color(0xFFE2E8F0) else Color(0xFF1E293B)
    val muted = if (dark) Color(0xFFACB7CA) else Color(0xFF64748B)
    val shape = HomeContinuousShape(26.dp)
    val compact = LocalHomeCompactSpacing.current
    Box(Modifier.fillMaxWidth().testTag("home-hero").semantics { stateDescription = title }
        .diffuseCardShadow(shape).clip(shape).background(Brush.linearGradient(listOf(top, lerp(top, bottom, .45f), bottom)))
        .drawBehind {
            drawRect(Brush.radialGradient(listOf(accent.copy(alpha = .10f), Color.Transparent),
                center = Offset(size.width * .9f, size.height * .12f), radius = size.width * .65f))
            drawRect(Brush.radialGradient(listOf(top.copy(alpha = .60f), Color.Transparent),
                center = Offset(size.width * .15f, size.height * .9f), radius = size.width * .8f))
        }) {
        Column(Modifier.fillMaxWidth().padding(if (LocalHomeShortSpacing.current) 14.dp else if (compact) 16.dp else 18.dp)) {
            Row(Modifier.fillMaxWidth().testTag("hero-information"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).testTag("hero-information-text"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HeroLiveDot(accent, live = phase == HomePhase.Running && motion)
                        Text(title, color = accent, fontSize = 21.sp, lineHeight = 28.sp,
                            fontWeight = FontWeight.Black, modifier = Modifier.testTag("hero-status-title")
                                .semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    Text(when {
                        processing -> "正在与内核通信…"
                        data.running -> CompactHomeFormat.uptime(data.uptimeSeconds)
                        else -> "服务未启动"
                    }, Modifier.padding(start = 22.dp).testTag("hero-uptime"),
                        color = if (dark) muted else Color(0xFF334155), fontSize = 14.sp,
                        lineHeight = 20.sp, fontWeight = FontWeight.Bold)
                    Text(listOf(data.core, data.mode).filter(String::isNotBlank).joinToString(" · "),
                        Modifier.padding(start = 22.dp, top = 3.dp).testTag("hero-core"),
                        color = muted, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
                    Text(data.config.ifBlank { "尚未选择配置" },
                        Modifier.padding(start = 22.dp).testTag("hero-config"),
                        color = text, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Default, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(10.dp))
                HeroStatusGlyph(phase, accent, motion)
            }
            if (data.pendingSettings && data.running && !processing) {
                Text("设置待生效 · 查看", Modifier.heightIn(min = 48.dp).nativePress(onClick = settings)
                    .wrapContentHeight(), color = accent, fontSize = 12.sp)
            }
            Spacer(Modifier.height(if (LocalHomeShortSpacing.current) 8.dp else if (compact) 10.dp else 16.dp))
            // Key the whole controller by lifecycle, not by raw progress messages.
            AnimatedContent(phase, transitionSpec = {
                (fadeIn(tween(if (motion) 250 else 0)) + slideInVertically(tween(if (motion) 250 else 0)) { it / 8 })
                    .togetherWith(fadeOut(tween(if (motion) 150 else 0)) +
                        slideOutVertically(tween(if (motion) 150 else 0)) { -it / 8 })
                    .using(SizeTransform(clip = false) { _, _ -> tween(if (motion) 250 else 0) })
            }, modifier = Modifier.fillMaxWidth(), label = "hero-pill-morph") { shown ->
                Box(Modifier.fillMaxWidth().testTag("hero-capsule-surface")) {
                Row(Modifier.fillMaxWidth().clip(HomeContinuousShape(20.dp))
                    .background(if (dark) Color.White.copy(alpha = .09f) else Color.White.copy(alpha = .95f))
                    .padding(4.dp).heightIn(min = 48.dp).testTag("home-control-pill"),
                    verticalAlignment = Alignment.CenterVertically) {
                    val blue = if (dark) Color(0xFF8AB4FF) else Color(0xFF2563EB)
                    when (shown) {
                        HomePhase.Running -> {
                            PillAction("重载", "home-reload", blue, Modifier.weight(1f), !processing && phase == shown, motion, reload)
                            Box(Modifier.width(1.dp).height(18.dp).background(muted.copy(alpha = .12f)))
                            PillAction("停止", "home-toggle", if (dark) Color(0xFFFDA4AF) else Color(0xFFE11D48),
                                Modifier.weight(1f), !processing && phase == shown, motion, toggle)
                            Box(Modifier.width(1.dp).height(18.dp).background(muted.copy(alpha = .12f)))
                            PillAction("重启", "home-restart", if (dark) Color(0xFFFBBF24) else Color(0xFFD97706),
                                Modifier.weight(1f), !processing && phase == shown, motion, restart)
                        }
                        HomePhase.Stopped -> PillAction("启动服务", "home-toggle", blue, Modifier.weight(1f), !processing && phase == shown, motion, toggle)
                        HomePhase.Processing -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("home-processing").semantics { stateDescription = title; liveRegion = LiveRegionMode.Polite },
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            NativeSpinner(accent, motion, Modifier.size(18.dp))
                            Spacer(Modifier.width(9.dp))
                            Text(title, color = accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            }
            if (quickActions != null) {
                Spacer(Modifier.height(8.dp))
                quickActions()
            }
        }
        if (processing) ProcessingShimmer(accent, motion, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun PillAction(label: String, tag: String, tint: Color, modifier: Modifier,
    enabled: Boolean, motion: Boolean, click: () -> Unit) {
    Box(modifier.heightIn(min = 48.dp).testTag(tag).clip(CircleShape)
        .nativePress(enabled, label, motion, click).padding(horizontal = 4.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = tint, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HeroStatusGlyph(phase: HomePhase, color: Color, motion: Boolean) {
    // Normal density follows the reference; accessibility text gets space before decoration.
    val sizeDp = if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) 56.dp else 76.dp
    // V18.3: the ring sweeps and the check / dash is drawn in whenever the state settles.
    val draw = remember { Animatable(1f) }
    var firstPhase by remember { mutableStateOf(true) }
    LaunchedEffect(phase, motion) {
        if (phase == HomePhase.Processing) return@LaunchedEffect
        if (!motion || firstPhase) { draw.snapTo(1f); firstPhase = false; return@LaunchedEffect }
        draw.snapTo(0f)
        draw.animateTo(1f, tween(560, easing = io.github.xgl34222220.hetu.ui.HetuMotion.EmphasizedDecelerate))
    }
    Box(Modifier.size(sizeDp).testTag("hero-status-glyph"), contentAlignment = Alignment.Center) {
        if (phase == HomePhase.Processing) NativeSpinner(color, motion, Modifier.fillMaxSize().padding(8.dp))
        else Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val p = draw.value.coerceIn(0f, 1f)
            drawCircle(color.copy(alpha = .18f), radius = w * .4f, style = Stroke(w * .1f))
            if (phase == HomePhase.Running) {
                val sweep = (p / .6f).coerceIn(0f, 1f)
                drawArc(color, -90f, 270f * sweep, false, Offset(w * .1f, w * .1f), Size(w * .8f, w * .8f),
                    style = Stroke(w * .1f, cap = StrokeCap.Round))
                val check = ((p - .35f) / .65f).coerceIn(0f, 1f)
                if (check > 0f) {
                    val full = Path().apply { moveTo(w * .32f, w * .5f); lineTo(w * .45f, w * .63f); lineTo(w * .70f, w * .36f) }
                    val measure = PathMeasure().apply { setPath(full, false) }
                    val partial = Path()
                    measure.getSegment(0f, measure.length * check, partial, true)
                    drawPath(partial, color, style = Stroke(w * .11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            } else {
                val half = w * .15f * p
                drawLine(color, Offset(w * .5f - half, w * .5f), Offset(w * .5f + half, w * .5f), w * .08f, StrokeCap.Round)
            }
        }
    }
}

/** Running state "heartbeat": a ring expands from the status dot and fades. Draw-phase only. */
@Composable
private fun HeroLiveDot(color: Color, live: Boolean) {
    val ripple = if (live) {
        val transition = rememberInfiniteTransition(label = "hero-live")
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearOutSlowInEasing)), label = "hero-live-ripple").value
    } else 0f
    Box(Modifier.size(12.dp).drawBehind {
        if (live) {
            val r = size.minDimension / 2f
            drawCircle(color.copy(alpha = .38f * (1f - ripple)), radius = r + r * 1.1f * ripple)
        }
        drawCircle(color, radius = size.minDimension / 2f)
    })
}

@Composable
internal fun NativeSpinner(color: Color, motion: Boolean, modifier: Modifier = Modifier) {
    val angle = if (motion) {
        val transition = rememberInfiniteTransition(label = "status-spinner")
        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "status-angle").value
    } else 0f
    Canvas(modifier.graphicsLayer { rotationZ = angle }) {
        val stroke = (size.minDimension * .085f).coerceAtLeast(1.5.dp.toPx())
        drawCircle(color.copy(alpha = .17f), radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
        drawArc(color, -90f, 250f, false, Offset(stroke / 2, stroke / 2),
            Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun ProcessingShimmer(color: Color, motion: Boolean, modifier: Modifier) {
    val offset = if (motion) {
        val transition = rememberInfiniteTransition(label = "processing-shimmer")
        transition.animateFloat(-.5f, 1.5f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "shimmer-offset").value
    } else .25f
    Canvas(modifier.fillMaxWidth().height(2.dp).testTag("home-processing-shimmer")) {
        drawRect(color.copy(alpha = .14f))
        val start = offset * size.width
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, color.copy(alpha = .9f), Color.Transparent),
            startX = start, endX = start + size.width * .5f), topLeft = Offset(start, 0f), size = Size(size.width * .5f, size.height))
    }
}

@Composable
internal fun LoadingWaveDots(motion: Boolean, color: Color, modifier: Modifier = Modifier) {
    val phase = if (motion) {
        val transition = rememberInfiniteTransition(label = "latency-wave")
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "dot-phase").value
    } else .4f
    Row(modifier.heightIn(min = 30.dp).semantics { text = AnnotatedString("···"); contentDescription = "正在测量延迟" },
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val wave = ((1 - cos(2 * PI * (phase - index * .114))) / 2).toFloat()
            Box(Modifier.size(5.dp).graphicsLayer {
                alpha = .2f + .8f * wave; scaleX = .8f + .4f * wave; scaleY = scaleX
                translationY = -2.dp.toPx() * wave
            }.background(color, CircleShape))
        }
    }
}

/** ModalBottomSheet supplies drag, focus, back handling, accessibility and gesture-bar insets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeDetailsSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val sheet = rememberInteractiveSheetState()
    val scope = rememberCoroutineScope()
    val t = LocalHetuTokens.current
    MotionModalSheet12(onDismissRequest = onDismiss, sheetState = sheet, sheetGesturesEnabled = true,
        shape = SheetShape12(32.dp),
        containerColor = t.cardBackground, contentColor = t.textPrimary,
        scrimColor = Color(0xFF0F172A).copy(alpha = .4f), tonalElevation = 0.dp,
        modifier = Modifier.testTag("native-details-sheet"),
        dragHandle = { Box(Modifier.fillMaxWidth().height(48.dp).testTag("sheet-drag-handle")
            .semantics { contentDescription = "下拉关闭" }, contentAlignment = Alignment.Center) {
            Box(Modifier.width(40.dp).height(6.dp).background(t.textSecondary.copy(alpha = .28f), CircleShape))
        } }) {
        Column(Modifier.sheetReveal12().fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, Modifier.fillMaxWidth(), color = t.textPrimary, fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            content()
            Spacer(Modifier.height(2.dp))
        }
        Button(onClick = { scope.launch { sheet.hide(); onDismiss() } },
            modifier = Modifier.sheetReveal12(1).padding(horizontal = 24.dp, vertical = 16.dp).fillMaxWidth().heightIn(min = 52.dp)
                .testTag("sheet-confirm"), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB), contentColor = Color.White)) {
            Text("确定", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun NativeNetworkDetails(data: CompactHomeData, onDismiss: () -> Unit) {
    val t = LocalHetuTokens.current
    NativeDetailsSheet("网络详情", onDismiss) {
        val flag = CompactHomeFormat.flag(data.countryCode)
        if (flag.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.clip(RoundedCornerShape(7.dp)).background(t.elevatedCardBackground)
                .padding(horizontal = 7.dp, vertical = 3.dp)) { Text(flag, fontSize = 22.sp) }
            Text(data.region.takeUnless { it.isBlank() || it == "—" } ?: data.countryCode,
                color = t.textPrimary, fontWeight = FontWeight.SemiBold)
        }
        NetworkDetailRow(if (data.wan.contains(':')) "IPv6" else "IPv4", data.wan)
        NetworkDetailRow("地区", data.region)
        NetworkDetailRow("ISP", data.isp)
        NetworkDetailRow("ASN", data.asn)
        HorizontalDivider(color = t.textSecondary.copy(alpha = .1f))
        NetworkDetailRow("LAN", data.lan)
        NetworkDetailRow("接口", data.lanInterface)
        NetworkDetailRow("活动连接", data.connections.toString())
    }
}

@Composable
private fun NetworkDetailRow(label: String, raw: String) {
    val t = LocalHetuTokens.current
    val value = raw.takeUnless { it.isBlank() || it == "—" } ?: "未提供"
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.widthIn(min = 56.dp), color = t.textSecondary, fontSize = 13.sp)
        Spacer(Modifier.width(12.dp))
        SelectionContainer(Modifier.weight(1f)) {
            Text(value, Modifier.fillMaxWidth(), color = t.textPrimary, textAlign = TextAlign.End,
                fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 21.sp,
                style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"))
        }
    }
}


/** Presentation state only: never invokes proxy, routing or network operations. */
@Stable
internal class NetworkModeTransition(initialLan: Boolean) {
    var displayedLan by mutableStateOf(initialLan)
        internal set
    var leaving by mutableStateOf(false)
        internal set
    val progress = Animatable(1f)
}

/**
 * Sequential out-in, not Crossfade/AnimatedContent. A new target cancels the old coroutine;
 * the latest target wins, and a stopped lifecycle/reduced-motion request settles immediately.
 * Exactly one subtree is composed throughout both halves of the transition.
 */
@Composable
internal fun rememberNetworkModeTransition(targetLan: Boolean, motion: Boolean): NetworkModeTransition {
    val state = remember { NetworkModeTransition(targetLan) }
    LaunchedEffect(targetLan, motion) {
        if (!motion) {
            state.displayedLan = targetLan
            state.leaving = false
            state.progress.snapTo(1f)
        } else {
            if (state.displayedLan != targetLan) {
                state.leaving = true
                state.progress.animateTo(0f, tween(110, easing = FastOutSlowInEasing))
                state.displayedLan = targetLan
                state.leaving = false
                state.progress.snapTo(0f)
            } else state.leaving = false
            state.progress.animateTo(1f, tween(220, easing = CubicBezierEasing(.34f, 1.56f, .64f, 1f)))
        }
    }
    return state
}