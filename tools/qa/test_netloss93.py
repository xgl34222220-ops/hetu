#!/usr/bin/env python3
"""Network-loss fix host regression for hetu-root.sh (2026-10-14; no Root, no real cgroup).

Symptom: after a while or overnight (doze) only proxied sites, or the whole network, stopped
working ("无法连接到网络") until Hetu was stopped. The shipped functions are extracted from
assets/hetu-root.sh and run under /bin/sh against temporary fixtures:

* cgroup escape: the start shell, watchdog and monitor move into the root cgroup of the v2 tree
  and of Android's v1 hierarchies (only on Android: gated by CG_ESCAPE_GATE), setsid is used only
  when it keeps the PID of a background child.
* watchdog liveness: a SIGSTOPped core is resumed; a controller that accepts but never answers,
  a refused controller and a silent DNS listener count as failures (curl and nc), an answering
  one (any HTTP status) as alive; three failures restart the core from the saved start arguments,
  and a refused restart ends the hung core so the existing core-exit path removes capture.
* restart budget: at most WD_RESTART_MAX per window and boot; restart replays the exact arguments
  in a clean environment and gives the watchdog PID back when it fails.
* proxy egress (partial outage): the group the rules end in is probed through the controller;
  only failures while DIRECT works count; three of them re-test the auto-select groups and close
  only the connections whose chain passes through the failing group (DIRECT and other groups stay
  up), behind an exponential backoff; the core is never reloaded or restarted for a dead node
  (4.4.4 field report: "玩着玩着就没网" while every connection was dropped). A data-plane outage
  re-checks the capture rules without closing connections.
* network change: an App repair-network call leaves a kick that makes the watchdog re-probe.
* rule shapes: preserve_existing_tcp never lets a fake-IP destination go direct, the network
  stack UID 1073 stays out of DNS takeover (captured resolver); cgroup memory limit uses memory.high (+50 % max).
* 3.3.3 Wi-Fi recovery: after stop/start (net-recover) and on a whole-phone outage seen by the watchdog
  (proxy and DIRECT both fail while the core answers) an HTTP 204 is fetched over the Wi-Fi outside Hetu;
  a failure or a not-validated Wi-Fi escalates flushnet + reevaluate -> cmd wifi reconnect -> Wi-Fi off/on,
  on by default, turned off by the policy marker, rate-limited, logged and notified. DIRECT checks use
  mainland-China probe hosts (connectivitycheck.gstatic.com times out direct there).
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import os
import re
import shutil
import signal
import socket
import subprocess
import tempfile
import threading
import time
import unittest
from urllib.parse import unquote

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / 'android-app/app/src/main/assets/hetu-root.sh'
TEXT = SCRIPT.read_text()
FAKE4 = '198.18.0.0/16'


def function(name):
    one_line = re.search(r'^' + re.escape(name) + r'\(\)\{ .*\}\n', TEXT, re.M)
    if one_line:
        return one_line.group(0)
    match = re.search(r'^' + re.escape(name) + r'\(\)\{\n.*?^\}\n', TEXT, re.S | re.M)
    if not match:
        raise AssertionError('function missing from hetu-root.sh: ' + name)
    return match.group(0)


def shq(value):
    return "'" + str(value).replace("'", "'\\''") + "'"


def top_assignment(name):
    match = re.search(r'^' + re.escape(name) + r'=.*$', TEXT, re.M)
    if not match:
        raise AssertionError('variable missing from hetu-root.sh: ' + name)
    return match.group(0) + '\n'


SELFHEAL_VARS = ('CG_ESCAPE_GATE', 'CG_ESCAPE_V1', 'CG_ESCAPED', 'SETSID', 'SETSID_CHECKED', 'NETSTACK_UID',
                 'WD_HTTP_TOOLS', 'WD_HTTP_TIMEOUT', 'WD_LIVE_GRACE', 'WD_LIVE_EVERY', 'WD_LIVE_RETRY', 'WD_LIVE_LIMIT',
                 'WD_DNS_EVERY', 'WD_DNS_LIMIT', 'WD_EGRESS_GRACE', 'WD_EGRESS_EVERY', 'WD_EGRESS_RETRY',
                 'WD_EGRESS_LIMIT', 'WD_EGRESS_BACKOFF', 'WD_EGRESS_BACKOFF_MAX', 'WD_RESTART_MAX',
                 'WD_RESTART_WINDOW', 'WD_LOG_MAX', 'WD_PROXY_URL', 'WD_DIRECT_URLS', 'WD_HEAL_REASON',
                 'CORE_GROUP_ID', 'WIFI_HEAL_URLS', 'WIFI_HEAL_DNS', 'WIFI_HEAL_OFF', 'WIFI_HEAL_GAP', 'WIFI_HEAL_HOUR_MAX',
                 'WIFI_HEAL_SETTLE', 'WIFI_HEAL_WAIT', 'WIFI_HEAL_OFFLINE_LIMIT', 'WD_CLOSE_MAX', 'WD_BUSY_GAP')
HELPERS = ('has', 'port', 'kind_has_api', 'cg_join', 'detach_probe', 'cg_escape', 'wd_log', 'wd_log_body', 'wd_now',
           'wd_notify', 'wd_http', 'wd_dns_probe', 'wd_core_frozen', 'wd_selfheal_init', 'wd_net_sig', 'wd_net_watch',
           'wd_liveness', 'wd_restart_allowed', 'wd_restart_core', 'wd_heal_notice', 'wd_recover', 'wd_egress_target',
           'wd_urlenc', 'wd_delay', 'wd_egress_tick', 'wd_selfheal_tick', 'fake_range', 'wd_session_fake_range',
           'wd_root_fake_captured', 'wd_offline_seen', 'wifi_heal_async', 'wd_uptime', 'wd_budget_start',
           'wd_budget_left', 'wd_groups', 'wd_conn_match', 'wd_close_through', 'wd_conn_busy', 'wd_heal_chain', 'wd_node_heal',
           'wd_proxy_heal', 'wd_dataplane_heal')


class Controller:
    """A Clash-API stand-in: records requests, answers per path, or stays silent."""

    def __init__(self, secret='s3cr3t'):
        self.requests = []
        self.secret = secret
        self.proxy_ok = True
        self.direct_ok = True
        self.mode = 'rule'
        self.connections = []
        self.connections_code = 200
        self.closed = []
        # Ids whose download counter grows on every GET /connections (a transfer in progress).
        self.moving = set()
        self.connection_reads = 0
        # GET /proxies groups: (type, name, now, fixed); empty = 404 like the older fixture.
        self.groups = []
        self.rules = ('{"rules":[{"index":0,"type":"RuleSet","payload":"ads","proxy":"REJECT","size":-1},'
                      '{"index":1,"type":"Domain","payload":"x.cn","proxy":"DIRECT"},'
                      '{"index":2,"type":"Match","payload":"","proxy":"\U0001F680 节点选择","extra":{"a":1}}]}')
        owner = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def handle_any(self):
                owner.requests.append((self.command, self.path, self.headers.get('Authorization', '')))
                if self.headers.get('Authorization', '') != 'Bearer ' + owner.secret:
                    return self.reply(401, '{"message":"Unauthorized"}')
                path = self.path
                if path == '/version':
                    return self.reply(200, '{"version":"fake"}')
                if path == '/configs':
                    return self.reply(200, '{"port":0,"mode":"%s","log-level":"info"}' % owner.mode)
                if path == '/rules':
                    return self.reply(200, owner.rules)
                if path.startswith('/proxies/'):
                    name = unquote(path.split('/')[2])
                    ok = owner.direct_ok if name == 'DIRECT' else owner.proxy_ok
                    return self.reply(200, '{"delay":87}') if ok else self.reply(504, '{"message":"Timeout"}')
                if path == '/connections' and self.command == 'GET':
                    owner.connection_reads += 1
                    return self.reply(owner.connections_code, connections_json(owner.connections, owner.moving, owner.connection_reads))
                if path == '/proxies' and owner.groups:
                    import json
                    body = {'proxies': {n: {'type': t, 'name': n, 'now': now, **({'fixed': fx} if t in ('URLTest', 'Fallback') else {})}
                                        for t, n, now, fx in owner.groups}}
                    return self.reply(200, json.dumps(body, ensure_ascii=False, separators=(',', ':')))
                if path.startswith('/connections/') and self.command == 'DELETE':
                    owner.closed.append(unquote(path.split('/')[2]))
                    return self.reply(204, '')
                if path in ('/connections', '/restart'):
                    return self.reply(200 if path == '/restart' else 204, '{"status":"ok"}' if path == '/restart' else '')
                self.reply(404, '{"message":"not found"}')

            def reply(self, code, body):
                data = body.encode()
                self.send_response(code)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                if data:
                    self.wfile.write(data)

            do_GET = do_PUT = do_POST = do_DELETE = handle_any

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), H)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


def connections_json(conns, moving=(), reads=1):
    """Mihomo's GET /connections shape: metadata before chains, Go's \u0026-style HTML escapes."""
    import json
    items = [{'id': cid, 'metadata': {'network': 'tcp', 'type': 'TProxy', 'destinationIP': '203.0.113.9', 'uid': 10123,
                                      'host': 'example.test', 'process': 'com.example'},
              'upload': 10, 'download': 20 + (reads * 65536 if cid in moving else 0), 'start': '2026-10-18T00:00:00Z',
              'chains': chains, 'rule': 'Match', 'rulePayload': ''}
             for cid, chains in conns]
    body = json.dumps({'downloadTotal': 1, 'uploadTotal': 2, 'connections': items, 'memory': 1}, ensure_ascii=False, separators=(',', ':'))
    return body.replace('&', '\\u0026')


class Silent:
    """Accepts connections into the backlog and never answers (a frozen core)."""

    def __init__(self, reply=b''):
        self.sock = socket.socket()
        self.sock.bind(('127.0.0.1', 0))
        self.sock.listen(8)
        self.port = self.sock.getsockname()[1]
        self.reply = reply
        self.conns = []
        if reply:
            threading.Thread(target=self.serve, daemon=True).start()

    def serve(self):
        while True:
            try:
                c, _ = self.sock.accept()
            except OSError:
                return
            self.conns.append(c)
            try:
                c.settimeout(2)
                c.recv(512)
                c.sendall(self.reply)
            except OSError:
                pass

    def close(self):
        for c in self.conns:
            c.close()
        self.sock.close()


def free_port():
    s = socket.socket()
    s.bind(('127.0.0.1', 0))
    p = s.getsockname()[1]
    s.close()
    return p


class ShellCase(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='hetu-netloss-')
        self.dir = Path(self.temp.name)
        self.run_dir = self.dir / 'run'
        (self.run_dir / 'state').mkdir(parents=True)
        self.log = self.run_dir / 'watchdog.log'
        self.cleanups = []

    def tearDown(self):
        for c in reversed(self.cleanups):
            try:
                c()
            except Exception:
                pass
        self.temp.cleanup()

    def prelude(self, extra=''):
        text = 'set -u\nRUN=%s\nBASE=%s\nWATCHDOG_LOG="$RUN/watchdog.log"\nWATCHDOG_PID="$RUN/watchdog.pid"\n' % (
            shq(self.run_dir), shq(self.dir))
        text += 'PIDFILE="$RUN/core.pid"\nSESSION="$RUN/session.state"\nCG_MOUNTS=%s\n' % shq(self.dir / 'no-mounts')
        text += ''.join(top_assignment(v) for v in SELFHEAL_VARS)
        text += 'CG_ESCAPE_GATE=%s\n' % shq(self.dir / 'no-android')
        text += 'core_kind(){ printf mihomo; }\ncore_maybe_alive(){ kill -0 "$1" 2>/dev/null; }\n'
        text += ''.join(function(n) for n in HELPERS)
        return text + extra

    def sh(self, body, extra='', env=None, timeout=60):
        return subprocess.run(['/bin/sh', '-c', self.prelude(extra) + body], capture_output=True, text=True,
                              timeout=timeout, env={**os.environ, **(env or {})})

    def log_text(self):
        return self.log.read_text() if self.log.exists() else ''

    def controller(self, **kw):
        c = Controller(**kw)
        self.cleanups.append(c.close)
        return c

    def session(self, cp=0, dp=0, protos='tcp udp', secret='s3cr3t'):
        (self.run_dir / 'session.state').write_text(
            'MODE=tproxy\nCONTROLLER_PORT=%s\nACTIVE_DNS_PORT=%s\nDNS_PROTOS=%s\n' % (cp, dp, protos))
        cfg = self.run_dir / 'state/startup-config'
        cfg.write_text("mixed-port: 0\nsecret: '%s'\nexternal-controller: 127.0.0.1:%s\n" % (secret.replace("'", "''"), cp))
        (self.run_dir / 'state/start.args').write_text('/bin/core\n%s\ntproxy\n' % cfg)


class CgroupEscape(ShellCase):
    def test_escape_moves_into_v2_root_and_v1_roots_only_on_android(self):
        v2 = self.dir / 'cg2'
        v2.mkdir()
        (v2 / 'cgroup.procs').write_text('')
        acct = self.dir / 'acct'
        acct.mkdir()
        (acct / 'cgroup.procs').write_text('')
        freezer = self.dir / 'freezer'
        freezer.mkdir()
        (freezer / 'tasks').write_text('')
        mounts = self.dir / 'mounts'
        mounts.write_text('proc /proc proc rw 0 0\nnone %s cgroup2 rw 0 0\n' % v2)
        gate = self.dir / 'system-bin'
        gate.mkdir()
        body = ('CG_MOUNTS=%s; CG_ESCAPE_V1="%s %s %s"; cg_escape 4242; echo "escaped=$CG_ESCAPED"'
                % (shq(mounts), acct, freezer, self.dir / 'absent'))
        r = self.sh(body.replace('cg_escape', 'CG_ESCAPE_GATE=%s; cg_escape' % shq(gate), 1))
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual('', r.stderr)
        self.assertEqual((v2 / 'cgroup.procs').read_text().strip(), '4242')
        self.assertEqual((acct / 'cgroup.procs').read_text().strip(), '4242')
        self.assertEqual((freezer / 'tasks').read_text().strip(), '4242', 'v1 without cgroup.procs uses tasks')
        self.assertIn('escaped=v2,', r.stdout)
        for path in (v2 / 'cgroup.procs', acct / 'cgroup.procs'):
            path.write_text('')
        r = self.sh(body)  # gate absent: a Linux host (or test harness) is never touched
        self.assertEqual((v2 / 'cgroup.procs').read_text(), '')
        self.assertIn('escaped=\n', r.stdout)

    def test_setsid_is_used_only_when_it_keeps_the_background_pid(self):
        if not shutil.which('setsid'):
            self.skipTest('no setsid on this host')
        r = self.sh('detach_probe; echo "setsid=$SETSID"; SETSID_CHECKED=0; has(){ [ "$1" != setsid ] && command -v "$1" >/dev/null 2>&1; }; detach_probe; echo "without=$SETSID"')
        self.assertIn('setsid=setsid', r.stdout, r.stderr)
        self.assertIn('without=\n', r.stdout)
        fake = self.dir / 'bin'
        fake.mkdir()
        # A setsid that forks (as it does for a group leader) changes the PID: never used then.
        (fake / 'setsid').write_text('#!/bin/sh\n"$@" &\nwait\n')
        (fake / 'setsid').chmod(0o755)
        r = self.sh('detach_probe; echo "forking=$SETSID"', env={'PATH': str(fake) + ':' + os.environ['PATH']})
        self.assertIn('forking=\n', r.stdout, r.stderr)


class Liveness(ShellCase):
    def test_answering_controller_is_alive_any_status_and_silent_or_refused_is_not(self):
        c = self.controller()
        for tools in ('curl', 'nc'):
            if not shutil.which(tools):
                continue
            with self.subTest(tool=tools):
                self.session(cp=c.port)
                r = self.sh('WD_HTTP_TOOLS=%s; COREPID=$$; wd_selfheal_init; wd_liveness; echo "rc=$? $WD_LIVE_REASON"' % tools)
                self.assertIn('rc=0', r.stdout, r.stderr)
                self.assertEqual(c.requests[-1][:2], ('GET', '/version'))
                self.assertEqual(c.requests[-1][2], 'Bearer s3cr3t', 'secret read from the startup copy')
                self.session(cp=c.port, secret='wrong')
                r = self.sh('WD_HTTP_TOOLS=%s; COREPID=$$; wd_selfheal_init; wd_liveness; echo "rc=$?"' % tools)
                self.assertIn('rc=0', r.stdout, 'a 401 still proves the core answers')
                silent = Silent()
                self.cleanups.append(silent.close)
                self.session(cp=silent.port)
                t0 = time.time()
                r = self.sh('WD_HTTP_TOOLS=%s; COREPID=$$; wd_selfheal_init; wd_liveness; echo "rc=$? $WD_LIVE_REASON"' % tools)
                self.assertIn('rc=1 controller-%d-no-answer' % silent.port, r.stdout, r.stderr)
                self.assertLess(time.time() - t0, 15)
                self.session(cp=free_port())
                r = self.sh('WD_HTTP_TOOLS=%s; COREPID=$$; wd_selfheal_init; wd_liveness; echo "rc=$?"' % tools)
                self.assertIn('rc=1', r.stdout, 'nothing listening any more')

    def test_dns_listener_probe_for_cores_without_controller(self):
        if not shutil.which('nc'):
            self.skipTest('no nc')
        answering = Silent(reply=b'\x00\x0cHT\x81\x83' + b'\x00' * 8)
        self.cleanups.append(answering.close)
        self.session(cp=0, dp=answering.port)
        r = self.sh('COREPID=$$; wd_selfheal_init; wd_liveness; echo "rc=$?"')
        self.assertIn('rc=0', r.stdout, r.stderr)
        silent = Silent()
        self.cleanups.append(silent.close)
        self.session(cp=0, dp=silent.port)
        r = self.sh('COREPID=$$; wd_selfheal_init; wd_liveness; echo "rc=$? $WD_LIVE_REASON"')
        self.assertIn('rc=1 dns-%d-no-answer' % silent.port, r.stdout, r.stderr)
        self.session(cp=0, dp=silent.port, protos='udp')
        r = self.sh('COREPID=$$; wd_selfheal_init; wd_liveness; echo "rc=$?"')
        self.assertIn('rc=0', r.stdout, 'UDP-only DNS cannot be probed over TCP: unknown is never a failure')

    def test_stopped_core_is_resumed_and_reported(self):
        proc = subprocess.Popen(['sleep', '60'])
        self.cleanups.append(proc.kill)
        os.kill(proc.pid, signal.SIGSTOP)
        time.sleep(0.2)
        self.session(cp=0, dp=0)
        r = self.sh('COREPID=%d; wd_selfheal_init; wd_core_frozen $COREPID && echo "frozen=$WD_FROZEN_HOW"; wd_liveness; echo "rc=$?"' % proc.pid)
        self.assertIn('frozen=stopped', r.stdout, r.stderr)
        self.assertIn('rc=0', r.stdout)
        self.assertIn('was frozen (stopped)', self.log_text())
        state = Path('/proc/%d/stat' % proc.pid).read_text().rsplit(')', 1)[1].split()[0]
        self.assertNotEqual('T', state)

    def test_three_failed_probes_restart_and_a_refused_restart_ends_the_hung_core(self):
        silent = Silent()
        self.cleanups.append(silent.close)
        self.session(cp=silent.port)
        core = subprocess.Popen(['sleep', '60'])
        self.cleanups.append(core.kill)
        (self.run_dir / 'core.pid').write_text('%d\n' % core.pid)
        restarter = self.dir / 'restart.calls'
        stub = ('wd_restart_core(){ echo restart >> %s; return 1; }\nKILL=0\n' % shq(restarter))
        body = ('COREPID=%d; WD_HTTP_TIMEOUT=1; wd_selfheal_init; WD_LIVE_RETRY=0; WD_LIVE_AT=0; WD_EG_ON=0;'
                'for i in 1 2 3; do WD_LIVE_AT=0; wd_selfheal_tick; done; echo "fail=$WD_LIVE_FAIL heal=$WD_HEAL_REASON"' % core.pid)
        r = self.sh(body, extra=stub, timeout=90)
        self.assertIn('heal=controller-%d-no-answer' % silent.port, r.stdout, r.stderr)
        self.assertEqual(restarter.read_text().split(), ['restart'])
        core.wait(timeout=5)
        self.assertEqual(core.returncode, -signal.SIGKILL, 'the unresponsive core is ended for the core-exit path')
        log = self.log_text()
        for text in ('core liveness probe failed', '3/3', 'self-heal: restarting the core', 'self-heal: ending unresponsive core'):
            self.assertIn(text, log)

    def test_budget_refuses_a_fourth_restart_in_the_window_and_resets_after_it(self):
        r = self.sh('for i in 1 2 3 4; do if wd_restart_allowed; then echo yes; else echo no; fi; done')
        self.assertEqual(r.stdout.split(), ['yes', 'yes', 'yes', 'no'], r.stderr)
        budget = (self.run_dir / 'state/selfheal-budget').read_text().split()
        self.assertEqual(budget[1], '3')
        r = self.sh('WD_RESTART_WINDOW=0; wd_restart_allowed && echo yes')
        self.assertIn('yes', r.stdout, 'a passed window starts over')

    def test_restart_replays_the_saved_arguments_in_a_clean_environment(self):
        self.session(cp=1)
        (self.run_dir / 'state/start.args').write_text('/bin/core\nwith space\n\n10100,10200\n')
        rec = self.dir / 'replayed'
        # The watchdog re-executes "$0" start: this script plays both roles.
        fake_start = ('if [ "${1:-}" = start ]; then shift\n'
                      '  { for a in "$@"; do printf "[%%s]" "$a"; done; echo; env | grep -c "^HETU_BOOT_RESTORE_ID=";'
                      ' echo "selfheal=${HETU_SELFHEAL_RESTART:-}"; } > %s\n'
                      '  echo 4321 > %s\n'
                      '  if [ "${FAIL:-0}" = 1 ]; then echo \'{"ok":false,"message":"x"}\'; exit 1; fi\n'
                      '  echo \'{"ok":true,"message":"started"}\'; exit 0\nfi\n') % (shq(rec), shq(self.run_dir / 'core.pid'))
        script = self.dir / 'hetu-root.sh'
        script.write_text('#!/bin/sh\n' + fake_start + self.prelude('COREPID=11; KILL=0; WD_TMP="$RUN/.wd.t"\n') +
                          'wd_restart_core; echo "rc=$?"\n')
        script.chmod(0o755)
        (self.run_dir / 'watchdog.pid').write_text('999\n')
        env = {**os.environ, 'HETU_BOOT_RESTORE_ID': 'old-boot'}
        r = subprocess.run([str(script)], capture_output=True, text=True, timeout=30, env=env)
        self.assertIn('rc=0', r.stdout, r.stderr)
        lines = rec.read_text().splitlines()
        self.assertEqual(lines[0], '[/bin/core][with space][][10100,10200]')
        self.assertEqual(lines[1], '0', 'boot/automatic recovery identities are not inherited')
        self.assertEqual(lines[2], 'selfheal=1')
        self.assertFalse((self.run_dir / 'watchdog.pid').exists(), 'the restarted session owns the watchdog pid file')
        self.assertIn('self-heal: core restarted (4321)', self.log_text())
        (self.run_dir / 'core.pid').write_text('11\n')
        r = subprocess.run([str(script)], capture_output=True, text=True, timeout=30, env={**env, 'FAIL': '1'})
        self.assertIn('rc=1', r.stdout, r.stderr)
        self.assertEqual((self.run_dir / 'watchdog.pid').read_text().strip().isdigit(), True, 'a failed restart gives the pid file back')
        self.assertIn('self-heal: core restart failed', self.log_text())


class Egress(ShellCase):
    def run_ticks(self, c, ticks, extra=''):
        self.session(cp=c.port)
        body = ('COREPID=$$; KILL=0; wd_selfheal_init; WD_EGRESS_RETRY=0; WD_EGRESS_EVERY=0; ' + extra +
                'for i in $(seq %d); do wd_now; WD_EG_AT=0; wd_egress_tick; done; '
                'echo "level=$WD_EG_LEVEL fail=$WD_EG_FAIL target=$WD_EG_TARGET on=$WD_EG_ON"' % ticks)
        return self.sh(body, extra='wd_restart_core(){ echo restart >> "$RUN/restart.calls"; return 1; }\n', timeout=120)

    def test_target_is_the_match_group_and_global_mode_uses_global(self):
        c = self.controller()
        r = self.run_ticks(c, 1)
        self.assertIn('target=\U0001F680 节点选择', r.stdout, r.stderr)
        delay = [unquote(p) for m, p, a in c.requests if p.startswith('/proxies/')]
        self.assertEqual(delay[0], '/proxies/\U0001F680 节点选择/delay?timeout=5000&url=http://www.gstatic.com/generate_204')
        c.mode = 'global'
        r = self.run_ticks(c, 1)
        self.assertIn('target=GLOBAL', r.stdout)
        c.mode = 'direct'
        c.requests.clear()
        r = self.run_ticks(c, 1)
        self.assertFalse([p for m, p, a in c.requests if p.startswith('/proxies/')], 'direct mode: nothing to probe')

    def test_failures_only_count_while_direct_works(self):
        c = self.controller()
        c.proxy_ok = False
        c.direct_ok = False
        r = self.run_ticks(c, 5)
        self.assertIn('level=0 fail=0', r.stdout, r.stderr)
        self.assertFalse([1 for m, p, a in c.requests if m in ('DELETE', 'POST')])

    def test_ladder_closes_only_connections_through_the_failing_group_and_never_restarts(self):
        c = self.controller()
        c.proxy_ok = False
        c.connections = [('c-game', ['DIRECT']), ('c-proxy', ['香港 01', '\U0001F680 节点选择']),
                         ('c-other', ['日本 02', '\U0001F3AE 游戏'])]
        r = self.run_ticks(c, 3, extra='WD_EGRESS_BACKOFF=0; WD_EG_BACKOFF=0; ')
        self.assertIn('level=1', r.stdout, r.stderr)
        self.assertEqual(c.closed, ['c-proxy'], 'only the failing group\'s connection is closed')
        r = self.run_ticks(c, 9, extra='WD_EGRESS_BACKOFF=0; WD_EG_BACKOFF=0; ')
        self.assertEqual(set(c.closed), {'c-proxy'}, c.closed)
        actions = [(m, p) for m, p, a in c.requests if m in ('DELETE', 'POST')]
        self.assertNotIn(('DELETE', '/connections'), actions, 'never every connection')
        self.assertFalse([p for m, p in actions if m == 'POST'], 'never reloaded in place')
        self.assertFalse((self.run_dir / 'restart.calls').exists(), 'never restarted for a dead node')
        log = self.log_text()
        for text in ('proxy egress probe failed', 'closed 1 of 3 core connection(s) through \U0001F680 节点选择'):
            self.assertIn(text, log)
        self.assertNotIn('core reloaded in place', log)
        self.assertIn('proxy-down \U0001F680 节点选择', (self.run_dir / 'selfheal-state').read_text())
        # With the default backoff a second sustained failure waits instead of acting again.
        c.requests.clear(); c.closed.clear()
        r = self.run_ticks(c, 6)
        self.assertEqual(c.closed, ['c-proxy'], c.closed)
        self.assertIn('next self-heal action in', self.log_text())

    def test_unreadable_connection_list_closes_nothing(self):
        c = self.controller()
        c.proxy_ok = False
        c.connections = [('c-proxy', ['香港 01', '\U0001F680 节点选择'])]
        c.connections_code = 500
        r = self.run_ticks(c, 3, extra='WD_EGRESS_BACKOFF=0; WD_EG_BACKOFF=0; ')
        self.assertIn('level=1', r.stdout, r.stderr)
        self.assertEqual(c.closed, [])
        self.assertFalse([1 for m, p, a in c.requests if m == 'DELETE'])
        self.assertIn('closed no connection (connection list unreadable)', self.log_text())

    def test_close_through_matches_escaped_and_nested_names_only(self):
        c = self.controller()
        self.session(cp=c.port)
        c.connections = [('a', ['节点 A&B', '组 1']), ('b', ['DIRECT']), ('c', ['节点 C', '子组', '组 2']), ('d', []),
                         ('e', ['REJECT'])]
        r = self.sh('COREPID=$$; wd_selfheal_init; wd_close_through "$(printf \'节点 A&B\\t子组\\t\')"; '
                    'echo "rc=$? closed=$WD_CLOSED seen=$WD_CLOSE_SEEN"')
        self.assertIn('rc=0 closed=2 seen=5', r.stdout, r.stderr)
        self.assertEqual(sorted(c.closed), ['a', 'c'])
        r = self.sh('COREPID=$$; wd_selfheal_init; wd_close_through ""; echo "rc=$? closed=$WD_CLOSED"')
        self.assertIn('rc=1 closed=0', r.stdout)

    def test_data_plane_node_heal_closes_the_bad_nodes_connections_and_notifies_once(self):
        c = self.controller()
        self.session(cp=c.port)
        c.connections = [('hk', ['香港 01', '\U0001F41F 漏网之鱼']), ('jp', ['日本 02', '\U0001F3AE 游戏']), ('cn', ['DIRECT'])]
        body = ('COREPID=$$; wd_selfheal_init; WD_DP_BAD_NAMES="$(printf \'\U0001F41F 漏网之鱼\\t香港 01\\t\')"; '
                'WD_DP_BAD="\U0001F41F 漏网之鱼→香港 01"; wd_proxy_heal; wd_proxy_heal; echo "notified=$WD_NODE_NOTIFIED"')
        r = self.sh(body)
        self.assertIn('notified=1', r.stdout, r.stderr)
        self.assertEqual(c.closed, ['hk', 'hk'])
        self.assertNotIn(('DELETE', '/connections'), [(m, p) for m, p, a in c.requests])
        self.assertIn('closed 1 of 3 core connection(s) through \U0001F41F 漏网之鱼 香港 01', self.log_text())

    def test_node_moving_data_is_not_healed(self):
        # 5.1.0: a probe that timed out behind a busy download must not cut the download or re-test groups.
        c = self.controller()
        self.session(cp=c.port)
        c.connections = [('dl', ['香港 01', '\U0001F41F 漏网之鱼']), ('idle', ['香港 01', '\U0001F41F 漏网之鱼'])]
        c.moving = {'dl'}
        body = ('COREPID=$$; wd_selfheal_init; WD_BUSY_GAP=0; WD_DP_BAD_NAMES="$(printf \'\U0001F41F 漏网之鱼\\t\')"; '
                'WD_DP_BAD="\U0001F41F 漏网之鱼→香港 01"; wd_proxy_heal; echo "busy=$WD_CLOSE_BUSY notified=$WD_NODE_NOTIFIED"')
        r = self.sh(body)
        self.assertIn('busy=1 notified=0', r.stdout, r.stderr)
        self.assertEqual(c.closed, [])
        self.assertFalse([p for m, p, a in c.requests if p.startswith('/group/')], 'no group re-test for a working node')
        self.assertIn('1 connection(s) through it are receiving data; nothing closed', self.log_text())
        c.moving = set()
        r = self.sh(body)
        self.assertEqual(sorted(c.closed), ['dl', 'idle'], r.stderr)

    def test_heal_follows_the_selection_retests_only_failing_auto_groups_and_keeps_a_pinned_node(self):
        c = self.controller()
        self.session(cp=c.port)
        c.groups = [('Selector', '最终兜底', '故障转移', ''), ('Fallback', '故障转移', '香港 01', ''),
                    ('URLTest', '节点选择', '日本 02', ''), ('URLTest', '美国节点', '美国 03', '美国 03')]
        c.connections = [('final', ['香港 01', '故障转移', '最终兜底']), ('ms', ['香港 01', '故障转移', 'Microsoft']),
                         ('jp', ['日本 02', '节点选择', 'Google']), ('us', ['美国 03', '美国节点'])]
        r = self.sh('COREPID=$$; wd_selfheal_init; WD_BUSY_GAP=0; wd_node_heal "$(printf \'最终兜底\\t\')" probe')
        self.assertEqual(sorted(c.closed), ['final', 'ms'], r.stderr)
        retested = [unquote(p).split('/')[2] for m, p, a in c.requests if p.startswith('/group/')]
        self.assertEqual(retested, ['故障转移'], 'only the auto group in the failing chain')
        self.assertIn('through 最终兜底 故障转移 香港 01', self.log_text())
        c.closed.clear(); c.requests.clear()
        r = self.sh('COREPID=$$; wd_selfheal_init; WD_BUSY_GAP=0; wd_node_heal "$(printf \'美国节点\\t\')" probe')
        self.assertFalse([p for m, p, a in c.requests if p.startswith('/group/')], 'a pinned url-test group is not re-tested')
        self.assertIn('kept the pinned 美国节点→美国 03', self.log_text())
        self.assertEqual(c.closed, ['us'])

    def test_heal_without_a_failing_name_does_nothing(self):
        c = self.controller()
        self.session(cp=c.port)
        c.connections = [('final', ['香港 01', '最终兜底'])]
        r = self.sh('COREPID=$$; wd_selfheal_init; wd_node_heal "" probe; echo "notified=$WD_NODE_NOTIFIED"')
        self.assertIn('notified=0', r.stdout, r.stderr)
        self.assertEqual(c.closed, [])
        self.assertFalse([1 for m, p, a in c.requests if m == 'DELETE' or p.startswith('/group/')])
        self.assertIn('no group or node failed its own check; nothing done', self.log_text())

    def test_data_plane_outage_rechecks_rules_without_closing_connections(self):
        c = self.controller()
        self.session(cp=c.port)
        c.connections = [('x', ['DIRECT'])]
        r = self.sh('COREPID=$$; wd_selfheal_init; WD_DP_LEVEL=0; WD_DP_DETAIL=probe; wd_dataplane_heal; echo "level=$WD_DP_LEVEL"',
                    extra='wd_repair_rules(){ echo repair >> "$RUN/repair.calls"; }\n')
        self.assertIn('level=1', r.stdout, r.stderr)
        self.assertEqual((self.run_dir / 'repair.calls').read_text().split(), ['repair'])
        self.assertFalse([1 for m, p, a in c.requests if m == 'DELETE'])
        self.assertIn('re-checked the capture rules', self.log_text())

    def test_recovery_resets_the_ladder_and_refused_secret_turns_the_check_off(self):
        c = self.controller()
        r = self.run_ticks(c, 1, extra='WD_EG_LEVEL=2; WD_EG_FAIL=2; ')
        self.assertIn('level=0 fail=0', r.stdout, r.stderr)
        self.assertIn('proxy egress recovered', self.log_text())
        c.secret = 'rotated'
        r = self.run_ticks(c, 1)
        self.assertIn('on=0', r.stdout)


class TargetedCloseGuards(unittest.TestCase):
    def test_no_self_heal_path_closes_every_connection_or_reloads_for_a_dead_node(self):
        self.assertNotIn('DELETE /connections "', TEXT)
        self.assertNotIn('POST /restart', function('wd_egress_tick'))
        self.assertNotIn('wd_restart_core', function('wd_egress_tick'))
        self.assertIn('wd_node_heal "$WD_EG_TARGET$WD_TAB" "$WD_EG_TARGET"', function('wd_egress_tick'))
        self.assertIn('wd_node_heal "$WD_DP_BAD_NAMES"', function('wd_proxy_heal'))
        heal = function('wd_node_heal')
        self.assertLess(heal.index('/group/'), heal.index('wd_close_through'), 're-test first, then close')


class NetworkChange(ShellCase):
    def test_kick_and_address_change_force_an_immediate_liveness_probe(self):
        c = self.controller()
        self.session(cp=c.port)
        (self.run_dir / 'liveness.kick').write_text('')
        r = self.sh('COREPID=$$; wd_selfheal_init; WD_EG_ON=0; wd_selfheal_tick; echo "next=$((WD_LIVE_AT-WD_NOW))"')
        self.assertFalse((self.run_dir / 'liveness.kick').exists())
        self.assertIn('network change reported', self.log_text())
        self.assertIn(('GET', '/version'), [(m, p) for m, p, a in c.requests])
        self.assertIn('next=%d' % 15, r.stdout, r.stderr)
        r = self.sh('COREPID=$$; wd_selfheal_init; WD_NET_SIG="wlan0=10.0.0.2/24 "; wd_net_watch; echo "at=$WD_LIVE_AT"',
                    extra='wd_net_sig(){ printf "wlan0=10.0.0.9/24 "; }\n')
        self.assertIn('at=0', r.stdout, r.stderr)
        self.assertIn('network change (wlan0=10.0.0.9/24 )', self.log_text())

    def test_only_app_repair_calls_leave_a_kick(self):
        line = [l for l in TEXT.splitlines() if l.strip().startswith('repair-network)')][0]
        self.assertIn('[ -n "${HETU_WATCHDOG_CALL:-}" ] || { : > "$RUN/liveness.kick"; }', line)
        watchdog = function('watchdog')
        self.assertIn('HETU_WATCHDOG_CALL=1; export HETU_WATCHDOG_CALL', watchdog)
        self.assertLess(watchdog.index('wd_selfheal_init'), watchdog.index('while [ "$MISS" -lt 3 ]'))
        self.assertIn('wd_net_watch; fi', watchdog)
        self.assertIn('wd_selfheal_tick', watchdog)


class RuleShapes(ShellCase):
    def rules(self, captured, fn, *args):
        calls = self.dir / 'calls'
        rec = 'xt4(){ echo "xt4 $*" >> %s; }\nxt6(){ echo "xt6 $*" >> %s; }\n' % (shq(calls), shq(calls))
        names = ('fake_range', 'core_return', 'preserve_existing_tcp', 'dns_owner_return')
        body = ('SYSTEM_DNS=%s; CORE_GID=3005; FAKE_IP_V4=%s; FAKE_IP_V6=fc00::/18; MARK=0x10; MASK=0x10; DNSOUT=HETU_DNSOUT\n'
                % ('captured' if captured else 'exempt', FAKE4)) + rec + ''.join(function(n) for n in names) + fn + ' ' + ' '.join(args)
        if calls.exists():
            calls.unlink()
        r = subprocess.run(['/bin/sh', '-c', 'set -u\nNETSTACK_UID=1073\n' + body], capture_output=True, text=True, timeout=20)
        self.assertEqual((r.returncode, r.stderr), (0, ''))
        return calls.read_text().splitlines()

    def test_fake_ip_destinations_never_keep_a_direct_established_path(self):
        self.assertEqual(self.rules(True, 'preserve_existing_tcp', 'xt4', 'HETU_MOUT'),
                         ['xt4 -t mangle -A HETU_MOUT -p tcp -m conntrack --ctstate ESTABLISHED ! --ctorigdst %s -m connmark ! --mark 0x10/0x10 -j RETURN' % FAKE4])
        self.assertIn('! --ctorigdst fc00::/18', self.rules(True, 'preserve_existing_tcp', 'xt6', 'HETU_MOUT')[0])
        self.assertEqual(self.rules(False, 'preserve_existing_tcp', 'xt4', 'HETU_MOUT'),
                         ['xt4 -t mangle -A HETU_MOUT -p tcp -m conntrack --ctstate ESTABLISHED -m connmark ! --mark 0x10/0x10 -j RETURN'])

    def test_network_stack_dns_stays_out_of_takeover_when_the_resolver_is_captured(self):
        captured = self.rules(True, 'dns_owner_return', 'xt4')
        self.assertEqual(captured[-1], 'xt4 -t nat -A HETU_DNSOUT -m owner --uid-owner 1073 -j RETURN')
        self.assertIn('--gid-owner 3005', captured[0])
        # Without the core identity every root socket is exempt as in runtime 152 (rules kept identical).
        exempt = self.rules(False, 'dns_owner_return', 'xt4')
        self.assertEqual(exempt, ['xt4 -t nat -A HETU_DNSOUT -m owner --uid-owner 0 -j RETURN'])

    def test_memory_limit_uses_memory_high_with_a_hard_ceiling_above_it(self):
        root = self.dir / 'cg2'
        (root / 'hetu_core').mkdir(parents=True)
        (root / 'cgroup.subtree_control').write_text('')
        for name in ('memory.max', 'memory.high', 'cgroup.procs'):
            (root / 'hetu_core' / name).write_text('')
        mounts = self.dir / 'mounts'
        mounts.write_text('none %s cgroup2 rw 0 0\n' % root)
        names = ('tuning_cgroup_flags', 'cg_v1_dir', 'cg_join', 'cg_record', 'core_cgroup')
        body = ('set -u\nRUN=%s\nLOG="$RUN/core.log"\nCG_MOUNTS=%s\nCG_V1_MEMORY=""\nCG_V1_BLKIO=""\nCG_STATE="$RUN/cgroup.state"\n'
                'START_MEM=cg:256M\nSTART_IO=\n' % (shq(self.run_dir), shq(mounts)) + ''.join(function(n) for n in names) +
                'tuning_cgroup_flags; START_MEM_BYTES=268435456; core_cgroup 4242; echo "rc=$?"')
        r = subprocess.run(['/bin/sh', '-c', body], capture_output=True, text=True, timeout=20)
        self.assertIn('rc=0', r.stdout, r.stderr)
        self.assertEqual((root / 'hetu_core/memory.high').read_text().strip(), '268435456')
        self.assertEqual((root / 'hetu_core/memory.max').read_text().strip(), str(268435456 * 3 // 2))
        self.assertEqual((root / 'hetu_core/cgroup.procs').read_text().strip(), '4242')
        self.assertIn('memcg=v2:268435456 memhigh=v2', (self.run_dir / 'tuning-status').read_text())


class SourceGuards(unittest.TestCase):
    def test_start_escapes_before_spawning_and_records_what_a_restart_replays(self):
        start = function('start')
        self.assertLess(start.index('cg_escape "$$"'), start.index('acquire_lock'))
        self.assertLess(start.index('cg_escape "$$"'), start.index('transaction_begin'))
        self.assertLess(start.index('start_selfheal_record "$@"'), start.index('start_watchdog "$START_PID"'))
        record = function('start_selfheal_body')
        self.assertIn('[ -n "${HETU_SELFHEAL_RESTART:-}" ] || rm -f "$RUN/state/selfheal-budget"', record)
        self.assertIn('$SETSID "$0" watchdog', function('start_watchdog'))
        self.assertIn('$SETSID "$0" monitor', function('start_monitor'))
        launch = function('core_launch')
        self.assertIn('$SETSID "$CORE_RUNNER" setuidgid "$CORE_SPEC" "$START_BIN" -d "$RUN" -f "$START_CFG"', launch)
        self.assertIn('$SETSID "$START_BIN" -d "$RUN" -f "$START_CFG"', launch)
        self.assertIn('cg_escape "$$"', function('monitor_main'))
        self.assertIn('cg_escape "$$"', function('watchdog'))

    def test_failed_restart_falls_back_to_the_existing_core_exit_path(self):
        watchdog = function('watchdog')
        # The tail keeps restoring the network (Kill Switch: guards) and now also tells the user.
        self.assertIn('cleanup_confirmed && restorev6; then', watchdog)
        self.assertIn('[ -z "$WD_HEAL_REASON" ] || wd_heal_notice "$RESULT"', watchdog)
        recover = function('wd_recover')
        self.assertLess(recover.index('wd_restart_allowed'), recover.index('kill -9 "$COREPID"'))
        self.assertIn('[ "$(cat "$PIDFILE" 2>/dev/null)" = "$COREPID" ]', recover)
        notice = function('wd_heal_notice')
        self.assertIn('Kill Switch', notice)
        self.assertIn('cmd notification post', function('wd_notify'))

    def test_monitor_heals_when_core_and_watchdog_are_gone(self):
        self.assertIn('|| { watchdog_alive || "$0" status >/dev/null 2>&1; exit 0; }', function('monitor_main'))

    def test_script_parses(self):
        for shell in ('/bin/sh', 'dash', 'bash', 'mksh', 'busybox'):
            path = shutil.which(shell) if not shell.startswith('/') else shell
            if not path:
                continue
            cmd = [path, 'sh', '-n', str(SCRIPT)] if shell == 'busybox' else [path, '-n', str(SCRIPT)]
            subprocess.run(cmd, check=True, timeout=30)


DIAG = REPO.parents[1] / 'files/uploads/hetu-diag.txt'


class DataPlaneHelpers(ShellCase):
    """3.0.0 data-plane probe helpers: DNS query bytes, CIDR match, verdict, gating."""

    def stub_nc(self, response=b''):
        fake = self.dir / 'bin'
        fake.mkdir(exist_ok=True)
        rec = self.dir / 'nc.stdin'
        resp = self.dir / 'nc.resp'
        resp.write_bytes(response)
        (fake / 'nc').write_text('#!/bin/sh\ncat > %s\ncat %s\n' % (shq(rec), shq(resp)))
        (fake / 'nc').chmod(0o755)
        return {'PATH': str(fake) + ':' + os.environ['PATH']}, rec

    @staticmethod
    def query(name):
        qname = b''.join(bytes([len(l)]) + l.encode() for l in name.strip('.').split('.')) + b'\x00'
        body = b'HT\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00' + qname + b'\x00\x01\x00\x01'
        return len(body).to_bytes(2, 'big') + body

    def test_resolve_sends_one_tcp_query_with_the_right_length_and_reads_the_first_a_record(self):
        q = self.query('www.gstatic.com')[2:]
        cname = b'\xc0\x0c\x00\x05\x00\x01\x00\x00\x00\x3c\x00\x02\xc0\x10'
        a = b'\xc0\x0c\x00\x01\x00\x01\x00\x00\x00\x3c\x00\x04\xc6\x12\x00\x07'
        answer = b'HT\x81\x80\x00\x01\x00\x02\x00\x00\x00\x00' + q[12:] + cname + a
        env, rec = self.stub_nc(len(answer).to_bytes(2, 'big') + answer)
        for name in ('www.gstatic.com', 'www.gstatic.com.'):
            with self.subTest(name=name):
                r = self.sh('WD_DP=5353; WD_HTTP_TIMEOUT=2; wd_resolve %s; echo "rc=$? ip=$WD_RES_IP"' % shq(name),
                            extra=function('wd_resolve'), env=env)
                self.assertIn('rc=0 ip=198.18.0.7', r.stdout, r.stderr)
                sent = rec.read_bytes()
                self.assertEqual(sent, self.query('www.gstatic.com'), 'trailing dot adds no empty label')
                self.assertEqual(int.from_bytes(sent[:2], 'big'), 33)
                self.assertEqual(len(sent), 35)
        env, rec = self.stub_nc(b'')
        r = self.sh('WD_DP=5353; WD_HTTP_TIMEOUT=2; wd_resolve www.baidu.com; echo "rc=$? ip=[$WD_RES_IP]"',
                    extra=function('wd_resolve'), env=env)
        self.assertIn('rc=1 ip=[]', r.stdout, r.stderr)
        self.assertEqual(rec.read_bytes(), self.query('www.baidu.com'))

    def test_in_cidr(self):
        cases = [('198.18.0.7', '198.18.0.0/16', 0), ('198.19.0.1', '198.18.0.0/16', 1), ('198.19.255.255', '198.18.0.0/15', 0),
                 ('28.0.0.1', '28.0.0.0/8', 0), ('10.0.0.1', '10.0.0.1/32', 0), ('10.0.0.2', '10.0.0.1/32', 1),
                 ('198.18.0.7', '198.18.0.0', 1), ('198.18.0.7', '198.18.0.0/0', 1), ('198.18.0.7', '198.18.0.0/33', 1),
                 ('1.2.3', '1.2.0.0/16', 1), ('1.2.3.4', 'x/16', 1)]
        body = ''.join('wd_in_cidr %s %s; echo "$?"\n' % (shq(ip), shq(c)) for ip, c, _ in cases)
        r = self.sh(body, extra=function('wd_in_cidr'))
        self.assertEqual([int(x) for x in r.stdout.split()], [rc for _, _, rc in cases], r.stderr)

    def test_classify_every_branch(self):
        # 5.1.0: without a failing group there is no proxy-down verdict (a probe queued behind a download).
        cases = [(('', 'G→n', '3'), 'proxy-down'), (('fail', 'G→n', '1'), 'proxy-down'), (('fail', '', '3'), 'dataplane-down'),
                 (('ok', '', '3'), 'skip'), (('', '', '0'), 'skip'), (('', '', ''), 'skip'),
                 (('', '', '3'), 'skip')]
        body = ''.join('wd_dp_classify %s %s %s\n' % tuple(shq(a) for a in args) for args, _ in cases)
        r = self.sh(body, extra=function('wd_dp_classify'))
        self.assertEqual(r.stdout.split(), [want for _, want in cases], r.stderr)

    def gate_session(self, **over):
        c = self.controller()
        values = {'MODE': 'tproxy', 'CONTROLLER_PORT': c.port, 'ACTIVE_DNS_PORT': 1053, 'DNS_PROTOS': 'tcp udp',
                  'SYSTEM_DNS': 'captured', 'FAKE_IP_V4': FAKE4 + ',28.0.0.0/8', 'TCP': 1}
        values.update(over)
        self.session(cp=values['CONTROLLER_PORT'], dp=values['ACTIVE_DNS_PORT'])
        (self.run_dir / 'session.state').write_text(''.join('%s=%s\n' % kv for kv in values.items()))

    def dp_on(self, extra='', gate=False):
        pre = 'SYSTEM_DNS=exempt; FAKE_IP_V4=""; '
        if gate:
            g = self.dir / 'android'
            g.mkdir(exist_ok=True)
            pre += 'CG_ESCAPE_GATE=%s; ' % shq(g)
        r = self.sh(pre + 'COREPID=$$; wd_selfheal_init; echo "on=$WD_DP_ON fake=$WD_FAKE4"', extra=extra)
        return r.stdout.strip() + r.stderr

    def test_dp_on_gating(self):
        self.gate_session()
        self.assertIn('on=1 fake=%s' % FAKE4, self.dp_on())
        for over in ({'MODE': 'tun'}, {'TCP': 0}, {'SYSTEM_DNS': 'exempt'}, {'FAKE_IP_V4': ''}, {'CONTROLLER_PORT': 0},
                     {'ACTIVE_DNS_PORT': 0}):
            with self.subTest(over=over):
                self.gate_session(**over)
                self.assertIn('on=0', self.dp_on())
        self.gate_session(MODE='redirect')
        self.assertIn('on=1', self.dp_on())
        # Android: a live uid 0-9999 RETURN without the fake-range exclusion lets root flows go direct.
        self.gate_session()
        leaky = ('xt4q(){ [ "$2" = mangle ] && printf "%s\\n" "-A HETU_MOUT -m owner --uid-owner 0-9999 -j RETURN"; return 0; }\n')
        self.assertIn('on=0', self.dp_on(extra=leaky, gate=True))
        tight = ('xt4q(){ [ "$2" = mangle ] && printf "%s\\n" "-A HETU_MOUT ! -d 198.18.0.0/16 -m owner --uid-owner 0-9999 -j RETURN"; return 0; }\n')
        self.assertIn('on=1', self.dp_on(extra=tight, gate=True))


class NetRecover(unittest.TestCase):
    def test_ids_from_the_colorOS_dumpsys_lines(self):
        if not DIAG.exists():
            self.skipTest('diagnostic upload not present')
        lines = DIAG.read_text(errors='replace').splitlines(True)[3442:3456]
        body = function('net_recover_ids') + 'net_recover_ids'
        r = subprocess.run(['/bin/sh', '-c', body], input=''.join(lines), capture_output=True, text=True, timeout=20)
        self.assertEqual(r.stdout.split(), ['286', '290', '301', '302'], r.stderr)
        r = subprocess.run(['/bin/sh', '-c', body], input='  NetworkAgentInfo{network{7} x}\nNetworkRequest network{9}\n'
                           '  NetworkAgentInfo{network{7} again}\n', capture_output=True, text=True, timeout=20)
        self.assertEqual(r.stdout.split(), ['7'], 'unique, only NetworkAgentInfo lines')


def foreign_awk_reach():
    match = re.search(r"^FOREIGN_AWK_REACH='.*?^  function own\(c\)[^\n]*'\n", TEXT, re.S | re.M)
    if not match:
        raise AssertionError('FOREIGN_AWK_REACH missing from hetu-root.sh')
    return match.group(0)


class FakeReject(ShellCase):
    """2026-10 (TikTok/stop report): the post-stop fake-IP REJECT is gone; any leftover chain is removed."""

    def remove(self, present=True, hooked=True, sticky=False, marker=False):
        calls = self.dir / 'xt.calls'
        pres = self.dir / 'present'
        hook = self.dir / 'hooked'
        if present:
            pres.write_text('')
        if hooked:
            hook.write_text('')
        if marker:
            (self.run_dir / 'fake-reject').write_text('tok\n')
        stub = ('xt4q(){ echo "$*" >> %s; case "$*" in *"-S HXFAKE_REJECT"*) [ -f %s ]; return;; *"-C OUTPUT"*) [ -f %s ]; return;;'
                ' *"-D OUTPUT"*) rm -f %s;; *"-X HXFAKE_REJECT"*) %s;; esac; return 0; }\n'
                'has(){ [ "$1" != ip6tables ] && command -v "$1" >/dev/null 2>&1; }\niptables(){ :; }\nsleep(){ :; }\n'
                % (shq(calls), shq(pres), shq(hook), shq(hook), 'return 1' if sticky else 'rm -f ' + shq(pres)))
        stub += ''.join(function(n) for n in ('fake_reject_drop', 'fake_reject_present', 'fake_reject_remove'))
        stub += top_assignment('FAKE_REJECT_CHAIN')
        r = self.sh('fake_reject_remove; echo "rc=$?"', extra=stub)
        return r, calls, pres, hook

    def test_leftover_chain_is_removed_without_the_old_marker(self):
        r, calls, pres, hook = self.remove()
        self.assertIn('rc=0', r.stdout, r.stderr)
        text = calls.read_text()
        for want in ('-t filter -D OUTPUT -j HXFAKE_REJECT', '-t filter -F HXFAKE_REJECT', '-t filter -X HXFAKE_REJECT'):
            self.assertIn(want, text)
        self.assertFalse(pres.exists())
        self.assertFalse(hook.exists())
        self.assertIn('removed the legacy fake-IP reject chain', self.log_text())

    def test_marker_alone_is_cleared_and_absent_chain_is_only_looked_up(self):
        r, calls, _, _ = self.remove(present=False, hooked=False, marker=True)
        self.assertIn('rc=0', r.stdout, r.stderr)
        self.assertFalse((self.run_dir / 'fake-reject').exists())
        self.assertEqual([l for l in calls.read_text().splitlines() if '-S HXFAKE_REJECT' not in l], [])

    def test_a_chain_that_cannot_be_deleted_is_reported(self):
        r, _, pres, _ = self.remove(sticky=True)
        self.assertIn('rc=1', r.stdout, r.stderr)
        self.assertTrue(pres.exists())
        self.assertIn('still present', self.log_text())

    def test_no_code_path_installs_the_reject_any_more(self):
        self.assertNotIn('fake_reject_arm', TEXT)
        self.assertNotIn('-j "$FAKE_REJECT_CHAIN" >/dev/null 2>&1 &&', TEXT)
        self.assertNotRegex(TEXT, r'-[AI] OUTPUT[^\n]*FAKE_REJECT_CHAIN')
        self.assertNotIn('FAKE_REJECT_SECONDS', TEXT)
        self.assertIn('fake-reject-expire) [ "$#" = 2 ] || exit 0; root; fake_reject_remove || true;;', TEXT)
        self.assertIn('foreign_safe_cleanup', function('cleanup'))
        self.assertIn('fake_reject_remove', function('foreign_safe_cleanup'))
        self.assertIn('tun_rule_sweep', function('foreign_safe_cleanup'))
        self.assertIn('[ -d "$CG_ESCAPE_GATE" ] || return 0', function('foreign_safe_cleanup'))
        self.assertIn('fake-reject', function('stop_leftovers'))


class ForeignRules(ShellCase):
    """Rules of other proxy apps: blocking ones fail the start with a message, the rest are reported."""

    def run_awk(self, func, stdin, *args):
        body = top_assignment('FAKE_REJECT_CHAIN') + top_assignment('TUN_ROUTE_TABLE') + top_assignment('TUN_RULE_PREF_MIN')
        body += top_assignment('TUN_RULE_PREF_MAX') + foreign_awk_reach()
        body += ''.join(function(n) for n in ('foreign_fake_blocks', 'foreign_target_chains', 'foreign_tproxy_chains',
                                                 'foreign_redirect_chains', 'tun_rule_lines'))
        body += func + ' ' + ' '.join(shq(a) for a in args)
        r = subprocess.run(['/bin/sh', '-c', body], input=stdin, capture_output=True, text=True, timeout=20)
        self.assertEqual(r.returncode, 0, r.stderr)
        return r.stdout.split('\n')[:-1]

    def test_fake_range_blocks_are_found_only_when_reachable_and_overlapping(self):
        rules = '\n'.join(('-P OUTPUT ACCEPT', '-N other', '-N dead', '-N HXFAKE_REJECT', '-A OUTPUT -j other',
                           '-A other -d 198.18.0.0/16 -p tcp -j REJECT --reject-with tcp-reset',
                           '-A other -d 198.16.0.0/12 -j DROP', '-A dead -d 198.18.0.0/16 -j DROP',
                           '-A HXFAKE_REJECT -d 198.18.0.0/16 -j REJECT', '-A OUTPUT -j HXFAKE_REJECT',
                           '-A OUTPUT -d 10.0.0.0/8 -j DROP', '-A HETU_KOUT -d 198.18.0.0/16 -j REJECT', '')) 
        self.assertEqual(self.run_awk('foreign_fake_blocks', rules, '198.18.0.1/16'),
                         ['other 198.18.0.0/16', 'other 198.16.0.0/12'])
        self.assertEqual(self.run_awk('foreign_fake_blocks', rules, '28.0.0.1/8'), [])

    def test_tproxy_and_redirect_of_other_apps_are_named_and_hetu_or_netd_chains_never(self):
        mangle = '\n'.join(('-N box_pre', '-N stale', '-A PREROUTING -j box_pre', '-A box_pre -p tcp -j TPROXY --on-port 7893',
                            '-A stale -p udp -j TPROXY --on-port 1', '-A PREROUTING -j HETU_MPRE',
                            '-A HETU_MPRE -p tcp -j TPROXY --on-port 7895', ''))
        self.assertEqual(self.run_awk('foreign_tproxy_chains', mangle), ['box_pre'])
        nat = '\n'.join(('-A OUTPUT -j oem_nat_pre', '-A oem_nat_pre -p tcp -j REDIRECT --to-ports 1',
                         '-A OUTPUT -j adguard_out', '-A adguard_out -p tcp --dport 443 -j REDIRECT --to-ports 8443',
                         '-A OUTPUT -j HETU_DNSOUT', '-A HETU_DNSOUT -p udp --dport 53 -j REDIRECT --to-ports 1053', ''))
        self.assertEqual(self.run_awk('foreign_redirect_chains', nat), ['adguard_out'])

    def test_dead_tun_rules_are_selected_and_netd_rules_kept(self):
        rules = ('9000:\tfrom all to 172.19.0.0/30 lookup 2022\n9001:\tfrom all goto 9010\n'
                 '9002:\tnot from all dport 53 lookup main suppress_prefixlength 0\n'
                 '9003:\tfrom all iif lo uidrange 0-9999 unreachable\n'
                 '10000:\tfrom all fwmark 0xc0000/0xd0000 lookup legacy_system\n8999:\tfrom all lookup 2022\n')
        self.assertEqual(self.run_awk('tun_rule_lines', rules),
                         ['9000\tfrom all to 172.19.0.0/30 lookup 2022', '9001\tfrom all goto 9010',
                          '9003\tfrom all iif lo uidrange 0-9999 unreachable'])

    def tun_sweep(self, table_routes):
        calls = self.dir / 'ip.calls'
        if calls.exists():
            calls.unlink()
        stub = ('ip(){ echo "$*" >> %s; case "$*" in "-4 route show table 2022") printf %s;; "-4 rule show")'
                ' printf "9000:\\tfrom all lookup 2022\\n9010:\\tfrom all nop\\n";; esac; return 0; }\n'
                'v6supported(){ return 1; }\n' % (shq(calls), shq(table_routes)))
        stub += top_assignment('TUN_ROUTE_TABLE') + top_assignment('TUN_RULE_PREF_MIN') + top_assignment('TUN_RULE_PREF_MAX')
        stub += ''.join(function(n) for n in ('tun_rule_lines', 'tun_rule_sweep'))
        r = self.sh('tun_rule_sweep; echo "rc=$?"', extra=stub)
        self.assertIn('rc=0', r.stdout, r.stderr)
        return calls.read_text()

    def test_tun_sweep_deletes_only_while_the_table_is_empty(self):
        self.assertIn('-4 rule del pref 9000 from all lookup 2022', self.tun_sweep(''))
        self.assertNotIn('rule del', self.tun_sweep('default dev tun0 scope link\n'))

    def test_start_checks_other_apps_after_its_own_cleanup_and_before_ports(self):
        start = function('start')
        self.assertLess(start.index('stopwatchdog; cleanup;'), start.index('foreign_capture_check'))
        self.assertLess(start.index('foreign_capture_check'), start.index('check_start_ports'))
        check = function('foreign_capture_check')
        self.assertIn('[ -d "$CG_ESCAPE_GATE" ] || return 0', check)
        self.assertEqual(check.count('fail "'), 3)
        self.assertIn('FOREIGN_WARN', start)


class Phase300SourceGuards(unittest.TestCase):
    def test_stop_transaction_verifies_before_dropping_the_session(self):
        stop = function('stop_transaction')
        self.assertIn('stop_verify', stop)
        self.assertLess(stop.index('[ "$SC_FAILED" = 0 ] || return 1'), stop.index('stop_verify'))
        self.assertLess(stop.index('stop_verify'), stop.index('rm -f "$SESSION"'))
        self.assertLess(stop.index('rm -f "$SESSION"'), stop.index('net_recover_async stop'))
        self.assertNotIn('fake_reject_arm', stop)
        self.assertIn('stopwatchdog; cleanup; restorev6', function('start'))

    def test_detached_helpers_escape_the_app_cgroup_with_their_own_pid(self):
        for name, cmd in (('net_recover_async', '$SETSID "$0" net-recover "$1"'),):
            body = function(name)
            self.assertIn(cmd, body)
            self.assertIn('cg_escape "$!"', body)
            self.assertLess(body.index('detach_probe'), body.index(cmd))
            self.assertLess(body.index(cmd), body.index('cg_escape "$!"'))


WIFI_NAI_LINE = ('  NetworkAgentInfo{network{373}  handle{1605428498445}  ni{WIFI CONNECTED extra: } created=Thu  Score(Policies : '
                 'TRANSPORT_PRIMARY&EVER_EVALUATED&IS_UNMETERED&EVER_VALIDATED&IS_VALIDATED ; KeepConnected : 0)  lp{{InterfaceName: wlan0 '
                 'LinkAddresses: [ 192.168.2.99/23 ] DnsAddresses: [ /202.101.224.69,/114.114.114.114 ]}}  nc{[ Transports: WIFI '
                 'Capabilities: NOT_METERED&INTERNET&NOT_RESTRICTED&TRUSTED&NOT_VPN&VALIDATED&NOT_ROAMING&FOREGROUND '
                 'LinkUpBandwidth>=49382Kbps SSID: "lh"]}  factorySerialNumber=7}\n')
CELL_NAI_LINE = ('  NetworkAgentInfo{network{375}  ni{MOBILE[NR] CONNECTED extra: ims}  lp{{InterfaceName: rmnet_data4 }}  nc{[ Transports: '
                 'CELLULAR Capabilities: IMS&NOT_METERED&TRUSTED&NOT_VPN&VALIDATED ]}\n')


class WifiHeal(ShellCase):
    """3.3.3: Wi-Fi recovery driven by a real probe (net-recover after stop/start, wifi-heal from the watchdog)."""
    FUNCS = ('wifi_nai', 'wifi_addr', 'wifi_lookup', 'wifi_get', 'wifi_probe', 'wifi_check', 'wifi_heal_allowed',
             'wifi_heal_record', 'wifi_cmd_ok', 'wifi_heal', 'wifi_heal_body')

    def bin_dir(self):
        b = self.dir / 'bin'
        b.mkdir(exist_ok=True)
        for name, body in (('cmd', 'echo "cmd $*" >> "$CALLS"; [ "$1 $2" = "wifi reconnect" ] && [ -n "${NO_RECONNECT:-}" ] && { echo "Unknown command: reconnect"; exit 255; }\n'
                                   '[ "$1" = notification ] && echo "$*" >> "$NOTES"; exit 0\n'),
                           ('svc', 'echo "svc $*" >> "$CALLS"; exit 0\n')):
            f = b / name
            f.write_text('#!/bin/sh\n' + body)
            f.chmod(0o755)
        return b

    def run_heal(self, states, reason='stop', flushed='flushed', extra='', env=None):
        """wifi_check yields the given states in order (the last one repeats)."""
        b = self.bin_dir()
        seq = self.dir / 'states'
        seq.write_text('\n'.join(states) + '\n')
        stubs = ('wifi_check(){ WC_STATE=$(head -n 1 %s); [ "$(wc -l < %s)" -le 1 ] || sed -i 1d %s; WC_DETAIL="wlan0 stub"; }\n'
                 'net_recover(){ echo "net_recover $1" >> "$CALLS"; }\nsleep(){ :; }\n') % (shq(seq), shq(seq), shq(seq))
        fn = ''.join(function(n) for n in self.FUNCS if n != 'wifi_check')
        e = {'PATH': '%s:%s' % (b, os.environ['PATH']), 'CALLS': str(self.dir / 'calls'), 'NOTES': str(self.dir / 'notes')}
        e.update(env or {})
        body = 'wifi_heal %s %s; echo "rc=$?"' % (shq(reason), shq(flushed))
        r = self.sh(body, extra=fn + stubs + extra, env=e)
        calls = (self.dir / 'calls').read_text() if (self.dir / 'calls').exists() else ''
        notes = (self.dir / 'notes').read_text() if (self.dir / 'notes').exists() else ''
        return r, calls, notes

    def test_nai_reads_wifi_interface_and_validated_flag_from_dumpsys(self):
        body = function('wifi_nai') + 'wifi_nai'
        run = lambda text: subprocess.run(['/bin/sh', '-c', body], input=text, capture_output=True, text=True, timeout=20).stdout.strip()
        self.assertEqual(run(CELL_NAI_LINE + WIFI_NAI_LINE), 'wlan0 1')
        # EVER_VALIDATED / IS_VALIDATED in the score are not the capability.
        self.assertEqual(run(WIFI_NAI_LINE.replace('NOT_VPN&VALIDATED&', 'NOT_VPN&')), 'wlan0 0')
        self.assertEqual(run(CELL_NAI_LINE), '', 'no Wi-Fi network')
        self.assertEqual(run(WIFI_NAI_LINE.replace('&INTERNET&', '&')), '', 'Wi-Fi without INTERNET is not the one')

    def test_addr_parses_both_busybox_nslookup_formats(self):
        old = 'Server:    223.5.5.5\nAddress 1: 223.5.5.5 public1.alidns.com\n\nName:      www.google.cn\nAddress 1: 180.163.151.171\n'
        new = 'Server:\t\t223.5.5.5\nAddress:\t223.5.5.5:53\n\nNon-authoritative answer:\nName:\twww.google.cn\nAddress: 180.163.150.33\n'
        body = function('wifi_addr') + 'wifi_addr'
        for text, want in ((old, '180.163.151.171'), (new, '180.163.150.33'), ('Server: 1.1.1.1\nAddress 1: 1.1.1.1\n', '')):
            r = subprocess.run(['/bin/sh', '-c', body], input=text, capture_output=True, text=True, timeout=20)
            self.assertEqual(r.stdout.strip(), want)

    def test_probe_runs_as_core_identity_resolves_publicly_and_needs_a_204_over_wifi(self):
        b = self.bin_dir()
        bb = b / 'busybox'
        bb.write_text('#!/bin/sh\necho "bb $*" >> "$CALLS"\n'
                      'if [ "$1" = setuidgid ]; then shift 2; exec "$@"; fi\n'
                      'case "$1" in\n'
                      '  nslookup) [ "$3" = "${DEAD_DNS:-x}" ] && exit 1; printf "Server: %s\\n\\nName: %s\\nAddress 1: 203.0.113.%s\\n" "$3" "$2" "${#2}";;\n'
                      '  wget) for a; do case "$a" in http://*) u="$a";; esac; done; echo "wget $u" >> "$CALLS"\n'
                      '        case "$u" in *"${OK_IP:-none}"*) echo "  HTTP/1.1 204 No Content" >&2;; *) echo "  HTTP/1.1 ${CODE:-302} Found" >&2;; esac;;\n'
                      'esac\n')
        bb.chmod(0o755)
        ip = b / 'ip'
        ip.write_text('#!/bin/sh\necho "$4 via 192.168.2.1 dev ${ROUTE_DEV:-wlan0} table wlan0 src 192.168.2.99 uid 0"\n')
        ip.chmod(0o755)
        extra = ('wifi_runner(){ WP_BB=%s; WP_RUN="%s setuidgid root:net_admin"; }\n'
                 'has(){ [ "$1" != curl ] && command -v "$1" >/dev/null 2>&1; }\n') % (shq(bb), bb)
        extra += ''.join(function(n) for n in ('wifi_addr', 'wifi_lookup', 'wifi_get', 'wifi_probe'))
        e = {'PATH': '%s:%s' % (b, os.environ['PATH']), 'CALLS': str(self.dir / 'calls')}

        def probe(**env):
            if (self.dir / 'calls').exists():
                (self.dir / 'calls').unlink()
            r = self.sh('wifi_probe wlan0; echo "rc=$? how=$WP_HOW"', extra=extra, env={**e, **env})
            return r.stdout.strip(), (self.dir / 'calls').read_text() if (self.dir / 'calls').exists() else ''

        # www.google.cn (13 chars) answers 204.
        out, calls = probe(OK_IP='203.0.113.13')
        self.assertIn('rc=0 how=www.google.cn=204', out)
        self.assertIn('bb setuidgid root:net_admin %s nslookup www.google.cn 223.5.5.5' % bb, calls)
        self.assertIn('wget http://203.0.113.13/generate_204', calls)
        self.assertNotIn('gstatic', calls)
        # A captive/blocked answer (302) from every probe host is a failure, the first public resolver may be down.
        out, calls = probe(DEAD_DNS='223.5.5.5')
        self.assertIn('rc=1', out)
        for host in ('www.google.cn=302', 'connect.rom.miui.com=302', 'www.qualcomm.cn=302'):
            self.assertIn(host, out)
        self.assertIn('nslookup www.google.cn 119.29.29.29', calls)
        # The route is not the Wi-Fi (e.g. cellular became the default): cannot test.
        out, _ = probe(ROUTE_DEV='rmnet_data1', OK_IP='203.0.113.13')
        self.assertIn('rc=2 how=route-not-wlan0', out)

    def test_working_wifi_is_left_alone(self):
        r, calls, notes = self.run_heal(['ok'])
        self.assertIn('rc=0', r.stdout, r.stderr)
        self.assertNotIn('svc', calls)
        self.assertNotIn('wifi reconnect', calls)
        self.assertIn('wifi-heal (stop): Wi-Fi works', self.log_text())
        self.assertFalse((self.run_dir / 'wifi-heal.lock').exists())

    def test_bad_wifi_escalates_reconnect_then_off_on_with_log_notification_and_record(self):
        r, calls, notes = self.run_heal(['bad'])
        self.assertIn('rc=0', r.stdout, r.stderr)
        lines = calls.split('\n')
        self.assertLess(lines.index('cmd wifi reconnect'), lines.index('svc wifi disable'))
        self.assertLess(lines.index('svc wifi disable'), lines.index('svc wifi enable'))
        self.assertNotIn('net_recover', calls, 'net-recover already flushed and re-evaluated')
        log = self.log_text()
        self.assertIn('wifi-heal (stop): Wi-Fi has no internet', log)
        self.assertIn('reconnected Wi-Fi (cmd wifi reconnect, then Wi-Fi off/on)', log)
        self.assertIn('hetu-selfheal', notes)
        self.assertIn('自动重连 Wi‑Fi', notes)
        self.assertEqual(len((self.run_dir / 'state/wifi-reconnects').read_text().split()), 1)

    def test_reconnect_that_fixes_it_skips_off_on_and_missing_reconnect_command_goes_to_off_on(self):
        r, calls, _ = self.run_heal(['bad', 'ok'])
        self.assertIn('cmd wifi reconnect', calls)
        self.assertNotIn('svc wifi', calls)
        self.assertIn('reconnected Wi-Fi (cmd wifi reconnect); now ok', self.log_text())
        (self.run_dir / 'state/wifi-reconnects').unlink()
        (self.dir / 'calls').unlink()
        r, calls, _ = self.run_heal(['bad'], env={'NO_RECONNECT': '1'})
        self.assertIn('svc wifi disable', calls)
        self.assertIn('reconnected Wi-Fi (Wi-Fi off/on)', self.log_text())

    def test_watchdog_reason_flushes_and_reevaluates_first(self):
        r, calls, _ = self.run_heal(['bad', 'ok'], reason='watchdog', flushed='')
        self.assertIn('net_recover wifi-heal (watchdog)', calls)
        self.assertNotIn('wifi reconnect', calls)
        self.assertIn('Wi-Fi works again after flushnet + reevaluate', self.log_text())

    def test_policy_marker_turns_the_reconnect_off(self):
        (self.dir / 'policy').mkdir()
        (self.dir / 'policy/wifi-reconnect-off').write_text('')
        r, calls, notes = self.run_heal(['bad'])
        self.assertNotIn('svc', calls)
        self.assertNotIn('wifi reconnect', calls)
        self.assertEqual(notes, '')
        self.assertIn('Wi-Fi reconnect is turned off in the settings', self.log_text())

    def test_rate_limit_once_per_ten_minutes_and_three_per_hour(self):
        now = int(time.time())
        state = self.run_dir / 'state/wifi-reconnects'
        cases = [([], 0), ([now - 120], 1), ([now - 700], 0), ([now - 3000, now - 2000, now - 1000], 1),
                 ([now - 4000, now - 2000, now - 1000], 0), ([now + 9999], 0)]
        fn = function('wifi_heal_allowed')
        for stamps, want in cases:
            with self.subTest(stamps=[s - now for s in stamps]):
                state.write_text(''.join('%d\n' % s for s in stamps))
                r = self.sh('wifi_heal_allowed; echo "rc=$? $WHA_WHY"', extra=fn + 'WHA_WHY=""\n')
                self.assertIn('rc=%d' % want, r.stdout, r.stderr)
        state.write_text('%d\n' % (now - 60))
        r, calls, _ = self.run_heal(['bad'])
        self.assertNotIn('svc', calls)
        self.assertIn('not reconnecting Wi-Fi: last reconnect', self.log_text())

    def test_a_running_heal_is_not_doubled(self):
        lock = self.run_dir / 'wifi-heal.lock'
        lock.mkdir()
        (lock / 'pid').write_text(str(os.getpid()))
        r, calls, _ = self.run_heal(['bad'])
        self.assertEqual(calls, '')
        self.assertTrue(lock.exists())
        (lock / 'pid').write_text('999999')
        r, calls, _ = self.run_heal(['ok'])
        self.assertIn('Wi-Fi works', self.log_text(), 'a stale lock is taken over')


class WholePhoneOutage(ShellCase):
    """3.3.3: proxy and DIRECT both failing while the core answers starts the Wi-Fi check (watchdog blind spot)."""

    def ticks(self, c, n, gate=True):
        self.session(cp=c.port)
        self0 = self.dir / 'hetu-root.sh'
        spawned = self.dir / 'spawned'
        self0.write_text('#!/bin/sh\necho "$*" >> %s\n' % shq(spawned))
        self0.chmod(0o755)
        g = self.dir / 'android'
        g.mkdir(exist_ok=True)
        body = ('CG_ESCAPE_V1=""; ' + ('CG_ESCAPE_GATE=%s; ' % shq(g) if gate else '') +
                'COREPID=$$; KILL=0; wd_selfheal_init; WD_EGRESS_RETRY=0; WD_EGRESS_EVERY=0; '
                'for i in $(seq %d); do wd_now; WD_EG_AT=0; wd_egress_tick; done; echo "off=$WD_OFF_N level=$WD_EG_LEVEL"' % n)
        r = subprocess.run(['/bin/sh', '-c', self.prelude('wd_restart_core(){ return 1; }\n') + body, str(self0)],
                           capture_output=True, text=True, timeout=120)
        for _ in range(30):
            if spawned.exists() or not gate:
                break
            time.sleep(0.1)
        return r, spawned.read_text() if spawned.exists() else ''

    def test_direct_failing_too_spawns_one_wifi_heal_and_never_touches_the_core(self):
        c = self.controller()
        c.proxy_ok = False
        c.direct_ok = False
        r, spawned = self.ticks(c, 5)
        self.assertIn('level=0', r.stdout, r.stderr)
        self.assertEqual(spawned.split('\n')[0], 'wifi-heal watchdog')
        self.assertEqual(spawned.count('wifi-heal'), 1, 'next check only after WIFI_HEAL_GAP/2')
        self.assertIn('whole-phone outage (proxy egress via', self.log_text())
        self.assertFalse([1 for m, p, a in c.requests if m in ('DELETE', 'POST')])
        direct = [u for u in (unquote(p) for m, p, a in c.requests) if u.startswith('/proxies/DIRECT/')]
        self.assertTrue(direct and not [p for p in direct if 'gstatic' in p], direct)
        self.assertIn('url=http://connect.rom.miui.com/generate_204', direct[0])

    def test_one_offline_verdict_or_a_working_direct_path_does_not(self):
        c = self.controller()
        c.proxy_ok = False
        c.direct_ok = False
        r, spawned = self.ticks(c, 1)
        self.assertIn('off=1', r.stdout, r.stderr)
        self.assertEqual(spawned, '')
        c.direct_ok = True
        r, spawned = self.ticks(c, 2)
        self.assertIn('off=0', r.stdout, r.stderr)
        self.assertEqual(spawned, '')

    def test_off_android_nothing_is_spawned(self):
        c = self.controller()
        c.proxy_ok = False
        c.direct_ok = False
        r, spawned = self.ticks(c, 4, gate=False)
        self.assertEqual(spawned, '')
        self.assertNotIn('whole-phone outage', self.log_text())


class Phase333SourceGuards(unittest.TestCase):
    def test_net_recover_runs_the_wifi_check_after_flush_and_watchdog_offline_uses_it(self):
        self.assertIn('net-recover) [ "$#" = 2 ] || exit 0; root; net_recover "$2"; wifi_heal "$2" flushed;;', TEXT)
        self.assertIn('wifi-heal) [ "$#" = 2 ] || exit 0; root; wifi_heal "$2";;', TEXT)
        self.assertIn('wd_offline_seen "$WD_DP_DETAIL"', function('wd_dataplane_tick'))
        self.assertIn('wd_offline_seen "proxy egress via $WD_EG_TARGET and DIRECT fail"', function('wd_egress_tick'))
        async_body = function('wifi_heal_async')
        self.assertLess(async_body.index('detach_probe'), async_body.index('$SETSID "$0" wifi-heal "$1"'))
        self.assertLess(async_body.index('$SETSID "$0" wifi-heal "$1"'), async_body.index('cg_escape "$!"'))
        # The start path itself only spawns net-recover (single su; the probe runs detached).
        self.assertNotIn('wifi_heal', function('start'))

    def test_direct_checks_and_wifi_probe_avoid_google_hosts_unreachable_direct_in_china(self):
        for name in ('WD_DIRECT_URLS', 'WIFI_HEAL_URLS'):
            line = top_assignment(name)
            self.assertNotIn('gstatic', line)
            self.assertNotIn('google.com', line)
            self.assertIn('connect.rom.miui.com/generate_204', line)
        self.assertIn('www.google.cn/generate_204', top_assignment('WIFI_HEAL_URLS'))
        self.assertEqual(top_assignment('WIFI_HEAL_OFF'), 'WIFI_HEAL_OFF="$BASE/policy/wifi-reconnect-off"\n')

    def test_app_side_policy_and_validation_list(self):
        pkg = REPO / 'android-app/app/src/main/java/io/github/xgl34222220/hetu'
        adblock = (pkg / 'AdblockDnsPolicy.java').read_text()
        hosts = re.search(r'VALIDATION_PROBE_HOSTS =\s*Collections\.unmodifiableList\(Arrays\.asList\((.*?)\)\);', adblock, re.S).group(1)
        extra = re.search(r'VALIDATION_PROBE_EXTRA_HOSTS =\s*Collections\.unmodifiableList\(Arrays\.asList\((.*?)\)\);', adblock, re.S).group(1)
        suffixes = re.search(r'VALIDATION_PROBE_SUFFIXES =\s*Collections\.unmodifiableList\(Arrays\.asList\((.*?)\)\);', adblock, re.S).group(1)
        direct = hosts + extra + suffixes
        for gone in ('connectivitycheck.gstatic.com', 'connectivitycheck.android.com', 'gstatic.com', 'google.com'):
            self.assertNotIn('"%s"' % gone, direct)
        for kept in ('connect.rom.miui.com', 'www.qualcomm.cn', 'connectivitycheck.platform.hicloud.com', 'conn1.oppomobile.com',
                     'conn2.oppomobile.com', 'wifi.vivo.com.cn', 'allawntech.com', 'google.cn', 'googleapis.cn'):
            self.assertIn('"%s"' % kept, direct)
        bypass = (pkg / 'app/CaptivePortalBypass.kt').read_text()
        self.assertIn('"gstatic.com"', re.search(r'NEVER_DIRECT = listOf\((.*?)\)', bypass).group(1))
        reval = (pkg / 'app/NetworkRevalidation.kt').read_text()
        self.assertIn('const val WIFI_RECONNECT_DEFAULT = true', reval)
        self.assertNotIn('svc wifi', reval, 'the App no longer toggles Wi-Fi itself')
        manager = (pkg / 'RootProxyManager.java').read_text()
        self.assertIn('prefs.getBoolean("proxyWifiToggleRecovery",true)', manager)
        self.assertIn('policyDir+"/wifi-reconnect-off"', manager)


if __name__ == '__main__':
    unittest.main()
