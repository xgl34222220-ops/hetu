package io.github.xgl34222220.hetu.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import top.yukonga.miuix.kmp.blur.LayerBackdrop

data class DockItem(val label: String, val icon: ImageVector, val opticalScale: Float = 1f)

/** Readable frosted navigation. One quiet shell and one flat moving selection. */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun HetuGlassDock(items: List<DockItem>, selected: Int, onSelect: (Int) -> Unit,
    hazeState: HazeState, backdrop: LayerBackdrop?, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("hetu", 0) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in setOf("floatingBottomBar", "enableBlur", "liquidGlass")) revision++
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    revision
    val floating = prefs.getBoolean("floatingBottomBar", true)
    val frosted = prefs.getBoolean("enableBlur", true) && prefs.getBoolean("liquidGlass", true)
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val itemHeight = maxOf(54.dp, 31.dp + with(LocalDensity.current) { 18.sp.toDp() })
    val shape = RoundedCornerShape(if (floating) 28.dp else 0.dp)
    Box(modifier.then(if (floating) Modifier.padding(horizontal = HetuBottomBarMetrics.FloatingHorizontal)
        .padding(bottom = bottomInset + HetuBottomBarMetrics.FloatingBottom) else Modifier)
        .fillMaxWidth().height(itemHeight + 8.dp + if (floating) 0.dp else bottomInset)
        .testTag("navigation-dock")) {
        Box(Modifier.fillMaxSize().shadow(if (floating) 8.dp else 0.dp, shape, clip = false)
            .clip(shape)
            .then(if (frosted) Modifier.hazeEffect(hazeState, HazeMaterials.regular()) {
                blurRadius = 22.dp; noiseFactor = .008f
            } else Modifier)
            .background(t.elevatedCardBackground.copy(alpha = if (frosted) .86f else 1f)))
        DockItems(items, selected, onSelect, itemHeight,
            Modifier.fillMaxSize().padding(start = 4.dp, top = 4.dp, end = 4.dp,
                bottom = 4.dp + if (floating) 0.dp else bottomInset),
            scheme.primary, t.textSecondary,
            if (dark) scheme.primary.copy(alpha = .18f) else scheme.primary.copy(alpha = .09f))
    }
}

@Composable
private fun DockItems(items: List<DockItem>, selected: Int, onSelect: (Int) -> Unit,
    itemHeight: Dp, modifier: Modifier, selectedColor: Color, unselectedColor: Color, indicatorColor: Color) {
    BoxWithConstraints(modifier) {
        val itemWidth = maxWidth / items.size.toFloat()
        val widthPx = with(LocalDensity.current) { itemWidth.toPx() }
        val motion = LocalHetuMotionEnabled.current
        val view = LocalView.current
        val selectedCallback by rememberUpdatedState(onSelect)
        val committed = selected.coerceIn(0, items.lastIndex)
        val latestCommitted by rememberUpdatedState(committed)
        var dragging by remember { mutableStateOf(false) }
        var dragIndex by remember { mutableFloatStateOf(selected.toFloat()) }
        var hovered by remember { mutableIntStateOf(selected) }
        val target = if (dragging) hovered else committed
        val dragModifier = Modifier.pointerInput(items.size, widthPx) {
            detectHorizontalDragGestures(
                onDragStart = { position ->
                    dragging = true
                    dragIndex = (position.x / widthPx - .5f).coerceIn(0f, items.lastIndex.toFloat())
                    hovered = kotlin.math.round(dragIndex).toInt().coerceIn(0, items.lastIndex)
                },
                onDragCancel = { dragging = false },
                onDragEnd = {
                    val next = hovered; dragging = false
                    if (next != latestCommitted) selectedCallback(next)
                },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    dragIndex = (dragIndex + amount / widthPx).coerceIn(0f, items.lastIndex.toFloat())
                    val next = kotlin.math.round(dragIndex).toInt().coerceIn(0, items.lastIndex)
                    if (next != hovered) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    hovered = next
                })
        }
        val indicatorX by animateDpAsState(itemWidth * if (dragging) dragIndex else committed.toFloat(),
            if (!motion || dragging) snap() else spring(dampingRatio = .88f, stiffness = 480f), label = "dock-selection")
        Box(Modifier.offset(x = indicatorX + 3.dp).width(itemWidth - 6.dp).height(itemHeight)
            .background(indicatorColor, RoundedCornerShape(24.dp)))
        Row(Modifier.fillMaxWidth().then(dragModifier).selectableGroup()) {
            items.forEachIndexed { index, item ->
                val active = index == target
                val source = remember(item.label) { MutableInteractionSource() }
                val pressed by source.collectIsPressedAsState()
                val color by animateColorAsState(if (active) selectedColor else unselectedColor,
                    tween(if (motion) 160 else 0), label = "dock-color:${item.label}")
                val scale by animateFloatAsState(if (pressed) .94f else 1f,
                    if (motion) spring(dampingRatio = .8f, stiffness = 650f) else snap(), label = "dock-press:${item.label}")
                Column(Modifier.width(itemWidth).height(itemHeight).graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(RoundedCornerShape(24.dp)).selectable(active, role = Role.Tab,
                        interactionSource = source, indication = null,
                        onClick = { if (!active) { view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); onSelect(index) } }),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(item.icon, ht(item.label), Modifier.size(23.dp).graphicsLayer {
                        scaleX = item.opticalScale; scaleY = item.opticalScale
                    }, tint = color)
                    Spacer(Modifier.height(2.dp))
                    Text(ht(item.label), Modifier.fillMaxWidth(), color = color, fontSize = 12.sp,
                        lineHeight = 18.sp, textAlign = TextAlign.Center,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}
