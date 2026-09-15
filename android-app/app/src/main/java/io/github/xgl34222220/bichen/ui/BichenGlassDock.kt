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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/**
 * LuoShu-shaped floating dock with a device-safe glass renderer.
 *
 * Do not gate the runtime shader only on isRuntimeShaderSupported(): several OEM Android 16
 * compositors report shader support but render the backdrop as an opaque charcoal slab or a
 * horizontal white strip.  The rest of Bichen can still use blur; the dock deliberately uses the
 * same LuoShu geometry/spacing with a stable translucent surface so it never corrupts the UI.
 */
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
    // Keep parameters for API compatibility with the app shell.  Rendering is intentionally
    // independent from OEM backdrop shaders until they can be validated per device.
    @Suppress("UNUSED_VARIABLE") val ignoredHaze = hazeState
    @Suppress("UNUSED_VARIABLE") val ignoredBackdrop = backdrop

    val scheme = MaterialTheme.colorScheme
    val tokens = LocalBichenTokens.current
    val dark = scheme.background.luminance() < .5f
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val shape = RoundedCornerShape(31.dp)

    val shellBrush = if (dark) {
        Brush.verticalGradient(
            listOf(
                tokens.elevatedCardBackground.copy(alpha = .90f),
                tokens.cardBackground.copy(alpha = .82f),
            ),
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = .88f),
                tokens.elevatedCardBackground.copy(alpha = .78f),
            ),
        )
    }

    Box(
        modifier = modifier
            .padding(horizontal = 20.dp)
            .padding(bottom = bottomInset + 12.dp)
            .fillMaxWidth()
            .height(72.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .shadow(16.dp, shape, clip = false)
                .clip(shape)
                .background(shellBrush)
                .drawBehind {
                    drawRoundRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = if (dark) .09f else .42f),
                                Color.Transparent,
                            ),
                            center = Offset(size.width * .18f, 0f),
                            radius = size.width * .76f,
                        ),
                        cornerRadius = CornerRadius(size.height / 2f),
                    )
                }
                .border(
                    .8.dp,
                    if (dark) Color.White.copy(alpha = .13f) else Color.White.copy(alpha = .86f),
                    shape,
                ),
        )

        DockLayout(
            items = items,
            selected = selected,
            onSelect = onSelect,
            modifier = Modifier.fillMaxSize().padding(6.dp),
            dark = dark,
        )
    }
}

@Composable
private fun DockLayout(
    items: List<DockItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
    dark: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(modifier = modifier) {
        val itemWidth = maxWidth / items.size.toFloat()
        val targetIndex = selected.coerceIn(0, items.lastIndex)
        val stretch = remember { Animatable(0f) }
        var direction by remember { mutableFloatStateOf(0f) }
        var previousIndex by remember { mutableIntStateOf(targetIndex) }

        LaunchedEffect(targetIndex) {
            if (targetIndex != previousIndex) {
                direction = if (targetIndex > previousIndex) 1f else -1f
                previousIndex = targetIndex
                stretch.snapTo(1f)
                stretch.animateTo(
                    0f,
                    spring(dampingRatio = .58f, stiffness = Spring.StiffnessMediumLow),
                )
            }
        }

        val indicatorX by animateDpAsState(
            targetValue = itemWidth * targetIndex.toFloat(),
            animationSpec = spring(dampingRatio = .70f, stiffness = 320f),
            label = "bichenDockX",
        )
        val extra = 10.dp * stretch.value
        val start = indicatorX + 4.dp - if (direction < 0f) extra else 0.dp
        val indicatorShape = RoundedCornerShape(23.dp)
        val indicatorColor = scheme.primary.copy(alpha = if (dark) .25f else .14f)

        Box(
            modifier = Modifier
                .offset(x = start)
                .width(itemWidth - 8.dp + extra)
                .height(60.dp)
                .shadow(3.dp, indicatorShape, clip = false)
                .clip(indicatorShape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            indicatorColor.copy(alpha = (indicatorColor.alpha * 1.18f).coerceAtMost(1f)),
                            indicatorColor.copy(alpha = indicatorColor.alpha * .74f),
                        ),
                    ),
                )
                .drawBehind {
                    drawRoundRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = if (dark) .08f else .28f),
                                Color.Transparent,
                            ),
                            center = Offset(size.width * .26f, 0f),
                            radius = size.width * .78f,
                        ),
                        cornerRadius = CornerRadius(size.height / 2f),
                    )
                }
                .border(
                    1.dp,
                    Color.White.copy(alpha = if (dark) .18f else .54f),
                    indicatorShape,
                ),
        )

        Row(Modifier.fillMaxWidth().selectableGroup()) {
            items.forEachIndexed { index, item ->
                val selectedItem = index == targetIndex
                val interactionSource = remember(item.label) { MutableInteractionSource() }
                val pressed by interactionSource.collectIsPressedAsState()
                val baseColor = if (selectedItem) scheme.primary else scheme.onSurfaceVariant.copy(alpha = .90f)
                val itemColor by animateColorAsState(
                    targetValue = if (pressed) baseColor.copy(alpha = .62f) else baseColor,
                    animationSpec = tween(170),
                    label = "${item.label}DockColor",
                )
                val itemScale by animateFloatAsState(
                    targetValue = when {
                        pressed -> .92f
                        selectedItem -> 1.035f
                        else -> 1f
                    },
                    animationSpec = spring(dampingRatio = .66f, stiffness = 520f),
                    label = "${item.label}DockScale",
                )

                Column(
                    modifier = Modifier
                        .width(itemWidth)
                        .height(60.dp)
                        .graphicsLayer { scaleX = itemScale; scaleY = itemScale }
                        .clip(RoundedCornerShape(23.dp))
                        .selectable(
                            selected = selectedItem,
                            role = Role.Tab,
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { if (!selectedItem) onSelect(index) },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = itemColor,
                        modifier = Modifier
                            .size(22.dp)
                            .graphicsLayer { scaleX = item.opticalScale; scaleY = item.opticalScale },
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        item.label,
                        color = itemColor,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        fontWeight = if (selectedItem) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
