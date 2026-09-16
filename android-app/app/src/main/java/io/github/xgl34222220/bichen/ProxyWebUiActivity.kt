package io.github.xgl34222220.bichen

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.xgl34222220.bichen.ui.BichenTheme
import io.github.xgl34222220.bichen.ui.LocalBichenTokens

private fun localDashboardUrl(secret: String): String {
    val encoded = Uri.encode(secret)
    return "http://127.0.0.1:${MihomoStartupConfig.CONTROLLER_PORT}/ui/#/setup" +
        "?protocol=http&hostname=127.0.0.1&port=${MihomoStartupConfig.CONTROLLER_PORT}" +
        "&secret=$encoded&disableUpgradeCore=1&disableTunMode=1"
}

/**
 * Local-only Zashboard host.
 *
 * The dashboard files and Clash API are both served by Mihomo at 127.0.0.1 on the same port.
 * This intentionally avoids hosted-dashboard CORS / Private Network Access behavior in WebView.
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
    val scheme = MaterialTheme.colorScheme
    val inspector = remember { ProxyRuntimeInspector(context) }

    var preparing by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var generation by remember { mutableIntStateOf(0) }
    var forceRepair by remember { mutableStateOf(false) }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.safeBrowsingEnabled = true

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
                    return if (uri.scheme == "http" || uri.scheme == "https") {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        true
                    } else {
                        true
                    }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        pageError = "Zashboard 加载失败：${error?.description ?: "未知错误"}"
                    }
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                    super.onReceivedHttpError(view, request, response)
                    if (request?.isForMainFrame == true && (response?.statusCode ?: 200) >= 400) {
                        pageError = "Zashboard 返回 HTTP ${response?.statusCode ?: 0}"
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!url.isNullOrBlank() && url.startsWith("http://127.0.0.1:${MihomoStartupConfig.CONTROLLER_PORT}/ui/")) {
                        pageError = ""
                    }
                }
            }
        }
    }

    LaunchedEffect(generation) {
        preparing = true
        pageError = ""
        progress = 0
        try {
            val runtime = inspector.sample()
            if (!runtime.running) error("代理核心没有运行，请先启动代理")
            val secret = inspector.controllerSecret()
            if (secret.isBlank()) error("本机控制密钥尚未初始化，请重启代理一次")

            if (forceRepair) inspector.repairWebUi() else inspector.ensureWebUi()
            forceRepair = false
            webView.stopLoading()
            webView.clearHistory()
            webView.loadUrl(localDashboardUrl(secret))
        } catch (error: Exception) {
            pageError = error.message ?: "Zashboard 准备失败"
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

    fun repairAndReload() {
        forceRepair = true
        generation++
    }

    Column(Modifier.fillMaxSize().background(tokens.pageBackground)) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .heightIn(min = 64.dp)
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { if (webView.canGoBack()) webView.goBack() else onClose() },
                modifier = Modifier.size(46.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = tokens.textPrimary)
            }

            Column(Modifier.weight(1f).padding(horizontal = 5.dp)) {
                Text(
                    "Zashboard",
                    color = tokens.textPrimary,
                    fontSize = 18.sp,
                    lineHeight = 23.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "本机 WebUI · 同源连接",
                    color = tokens.textSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            IconButton(
                onClick = {
                    pageError = ""
                    if (!preparing) webView.reload()
                },
                enabled = !preparing,
                modifier = Modifier.size(44.dp),
            ) {
                Icon(Icons.Rounded.Refresh, "刷新", tint = scheme.primary)
            }
            IconButton(
                onClick = ::repairAndReload,
                enabled = !preparing,
                modifier = Modifier.size(44.dp),
            ) {
                Icon(Icons.Rounded.Build, "修复 WebUI", tint = tokens.textSecondary)
            }
        }

        if (progress in 1..99 && !preparing && pageError.isBlank()) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
        }

        when {
            preparing -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 2.5.dp)
                        Text("正在准备本机 Zashboard…", color = tokens.textSecondary)
                    }
                }
            }

            pageError.isNotBlank() -> {
                Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.Center) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = tokens.cardBackground,
                        shadowElevation = 0.dp,
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("WebUI 暂时不可用", color = tokens.textPrimary, style = MaterialTheme.typography.titleMedium)
                            Text(pageError, color = tokens.textSecondary, style = MaterialTheme.typography.bodyMedium)
                            Button(
                                onClick = ::repairAndReload,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Icon(Icons.Rounded.Build, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("重新安装并打开 Zashboard")
                            }
                        }
                    }
                }
            }

            else -> {
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
