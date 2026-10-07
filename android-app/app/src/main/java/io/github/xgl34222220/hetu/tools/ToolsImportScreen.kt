package io.github.xgl34222220.hetu.tools

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.home.HomeCard
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeMotion
import io.github.xgl34222220.hetu.home.HomeRowDivider
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeMotionEnabled
import io.github.xgl34222220.hetu.home.homeEnter
import io.github.xgl34222220.hetu.home.rememberHomeStagger
import io.github.xgl34222220.hetu.ui.ht

/**
 * 导入配置 (pushed from the «+» of 配置与订阅). Stateless form.
 *
 * Two sources behind a sliding tab: a link (address and optional name) or a file the user
 * picks in the system picker. The body cross-fades between them; 取消 and 导入配置 are pinned
 * above the keyboard. A link error unfolds under the 配置链接 field.
 *
 * Leaving with input shows [ToolsDiscardFormDialogCard] (hosted by `ToolsRoute`).
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
    val motion = LocalHomeMotionEnabled.current
    val stagger = rememberHomeStagger()
    ToolsPage(
        title = "导入配置",
        onBack = onBack,
        modifier = modifier,
        footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolsButton("取消", onBack, Modifier.weight(1f), kind = HomeButtonKind.Soft, neutral = true, enabled = !form.importing)
                ToolsButton(
                    "导入配置", onImport, Modifier.weight(1.6f), kind = HomeButtonKind.Primary, icon = ToolsIcons.Download,
                    enabled = form.canImport(pickedFile) || form.importing, loading = form.importing,
                )
            }
        },
    ) {
        ToolsSegmented(
            options = ToolsImportTab.entries.map { it to it.label },
            selected = form.tab,
            onSelect = onTabChange,
            modifier = Modifier.homeEnter(stagger, 0),
            enabled = !form.importing,
            icons = mapOf(ToolsImportTab.File to ToolsIcons.File, ToolsImportTab.Link to ToolsIcons.Link),
        )
        HomeCard(Modifier.fillMaxWidth().homeEnter(stagger, 1)) {
            Column(Modifier.padding(HomeDims.cardPadding), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                AnimatedContent(
                    targetState = form.tab,
                    transitionSpec = {
                        if (motion) fadeIn(HomeMotion.fade(true, 220)) togetherWith fadeOut(HomeMotion.fade(true, 120))
                        else EnterTransition.None togetherWith ExitTransition.None
                    },
                    label = "tools-import-source",
                ) { tab ->
                    if (tab == ToolsImportTab.Link) {
                        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            ToolsField(
                                "配置链接", form.url, onUrlChange,
                                placeholder = "https://", monospace = true, keyboardType = KeyboardType.Uri,
                                error = form.urlError, enabled = !form.importing, clearable = true,
                            )
                            ToolsField("配置名称（可选）", form.name, onNameChange, placeholder = "留空则使用链接中的文件名", enabled = !form.importing, clearable = true)
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(ht("配置文件"), Modifier.padding(horizontal = 2.dp), color = c.t1, style = HomeType.cardLabel)
                            Column(Modifier.fillMaxWidth().clip(ToolsDims.tileShape).background(if (c.dark) c.sunken else c.bg)) {
                                if (pickedFile != null) {
                                    ToolsRow(AnnotatedString(pickedFile.name), icon = ToolsIcons.File, subtitle = pickedFile.detail.ifBlank { null }, subtitleMaxLines = 1)
                                    HomeRowDivider()
                                }
                                ToolsRow(
                                    AnnotatedString(ht(if (pickedFile == null) "选择文件" else "重新选择文件")),
                                    icon = ToolsIcons.Folder, enabled = !form.importing,
                                    onClick = onPickFile, trailing = { ToolsChevron() },
                                )
                            }
                        }
                    }
                }
                // Sentence by sentence, so each one finds its entry in the vocabulary.
                ToolsNote(
                    ht("导入后设为当前配置。支持 UTF-8，最大 4 MiB。") + (if (form.tab == ToolsImportTab.Link) ht("链接需直接返回 YAML 文件。") else "") + ht("同名配置会保留为副本。"),
                    icon = HomeIcons.Info,
                )
            }
        }
    }
}
