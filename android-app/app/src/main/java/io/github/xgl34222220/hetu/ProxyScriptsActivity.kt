package io.github.xgl34222220.hetu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.HetuTheme
import io.github.xgl34222220.hetu.ui.LocalHetuTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class ProxyScriptsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HetuTheme { ProxyScriptsScreen { finish() } } }
    }
}

private data class ScriptEntry(
    val title: String,
    val subtitle: String,
    val path: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyScriptsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val t = LocalHetuTokens.current
    val scope = rememberCoroutineScope()
    val entries = remember {
        listOf(
            ScriptEntry(
                "服务启动前",
                "代理核心启动前执行；非 0 退出码会阻止手动启动",
                ProxyScriptHooks.PRE_START,
            ),
            ScriptEntry(
                "服务停止后",
                "代理网络恢复完成后执行；非 0 退出码会报告失败",
                ProxyScriptHooks.POST_STOP,
            ),
        )
    }
    var editor by remember { mutableStateOf<ScriptEntry?>(null) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var environment by remember { mutableStateOf(false) }

    fun open(entry: ScriptEntry) {
        if (busy) return
        scope.launch {
            busy = true
            message = ""
            try {
                text = ProxyScriptHooks.read(context, entry.path)
                editor = entry
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                message = failure.message ?: "脚本读取失败"
            } finally {
                busy = false
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().crystalPageBackground(),
        contentPadding = PaddingValues(
            start = 12.dp,
            top = 8.dp,
            end = 12.dp,
            bottom = io.github.xgl34222220.hetu.ui.hetuContentBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().height(58.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = t.textPrimary)
                }
                Text(
                    "脚本",
                    Modifier.weight(1f),
                    color = t.textPrimary,
                    fontSize = 30.sp,
                    lineHeight = 38.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = t.cardBackground,
                shadowElevation = 0.dp,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    entries.forEachIndexed { index, entry ->
                        WorkspaceSettingRow(
                            entry.title,
                            entry.subtitle,
                            Icons.Rounded.Code,
                            onClick = { open(entry) },
                        ) {
                            Text(
                                if (busy) "读取中" else "编辑",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        if (index != entries.lastIndex) WorkspaceInsetDivider()
                    }
                }
            }
        }

        item {
            Surface(
                onClick = { environment = true },
                shape = RoundedCornerShape(20.dp),
                color = t.cardBackground,
                shadowElevation = 0.dp,
            ) {
                WorkspaceSettingRow(
                    "脚本环境",
                    "查看河图注入的环境变量、路径和执行限制",
                    Icons.Rounded.Terminal,
                ) {
                    Icon(Icons.Rounded.Info, null, Modifier.size(18.dp), tint = t.textSecondary)
                }
            }
        }

        if (message.isNotBlank()) {
            item {
                HetuTaskFeedback(
                    message,
                    error = message.contains("失败") || message.contains("错误"),
                    busy = busy,
                )
            }
        }
    }

    editor?.let { entry ->
        ModalBottomSheet(
            onDismissRequest = { if (!busy) editor = null },
            containerColor = Color.Transparent,
            shape = RoundedCornerShape(topStart = HetuGlassRadius.Sheet, topEnd = HetuGlassRadius.Sheet),
        ) {
            Column(
                Modifier.fillMaxWidth().liquidSheetMaterial().navigationBarsPadding().imePadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    entry.title,
                    color = t.textPrimary,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    entry.subtitle,
                    color = t.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                LiquidGlassTextField(
                    value = text,
                    onValueChange = { if (it.length <= 64 * 1024) text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 480.dp),
                    label = "脚本内容",
                    placeholder = "#!/system/bin/sh\n# 在这里输入脚本",
                    singleLine = false,
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    ),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    ProxyScriptHooks.clear(context, entry.path)
                                    text = ""
                                    message = "已清空${entry.title}脚本"
                                } catch (failure: Exception) {
                                    message = failure.message ?: "清空失败"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("清空")
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    ProxyScriptHooks.write(context, entry.path, text)
                                    message = "${entry.title}脚本已保存"
                                    editor = null
                                } catch (failure: Exception) {
                                    message = failure.message ?: "保存失败"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Rounded.Save, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (busy) "处理中" else "保存")
                    }
                }
            }
        }
    }

    if (environment) {
        ModalBottomSheet(
            onDismissRequest = { environment = false },
            containerColor = t.cardBackground,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(.62f).navigationBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "脚本环境",
                    color = t.textPrimary,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Box(
                    Modifier.fillMaxWidth().weight(1f)
                        .background(t.controlBackground, RoundedCornerShape(16.dp))
                        .padding(14.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        ProxyScriptHooks.environmentText(),
                        color = t.textPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
}
