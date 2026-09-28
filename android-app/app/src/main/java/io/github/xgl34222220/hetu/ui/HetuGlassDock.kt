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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.snap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.squircle.squircleClip

data class DockItem(val label: String, val icon: ImageVector, val opticalScale: Float = 1f)

/**
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
) {
    if (items.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalHetuTokens.current
    val dark = scheme.background.luminance() < .5f
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("hetu", 0) }
    var floating by remember { mutableStateOf(prefs.getBoolean("floatingBottomBar", true)) }
    var enableBlur by remember { mutableStateOf(prefs.getBoolean("enableBlur", true)) }
    var activeGlass by remember { mutableStateOf(prefs.getBoolean("liquidGlass", true)) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { shared, key ->
            when (key) {
                "floatingBottomBar" -> floating = shared.getBoolean(key, true)
                "enableBlur" -> enableBlur = shared.getBoolean(key, true)
                "liquidGlass" -> activeGlass = shared.getBoolean(key, true)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val shape = if (floating) RoundedCornerShape(32.dp) else RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    // Never render a translucent glass shell without a real blur/backdrop behind it.
    // That fallback was the source of the opaque white slab when either appearance switch was disabled.
    val renderGlass = activeGlass && enableBlur
    val runtimeLiquid = renderGlass && backdrop != null && isRuntimeShaderSupported()
    val activeHaze = renderGlass && !runtimeLiquid
    val dockSurfaceBackdrop = rememberLayerBackdrop()
    val hazeModifier = if (activeHaze) {
        Modifier.hazeEffect(state = hazeState, style = HazeMaterials.ultraThin()) {
            blurRadius = 20.dp
            noiseFactor = .006f
        }
    } else Modifier
    val glassBrush = when {
        renderGlass && dark -> Brush.verticalGradient(
            listOf(Color.White.copy(alpha = .085f), Color(0xFF60A5FA).copy(alpha = .035f)),
        )
        renderGlass -> Brush.verticalGradient(
            listOf(Color(0xFFF8FBFF).copy(alpha = .58f), Color(0xFFEAF2FF).copy(alpha = .34f)),
        )
        else -> Brush.verticalGradient(
            if (dark) listOf(Color(0xFF1A2230), Color(0xFF151C27))
            else listOf(Color(0xFFF1F5F9), Color(0xFFE8EEF6)),
        )
    }
    val shellTint = if (dark) scheme.surface.copy(alpha = .26f) else Color(0xFFF8FBFF).copy(alpha = .34f)
    val liquidShellModifier = if (runtimeLiquid) {
        Modifier.drawBackdrop(
            backdrop = requireNotNull(backdrop),
            shape = { shape },
            effects = {
                padding = maxOf(padding, 30.dp.toPx())
                colorControls(
                    brightness = if (dark) -.015f else .025f,
                    contrast = 1.05f,
                    saturation = 1.15f,
                )
                blur(24.dp.toPx(), 24.dp.toPx())
                liquidGlassLens(
                    refractionHeight = 12.dp.toPx(),
                    refractionAmount = 8.dp.toPx(),
                    depthEffect = true,
                    chromaticAberration = .045f,
                )
            },
            highlight = {
                (if (dark) Highlight.GlassStrokeSmallDark else Highlight.GlassStrokeSmallLight)
                    .copy(alpha = if (dark) .16f else .24f)
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

    Box(
        modifier = modifier
            .background(Color.Transparent)
            .then(if (floating) Modifier.padding(horizontal = 24.dp).padding(bottom = bottomInset + 12.dp) else Modifier)
            .fillMaxWidth()
            .height(60.dp + if (floating) 0.dp else bottomInset).testTag("hetu-dock"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .shadow(
                    if (floating) 14.dp else 4.dp,
                    shape,
                    clip = false,
                    ambientColor = Color(0xFF0F172A).copy(alpha = if (dark) .12f else .035f),
                    spotColor = Color(0xFF0F172A).copy(alpha = if (dark) .16f else .075f),
                )
                .clip(shape)
                .then(if (runtimeLiquid) Modifier.layerBackdrop(dockSurfaceBackdrop) else Modifier)
                .then(liquidShellModifier),
        )

        DockItems(
            items = items,
            selected = selected,
            onSelect = onSelect,
            itemHeight = 48.dp,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 6.dp, top = 6.dp, end = 6.dp, bottom = if (floating) 6.dp else bottomInset + 6.dp),
            indicatorColor = if (dark) Color(0xFF233D64) else Color(0xFFE7F1FF),
            indicatorBorderColor = if (dark) Color(0xFF60A5FA).copy(alpha = .22f) else Color(0xFF2563EB).copy(alpha = .18f),
            indicatorShadow = 0.dp,
            selectedColor = if (dark) Color(0xFF8AB4FF) else Color(0xFF2563EB),
            unselectedColor = scheme.onSurfaceVariant.copy(alpha = .90f),
            liquidGlass = renderGlass,
            indicatorBackdrop = dockSurfaceBackdrop.takeIf { runtimeLiquid },
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
    val motion = LocalHetuMotionEnabled.current && android.animation.ValueAnimator.areAnimatorsEnabled()
    val target = selected.coerceIn(0, items.lastIndex)
    val haptics = rememberHetuHaptics()
    Row(modifier.selectableGroup(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { index, item ->
            key(item.label) {
                val active = index == target
                val interaction = remember { MutableInteractionSource() }
                val weight by animateFloatAsState(if (active) 1.65f else 1f,
                    if (motion) spring(dampingRatio = .84f, stiffness = 300f) else snap(), label = "dock-weight-$index")
                // V18.3: icon "pop" when a tab becomes active (not on first composition).
                val iconPop = remember { Animatable(1f) }
                var wasActive by remember { mutableStateOf(active) }
                LaunchedEffect(active) {
                    if (active && !wasActive && motion) {
                        iconPop.snapTo(1f)
                        iconPop.animateTo(1.16f, tween(110, easing = HetuMotion.Standard))
                        iconPop.animateTo(1f, spring(dampingRatio = .45f, stiffness = 420f))
                    }
                    wasActive = active
                }
                val fill by animateColorAsState(if (active) indicatorColor else Color.Transparent,
                    if (motion) tween(220) else snap(), label = "dock-fill-$index")
                val color by animateColorAsState(if (active) selectedColor else unselectedColor,
                    if (motion) tween(180) else snap(), label = "dock-tint-$index")
                Row(Modifier.weight(weight).height(itemHeight).testTag("dock-tab-$index")
                    .hetuPressScale(interaction, pressedScale = .97f, motion = motion)
                    .clip(RoundedCornerShape(26.dp))
                    // Fill is on the foreground sibling, outside every backdrop/shader layer.
                    .drawBehind {
                        val inset = 6.dp.toPx()
                        val h = (size.height - 2 * inset).coerceAtLeast(0f)
                        drawRoundRect(fill, topLeft = Offset(0f,inset),
                            size = androidx.compose.ui.geometry.Size(size.width,h), cornerRadius = CornerRadius(h/2f))
                    }
                    .selectable(active, role = Role.Tab, interactionSource = interaction, indication = null,
                        onClick = { if (!active) { haptics.perform(HetuHaptic.Tick); onSelect(index) } })
                    .semantics { contentDescription = item.label }
                    .padding(horizontal = 6.dp), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(item.icon, null, Modifier.size(21.dp).graphicsLayer {
                        scaleX = item.opticalScale * iconPop.value; scaleY = item.opticalScale * iconPop.value
                    }, tint = color)
                    if (active) {
                        // V18.3: the label slides out of the icon while the capsule widens,
                        // instead of popping in at full opacity. Still exactly one label node.
                        val labelIn = remember { Animatable(if (motion) 0f else 1f) }
                        LaunchedEffect(Unit) {
                            if (motion) labelIn.animateTo(1f, tween(240, delayMillis = 40, easing = HetuMotion.EmphasizedDecelerate))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(item.label, Modifier.testTag("dock-active-label").graphicsLayer {
                            val p = labelIn.value.coerceIn(0f, 1f)
                            alpha = p
                            translationX = (1f - p) * -8.dp.toPx()
                        }, color = color,
                            fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
    }
}
