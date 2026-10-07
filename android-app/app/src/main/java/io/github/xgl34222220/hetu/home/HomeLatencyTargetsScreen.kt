package io.github.xgl34222220.hetu.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.ui.ht

/**
 * 本机直测目标 (full page). Stateless form: the host owns [config] and [feedback].
 *
 * The result line sits in the pinned footer above the two buttons: green after a save or a
 * reset, red when validation fails. Editing any field clears it (the host does that).
 */
@Composable
internal fun HomeLatencyTargetsScreen(
    config: HomeTargetsConfig,
    feedback: HomeTargetsFeedback?,
    onTargetChange: (index: Int, target: HomeTarget) -> Unit,
    onAutoRefreshChange: (seconds: Int) -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalHomeColors.current
    val stagger = rememberHomeStagger()
    HomeSubPage(
        title = "本机直测目标",
        subtitle = "河图进程请求，未指定代理节点",
        onBack = onBack,
        modifier = modifier,
        footer = {
            HomeReveal(feedback != null) {
                when (feedback) {
                    is HomeTargetsFeedback.Invalid -> HomeNotice(ht(feedback.message), HomeIcons.CircleAlert, tone = HomeTone.Bad, filled = false)
                    is HomeTargetsFeedback.Saved, is HomeTargetsFeedback.Restored -> HomeNotice(ht(feedback.message), HomeIcons.CircleCheck, tone = HomeTone.Good, filled = false)
                    null -> Unit
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeButton("恢复默认", onReset, Modifier.weight(1f), icon = HomeIcons.RotateCcw)
                HomeButton("保存", onSave, Modifier.weight(1f), kind = HomeButtonKind.Primary, icon = HomeIcons.Save)
            }
        },
    ) {
        config.targets.forEachIndexed { index, target ->
            HomeCard(Modifier.fillMaxWidth().homeEnter(stagger, index)) {
                Column(Modifier.padding(HomeDims.cardPadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(ht("目标 %d").fill(index + 1), color = c.t2, style = HomeType.cardLabel)
                    HomeTextField("名称", target.name, { onTargetChange(index, target.copy(name = it)) })
                    HomeTextField(
                        "HTTP(S) 测速地址", target.url, { onTargetChange(index, target.copy(url = it)) },
                        keyboardType = KeyboardType.Uri, placeholder = "https://",
                    )
                }
            }
        }
        AutoRefreshRow(config.autoRefreshSeconds, onAutoRefreshChange, Modifier.homeEnter(stagger, config.targets.size))
    }
}

/** “值 + 上下箭头” row; tapping opens an anchored menu with 关闭 / 30 秒 / 60 秒. */
@Composable
private fun AutoRefreshRow(seconds: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    var open by remember { mutableStateOf(false) }
    HomeCard(modifier.fillMaxWidth(), onClick = { open = true }, clickLabel = "选择自动刷新间隔") {
        Row(
            Modifier.fillMaxWidth().heightIn(min = HomeDims.rowMinHeight).padding(horizontal = HomeDims.cardPadding, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(HomeIcons.Timer, null, Modifier.size(24.dp), tint = c.t1)
            Column(Modifier.weight(1f)) {
                Text(ht("自动刷新"), color = c.t1, style = HomeType.rowTitle)
                Text(ht("首页停留时按间隔重新测速"), color = c.t2, style = HomeType.rowSub)
            }
            Box {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HomeRollingText(homeAutoRefreshLabel(seconds), c.t2, HomeType.label)
                    Icon(HomeIcons.ChevronsUpDown, null, Modifier.size(18.dp), tint = c.t3)
                }
                DropdownMenu(
                    expanded = open,
                    onDismissRequest = { open = false },
                    shape = HomeDims.menuShape,
                    containerColor = c.raised,
                    shadowElevation = 10.dp,
                ) {
                    HomeTargets.autoRefreshChoices.forEach { choice ->
                        val selected = choice == seconds
                        DropdownMenuItem(
                            text = { Text(homeAutoRefreshLabel(choice), color = if (selected) c.accent else c.t1, style = HomeType.body) },
                            onClick = { haptics(HomeHaptic.Tick); open = false; onChange(choice) },
                            trailingIcon = if (selected) { { Icon(HomeIcons.Check, null, Modifier.size(20.dp), tint = c.accent) } } else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun homeAutoRefreshLabel(seconds: Int): String = if (seconds <= 0) ht("关闭") else ht("%d 秒").fill(seconds)
