package io.github.xgl34222220.hetu.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * 本机直测目标 (full page). Stateless form: the host owns [config] and [feedback].
 *
 * The feedback strip sits in the fixed footer above the two buttons: green after a save or a
 * reset, red when validation fails. Editing any field should clear it (the host does that).
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
    Column(modifier.fillMaxSize().background(c.bg).imePadding()) {
        HomeTopBar(title = "本机直测目标", subtitle = "河图进程请求，未指定代理节点", onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = HomeDims.gutter, end = HomeDims.gutter, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(HomeDims.gap),
        ) {
            config.targets.forEachIndexed { index, target ->
                HomeCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(HomeDims.cardPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("目标 ${index + 1}", color = c.t2, style = HomeType.section)
                        HomeTextField("名称", target.name, { onTargetChange(index, target.copy(name = it)) })
                        HomeTextField(
                            "HTTP(S) 测速地址", target.url, { onTargetChange(index, target.copy(url = it)) },
                            monospace = true, keyboardType = KeyboardType.Uri, placeholder = "https://",
                        )
                    }
                }
            }
            AutoRefreshRow(config.autoRefreshSeconds, onAutoRefreshChange)
        }
        HomeDivider()
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.bg)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = HomeDims.gutter, end = HomeDims.gutter, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (feedback) {
                is HomeTargetsFeedback.Invalid -> HomeBanner(feedback.message, HomeIcons.CircleAlert, tone = HomeTone.Bad)
                is HomeTargetsFeedback.Saved, is HomeTargetsFeedback.Restored -> HomeBanner(feedback.message, HomeIcons.CircleCheck, tone = HomeTone.Good)
                null -> Unit
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeButton("恢复默认", onReset, Modifier.weight(1f), icon = HomeIcons.RotateCcw)
                HomeButton("保存", onSave, Modifier.weight(1f), kind = HomeButtonKind.Primary, icon = HomeIcons.Save)
            }
        }
    }
}

/** “值 + 上下箭头” row; tapping opens an anchored menu with 关闭 / 30 秒 / 60 秒. */
@Composable
private fun AutoRefreshRow(seconds: Int, onChange: (Int) -> Unit) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    var open by remember { mutableStateOf(false) }
    HomeCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = HomeDims.rowMinHeight)
                .clickable(role = Role.DropdownList) { haptics(HomeHaptic.Tap); open = true }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(HomeIcons.Timer, null, Modifier.size(20.dp), tint = c.t2)
            Column(Modifier.weight(1f)) {
                Text("自动刷新", color = c.t1, style = HomeType.rowTitle)
                Text("首页停留时按间隔重新测速", color = c.t2, style = HomeType.rowSub)
            }
            Box {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(HomeTargets.autoRefreshLabel(seconds), color = c.t2, style = HomeType.label)
                    Icon(HomeIcons.ChevronsUpDown, null, Modifier.size(16.dp), tint = c.t3)
                }
                DropdownMenu(
                    expanded = open,
                    onDismissRequest = { open = false },
                    shape = HomeDims.menuShape,
                    containerColor = c.surface,
                    border = BorderStroke(1.dp, c.line),
                ) {
                    HomeTargets.autoRefreshChoices.forEach { choice ->
                        val selected = choice == seconds
                        DropdownMenuItem(
                            text = { Text(HomeTargets.autoRefreshLabel(choice), color = if (selected) c.accent else c.t1, style = HomeType.body) },
                            onClick = { haptics(HomeHaptic.Tick); open = false; onChange(choice) },
                            trailingIcon = if (selected) { { Icon(HomeIcons.Check, null, Modifier.size(17.dp), tint = c.accent) } } else null,
                        )
                    }
                }
            }
        }
    }
}
