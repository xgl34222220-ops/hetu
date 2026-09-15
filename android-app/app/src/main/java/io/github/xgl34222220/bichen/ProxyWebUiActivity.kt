package io.github.xgl34222220.bichen

import android.annotation.SuppressLint
import android.content.Intent
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.xgl34222220.bichen.ui.BichenTheme
import io.github.xgl34222220.bichen.ui.LocalBichenTokens
import org.json.JSONArray
import org.json.JSONObject

private const val WEB_UI_BOOTSTRAP = "bichen-metacubexd-bootstrap-v3"

/**
 * Loopback-only MetaCubeXD host. The controller secret is injected into this WebView's
 * localStorage and never placed in the URL, intent extras, logs, or clipboard.
 *
 * MetaCubeXD is a PWA. Android WebView can retain an old service worker/cache after the local
 * dashboard files are upgraded, which leaves a completely blank page when old HTML references
 * chunks that no longer exist. We always bypass the HTTP cache, unregister stale workers once per
 * WebView session, clear CacheStorage, then reload the local dashboard before handing it to users.
 */
class ProxyWebUiActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BichenTheme { ProxyWebUiScreen { finish() } } }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ProxyWebUiScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val tokens = LocalBichenTokens.current
    val inspector = remember { ProxyRuntimeInspector(context) }
    val secret = remember { inspector.controllerSecret() }
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var pageError by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        try {
            inspector.ensureWebUi()
            ready = true
        } catch (e: Exception) {
            error = e.message ?: "WebUI 准备失败"
        }
    }

    val webView = remember(ready) {
        if (!ready) null else WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
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
                    if (local) return false
                    return runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        true
                    }.getOrDefault(true)
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, webError: WebResourceError?) {
                    super.onReceivedError(view, request, webError)
                    if (request?.isForMainFrame == true) {
                        pageError = "MetaCubeXD 页面加载失败：${webError?.description ?: "未知错误"}"
                    }
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                    super.onReceivedHttpError(view, request, response)
                    if (request?.isForMainFrame == true && (response?.statusCode ?: 200) >= 400) {
                        pageError = "MetaCubeXD 页面返回 HTTP ${response?.statusCode ?: 0}"
                    }
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    if (url.isNullOrBlank() || !url.startsWith("http://127.0.0.1:${MihomoStartupConfig.CONTROLLER_PORT}/ui/")) return

                    val endpoint = JSONObject()
                        .put("id", "bichen-local")
                        .put("url", "http://127.0.0.1:${MihomoStartupConfig.CONTROLLER_PORT}")
                        .put("secret", secret)
                    val list = JSONArray().put(endpoint).toString()
                    val listLiteral = JSONObject.quote(list)
                    val js = """
                        (async function() {
                          var id = 'bichen-local';
                          var desired = $listLiteral;
                          try {
                            localStorage.setItem('selectedEndpoint', id);
                            localStorage.setItem('endpointList', desired);

                            if (!sessionStorage.getItem('$WEB_UI_BOOTSTRAP')) {
                              sessionStorage.setItem('$WEB_UI_BOOTSTRAP', '1');
                              try {
                                if ('serviceWorker' in navigator) {
                                  var registrations = await navigator.serviceWorker.getRegistrations();
                                  await Promise.all(registrations.map(function(registration) {
                                    return registration.unregister();
                                  }));
                                }
                              } catch (_) {}
                              try {
                                if (window.caches) {
                                  var keys = await caches.keys();
                                  await Promise.all(keys.map(function(key) { return caches.delete(key); }));
                                }
                              } catch (_) {}
                              window.location.replace('/ui/?bichen_fresh=1');
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
                            pageError = "MetaCubeXD 初始化失败，请点右上角刷新重试"
                        } else if (result?.contains("ready") == true) {
                            pageError = ""
                        }
                    }
                }
            }
            loadUrl("http://127.0.0.1:${MihomoStartupConfig.CONTROLLER_PORT}/ui/")
        }
    }

    DisposableEffect(webView) {
        if (webView == null) return@DisposableEffect onDispose { }
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

    fun forceFreshReload(view: WebView) {
        pageError = ""
        progress = 0
        view.clearCache(true)
        val stamp = System.currentTimeMillis()
        view.evaluateJavascript("sessionStorage.removeItem('$WEB_UI_BOOTSTRAP');") {
            view.loadUrl("http://127.0.0.1:${MihomoStartupConfig.CONTROLLER_PORT}/ui/?bichen_retry=$stamp")
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Surface(onClick = onClose, shape = CircleShape, color = tokens.cardBackground, shadowElevation = 1.dp, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Close, "关闭 WebUI", modifier = Modifier.size(21.dp)) }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("WebUI", color = tokens.textPrimary, style = MaterialTheme.typography.titleLarge)
                Text("MetaCubeXD · 本机控制接口", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
            if (webView != null) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Surface(onClick = { forceFreshReload(webView) }, shape = CircleShape, color = tokens.cardBackground, shadowElevation = 1.dp, modifier = Modifier.size(44.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Refresh, "彻底刷新 WebUI", modifier = Modifier.size(21.dp)) }
                    }
                }
            }
        }
        when {
            error.isNotBlank() -> {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Surface(shape = RoundedCornerShape(24.dp), color = tokens.cardBackground) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("WebUI 暂时不可用", color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium)
                            Text(error, color = tokens.textSecondary, style = MaterialTheme.typography.bodyMedium)
                            Text("确认代理核心正在运行后重试；缺失或损坏的 MetaCubeXD 文件会自动重新下载。", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            webView == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text("正在准备 MetaCubeXD…", color = tokens.textSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            pageError.isNotBlank() -> {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Surface(shape = RoundedCornerShape(24.dp), color = tokens.cardBackground) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("MetaCubeXD 加载失败", color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium)
                            Text(pageError, color = tokens.textSecondary, style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { forceFreshReload(webView) }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("清理缓存并重载")
                            }
                        }
                    }
                }
            }
            else -> {
                if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
                AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}
