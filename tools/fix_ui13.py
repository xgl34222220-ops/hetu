#!/usr/bin/env python3
"""UI13 regression follow-up: correct semantics and retain every failing scenario."""
from pathlib import Path
import hashlib
import json

ROOT = 'android-app/app/src/main/java/io/github/xgl34222220/hetu/'
TEST = 'android-app/app/src/test/java/io/github/xgl34222220/hetu/'
contract_path = Path('tools/ui13_contract.json')
contract = json.loads(contract_path.read_text())
changed = []

def edit(path, replacements):
    p = Path(path)
    original = p.read_bytes()
    assert hashlib.sha256(original).hexdigest() == contract['after'][path], path
    source = original.decode()
    for old, new in replacements:
        assert source.count(old) == 1, (path, old)
        source = source.replace(old, new, 1)
    p.write_text(source)
    changed.append(path)

# Text is a child of the clickable tab. Inspect merged semantics for tab text,
# while keeping unmerged geometry/gesture selectors for all other components.
edit(TEST + 'PanelNavigation13Test.kt', [
    ('private fun node(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true)',
     'private fun node(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = !tag.startsWith("panel11-tab-"))'),
    ('node("panel11-tab-${tab.name}").assertTextEquals(labels[i])',
     'node("panel11-tab-${tab.name}").assertTextEquals(labels[i])\n                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))')
])

# The outer role must take precedence over nativePress's generic Button role.
# All visible labels, exact positions, selection state, and 48dp hits stay intact.
edit(ROOT + 'PanelNavigation13.kt', [
    ('.nativePress(label = tab.label, onClick = { if (!active) change(tab) })\n                        .testTag("panel11-tab-${tab.name}").semantics { this.selected = active; role = Role.Tab },',
     '.semantics { this.selected = active; role = Role.Tab }\n                        .nativePress(label = tab.label, onClick = { if (!active) change(tab) })\n                        .testTag("panel11-tab-${tab.name}"),')
])

# onClickLabel is not contentDescription. Give the independent child-strategy
# target its own accessible name; touch tests still inject real coordinates.
edit(ROOT + 'PanelStrategy11.kt', [
    ('.nativePress(label = "展开子策略${node.name}", onClick = onNested), contentAlignment = Alignment.Center)',
     '.semantics { contentDescription = "展开子策略${node.name}" }\n                .nativePress(label = "展开子策略${node.name}", onClick = onNested), contentAlignment = Alignment.Center)')
])

# Only the explicitly reviewed follow-up files receive new expected digests.
# Protected runtime, homepage, dependency, old test counts and assertions remain.
for path in changed:
    contract['after'][path] = hashlib.sha256(Path(path).read_bytes()).hexdigest()
contract_path.write_text(json.dumps(contract, ensure_ascii=False, indent=2))

p = Path('tools/finish_ui13.py')
s = p.read_text()
old = '## 验证\n"""'
new = '''## 失败记录与修正
首次运行36439433971生产编译成功，219项中215通过、4失败；未发布安装包。三项Tab文字断言误读未合并父节点，改为读取真实合并文本并保留六路由、顺序、坐标与选中态的严格检查；新增Tab角色断言。另一个子策略操作只有onClickLabel，现补齐独立无障碍名称，原真实触摸/返回栈场景继续执行。没有删除、跳过或改为空回调。

## 验证
"""'''
assert s.count(old) == 1
p.write_text(s.replace(old, new, 1))
print('UI13 follow-up applied to exact reviewed inputs:', changed)
