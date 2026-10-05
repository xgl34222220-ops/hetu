package io.github.xgl34222220.hetu

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val PREF_SUB_STORE_BACKEND = "subStoreBackendUrl"
private const val DEFAULT_SUB_STORE_BACKEND = "http://127.0.0.1:3000"
private const val SUB_STORE_FRONTEND = "https://sub-store.vercel.app/"

/** Outline paths scoped to the Web-tool concepts, with no effect on dashboard icons. */
internal object WebToolIcons {
    private fun line(name: String, vararg paths: String) = ImageVector.Builder(
        name = "HetuWeb.$name", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply { paths.forEach { addPath(PathParser().parsePathString(it).toNodes(), fill = null,
        stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) } }.build()
    val Back = line("Back", "m15 4-8 8 8 8")
    val Window = line("Window", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z", "M3 8h18", "M8 3v5")
    val Tune = line("Tune", "M3 5h4m4 0h10M3 12h10m4 0h4M3 19h4m4 0h10", "M7 5a2 2 0 1 0 4 0a2 2 0 1 0-4 0M13 12a2 2 0 1 0 4 0a2 2 0 1 0-4 0M7 19a2 2 0 1 0 4 0a2 2 0 1 0-4 0")
    val Link = line("Link", "M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7L12 5", "M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7L12 19")
    val Download = line("Download", "M12 2v14m-5-5 5 5 5-5", "M7 7H5a2 2 0 0 0-2 2v11a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-2")
    val Brush = line("Brush", "m16 2-5 9", "m7 10 9 5-5 8-9-5 5-8Z", "m6 17-2 3m6-1-2 3", "m5 14 9 5")
    val External = line("External", "M14 3h7v7m0-7L10 14", "M10 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-5")
    val Edit = line("Edit", "m15 4 5 5M3 21l1-6L16 3a3.5 3.5 0 0 1 5 5L9 20l-6 1Z")
    val Trash = line("Trash", "M3 6h18M9 6V3h6v3M6 6v14a1 1 0 0 0 1 1h10a1 1 0 0 0 1-1V6M10 10v7m4-7v7")
    val Document = line("Document", "M6 2h12a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2Z", "M8 7h8M8 12h8M8 17h4")
    val Lock = line("Lock", "M7 10V7a5 5 0 0 1 10 0v3", "M5 10h14v11H5Z", "M12 14v3")
    val Terminal = line("Terminal", "M4 3h16a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z", "m7 8 4 4-4 4M14 16h3")
}

@Composable
internal fun WebToolTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", Context.MODE_PRIVATE) }
    val accent = prefs.getString("accentHex", "").orEmpty().takeUnless {
        it.equals("#2563EB", true) || it.equals("#3B82F6", true) || it.equals("#2A62E8", true) || it.equals("#7EA6FF", true)
    }.orEmpty()
    HetuAppTheme(prefs.getString("appearance", "system").orEmpty(), prefs.getBoolean("hetuDynamicColor", prefs.getBoolean("enableMonet", false)),
        accent, prefs.getBoolean("pureBlackDark", false), content)
}

/** The reference uses a centred two-line title rather than the legacy left subtitle. */
@Composable
internal fun WebToolPage(title: String, subtitle: String? = null, onBack: () -> Unit, subtitleIcon: ImageVector? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Hx.colors
    val brush = Brush.verticalGradient(if (c.dark) listOf(c.canvas, c.canvas) else listOf(Color(0xFFECEEFB), Color(0xFFEEF0FC)))
    Column(Modifier.fillMaxSize().background(brush).statusBarsPadding().displayCutoutPadding().navigationBarsPadding().imePadding()) {
        Box(Modifier.fillMaxWidth().height(if (subtitle == null) 64.dp else 78.dp)) {
            IconButton(onBack, Modifier.align(Alignment.CenterStart).padding(start = 4.dp)) {
                Icon(WebToolIcons.Back, "返回", tint = c.text, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.align(Alignment.Center).padding(horizontal = 52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, color = c.text, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    if (subtitleIcon != null) { Icon(subtitleIcon, null, tint = c.textMuted, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(5.dp)) }
                    Text(subtitle, color = c.textMuted, fontSize = 12.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        content()
    }
}

@Composable
internal fun WebToolCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(HomeContinuousShape(24.dp)).background(Hx.colors.surface), content = content)
}

@Composable
internal fun WebToolAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null,
    outlined: Boolean = false, enabled: Boolean = true, busy: Boolean = false, danger: Boolean = false, pill: Boolean = false) {
    val c = Hx.colors
    val color = if (danger) Color(0xFFF33E4B) else if (c.dark) c.accent else Color(0xFF287AFF)
    val shape = RoundedCornerShape(if (pill) 50.dp else 14.dp)
    val fg = if (!enabled) c.textFaint else if (outlined) color else Color.White
    Row(modifier.heightIn(min = 44.dp).clip(shape)
        .background(if (!enabled) c.surfaceMuted else if (outlined) Color.Transparent else color)
        .then(if (outlined) Modifier.border(1.dp, if (enabled) color else c.line, shape) else Modifier)
        .clickable(enabled = enabled && !busy, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (busy) { CircularProgressIndicator(Modifier.size(20.dp), color = color, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        else if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(21.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, fontSize = 16.sp, lineHeight = 21.sp, color = fg, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun WebToolField(value: String, onChange: (String) -> Unit, label: String, clear: Boolean = false, enabled: Boolean = true) {
    val c = Hx.colors
    val fieldShape = RoundedCornerShape(if (clear) 17.dp else 11.dp)
    Column(verticalArrangement = Arrangement.spacedBy(if (clear) 6.dp else 7.dp)) {
        Text(label, color = if (clear) c.text else c.textMuted,
            fontSize = if (clear) 16.sp else 13.sp, lineHeight = if (clear) 22.sp else 18.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth().heightIn(min = if (clear) 44.dp else 48.dp).clip(fieldShape)
            .background(c.surfaceMuted.copy(alpha = if (clear) .5f else .4f)).border(.7.dp, c.line.copy(alpha = .6f), fieldShape)
            .padding(start = 13.dp, end = if (clear) 2.dp else 13.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(value, onChange, enabled = enabled, singleLine = true,
                textStyle = TextStyle(color = c.text, fontSize = if (clear) 16.sp else 15.5.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
                cursorBrush = SolidColor(c.accent), modifier = Modifier.weight(1f).padding(vertical = if (clear) 9.dp else 11.dp))
            if (clear && value.isNotEmpty()) IconButton(onClick = { onChange("") }, enabled = enabled, modifier = Modifier.size(42.dp)) {
                Icon(Icons.Rounded.Cancel, "清空后端地址", tint = c.textFaint.copy(alpha = .7f), modifier = Modifier.size(19.dp))
            }
        }
    }
}

@Composable
internal fun WebToolSheet(title: String, onDismiss: () -> Unit, separatedChoices: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val c = Hx.colors
    HxSheet(onDismiss, containerColor = if (separatedChoices && !c.dark) Color(0xFFF5F4FD) else c.surface) {
        val close = LocalHxSheetClose.current
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Hx.colors.text, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { close(onDismiss) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Close, "关闭", tint = Hx.colors.textMuted) }
        }
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 17.dp), verticalArrangement = Arrangement.spacedBy(if (separatedChoices) 10.dp else 16.dp), content = content)
    }
}

class ProxySubStoreActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { WebToolTheme { ProxySubStoreScreen { finish() } } }
    }
}

@Composable
internal fun ProxySubStoreScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", Context.MODE_PRIVATE) }
    val c = Hx.colors
    val scope = rememberCoroutineScope()
    var backend by remember { mutableStateOf(prefs.getString(PREF_SUB_STORE_BACKEND, DEFAULT_SUB_STORE_BACKEND).orEmpty().ifBlank { DEFAULT_SUB_STORE_BACKEND }) }
    var checking by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf<Boolean?>(null) }
    var status by remember { mutableStateOf("填写本机 Sub-Store 后端地址后检测。默认端口为 3000。") }
    WebToolPage("Sub-Store", "本地订阅管理面板", onBack) {
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 13.dp).padding(top = 9.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            WebToolCard {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("本地后端", color = c.text, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        WebToolField(backend, { backend = it.take(240); available = null; status = "地址已修改，请重新检测。" }, "后端地址", clear = true, enabled = !checking)
                        Text(status, color = when (available) { true -> c.good; false -> c.bad; null -> c.textMuted }, fontSize = 12.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        WebToolAction(if (checking) "检测中" else "检测", {
                            val value = backend.trim().trimEnd('/').ifBlank { DEFAULT_SUB_STORE_BACKEND }
                            checking = true
                            scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) { probeSubStore(value) }
                                    available = result.first; status = result.second
                                    if (result.first) prefs.edit().putString(PREF_SUB_STORE_BACKEND, value).apply()
                                } catch (cancel: CancellationException) { throw cancel }
                                finally { checking = false }
                            }
                        }, Modifier.weight(1f), Icons.Rounded.Refresh, outlined = true, enabled = !checking, busy = checking, pill = true)
                        WebToolAction("打开面板", {
                            val value = backend.trim().trimEnd('/').ifBlank { DEFAULT_SUB_STORE_BACKEND }
                            prefs.edit().putString(PREF_SUB_STORE_BACKEND, value).apply()
                            context.startActivity(Intent(context, ProxySubStoreWebActivity::class.java).putExtra("backend", value))
                        }, Modifier.weight(1f), WebToolIcons.External, pill = true)
                    }
                }
            }
            WebToolCard {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(WebToolIcons.Document, null, tint = c.text, modifier = Modifier.size(25.dp))
                        Text("说明", color = c.text, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("河图连接的是你设备上已经运行的 Sub-Store 后端，不会把订阅内容上传给河图服务器。\n\n面板使用 Sub-Store 官方前端，并把 API 指向你填写的本机地址。\n\n若显示未安装，请先确保本地后端已启动。",
                        color = c.textMuted, fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
private fun probeSubStore(base: String): Pair<Boolean, String> {
    if (!(base.startsWith("http://127.0.0.1") || base.startsWith("http://localhost") || base.startsWith("https://"))) {
        return false to "为避免误连，请使用本机 127.0.0.1 / localhost，或明确的 HTTPS 后端地址。"
    }
    return try {
        val connection = (URL(base.trimEnd('/') + "/api/utils/env").openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 3000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Hetu-Android")
        }
        try {
            val code = connection.responseCode
            val body = if (code in 200..299) connection.inputStream.bufferedReader().use { it.readText().take(64 * 1024) } else ""
            if (code !in 200..299) {
                false to "后端不可用（HTTP " + code + "）"
            } else {
                val root = runCatching { JSONObject(body) }.getOrNull()
                val data = root?.optJSONObject("data")
                val backendName = data?.optString("backend").orEmpty()
                val version = data?.optString("version").orEmpty()
                val label = listOf(backendName, version).filter { it.isNotBlank() }.joinToString(" · ")
                true to if (label.isBlank()) "Sub-Store 后端已连接" else "Sub-Store 已连接 · " + label
            }
        } finally {
            connection.disconnect()
        }
    } catch (error: Exception) {
        false to ("Sub-Store 未安装或后端未启动：" + (error.message ?: error.javaClass.simpleName))
    }
}

/** Progress is driven exclusively by the actual WebView, never by a timer. */
internal class WebToolLoadState(initialUrl: String) {
    var progress by mutableIntStateOf(0)
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var url by mutableStateOf(initialUrl)
    val host: String get() = android.net.Uri.parse(url).host.orEmpty()
}

internal fun webToolClient(state: WebToolLoadState) = object : WebViewClient() {
    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        state.url = url.orEmpty(); state.loading = true; state.error = null; state.progress = 0
    }
    override fun onPageFinished(view: WebView?, url: String?) { state.loading = false }
    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        if (request?.isForMainFrame == true) { state.error = error?.description?.toString() ?: "页面加载失败"; state.loading = false }
    }
}

internal fun webToolChromeClient(state: WebToolLoadState) = object : WebChromeClient() {
    override fun onProgressChanged(view: WebView?, newProgress: Int) { state.progress = newProgress.coerceIn(0, 100) }
}

/** Opens a selected HTTP(S) page, rejecting URI user-info in both page and Sub-Store backend. */
internal fun webToolBrowserIntent(url: String): Intent? {
    if (url.any { it.isWhitespace() || it.isISOControl() }) return null
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
    val page = android.net.Uri.parse(url)
    val backend = runCatching { page.getQueryParameter("api") }.getOrNull()
    if (backend != null) {
        if (backend.any { it.isWhitespace() || it.isISOControl() }) return null
        val api = runCatching { java.net.URI(backend) }.getOrNull() ?: return null
        if (api.scheme?.lowercase() !in setOf("http", "https") || api.host.isNullOrBlank() || api.rawUserInfo != null) return null
    }
    return Intent(Intent.ACTION_VIEW, page.normalizeScheme()).addCategory(Intent.CATEGORY_BROWSABLE)
}

/** Keeps status/cutout/keyboard/navigation insets and native history-aware Back. */
internal fun ComponentActivity.setWebToolContent(view: WebView, state: WebToolLoadState, name: String, subStoreBackend: String? = null) {
    enableEdgeToEdge()
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    setContent {
        WebToolTheme {
            val back = { if (view.canGoBack()) view.goBack() else finish() }
            BackHandler(onBack = back)
            WebToolPage(name, if (subStoreBackend == null) state.host else null, back, if (subStoreBackend == null && state.url.startsWith("https://")) WebToolIcons.Lock else null) {
                if (subStoreBackend != null) {
                    WebToolCard(Modifier.padding(horizontal = 13.dp).padding(bottom = 12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(WebToolIcons.Lock, "HTTPS", tint = Hx.colors.textMuted, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(state.host, color = Hx.colors.textMuted, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("默认浏览器打开", color = Hx.colors.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Hx.colors.accentSoft)
                                    .clickable {
                                        val target = webToolBrowserIntent(view.url ?: state.url)
                                        if (target != null) runCatching { startActivity(target) }.onFailure {
                                            android.widget.Toast.makeText(this@setWebToolContent, "没有可用的浏览器", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }.padding(horizontal = 9.dp, vertical = 5.dp))
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 13.dp).padding(top = if (subStoreBackend == null) 9.dp else 0.dp, bottom = 16.dp)
                    .clip(RoundedCornerShape(18.dp)).background(Hx.colors.surface)) {
                    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                    if (state.loading || state.error != null) {
                        Column(Modifier.fillMaxSize().background(Hx.colors.surface).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (state.loading) LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(4.dp)),
                                color = Color(0xFF287AFF), trackColor = Hx.colors.line.copy(alpha = .6f), gapSize = 0.dp, drawStopIndicator = {})
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    if (state.loading) CircularProgressIndicator(Modifier.size(34.dp), color = Color(0xFF287AFF), trackColor = Hx.colors.accentSoft, strokeWidth = 4.dp)
                                    Text(state.error ?: if (subStoreBackend == null) "正在加载外部面板…" else "正在加载 Sub-Store…",
                                        color = if (state.error == null) Hx.colors.textMuted else Hx.colors.bad, fontSize = 15.sp, lineHeight = 21.sp,
                                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 20.dp))
                                    if (state.error != null) TextButton(onClick = { state.error = null; state.loading = true; view.reload() }) { Text("重试") }
                                }
                            }
                            if (subStoreBackend != null) HorizontalDivider(color = Hx.colors.line.copy(alpha = .35f))
                            Text(if (subStoreBackend == null) "远端内容由网站提供" else "官方前端内容由网页提供", color = Hx.colors.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(top = 14.dp, bottom = if (subStoreBackend == null) 16.dp else 5.dp))
                            if (subStoreBackend != null) Text("后端 ${android.net.Uri.parse(subStoreBackend).let { "${it.host.orEmpty()}${if (it.port >= 0) ":${it.port}" else ""}" }}",
                                color = Hx.colors.textFaint, fontSize = 10.sp, modifier = Modifier.padding(bottom = 12.dp))
                        }
                    }
                }
            }
        }
    }
}

class ProxySubStoreWebActivity : ComponentActivity() {
    private var webView: WebView? = null
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val backend = intent.getStringExtra("backend").orEmpty().ifBlank { DEFAULT_SUB_STORE_BACKEND }
        val api = URLEncoder.encode(backend, "UTF-8")
        val initialUrl = SUB_STORE_FRONTEND + "?api=" + api
        val state = WebToolLoadState(initialUrl)
        val view = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.setSupportZoom(false)
            webChromeClient = webToolChromeClient(state)
            webViewClient = webToolClient(state)
        }
        webView = view
        setWebToolContent(view, state, "Sub-Store", backend)
        if (!view.restoreSavedPage(savedInstanceState)) view.loadUrl(initialUrl)
        else { state.url = view.url ?: SUB_STORE_FRONTEND; state.progress = view.progress; state.loading = view.progress < 100 }
    }
    override fun onSaveInstanceState(outState: Bundle) { webView?.savePage(outState); super.onSaveInstanceState(outState) }
    override fun onDestroy() {
        webView?.apply { stopLoading(); loadUrl("about:blank"); clearHistory(); removeAllViews(); destroy() }
        webView = null
        super.onDestroy()
    }
}
