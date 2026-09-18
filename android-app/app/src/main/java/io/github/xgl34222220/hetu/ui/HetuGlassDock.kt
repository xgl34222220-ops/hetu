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
    val shape = if (floating) RoundedCornerShape(31.dp) else RoundedCornerShape(topStart = 31.dp, topEnd = 31.dp)
    // Never render a translucent glass shell without a real blur/backdrop behind it.
    // That fallback was the source of the opaque white slab when either appearance switch was disabled.
    val renderGlass = activeGlass && enableBlur
    val runtimeLiquid = renderGlass && backdrop != null && isRuntimeShaderSupported()
    val activeHaze = renderGlass && !runtimeLiquid
    val dockSurfaceBackdrop = rememberLayerBackdrop()
    val hazeModifier = if (activeHaze) {
        Modifier.hazeEffect(state = hazeState, style = HazeMaterials.ultraThin()) {
            blurRadius = 30.dp
            noiseFactor = .018f
        }
    } else Modifier
    val glassBrush = when {
        renderGlass && dark -> Brush.verticalGradient(listOf(Color.White.copy(alpha = .10f), Color.White.copy(alpha = .035f)))
        renderGlass -> Brush.verticalGradient(listOf(Color.White.copy(alpha = .22f), Color.White.copy(alpha = .09f)))
        else -> Brush.verticalGradient(
            listOf(tokens.elevatedCardBackground.copy(alpha = .98f), tokens.elevatedCardBackground.copy(alpha = .98f)),
        )
    }
    val shellTint = if (dark) scheme.surface.copy(alpha = .39f) else Color.White.copy(alpha = .40f)
    val liquidShellModifier = if (runtimeLiquid) {
        Modifier.drawBackdrop(
            backdrop = requireNotNull(backdrop),
            shape = { shape },
            effects = {
                padding = maxOf(padding, 30.dp.toPx())
                colorControls(
                    brightness = if (dark) -.015f else .025f,
                    contrast = 1.05f,
                    saturation = 1.40f,
                )
                blur(9.dp.toPx(), 9.dp.toPx())
                liquidGlassLens(
                    refractionHeight = 17.dp.toPx(),
                    refractionAmount = 13.dp.toPx(),
                    depthEffect = true,
                    chromaticAberration = .045f,
                )
            },
            highlight = {
                (if (dark) Highlight.GlassStrokeSmallDark else Highlight.GlassStrokeSmallLight)
                    .copy(alpha = if (dark) .72f else .86f)
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
            .then(if (floating) Modifier.padding(horizontal = 20.dp).padding(bottom = bottomInset + 12.dp) else Modifier)
            .fillMaxWidth()
            .height(72.dp + if (floating) 0.dp else bottomInset),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .shadow(if (floating) 18.dp else 5.dp, shape, clip = false)
                .then(if (floating) Modifier.squircleClip(31.dp) else Modifier.clip(shape))
                .then(if (runtimeLiquid) Modifier.layerBackdrop(dockSurfaceBackdrop) else Modifier)
                .then(liquidShellModifier)
                .border(
                    if (runtimeLiquid) .45.dp else .7.dp,
                    if (renderGlass) {
                        if (dark) Color.White.copy(alpha = .11f) else Color.White.copy(alpha = .32f)
                    } else if (dark) Color.White.copy(alpha = .10f) else Color.White.copy(alpha = .50f),
                    shape,
                ),
        )

        DockItems(
            items = items,
            selected = selected,
            onSelect = onSelect,
            itemHeight = 60.dp,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 6.dp, top = 6.dp, end = 6.dp, bottom = if (floating) 6.dp else bottomInset + 6.dp),
            indicatorColor = scheme.primary.copy(alpha = if (dark) .28f else .16f),
            indicatorBorderColor = Color.White.copy(alpha = if (dark) .18f else .46f),
            indicatorShadow = 3.dp,
            selectedColor = scheme.primary,
            unselectedColor = scheme.onSurfaceVariant.copy(alpha = .90f),
            liquidGlass = renderGlass,
            indicatorBackdrop = dockSurfaceBackdrop.takeIf { runtimeLiquid },
            dark = dark,
        )
    }
}

@Composable
private fun DockItems(
    items: List<DockItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    itemHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
    indicatorColor: Color,
    indicatorBorderColor: Color,
    indicatorShadow: androidx.compose.ui.unit.Dp,
    selectedColor: Color,
    unselectedColor: Color,
    liquidGlass: Boolean,
    indicatorBackdrop: LayerBackdrop?,
    dark: Boolean,
) {
    BoxWithConstraints(modifier = modifier) {
        val itemWidth = maxWidth / items.size.toFloat()
        val targetIndex = selected.coerceIn(0, items.lastIndex)
        val indicatorInset = 4.dp
        val liquidStretch = remember { Animatable(0f) }
        var travelDirection by remember { mutableFloatStateOf(0f) }
        var previousIndex by remember { mutableIntStateOf(targetIndex) }
        LaunchedEffect(targetIndex) {
            if (targetIndex != previousIndex) {
                travelDirection = if (targetIndex > previousIndex) 1f else -1f
                previousIndex = targetIndex
                liquidStretch.snapTo(1f)
                liquidStretch.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = .55f,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                )
            }
        }
        val indicatorX by animateDpAsState(
            targetValue = itemWidth * targetIndex.toFloat(),
            animationSpec = spring(
                dampingRatio = if (liquidGlass) .68f else .84f,
                stiffness = if (liquidGlass) 310f else Spring.StiffnessMediumLow,
            ),
            label = "hetuLuoShuDockIndicator",
        )
        val liquidExtra = if (liquidGlass) 13.dp * liquidStretch.value else 0.dp
        val indicatorStart = indicatorX + indicatorInset - if (travelDirection < 0f) liquidExtra else 0.dp
        val indicatorShape = RoundedCornerShape(23.dp)
        val activeLens = liquidGlass && indicatorBackdrop != null
        val movingLensModifier = if (activeLens) {
            Modifier.drawBackdrop(
                backdrop = requireNotNull(indicatorBackdrop),
                shape = { indicatorShape },
                effects = {
                    val stretch = liquidStretch.value
                    padding = maxOf(padding, 22.dp.toPx())
                    colorControls(brightness = .015f, contrast = 1.06f, saturation = 1.34f)
                    blur(3.dp.toPx(), 3.dp.toPx())
                    liquidGlassLens(
                        refractionHeight = (13.dp + 4.dp * stretch).toPx(),
                        refractionAmount = (14.dp + 5.dp * stretch).toPx(),
                        depthEffect = true,
                        chromaticAberration = .08f + .10f * stretch,
                    )
                },
                highlight = {
                    (if (dark) Highlight.GlassStrokeSmallDark else Highlight.GlassStrokeSmallLight)
                        .copy(alpha = .88f)
                },
                layerBlock = {
                    scaleY = 1f - .045f * liquidStretch.value
                },
                onDrawSurface = {
                    drawRect(indicatorColor)
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = if (dark) .055f else .16f),
                                Color.Transparent,
                            ),
                        ),
                    )
                },
            )
        } else {
            Modifier.drawBehind {
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        if (liquidGlass) {
                            listOf(
                                indicatorColor.copy(alpha = (indicatorColor.alpha * 1.18f).coerceAtMost(1f)),
                                indicatorColor.copy(alpha = indicatorColor.alpha * .72f),
                            )
                        } else {
                            listOf(indicatorColor, indicatorColor)
                        },
                    ),
                    cornerRadius = radius,
                )
                if (liquidGlass) {
                    drawRoundRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = if (dark) .10f else .24f),
                                Color.Transparent,
                            ),
                            center = Offset(size.width * .27f, 0f),
                            radius = size.width * .74f,
                        ),
                        cornerRadius = radius,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .offset(x = indicatorStart)
                .width(itemWidth - (indicatorInset * 2) + liquidExtra)
                .height(itemHeight)
                .shadow(if (activeLens) 4.dp else indicatorShadow, indicatorShape, clip = false)
                .squircleClip(23.dp)
                .then(movingLensModifier)
                .border(1.dp, indicatorBorderColor, indicatorShape),
        )

        Row(Modifier.fillMaxWidth().selectableGroup()) {
            items.forEachIndexed { index, item ->
                val active = index == targetIndex
                val interactionSource = remember(item.label) { MutableInteractionSource() }
                val pressed by interactionSource.collectIsPressedAsState()
                val baseItemColor = if (active) selectedColor else unselectedColor
                val itemColor by animateColorAsState(
                    targetValue = if (pressed) baseItemColor.copy(alpha = .62f) else baseItemColor,
                    animationSpec = tween(170),
                    label = "${item.label}DockColor",
                )
                val itemScale by animateFloatAsState(
                    targetValue = when {
                        pressed -> .92f
                        active && liquidGlass -> 1.035f
                        else -> 1f
                    },
                    animationSpec = spring(dampingRatio = .66f, stiffness = 520f),
                    label = "${item.label}DockScale",
                )
                Column(
                    modifier = Modifier
                        .width(itemWidth)
                        .height(itemHeight)
                        .graphicsLayer {
                            scaleX = itemScale
                            scaleY = itemScale
                        }
                        .clip(RoundedCornerShape(23.dp))
                        .selectable(
                            selected = active,
                            role = Role.Tab,
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { if (!active) onSelect(index) },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = itemColor,
                        modifier = Modifier
                            .size(22.dp)
                            .graphicsLayer {
                                scaleX = item.opticalScale
                                scaleY = item.opticalScale
                            },
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        item.label,
                        color = itemColor,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
