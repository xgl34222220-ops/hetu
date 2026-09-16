package io.github.xgl34222220.bichen

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.xgl34222220.bichen.ui.BichenTheme
import io.github.xgl34222220.bichen.ui.LocalBichenTokens
import org.json.JSONArray
import org.json.JSONObject

private const val WEB_UI_BOOTSTRAP = "bichen-dashboard-bootstrap-v4"
private const val PREF_WEB_DASHBOARD = "proxyWebDashboardId"
private const val PREF_CUSTOM_DASHBOARDS = "proxyCustomDashboards"

private data class DashboardTarget(
    val id: String,
    val name: String,
    val url: String,
    val local: Boolean = false,
    val builtIn: Boolean = true,
)

private val BUILTIN_DASHBOARDS = listOf(
    DashboardTarget(
        id = "local",
        name = "本地面板",
        url = "http://127.0.0.1:${MihomoStartupConfig.CONTROLLER_PORT}/ui/",
        local = true,
    ),
    DashboardTarget(
        id = "zashboard",
        name = "Zashboard",
        url = "https://board.zash.run.place/",
    ),
    DashboardTarget(
        id = "metacubexd",
        name = "MetaCubeXD",
        url = "https://d.metacubex.one/",
    ),
)

/**
 * Dashboard host styled after the reference app instead of exposing a raw WebView.
 * Local MetaCubeXD remains the secure default and receives the controller secret only inside
 * its loopback origin. Remote dashboards are optional views and never receive the secret from
 * Bichen automatically.
 */
class ProxyWebUiActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BichenTheme { ProxyWebUiScreen { finish() } } }
    }
}

private fun loadCustomDashboards(context: Context): List<DashboardTarget> {
    val prefs = context.getSharedPreferences("bichen", Context.MODE_PRIVATE)
    val raw = prefs.getString(PREF_CUSTOM_DASHBOARDS, "[]").orEmpty()
    return runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val name = item.optString("name")
                val url = item.optString("url")
                if (id.isNotBlank() && name.isNotBlank() && (url.startsWith("https://") || url.startsWith("http://"))) {
                    add(DashboardTarget(id, name, url, local = false, builtIn = false))
                }
            }
        }
    }.getOrDefault(emptyList())
}

private fun resolvedDashboardUrl(target: DashboardTarget, secret: String): String {
    if (target.id != "zashboard") return target.url
    val encoded = android.net.Uri.encode(secret)
    return target.url.trimEnd('/') + "/#/setup?protocol=http&hostname=127.0.0.1&port=${MihomoStartupConfig.CONTROLLER_PORT}&secret=$encoded&disableUpgradeCore=1&disableTunMode=1"
}

private fun saveCustomDashboards(context: Context, items: List<DashboardTarget>) {
    val array = JSONArray()
    items.filterNot { it.builtIn }.forEach { item ->
        array.put(JSONObject().put("id", item.id).put("name", item.name).put("url", item.url))
    }
    context.getSharedPreferences("bichen", Context.MODE_PRIVATE)
        .edit().putString(PREF_CUSTOM_DASHBOARDS, array.toString()).apply()
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyWebUiScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val inspector = remember { ProxyRuntimeInspector(context) }
    val secret = remember { inspector.controllerSecret() }
    val prefs = remember { context.getSharedPreferences("bichen", Context.MODE_PRIVATE) }

    var customTargets by remember { mutableStateOf(loadCustomDashboards(context)) }
    val allTargets = remember(customTargets) { BUILTIN_DASHBOARDS + customTargets }
    val initialId = remember { prefs.getString(PREF_WEB_DASHBOARD, "local") ?: "local" }
    var selected by remember {
        mutableStateOf((BUILTIN_DASHBOARDS + loadCustomDashboards(context)).firstOrNull { it.id == initialId } ?: BUILTIN_DASHBOARDS.first())
    }
    var preparing by remember { mutableStateOf(true) }
    var prepareError by remember { mutableStateOf("") }
    var pageError by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var showSwitcher by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }

    fun selectedDisplayUrl(): String = selected.url
        .removePrefix("https://")
        .removePrefix("http://")
        .removeSuffix("/")

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.safeBrowsingEnabled = true
            clearCache(true)

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress = newProgress.coerceIn(0, 100)
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    val localHost = uri.host == "127.0.0.1" || uri.host == "localhost"
                    val local = uri.scheme == "http" && localHost && uri.port == MihomoStartupConfig.CONTROLLER_PORT
                    val selectedHost = runCatching { android.net.Uri.parse(selected.url).host }.getOrNull()
                    val knownHost = BUILTIN_DASHBOARDS.mapNotNull { runCatching { android.net.Uri.parse(it.url).host }.getOrNull() }.contains(uri.host)
                    if (local || uri.host == selectedHost || knownHost) return false
                    return runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        true
                    }.getOrDefault(true)
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, webError: WebResourceError?) {
                    super.onReceivedError(view, request, webError)
                    if (request?.isForMainFrame == true) {
                        pageError = "${selected.name} 加载失败：${webError?.description ?: "未知错误"}"
                    }
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                    super.onReceivedHttpError(view, request, response)
                    if (request?.isForMainFrame == true && (response?.statusCode ?: 200) >= 400) {
                        pageError = "${selected.name} 返回 HTTP ${response?.statusCode ?: 0}"
                    }
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    if (url.isNullOrBlank()) return
                    pageError = ""
                    val injectEndpoint = selected.local || selected.id == "metacubexd"
                    if (!injectEndpoint) return
                    if (selected.local && !url.startsWith("http://127.0.0.1:${MihomoStartupConfig.CONTROLLER_PORT}/ui/")) return

                    val endpoint = JSONObject()
                        .put("id", "bichen-local")
                        .put("url", "http://127.0.0.1:${MihomoStartupConfig.CONTROLLER_PORT}")
                        .put("secret", secret)
                    val list = JSONArray().put(endpoint).toString()
                    val listLiteral = JSONObject.quote(list)
                    val marker = "$WEB_UI_BOOTSTRAP-${selected.id}"
                    val js = """
                        (async function() {
                          var id = 'bichen-local';
                          var desired = $listLiteral;
                          try {
                            localStorage.setItem('selectedEndpoint', id);
                            localStorage.setItem('endpointList', desired);
                            if (!sessionStorage.getItem('$marker')) {
                              sessionStorage.setItem('$marker', '1');
                              try {
                                if ('serviceWorker' in navigator) {
                                  var registrations = await navigator.serviceWorker.getRegistrations();
                                  await Promise.all(registrations.map(function(registration) { return registration.unregister(); }));
                                }
                              } catch (_) {}
                              try {
                                if (window.caches) {
                                  var keys = await caches.keys();
                                  await Promise.all(keys.map(function(key) { return caches.delete(key); }));
                                }
                              } catch (_) {}
                              ${if (selected.local) "window.location.replace('/ui/?bichen_fresh=1');" else "window.location.reload();"}
                              return 'reloading';
                            }
                            return 'ready';
                          } catch (e) {
                            return 'bootstrap-error:' + String(e);
                          }
                        })();
                    """.trimIndent()
                    view.evaluateJavascript(js) { result ->
                        if (result?.contains("bootstrap-error:") == true) {
                            pageError = "${selected.name} 初始化失败，请刷新重试"
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(selected.id) {
        preparing = true
        prepareError = ""
        pageError = ""
        progress = 0
        try {
            if (selected.local) inspector.ensureWebUi()
            webView.stopLoading()
            webView.clearHistory()
            webView.loadUrl(resolvedDashboardUrl(selected, secret))
            prefs.edit().putString(PREF_WEB_DASHBOARD, selected.id).apply()
        } catch (e: Exception) {
            prepareError = e.message ?: "面板准备失败"
        } finally {
            preparing = false
        }
    }

    DisposableEffect(webView) {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else onClose()
            }
        }
        (context as? ComponentActivity)?.onBackPressedDispatcher?.addCallback(callback)
        onDispose {
            callback.remove()
            webView.stopLoading()
            webView.destroy()
        }
    }

    fun forceFreshReload() {
        pageError = ""
        prepareError = ""
        progress = 0
        webView.clearCache(true)
        val stamp = System.currentTimeMillis()
        if (selected.local) {
            webView.evaluateJavascript("sessionStorage.removeItem('$WEB_UI_BOOTSTRAP-${selected.id}');") {
                webView.loadUrl("http://127.0.0.1:${MihomoStartupConfig.CONTROLLER_PORT}/ui/?bichen_retry=$stamp")
            }
        } else {
            val base = resolvedDashboardUrl(selected, secret)
            if (selected.id == "zashboard") {
                webView.loadUrl(base)
            } else {
                val separator = if (base.contains('?')) '&' else '?'
                webView.loadUrl("$base$separator" + "bichen_retry=$stamp")
            }
        }
    }

    fun clearPanelData() {
        pageError = ""
        webView.evaluateJavascript("try{localStorage.clear();sessionStorage.clear();if(window.caches){caches.keys().then(k=>k.forEach(x=>caches.delete(x)));}}catch(e){}") {
            webView.clearCache(true)
            forceFreshReload()
        }
    }

    Column(Modifier.fillMaxSize().background(tokens.pageBackground)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 62.dp).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                if (webView.canGoBack()) webView.goBack() else onClose()
            }, modifier = Modifier.size(46.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = tokens.textPrimary)
            }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    selectedDisplayUrl(),
                    color = tokens.textPrimary,
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(selected.name, color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = ::forceFreshReload, enabled = !preparing, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Rounded.Refresh, "刷新面板", tint = scheme.primary)
            }
            IconButton(onClick = { showSwitcher = true }, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Rounded.Tune, "切换面板", tint = scheme.primary)
            }
            IconButton(onClick = ::clearPanelData, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Rounded.DeleteOutline, "清理面板缓存", tint = tokens.textSecondary)
            }
        }

        if (progress in 1..99 && !preparing) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
        }

        when {
            prepareError.isNotBlank() -> {
                Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                    Surface(shape = RoundedCornerShape(20.dp), color = tokens.cardBackground) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("面板暂时不可用", color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium)
                            Text(prepareError, color = tokens.textSecondary, style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = ::forceFreshReload, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                                Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("重新加载")
                            }
                        }
                    }
                }
            }
            preparing -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 2.5.dp)
                        Text("正在准备 ${selected.name}…", color = tokens.textSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            pageError.isNotBlank() -> {
                Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                    Surface(shape = RoundedCornerShape(20.dp), color = tokens.cardBackground) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                            Text("${selected.name} 加载失败", color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium)
                            Text(pageError, color = tokens.textSecondary, style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = ::forceFreshReload, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("清理并重载")
                            }
                        }
                    }
                }
            }
            else -> AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f))
        }
    }

    if (showSwitcher) {
        ModalBottomSheet(
            onDismissRequest = { showSwitcher = false },
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
            containerColor = tokens.pageBackground,
            scrimColor = Color.Black.copy(alpha = .28f),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("切换面板", color = tokens.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
                Text("内置面板", color = tokens.textSecondary, style = MaterialTheme.typography.labelMedium)
                Column(
                    Modifier.fillMaxWidth()
                        .background(
                            Brush.verticalGradient(listOf(tokens.selectionBackground.copy(alpha = .88f), tokens.cardBackground.copy(alpha = .78f))),
                            RoundedCornerShape(18.dp),
                        )
                        .border(.7.dp, tokens.outline.copy(alpha = .42f), RoundedCornerShape(18.dp)),
                ) {
                    BUILTIN_DASHBOARDS.forEachIndexed { index, target ->
                        DashboardTargetRow(
                            target = target,
                            selected = selected.id == target.id,
                            onClick = {
                                selected = target
                                showSwitcher = false
                            },
                        )
                        if (index < BUILTIN_DASHBOARDS.lastIndex) HorizontalDivider(color = tokens.outline.copy(alpha = .45f), modifier = Modifier.padding(horizontal = 14.dp))
                    }
                }
                if (customTargets.isNotEmpty()) {
                    Text("自定义面板", color = tokens.textSecondary, style = MaterialTheme.typography.labelMedium)
                    Column(
                        Modifier.fillMaxWidth()
                            .background(tokens.cardBackground, RoundedCornerShape(18.dp))
                            .border(.7.dp, tokens.outline.copy(alpha = .35f), RoundedCornerShape(18.dp)),
                    ) {
                        customTargets.forEachIndexed { index, target ->
                            DashboardTargetRow(
                                target = target,
                                selected = selected.id == target.id,
                                onClick = {
                                    selected = target
                                    showSwitcher = false
                                },
                                onDelete = {
                                    customTargets = customTargets.filterNot { it.id == target.id }
                                    saveCustomDashboards(context, customTargets)
                                    if (selected.id == target.id) selected = BUILTIN_DASHBOARDS.first()
                                },
                            )
                            if (index < customTargets.lastIndex) HorizontalDivider(color = tokens.outline.copy(alpha = .45f), modifier = Modifier.padding(horizontal = 14.dp))
                        }
                    }
                }
                Surface(
                    onClick = { showSwitcher = false; showAddDialog = true },
                    shape = RoundedCornerShape(16.dp),
                    color = tokens.selectionBackground,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Add, null, tint = scheme.primary)
                        Spacer(Modifier.width(9.dp))
                        Text("新增面板", color = scheme.primary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    }
                }
                Text(
                    "Zashboard 与 MetaCubeXD 会自动连接本机 Mihomo；自定义面板仍需按其自身方式填写控制端点。",
                    color = tokens.textSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            shape = RoundedCornerShape(22.dp),
            title = { Text("新增面板") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(newName, { newName = it }, label = { Text("名称") }, singleLine = true)
                    OutlinedTextField(newUrl, { newUrl = it }, label = { Text("URL") }, placeholder = { Text("https://example.com/") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = newUrl.trim()
                    val name = newName.trim().ifBlank { runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty() }
                    if (name.isNotBlank() && (url.startsWith("https://") || url.startsWith("http://"))) {
                        val target = DashboardTarget("custom-${System.currentTimeMillis()}", name, url, builtIn = false)
                        customTargets = customTargets + target
                        saveCustomDashboards(context, customTargets)
                        selected = target
                        newName = ""
                        newUrl = ""
                        showAddDialog = false
                    }
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun DashboardTargetRow(
    target: DashboardTarget,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val tokens = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (target.local) Icons.Rounded.Home else Icons.Rounded.Language,
            null,
            tint = if (selected) scheme.primary else tokens.textPrimary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(target.name, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
            Text(target.url, color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(Icons.Rounded.Check, "当前面板", tint = scheme.primary, modifier = Modifier.size(22.dp))
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.DeleteOutline, "删除", tint = tokens.textSecondary, modifier = Modifier.size(19.dp))
            }
        }
    }
}
