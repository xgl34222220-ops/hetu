#!/usr/bin/env python3
"""Install smoke of the shipped (release, non-debuggable, R8) APK in the owned AOSP emulator.

Runs after the full debug-APK smoke in the same emulator: the release APK is installed over the
installed test APK with `install -r` (same signing identity: an in-place upgrade that keeps data),
then launched and driven through the screens this pass changed. Never starts a proxy, never
uses run-as (the release is not debuggable) and never clears app data.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

from run_hetu_emulator import verify_owned_emulator_target

PKG = 'io.github.xgl34222220.hetu'
ADB = str(Path(os.environ['ANDROID_HOME']) / 'platform-tools' / 'adb')
OUT = Path(os.environ.get('HETU_SMOKE_OUT', 'out/android-smoke')) / 'release'
OUT.mkdir(parents=True, exist_ok=True)
# Chinese label first; the English vocabulary entry when the device language is English.
LABELS = {'工具': ('工具', 'Tools'), '设置': ('设置', 'Settings'), '网络测试': ('网络测试', 'Network test'),
          '文件管理': ('文件管理', 'Files'), '基础代理配置': ('基础代理配置', 'Proxy configuration'),
          '配置选择': ('配置选择', 'Configurations'), '查看': ('查看', 'Inspect'), '编辑': ('编辑', 'Edit'),
          '查看配置': ('查看配置', 'View configuration'), '返回': ('返回', 'Back')}
checks = []


def adb(*args, timeout=90, check=True):
    return subprocess.run([ADB, *args], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout, check=check).stdout.decode(errors='replace')


def ui():
    for attempt in range(30):
        path = f'/data/local/tmp/hetu-release-{attempt}.xml'
        adb('shell', 'uiautomator', 'dump', path, timeout=30, check=False)
        raw = adb('shell', 'cat', path, timeout=15, check=False)
        adb('shell', 'rm', '-f', path, timeout=10, check=False)
        start = raw.find('<?xml')
        if start >= 0:
            try:
                return ET.fromstring(raw[start:]), raw[start:]
            except ET.ParseError:
                pass
        time.sleep(1)
    raise AssertionError('No accessibility hierarchy')


def capture(name):
    root, raw = ui()
    (OUT / (name + '.xml')).write_text(raw)
    png = subprocess.run([ADB, 'exec-out', 'screencap', '-p'], stdout=subprocess.PIPE, timeout=45, check=True).stdout
    (OUT / (name + '.png')).write_bytes(png)
    return root


def nodes(root, key):
    wanted = LABELS.get(key, (key,))
    return [n for n in root.iter('node') if n.get('text') in wanted or n.get('content-desc') in wanted]


def bounds(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds', '[0,0][0,0]')))
    return x1, y1, x2, y2


def wait_for(key, name, timeout=40):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        root = capture(name)
        if nodes(root, key):
            checks.append({'name': name, 'label': key, 'result': 'passed'})
            return root
        time.sleep(1.5)
    raise AssertionError(f'{key} not shown ({name})')


def tap(key, bottom=False, long=False):
    root, _ = ui()
    found = nodes(root, key)
    assert found, 'Label unavailable: ' + key
    found.sort(key=lambda n: bounds(n)[1], reverse=bottom)
    x1, y1, x2, y2 = bounds(found[0])
    x, y = (x1 + x2) // 2, (y1 + y2) // 2
    if long: adb('shell', 'input', 'swipe', str(x), str(y), str(x), str(y), '900')
    else: adb('shell', 'input', 'tap', str(x), str(y))
    time.sleep(2)


def alive():
    assert adb('shell', 'pidof', PKG, check=False).strip(), 'Release app process exited'
    crash = adb('logcat', '-d', '-b', 'crash', timeout=30, check=False)
    assert 'FATAL EXCEPTION' not in crash or PKG not in crash, 'Release app crashed:\n' + crash[-4000:]


def main():
    verify_owned_emulator_target(os.environ)
    debug = next(Path('candidate').glob('*.apk'))
    release = next(Path('release-candidate').glob('*.apk'))
    # The test APK is installed (and has run) first, so this is a real in-place upgrade with data.
    installed = adb('install', '--no-streaming', '-r', str(debug), timeout=600)
    assert 'Success' in installed, installed
    before = adb('shell', 'dumpsys', 'package', PKG)
    first = re.search(r'firstInstallTime=(.+)', before).group(1).strip()
    upgraded = adb('install', '--no-streaming', '-r', str(release), timeout=600)
    (OUT / 'install-release.txt').write_text(upgraded)
    assert 'Success' in upgraded, 'Release did not install over the test APK in place: ' + upgraded
    after = adb('shell', 'dumpsys', 'package', PKG)
    (OUT / 'package-after.txt').write_text('\n'.join(l for l in after.splitlines() if 'Flags' in l or 'version' in l or 'InstallTime' in l))
    assert re.search(r'firstInstallTime=(.+)', after).group(1).strip() == first, 'Upgrade reinstalled instead of updating in place'
    flags = ' '.join(l for l in after.splitlines() if 'pkgFlags=' in l or 'flags=[' in l)
    assert 'DEBUGGABLE' not in flags, 'Installed release is debuggable: ' + flags
    assert 'versionCode=' + os.environ.get('HETU_EXPECTED_VERSION', '2093') in after
    adb('logcat', '-c', '-b', 'crash', check=False)
    adb('shell', 'am', 'force-stop', PKG)
    components = adb('shell', 'cmd', 'package', 'query-activities', '--brief', '--components', '-a', 'android.intent.action.MAIN',
                     '-c', 'android.intent.category.LAUNCHER', '-p', PKG)
    launcher = [l.strip() for l in components.splitlines() if l.strip().startswith(PKG + '/')][0]
    (OUT / 'launch.txt').write_text(adb('shell', 'am', 'start', '-W', '-n', launcher, timeout=240))
    wait_for('工具', 'r01-launched', timeout=90)
    alive()
    # Item 1: 网络测试 is the first row of 工具.
    tap('工具', bottom=True)
    root = wait_for('网络测试', 'r02-tools')
    net, files = nodes(root, '网络测试'), nodes(root, '文件管理')
    assert files and bounds(net[0])[1] < bounds(files[0])[1], '网络测试 must be above 文件管理'
    alive()
    # Item 2: 设置 › 基础代理配置 › 配置选择, 查看 opens the editor in place (no tab switch).
    tap('设置', bottom=True)
    wait_for('基础代理配置', 'r03-settings')
    tap('基础代理配置')
    root = wait_for('配置选择', 'r04-network-settings')
    heading = bounds(nodes(root, '配置选择')[0])
    rows = [n for n in root.iter('node') if (n.get('text') or '').endswith(('.yaml', '.yml')) and bounds(n)[1] > heading[3]]
    assert rows, 'No config rows under 配置选择'
    x1, y1, x2, y2 = bounds(rows[0])
    adb('shell', 'input', 'swipe', str((x1 + x2) // 2), str((y1 + y2) // 2), str((x1 + x2) // 2), str((y1 + y2) // 2), '900')
    time.sleep(2)
    wait_for('编辑', 'r05-config-menu')
    tap('查看')
    wait_for('查看配置', 'r06-config-viewer')
    alive()
    adb('shell', 'input', 'keyevent', '4')
    time.sleep(2)
    wait_for('配置选择', 'r07-back-to-network-settings')
    alive()
    report = {'result': 'PASS', 'debuggable': False, 'inPlaceUpgradeFromTestApk': True, 'firstInstallTime': first, 'checks': checks}
    (OUT / 'release-smoke.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
