from pathlib import Path
import runpy

runpy.run_path(".github/scripts/v2053b_configs_impl.py", run_name="__main__")
runpy.run_path(".github/scripts/v2053_fix_imports.py", run_name="__main__")

build = Path("android-app/app/build.gradle.kts")
s = build.read_text()
assert 'versionCode = 2054' in s and 'versionName = "0.10.4-v20"' in s
s = s.replace('versionCode = 2054', 'versionCode = 2053', 1)
s = s.replace('versionName = "0.10.4-v20"', 'versionName = "0.10.3-v20"', 1)
build.write_text(s)

runpy.run_path(".github/scripts/v2054_settings_pages.py", run_name="__main__")
runpy.run_path(".github/scripts/v2055_theme.py", run_name="__main__")
