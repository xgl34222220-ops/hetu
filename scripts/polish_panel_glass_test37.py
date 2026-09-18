from pathlib import Path
import re

path = Path('android-app/app/src/main/java/io/github/xgl34222220/hetu/ReferenceProxyActivity.kt')
text = path.read_text(encoding='utf-8')

# Imports for the same runtime glass stack used by HetuGlassDock/LuoShu.
if 'import dev.chrisbanes.haze.HazeState' not in text:
    text = text.replace(
        'import dev.chrisbanes.haze.hazeSource\nimport dev.chrisbanes.haze.rememberHazeState\n',
        'import dev.chrisbanes.haze.HazeState\n'
        'import dev.chrisbanes.haze.hazeEffect\n'
        'import dev.chrisbanes.haze.hazeSource\n'
        'import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi\n'
        'import dev.chrisbanes.haze.materials.HazeMaterials\n'
        'import dev.chrisbanes.haze.rememberHazeState\n'
    )
if 'import io.github.xgl34222220.hetu.ui.glass.liquidGlassLens' not in text:
    text = text.replace(
        'import io.github.xgl34222220.hetu.ui.LocalHetuTokens\n',
        'import io.github.xgl34222220.hetu.ui.LocalHetuTokens\n'
        'import io.github.xgl34222220.hetu.ui.glass.liquidGlassLens\n'
    )
if 'import top.yukonga.miuix.kmp.blur.LayerBackdrop' not in text:
    text = text.replace(
        'import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported\n',
        'import top.yukonga.miuix.kmp.blur.LayerBackdrop\n'
        'import top.yukonga.miuix.kmp.blur.blur\n'
        'import top.yukonga.miuix.kmp.blur.colorControls\n'
        'import top.yukonga.miuix.kmp.blur.drawBackdrop\n'
        'import top.yukonga.miuix.kmp.blur.highlight.Highlight\n'
        'import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported\n'
    )
if 'import top.yukonga.miuix.kmp.squircle.squircleClip' not in text:
    text = text.replace(
        'import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop\n',
        'import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop\n'
        'import top.yukonga.miuix.kmp.squircle.squircleClip\n'
    )

# Feed the page backdrop into the panel so its header can refract the real page content.
call_old = '''                RefProxyPage.Panel -> RefPanel(\n                    state = state,\n                    repo = repo,\n                    delays = delays,\n                    onRefreshState = { scope.launch { refresh() } },\n                    onOpenSettings = { page = RefProxyPage.Settings },\n                    onDetailVisibleChanged = { panelDetailVisible = it },\n                )'''
call_new = '''                RefProxyPage.Panel -> RefPanel(\n                    state = state,\n                    repo = repo,\n                    delays = delays,\n                    hazeState = haze,\n                    backdrop = liquidBackdrop.takeIf { liquid },\n                    onRefreshState = { scope.launch { refresh() } },\n                    onOpenSettings = { page = RefProxyPage.Settings },\n                    onDetailVisibleChanged = { panelDetailVisible = it },\n                )'''
if call_old in text:
    text = text.replace(call_old, call_new, 1)
elif 'hazeState = haze' not in text:
    raise SystemExit('panel call signature not found')

# Extend RefPanel with the shared glass backdrop inputs.
sig_old = '''@OptIn(ExperimentalMaterial3Api::class)\n@Composable\nprivate fun RefPanel(\n    state: ProxyComposeState,\n    repo: ProxyDashboardRepository,\n    delays: MutableMap<String, Long>,\n    onRefreshState: () -> Unit,'''
sig_new = '''@OptIn(ExperimentalMaterial3Api::class, ExperimentalHazeMaterialsApi::class)\n@Composable\nprivate fun RefPanel(\n    state: ProxyComposeState,\n    repo: ProxyDashboardRepository,\n    delays: MutableMap<String, Long>,\n    hazeState: HazeState,\n    backdrop: LayerBackdrop?,\n    onRefreshState: () -> Unit,'''
if sig_old in text:
    text = text.replace(sig_old, sig_new, 1)
elif 'hazeState: HazeState' not in text:
    raise SystemExit('RefPanel signature not found')

# Replace the old flat title/tabs header item with one floating liquid-glass island.
start_marker = '''            item {\n                Column(\n                    Modifier.statusBarsPadding().padding(top = 14.dp, bottom = 4.dp),'''
end_marker = '''            if (error.isNotBlank()) item { RefNotice(error) }'''
start = text.find(start_marker)
end = text.find(end_marker, start if start >= 0 else 0)
if start >= 0 and end > start:
    new_item = '''            item {\n                Box(Modifier.statusBarsPadding().padding(top = 14.dp, bottom = 4.dp)) {\n                    RefPanelGlassHeader(\n                        selected = tab,\n                        onSelect = { tab = it },\n                        searchOpen = searchOpen,\n                        query = query,\n                        onQueryChange = { query = it },\n                        onSearchToggle = {\n                            searchOpen = !searchOpen\n                            if (!searchOpen) query = ""\n                        },\n                        onOpenSettings = onOpenSettings,\n                        hazeState = hazeState,\n                        backdrop = backdrop,\n                    )\n                }\n            }\n'''
    text = text[:start] + new_item + text[end:]
elif 'RefPanelGlassHeader(' not in text:
    raise SystemExit('panel header item not found')

# Replace the previous flat header action + tabs helpers with real refractive glass helpers.
block_pattern = re.compile(
    r'@Composable\nprivate fun RefPanelHeaderAction\([\s\S]*?\n@Composable\nprivate fun RefPanelOverview',
    re.MULTILINE,
)
replacement = r'''@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun RefPanelGlassHeader(
    selected: RefPanelTab,
    onSelect: (RefPanelTab) -> Unit,
    searchOpen: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchToggle: () -> Unit,
    onOpenSettings: () -> Unit,
    hazeState: HazeState,
    backdrop: LayerBackdrop?,
) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val runtimeLiquid = backdrop != null && isRuntimeShaderSupported()
    val headerSurfaceBackdrop = rememberLayerBackdrop()
    val shape = RoundedCornerShape(28.dp)
    val shellTint = if (dark) scheme.surface.copy(alpha = .37f) else Color.White.copy(alpha = .38f)
    val fallbackBrush = if (dark) {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = .095f), Color.White.copy(alpha = .035f)))
    } else {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = .24f), Color.White.copy(alpha = .10f)))
    }
    val hazeModifier = if (!runtimeLiquid) {
        Modifier.hazeEffect(state = hazeState, style = HazeMaterials.ultraThin()) {
            blurRadius = 28.dp
            noiseFactor = .016f
        }
    } else Modifier
    val liquidShellModifier = if (runtimeLiquid) {
        Modifier.drawBackdrop(
            backdrop = requireNotNull(backdrop),
            shape = { shape },
            effects = {
                padding = maxOf(padding, 28.dp.toPx())
                colorControls(
                    brightness = if (dark) -.012f else .022f,
                    contrast = 1.045f,
                    saturation = 1.34f,
                )
                blur(8.dp.toPx(), 8.dp.toPx())
                liquidGlassLens(
                    refractionHeight = 16.dp.toPx(),
                    refractionAmount = 12.dp.toPx(),
                    depthEffect = true,
                    chromaticAberration = .04f,
                )
            },
            highlight = {
                (if (dark) Highlight.GlassStrokeSmallDark else Highlight.GlassStrokeSmallLight)
                    .copy(alpha = if (dark) .70f else .84f)
            },
            onDrawSurface = {
                drawRect(shellTint)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (dark) .055f else .18f),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * .14f, 0f),
                        radius = size.width * .66f,
                    ),
                )
            },
        )
    } else {
        Modifier.then(hazeModifier).background(fallbackBrush)
    }

    Box(
        Modifier
            .fillMaxWidth()
            .shadow(14.dp, shape, clip = false)
            .squircleClip(28.dp)
            .then(if (runtimeLiquid) Modifier.layerBackdrop(headerSurfaceBackdrop) else Modifier)
            .then(liquidShellModifier)
            .border(
                if (runtimeLiquid) .45.dp else .7.dp,
                if (dark) Color.White.copy(alpha = .11f) else Color.White.copy(alpha = .30f),
                shape,
            ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "面板",
                    color = t.textPrimary,
                    fontSize = 26.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.weight(1f),
                )
                RefPanelHeaderAction(
                    icon = if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search,
                    contentDescription = if (searchOpen) "关闭搜索" else "搜索",
                    active = searchOpen,
                    backdrop = headerSurfaceBackdrop.takeIf { runtimeLiquid },
                    onClick = onSearchToggle,
                )
                Spacer(Modifier.width(8.dp))
                RefPanelHeaderAction(
                    icon = Icons.Rounded.Settings,
                    contentDescription = "设置",
                    backdrop = headerSurfaceBackdrop.takeIf { runtimeLiquid },
                    onClick = onOpenSettings,
                )
            }
            RefPanelTabs(
                selected = selected,
                indicatorBackdrop = headerSurfaceBackdrop.takeIf { runtimeLiquid },
                liquidGlass = true,
                onSelect = onSelect,
            )
            if (searchOpen) {
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("搜索策略组或节点") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)) },
                    shape = RoundedCornerShape(18.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.White.copy(alpha = if (dark) .07f else .18f),
                        unfocusedContainerColor = Color.White.copy(alpha = if (dark) .05f else .13f),
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
            }
        }
    }
}

@Composable
private fun RefPanelHeaderAction(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean = false,
    backdrop: LayerBackdrop? = null,
    onClick: () -> Unit,
) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val source = remember(contentDescription) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) .92f else 1f,
        spring(dampingRatio = .72f, stiffness = 560f),
        label = "panelHeaderAction$contentDescription",
    )
    val shape = CircleShape
    val liquid = backdrop != null && isRuntimeShaderSupported()
    val fill = if (dark) Color.White.copy(alpha = .07f) else Color.White.copy(alpha = .17f)
    val glassModifier = if (liquid) {
        Modifier.drawBackdrop(
            backdrop = requireNotNull(backdrop),
            shape = { shape },
            effects = {
                padding = maxOf(padding, 18.dp.toPx())
                colorControls(brightness = .015f, contrast = 1.05f, saturation = 1.30f)
                blur(3.dp.toPx(), 3.dp.toPx())
                liquidGlassLens(
                    refractionHeight = 10.dp.toPx(),
                    refractionAmount = 8.dp.toPx(),
                    depthEffect = true,
                    chromaticAberration = .05f,
                )
            },
            highlight = {
                (if (dark) Highlight.GlassStrokeSmallDark else Highlight.GlassStrokeSmallLight)
                    .copy(alpha = .80f)
            },
            onDrawSurface = { drawRect(fill) },
        )
    } else {
        Modifier.background(fill, shape)
    }
    Box(
        Modifier.size(36.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .88f else 1f }
            .shadow(2.dp, shape, clip = false)
            .clip(shape)
            .then(glassModifier)
            .border(.55.dp, Color.White.copy(alpha = if (dark) .12f else .34f), shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription,
            tint = if (active) scheme.primary else if (dark) t.textSecondary else Color(0xFF64748B),
            modifier = Modifier.size(17.dp),
        )
    }
}

@Composable
private fun RefPanelTabs(
    selected: RefPanelTab,
    indicatorBackdrop: LayerBackdrop? = null,
    liquidGlass: Boolean = false,
    onSelect: (RefPanelTab) -> Unit,
) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val tabs = RefPanelTab.entries
    val activeLens = liquidGlass && indicatorBackdrop != null && isRuntimeShaderSupported()
    val trackFill = if (dark) Color.White.copy(alpha = .045f) else Color.White.copy(alpha = .10f)
    val trackBorder = if (dark) Color.White.copy(alpha = .085f) else Color.White.copy(alpha = .24f)
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(38.dp)
            .background(trackFill, CircleShape)
            .border(.5.dp, trackBorder, CircleShape)
            .padding(3.dp),
    ) {
        val itemWidth = maxWidth / tabs.size.toFloat()
        val targetIndex = tabs.indexOf(selected).coerceAtLeast(0)
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
                    spring(dampingRatio = .56f, stiffness = Spring.StiffnessMediumLow),
                )
            }
        }
        val indicatorX by animateDpAsState(
            targetValue = itemWidth * targetIndex.toFloat(),
            animationSpec = spring(dampingRatio = .68f, stiffness = 310f),
            label = "panelLiquidTabIndicator",
        )
        val extra = if (liquidGlass) 8.dp * stretch.value else 0.dp
        val indicatorStart = indicatorX - if (direction < 0f) extra else 0.dp
        val indicatorShape = RoundedCornerShape(18.dp)
        val indicatorTint = scheme.primary.copy(alpha = if (dark) .22f else .13f)
        val lensModifier = if (activeLens) {
            Modifier.drawBackdrop(
                backdrop = requireNotNull(indicatorBackdrop),
                shape = { indicatorShape },
                effects = {
                    val s = stretch.value
                    padding = maxOf(padding, 18.dp.toPx())
                    colorControls(brightness = .018f, contrast = 1.055f, saturation = 1.30f)
                    blur(2.5.dp.toPx(), 2.5.dp.toPx())
                    liquidGlassLens(
                        refractionHeight = (10.dp + 3.dp * s).toPx(),
                        refractionAmount = (11.dp + 4.dp * s).toPx(),
                        depthEffect = true,
                        chromaticAberration = .055f + .07f * s,
                    )
                },
                highlight = {
                    (if (dark) Highlight.GlassStrokeSmallDark else Highlight.GlassStrokeSmallLight)
                        .copy(alpha = .84f)
                },
                layerBlock = { scaleY = 1f - .035f * stretch.value },
                onDrawSurface = { drawRect(indicatorTint) },
            )
        } else {
            Modifier.background(
                Brush.verticalGradient(
                    listOf(
                        indicatorTint.copy(alpha = (indicatorTint.alpha * 1.18f).coerceAtMost(1f)),
                        indicatorTint.copy(alpha = indicatorTint.alpha * .72f),
                    ),
                ),
                indicatorShape,
            )
        }
        Box(
            Modifier.offset(x = indicatorStart)
                .width(itemWidth + extra)
                .fillMaxHeight()
                .shadow(if (activeLens) 4.dp else 2.dp, indicatorShape, clip = false)
                .squircleClip(18.dp)
                .then(lensModifier)
                .border(.6.dp, Color.White.copy(alpha = if (dark) .14f else .38f), indicatorShape),
        )
        Row(Modifier.fillMaxSize()) {
            tabs.forEach { tab ->
                val active = tab == selected
                val source = remember(tab) { MutableInteractionSource() }
                val pressed by source.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    when {
                        pressed -> .94f
                        active && liquidGlass -> 1.025f
                        else -> 1f
                    },
                    spring(dampingRatio = .68f, stiffness = 520f),
                    label = "tab${tab.name}",
                )
                Box(
                    Modifier.width(itemWidth).fillMaxHeight()
                        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .88f else 1f }
                        .clip(CircleShape)
                        .clickable(interactionSource = source, indication = null) { if (!active) onSelect(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        tab.label,
                        color = if (active) scheme.primary else if (dark) t.textSecondary else Color(0xFF64748B),
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun RefPanelOverview'''

if 'private fun RefPanelGlassHeader(' not in text:
    text, count = block_pattern.subn(replacement, text, count=1)
    if count != 1:
        raise SystemExit('panel header helper block not found')

# Hard invariants for test.37.
required = [
    'private fun RefPanelGlassHeader(',
    'hazeState: HazeState',
    'backdrop: LayerBackdrop?',
    'liquidGlassLens(',
    'panelLiquidTabIndicator',
    'indicatorBackdrop = headerSurfaceBackdrop.takeIf { runtimeLiquid }',
]
for marker in required:
    if marker not in text:
        raise SystemExit(f'missing invariant: {marker}')

path.write_text(text, encoding='utf-8')
print('Applied panel liquid-glass header + tabs test.37')
