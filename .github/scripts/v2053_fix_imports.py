from pathlib import Path

p = Path("android-app/app/src/main/java/io/github/xgl34222220/hetu/app/ConfigScreens.kt")
s = p.read_text()
anchor = "import androidx.compose.material.icons.rounded.Add\n"
imp = "import androidx.compose.material.icons.rounded.Description\n"
if imp not in s:
    assert anchor in s
    s = s.replace(anchor, anchor + imp, 1)
p.write_text(s)
print("V20.53 config imports fixed")
