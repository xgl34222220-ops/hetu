#!/usr/bin/env bash
set -euo pipefail
APK="${APK:-android-app/app/build/outputs/apk/debug/app-debug.apk}"
PKG="io.github.xgl34222220.bichen.preview"
OUT="out/compose-smoke"
mkdir -p "$OUT"

adb install -r "$APK"
adb shell am force-stop "$PKG" || true
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null

for _ in $(seq 1 20); do
  if adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' | grep -q .; then break; fi
  sleep 1
done
sleep 2

dump_ui() {
  local name="$1" remote="/sdcard/window.xml" local_xml="$OUT/$1.xml"
  rm -f "$local_xml"
  adb shell rm -f "$remote" >/dev/null 2>&1 || true
  for _ in $(seq 1 12); do
    if adb shell uiautomator dump "$remote" >/dev/null 2>&1 \
      && adb pull "$remote" "$local_xml" >/dev/null 2>&1 \
      && [ -s "$local_xml" ]; then
      adb exec-out screencap -p > "$OUT/$name.png" || true
      return 0
    fi
    sleep 1
  done
  adb exec-out screencap -p > "$OUT/${name}-failure.png" 2>/dev/null || true
  adb logcat -d > "$OUT/${name}-failure-logcat.txt" 2>/dev/null || true
  return 1
}

assert_text() {
  local file="$1" text="$2"
  python3 - "$file" "$text" <<'PY'
import sys,xml.etree.ElementTree as ET
root=ET.parse(sys.argv[1]).getroot(); q=sys.argv[2]
vals=[]
for n in root.iter(): vals += [n.attrib.get('text',''), n.attrib.get('content-desc','')]
if not any(q in x for x in vals): raise SystemExit(f"missing UI text: {q}")
PY
}

assert_not_text() {
  local file="$1" text="$2"
  python3 - "$file" "$text" <<'PY'
import sys,xml.etree.ElementTree as ET
root=ET.parse(sys.argv[1]).getroot(); q=sys.argv[2]
vals=[]
for n in root.iter(): vals += [n.attrib.get('text',''), n.attrib.get('content-desc','')]
if any(q in x for x in vals): raise SystemExit(f"unexpected UI text: {q}")
PY
}

tap_text() {
  local text="$1"
  dump_ui tap
  read -r x y < <(python3 - "$OUT/tap.xml" "$text" <<'PY'
import re,sys,xml.etree.ElementTree as ET
root=ET.parse(sys.argv[1]).getroot(); q=sys.argv[2]
for n in root.iter():
    if q in n.attrib.get('text','') or q in n.attrib.get('content-desc',''):
        m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n.attrib.get('bounds',''))
        if m:
            a,b,c,d=map(int,m.groups()); print((a+c)//2,(b+d)//2); raise SystemExit
raise SystemExit(f"cannot locate {q}")
PY
  )
  adb shell input tap "$x" "$y"
  sleep 1
}

# Ad-block shell
dump_ui home
assert_text "$OUT/home.xml" "首页"
assert_text "$OUT/home.xml" "应用"
assert_text "$OUT/home.xml" "规则"
assert_text "$OUT/home.xml" "活动"
assert_text "$OUT/home.xml" "代理"
assert_not_text "$OUT/home.xml" "无法读取内置订阅"

tap_text "应用"
dump_ui apps
assert_text "$OUT/apps.xml" "应用放行"

tap_text "规则"
dump_ui rules
assert_text "$OUT/rules.xml" "过滤规则"
assert_text "$OUT/rules.xml" "AdAway 通用规则"
assert_text "$OUT/rules.xml" "秋风纯广告"
assert_not_text "$OUT/rules.xml" "无法读取内置订阅"

tap_text "活动"
dump_ui activity
assert_text "$OUT/activity.xml" "请求活动"

# Proxy shell and all four primary destinations.
tap_text "首页"
tap_text "代理"
dump_ui proxy-home
assert_text "$OUT/proxy-home.xml" "代理"
assert_text "$OUT/proxy-home.xml" "首页"
assert_text "$OUT/proxy-home.xml" "面板"
assert_text "$OUT/proxy-home.xml" "工具"
assert_text "$OUT/proxy-home.xml" "设置"
assert_text "$OUT/proxy-home.xml" "启动"
assert_text "$OUT/proxy-home.xml" "Baidu"
assert_text "$OUT/proxy-home.xml" "Cloudflare"
assert_text "$OUT/proxy-home.xml" "Google"

tap_text "面板"
dump_ui proxy-panel
assert_text "$OUT/proxy-panel.xml" "节点"
assert_text "$OUT/proxy-panel.xml" "概览"
assert_text "$OUT/proxy-panel.xml" "订阅"
assert_text "$OUT/proxy-panel.xml" "连接"
assert_text "$OUT/proxy-panel.xml" "规则"
assert_text "$OUT/proxy-panel.xml" "规则集"

tap_text "工具"
dump_ui proxy-tools
assert_text "$OUT/proxy-tools.xml" "脚本"
assert_text "$OUT/proxy-tools.xml" "日志查看"
assert_text "$OUT/proxy-tools.xml" "应用管理"
assert_text "$OUT/proxy-tools.xml" "更新 WebUI"
assert_text "$OUT/proxy-tools.xml" "更新核心"

tap_text "设置"
dump_ui proxy-settings
assert_text "$OUT/proxy-settings.xml" "核心选择"
assert_text "$OUT/proxy-settings.xml" "运行模式"
assert_text "$OUT/proxy-settings.xml" "IPv6"
assert_text "$OUT/proxy-settings.xml" "主题与界面"
assert_text "$OUT/proxy-settings.xml" "WebUI 面板"

PID=$(adb shell pidof "$PKG" | tr -d '\r')
test -n "$PID"
adb logcat -d > "$OUT/logcat.txt"
if grep -A18 -B4 'FATAL EXCEPTION' "$OUT/logcat.txt" | grep -q "$PKG"; then
  echo "Compose UI crashed" >&2
  exit 1
fi
printf 'BICHEN_BOXPROXY_SMOKE_PASS\n' | tee "$OUT/result.txt"
