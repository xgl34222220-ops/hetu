#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import re
import shutil

ROOT = Path(__file__).resolve().parents[1]
SELF = Path(__file__).resolve()

TEXT_EXTS = {
    ".java", ".kt", ".kts", ".xml", ".gradle", ".properties", ".pro",
    ".sh", ".awk", ".py", ".md", ".txt", ".json", ".tsv", ".yml",
    ".yaml", ".c", ".h", ".go"
}
SKIP_PARTS = {".git", ".gradle", "build", "out", ".upstream"}
WORKFLOWS = ROOT / ".github/workflows"

def under_workflows(path: Path) -> bool:
    try:
        path.relative_to(WORKFLOWS)
        return True
    except ValueError:
        return False

def is_text_file(path: Path) -> bool:
    if path == SELF or under_workflows(path):
        return False
    if any(part in SKIP_PARTS for part in path.parts):
        return False
    return path.is_file() and path.suffix.lower() in TEXT_EXTS

def migrate_rule_assets() -> None:
    module = ROOT / "module"
    assets = ROOT / "android-app/app/src/main/assets"
    rules_dst = assets / "rules"
    rules_dst.mkdir(parents=True, exist_ok=True)

    source = module / "sources.tsv"
    if source.exists():
        shutil.copy2(source, assets / "sources.tsv")

    for name in ("adaway.txt", "china.txt", "tracking.txt", "hagezi.txt", "checksums.sha256", "snapshot.json"):
        src = module / "rules" / name
        if src.exists():
            shutil.copy2(src, rules_dst / name)

    notices = module / "THIRD_PARTY_NOTICES.md"
    if notices.exists():
        docs = ROOT / "docs"
        docs.mkdir(exist_ok=True)
        shutil.copy2(notices, docs / "THIRD_PARTY_NOTICES.md")

def transform_text(text: str) -> str:
    # Collapse the old nested Root runtime directory before the generic brand rename.
    text = text.replace("/data/adb/bichen/proxy", "/data/adb/hetu")
    text = text.replace("/data/adb/bichen", "/data/adb/hetu")
    text = text.replace("io.github.xgl34222220.bichen", "io.github.xgl34222220.hetu")
    text = text.replace("BICHEN_", "HETU_")
    text = text.replace("Bichen", "Hetu")
    text = text.replace("bichen", "hetu")
    text = text.replace("辟尘", "河图")

    # Defensive collapse for paths assembled/rewritten in a different order.
    text = text.replace("/data/adb/hetu/proxy", "/data/adb/hetu")

    # App-private runtime/cache directories should no longer be named proxy either.
    text = text.replace('"proxy/run/state/startup-config"', '"hetu/run/state/startup-config"')
    text = text.replace('"proxy-root-stage"', '"hetu-root-stage"')
    return text

def rewrite_gradle() -> None:
    path = ROOT / "android-app/app/build.gradle.kts"
    text = path.read_text(encoding="utf-8")
    text = re.sub(r'^import org\.gradle\.api\.tasks\.Sync\n+', '', text, flags=re.M)
    text = re.sub(
        r'val generatedRuleAssets = .*?\nval syncHetuRuleAssets = tasks\.register<Sync>\("syncHetuRuleAssets"\) \{.*?\n\}\n\n',
        '',
        text,
        flags=re.S,
    )
    text = re.sub(r'\n    sourceSets \{.*?\n    \}\n', '\n', text, flags=re.S)
    text = re.sub(
        r'\ntasks\.named\("preBuild"\)\.configure \{\n    dependsOn\(syncHetuRuleAssets\)\n\}\n',
        '\n',
        text,
    )
    path.write_text(text, encoding="utf-8")

def rewrite_manifest() -> None:
    path = ROOT / "android-app/app/src/main/AndroidManifest.xml"
    text = path.read_text(encoding="utf-8")
    text = text.replace(' android:name=".HetuApplication"', '')
    text = re.sub(
        r'\n\s*<activity android:name="\.MainActivity"[^>]*/>',
        '',
        text,
    )
    path.write_text(text, encoding="utf-8")

def remove_legacy_module_code() -> None:
    java = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/hetu"
    for name in ("MainActivity.java", "HetuApplication.java", "ModuleInstaller.java", "ModuleArchive.java"):
        p = java / name
        if p.exists():
            p.unlink()

    for rel in (
        "tests/ModuleArchiveTest.java",
        "tests/module_engine_test.py",
        "docs/MODULE_CHANGES.md",
    ):
        p = ROOT / rel
        if p.exists():
            p.unlink()

    module = ROOT / "module"
    if module.exists():
        shutil.rmtree(module)

    downloads = ROOT / "downloads"
    if downloads.exists():
        shutil.rmtree(downloads)

def rename_brand_paths() -> None:
    paths = [
        p for p in ROOT.rglob("*")
        if not any(part in SKIP_PARTS for part in p.parts) and not under_workflows(p)
    ]
    for path in sorted(paths, key=lambda p: len(p.parts), reverse=True):
        if not path.exists():
            continue
        name = path.name.replace("Bichen", "Hetu").replace("bichen", "hetu")
        if name != path.name:
            target = path.with_name(name)
            if target.exists():
                raise RuntimeError(f"rename collision: {path} -> {target}")
            path.rename(target)

def rewrite_readme() -> None:
    readme = ROOT / "README.md"
    readme.write_text(
        """# 河图 Hetu

河图是一个 Root Android 网络管理 App，负责 Mihomo 核心部署、透明代理、规则、订阅、去广告串联和运行状态管理。

## 当前架构

- Android 包名：`io.github.xgl34222220.hetu`
- Root 运行根目录：`/data/adb/hetu`
- 核心、配置、运行状态、规则和日志都由 App 直接部署和维护。
- 不再要求安装独立 Magisk / KernelSU / APatch 模块。
- 开机恢复由 App 的 `BootReceiver` 与 Root 控制层完成。
- Mihomo 配置中的 `proxies`、`proxy-groups` 等标准字段保持原义，不做品牌化改名。

## 运行目录

```text
/data/adb/hetu/
├── bin/
├── run/
│   ├── state/
│   └── ruleset/
└── proxy-root.sh
```

其中 `proxy-root.sh` 是透明代理控制脚本文件名，不再代表运行目录；运行目录本身不再包含旧的 `/proxy` 层级。

## 规则资产

规则源与内置规则已迁入 APK 的 `assets/`，构建不再从独立模块目录复制文件。

## 构建

```sh
cd android-app
gradle :app:assembleDebug --stacktrace
```

JNI 桥接与 Android 源码命名空间均使用 `io.github.xgl34222220.hetu`。原生库名为 `libhetu_core.so`。
""",
        encoding="utf-8",
    )

def main() -> None:
    migrate_rule_assets()

    for path in list(ROOT.rglob("*")):
        if not is_text_file(path):
            continue
        try:
            old = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        new = transform_text(old)
        if new != old:
            path.write_text(new, encoding="utf-8")

    rename_brand_paths()
    rewrite_gradle()
    rewrite_manifest()
    remove_legacy_module_code()
    rewrite_readme()

    marker = ROOT / ".hetu-migration-complete"
    marker.write_text(
        "Hetu rebrand: package io.github.xgl34222220.hetu; runtime /data/adb/hetu; standalone module removed.\n",
        encoding="utf-8",
    )

if __name__ == "__main__":
    main()
