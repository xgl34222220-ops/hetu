from pathlib import Path

path = Path('android-app/app/src/main/java/io/github/xgl34222220/hetu/ReferenceProxyActivity.kt')
text = path.read_text()
start = text.index('@OptIn(ExperimentalHazeMaterialsApi::class)\n@Composable\nprivate fun RefPanelGlassHeader(')
end = text.index('@Composable\nprivate fun RefPanelOverview(', start)

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
    val shape = RoundedCornerShape(28.dp)
    val shellTint = if (dark) scheme.surface.copy(alpha = .35f) else Color.White.copy(alpha = .36f)
    val fallbackBrush = if (dark) {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = .10f), Color.White.copy(alpha = .035f)))
    } else {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = .23f), Color.White.copy(alpha = .085f)))
    }
    val hazeModifier = if (!runtimeLiquid) {
        Modifier.hazeEffect(state = hazeState, style = HazeMaterials.ultraThin()) {
            blurRadius = 28.dp
            noiseFactor = .016f
        }
    } else Modifier
    val liquidShellModifier = if (runtimeLiquid) {
        // One RuntimeShader layer only. The previous build nested another backdrop for
        // both action bubbles and the moving tab lens, which can crash some OEM GPUs.
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
                    onClick = onSearchToggle,
                )
                Spacer(Modifier.width(8.dp))
                RefPanelHeaderAction(
                    icon = Icons.Rounded.Settings,
                    contentDescription = "设置",
                    onClick = onOpenSettings,
                )
            }
            RefPanelTabs(
                selected = selected,
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
    val bubbleBrush = Brush.radialGradient(
        colors = if (dark) {
            listOf(Color.White.copy(alpha = .13f), Color.White.copy(alpha = .055f))
        } else {
            listOf(Color.White.copy(alpha = .34f), Color.White.copy(alpha = .14f))
        },
        center = Offset(.28f, .12f),
    )
    Box(
        Modifier.size(36.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .88f else 1f }
            .shadow(2.dp, shape, clip = false)
            .background(bubbleBrush, shape)
            .border(.55.dp, Color.White.copy(alpha = if (dark) .12f else .34f), shape)
            .clip(shape)
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
    liquidGlass: Boolean = false,
    onSelect: (RefPanelTab) -> Unit,
) {
    val t = LocalHetuTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val tabs = RefPanelTab.entries
    val trackBrush = Brush.verticalGradient(
        if (dark) {
            listOf(Color.White.copy(alpha = .075f), Color.White.copy(alpha = .028f))
        } else {
            listOf(Color.White.copy(alpha = .18f), Color.White.copy(alpha = .065f))
        },
    )
    val trackBorder = if (dark) Color.White.copy(alpha = .085f) else Color.White.copy(alpha = .24f)
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(38.dp)
            .background(trackBrush, CircleShape)
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
        val lensBrush = Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = if (dark) .09f else .30f),
                indicatorTint.copy(alpha = (indicatorTint.alpha * 1.08f).coerceAtMost(1f)),
                indicatorTint.copy(alpha = indicatorTint.alpha * .66f),
            ),
        )
        Box(
            Modifier.offset(x = indicatorStart)
                .width(itemWidth + extra)
                .fillMaxHeight()
                .graphicsLayer { scaleY = 1f - .035f * stretch.value }
                .shadow(3.dp, indicatorShape, clip = false)
                .background(lensBrush, indicatorShape)
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

'''

text = text[:start] + replacement + text[end:]
# Hard safety: the panel header must not create a nested backdrop source or nested drawBackdrop children.
block = text[text.index('private fun RefPanelGlassHeader('):text.index('private fun RefPanelOverview(')]
if 'headerSurfaceBackdrop' in block:
    raise SystemExit('nested header backdrop still present')
if block.count('Modifier.drawBackdrop(') != 1:
    raise SystemExit(f'expected exactly one panel drawBackdrop, got {block.count("Modifier.drawBackdrop(")}')
path.write_text(text)
print('Applied safe single-layer panel liquid glass fix')
