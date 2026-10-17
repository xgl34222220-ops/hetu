#!/usr/bin/env python3
"""WeChat message-latency host regression for hetu-root.sh (2026-10-13).

No real network, firewall, ipset or Root operation: the shipped hetu-root.sh runs against the
stateful netfilter model (netfilter_model93.py) and the ipset model of test_cnip93.py.

The opt-in 「微信直连（绕过核心）」 switch hands WeChat's UID to hetu-root.sh as a Root direct UID.
These checks pin what that has to mean in the kernel, for the long link that carries WeChat pushes:

* the WeChat UID RETURNs before any TPROXY mark (TCP and UDP), before the QUIC reject and in the
  IPv6 guard, so no WeChat flow reaches the core; every other app is still proxied;
* a fake-ip destination from the WeChat UID still reaches the core (a cached fake address would
  otherwise be black-holed), which is why the runtime copy also gives WeChat names real addresses;
* without the switch the CN ipset bypass catches WeChat's mainland servers but not its Hong Kong /
  Singapore servers (not CN addresses), which stay on the core path;
* stop leaves no HETU_ chain and no direct-UID rule behind.
"""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import test_cnip93 as cnip  # noqa: E402

WECHAT_UID = 10234
OTHER_UID = 10123
# 114.114.114.114 is in the shipped CN snapshot; 43.154.240.240 is a Tencent Cloud Hong Kong address.
CN_ADDR, HK_ADDR, FOREIGN = '114.114.114.114', '43.154.240.240', '8.8.8.8'


class WechatRoot93(unittest.TestCase):
    # Reuse the CNIP harness (fake core, netfilter + ipset models) without inheriting its tests.
    setUp = cnip.Cnip93.setUp
    tearDown = cnip.Cnip93.tearDown
    policy = cnip.Cnip93.policy
    stop = cnip.Cnip93.stop
    read = staticmethod(cnip.Cnip93.read)
    model = cnip.Cnip93.model
    sets = cnip.Cnip93.sets
    all_rules = cnip.Cnip93.all_rules
    chain = cnip.Cnip93.chain
    assert_clean = cnip.Cnip93.assert_clean

    def start_with(self, direct_uids=''):
        args = ['start', str(self.base / 'bin/core'), str(self.base / 'run/state/startup-config'), 'tproxy', str(self.tp), '0', 'enable', '1', '1',
                'redirect', '1', str(self.dp), str(self.cp), 'all', '', '0', '0', '', '', direct_uids, '1', '1', '', '', '1', '1', '0', '', '', '', '0']
        r = cnip.subprocess.run(['sh', str(self.script)] + args, env=self.env, capture_output=True, text=True, timeout=90)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr + self.read(self.base / 'run/start.error'))
        return r

    def verdict(self, uid, dst, proto='tcp', table='mangle', fam='4', dport=443):
        rules = self.model()['xt'][fam][table]['rules']
        return cnip.Evaluator(rules, self.sets()).run('OUTPUT', cnip.Packet(uid, dst, proto, dport))

    def test_direct_wechat_uid_never_reaches_the_core_and_others_still_do(self):
        self.start_with(str(WECHAT_UID))
        for proto, port in (('tcp', 443), ('tcp', 8080), ('tcp', 80), ('udp', 443)):
            self.assertIsNone(self.verdict(WECHAT_UID, FOREIGN, proto, dport=port), (proto, port))
            self.assertIsNone(self.verdict(WECHAT_UID, HK_ADDR, proto, dport=port), (proto, port))
            self.assertEqual('proxy', self.verdict(OTHER_UID, FOREIGN, proto, dport=port), (proto, port))
        mout = self.chain('4', 'mangle', 'HETU_MOUT')
        owner = [i for i, s in enumerate(mout) if '--uid-owner %d' % WECHAT_UID in s and '-j RETURN' in s]
        marks = [i for i, s in enumerate(mout) if '-j MARK' in s]
        self.assertTrue(owner and marks and owner[0] < marks[0], mout)
        # The QUIC reject (UDP 443) does not apply to the bypassed UID either.
        quic = self.chain('4', 'filter', 'HETU_QUICOUT')
        if quic is not None:
            self.assertIsNone(self.verdict(WECHAT_UID, FOREIGN, 'udp', table='filter'))
            self.assertEqual('reject', self.verdict(OTHER_UID, FOREIGN, 'udp', table='filter'))

    def test_fake_ip_destination_from_wechat_still_reaches_the_core(self):
        self.start_with(str(WECHAT_UID))
        mout = self.chain('4', 'mangle', 'HETU_MOUT')
        self.assertTrue(any('! -d 198.18.0.1/16' in s and '--uid-owner %d' % WECHAT_UID in s for s in mout), mout)
        self.assertEqual('proxy', self.verdict(WECHAT_UID, '198.18.3.7'))
        self.assertIsNone(self.verdict(WECHAT_UID, FOREIGN))

    def test_cn_ipset_covers_mainland_servers_but_not_hong_kong(self):
        self.policy()
        self.start_with('')
        self.assertIsNone(self.verdict(WECHAT_UID, CN_ADDR))
        self.assertEqual('proxy', self.verdict(WECHAT_UID, HK_ADDR))
        self.assertEqual('proxy', self.verdict(WECHAT_UID, FOREIGN))

    def test_stop_removes_direct_uid_rules(self):
        self.start_with(str(WECHAT_UID))
        self.assertTrue(any('--uid-owner %d' % WECHAT_UID in l for l in self.all_rules()))
        self.stop()
        self.assertFalse(any('--uid-owner %d' % WECHAT_UID in l for l in self.all_rules()))
        self.assert_clean()


if __name__ == '__main__':
    unittest.main()
