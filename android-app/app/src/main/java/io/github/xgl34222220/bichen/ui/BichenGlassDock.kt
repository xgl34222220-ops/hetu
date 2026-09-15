package io.github.xgl34222220.bichen.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import io.github.xgl34222220.bichen.ui.glass.liquidGlassLens
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

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun BichenGlassDock(
    items: List<DockItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    hazeState: HazeState,
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val shape = RoundedCornerShape(31.dp)
    val runtimeLiquid = backdrop != null && isRuntimeShaderSupported()
    val surfaceBackdrop = rememberLayerBackdrop()
    val haze = if (!runtimeLiquid) Modifier.hazeEffect(state = hazeState, style = HazeMaterials.ultraThin()) {
        blurRadius = 30.dp
        noiseFactor = .018f
    } else Modifier
    val shellTint = if (dark) scheme.surface.copy(alpha = .39f) else Color.White.copy(alpha = .40f)
    val shellModifier = if (runtimeLiquid) {
        Modifier.drawBackdrop(
            backdrop = requireNotNull(backdrop),
            shape = { shape },
            effects = {
                padding = maxOf(padding, 30.dp.toPx())
                colorControls(brightness = if (dark) -.015f else .025f, contrast = 1.05f, saturation = 1.40f)
                blur(9.dp.toPx(), 9.dp.toPx())
                liquidGlassLens(17.dp.toPx(), 13.dp.toPx(), depthEffect = true, chromaticAberration = .045f)
            },
            highlight = {
                (if (dark) Highlight.GlassStrokeSmallDark else Highlight.GlassStrokeSmallLight)
                    .copy(alpha = if (dark) .72f else .86f)
            },
            onDrawSurface = {
                drawRect(shellTint)
                drawRect(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = if (dark) .06f else .20f), Color.Transparent),
                        center = Offset(size.width * .16f, 0f),
                        radius = size.width * .70f,
                    ),
                )
            },
        )
    } else {
        Modifier.then(haze).drawBehind {
            drawRoundRect(
                brush = Brush.verticalGradient(
                    if (dark) listOf(Color.White.copy(.10f), Color.White.copy(.035f))
                    else listOf(Color.White.copy(.32f), Color.White.copy(.12f)),
                ),
                cornerRadius = CornerRadius(size.height / 2f),
            )
        }
    }

    Box(
        modifier = modifier
            .padding(horizontal = 20.dp)
            .padding(bottom = bottomInset + 12.dp)
            .fillMaxWidth()
            .height(72.dp),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .shadow(18.dp, shape, clip = false)
                .squircleClip(31.dp)
                .then(if (runtimeLiquid) Modifier.layerBackdrop(surfaceBackdrop) else Modifier)
                .then(shellModifier)
                .border(.7.dp, if (dark) Color.White.copy(.11f) else Color.White.copy(.32f), shape),
        )
        DockLayout(
            items = items,
            selected = selected,
            onSelect = onSelect,
            backdrop = surfaceBackdrop.takeIf { runtimeLiquid },
            dark = dark,
            modifier = Modifier.fillMaxSize().padding(6.dp),
        )
    }
}

@Composable
private fun BoxWithConstraintsScope.DockLayout(
    items: List<DockItem>, selected: Int, onSelect: (Int) -> Unit,
    backdrop: LayerBackdrop?, dark: Boolean, modifier: Modifier,
) {}

@Composable
private fun DockLayout(
    items: List<DockItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    backdrop: LayerBackdrop?,
    dark: Boolean,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(modifier) {
        val itemWidth = maxWidth / items.size.toFloat()
        val target = selected.coerceIn(0, items.lastIndex)
        val stretch = remember { Animatable(0f) }
        var direction by remember { mutableFloatStateOf(0f) }
        var previous by remember { mutableIntStateOf(target) }
        LaunchedEffect(target) {
            if (target != previous) {
                direction = if (target > previous) 1f else -1f
                previous = target
                stretch.snapTo(1f)
                stretch.animateTo(0f, spring(dampingRatio = .55f, stiffness = Spring.StiffnessMediumLow))
            }
        }
        val x by animateDpAsState(itemWidth * target.toFloat(), spring(dampingRatio = .68f, stiffness = 310f), label = "dockX")
        val extra = 13.dp * stretch.value
        val start = x + 4.dp - if (direction < 0f) extra else 0.dp
        val lensShape = RoundedCornerShape(23.dp)
        val activeLens = backdrop != null && isRuntimeShaderSupported()
        val lens = if (activeLens) {
            Modifier.drawBackdrop(
                backdrop = requireNotNull(backdrop),
                shape = { lensShape },
                effects = {
                    padding = maxOf(padding, 22.dp.toPx())
                    colorControls(brightness = .015f, contrast = 1.06f, saturation = 1.34f)
                    blur(3.dp.toPx(), 3.dp.toPx())
                    liquidGlassLens((13.dp + 4.dp * stretch.value).toPx(), (14.dp + 5.dp * stretch.value).toPx(), true, .08f + .10f * stretch.value)
                },
                highlight = { (if (dark) Highlight.GlassStrokeSmallDark else Highlight.GlassStrokeSmallLight).copy(alpha = .88f) },
                layerBlock = { scaleY = 1f - .045f * stretch.value },
                onDrawSurface = { drawRect(scheme.primary.copy(alpha = if (dark) .28f else .16f)) },
            )
        } else Modifier.drawBehind {
            drawRoundRect(
                Brush.verticalGradient(listOf(scheme.primary.copy(alpha = if (dark) .28f else .18f), scheme.primary.copy(alpha = if (dark) .16f else .10f))),
                cornerRadius = CornerRadius(size.height / 2f),
            )
        }
        Box(
            Modifier.offset(x = start).width(itemWidth - 8.dp + extra).height(60.dp)
                .shadow(if (activeLens) 4.dp else 3.dp, lensShape, clip = false)
                .squircleClip(23.dp).then(lens)
                .border(1.dp, Color.White.copy(alpha = if (dark) .18f else .46f), lensShape),
        )
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            items.forEachIndexed { index, item ->
                val on = index == target
                val source = remember(item.label) { MutableInteractionSource() }
                val pressed by source.collectIsPressedAsState()
                val base = if (on) scheme.primary else scheme.onSurfaceVariant.copy(.90f)
                val color by animateColorAsState(if (pressed) base.copy(alpha = .62f) else base, label = "${item.label}Color")
                val scale by animateFloatAsState(if (pressed) .92f else if (on) 1.035f else 1f, spring(dampingRatio = .66f, stiffness = 520f), label = "${item.label}Scale")
                Column(
                    Modifier.width(itemWidth).height(60.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                        .selectable(on, role = Role.Tab, interactionSource = source, indication = null) { if (!on) onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(item.icon, null, tint = color, modifier = Modifier.size(22.dp).graphicsLayer { scaleX = item.opticalScale; scaleY = item.opticalScale })
                    Spacer(Modifier.height(3.dp))
                    Text(item.label, color = color, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}
