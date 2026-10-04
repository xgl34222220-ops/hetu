package io.github.xgl34222220.hetu.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeBanner
import io.github.xgl34222220.hetu.home.HomeButton
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.home.HomeCard
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeDivider
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeSheetContent
import io.github.xgl34222220.hetu.home.HomeSpinner
import io.github.xgl34222220.hetu.home.HomeTone
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors

/**
 * 诊断与维护 (工具 › 诊断工具). Stateless.
 *
 * - Page 40: 运行预检 (the result stays in the card as a green or red strip), 查看, 紧急.
 * - Pages 41 and 43 (sheets) and 42 (dialog) are hosted by the route; their contents are below.
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
) {
    val c = LocalHomeColors.current
    val checking = state.preflight is ToolsPreflight.Running
    ToolsPage(title = "诊断与维护", onBack = onBack, modifier = modifier, subtitle = "预检、运行副本、诊断信息与紧急恢复") {
        Column(verticalArrangement = Arrangement.spacedBy(HomeDims.gap)) {
            HomeCard(Modifier.fillMaxWidth()) {
                ToolsCardTitle("运行预检")
                ToolsRow(
                    AnnotatedString("开始预检"), icon = ToolsFeatureIcons.ShieldPlus, iconTint = c.t1,
                    subtitle = "验证 Root / TPROXY / UID / IPv6 / 绕过规则是否可用",
                    enabled = !checking, onClick = onPreflight,
                    trailing = { if (checking) HomeSpinner(size = 16.dp, color = c.accent) else ToolsChevron() },
                )
                when (val result = state.preflight) {
                    is ToolsPreflight.Passed -> HomeBanner(result.message, HomeIcons.CircleCheck, Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), tone = HomeTone.Good)
                    is ToolsPreflight.Failed -> HomeBanner(result.message, HomeIcons.CircleAlert, Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), tone = HomeTone.Bad)
                    ToolsPreflight.Idle, ToolsPreflight.Running -> Unit
                }
            }
            HomeCard(Modifier.fillMaxWidth()) {
                ToolsCardTitle("查看")
                ToolsRow(
                    AnnotatedString("启动配置"), icon = ToolsIcons.FileText, iconTint = c.t1,
                    subtitle = "最终生成的运行副本，不修改源配置", onClick = onStartupConfig, trailing = { ToolsChevron() },
                )
                HomeDivider(Modifier.padding(horizontal = 16.dp))
                ToolsRow(
                    AnnotatedString("消息与网络诊断"), icon = ToolsFeatureIcons.Router, iconTint = c.t1,
                    subtitle = "Google / 微信连接、分流与最近运行事件", onClick = onReport, trailing = { ToolsChevron() },
                )
            }
            HomeCard(Modifier.fillMaxWidth()) {
                ToolsCardTitle("紧急")
                ToolsRow(
                    AnnotatedString("恢复网络"), icon = ToolsFeatureIcons.Siren, iconTint = c.bad,
                    subtitle = "停止代理并回滚河图添加的 iptables / 路由规则", onClick = onRestore, trailing = { ToolsChevron() },
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Sheets (pages 41, 43)                                               */
/* ------------------------------------------------------------------ */

/** Page 41: the generated startup copy, YAML-coloured, with 复制. */
@Composable
internal fun ToolsStartupConfigSheetContent(content: ToolsDiagText, onCopy: (String) -> Unit, modifier: Modifier = Modifier) {
    val palette = toolsYamlPalette()
    DiagTextSheet("启动配置", "河图生成的最终 Mihomo 运行副本", content, onCopy, modifier) { text ->
        remember(text, palette) { toolsYamlAnnotated(text, palette) }
    }
}

/** Page 43: the plain-text report, with 复制. */
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
    styled: @Composable (String) -> AnnotatedString,
) {
    val c = LocalHomeColors.current
    val copyable = !content.loading && content.error == null && content.text.isNotBlank()
    HomeSheetContent(
        title = title, modifier = modifier, subtitle = subtitle,
        trailing = { HomeButton("复制", { onCopy(content.text) }, kind = HomeButtonKind.Soft, icon = HomeIcons.Copy, enabled = copyable) },
    ) {
        when {
            content.loading -> ToolsLoading()
            content.error != null -> HomeBanner(content.error, HomeIcons.CircleAlert, tone = HomeTone.Bad)
            content.text.isBlank() -> Text("暂无内容。", Modifier.padding(horizontal = 4.dp, vertical = 8.dp), color = c.t2, style = HomeType.note)
            else -> ToolsCodeBox(styled(content.text))
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Dialog (page 42)                                                    */
/* ------------------------------------------------------------------ */

@Composable
internal fun ToolsRestoreNetworkDialogCard(onConfirm: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier, loading: Boolean = false) {
    ToolsDialogCard(
        title = "恢复网络？",
        text = "将停止代理，并回滚河图添加的 iptables / 路由规则。用于网络异常时的紧急恢复。",
        confirmLabel = "恢复", confirmKind = ToolsConfirmKind.Danger, confirmLoading = loading,
        onConfirm = onConfirm, onCancel = onCancel, modifier = modifier,
    )
}
