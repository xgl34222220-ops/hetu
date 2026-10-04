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
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.HetuHaptic
import io.github.xgl34222220.hetu.ui.ht
import io.github.xgl34222220.hetu.ui.rememberHetuHaptics

/* ------------------------------------------------------------------ */
/*  面板: every live Mihomo view in one swipeable page                   */
/* ------------------------------------------------------------------ */

@Composable
internal fun PanelScreen(vm: HetuViewModel, bottomPadding: Dp) {
    HxTabbedPage(
        title = ht("面板"),
        tabs = HxPanelSections.map { (key, label) -> HxPageTab(key, ht(label)) },
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

@Composable
internal fun ToolsScreen(vm: HetuViewModel, bottomPadding: Dp) {
    val context = LocalContext.current
    val nav = LocalNav.current
    val stagger = rememberHxStagger()
    var searching by remember { mutableStateOf(false) }
    var toolQuery by remember { mutableStateOf("") }
    fun open(type: Class<out android.app.Activity>) { context.startActivity(Intent(context, type)) }

    HxPage(
        title = ht("工具"),
        scrollToTopSignal = vm.reselect,
        bottomPadding = bottomPadding,
        largeTitleStartPadding = 26.dp,
        largeTitleTopPadding = 26.dp,
        largeTitleFontSizeSp = 36f,
        largeTitleBottomPadding = 18.dp,
        canvasColor = Hx.colors.canvas,
        actions = {
            HxBarAction(
                if (searching) Icons.Rounded.Close else Icons.Rounded.Search,
                if (searching) ht("关闭搜索") else ht("搜索"),
                onClick = {
                    if (searching) toolQuery = ""
                    searching = !searching
                },
            )
        },
    ) {
        if (searching) {
            item(key = "tool-search") {
                Box(Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
                    HxSearchField(toolQuery, { toolQuery = it }, ht("搜索工具"), autoFocus = true)
                }
            }
            item(key = "tool-search-results") {
                val q = toolQuery.trim()
                val tools = listOf(
                    Triple("文件管理", "查看与处理应用文件", "文件 导入 下载 编辑"),
                    Triple("脚本", "运行与管理服务脚本", "服务 Hook shell"),
                    Triple("日志文件", "查看与导出运行日志", "日志 调试"),
                    Triple("应用管理", "代理与直连", "应用 网络 UID"),
                    Triple("共享网络", "热点与代理", "网络 热点 共享"),
                    Triple("网络匹配", "自动切换", "网络 Wi-Fi SSID BSSID"),
                    Triple("绕过规则", "网段与接口", "网络 CIDR 接口"),
                    Triple("配置管理", "导入与编辑配置文件", "配置 订阅 YAML"),
                    Triple("Sub-Store", "订阅处理", "订阅 后端"),
                    Triple("CNIP", "规则集管理", "中国 IP 直连"),
                    Triple("核心管理", "下载与更新", "核心 Mihomo Xray sing-box"),
                    Triple("广告过滤", "规则与屏蔽", "广告 DNS 白名单 黑名单"),
                    Triple("诊断工具", "网络与环境", "网络 诊断 维护 恢复"),
                    Triple("Web面板", "外部面板", "WebUI Zashboard"),
                ).filter { q.isBlank() || it.first.contains(q, true) || it.second.contains(q, true) || it.third.contains(q, true) || ht(it.first).contains(q, true) || ht(it.second).contains(q, true) }
                if (tools.isEmpty()) HxEmpty(Icons.Rounded.Search, ht("没有匹配的工具"), ht("试试工具名称或功能关键词"))
                else tools.groupBy { if (it.first == "诊断工具") "diagnostics" else "tools" }.values.forEach { group ->
                    ToolReferenceCard {
                        group.forEach { (title, subtitle, _) ->
                            val icon = when (title) {
                                "文件管理" -> Icons.Rounded.Folder
                                "脚本" -> Icons.Rounded.Terminal
                                "日志文件" -> Icons.Rounded.Article
                                "应用管理" -> Icons.Rounded.Apps
                                "共享网络" -> Icons.Rounded.WifiTethering
                                "网络匹配" -> Icons.Rounded.Wifi
                                "绕过规则" -> Icons.Rounded.AltRoute
                                "配置管理" -> Icons.Rounded.CloudDownload
                                "Sub-Store" -> Icons.Rounded.CloudSync
                                "CNIP" -> Icons.Rounded.Place
                                "核心管理" -> Icons.Rounded.Memory
                                "广告过滤" -> Icons.Rounded.Shield
                                "诊断工具" -> Icons.Rounded.HealthAndSafety
                                else -> Icons.Rounded.Web
                            }
                            ToolReferenceRow(title, subtitle, icon) {
                                when (title) {
                                    "文件管理" -> nav.push(HxRoute.Files)
                                    "脚本" -> open(ProxyScriptsActivity::class.java)
                                    "日志文件" -> nav.push(HxRoute.Logs)
                                    "应用管理" -> nav.push(HxRoute.Apps)
                                    "共享网络" -> nav.push(HxRoute.SharedNet)
                                    "网络匹配" -> nav.push(HxRoute.NetMatch)
                                    "绕过规则" -> nav.push(HxRoute.Bypass)
                                    "配置管理" -> nav.push(HxRoute.Configs)
                                    "Sub-Store" -> open(ProxySubStoreActivity::class.java)
                                    "CNIP" -> nav.push(HxRoute.CnIp)
                                    "核心管理" -> nav.push(HxRoute.Cores)
                                    "广告过滤" -> nav.push(HxRoute.Adblock)
                                    "诊断工具" -> nav.push(HxRoute.Diagnostics)
                                    else -> open(ProxyWebPanelsActivity::class.java)
                                }
                            }
                        }
                    }
                }
            }
        } else item(key = "file-run") {
            ToolReferenceCard(Modifier.hxEnter(stagger, 0)) {
                ToolReferenceRow(
                    "文件管理",
                    "查看与处理应用文件",
                    Icons.Rounded.Folder,
                ) { nav.push(HxRoute.Files) }
                ToolReferenceRow(
                    "脚本",
                    "运行与管理服务脚本",
                    Icons.Rounded.Terminal,
                ) { open(ProxyScriptsActivity::class.java) }
            }
        }

        if (!searching) item(key = "logs") {
            ToolReferenceCard(Modifier.hxEnter(stagger, 1)) {
                ToolReferenceRow(
                    "日志文件",
                    "查看与导出运行日志",
                    Icons.Rounded.Article,
                ) { nav.push(HxRoute.Logs) }
            }
        }

        if (!searching) item(key = "apps") {
            ToolReferenceCard(Modifier.hxEnter(stagger, 2)) {
                ToolReferenceRow(
                    "应用管理",
                    "管理应用代理与直连规则",
                    Icons.Rounded.Apps,
                ) { nav.push(HxRoute.Apps) }
            }
        }

        if (!searching) item(key = "network") {
            ToolReferenceCard(Modifier.hxEnter(stagger, 3)) {
                ToolReferenceRow(
                    "网络匹配",
                    "设置网络匹配后要执行的操作",
                    Icons.Rounded.Wifi,
                ) { nav.push(HxRoute.NetMatch) }
                ToolReferenceRow(
                    "共享网络",
                    "管理共享网络转发相关设置",
                    Icons.Rounded.WifiTethering,
                ) { nav.push(HxRoute.SharedNet) }
                ToolReferenceRow(
                    "绕过规则",
                    "管理本地 CIDR 与接口规则",
                    Icons.Rounded.AltRoute,
                ) { nav.push(HxRoute.Bypass) }
            }
        }

        if (!searching) item(key = "subscription") {
            ToolReferenceCard(Modifier.hxEnter(stagger, 4)) {
                ToolReferenceRow(
                    "配置管理",
                    "导入与编辑配置文件",
                    Icons.Rounded.CloudDownload,
                ) { nav.push(HxRoute.Configs) }
                ToolReferenceRow(
                    "Sub-Store",
                    "订阅处理",
                    Icons.Rounded.CloudSync,
                ) { open(ProxySubStoreActivity::class.java) }
                ToolReferenceRow(
                    "CNIP",
                    "规则集管理",
                    Icons.Rounded.Place,
                ) { nav.push(HxRoute.CnIp) }
            }
        }

        if (!searching) item(key = "updates") {
            ToolReferenceCard(Modifier.hxEnter(stagger, 5)) {
                ToolReferenceRow(
          "核心管理",
          "下载与更新",
          Icons.Rounded.Memory,
                ) { nav.push(HxRoute.Cores) }
                ToolReferenceRow(
          "广告过滤",
          "规则与屏蔽",
          Icons.Rounded.Shield,
                ) { nav.push(HxRoute.Adblock) }
                ToolReferenceRow(
          "诊断工具",
          "网络与环境",
          Icons.Rounded.HealthAndSafety,
                ) { nav.push(HxRoute.Diagnostics) }
                ToolReferenceRow(
          "Web面板",
          "外部面板",
          Icons.Rounded.Web,
                ) { open(ProxyWebPanelsActivity::class.java) }
            }
        }
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
            .heightIn(min = 72.dp)
            .hxPressScale(source, .985f)
            .clickable(
                interactionSource = source,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 15.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            Icon(
                toolReferenceLineIcon(title, icon),
                contentDescription = null,
                tint = c.text.copy(alpha = if (c.dark) .92f else .88f),
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(
                ht(title),
                color = c.text,
                fontSize = 18.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                ht(subtitle),
                color = c.textMuted,
                fontSize = 15.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Medium,
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
