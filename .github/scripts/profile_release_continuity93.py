#!/usr/bin/env python3
"""Generate the Hetu baseline profile from the installed release APK in the owned AOSP emulator.

No Macrobenchmark module or extra Gradle plugin is involved (the V20.93 source layer pins the
dependency set). Instead this reproduces what BaselineProfileRule does on API 33+:

1. `cmd package compile --reset` so every method runs interpreted/JIT and is recorded by ART;
2. drive the user journeys several times from a cold process: cold start, 首页, 面板, 工具 ›
   网络测试, 工具 › 日志文件, 设置 › 基础代理配置 › 配置选择;
3. ask the app to flush its JIT profile through the bundled profileinstaller receiver
   (androidx.profileinstaller.action.SAVE_PROFILE, guarded by android.permission.DUMP);
4. `pm dump-profiles --dump-classes-and-methods` and read the human-readable rules ART wrote.

The release APK is R8-obfuscated, so the dumped rules use minified names. They are mapped back to
source names with the same build's R8 mapping (retrace_profile_continuity93.py) and the result is
committed as android-app/app/src/main/baseline-prof.txt. Never starts a proxy, never uses run-as,
never clears app data and runs only after the release install smoke in the same emulator.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

from run_hetu_emulator import verify_owned_emulator_target

PKG = 'io.github.xgl34222220.hetu'
ADB = str(Path(os.environ['ANDROID_HOME']) / 'platform-tools' / 'adb')
OUT = Path(os.environ.get('HETU_SMOKE_OUT', 'out/android-smoke')) / 'baseline-profile'
ITERATIONS = int(os.environ.get('HETU_BASELINE_PROFILE_ITERATIONS', '3'))
MIN_RAW_METHOD_RULES = 2000
LABELS = {'首页': ('首页', 'Home'), '面板': ('面板', 'Panel'), '工具': ('工具', 'Tools'), '设置': ('设置', 'Settings'),
          '网络测试': ('网络测试', 'Network test'), '日志文件': ('日志文件', 'Log files'),
          '基础代理配置': ('基础代理配置', 'Proxy configuration'), '配置选择': ('配置选择', 'Configurations')}
steps = []


def adb(*args, timeout=90, check=True):
    return subprocess.run([ADB, *args], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout,
                          check=check).stdout.decode(errors='replace')


def ui():
    for attempt in range(20):
        path = f'/data/local/tmp/hetu-profile-{attempt}.xml'
        adb('shell', 'uiautomator', 'dump', path, timeout=30, check=False)
        raw = adb('shell', 'cat', path, timeout=15, check=False)
        adb('shell', 'rm', '-f', path, timeout=10, check=False)
        start = raw.find('<?xml')
        if start >= 0:
            try:
                return ET.fromstring(raw[start:])
            except ET.ParseError:
                pass
        time.sleep(1)
    raise AssertionError('No accessibility hierarchy')


def nodes(root, key):
    wanted = LABELS.get(key, (key,))
    return [n for n in root.iter('node') if n.get('text') in wanted or n.get('content-desc') in wanted]


def bounds(node):
    return tuple(map(int, re.findall(r'\d+', node.get('bounds', '[0,0][0,0]'))))


def size():
    match = re.search(r'(\d+)x(\d+)', adb('shell', 'wm', 'size', check=False))
    return (int(match.group(1)), int(match.group(2))) if match else (393, 852)


def swipe_up():
    width, height = size()
    adb('shell', 'input', 'swipe', str(width // 2), str(height * 7 // 10), str(width // 2), str(height * 3 // 10), '350')
    time.sleep(1.2)


def swipe_down():
    width, height = size()
    adb('shell', 'input', 'swipe', str(width // 2), str(height * 3 // 10), str(width // 2), str(height * 7 // 10), '350')
    time.sleep(1.2)


def wait(key, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if nodes(ui(), key):
            return True
        time.sleep(1)
    return False


def tap(key, bottom=False, scroll=0):
    for attempt in range(scroll + 1):
        found = nodes(ui(), key)
        if found:
            found.sort(key=lambda n: bounds(n)[1], reverse=bottom)
            x1, y1, x2, y2 = bounds(found[0])
            adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
            time.sleep(2.5)
            return True
        if attempt < scroll:
            swipe_up()
    return False


def back():
    adb('shell', 'input', 'keyevent', '4')
    time.sleep(2)


def step(iteration, name, ok):
    steps.append({'iteration': iteration, 'step': name, 'result': 'passed' if ok else 'skipped'})


def alive():
    assert adb('shell', 'pidof', PKG, check=False).strip(), 'Release app process exited during profiling'


def save_profile():
    # profileinstaller >=1.3 sends SIGUSR1 to its own process, which makes ART flush the JIT profile.
    out = adb('shell', 'am', 'broadcast', '-a', 'androidx.profileinstaller.action.SAVE_PROFILE',
              '-n', PKG + '/androidx.profileinstaller.ProfileInstallReceiver', check=False)
    time.sleep(3)
    return out


def journey(launcher, iteration):
    adb('shell', 'am', 'force-stop', PKG)
    time.sleep(1)
    launch = adb('shell', 'am', 'start', '-W', '-n', launcher, timeout=240)
    (OUT / f'launch-{iteration}.txt').write_text(launch)
    step(iteration, 'cold-start', wait('工具', timeout=90))
    alive()
    step(iteration, 'home', tap('首页', bottom=True))
    swipe_up(); swipe_down()
    step(iteration, 'panel', tap('面板', bottom=True))
    time.sleep(2); swipe_up(); swipe_down()
    step(iteration, 'tools', tap('工具', bottom=True))
    opened = tap('网络测试', scroll=2)
    step(iteration, 'network-test', opened)
    if opened:
        time.sleep(2); swipe_up(); swipe_down(); back()
    tap('工具', bottom=True)
    opened = tap('日志文件', scroll=6)
    step(iteration, 'logs', opened)
    if opened:
        time.sleep(2); back()
    alive()
    step(iteration, 'settings', tap('设置', bottom=True))
    swipe_up(); swipe_down()
    opened = tap('基础代理配置', scroll=2)
    step(iteration, 'config', opened and wait('配置选择', timeout=20))
    if opened:
        swipe_up(); back()
    step(iteration, 'home-return', tap('首页', bottom=True))
    alive()
    saved = save_profile()
    (OUT / f'save-profile-{iteration}.txt').write_text(saved)


def dump_profile():
    out = adb('shell', 'pm', 'dump-profiles', '--dump-classes-and-methods', PKG, timeout=180, check=False)
    (OUT / 'dump-profiles.txt').write_text(out)
    listing = adb('shell', 'ls', '-l', '/data/misc/profman/', check=False)
    (OUT / 'profman-listing.txt').write_text(listing)
    text = adb('shell', 'cat', f'/data/misc/profman/{PKG}-primary.prof.txt', timeout=120, check=False)
    if 'No such file' in text or not text.strip():
        raise AssertionError('pm dump-profiles produced no human-readable profile:\n' + out + '\n' + listing)
    return text


def main():
    verify_owned_emulator_target(os.environ)
    OUT.mkdir(parents=True, exist_ok=True)
    dumped = adb('shell', 'dumpsys', 'package', PKG)
    flags = ' '.join(l for l in dumped.splitlines() if 'pkgFlags=' in l or 'flags=[' in l)
    assert 'DEBUGGABLE' not in flags, 'Profiles must come from the non-debuggable release APK: ' + flags
    components = adb('shell', 'cmd', 'package', 'query-activities', '--brief', '--components', '-a', 'android.intent.action.MAIN',
                     '-c', 'android.intent.category.LAUNCHER', '-p', PKG)
    launcher = [l.strip() for l in components.splitlines() if l.strip().startswith(PKG + '/')][0]
    (OUT / 'compile-reset.txt').write_text(adb('shell', 'cmd', 'package', 'compile', '--reset', PKG, timeout=300, check=False))
    for iteration in range(1, ITERATIONS + 1):
        journey(launcher, iteration)
    raw = dump_profile()
    (OUT / 'release-primary.prof.txt').write_text(raw)
    lines = [l for l in raw.splitlines() if l.strip()]
    methods = [l for l in lines if '->' in l]
    report = {'result': 'PASS', 'iterations': ITERATIONS, 'rawRules': len(lines), 'rawMethodRules': len(methods),
              'rawClassRules': len(lines) - len(methods), 'steps': steps}
    mapping = next(Path('r8-mapping').glob('**/mapping.txt'), None) if Path('r8-mapping').is_dir() else None
    if mapping is not None:
        generated = OUT / 'baseline-prof.generated.txt'
        result = subprocess.run([sys.executable, str(Path(__file__).with_name('retrace_profile_continuity93.py')),
                                 '--profile', str(OUT / 'release-primary.prof.txt'), '--mapping', str(mapping),
                                 '--output', str(generated), '--report', str(OUT / 'retrace-report.json')],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=600)
        (OUT / 'retrace.log').write_text(result.stdout.decode(errors='replace'))
        report['retraceExit'] = result.returncode
        if result.returncode:
            report['result'] = 'FAIL'
    else:
        report.update(result='FAIL', error='R8 mapping.txt of the profiled release build was not downloaded')
    passed = {s['step'] for s in steps if s['result'] == 'passed'}
    missing = {'cold-start', 'home', 'panel', 'network-test', 'logs', 'settings', 'config'} - passed
    report['journeysNeverReached'] = sorted(missing)
    if missing or len(methods) < MIN_RAW_METHOD_RULES:
        report['result'] = 'FAIL'
    (OUT / 'profile-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({k: v for k, v in report.items() if k != 'steps'}, ensure_ascii=False, indent=2))
    return 0 if report['result'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
