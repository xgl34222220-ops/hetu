#!/usr/bin/env python3
"""Ad-block repair host regression (PR #33 audit fixes).

No real network, firewall, ipset, Root or Android. Covers:

* DNS layer: the shipped pure-Java AdblockDnsPolicy is compiled and run against YAML fixtures:
  rule-set:hetu-adblock-dns -> rcode://success is the FIRST nameserver-policy entry (Mihomo
  ab405bad evaluates the ordered map first-match), fake-ip blacklist / rule / whitelist handling,
  inline forms left untouched, the DNS set skips parents and children of allowed domains, and the
  subscription sniffer only gains parse-pure-ip when enable: true and the key is absent.
* CNIP "TCP only" (default off): with TCPONLY=1 only TCP to CN addresses RETURNs, UDP/QUIC to CN
  still reaches the proxy; default behaviour is byte-for-byte the previous rule set. Uses the
  stateful netfilter/ipset fixtures of test_cnip93.
* core.log rotation in the watchdog: above 8 MiB the newest 1 MiB is kept as core.log.1 and
  core.log is truncated in place (the core appends with O_APPEND).
* Source guards: 拦截实测 is a raw CONNECT judged by controller hitCount (no cleartext
  HttpURLConnection), the service counter reads controller hitCount before any su log read,
  both ad-block pages share ToolsRuntimeBridge.adblockChain, all three deploy paths ship the DNS set.
"""
from pathlib import Path
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import test_cnip93  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
SRC = REPO / 'android-app/app/src/main'
PKG = SRC / 'java/io/github/xgl34222220/hetu'
ROOT_SCRIPT = SRC / 'assets/hetu-root.sh'

DRIVER = r'''package io.github.xgl34222220.hetu;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
public class AdblockDnsDriver {
  public static void main(String[] a) throws Exception {
    String in = new String(Files.readAllBytes(Paths.get(a[1])), StandardCharsets.UTF_8);
    switch (a[0]) {
      case "inject": { AdblockDnsPolicy.Result r = AdblockDnsPolicy.inject(in);
        System.out.print((r.injected ? "1" : "0") + "\u0001" + r.fakeIp + "\u0001" + r.reason + "\u0001" + r.yaml); break; }
      case "sniffer": System.out.print(AdblockDnsPolicy.snifferState(in) + "\u0001" + AdblockDnsPolicy.ensureParsePureIp(in)); break;
      case "domains": { String[] parts = in.split("\\|", -1);
        List<String> block = Arrays.asList(parts[0].split(",")), allow = parts[1].isEmpty() ? new ArrayList<>() : Arrays.asList(parts[1].split(","));
        System.out.print(String.join(",", AdblockDnsPolicy.dnsBlockDomains(block, allow))); break; }
      case "mode": System.out.print(AdblockDnsPolicy.enhancedMode(in) + "\u0001" + AdblockDnsPolicy.isInjected(in)); break;
    }
  }
}
'''


def java_tool(name):
    home = os.environ.get('JAVA_HOME')
    if home and (Path(home) / 'bin' / name).exists():
        return str(Path(home) / 'bin' / name)
    return shutil.which(name)


class AdblockDnsPolicyHost(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.jdir = tempfile.TemporaryDirectory(prefix='hetu-adblock-dns-')
        javac, java = java_tool('javac'), java_tool('java')
        if not javac or not java:
            raise unittest.SkipTest('host JDK required to execute AdblockDnsPolicy')
        drv = Path(cls.jdir.name) / 'AdblockDnsDriver.java'; drv.write_text(DRIVER)
        r = subprocess.run([javac, '-encoding', 'UTF-8', '-d', cls.jdir.name, str(PKG / 'AdblockDnsPolicy.java'), str(drv)],
                           capture_output=True, text=True)
        assert r.returncode == 0, r.stderr
        cls.java = java

    @classmethod
    def tearDownClass(cls):
        cls.jdir.cleanup()

    def call(self, mode, text):
        f = Path(self.jdir.name) / 'in.txt'; f.write_text(text)
        r = subprocess.run([self.java, '-cp', self.jdir.name, 'io.github.xgl34222220.hetu.AdblockDnsDriver', mode, str(f)],
                           capture_output=True, text=True, timeout=60)
        self.assertEqual(0, r.returncode, r.stderr)
        return r.stdout.split('\u0001')

    def inject(self, yaml):
        injected, fake, reason, out = self.call('inject', yaml)
        return injected == '1', fake, reason, out

    @staticmethod
    def dns_children(out, key):
        lines = out.split('\n'); start = lines.index('dns:')
        end = next((i for i in range(start + 1, len(lines)) if lines[i] and not lines[i].startswith((' ', '#'))), len(lines))
        block = lines[start + 1:end]
        at = next(i for i, l in enumerate(block) if l.strip().startswith(key + ':'))
        kid = len(block[at]) - len(block[at].lstrip())
        items = []
        for l in block[at + 1:]:
            if not l.strip(): continue
            ind = len(l) - len(l.lstrip())
            if ind < kid or (ind == kid and not l.strip().startswith('-')): break
            items.append(l.strip())
        return items

    def test_block_answer_is_first_policy_entry(self):
        ok, _, _, out = self.inject("dns:\n  enable: true\n  nameserver-policy:\n    'geosite:cn': 223.5.5.5\n  nameserver:\n    - 223.5.5.5\n")
        self.assertTrue(ok)
        self.assertEqual(["'rule-set:hetu-adblock-dns': 'rcode://success'", "'geosite:cn': 223.5.5.5"],
                         self.dns_children(out, 'nameserver-policy'))
        self.assertEqual('true', self.call('mode', out)[1])

    def test_missing_policy_is_created_and_no_dns_block_is_untouched(self):
        ok, _, _, out = self.inject("dns:\n  enable: true\n  nameserver:\n    - system\nrules:\n  - MATCH,DIRECT\n")
        self.assertTrue(ok)
        self.assertEqual(["'rule-set:hetu-adblock-dns': 'rcode://success'"], self.dns_children(out, 'nameserver-policy'))
        self.assertTrue(out.endswith('rules:\n  - MATCH,DIRECT\n'))
        ok, _, reason, out = self.inject('rules:\n  - MATCH,DIRECT\n')
        self.assertFalse(ok); self.assertEqual('no-dns', reason); self.assertEqual('rules:\n  - MATCH,DIRECT\n', out)

    def test_inline_forms_are_left_alone(self):
        for yaml, why in (("dns: {enable: true}\n", 'inline-dns'),
                          ("dns:\n  enable: true\n  nameserver-policy: {'geosite:cn': 1.1.1.1}\n", 'inline-nameserver-policy')):
            ok, _, reason, out = self.inject(yaml)
            self.assertFalse(ok); self.assertEqual(why, reason); self.assertEqual(yaml, out)

    def test_fake_ip_modes(self):
        _, fake, _, out = self.inject("dns:\n  enable: true\n  enhanced-mode: fake-ip\n  fake-ip-filter:\n    - '*.lan'\n")
        self.assertEqual('fake-ip-filter', fake)
        self.assertEqual(["- 'rule-set:hetu-adblock-dns'", "- '*.lan'"], self.dns_children(out, 'fake-ip-filter'))
        _, fake, _, out = self.inject("dns:\n  enable: true\n  enhanced-mode: fake-ip\n  fake-ip-filter-mode: rule\n  fake-ip-filter:\n  - MATCH,fake-ip\n")
        self.assertEqual('fake-ip-rule', fake)
        self.assertEqual(['- RULE-SET,hetu-adblock-dns,real-ip', '- MATCH,fake-ip'], self.dns_children(out, 'fake-ip-filter'))
        _, fake, _, out = self.inject("dns:\n  enable: true\n  enhanced-mode: fake-ip\n  fake-ip-filter-mode: whitelist\n  fake-ip-filter:\n    - '+.g.test'\n")
        self.assertEqual('fake-ip-whitelist', fake); self.assertEqual(["- '+.g.test'"], self.dns_children(out, 'fake-ip-filter'))
        _, fake, _, out = self.inject("dns:\n  enable: true\n  enhanced-mode: fake-ip\n")
        self.assertEqual(["- 'rule-set:hetu-adblock-dns'", '- dns.msftnsci.com', '- www.msftnsci.com', '- www.msftconnecttest.com'],
                         self.dns_children(out, 'fake-ip-filter'))
        _, fake, _, out = self.inject("dns:\n  enable: true\n  enhanced-mode: fake-ip\n  fake-ip-filter: ['*.lan']\n")
        self.assertEqual('fake-ip-inline-skipped', fake); self.assertIn("fake-ip-filter: ['*.lan']", out)
        _, fake, _, out = self.inject("dns:\n  enable: true\n  enhanced-mode: redir-host\n")
        self.assertEqual('', fake); self.assertNotIn('fake-ip-filter', out)

    def test_dns_set_excludes_parents_and_children_of_allowed(self):
        got = self.call('domains', 'example.com,ads.example.com,tracker.test,cdn.ok.test,ok.test,+.plain.ad|good.ads.example.com,ok.test')[0]
        self.assertEqual('plain.ad,tracker.test', got)

    def test_sniffer_merge_respects_explicit_choices(self):
        state, out = self.call('sniffer', 'sniffer:\n  enable: true\n  sniff:\n    TLS:\n      ports: [443]\n')
        self.assertEqual('enabled', state); self.assertIn('  enable: true\n  parse-pure-ip: true\n', out)
        for yaml, want in (('sniffer:\n  enable: false\n', 'disabled'), ('sniffer:\n  enable: true\n  parse-pure-ip: false\n', 'pure-ip-off'),
                           ('sniffer:\n  force-dns-mapping: true\n', 'disabled'), ('rules: []\n', 'none')):
            state, out = self.call('sniffer', yaml)
            self.assertEqual(want, state); self.assertEqual(yaml, out)


class CnipTcpOnly93(test_cnip93.Cnip93):
    """Reuses the CNIP stateful fixtures; only the tests below are collected (see load_tests)."""

    def policy(self, v4=1, v6=1, force='', tcp_only=None):
        text = 'V4=%d\nV6=%d\nFORCE=%s\n' % (v4, v6, force)
        if tcp_only is not None: text += 'TCPONLY=%d\n' % tcp_only
        (self.base / 'policy/cnip').write_text(text)

    def test_tcp_only_keeps_cn_udp_in_the_core(self):
        self.policy(tcp_only=1)
        self.start()
        self.assertIn('CNIP_TCPONLY=1\n', self.read(self.base / 'run/session.state'))
        self.assertTrue(any('-p tcp -m set --match-set hetu_cn4 dst -j RETURN' in s for s in self.chain('4', 'mangle', 'HETU_MOUT')))
        self.assertIsNone(self.verdict('4', 10123, '114.114.114.114', 'tcp'))
        self.assertEqual('proxy', self.verdict('4', 10123, '114.114.114.114', 'udp'))
        self.assertEqual('proxy', self.verdict('4', 10123, '8.8.8.8', 'udp'))
        self.assertIs(True, self.status()['cnipTcpOnly'])
        self.stop(); self.assert_clean()

    def test_default_policy_is_unchanged(self):
        self.policy()
        self.start()
        self.assertIn('CNIP_TCPONLY=0\n', self.read(self.base / 'run/session.state'))
        mout = self.chain('4', 'mangle', 'HETU_MOUT')
        self.assertIn('-m set --match-set hetu_cn4 dst -j RETURN', mout)
        self.assertFalse(any('-p tcp -m set' in s for s in mout))
        self.assertIsNone(self.verdict('4', 10123, '114.114.114.114', 'udp'))
        self.assertIs(False, self.status()['cnipTcpOnly'])
        self.stop(); self.assert_clean()


def extract_function(script, name):
    start = script.index('\n' + name + '(){') + 1
    end = script.index('\n}\n', start) + 3
    return script[start:end]


class CoreLogRotation93(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='hetu-logrot-')
        self.dir = Path(self.temp.name)
        self.script = ROOT_SCRIPT.read_text()

    def tearDown(self):
        self.temp.cleanup()

    def rotate(self, size):
        log = self.dir / 'core.log'
        with open(log, 'wb') as f:
            f.write(b'a' * (size - 1048576 if size > 1048576 else 0)); f.write(b'b' * min(size, 1048576))
        body = 'LOG=%s\nWATCHDOG_LOG=%s\nLOG_ROTATE_BYTES=8388608\n%s\ncore_log_rotate\n' % (
            log, self.dir / 'watchdog.log', extract_function(self.script, 'core_log_rotate'))
        r = subprocess.run(['sh', '-c', body], capture_output=True, text=True, timeout=30)
        self.assertEqual(0, r.returncode, r.stderr)
        return log

    def test_large_log_is_truncated_keeping_the_newest_mebibyte(self):
        log = self.rotate(9 * 1048576)
        self.assertEqual(0, log.stat().st_size)
        kept = (self.dir / 'core.log.1').read_bytes()
        self.assertEqual(b'b' * 1048576, kept)
        self.assertIn('core.log rotated', (self.dir / 'watchdog.log').read_text())

    def test_small_log_is_untouched(self):
        log = self.rotate(4 * 1048576)
        self.assertEqual(4 * 1048576, log.stat().st_size)
        self.assertFalse((self.dir / 'core.log.1').exists())

    def test_watchdog_runs_rotation_and_core_appends(self):
        watchdog = extract_function(self.script, 'watchdog')
        self.assertIn('core_log_rotate;', watchdog)
        self.assertIn('>>"$LOG" 2>&1 &', self.script)


class AdblockSourceGuards93(unittest.TestCase):
    def read(self, rel):
        return (PKG / rel).read_text()

    def test_probe_is_a_connect_judged_by_controller_counters(self):
        v = self.read('app/AdblockVerification.kt')
        self.assertNotIn('import java.net.HttpURLConnection', v)
        self.assertNotIn('openConnection(', v)
        self.assertNotIn('URL("http://', v)
        self.assertIn('CONNECT $domain:443 HTTP/1.1', v)
        self.assertIn('verdictFromCounters(before, after, domain)', v)
        self.assertIn('fun dnsProbe(', v)
        self.assertIn('InetAddress.getAllByName(domain)', v)
        # Cleartext stays forbidden for everything but loopback (the root cause is not "fixed" by loosening it).
        nsc = (SRC / 'res/xml/network_security_config.xml').read_text()
        self.assertIn('cleartextTrafficPermitted="false"', nsc)

    def test_service_counter_reads_controller_before_su(self):
        s = self.read('ProxyNetworkMatchService.java')
        body = s[s.index('private void updateAdblockMetrics(){'):s.index('private long controllerAdblockHits(){')]
        self.assertLess(body.index('controllerAdblockHits()'), body.index('RootBridge.rootShell('))
        self.assertIn('if(controllerHits>=0&&!prefs.getBoolean("proxyAdblockUiVisible",false))return;', body)

    def test_both_pages_share_one_data_source(self):
        self.assertIn('ToolsRuntimeBridge.adblockChain(context)', self.read('app/AdblockScreen.kt'))
        self.assertIn('ToolsRuntimeBridge.adblockChain(app)', self.read('tools/ToolsFeatureAdapter.kt'))
        self.assertIn('chain.report', self.read('tools/ToolsFeatureAdapter.kt'))
        bridge = self.read('ToolsRuntimeBridge.kt')
        self.assertIn('hits = counted?.hitCount ?:', bridge)
        self.assertIn('missCount = counted?.missCount', bridge)
        self.assertNotIn('Settings.Global.putString', bridge)

    def test_every_deploy_path_ships_the_dns_set(self):
        m = self.read('RootProxyManager.java')
        self.assertEqual(3, m.count('AdblockDnsPolicy.FILE_NAME'))
        self.assertIn('loaded.has(AdblockDnsPolicy.PROVIDER_NAME)', m)
        self.assertIn('"TCPONLY="+bit(prefs.getBoolean("proxyCnIpKernelTcpOnly",false))', m)
        self.assertIn('prefs.getBoolean("proxyRecommendedDns",false)', m)
        cfg = self.read('MihomoStartupConfig.java')
        self.assertIn('injectAdblockProvider(yaml,dns.injected)', cfg)
        self.assertIn('AdblockDnsPolicy.ensureParsePureIp(yaml)', cfg)


def load_tests(loader, tests, pattern):
    suite = unittest.TestSuite()
    for cls in (AdblockDnsPolicyHost, CoreLogRotation93, AdblockSourceGuards93):
        suite.addTests(loader.loadTestsFromTestCase(cls))
    for name in ('test_tcp_only_keeps_cn_udp_in_the_core', 'test_default_policy_is_unchanged'):
        suite.addTest(CnipTcpOnly93(name))
    return suite


if __name__ == '__main__':
    unittest.main(verbosity=2)
