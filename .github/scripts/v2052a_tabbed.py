from pathlib import Path

root = Path('.')

build = root / 'android-app/app/build.gradle.kts'
s = build.read_text()
assert 'versionCode = 2051' in s and 'versionName = "0.10.1-v20"' in s
s = s.replace('versionCode = 2051', 'versionCode = 2052', 1)
s = s.replace('versionName = "0.10.1-v20"', 'versionName = "0.10.2-v20"', 1)
build.write_text(s)

p = root / 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/HxTabbedPage.kt'
s = p.read_text()
old = '''            Text(
                title,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 96.dp)
                    .graphicsLayer {
                        alpha = ((collapse - .45f) / .55f).coerceIn(0f, 1f)
                        translationY = (1f - alpha) * 6.dp.toPx()
                    },
                style = MaterialTheme.typography.titleMedium,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
'''
new = '''            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 96.dp)
                    .graphicsLayer {
                        alpha = ((collapse - .45f) / .55f).coerceIn(0f, 1f)
                        translationY = (1f - alpha) * 6.dp.toPx()
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (panelReferenceStyle) Text("示例数据", style = MaterialTheme.typography.labelSmall, color = c.textFaint, maxLines = 1)
            }
'''
assert old in s
s = s.replace(old, new, 1)
old = '''                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = if (panelReferenceStyle) 36.sp else 32.sp, lineHeight = if (panelReferenceStyle) 44.sp else MaterialTheme.typography.headlineMedium.lineHeight, letterSpacing = (-0.6).sp),
                    fontWeight = FontWeight.Bold,
                    color = c.text,
                    maxLines = 1,
                )
'''
new = '''                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = if (panelReferenceStyle) 36.sp else 32.sp, lineHeight = if (panelReferenceStyle) 44.sp else MaterialTheme.typography.headlineMedium.lineHeight, letterSpacing = (-0.6).sp),
                        fontWeight = FontWeight.Bold,
                        color = c.text,
                        maxLines = 1,
                    )
                    if (panelReferenceStyle) Text("示例数据", style = MaterialTheme.typography.labelSmall, color = c.textFaint, modifier = Modifier.padding(bottom = 7.dp), maxLines = 1)
                }
'''
assert old in s
s = s.replace(old, new, 1)
p.write_text(s)
print('V20.52 tabbed header parity applied')
