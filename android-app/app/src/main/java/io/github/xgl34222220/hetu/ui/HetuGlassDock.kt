package io.github.xgl34222220.hetu.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.snap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import io.github.xgl34222220.hetu.ui.glass.liquidGlassLens
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.colorControls
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.squircle.squircleClip

data class DockItem(val label: String, val icon: ImageVector, val opticalScale: Float = 1f)

/** Font scaling adds room for labels; navigation insets are owned by the outer dock once. */
@Composable
internal fun hetuDockBodyHeight(): Dp = maxOf(64.dp, 45.dp + with(LocalDensity.current) { 14.sp.toDp() })

@Composable
internal fun hetuDockOuterHeight(floating: Boolean): Dp = hetuDockBodyHeight() +
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + if (floating) 8.dp else 0.dp

/**
 * V20.45: real MIUIX drawBackdrop liquid dock. Uses miuix-blur drawBackdrop/blur/highlight
 * plus the runtime refraction lens instead of an opaque Card/textureBlur shell.
 * Shared MIUIX floating dock. This mirrors LuoShu's three-layer implementation:
 * page backdrop -> refractive shell -> moving refractive lens. Icons and labels are
 * always siblings above the shader layers, preventing OEM compositors from turning
 * their offscreen buffers into white rectangles.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun HetuGlassDock(
    items: List<DockItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    hazeState: HazeState,
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    onHeightChanged: (Dp) -> Unit = {},
) {
    if (items.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalHetuTokens.current
    val dark = scheme.background.luminance() < .5f
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val density = LocalDensity.current
    val bodyHeight = hetuDockBodyHeight()
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("hetu", 0) }
    var floating by remember { mutableStateOf(prefs.getBoolean("floatingBottomBar", true)) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { shared, key ->
            when (key) {
                "floatingBottomBar" -> floating = shared.getBoolean(key, true)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val shape = if (floating) RoundedCornerShape(31.dp) else RoundedCornerShape(topStart = 31.dp, topEnd = 31.dp)
    // Never render a translucent glass shell without a real blur/backdrop behind it.
    // That fallback was the source of the opaque white slab when either appearance switch was disabled.
    val renderGlass = rememberHetuGlassEnabled()
    // Liquid-glass pass: the capsule is faked (translucent gradient, bright rim, soft shadow), so
    // no page content is recorded or blurred per frame while lists scroll beneath it.
    val runtimeLiquid = false
    val activeHaze = false
    val hazeModifier = if (activeHaze) {
        Modifier.hazeEffect(state = hazeState, style = HazeMaterials.ultraThin()) {
            blurRadius = 32.dp
            noiseFactor = .006f
        }
    } else Modifier
    val glassBrush = when {
        renderGlass && dark -> Brush.verticalGradient(
            listOf(Color(0xFF2A3342).copy(alpha = .90f), Color(0xFF1B222D).copy(alpha = .88f)),
        )
        renderGlass -> Brush.verticalGradient(
            listOf(Color.White.copy(alpha = .86f), Color(0xFFE9EEF5).copy(alpha = .80f)),
        )
        else -> Brush.verticalGradient(
            if (dark) listOf(Color(0xFF1A2230), Color(0xFF151C27))
            else listOf(Color(0xFFF4F5F7), Color(0xFFE8EEF6)),
        )
    }
    val shellTint = if (dark) scheme.surface.copy(alpha = .22f) else Color.White.copy(alpha = .14f)
    val liquidShellModifier = if (runtimeLiquid) {
        Modifier.drawBackdrop(
            backdrop = requireNotNull(backdrop),
            shape = { shape },
            effects = {
                padding = maxOf(padding, 30.dp.toPx())
                colorControls(
                    brightness = if (dark) -.015f else .025f,
                    contrast = 1.05f,
                    saturation = 1.04f,
                )
                blur(26.dp.toPx(), 26.dp.toPx())
                liquidGlassLens(
                    refractionHeight = 14.dp.toPx(),
                    refractionAmount = 10.dp.toPx(),
                    depthEffect = true,
                    chromaticAberration = .045f,
                )
            },
            highlight = {
                (if (dark) Highlight.GlassStrokeSmallDark else Highlight.GlassStrokeSmallLight)
                    .copy(alpha = if (dark) .22f else .42f)
            },
            onDrawSurface = {
                drawRect(shellTint)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (dark) .06f else .20f),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * .16f, 0f),
                        radius = size.width * .70f,
                    ),
                )
            },
        )
    } else {
        Modifier
            .then(hazeModifier)
            .background(glassBrush)
            .drawBehind {
                if (renderGlass) {
                    drawRoundRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = if (dark) .08f else .24f),
                                Color.Transparent,
                            ),
                            center = Offset(size.width * .18f, 0f),
                            radius = size.width * .72f,
                        ),
                        cornerRadius = CornerRadius(size.height / 2f),
                    )
                }
            }
    }

    val rimBrush = Brush.verticalGradient(listOf(
        Color.White.copy(alpha = if (dark) .22f else .95f),
        Color.White.copy(alpha = if (dark) .05f else .40f),
    ))
    val shadowTint = if (dark) Color.Black.copy(alpha = .34f) else Color(0xFF4A5A74).copy(alpha = .12f)
    Box(
        modifier = modifier
            .onSizeChanged { onHeightChanged(with(density) { it.height.toDp() }) }
            .background(Color.Transparent)
            .then(if (floating) Modifier.padding(horizontal = 24.dp).padding(bottom = bottomInset + 8.dp) else Modifier)
            .fillMaxWidth()
            .height(bodyHeight + if (floating) 0.dp else bottomInset).testTag("hetu-dock"),
    ) {
        // Static glass capsule: soft shadow (two offset translucent outlines), frosted fill, bright rim.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    if (!renderGlass) return@drawBehind
                    val r = if (floating) CornerRadius(31.dp.toPx()) else CornerRadius(0f)
                    drawRoundRect(shadowTint, Offset(0f, 3.dp.toPx()), size, r)
                    drawRoundRect(shadowTint.copy(alpha = shadowTint.alpha * .5f), Offset(0f, 7.dp.toPx()), size, r)
                }
                .clip(shape)
                .then(liquidShellModifier)
                .border(1.dp, rimBrush, shape),
        )

        DockItems(
            items = items,
            selected = selected,
            onSelect = onSelect,
            itemHeight = bodyHeight - 8.dp,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 4.dp, top = 4.dp, end = 4.dp, bottom = if (floating) 4.dp else bottomInset + 4.dp),
            indicatorColor = if (dark) Color.White.copy(alpha = .13f) else Color.White.copy(alpha = .92f),
            indicatorBorderColor = if (dark) Color.White.copy(alpha = .22f) else Color.White,
            indicatorShadow = 0.dp,
            selectedColor = if (dark) Color(0xFF8AB4FF) else Color(0xFF1267D6),
            unselectedColor = if (dark) Color(0xFFC9D1DC) else Color(0xFF3A4352),
            liquidGlass = renderGlass,
            indicatorBackdrop = null,
            dark = dark,
        )
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
private fun DockItems(
    items: List<DockItem>, selected: Int, onSelect: (Int) -> Unit,
    itemHeight: androidx.compose.ui.unit.Dp, modifier: Modifier,
    indicatorColor: Color, indicatorBorderColor: Color, indicatorShadow: androidx.compose.ui.unit.Dp,
    selectedColor: Color, unselectedColor: Color, liquidGlass: Boolean,
    indicatorBackdrop: LayerBackdrop?, dark: Boolean,
) {
    // V19: reference-style dock. Every destination shows icon + label (equal slots);
    // ONE glass lens travels between slots. Its leading edge is stiffer than its trailing
    // edge, so the lens stretches in flight, thins slightly under tension and settles.
    val motion = LocalHetuMotionEnabled.current && android.animation.ValueAnimator.areAnimatorsEnabled()
    val target = selected.coerceIn(0, items.lastIndex)
    val haptics = rememberHetuHaptics()
    val slots = remember(items.size) { mutableStateListOf<Pair<Float, Float>>().apply { repeat(items.size) { add(0f to 0f) } } }
    val lensLeft = remember { Animatable(0f) }
    val lensRight = remember { Animatable(0f) }
    val lensPress = remember { Animatable(1f) }
    var lensPlaced by remember { mutableStateOf(false) }
    val slot = slots.getOrNull(target) ?: (0f to 0f)
    LaunchedEffect(slot, motion) {
        if (slot.second <= 0f) return@LaunchedEffect
        if (!lensPlaced || !motion) {
            lensLeft.snapTo(slot.first); lensRight.snapTo(slot.first + slot.second); lensPlaced = true
            return@LaunchedEffect
        }
        val forward = slot.first > lensLeft.value
        launch { lensLeft.animateTo(slot.first, spring(dampingRatio = .74f, stiffness = if (forward) 210f else 560f)) }
        launch { lensRight.animateTo(slot.first + slot.second, spring(dampingRatio = .74f, stiffness = if (forward) 560f else 210f)) }
    }
    val highlight = Color.White.copy(alpha = if (dark) .10f else .70f)
    Row(
        modifier.selectableGroup().drawBehind {
            if (!lensPlaced || lensRight.value <= lensLeft.value) return@drawBehind
            val width = lensRight.value - lensLeft.value
            val settled = slot.second.coerceAtLeast(1f)
            val stretch = ((width - settled) / settled).coerceIn(0f, 1.2f)
            val h = size.height * .90f * (1f - .10f * stretch) * lensPress.value
            val w = width * lensPress.value
            val x = lensLeft.value + (width - w) / 2f
            val y = (size.height - h) / 2f
            val r = CornerRadius(h / 2f)
            // Raised lens: a soft shadow under it, then a frosted fill that is brighter at the top.
            drawRoundRect(Color.Black.copy(alpha = if (dark) .28f else .07f), Offset(x, y + 2.dp.toPx()), androidx.compose.ui.geometry.Size(w, h), r)
            drawRoundRect(Color.Black.copy(alpha = if (dark) .12f else .035f), Offset(x, y + 5.dp.toPx()), androidx.compose.ui.geometry.Size(w, h), r)
            drawRoundRect(indicatorColor, Offset(x, y), androidx.compose.ui.geometry.Size(w, h), r)
            drawRoundRect(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) .10f else .55f), Color.Transparent), startY = y, endY = y + h),
                Offset(x, y), androidx.compose.ui.geometry.Size(w, h), r,
            )
            // Specular rim: bright top edge fading down, like the reference lens.
            drawRoundRect(
                Brush.verticalGradient(listOf(highlight, Color.Transparent), startY = y, endY = y + h * .55f),
                Offset(x, y), androidx.compose.ui.geometry.Size(w, h), r,
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx()),
            )
            drawRoundRect(indicatorBorderColor, Offset(x, y), androidx.compose.ui.geometry.Size(w, h), r,
                style = androidx.compose.ui.graphics.drawscope.Stroke(.8.dp.toPx()))
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, item ->
            key(item.label) {
                val active = index == target
                val interaction = remember { MutableInteractionSource() }
                val color by animateColorAsState(if (active) selectedColor else unselectedColor,
                    if (motion) tween(200) else snap(), label = "dock-tint-$index")
                val iconPop = remember { Animatable(1f) }
                var wasActive by remember { mutableStateOf(active) }
                LaunchedEffect(active, motion) {
                    if (!motion || !active) iconPop.snapTo(1f)
                    if (active && !wasActive && motion) {
                        iconPop.snapTo(1f)
                        iconPop.animateTo(1.14f, tween(120, easing = HetuMotion.Standard))
                        iconPop.animateTo(1f, spring(dampingRatio = .45f, stiffness = 420f))
                    }
                    wasActive = active
                }
                // Pressing the active slot squeezes the lens itself (tactile "glass" feel).
                LaunchedEffect(interaction, active, motion) {
                    lensPress.snapTo(1f)
                    if (!active || !motion) return@LaunchedEffect
                    interaction.interactions.collect { event ->
                        when (event) {
                            is androidx.compose.foundation.interaction.PressInteraction.Press ->
                                launch { lensPress.animateTo(.94f, tween(90, easing = HetuMotion.Standard)) }
                            is androidx.compose.foundation.interaction.PressInteraction.Release,
                            is androidx.compose.foundation.interaction.PressInteraction.Cancel ->
                                launch { lensPress.animateTo(1f, spring(dampingRatio = .5f, stiffness = 480f)) }
                            else -> Unit
                        }
                        Unit
                    }
                }
                Column(
                    Modifier.weight(1f).height(itemHeight).testTag("dock-tab-$index")
                        .onPlaced { c ->
                            // Placement repeats on every frame the dock slides; write only real changes
                            // so a moving dock does not recompose its items each frame.
                            val placed = c.positionInParent().x to c.size.width.toFloat()
                            if (index < slots.size && slots[index] != placed) slots[index] = placed
                        }
                        .hetuPressScale(interaction, pressedScale = .92f, motion = motion && !active)
                        .selectable(active, role = Role.Tab, interactionSource = interaction, indication = null,
                            onClick = { if (!active) haptics.perform(HetuHaptic.Tick); onSelect(index) })
                        .semantics { contentDescription = item.label },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(item.icon, null, Modifier.size(28.dp).graphicsLayer {
                        scaleX = item.opticalScale * iconPop.value; scaleY = item.opticalScale * iconPop.value
                    }, tint = color)
                    Spacer(Modifier.height(1.dp))
                    Text(item.label, (if (active) Modifier.testTag("dock-active-label") else Modifier).padding(horizontal = 2.dp),
                        color = color, fontSize = 12.sp, lineHeight = 14.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}
