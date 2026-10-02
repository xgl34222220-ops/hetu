package io.github.xgl34222220.hetu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun StrategyLayoutSheet(
    vm: HetuViewModel,
    options: PanelOptions11,
    onDismiss: () -> Unit,
    onOpenApi: () -> Unit,
) {
    val c = Hx.colors
    HxSheet(onDismiss = onDismiss) {
        val close = LocalHxSheetClose.current
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "排序与布局",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = c.text,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(40.dp).clickable { close(onDismiss) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Close, "关闭", tint = c.textMuted, modifier = Modifier.size(21.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(18.dp), color = c.surfaceMuted) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PanelLayoutSegment(
                        label = "排序方式",
                        options = listOf("config" to "按配置", "name" to "名称", "latency" to "延迟"),
                        selected = options.sort,
                    ) { options.copy(sort = it).save(vm.prefs) }
                    PanelLayoutSwitch("倒序", options.descending) {
                        options.copy(descending = it).save(vm.prefs)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Surface(shape = RoundedCornerShape(18.dp), color = c.surfaceMuted) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PanelLayoutSegment(
                        "策略列数",
                        listOf("1" to "1 列", "2" to "2 列"),
                        options.groupColumns.toString(),
                    ) {
                        options.copy(groupColumns = it.toIntOrNull()?.coerceIn(1, 2) ?: 2).save(vm.prefs)
                    }
                    PanelLayoutSwitch("紧凑策略卡", options.groupCompact) {
                        options.copy(groupCompact = it).save(vm.prefs)
                    }
                    PanelLayoutSegment(
                        "节点列数",
                        listOf("1" to "1 列", "2" to "2 列"),
                        options.columns.toString(),
                    ) {
                        options.copy(columns = it.toIntOrNull()?.coerceIn(1, 2) ?: 2).save(vm.prefs)
                    }
                    PanelLayoutSwitch("紧凑节点卡", options.compact) {
                        options.copy(compact = it).save(vm.prefs)
                    }
                    PanelLayoutSegment(
                        "名称显示",
                        listOf("clip" to "单行截断", "wrap" to "自动换行"),
                        options.nameOverflow,
                    ) {
                        options.copy(nameOverflow = it).save(vm.prefs)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { close(onOpenApi) },
                shape = RoundedCornerShape(18.dp),
                color = c.surfaceMuted,
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Tune, null, tint = c.textMuted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "测速与 API",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = c.text,
                        )
                        Text(
                            "设置测速引擎与 API 相关选项",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textMuted,
                        )
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = c.textFaint, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun PanelLayoutSegment(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val c = Hx.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = c.text,
            modifier = Modifier.width(116.dp),
        )
        HxSegmented(
            options = options,
            selected = selected,
            onSelect = onSelect,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PanelLayoutSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Hx.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = c.text,
            modifier = Modifier.weight(1f),
        )
        HxSwitch(checked = checked, onChange = onChange)
    }
}
