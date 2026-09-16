package io.github.xgl34222220.bichen.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
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
    @Suppress("UNUSED_VARIABLE") val ignoredHazeFallback = hazeState

    val tokens = LocalBichenTokens.current
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val shape = RoundedCornerShape(26.dp)
    val glassModifier = if (backdrop != null) {
        Modifier
            .textureBlur(
                backdrop = backdrop,
                shape = shape,
                blurRadius = 24f,
            )
            .background(tokens.cardBackground.copy(alpha = .56f), shape)
    } else {
        Modifier.background(tokens.cardBackground.copy(alpha = .97f), shape)
    }

    Box(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .padding(bottom = bottomInset + 9.dp)
            .fillMaxWidth()
            .height(66.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .shadow(10.dp, shape, clip = false)
                .then(glassModifier),
        )

        DockLayout(
            items = items,
            selected = selected,
            onSelect = onSelect,
            modifier = Modifier.fillMaxSize().padding(5.dp),
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
    val tokens = LocalBichenTokens.current
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
                stretch.animateTo(0f, spring(dampingRatio = .78f, stiffness = Spring.StiffnessMediumLow))
            }
        }

        val indicatorX by animateDpAsState(
            targetValue = itemWidth * targetIndex.toFloat(),
            animationSpec = spring(dampingRatio = .80f, stiffness = 420f),
            label = "bichenDockX",
        )
        val extra = 5.dp * stretch.value
        val start = indicatorX + 3.dp - if (direction < 0f) extra else 0.dp
        val indicatorShape = RoundedCornerShape(19.dp)

        Box(
            modifier = Modifier
                .offset(x = start)
                .width(itemWidth - 6.dp + extra)
                .height(56.dp)
                .clip(indicatorShape)
                .background(tokens.selectionBackground.copy(alpha = .86f)),
        )

        Row(Modifier.fillMaxWidth().selectableGroup()) {
            items.forEachIndexed { index, item ->
                val selectedItem = index == targetIndex
                val source = remember(item.label) { MutableInteractionSource() }
                val pressed by source.collectIsPressedAsState()
                val baseColor = if (selectedItem) scheme.primary else tokens.textSecondary
                val itemColor by animateColorAsState(
                    targetValue = if (pressed) baseColor.copy(alpha = .70f) else baseColor,
                    animationSpec = tween(100),
                    label = "${item.label}DockColor",
                )
                val itemScale by animateFloatAsState(
                    targetValue = if (pressed) .94f else 1f,
                    animationSpec = spring(dampingRatio = .76f, stiffness = 700f),
                    label = "${item.label}DockScale",
                )

                Column(
                    modifier = Modifier
                        .width(itemWidth)
                        .height(56.dp)
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
                            .size(BichenLuoShuIconTokens.DockGlyph)
                            .graphicsLayer { scaleX = item.opticalScale; scaleY = item.opticalScale },
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        item.label,
                        color = itemColor,
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        fontWeight = if (selectedItem) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
