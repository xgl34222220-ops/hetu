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

@Composable
internal fun ToolsScreen(vm: HetuViewModel, bottomPadding: Dp) {
    val context = LocalContext.current
    val nav = LocalNav.current
    val stagger = rememberHxStagger()
    fun open(type: Class<out android.app.Activity>) { context.startActivity(Intent(context, type)) }

    HxPage(
        title = "工具",
        scrollToTopSignal = vm.reselect,
        bottomPadding = bottomPadding,
        largeTitleStartPadding = 26.dp,
        largeTitleFontSizeSp = 36f,
        largeTitleBottomPadding = 18.dp,
        canvasColor = if (Hx.colors.dark) Hx.colors.canvas else Color(0xFFEBEDFA),
    ) {
        item(key = "file-run") {
            ToolReferenceCard(Modifier.hxEnter(stagger, 0)) {
                ToolReferenceRow(
                    "文件管理",
                    "查看与处理应用文件",
                    Icons.Rounded.Folder,
                ) { nav.push(HxRoute.Files) }
                ToolReferenceRow(
                    "脚本",
                    "管理服务脚本",
                    Icons.Rounded.Terminal,
                ) { open(ProxyScriptsActivity::class.java) }
            }
        }

        item(key = "logs") {
            ToolReferenceCard(Modifier.hxEnter(stagger, 1)) {
                ToolReferenceRow(
                    "日志查看",
                    "查看运行日志与调试输出",
                    Icons.Rounded.Article,
                ) { nav.push(HxRoute.Logs) }
            }
        }

        item(key = "apps") {
            ToolReferenceCard(Modifier.hxEnter(stagger, 2)) {
                ToolReferenceRow(
                    "应用管理",
                    "查看并管理应用相关规则",
                    Icons.Rounded.Apps,
                ) { nav.push(HxRoute.Apps) }
            }
        }

        item(key = "network") {
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

        item(key = "subscription") {
            ToolReferenceCard(Modifier.hxEnter(stagger, 4)) {
                ToolReferenceRow(
                    "配置与订阅",
                    "导入、切换配置与编辑订阅",
                    Icons.Rounded.CloudDownload,
                ) { nav.push(HxRoute.Configs) }
                ToolReferenceRow(
                    "Sub-Store",
                    "订阅处理、覆写与配置导入",
                    Icons.Rounded.CloudSync,
                ) { open(ProxySubStoreActivity::class.java) }
                ToolReferenceRow(
                    "CNIP 设置",
                    "配置 CNIP 数据源并更新地理数据",
                    Icons.Rounded.Place,
                ) { nav.push(HxRoute.CnIp) }
            }
        }

        item(key = "updates") {
            ToolReferenceCard(Modifier.hxEnter(stagger, 5)) {
                ToolReferenceRow(
                    "更新 WebUI",
                    "检查、安装与管理本地 WebUI 资源",
                    Icons.Rounded.Web,
                ) { open(ProxyWebPanelsActivity::class.java) }
                ToolReferenceRow(
                    "更新核心",
                    "下载、更新、导入与管理运行核心",
                    Icons.Rounded.Memory,
                ) { nav.push(HxRoute.Cores) }
                ToolReferenceRow(
                    "广告过滤",
                    "规则源、黑白名单与拦截统计",
                    Icons.Rounded.Shield,
                ) { nav.push(HxRoute.Adblock) }
            }
        }

        item(key = "maintenance") {
            ToolReferenceCard(Modifier.hxEnter(stagger, 6)) {
                ToolReferenceRow(
                    "诊断与维护",
                    "运行预检、网络诊断与紧急恢复",
                    Icons.Rounded.HealthAndSafety,
                ) { nav.push(HxRoute.Diagnostics) }
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
