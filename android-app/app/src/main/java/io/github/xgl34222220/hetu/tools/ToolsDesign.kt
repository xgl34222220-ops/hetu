package io.github.xgl34222220.hetu.tools

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.home.HomeBarScaffold
import io.github.xgl34222220.hetu.home.HomeButton
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.home.HomeCard
import io.github.xgl34222220.hetu.home.homeGlassPanel
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeDivider
import io.github.xgl34222220.hetu.home.HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeRefreshBox
import io.github.xgl34222220.hetu.home.HomeRowSubStyle
import io.github.xgl34222220.hetu.home.HomeSegmentStyle
import io.github.xgl34222220.hetu.home.HomeSegmented
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.homeGlassSource
import io.github.xgl34222220.hetu.ui.ht

/**
 * Dimensions of the 工具 pages. They are the home tokens under their old names, so the four
 * tabs share one rhythm: 14 dp gutters and gaps, 24 dp cards, 16 dp controls.
 */
internal object ToolsDesignDims {
    val gutter = HomeDims.gutter
    val gap = HomeDims.gap
    val cardPadding = HomeDims.cardPadding
    val rowMinHeight = HomeDims.rowMinHeight
    val rowMinHeightSmall = HomeDims.rowMinHeightSmall
    val touch = HomeDims.touch
    val barHeight = HomeDims.barHeight
    val dockClearance = HomeDims.dockClearance
    val cardShape = HomeDims.cardShape
    val innerShape = HomeDims.innerShape
    val controlShape = HomeDims.controlShape
    val segmentShape = HomeDims.segmentShape
    val badgeShape = HomeDims.badgeShape
    val chipShape = HomeDims.chipShape
    val sheetShape = HomeDims.sheetShape
    val menuShape = HomeDims.menuShape
}

/** The home type scale under its old names, plus the few sizes only the tool pages use. */
internal object ToolsTypography {
    val largeTitle = HomeType.largeTitle
    val barTitle = HomeType.barTitle
    val barSubtitle = HomeType.barSubtitle
    val sheetTitle = HomeType.sheetTitle
    val heroStatus = HomeType.heroStatus
    val rowTitle = HomeType.rowTitle
    val body = HomeType.body
    val bodySmall = HomeType.bodySmall
    val rowSub = HomeRowSubStyle
    val section = HomeType.section
    val caption = HomeType.caption
    val note = HomeType.note
    val noteStrong = HomeType.noteStrong
    val label = HomeType.label
    val badge = HomeType.badge
    val value = HomeType.value
    val metric = HomeType.metric
    val metricLarge = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
    val delay = HomeType.delay
    val button = HomeType.button
    val buttonSmall = HomeType.buttonSmall
    val mono = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontFamily = FontFamily.Monospace)
}

/**
 * Kept for the hosts and previews that wrap the module in it. The palette itself now comes from
 * the app theme (which provides the home colours for every page), so there is nothing to map.
 */
@Composable
internal fun ToolsConceptTheme(content: @Composable () -> Unit) = content()

/* ------------------------------------------------------------------ */
/*  Page frames                                                         */
/* ------------------------------------------------------------------ */

/** A real refresh gesture, with the same action exposed to keyboard and accessibility users. */
@Composable
internal fun ToolsPullRefresh(
    refreshing: Boolean,
    onRefresh: (() -> Unit)?,
    modifier: Modifier = Modifier,
    indicatorTop: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    if (onRefresh == null) Box(modifier) { content() }
    else HomeRefreshBox(refreshing, onRefresh, ht("刷新"), modifier, indicatorPadding = PaddingValues(top = indicatorTop)) { content() }
}

/**
 * Sub-page of the tools tab: the content scrolls under a glass bar, cards keep the 14 dp rhythm.
 *
 * @param scroll hoisted when the host needs to position the page.
 * @param footer pinned under the content, above the navigation bar (primary actions of a form).
 * @param titleInset room kept free on each side of the centred title; raise it for bars with
 *   more than two actions.
 */
@Composable
internal fun ToolsPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    scroll: ScrollState = rememberScrollState(),
    titleInset: Dp = 104.dp,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val lifted by remember(scroll) { derivedStateOf { scroll.value > 6 } }
    HomeBarScaffold(title, onBack, lifted, modifier, subtitle = subtitle, actions = actions, footer = footer, titleInset = titleInset) { top, glass ->
        ToolsPullRefresh(refreshing, onRefresh, Modifier.fillMaxSize(), indicatorTop = top) {
            Column(
                Modifier
                    .fillMaxSize()
                    .homeGlassSource(glass)
                    .verticalScroll(scroll)
                    .let { if (footer == null) it.windowInsetsPadding(WindowInsets.navigationBars) else it }
                    .padding(start = HomeDims.gutter, end = HomeDims.gutter, top = top + 6.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(HomeDims.gap),
                content = content,
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Surfaces and controls: the home kit under the names the pages use   */
/* ------------------------------------------------------------------ */

@Composable
internal fun ToolsSurfaceCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    clickLabel: String? = null,
    background: Color = LocalHomeColors.current.surface,
    content: @Composable ColumnScope.() -> Unit,
) = HomeCard(modifier, onClick, clickLabel, background, content = content)

@Composable
internal fun ToolsHairline(modifier: Modifier = Modifier) = HomeDivider(modifier)

@Composable
internal fun ToolsIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    tint: Color = LocalHomeColors.current.t1,
    spinning: Boolean = false,
) = HomeIconButton(icon, label, onClick, modifier, enabled, loading, tint, spinning)

/**
 * Button of a tool page. Soft is the accent tint of the concept (下载, 导入, 保留草稿) unless
 * [neutral] asks for the grey one that sits next to a primary action (取消).
 */
@Composable
internal fun ToolsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: HomeButtonKind = HomeButtonKind.Secondary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    neutral: Boolean = false,
    height: Dp = 50.dp,
) = HomeButton(text, onClick, modifier, kind, icon, enabled, loading, height = height, tinted = kind == HomeButtonKind.Soft && !neutral)

/**
 * Tabs of a page (黑名单 / 白名单 / 核心, 从文件导入 / 从链接导入): a card-coloured track whose
 * accent-tinted thumb slides to the chosen tab.
 */
@Composable
internal fun <T> ToolsSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icons: Map<T, ImageVector> = emptyMap(),
    track: Color = LocalHomeColors.current.surface,
    height: Dp = 60.dp,
) = HomeSegmented(
    options, selected, onSelect, modifier, enabled,
    style = HomeSegmentStyle.Soft, track = track, height = height, corner = if (height >= 56.dp) 24.dp else 16.dp,
    textStyle = if (height >= 56.dp) ToolsTabStyle else HomeType.button, icons = icons, inset = if (height >= 56.dp) 7.dp else 4.dp,
)

private val ToolsTabStyle = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)

/* ------------------------------------------------------------------ */
/*  Bottom sheet frame                                                  */
/* ------------------------------------------------------------------ */

/**
 * The inside of a bottom sheet: grab handle, title row, body, optional pinned footer. Unlike the
 * home frame the body does not scroll by itself, because the tool sheets bring their own
 * scrolling blocks (code, outline). Only the frame's labels are localized; bodies are verbatim.
 */
@Composable
internal fun ToolsSheetContent(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClose: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalHomeColors.current
    Column(modifier.fillMaxWidth().homeGlassPanel(HomeDims.sheetShape, c.raised, raised = true).windowInsetsPadding(WindowInsets.navigationBars).imePadding()) {
        Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(40.dp, 5.dp).background(if (c.dark) c.line2 else Color(0xFFC9CCDA), HomeDims.pillShape))
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).heightIn(min = HomeDims.touch), verticalArrangement = Arrangement.Center) {
                Text(ht(title), Modifier.semantics { heading() }, color = c.t1, style = HomeType.sheetTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(ht(subtitle), Modifier.padding(top = 3.dp), color = c.t2, style = HomeRowSubStyle)
            }
            when {
                trailing != null -> trailing()
                onClose != null -> HomeIconButton(HomeIcons.X, "关闭", onClose)
            }
        }
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = if (footer == null) 22.dp else 12.dp), content = body)
        if (footer != null) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = footer)
        }
    }
}
