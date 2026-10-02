from pathlib import Path

root = Path('.')
p = root / 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/RulesScreen.kt'
s = p.read_text()

s = s.replace(
    'HxEmpty(Icons.Rounded.Rule, "代理未运行", "规则、规则集和订阅由运行中的 Mihomo 提供，启动后可在此更新")',
    'HxEmpty(Icons.Rounded.Rule, "代理未运行", "启动代理后可查看策略与节点")',
    1,
)
s = s.replace(
    'item(key = "rules-empty") { HxEmpty(Icons.Rounded.Rule, if (vm.rules.isEmpty()) "没有规则" else "没有匹配的规则") }',
    'item(key = "rules-empty") { HxEmpty(Icons.Rounded.SearchOff, if (vm.rules.isEmpty()) "没有规则" else "没有匹配的规则", if (query.isBlank()) null else "尝试其他关键词") }',
    1,
)

# Reference cards use tighter 14-16dp corners.
s = s.replace('    val shape = RoundedCornerShape(22.dp)\n', '    val shape = RoundedCornerShape(16.dp)\n', 2)

old = '''        AnimatedVisibility(
            visible = task != null && task.ok == false && task.message.isNotBlank(),
            enter = fadeIn(tween(HxMotion.Medium)) + expandVertically(tween(HxMotion.Medium, easing = HxMotion.Emphasized)),
            exit = fadeOut(tween(HxMotion.Short)) + shrinkVertically(tween(HxMotion.Short)),
        ) {
            Text(task?.message.orEmpty(), style = MaterialTheme.typography.bodySmall, color = c.bad, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
        }
'''
new = '''        AnimatedVisibility(
            visible = task != null && task.ok == false && task.message.isNotBlank(),
            enter = fadeIn(tween(HxMotion.Medium)) + expandVertically(tween(HxMotion.Medium, easing = HxMotion.Emphasized)),
            exit = fadeOut(tween(HxMotion.Short)) + shrinkVertically(tween(HxMotion.Short)),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("更新失败：${task?.message.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = c.bad, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("重试", style = MaterialTheme.typography.labelLarge, color = c.accent, modifier = Modifier.clip(Hx.pillShape).clickable { onUpdate() }.padding(horizontal = 9.dp, vertical = 4.dp))
            }
        }
'''
assert old in s
s = s.replace(old, new, 1)

old = '''        AnimatedVisibility(
            visible = task != null && task.ok == false && task.message.isNotBlank(),
            enter = fadeIn(tween(HxMotion.Medium)) + expandVertically(tween(HxMotion.Medium, easing = HxMotion.Emphasized)),
            exit = fadeOut(tween(HxMotion.Short)) + shrinkVertically(tween(HxMotion.Short)),
        ) {
            Text(task?.message.orEmpty(), style = MaterialTheme.typography.bodySmall, color = c.bad, maxLines = 2, modifier = Modifier.padding(top = 8.dp))
        }
'''
new = '''        AnimatedVisibility(
            visible = task != null && task.ok == false && task.message.isNotBlank(),
            enter = fadeIn(tween(HxMotion.Medium)) + expandVertically(tween(HxMotion.Medium, easing = HxMotion.Emphasized)),
            exit = fadeOut(tween(HxMotion.Short)) + shrinkVertically(tween(HxMotion.Short)),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("更新失败：${task?.message.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = c.bad, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("重试", style = MaterialTheme.typography.labelLarge, color = c.accent, modifier = Modifier.clip(Hx.pillShape).clickable { onUpdate() }.padding(horizontal = 9.dp, vertical = 4.dp))
            }
        }
'''
assert old in s
s = s.replace(old, new, 1)

s = s.replace('    val shape = RoundedCornerShape(22.dp)\n    val proxyColor', '    val shape = RoundedCornerShape(14.dp)\n    val proxyColor', 1)
s = s.replace('.padding(bottom = 9.dp)\n            .hxSoftShadow(shape, 2.dp)', '.padding(bottom = 6.dp)', 1)
s = s.replace('.padding(horizontal = 16.dp, vertical = 13.dp),', '.padding(horizontal = 14.dp, vertical = 9.dp),', 1)

p.write_text(s)
print('V20.52 rules/providers parity applied')
