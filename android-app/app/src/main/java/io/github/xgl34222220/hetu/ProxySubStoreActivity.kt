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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.xgl34222220.hetu.home.HomeButton
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.home.HomeCardTitle
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeFormField
import io.github.xgl34222220.hetu.home.HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomePill
import io.github.xgl34222220.hetu.home.HomePop
import io.github.xgl34222220.hetu.home.HomeProgressBar
import io.github.xgl34222220.hetu.home.HomeSpinner
import io.github.xgl34222220.hetu.home.HomeTone
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.badText
import io.github.xgl34222220.hetu.home.goodText
import io.github.xgl34222220.hetu.home.homeEnter
import io.github.xgl34222220.hetu.home.rememberHomeStagger
import io.github.xgl34222220.hetu.tools.ToolsFeatureIcons
import io.github.xgl34222220.hetu.tools.ToolsIcons
import io.github.xgl34222220.hetu.ui.ht
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

/** The glyphs of the Web tools, from the same line-icon family as the rest of the app. */
internal object WebToolIcons {
    val Back: ImageVector get() = HomeIcons.ChevronLeft
    val Window: ImageVector get() = HxIcons.AppWindow
    val Tune: ImageVector get() = HomeIcons.SlidersHorizontal
    val Link: ImageVector get() = ToolsIcons.Link
    val Download: ImageVector get() = ToolsIcons.Download
    val Brush: ImageVector get() = HxIcons.BrushCleaning
    val External: ImageVector get() = HxIcons.ExternalLink
    val Edit: ImageVector get() = ToolsIcons.Pencil
    val Trash: ImageVector get() = ToolsIcons.Trash2
    val Document: ImageVector get() = ToolsIcons.FileText
    val Terminal: ImageVector get() = ToolsIcons.SquareTerminal
    val Lock: ImageVector by lazy {
        ImageVector.Builder(name = "HetuWeb.Lock", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            listOf("M7 11V7a5 5 0 0 1 10 0v4", "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2Z").forEach {
                addPath(PathParser().parsePathString(it).toNodes(), fill = null, stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            }
        }.build()
    }
}

@Composable
internal fun WebToolTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", Context.MODE_PRIVATE) }
    HetuAppTheme(prefs.getString("appearance", "system").orEmpty(), prefs.getBoolean("hetuDynamicColor", prefs.getBoolean("enableMonet", false)),
        hxStoredAccent(prefs), prefs.getBoolean("pureBlackDark", false), content)
}

/**
 * A page whose body does not scroll as a whole (a web view, a short form): the app's bar with a
 * centred title and an optional line under it, then [content] filling the rest.
 */
@Composable
internal fun WebToolPage(title: String, subtitle: String? = null, onBack: () -> Unit, subtitleIcon: ImageVector? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalHomeColors.current
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().displayCutoutPadding().navigationBarsPadding().imePadding()) {
        Box(Modifier.fillMaxWidth().height(if (subtitle.isNullOrBlank()) HomeDims.barHeight else HomeDims.barHeight + 14.dp)) {
            HomeIconButton(HomeIcons.ChevronLeft, "返回", onBack, Modifier.align(Alignment.TopStart).padding(start = 6.dp, top = 8.dp), glyph = 26.dp)
            Column(Modifier.align(Alignment.TopCenter).padding(horizontal = 60.dp).padding(top = if (subtitle.isNullOrBlank()) 19.dp else 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, Modifier.semantics { heading() }, color = c.t1, style = HomeType.barTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (subtitleIcon != null) Icon(subtitleIcon, null, Modifier.size(13.dp), tint = c.t2)
                    Text(subtitle, color = c.t2, style = HomeType.barSubtitle, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        content()
    }
}

@Composable
internal fun WebToolCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(HomeDims.cardShape).background(LocalHomeColors.current.surface), content = content)
}

/** The app's button. [outlined] is the quieter of the two; [pill] is accepted for older callers. */
@Composable
internal fun WebToolAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null,
    outlined: Boolean = false, enabled: Boolean = true, busy: Boolean = false, danger: Boolean = false, @Suppress("UNUSED_PARAMETER") pill: Boolean = false) {
    HomeButton(text, onClick, modifier, kind = if (outlined) HomeButtonKind.Secondary else HomeButtonKind.Primary,
        icon = icon, enabled = enabled, loading = busy, danger = danger)
}

@Composable
internal fun WebToolField(value: String, onChange: (String) -> Unit, label: String, clear: Boolean = false, enabled: Boolean = true) {
    HomeFormField(label, value, onChange, enabled = enabled, clearable = clear, keyboardType = if (clear) KeyboardType.Uri else KeyboardType.Text)
}

/** A sheet headed by [title] (drawn as given) with a close button; closing never commits what is in it. */
@Composable
internal fun WebToolSheet(title: String, onDismiss: () -> Unit, @Suppress("UNUSED_PARAMETER") separatedChoices: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalHomeColors.current
    HxSheet(onDismiss) {
        val close = LocalHxSheetClose.current
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).semantics { heading() }, color = c.t1, style = HomeType.sheetTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            HomeIconButton(HomeIcons.X, "关闭", { close(onDismiss) }, tint = c.t2)
        }
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

class ProxySubStoreActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { WebToolTheme { ProxySubStoreScreen { finish() } } }
    }
}

/** Sub-Store: where the local backend lives, whether it answers, and the door to its panel. */
@Composable
internal fun ProxySubStoreScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", Context.MODE_PRIVATE) }
    val c = LocalHomeColors.current
    val scope = rememberCoroutineScope()
    var backend by remember { mutableStateOf(prefs.getString(PREF_SUB_STORE_BACKEND, DEFAULT_SUB_STORE_BACKEND).orEmpty().ifBlank { DEFAULT_SUB_STORE_BACKEND }) }
    var checking by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf<Boolean?>(null) }
    var status by remember { mutableStateOf("填写本机 Sub-Store 后端地址后检测。默认端口为 3000。") }
    val stagger = rememberHomeStagger()
    WebToolPage("Sub-Store", ht("本地订阅管理面板"), onBack) {
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = HomeDims.gutter).padding(top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(HomeDims.gap)) {
            WebToolCard(Modifier.homeEnter(stagger, 0)) {
                HomeCardTitle(ht("本地后端")) {
                    // The verdict of the last check, at a glance.
                    HomePop(available != null) {
                        HomePill(ht(if (available == true) "已连接" else "未连接"), Modifier.padding(end = 10.dp), tone = if (available == true) HomeTone.Good else HomeTone.Bad)
                    }
                }
                Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    WebToolField(backend, { backend = it.take(240); available = null; status = "地址已修改，请重新检测。" }, "后端地址", clear = true, enabled = !checking)
                    Text(status, color = when (available) { true -> c.goodText; false -> c.badText; null -> c.t2 }, style = HomeType.note.copy(fontWeight = FontWeight.Medium))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        }, Modifier.weight(1f), HomeIcons.RefreshCw, outlined = true, enabled = !checking, busy = checking)
                        WebToolAction("打开面板", {
                            val value = backend.trim().trimEnd('/').ifBlank { DEFAULT_SUB_STORE_BACKEND }
                            prefs.edit().putString(PREF_SUB_STORE_BACKEND, value).apply()
                            context.startActivity(Intent(context, ProxySubStoreWebActivity::class.java).putExtra("backend", value))
                        }, Modifier.weight(1f), WebToolIcons.External)
                    }
                }
            }
            WebToolCard(Modifier.homeEnter(stagger, 1)) {
                HomeCardTitle(ht("说明"))
                Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    listOf(
                        ToolsFeatureIcons.ShieldCheck to "河图连接的是你设备上已经运行的 Sub-Store 后端，不会把订阅内容上传给河图服务器。",
                        HxIcons.AppWindow to "面板使用 Sub-Store 官方前端，并把 API 指向你填写的本机地址。",
                        HomeIcons.Info to "若显示未安装，请先确保本地后端已启动。",
                    ).forEach { (icon, line) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Icon(icon, null, Modifier.padding(top = 1.dp).size(20.dp), tint = c.t2)
                            Text(ht(line), Modifier.weight(1f), color = c.t2, style = HomeType.body.copy(fontSize = 15.sp, lineHeight = 22.sp))
                        }
                    }
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

/** Keeps status/cutout/keyboard/navigation insets and native history-aware Back. */
internal fun ComponentActivity.setWebToolContent(view: WebView, state: WebToolLoadState, name: String, subStoreBackend: String? = null) {
    enableEdgeToEdge()
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    setContent {
        WebToolTheme {
            val back = { if (view.canGoBack()) view.goBack() else finish() }
            BackHandler(onBack = back)
            val c = LocalHomeColors.current
            WebToolPage(name, state.host.ifBlank { null }, back, if (state.url.startsWith("https://")) WebToolIcons.Lock else null) {
                // The page comes from the web; the frame around it says where from and how far along.
                Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = HomeDims.gutter).padding(top = 6.dp, bottom = 14.dp)
                    .clip(HomeDims.cardShape).background(c.surface)) {
                    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                    if (state.loading || state.error != null) {
                        Column(Modifier.fillMaxSize().background(c.surface), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (state.loading) HomeProgressBar(state.progress / 100f, Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 14.dp), height = 4.dp)
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    if (state.loading) HomeSpinner(size = 34.dp, strokeWidth = 3.dp)
                                    else Icon(HomeIcons.CircleAlert, null, Modifier.size(40.dp), tint = c.bad)
                                    Text(state.error ?: ht(if (subStoreBackend == null) "正在加载外部面板…" else "正在加载 Sub-Store…"),
                                        Modifier.padding(horizontal = 24.dp), color = if (state.error == null) c.t2 else c.badText,
                                        style = HomeType.body.copy(fontWeight = FontWeight.Medium), textAlign = TextAlign.Center)
                                    if (state.error != null) HomeButton("重试", { state.error = null; state.loading = true; view.reload() }, kind = HomeButtonKind.Soft, icon = HomeIcons.RefreshCw, tinted = true)
                                }
                            }
                            Text(ht(if (subStoreBackend == null) "远端内容由网站提供" else "官方前端内容由网页提供"), Modifier.padding(top = 14.dp, bottom = if (subStoreBackend == null) 16.dp else 4.dp),
                                color = c.t3, style = HomeType.caption)
                            if (subStoreBackend != null) Text("后端 ${android.net.Uri.parse(subStoreBackend).let { "${it.host.orEmpty()}${if (it.port >= 0) ":${it.port}" else ""}" }}",
                                Modifier.padding(bottom = 14.dp), color = c.t3, style = HomeType.mono.copy(fontSize = 12.sp))
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
        val state = WebToolLoadState(SUB_STORE_FRONTEND)
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
        if (!view.restoreSavedPage(savedInstanceState)) view.loadUrl(SUB_STORE_FRONTEND + "?api=" + api)
        else { state.url = view.url ?: SUB_STORE_FRONTEND; state.progress = view.progress; state.loading = view.progress < 100 }
    }
    override fun onSaveInstanceState(outState: Bundle) { webView?.savePage(outState); super.onSaveInstanceState(outState) }
    override fun onDestroy() {
        webView?.apply { stopLoading(); loadUrl("about:blank"); clearHistory(); removeAllViews(); destroy() }
        webView = null
        super.onDestroy()
    }
}
