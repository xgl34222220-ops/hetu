#!/usr/bin/env python3
"""6.0.0 foreign-only outage host regression for hetu-root.sh (no Root, no device).

Field report: "用着用着突然没网，停止重启还是没网，测速是正常的": only foreign sites fail, domestic apps
work, node delay tests are normal, on every core, on Wi-Fi and mobile data, and stop/start or several
restarts do not help. Until 6.0.0 the watchdog had no verdict for exactly this state (a failing probe
while every proxy group answers its delay test was "skip").

* One failure mode that matches every symptom is host-reproduced with Mihomo v1.19.31: when another
  process holds the lock on $RUN/cache.db for more than Mihomo's 1 s wait, Mihomo logs "[CacheFile]
  can't open cache file: timeout" and runs without it for its whole life; with the persisted fake-IP
  pool (Hetu turns store-fake-ip on) it then hands out a new fake IP for the same name on every query
  and resets every fake-IP connection, while real-IP traffic and delay tests keep working. sing-box
  1.14.3 refuses to start instead ("initialize cache-file: timeout").
* Start: before a Mihomo or sing-box core launches, Hetu's own core processes holding the lock end and
  a lock that stays held moves the file aside (kept as cache.db.held-<time>).
* Watchdog: a failing probe is followed by the same name resolved again (a new address = fake-IP record
  lost), the core log's cache warning, and the same request through Hetu's loopback HTTP listener in
  the core (rules, groups, nodes; no TPROXY, no fake IP). New verdicts: fakeip-lost (restart at once,
  within the restart budget), dataplane-down when the listener answers, route-down when it fails too;
  route-down heals a group/node named by the core log like a dead node and only reports DIRECT, a
  reject or an empty group. Proxied connections still receiving data leave the probe without verdict.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from urllib.parse import unquote, urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_netloss93 import HELPERS, ShellCase, TEXT, function, shq, top_assignment  # noqa: E402

NEW = ('wd_fetch', 'wd_fetch_proxied', 'wd_route_seen', 'wd_cache_lost', 'wd_resolve', 'wd_in_cidr', 'wd_scan_groups',
       'wd_dp_probe', 'wd_dp_classify', 'wd_egress_state', 'wd_fakeip_heal', 'wd_route_heal', 'wd_dataplane_tick',
       'foreign_tun_links', 'cache_lock_pids', 'core_cache_guard')
DP_VARS = ('WD_DP_GRACE', 'WD_DP_EVERY', 'WD_DP_RETRY', 'WD_DP_LIMIT', 'WD_DP_TIMEOUT', 'WD_DP_HOST', 'WD_DP_PATH',
           'WD_DP_CN_HOSTS', 'WD_DP_GROUP_MAX', 'WD_DP_BUDGET', 'WD_DP_EGRESS_OFFSET')
FISH = '\U0001F41F 漏网之鱼'
ROCKET = '\U0001F680 节点选择'
MIHOMO_DIAL = ('time="2026-10-19T13:25:44Z" level=warning msg="[TCP] dial %s (match Match/) 127.0.0.1:45332 --> '
               'www.gstatic.com:80 error: 127.0.0.1:1 connect error: dial tcp 127.0.0.1:1: connect: connection refused"')


class Case(ShellCase):
    """ShellCase plus the data-plane functions, the core log path and a `cmd` that records notifications."""

    def setUp(self):
        super().setUp()
        self.bin = self.dir / 'fakebin'
        self.bin.mkdir()
        self.notes = self.dir / 'notify.log'
        (self.bin / 'cmd').write_text('#!/bin/sh\nprintf "%%s\\n" "$*" >> %s\n' % shq(self.notes))
        (self.bin / 'cmd').chmod(0o755)

    def prelude(self, extra=''):
        text = super().prelude('') + 'SYSTEM_DNS=exempt\nFAKE_IP_V4=""\n' + ''.join(top_assignment(v) for v in DP_VARS)
        text += ''.join(function(n) for n in NEW if n not in HELPERS)
        return text + extra

    def sh(self, body, extra='', env=None, timeout=60):
        env = {'PATH': str(self.bin) + ':' + os.environ['PATH'], **(env or {})}
        return super().sh(body + '\nwait\n', extra=extra, env=env, timeout=timeout)

    def notifications(self):
        return self.notes.read_text().splitlines() if self.notes.exists() else []


class Classify(unittest.TestCase):
    def test_new_verdicts(self):
        cases = [(('', '', '3', '', '1'), 'fakeip-lost'), (('fail', 'G→n', '3', '', '1'), 'fakeip-lost'),
                 (('', '', '3', 'ok', '0'), 'dataplane-down'), (('ok', '', '3', 'ok', ''), 'dataplane-down'),
                 (('', '', '0', 'fail', '0'), 'route-down'), (('ok', '', '3', 'fail', ''), 'route-down'),
                 (('', 'G→n', '3', 'fail', '0'), 'proxy-down'), (('fail', '', '3', 'fail', '0'), 'dataplane-down'),
                 (('', '', '3', '', '0'), 'skip'), (('ok', '', '3', '', ''), 'skip')]
        script = function('wd_dp_classify') + ''.join('wd_dp_classify %s\n' % ' '.join(shq(a) for a in args) for args, _ in cases)
        r = subprocess.run(['/bin/sh', '-c', script], capture_output=True, text=True, check=True)
        self.assertEqual(r.stdout.split(), [want for _, want in cases], r.stderr)


class Proxy:
    """Hetu's loopback HTTP listener stand-in: answers by host like Mihomo/sing-box (502 when the dial fails)."""

    def __init__(self):
        self.requests = []
        owner = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_GET(self):
                owner.requests.append((self.path, self.headers.get('Host', '')))
                host = urlsplit(self.path).hostname or ''
                if host == 'hang.test':
                    time.sleep(6)
                    return
                code = {'ok.test': 204, 'redirect.test': 302, 'fail.test': 502}.get(host, 503)
                self.send_response(code)
                self.send_header('Content-Length', '0')
                self.send_header('Connection', 'close')
                self.end_headers()

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), H)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


class ProxiedFetch(Case):
    TOOLS = {'curl': '', 'nc': 'has(){ [ "$1" = curl ] && return 1; command -v "$1" >/dev/null 2>&1; }\n'}

    def test_answers_count_and_proxy_errors_or_silence_do_not(self):
        p = Proxy()
        self.cleanups.append(p.close)
        for tool, stub in self.TOOLS.items():
            for host, want in (('ok.test', 0), ('redirect.test', 0), ('fail.test', 1), ('hang.test', 1), ('other.test', 1)):
                with self.subTest(tool=tool, host=host):
                    p.requests.clear()
                    r = self.sh('WD_DP_TIMEOUT=2; WD_TMP="$RUN/.wd"; wd_fetch_proxied %s /generate_204 %d; echo "rc=$?"' % (host, p.port),
                                extra=stub, timeout=30)
                    self.assertIn('rc=%d' % want, r.stdout, r.stderr)
                    self.assertEqual(p.requests[0], ('http://%s/generate_204' % host, host), 'absolute URI and Host')
        r = self.sh('wd_fetch_proxied ok.test / %d; echo "rc=$?"' % p.port, extra='has(){ return 1; }\n')
        self.assertIn('rc=2', r.stdout, 'no tool is no verdict')


class RouteSeen(Case):
    LINES = {
        MIHOMO_DIAL % FISH: (FISH, '', 'Match', '127.0.0.1:1 connect error: dial tcp 127.0.0.1:1: connect: connection refused'),
        'time="x" level=info msg="[TCP] 127.0.0.1:59054(curl) --> www.gstatic.com:80 match GeoSite(google) using %s[香港 01]"' % ROCKET:
            (ROCKET, '香港 01', 'GeoSite(google)', ''),
        'time="x" level=info msg="[TCP] 127.0.0.1:59054 --> www.gstatic.com:80 match Match using DIRECT"': ('DIRECT', '', 'Match', ''),
        'time="x" level=warning msg="[TCP] dial DIRECT 127.0.0.1:1 --> www.gstatic.com:80 error: i/o timeout"': ('DIRECT', '', '', 'i/o timeout'),
        'ERROR[0001] [2664767641 2ms] inbound/http[hetu-egress-probe]: process connection from 127.0.0.1:34844: (open connection to '
        'www.gstatic.com:80 using outbound/socks[dead]: dial tcp 127.0.0.1:1: connect: connection refused | Get "x": EOF)':
            ('dead', '', '', 'dial tcp 127.0.0.1:1: connect: connection refused | Get "x": EOF)'),
        'INFO[0001] [1 3ms] outbound/vless[香港 01]: outbound connection to www.gstatic.com:80': ('香港 01', '', '', ''),
    }

    def seen(self, before, line):
        log = self.run_dir / 'core.log'
        log.write_bytes(before.encode() + line.encode() + b'\n')
        r = self.sh('wd_route_seen www.gstatic.com %d; echo "rc=$?"; printf "%%s\\t%%s\\t%%s\\t%%s\\n" "$WD_ROUTE_VIA" "$WD_ROUTE_NODE" "$WD_ROUTE_RULE" "$WD_ROUTE_ERR"'
                    % len(before.encode()))
        rc, fields = r.stdout.splitlines()[-2:]
        return rc, tuple(fields.split('\t'))

    def test_mihomo_and_sing_box_lines(self):
        old = 'time="x" level=info msg="[TCP] 1.1.1.1:1 --> www.gstatic.com:80 match Old using OLD[stale]"\n'
        for line, want in self.LINES.items():
            with self.subTest(line=line[:60]):
                self.assertEqual(self.seen(old, line), ('rc=0', want))

    def test_lines_before_the_probe_and_other_hosts_are_ignored(self):
        old = MIHOMO_DIAL % FISH + '\n'
        self.assertEqual(self.seen(old, 'time="x" level=info msg="[TCP] 1.1.1.1:1 --> www.baidu.com:80 match Match using DIRECT"')[0], 'rc=1')
        self.assertEqual(self.seen('', 'plain text without a route')[0], 'rc=1')


class CacheLost(Case):
    def test_only_an_open_failure_counts(self):
        log = self.run_dir / 'core.log'
        for text, want in (('time="x" level=warning msg="[CacheFile] can\'t open cache file: timeout"\n', 0),
                           ('time="x" level=warning msg="[CacheFile] write cache to /run/cache.db failed: x"\n', 1),
                           ('time="x" level=info msg="Start initial configuration in progress"\n', 1)):
            log.write_text(text)
            r = self.sh('wd_cache_lost; echo "rc=$?"')
            self.assertIn('rc=%d' % want, r.stdout, r.stderr)
        log.unlink()
        self.assertIn('rc=1', self.sh('wd_cache_lost; echo "rc=$?"').stdout)


PROBE_STUBS = r'''
wd_resolve(){
  WRN=$(cat "$RUN/resolve.n" 2>/dev/null || echo 0); WRN=$((WRN+1)); echo "$WRN" > "$RUN/resolve.n"
  case "$1" in
    www.gstatic.com) if [ -f "$RUN/lost" ]; then WD_RES_IP="198.18.0.$((3+WRN))"; else WD_RES_IP=198.18.0.7; fi;;
    *) WD_RES_IP=203.0.113.5;;
  esac
}
wd_fetch(){ return 1; }
wd_fetch_proxied(){ echo "$3" >> "$RUN/proxied.calls"; [ ! -f "$RUN/proxied.ok" ] || return 0; [ ! -f "$RUN/proxied.line" ] || cat "$RUN/proxied.line" >> "$LOG"; return 1; }
'''


class Probe(Case):
    def probe(self, c, files=()):
        self.session(cp=c.port, dp=5353)
        for name, text in files:
            (self.run_dir / name).write_text(text)
        body = ('COREPID=$$; wd_selfheal_init; WD_EG_ON=1; WD_DP=5353; WD_FAKE4=198.18.0.0/16; WD_BUSY_GAP=0; WD_TMP="$RUN/.wd"; '
                ': >> "$LOG"; wd_dp_probe; printf "class=%s\\nvia=%s\\ndetail=%s\\n" "$WD_DP_CLASS" "$WD_ROUTE_VIA" "$WD_DP_DETAIL"')
        r = self.sh(body, extra=PROBE_STUBS)
        out = dict(line.split('=', 1) for line in r.stdout.splitlines() if '=' in line)
        out['stderr'] = r.stderr
        return out

    def fixture(self):
        c = self.controller()
        c.groups = [('Selector', FISH, 'DIRECT', ''), ('URLTest', ROCKET, '香港 01', '')]
        return c

    def test_a_new_address_for_the_same_name_is_fakeip_lost_without_asking_the_listener(self):
        c = self.fixture()
        out = self.probe(c, [('lost', '')])
        self.assertEqual(out['class'], 'fakeip-lost', out)
        self.assertIn('fake-IP record lost', out['detail'])
        self.assertFalse((self.run_dir / 'proxied.calls').exists())

    def test_the_core_log_cache_warning_is_fakeip_lost(self):
        c = self.fixture()
        out = self.probe(c, [('core.log', 'time="x" level=warning msg="[CacheFile] can\'t open cache file: timeout"\n')])
        self.assertEqual(out['class'], 'fakeip-lost', out)
        self.assertIn('cache file could not be opened', out['detail'])

    def test_listener_answers_while_captured_requests_fail_is_dataplane_down(self):
        c = self.fixture()
        out = self.probe(c, [('proxied.ok', '')])
        self.assertEqual(out['class'], 'dataplane-down', out)
        self.assertIn("through the core's own listener it answers", out['detail'])
        self.assertEqual((self.run_dir / 'proxied.calls').read_text().split(), [str(c.port + 60)], 'controller port + 60')

    def test_listener_failing_too_is_route_down_with_what_the_core_log_says(self):
        c = self.fixture()
        out = self.probe(c, [('proxied.line', MIHOMO_DIAL % FISH + '\n')])
        self.assertEqual(out['class'], 'route-down', out)
        self.assertEqual(out['via'], FISH)
        self.assertIn('rule Match, via %s' % FISH, out['detail'])

    def test_proxied_downloads_still_moving_leave_it_without_verdict(self):
        c = self.fixture()
        c.connections = [('dl', ['香港 01', ROCKET]), ('cn', ['DIRECT'])]
        c.moving = {'dl'}
        out = self.probe(c, [('proxied.ok', '')])
        self.assertEqual(out['class'], 'skip', out)
        self.assertIn('1 proxied connection(s) receiving data', out['detail'])
        c.moving = {'cn'}
        self.assertEqual(self.probe(c, [('proxied.ok', '')])['class'], 'dataplane-down', 'a direct download does not count')


class RouteHeal(Case):
    def heal(self, c, via, node='', rule='Match', twice=False):
        self.session(cp=c.port)
        call = 'WD_ROUTE_VIA=%s; WD_ROUTE_NODE=%s; WD_ROUTE_RULE=%s; WD_DP_DETAIL=probe; wd_route_heal; ' % (shq(via), shq(node), shq(rule))
        body = 'COREPID=$$; wd_selfheal_init; WD_BUSY_GAP=0; WD_TMP="$RUN/.wd"; ' + call + (call if twice else '') + \
               'echo "notified=$WD_ROUTE_NOTIFIED node=$WD_NODE_NOTIFIED"'
        return self.sh(body)

    def test_a_group_on_direct_is_reported_once_and_nothing_is_changed(self):
        c = self.controller()
        c.groups = [('Selector', FISH, 'DIRECT', '')]
        c.connections = [('x', ['DIRECT', FISH])]
        r = self.heal(c, FISH, twice=True)
        self.assertIn('notified=1', r.stdout, r.stderr)
        notes = self.notifications()
        self.assertEqual(len(notes), 1, notes)
        self.assertIn('「Match」', notes[0])
        self.assertIn('「%s」，它现在实际走的是直连（DIRECT）' % FISH, notes[0])
        self.assertFalse([1 for m, p, a in c.requests if m == 'DELETE' or p.startswith('/group/')])
        self.assertIn('is routed to DIRECT (rule Match, target %s); configuration choice, nothing changed' % FISH, self.log_text())

    def test_an_empty_group_and_a_reject_say_so(self):
        c = self.controller()
        c.groups = [('Selector', '空组', 'COMPATIBLE', '')]
        self.heal(c, '空组')
        self.assertIn('空策略组', self.notifications()[-1])
        self.notes.unlink()
        self.heal(c, 'REJECT', rule='GeoSite(google)')
        self.assertIn('规则「GeoSite(google)」把外网请求直接设为拒绝（REJECT）', self.notifications()[-1])

    def test_a_group_on_a_node_is_healed_like_a_dead_node(self):
        c = self.controller()
        c.groups = [('Selector', ROCKET, '香港 01', '')]
        c.connections = [('hk', ['香港 01', ROCKET]), ('cn', ['DIRECT'])]
        r = self.heal(c, ROCKET)
        self.assertIn('notified=0 node=1', r.stdout, r.stderr)
        self.assertEqual(c.closed, ['hk'])
        self.assertIn('代理节点无响应', self.notifications()[0])

    def test_without_a_log_line_only_the_finding_is_reported(self):
        c = self.controller()
        self.heal(c, '')
        self.assertIn('直接通过河图核心访问 www.gstatic.com 也失败', self.notifications()[0])
        self.assertFalse([1 for m, p, a in c.requests if m == 'DELETE' or p.startswith('/group/')])


class FakeipHeal(Case):
    RESTART_OK = 'wd_restart_core(){ echo restart >> "$RUN/restart.calls"; return 0; }\n'
    RESTART_FAIL = 'wd_restart_core(){ echo restart >> "$RUN/restart.calls"; return 1; }\n'

    def calls(self):
        f = self.run_dir / 'restart.calls'
        return f.read_text().split() if f.exists() else []

    def test_restart_at_once_and_say_what_happened(self):
        c = self.controller()
        self.session(cp=c.port)
        r = self.sh('COREPID=$$; wd_selfheal_init; WD_TMP="$RUN/.wd"; WD_DP_DETAIL=probe; wd_fakeip_heal; echo after', extra=self.RESTART_OK)
        self.assertEqual(self.calls(), ['restart'], r.stderr)
        self.assertNotIn('after', r.stdout, 'a restarted core has its own watchdog')
        self.assertIn('已自动重启核心', self.notifications()[0])
        self.assertIn('lost its fake-IP records (probe); restarting the core', self.log_text())

    def test_no_restart_left_is_reported_once(self):
        c = self.controller()
        self.session(cp=c.port)
        boot = Path('/proc/sys/kernel/random/boot_id').read_text().strip()
        (self.run_dir / 'state/selfheal-budget').write_text('%s 3 %d\n' % (boot, int(Path('/proc/uptime').read_text().split('.')[0])))
        r = self.sh('COREPID=$$; wd_selfheal_init; WD_TMP="$RUN/.wd"; wd_fakeip_heal; wd_fakeip_heal; echo "n=$WD_FAKEIP_NOTIFIED"',
                    extra=self.RESTART_OK)
        self.assertIn('n=1', r.stdout, r.stderr)
        self.assertEqual(self.calls(), [])
        self.assertEqual(len(self.notifications()), 1)
        self.assertIn('自动重启核心没有成功', self.notifications()[0])

    def tick(self, verdicts, extra=''):
        c = self.controller()
        self.session(cp=c.port)
        (self.run_dir / 'verdicts').write_text('\n'.join(verdicts) + '\n')
        (self.run_dir / 'heal.calls').unlink(missing_ok=True)
        stub = ('wd_dp_probe(){ WD_DP_CLASS=$(head -n 1 "$RUN/verdicts"); sed -i 1d "$RUN/verdicts"; WD_DP_DETAIL=probe; }\n'
                'wd_route_heal(){ echo route >> "$RUN/heal.calls"; }\nwd_dataplane_heal(){ echo dataplane >> "$RUN/heal.calls"; }\n')
        body = 'COREPID=$$; wd_selfheal_init; WD_TMP="$RUN/.wd"; for i in %s; do wd_now; wd_dataplane_tick; done; echo done' % \
               ' '.join(str(i) for i in range(len(verdicts)))
        r = self.sh(body, extra=stub + extra)
        heals = (self.run_dir / 'heal.calls')
        return r, heals.read_text().split() if heals.exists() else []

    def test_tick_acts_on_the_first_fakeip_verdict_and_needs_two_route_verdicts(self):
        r, heals = self.tick(['fakeip-lost'], extra=self.RESTART_FAIL)
        self.assertEqual(self.calls(), ['restart'], r.stderr)
        self.assertIn('fakeip-lost', (self.run_dir / 'egress-state').read_text())
        (self.run_dir / 'restart.calls').unlink()
        r, heals = self.tick(['route-down'])
        self.assertEqual(heals, [], 'one verdict is not enough')
        r, heals = self.tick(['route-down', 'route-down'])
        self.assertEqual(heals, ['route'], r.stderr)
        r, heals = self.tick(['dataplane-down', 'dataplane-down'])
        self.assertEqual(heals, ['dataplane'], r.stderr)


class SelfhealTickCache(Case):
    def run_tick(self, log, config_extra):
        c = self.controller()
        self.session(cp=c.port)
        cfg = self.run_dir / 'state/startup-config'
        cfg.write_text(cfg.read_text() + config_extra)
        (self.run_dir / 'core.log').write_text(log)
        (self.run_dir / 'heal.calls').unlink(missing_ok=True)
        stub = 'wd_fakeip_heal(){ echo heal >> "$RUN/heal.calls"; }\n'
        r = self.sh('COREPID=$$; wd_selfheal_init; WD_EG_ON=0; WD_DP_ON=0; WD_TMP="$RUN/.wd"; wd_selfheal_tick; wd_selfheal_tick; echo ok',
                    extra=stub)
        heals = self.run_dir / 'heal.calls'
        return r, heals.read_text().split() if heals.exists() else []

    def test_a_core_without_its_cache_file_is_restarted_once_at_its_first_tick(self):
        locked = 'time="x" level=warning msg="[CacheFile] can\'t open cache file: timeout"\n'
        r, heals = self.run_tick(locked, 'profile:\n  store-fake-ip: true\n')
        self.assertEqual(heals, ['heal'], r.stderr)
        self.assertIn('runs without its cache file', self.log_text())
        r, heals = self.run_tick(locked, 'profile:\n  store-fake-ip: false\n')
        self.assertEqual(heals, [], 'an in-memory fake-IP pool does not need the file')
        r, heals = self.run_tick('time="x" level=info msg="ok"\n', 'profile:\n  store-fake-ip: true\n')
        self.assertEqual(heals, [])


@unittest.skipUnless(shutil.which('flock'), 'needs util-linux flock')
class CacheGuard(Case):
    def setUp(self):
        super().setUp()
        (self.dir / 'bin').mkdir()
        self.db = self.run_dir / 'cache.db'
        self.db.write_bytes(b'bolt')
        self.holders = []

    def tearDown(self):
        for h in self.holders:
            if h.poll() is None:
                h.kill()
            h.wait()
        super().tearDown()

    def hold(self, exe='flock'):
        h = subprocess.Popen([exe, '-o', str(self.db), 'sleep', '30'])
        self.holders.append(h)
        for _ in range(100):
            if str(h.pid) in self.sh('cache_lock_pids "$RUN/cache.db"').stdout.split():
                return h
            time.sleep(0.05)
        self.fail('holder never took the lock')

    def guard(self, kind='mihomo'):
        return self.sh('START_KIND=%s; core_cache_guard; echo "note=[$CACHE_GUARD_NOTE]"' % kind)

    def test_nothing_happens_without_a_holder(self):
        self.assertEqual(self.sh('cache_lock_pids "$RUN/cache.db"').stdout, '')
        r = self.guard()
        self.assertIn('note=[]', r.stdout, r.stderr)
        self.assertEqual(self.db.read_bytes(), b'bolt')

    def test_another_processs_lock_moves_the_file_aside_and_keeps_it(self):
        h = self.hold()
        r = self.guard()
        self.assertIn('（PID %d）占用，已换用新的缓存文件' % h.pid, r.stdout, r.stderr)
        self.assertFalse(self.db.exists())
        held = list(self.run_dir.glob('cache.db.held-*'))
        self.assertEqual([p.read_bytes() for p in held], [b'bolt'])
        self.assertIsNone(h.poll(), 'a process that is not Hetu\'s core is never signalled')
        self.assertIn('stays locked by pid(s) %d; moved to' % h.pid, self.log_text())

    def test_hetus_own_core_holding_it_ends_and_the_file_stays(self):
        core = self.dir / 'bin/core'
        shutil.copy(shutil.which('flock'), core)
        h = self.hold(str(core))
        r = self.guard('sing-box')
        self.assertIn('note=[]', r.stdout, r.stderr)
        self.assertEqual(self.db.read_bytes(), b'bolt')
        self.assertIsNotNone(h.wait(timeout=5))
        self.assertIn("was locked by Hetu's own process(es) %d; ended them" % h.pid, self.log_text())

    def test_other_cores_do_not_use_the_file(self):
        h = self.hold()
        self.assertIn('note=[]', self.guard('xray').stdout)
        self.assertTrue(self.db.exists())
        self.assertIsNone(h.poll())

    def test_start_runs_the_guard_right_before_the_core_and_reports_it(self):
        start = function('start')
        self.assertLess(start.index('core_cache_guard'), start.index(': > "$LOG" || fail'))
        self.assertLess(start.index('stopcore || fail'), start.index('core_cache_guard'))
        self.assertIn('[ -z "${CACHE_GUARD_NOTE:-}" ] || DESC="$DESC；注意：$CACHE_GUARD_NOTE"', start)


def dns_query(port, name):
    labels = b''.join(bytes([len(p)]) + p.encode() for p in name.split('.')) + b'\x00'
    msg = b'\x00\x07\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00' + labels + b'\x00\x01\x00\x01'
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.settimeout(3)
        s.sendto(msg, ('127.0.0.1', port))
        return socket.inet_ntoa(s.recvfrom(512)[0][-4:])


@unittest.skipUnless(os.environ.get('HETU_TEST_MIHOMO') and shutil.which('flock'), 'set HETU_TEST_MIHOMO to a Mihomo binary')
class RealMihomo(Case):
    """End to end with a real Mihomo: the failure, the guard that prevents it and the probe that sees it."""

    CONFIG = ('log-level: info\nexternal-controller: 127.0.0.1:%(cp)d\nprofile:\n  store-fake-ip: true\n'
              'dns:\n  enable: true\n  listen: 127.0.0.1:%(dns)d\n  enhanced-mode: fake-ip\n  fake-ip-range: 198.18.0.1/16\n'
              '  nameserver: [127.0.0.1:1]\nrules: ["MATCH,DIRECT"]\n')

    def setUp(self):
        super().setUp()
        (self.dir / 'bin').mkdir()
        self.core = self.dir / 'bin/core'
        shutil.copy(os.environ['HETU_TEST_MIHOMO'], self.core)
        self.procs = []
        self.logs = []

    def tearDown(self):
        for p in self.procs:
            if p.poll() is None:
                p.kill()
            p.wait()
        for f in self.logs:
            f.close()
        super().tearDown()

    def launch(self, name, exe=None):
        from test_netloss93 import free_port
        cp, dns = free_port(), free_port()
        cfg = self.dir / (name + '.yaml')
        cfg.write_text(self.CONFIG % {'cp': cp, 'dns': dns})
        log = open(self.dir / (name + '.log'), 'w')
        self.logs.append(log)
        p = subprocess.Popen([str(exe or self.core), '-d', str(self.run_dir), '-f', str(cfg)], stdout=log, stderr=subprocess.STDOUT)
        self.procs.append(p)
        for _ in range(100):
            try:
                dns_query(dns, 'probe.test')
                break
            except OSError:
                time.sleep(0.1)
        return p, dns, self.dir / (name + '.log')

    def stable(self, dns):
        return dns_query(dns, 'www.gstatic.com') == dns_query(dns, 'www.gstatic.com')

    def test_a_held_lock_loses_the_fake_ip_records_and_the_guard_prevents_it(self):
        holder, dns_a, _ = self.launch('a')
        self.assertTrue(self.stable(dns_a))
        broken, dns_b, log_b = self.launch('b')
        self.assertFalse(self.stable(dns_b), 'a new address on every query')
        r = self.sh('LOG=%s; wd_cache_lost; echo "rc=$?"' % shq(log_b))
        self.assertIn('rc=0', r.stdout, log_b.read_text())
        broken.kill()
        broken.wait()
        # The guard ends Hetu's own holder; the next core keeps its records.
        r = self.sh('START_KIND=mihomo; core_cache_guard; echo "note=[$CACHE_GUARD_NOTE]"')
        self.assertIn('note=[]', r.stdout, r.stderr)
        self.assertIsNotNone(holder.wait(timeout=5))
        fixed, dns_c, log_c = self.launch('c')
        self.assertTrue(self.stable(dns_c))
        self.assertNotIn("can't open cache file", log_c.read_text())
        # A holder that is not Hetu's: the file is moved aside and the core starts on a new one.
        fixed.kill()
        fixed.wait()
        other = subprocess.Popen(['flock', '-o', str(self.run_dir / 'cache.db'), 'sleep', '30'])
        self.procs.append(other)
        time.sleep(0.3)
        r = self.sh('START_KIND=mihomo; core_cache_guard; echo "note=[$CACHE_GUARD_NOTE]"')
        self.assertIn('已换用新的缓存文件', r.stdout, r.stderr)
        fresh, dns_d, log_d = self.launch('d')
        self.assertTrue(self.stable(dns_d))
        self.assertNotIn("can't open cache file", log_d.read_text())

    def test_the_probe_resolver_sees_the_lost_records(self):
        self.launch('a')
        broken, dns_b, log_b = self.launch('b')
        r = self.sh('WD_DP=%d; WD_DNS_PROTOS="tcp udp"; WD_HTTP_TIMEOUT=3; wd_resolve www.gstatic.com; A=$WD_RES_IP; '
                    'wd_resolve www.gstatic.com; echo "$A $WD_RES_IP"' % dns_b)
        first, second = r.stdout.split()
        self.assertNotEqual(first, second, r.stderr)
        self.assertTrue(first.startswith('198.18.'))


@unittest.skipUnless(os.environ.get('HETU_TEST_SINGBOX') and shutil.which('flock'), 'set HETU_TEST_SINGBOX to a sing-box binary')
class RealSingBox(Case):
    """sing-box refuses to start while another process holds its cache file; the guard lets it start."""

    def config(self, dns):
        return ('{"log":{"level":"info"},"dns":{"servers":[{"type":"local","tag":"loc"},{"type":"fakeip","tag":"fk",'
                '"inet4_range":"198.18.0.0/15"}],"rules":[{"query_type":["A"],"server":"fk"}],"final":"loc"},'
                '"inbounds":[{"type":"direct","tag":"dns-in","listen":"127.0.0.1","listen_port":%d}],'
                '"outbounds":[{"type":"direct","tag":"direct"}],"route":{"rules":[{"inbound":"dns-in","action":"hijack-dns"}],'
                '"final":"direct","default_domain_resolver":"loc"},'
                '"experimental":{"cache_file":{"enabled":true,"path":"cache.db","store_fakeip":true}}}' % dns)

    def run_core(self, name):
        from test_netloss93 import free_port
        dns = free_port()
        cfg = self.dir / (name + '.json')
        cfg.write_text(self.config(dns))
        with open(self.dir / (name + '.log'), 'w') as log:
            p = subprocess.Popen([os.environ['HETU_TEST_SINGBOX'], 'run', '-c', str(cfg), '-D', str(self.run_dir), '--disable-color'],
                                 stdout=log, stderr=subprocess.STDOUT)
        try:
            for _ in range(150):
                if p.poll() is not None:
                    return None, (self.dir / (name + '.log')).read_text()
                try:
                    return dns_query(dns, 'www.gstatic.com'), ''
                except OSError:
                    time.sleep(0.1)
            return None, 'no answer'
        finally:
            if p.poll() is None:
                p.kill()
            p.wait()

    def test_a_held_lock_stops_the_start_and_the_guard_lets_it_start(self):
        (self.run_dir / 'cache.db').write_bytes(b'')
        holder = subprocess.Popen(['flock', '-o', str(self.run_dir / 'cache.db'), 'sleep', '60'])
        try:
            time.sleep(0.3)
            ip, log = self.run_core('a')
            self.assertIsNone(ip)
            self.assertIn('initialize cache-file: timeout', log)
            r = self.sh('START_KIND=sing-box; core_cache_guard; echo "note=[$CACHE_GUARD_NOTE]"')
            self.assertIn('已换用新的缓存文件', r.stdout, r.stderr)
            ip, log = self.run_core('b')
            self.assertTrue(ip and ip.startswith('198.18.'), log)
        finally:
            holder.kill()
            holder.wait()


class Guards(unittest.TestCase):
    def test_the_listener_port_matches_the_app(self):
        java = (Path(__file__).resolve().parents[2] / 'android-app/app/src/main/java/io/github/xgl34222220/hetu/MihomoStartupConfig.java').read_text()
        self.assertIn('static final int EGRESS_PROBE_PORT_OFFSET=60;', java)
        self.assertIn('WD_DP_EGRESS_OFFSET=60\n', TEXT)

    def test_restarts_stay_within_the_budget_and_no_heal_reloads_for_a_routing_choice(self):
        self.assertIn('wd_restart_allowed', function('wd_fakeip_heal'))
        route = function('wd_route_heal')
        self.assertNotIn('wd_restart_core', route)
        self.assertNotIn('/configs', route)
        self.assertNotIn('DELETE', route)


if __name__ == '__main__':
    unittest.main()
