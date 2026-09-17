from pathlib import Path

p = Path('android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxySubscriptionActivity.kt')
s = p.read_text()

bad_char = "it == '\\\n'"
good_char = "it == '\\n'"
bad_join = 'joinToString("\\\n")'
good_join = 'joinToString("\\n")'

if bad_char not in s:
    raise RuntimeError('missing malformed YAML newline char literal')
if bad_join not in s:
    raise RuntimeError('missing malformed YAML newline string literal')

s = s.replace(bad_char, good_char)
s = s.replace(bad_join, good_join)
p.write_text(s)
print('Fixed YAML newline escaping')
