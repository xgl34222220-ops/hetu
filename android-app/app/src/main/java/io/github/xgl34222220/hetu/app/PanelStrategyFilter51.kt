package io.github.xgl34222220.hetu

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun StrategyFilterMenu(
    options: PanelOptions11,
    onChange: (PanelOptions11) -> Unit,
    onDismiss: () -> Unit,
) {
    HxSheet(onDismiss = onDismiss, title = "策略筛选") {
        Column(Modifier.padding(horizontal = 12.dp)) {
            StrategyFilterRows(options, onChange)
        }
    }
}

@Composable
private fun StrategyFilterRows(options: PanelOptions11, onChange: (PanelOptions11) -> Unit) {
    StrategyFilterToggle("显示隐藏策略", options.showHidden) { onChange(options.copy(showHidden = it)) }
    StrategyFilterToggle("根据模式显示 GLOBAL", options.globalByMode) { onChange(options.copy(globalByMode = it)) }
    StrategyFilterToggle("按订阅分组节点", options.providers) { onChange(options.copy(providers = it)) }
    StrategyFilterToggle("展开新策略时折叠上一个", options.collapsePrevious) { onChange(options.copy(collapsePrevious = it)) }
}

@Composable
private fun StrategyFilterToggle(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    val c = Hx.colors
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = source, indication = LocalIndication.current) { onChecked(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .background(if (checked) c.accent else Color.Transparent, RoundedCornerShape(5.dp))
                .border(1.dp, if (checked) c.accent else c.line, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Rounded.Check, null, tint = c.onAccent, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 1)
    }
}
