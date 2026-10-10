#!/usr/bin/env python3
"""Per-core host regression for hetu-root.sh: no real network, firewall or Root operations.

Every core Hetu can download (Mihomo, Mihomo Smart, sing-box, sing-box reF1nd, Xray, V2Fly,
Hysteria 2) is deployed to $BASE/bin/core; the App writes which CLI it speaks to
$BASE/run/state/core.kind (absent = Mihomo, so existing deployments and boot plans are unchanged).

For each core kind a fake binary records its argv/environment and, when launched, actually binds
the listeners the real core would (TPROXY TCP+UDP, DNS TCP+UDP, and the controller only for the
Clash-API cores). The shipped start/stop/status/validate code then runs against the stateful
netfilter + policy-routing model (tools/qa/netfilter_model93.py):

* validation uses the core's own check CLI on the native payload (fake-IP metadata stripped),
* start installs identical capture for every core and records no controller port for cores
  without one (health and the App skip it),
* the core's own sockets bypass interception by owner/bypass mark, never by an SO_MARK,
* stop leaves no Hetu rule, route, chain, watchdog or core process behind,
* status self-heals a dead core of any kind (network-loss fix stays intact).
"""
from pathlib import Path
import json
import os
import re
import shlex
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import unittest

REPO = Path(__file__).resolve().parents[2]
SRC = REPO / 'android-app/app/src/main'
ASSETS = SRC / 'assets'
PKG = SRC / 'java/io/github/xgl34222220/hetu'
MODEL = Path(__file__).with_name('netfilter_model93.py')
ALL_CORES = ('mihomo', 'mihomo-smart', 'sing-box', 'sing-box-ref1nd', 'xray', 'v2fly', 'hysteria')
KIND = {'mihomo': 'mihomo', 'mihomo-smart': 'mihomo', 'sing-box': 'sing-box', 'sing-box-ref1nd': 'sing-box',
        'xray': 'xray', 'v2fly': 'v2ray', 'hysteria': 'hysteria'}
API_KINDS = ('mihomo', 'sing-box')
METADATA = ('# HETU_FAKE_IP_POLICY=1\n# HETU_FAKE_IP_V4=198.18.0.1/16\n# HETU_FAKE_IP_V6=\n'
            '# HETU_LAN_RETURN_V4=0.0.0.0/8,10.0.0.0/8,100.64.0.0/10,127.0.0.0/8,169.254.0.0/16,172.16.0.0/12,192.168.0.0/16,224.0.0.0/4,240.0.0.0/4\n'
            '# HETU_LAN_RETURN_V6=::1/128,fc00::/7,fe80::/10,ff00::/8\n')
PAYLOAD = {'mihomo': 'mode: rule\n', 'sing-box': '{\n  "inbounds": []\n}\n', 'xray': '{\n  "inbounds": []\n}\n',
           'v2ray': '{\n  "inbounds": []\n}\n', 'hysteria': 'server: 192.0.2.1:443\nauth: x\n'}

# The fake core. Check mode exits with the requested status; run mode binds every port in
# HETU_FAKE_LISTEN ("tcp:PORT,udp:PORT,...") and sleeps until terminated.
FAKE_CORE_PY = r'''
import os, socket, sys, time, json, signal
argv = sys.argv[1:]
log = os.environ['HETU_FAKE_LOG']
with open(log, 'a') as f:
    f.write(json.dumps({'argv': argv, 'xray': os.environ.get('XRAY_LOCATION_ASSET'), 'v2ray': os.environ.get('V2RAY_LOCATION_ASSET'),
                        'cwd': os.getcwd()}) + '\n')
check = argv[:1] in (['check'], ['test']) or '-test' in argv or argv[:1] == ['-t']
if check:
    sys.exit(int(os.environ.get('HETU_FAKE_CHECK_RC', '0')))
socks = []
for spec in filter(None, os.environ.get('HETU_FAKE_LISTEN', '').split(',')):
    proto, port = spec.split(':')
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM if proto == 'tcp' else socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(('127.0.0.1', int(port)))
    if proto == 'tcp': s.listen(4)
    socks.append(s)
signal.signal(signal.SIGTERM, lambda *a: sys.exit(0))
while True: time.sleep(0.2)
'''


def free_port():
    while True:
        with socket.socket() as t, socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as u:
            t.bind(('127.0.0.1', 0)); p = t.getsockname()[1]
            try: u.bind(('127.0.0.1', p))
            except OSError: continue
            if 20000 < p < 60000: return p


class CoreSupport93(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='hetu-core93-')
        self.dir = Path(self.temp.name); self.base = self.dir / 'hetu'
        for d in ('bin', 'run/state', 'boot'): (self.base / d).mkdir(parents=True, exist_ok=True)
        self.bin = self.dir / 'commands'; self.bin.mkdir()
        self.state = self.dir / 'net.json'; self.boot_id = self.dir / 'boot-id'; self.boot_id.write_text('current-boot\n')
        for tool in ('iptables', 'ip6tables', 'iptables-restore', 'ip6tables-restore', 'iptables-save', 'ip6tables-save', 'ip'):
            c = self.bin / tool; c.write_text('#!/bin/sh\nexec ' + shlex.quote(sys.executable) + ' ' + shlex.quote(str(MODEL)) + ' ' + tool + ' "$@"\n'); c.chmod(0o700)
        (self.dir / 'fakecore.py').write_text(FAKE_CORE_PY)
        self.core_log = self.dir / 'core.jsonl'
        self.tp, self.dp, self.cp = free_port(), free_port(), free_port()
        self.env = dict(os.environ, PATH=str(self.bin) + ':' + os.environ['PATH'], HETU_NETMODEL=str(self.state),
                        HETU_NETMODEL_RESTORE_DELAY=str(self.dir / 'restore-delay'), HETU_NETMODEL_RESTORE_FAIL=str(self.dir / 'restore-fail'),
                        HETU_FAKE_LOG=str(self.core_log))
        source = (ASSETS / 'hetu-root.sh').read_text().replace('/system/bin/sh', '/bin/sh').replace('/data/adb/hetu', str(self.base)).replace('/proc/sys/kernel/random/boot_id', str(self.boot_id))
        at = source.rindex('case "${1:-status}" in')
        # Fixture authorization and an IPv6-capable kernel; every network effect is the model.
        # The fake core is a /bin/sh wrapper, so its identity is its argv[1] (=bin/core), not /proc/exe.
        stubs = ('root(){ :; }\nv6supported(){ return 0; }\n'
                 'pidcore(){ case "$1" in \'\'|*[!0-9]*) return 1;; esac; [ -r "/proc/$1/cmdline" ] || return 1; '
                 'tr \'\\000\' \'\\n\' < "/proc/$1/cmdline" 2>/dev/null | sed -n 2p | grep -qx "$BASE/bin/core"; }\n'
                 'core_candidate(){ pidcore "$1"; }\n'
                 'settings(){ echo off; }\n')
        self.script = self.base / 'hetu-root.sh'; self.script.write_text(source[:at] + stubs + source[at:]); self.script.chmod(0o700)
        self.functions = self.dir / 'functions.sh'; self.functions.write_text(source[:at] + stubs)

    def tearDown(self):
        subprocess.run(['sh', str(self.script), 'stop'], env=self.env, capture_output=True, text=True, timeout=30)
        for proc in Path('/proc').iterdir():
            if proc.name.isdigit():
                try:
                    if str(self.base) in (proc / 'cmdline').read_bytes().decode(errors='ignore'): os.kill(int(proc.name), signal.SIGKILL)
                except (OSError, ValueError): pass
        self.temp.cleanup()

    # ------------------------------------------------------------------ helpers
    def deploy(self, core_id, write_kind=True):
        """What RootProxyManager installs: the core binary as bin/core plus run/state/core.kind."""
        core = self.base / 'bin/core'
        core.write_text('#!/bin/sh\n' + shlex.quote(sys.executable) + ' ' + shlex.quote(str(self.dir / 'fakecore.py')) + ' "$@" &\n'
                        'C=$!\ntrap \'kill "$C" 2>/dev/null; wait "$C"; exit 0\' TERM INT\nwait "$C"\n')
        core.chmod(0o700)
        kind = self.base / 'run/state/core.kind'
        if write_kind: kind.write_text(KIND[core_id] + '\n')
        elif kind.exists(): kind.unlink()
        cfg = self.base / 'run/state/startup-config'
        cfg.write_text(PAYLOAD[KIND[core_id]] + METADATA)
        return cfg

    def calls(self):
        if not self.core_log.exists(): return []
        return [json.loads(l) for l in self.core_log.read_text().splitlines()]

    def dispatch(self, *args, env=None, timeout=60):
        return subprocess.run(['sh', str(self.script), *args], env=dict(self.env, **(env or {})), capture_output=True, text=True, timeout=timeout)

    def body(self, text, timeout=30):
        script = self.dir / 'body.sh'; script.write_text('. ' + shlex.quote(str(self.functions)) + '\n' + text + '\n')
        return subprocess.run(['sh', str(script)], env=self.env, capture_output=True, text=True, timeout=timeout)

    def dump(self):
        out = {}
        for fam, tool in (('4', 'iptables'), ('6', 'ip6tables')):
            for table in ('mangle', 'nat', 'filter'):
                out[fam + '-' + table] = subprocess.run([sys.executable, str(MODEL), tool, '-t', table, '-S'], env=self.env, capture_output=True, text=True).stdout.split('\n')
            out[fam + '-rules'] = subprocess.run([sys.executable, str(MODEL), 'ip', '-' + fam, 'rule', 'show'], env=self.env, capture_output=True, text=True).stdout.split('\n')
        return out

    def owned(self):
        return [(k, l) for k, lines in self.dump().items() for l in lines
                if 'HETU_' in l or any(' lookup %d' % t in l or ' table %d' % t in l for t in range(20260, 20300))]

    def start_args(self, cfg, kill='0'):
        # 31-field protocol: tproxy TCP+UDP, DNS redirect, core scope, prevalidated=0, cached caps.
        return ['start', str(self.base / 'bin/core'), str(cfg), 'tproxy', str(self.tp), '0', 'enable', '1', '1', 'redirect', '0',
                str(self.dp), str(self.cp), 'core', '', '0', kill, '', '', '', '0', '1', '', '', '1', '1', '0', '', '', '', '0']

    def listen_env(self, kind):
        specs = ['tcp:%d' % self.tp, 'udp:%d' % self.tp, 'tcp:%d' % self.dp, 'udp:%d' % self.dp]
        if kind in API_KINDS: specs.append('tcp:%d' % self.cp)
        return {'HETU_FAKE_LISTEN': ','.join(specs)}

    def expected_check(self, kind, payload):
        run = str(self.base / 'run')
        return {'mihomo': ['-t', '-d', run, '-f', str(self.base / 'run/state/startup-config')],
                'sing-box': ['check', '-c', payload, '-D', run, '--disable-color'],
                'xray': ['run', '-test', '-c', payload], 'v2ray': ['test', '-c', payload]}.get(kind)

    def expected_run(self, kind, payload):
        run = str(self.base / 'run')
        return {'mihomo': ['-d', run, '-f', str(self.base / 'run/state/startup-config')],
                'sing-box': ['run', '-c', payload, '-D', run, '--disable-color'],
                'xray': ['run', '-c', payload], 'v2ray': ['run', '-c', payload],
                'hysteria': ['client', '-c', payload, '--disable-update-check']}[kind]

    # ------------------------------------------------------------------ tests
    def test_every_core_starts_with_its_own_cli_and_stops_without_leftovers(self):
        for core_id in ALL_CORES:
            kind = KIND[core_id]
            with self.subTest(core=core_id):
                if self.core_log.exists(): self.core_log.unlink()
                cfg = self.deploy(core_id)
                r = self.dispatch(*self.start_args(cfg), env=self.listen_env(kind))
                self.assertEqual(0, r.returncode, r.stdout + r.stderr)
                reply = json.loads(r.stdout.strip().splitlines()[-1])
                self.assertTrue(reply['ok'], reply)
                ext = {'hysteria': 'yaml', 'mihomo': None}.get(kind, 'json')
                payload = str(self.base / 'run' / ('hetu-core.' + ext)) if ext else str(cfg)
                calls = self.calls()
                if kind != 'hysteria':
                    self.assertEqual(self.expected_check(kind, payload), calls[0]['argv'], 'check CLI for ' + core_id)
                self.assertEqual(self.expected_run(kind, payload), calls[-1]['argv'], 'run CLI for ' + core_id)
                if kind != 'mihomo':
                    # The native payload is the startup copy minus Hetu's fake-IP metadata block.
                    self.assertEqual(PAYLOAD[kind], Path(payload).read_text())
                    if ext == 'json': json.loads(Path(payload).read_text())
                    self.assertEqual(str(self.base / 'bin/assets'), calls[-1]['xray'])
                    self.assertEqual(str(self.base / 'bin/assets'), calls[-1]['v2ray'])
                session = (self.base / 'run/session.state').read_text()
                expected_cp = str(self.cp) if kind in API_KINDS else '0'
                self.assertIn('CONTROLLER_PORT=%s\n' % expected_cp, session)
                rules = self.dump()
                # Identical, core-independent capture: TPROXY on the shared port, bypass mark first.
                self.assertTrue(any('TPROXY --on-port %d' % self.tp in l for l in rules['4-mangle']), rules['4-mangle'])
                self.assertIn('-A HETU_MOUT -m mark --mark 0x08000000/0x08000000 -j RETURN', rules['4-mangle'])
                self.assertIn('-A HETU_MOUT -m owner --uid-owner 0-9999 -j RETURN', rules['4-mangle'])
                self.assertTrue(any('REDIRECT --to-ports %d' % self.dp in l for l in rules['4-nat']), rules['4-nat'])
                health = self.dispatch('network-health')
                self.assertNotIn('listener-%d' % self.cp, health.stdout)
                stop = self.dispatch('stop')
                self.assertEqual(0, stop.returncode, stop.stdout + stop.stderr)
                self.assertEqual([], self.owned(), 'stop left capture behind for ' + core_id)
                self.assertFalse((self.base / 'run/core.pid').exists())
                self.assertFalse((self.base / 'run/watchdog.pid').exists())
                status = json.loads(self.dispatch('status').stdout)
                self.assertFalse(status['running'])

    def test_missing_kind_file_keeps_the_exact_mihomo_cli(self):
        cfg = self.deploy('mihomo', write_kind=False)
        r = self.dispatch(*self.start_args(cfg), env=self.listen_env('mihomo'))
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        calls = self.calls()
        self.assertEqual(['-t', '-d', str(self.base / 'run'), '-f', str(cfg)], calls[0]['argv'])
        self.assertEqual(['-d', str(self.base / 'run'), '-f', str(cfg)], calls[-1]['argv'])
        self.assertIsNone(calls[-1]['xray'])
        self.assertFalse(list((self.base / 'run').glob('hetu-core.*')))

    def test_core_without_controller_is_ready_without_one_and_api_core_waits_for_it(self):
        for core_id in ('xray', 'sing-box'):
            with self.subTest(core=core_id):
                kind = KIND[core_id]
                cfg = self.deploy(core_id)
                env = self.listen_env(kind)
                if kind in API_KINDS:
                    # No controller listener: the API core must never be reported ready.
                    env['HETU_FAKE_LISTEN'] = ','.join(s for s in env['HETU_FAKE_LISTEN'].split(',') if s != 'tcp:%d' % self.cp)
                    body = ('START_API=1; listen_snapshot; ready "$(cat "$PIDFILE")" tproxy %d 0 1 1 redirect %d %d' % (self.tp, self.dp, self.cp))
                r = self.dispatch(*self.start_args(cfg), env=env, timeout=120) if kind not in API_KINDS else None
                if r is not None:
                    self.assertEqual(0, r.returncode, r.stdout + r.stderr)
                    self.assertTrue(json.loads(r.stdout.strip().splitlines()[-1])['ok'])
                    self.assertEqual(0, self.dispatch('stop').returncode)
                else:
                    core = subprocess.Popen(['sh', str(self.base / 'bin/core'), 'run'], env=dict(self.env, **env))
                    try:
                        time.sleep(0.6)
                        (self.base / 'run/core.pid').write_text(str(core.pid) + '\n')
                        self.assertEqual(1, self.body(body).returncode, 'sing-box without its clash_api listener is not ready')
                        self.assertEqual(0, self.body(body.replace('START_API=1', 'START_API=0')).returncode)
                    finally:
                        core.terminate(); core.wait(timeout=5)
                        (self.base / 'run/core.pid').unlink()

    def test_failed_native_check_never_touches_the_network(self):
        for core_id in ('sing-box', 'xray', 'v2fly'):
            with self.subTest(core=core_id):
                cfg = self.deploy(core_id)
                r = self.dispatch(*self.start_args(cfg), env=dict(self.listen_env(KIND[core_id]), HETU_FAKE_CHECK_RC='1'))
                self.assertEqual(1, r.returncode)
                reply = json.loads(r.stdout.strip().splitlines()[-1])
                self.assertFalse(reply['ok'])
                self.assertIn('配置校验失败', reply['message'])
                self.assertIn({'sing-box': 'sing-box', 'xray': 'Xray', 'v2fly': 'V2Fly'}[core_id], reply['message'])
                self.assertEqual([], self.owned())
                self.assertEqual(1, len(self.calls()), 'only the check ran; the core was never launched')
                self.core_log.unlink()

    def test_validate_action_uses_each_core_check_and_reports_failure(self):
        for core_id in ALL_CORES:
            kind = KIND[core_id]
            with self.subTest(core=core_id):
                self.deploy(core_id)
                cfg = self.dir / ('editor-' + kind)
                cfg.write_text(PAYLOAD[kind] + METADATA)
                if self.core_log.exists(): self.core_log.unlink()
                r = self.dispatch('validate', str(cfg), kind)
                self.assertEqual(0, r.returncode, r.stdout + r.stderr)
                self.assertTrue(json.loads(r.stdout)['ok'])
                if kind != 'hysteria':
                    self.assertEqual(1, len(self.calls()))
                    argv = self.calls()[0]['argv']
                    self.assertTrue(argv[:1] in (['-t'], ['check'], ['test']) or '-test' in argv, argv)
                failed = self.dispatch('validate', str(cfg), kind, env={'HETU_FAKE_CHECK_RC': '3'})
                if kind != 'hysteria':
                    self.assertEqual(1, failed.returncode)
                    self.assertIn('配置校验失败', json.loads(failed.stdout)['message'])
                # The running core's payload is never replaced by an editor validation.
                self.assertFalse((self.base / 'run' / 'hetu-core.json').exists() and kind == 'mihomo')
                self.assertFalse(list((self.base / 'run').glob('validate.*')))
        bad = self.dir / 'bad-hysteria'; bad.write_text('auth: x\n')
        self.deploy('hysteria')
        r = self.dispatch('validate', str(bad), 'hysteria')
        self.assertEqual(1, r.returncode)
        self.assertIn('Hysteria', json.loads(r.stdout)['message'])
        self.assertEqual(1, self.dispatch('validate', str(bad), 'clash').returncode)

    def test_status_self_heals_a_dead_core_of_every_kind(self):
        for core_id in ('sing-box', 'hysteria'):
            with self.subTest(core=core_id):
                cfg = self.deploy(core_id)
                r = self.dispatch(*self.start_args(cfg), env=self.listen_env(KIND[core_id]))
                self.assertEqual(0, r.returncode, r.stdout + r.stderr)
                self.assertGreater(len(self.owned()), 10)
                # The App/watchdog were killed together with the core: only capture remains.
                wd = (self.base / 'run/watchdog.pid').read_text().strip()
                os.kill(int(wd), signal.SIGKILL)
                pid = int((self.base / 'run/core.pid').read_text())
                os.kill(pid, signal.SIGKILL)
                for proc in Path('/proc').iterdir():
                    if proc.name.isdigit():
                        try:
                            if 'fakecore.py' in (proc / 'cmdline').read_bytes().decode(errors='ignore'): os.kill(int(proc.name), signal.SIGKILL)
                        except OSError: pass
                time.sleep(0.4)
                status = json.loads(self.dispatch('status').stdout)
                self.assertFalse(status['running'])
                self.assertTrue(status['recoveredStaleRules'])
                self.assertEqual([], self.owned())

    def test_app_deploys_kind_assets_and_reads_controller_capability_per_core(self):
        manager = (PKG / 'RootProxyManager.java').read_text()
        # Kind file and Xray/V2Fly geo databases are deployed together with the binary.
        self.assertTrue('ROOT+"/run/state/core.kind"' in manager, 'ROOT+"/run/state/core.kind"')
        self.assertTrue('ROOT+"/bin/assets"' in manager, 'ROOT+"/bin/assets"')
        self.assertFalse('的运行后端还未接入' in manager, '的运行后端还未接入')
        config = (PKG / 'ProxyCoreConfig.java').read_text()
        # Never an SO_MARK on Android (netd owns the fwmark); native marks are stripped.
        self.assertFalse('"routing_mark", ' in config, '"routing_mark", ')
        self.assertTrue('stripKey(outbounds, "routing_mark")' in config, 'stripKey(outbounds, "routing_mark")')
        self.assertTrue('sockopt.remove("mark")' in config, 'sockopt.remove("mark")')
        profile = (PKG / 'ProxyRuntimeProfile.java').read_text()
        ids = dict(re.findall(r'([A-Z_]+)\("([a-z0-9-]+)", "[^"]+", set\(', profile))
        self.assertEqual(set(ALL_CORES), set(ids.values()))
        downloads = (PKG / 'ProxyCoreDownloadManager.kt').read_text()
        self.assertTrue('apernet/hysteria' in downloads, 'apernet/hysteria')
        self.assertFalse('暂不支持：' in downloads, '暂不支持：')

    def test_root_script_cli_shapes_match_the_app(self):
        text = (ASSETS / 'hetu-root.sh').read_text()
        for shape in ('"$BIN" -t -d "$RUN" -f "$CFG"', '"$START_BIN" -d "$RUN" -f "$START_CFG"',
                      'set -- check -c "$VN_CFG" -D "$VC_DIR" --disable-color', 'set -- run -test -c "$VN_CFG"',
                      'set -- test -c "$VN_CFG"', 'set -- run -c "$2" -D "$RUN" --disable-color', 'set -- run -c "$2"',
                      'set -- client -c "$2" --disable-update-check'):
            self.assertIn(shape, text)
        # Only the Clash-API cores keep a controller port in the session/health manifest.
        self.assertIn('kind_has_api(){ case "$1" in mihomo|sing-box) return 0;; *) return 1;; esac; }', text)


if __name__ == '__main__':
    unittest.main(verbosity=2)
