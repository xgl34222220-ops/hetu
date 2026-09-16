package io.github.xgl34222220.bichen.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
    if (items.isEmpty()) return
    @Suppress("UNUSED_VARIABLE") val unusedFallback = hazeState
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("bichen", 0) }
    val tokens = LocalBichenTokens.current
    val floating = prefs.getBoolean("floatingBottomBar", true)
    val enableBlur = prefs.getBoolean("enableBlur", true)
    val liquid = prefs.getBoolean("liquidGlass", true)
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val shape = if (floating) RoundedCornerShape(999.dp) else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val shellAlpha = if (MaterialTheme.colorScheme.background.red < .2f) .86f else .82f
    val glass = if (enableBlur && liquid && backdrop != null) {
        Modifier
            .textureBlur(backdrop = backdrop, shape = shape, blurRadius = 20f)
            .background(tokens.cardBackground.copy(alpha = shellAlpha), shape)
    } else {
        Modifier.background(tokens.cardBackground.copy(alpha = if (floating) .94f else .98f), shape)
    }

    Box(
        modifier = modifier
            .then(if (floating) Modifier.padding(horizontal = 16.dp) else Modifier)
            .padding(bottom = if (floating) bottomInset + 12.dp else 0.dp)
            .fillMaxWidth()
            .height(if (floating) 64.dp else 72.dp),
    ) {
        Box(
            Modifier.fillMaxSize()
                .shadow(if (floating) 10.dp else 0.dp, shape, clip = false)
                .then(glass)
                .border(1.dp, Color.White.copy(alpha = if (enableBlur && liquid) .40f else .10f), shape),
        )
        DockItems(
            items = items,
            selected = selected,
            onSelect = onSelect,
            modifier = Modifier.fillMaxSize().padding(horizontal = 5.dp, vertical = if (floating) 5.dp else 8.dp),
        )
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
            animationSpec = spring(dampingRatio = .84f, stiffness = 420f),
            label = "dockX",
        )
        Box(
            Modifier.offset(x = x + 2.dp)
                .width(width - 4.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(999.dp))
                .background(tokens.selectionBackground.copy(alpha = .82f)),
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
                    Modifier.width(width)
                        .fillMaxHeight()
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
                        modifier = Modifier.size(21.dp).graphicsLayer { scaleX = item.opticalScale; scaleY = item.opticalScale },
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        item.label,
                        color = color,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
