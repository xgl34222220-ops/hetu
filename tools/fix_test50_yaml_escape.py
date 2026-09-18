from pathlib import Path

p = Path('android-app/app/src/main/java/io/github/xgl34222220/hetu/ProxySubscriptionActivity.kt')
s = p.read_text()

# The first generated source accidentally contained a literal backslash followed by
# a physical newline inside the Kotlin char/string literals. Build the malformed
# token explicitly so this repair does not depend on Python source escaping tricks.
bad_char = "it == '" + "\\" + "\n" + "'"
good_char = "it == '\\n'"
bad_join = 'joinToString("' + "\\" + "\n" + '")'
good_join = 'joinToString("\\n")'

changed = False
if bad_char in s:
    s = s.replace(bad_char, good_char)
    changed = True
elif good_char not in s:
    raise RuntimeError('YAML newline char literal is neither malformed nor already fixed')

if bad_join in s:
    s = s.replace(bad_join, good_join)
    changed = True
elif good_join not in s:
    raise RuntimeError('YAML newline string literal is neither malformed nor already fixed')

if changed:
    p.write_text(s)
    print('Fixed YAML newline escaping')
else:
    print('YAML newline escaping already fixed')
