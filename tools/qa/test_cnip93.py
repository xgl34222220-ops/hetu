#!/usr/bin/env python3
"""CNIP kernel-bypass host regression (ipset hetu_cn4/hetu_cn6).

No real network, firewall, ipset or Root operation. The shipped hetu-root.sh runs against the
stateful netfilter model (netfilter_model93.py) and a stateful ipset model defined here; the
shipped CN snapshots (assets/cnip) are the address lists.

* With ipset: both sets are restored (create *_new -> rename/swap), every capture/guard chain
  RETURNs CN destinations, the DNS hijack chains never reference a set, and status says ipset.
* Without ipset (or a kernel without the set match): the start still succeeds, nothing references
  a set and status reports cnip=degraded (the core RULE-SET keeps doing the job).
* Stop leaves no hetu_ set, no HETU_ chain (heads or *_T tails) and no rule referencing a set;
  sets are destroyed only after the sweep was verified.
* Restart with unchanged data reuses the sets (no restore); changed data is swapped in atomically,
  and cnip-reload hot-swaps new data while invalid data keeps the old set.
* An exempt (FORCE) UID jumps to the tail chain and still reaches the proxy for CN addresses;
  other apps RETURN for CN and are proxied for everything else.
* With Kill Switch the CN RETURN sits before the reject, and stop leaves nothing behind.
"""
from pathlib import Path
import ipaddress
import json
import os
import shlex
import signal
import subprocess
import sys
import tempfile
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_core_support93 import FAKE_CORE_PY, METADATA, MODEL, free_port  # noqa: E402
from test_fast_start93 import FAKE_BUSYBOX  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
SRC = REPO / 'android-app/app/src/main'
ASSETS = SRC / 'assets'

# Stateful ipset model: just enough of list/create/destroy/restore/swap/rename for hetu-root.sh.
FAKE_IPSET = r'''
import json, os, sys
P = os.environ['HETU_IPSET_STATE']
def load():
    try: return json.load(open(P))
    except (OSError, ValueError): return {}
def save(s): json.dump(s, open(P, 'w'))
def log(a):
    with open(P + '.calls', 'a') as f: f.write(' '.join(a) + '\n')
a = sys.argv[1:]; log(a); s = load()
if os.path.exists(P + '.absent'): sys.exit(127)
def die(m): sys.stderr.write('ipset v7.0: ' + m + '\n'); sys.exit(1)
cmd = a[0] if a else ''
if cmd == 'list':
    if a[1:2] == ['-n']:
        if len(a) > 2:
            if a[2] not in s: die('The set with the given name does not exist')
            print(a[2])
        else:
            for n in sorted(s): print(n)
        sys.exit(0)
    n = a[1]
    if n not in s: die('The set with the given name does not exist')
    print('Name: %s\nType: hash:net\nNumber of entries: %d' % (n, len(s[n]['m'])))
    sys.exit(0)
if cmd == 'create':
    if os.path.exists(P + '.nocreate'): die('Kernel error received: set type not supported')
    n = a[1]
    if n in s and '-exist' not in a: die('Set cannot be created: set with the same name already exists')
    s.setdefault(n, {'family': a[a.index('family') + 1] if 'family' in a else 'inet', 'm': []}); save(s); sys.exit(0)
if cmd == 'destroy':
    if len(a) == 1: s = {}
    else:
        if a[1] not in s: die('The set with the given name does not exist')
        del s[a[1]]
    save(s); sys.exit(0)
if cmd == 'restore':
    if os.path.exists(P + '.norestore'): die('restore refused by fixture')
    work = json.loads(json.dumps(s))
    for line in sys.stdin.read().splitlines():
        w = line.split()
        if not w: continue
        if w[0] == 'create':
            if w[1] in work and '-exist' not in a: die('set exists')
            work.setdefault(w[1], {'family': w[w.index('family') + 1], 'm': []})
        elif w[0] == 'add':
            if w[1] not in work: die('no set ' + w[1])
            work[w[1]]['m'].append(w[2])
        else: die('unsupported restore line ' + line)
    save(work); sys.exit(0)
if cmd == 'swap':
    if a[1] not in s or a[2] not in s: die('The set with the given name does not exist')
    s[a[1]], s[a[2]] = s[a[2]], s[a[1]]; save(s); sys.exit(0)
if cmd == 'rename':
    if a[1] not in s: die('The set with the given name does not exist')
    if a[2] in s: die('a set with the new name already exists')
    s[a[2]] = s.pop(a[1]); save(s); sys.exit(0)
die('unsupported ' + ' '.join(a))
'''

# Every set-match rule the model accepts must also be one the kernel could load.
FAKE_SET_MATCH_GUARD = r'''#!/bin/sh
# Refuse "-m set" rules when the fixture says the kernel lacks xt_set.
for a in "$@"; do if [ "$a" = set ] && [ -e "$HETU_IPSET_STATE.nomatch" ]; then echo "iptables: Couldn't load match \`set'" >&2; exit 1; fi; done
exec %(py)s %(model)s %(tool)s "$@"
'''


class Packet:
    def __init__(self, uid, dst, proto='tcp', dport=443, mark=0):
        self.uid, self.dst, self.proto, self.dport, self.mark = uid, ipaddress.ip_address(dst), proto, dport, mark


class Evaluator:
    """Walks the modelled chains for one locally generated packet (OUTPUT hook)."""

    def __init__(self, rules, sets):
        self.rules, self.sets = rules, sets

    def in_set(self, name, addr):
        return any(addr in ipaddress.ip_network(n, strict=False) for n in self.sets.get(name, {}).get('m', [])
                   if ipaddress.ip_network(n, strict=False).version == addr.version)

    def match(self, words, pkt):
        i, neg, module = 0, False, ''
        while i < len(words):
            w = words[i]
            if w == '!': neg = True; i += 1; continue
            if w in ('-j', '-g'): return True
            if w == '-m': module = words[i + 1]; i += 2; continue
            if w in ('--ctstate', '--state'): ok = 'NEW' in words[i + 1].split(','); i += 2
            elif w == '--mark' and module == 'connmark':
                m, _, mask = words[i + 1].partition('/'); ok = (0 & int(mask or '0xffffffff', 0)) == (int(m, 0) & int(mask or '0xffffffff', 0)); i += 2
            elif w == '-p': ok = pkt.proto == words[i + 1]; i += 2
            elif w == '--uid-owner':
                v = words[i + 1]; lo, _, hi = v.partition('-'); ok = int(lo) <= pkt.uid <= int(hi or lo); i += 2
            elif w == '--gid-owner': ok = False; i += 2
            elif w == '--match-set': ok = self.in_set(words[i + 1], pkt.dst); i += 3
            elif w == '-d':
                net = ipaddress.ip_network(words[i + 1], strict=False)
                ok = net.version == pkt.dst.version and pkt.dst in net; i += 2
            elif w == '--dport': ok = pkt.dport == int(words[i + 1]); i += 2
            elif w == '--mark':
                m, _, mask = words[i + 1].partition('/'); mask = int(mask or '0xffffffff', 0)
                ok = (pkt.mark & mask) == (int(m, 0) & mask); i += 2
            elif w == '-o': ok = words[i + 1] == 'lo' and False; i += 2
            elif w == '--dst-type': ok = False; i += 2
            else: raise AssertionError('evaluator: unsupported match %r in %r' % (w, ' '.join(words)))
            if neg: ok, neg = not ok, False
            if not ok: return False
        return True

    def target(self, words):
        for j, w in enumerate(words):
            if w in ('-j', '-g'): return w, words[j + 1], words[j + 2:]
        return None, None, []

    def run(self, chain, pkt, depth=0):
        assert depth < 16, 'chain loop'
        for spec in self.rules.get(chain, []):
            words = shlex.split(spec)
            if not self.match(words, pkt): continue
            kind, tgt, extra = self.target(words)
            if tgt is None: continue
            if tgt == 'RETURN': return None
            if tgt == 'MARK':
                return 'proxy'
            if tgt in ('TPROXY', 'REDIRECT'): return 'proxy'
            if tgt in ('REJECT', 'DROP'): return 'reject'
            if tgt in ('ACCEPT', 'CONNMARK', 'CT'): continue
            if tgt in self.rules:
                verdict = self.run(tgt, pkt, depth + 1)
                if kind == '-g': return verdict
                if verdict is not None: return verdict
                continue
            raise AssertionError('evaluator: unknown target %s' % tgt)
        return None


class Cnip93(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='hetu-cnip-')
        self.dir = Path(self.temp.name); self.base = self.dir / 'hetu'
        for d in ('run/state', 'run/ruleset', 'boot', 'scripts', 'policy', 'bin'): (self.base / d).mkdir(parents=True, exist_ok=True)
        self.bin = self.dir / 'commands'; self.bin.mkdir()
        self.state = self.dir / 'net.json'; self.ipstate = self.dir / 'ipset.json'
        (self.dir / 'boot-id').write_text('boot-one\n')
        for tool in ('iptables', 'ip6tables', 'iptables-restore', 'ip6tables-restore', 'iptables-save', 'ip6tables-save', 'ip'):
            c = self.bin / tool
            c.write_text(FAKE_SET_MATCH_GUARD % {'py': shlex.quote(sys.executable), 'model': shlex.quote(str(MODEL)), 'tool': tool}); c.chmod(0o700)
        (self.dir / 'ipset.py').write_text(FAKE_IPSET)
        self.ipset = self.bin / 'ipset'
        self.ipset.write_text('#!/bin/sh\nexec %s %s "$@"\n' % (shlex.quote(sys.executable), shlex.quote(str(self.dir / 'ipset.py')))); self.ipset.chmod(0o700)
        for tool in ('chown', 'settings', 'pm', 'cmd'):
            c = self.bin / tool; c.write_text('#!/bin/sh\n' + ('echo off\n' if tool == 'settings' else '') + 'exit 0\n'); c.chmod(0o700)
        self.users = self.dir / 'data-user'; (self.users / '0').mkdir(parents=True)
        self.busybox = self.dir / 'ksu/bin/busybox'; self.busybox.parent.mkdir(parents=True)
        self.busybox.write_text(FAKE_BUSYBOX); self.busybox.chmod(0o700)
        (self.dir / 'fakecore.py').write_text(FAKE_CORE_PY)
        self.tp, self.dp, self.cp = free_port(), free_port(), free_port()
        self.env = dict(os.environ, PATH=str(self.bin) + ':' + os.environ['PATH'], HETU_NETMODEL=str(self.state),
                        HETU_IPSET_STATE=str(self.ipstate), HETU_FAKE_LOG=str(self.dir / 'core.jsonl'),
                        HETU_PRIVATE_DNS_MODE='off', HETU_BB_LOG=str(self.dir / 'bb.log'),
                        HETU_FAKE_LISTEN='tcp:%d,udp:%d,tcp:%d,udp:%d,tcp:%d' % (self.tp, self.tp, self.dp, self.dp, self.cp))
        source = (ASSETS / 'hetu-root.sh').read_text().replace('/system/bin/sh', '/bin/sh').replace('/data/adb/hetu', str(self.base)) \
            .replace('/proc/sys/kernel/random/boot_id', str(self.dir / 'boot-id')).replace('/data/adb/ksu/bin/busybox', str(self.busybox)) \
            .replace('ls /data/user ', 'ls ' + shlex.quote(str(self.users)) + ' ')
        at = source.rindex('case "${1:-status}" in')
        stubs = ('root(){ :; }\nv6supported(){ return 0; }\n'
                 'pidcore(){ case "$1" in \'\'|*[!0-9]*) return 1;; esac; [ -r "/proc/$1/cmdline" ] || return 1; '
                 'tr \'\\000\' \'\\n\' < "/proc/$1/cmdline" 2>/dev/null | sed -n 2p | grep -qx "$BASE/bin/core"; }\n'
                 'core_candidate(){ pidcore "$1"; }\n'
                 'core_identity_confirm(){ CORE_GID=""; SYSTEM_DNS=exempt; [ -n "$CORE_RUNNER" ] || return 0; pidcore "$1" || return 1; '
                 'CORE_GID="$CORE_GROUP_ID"; [ "$START_DNS" != off ] || return 0; case "$START_MODE" in tun|ebpf) ;; *) SYSTEM_DNS=captured;; esac; }\n')
        self.script = self.base / 'hetu-root.sh'; self.script.write_text(source[:at] + stubs + source[at:]); self.script.chmod(0o700)
        core = self.base / 'bin/core'
        core.write_text('#!/bin/sh\n' + shlex.quote(sys.executable) + ' ' + shlex.quote(str(self.dir / 'fakecore.py')) + ' "$@" &\n'
                        'C=$!\ntrap \'kill "$C" 2>/dev/null; wait "$C"; exit 0\' TERM INT\nwait "$C"\n')
        core.chmod(0o700)
        (self.base / 'run/state/startup-config').write_text('mode: rule\n' + METADATA)
        for fam in ('4', '6'):
            (self.base / ('run/ruleset/hetu-cn-v%s.txt' % fam)).write_bytes((ASSETS / ('cnip/hetu-cn-v%s.txt' % fam)).read_bytes())

    def tearDown(self):
        subprocess.run(['sh', str(self.script), 'stop'], env=self.env, capture_output=True, text=True, timeout=30)
        for proc in Path('/proc').iterdir():
            if proc.name.isdigit():
                try:
                    if str(self.dir) in (proc / 'cmdline').read_bytes().decode(errors='ignore'): os.kill(int(proc.name), signal.SIGKILL)
                except (OSError, ValueError): pass
        self.temp.cleanup()

    # ------------------------------------------------------------------ helpers
    def policy(self, v4=1, v6=1, force=''):
        (self.base / 'policy/cnip').write_text('V4=%d\nV6=%d\nFORCE=%s\n' % (v4, v6, force))

    def start(self, kill='0', expect_ok=True):
        args = ['start', str(self.base / 'bin/core'), str(self.base / 'run/state/startup-config'), 'tproxy', str(self.tp), '0', 'enable', '1', '1',
                'redirect', '1', str(self.dp), str(self.cp), 'all', '', '0', kill, '', '', '', '1', '1', '', '', '1', '1', '0', '', '', '', '0']
        r = subprocess.run(['sh', str(self.script)] + args, env=self.env, capture_output=True, text=True, timeout=90)
        last = r.stdout.strip().splitlines()[-1] if r.stdout.strip() else ''
        if expect_ok:
            self.assertEqual(0, r.returncode, r.stdout + r.stderr + self.read(self.base / 'run/start.error'))
            self.assertTrue(json.loads(last)['ok'], r.stdout)
        return r

    def stop(self):
        r = subprocess.run(['sh', str(self.script), 'stop'], env=self.env, capture_output=True, text=True, timeout=60)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        return r

    def run_cmd(self, *args):
        return subprocess.run(['sh', str(self.script)] + list(args), env=self.env, capture_output=True, text=True, timeout=60)

    def status(self):
        r = self.run_cmd('status')
        return json.loads(r.stdout.strip().splitlines()[-1])

    @staticmethod
    def read(p):
        try: return Path(p).read_text()
        except OSError: return ''

    def model(self):
        return json.loads(self.state.read_text())

    def sets(self):
        try: return json.loads(self.ipstate.read_text())
        except (OSError, ValueError): return {}

    def ipset_calls(self):
        return self.read(str(self.ipstate) + '.calls').splitlines()

    def all_rules(self):
        out = []
        for fam, tables in self.model()['xt'].items():
            for table, t in tables.items():
                for chain, specs in t['rules'].items():
                    out += ['%s %s %s %s' % (fam, table, chain, s) for s in specs]
                    if chain.startswith(('HETU_', 'BICHEN_')): out.append('%s %s -N %s' % (fam, table, chain))
        return out

    def chain(self, fam, table, name):
        return self.model()['xt'][fam][table]['rules'].get(name)

    def verdict(self, fam, uid, dst, proto='tcp', table='mangle'):
        rules = self.model()['xt'][fam][table]['rules']
        return Evaluator(rules, self.sets()).run('OUTPUT', Packet(uid, dst, proto))

    def assert_clean(self):
        leftovers = [l for l in self.all_rules() if 'HETU_' in l or 'BICHEN_' in l or '--match-set' in l]
        self.assertEqual([], leftovers)
        self.assertEqual({}, {k: v for k, v in self.sets().items() if k.startswith('hetu_')})

    # ------------------------------------------------------------------ tests
    def test_start_with_ipset_installs_cn_returns_and_stop_leaves_nothing(self):
        self.policy()
        self.start()
        sets = self.sets()
        self.assertEqual({'hetu_cn4', 'hetu_cn6'}, set(sets))
        self.assertGreater(len(sets['hetu_cn4']['m']), 1000); self.assertGreater(len(sets['hetu_cn6']['m']), 500)
        self.assertEqual('inet6', sets['hetu_cn6']['family'])
        session = self.read(self.base / 'run/session.state')
        self.assertIn('CNIP=ipset\n', session); self.assertIn('CNIP_V4=1\n', session); self.assertIn('CNIP_V6=1\n', session)
        for fam, cn in (('4', 'hetu_cn4'), ('6', 'hetu_cn6')):
            mout = self.chain(fam, 'mangle', 'HETU_MOUT')
            self.assertIn('-m set --match-set %s dst -j RETURN' % cn, mout)
            mpre = self.chain(fam, 'mangle', 'HETU_MPRE')
            self.assertTrue(any('--match-set %s dst -j RETURN' % cn in s and '! --mark' in s for s in mpre), mpre)
            for name in ('HETU_WROUT', 'HETU_QUICOUT'):
                rules = self.chain(fam, 'filter', name)
                if rules is not None: self.assertTrue(any('--match-set ' + cn in s for s in rules), (name, rules))
        self.assertTrue(any('--match-set hetu_cn6' in s for s in self.chain('6', 'filter', 'HETU_V6OUT') or ['--match-set hetu_cn6']))
        # DNS hijack is untouched: CN destinations still have their DNS captured.
        for fam in ('4', '6'):
            for table in ('mangle', 'nat', 'filter'):
                for name in ('HETU_DNSOUT', 'HETU_DNSPRE', 'HETU_DOTOUT'):
                    self.assertFalse(any('--match-set' in s for s in (self.chain(fam, table, name) or [])), (fam, table, name))
        st = self.status()
        self.assertEqual('ipset', st['cnip']); self.assertGreater(st['cnipV4Entries'], 1000); self.assertGreater(st['cnipV6Entries'], 500)
        # A CN destination returns to the kernel; anything else is still proxied.
        self.assertIsNone(self.verdict('4', 10123, '114.114.114.114'))
        self.assertEqual('proxy', self.verdict('4', 10123, '8.8.8.8'))
        self.stop()
        self.assert_clean()
        self.assertNotIn('ipset', self.status().get('cnip', 'off'))

    def test_without_ipset_start_degrades_and_references_no_set(self):
        self.policy()
        # A binary that cannot run (no ipset in this ROM); the fixture shadows any host ipset.
        Path(str(self.ipstate) + '.absent').write_text('')
        self.start()
        self.assertIn('CNIP=degraded\n', self.read(self.base / 'run/session.state'))
        self.assertIn('CNIP_REASON=ipset-unavailable\n', self.read(self.base / 'run/session.state'))
        self.assertFalse([l for l in self.all_rules() if '--match-set' in l])
        self.assertTrue(any('HETU_MOUT' in l for l in self.all_rules()), 'the capture itself is installed')
        st = self.status(); self.assertEqual('degraded', st['cnip']); self.assertEqual(0, st['cnipV4Entries'])
        self.assertEqual('proxy', self.verdict('4', 10123, '114.114.114.114'), 'core RULE-SET decides instead')
        r = self.run_cmd('cnip-reload')
        self.assertFalse(json.loads(r.stdout.strip().splitlines()[-1])['reloaded'])
        self.stop()
        self.assert_clean()

    def test_kernel_without_set_match_degrades(self):
        self.policy()
        Path(str(self.ipstate) + '.nomatch').write_text('')
        self.start()
        self.assertIn('CNIP=degraded\n', self.read(self.base / 'run/session.state'))
        self.assertFalse([l for l in self.all_rules() if '--match-set' in l])
        self.assertFalse(any(k.startswith('hetu_') for k in self.sets()), 'the probe set is gone')
        self.stop(); self.assert_clean()

    def test_invalid_data_degrades_that_family_only(self):
        self.policy()
        (self.base / 'run/ruleset/hetu-cn-v6.txt').write_text('2400:da00::/32\n')  # far below the 500-entry floor
        self.start()
        session = self.read(self.base / 'run/session.state')
        self.assertIn('CNIP=ipset\n', session); self.assertIn('CNIP_V6=0\n', session); self.assertIn('v6-data-invalid', session)
        self.assertNotIn('hetu_cn6', self.sets())
        self.assertFalse([l for l in self.all_rules() if l.startswith('6 ') and '--match-set' in l])
        self.assertTrue([l for l in self.all_rules() if l.startswith('4 ') and '--match-set hetu_cn4' in l])
        self.stop(); self.assert_clean()

    def test_policy_off_installs_nothing(self):
        self.start()
        self.assertIn('CNIP=off\n', self.read(self.base / 'run/session.state'))
        self.assertEqual({}, self.sets())
        self.assertFalse([l for l in self.all_rules() if '--match-set' in l or '_T ' in l])
        self.stop(); self.assert_clean()

    def test_restart_reuses_unchanged_sets_and_swaps_changed_data(self):
        self.policy()
        self.start()
        first = len([c for c in self.ipset_calls() if c.startswith('restore')])
        self.assertEqual(2, first)
        # restart = stop + start; stop destroys, so measure an in-place start cleanup too
        r = self.run_cmd('stop'); self.assertEqual(0, r.returncode)
        self.assert_clean()
        self.start()
        self.assertEqual(4, len([c for c in self.ipset_calls() if c.startswith('restore')]), 'after stop the sets are rebuilt')
        # A second start without stop (restart path: start-time cleanup keeps the sets).
        before = len([c for c in self.ipset_calls() if c.startswith('restore')])
        self.start()
        self.assertEqual(before, len([c for c in self.ipset_calls() if c.startswith('restore')]), 'unchanged data: sets reused')
        self.assertEqual({'hetu_cn4', 'hetu_cn6'}, set(self.sets()))
        self.assertEqual('ipset', self.status()['cnip'])
        # Changed data on the next start: restored into *_new and swapped, rules keep the name.
        f4 = self.base / 'run/ruleset/hetu-cn-v4.txt'; f4.write_text(f4.read_text() + '1.2.3.0/24\n')
        self.start()
        calls = self.ipset_calls()
        self.assertTrue(any(c.startswith('swap hetu_cn4_new hetu_cn4') for c in calls), calls[-12:])
        self.assertIn('1.2.3.0/24', self.sets()['hetu_cn4']['m'])
        self.assertNotIn('hetu_cn4_new', self.sets())
        self.stop(); self.assert_clean()

    def test_cnip_reload_hot_swaps_and_keeps_old_set_on_bad_data(self):
        self.policy()
        self.start()
        r = self.run_cmd('cnip-reload', 'auto')
        out = json.loads(r.stdout.strip().splitlines()[-1]); self.assertTrue(out['ok']); self.assertFalse(out['reloaded'])
        f4 = self.base / 'run/ruleset/hetu-cn-v4.txt'; good = f4.read_text()
        f4.write_text(good + '5.6.7.0/24\n')
        r = self.run_cmd('cnip-reload')
        out = json.loads(r.stdout.strip().splitlines()[-1]); self.assertTrue(out['reloaded'], r.stdout + r.stderr)
        self.assertIn('5.6.7.0/24', self.sets()['hetu_cn4']['m'])
        size = len(self.sets()['hetu_cn4']['m'])
        f4.write_text('garbage\n')
        r = self.run_cmd('cnip-reload')
        out = json.loads(r.stdout.strip().splitlines()[-1]); self.assertFalse(out['ok']); self.assertFalse(out['reloaded'])
        self.assertEqual(size, len(self.sets()['hetu_cn4']['m']), 'invalid data keeps the old set')
        self.assertNotIn('hetu_cn4_new', self.sets())
        self.stop(); self.assert_clean()

    def test_exempt_uid_reaches_proxy_for_cn_addresses(self):
        self.policy(force='10234')
        self.start()
        self.assertIn('CNIP_FORCE=10234\n', self.read(self.base / 'run/session.state'))
        for fam in ('4', '6'):
            mout = self.chain(fam, 'mangle', 'HETU_MOUT')
            g = [i for i, s in enumerate(mout) if '--uid-owner 10234 -g HETU_MOUT_T' in s]
            cn = [i for i, s in enumerate(mout) if '--match-set hetu_cn%s dst -j RETURN' % fam in s]
            self.assertTrue(g and cn and g[0] < cn[0], mout)
            self.assertIsNotNone(self.chain(fam, 'mangle', 'HETU_MOUT_T'))
        self.assertEqual('proxy', self.verdict('4', 10234, '114.114.114.114'), 'exempt app: CN still proxied')
        self.assertIsNone(self.verdict('4', 10123, '114.114.114.114'), 'other app: CN direct')
        self.assertEqual('proxy', self.verdict('4', 10123, '8.8.8.8'))
        self.assertEqual('proxy', self.verdict('4', 10234, '8.8.8.8'))
        self.assertEqual('proxy', self.verdict('6', 10234, '240e::1'))
        self.assertIsNone(self.verdict('6', 10123, '240e::1'))
        # Integrity records and repairs the tails together with their heads.
        script = (ASSETS / 'hetu-root.sh').read_text()
        owned = script[script.index('health_owned(){'):script.index('health_record(){')]
        for tail in ('MOUT_T', 'NOUT_T', 'WROUT_T', 'QUICOUT_T', 'V6OUT_T'): self.assertIn(tail, owned)
        self.stop(); self.assert_clean()

    def test_kill_switch_returns_cn_before_reject_and_stop_cleans_tails(self):
        self.policy(force='10234')
        self.start(kill='1')
        for fam in ('4', '6'):
            self.assertIsNone(self.chain(fam, 'filter', 'HETU_KOUT_T'), 'bootstrap guard tail dropped once running')
        # Core death: the watchdog keeps the network closed with the Kill Switch guard.
        os.kill(int(self.read(self.base / 'run/core.pid').strip()), signal.SIGKILL)
        for _ in range(60):
            if self.chain('4', 'filter', 'HETU_KOUT') and self.chain('6', 'filter', 'HETU_KOUT'): break
            time.sleep(0.25)
        for fam, dst_cn, dst_other in (('4', '114.114.114.114', '8.8.8.8'), ('6', '240e::1', '2001:4860:4860::8888')):
            kout = self.chain(fam, 'filter', 'HETU_KOUT'); self.assertTrue(kout, fam)
            self.assertIn('-m set --match-set hetu_cn%s dst -j RETURN' % fam, kout)
            self.assertEqual(['-j REJECT'], self.chain(fam, 'filter', 'HETU_KOUT_T'))
            self.assertIsNone(self.verdict(fam, 10123, dst_cn, table='filter'), 'CN stays reachable directly')
            self.assertEqual('reject', self.verdict(fam, 10123, dst_other, table='filter'))
            self.assertEqual('reject', self.verdict(fam, 10234, dst_cn, table='filter'), 'exempt app stays closed')
        self.assertEqual({'hetu_cn4', 'hetu_cn6'}, set(self.sets()), 'the guard still references the sets')
        self.stop(); self.assert_clean()

    def test_failed_sweep_never_destroys_referenced_sets(self):
        script = (ASSETS / 'hetu-root.sh').read_text()
        self.assertIn('if [ "$PRESERVE_KILL" != 1 ] && ! cnip_rules_reference_sets; then cnip_sweep_sets all; fi', script)
        at = script.index('cleanup_confirmed(){'); body = script[at:script.index('\n}\n', at)]
        self.assertLess(body.index('return 1'), body.index('cnip_sweep_sets all'), 'sets go only after the verified sweep')
        self.assertIn("grep -q -- '--match-set hetu_'", script[script.index('cleanup_verify(){'):])


if __name__ == '__main__':
    unittest.main(verbosity=2)
