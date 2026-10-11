package io.github.xgl34222220.hetu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeRowDims
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.badText
import io.github.xgl34222220.hetu.home.fill
import io.github.xgl34222220.hetu.home.homeTap
import io.github.xgl34222220.hetu.home.warnText
import io.github.xgl34222220.hetu.ui.ht

/**
 * 测速历史 on the 网速 tab: node delay runs and 网速 runs, newest first. A row expands to its per-node
 * readings; each run can be deleted and the whole history cleared. [version] reloads the list after a
 * new run was recorded on this page.
 */
@Composable
internal fun SpeedTestHistorySection(version: Int) {
    val context = LocalContext.current
    val c = LocalHomeColors.current
    var reload by remember { mutableIntStateOf(0) }
    val runs = remember(version, reload) { SpeedTestHistory.load(context) }
    var open by remember { mutableLongStateOf(0L) }
    var confirmClear by remember { mutableStateOf(false) }
    HxSection(ht("测速历史"), trailing = {
        if (runs.isNotEmpty()) Text(
            ht("清空"), Modifier.homeTap { confirmClear = true }.padding(horizontal = 8.dp, vertical = 4.dp),
            color = c.accent, style = HomeType.noteStrong,
        )
    }) {
        HxGroup {
            if (runs.isEmpty()) {
                Text(
                    ht("还没有测速记录：策略组测速和网速测试完成后会保存在这里"),
                    Modifier.fillMaxWidth().padding(horizontal = HomeRowDims.start, vertical = 14.dp),
                    color = c.t3, style = HomeType.caption,
                )
            }
            runs.forEachIndexed { index, run ->
                if (index > 0) HxDivider(0.dp)
                SpeedHistoryRow(run, expanded = open == run.id, onToggle = { open = if (open == run.id) 0L else run.id }) {
                    SpeedTestHistory.delete(context, run.id)
                    if (open == run.id) open = 0L
                    reload++
                }
            }
        }
        Text(
            ht("最多保留最近 %s 次。节点测速的流量按探测次数估算；网速测试的流量为实测值，未完成或样本过少时标注“估算”。").fill(SpeedTestHistory.MAX_RUNS.toString()),
            Modifier.padding(horizontal = 16.dp).padding(top = 8.dp),
            color = c.t3, style = HomeType.caption,
        )
    }
    if (confirmClear) HxConfirmDialog(
        title = "清空测速历史？",
        message = "所有已保存的测速记录都会删除，无法恢复。",
        confirmLabel = "清空",
        danger = true,
        onConfirm = { SpeedTestHistory.clear(context); open = 0L; reload++; confirmClear = false },
        onDismiss = { confirmClear = false },
    )
}

@Composable
private fun SpeedHistoryRow(run: SpeedRunRecord, expanded: Boolean, onToggle: () -> Unit, onDelete: () -> Unit) {
    val c = LocalHomeColors.current
    Column(
        Modifier.fillMaxWidth().homeTap { onToggle() }.heightIn(min = 54.dp)
            .padding(horizontal = HomeRowDims.start, vertical = 10.dp).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(run.title, Modifier.weight(1f), color = c.t1, style = HomeType.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(SpeedTestHistory.timeText(run.at), color = c.t3, style = HomeType.caption)
        }
        Text(SpeedTestHistory.summary(run), color = c.t2, style = HomeType.caption, maxLines = if (expanded) 3 else 1, overflow = TextOverflow.Ellipsis)
        Text(ht(SpeedTestHistory.trafficText(run)), color = if (run.estimated) c.warnText else c.t3, style = HomeType.caption)
        if (run.hint.isNotBlank()) Text(run.hint, color = if (run.ok == 0 && run.kind == SpeedRunKind.Latency) c.badText else c.t3, style = HomeType.caption)
        if (expanded) {
            run.nodes.sortedWith(compareBy<SpeedNodeSample> { if (it.delayMs > 0) 0 else 1 }.thenBy { if (it.delayMs > 0) it.delayMs else 0L })
                .take(60).forEach { node ->
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(node.name, Modifier.weight(1f), color = c.t2, style = HomeType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            ht(SpeedTestHistory.delayText(node)),
                            color = when { node.delayMs > 0 -> c.t1; node.delayMs == SpeedNodeSample.TIMEOUT -> c.warnText; else -> c.badText },
                            style = HomeType.caption,
                        )
                    }
                }
            if (run.nodes.size > 60) Text(ht("另有 %s 个节点未显示").fill((run.nodes.size - 60).toString()), color = c.t3, style = HomeType.caption)
            Text(
                ht("删除这条记录"), Modifier.padding(top = 6.dp).homeTap { onDelete() }.padding(vertical = 4.dp),
                color = c.badText, style = HomeType.noteStrong,
            )
        }
    }
}
