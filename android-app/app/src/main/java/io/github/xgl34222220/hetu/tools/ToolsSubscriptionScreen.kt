package io.github.xgl34222220.hetu.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.tools.ToolsButton as HomeButton
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.tools.ToolsSurfaceCard as HomeCard
import io.github.xgl34222220.hetu.tools.ToolsDesignDims as HomeDims
import io.github.xgl34222220.hetu.home.HomeIcons

/**
 * 添加订阅 / 编辑订阅 (pushed from 配置与订阅). Stateless form.
 *
 * - Page 5: add, filled in.
 * - Page 21: add, “名称不能为空”.
 * - Page 22: add, “请输入有效的 http/https 链接”.
 * - Page 23: edit, link error. While editing the name is read-only because policy groups
 *   reference it; it can only be changed in the YAML editor.
 *
 * Errors are set by the host on 保存 and should be cleared when the field is edited.
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
    ToolsPage(title = if (adding) "添加订阅" else "编辑订阅", onBack = onBack, modifier = modifier) {
        HomeCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(HomeDims.cardPadding), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                if (adding) {
                    ToolsField("订阅名称", form.name, onNameChange, placeholder = "例如 主订阅", error = form.nameError)
                } else {
                    ToolsField("订阅名称", form.name, {}, readOnly = true, hint = "名称用于策略组引用，可在 YAML 编辑器中统一修改。")
                }
                ToolsField(
                    "订阅链接", form.url, onUrlChange,
                    placeholder = "https://", monospace = true, keyboardType = KeyboardType.Uri,
                    error = form.urlError, hint = "保存到当前配置，运行时会尝试应用。",
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HomeButton("保存", onSave, Modifier.fillMaxWidth(), kind = HomeButtonKind.Primary, icon = HomeIcons.Save, loading = form.saving)
                    HomeButton("取消", onBack, Modifier.fillMaxWidth(), kind = HomeButtonKind.Ghost, enabled = !form.saving)
                }
            }
        }
    }
}

/** “放弃填写？” — shared by the subscription form and the import form (page 12). */
@Composable
internal fun ToolsDiscardFormDialogCard(onConfirm: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    ToolsDialogCard(
        title = "放弃填写？",
        text = "已填写的内容还没有保存，返回会放弃这些修改。",
        confirmLabel = "放弃", confirmKind = ToolsConfirmKind.Danger,
        onConfirm = onConfirm, onCancel = onCancel, modifier = modifier,
    )
}
