#!/usr/bin/env python3
"""CNIP kernel bypass without an ipset binary: the iptables CIDR tree (host regression).

Same isolated fixtures as test_cnip93 (stateful netfilter model, fake core, no real firewall or Root),
but the ROM has no `ipset` (the fixture binary exits 127, as on stock ColorOS / OnePlus PLK110).

* The start installs, per family, one mangle tree: OUTPUT/PREROUTING -j HETU_CNC (first rule) ->
  HETU_CNL -> nested HETU_CN_<bits>_<key> blocks -> HETU_CNY (CONNMARK CN|CHK). Only ctstate NEW
  without the CHK bit walks it; every capture/guard chain RETURNs on the CN conntrack bit instead of a
  set match. status reports cnip=iptables with the real entry counts.
* The tree matches exactly the shipped CN list (every network edge and random addresses), and a
  connection classified once is never walked again (established packets only test the bit).
* TCPONLY, V4/V6 and FORCE UIDs behave like the ipset path; IPv6 follows the IPv6 mode.
* Invalid data switches that family off; restart never duplicates the hooks; cnip-reload rebuilds
  changed data and keeps the installed tree when the new data is invalid.
* Kill Switch: the tree survives a core death with the guard (CN stays reachable, everything else
  rejected) and stop removes it; stop is idempotent and stop-verify sees a leftover tree.
"""
from pathlib import Path
import bisect
import ipaddress
import json
import os
import shlex
import signal
import sys
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import test_cnip93  # noqa: E402
from test_cnip93 import ASSETS  # noqa: E402

CN, CHK = 0x20000000, 0x40000000


class Conn:
    def __init__(self, uid, dst, proto='tcp', dport=443):
        self.uid, self.dst, self.proto, self.dport = uid, ipaddress.ip_address(dst), proto, dport
        self.ctmark, self.state, self.mark = 0, 'NEW', 0
        self.walked = 0


class Evaluator:
    """Walks modelled chains for one locally generated packet with real -j/-g/RETURN semantics."""

    def __init__(self, rules):
        self.rules = rules
        self.parsed = {c: [shlex.split(s) for s in specs] for c, specs in rules.items()}

    def match(self, w, pkt):
        i, neg, module = 0, False, ''
        while i < len(w):
            x = w[i]
            if x == '!': neg = True; i += 1; continue
            if x in ('-j', '-g'): return True
            if x == '-m': module = w[i + 1]; i += 2; continue
            if x in ('--ctstate', '--state'): ok = pkt.state in w[i + 1].split(','); i += 2
            elif x == '--mark':
                m, _, mask = w[i + 1].partition('/'); mask = int(mask or '0xffffffff', 0)
                cur = pkt.ctmark if module == 'connmark' else pkt.mark
                ok = (cur & mask) == (int(m, 0) & mask); i += 2
            elif x == '-p': ok = pkt.proto == w[i + 1]; i += 2
            elif x == '--uid-owner':
                lo, _, hi = w[i + 1].partition('-'); ok = int(lo) <= pkt.uid <= int(hi or lo); i += 2
            elif x == '--gid-owner': ok = False; i += 2
            elif x == '--match-set': raise AssertionError('no set may be referenced without ipset: %r' % ' '.join(w))
            elif x == '-d':
                net = ipaddress.ip_network(w[i + 1], strict=False)
                ok = net.version == pkt.dst.version and pkt.dst in net; i += 2
            elif x == '--dport': ok = pkt.dport == int(w[i + 1]); i += 2
            elif x == '-o': ok = False; i += 2
            elif x == '--dst-type': ok = False; i += 2
            elif x == '--ctorigdst':
                ok = pkt.dst in ipaddress.ip_network(w[i + 1], strict=False) if pkt.dst.version == ipaddress.ip_network(w[i + 1], strict=False).version else False; i += 2
            else: raise AssertionError('evaluator: unsupported match %r in %r' % (x, ' '.join(w)))
            if neg: ok, neg = not ok, False
            if not ok: return False
        return True

    @staticmethod
    def target(w):
        for j, x in enumerate(w):
            if x in ('-j', '-g'): return x, w[j + 1], w[j + 2:]
        return None, None, []

    def run(self, chain, pkt, depth=0):
        assert depth < 24, 'chain loop'
        for w in self.parsed.get(chain, []):
            if chain.startswith('HETU_CN'): pkt.walked += 1
            if not self.match(w, pkt): continue
            kind, tgt, extra = self.target(w)
            if tgt is None: continue
            if tgt == 'RETURN': return None
            if tgt == 'CONNMARK':
                value, _, mask = extra[extra.index('--set-xmark') + 1].partition('/')
                value, mask = int(value, 0), int(mask, 0)
                pkt.ctmark = (pkt.ctmark & ~mask) ^ value
                continue
            if tgt in ('MARK', 'TPROXY', 'REDIRECT'): return 'proxy'
            if tgt in ('REJECT', 'DROP'): return 'reject'
            if tgt in ('ACCEPT', 'CT'): continue
            if tgt in self.parsed:
                verdict = self.run(tgt, pkt, depth + 1)
                if kind == '-g': return verdict
                if verdict is not None: return verdict
                continue
            raise AssertionError('evaluator: unknown target %s' % tgt)
        return None


class CnipXt93(test_cnip93.Cnip93):
    def setUp(self):
        super().setUp()
        Path(str(self.ipstate) + '.absent').write_text('')

    def policy(self, v4=1, v6=1, force='', tcp_only=None):
        text = 'V4=%d\nV6=%d\nFORCE=%s\n' % (v4, v6, force)
        if tcp_only is not None: text += 'TCPONLY=%d\n' % tcp_only
        (self.base / 'policy/cnip').write_text(text)

    def session(self):
        return self.read(self.base / 'run/session.state')

    def conn_verdict(self, fam, conn, table='mangle'):
        rules = self.model()['xt'][fam]
        if table != 'mangle':
            # Locally generated: mangle OUTPUT (the classifier) always runs first.
            Evaluator(rules['mangle']['rules']).run('OUTPUT', conn)
        return Evaluator(rules[table]['rules']).run('OUTPUT', conn)

    def tree_chains(self, fam):
        return [c for c in self.model()['xt'][fam]['mangle']['rules'] if c.startswith('HETU_CN')]

    def assert_no_tree(self):
        left = [l for l in self.all_rules() if 'HETU_CN' in l or ('connmark --mark 0x20000000' in l)]
        self.assertEqual([], left)

    # ------------------------------------------------------------------ tests
    def test_tree_installed_returns_cn_and_stop_removes_everything(self):
        self.policy()
        self.start()
        session = self.session()
        self.assertIn('CNIP=iptables\n', session); self.assertIn('CNIP_V4=1\n', session); self.assertIn('CNIP_V6=1\n', session)
        self.assertIn('CNIP_REASON=ipset-unavailable\n', session)
        self.assertFalse([l for l in self.all_rules() if '--match-set' in l])
        for fam in ('4', '6'):
            mangle = self.model()['xt'][fam]['mangle']['rules']
            for hook in ('OUTPUT', 'PREROUTING'):
                self.assertEqual('-j HETU_CNC', mangle[hook][0], (fam, hook, mangle[hook]))
                self.assertEqual(1, mangle[hook].count('-j HETU_CNC'))
            self.assertEqual(['-m conntrack --ctstate NEW -m connmark ! --mark 0x40000000/0x40000000 -j HETU_CNL',
                              '-m conntrack --ctstate NEW -j CONNMARK --set-xmark 0x40000000/0x40000000'], mangle['HETU_CNC'])
            self.assertEqual(['-j CONNMARK --set-xmark 0x60000000/0x60000000'], mangle['HETU_CNY'])
            self.assertIn('-m connmark --mark 0x20000000/0x20000000 -j RETURN', mangle['HETU_MOUT'])
            self.assertTrue(any('-m connmark --mark 0x20000000/0x20000000 -j RETURN' in s and '! --mark' in s for s in mangle['HETU_MPRE']))
            for name in ('HETU_WROUT', 'HETU_QUICOUT'):
                rules = self.chain(fam, 'filter', name)
                if rules is not None: self.assertTrue(any('connmark --mark 0x20000000/0x20000000' in s for s in rules), (name, rules))
            # No chain of the tree is longer than the split threshold plus its block jumps allow.
            longest = max(len(mangle[c]) for c in self.tree_chains(fam) if c.startswith('HETU_CN_'))
            self.assertLess(longest, 80)
        # DNS hijack chains never test the CN bit: CN destinations keep their DNS captured.
        for fam in ('4', '6'):
            for table in ('mangle', 'nat', 'filter'):
                for name in ('HETU_DNSOUT', 'HETU_DNSPRE', 'HETU_DOTOUT'):
                    self.assertFalse(any('connmark' in s for s in (self.chain(fam, table, name) or [])), (fam, table, name))
        st = self.status()
        self.assertEqual('iptables', st['cnip']); self.assertEqual('ipset-unavailable', st['cnipReason'])
        self.assertEqual(6273, st['cnipV4Entries']); self.assertEqual(3468, st['cnipV6Entries'])
        cn = Conn(10123, '114.114.114.114'); self.assertIsNone(self.conn_verdict('4', cn))
        self.assertEqual(CN | CHK, cn.ctmark)
        other = Conn(10123, '8.8.8.8'); self.assertEqual('proxy', self.conn_verdict('4', other))
        self.assertEqual(CHK, other.ctmark)
        self.assertIsNone(self.conn_verdict('6', Conn(10123, '240e::1')))
        self.assertEqual('proxy', self.conn_verdict('6', Conn(10123, '2001:4860:4860::8888')))
        self.stop()
        self.assert_clean(); self.assert_no_tree()
        self.assertEqual('off', self.status().get('cnip', 'off'))

    def test_tree_matches_the_list_exactly_and_established_packets_skip_it(self):
        self.policy()
        self.start()
        for fam, width in (('4', 32), ('6', 128)):
            nets = sorted((int(n.network_address), int(n.broadcast_address)) for n in
                          (ipaddress.ip_network(l.strip()) for l in (ASSETS / ('cnip/hetu-cn-v%s.txt' % fam)).read_text().splitlines() if l.strip()))
            starts = [a for a, _ in nets]

            def member(a):
                i = bisect.bisect_right(starts, a) - 1
                return i >= 0 and nets[i][0] <= a <= nets[i][1]
            mangle = Evaluator(self.model()['xt'][fam]['mangle']['rules'])
            probes = []
            for a, b in nets[::7]: probes += [a, b, max(a - 1, 0), min(b + 1, 2 ** width - 1)]
            seed = 0x9e3779b9
            for _ in range(1500):
                seed = (seed * 6364136223846793005 + 1442695040888963407) % 2 ** 64
                probes.append(seed >> 32 if fam == '4' else (0x24 << 120) | (seed << 58) % 2 ** 122)
            worst = 0
            for a in probes:
                conn = Conn(10123, str(ipaddress.ip_address(a)))
                mangle.run('OUTPUT', conn)
                self.assertEqual(member(a), bool(conn.ctmark & CN), ipaddress.ip_address(a))
                self.assertTrue(conn.ctmark & CHK)
                worst = max(worst, conn.walked)
                conn.state, conn.walked = 'ESTABLISHED', 0
                mangle.run('OUTPUT', conn)
                self.assertLessEqual(conn.walked, 2, 'an established packet only passes HETU_CNC')
            self.assertLess(worst, 220, 'bounded walk per new connection')
        self.stop(); self.assert_no_tree()

    def test_tcp_only_and_force_uid_follow_the_policy(self):
        self.policy(force='10234', tcp_only=1)
        self.start()
        self.assertIn('CNIP_TCPONLY=1\n', self.session()); self.assertIn('CNIP_FORCE=10234\n', self.session())
        mangle = self.model()['xt']['4']['mangle']['rules']
        self.assertTrue(mangle['HETU_CNC'][0].startswith('-p tcp -m conntrack --ctstate NEW'), mangle['HETU_CNC'])
        mout = mangle['HETU_MOUT']
        g = [i for i, s in enumerate(mout) if '--uid-owner 10234 -g HETU_MOUT_T' in s]
        r = [i for i, s in enumerate(mout) if s == '-p tcp -m connmark --mark 0x20000000/0x20000000 -j RETURN']
        self.assertTrue(g and r and g[0] < r[0], mout)
        self.assertIs(True, self.status()['cnipTcpOnly'])
        self.assertIsNone(self.conn_verdict('4', Conn(10123, '114.114.114.114')))
        self.assertEqual('proxy', self.conn_verdict('4', Conn(10123, '114.114.114.114', proto='udp', dport=53000)), 'UDP to CN stays in the core')
        self.assertEqual('proxy', self.conn_verdict('4', Conn(10234, '114.114.114.114')), 'exempt app: CN still proxied')
        self.assertEqual('proxy', self.conn_verdict('4', Conn(10123, '8.8.8.8')))
        self.stop(); self.assert_clean(); self.assert_no_tree()

    def test_v4_only_policy_and_invalid_data_switch_a_family_off(self):
        self.policy(v4=1, v6=0)
        self.start()
        self.assertIn('CNIP_V6=0\n', self.session())
        self.assertTrue(self.tree_chains('4')); self.assertEqual([], self.tree_chains('6'))
        self.assertFalse([l for l in self.all_rules() if l.startswith('6 ') and 'connmark --mark 0x20000000' in l])
        self.stop()
        self.policy()
        (self.base / 'run/ruleset/hetu-cn-v4.txt').write_text('1.2.3.0/24\n')  # below the 1000-entry floor
        self.start()
        session = self.session()
        self.assertIn('CNIP=iptables\n', session); self.assertIn('CNIP_V4=0\n', session); self.assertIn('v4-data-invalid', session)
        self.assertEqual([], self.tree_chains('4')); self.assertTrue(self.tree_chains('6'))
        self.assertFalse([l for l in self.all_rules() if l.startswith('4 ') and 'connmark --mark 0x20000000' in l])
        self.stop(); self.assert_no_tree()
        (self.base / 'run/ruleset/hetu-cn-v6.txt').write_text('garbage\n')
        self.start()
        self.assertIn('CNIP=degraded\n', self.session())
        self.assertFalse([l for l in self.all_rules() if 'connmark --mark 0x20000000' in l or 'HETU_CN' in l])
        self.stop(); self.assert_clean()

    def test_restart_never_duplicates_and_reload_rebuilds_or_keeps(self):
        self.policy()
        self.start()
        first = len(self.tree_chains('4'))
        self.start()  # in-place restart: start-time cleanup, then a fresh tree
        mangle = self.model()['xt']['4']['mangle']['rules']
        self.assertEqual(1, mangle['OUTPUT'].count('-j HETU_CNC')); self.assertEqual(1, mangle['PREROUTING'].count('-j HETU_CNC'))
        self.assertEqual(first, len(self.tree_chains('4')))
        r = self.run_cmd('cnip-reload', 'auto')
        out = json.loads(r.stdout.strip().splitlines()[-1]); self.assertTrue(out['ok']); self.assertFalse(out['reloaded']); self.assertEqual('iptables', out['cnip'])
        f4 = self.base / 'run/ruleset/hetu-cn-v4.txt'; good = f4.read_text()
        self.assertEqual('proxy', self.conn_verdict('4', Conn(10123, '5.6.7.8')))
        f4.write_text(good + '5.6.7.0/24\n')
        r = self.run_cmd('cnip-reload')
        out = json.loads(r.stdout.strip().splitlines()[-1]); self.assertTrue(out['reloaded'], r.stdout + r.stderr)
        self.assertIsNone(self.conn_verdict('4', Conn(10123, '5.6.7.8')))
        self.assertEqual(6274, self.status()['cnipV4Entries'])
        self.assertEqual(1, self.model()['xt']['4']['mangle']['rules']['OUTPUT'].count('-j HETU_CNC'))
        f4.write_text('garbage\n')
        r = self.run_cmd('cnip-reload')
        out = json.loads(r.stdout.strip().splitlines()[-1]); self.assertFalse(out['ok']); self.assertFalse(out['reloaded'])
        self.assertIsNone(self.conn_verdict('4', Conn(10123, '5.6.7.8')), 'invalid data keeps the installed tree')
        self.assertEqual('iptables', self.status()['cnip'])
        self.stop(); self.assert_clean(); self.assert_no_tree()

    def test_missing_hook_reports_degraded(self):
        self.policy(v4=1, v6=0)
        self.start()
        state = self.model()
        state['xt']['4']['mangle']['rules']['OUTPUT'].remove('-j HETU_CNC')
        self.state.write_text(json.dumps(state))
        st = self.status()
        self.assertEqual('degraded', st['cnip']); self.assertIn('v4-chain-missing', st['cnipReason'])
        self.stop(); self.assert_clean(); self.assert_no_tree()

    def test_kill_switch_keeps_tree_with_guard_and_stop_removes_it(self):
        self.policy(force='10234')
        self.start(kill='1')
        os.kill(int(self.read(self.base / 'run/core.pid').strip()), signal.SIGKILL)
        for _ in range(60):
            if self.chain('4', 'filter', 'HETU_KOUT') and self.chain('6', 'filter', 'HETU_KOUT'): break
            time.sleep(0.25)
        for fam, dst_cn, dst_other in (('4', '114.114.114.114', '8.8.8.8'), ('6', '240e::1', '2001:4860:4860::8888')):
            kout = self.chain(fam, 'filter', 'HETU_KOUT'); self.assertTrue(kout, fam)
            self.assertIn('-m connmark --mark 0x20000000/0x20000000 -j RETURN', kout)
            self.assertTrue(self.tree_chains(fam), 'the classifier stays with the guard')
            self.assertIsNone(self.conn_verdict(fam, Conn(10123, dst_cn), table='filter'), 'CN stays reachable directly')
            self.assertEqual('reject', self.conn_verdict(fam, Conn(10123, dst_other), table='filter'))
            self.assertEqual('reject', self.conn_verdict(fam, Conn(10234, dst_cn), table='filter'), 'exempt app stays closed')
        self.stop(); self.assert_clean(); self.assert_no_tree()

    def test_stop_is_idempotent_and_verify_sees_a_leftover_tree(self):
        self.policy()
        self.start()
        self.stop(); self.stop()
        self.assert_clean(); self.assert_no_tree()
        script = (ASSETS / 'hetu-root.sh').read_text()
        verify = script[script.index('cleanup_verify(){'):script.index('cleanup_confirmed(){')]
        leftovers = script[script.index('stop_leftovers(){'):script.index('stop_verify(){')]
        # Both checks flag any HETU_ chain or hook, which covers HETU_CNC/HETU_CNL/HETU_CNY/HETU_CN_*.
        for body in (verify, leftovers): self.assertIn("(^-N |^-A | -j | -g )(HETU_|BICHEN_)", body)
        # A tree left by a crashed session (no session file) is swept by the next stop.
        state = self.model()
        m = state['xt']['4']['mangle']
        for c, specs in (('HETU_CNC', ['-j HETU_CNL']), ('HETU_CNL', ['-d 1.0.1.0/24 -g HETU_CNY']), ('HETU_CNY', ['-j CONNMARK --set-xmark 0x60000000/0x60000000'])):
            m['order'].append(c); m['rules'][c] = specs
        m['rules']['OUTPUT'].insert(0, '-j HETU_CNC')
        self.state.write_text(json.dumps(state))
        self.stop()
        self.assert_no_tree(); self.assert_clean()


def load_tests(loader, tests, pattern):
    # Only this module's tests; the inherited ipset tests run in test_cnip93.
    return unittest.TestSuite(loader.loadTestsFromName(name, CnipXt93) for name in loader.getTestCaseNames(CnipXt93)
                              if name in CnipXt93.__dict__)


if __name__ == '__main__':
    unittest.main(verbosity=2)
