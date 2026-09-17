from pathlib import Path

checks = {
    Path('android-app/app/build.gradle.kts'): [
        'versionCode = 450',
        'versionName = "0.4.0-test.50"',
    ],
    Path('android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt'): [
        'groupArrow',
        'Color(0xFFE7EDF4)',
        'modifier.height(62.dp)',
        '连接协议',
        '分流命中',
        'closeConnectionPress',
        'thickness = .5.dp',
        'rules.chunked(18)',
    ],
    Path('android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyAdblockChainActivity.kt'): [
        'Color(0xFFF8FAFC)',
        'private fun ChainMetric',
    ],
    Path('android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxySubscriptionActivity.kt'): [
        'BasicTextField',
        'lineCount = remember(yamlText)',
        r"yamlText.count { it == '\n' }",
        r'joinToString("\n")',
        'Color(0xFFF8FAFC)',
    ],
    Path('android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyAdvancedSettingsActivity.kt'): [
        'Root 数据面 · 修改后重启代理生效',
        'maxLines = 1',
        'overflow = TextOverflow.Ellipsis',
    ],
}

missing = []
for path, tokens in checks.items():
    text = path.read_text()
    for token in tokens:
        if token not in text:
            missing.append(f'{path}: {token!r}')

if missing:
    raise SystemExit('Missing test50 source tokens:\n' + '\n'.join(missing))

print('test50 source verification passed')
