package io.github.xgl34222220.hetu

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.HetuTheme
import io.github.xgl34222220.hetu.ui.LocalHetuTokens
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

class ProxySubStoreActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HetuTheme { ProxySubStoreScreen { finish() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxySubStoreScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", Context.MODE_PRIVATE) }
    val tokens = LocalHetuTokens.current
    val scope = rememberCoroutineScope()
    var backend by remember {
        mutableStateOf(prefs.getString(PREF_SUB_STORE_BACKEND, DEFAULT_SUB_STORE_BACKEND).orEmpty().ifBlank { DEFAULT_SUB_STORE_BACKEND })
    }
    var checking by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf<Boolean?>(null) }
    var status by remember { mutableStateOf("填写本机 Sub-Store 后端地址后检测。默认端口为 3000。") }

    fun normalized(): String = backend.trim().trimEnd('/')

    Scaffold(
        containerColor = tokens.pageBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Sub-Store", fontWeight = FontWeight.Bold)
                        Text("本地订阅管理面板", fontSize = 11.sp, color = tokens.textSecondary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = tokens.pageBackground),
            )
        },
        modifier = Modifier.fillMaxSize().background(tokens.pageBackground),
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = tokens.cardBackground),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("本地后端", color = tokens.textPrimary, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = backend,
                        onValueChange = {
                            backend = it.take(240)
                            available = null
                            status = "地址已修改，请重新检测。"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("后端地址") },
                        placeholder = { Text(DEFAULT_SUB_STORE_BACKEND) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                    )
                    Text(
                        status,
                        color = when (available) {
                            true -> tokens.success
                            false -> MaterialTheme.colorScheme.error
                            null -> tokens.textSecondary
                        },
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                if (checking) return@OutlinedButton
                                val value = normalized()
                                checking = true
                                status = "正在检测 Sub-Store 后端…"
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { probeSubStore(value) }
                                    available = result.first
                                    status = result.second
                                    if (result.first) prefs.edit().putString(PREF_SUB_STORE_BACKEND, value).apply()
                                    checking = false
                                }
                            },
                            enabled = !checking,
                            modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(Modifier.width(7.dp))
                            Text(if (checking) "检测中" else "检测")
                        }
                        Button(
                            onClick = {
                                val value = normalized().ifBlank { DEFAULT_SUB_STORE_BACKEND }
                                prefs.edit().putString(PREF_SUB_STORE_BACKEND, value).apply()
                                context.startActivity(
                                    Intent(context, ProxySubStoreWebActivity::class.java)
                                        .putExtra("backend", value),
                                )
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Icon(Icons.Rounded.OpenInBrowser, contentDescription = null)
                            Spacer(Modifier.width(7.dp))
                            Text("打开面板")
                        }
                    }
                }
            }

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = tokens.cardBackground),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("说明", color = tokens.textPrimary, fontWeight = FontWeight.Bold)
                    Text(
                        "河图连接的是你设备上已经运行的 Sub-Store 后端，不会把订阅内容上传给河图服务器。面板使用 Sub-Store 官方前端，并把 API 指向你填写的本机地址。若显示未安装，请先确保本地后端已启动。",
                        color = tokens.textSecondary,
                        fontSize = 12.sp,
                        lineHeight = 19.sp,
                    )
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

class ProxySubStoreWebActivity : ComponentActivity() {
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val backend = intent.getStringExtra("backend").orEmpty().ifBlank { DEFAULT_SUB_STORE_BACKEND }
        val api = URLEncoder.encode(backend, "UTF-8")
        val view = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.setSupportZoom(false)
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            loadUrl(SUB_STORE_FRONTEND + "?api=" + api)
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
