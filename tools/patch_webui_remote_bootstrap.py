from pathlib import Path

p = Path('android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyWebUiActivity.kt')
s = p.read_text()
old = """                              window.location.replace('/ui/?bichen_fresh=1');
                              return 'reloading';
"""
new = """                              ${if (selected.local) "window.location.replace('/ui/?bichen_fresh=1');" else "window.location.reload();"}
                              return 'reloading';
"""
if old not in s:
    raise SystemExit('bootstrap reload marker missing')
s = s.replace(old, new, 1)
s = s.replace('pageError = "本地 MetaCubeXD 初始化失败，请刷新重试"', 'pageError = "${selected.name} 初始化失败，请刷新重试"', 1)
p.write_text(s)
