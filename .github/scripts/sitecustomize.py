"""Apply post-V20.60 presentation parity adjustments before export/build.

Python loads this module automatically for scripts launched from .github/scripts.
The guard keeps earlier reconstruction steps untouched and the transform is idempotent.
"""
from pathlib import Path

build = Path("android-app/app/build.gradle.kts")
if build.is_file():
    build_text = build.read_text()
    if 'versionCode = 2060' in build_text:
        build_text = build_text.replace('versionCode = 2060', 'versionCode = 2061', 1)
        build_text = build_text.replace('versionName = "0.11.0-v20"', 'versionName = "0.11.1-v20"', 1)
        build.write_text(build_text)

        about = Path("android-app/app/src/main/java/io/github/xgl34222220/hetu/app/MiscScreens.kt")
        text = about.read_text()
        text = text.replace(
            '''    val revision = remember {
        runCatching { context.assets.open("mihomo-revision.txt").bufferedReader().use { it.readText().trim() } }.getOrDefault("")
    }

''',
            "",
            1,
        )
        text = text.replace(
            'SettingsRow("内置核心", subtitle = revision.ifBlank { "Mihomo" }, icon = Icons.Rounded.Memory)',
            'SettingsRow("内置核心", subtitle = "Mihomo", icon = Icons.Rounded.Memory)',
            1,
        )
        about.write_text(text)

        settings = Path("android-app/app/src/main/java/io/github/xgl34222220/hetu/app/SettingsScreen.kt")
        text = settings.read_text()
        text = text.replace(
            'else Modifier.border(1.dp, c.line, RoundedCornerShape(12.dp))',
            'else Modifier',
            1,
        )
        text = text.replace(
            '.background(if (selected) c.accentSoft else Color.Transparent)',
            '.background(if (selected) c.accentSoft else c.surfaceMuted.copy(alpha = .42f))',
            1,
        )
        settings.write_text(text)

        assert "示例数据" not in about.read_text()
        assert "示例数据" not in settings.read_text()
