#!/usr/bin/env python3
"""Root monitor daemon (network match + traffic accounting while the App is killed) host regression.

No Android, device, Root or real network. The shipped hetu-root.sh runs with the CNIP harness of
test_cnip93.py (stateful netfilter model, fake core). A local HTTP server plays the core's Clash
API (/connections, Bearer secret); `ip route get`, `cmd wifi status`, `am` and `date +%s` are
fixtures, so the default network, the Wi-Fi identity, the App hand-off and the minute clock are
scripted.

* No $BASE/policy/monitor (or both switches off): start spawns no monitor, as before.
* With the policy the monitor runs under its own identity ("<script> monitor"), one instance only
  (second instance exits at once), and every exit path removes it: stop (TERM, also while a
  network script runs: the script and its timeout die too), a core exit handled by the watchdog,
  and a new start. Stop still leaves no HETU_ chain or set (network-recovery contract unchanged).
* Traffic: per-connection deltas (first poll is a baseline), aggregated per minute by app / host /
  destination IP / outbound / rule plus controller totals (which include closed connections);
  closed minutes and the open minute at stop reach the spool; traffic-take hands one batch out
  until traffic-ack confirms its id. curl and the nc HTTP/1.0 fallback both carry the secret.
* Network match: same OR semantics as NetworkMatchRules (SSID/BSSID, any cellular); the first
  stable decision is a baseline, a change acts only after the 2.5 s debounce, run_script runs in
  the daemon (env, exit code, scripts.log), stop / switch_config are handed to the App service via
  am start-foreground-service, and a flapping network is rate limited.
* App <-> daemon formats: the production DaemonMonitorPolicy.java (compiled verbatim) writes the
  policy the shell monitor evaluates, and parses the batch traffic-take prints.
"""
from pathlib import Path
import json
import os
import shlex
import shutil
import subprocess
import sys
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import test_cnip93 as cn  # noqa: E402

IP_WRAPPER = r'''#!/bin/sh
if [ "$2" = route ] && [ "$3" = get ]; then
  U=$(cat %(up)s 2>/dev/null) || exit 2
  [ -n "$U" ] || exit 2
  printf '%%s via 192.0.2.1 dev %%s table 1003 src 192.0.2.10 uid 0\n' "$4" "$U"; exit 0
fi
exec %(py)s %(model)s ip "$@"
'''
CMD_WRAPPER = r'''#!/bin/sh
if [ "$1" = wifi ] && [ "$2" = status ]; then
  S=$(cat %(ssid)s 2>/dev/null); B=$(cat %(bssid)s 2>/dev/null)
  echo "Wifi is enabled"
  [ -z "$S" ] || { echo "Wifi is connected to \"$S\""; echo "WifiInfo: SSID: \"$S\", BSSID: $B, MAC: 02:00:00:00:00:00, IP: /192.0.2.10"; }
fi
exit 0
'''
AM_WRAPPER = r'''#!/bin/sh
printf '%%s\n' "$*" >> %(log)s
echo "Starting service: Intent { act=$4 cmp=$2 }"
exit 0
'''
DATE_WRAPPER = r'''#!/bin/sh
if [ "$#" = 1 ] && [ "$1" = '+%%s' ] && [ -s %(epoch)s ]; then cat %(epoch)s; exit 0; fi
exec %(real)s "$@"
'''
# Plays toybox nc: request on stdin, raw response on stdout.
NC_PY = r'''
import socket, sys
args = [a for a in sys.argv[1:]]
port = int(args[-1]); host = args[-2]
data = sys.stdin.buffer.read()
s = socket.create_connection((host, port), timeout=4); s.sendall(data)
while True:
    b = s.recv(65536)
    if not b: break
    sys.stdout.buffer.write(b)
'''
SECRET = 'hetu-secret'
PKG = cn.SRC / 'java/io/github/xgl34222220/hetu'
# The production pure-Java policy/batch rules, compiled verbatim: the App writes what the shell
# monitor reads, and parses what traffic-take prints.
JAVA_HARNESS = r"""
package io.github.xgl34222220.hetu;
import java.nio.file.*; import java.util.*;
public final class DaemonPolicyHarness {
  public static void main(String[] a) throws Exception {
    if (a[0].equals("body")) {
      DaemonMonitorPolicy.Inputs in = new DaemonMonitorPolicy.Inputs();
      in.traffic = true; in.netmatch = true; in.apiPort = Integer.parseInt(a[1]); in.apiSecret = a[2];
      in.intervalSeconds = 3; in.ssids = Arrays.asList("Home", "Bad\nNETMATCH=0"); in.matchAction = "run_script:on-home.sh";
      in.unmatchAction = "stop"; in.mode = "tproxy"; in.config = "Home.yaml";
      System.out.print(DaemonMonitorPolicy.body(in));
    } else {
      DaemonMonitorPolicy.Batch b = DaemonMonitorPolicy.parseBatch(new String(Files.readAllBytes(Paths.get(a[1])), "UTF-8"));
      StringBuilder out = new StringBuilder();
      out.append("id=").append(b.id).append(" rejected=").append(b.rejected).append('\n');
      for (Map.Entry<Long, long[]> t : b.totals.entrySet()) out.append("T ").append(t.getKey()).append(' ').append(t.getValue()[0]).append(' ').append(t.getValue()[1]).append('\n');
      for (DaemonMonitorPolicy.Row r : b.rows) out.append("B ").append(r.minute).append(' ').append(r.dimension).append(' ').append(r.key).append(' ')
          .append(r.upload).append(' ').append(r.download).append(' ').append(r.connections).append('\n');
      System.out.print(out);
    }
  }
}
"""


def java_harness(folder):
    src = folder / 'jsrc/io/github/xgl34222220/hetu'; src.mkdir(parents=True)
    for name in ('DaemonMonitorPolicy.java', 'NetworkMatchRules.java'):
        (src / name).write_text((PKG / name).read_text())
    (src / 'DaemonPolicyHarness.java').write_text(JAVA_HARNESS)
    javac = ['javac'] if shutil.which('javac') else ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main']
    subprocess.run([*javac, '--release', '17', '-d', str(folder / 'jclasses'), *map(str, src.glob('*.java'))], check=True, timeout=120, capture_output=True, text=True)
    return lambda *args: subprocess.run(['java', '-cp', str(folder / 'jclasses'), 'io.github.xgl34222220.hetu.DaemonPolicyHarness', *args],
                                        check=True, timeout=60, capture_output=True, text=True).stdout


CTL_PY = r'''
import json, os, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
doc, log, secret = os.environ['HETU_CTL_DOC'], os.environ['HETU_CTL_LOG'], os.environ['HETU_CTL_SECRET']
class H(BaseHTTPRequestHandler):
    def do_GET(self):
        with open(log, 'a') as f: f.write(json.dumps([self.path, self.headers.get('Authorization')]) + '\n')
        if self.headers.get('Authorization') != 'Bearer ' + secret:
            self.send_response(401); self.end_headers(); return
        body = open(doc, 'rb').read()
        self.send_response(200); self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)
    def log_message(self, *a): pass
ThreadingHTTPServer(('127.0.0.1', int(os.environ['HETU_CTL_PORT'])), H).serve_forever()
'''


class Controller:
    """Clash API stand-in served by the fake core itself: /connections from a document file."""

    def __init__(self, folder):
        self.path = folder / 'connections.json'; self.log = folder / 'ctl.jsonl'
        self.doc = {'downloadTotal': 0, 'uploadTotal': 0, 'connections': [], 'memory': 1}
        self.publish()

    def update(self, **values):
        self.doc.update(values); self.publish()

    def publish(self):
        tmp = self.path.with_suffix('.tmp')
        tmp.write_text(json.dumps(self.doc, ensure_ascii=False, separators=(',', ':'))); tmp.replace(self.path)

    @property
    def requests(self):
        try: return [tuple(json.loads(l)) for l in self.log.read_text().splitlines()]
        except OSError: return []

    def clear(self):
        try: self.log.unlink()
        except OSError: pass


def conn(cid, up, down, host='', ip='203.0.113.5', process='', uid=None, chains=('NodeA', 'Proxy'), rule='DomainSuffix', payload='example.com'):
    meta = {'network': 'tcp', 'type': 'Tproxy', 'sourceIP': '10.0.0.2', 'destinationIP': ip, 'sourcePort': '40000',
            'destinationPort': '443', 'host': host, 'dnsMode': 'normal', 'process': process, 'processPath': '',
            'specialProxy': '', 'specialRules': '', 'remoteDestination': '', 'sniffHost': ''}
    if uid is not None: meta['uid'] = uid
    return {'id': cid, 'metadata': meta, 'upload': up, 'download': down, 'start': '2026-10-12T00:00:00Z',
            'chains': list(chains), 'rule': rule, 'rulePayload': payload}


class DaemonTraffic93(unittest.TestCase):
    setUp_cnip = cn.Cnip93.setUp
    tearDown_cnip = cn.Cnip93.tearDown
    run_cmd = cn.Cnip93.run_cmd
    status = cn.Cnip93.status
    read = staticmethod(cn.Cnip93.read)
    model = cn.Cnip93.model
    sets = cn.Cnip93.sets
    all_rules = cn.Cnip93.all_rules
    assert_clean = cn.Cnip93.assert_clean

    def setUp(self):
        self.setUp_cnip()
        self.env['HETU_FAKE_LISTEN'] = 'tcp:%d,udp:%d,tcp:%d,udp:%d' % (self.tp, self.tp, self.dp, self.dp)
        self.ctl = Controller(self.dir)
        (self.dir / 'ctl.py').write_text(CTL_PY)
        self.env.update(HETU_CTL_DOC=str(self.ctl.path), HETU_CTL_LOG=str(self.ctl.log), HETU_CTL_SECRET=SECRET, HETU_CTL_PORT=str(self.cp))
        core = self.base / 'bin/core'
        core.write_text('#!/bin/sh\n' + shlex.quote(sys.executable) + ' ' + shlex.quote(str(self.dir / 'fakecore.py')) + ' "$@" &\nC=$!\n'
                        + shlex.quote(sys.executable) + ' ' + shlex.quote(str(self.dir / 'ctl.py')) + ' &\nH=$!\n'
                        'trap \'kill "$C" "$H" 2>/dev/null; wait; exit 0\' TERM INT\nwait "$C"\n')
        self.up = self.dir / 'upstream'; self.up.write_text('wlan0\n')
        self.ssid = self.dir / 'ssid'; self.ssid.write_text('Cafe\n')
        self.bssid = self.dir / 'bssid'; self.bssid.write_text('aa:bb:cc:dd:ee:01\n')
        self.am_log = self.dir / 'am.log'
        self.epoch = self.dir / 'epoch'; self.epoch.write_text('1760000000\n')
        real_date = subprocess.run(['sh', '-c', 'command -v date'], capture_output=True, text=True).stdout.strip()
        q = shlex.quote
        for name, body in (('ip', IP_WRAPPER % {'up': q(str(self.up)), 'py': q(sys.executable), 'model': q(str(cn.MODEL))}),
                           ('cmd', CMD_WRAPPER % {'ssid': q(str(self.ssid)), 'bssid': q(str(self.bssid))}),
                           ('am', AM_WRAPPER % {'log': q(str(self.am_log))}),
                           ('date', DATE_WRAPPER % {'epoch': q(str(self.epoch)), 'real': q(real_date)}),
                           ('nc', '#!/bin/sh\nexec %s %s "$@"\n' % (q(sys.executable), q(str(self.dir / 'nc.py'))))):
            p = self.bin / name; p.write_text(body); p.chmod(0o700)
        (self.dir / 'nc.py').write_text(NC_PY)
        # Hot state in the temp tree instead of tmpfs /dev.
        text = self.script.read_text().replace('MONITOR_TMP_ROOT=/dev/hetu-monitor', 'MONITOR_TMP_ROOT=' + str(self.dir / 'tmpfs'))
        self.script.write_text(text)

    def tearDown(self):
        self.tearDown_cnip()

    # ------------------------------------------------------------------ helpers
    def policy(self, **values):
        body = {'VERSION': '1', 'GEN': 'test', 'TRAFFIC': '0', 'NETMATCH': '0', 'API_PORT': str(self.cp), 'API_SECRET': SECRET,
                'TRAFFIC_INTERVAL': '3', 'MODE': 'tproxy', 'CONFIG': 'Home.yaml'}
        lists = {k: values.pop(k) for k in ('SSID', 'BSSID', 'SLOT', 'MCCMNC') if k in values}
        body.update({k: str(v) for k, v in values.items()})
        lines = ['%s=%s' % kv for kv in body.items()]
        for key, items in lists.items(): lines += ['%s=%s' % (key, x) for x in items]
        (self.base / 'policy/monitor').write_text('\n'.join(lines) + '\n')

    def start(self):
        args = ['start', str(self.base / 'bin/core'), str(self.base / 'run/state/startup-config'), 'tproxy', str(self.tp), '0', 'enable', '1', '1',
                'redirect', '1', str(self.dp), str(self.cp), 'all', '', '0', '0', '', '', '', '1', '1', '', '', '1', '1', '0', '', '', '', '0']
        r = subprocess.run(['sh', str(self.script)] + args, env=self.env, capture_output=True, text=True, timeout=90)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertTrue(json.loads(r.stdout.strip().splitlines()[-1])['ok'], r.stdout)

    def stop(self):
        r = subprocess.run(['sh', str(self.script), 'stop'], env=self.env, capture_output=True, text=True, timeout=60)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertTrue(json.loads(r.stdout.strip().splitlines()[-1])['ok'], r.stdout)

    def monitor_pids(self):
        out = []
        for proc in Path('/proc').iterdir():
            if not proc.name.isdigit(): continue
            try: argv = (proc / 'cmdline').read_bytes().split(b'\0')
            except OSError: continue
            if str(self.script).encode() in argv and b'monitor' in argv: out.append(int(proc.name))
        return out

    def procs_with(self, needle):
        out = []
        for proc in Path('/proc').iterdir():
            if not proc.name.isdigit(): continue
            try:
                if needle in (proc / 'cmdline').read_bytes().decode(errors='ignore'): out.append(int(proc.name))
            except OSError: pass
        return out

    def monitor_status(self):
        r = self.run_cmd('monitor-status')
        return json.loads(r.stdout.strip().splitlines()[-1])

    def wait(self, predicate, timeout=20, step=0.2, what='condition'):
        end = time.time() + timeout
        while time.time() < end:
            value = predicate()
            if value: return value
            time.sleep(step)
        self.fail('timed out waiting for ' + what)

    def actions(self):
        return self.read(self.base / 'run/monitor/actions.log')

    def spool_blocks(self):
        r = self.run_cmd('traffic-take')
        self.assertEqual(0, r.returncode, r.stderr)
        return r.stdout

    @staticmethod
    def parse(batch):
        lines = batch.splitlines()
        rows, totals, minute = {}, {}, None
        for line in lines[1:]:
            f = line.split('\t')
            if f[0] == 'M': minute = int(f[1])
            elif f[0] == 'T': totals[minute] = (int(f[1]), int(f[2]))
            elif f[0] == 'B':
                key = (minute, f[1], f[2]); old = rows.get(key, (0, 0, 0))
                rows[key] = (old[0] + int(f[3]), old[1] + int(f[4]), old[2] + int(f[5]))
        return lines[0].split('\t') if lines else [], rows, totals

    # ------------------------------------------------------------------ tests
    def test_without_policy_no_monitor_and_stop_unchanged(self):
        self.start()
        time.sleep(0.5)
        self.assertEqual([], self.monitor_pids())
        self.assertFalse((self.base / 'run/monitor.pid').exists())
        self.assertFalse(self.monitor_status()['alive'])
        self.stop()
        self.assert_clean()
        # Both switches off is the same as no policy.
        self.policy(TRAFFIC=0, NETMATCH=0)
        self.start(); time.sleep(0.5)
        self.assertEqual([], self.monitor_pids())
        self.stop(); self.assert_clean()

    def test_traffic_minutes_by_dimension_and_stop_flushes_and_cleans(self):
        self.policy(TRAFFIC=1)
        c = self.ctl
        c.update(uploadTotal=1000, downloadTotal=5000,
                     connections=[conn('old', 900, 4000, host='before.example', process='com.old')])
        self.start()
        pids = self.wait(self.monitor_pids, what='monitor process')
        self.assertEqual(1, len(pids))
        st = self.monitor_status()
        self.assertTrue(st['alive']); self.assertTrue(st['traffic']); self.assertFalse(st['netmatch'])
        self.wait(lambda: len(c.requests) >= 1, what='first poll')
        self.assertEqual('Bearer ' + SECRET, c.requests[0][1])
        # Minute 1: "old" grows by 100/1000, a new connection appears (closed bytes only in totals).
        c.update(uploadTotal=1600, downloadTotal=8000,
                     connections=[conn('old', 1000, 5000, host='before.example', process='com.old'),
                                  conn('new', 300, 1200, host='', ip='198.51.100.7', uid=10123, chains=('DIRECT',), rule='Match', payload='')])
        n = len(c.requests); self.wait(lambda: len(c.requests) > n + 1, timeout=15, what='minute-1 polls')
        # Minute 2 starts: the first minute is flushed to the spool.
        self.epoch.write_text('1760000060\n')
        c.update(uploadTotal=1700, downloadTotal=8100,
                     connections=[conn('new', 400, 1300, ip='198.51.100.7', uid=10123, chains=('DIRECT',), rule='Match', payload='')])
        n = len(c.requests); self.wait(lambda: len(c.requests) > n + 1, timeout=15, what='minute-2 polls')
        self.stop()
        self.assertEqual([], self.monitor_pids(), 'stop leaves no monitor')
        self.assertFalse((self.base / 'run/monitor.pid').exists())
        self.assertFalse((self.base / 'run/.monitor.lock').exists())
        self.assert_clean()
        batch = self.spool_blocks()
        head, rows, totals = self.parse(batch)
        self.assertEqual(['HETU-TRAFFIC', '1'], head[:2])
        m1, m2 = 1759999980 + 0, 1760000040
        m1 = 1760000000 // 60 * 60; m2 = 1760000060 // 60 * 60
        self.assertEqual((100, 1000, 0), rows[(m1, 'app', 'com.old')], 'first poll is a baseline; only the growth counts')
        self.assertEqual((300, 1200, 1), rows[(m1, 'app', 'uid:10123')])
        self.assertEqual((100, 1000, 0), rows[(m1, 'host', 'before.example')])
        self.assertEqual((300, 1200, 1), rows[(m1, 'ip', '198.51.100.7')])
        self.assertEqual((100, 1000, 0), rows[(m1, 'outbound', 'NodeA')])
        self.assertEqual((300, 1200, 1), rows[(m1, 'outbound', 'DIRECT')])
        self.assertEqual((100, 1000, 0), rows[(m1, 'rule', 'DomainSuffix(example.com)')])
        self.assertEqual((300, 1200, 1), rows[(m1, 'rule', 'Match')])
        self.assertEqual((300, 1200, 1), rows[(m1, 'host', '-')], 'an empty host is kept as "-"')
        self.assertEqual((600, 3000), totals[m1], 'totals follow the controller counters, closed bytes included')
        # Open minute flushed by the TERM path at stop.
        self.assertEqual((100, 100, 0), rows[(m2, 'app', 'uid:10123')])
        self.assertEqual((100, 100), totals[m2])
        # The same batch until acknowledged; then nothing.
        again = self.spool_blocks()
        self.assertEqual(batch, again)
        r = self.run_cmd('traffic-ack', head[2]); self.assertEqual(0, r.returncode, r.stdout)
        self.assertEqual('', self.spool_blocks())

    def test_nc_fallback_carries_secret_and_wrong_secret_records_nothing(self):
        text = self.script.read_text().replace('MONITOR_HTTP_TOOLS="curl wget nc"', 'MONITOR_HTTP_TOOLS="nc"')
        self.script.write_text(text)
        self.policy(TRAFFIC=1)
        c = self.ctl
        c.update(uploadTotal=10, downloadTotal=10, connections=[conn('a', 1, 1, host='nc.example', process='com.nc')])
        self.start()
        self.wait(lambda: len(c.requests) >= 1, what='nc poll')
        self.assertEqual(('/connections', 'Bearer ' + SECRET), c.requests[0])
        c.update(uploadTotal=60, downloadTotal=90, connections=[conn('a', 51, 81, host='nc.example', process='com.nc')])
        n = len(c.requests); self.wait(lambda: len(c.requests) > n, timeout=15, what='nc second poll')
        self.stop()
        _, rows, totals = self.parse(self.spool_blocks())
        m = 1760000000 // 60 * 60
        self.assertEqual((50, 80, 0), rows[(m, 'host', 'nc.example')])
        # A wrong secret: requests are refused (401) and nothing is recorded.
        self.run_cmd('traffic-ack', self.parse(self.spool_blocks())[0][2])
        self.policy(TRAFFIC=1, API_SECRET='wrong')
        c.clear(); self.start()
        self.wait(lambda: len(c.requests) >= 2, timeout=15, what='refused polls')
        self.stop()
        self.assertEqual('', self.spool_blocks())

    def test_single_instance_identity_and_core_exit_stops_monitor(self):
        self.policy(TRAFFIC=1)
        self.start()
        pid = self.wait(self.monitor_pids, what='monitor')[0]
        self.assertEqual(str(pid), self.read(self.base / 'run/monitor.pid').strip())
        gen = self.read(self.base / 'run/generation').strip()
        r = subprocess.run(['sh', str(self.script), 'monitor', gen], env=self.env, capture_output=True, text=True, timeout=10)
        self.assertEqual(0, r.returncode)
        self.assertEqual([pid], self.monitor_pids(), 'a second instance exits at once')
        # A stale generation never starts.
        r = subprocess.run(['sh', str(self.script), 'monitor', 'stale-1'], env=self.env, capture_output=True, text=True, timeout=10)
        self.assertEqual([pid], self.monitor_pids())
        # A new start replaces the monitor (stopwatchdog stops it first).
        self.start()
        new = self.wait(lambda: [p for p in self.monitor_pids() if p != pid], what='replacement monitor')
        self.assertNotIn(pid, self.monitor_pids())
        self.assertEqual(1, len(self.monitor_pids()))
        # Core exits: the watchdog restores the network and the monitor is gone.
        core = int(self.read(self.base / 'run/core.pid').strip())
        os.kill(core, 9)
        self.wait(lambda: not self.monitor_pids(), timeout=20, what='monitor exit after core exit')
        self.wait(lambda: 'network-restored' in self.read(self.base / 'run/watchdog.log'), timeout=20, what='watchdog restore')
        self.assertFalse(self.monitor_status()['alive'])
        self.assert_clean()
        self.assertNotIn(new[0], self.monitor_pids())

    def test_network_match_baseline_debounce_script_handoff_and_limit(self):
        (self.base / 'scripts/on-home.sh').write_text('echo "event=$HETU_EVENT network=$HETU_NETWORK mode=$HETU_MODE config=$HETU_CONFIG origin=$HETU_ORIGIN"\nexit 3\n')
        self.policy(NETMATCH=1, SSID=['Home'], MATCH_ACTION='run_script:on-home.sh', UNMATCH_ACTION='stop')
        self.start()
        self.wait(self.monitor_pids, what='monitor')
        self.assertTrue(self.monitor_status()['netmatch'])
        self.wait(lambda: 'baseline match=0 action=stop' in self.actions(), what='baseline')
        time.sleep(2.5)
        self.assertFalse(self.am_log.exists(), 'the decision the App already acted on is not repeated')
        # Home: matched -> run_script after the debounce, inside the daemon.
        self.ssid.write_text('Home\n')
        self.wait(lambda: 'script=on-home.sh exit=3' in self.actions(), timeout=20, what='script run')
        log = self.read(self.base / 'run/scripts.log')
        self.assertIn('event=network-match network=Wi‑Fi · Home · aa:bb:cc:dd:ee:01 mode=tproxy config=Home.yaml origin=root-monitor', log)
        self.assertIn('[network-match] exit=3', log)
        # Cellular: unmatched -> stop handed to the App service.
        self.up.write_text('rmnet_data0\n')
        self.wait(lambda: self.am_log.exists() and 'kind stop' in self.read(self.am_log), timeout=20, what='stop hand-off')
        call = self.read(self.am_log)
        self.assertIn('start-foreground-service -n io.github.xgl34222220.hetu/.ProxyNetworkMatchService -a io.github.xgl34222220.hetu.DAEMON_NETWORK_ACTION', call)
        self.assertIn('--es label 移动数据', call)
        self.assertIn('handoff=stop', self.actions())
        self.assertIn('"lastAction":', json.dumps(self.monitor_status(), ensure_ascii=False))
        # Flapping between Home and cellular: the script runs at most once more inside 30 s.
        before = self.read(self.base / 'run/scripts.log').count('[network-match] exit=')
        for _ in range(2):
            self.up.write_text('wlan0\n'); time.sleep(6.5)
            self.up.write_text('rmnet_data0\n'); time.sleep(6.5)
        after = self.read(self.base / 'run/scripts.log').count('[network-match] exit=')
        self.assertLessEqual(after - before, 1)
        self.assertIn('rate-limited', self.actions())
        self.stop(); self.assertEqual([], self.monitor_pids()); self.assert_clean()

    def test_switch_config_handoff_and_stop_kills_running_script(self):
        (self.base / 'scripts/slow.sh').write_text('echo started\nsleep 30\necho never\n')
        self.policy(NETMATCH=1, SSID=['Home'], MATCH_ACTION='run_script:slow.sh', UNMATCH_ACTION='switch_config:Away.yaml')
        self.ssid.write_text('Home\n')
        self.start()
        self.wait(lambda: 'baseline match=1' in self.actions(), what='baseline')
        self.ssid.write_text('Cafe\n')
        self.wait(lambda: self.am_log.exists() and 'kind switch' in self.read(self.am_log), timeout=20, what='switch hand-off')
        self.assertIn('--es target Away.yaml', self.read(self.am_log))
        self.ssid.write_text('Home\n')
        self.wait(lambda: 'started' in self.read(self.base / 'run/scripts.log'), timeout=20, what='slow script running')
        self.assertTrue(self.procs_with('slow.sh'))
        t = time.time(); self.stop()
        self.assertLess(time.time() - t, 15)
        self.assertEqual([], self.monitor_pids())
        self.wait(lambda: not self.procs_with('slow.sh'), timeout=5, what='script killed with the monitor')
        self.assertNotIn('never', self.read(self.base / 'run/scripts.log'))
        self.assert_clean()

    def test_java_policy_drives_monitor_and_java_parses_take_output(self):
        java = java_harness(self.dir)
        body = java('body', str(self.cp), SECRET)
        self.assertNotIn('NETMATCH=0', body, 'a crafted SSID cannot add a line')
        (self.base / 'policy/monitor').write_text(body)
        (self.base / 'scripts/on-home.sh').write_text('exit 0\n')
        self.ssid.write_text('Home\n')
        c = self.ctl
        c.update(uploadTotal=10, downloadTotal=20, connections=[conn('j', 1, 1, host='java.example', process='com.java')])
        self.start()
        st = self.monitor_status()
        self.assertTrue(st['alive'] and st['netmatch'] and st['traffic'])
        self.assertEqual(body.splitlines()[0][4:], st['policy'], 'monitor-status reports the exported generation')
        self.wait(lambda: 'baseline match=1 action=run_script:on-home.sh' in self.actions(), what='Java rules evaluated by the shell')
        self.wait(lambda: len(c.requests) >= 1, what='poll')
        c.update(uploadTotal=40, downloadTotal=90, connections=[conn('j', 21, 51, host='java.example', process='com.java')])
        n = len(c.requests); self.wait(lambda: len(c.requests) > n, timeout=15, what='second poll')
        self.stop()
        take = self.base / 'take.txt'; take.write_text(self.spool_blocks())
        parsed = java('batch', str(take)).splitlines()
        m = 1760000000 // 60 * 60
        self.assertTrue(parsed[0].startswith('id=') and parsed[0].endswith(' rejected=0'), parsed[0])
        self.assertIn('T %d 30 70' % m, parsed)
        for dim, key in (('app', 'com.java'), ('host', 'java.example'), ('ip', '203.0.113.5'), ('outbound', 'NodeA'), ('rule', 'DomainSuffix(example.com)')):
            self.assertIn('B %d %s %s 20 50 0' % (m, dim, key), parsed)

if __name__ == '__main__':
    unittest.main()
