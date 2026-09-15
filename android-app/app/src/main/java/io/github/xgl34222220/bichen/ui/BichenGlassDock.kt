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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/**
 * Device-safe LuoShu dock.
 *
 * Important: this renderer intentionally has ZERO explicit white overlay layers and ZERO
 * per-item background surfaces. Some Android 16/OEM compositors turned translucent white
 * gradients/backdrop layers into opaque rectangular blocks. The dock is now one continuous
 * rounded shell plus one tinted moving selection lens only.
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
    @Suppress("UNUSED_VARIABLE") val ignoredHaze = hazeState
    @Suppress("UNUSED_VARIABLE") val ignoredBackdrop = backdrop

    val scheme = MaterialTheme.colorScheme
    val tokens = LocalBichenTokens.current
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val shape = RoundedCornerShape(31.dp)

    // One continuous tinted shell. No Color.White, no shader, no haze, no layered glass bitmap.
    val shellColor = lerp(tokens.elevatedCardBackground, scheme.primaryContainer, .08f)
    val shellBorder = lerp(scheme.outlineVariant, scheme.primary, .12f).copy(alpha = .38f)

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
                .shadow(12.dp, shape, clip = false)
                .clip(shape)
                .background(shellColor)
                .border(.7.dp, shellBorder, shape),
        )

        DockLayout(
            items = items,
            selected = selected,
            onSelect = onSelect,
            modifier = Modifier.fillMaxSize().padding(6.dp),
        )
    }
}

@Composable
private fun DockLayout(
    items: List<DockItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
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
        val indicatorColor = lerp(scheme.primaryContainer, scheme.secondaryContainer, .12f)
        val indicatorBorder = scheme.primary.copy(alpha = .18f)

        // The only moving background in the dock: one rounded primary-tinted lens.
        Box(
            modifier = Modifier
                .offset(x = start)
                .width(itemWidth - 8.dp + extra)
                .height(60.dp)
                .shadow(2.dp, indicatorShape, clip = false)
                .clip(indicatorShape)
                .background(indicatorColor)
                .border(.8.dp, indicatorBorder, indicatorShape),
        )

        Row(Modifier.fillMaxWidth().selectableGroup()) {
            items.forEachIndexed { index, item ->
                val selectedItem = index == targetIndex
                val source = remember(item.label) { MutableInteractionSource() }
                val pressed by source.collectIsPressedAsState()
                val baseColor = if (selectedItem) scheme.primary else scheme.onSurfaceVariant.copy(alpha = .86f)
                val itemColor by animateColorAsState(
                    targetValue = if (pressed) baseColor.copy(alpha = .62f) else baseColor,
                    animationSpec = tween(160),
                    label = "${item.label}DockColor",
                )
                val itemScale by animateFloatAsState(
                    targetValue = when {
                        pressed -> .92f
                        selectedItem -> 1.03f
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
                        .selectable(
                            selected = selectedItem,
                            role = Role.Tab,
                            interactionSource = source,
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
