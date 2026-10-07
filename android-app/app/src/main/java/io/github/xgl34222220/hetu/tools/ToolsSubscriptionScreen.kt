package io.github.xgl34222220.hetu.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.home.HomeCard
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeTone
import io.github.xgl34222220.hetu.home.homeEnter
import io.github.xgl34222220.hetu.home.rememberHomeStagger

/**
 * 添加订阅 / 编辑订阅 (pushed from 配置与订阅). Stateless form.
 *
 * One card with the two fields; 取消 and 保存 are pinned above the keyboard so they stay in
 * reach while typing. Errors unfold under their field and are set by the host on 保存; the
 * host clears them when the field is edited. While editing, the name is read-only because
 * policy groups reference it; it can only be changed in the YAML editor.
 *
 * Leaving with unsaved input shows [ToolsDiscardFormDialogCard] (hosted by `ToolsRoute`).
 */
@Composable
internal fun ToolsSubscriptionScreen(
    form: ToolsSubscriptionForm,
    onNameChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val adding = form.mode is ToolsSubscriptionMode.Add
    val stagger = rememberHomeStagger()
    ToolsPage(
        title = if (adding) "添加订阅" else "编辑订阅",
        onBack = onBack,
        modifier = modifier,
        footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolsButton("取消", onBack, Modifier.weight(1f), kind = HomeButtonKind.Soft, neutral = true, enabled = !form.saving)
                ToolsButton("保存", onSave, Modifier.weight(1.6f), kind = HomeButtonKind.Primary, icon = HomeIcons.Save, loading = form.saving)
            }
        },
    ) {
        HomeCard(Modifier.fillMaxWidth().homeEnter(stagger, 0)) {
            Column(Modifier.padding(HomeDims.cardPadding), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                if (adding) {
                    ToolsField("订阅名称", form.name, onNameChange, placeholder = "例如 主订阅", error = form.nameError, enabled = !form.saving, clearable = true)
                } else {
                    ToolsField("订阅名称", form.name, {}, readOnly = true, hint = "名称用于策略组引用，可在 YAML 编辑器中统一修改。")
                }
                ToolsField(
                    "订阅链接", form.url, onUrlChange,
                    placeholder = "https://", monospace = true, keyboardType = KeyboardType.Uri,
                    error = form.urlError, hint = "保存到当前配置，运行时会尝试应用。", enabled = !form.saving, clearable = true,
                )
            }
        }
    }
}

/** “放弃填写？” — shared by the subscription form and the import form. */
@Composable
internal fun ToolsDiscardFormDialogCard(onConfirm: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    ToolsDialogCard(
        title = "放弃填写？",
        text = "已填写的内容还没有保存，返回会放弃这些修改。",
        confirmLabel = "放弃", confirmKind = ToolsConfirmKind.DangerSoft,
        icon = HomeIcons.TriangleAlert, iconTone = HomeTone.Warn,
        onConfirm = onConfirm, onCancel = onCancel, modifier = modifier,
    )
}
