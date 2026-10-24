#!/usr/bin/env python3
"""Shared network (hotspot/USB/Ethernet tethering) ingress scope + MAC whitelist host regression.

No real network, firewall or Root operation. The shipped hetu-root.sh runs against the stateful
netfilter model (netfilter_model93.py) with the CNIP harness of test_cnip93.py; `ip route get`
is answered by a fixture so the upstream (default-route) interface is known.

* Without $BASE/policy/share the shared chains keep the legacy, unscoped form (no *_S tail).
* Scoped: every shared PREROUTING/FORWARD chain dispatches only the listed tether ingress (plus
  lo for the local TPROXY re-route) with -g into HETU_<chain>_S; an upstream reply on wlan0
  (IPv6 without NAT, the misclassified case) is no longer TPROXYed, guarded or DNS-hijacked.
* The default-route interface (also through a "+" wildcard) and rmnet/ccmni/lo are dropped from
  the list and reported, never captured.
* MAC whitelist (MODE=only): only listed clients on the shared ingress reach the proxy and the
  guards; an empty whitelist proxies no shared client. The direct (blacklist) list is ignored.
* Kill Switch: the bootstrap guard tail goes with its head, the watchdog rebuilds HETU_KFWD with
  the session scope, and stop leaves nothing. A TERMinated core leaves nothing after status.
* Every new chain is HETU_-prefixed and known to sweep/unwind/integrity/cleanup fallbacks.
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

ASSETS = cn.ASSETS
IP_WRAPPER = r'''#!/bin/sh
# `ip -4|-6 route get X` answers from the upstream fixture; everything else is the model.
if [ "$2" = route ] && [ "$3" = get ]; then
  U=$(cat %(up)s 2>/dev/null) || exit 2
  [ -n "$U" ] || exit 2
  printf '%%s via 192.0.2.1 dev %%s table 1003 src 192.0.2.10 uid 0\n' "$4" "$U"; exit 0
fi
exec %(py)s %(model)s ip "$@"
'''


class Packet:
    def __init__(self, iif, dst, proto='udp', dport=443, mac=None, mark=0, local=False, ct='NEW', reply=False):
        self.iif, self.dst, self.proto, self.dport = iif, ipaddress.ip_address(dst), proto, dport
        self.mac, self.mark, self.local, self.ct, self.reply = mac, mark, local, ct, reply


class IngressEvaluator:
    """Walks PREROUTING/FORWARD chains for one received packet."""

    def __init__(self, rules):
        self.rules = rules

    def match(self, words, p):
        i, neg, module = 0, False, ''
        while i < len(words):
            w = words[i]
            if w == '!': neg = True; i += 1; continue
            if w in ('-j', '-g'): return True
            if w == '-m': module = words[i + 1]; i += 2; continue
            if w == '-i':
                v = words[i + 1]; ok = p.iif.startswith(v[:-1]) if v.endswith('+') else p.iif == v; i += 2
            elif w == '--mac-source': ok = p.mac is not None and p.mac.lower() == words[i + 1].lower(); i += 2
            elif w == '-p': ok = p.proto == words[i + 1]; i += 2
            elif w == '--dport': ok = p.dport == int(words[i + 1]); i += 2
            elif w == '-d':
                net = ipaddress.ip_network(words[i + 1], strict=False); ok = net.version == p.dst.version and p.dst in net; i += 2
            elif w == '--mark':
                m, _, mask = words[i + 1].partition('/'); mask = int(mask or '0xffffffff', 0)
                value = 0 if module == 'connmark' else p.mark
                ok = (value & mask) == (int(m, 0) & mask); i += 2
            elif w == '--dst-type': ok = p.local; i += 2
            elif w in ('--ctstate', '--state'): ok = p.ct in words[i + 1].split(','); i += 2
            elif w == '--ctdir': ok = p.reply == (words[i + 1] == 'REPLY'); i += 2
            elif w == '--match-set': ok = False; i += 3
            elif w in ('--uid-owner', '--gid-owner'): ok = False; i += 2
            else: raise AssertionError('evaluator: unsupported match %r in %r' % (w, ' '.join(words)))
            if neg: ok, neg = not ok, False
            if not ok: return False
        return True

    def run(self, chain, p, depth=0):
        assert depth < 16, 'chain loop'
        for spec in self.rules.get(chain, []):
            words = shlex.split(spec)
            if not self.match(words, p): continue
            kind = tgt = None
            for j, w in enumerate(words):
                if w in ('-j', '-g'): kind, tgt = w, words[j + 1]; break
            if tgt is None: continue
            if tgt == 'RETURN': return None
            if tgt in ('TPROXY', 'REDIRECT'): return 'proxy'
            if tgt in ('REJECT', 'DROP'): return 'reject'
            if tgt in ('ACCEPT', 'CONNMARK', 'CT', 'MARK'): continue
            if tgt in self.rules:
                verdict = self.run(tgt, p, depth + 1)
                if kind == '-g' or verdict is not None: return verdict
                continue
            raise AssertionError('evaluator: unknown target %s' % tgt)
        return None


class NetShare93(unittest.TestCase):
    setUp_cnip = cn.Cnip93.setUp
    tearDown = cn.Cnip93.tearDown
    stop = cn.Cnip93.stop
    run_cmd = cn.Cnip93.run_cmd
    status = cn.Cnip93.status
    read = staticmethod(cn.Cnip93.read)
    model = cn.Cnip93.model
    sets = cn.Cnip93.sets
    all_rules = cn.Cnip93.all_rules
    chain = cn.Cnip93.chain
    assert_clean = cn.Cnip93.assert_clean

    def setUp(self):
        self.setUp_cnip()
        self.up = self.dir / 'upstream'
        ip = self.bin / 'ip'
        ip.write_text(IP_WRAPPER % {'up': shlex.quote(str(self.up)), 'py': shlex.quote(sys.executable), 'model': shlex.quote(str(cn.MODEL))})
        ip.chmod(0o700)

    # ------------------------------------------------------------------ helpers
    def share_policy(self, ifaces='', mode='direct', macs=''):
        (self.base / 'policy/share').write_text('IFACES=%s\nMODE=%s\nMACS=%s\n' % (ifaces, mode, macs))

    def start(self, kill='0', direct_macs='', expect_ok=True, mode='tproxy'):
        args = ['start', str(self.base / 'bin/core'), str(self.base / 'run/state/startup-config'), mode, str(self.tp), str(self.tp if mode != 'tproxy' else 0),
                'enable', '1', '1', 'redirect', '1', str(self.dp), str(self.cp), 'all', '', '1', kill, '', '', '', '1', '1', '', direct_macs,
                '1', '1', '0', '', '', '', '0']
        r = subprocess.run(['sh', str(self.script)] + args, env=self.env, capture_output=True, text=True, timeout=90)
        last = r.stdout.strip().splitlines()[-1] if r.stdout.strip() else '{}'
        if expect_ok:
            self.assertEqual(0, r.returncode, r.stdout + r.stderr + self.read(self.base / 'run/start.error'))
            self.assertTrue(json.loads(last)['ok'], r.stdout)
        return r, json.loads(last)

    def rules(self, fam, table):
        return self.model()['xt'][fam][table]['rules']

    def verdict(self, fam, table, hook, pkt):
        return IngressEvaluator(self.rules(fam, table)).run(hook, pkt)

    def tproxy_mark(self, fam):
        for specs in self.rules(fam, 'mangle').values():
            for s in specs:
                if '--tproxy-mark' in s:
                    return int(s.split('--tproxy-mark ')[1].split()[0].split('/')[0], 0)
        self.fail('no TPROXY rule')

    def session(self):
        return self.read(self.base / 'run/session.state')

    def shared_tails(self):
        return [l for l in self.all_rules() if l.split()[2:3] == ['-N'] and l.split()[3].endswith('_S')]

    # ------------------------------------------------------------------ tests
    def test_without_policy_shared_chains_keep_legacy_unscoped_form(self):
        self.start(direct_macs='aa:aa:aa:aa:aa:01')
        self.assertEqual([], self.shared_tails())
        for fam in ('4', '6'):
            mpre = self.chain(fam, 'mangle', 'HETU_MPRE')
            self.assertIn('-m mac --mac-source aa:aa:aa:aa:aa:01 -j RETURN', mpre)
            self.assertFalse([s for s in mpre if ' -g HETU_' in s], mpre)
        self.assertIn('SHARE_SCOPED=0\n', self.session())
        # The legacy form is what over-captures: an upstream IPv6 UDP reply on wlan0.
        self.assertEqual('proxy', self.verdict('6', 'mangle', 'PREROUTING', Packet('wlan0', '2408:8000::5', ct='ESTABLISHED', reply=True)))
        st = self.status(); self.assertFalse(st['sharedScoped']); self.assertEqual('', st['sharedInterfaces'])
        self.stop(); self.assert_clean()

    def test_scoped_direct_mode_captures_only_tether_ingress(self):
        self.share_policy('wlan2,rndis0')
        self.start(direct_macs='aa:aa:aa:aa:aa:01')
        self.assertIn('SHARE_SCOPED=1\nSHARE_IFACES=wlan2,rndis0\nSHARE_MAC_MODE=direct\n', self.session())
        for fam in ('4', '6'):
            mpre = self.chain(fam, 'mangle', 'HETU_MPRE')
            for want in ('-i lo -g HETU_MPRE_S', '-i wlan2 -g HETU_MPRE_S', '-i rndis0 -g HETU_MPRE_S'):
                self.assertIn(want, mpre)
            self.assertEqual('-j RETURN', mpre[-1])
            self.assertLess(mpre.index('-m mac --mac-source aa:aa:aa:aa:aa:01 -j RETURN'), mpre.index('-i wlan2 -g HETU_MPRE_S'))
            self.assertFalse([s for s in mpre if 'TPROXY' in s], 'capture lives in the tail only')
            self.assertTrue([s for s in self.chain(fam, 'mangle', 'HETU_MPRE_S') if 'TPROXY' in s])
            for table, name in (('nat', 'HETU_DNSPRE'), ('filter', 'HETU_WRFWD'), ('filter', 'HETU_QUICFWD')):
                head = self.chain(fam, table, name)
                self.assertIsNotNone(head, (fam, name)); self.assertEqual('-j RETURN', head[-1])
                self.assertIsNotNone(self.chain(fam, table, name + '_S'), (fam, name))
                self.assertFalse([s for s in head if s.startswith('-i lo')] if table == 'filter' else [], 'FORWARD never sees lo')
            mark = self.tproxy_mark(fam)
            dst = '8.8.8.8' if fam == '4' else '2001:4860:4860::8888'
            self.assertEqual('proxy', self.verdict(fam, 'mangle', 'PREROUTING', Packet('wlan2', dst, mac='bb:bb:bb:bb:bb:02')))
            self.assertEqual('proxy', self.verdict(fam, 'mangle', 'PREROUTING', Packet('rndis0', dst, proto='tcp', mac='cc:cc:cc:cc:cc:03')))
            self.assertIsNone(self.verdict(fam, 'mangle', 'PREROUTING', Packet('wlan2', dst, mac='aa:aa:aa:aa:aa:01')), 'direct MAC')
            self.assertIsNone(self.verdict(fam, 'mangle', 'PREROUTING', Packet('wlan0', dst, ct='ESTABLISHED', reply=True)), 'upstream reply')
            self.assertIsNone(self.verdict(fam, 'mangle', 'PREROUTING', Packet('tun1', dst)), 'foreign ingress')
            self.assertEqual('proxy', self.verdict(fam, 'mangle', 'PREROUTING', Packet('lo', dst, mark=mark)), 'local re-route')
            self.assertEqual('reject', self.verdict(fam, 'filter', 'FORWARD', Packet('wlan2', dst, mac='bb:bb:bb:bb:bb:02')))
            self.assertIsNone(self.verdict(fam, 'filter', 'FORWARD', Packet('wlan0', dst)), 'no guard on upstream ingress')
            self.assertEqual('proxy', self.verdict(fam, 'nat', 'PREROUTING', Packet('wlan2', dst, dport=53, mac='bb:bb:bb:bb:bb:02')))
            self.assertIsNone(self.verdict(fam, 'nat', 'PREROUTING', Packet('wlan0', dst, dport=53)), 'no DNS hijack on upstream ingress')
        st = self.status()
        self.assertTrue(st['sharedScoped']); self.assertEqual('wlan2,rndis0', st['sharedInterfaces']); self.assertEqual('direct', st['sharedMacMode'])
        self.assertNotEqual('degraded', st['networkIntegrity'], st)  # the recorded scope is the live scope
        self.stop(); self.assert_clean()

    def test_upstream_cellular_and_wildcard_interfaces_are_dropped(self):
        self.up.write_text('wlan0\n')
        self.share_policy('wlan0,wlan+,wlan2,rmnet_data0,lo')
        r = self.start(expect_ok=False)[0]
        self.assertNotEqual(0, r.returncode, 'lo is refused by validation')
        self.assert_clean()
        self.share_policy('wlan0,wlan+,wlan2,rmnet_data0,ccmni1')
        self.start()
        session = self.session()
        self.assertIn('SHARE_IFACES=wlan2\n', session)
        self.assertIn('SHARE_DROPPED=wlan0,wlan+,rmnet_data0,ccmni1\n', session)
        for l in self.all_rules():
            if 'HETU_' in l:
                for bad in ('-i wlan0', '-i wlan+', '-i rmnet', '-i ccmni'): self.assertNotIn(bad, l)
        self.assertIsNone(self.verdict('6', 'mangle', 'PREROUTING', Packet('wlan0', '2408:8000::5', reply=True, ct='ESTABLISHED')))
        self.assertEqual('proxy', self.verdict('4', 'mangle', 'PREROUTING', Packet('wlan2', '8.8.8.8', mac='bb:bb:bb:bb:bb:02')))
        self.assertEqual('wlan0,wlan+,rmnet_data0,ccmni1', self.status()['sharedDroppedInterfaces'])
        self.assertIn('dropped=wlan0,wlan+,rmnet_data0,ccmni1', self.read(self.base / 'run/share.log'))
        self.stop(); self.assert_clean()

    def test_all_requested_interfaces_upstream_proxies_no_shared_client(self):
        self.up.write_text('wlan0\n')
        self.share_policy('wlan0')
        self.start()
        self.assertIn('SHARE_SCOPED=1\nSHARE_IFACES=\n', self.session())
        for fam in ('4', '6'):
            self.assertEqual(['-m mark --mark 0x08000000/0x08000000 -j RETURN', '-i lo -g HETU_MPRE_S', '-j RETURN'], self.chain(fam, 'mangle', 'HETU_MPRE'))
        self.assertIsNone(self.verdict('4', 'mangle', 'PREROUTING', Packet('wlan2', '8.8.8.8')))
        self.stop(); self.assert_clean()

    def test_mac_whitelist_proxies_only_listed_clients(self):
        self.share_policy('wlan2', 'only', 'bb:bb:bb:bb:bb:02,dd:dd:dd:dd:dd:04')
        self.start(direct_macs='aa:aa:aa:aa:aa:01')
        session = self.session()
        self.assertIn('SHARED_BYPASS_MACS=\n', session, 'the direct list has no meaning in whitelist mode')
        self.assertIn('SHARE_MAC_MODE=only\nSHARE_ONLY_MACS=bb:bb:bb:bb:bb:02,dd:dd:dd:dd:dd:04\n', session)
        for fam in ('4', '6'):
            mpre = self.chain(fam, 'mangle', 'HETU_MPRE')
            self.assertIn('-i wlan2 -m mac --mac-source bb:bb:bb:bb:bb:02 -g HETU_MPRE_S', mpre)
            self.assertFalse([s for s in self.all_rules() if 'aa:aa:aa:aa:aa:01' in s])
            dst = '8.8.8.8' if fam == '4' else '2001:4860:4860::8888'
            self.assertEqual('proxy', self.verdict(fam, 'mangle', 'PREROUTING', Packet('wlan2', dst, mac='BB:BB:BB:BB:BB:02')))
            self.assertEqual('proxy', self.verdict(fam, 'mangle', 'PREROUTING', Packet('wlan2', dst, mac='dd:dd:dd:dd:dd:04')))
            self.assertIsNone(self.verdict(fam, 'mangle', 'PREROUTING', Packet('wlan2', dst, mac='cc:cc:cc:cc:cc:03')))
            self.assertIsNone(self.verdict(fam, 'mangle', 'PREROUTING', Packet('rndis0', dst, mac='bb:bb:bb:bb:bb:02')), 'listed MAC, other ingress')
            self.assertIsNone(self.verdict(fam, 'filter', 'FORWARD', Packet('wlan2', dst, mac='cc:cc:cc:cc:cc:03')), 'unlisted client goes direct, unguarded')
            self.assertEqual('reject', self.verdict(fam, 'filter', 'FORWARD', Packet('wlan2', dst, mac='bb:bb:bb:bb:bb:02')))
            self.assertIsNone(self.verdict(fam, 'nat', 'PREROUTING', Packet('wlan2', dst, dport=53, mac='cc:cc:cc:cc:cc:03')))
            self.assertEqual('proxy', self.verdict(fam, 'nat', 'PREROUTING', Packet('wlan2', dst, dport=53, mac='bb:bb:bb:bb:bb:02')))
        st = self.status(); self.assertEqual('only', st['sharedMacMode']); self.assertEqual('bb:bb:bb:bb:bb:02,dd:dd:dd:dd:dd:04', st['sharedOnlyMacs'])
        self.stop(); self.assert_clean()

    def test_empty_whitelist_without_interfaces_proxies_no_shared_client(self):
        self.share_policy('', 'only', '')
        self.start()
        for fam in ('4', '6'):
            mark = self.tproxy_mark(fam)
            dst = '8.8.8.8' if fam == '4' else '2001:4860:4860::8888'
            self.assertIsNone(self.verdict(fam, 'mangle', 'PREROUTING', Packet('wlan2', dst, mac='bb:bb:bb:bb:bb:02')))
            self.assertIsNone(self.verdict(fam, 'filter', 'FORWARD', Packet('wlan2', dst, mac='bb:bb:bb:bb:bb:02')))
            self.assertEqual('proxy', self.verdict(fam, 'mangle', 'PREROUTING', Packet('lo', dst, mark=mark)), 'this device stays proxied')
        self.stop(); self.assert_clean()

    def test_redirect_mode_scopes_nat_prerouting(self):
        self.share_policy('wlan2')
        self.start(mode='redirect')
        for fam in ('4', '6'):
            npre = self.chain(fam, 'nat', 'HETU_NPRE')
            self.assertIn('-i wlan2 -g HETU_NPRE_S', npre); self.assertIn('-i lo -g HETU_NPRE_S', npre)
            dst = '8.8.8.8' if fam == '4' else '2001:4860:4860::8888'
            self.assertEqual('proxy', self.verdict(fam, 'nat', 'PREROUTING', Packet('wlan2', dst, proto='tcp', mac='bb:bb:bb:bb:bb:02')))
            self.assertIsNone(self.verdict(fam, 'nat', 'PREROUTING', Packet('wlan0', dst, proto='tcp')))
        self.stop(); self.assert_clean()

    def test_kill_switch_keeps_scope_after_core_death_and_stop_cleans(self):
        self.share_policy('wlan2')
        self.start(kill='1')
        for fam in ('4', '6'):
            self.assertIsNone(self.chain(fam, 'filter', 'HETU_KFWD_S'), 'bootstrap guard tail revoked with its head')
        os.kill(int(self.read(self.base / 'run/core.pid').strip()), signal.SIGKILL)
        for _ in range(80):
            if self.chain('4', 'filter', 'HETU_KFWD') and (self.chain('6', 'filter', 'HETU_KFWD') is not None): break
            time.sleep(0.25)
        for fam in ('4', '6'):
            kfwd = self.chain(fam, 'filter', 'HETU_KFWD'); self.assertTrue(kfwd, fam)
            self.assertIn('-i wlan2 -g HETU_KFWD_S', kfwd); self.assertEqual('-j RETURN', kfwd[-1])
            self.assertEqual('-j REJECT', self.chain(fam, 'filter', 'HETU_KFWD_S')[-1])
            dst = '8.8.8.8' if fam == '4' else '2001:4860:4860::8888'
            self.assertEqual('reject', self.verdict(fam, 'filter', 'FORWARD', Packet('wlan2', dst, mac='bb:bb:bb:bb:bb:02')))
            self.assertIsNone(self.verdict(fam, 'filter', 'FORWARD', Packet('wlan0', dst)))
        self.stop(); self.assert_clean()

    def test_terminated_core_leaves_no_scoped_chain_after_status(self):
        self.share_policy('wlan2', 'only', 'bb:bb:bb:bb:bb:02')
        self.start()
        self.assertTrue(self.shared_tails())
        os.kill(int(self.read(self.base / 'run/core.pid').strip()), signal.SIGTERM)
        for _ in range(80):
            st = self.status()
            if not st['running'] and not [l for l in self.all_rules() if 'HETU_' in l]: break
            time.sleep(0.25)
        self.status()
        self.assertEqual([], [l for l in self.all_rules() if 'HETU_' in l])
        self.stop(); self.assert_clean()

    def test_invalid_policy_fails_before_touching_the_network(self):
        self.share_policy('wlan2', 'everything', '')
        r, out = self.start(expect_ok=False)
        self.assertFalse(out.get('ok', False)); self.assertIn('MAC 模式', out.get('message', ''))
        self.assert_clean()
        self.share_policy('wlan2', 'only', 'ff:ff:ff:ff:ff:ff')
        r, out = self.start(expect_ok=False)
        self.assertFalse(out.get('ok', False)); self.assert_clean()

    def test_every_new_chain_is_known_to_sweep_unwind_integrity_and_cleanup(self):
        script = (ASSETS / 'hetu-root.sh').read_text()
        self.assertIn('c == "HETU_KFWD_S"', script[script.index('sweep_plan(){'):script.index('sweep_table(){')])
        unwind = script[script.index('capture_unwind(){'):script.index('\nstart(){')]
        for tail in ('${MPRE}_S', '${NPRE}_S', '${DNSPRE}_S', '${WRFWD}_S', '${QUICFWD}_S', '${V6FWD}_S'): self.assertIn(tail, unwind)
        owned = script[script.index('health_owned(){'):script.index('health_record(){')]
        for tail in ('MPRE_S', 'NPRE_S', 'DNSPRE_S', 'WRFWD_S', 'QUICFWD_S', 'V6FWD_S'): self.assertIn(tail, owned)
        for fn in ('cleanup4(){', 'cleanup6(){'):
            body = script[script.index(fn):script.index('\n}\n', script.index(fn))]
            self.assertIn('share_drop_tails', body)
        drop = script[script.index('share_drop_tails(){'):script.index('\n}\n', script.index('share_drop_tails(){'))]
        self.assertIn('if [ "$PRESERVE_KILL" != 1 ]; then cn_drop_tail "$SDT_BIN" filter "${KFWD}_S"; fi', drop)
        self.assertNotIn('shared_mac_returns xt', script, 'every shared chain goes through share_scope')


if __name__ == '__main__':
    unittest.main(verbosity=2)
