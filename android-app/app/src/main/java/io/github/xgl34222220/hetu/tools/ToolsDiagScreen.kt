package io.github.xgl34222220.hetu.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.StartupConfigViewer
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.home.HomeCard
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeNotice
import io.github.xgl34222220.hetu.home.HomeReveal
import io.github.xgl34222220.hetu.home.HomeRowDims
import io.github.xgl34222220.hetu.home.HomeRowDivider
import io.github.xgl34222220.hetu.home.HomeSpinner
import io.github.xgl34222220.hetu.home.HomeTone
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.homeEnter
import io.github.xgl34222220.hetu.home.rememberHomeStagger
import io.github.xgl34222220.hetu.ui.ht
import kotlinx.coroutines.CancellationException

/**
 * 诊断与维护 (工具 › 诊断工具). Stateless.
 *
 * Preflight (its result unfolds under the row), the two read-only views, the event journal of
 * the host when it has one, and the emergency restore. The two sheets and the restore dialog
 * are hosted by the route; their contents are below.
 */
@Composable
internal fun ToolsDiagScreen(
    state: ToolsDiagState,
    onBack: () -> Unit,
    onPreflight: () -> Unit,
    onStartupConfig: () -> Unit,
    onReport: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDiagnosticsDetails: (() -> Unit)? = null,
    onOpenNetworkTest: (() -> Unit)? = null,
) {
    val c = LocalHomeColors.current
    val checking = state.preflight is ToolsPreflight.Running
    val stagger = rememberHomeStagger()
    ToolsPage(title = "诊断与维护", onBack = onBack, modifier = modifier, subtitle = "预检、运行副本、诊断信息与紧急恢复") {
        HomeCard(Modifier.fillMaxWidth().homeEnter(stagger, 0)) {
            ToolsCardTitle("运行预检")
            ToolsRow(
                AnnotatedString(ht("开始预检")), icon = ToolsFeatureIcons.ShieldPlus,
                subtitle = ht("验证 Root / TPROXY / UID / IPv6 / 绕过规则是否可用"),
                enabled = !checking, onClick = onPreflight,
                trailing = { if (checking) HomeSpinner(size = 20.dp, color = c.accent) else ToolsChevron() },
            )
            val result = state.preflight
            HomeReveal(result is ToolsPreflight.Passed || result is ToolsPreflight.Failed) {
                val passed = result is ToolsPreflight.Passed
                val message = when (result) {
                    is ToolsPreflight.Passed -> result.message
                    is ToolsPreflight.Failed -> result.message
                    else -> ""
                }
                HomeNotice(
                    message, if (passed) HomeIcons.CircleCheck else HomeIcons.CircleAlert,
                    Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp), tone = if (passed) HomeTone.Good else HomeTone.Bad,
                )
            }
        }
        HomeCard(Modifier.fillMaxWidth().homeEnter(stagger, 1)) {
            ToolsCardTitle("查看")
            ToolsRow(
                AnnotatedString(ht("启动配置")), icon = ToolsIcons.FileText,
                subtitle = ht("最终生成的运行副本，不修改源配置"), onClick = onStartupConfig, trailing = { ToolsChevron() },
            )
            HomeRowDivider(start = HomeRowDims.textStart)
            ToolsRow(
                AnnotatedString(ht("消息与网络诊断")), icon = ToolsFeatureIcons.Router,
                subtitle = ht("Google / 微信连接、分流与最近运行事件"), onClick = onReport, trailing = { ToolsChevron() },
            )
            if (onOpenNetworkTest != null) {
                HomeRowDivider(start = HomeRowDims.textStart)
                ToolsRow(
                    AnnotatedString(ht("多平台网络测试")), icon = ToolsIcons.Activity,
                    subtitle = ht("并发测试 Google、YouTube、GitHub、Telegram、ChatGPT 等平台"), onClick = onOpenNetworkTest, trailing = { ToolsChevron() },
                )
            }
        }
        HomeCard(Modifier.fillMaxWidth().homeEnter(stagger, 2)) {
            ToolsCardTitle("运行记录")
            ToolsRow(
                AnnotatedString(ht("网络事件记录")), icon = ToolsIcons.Activity,
                subtitle = ht("事件与错误 ID、脱敏报告、运行记录修复与恢复诊断"),
                onClick = onOpenDiagnosticsDetails, trailing = { ToolsChevron() },
            )
        }
        HomeCard(Modifier.fillMaxWidth().homeEnter(stagger, 3)) {
            ToolsCardTitle("紧急")
            ToolsRow(
                AnnotatedString(ht("恢复网络")), icon = ToolsFeatureIcons.Siren, iconTint = c.bad,
                subtitle = ht("停止代理并回滚河图添加的 iptables / 路由规则"), onClick = onRestore, trailing = { ToolsChevron() },
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Sheets                                                              */
/* ------------------------------------------------------------------ */

/** The generated startup copy, YAML-coloured, with 复制. */
@Composable
internal fun ToolsStartupConfigSheetContent(content: ToolsDiagText, onCopy: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    val palette = remember(c) { ToolsYamlPalette(key = c.t1, bool = c.accent, number = c.accent, comment = c.t3, error = c.bad) }
    val annotate = remember(palette) { { text: String -> toolsYamlAnnotated(text, palette) } }
    DiagTextSheet("启动配置", "河图生成的最终 Mihomo 运行副本", content, onCopy,
        modifier.heightIn(min = (LocalConfiguration.current.screenHeightDp * .60f).dp),
        body = { text -> StartupConfigViewer(text, ToolsEditorType.code.copy(fontSize = 14.sp, lineHeight = 21.sp), c.t1,
            Modifier.clip(HomeDims.innerShape).background(if (c.dark) c.sunken else c.bg).padding(horizontal = 16.dp, vertical = 14.dp),
            annotated = annotate) },
    ) { AnnotatedString(it) }
}

/** The plain-text report, with 复制. */
@Composable
internal fun ToolsReportSheetContent(content: ToolsDiagText, onCopy: (String) -> Unit, modifier: Modifier = Modifier) {
    DiagTextSheet("消息与网络诊断", "不采集聊天内容；密钥与完整 URL 已脱敏", content, onCopy, modifier) { AnnotatedString(it) }
}

@Composable
private fun DiagTextSheet(
    title: String,
    subtitle: String,
    content: ToolsDiagText,
    onCopy: (String) -> Unit,
    modifier: Modifier,
    body: (@Composable (String) -> Unit)? = null,
    styled: @Composable (String) -> AnnotatedString,
) {
    val c = LocalHomeColors.current
    val copyable = !content.loading && content.error == null && content.text.isNotBlank()
    var copyError by remember(content) { mutableStateOf(false) }
    fun copy() {
        try {
            onCopy(content.text)
            copyError = false
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { copyError = true }
    }
    ToolsSheetContent(
        title = title, modifier = modifier, subtitle = subtitle,
        trailing = { ToolsButton("复制", ::copy, kind = HomeButtonKind.Soft, icon = HomeIcons.Copy, enabled = copyable, height = 44.dp) },
    ) {
        if (copyError) HomeNotice("复制失败，剪贴板暂不可用", HomeIcons.CircleAlert, tone = HomeTone.Bad)
        when {
            content.loading -> ToolsLoading(cards = 2)
            content.error != null -> HomeNotice(content.error, HomeIcons.CircleAlert, tone = HomeTone.Bad)
            content.text.isBlank() -> Text(ht("暂无内容。"), Modifier.padding(horizontal = 6.dp, vertical = 8.dp), color = c.t2, style = HomeType.note)
            else -> if (body != null) body(content.text) else ToolsCodeBox(styled(content.text))
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Dialog                                                              */
/* ------------------------------------------------------------------ */

@Composable
internal fun ToolsRestoreNetworkDialogCard(onConfirm: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier, loading: Boolean = false) {
    ToolsDialogCard(
        title = "恢复网络？",
        text = "将停止代理，并回滚河图添加的 iptables / 路由规则。用于网络异常时的紧急恢复。",
        confirmLabel = "恢复", confirmKind = ToolsConfirmKind.Danger, confirmLoading = loading,
        icon = ToolsFeatureIcons.Siren, iconTone = HomeTone.Bad,
        onConfirm = onConfirm, onCancel = onCancel, modifier = modifier,
    )
}
