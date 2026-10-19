#!/usr/bin/env python3
"""6.0.0 domestic UDP kernel bypass host regression (hetu-root.sh, no Root, no device).

Field report: some domestic apps lag badly, video calls above all. By default every UDP packet of
every app (call media, games, P2P) went through the core's userspace relay, CN destinations
included; the kernel CN bypass (CNIP) was opt-in and needs ipset, which stock ColorOS lacks.

* The App now writes policy/cnip with UDPONLY=1 AUTO=1 by default (the full bypass stays a switch):
  only UDP to CN addresses RETURNs before TPROXY, TCP still reaches the core, DNS takeover is
  untouched.
* Without ipset the CN programs of hetu_match.o (hetu-bpf sync-cn, one `-m bpf --object-pinned`
  rule per chain) do the same, for the default and for the full bypass; the iptables tree stays the
  explicit opt-in. The Kill Switch guard never references a CN pin.
* AUTO: a kernel that can do it neither way runs without it (cnip=off, reason kept) instead of
  reporting a degraded CNIP; the user's own full bypass still reports degraded.
* cnip-reload re-syncs the LPM maps (invalid data keeps them); stop leaves no rule and no pin.

Runs the shipped script against the netfilter / ipset / hetu-bpf stand-ins of test_cnip93.py and
test_ebpf93.py.
"""
from pathlib import Path
import ipaddress
import json
import re
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import test_cnip93 as cn  # noqa: E402
import test_ebpf93 as eb  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
SRC = REPO / 'android-app/app/src/main'

# The hetu-bpf stand-in plus sync-cn with the loader's bounds (v4 1000-20000, v6 500-30000 networks).
SYNC_CN = r'''
if cmd == 'sync-cn':
    fam = a[2]
    r = fixture('cn-fail')
    if r: fail(r)
    try: lines = [l.split('#')[0].strip() for l in open(a[3])]
    except OSError: fail('cn-data-missing')
    nets = []
    for l in lines:
        if not l: continue
        try: n = ipaddress.ip_network(l, strict=False)
        except ValueError: continue
        if n.version == int(fam): nets.append(str(n))
    lo, hi = (1000, 20000) if fam == '4' else (500, 30000)
    if not lo <= len(nets) <= hi: fail('cn-data-invalid')
    wr(a[1], 'hetu_cn' + fam, {k: 1 for k in nets}); print('cn%s=%d' % (fam, len(nets))); sys.exit(0)
'''
FAKE_BPF = eb.FAKE_BPF.replace("if cmd == 'count':", SYNC_CN.strip('\n') + "\nif cmd == 'count':", 1)
assert FAKE_BPF != eb.FAKE_BPF


class Evaluator(eb.Evaluator):
    """The CN programs answer from their LPM maps like the bypass programs."""

    def bpf(self, path, pkt):
        prog = Path(path).name[len('prog_'):]
        if prog in ('hetu_cn4', 'hetu_cn6'):
            nets = json.loads((self.pins / ('map_' + prog)).read_text())
            return any(pkt.dst in ipaddress.ip_network(n) for n in nets if ipaddress.ip_network(n).version == pkt.dst.version)
        return super().bpf(path, pkt)


class CnUdp600(eb.Ebpf93):
    def setUp(self):
        super().setUp()
        (self.dir / 'fakebpf.py').write_text(FAKE_BPF)

    def udp_policy(self, force=''):
        (self.base / 'policy/cnip').write_text('V4=1\nV6=1\nFORCE=%s\nTCPONLY=0\nUDPONLY=1\nAUTO=1\n' % force)

    def no_ipset(self):
        Path(str(self.ipstate) + '.absent').write_text('')
        Path(str(self.ipstate) + '.noconnmark').write_text('')

    def no_loader(self):
        (self.base / 'bin/hetu-bpf').unlink()

    def verdict(self, fam, uid, dst, proto='tcp', table='mangle'):
        rules = self.model()['xt'][fam][table]['rules']
        return Evaluator(rules, self.sets(), self.pins).run('OUTPUT', cn.Packet(uid, dst, proto))

    def session(self):
        return self.read(self.base / 'run/session.state')

    def cn_rules(self):
        return [l for l in self.all_rules() if '--match-set hetu_cn' in l or 'prog_hetu_cn' in l]

    def assert_udp_only(self):
        for fam, cn_dst, other in (('4', '114.114.114.114', '8.8.8.8'), ('6', '240e::1', '2001:4860::1')):
            self.assertIsNone(self.verdict(fam, 10123, cn_dst, 'udp'), 'UDP to a CN address skips the core (v%s)' % fam)
            self.assertEqual('proxy', self.verdict(fam, 10123, cn_dst, 'tcp'), 'TCP to it still reaches the core (v%s)' % fam)
            self.assertEqual('proxy', self.verdict(fam, 10123, other, 'udp'), 'UDP elsewhere is proxied (v%s)' % fam)
            if fam == '4':
                self.assertIsNone(self.verdict(fam, 10123, cn_dst, 'udp', table='filter'), 'the UDP leak guard lets it out')
        rules = self.cn_rules()
        self.assertTrue(rules)
        self.assertFalse([l for l in rules if ' -p udp ' not in l + ' '], 'every CN return is UDP-only')
        # DNS takeover never references the CN bypass.
        for fam in ('4', '6'):
            for table in ('mangle', 'nat', 'filter'):
                for name in ('HETU_DNSOUT', 'HETU_DNSPRE', 'HETU_DOTOUT'):
                    self.assertFalse([s for s in (self.chain(fam, table, name) or []) if 'hetu_cn' in s], (fam, table, name))

    # ------------------------------------------------------------------ ipset
    def test_default_policy_with_ipset_bypasses_only_udp(self):
        self.udp_policy()
        self.start_with()
        session = self.session()
        self.assertIn('CNIP=ipset\n', session)
        self.assertIn('CNIP_TCPONLY=0\nCNIP_UDPONLY=1\n', session)
        self.assertIn('-p udp -m set --match-set hetu_cn4 dst -j RETURN', self.chain('4', 'mangle', 'HETU_MOUT'))
        self.assert_udp_only()
        st = self.status()
        self.assertEqual(('ipset', True, False), (st['cnip'], st['cnipUdpOnly'], st['cnipTcpOnly']))
        self.assertGreater(st['cnipV4Entries'], 1000)
        self.assertFalse(self.pins.exists(), 'ipset needs no pin')
        self.stop(); self.assert_clean(); self.assert_no_ebpf()

    # ------------------------------------------------------------------ eBPF backend
    def test_without_ipset_the_ebpf_cn_programs_bypass_udp(self):
        self.udp_policy()
        self.no_ipset()
        self.start_with()
        session = self.session()
        self.assertIn('CNIP=ebpf\nCNIP_V4=1\nCNIP_V6=1\n', session)
        self.assertIn('CNIP_UDPONLY=1\n', session)
        self.assertIn('-p udp -m bpf --object-pinned %s/prog_hetu_cn4 -j RETURN' % self.pins, self.chain('4', 'mangle', 'HETU_MOUT'))
        self.assertIn('-p udp -m bpf --object-pinned %s/prog_hetu_cn6 -j RETURN' % self.pins, self.chain('6', 'mangle', 'HETU_MOUT'))
        self.assertGreater(len(self.maps()['hetu_cn4']), 1000)
        self.assertGreater(len(self.maps()['hetu_cn6']), 500)
        self.assertFalse([l for l in self.all_rules() if '--match-set' in l or 'HETU_CN' in l], 'no ipset and no iptables tree')
        self.assert_udp_only()
        st = self.status()
        self.assertEqual(('ebpf', True), (st['cnip'], st['cnipUdpOnly']))
        self.assertEqual(len(self.maps()['hetu_cn4']), st['cnipV4Entries'])
        self.assertEqual('off', st['ebpf'], 'the opt-in matcher stays off')
        calls = self.read(str(self.fix) + '.calls')
        self.assertEqual(1, calls.count('load '), calls)
        self.stop(); self.assert_clean(); self.assert_no_ebpf()

    def test_the_users_full_bypass_uses_the_ebpf_programs_for_tcp_and_udp(self):
        self.policy()
        self.no_ipset()
        self.start_with()
        self.assertIn('CNIP=ebpf\n', self.session())
        self.assertIn('-m bpf --object-pinned %s/prog_hetu_cn4 -j RETURN' % self.pins, self.chain('4', 'mangle', 'HETU_MOUT'))
        self.assertIsNone(self.verdict('4', 10123, '114.114.114.114', 'tcp'))
        self.assertIsNone(self.verdict('4', 10123, '114.114.114.114', 'udp'))
        self.assertEqual('proxy', self.verdict('4', 10123, '8.8.8.8', 'tcp'))
        self.assertFalse(self.status()['cnipUdpOnly'])
        self.stop(); self.assert_clean(); self.assert_no_ebpf()

    def test_the_matcher_and_the_cn_programs_share_one_load(self):
        self.enable()
        self.udp_policy()
        self.no_ipset()
        self.start_with()
        self.assertEqual('active', self.status()['ebpf'])
        self.assertEqual('ebpf', self.status()['cnip'])
        calls = self.read(str(self.fix) + '.calls')
        self.assertEqual(1, calls.count('load '), calls)
        self.assertIn('sync-cn', calls)
        self.assertTrue(any('prog_hetu_uid_direct' in l for l in self.all_rules()))
        self.assert_udp_only()
        self.stop(); self.assert_clean(); self.assert_no_ebpf()

    def test_kill_switch_guard_never_references_a_cn_pin(self):
        self.udp_policy()
        self.no_ipset()
        self.start_with(kill='1')
        self.assertIn('CNIP=ebpf\n', self.session())
        for fam in ('4', '6'):
            for name, specs in self.model()['xt'][fam]['filter']['rules'].items():
                if name.startswith(('HETU_KOUT', 'HETU_KFWD')):
                    self.assertFalse([s for s in specs if '-m bpf' in s], (fam, name, specs))
        self.assertTrue(self.cn_rules(), 'the capture chains still bypass CN UDP')

    def test_cnip_reload_resyncs_the_maps_and_bad_data_keeps_them(self):
        self.udp_policy()
        self.no_ipset()
        self.start_with()
        before = self.maps()['hetu_cn4']
        cn4 = self.base / 'run/ruleset/hetu-cn-v4.txt'
        cn4.write_text(cn4.read_text() + '\n203.0.113.0/24\n')
        r = self.run_cmd('cnip-reload')
        self.assertEqual({'ok': True, 'reloaded': True, 'cnip': 'ebpf'}, {k: json.loads(r.stdout.splitlines()[-1])[k] for k in ('ok', 'reloaded', 'cnip')})
        self.assertIn('203.0.113.0/24', self.maps()['hetu_cn4'])
        self.assertEqual(len(before) + 1, len(self.maps()['hetu_cn4']))
        syncs = self.read(str(self.fix) + '.calls').count('sync-cn')
        r = self.run_cmd('cnip-reload')
        self.assertFalse(json.loads(r.stdout.splitlines()[-1])['reloaded'], 'unchanged data is not synced again')
        self.assertEqual(syncs, self.read(str(self.fix) + '.calls').count('sync-cn'))
        cn4.write_text('1.0.1.0/24\n')
        r = self.run_cmd('cnip-reload')
        self.assertFalse(json.loads(r.stdout.splitlines()[-1])['ok'])
        self.assertEqual(len(before) + 1, len(self.maps()['hetu_cn4']), 'invalid data keeps the map')
        watchdog = re.search(r'^watchdog\(\)\{\n.*?^\}\n', (SRC / 'assets/hetu-root.sh').read_text(), re.S | re.M).group(0)
        self.assertIn('ipset|iptables|ebpf) "$0" cnip-reload auto', watchdog)

    # ------------------------------------------------------------------ AUTO
    def test_the_default_runs_without_it_where_the_kernel_cannot(self):
        for case in ('no-loader', 'load-fails', 'xt-bpf-refused'):
            with self.subTest(case=case):
                self.setUp_case(case)
                self.udp_policy()
                self.start_with()
                session = self.session()
                self.assertIn('CNIP=off\n', session, case)
                self.assertRegex(session, r'CNIP_REASON=ipset-unavailable[^\n]*\n')
                self.assertIn('CNIP_UDPONLY=0\n', session)
                self.assertEqual([], self.cn_rules())
                self.assertEqual('proxy', self.verdict('4', 10123, '114.114.114.114', 'udp'), 'UDP goes through the core as before')
                st = self.status()
                self.assertEqual(('off', False), (st['cnip'], st['cnipUdpOnly']))
                self.stop(); self.assert_clean(); self.assert_no_ebpf()

    def setUp_case(self, case):
        for p in (str(self.fix) + '.load-fail', str(self.fix) + '.calls'):
            Path(p).unlink(missing_ok=True)
        (self.dir / 'bpf-fixture.nobpf').unlink(missing_ok=True)
        self.no_ipset()
        if case == 'no-loader' and (self.base / 'bin/hetu-bpf').exists():
            self.no_loader()
        elif case == 'load-fails':
            Path(str(self.fix) + '.load-fail').write_text('selinux-denied')
        elif case == 'xt-bpf-refused':
            Path(str(self.fix) + '.nobpf').write_text('')
        if case != 'no-loader' and not (self.base / 'bin/hetu-bpf').exists():
            loader = self.base / 'bin/hetu-bpf'
            loader.write_text('#!/bin/sh\nexec %s %s "$@"\n' % (sys.executable, self.dir / 'fakebpf.py')); loader.chmod(0o700)

    def test_the_users_full_bypass_still_reports_degraded(self):
        self.policy()
        self.no_ipset()
        Path(str(self.fix) + '.load-fail').write_text('selinux-denied')
        self.start_with()
        session = self.session()
        self.assertIn('CNIP=degraded\n', session)
        self.assertIn('bpf-selinux-denied', session)
        self.assertEqual('degraded', self.status()['cnip'])
        self.stop(); self.assert_clean(); self.assert_no_ebpf()


# Only the tests above: the inherited CNIP and matcher suites run from their own files.
for _name in dir(eb.Ebpf93):
    if _name.startswith('test') and _name not in CnUdp600.__dict__:
        setattr(CnUdp600, _name, None)


class AppSide(unittest.TestCase):
    def test_the_app_writes_the_udp_policy_by_default_and_deploys_the_object(self):
        manager = (SRC / 'java/io/github/xgl34222220/hetu/RootProxyManager.java').read_text()
        self.assertIn('boolean cnUdp=!(cnipV4||cnipV6)&&prefs.getBoolean(CnipFakeIp.PREF_UDP_DIRECT,true);', manager)
        self.assertIn('if(ebpf||!cnipBody.isEmpty())appendEbpfPayloads(cmd,stage,deploySuffix);', manager)
        cnip = (SRC / 'java/io/github/xgl34222220/hetu/app/CnipFakeIp.kt').read_text()
        self.assertIn('"V4=1\\nV6=1\\nFORCE=$forceUids\\nTCPONLY=0\\nUDPONLY=1\\nAUTO=1\\n"', cnip)
        self.assertIn('const val PREF_UDP_DIRECT = "proxyCnIpUdpDirect"', cnip)

    def test_the_status_line_and_session_carry_the_udp_only_flag(self):
        text = (SRC / 'assets/hetu-root.sh').read_text()
        self.assertIn('"cnipTcpOnly":%s,"cnipUdpOnly":%s', text)
        self.assertIn('CNIP_TCPONLY=%s\\nCNIP_UDPONLY=%s\\n', text)
        start = re.search(r'^start\(\)\{\n.*?^\}\n', text, re.S | re.M).group(0)
        self.assertLess(start.index('ebpf_prepare'), start.index('cnip_bpf_install'))
        self.assertLess(start.index('cnip_bpf_install'), start.index('install_capture_rules'))


if __name__ == '__main__':
    unittest.main()
