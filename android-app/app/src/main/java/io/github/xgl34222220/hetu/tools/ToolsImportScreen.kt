package io.github.xgl34222220.hetu.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.tools.ToolsButton as HomeButton
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.tools.ToolsSurfaceCard as HomeCard
import io.github.xgl34222220.hetu.tools.ToolsDesignDims as HomeDims
import io.github.xgl34222220.hetu.tools.ToolsHairline as HomeDivider
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.tools.ToolsSegmented as HomeSegmented
import io.github.xgl34222220.hetu.tools.ToolsTypography as HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors

/**
 * 导入配置 (pushed from the «+» of 配置与订阅). Stateless form.
 *
 * - Page 24: 从链接导入, filled in.
 * - Page 20: link error under the 配置链接 field.
 * - Page 12: leaving with input shows [ToolsDiscardFormDialogCard] (hosted by `ToolsRoute`).
 *
 * The 从文件导入 segment is tappable on all three pages, so its body is here as well; the
 * concept page for it (27) belongs to Part 2 and gets its preview there.
 */
@Composable
internal fun ToolsImportScreen(
    form: ToolsImportForm,
    pickedFile: ToolsPickedFile?,
    onTabChange: (ToolsImportTab) -> Unit,
    onUrlChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onPickFile: () -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalHomeColors.current
    val link = form.tab == ToolsImportTab.Link
    ToolsPage(title = "导入配置", onBack = onBack, modifier = modifier) {
        HomeSegmented(
            options = ToolsImportTab.entries.map { it to it.label },
            selected = form.tab,
            onSelect = onTabChange,
            enabled = !form.importing,
        )
        Spacer(Modifier.height(16.dp))
        HomeCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(HomeDims.cardPadding), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                if (link) {
                    ToolsField(
                        "配置链接", form.url, onUrlChange,
                        placeholder = "https://", monospace = true, keyboardType = KeyboardType.Uri, error = form.urlError,
                    )
                    ToolsField("配置名称（可选）", form.name, onNameChange, placeholder = "留空则使用链接中的文件名")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("配置文件", Modifier.padding(horizontal = 2.dp), color = c.t2, style = HomeType.section)
                        HomeCard(Modifier.fillMaxWidth(), background = c.bg) {
                            if (pickedFile != null) {
                                ToolsRow(AnnotatedString(pickedFile.name), icon = ToolsIcons.File, subtitle = pickedFile.detail.ifBlank { null })
                                HomeDivider()
                            }
                            ToolsRow(
                                AnnotatedString(if (pickedFile == null) "选择文件" else "重新选择文件"),
                                icon = ToolsIcons.Folder, compact = true, enabled = !form.importing,
                                onClick = onPickFile, trailing = { ToolsChevron() },
                            )
                        }
                    }
                }
                ToolsNote(
                    "导入后设为当前配置。支持 UTF-8，最大 4 MiB。" + (if (link) "链接需直接返回 YAML 文件。" else "") + "同名配置会保留为副本。",
                    icon = HomeIcons.Info,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeButton(
                "导入配置", onImport, Modifier.fillMaxWidth(), kind = HomeButtonKind.Primary, icon = ToolsIcons.Download,
                enabled = form.canImport(pickedFile) || form.importing, loading = form.importing,
            )
            HomeButton("取消", onBack, Modifier.fillMaxWidth(), kind = HomeButtonKind.Ghost, enabled = !form.importing)
        }
    }
}
