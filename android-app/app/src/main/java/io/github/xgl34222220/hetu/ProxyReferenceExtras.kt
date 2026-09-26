package io.github.xgl34222220.hetu

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import io.github.rosemoe.sora.widget.schemes.SchemeGitHub
import io.github.xgl34222220.hetu.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val EXTRA_PANEL_NAME = "panel_name"
private const val EXTRA_PANEL_URL = "panel_url"
internal const val EXTRA_RUNTIME_PATH = "runtime_path"

internal data class HetuWebPanel(val id: String, val name: String, val url: String)

internal object HetuWebPanels {
    fun validUrl(value: String): Boolean = runCatching {
        val uri = java.net.URI(value)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && value.none { it.code < 32 }
    }.getOrDefault(false)
    private const val KEY = "proxyWebPanelsJson"
    private const val SELECTED = "proxyWebPanelSelected"

    fun list(context: Context): List<HetuWebPanel> {
        val prefs = context.getSharedPreferences("hetu", Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, "[]").orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        val out = ArrayList<HetuWebPanel>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            val name = item.optString("name").trim()
            val url = item.optString("url").trim()
            if (id.isNotBlank() && name.isNotBlank() && validUrl(url)) {
                out += HetuWebPanel(id, name, url)
            }
        }
        return out
    }

    fun save(context: Context, panels: List<HetuWebPanel>) {
        val array = JSONArray()
        panels.forEach { panel ->
            array.put(JSONObject().put("id", panel.id).put("name", panel.name).put("url", panel.url))
        }
        context.getSharedPreferences("hetu", Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    fun selected(context: Context): String =
        context.getSharedPreferences("hetu", Context.MODE_PRIVATE).getString(SELECTED, "local").orEmpty().ifBlank { "local" }

    fun select(context: Context, id: String) {
        context.getSharedPreferences("hetu", Context.MODE_PRIVATE).edit().putString(SELECTED, id).apply()
    }

    fun openSelected(context: Context) {
        val id = selected(context)
        val panel = list(context).firstOrNull { it.id == id }
        if (panel == null || id == "local") {
            context.startActivity(Intent(context, ProxyLocalWebUiActivity::class.java))
        } else {
            context.startActivity(
                Intent(context, ProxyWebPanelViewerActivity::class.java)
                    .putExtra(EXTRA_PANEL_NAME, panel.name)
                    .putExtra(EXTRA_PANEL_URL, panel.url),
            )
        }
    }
}

@Composable
private fun ExtraPage(
    title: String,
    subtitle: String = "",
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalHetuTokens.current
    Column(Modifier.fillMaxSize().crystalPageBackground()) {
        Column(Modifier.statusBarsPadding()) { HetuPageHeader(title, onBack, subtitle) }
        Column(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun ExtraCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().crystalMaterial(RoundedCornerShape(22.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun ExtraRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    value: String = "",
    onClick: (() -> Unit)? = null,
) {
    val t = LocalHetuTokens.current
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .10f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(ht(title), color = t.textPrimary, fontSize = 14.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(ht(subtitle), color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        }
        if (value.isNotBlank()) {
            Text(value, color = t.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(5.dp))
        }
        if (onClick != null) Icon(Icons.Rounded.ChevronRight, null, tint = t.textMuted, modifier = Modifier.size(18.dp))
    }
}

class ProxyWebPanelsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HetuTheme { ProxyWebPanelsScreen { finish() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyWebPanelsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", Context.MODE_PRIVATE) }
    val t = LocalHetuTokens.current
    var revision by remember { mutableIntStateOf(0) }
    val panels = remember(revision) { HetuWebPanels.list(context) }
    val selected = remember(revision) { HetuWebPanels.selected(context) }
    var adding by remember { mutableStateOf(false) }
    var panelName by remember { mutableStateOf("") }
    var panelUrl by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var localMode by remember { mutableStateOf(prefs.getString("proxyWebPanelLocalMode", "auto").orEmpty().ifBlank { "auto" }) }
    var modePicker by remember { mutableStateOf(false) }
    var editingPanel by remember { mutableStateOf<HetuWebPanel?>(null) }
    var deletingPanel by remember { mutableStateOf<HetuWebPanel?>(null) }
    var feedback by remember { mutableStateOf("") }
    var panelUpdating by remember { mutableStateOf(false) }
    val panelScope = rememberCoroutineScope()

    ExtraPage("Web 面板", "本地控制台与自定义 HTTPS 面板", onBack) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ExtraCard {
                    ExtraRow(
                        Icons.Rounded.Dashboard,
                        "河图本地面板",
                        "直接连接 127.0.0.1 Mihomo 控制器，不向局域网暴露接口",
                        if (selected == "local") "默认" else "",
                    ) {
                        HetuWebPanels.select(context, "local")
                        revision++
                        context.startActivity(Intent(context, ProxyLocalWebUiActivity::class.java))
                    }
                    HorizontalDivider(color = t.outline)
                    ExtraRow(Icons.Rounded.Tune, "本地面板模式", "自动 / 河图内置 / 本地 Zashboard", localMode) { modePicker = true }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("自定义面板", color = t.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { editingPanel = null; adding = true; error = ""; panelName = ""; panelUrl = "" }) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("添加")
                    }
                }
            }

            if (panels.isEmpty()) {
                item {
                    Text(
                        "还没有自定义面板。只接受 HTTPS 地址；自定义面板是否兼容当前控制器由面板自身决定。",
                        color = t.textSecondary,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            items(panels, key = { it.id }) { panel ->
                ExtraCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(panel.name, color = t.textPrimary, fontWeight = FontWeight.Bold)
                            Text(panel.url, color = t.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (selected == panel.id) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = .12f)) {
                                Text("默认", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                HetuWebPanels.select(context, panel.id)
                                revision++
                                context.startActivity(
                                    Intent(context, ProxyWebPanelViewerActivity::class.java)
                                        .putExtra(EXTRA_PANEL_NAME, panel.name)
                                        .putExtra(EXTRA_PANEL_URL, panel.url),
                                )
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                        ) { Text("打开") }
                        IconButton(onClick = { editingPanel = panel; panelName = panel.name; panelUrl = panel.url; adding = true; error = "" }) { Icon(Icons.Rounded.Edit, "编辑") }
                        OutlinedButton(
                            onClick = {
                                deletingPanel = panel
                            },
                            shape = RoundedCornerShape(16.dp),
                        ) { Icon(Icons.Rounded.DeleteOutline, "删除") }
                    }
                }
            }

            item {
                ExtraCard {
                    ExtraRow(Icons.Rounded.CloudDownload, if (panelUpdating) "正在更新 Zashboard…" else "安装 / 更新本地 Zashboard", "从官方 GitHub 获取，资源保存在本机；失败时保留原版本") {
                        if (!panelUpdating) {
                            panelUpdating = true
                            panelScope.launch {
                                try { WebPanelAssets.update(context); feedback = "Zashboard 已更新，重新打开本地面板即可使用" }
                                catch (cancel: CancellationException) { throw cancel }
                                catch (failure: Exception) { feedback = failure.message ?: "面板更新失败" }
                                finally { panelUpdating = false }
                            }
                        }
                    }
                }
            }
            if (feedback.isNotBlank()) item { HetuTaskFeedback(feedback) }
            item {
                ExtraCard {
                    ExtraRow(Icons.Rounded.CleaningServices, "清除 Web 缓存", "清理 WebView 缓存、Cookie 与本地 WebStorage") {
                        runCatching {
                            WebView(context).apply {
                                clearCache(true)
                                clearHistory()
                                destroy()
                            }
                            CookieManager.getInstance().removeAllCookies(null)
                            CookieManager.getInstance().flush()
                            WebStorage.getInstance().deleteAllData()
                        }.onSuccess { feedback = "Web 缓存已清除" }.onFailure { feedback = it.message ?: "清理失败" }
                    }
                }
            }
        }
    }

    if (deletingPanel != null) ModalBottomSheet(onDismissRequest = { deletingPanel = null }, containerColor = Color.Transparent) {
        val panel = deletingPanel!!
        Column(Modifier.fillMaxWidth().liquidSheetMaterial().navigationBarsPadding().padding(20.dp)) {
            Text("删除面板 ${panel.name}？", style = MaterialTheme.typography.titleLarge)
            Text("仅移除面板入口，远端服务不受影响。")
            Button(onClick = {
                HetuWebPanels.save(context, panels.filterNot { it.id == panel.id })
                if (selected == panel.id) HetuWebPanels.select(context, "local")
                deletingPanel = null; revision++
            }) { Text("删除") }
        }
    }
    if (adding) {
        ModalBottomSheet(
            onDismissRequest = { adding = false },
            containerColor = Color.Transparent,
            shape = RoundedCornerShape(topStart = HetuGlassRadius.Sheet, topEnd = HetuGlassRadius.Sheet),
        ) {
            Column(
                Modifier.fillMaxWidth().liquidSheetMaterial().navigationBarsPadding().imePadding().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(if (editingPanel == null) "添加 Web 面板" else "编辑 Web 面板", color = t.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                LiquidGlassTextField(panelName, { panelName = it.take(40); error = "" }, "名称", Modifier.fillMaxWidth())
                LiquidGlassTextField(panelUrl, { panelUrl = it.take(500); error = "" }, "HTTPS 地址", Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                Button(
                    onClick = {
                        val name = panelName.trim()
                        val url = panelUrl.trim()
                        when {
                            name.isBlank() -> error = "请输入面板名称"
                            !HetuWebPanels.validUrl(url) -> error = "请输入有效的 HTTPS 面板地址"
                            else -> {
                                val id = editingPanel?.id ?: ("custom-" + System.currentTimeMillis().toString(16))
                                HetuWebPanels.save(context, panels.filterNot { it.id == id } + HetuWebPanel(id, name, url))
                                revision++
                                adding = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text("保存") }
            }
        }
    }

    if (modePicker) {
        ModalBottomSheet(
            onDismissRequest = { modePicker = false },
            containerColor = Color.Transparent,
            shape = RoundedCornerShape(topStart = HetuGlassRadius.Sheet, topEnd = HetuGlassRadius.Sheet),
        ) {
            Column(Modifier.fillMaxWidth().liquidSheetMaterial().navigationBarsPadding().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("本地面板模式", color = t.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                listOf("auto" to "自动：优先本地 Zashboard", "builtin" to "河图内置面板", "zashboard" to "本地 Zashboard").forEach { item ->
                    ExtraRow(Icons.Rounded.Web, item.second, "自动模式在未安装 Zashboard 时打开内置面板", if (localMode == item.first) "已选" else "") {
                        localMode = item.first
                        prefs.edit().putString("proxyWebPanelLocalMode", item.first).apply()
                        modePicker = false
                    }
                }
            }
        }
    }
}

class ProxyWebPanelViewerActivity : ComponentActivity() {
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra(EXTRA_PANEL_NAME).orEmpty().ifBlank { "Web 面板" }
        val url = intent.getStringExtra(EXTRA_PANEL_URL).orEmpty()
        if (!HetuWebPanels.validUrl(url)) {
            finish()
            return
        }
        title = name
        val view = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            loadUrl(url)
        }
        webView = view
        setContentView(view)
    }

    override fun onBackPressed() {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        webView = null
        super.onDestroy()
    }
}

class ProxySelectorPreferencesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HetuTheme { ProxySelectorPreferencesScreen { finish() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxySelectorPreferencesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", Context.MODE_PRIVATE) }
    val t = LocalHetuTokens.current
    var groupColumns by remember { mutableIntStateOf(prefs.getInt("proxySelectorGroupColumns", 0)) }
    var nodeColumns by remember { mutableIntStateOf(prefs.getInt("proxySelectorNodeColumns", 0)) }
    var density by remember { mutableStateOf(prefs.getString("proxySelectorDensity", "standard").orEmpty()) }
    var overflow by remember { mutableStateOf(prefs.getString("proxySelectorNameOverflow", "wrap").orEmpty().let { if (it == "clip") "wrap" else it }) }
    var sort by remember { mutableStateOf(prefs.getString("proxySelectorNodeSort", "config").orEmpty()) }
    var desc by remember { mutableStateOf(prefs.getBoolean("proxySelectorSortDescending", false)) }
    var groupByProvider by remember { mutableStateOf(prefs.getBoolean("proxySelectorGroupByProvider", false)) }
    var collapsePrevious by remember { mutableStateOf(prefs.getBoolean("proxySelectorCollapsePrevious", true)) }
    var expandSelectedInSheet by remember { mutableStateOf(prefs.getBoolean("proxySelectorExpandSelectedInSheet", true)) }
    var disconnect by remember { mutableStateOf(prefs.getBoolean("proxySelectorDisconnectOnSelect", false)) }
    var hidden by remember { mutableStateOf(prefs.getBoolean("proxySelectorShowHidden", false)) }
    var detectIpv6 by remember { mutableStateOf(prefs.getBoolean("proxySelectorDetectIpv6", true)) }
    var globalMode by remember { mutableStateOf(prefs.getBoolean("proxySelectorShowGlobalByMode", true)) }
    var picker by remember { mutableStateOf<String?>(null) }

    fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
    fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    fun putBool(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }

    ExtraPage("策略显示", "节点、策略组与切换行为", onBack) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ExtraCard {
                    ExtraRow(Icons.Rounded.ViewColumn, "策略组列数", "自动 / 单列 / 双列", when (groupColumns) { 1 -> "单列"; 2 -> "双列"; else -> "自动" }) { picker = "groupColumns" }
                    HorizontalDivider(color = t.outline)
                    ExtraRow(Icons.Rounded.GridView, "节点列数", "展开策略后的节点网格", when (nodeColumns) { 1 -> "单列"; 2 -> "双列"; 3 -> "三列"; else -> "自动" }) { picker = "nodeColumns" }
                    HorizontalDivider(color = t.outline)
                    ExtraRow(Icons.Rounded.DensityMedium, "显示密度", "标准或紧凑卡片", if (density == "compact") "紧凑" else "标准") { picker = "density" }
                    HorizontalDivider(color = t.outline)
                    ExtraRow(Icons.Rounded.TextFields, "名称溢出", "完整换行 / 滚动", when (overflow) { "scroll" -> "滚动"; "wrap" -> "换行"; else -> "换行" }) { picker = "overflow" }
                }
            }
            item {
                ExtraCard {
                    ExtraRow(Icons.Rounded.Sort, "节点排序", "配置顺序 / 名称 / 延迟", when (sort) { "name" -> "名称"; "latency" -> "延迟"; else -> "配置顺序" }) { picker = "sort" }
                    HorizontalDivider(color = t.outline)
                    ExtraSwitchRow("倒序", "对当前节点排序结果反向显示", desc) { desc = it; putBool("proxySelectorSortDescending", it) }
                    ExtraSwitchRow("按 Provider 分组", "有 Provider 信息时按来源组织节点", groupByProvider) { groupByProvider = it; putBool("proxySelectorGroupByProvider", it) }
                    ExtraSwitchRow("折叠上一个策略", "打开新策略组时收起之前的策略", collapsePrevious) { collapsePrevious = it; putBool("proxySelectorCollapsePrevious", it) }
                    ExtraSwitchRow("底部弹窗展开策略", "点击策略组后从底部弹出节点列表", expandSelectedInSheet) { expandSelectedInSheet = it; putBool("proxySelectorExpandSelectedInSheet", it) }
                }
            }
            item {
                ExtraCard {
                    ExtraSwitchRow("切换节点后断开旧连接", "仅断开经过当前策略组的旧连接，保留直连和其他策略连接", disconnect) { disconnect = it; putBool("proxySelectorDisconnectOnSelect", it) }
                    ExtraSwitchRow("显示隐藏策略", "显示配置中标记为隐藏的策略组", hidden) { hidden = it; putBool("proxySelectorShowHidden", it) }
                    ExtraSwitchRow("测速时检测 IPv6", "支持时同时识别 IPv6 连通性", detectIpv6) { detectIpv6 = it; putBool("proxySelectorDetectIpv6", it) }
                    ExtraSwitchRow("按模式显示 GLOBAL", "仅在适合的模式显示 GLOBAL 策略", globalMode) { globalMode = it; putBool("proxySelectorShowGlobalByMode", it) }
                }
            }
            item {
                ExtraCard {
                    ExtraRow(Icons.Rounded.Image, "策略图标", "本地图片、HTTPS 图片与策略组覆盖") {
                        context.startActivity(Intent(context, ProxyPolicyIconsActivity::class.java))
                    }
                }
            }
        }
    }

    picker?.let { type ->
        val options: List<Pair<String, String>> = when (type) {
            "groupColumns" -> listOf("0" to "自动", "1" to "单列", "2" to "双列")
            "nodeColumns" -> listOf("0" to "自动", "1" to "单列", "2" to "双列", "3" to "三列")
            "density" -> listOf("standard" to "标准", "compact" to "紧凑")
            "overflow" -> listOf("wrap" to "完整换行", "scroll" to "滚动")
            else -> listOf("config" to "配置顺序", "name" to "名称", "latency" to "延迟")
        }
        ModalBottomSheet(
            onDismissRequest = { picker = null },
            containerColor = Color.Transparent,
            shape = RoundedCornerShape(topStart = HetuGlassRadius.Sheet, topEnd = HetuGlassRadius.Sheet),
        ) {
            Column(Modifier.fillMaxWidth().liquidSheetMaterial().navigationBarsPadding().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { option ->
                    ExtraRow(Icons.Rounded.CheckCircle, option.second, "") {
                        when (type) {
                            "groupColumns" -> { groupColumns = option.first.toInt(); putInt("proxySelectorGroupColumns", groupColumns) }
                            "nodeColumns" -> { nodeColumns = option.first.toInt(); putInt("proxySelectorNodeColumns", nodeColumns) }
                            "density" -> { density = option.first; putString("proxySelectorDensity", density) }
                            "overflow" -> { overflow = option.first; putString("proxySelectorNameOverflow", overflow) }
                            else -> { sort = option.first; putString("proxySelectorNodeSort", sort) }
                        }
                        picker = null
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtraSwitchRow(title: String, subtitle: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    val t = LocalHetuTokens.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(ht(title), color = t.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(ht(subtitle), color = t.textSecondary, fontSize = 11.5.sp, lineHeight = 16.sp)
        }
        Switch(checked = checked, onCheckedChange = onChanged)
    }
}

class ProxyStartupConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HetuTheme { ProxyStartupConfigScreen { finish() } } }
    }
}

@Composable
private fun ProxyStartupConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val t = LocalHetuTokens.current
    val scope = rememberCoroutineScope()
    val manager = remember { RootProxyManager(context) }
    val prefs = remember { context.getSharedPreferences("hetu", Context.MODE_PRIVATE) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/yaml")) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } }
        }
    }

    fun load() {
        if (busy && text.isNotBlank()) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { manager.startupConfig() } }
            result.onSuccess { text = it; message = "" }
            result.onFailure { message = it.message ?: "尚未生成启动配置" }
            busy = false
        }
    }

    LaunchedEffect(Unit) { load() }

    ExtraPage("启动配置", "河图生成的最终 Mihomo 运行副本", onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                manager.prepare(ProxyRuntimeProfile.load(prefs))
                                manager.startupConfig()
                            }
                        }
                        result.onSuccess { text = it; message = "启动配置已重新生成（未重启代理）" }
                        result.onFailure { message = it.message ?: "生成失败" }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
            ) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(5.dp)); Text("重新生成") }
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Hetu startup config", text))
                    message = "已复制"
                },
                enabled = text.isNotBlank(),
                shape = RoundedCornerShape(16.dp),
            ) { Icon(Icons.Rounded.ContentCopy, "复制") }
            OutlinedButton(
                onClick = { exporter.launch("hetu-startup-config.yaml") },
                enabled = text.isNotBlank(),
                shape = RoundedCornerShape(16.dp),
            ) { Icon(Icons.Rounded.SaveAlt, "导出") }
        }
        if (message.isNotBlank()) Text(message, color = t.textSecondary, fontSize = 12.sp)
        Box(
            Modifier.fillMaxWidth().weight(1f).crystalMaterial(RoundedCornerShape(18.dp), depth = CrystalDepth.InsetItem).padding(12.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else Text(
                text.ifBlank { "暂无启动配置" },
                color = t.textPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
                modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState()),
            )
        }
    }
}

class ProxyNotificationSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HetuTheme { ProxyNotificationSettingsScreen { finish() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyNotificationSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", Context.MODE_PRIVATE) }
    val t = LocalHetuTokens.current
    var enabled by remember { mutableStateOf(prefs.getBoolean(ProxyStatusNotificationService.PREF_ENABLED, false)) }
    var template by remember { mutableStateOf(prefs.getString(ProxyStatusNotificationService.PREF_TEMPLATE, ProxyStatusNotificationService.DEFAULT_TEMPLATE).orEmpty()) }
    var refresh by remember { mutableIntStateOf(prefs.getInt(ProxyStatusNotificationService.PREF_REFRESH_SECONDS, 3).coerceIn(2, 60)) }
    var clickTarget by remember { mutableStateOf(prefs.getString(ProxyStatusNotificationService.PREF_CLICK_TARGET, "Home").orEmpty()) }
    val actions = remember {
        mutableStateListOf(
            prefs.getString(ProxyStatusNotificationService.PREF_ACTION_1, "reload").orEmpty(),
            prefs.getString(ProxyStatusNotificationService.PREF_ACTION_2, "restart").orEmpty(),
            prefs.getString(ProxyStatusNotificationService.PREF_ACTION_3, "stop").orEmpty(),
        )
    }
    val labels = remember {
        mutableStateListOf(
            prefs.getString(ProxyStatusNotificationService.PREF_ACTION_LABEL_1, "").orEmpty(),
            prefs.getString(ProxyStatusNotificationService.PREF_ACTION_LABEL_2, "").orEmpty(),
            prefs.getString(ProxyStatusNotificationService.PREF_ACTION_LABEL_3, "").orEmpty(),
        )
    }
    var picker by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var message by remember { mutableStateOf("") }

    ExtraPage("通知设置", "状态模板、刷新频率、点击目标与快捷按钮", onBack) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ExtraCard {
                    ExtraSwitchRow("状态通知", "常驻显示运行状态、流量与快捷控制", enabled) {
                        enabled = it
                        ProxyStatusNotificationService.setEnabled(context, it)
                    }
                    HorizontalDivider(color = t.outline)
                    OutlinedTextField(
                        value = template,
                        onValueChange = { template = it.take(320) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("通知正文模板") },
                        minLines = 3,
                    )
                    Text(
                        "变量：{status} {uptime} {upload} {download} {cpu} {memory} {connections} {config} {core} {mode}",
                        color = t.textSecondary,
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp,
                    )
                    ExtraRow(Icons.Rounded.Refresh, "刷新频率", "通知状态读取间隔", refresh.toString() + " 秒") { picker = "refresh" to -1 }
                    HorizontalDivider(color = t.outline)
                    ExtraRow(
                        Icons.Rounded.TouchApp,
                        "点击通知打开",
                        "主界面页面或独立半透明浮窗",
                        when (clickTarget) {
                            "PanelSheet" -> "面板浮窗"
                            "StrategySheet" -> "策略浮窗"
                            "Home" -> "首页"
                            "Panel" -> "面板"
                            "Strategy" -> "策略"
                            "Tools" -> "工具"
                            "Settings" -> "设置"
                            else -> clickTarget
                        },
                    ) { picker = "target" to -1 }
                }
            }
            items(3) { index ->
                ExtraCard {
                    Text("快捷按钮 " + (index + 1), color = t.textPrimary, fontWeight = FontWeight.Bold)
                    ExtraRow(Icons.Rounded.Bolt, "动作", "重载 / 重启 / 停止 / 隐藏 / 无", actions[index]) { picker = "action" to index }
                    OutlinedTextField(
                        value = labels[index],
                        onValueChange = { labels[index] = it.take(12) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("自定义按钮文字（可空）") },
                        singleLine = true,
                    )
                }
            }
            item {
                Button(
                    onClick = {
                        prefs.edit()
                            .putString(ProxyStatusNotificationService.PREF_TEMPLATE, template.ifBlank { ProxyStatusNotificationService.DEFAULT_TEMPLATE })
                            .putInt(ProxyStatusNotificationService.PREF_REFRESH_SECONDS, refresh)
                            .putString(ProxyStatusNotificationService.PREF_CLICK_TARGET, clickTarget)
                            .putString(ProxyStatusNotificationService.PREF_ACTION_1, actions[0])
                            .putString(ProxyStatusNotificationService.PREF_ACTION_2, actions[1])
                            .putString(ProxyStatusNotificationService.PREF_ACTION_3, actions[2])
                            .putString(ProxyStatusNotificationService.PREF_ACTION_LABEL_1, labels[0])
                            .putString(ProxyStatusNotificationService.PREF_ACTION_LABEL_2, labels[1])
                            .putString(ProxyStatusNotificationService.PREF_ACTION_LABEL_3, labels[2])
                            .apply()
                        ProxyStatusNotificationService.refresh(context)
                        message = "已保存"
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    shape = RoundedCornerShape(18.dp),
                ) { Text("保存通知设置") }
                if (message.isNotBlank()) Text(message, color = t.success, fontSize = 12.sp)
            }
        }
    }

    picker?.let { current ->
        val options: List<Pair<String, String>> = when (current.first) {
            "refresh" -> listOf("2" to "2 秒", "3" to "3 秒", "5" to "5 秒", "10" to "10 秒", "30" to "30 秒", "60" to "60 秒")
            "target" -> listOf("Home" to "首页", "Panel" to "面板", "Strategy" to "策略", "PanelSheet" to "面板浮窗", "StrategySheet" to "策略浮窗", "Tools" to "工具", "Settings" to "设置")
            else -> listOf("reload" to "重载", "restart" to "重启", "stop" to "停止", "hide" to "隐藏通知", "none" to "无")
        }
        ModalBottomSheet(
            onDismissRequest = { picker = null },
            containerColor = Color.Transparent,
            shape = RoundedCornerShape(topStart = HetuGlassRadius.Sheet, topEnd = HetuGlassRadius.Sheet),
        ) {
            Column(Modifier.fillMaxWidth().liquidSheetMaterial().navigationBarsPadding().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { option ->
                    ExtraRow(Icons.Rounded.Check, option.second, "") {
                        when (current.first) {
                            "refresh" -> refresh = option.first.toInt()
                            "target" -> clickTarget = option.first
                            else -> actions[current.second] = option.first
                        }
                        picker = null
                    }
                }
            }
        }
    }
}

class ProxyCoreImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HetuTheme { ProxyCoreImportScreen(intent.data) { finish() } } }
    }
}

@Composable
private fun ProxyCoreImportScreen(uri: Uri?, onBack: () -> Unit) {
    val context = LocalContext.current
    val t = LocalHetuTokens.current
    val scope = rememberCoroutineScope()
    val manager = remember { ProxyCoreDownloadManager(context) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(if (uri == null) "没有收到可导入的核心文件" else "选择这个文件属于哪个核心") }

    ExtraPage("导入核心", "从外部文件管理器导入 ELF 核心", onBack) {
        Text(message, color = t.textSecondary, fontSize = 12.5.sp, lineHeight = 18.sp)
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ProxyRuntimeProfile.Core.values().toList(), key = { it.id }) { core ->
                ExtraCard {
                    ExtraRow(Icons.Rounded.Memory, core.label, "河图会验证 ELF 后保存到核心仓库") {
                        if (uri == null || busy) return@ExtraRow
                        busy = true
                        message = "正在校验并导入 " + core.label + "…"
                        scope.launch {
                            val result = runCatching { manager.importFromUri(core, uri, uri.lastPathSegment ?: "外部核心") }
                            result.onSuccess { message = core.label + " 已导入" }
                            result.onFailure { message = it.message ?: "导入失败" }
                            busy = false
                        }
                    }
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

private data class HetuLibraryInfo(val id: String, val name: String, val version: String, val project: String)

private val HETU_LIBRARIES = listOf(
    HetuLibraryInfo("compose", "Jetpack Compose", "BOM 2026.03.00", "https://developer.android.com/compose"),
    HetuLibraryInfo("materialkolor", "MaterialKolor", "2.0.0", "https://github.com/jordond/MaterialKolor"),
    HetuLibraryInfo("haze", "Haze", "1.6.10", "https://github.com/chrisbanes/haze"),
    HetuLibraryInfo("miuix-blur", "miuix-blur", "0.9.3", "https://github.com/compose-multiplatform/miuix"),
    HetuLibraryInfo("miuix-squircle", "miuix-squircle", "0.9.3", "https://github.com/compose-multiplatform/miuix"),
    HetuLibraryInfo("sora", "Sora Editor", "0.24.6", "https://github.com/Rosemoe/sora-editor"),
    HetuLibraryInfo("networknt-json-schema", "NetworkNT JSON Schema Validator", "1.5.9", "https://github.com/networknt/json-schema-validator"),
    HetuLibraryInfo("sing-box-schema", "sing-box official JSON Schema", "2026-09-26", "https://sing-box.sagernet.org/schema.json"),
    HetuLibraryInfo("snakeyaml", "SnakeYAML", "2.3", "https://bitbucket.org/snakeyaml/snakeyaml"),
    HetuLibraryInfo("androidsvg", "AndroidSVG", "1.4", "https://github.com/BigBadaboom/androidsvg"),
    HetuLibraryInfo("mihomo", "Mihomo", "1.19.31", "https://github.com/MetaCubeX/mihomo"),
)

private fun openExternal(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

class ProxyAboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HetuTheme { ProxyAboutScreen { finish() } } }
    }
}

@Composable
private fun ProxyAboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val t = LocalHetuTokens.current
    ExtraPage("关于河图", "版本、项目与开源依赖", onBack) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                ExtraCard {
                    Text("河图", color = t.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                    Text("v" + BuildConfig.VERSION_NAME, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("Root 透明代理、订阅、规则、面板、连接与运行维护。", color = t.textSecondary, fontSize = 12.5.sp, lineHeight = 18.sp)
                }
            }
            item {
                ExtraCard {
                    ExtraRow(Icons.Rounded.Code, "GitHub 项目", "xgl34222220-ops/hetu") { openExternal(context, "https://github.com/xgl34222220-ops/hetu") }
                    HorizontalDivider(color = t.outline)
                    ExtraRow(Icons.Rounded.LibraryBooks, "开源库", "查看当前构建使用的主要第三方项目") { context.startActivity(Intent(context, ProxyAboutLibrariesActivity::class.java)) }
                    HorizontalDivider(color = t.outline)
                    ExtraRow(Icons.Rounded.Favorite, "赞助与支持", "查看项目支持页面") { context.startActivity(Intent(context, ProxyAboutSponsorshipActivity::class.java)) }
                }
            }
            item {
                ExtraCard {
                    ExtraRow(Icons.Rounded.Shield, "运行目录", "/data/adb/hetu")
                    HorizontalDivider(color = t.outline)
                    ExtraRow(Icons.Rounded.PhoneAndroid, "Android", android.os.Build.VERSION.RELEASE + " / API " + android.os.Build.VERSION.SDK_INT)
                }
            }
        }
    }
}

class ProxyAboutLibrariesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HetuTheme { ProxyAboutLibrariesScreen { finish() } } }
    }
}

@Composable
private fun ProxyAboutLibrariesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    ExtraPage("开源库", "当前河图构建使用的主要组件", onBack) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(HETU_LIBRARIES, key = { it.id }) { lib ->
                ExtraCard {
                    ExtraRow(Icons.Rounded.Extension, lib.name, lib.project, lib.version) {
                        context.startActivity(Intent(context, ProxyAboutLibraryDetailActivity::class.java).putExtra("library_id", lib.id))
                    }
                }
            }
        }
    }
}

class ProxyAboutLibraryDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent.getStringExtra("library_id").orEmpty()
        setContent { HetuTheme { ProxyAboutLibraryDetailScreen(id) { finish() } } }
    }
}

@Composable
private fun ProxyAboutLibraryDetailScreen(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val t = LocalHetuTokens.current
    val lib = HETU_LIBRARIES.firstOrNull { it.id == id }
    ExtraPage(lib?.name ?: "开源库详情", lib?.version ?: "", onBack) {
        ExtraCard {
            Text(lib?.name ?: "未知组件", color = t.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("版本：" + (lib?.version ?: "—"), color = t.textSecondary)
            Text("许可证与完整版权信息以对应上游项目和 APK 内随附许可证为准。", color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
            if (lib != null) {
                Button(onClick = { openExternal(context, lib.project) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Rounded.OpenInNew, null); Spacer(Modifier.width(6.dp)); Text("打开上游项目")
                }
            }
        }
    }
}

class ProxyAboutSponsorshipActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HetuTheme { ProxyAboutSponsorshipScreen { finish() } } }
    }
}

@Composable
private fun ProxyAboutSponsorshipScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val t = LocalHetuTokens.current
    ExtraPage("赞助与支持", "支持河图持续维护", onBack) {
        ExtraCard {
            Text("感谢支持", color = t.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "当前构建没有配置支付地址，因此不会展示或跳转到未经确认的收款渠道。你仍可以通过 GitHub 关注项目、提交问题和测试反馈。",
                color = t.textSecondary,
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
            )
            Button(
                onClick = { openExternal(context, "https://github.com/xgl34222220-ops/hetu") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) { Icon(Icons.Rounded.Favorite, null); Spacer(Modifier.width(6.dp)); Text("打开项目主页") }
        }
    }
}

internal object HetuLauncherIcons {
    private const val OFFICIAL = "io.github.xgl34222220.hetu.LauncherOfficial"
    private const val CLASSIC = "io.github.xgl34222220.hetu.LauncherClassic"
    private const val TILE_OFFICIAL = "io.github.xgl34222220.hetu.TilePreferencesOfficial"
    private const val TILE_CLASSIC = "io.github.xgl34222220.hetu.TilePreferencesClassic"

    fun apply(context: Context, value: String) {
        val pm = context.packageManager
        val official = ComponentName(context, OFFICIAL)
        val classic = ComponentName(context, CLASSIC)
        val tileOfficial = ComponentName(context, TILE_OFFICIAL)
        val tileClassic = ComponentName(context, TILE_CLASSIC)
        val useClassic = value == "classic"
        pm.setComponentEnabledSetting(
            official,
            if (useClassic) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        pm.setComponentEnabledSetting(
            classic,
            if (useClassic) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        pm.setComponentEnabledSetting(
            tileOfficial,
            if (useClassic) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        pm.setComponentEnabledSetting(
            tileClassic,
            if (useClassic) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        context.getSharedPreferences("hetu", Context.MODE_PRIVATE).edit().putString("launcherIcon", if (useClassic) "classic" else "official").apply()
    }
}
