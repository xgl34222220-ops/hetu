package io.github.xgl34222220.hetu

import io.github.xgl34222220.hetu.ui.HetuPageHeader

import io.github.xgl34222220.hetu.ui.ReferenceButton as Button

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.border
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

class ProxyLatencyTargetsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefs = remember { getSharedPreferences("hetu", Context.MODE_PRIVATE) }
            HetuAppTheme(prefs.getString("hetuAppearance", "system").orEmpty(), prefs.getBoolean("hetuDynamicColor", false)) {
                ProxyLatencyTargetsScreen { finish() }
            }
        }
    }
}

@Composable
internal fun ProxyLatencyTargetsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", Context.MODE_PRIVATE) }
    val c = Hx.colors
    var targets by remember { mutableStateOf(ProxyLatencyTargets.load(prefs)) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    HxPage(title = "本机直测目标", subtitle = "测量直连请求，未指定代理节点", onBack = onBack, largeTitle = false) {
        items(3) { index ->
            val target = targets[index]
            HxSection {
                HxCard(padding = PaddingValues(16.dp)) {
                    Text("目标 ${index + 1}", fontSize = 13.sp, color = c.textMuted)
                    Spacer(Modifier.height(12.dp))
                    HomeTargetField("名称", target.name) { value ->
                        targets = targets.toMutableList().also { it[index] = target.copy(name = value.take(40)) }
                        message = ""
                    }
                    Spacer(Modifier.height(10.dp))
                    HomeTargetField("HTTP(S) 测速地址", target.url) { value ->
                        targets = targets.toMutableList().also { it[index] = target.copy(url = value.take(400)) }
                        message = ""
                    }
                }
            }
        }
        if (message.isNotBlank()) item {
            Text(message, color = if (error) c.bad else c.good, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = Hx.gutter + 4.dp).padding(bottom = 7.dp))
        }
        item {
            HxSection {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HxButton("恢复默认", onClick = {
                        ProxyLatencyTargets.reset(prefs)
                        targets = ProxyLatencyTargets.defaults
                        message = "已恢复默认测速目标"
                        error = false
                    }, modifier = Modifier.weight(1f), icon = Icons.Rounded.RestartAlt, filled = false, outlined = true)
                    HxButton("保存", onClick = {
                        val failure = targets.firstNotNullOfOrNull(ProxyLatencyTargets::validate)
                        when {
                            failure != null -> { message = failure; error = true }
                            targets.map { it.name.trim().lowercase(java.util.Locale.ROOT) }.distinct().size != targets.size -> {
                                message = "三个测速目标名称不能重复。"; error = true
                            }
                            else -> {
                                ProxyLatencyTargets.save(prefs, targets)
                                message = "测速目标已保存；返回首页后立即生效。"; error = false
                            }
                        }
                    }, modifier = Modifier.weight(1f), icon = Icons.Rounded.Save)
                }
            }
        }
    }
}

@Composable
private fun HomeTargetField(label: String, value: String, onValueChange: (String) -> Unit) {
    val c = Hx.colors
    Text(label, color = c.textMuted, fontSize = 12.sp)
    Spacer(Modifier.height(4.dp))
    androidx.compose.foundation.text.BasicTextField(
        value = value, onValueChange = onValueChange, singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(color = c.text, fontSize = 15.sp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp)
            .then(Modifier.border(1.dp, c.line, RoundedCornerShape(9.dp)))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}
