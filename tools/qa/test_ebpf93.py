#!/usr/bin/env python3
"""Opt-in eBPF rule matcher host regression (hetu-root.sh + pinned maps).

No real network, firewall, bpf(2) or Root operation. The shipped hetu-root.sh runs against the
stateful netfilter model (netfilter_model93.py) with the CNIP harness of test_cnip93.py; a
stateful stand-in for the hetu-bpf loader keeps its "pins" as files in a temporary directory
that replaces /sys/fs/bpf/hetu, and the iptables wrapper accepts `-m bpf --object-pinned` only
for a pinned program that exists (xt_bpf opens the path).

* Default OFF: without $BASE/policy/ebpf the rules are the established owner/CIDR rules, no pin
  is created and status reports ebpf=off.
* Enabled with every probe passing: one `-m bpf --object-pinned` rule replaces each per-UID and
  per-network loop, the maps hold the session lists, and every sampled packet gets the same
  verdict as on the owner/CIDR path (blacklist and whitelist scopes).
* Any probe failure (bpf syscall/SELinux/bpffs, missing payload, xt_bpf refused, a list the
  maps cannot hold) falls back to the unchanged owner/CIDR rules with ebpf=fallback reason=...
  and leaves no pin behind.
* ebpf-update rewrites the maps only: no iptables process runs, integrity stays healthy, and
  invalid lists keep the old maps. Without arguments it re-syncs the recorded lists.
* Stop, start rollback and the next start's cleanup remove every bpf rule and every pin; the
  Kill Switch guard never references a pin.
"""
from pathlib import Path
import ipaddress
import json
import os
import shlex
import signal
import subprocess
import sys
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import test_cnip93 as cn  # noqa: E402
from test_core_support93 import MODEL  # noqa: E402

ASSETS = cn.ASSETS
PROGS = ('hetu_uid_direct', 'hetu_uid_list', 'hetu_bypass4', 'hetu_bypass6', 'hetu_cn4', 'hetu_cn6')
MAPS = ('hetu_uid', 'hetu_cidr4', 'hetu_cidr6', 'hetu_cn4', 'hetu_cn6')

# Stand-in for native/ebpf/loader (same commands, reason tokens and list rules); pins are files.
FAKE_BPF = r'''
import ipaddress, json, os, sys
FIX = os.environ['HETU_BPF_FIXTURE']
a = sys.argv[1:]
with open(FIX + '.calls', 'a') as f: f.write(' '.join(a) + '\n')
def fail(reason): print(reason); sys.exit(1)
def fixture(name):
    p = FIX + '.' + name
    return open(p).read().strip() if os.path.exists(p) else None
def mp(d, n): return os.path.join(d, 'map_' + n)
def rd(d, n):
    if not os.path.exists(mp(d, n)): fail('pins-missing')
    return json.load(open(mp(d, n)))
def wr(d, n, v):
    if not os.path.exists(mp(d, n)): fail('pins-missing')
    json.dump(v, open(mp(d, n), 'w'))
def uids(s):
    out = []
    for item in [x for x in s.split(',') if x]:
        lo, _, hi = item.partition('-'); lo = int(lo); hi = int(hi or lo)
        if lo < 10000 or hi < lo or hi - lo >= 4096: fail('uid-list-invalid')
        out += list(range(lo, hi + 1))
    if len(out) > 4096: fail('uid-list-invalid')
    return out
cmd = a[0] if a else ''
if cmd == 'probe':
    r = fixture('probe-fail')
    if r: fail(r)
    os.makedirs(a[1], exist_ok=True); print('ok'); sys.exit(0)
if cmd == 'load':
    if not os.path.isfile(a[1]): fail('object-invalid')
    r = fixture('load-fail')
    if r: fail(r)
    os.makedirs(a[2], exist_ok=True)
    for n in ''' + repr(MAPS) + ''': json.dump({}, open(mp(a[2], n), 'w'))
    for n in ''' + repr(PROGS) + ''': open(os.path.join(a[2], 'prog_' + n), 'w').write(n)
    print('loaded maps=5 progs=6'); sys.exit(0)
if cmd == 'sync-uid':
    r = fixture('sync-fail')
    if r: fail(r)
    m = {}
    for u in uids(a[2]): m[str(u)] = m.get(str(u), 0) | 1
    for u in uids(a[3]): m[str(u)] = m.get(str(u), 0) | 2
    wr(a[1], 'hetu_uid', m); print('uid=%d' % len(m)); sys.exit(0)
if cmd == 'sync-cidr':
    v4, v6 = [], []
    for item in [x for x in a[2].split(',') if x]:
        try: n = ipaddress.ip_network(item, strict=False)
        except ValueError: fail('cidr-list-invalid')
        (v4 if n.version == 4 else v6).append(str(n))
    wr(a[1], 'hetu_cidr4', {k: 1 for k in v4}); wr(a[1], 'hetu_cidr6', {k: 1 for k in v6})
    print('cidr4=%d cidr6=%d' % (len(v4), len(v6))); sys.exit(0)
if cmd == 'count':
    print(' '.join('%s=%d' % (n[5:], len(rd(a[1], n))) for n in ''' + repr(MAPS) + ''')); sys.exit(0)
if cmd == 'unload':
    d = a[1]
    if not os.path.isdir(d): sys.exit(0)
    for e in os.listdir(d):
        if not e.startswith(('prog_', 'map_', 'probe_')): fail('unpin-failed')
        os.remove(os.path.join(d, e))
    os.rmdir(d); sys.exit(0)
fail('usage')
'''

# xt_bpf: refuse the match when the kernel lacks it, or when the pinned program does not exist.
# iptables-restore input is kept (the Kill Switch guard and the batch are committed through it).
FAKE_XT = r'''#!/bin/sh
FIX="$HETU_BPF_FIXTURE"
case "$0" in *restore) IN=$(cat); printf '%%s\n' "$IN" >> "$FIX.restore"; printf '%%s\n' "$IN" | grep -q -- '-m bpf' && [ -e "$FIX.nobpf" ] && { echo "Couldn't load match bpf" >&2; exit 1; }
  for P in $(printf '%%s\n' "$IN" | grep -o -- '--object-pinned [^ ]*' | cut -d' ' -f2); do [ -e "$P" ] || { echo "bpf: pinned object missing" >&2; exit 1; }; done
  if [ -e "$FIX.failtproxy" ] && printf '%%s\n' "$IN" | grep -q -- '--tproxy-mark'; then exit 1; fi
  printf '%%s\n' "$IN" | exec %(py)s %(model)s %(tool)s "$@";;
esac
P=""; N=""; for a in "$@"; do [ "$N" = 1 ] && P="$a"; N=0; [ "$a" = --object-pinned ] && N=1; [ "$a" = bpf ] && [ -e "$FIX.nobpf" ] && { echo "iptables: Couldn't load match \`bpf'" >&2; exit 1; }; done
[ -z "$P" ] || [ -e "$P" ] || { echo "bpf: pinned object missing" >&2; exit 1; }
if [ -e "$FIX.failtproxy" ]; then for a in "$@"; do [ "$a" = --tproxy-mark ] && exit 1; done; fi
exec %(py)s %(model)s %(tool)s "$@"
'''


class Evaluator(cn.Evaluator):
    """Adds `-m bpf --object-pinned <pin>/prog_*` answered from the stand-in's map files."""

    def __init__(self, rules, sets, pins):
        super().__init__(rules, sets); self.pins = pins

    def bpf(self, path, pkt):
        prog = Path(path).name[len('prog_'):]
        load = lambda n: json.loads((self.pins / ('map_' + n)).read_text())
        if prog in ('hetu_uid_direct', 'hetu_uid_list'):
            flags = load('hetu_uid').get(str(pkt.uid), 0)
            return bool(flags & (1 if prog == 'hetu_uid_direct' else 2))
        name = {'hetu_bypass4': 'hetu_cidr4', 'hetu_bypass6': 'hetu_cidr6'}[prog]
        return any(pkt.dst in ipaddress.ip_network(n) for n in load(name) if ipaddress.ip_network(n).version == pkt.dst.version)

    def match(self, words, pkt):
        if '--object-pinned' in words:
            i = words.index('--object-pinned')
            assert words[i - 2:i] == ['-m', 'bpf'] and (i < 3 or words[i - 3] != '!'), words
            if not self.bpf(words[i + 1], pkt): return False
            words = words[:i - 2] + words[i + 2:]
        return super().match(words, pkt)


class Ebpf93(cn.Cnip93):
    def setUp(self):
        super().setUp()
        self.pins = self.dir / 'bpffs/hetu'; self.pins.parent.mkdir()
        self.fix = self.dir / 'bpf-fixture'
        self.env['HETU_BPF_FIXTURE'] = str(self.fix)
        script = self.script.read_text().replace('/sys/fs/bpf/hetu', str(self.pins))
        self.script.write_text(script)
        for tool in ('iptables', 'ip6tables', 'iptables-restore', 'ip6tables-restore'):
            c = self.bin / tool
            c.write_text(FAKE_XT % {'py': shlex.quote(sys.executable), 'model': shlex.quote(str(MODEL)), 'tool': tool}); c.chmod(0o700)
        (self.dir / 'fakebpf.py').write_text(FAKE_BPF)
        loader = self.base / 'bin/hetu-bpf'
        loader.write_text('#!/bin/sh\nexec %s %s "$@"\n' % (shlex.quote(sys.executable), shlex.quote(str(self.dir / 'fakebpf.py')))); loader.chmod(0o700)
        (self.base / 'bin/hetu_match.o').write_bytes((ASSETS / 'hetu-bpf/hetu_match.o').read_bytes()
                                                      if (ASSETS / 'hetu-bpf/hetu_match.o').exists() else b'\x7fELF')

    # ------------------------------------------------------------------ helpers
    def enable(self, on=True):
        p = self.base / 'policy/ebpf'
        if on: p.write_text('ENABLED=1\n')
        elif p.exists(): p.unlink()

    def start_with(self, scope='blacklist', uids='10100,10200-10202', direct='10050', cidrs='203.0.113.0/24,2001:db8::/32', kill='0', expect_ok=True):
        args = ['start', str(self.base / 'bin/core'), str(self.base / 'run/state/startup-config'), 'tproxy', str(self.tp), '0', 'enable', '1', '1',
                'redirect', '1', str(self.dp), str(self.cp), scope, uids, '0', kill, cidrs, '', direct, '1', '1', '', '', '1', '1', '0', '', '', '', '0']
        r = subprocess.run(['sh', str(self.script)] + args, env=self.env, capture_output=True, text=True, timeout=90)
        last = r.stdout.strip().splitlines()[-1] if r.stdout.strip() else ''
        if expect_ok:
            self.assertEqual(0, r.returncode, r.stdout + r.stderr + self.read(self.base / 'run/last-start-error'))
            self.assertTrue(json.loads(last)['ok'], r.stdout)
        return r

    def bpf_rules(self):
        return [l for l in self.all_rules() if '-m bpf' in l]

    def owner_uid_rules(self, uid):
        return [l for l in self.all_rules() if '--uid-owner %s ' % uid in l + ' ']

    def maps(self):
        return {n: json.loads((self.pins / ('map_' + n)).read_text()) for n in MAPS}

    def verdict(self, fam, uid, dst, proto='tcp', table='mangle'):
        rules = self.model()['xt'][fam][table]['rules']
        return Evaluator(rules, self.sets(), self.pins).run('OUTPUT', cn.Packet(uid, dst, proto))

    SAMPLES = ((10050, '8.8.8.8'), (10100, '8.8.8.8'), (10201, '1.1.1.1'), (10300, '8.8.8.8'), (10300, '203.0.113.7'),
               (10100, '203.0.113.7'), (10050, '198.18.0.9'), (10300, '192.168.1.2'))

    def verdicts(self):
        out = {}
        for uid, dst in self.SAMPLES:
            for table in ('mangle', 'filter'):
                out[(uid, dst, table)] = self.verdict('4', uid, dst, table=table)
        for uid, dst in ((10100, '2001:4860::1'), (10300, '2001:4860::1'), (10300, '2001:db8::5')):
            out[(uid, dst, 'mangle6')] = self.verdict('6', uid, dst)
        return out

    def assert_no_ebpf(self):
        self.assertEqual([], self.bpf_rules())
        self.assertFalse(self.pins.exists(), 'no pin may stay behind')

    def fallback_case(self, reason):
        self.enable()
        self.start_with()
        st = self.status()
        self.assertEqual(('fallback', reason), (st['ebpf'], st['ebpfReason']))
        self.assert_no_ebpf()
        self.assertTrue(self.owner_uid_rules(10050) and self.owner_uid_rules(10100), 'owner path unchanged')
        self.assertIn('EBPF_MATCH=fallback\nEBPF_REASON=%s\n' % reason, self.read(self.base / 'run/session.state'))
        self.stop(); self.assert_clean(); self.assert_no_ebpf()

    # ------------------------------------------------------------------ tests
    def test_default_off_keeps_owner_rules_and_creates_no_pin(self):
        self.start_with()
        st = self.status()
        self.assertEqual(('off', ''), (st['ebpf'], st['ebpfReason']))
        self.assert_no_ebpf()
        self.assertTrue(self.owner_uid_rules(10050) and self.owner_uid_rules(10100) and self.owner_uid_rules('10200-10202'))
        self.assertTrue(any('-d 203.0.113.0/24 -j RETURN' in l for l in self.all_rules()))
        self.assertNotIn('probe', self.read(str(self.fix) + '.calls'), 'the loader never runs while off')
        self.assertIn('EBPF_MATCH=off\n', self.read(self.base / 'run/session.state'))
        self.stop(); self.assert_clean()

    def test_active_matcher_replaces_loops_with_identical_verdicts(self):
        self.start_with()
        baseline = self.verdicts(); baseline_count = len(self.all_rules())
        self.stop(); self.assert_clean()
        self.enable()
        self.start_with()
        st = self.status()
        self.assertEqual(('active', ''), (st['ebpf'], st['ebpfReason']))
        self.assertNotEqual('degraded', st['networkIntegrity'], st)  # host has no ss: listeners read as unknown
        self.assertEqual([], self.owner_uid_rules(10050) + self.owner_uid_rules(10100) + self.owner_uid_rules('10200-10202'))
        self.assertFalse(any('-d 203.0.113.0/24' in l or '-d 2001:db8::/32' in l for l in self.all_rules()))
        mout4 = self.chain('4', 'mangle', 'HETU_MOUT')
        self.assertIn('! -d 198.18.0.1/16 -m bpf --object-pinned %s/prog_hetu_uid_direct -j RETURN' % self.pins, mout4)
        self.assertIn('! -d 198.18.0.1/16 -m bpf --object-pinned %s/prog_hetu_uid_list -j RETURN' % self.pins, mout4)
        self.assertIn('-m bpf --object-pinned %s/prog_hetu_bypass4 -j RETURN' % self.pins, mout4)
        self.assertIn('-m bpf --object-pinned %s/prog_hetu_bypass6 -j RETURN' % self.pins, self.chain('6', 'mangle', 'HETU_MOUT'))
        self.assertLess(len(self.all_rules()), baseline_count)
        maps = self.maps()
        self.assertEqual({'10050': 1, '10100': 2, '10200': 2, '10201': 2, '10202': 2}, maps['hetu_uid'])
        self.assertEqual({'203.0.113.0/24': 1}, maps['hetu_cidr4']); self.assertEqual({'2001:db8::/32': 1}, maps['hetu_cidr6'])
        self.assertEqual(baseline, self.verdicts(), 'the matcher must not change any verdict')
        self.assertIsNone(self.verdict('4', 10100, '8.8.8.8')); self.assertEqual('proxy', self.verdict('4', 10300, '8.8.8.8'))
        self.assertEqual('proxy', self.verdict('4', 10050, '198.18.0.9'), 'fake-IP from a direct app still reaches the core')
        self.assertIn('EBPF_MATCH=active\nEBPF_REASON=\n', self.read(self.base / 'run/session.state'))
        self.stop(); self.assert_clean(); self.assert_no_ebpf()
        self.assertEqual(('off', ''), (self.status()['ebpf'], self.status()['ebpfReason']))

    def test_whitelist_scope_marks_only_listed_uids_through_the_map(self):
        self.start_with(scope='whitelist', uids='10100,10201', direct='')
        baseline = self.verdicts(); self.stop()
        self.enable()
        self.start_with(scope='whitelist', uids='10100,10201', direct='')
        self.assertEqual('active', self.status()['ebpf'])
        self.assertEqual([], self.owner_uid_rules(10100) + self.owner_uid_rules(10201))
        self.assertTrue(any('prog_hetu_uid_list -p tcp -j MARK' in s for s in self.all_rules()))
        self.assertEqual(baseline, self.verdicts())
        self.assertEqual('proxy', self.verdict('4', 10100, '8.8.8.8')); self.assertIsNone(self.verdict('4', 10300, '8.8.8.8'))
        self.stop(); self.assert_clean(); self.assert_no_ebpf()

    def test_ebpf_update_rewrites_maps_without_touching_iptables(self):
        self.enable(); self.start_with()
        before = self.state.read_text(); calls = self.read(str(self.state) + '.calls')
        r = self.run_cmd('ebpf-update', '', '10300', '198.51.100.0/24')
        out = json.loads(r.stdout.strip().splitlines()[-1])
        self.assertTrue(out['ok'] and out['updated'], r.stdout + r.stderr)
        self.assertIn('uid=1', out['ebpfEntries'])
        self.assertEqual(before, self.state.read_text(), 'no rule changed')
        self.assertEqual(calls, self.read(str(self.state) + '.calls'), 'no iptables process ran')
        self.assertEqual({'10300': 2}, self.maps()['hetu_uid'])
        self.assertIsNone(self.verdict('4', 10300, '8.8.8.8')); self.assertEqual('proxy', self.verdict('4', 10100, '8.8.8.8'))
        self.assertIsNone(self.verdict('4', 10123, '198.51.100.9')); self.assertEqual('proxy', self.verdict('4', 10123, '203.0.113.7'))
        st = self.status()
        self.assertNotEqual('degraded', st['networkIntegrity'], st)  # the integrity baseline is untouched
        self.assertNotIn('HETU_', st['networkFault'])
        # Invalid lists keep the old maps.
        r = self.run_cmd('ebpf-update', '9999', '', '')
        self.assertFalse(json.loads(r.stdout.strip().splitlines()[-1])['ok'])
        self.assertEqual({'10300': 2}, self.maps()['hetu_uid'])
        # A list the maps cannot hold is refused by the loader; the recorded lists stay.
        r = self.run_cmd('ebpf-update', '10000-19999', '', '')
        out = json.loads(r.stdout.strip().splitlines()[-1])
        self.assertEqual((False, 'uid-list-invalid'), (out['ok'], out['ebpfReason']))
        # No arguments: re-sync the recorded lists (e.g. after a map was emptied).
        (self.pins / 'map_hetu_uid').write_text('{}')
        r = self.run_cmd('ebpf-update')
        self.assertTrue(json.loads(r.stdout.strip().splitlines()[-1])['updated'], r.stdout)
        self.assertEqual({'10300': 2}, self.maps()['hetu_uid'])
        self.stop(); self.assert_clean(); self.assert_no_ebpf()
        r = self.run_cmd('ebpf-update', '', '', '')
        out = json.loads(r.stdout.strip().splitlines()[-1])
        self.assertEqual((True, False, 'off'), (out['ok'], out['updated'], out['ebpf']))

    def test_lost_pins_are_reported_and_never_repaired_blindly(self):
        self.enable(); self.start_with()
        moved = self.dir / 'pins-away'; self.pins.rename(moved)
        st = self.status()
        self.assertEqual(('active', 'pins-missing'), (st['ebpf'], st['ebpfReason']))
        self.assertIn('ebpf-pins', st['networkFault'])
        moved.rename(self.pins)
        self.stop(); self.assert_clean(); self.assert_no_ebpf()

    def test_update_is_refused_when_matcher_fell_back(self):
        Path(str(self.fix) + '.nobpf').write_text('')
        self.enable(); self.start_with()
        before = self.state.read_text()
        out = json.loads(self.run_cmd('ebpf-update', '', '10300', '').stdout.strip().splitlines()[-1])
        self.assertEqual((True, False, 'fallback'), (out['ok'], out['updated'], out['ebpf']))
        self.assertEqual(before, self.state.read_text())
        self.assert_no_ebpf()

    def test_probe_failure_selinux_falls_back(self):
        Path(str(self.fix) + '.probe-fail').write_text('selinux-denied\n')
        self.fallback_case('selinux-denied')

    def test_probe_failure_bpffs_falls_back(self):
        Path(str(self.fix) + '.probe-fail').write_text('bpffs-missing\n')
        self.fallback_case('bpffs-missing')

    def test_program_load_failure_falls_back(self):
        Path(str(self.fix) + '.load-fail').write_text('prog-load-permission\n')
        self.fallback_case('prog-load-permission')

    def test_kernel_without_xt_bpf_falls_back_and_unpins(self):
        Path(str(self.fix) + '.nobpf').write_text('')
        self.fallback_case('xt-bpf-unsupported')
        self.assertIn('unload', self.read(str(self.fix) + '.calls'))

    def test_missing_payload_falls_back(self):
        (self.base / 'bin/hetu-bpf').unlink()
        self.fallback_case('payload-missing')

    def test_list_the_map_cannot_hold_falls_back(self):
        self.enable()
        self.start_with(direct='10000-19999')
        st = self.status()
        self.assertEqual(('fallback', 'uid-list-invalid'), (st['ebpf'], st['ebpfReason']))
        self.assert_no_ebpf()
        self.assertTrue(self.owner_uid_rules('10000-19999'))
        self.stop(); self.assert_clean()

    def test_failed_start_rolls_back_rules_and_pins(self):
        self.enable()
        Path(str(self.fix) + '.failtproxy').write_text('')
        r = self.start_with(expect_ok=False)
        self.assertNotEqual(0, r.returncode, r.stdout)
        self.assertIn('load', self.read(str(self.fix) + '.calls'), 'the matcher was loaded before the failure')
        self.assert_clean(); self.assert_no_ebpf()

    def test_next_start_cleans_pins_left_by_a_lost_session(self):
        self.enable(); self.start_with()
        # A lost session: core and watchdog gone, journal deleted, rules and pins still present.
        try: os.kill(int(self.read(self.base / 'run/watchdog.pid').strip()), signal.SIGKILL)
        except (ValueError, OSError): pass
        for proc in Path('/proc').iterdir():
            if proc.name.isdigit():
                try:
                    cmd = (proc / 'cmdline').read_bytes().decode(errors='ignore')
                    if str(self.dir) in cmd and ('fakecore.py' in cmd or 'bin/core' in cmd): os.kill(int(proc.name), signal.SIGKILL)
                except (OSError, ValueError): pass
        (self.base / 'run/net.state').unlink(missing_ok=True)
        time.sleep(0.5)
        self.assertTrue(self.pins.exists() and self.bpf_rules())
        self.start_with()
        self.assertEqual('active', self.status()['ebpf'])
        self.assertEqual(1, sum(1 for s in self.chain('4', 'mangle', 'HETU_MOUT') if 'prog_hetu_uid_direct' in s), 'no duplicated rule')
        self.stop(); self.assert_clean(); self.assert_no_ebpf()

    def test_kill_switch_guard_never_references_pins(self):
        self.enable()
        self.start_with(kill='1')
        self.assertEqual('active', self.status()['ebpf'])
        os.kill(int(self.read(self.base / 'run/core.pid').strip()), signal.SIGKILL)
        for _ in range(60):
            if self.chain('4', 'filter', 'HETU_KOUT') and self.chain('6', 'filter', 'HETU_KOUT'): break
            time.sleep(0.25)
        for fam in ('4', '6'):
            kout = self.chain(fam, 'filter', 'HETU_KOUT'); self.assertTrue(kout, fam)
            self.assertFalse(any('-m bpf' in s for s in kout), kout)
            self.assertTrue(any('--uid-owner 10050' in s for s in kout), 'the guard keeps plain owner rules')
        guards = [l for l in self.read(str(self.fix) + '.restore').splitlines() if 'HETU_KOUT' in l or 'HETU_KFWD' in l]
        self.assertTrue(guards); self.assertFalse(any('-m bpf' in l for l in guards))
        self.stop(); self.assert_clean(); self.assert_no_ebpf()

    def test_source_contract(self):
        script = (ASSETS / 'hetu-root.sh').read_text()
        cleanup = script[script.index('cleanup(){'):script.index('\n', script.index('cleanup(){'))]
        self.assertLess(cleanup.index('sweep_rules'), cleanup.index('ebpf_unload'), 'pins go after the rules')
        unload = script[script.index('ebpf_unload(){'):script.index('\n}\n', script.index('ebpf_unload(){'))]
        self.assertIn('ebpf_rules_reference_pins', unload)
        guard = script[script.index('atomic_kill_guard(){'):script.index('install_kill4(){')]
        self.assertIn('EBPF_RULES=0', guard)
        start = script[script.index('\nstart(){'):script.index('\nwatchdog_alive(){')]
        self.assertLess(start.index('stopwatchdog; cleanup;'), start.index('ebpf_prepare'))
        self.assertLess(start.index('ebpf_prepare'), start.index('XT_BATCH=1; install_capture_rules'))
        self.assertIn('ebpf-update)', script[script.rindex('case "${1:-status}" in'):])
        self.assertIn("grep -qF -- \"--object-pinned $EBPF_PIN/\"", script[script.index('cleanup_verify(){'):])


# The CNIP scenarios already run in test_cnip93.py; only this module's tests run here.
for _name in [n for n in dir(cn.Cnip93) if n.startswith('test_')]:
    if _name not in Ebpf93.__dict__: setattr(Ebpf93, _name, None)


if __name__ == '__main__':
    unittest.main(verbosity=2)
