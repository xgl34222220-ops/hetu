package io.github.xgl34222220.hetu

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.LocalHetuTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREF_ONBOARDING_DONE = "hetuOnboardingDone"

@Composable
internal fun HetuOnboardingGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", Context.MODE_PRIVATE) }
    var done by remember { mutableStateOf(prefs.getBoolean(PREF_ONBOARDING_DONE, false)) }
    if (done) {
        content()
    } else {
        HetuOnboardingScreen {
            prefs.edit().putBoolean(PREF_ONBOARDING_DONE, true).apply()
            done = true
        }
    }
}

@Composable
private fun HetuOnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val tokens = LocalHetuTokens.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var accepted by remember { mutableStateOf(false) }
    var rootChecking by remember { mutableStateOf(false) }
    var rootGranted by remember { mutableStateOf(false) }
    var rootMessage by remember { mutableStateOf("尚未检测 Root 权限") }

    Surface(
        modifier = Modifier.fillMaxSize().background(tokens.pageBackground),
        color = tokens.pageBackground,
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(22.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = tokens.cardBackground,
                    tonalElevation = 0.dp,
                ) {
                    Box(Modifier.size(68.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            if (step == 0) Icons.Rounded.Security else Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(34.dp),
                            tint = if (step == 0) MaterialTheme.colorScheme.primary else tokens.success,
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (step == 0) "欢迎使用河图" else "Root 权限",
                        color = tokens.textPrimary,
                        fontSize = 30.sp,
                        lineHeight = 36.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        if (step == 0) "透明代理、订阅、规则、连接与运行维护统一在一个 App 内完成。"
                        else "河图的透明代理与运行文件管理需要 Root。授权只用于你主动启用的代理、规则与维护操作。",
                        color = tokens.textSecondary,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                    )
                }

                if (step == 0) {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = tokens.cardBackground,
                        tonalElevation = 0.dp,
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("使用说明", fontWeight = FontWeight.Bold, color = tokens.textPrimary)
                            Text(
                                "河图用于个人设备的网络代理、规则过滤和 Root 运行管理。请遵守所在地法律、网络管理要求以及你所使用服务的条款。配置、订阅与脚本可能包含敏感信息，请只导入你信任的来源。",
                                color = tokens.textSecondary,
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = accepted, onCheckedChange = { accepted = it })
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "我已阅读并理解以上说明",
                                    color = tokens.textPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = tokens.cardBackground,
                        tonalElevation = 0.dp,
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                rootMessage,
                                color = if (rootGranted) tokens.success else tokens.textSecondary,
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                            )
                            Button(
                                onClick = {
                                    if (rootChecking) return@Button
                                    rootChecking = true
                                    rootMessage = "正在请求并检测 Root 权限…"
                                    scope.launch {
                                        rootGranted = withContext(Dispatchers.IO) {
                                            runCatching { RootBridge.hasRoot(context) }.getOrDefault(false)
                                        }
                                        rootMessage = if (rootGranted) {
                                            "Root 权限可用，可以进入河图。"
                                        } else {
                                            "尚未获得 Root。请在 Magisk、KernelSU、APatch 或你的 Root 管理器中允许河图后重试。"
                                        }
                                        rootChecking = false
                                    }
                                },
                                enabled = !rootChecking,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                                shape = RoundedCornerShape(18.dp),
                            ) {
                                if (rootChecking) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("正在检测")
                                } else {
                                    Text(if (rootGranted) "重新检测 Root" else "申请 Root")
                                }
                            }
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (step > 0) {
                    OutlinedButton(
                        onClick = { step = 0 },
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) { Text("上一步") }
                }
                Button(
                    onClick = {
                        if (step == 0) step = 1 else onDone()
                    },
                    enabled = if (step == 0) accepted else rootGranted,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text(if (step == 0) "下一步" else "进入河图")
                }
            }
        }
    }
}
