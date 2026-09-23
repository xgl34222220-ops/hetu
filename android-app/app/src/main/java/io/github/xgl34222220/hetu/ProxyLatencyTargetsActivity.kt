package io.github.xgl34222220.hetu

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.HetuTheme
import io.github.xgl34222220.hetu.ui.LocalHetuTokens

internal data class ProxyLatencyTarget(val name: String, val url: String)

internal object ProxyLatencyTargets {
    private const val COUNT = 3
    private const val NAME_PREFIX = "proxyLatencyTargetName"
    private const val URL_PREFIX = "proxyLatencyTargetUrl"
    private const val LAST_PREFIX = "proxyUiLastDelayTarget"

    val defaults = listOf(
        ProxyLatencyTarget("Baidu", "https://www.baidu.com/"),
        ProxyLatencyTarget("Cloudflare", "https://cp.cloudflare.com/generate_204"),
        ProxyLatencyTarget("Google", "https://www.gstatic.com/generate_204"),
    )

    fun load(context: Context): List<ProxyLatencyTarget> =
        load(context.getSharedPreferences("hetu", Context.MODE_PRIVATE))

    fun load(prefs: SharedPreferences): List<ProxyLatencyTarget> =
        (0 until COUNT).map { index ->
            val fallback = defaults[index]
            ProxyLatencyTarget(
                prefs.getString("$NAME_PREFIX$index", fallback.name).orEmpty().trim().ifBlank { fallback.name },
                prefs.getString("$URL_PREFIX$index", fallback.url).orEmpty().trim().ifBlank { fallback.url },
            )
        }

    fun save(prefs: SharedPreferences, values: List<ProxyLatencyTarget>) {
        val normalized = (0 until COUNT).map { index -> values.getOrNull(index) ?: defaults[index] }
        prefs.edit().apply {
            normalized.forEachIndexed { index, target ->
                putString("$NAME_PREFIX$index", target.name.trim().take(40))
                putString("$URL_PREFIX$index", target.url.trim().take(400))
            }
        }.apply()
    }

    fun reset(prefs: SharedPreferences) {
        prefs.edit().apply {
            (0 until COUNT).forEach { index ->
                remove("$NAME_PREFIX$index")
                remove("$URL_PREFIX$index")
                remove("$LAST_PREFIX$index")
            }
        }.apply()
    }

    fun lastResults(prefs: SharedPreferences): Map<String, Long> {
        val targets = load(prefs)
        return buildMap {
            targets.forEachIndexed { index, target ->
                val value = prefs.getLong("$LAST_PREFIX$index", -2L)
                if (value != -2L) put(target.name, value)
            }
        }
    }

    fun persistLast(prefs: SharedPreferences, measured: Map<String, Long>) {
        val targets = load(prefs)
        prefs.edit().apply {
            targets.forEachIndexed { index, target ->
                putLong("$LAST_PREFIX$index", measured[target.name] ?: -2L)
            }
        }.apply()
    }

    fun validate(target: ProxyLatencyTarget): String? {
        if (target.name.trim().isBlank()) return "测速目标名称不能为空"
        if (target.url.trim().isBlank()) return "测速地址不能为空"
        return runCatching {
            val uri = Uri.parse(target.url.trim())
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                "测速地址必须是有效的 HTTP(S) URL"
            } else null
        }.getOrElse { "测速地址格式无效" }
    }
}

class ProxyLatencyTargetsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HetuTheme { ProxyLatencyTargetsScreen { finish() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyLatencyTargetsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", Context.MODE_PRIVATE) }
    val tokens = LocalHetuTokens.current
    var targets by remember { mutableStateOf(ProxyLatencyTargets.load(prefs)) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize().background(tokens.pageBackground),
        containerColor = tokens.pageBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("延迟目标", fontWeight = FontWeight.Bold)
                        Text("首页测速与自动刷新使用这些地址", fontSize = 11.sp, color = tokens.textSecondary)
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
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(3) { index ->
                val target = targets[index]
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = tokens.cardBackground),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("目标 ${index + 1}", fontSize = 13.sp, color = tokens.textSecondary, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = target.name,
                            onValueChange = { value ->
                                val next = targets.toMutableList()
                                next[index] = target.copy(name = value.take(40))
                                targets = next
                                message = ""
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("名称") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                        )
                        OutlinedTextField(
                            value = target.url,
                            onValueChange = { value ->
                                val next = targets.toMutableList()
                                next[index] = target.copy(url = value.take(400))
                                targets = next
                                message = ""
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("HTTP(S) 测速地址") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                        )
                    }
                }
            }
            if (message.isNotBlank()) {
                item {
                    Text(
                        message,
                        color = if (error) MaterialTheme.colorScheme.error else tokens.success,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = {
                            ProxyLatencyTargets.reset(prefs)
                            targets = ProxyLatencyTargets.defaults
                            message = "已恢复默认测速目标"
                            error = false
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Icon(Icons.Rounded.RestartAlt, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text("恢复默认")
                    }
                    Button(
                        onClick = {
                            val failure = targets.firstNotNullOfOrNull(ProxyLatencyTargets::validate)
                            if (failure != null) {
                                message = failure
                                error = true
                            } else if (targets.map { it.name.trim().lowercase() }.distinct().size != targets.size) {
                                message = "三个测速目标名称不能重复"
                                error = true
                            } else {
                                ProxyLatencyTargets.save(prefs, targets)
                                message = "测速目标已保存；返回首页后立即生效"
                                error = false
                            }
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text("保存")
                    }
                }
            }
        }
    }
}
