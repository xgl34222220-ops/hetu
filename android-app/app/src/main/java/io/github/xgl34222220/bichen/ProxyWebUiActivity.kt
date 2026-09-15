package io.github.xgl34222220.bichen

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
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

/**
 * Loopback-only MetaCubeXD host. The controller secret is injected into this WebView's
 * localStorage and never placed in the URL, intent extras, logs, or clipboard.
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
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress = newProgress.coerceIn(0, 100)
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    val local = uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == MihomoStartupConfig.CONTROLLER_PORT
                    if (local) return false
                    return runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        true
                    }.getOrDefault(true)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    val endpoint = JSONObject()
                        .put("id", "bichen-local")
                        .put("url", "http://127.0.0.1:${MihomoStartupConfig.CONTROLLER_PORT}")
                        .put("secret", secret)
                    val list = JSONArray().put(endpoint).toString()
                    val listLiteral = JSONObject.quote(list)
                    val js = """
                        (function() {
                          var id = 'bichen-local';
                          var desired = $listLiteral;
                          var changed = localStorage.getItem('selectedEndpoint') !== id || localStorage.getItem('endpointList') !== desired;
                          if (changed) {
                            localStorage.setItem('selectedEndpoint', id);
                            localStorage.setItem('endpointList', desired);
                            window.location.replace('/ui/');
                          }
                        })();
                    """.trimIndent()
                    view.evaluateJavascript(js, null)
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
                    Surface(onClick = { webView.reload() }, shape = CircleShape, color = tokens.cardBackground, shadowElevation = 1.dp, modifier = Modifier.size(44.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Refresh, "刷新 WebUI", modifier = Modifier.size(21.dp)) }
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
                            Text("如果这是升级后的第一次打开，请先重启一次代理核心，让新的本地 WebUI 配置生效。", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
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
            else -> {
                if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
                AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}
