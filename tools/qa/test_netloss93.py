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
  only failures while DIRECT works count; three of them close all connections, then reload in
  place (Mihomo POST /restart), then restart the core, each step behind an exponential backoff.
* network change: an App repair-network call leaves a kick that makes the watchdog re-probe.
* rule shapes: preserve_existing_tcp never lets a fake-IP destination go direct, the network
  stack UID 1073 stays out of DNS takeover (captured resolver); cgroup memory limit uses memory.high (+50 % max).
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
                 'WD_RESTART_WINDOW', 'WD_LOG_MAX', 'WD_PROXY_URL', 'WD_DIRECT_URLS', 'WD_HEAL_REASON')
HELPERS = ('has', 'port', 'kind_has_api', 'cg_join', 'detach_probe', 'cg_escape', 'wd_log', 'wd_log_body', 'wd_now',
           'wd_notify', 'wd_http', 'wd_dns_probe', 'wd_core_frozen', 'wd_selfheal_init', 'wd_net_sig', 'wd_net_watch',
           'wd_liveness', 'wd_restart_allowed', 'wd_restart_core', 'wd_heal_notice', 'wd_recover', 'wd_egress_target',
           'wd_urlenc', 'wd_delay', 'wd_egress_tick', 'wd_selfheal_tick', 'fake_range', 'wd_session_fake_range',
           'wd_root_fake_captured')


class Controller:
    """A Clash-API stand-in: records requests, answers per path, or stays silent."""

    def __init__(self, secret='s3cr3t'):
        self.requests = []
        self.secret = secret
        self.proxy_ok = True
        self.direct_ok = True
        self.mode = 'rule'
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

    def test_ladder_closes_connections_then_reloads_then_restarts_with_backoff(self):
        c = self.controller()
        c.proxy_ok = False
        r = self.run_ticks(c, 3, extra='WD_EGRESS_BACKOFF=0; WD_EG_BACKOFF=0; ')
        self.assertIn('level=1', r.stdout, r.stderr)
        self.assertIn(('DELETE', '/connections'), [(m, p) for m, p, a in c.requests])
        r = self.run_ticks(c, 9, extra='WD_EGRESS_BACKOFF=0; WD_EG_BACKOFF=0; ')
        actions = [(m, p) for m, p, a in c.requests if m in ('DELETE', 'POST')]
        self.assertEqual(actions[1:3], [('DELETE', '/connections'), ('POST', '/restart')], actions)
        self.assertIn('level=3', r.stdout)
        self.assertEqual((self.run_dir / 'restart.calls').read_text().split(), ['restart'])
        log = self.log_text()
        for text in ('proxy egress probe failed', 'closed all core connections', 'core reloaded in place', 'restarting the core'):
            self.assertIn(text, log)
        # With the default backoff a second sustained failure waits instead of acting again.
        c.requests.clear()
        r = self.run_ticks(c, 6)
        actions = [(m, p) for m, p, a in c.requests if m in ('DELETE', 'POST')]
        self.assertEqual(actions, [('DELETE', '/connections')], actions)
        self.assertIn('next self-heal action in', self.log_text())

    def test_recovery_resets_the_ladder_and_refused_secret_turns_the_check_off(self):
        c = self.controller()
        r = self.run_ticks(c, 1, extra='WD_EG_LEVEL=2; WD_EG_FAIL=2; ')
        self.assertIn('level=0 fail=0', r.stdout, r.stderr)
        self.assertIn('proxy egress recovered', self.log_text())
        c.secret = 'rotated'
        r = self.run_ticks(c, 1)
        self.assertIn('on=0', r.stdout)


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
        cases = [(('', 'G→n', '3'), 'proxy-down'), (('fail', 'G→n', '1'), 'proxy-down'), (('fail', '', '3'), 'dataplane-down'),
                 (('ok', '', '3'), 'proxy-down'), (('', '', '0'), 'proxy-down'), (('', '', ''), 'proxy-down'),
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


class FakeReject(ShellCase):
    def arm(self, gate=True, v4=FAKE4 + ',28.0.0.0/8', v6='fc00::/18'):
        calls = self.dir / 'xt.calls'
        hooked = self.dir / 'hooked'
        spawned = self.dir / 'spawned'
        self0 = self.dir / 'hetu-root.sh'
        self0.write_text('#!/bin/sh\necho "$*" >> %s\n' % shq(spawned))
        self0.chmod(0o755)
        g = self.dir / 'android'
        g.mkdir(exist_ok=True)
        stub = ('xt4q(){ echo "$*" >> %s; case "$*" in *"-C OUTPUT"*) [ -f %s ]; return;; *"-I OUTPUT"*) : > %s;; *"-D OUTPUT"*) rm -f %s;; esac; return 0; }\n'
                'has(){ [ "$1" != ip6tables ] && command -v "$1" >/dev/null 2>&1; }\nv6supported(){ return 0; }\n'
                % (shq(calls), shq(hooked), shq(hooked), shq(hooked)))
        stub += ''.join(function(n) for n in ('fake_reject_arm', 'fake_reject_drop', 'fake_reject_remove'))
        stub += top_assignment('FAKE_REJECT_CHAIN') + top_assignment('FAKE_REJECT_SECONDS')
        body = 'CG_ESCAPE_V1=""; '
        if gate:
            body += 'CG_ESCAPE_GATE=%s; ' % shq(g)
        body += 'fake_reject_arm %s %s; echo "rc=$?"' % (shq(v4), shq(v6))
        r = subprocess.run(['/bin/sh', '-c', self.prelude(stub) + body, str(self0)], capture_output=True, text=True, timeout=30)
        self.assertIn('rc=0', r.stdout, r.stderr)
        return calls, hooked, spawned, stub, self0

    def test_arm_installs_reset_rules_and_spawns_the_expiry_then_remove_clears_them(self):
        calls, hooked, spawned, stub, self0 = self.arm()
        text = calls.read_text()
        for want in ('-t filter -N HXFAKE_REJECT',
                     '-t filter -A HXFAKE_REJECT -d %s -p tcp -j REJECT --reject-with tcp-reset' % FAKE4,
                     '-t filter -A HXFAKE_REJECT -d %s -p udp -j REJECT --reject-with icmp-port-unreachable' % FAKE4,
                     '-t filter -I OUTPUT 1 -j HXFAKE_REJECT'):
            self.assertIn(want, text)
        self.assertNotIn('28.0.0.0', text, 'only the first CIDR of a list')
        self.assertTrue(hooked.exists())
        token = (self.run_dir / 'fake-reject').read_text().strip()
        self.assertTrue(token)
        for _ in range(50):
            if spawned.exists():
                break
            time.sleep(0.1)
        self.assertEqual(spawned.read_text().strip(), 'fake-reject-expire ' + token)
        self.assertIn('are reset for 120s', self.log_text())
        calls.unlink()
        r = subprocess.run(['/bin/sh', '-c', self.prelude(stub) + 'fake_reject_remove; echo "rc=$?"', str(self0)],
                           capture_output=True, text=True, timeout=30)
        self.assertIn('rc=0', r.stdout, r.stderr)
        text = calls.read_text()
        for want in ('-t filter -D OUTPUT -j HXFAKE_REJECT', '-t filter -F HXFAKE_REJECT', '-t filter -X HXFAKE_REJECT'):
            self.assertIn(want, text)
        self.assertFalse(hooked.exists())
        self.assertFalse((self.run_dir / 'fake-reject').exists())
        calls.unlink()
        subprocess.run(['/bin/sh', '-c', self.prelude(stub) + 'fake_reject_remove', str(self0)], timeout=30)
        self.assertFalse(calls.exists(), 'no window opened: remove touches nothing')

    def test_arm_does_nothing_off_android_or_without_a_range(self):
        for kw in ({'gate': False}, {'v4': '', 'v6': ''}, {'v4': 'bogus', 'v6': ''}, {'v4': '198.18.0.0/16;x', 'v6': ''}):
            with self.subTest(**kw):
                calls, hooked, spawned, _, _ = self.arm(**kw)
                self.assertFalse(hooked.exists())
                self.assertFalse((self.run_dir / 'fake-reject').exists())
                self.assertFalse(spawned.exists())
                if calls.exists():
                    self.assertNotIn('-N HXFAKE_REJECT', calls.read_text())
                    calls.unlink()


class Phase300SourceGuards(unittest.TestCase):
    def test_stop_transaction_verifies_before_dropping_the_session(self):
        stop = function('stop_transaction')
        self.assertIn('stop_verify', stop)
        self.assertLess(stop.index('[ "$SC_FAILED" = 0 ] || return 1'), stop.index('stop_verify'))
        self.assertLess(stop.index('stop_verify'), stop.index('rm -f "$SESSION"'))
        self.assertLess(stop.index('rm -f "$SESSION"'), stop.index('fake_reject_arm "$ST_FAKE4" "$ST_FAKE6"'))
        self.assertIn('net_recover_async stop', stop)
        self.assertIn('fake_reject_remove; stopwatchdog; cleanup', function('start'))

    def test_detached_helpers_escape_the_app_cgroup_with_their_own_pid(self):
        for name, cmd in (('fake_reject_arm', '$SETSID "$0" fake-reject-expire "$FRA_TOKEN"'),
                          ('net_recover_async', '$SETSID "$0" net-recover "$1"')):
            body = function(name)
            self.assertIn(cmd, body)
            self.assertIn('cg_escape "$!"', body)
            self.assertLess(body.index('detach_probe'), body.index(cmd))
            self.assertLess(body.index(cmd), body.index('cg_escape "$!"'))


if __name__ == '__main__':
    unittest.main()
