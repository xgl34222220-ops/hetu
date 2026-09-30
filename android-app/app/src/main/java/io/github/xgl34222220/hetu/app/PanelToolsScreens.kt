package io.github.xgl34222220.hetu

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Web
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.HetuHaptic
import io.github.xgl34222220.hetu.ui.rememberHetuHaptics

/* ------------------------------------------------------------------ */
/*  面板: every live Mihomo view in one swipeable page                   */
/* ------------------------------------------------------------------ */

@Composable
internal fun PanelScreen(vm: HetuViewModel, bottomPadding: Dp) {
    HxTabbedPage(
        title = "面板",
        tabs = HxPanelSections.map { (key, label) -> HxPageTab(key, label) },
        selected = vm.panelSection,
        onSelect = vm::openPanel,
        showSubtitle = false,
        minimalHeader = true,
        panelReferenceStyle = true,
        bottomPadding = bottomPadding,
        scrollToTopSignal = vm.reselect,
        pageComposable = { key ->
            when (key) {
                "overview" -> PanelOverviewScreen(vm, bottomPadding)
                "proxies" -> ProxiesScreen(vm, bottomPadding)
                "conn", "logs" -> ConnectionsScreen(vm, bottomPadding, forcedSection = key)
                "providers", "rules", "sets" -> RulesScreen(vm, bottomPadding, forcedSection = key)
                else -> PanelOverviewScreen(vm, bottomPadding)
            }
        },
    )
}

/* ------------------------------------------------------------------ */
/*  工具: every management tool, one tap away                           */
/* ------------------------------------------------------------------ */

private data class ConceptTool(val title: String, val summary: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val open: () -> Unit)
private data class ConceptToolSection(val title: String, val summary: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val tools: List<ConceptTool>)

@Composable
internal fun ToolsScreen(vm: HetuViewModel, bottomPadding: Dp) {
    val context = LocalContext.current
    val nav = LocalNav.current
    val stagger = rememberHxStagger()
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    fun open(type: Class<out android.app.Activity>) { context.startActivity(Intent(context, type)) }
    val sections = listOf(
        ConceptToolSection("文件与脚本", "管理应用文件、脚本与日志", Icons.Rounded.Folder, listOf(
            ConceptTool("文件管理", "浏览与编辑", Icons.Rounded.Folder) { nav.push(HxRoute.Files) },
            ConceptTool("脚本", "运行与管理", Icons.Rounded.Terminal) { open(ProxyScriptsActivity::class.java) },
            ConceptTool("日志文件", "查看与导出", Icons.Rounded.Article) { nav.push(HxRoute.Logs) },
        )),
        ConceptToolSection("网络与应用", "管理应用、共享网络与网络策略", Icons.Rounded.Wifi, listOf(
            ConceptTool("应用管理", "代理与直连", Icons.Rounded.Apps) { nav.push(HxRoute.Apps) },
            ConceptTool("共享网络", "热点与代理", Icons.Rounded.WifiTethering) { nav.push(HxRoute.SharedNet) },
            ConceptTool("网络匹配", "自动切换", Icons.Rounded.Wifi) { nav.push(HxRoute.NetMatch) },
            ConceptTool("绕过规则", "网段与接口", Icons.Rounded.AltRoute) { nav.push(HxRoute.Bypass) },
        )),
        ConceptToolSection("配置与订阅", "配置文件与订阅来源", Icons.Rounded.CloudDownload, listOf(
            ConceptTool("配置管理", "导入与编辑", Icons.Rounded.Description) { nav.push(HxRoute.Configs) },
            ConceptTool("Sub-Store", "订阅处理", Icons.Rounded.CloudSync) { open(ProxySubStoreActivity::class.java) },
            ConceptTool("CNIP", "规则集管理", Icons.Rounded.Place) { nav.push(HxRoute.CnIp) },
        )),
        ConceptToolSection("其他工具", "核心、过滤与运行维护", Icons.Rounded.Memory, listOf(
            ConceptTool("核心管理", "下载与更新", Icons.Rounded.Memory) { nav.push(HxRoute.Cores) },
            ConceptTool("广告过滤", "规则与屏蔽", Icons.Rounded.Shield) { nav.push(HxRoute.Adblock) },
            ConceptTool("诊断工具", "网络与环境", Icons.Rounded.HealthAndSafety) { nav.push(HxRoute.Diagnostics) },
            ConceptTool("WebUI", "外部面板", Icons.Rounded.Web) { open(ProxyWebPanelsActivity::class.java) },
        )),
    )
    val visible = sections.map { section -> section.copy(tools = section.tools.filter { query.isBlank() || it.title.contains(query, true) || it.summary.contains(query, true) || section.title.contains(query, true) }) }.filter { it.tools.isNotEmpty() }
    HxPage(title = "工具", scrollToTopSignal = vm.reselect, bottomPadding = bottomPadding,
        actions = { HxBarAction(if (searching) Icons.Rounded.SearchOff else Icons.Rounded.Search, "搜索工具", { searching = !searching; if (!searching) query = "" }) },
    ) {
        if (searching) item("tool-search") { HxSearchField(query, { query = it }, "搜索工具", Modifier.padding(horizontal = Hx.gutter).padding(bottom = 12.dp), autoFocus = true) }
        visible.forEachIndexed { index, section ->
            item(key = section.title) {
                HxSection {
                    HxCard(modifier = Modifier.hxEnter(stagger, index).testTag("tools-${section.title}")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HxIconBadge(section.icon)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(section.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Hx.colors.text)
                                Text(section.summary, style = MaterialTheme.typography.bodySmall, color = Hx.colors.textMuted)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        val columns = if (LocalDensity.current.fontScale > 1.25f) 2 else section.tools.size.coerceAtMost(4)
                        section.tools.chunked(columns).forEach { row ->
                            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { tool -> ConceptToolTile(tool, Modifier.weight(1f)) }
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
        if (visible.isEmpty()) item("no-tools") { HxEmpty(Icons.Rounded.SearchOff, "没有匹配的工具") }
    }
}

@Composable
private fun ConceptToolTile(tool: ConceptTool, modifier: Modifier) {
    val c = Hx.colors
    val source = remember { MutableInteractionSource() }
    Column(modifier.heightIn(min = 94.dp).testTag("tool-${tool.title}").hxPressScale(source).clip(Hx.rowShape)
        .background(c.surfaceMuted.copy(alpha = .85f)).clickable(interactionSource = source, indication = LocalIndication.current, onClick = tool.open)
        .padding(horizontal = 9.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(tool.icon, null, tint = c.accent, modifier = Modifier.size(26.dp))
        Text(tool.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = c.text)
        Text(tool.summary, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
    }
}

/**
 * Tool-page card modeled after the reference UI: cards themselves define groups,
 * without extra section headings or divider lines competing with the content.
 */
@Composable
private fun ToolReferenceCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val c = Hx.colors
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (c.dark) c.surface.copy(alpha = .88f) else Color(0xFFF9F8FE),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            Modifier.padding(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            content = { content() },
        )
    }
}

@Composable
private fun ToolReferenceRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    val c = Hx.colors
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .hxPressScale(source, .985f)
            .clickable(
                interactionSource = source,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 13.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = null,
                tint = c.text.copy(alpha = if (c.dark) .92f else .88f),
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = c.text,
                fontSize = 18.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                subtitle,
                color = c.textMuted,
                fontSize = 13.5.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = c.textMuted.copy(alpha = .82f),
            modifier = Modifier.size(20.dp),
        )
    }
}

internal class HxEditEntry(initial: String) {
    val id: Long = nextId++
    var text by mutableStateOf(initial)

    private companion object {
        var nextId = 0L
    }
}

internal fun hxLooksLikeCidr(value: String): Boolean {
    val parts = value.split('/')
    if (parts.size != 2) return false
    val bits = parts[1].toIntOrNull() ?: return false
    val ip = parts[0]
    return if (ip.contains(':')) bits in 0..128 && ip.all { it.isLetterOrDigit() || it == ':' }
    else bits in 0..32 && ip.split('.').let { octets -> octets.size == 4 && octets.all { o -> o.toIntOrNull()?.let { it in 0..255 } == true } }
}
