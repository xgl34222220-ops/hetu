package io.github.xgl34222220.bichen

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
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
    val secret = remember {
        context.getSharedPreferences("bichen", 0).getString("proxyControllerSecret", "").orEmpty()
    }
    var progress by remember { mutableIntStateOf(0) }
    val webView = remember {
        WebView(context).apply {
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
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Surface(onClick = { webView.reload() }, shape = CircleShape, color = tokens.cardBackground, shadowElevation = 1.dp, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Refresh, "刷新 WebUI", modifier = Modifier.size(21.dp)) }
                }
            }
        }
        if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f))
    }
}
