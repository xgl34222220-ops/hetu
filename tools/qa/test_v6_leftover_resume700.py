#!/usr/bin/env python3
"""7.0.0 host regression: leftover guard chains, xtables lock retries, fail-open auto-resume and soft-pass.

Field report 2026-10-24 (OnePlus PLK110 / ColorOS, Android 17, KernelSU): the user switched Wi-Fi
(192.168.2.158 -> 192.168.1.141); the core logged DIRECT "network is unreachable"; at 17:31 the watchdog failed open
(REASON Wi-Fi validated=0, no default network, DIRECT fails too; RESULT=clean). Afterwards every start failed with
"IPv6 禁用保护安装失败，未启动代理" while ip6tables still held HETU_V6OUT and HETU_DOTOUT, complete and hooked from OUTPUT:
every later `-N` failed with "Chain already exists", and the proxy stayed off for the rest of the boot.

* Task 1: xt4/xt6 retry a lock failure (exit 4) twice; a failed `-N` of a leftover HETU chain is reclaimed (unhooked
  from the builtin chains, flushed) so the caller installs exactly one hook; the batch restore does the same; a
  rollback whose cleanup could not be confirmed removes the IPv6-disable / DoT guards one bounded command at a time.
* Task 2: after a fail-open, a detached worker resumes the proxy once the network is back (default network, route,
  DIRECT 204) and stable, not within 120 s of the fail-open, at most twice per boot; an explicit stop disarms it.
* Task 3: HETU_V6OUT lets system UIDs (resolver / NetworkMonitor) through before its port-53 REJECTs; the watchdog lifts
  only the DNS takeover and DoT guard hooks (soft-pass) before it fails open, and puts them back when the network works.

The netfilter state is tools/qa/netfilter_model93.py (through test_failopen700's NetModelCase).
"""
import importlib.util
import os
from pathlib import Path
import shlex
import subprocess
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_netloss93 import TEXT, function  # noqa: E402

spec = importlib.util.spec_from_file_location('failopen700_fixture_v6', Path(__file__).with_name('test_failopen700.py'))
fo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fo)
NetModelCase = fo.NetModelCase

# The field dump's guard chains (ip6tables filter), hooked from OUTPUT.
FIELD_V6 = (
    ('-N', 'HETU_DOTOUT'), ('-A', 'HETU_DOTOUT', '-m', 'owner', '--uid-owner', '0', '--gid-owner', '3005', '-j', 'RETURN'),
    ('-A', 'HETU_DOTOUT', '-p', 'tcp', '-m', 'owner', '--uid-owner', '0', '-m', 'tcp', '--dport', '853', '-j', 'REJECT', '--reject-with', 'tcp-reset'),
    ('-N', 'HETU_V6OUT'), ('-A', 'HETU_V6OUT', '-o', 'lo', '-j', 'RETURN'), ('-A', 'HETU_V6OUT', '-p', 'ipv6-icmp', '-j', 'RETURN'),
    ('-A', 'HETU_V6OUT', '-p', 'udp', '-m', 'udp', '--dport', '53', '-j', 'REJECT', '--reject-with', 'icmp6-port-unreachable'),
    ('-A', 'HETU_V6OUT', '-m', 'owner', '--uid-owner', '0-9999', '-j', 'RETURN'),
    ('-A', 'HETU_V6OUT', '-j', 'REJECT', '--reject-with', 'icmp6-port-unreachable'),
    ('-A', 'OUTPUT', '-j', 'HETU_DOTOUT'), ('-A', 'OUTPUT', '-j', 'HETU_V6OUT'),
)
ACTIVE = 'START_ACTIVE=1; START_ROLLBACK=0; TXN_DEADLINE=999999999; START_CANCEL_TOKEN=$(cat "$RUN/start-cancel-generation" 2>/dev/null || true)\n'
GUARDS = 'CORE_GID=3005\ninstall_v6_disable 0 || exit 9\ninstall_dot_guard xt6 || exit 8\necho installed\n'


class Base(NetModelCase):
    def setUp(self):
        super().setUp()
        self.bin_dir = self.bin

    def lines(self, tool, table):
        return [l for l in self.model(tool, '-t', table, '-S').stdout.splitlines() if l]

    def seed(self, tool, table, rules):
        for r in rules:
            res = self.model(tool, '-t', table, *r)
            self.assertEqual(0, res.returncode, (r, res.stderr))

    def flaky(self, tool, pattern, times, code=4):
        """`tool` fails `times` times with `code` (nothing committed) for calls whose args contain `pattern`."""
        counter = self.dir / ('flaky-' + tool)
        counter.write_text(str(times))
        c = self.bin_dir / tool
        c.write_text('#!/bin/sh\n'
                     'n=$(cat %s)\n'
                     'case " $* " in *%s*) if [ "$n" -gt 0 ]; then echo $((n-1)) > %s; '
                     'echo "Another app is currently holding the xtables lock." >&2; cat >/dev/null 2>&1 </dev/null; exit %d; fi;; esac\n'
                     'exec %s %s %s "$@"\n' % (shlex.quote(str(counter)), shlex.quote(pattern), shlex.quote(str(counter)), code,
                                              shlex.quote(sys.executable), shlex.quote(str(fo.MODEL)), tool))
        c.chmod(0o700)
        return counter


class LeftoverReclaim(Base):
    def test_hooked_field_leftovers_are_reclaimed_with_exactly_one_hook_each(self):
        for prefix in ('', ACTIVE):
            with self.subTest(start_active=bool(prefix)):
                self.state.unlink(missing_ok=True)
                self.seed('ip6tables', 'filter', FIELD_V6)
                r = self.body(prefix + GUARDS)
                self.assertEqual(0, r.returncode, r.stdout + r.stderr)
                rules = self.lines('ip6tables', 'filter')
                self.assertEqual(1, rules.count('-A OUTPUT -j HETU_V6OUT'), rules)
                self.assertEqual(1, rules.count('-A OUTPUT -j HETU_DOTOUT'), rules)
                v6 = [l for l in rules if l.startswith('-A HETU_V6OUT ')]
                self.assertEqual(['-A HETU_V6OUT -o lo -j RETURN', '-A HETU_V6OUT -p ipv6-icmp -j RETURN',
                                  '-A HETU_V6OUT -m owner --uid-owner 0-9999 -j RETURN',
                                  '-A HETU_V6OUT -p udp --dport 53 -j REJECT', '-A HETU_V6OUT -p tcp --dport 53 -j REJECT',
                                  '-A HETU_V6OUT -j REJECT'], v6)
                dot = [l for l in rules if l.startswith('-A HETU_DOTOUT ')]
                self.assertEqual(2, len(dot), dot)
                self.assertIn('reclaimed leftover ip6tables filter chain HETU_V6OUT', (self.base / 'run' / 'watchdog.log').read_text())

    def test_hooks_with_matches_are_removed_too(self):
        self.seed('ip6tables', 'filter', FIELD_V6 + (('-A', 'OUTPUT', '-o', 'wlan0', '-j', 'HETU_V6OUT'),
                                                     ('-A', 'FORWARD', '-j', 'HETU_V6OUT')))
        r = self.body(GUARDS)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        rules = self.lines('ip6tables', 'filter')
        self.assertEqual([l for l in rules if l.endswith('-j HETU_V6OUT')], ['-A OUTPUT -j HETU_V6OUT'])

    def test_kill_switch_chain_and_foreign_chains_keep_the_failure(self):
        self.seed('ip6tables', 'filter', (('-N', 'HETU_KOUT'), ('-A', 'HETU_KOUT', '-j', 'REJECT'), ('-I', 'OUTPUT', '1', '-j', 'HETU_KOUT'),
                                          ('-N', 'fw_other'), ('-A', 'OUTPUT', '-j', 'fw_other')))
        r = self.body('xt6 -t filter -N HETU_KOUT; echo "kout=$?"; xt6 -t filter -N fw_other; echo "fw=$?"')
        self.assertIn('kout=1', r.stdout, r.stderr)
        self.assertIn('fw=1', r.stdout, r.stderr)
        rules = self.lines('ip6tables', 'filter')
        self.assertIn('-A OUTPUT -j HETU_KOUT', rules)
        self.assertIn('-A HETU_KOUT -j REJECT', rules)
        self.assertIn('-A OUTPUT -j fw_other', rules)

    def test_a_chain_that_really_is_absent_keeps_the_failure(self):
        self.flaky('ip6tables', '-N HETU_V6OUT', 5, code=1)
        r = self.body(GUARDS)
        self.assertEqual(9, r.returncode, r.stdout + r.stderr)
        self.assertNotIn('installed', r.stdout)


class LockRetry(Base):
    def test_lock_failure_once_then_success(self):
        for prefix in ('', ACTIVE):
            with self.subTest(start_active=bool(prefix)):
                self.state.unlink(missing_ok=True)
                counter = self.flaky('ip6tables', '-N HETU_V6OUT', 1)
                r = self.body(prefix + GUARDS)
                self.assertEqual(0, r.returncode, r.stdout + r.stderr)
                self.assertEqual('0', counter.read_text().strip())
                rules = self.lines('ip6tables', 'filter')
                self.assertEqual(1, rules.count('-A OUTPUT -j HETU_V6OUT'), rules)

    def test_lock_retry_is_bounded(self):
        counter = self.flaky('ip6tables', '-N HETU_V6OUT', 3)
        r = self.body(GUARDS)
        self.assertEqual(9, r.returncode, r.stdout + r.stderr)
        self.assertEqual('0', counter.read_text().strip(), 'three attempts in all')

    def test_other_failures_are_not_retried(self):
        counter = self.flaky('ip6tables', '-N HETU_V6OUT', 2, code=2)
        r = self.body(GUARDS)
        self.assertEqual(9, r.returncode, r.stdout + r.stderr)
        self.assertEqual('1', counter.read_text().strip())

    def test_batch_restore_retries_the_lock_and_reclaims_leftovers(self):
        self.seed('iptables', 'mangle', (('-N', 'HETU_MOUT'), ('-A', 'HETU_MOUT', '-p', 'sctp', '-j', 'RETURN'),
                                         ('-I', 'OUTPUT', '1', '-j', 'HETU_MOUT')))
        counter = self.flaky('ip6tables-restore', '', 1)
        r = self.body(self.START + 'allocnet || exit 3\nxt_batch_begin || exit 4\n'
                      'XT_BATCH=1; install_capture_rules; XT_BATCH=0\nxt_batch_commit || exit 6\necho committed\n')
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn('committed', r.stdout)
        self.assertEqual('0', counter.read_text().strip())
        mangle = self.lines('iptables', 'mangle')
        self.assertEqual(1, len([l for l in mangle if l.startswith('-A OUTPUT') and l.endswith('-j HETU_MOUT')]), mangle)
        self.assertNotIn('-A HETU_MOUT -p sctp -j RETURN', mangle)
        self.assertTrue([l for l in self.lines('ip6tables', 'mangle') if l.startswith('-A OUTPUT') and 'HETU_MOUT' in l])


class RollbackGuards(Base):
    def test_guard_force_remove_takes_the_guards_out_even_through_a_lock_burst(self):
        self.seed('ip6tables', 'filter', FIELD_V6 + (('-N', 'HETU_V6FWD'), ('-A', 'HETU_V6FWD', '-j', 'REJECT'), ('-A', 'FORWARD', '-j', 'HETU_V6FWD')))
        self.seed('iptables', 'filter', (('-N', 'HETU_DOTOUT'), ('-A', 'HETU_DOTOUT', '-j', 'REJECT'), ('-A', 'OUTPUT', '-j', 'HETU_DOTOUT'),
                                         ('-N', 'HETU_KOUT'), ('-A', 'OUTPUT', '-j', 'HETU_KOUT')))
        self.flaky('ip6tables', '-D OUTPUT -j HETU_V6OUT', 1)
        r = self.body('guard_force_remove; echo rc=$?')
        self.assertIn('rc=0', r.stdout, r.stderr)
        self.assertEqual([], [l for l in self.lines('ip6tables', 'filter') if 'HETU_' in l])
        self.assertEqual(['-N HETU_KOUT', '-A OUTPUT -j HETU_KOUT'], [l for l in self.lines('iptables', 'filter') if 'HETU_' in l])

    def test_rollback_and_stop_paths(self):
        rb = function('rollback_start')
        self.assertIn('cleanup_confirmed || { RB_FAILED=1; guard_force_remove; }', rb)
        # A normal cleanup removes them too (the sweep, or per-chain unhook with lock retries).
        self.seed('ip6tables', 'filter', FIELD_V6)
        r = self.body('cleanup; echo rc=$?')
        self.assertIn('rc=0', r.stdout, r.stderr)
        self.assertEqual([], [l for l in self.lines('ip6tables', 'filter') if 'HETU_' in l])


# ---- Task 2: auto-resume ---------------------------------------------------------------------------------
RESUME_STUBS = r'''
fo_uptime(){ FOU=$CLOCK; }
sleep(){ CLOCK=$((CLOCK+${1%%.*})); export CLOCK; sleep_hook; }
sleep_hook(){ :; }
failopen_resume_default_net(){ [ "$CLOCK" -ge "$(cat "$RUN/net-up-at")" ]; }
failopen_resume_route(){ FRR_IF=wlan0; [ "$CLOCK" -ge "$(cat "$RUN/net-up-at")" ]; }
wifi_probe(){ WP_HOW="www.google.cn=204"; [ "$CLOCK" -ge "$(cat "$RUN/direct-at" 2>/dev/null || echo 0)" ]; }
wd_notify(){ echo "notify $1" >> "$RUN/notify"; }
force_stop_run(){ echo "force $1" >> "$RUN/force"; }
'''


class AutoResume(Base):
    def setUp(self):
        super().setUp()
        self.run = self.base / 'run'
        (self.run / 'state' / 'start.args').write_text('/x/core\n/x/cfg\ntproxy\n')
        self.failopen_record()
        (self.run / 'state' / 'failopen-resume').write_text('current-boot 1000\n')
        (self.run / 'net-up-at').write_text('1000')
        (self.run / 'start-reply').write_text('{"ok":true,"message":"started"}')

    def failopen_record(self):
        (self.run / 'failopen-state').write_text('AT=2026-10-24T17:31:00+0800\nBOOT=current-boot\nSOURCE=watchdog\nREASON=x\nRESULT=clean\n')

    def worker(self, extra='', clock=1000):
        script = self.dir / 'resume.sh'
        script.write_text('if [ "${1:-}" = start ]; then shift; printf "start clock=%%s resume=%%s args=%%s\\n" "$CLOCK" "${HETU_FAILOPEN_RESUME:-}" "$*" >> %s; '
                          '[ ! -f %s ] || rm -f %s; cat %s; exit 0; fi\n'
                          '. %s\n%s\n%s\nCLOCK=%d; export CLOCK\nfailopen_resume; echo done\n'
                          % (shlex.quote(str(self.run / 'starts')), shlex.quote(str(self.run / 'start-clears')),
                             shlex.quote(str(self.run / 'failopen-state')), shlex.quote(str(self.run / 'start-reply')),
                             shlex.quote(str(self.functions)), RESUME_STUBS, extra, clock))
        script.chmod(0o700)  # the worker re-runs "$0" start, as the watchdog's restart does on the device
        r = subprocess.run(['sh', str(script)], env=self.env, capture_output=True, text=True, timeout=60)
        self.assertIn('done', r.stdout, r.stderr)
        return r

    def starts(self):
        f = self.run / 'starts'
        return f.read_text().splitlines() if f.exists() else []

    def test_network_back_resumes_once_after_the_holdoff(self):
        self.worker()
        starts = self.starts()
        self.assertEqual(1, len(starts), starts)
        clock = int(starts[0].split('clock=')[1].split()[0])
        self.assertGreaterEqual(clock, 1120)
        self.assertLess(clock, 1140)
        self.assertIn('resume=1 args=/x/core /x/cfg tproxy', starts[0])
        self.assertEqual('current-boot 1', (self.run / 'state' / 'failopen-resumes').read_text().strip())
        self.assertFalse((self.run / 'state' / 'failopen-resume').exists())
        self.assertIn('notify failopen-resume network-back', (self.run / 'notify').read_text())
        self.assertFalse((self.run / 'failopen-resume.lock').exists())

    def test_network_must_stay_up_and_direct_must_work(self):
        (self.run / 'net-up-at').write_text('1300')
        (self.run / 'direct-at').write_text('1400')
        self.worker()
        starts = self.starts()
        self.assertEqual(1, len(starts), starts)
        self.assertGreaterEqual(int(starts[0].split('clock=')[1].split()[0]), 1400)

    def test_explicit_stop_disarms(self):
        self.worker('sleep_hook(){ [ "$CLOCK" -lt 1050 ] || failopen_resume_disarm; }')
        self.assertEqual([], self.starts())
        for name in ('stop_transaction_bounded', 'force_stop_run'):
            self.assertIn('failopen_resume_disarm', function(name), name)

    def test_manual_start_ends_the_worker(self):
        self.worker('sleep_hook(){ [ "$CLOCK" -lt 1050 ] || rm -f "$FAILOPEN_STATE"; }')
        self.assertEqual([], self.starts())

    def test_running_core_ends_the_worker(self):
        self.worker('pidcore(){ return 0; }')
        self.assertEqual([], self.starts())

    def test_resume_cap_per_boot(self):
        (self.run / 'state' / 'failopen-resumes').write_text('current-boot 2\n')
        self.worker()
        self.assertEqual([], self.starts())
        gate = self.dir / 'gate'
        gate.mkdir()
        arm = ('CG_ESCAPE_GATE=%s; failopen_resume_async(){ echo spawned >> "$RUN/spawn"; }\n' % shlex.quote(str(gate)))
        r = self.body(arm + 'rm -f "$FAILOPEN_RESUME"; failopen_resume_arm 2000; [ -f "$FAILOPEN_RESUME" ] && echo armed || echo refused')
        self.assertIn('refused', r.stdout, r.stderr)
        # A previous boot's count does not count; one resume used still leaves one.
        for count in ('older-boot 2', 'current-boot 1'):
            (self.run / 'state' / 'failopen-resumes').write_text(count + '\n')
            r = self.body(arm + 'rm -f "$FAILOPEN_RESUME"; failopen_resume_arm 2000; cat "$FAILOPEN_RESUME"')
            self.assertIn('current-boot 2000', r.stdout, (count, r.stderr))
        self.assertEqual(['spawned', 'spawned'], (self.run / 'spawn').read_text().splitlines())
        # Second resume reaches the cap: the third fail-open is not armed.
        (self.run / 'state' / 'failopen-resume').write_text('current-boot 1000\n')
        self.worker()
        self.assertEqual('current-boot 2', (self.run / 'state' / 'failopen-resumes').read_text().strip())
        r = self.body(arm + 'rm -f "$FAILOPEN_RESUME"; failopen_resume_arm 2000; [ -f "$FAILOPEN_RESUME" ] && echo armed || echo refused')
        self.assertIn('refused', r.stdout)

    def test_failed_resume_stops_trying_and_keeps_the_record(self):
        (self.run / 'start-reply').write_text('{"ok":false,"message":"boom"}')
        (self.run / 'start-clears').write_text('')
        (self.run / 'session.state').write_text('MODE=tproxy\n')
        self.worker()
        self.assertEqual(1, len(self.starts()))
        self.assertIn('BOOT=current-boot', (self.run / 'failopen-state').read_text())
        self.assertIn('notify failopen-resume start-failed', (self.run / 'notify').read_text())
        self.assertEqual(['force failopen-resume'], (self.run / 'force').read_text().splitlines())
        self.assertFalse((self.run / 'state' / 'failopen-resume').exists())

    def test_worker_window_is_bounded(self):
        (self.run / 'net-up-at').write_text('99999')
        self.worker('WD_RESUME_WINDOW=300')
        self.assertEqual([], self.starts())
        self.assertFalse((self.run / 'state' / 'failopen-resume').exists())

    def test_watchdog_fail_open_arms_the_resume(self):
        self.install()
        gate = self.dir / 'gate'
        gate.mkdir()
        (self.run / 'state' / 'failopen-resume').unlink()
        stubs = ('failopen_wifi(){ :; }\nnet_recover(){ :; }\nsleep(){ :; }\nwd_notify(){ :; }\ntun_rule_sweep(){ :; }\n'
                 'CG_ESCAPE_GATE=%s; failopen_resume_async(){ echo spawned >> "$RUN/spawn"; }\n' % shlex.quote(str(gate)))
        r = self.body(stubs + 'KILL=0; WD_NOW=4321; WD_FO_WHY="x"; wd_failopen "y"; echo unreachable')
        self.assertNotIn('unreachable', r.stdout, r.stderr)
        self.assertEqual('current-boot 4321', (self.run / 'state' / 'failopen-resume').read_text().strip())
        self.assertEqual(['spawned'], (self.run / 'spawn').read_text().splitlines())
        self.assertEqual([], self.owned())

    def test_manual_start_is_not_refused_by_the_record(self):
        start = function('start')
        refuse = start[start.index('FO_AT=$(failopen_current)') - 200:start.index('FO_AT=$(failopen_current)')]
        self.assertIn('HETU_AUTOMATIC_RECOVERY_ID', refuse)
        self.assertNotIn('HETU_FAILOPEN_RESUME:-}${', refuse)
        self.assertIn('failopen-resume) [ "$#" = 1 ] || exit 0; root; failopen_resume;;', TEXT)

    def test_network_checks(self):
        rules31 = '0:\tfrom all lookup local\n29040:\tfrom all fwmark 0x0/0xffff iif lo uidrange 10121-10121 lookup wlan0\n31000:\tfrom all fwmark 0x0/0xffff iif lo lookup wlan0\n32000:\tfrom all unreachable\n'
        uid_only = '0:\tfrom all lookup local\n29040:\tfrom all fwmark 0x0/0xffff iif lo uidrange 10121-10121 lookup wlan0\n32000:\tfrom all unreachable\n'
        cases = ((rules31, 0), (fo.HEALTHY_RULES, 0), (fo.FIELD_RULES, 1), (uid_only, 1))
        for rules, want in cases:
            (self.dir / 'rules').write_text(rules)
            r = self.body('ip(){ cat %s; }\nfailopen_resume_default_net; echo "rc=$?"' % shlex.quote(str(self.dir / 'rules')))
            self.assertIn('rc=%d' % want, r.stdout, rules)
        r = self.body('ip(){ case "$*" in *1.1.1.1*) echo "1.1.1.1 via 192.168.1.1 dev wlan0 table wlan0 src 192.168.1.141 uid 0";; *) return 2;; esac; }\n'
                      'failopen_resume_route; echo "rc=$? if=$FRR_IF"')
        self.assertIn('rc=0 if=wlan0', r.stdout, r.stderr)
        r = self.body('ip(){ echo "RTNETLINK answers: Network is unreachable" >&2; return 2; }\nfailopen_resume_route; echo "rc=$?"')
        self.assertIn('rc=1', r.stdout)


# ---- Task 3b: soft-pass -----------------------------------------------------------------------------------
class SoftPassRules(Base):
    START_DNS = NetModelCase.START.replace('START_SCOPE=core', 'START_SCOPE=blacklist')

    def test_lift_and_restore_only_the_dns_and_dot_hooks_at_their_positions(self):
        self.install()
        r = self.body('CORE_GID=3005; SYSTEM_DNS=captured\ninstall_dot_guard xt4 || exit 7\ninstall_dot_guard xt6 || exit 8')
        self.assertEqual(0, r.returncode, r.stderr)
        before = {k: self.lines(*k) for k in (('iptables', 'nat'), ('ip6tables', 'nat'), ('iptables', 'filter'), ('ip6tables', 'filter'), ('iptables', 'mangle'))}
        self.assertTrue(any(l == '-A OUTPUT -j HETU_DNSOUT' for l in before[('iptables', 'nat')]), before)
        r = self.body('COREPID=777; WD_NOW=1000; softpass_lift; echo "rc=$?"')
        self.assertIn('rc=0', r.stdout, r.stderr)
        for k in before:
            now = self.lines(*k)
            self.assertNotIn('-A OUTPUT -j HETU_DNSOUT', now, k)
            self.assertNotIn('-A OUTPUT -j HETU_DOTOUT', now, k)
            self.assertEqual([l for l in before[k] if l not in ('-A OUTPUT -j HETU_DNSOUT', '-A OUTPUT -j HETU_DOTOUT')], now, k)
        state = (self.base / 'run' / 'state' / 'softpass').read_text().splitlines()
        self.assertEqual('777 1000', state[0])
        self.assertEqual(4, len(state) - 1, state)
        # repair-network leaves the lifted hooks alone while soft-pass holds.
        r = self.body('fo_uptime(){ FOU=1010; }; softpass_active 777 && echo active')
        self.assertIn('active', r.stdout)
        repair = TEXT[TEXT.index('health_repair(){ ('):]
        self.assertLess(repair.index('! softpass_active "$H_PID" || exit 0'), repair.index('health_collect'))
        r = self.body('softpass_restore test; softpass_restore again; echo done')
        self.assertIn('done', r.stdout, r.stderr)
        for k in before:
            self.assertEqual(before[k], self.lines(*k), k)
        self.assertFalse((self.base / 'run' / 'state' / 'softpass').exists())


SP_DECISION = r'''
fo_uptime(){ FOU=$WD_NOW; }
wifi_heal_async(){ return 0; }
wd_failopen_due(){ WD_FO_WHY="Wi-Fi validated=0"; return 0; }
wd_failopen(){ echo "FAILOPEN now=$WD_NOW why=$WD_FO_WHY"; exit 0; }
softpass_lift(){ printf '%s %s\nip6tables nat HETU_DNSOUT 1\n' "$COREPID" "$WD_NOW" > "$SOFTPASS_STATE"; echo "LIFT $WD_NOW"; }
softpass_restore(){ echo "RESTORE $WD_NOW $1"; rm -f "$SOFTPASS_STATE"; }
WIFI_HEAL_OFFLINE_LIMIT=2; WIFI_HEAL_GAP=600; COREPID=777
WD_OFF_N=0; WD_OFF_NEXT=0; WD_OFF_SINCE=0; WD_OFF_LAST=0; WD_OFF_HEAL_AT=0; WD_SP_TRIED=x; WD_SP_CHECK_AT=0; WD_NET_CHANGED_AT=0
'''


class SoftPassDecision(Base):
    def seq(self, steps, extra='', kill=0):
        body = SP_DECISION + extra + 'KILL=%d\n' % kill
        for now in steps:
            body += 'WD_NOW=%d; wd_offline_seen "probe"; echo "t=%d"\n' % (now, now)
        return self.body(body)

    def test_soft_pass_before_fail_open_when_the_fail_open_is_due(self):
        r = self.seq([1000, 1035, 1070, 1105, 1120], 'failopen_resume_default_net(){ return 0; }\n')
        out = r.stdout
        self.assertNotIn('LIFT 1000', out)
        self.assertIn('LIFT 1070', out, r.stderr)
        self.assertIn('t=1105', out)
        self.assertIn('FAILOPEN now=1120 why=Wi-Fi validated=0; soft-pass 50s did not help', out)

    def test_no_default_network_starts_soft_pass_on_the_first_verdict(self):
        r = self.seq([1000, 1035, 1070], 'failopen_resume_default_net(){ return 1; }\n')
        self.assertIn('LIFT 1000', r.stdout, r.stderr)
        self.assertIn('FAILOPEN now=1070 why=Wi-Fi validated=0; soft-pass 70s did not help', r.stdout)
        self.assertIn('soft-pass: no default network (probe)', (self.base / 'run' / 'watchdog.log').read_text())
        self.assertEqual(1, r.stdout.count('LIFT'))

    def test_kill_switch_skips_soft_pass(self):
        r = self.seq([1000, 1035, 1070], 'failopen_resume_default_net(){ return 1; }\n', kill=1)
        self.assertNotIn('LIFT', r.stdout)
        self.assertIn('FAILOPEN now=1070', r.stdout)

    def test_nothing_to_lift_does_not_delay_the_fail_open(self):
        r = self.seq([1000, 1035, 1070], 'failopen_resume_default_net(){ return 0; }\nsoftpass_lift(){ return 1; }\n')
        self.assertIn('FAILOPEN now=1070 why=Wi-Fi validated=0', r.stdout, r.stderr)
        self.assertNotIn('soft-pass', r.stdout)

    def test_tick_restores_when_the_network_works_or_the_window_ends(self):
        base = SP_DECISION + 'softpass_lift; WD_OFF_SINCE=1000; WD_OFF_LAST=1000\n'
        # Default network and a validated Wi-Fi: restored at the next check.
        r = self.body(base.replace('softpass_lift;', 'WD_NOW=1000; softpass_lift;') +
                      'failopen_resume_default_net(){ return 0; }\nwifi_nai(){ cat >/dev/null; echo "wlan0 1"; }\n'
                      'WD_NOW=1004; wd_softpass_tick; echo ok')
        self.assertIn('RESTORE 1004 default network, Wi-Fi validated', r.stdout, r.stderr)
        # Not validated: kept until the cap while the outage lasts.
        r = self.body(base.replace('softpass_lift;', 'WD_NOW=1000; softpass_lift;') +
                      'failopen_resume_default_net(){ return 0; }\nwifi_nai(){ cat >/dev/null; echo "wlan0 0"; }\n'
                      'for t in 1010 1050 1080; do WD_NOW=$t; WD_OFF_LAST=$t; wd_softpass_tick; done; echo kept\n'
                      'WD_NOW=1090; wd_softpass_tick')
        self.assertIn('kept', r.stdout, r.stderr)
        self.assertEqual(['RESTORE 1090 after 90s'], [l for l in r.stdout.splitlines() if l.startswith('RESTORE')])
        # Window over and no outage left: restored at 45 s.
        r = self.body(base.replace('softpass_lift;', 'WD_NOW=1000; softpass_lift;') +
                      'failopen_resume_default_net(){ return 1; }\nWD_OFF_SINCE=0\n'
                      'WD_NOW=1040; wd_softpass_tick; WD_NOW=1046; wd_softpass_tick')
        self.assertEqual(['RESTORE 1046 window over, no outage'], [l for l in r.stdout.splitlines() if l.startswith('RESTORE')])

    def test_sources_wired(self):
        self.assertIn('softpass_on_offline "$1"', function('wd_offline_seen'))
        self.assertIn('softpass_holds "$1" && return 0; wd_failopen "$1"', function('wd_offline_seen'))
        self.assertIn('wd_softpass_tick', function('watchdog'))
        self.assertIn('rm -f "${SOFTPASS_STATE:-$RUN/state/softpass}"', function('wd_selfheal_init'))
        self.assertIn('"$SOFTPASS_STATE"', function('force_teardown'))


if __name__ == '__main__':
    unittest.main(verbosity=2)
