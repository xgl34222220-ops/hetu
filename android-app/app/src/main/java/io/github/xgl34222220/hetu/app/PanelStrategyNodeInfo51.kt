package io.github.xgl34222220.hetu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
internal fun StrategyNodeInfoSheet(
    group: ProxyGroupUi,
    node: ProxyNodeUi,
    onDismiss: () -> Unit,
) {
    val c = Hx.colors
    HxSheet(onDismiss = onDismiss) {
        val close = LocalHxSheetClose.current
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "节点信息",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = c.text,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(38.dp).clickable { close(onDismiss) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Close, "关闭", tint = c.textMuted, modifier = Modifier.size(20.dp))
                }
            }
            HorizontalDivider(thickness = .5.dp, color = c.line)
            Spacer(Modifier.height(14.dp))
            Text(
                node.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = c.text,
            )
            Spacer(Modifier.height(8.dp))
            StrategyNodeInfoLine("协议", node.type.uppercase(Locale.ROOT).ifBlank { "—" })
            StrategyNodeInfoLine("提供商", node.provider.ifBlank { "配置内节点" })
            StrategyNodeInfoLine("支持", if (node.udp) "UDP" else "—")
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StrategyNodeInfoLine(label: String, value: String) {
    val c = Hx.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = c.textMuted,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = c.text,
        )
    }
}
