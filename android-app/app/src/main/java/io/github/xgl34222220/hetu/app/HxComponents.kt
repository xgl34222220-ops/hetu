package io.github.xgl34222220.hetu

import io.github.xgl34222220.hetu.home.homeGlassPanel
import io.github.xgl34222220.hetu.home.homeDiffuseCanvas
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.home.HomeBarBackdrop
import io.github.xgl34222220.hetu.home.HomeButton
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.home.HomeCardTitle
import io.github.xgl34222220.hetu.home.HomeChevron
import io.github.xgl34222220.hetu.home.HomeDialog
import io.github.xgl34222220.hetu.home.HomeDialogCard
import io.github.xgl34222220.hetu.home.HomeDialogConfirm
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeEmptyState
import io.github.xgl34222220.hetu.home.HomeFormField
import io.github.xgl34222220.hetu.home.HomeHaptic
import io.github.xgl34222220.hetu.home.HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeLargeTitleDims
import io.github.xgl34222220.hetu.home.HomeMenuDivider
import io.github.xgl34222220.hetu.home.HomeMenuItem
import io.github.xgl34222220.hetu.home.HomeMenuTitle
import io.github.xgl34222220.hetu.home.HomeMotion
import io.github.xgl34222220.hetu.home.HomeNotice
import io.github.xgl34222220.hetu.home.HomePill
import io.github.xgl34222220.hetu.home.HomePop
import io.github.xgl34222220.hetu.home.HomeProgressBar
import io.github.xgl34222220.hetu.home.HomeRefreshBox
import io.github.xgl34222220.hetu.home.HomeRollingText
import io.github.xgl34222220.hetu.home.HomeRowDims
import io.github.xgl34222220.hetu.home.HomeRowDivider
import io.github.xgl34222220.hetu.home.HomeRowLayout
import io.github.xgl34222220.hetu.home.HomeSearchField
import io.github.xgl34222220.hetu.home.HomeSegmentStyle
import io.github.xgl34222220.hetu.home.HomeSegmented
import io.github.xgl34222220.hetu.home.HomeSpinner
import io.github.xgl34222220.hetu.home.HomeStatusDot
import io.github.xgl34222220.hetu.home.HomeSwitch
import io.github.xgl34222220.hetu.home.HomeSwitchRow
import io.github.xgl34222220.hetu.home.HomeTone
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeBarProgressive
import io.github.xgl34222220.hetu.home.LocalHomeBlur
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeHaptics
import io.github.xgl34222220.hetu.home.LocalHomeMotionEnabled
import io.github.xgl34222220.hetu.home.badText
import io.github.xgl34222220.hetu.home.homeEnter
import io.github.xgl34222220.hetu.home.homeGlassSource
import io.github.xgl34222220.hetu.home.homeRowHighlight
import io.github.xgl34222220.hetu.home.homeTap
import io.github.xgl34222220.hetu.home.rememberHomeBarGlass
import io.github.xgl34222220.hetu.home.rememberHomeStagger
import io.github.xgl34222220.hetu.ui.HetuStaggerState
import io.github.xgl34222220.hetu.ui.ht
import kotlinx.coroutines.launch

/*
 * The component names the 设置 pages and the tool pages outside the tools module are written
 * against. Every one of them is now drawn with the home design kit (tokens, rows, switches,
 * dialogs, menus, motion), so these pages look and move like 首页, 面板 and 工具. Parameters
 * that only nudged a single page's pixels are still accepted, and ignored.
 *
 * Text: titles of pages, sheets, cards and rows are drawn as given, because any of them may be
 * a name the user chose; callers pass UI text through `ht`. Only what can never be data is
 * translated here: button labels, field labels and hints, and the words of a dialog.
 */

/* ------------------------------------------------------------------ */
/*  Page scaffold                                                      */
/* ------------------------------------------------------------------ */

internal val HxTopBarHeight = HomeDims.barHeight

/** Where the large title of a pushed page starts; it lines up with the back chevron above it. */
private val HxSubPageTitleInset = 22.dp

/** Hands the blocks of one page their place in its entrance, in the order they are composed. */
internal class HxEntrance(val stagger: HetuStaggerState) {
    private var next = 0
    fun claim(): Int = next++
}

internal val LocalHxEntrance = staticCompositionLocalOf<HxEntrance?> { null }

/**
 * Page entrance of a block inside an [HxPage]: the blocks composed as the page opens rise and
 * fade in one after another; a block that scrolls in later is simply there. Draw-phase only.
 */
@Composable
internal fun Modifier.hxPageEnter(): Modifier {
    val entrance = LocalHxEntrance.current ?: return this
    val index = remember(entrance) { entrance.claim() }
    return homeEnter(entrance.stagger, index)
}

/**
 * Every Hx screen uses this: a lazy list that scrolls under a glass bar.
 *
 * - Tab root (no [onBack], [largeTitle]): the 36 sp title sits at the left of the first rows
 *   with the page's actions pinned at the right. As the list scrolls the title leaves with it
 *   and a compact title fades into the bar.
 * - Pushed page with [largeTitle]: back and actions in the bar, the large title under it.
 * - Pushed page without it: the title is centred in the bar, [subtitle] hanging below.
 *
 * [title] and [subtitle] are drawn as given: a page may be named after a file or a node, so the
 * caller resolves UI text through `ht` itself.
 */
@Composable
internal fun HxPage(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    bottomPadding: Dp = 0.dp,
    listState: LazyListState = rememberLazyListState(),
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    leadingActions: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    scrollToTopSignal: Int = 0,
    showScrollTop: Boolean = true,
    largeTitle: Boolean = true,
    @Suppress("UNUSED_PARAMETER") largeTitleStartPadding: Dp = Hx.gutter,
    @Suppress("UNUSED_PARAMETER") largeTitleTopPadding: Dp = 0.dp,
    @Suppress("UNUSED_PARAMETER") largeTitleFontSizeSp: Float? = null,
    @Suppress("UNUSED_PARAMETER") compactTitleFontSizeSp: Float = 20f,
    @Suppress("UNUSED_PARAMETER") largeTitleBottomPadding: Dp = 10.dp,
    canvasColor: Color? = null,
    @Suppress("UNUSED_PARAMETER") flatCanvas: Boolean = true,
    @Suppress("UNUSED_PARAMETER") referenceTopBar: Boolean = true,
    @Suppress("UNUSED_PARAMETER") referenceLabel: String? = null,
    content: LazyListScope.() -> Unit,
) {
    val embed = LocalHxEmbed.current
    if (embed != null) {
        HxEmbeddedPage(embed, subtitle, refreshing, onRefresh, leadingActions, actions, overlay, content)
        return
    }
    // Re-tapping the current dock item scrolls the page back to the top.
    val initialSignal = remember { scrollToTopSignal }
    LaunchedEffect(scrollToTopSignal) {
        if (scrollToTopSignal != initialSignal) listState.animateScrollToItem(0)
    }
    val c = LocalHomeColors.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val shownTitle = title
    val shownSubtitle = subtitle?.takeIf { it.isNotBlank() }
    val root = onBack == null
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val titlePx = with(density) { HomeLargeTitleDims.title.toPx() }
    // 0 → large title in place, 1 → scrolled away. Read only in draw, so scrolling never recomposes.
    val headerProgress = remember(listState, titlePx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f else (listState.firstVisibleItemScrollOffset / titlePx).coerceIn(0f, 1f)
        }
    }
    val lifted by remember(listState, titlePx, largeTitle) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > (if (largeTitle) titlePx * .6f else 6f)
        }
    }
    val scrollTopWanted by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 7 } }
    val listBottom = (if (bottomPadding > 0.dp) bottomPadding else navInset) + 24.dp
    val rowHeight = if (root && largeTitle) HomeLargeTitleDims.bar else HomeDims.barHeight
    val barHeight = rowHeight + if (!largeTitle && shownSubtitle != null) 14.dp else 0.dp
    val listTop = statusTop + when {
        !largeTitle -> barHeight + 6.dp
        root -> HomeLargeTitleDims.titleTop
        else -> barHeight
    }
    // Read on every composition: it is a memory lookup, and the page that changes it sees it at once.
    val progressive = context.getSharedPreferences("hetu", 0).getString("topBarBlurStyle", "progressive") != "gaussian"

    val stagger = rememberHomeStagger()
    val entrance = remember(stagger) { HxEntrance(stagger) }

    CompositionLocalProvider(LocalHomeBlur provides LocalHxBlur.current, LocalHomeBarProgressive provides progressive, LocalHxEntrance provides entrance) {
        val glass = rememberHomeBarGlass(lifted)
        Box(Modifier.fillMaxSize().homeDiffuseCanvas(canvasColor)) {
            val list: @Composable () -> Unit = {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().homeGlassSource(glass),
                    contentPadding = PaddingValues(top = listTop, bottom = listBottom),
                ) {
                    if (largeTitle) item(key = "hx-page-header") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = if (root) HomeLargeTitleDims.inset else HxSubPageTitleInset, end = if (root) 72.dp else HxSubPageTitleInset, bottom = 14.dp)
                                .graphicsLayer {
                                    val p = headerProgress.value
                                    alpha = (1f - p * 1.6f).coerceIn(0f, 1f)
                                    val scale = 1f - .06f * p
                                    scaleX = scale
                                    scaleY = scale
                                    transformOrigin = TransformOrigin(0f, 1f)
                                },
                        ) {
                            Text(shownTitle, Modifier.semantics { heading() }, color = c.t1, style = HomeType.largeTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (shownSubtitle != null) Text(shownSubtitle, Modifier.padding(top = 2.dp), color = c.t2, style = HomeType.note.copy(fontSize = 15.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    content()
                }
            }
            if (onRefresh != null) {
                HomeRefreshBox(refreshing, onRefresh, ht("刷新"), Modifier.fillMaxSize(), indicatorPadding = PaddingValues(top = statusTop + barHeight)) { list() }
            } else {
                list()
            }

            HomeBarBackdrop(glass, Modifier.fillMaxWidth().height(statusTop + barHeight))
            Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).heightIn(min = barHeight)) {
                val twoLine = !largeTitle && shownSubtitle != null
                HxBarRow(
                    Modifier.fillMaxWidth().height(rowHeight).padding(horizontal = 6.dp),
                    start = {
                        if (onBack != null) HomeIconButton(HomeIcons.ChevronLeft, "返回", onBack, glyph = 26.dp)
                        leadingActions()
                    },
                    end = actions,
                ) {
                    // With a large title below it this one is only for the eye: it fades in as
                    // the large one leaves and is not announced a second time.
                    Text(
                        shownTitle,
                        (if (largeTitle) Modifier.clearAndSetSemantics { } else Modifier.semantics { heading() })
                            .padding(start = 8.dp, end = 8.dp, bottom = if (twoLine) 10.dp else 0.dp)
                            .graphicsLayer {
                                if (largeTitle) {
                                    val p = ((headerProgress.value - .55f) / .45f).coerceIn(0f, 1f)
                                    alpha = p
                                    translationY = (1f - p) * 8.dp.toPx()
                                }
                            },
                        color = c.t1, style = HomeType.barTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                }
                if (twoLine) {
                    Text(
                        shownSubtitle.orEmpty(), Modifier.align(Alignment.TopCenter).padding(top = rowHeight - 22.dp, start = 28.dp, end = 28.dp),
                        color = c.t2, style = HomeType.barSubtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                }
            }

            // Long lists get a small "back to top" button once the user is deep in them.
            val scrollScope = rememberCoroutineScope()
            HomePop(showScrollTop && scrollTopWanted, Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = listBottom - 6.dp)) {
                HxScrollTopButton { scrollScope.launch { listState.animateScrollToItem(0) } }
            }
            overlay()
        }
    }
}

/**
 * The row of a top bar: buttons at both ends and the title between them. The title sits on the
 * bar's centre line whenever it fits there; when one side holds more buttons than the other
 * leaves room for, it centres in the space that is left instead of sliding under them.
 */
@Composable
private fun HxBarRow(
    modifier: Modifier,
    start: @Composable RowScope.() -> Unit,
    end: @Composable RowScope.() -> Unit,
    title: @Composable () -> Unit,
) {
    Layout(
        content = {
            Row(verticalAlignment = Alignment.CenterVertically, content = start)
            Row(verticalAlignment = Alignment.CenterVertically, content = end)
            Box(contentAlignment = Alignment.Center) { title() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val leading = measurables[0].measure(loose)
        val trailing = measurables[1].measure(loose.copy(maxWidth = (constraints.maxWidth - leading.width).coerceAtLeast(0)))
        val free = (constraints.maxWidth - leading.width - trailing.width).coerceAtLeast(0)
        val label = measurables[2].measure(loose.copy(maxWidth = free))
        val centredRoom = constraints.maxWidth - 2 * maxOf(leading.width, trailing.width)
        val x = if (label.width <= centredRoom) (constraints.maxWidth - label.width) / 2 else leading.width + (free - label.width) / 2
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else maxOf(leading.height, trailing.height, label.height)
        layout(constraints.maxWidth, height) {
            leading.place(0, (height - leading.height) / 2)
            trailing.place(constraints.maxWidth - trailing.width, (height - trailing.height) / 2)
            label.place(x, (height - label.height) / 2)
        }
    }
}

@Composable
private fun HxScrollTopButton(onClick: () -> Unit) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val label = ht("回到顶部")
    Box(
        Modifier
            .size(46.dp)
            .homeTap(onClickLabel = label) { haptics(HomeHaptic.Tick); onClick() }
            .homeGlassPanel(CircleShape, c.raised, raised = true)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(HomeIcons.ArrowUp, null, Modifier.size(22.dp), tint = c.t1) }
}

/**
 * [HxPage] inside an [HxTabbedPage] section: just the list. Its bar actions, overlay and
 * subtitle are handed to the host page through stable wrappers, so they update in place
 * without the host and the section recomposing each other in a loop.
 */
@Composable
private fun HxEmbeddedPage(
    embed: HxEmbed,
    subtitle: String?,
    refreshing: Boolean,
    onRefresh: (() -> Unit)?,
    leadingActions: @Composable RowScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val currentLeadingActions by rememberUpdatedState(leadingActions)
    val currentActions by rememberUpdatedState(actions)
    val currentOverlay by rememberUpdatedState(overlay)
    val stableLeadingActions = remember<@Composable RowScope.() -> Unit> { { currentLeadingActions.invoke(this) } }
    val stableActions = remember<@Composable RowScope.() -> Unit> { { currentActions.invoke(this) } }
    val stableOverlay = remember<@Composable BoxScope.() -> Unit> { { currentOverlay.invoke(this) } }
    val host = embed.host
    val key = embed.key
    DisposableEffect(host, key) {
        host.leadingActions[key] = stableLeadingActions
        host.actions[key] = stableActions
        host.overlays[key] = stableOverlay
        onDispose {
            host.leadingActions.remove(key)
            host.actions.remove(key)
            host.overlays.remove(key)
        }
    }
    SideEffect { if (host.subtitles[key] != subtitle) host.subtitles[key] = subtitle }
    val list: @Composable () -> Unit = {
        LazyColumn(
            state = embed.listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = embed.top, bottom = embed.bottom),
        ) { content() }
    }
    if (onRefresh != null) {
        HomeRefreshBox(refreshing, onRefresh, ht("刷新"), Modifier.fillMaxSize(), indicatorPadding = PaddingValues(top = (embed.top - 6.dp).coerceAtLeast(0.dp))) { list() }
    } else {
        list()
    }
}

/* ------------------------------------------------------------------ */
/*  Surfaces                                                            */
/* ------------------------------------------------------------------ */

/** Press feedback for bespoke tappable surfaces: a small, quick scale. */
@Composable
internal fun Modifier.hxPressScale(source: MutableInteractionSource, pressedScale: Float = .975f): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, HomeMotion.glide(LocalHomeMotionEnabled.current), label = "press")
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Glass card on the canvas: existing 24 dp continuous corners, no resting shadow. */
@Composable
internal fun HxCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = LocalHomeColors.current.surface,
    padding: PaddingValues = PaddingValues(HomeDims.cardPadding),
    brush: Brush? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val haptics = LocalHomeHaptics.current
    val tappable = if (onClick == null) modifier else modifier.homeTap { haptics(HomeHaptic.Tap); onClick() }
    Column(
        tappable.homeGlassPanel(HomeDims.cardShape, color).let { if (brush != null) it.background(brush) else it }.padding(padding),
        content = content,
    )
}

/** A titled block of content with the page gutters. */
@Composable
internal fun HxSection(
    title: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().hxPageEnter().padding(horizontal = HomeDims.gutter).padding(bottom = HomeDims.gap)) {
        if (title != null || trailing != null) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, bottom = 8.dp).heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title.orEmpty(), Modifier.weight(1f).semantics { heading() }, color = LocalHomeColors.current.t2, style = HomeType.noteStrong)
                if (trailing != null) trailing()
            }
        }
        content()
    }
}

/** Rows stacked in one card. Rows that come and go resize the card smoothly. */
@Composable
internal fun HxGroup(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalHomeColors.current
    Column(
        modifier.fillMaxWidth().semantics { isTraversalGroup = true }.homeGlassPanel()
            .animateContentSize(HomeMotion.glide(LocalHomeMotionEnabled.current)).padding(top = if (title == null) 4.dp else 0.dp, bottom = 4.dp),
    ) {
        if (title != null) HomeCardTitle(title)
        content()
    }
}

/** Hairline between two rows of a card, starting under the text column. */
@Composable
internal fun HxDivider(inset: Dp = HomeRowDims.textStart) {
    HomeRowDivider(start = if (inset > 0.dp) HomeRowDims.textStart else HomeRowDims.start)
}

@Composable
internal fun HxIconBadge(icon: ImageVector, tint: Color = LocalHomeColors.current.t1, size: Dp = HomeRowDims.icon) {
    Icon(hxLineIcon(icon), contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

/**
 * The single list-row primitive. Title, optional supporting line, optional trailing slot; the
 * whole row is tappable when [onClick] is set, and remembers where it was touched so a menu
 * opened from it can grow out of it. [selected] keeps the row marked while that menu is open.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HxRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    @Suppress("UNUSED_PARAMETER") iconTint: Color = LocalHomeColors.current.t1,
    enabled: Boolean = true,
    danger: Boolean = false,
    @Suppress("UNUSED_PARAMETER") referenceRow: Boolean = true,
    @Suppress("UNUSED_PARAMETER") referenceTextSizeSp: Float = 18f,
    minimumHeight: Dp = 0.dp,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val source = remember { MutableInteractionSource() }
    val line = subtitle?.takeIf { it.isNotBlank() }
    val standard = if (line == null) HomeRowDims.oneLine else HomeRowDims.twoLine
    val interactive = if ((onClick != null || onLongClick != null) && enabled) Modifier.combinedClickable(
        interactionSource = source,
        indication = null,
        onLongClick = onLongClick?.let { long -> { haptics(HomeHaptic.Confirm); long() } },
        onClick = { if (onClick != null) { haptics(HomeHaptic.Tap); onClick() } },
    ) else Modifier
    HomeRowLayout(
        title = AnnotatedString(title),
        modifier = modifier.hxAnchorSource().homeRowHighlight(source, selected).then(interactive),
        subtitle = line,
        icon = icon?.let(::hxLineIcon),
        iconTint = if (danger) c.bad else c.t1,
        titleColor = if (danger) c.badText else c.t1,
        titleMaxLines = 2,
        subtitleMaxLines = 3,
        enabled = enabled,
        minHeight = if (minimumHeight > standard) minimumHeight else standard,
        trailing = trailing,
    )
}

@Composable
internal fun HxChevron() = HomeChevron()

/** A row that leads somewhere, or opens a picker when [dropdown]; [value] is the current choice. */
@Composable
internal fun HxNavRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = LocalHomeColors.current.t1,
    value: String? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    dropdown: Boolean = false,
    onClick: () -> Unit,
) {
    HxRow(title, subtitle = subtitle, icon = icon, iconTint = iconTint, enabled = enabled, danger = danger, onClick = onClick) {
        HxRowValue(value, dropdown)
    }
}

/** The current value at the end of a row, then the chevron that says what a tap does. */
@Composable
internal fun RowScope.HxRowValue(value: String?, dropdown: Boolean = false) {
    val c = LocalHomeColors.current
    if (!value.isNullOrBlank()) {
        HomeRollingText(value, c.t2, HomeType.label.copy(fontWeight = FontWeight.Medium), Modifier.widthIn(max = 150.dp), alignment = Alignment.CenterEnd)
    }
    HomeChevron(dropdown = dropdown)
}

/** One labelled switch: the whole row toggles and is announced once. */
@Composable
internal fun HxSwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    @Suppress("UNUSED_PARAMETER") iconTint: Color = LocalHomeColors.current.t1,
    enabled: Boolean = true,
) {
    HomeSwitchRow(
        title = AnnotatedString(title), checked = checked, onCheckedChange = onChange,
        subtitle = subtitle?.takeIf { it.isNotBlank() }, icon = icon?.let(::hxLineIcon), enabled = enabled,
    )
}

/** A switch that stands on its own, outside a row. */
@Composable
internal fun HxSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val haptics = LocalHomeHaptics.current
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(width = 50.dp, height = 48.dp)
            .toggleable(value = checked, interactionSource = source, indication = null, enabled = enabled, role = Role.Switch) { on ->
                haptics(HomeHaptic.Tick)
                onChange(on)
            },
        contentAlignment = Alignment.Center,
    ) { HomeSwitch(checked, enabled = enabled) }
}

/* ------------------------------------------------------------------ */
/*  Small pieces                                                        */
/* ------------------------------------------------------------------ */

internal enum class HxTone { Neutral, Accent, Good, Warn, Bad }

internal fun HxTone.home(): HomeTone = when (this) {
    HxTone.Neutral -> HomeTone.Neutral
    HxTone.Accent -> HomeTone.Accent
    HxTone.Good -> HomeTone.Good
    HxTone.Warn -> HomeTone.Warn
    HxTone.Bad -> HomeTone.Bad
}

@Composable
internal fun HxTone.fg(): Color = LocalHomeColors.current.let { c ->
    when (this) {
        HxTone.Neutral -> c.t2
        HxTone.Accent -> c.accent
        HxTone.Good -> c.good
        HxTone.Warn -> c.warn
        HxTone.Bad -> c.bad
    }
}

@Composable
internal fun HxTone.bg(): Color = LocalHomeColors.current.let { c ->
    when (this) {
        HxTone.Neutral -> c.sunken
        HxTone.Accent -> c.accentSoft
        HxTone.Good -> c.goodSoft
        HxTone.Warn -> c.warnSoft
        HxTone.Bad -> c.badSoft
    }
}

@Composable
internal fun HxPill(text: String, tone: HxTone = HxTone.Neutral, modifier: Modifier = Modifier) = HomePill(text, modifier, tone.home())

@Composable
internal fun HxDot(color: Color, size: Dp = 8.dp) = HomeStatusDot(color, size = size)

@Composable
internal fun HxSpinner(size: Dp = 18.dp, color: Color = LocalHomeColors.current.accent) = HomeSpinner(size = size, color = color)

/** Inline status message with an optional action. */
@Composable
internal fun HxBanner(
    text: String,
    tone: HxTone = HxTone.Accent,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") referenceCompact: Boolean = false,
) {
    val icon = when (tone) {
        HxTone.Bad -> HomeIcons.CircleAlert
        HxTone.Warn -> HomeIcons.TriangleAlert
        HxTone.Good -> HomeIcons.CircleCheck
        else -> HomeIcons.Info
    }
    HomeNotice(text, icon, modifier, tone = tone.home(), actionLabel = actionLabel, onAction = onAction)
}

/** [title] is UI text; [description] is drawn as given (often an error from the host). */
@Composable
internal fun HxEmpty(icon: ImageVector, title: String, description: String? = null, action: (@Composable () -> Unit)? = null) {
    HomeEmptyState(hxLineIcon(icon), title, description.orEmpty(), topPadding = 44.dp, verbatimSubtitle = true, action = action)
}

/** Segmented control with a thumb that slides between segments. */
@Composable
internal fun HxSegmented(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @Suppress("UNUSED_PARAMETER") selectionColor: Color = LocalHomeColors.current.surface,
    @Suppress("UNUSED_PARAMETER") selectedTextColor: Color = LocalHomeColors.current.t1,
) {
    HomeSegmented(options, selected, onSelect, modifier, enabled, style = HomeSegmentStyle.Raised, height = 44.dp, corner = 14.dp)
}

@Composable
internal fun HxSearchField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
) = HomeSearchField(value, onChange, placeholder, modifier, autoFocus)

/** A metric with a small caption. */
@Composable
internal fun HxMetric(label: String, value: String, modifier: Modifier = Modifier, unit: String? = null, valueColor: Color = LocalHomeColors.current.t1) {
    val c = LocalHomeColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = HomeType.caption, color = c.t2, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(value, style = HomeType.value, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!unit.isNullOrBlank()) Text(unit, Modifier.padding(bottom = 2.dp), style = HomeType.badge, color = c.t2)
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Sheets                                                              */
/* ------------------------------------------------------------------ */

internal fun hxCopy(context: Context, label: String, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}

/** Bottom sheet dressed with the home tokens: 28 dp top corners, a grab handle, an optional title row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HxSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    showClose: Boolean = false,
    containerColor: Color = LocalHomeColors.current.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalHomeColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Actions inside a sheet close it with the slide-down animation first, then run —
    // instead of the sheet vanishing in a single frame when its state flips to null.
    val close: (() -> Unit) -> Unit = remember(sheetState, scope) {
        { after ->
            scope.launch {
                try {
                    sheetState.hide()
                } finally {
                    after()
                }
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = containerColor,
        contentColor = c.t1,
        scrimColor = c.scrim,
        shape = HomeDims.sheetShape,
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 8.dp).size(width = 40.dp, height = 5.dp).background(if (c.dark) c.line2 else Color(0xFFC9CCDA), HomeDims.pillShape))
        },
    ) {
        CompositionLocalProvider(LocalHxSheetClose provides close) {
            // Keep material inside the sheet's anchored layout so clipping follows its offset.
            Column(Modifier.fillMaxWidth().homeGlassPanel(RectangleShape, containerColor, raised = true).navigationBarsPadding().padding(bottom = 14.dp)) {
                if (title != null) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = HomeDims.touch).padding(start = 20.dp, end = if (showClose) 8.dp else 20.dp).padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(title, Modifier.weight(1f).semantics { heading() }, color = c.t1, style = HomeType.sheetTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (showClose) HomeIconButton(HomeIcons.X, "关闭详情", { close(onDismiss) })
                    }
                }
                content()
            }
        }
    }
}

/**
 * Inside an [HxSheet]: `LocalHxSheetClose.current { … }` animates the sheet away and then
 * runs the block. Outside a sheet it simply runs the block.
 */
internal val LocalHxSheetClose = staticCompositionLocalOf<(() -> Unit) -> Unit> { { after -> after() } }

/**
 * Text viewer (logs, diagnostics, generated config, licences).
 *
 * @param wrapLines prose that wraps, in the UI font; otherwise monospace lines that scroll sideways.
 * @param referenceDocument a long legal text: monospace, but wrapped.
 */
@Composable
internal fun HxTextSheet(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    wrapLines: Boolean = false,
    showCopyLabel: Boolean = false,
    referenceDocument: Boolean = false,
) {
    val context = LocalContext.current
    val c = LocalHomeColors.current
    HxSheet(onDismiss = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).semantics { heading() }, color = c.t1, style = HomeType.sheetTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (onRefresh != null) HomeIconButton(HomeIcons.RefreshCw, "刷新", onRefresh)
            if (showCopyLabel) HomeButton("复制", { hxCopy(context, title, text) }, kind = HomeButtonKind.Soft, icon = HomeIcons.Copy, height = 44.dp, tinted = true)
            else HomeIconButton(HomeIcons.Copy, "复制", { hxCopy(context, title, text) })
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * .62f).dp)
                .padding(horizontal = 14.dp)
                .clip(HomeDims.innerShape)
                .background(if (c.dark) c.sunken else c.bg)
                .verticalScroll(rememberScrollState())
                .then(if (wrapLines) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                text.ifBlank { ht("（空）") },
                color = c.t1,
                style = when {
                    referenceDocument -> HxMonoStyle.copy(fontSize = 12.sp, lineHeight = 18.sp)
                    wrapLines -> HomeType.body.copy(fontSize = 15.sp, lineHeight = 23.sp)
                    else -> HxMonoStyle
                },
            )
        }
    }
}

internal val HxMonoStyle = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, fontFamily = FontFamily.Monospace)

/* ------------------------------------------------------------------ */
/*  Choices                                                             */
/* ------------------------------------------------------------------ */

internal enum class HxChoicePresentation { Standard, Settings, Language, Scale, Notification }

internal data class HxChoice(val id: String, val label: String, val description: String? = null, val enabled: Boolean = true)

/**
 * Pick one of [choices]. Opened from a row that was just touched it is a menu growing out of
 * that row, with the current choice ticked; otherwise (keyboard, accessibility) it is a bottom
 * sheet with the same rows.
 *
 * @param maxVisibleChoices cap the menu at this many rows; the rest scroll.
 * @param referenceRadios show [title] at the top of the menu (the pickers of 网络匹配).
 */
@Composable
internal fun HxChoiceSheet(
    title: String,
    choices: List<HxChoice>,
    selected: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    footer: String? = null,
    dimBehind: Boolean = true,
    referenceRadios: Boolean = false,
    trailingReferenceRadios: Boolean = false,
    @Suppress("UNUSED_PARAMETER") presentation: HxChoicePresentation = HxChoicePresentation.Standard,
    @Suppress("UNUSED_PARAMETER") menuWidthOverride: Dp? = null,
    maxVisibleChoices: Int? = null,
    @Suppress("UNUSED_PARAMETER") menuItemVerticalPadding: Dp? = null,
    @Suppress("UNUSED_PARAMETER") menuItemMinimumHeight: Dp? = null,
    @Suppress("UNUSED_PARAMETER") menuItemFontSizeSp: Float? = null,
    @Suppress("UNUSED_PARAMETER") menuAnchorEndInset: Dp? = null,
    @Suppress("UNUSED_PARAMETER") menuContentTopPadding: Dp? = null,
    @Suppress("UNUSED_PARAMETER") menuContentBottomPadding: Dp? = null,
    @Suppress("UNUSED_PARAMETER") menuRowPresentation: HxChoicePresentation? = null,
    @Suppress("UNUSED_PARAMETER") menuDividers: Boolean? = null,
    @Suppress("UNUSED_PARAMETER") menuSelectedHorizontalPadding: Dp? = null,
    @Suppress("UNUSED_PARAMETER") menuSelectedTextColor: Color? = null,
    @Suppress("UNUSED_PARAMETER") menuSelectedVerticalVisualInset: Dp? = null,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    // Opened from a row: show a dropdown right next to it (the row stays visible).
    val anchor = remember { HxAnchor.take() }
    if (anchor != null) {
        HxAnchoredMenu(anchor, onDismiss, dimBehind = dimBehind && (referenceRadios || trailingReferenceRadios), minWidth = 196.dp) { close ->
            if (referenceRadios || trailingReferenceRadios) {
                HomeMenuTitle(title)
                HomeMenuDivider()
            }
            val rows: @Composable () -> Unit = {
                choices.forEach { choice ->
                    HomeMenuItem(
                        choice.label, { close { onPick(choice.id) } },
                        description = choice.description, enabled = choice.enabled, checked = choice.id == selected,
                    )
                }
            }
            // Half a row peeks out below the cap, so it is plain that the list goes on.
            if (maxVisibleChoices != null && choices.size > maxVisibleChoices) {
                Column(Modifier.fillMaxWidth().heightIn(max = HxMenuRowHeight * maxVisibleChoices + HxMenuRowHeight / 2).verticalScroll(rememberScrollState())) { rows() }
            } else rows()
            if (!footer.isNullOrBlank()) {
                Text(footer, Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp), color = c.t2, style = HomeType.caption)
            }
        }
        return
    }
    // The tapped row lights up and its check pops in; the sheet then slides away.
    var picked by remember { mutableStateOf(selected) }
    var closing by remember { mutableStateOf(false) }
    HxSheet(onDismiss = onDismiss, title = title) {
        val close = LocalHxSheetClose.current
        Column(Modifier.padding(horizontal = 8.dp).verticalScroll(rememberScrollState())) {
            choices.forEach { choice ->
                HomeMenuItem(
                    choice.label,
                    onClick = {
                        if (!closing) {
                            closing = true
                            haptics(HomeHaptic.Tick)
                            picked = choice.id
                            close { onPick(choice.id) }
                        }
                    },
                    Modifier.heightIn(min = 54.dp),
                    description = choice.description, enabled = choice.enabled, checked = choice.id == picked,
                )
            }
            if (!footer.isNullOrBlank()) {
                Text(footer, Modifier.padding(horizontal = 12.dp, vertical = 12.dp), color = c.t2, style = HomeType.note)
            }
        }
    }
}

/** Height of one menu row at font scale 1; used to size capped menus. */
internal val HxMenuRowHeight = 46.dp

/* ------------------------------------------------------------------ */
/*  Dialogs                                                             */
/* ------------------------------------------------------------------ */

internal data class HxField(
    val label: String,
    val initial: String = "",
    val placeholder: String = "",
    val singleLine: Boolean = true,
    val number: Boolean = false,
)

/**
 * Form dialog: returns the entered values in field order. A failed [validate] unfolds its
 * message under the field it belongs to ([errorField], or the last field) and gives the card a
 * short shake.
 *
 * @param messageBelowFields show [message] under the fields as a hint rather than above them.
 */
@Composable
internal fun HxFormDialog(
    title: String,
    fields: List<HxField>,
    confirmLabel: String = "保存",
    message: String? = null,
    @Suppress("UNUSED_PARAMETER") configFooter: Boolean = false,
    messageBelowFields: Boolean = false,
    errorField: (String) -> Int? = { null },
    @Suppress("UNUSED_PARAMETER") widthFraction: Float? = null,
    @Suppress("UNUSED_PARAMETER") errorWidthFraction: Float? = null,
    @Suppress("UNUSED_PARAMETER") titleFontSize: TextUnit = 22.sp,
    @Suppress("UNUSED_PARAMETER") titleLineHeight: TextUnit = 28.sp,
    @Suppress("UNUSED_PARAMETER") titleTextAlign: TextAlign = TextAlign.Center,
    @Suppress("UNUSED_PARAMETER") centerTitleOnError: Boolean = false,
    @Suppress("UNUSED_PARAMETER") hideMessageOnError: Boolean = false,
    @Suppress("UNUSED_PARAMETER") highlightError: Boolean = true,
    @Suppress("UNUSED_PARAMETER") fieldOutlineColor: Color? = null,
    @Suppress("UNUSED_PARAMETER") errorFieldOutlineColor: Color? = null,
    @Suppress("UNUSED_PARAMETER") labelFontWeight: FontWeight? = null,
    @Suppress("UNUSED_PARAMETER") inputFontWeight: FontWeight? = null,
    @Suppress("UNUSED_PARAMETER") confirmColorOverride: Color? = null,
    @Suppress("UNUSED_PARAMETER") normalPlainFieldHeight: Dp? = null,
    @Suppress("UNUSED_PARAMETER") normalPlainFieldVerticalPadding: Dp? = null,
    @Suppress("UNUSED_PARAMETER") plainFields: Boolean = false,
    @Suppress("UNUSED_PARAMETER") compactPills: Boolean = false,
    validate: (List<String>) -> String? = { null },
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHomeHaptics.current
    val motion = LocalHomeMotionEnabled.current
    val values = remember { fields.map { mutableStateOf(it.initial) } }
    var error by remember { mutableStateOf<String?>(null) }
    // Invalid input: a short horizontal shake plus a reject haptic, like a wrong passcode.
    var shakeTick by remember { mutableIntStateOf(0) }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(shakeTick) {
        if (shakeTick > 0) {
            haptics(HomeHaptic.Reject)
            if (motion) for (x in listOf(12f, -9f, 6f, -3f, 0f)) shake.animateTo(x, tween(48))
        }
    }
    val submit: () -> Unit = {
        val result = values.map { it.value.trim() }
        val problem = validate(result)
        if (problem != null) { error = problem; shakeTick++ } else onConfirm(result)
    }
    val errorIndex = error?.let { errorField(it) ?: fields.lastIndex }
    HomeDialog(onDismiss) {
        HomeDialogCard(
            title = ht(title), confirmLabel = confirmLabel, onConfirm = submit, onCancel = onDismiss,
            modifier = Modifier.graphicsLayer { translationX = shake.value * density },
            text = message?.takeIf { it.isNotBlank() && !messageBelowFields }?.let { ht(it) },
        ) {
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                fields.forEachIndexed { index, field ->
                    HomeFormField(
                        label = field.label,
                        value = values[index].value,
                        onValueChange = { values[index].value = it; error = null },
                        placeholder = field.placeholder,
                        error = if (errorIndex == index) error else null,
                        hint = if (messageBelowFields && index == fields.lastIndex) message?.takeIf { it.isNotBlank() } else null,
                        keyboardType = if (field.number) KeyboardType.Number else KeyboardType.Text,
                    )
                }
            }
        }
    }
}

internal enum class HxConfirmStyle { Filled, Text, SoftRow, SoftStack, DestructiveStack }

/** One question, two answers. [danger] turns the confirming button red and adds a warning mark. */
@Composable
internal fun HxConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "确定",
    danger: Boolean = false,
    @Suppress("UNUSED_PARAMETER") presentation: HxConfirmStyle = HxConfirmStyle.Filled,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    HomeDialog(onDismiss) {
        HomeDialogCard(
            title = ht(title), confirmLabel = confirmLabel, onConfirm = onConfirm, onCancel = onDismiss,
            text = message.takeIf { it.isNotBlank() }?.let { ht(it) },
            icon = if (danger) HomeIcons.TriangleAlert else null, iconTone = HomeTone.Bad,
            confirm = if (danger) HomeDialogConfirm.Danger else HomeDialogConfirm.Primary,
        )
    }
}

/** A dialog card with free content, for the few dialogs that are neither a form nor a question. */
@Composable
internal fun HxReferenceDialog(
    onDismiss: () -> Unit,
    @Suppress("UNUSED_PARAMETER") widthFraction: Float = .84f,
    contentPadding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalHomeColors.current
    val shape = RoundedCornerShape(26.dp)
    HomeDialog(onDismiss) {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .homeGlassPanel(shape, c.raised, raised = true)
                .padding(start = contentPadding, end = contentPadding, top = if (contentPadding > 0.dp) 24.dp else 0.dp, bottom = if (contentPadding > 0.dp) 18.dp else 0.dp),
            content = content,
        )
    }
}

/* ------------------------------------------------------------------ */
/*  Buttons, bars, progress, fields                                     */
/* ------------------------------------------------------------------ */

/**
 * Button. [filled] = solid, [outlined] = hairline in the tone's colour, neither = the tone's
 * soft tint. [busy] swaps the icon for a spinner and holds the button.
 */
@Composable
internal fun HxButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    tone: HxTone = HxTone.Accent,
    filled: Boolean = true,
    outlined: Boolean = false,
) {
    HomeButton(
        text, onClick, modifier,
        kind = when { outlined -> HomeButtonKind.Secondary; filled -> HomeButtonKind.Primary; else -> HomeButtonKind.Soft },
        icon = icon?.let(::hxLineIcon), enabled = enabled, loading = busy,
        danger = tone == HxTone.Bad, tinted = tone != HxTone.Neutral,
    )
}

/**
 * Icon action of a top bar. With [anchorMenu] it remembers where it was touched, so the menu it
 * opens grows out of it.
 */
@Composable
internal fun HxBarAction(icon: ImageVector, description: String, onClick: () -> Unit, busy: Boolean = false, enabled: Boolean = true, anchorMenu: Boolean = false) {
    HomeIconButton(hxLineIcon(icon), description, onClick, if (anchorMenu) Modifier.hxAnchorSource() else Modifier, enabled = enabled, loading = busy)
}

/** Thin rounded progress bar with an eased fill. */
@Composable
internal fun HxProgressBar(ratio: Float, color: Color = LocalHomeColors.current.accent, modifier: Modifier = Modifier, height: Dp = 6.dp) =
    HomeProgressBar(ratio, modifier, color = color, track = color.copy(alpha = .16f), height = height)

/**
 * Free-standing input for sheets: a 52 dp outlined field whose outline turns accent while
 * focused. [label] and [placeholder] are slots because their callers already build them.
 */
@Composable
internal fun HxTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    @Suppress("UNUSED_PARAMETER") shape: androidx.compose.ui.graphics.Shape? = null,
) {
    val c = LocalHomeColors.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val outline by androidx.compose.animation.animateColorAsState(if (focused) c.accent else c.line2, HomeMotion.fade(LocalHomeMotionEnabled.current), label = "hx-field-outline")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides HomeType.cardLabel.copy(color = c.t1)) { label() }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = HomeType.body.copy(color = c.t1, fontWeight = FontWeight.Medium),
            singleLine = singleLine,
            maxLines = maxLines,
            keyboardOptions = keyboardOptions,
            interactionSource = source,
            cursorBrush = SolidColor(c.accent),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clip(HomeDims.controlShape)
                        .background(c.surface)
                        .border(if (focused) 1.5.dp else 1.dp, outline, HomeDims.controlShape)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
                ) {
                    if (value.isEmpty() && placeholder != null) {
                        CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides HomeType.body.copy(color = c.t3)) { placeholder() }
                    }
                    inner()
                }
            },
        )
    }
}
