package io.github.xgl34222220.bichen.ui

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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur

data class DockItem(val label: String, val icon: ImageVector, val opticalScale: Float = 1f)

@Composable
fun BichenGlassDock(
    items: List<DockItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    hazeState: HazeState,
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    @Suppress("UNUSED_VARIABLE") val hazeFallback = hazeState
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("bichen", 0) }
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val floating = prefs.getBoolean("floatingBottomBar", true)
    val enableBlur = prefs.getBoolean("enableBlur", true)
    val liquid = prefs.getBoolean("liquidGlass", true)
    val activeGlass = liquid
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val shape = if (floating) RoundedCornerShape(31.dp) else RoundedCornerShape(topStart = 31.dp, topEnd = 31.dp)

    val shellBrush = when {
        activeGlass && dark -> Brush.verticalGradient(
            listOf(Color.White.copy(alpha = .10f), Color.White.copy(alpha = .035f)),
        )
        activeGlass -> Brush.verticalGradient(
            listOf(Color.White.copy(alpha = .22f), Color.White.copy(alpha = .09f)),
        )
        else -> Brush.verticalGradient(
            listOf(tokens.elevatedCardBackground.copy(alpha = .98f), tokens.elevatedCardBackground.copy(alpha = .98f)),
        )
    }
    val shellModifier = if (enableBlur && activeGlass && backdrop != null) {
        Modifier
            .textureBlur(backdrop = backdrop, shape = shape, blurRadius = 20f)
            .background(shellBrush, shape)
    } else {
        Modifier.background(shellBrush, shape)
    }

    // Keep the exact visual rhythm of LuoShu's MIUIX dock: 20dp side inset,
    // 12dp floating gap, 31dp shell radius, 72dp shell and 60dp moving lens.
    Box(
        modifier = modifier
            .then(
                if (floating) {
                    Modifier.padding(horizontal = 20.dp).padding(bottom = bottomInset + 12.dp)
                } else {
                    Modifier
                },
            )
            .fillMaxWidth()
            .height(72.dp + if (floating) 0.dp else bottomInset),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .shadow(if (floating) 18.dp else 5.dp, shape, clip = false)
                .clip(shape)
                .then(shellModifier)
                .drawBehind {
                    if (activeGlass) {
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
                .border(
                    .7.dp,
                    if (activeGlass) {
                        if (dark) Color.White.copy(alpha = .11f) else Color.White.copy(alpha = .32f)
                    } else if (dark) {
                        Color.White.copy(alpha = .10f)
                    } else {
                        Color.White.copy(alpha = .50f)
                    },
                    shape,
                ),
        )

        DockItems(
            items = items,
            selected = selected,
            onSelect = onSelect,
            liquidGlass = activeGlass,
            dark = dark,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 6.dp, top = 6.dp, end = 6.dp, bottom = if (floating) 6.dp else bottomInset + 6.dp),
        )
    }
}

@Composable
private fun DockItems(
    items: List<DockItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    liquidGlass: Boolean,
    dark: Boolean,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(modifier = modifier) {
        val itemWidth = maxWidth / items.size.toFloat()
        val targetIndex = selected.coerceIn(0, items.lastIndex)
        val indicatorInset = 4.dp
        val indicatorHeight = 60.dp
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
            label = "bichenLuoShuDockIndicator",
        )
        val liquidExtra = if (liquidGlass) 13.dp * liquidStretch.value else 0.dp
        val indicatorStart = indicatorX + indicatorInset - if (travelDirection < 0f) liquidExtra else 0.dp
        val indicatorShape = RoundedCornerShape(23.dp)
        val indicatorColor = scheme.primary.copy(alpha = if (dark) .28f else .16f)
        val indicatorBorder = Color.White.copy(alpha = if (dark) .18f else .46f)

        Box(
            modifier = Modifier
                .offset(x = indicatorStart)
                .width(itemWidth - (indicatorInset * 2) + liquidExtra)
                .height(indicatorHeight)
                .shadow(3.dp, indicatorShape, clip = false)
                .clip(indicatorShape)
                .drawBehind {
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
                .border(1.dp, indicatorBorder, indicatorShape),
        )

        Row(Modifier.fillMaxWidth().selectableGroup()) {
            items.forEachIndexed { i, item ->
                val active = i == targetIndex
                val interactionSource = remember(item.label) { MutableInteractionSource() }
                val pressed by interactionSource.collectIsPressedAsState()
                val baseColor = if (active) scheme.primary else scheme.onSurfaceVariant.copy(alpha = .90f)
                val itemColor by animateColorAsState(
                    targetValue = if (pressed) baseColor.copy(alpha = .62f) else baseColor,
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
                    Modifier
                        .width(itemWidth)
                        .height(indicatorHeight)
                        .graphicsLayer {
                            scaleX = itemScale
                            scaleY = itemScale
                        }
                        .clip(RoundedCornerShape(23.dp))
                        .semantics(mergeDescendants = true) { contentDescription = item.label }
                        .selectable(
                            selected = active,
                            role = Role.Tab,
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { if (!active) onSelect(i) },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        item.icon,
                        contentDescription = null,
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
