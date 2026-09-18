from pathlib import Path

path = Path('android-app/app/src/main/java/io/github/xgl34222220/hetu/ReferenceProxyActivity.kt')
s = path.read_text()

old = '''        Box(
            Modifier.fillMaxSize()
                .then(if (!liquid) Modifier.hazeSource(haze) else Modifier)
                .then(if (liquid) Modifier.layerBackdrop(liquidBackdrop) else Modifier),
        ) {
            key(page) {
'''
new = '''        Box(
            Modifier.fillMaxSize()
                .then(if (!liquid) Modifier.hazeSource(haze) else Modifier)
                .then(if (liquid) Modifier.layerBackdrop(liquidBackdrop) else Modifier),
        ) {
            // Match LuoShu's backdrop architecture: the full-screen page backdrop must live
            // INSIDE the layerBackdrop source. Keeping it only on the parent leaves transparent
            // pixels near the Home tail, which the RuntimeShader can stretch into a white strip.
            Box(Modifier.matchParentSize().background(shellBackground))
            key(page) {
'''

if old not in s:
    if 'Match LuoShu\'s backdrop architecture' in s:
        print('backdrop source fix already applied')
    else:
        raise SystemExit('expected proxy backdrop source block not found')
else:
    s = s.replace(old, new, 1)
    path.write_text(s)
    print('moved full-screen proxy backdrop inside layerBackdrop source')
