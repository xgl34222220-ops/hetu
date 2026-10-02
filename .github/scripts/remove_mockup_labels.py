from pathlib import Path
import re

root = Path("android-app/app/src/main/java/io/github/xgl34222220/hetu")

targets = [
    root / "app/HomeScreen.kt",
    root / "app/SettingsScreen.kt",
    root / "app/PanelToolsScreens.kt",
    root / "app/HxTabbedPage.kt",
    root / "RootTproxyActivity.java",
    root / "ProxyAdvancedSettingsActivity.kt",
]

for p in targets:
    if not p.exists():
        continue
    s = p.read_text()

    # Mockup/reference annotations are not app UI. Remove every UI emission of that label.
    s = re.sub(r'^[ \t]*referenceLabel = "示例数据",\n', '', s, flags=re.M)
    s = re.sub(r'^[ \t]*actions = \{ Text\("示例数据".*?\},\n', '', s, flags=re.M)
    s = re.sub(r'^[ \t]*if \(panelReferenceStyle\) Text\("示例数据".*?\)\n', '', s, flags=re.M)
    s = re.sub(r'^[ \t]*Text\("示例数据".*?\)\n', '', s, flags=re.M)

    # Java header annotation created in the V20.56 transform.
    s = re.sub(
        r'^[ \t]*TextView sample=u\.text\("示例数据".*?;sample\.setGravity\(Gravity\.CENTER\);\n',
        '',
        s,
        flags=re.M,
    )
    s = re.sub(
        r'^[ \t]*top\.addView\(sample,new LinearLayout\.LayoutParams\(u\.dp\(70\),-1\)\);\n',
        '',
        s,
        flags=re.M,
    )

    p.write_text(s)

# Hard fail if any source still renders or contains the mockup annotation.
left = []
for p in root.rglob("*"):
    if p.suffix not in {".kt", ".java"}:
        continue
    s = p.read_text(errors="ignore")
    if "示例数据" in s:
        left.append(str(p))
assert not left, "mockup annotation still present: " + ", ".join(left)

print("Removed all mockup-only 示例数据 annotations from app sources")
