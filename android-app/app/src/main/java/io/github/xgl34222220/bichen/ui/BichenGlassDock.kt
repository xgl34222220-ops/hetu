package io.github.xgl34222220.bichen.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
    @Suppress("UNUSED_VARIABLE") val unusedFallback = hazeState
    val tokens = LocalBichenTokens.current
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val shape = RoundedCornerShape(21.dp)
    val glass = if (backdrop != null) {
        Modifier
            .textureBlur(backdrop = backdrop, shape = shape, blurRadius = 18f)
            .background(tokens.cardBackground.copy(alpha = .60f), shape)
    } else {
        Modifier.background(tokens.cardBackground.copy(alpha = .97f), shape)
    }

    Box(
        modifier = modifier
            .padding(horizontal = 14.dp)
            .padding(bottom = bottomInset + 7.dp)
            .fillMaxWidth()
            .height(58.dp),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .shadow(7.dp, shape, clip = false)
                .then(glass),
        )
        DockItems(items, selected, onSelect, Modifier.fillMaxSize().padding(4.dp))
    }
}

@Composable
private fun DockItems(
    items: List<DockItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
) {
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(modifier = modifier) {
        val width = maxWidth / items.size.toFloat()
        val index = selected.coerceIn(0, items.lastIndex)
        val x by animateDpAsState(
            targetValue = width * index.toFloat(),
            animationSpec = spring(dampingRatio = .82f, stiffness = 430f),
            label = "dockX",
        )
        Box(
            Modifier
                .offset(x = x + 2.dp)
                .width(width - 4.dp)
                .height(50.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(tokens.selectionBackground.copy(alpha = .88f)),
        )

        Row(Modifier.fillMaxWidth().selectableGroup()) {
            items.forEachIndexed { i, item ->
                val active = i == index
                val source = remember(item.label) { MutableInteractionSource() }
                val pressed by source.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = if (pressed) .95f else 1f,
                    animationSpec = spring(dampingRatio = .8f, stiffness = Spring.StiffnessHigh),
                    label = "dockScale${item.label}",
                )
                val color by animateColorAsState(
                    targetValue = if (active) scheme.primary else tokens.textSecondary,
                    label = "dockColor${item.label}",
                )
                Column(
                    Modifier
                        .width(width)
                        .height(50.dp)
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .selectable(
                            selected = active,
                            role = Role.Tab,
                            interactionSource = source,
                            indication = null,
                            onClick = { if (!active) onSelect(i) },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        item.icon,
                        contentDescription = item.label,
                        tint = color,
                        modifier = Modifier.size(20.dp).graphicsLayer { scaleX = item.opticalScale; scaleY = item.opticalScale },
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        item.label,
                        color = color,
                        fontSize = 9.5.sp,
                        lineHeight = 12.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
