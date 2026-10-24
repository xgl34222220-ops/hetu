#!/usr/bin/env python3
"""Built-in non-Mihomo core templates (BundledCoreTemplates.java) and speed-test budget host checks.

No network, Root or device. The templates are read straight from the shipped Java source:

* each template parses (JSON for sing-box / Xray / V2Fly, YAML structure for Hysteria);
* its ingress uses the runtime's TPROXY / Redirect / DNS ports and sing-box's clash_api uses the
  default controller port (all from MihomoStartupConfig.java, which RootProxyManager passes to
  hetu-root.sh);
* every reference resolves (outbound / rule-set / DNS server tags), China traffic is routed direct,
  and the only server is the reserved example.invalid placeholder (no real server or credential);
* the library lists the template without selecting it, and built-ins cannot be renamed or deleted;
* picking a non-Mihomo core with no config of its own selects its template (user decision 2026-10-12);
   Mihomo / Smart picks never do;
* the wave budget scales with node count while keeping the 20 s floor and six-request concurrency.

When HETU_CORE_BIN_DIR holds sing-box / xray / v2ray / hysteria binaries for this host, each template
is also checked by the core itself (sing-box check, xray run -test, v2ray test, Hysteria client parse).
Local evidence of that run: docs/qa/20261012-speedtest-core-templates.md.
"""
from pathlib import Path
import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
PKG = REPO / 'android-app/app/src/main/java/io/github/xgl34222220/hetu'
TEMPLATES = PKG / 'BundledCoreTemplates.java'
PLACEHOLDER = 'example.invalid'


def java_constant(name, source=None):
    source = source if source is not None else (PKG / 'MihomoStartupConfig.java').read_text()
    match = re.search(r'static final int ' + name + r'\s*=\s*(\d+)\s*;', source)
    assert match, name
    return int(match.group(1))


def templates():
    text = TEMPLATES.read_text()
    found = dict(re.findall(r'static final String (SING_BOX|XRAY|V2FLY|HYSTERIA) = """\n(.*?)""";', text, re.S))
    assert set(found) == {'SING_BOX', 'XRAY', 'V2FLY', 'HYSTERIA'}, sorted(found)
    return found


def simple_yaml(text):
    """Top-level keys and one nested level of the flat Hysteria template (no PyYAML on the runner)."""
    root, current = {}, None
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        assert '\t' not in line, 'tabs are invalid YAML indentation'
        key, sep, value = line.strip().partition(':')
        assert sep, 'not a mapping line: ' + line
        if not line.startswith(' '):
            current = key
            root[key] = value.strip() if value.strip() else {}
        else:
            assert isinstance(root[current], dict), 'nested value under scalar ' + current
            root[current][key] = value.strip()
    return root


class CoreTemplates93Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.t = templates()
        cls.ports = {name: java_constant(name) for name in ('TPROXY_PORT', 'REDIRECT_PORT', 'DNS_PORT', 'CONTROLLER_PORT')}

    def test_runtime_ports_are_the_ones_hetu_root_receives(self):
        self.assertEqual(self.ports, {'TPROXY_PORT': 19898, 'REDIRECT_PORT': 19797, 'DNS_PORT': 11053, 'CONTROLLER_PORT': 29090})
        manager = (PKG / 'RootProxyManager.java').read_text()
        for token in ('o.tproxyPort=MihomoStartupConfig.TPROXY_PORT', 'o.redirectPort=MihomoStartupConfig.REDIRECT_PORT',
                      'MihomoStartupConfig.DNS_PORT'):
            self.assertIn(token, manager)

    def test_sing_box_template_is_complete_and_self_consistent(self):
        root = json.loads(self.t['SING_BOX'])
        inbounds = {item['tag']: item for item in root['inbounds']}
        self.assertEqual(inbounds['hetu-tproxy'], {'type': 'tproxy', 'tag': 'hetu-tproxy', 'listen': '::', 'listen_port': self.ports['TPROXY_PORT']})
        self.assertEqual(inbounds['hetu-redirect']['listen_port'], self.ports['REDIRECT_PORT'])
        self.assertEqual(inbounds['hetu-dns-in']['listen_port'], self.ports['DNS_PORT'])
        tags = [item['tag'] for item in root['outbounds']]
        self.assertEqual(len(tags), len(set(tags)))
        for item in root['outbounds']:
            for member in item.get('outbounds', []):
                self.assertIn(member, tags)
            if 'server' in item:
                self.assertEqual(item['server'], PLACEHOLDER)
        route = root['route']
        self.assertIn(route['final'], tags)
        sets = {item['tag'] for item in route['rule_set']}
        for rule in route['rules'] + root['dns']['rules']:
            for name in rule.get('rule_set', []):
                self.assertIn(name, sets)
            if 'outbound' in rule:
                self.assertIn(rule['outbound'], tags)
        self.assertTrue(any(rule.get('outbound') == 'DIRECT' and 'geoip-cn' in rule.get('rule_set', []) for rule in route['rules']))
        self.assertTrue(any(rule.get('ip_is_private') for rule in route['rules']))
        servers = {item['tag'] for item in root['dns']['servers']}
        self.assertIn(root['dns']['final'], servers)
        self.assertIn(route['default_domain_resolver'], servers)
        for item in root['dns']['servers']:
            self.assertIn(item.get('detour', 'DIRECT'), tags)
        for item in route['rule_set']:
            self.assertIn(item['download_detour'], tags)
        self.assertEqual(root['experimental']['clash_api']['external_controller'], '127.0.0.1:%d' % self.ports['CONTROLLER_PORT'])

    def test_xray_and_v2fly_templates_route_china_direct_through_placeholder(self):
        for name, protocol in (('XRAY', 'vless'), ('V2FLY', 'vmess')):
            root = json.loads(self.t[name])
            ports = {item['tag']: item['port'] for item in root['inbounds']}
            self.assertEqual(ports, {'hetu-tproxy': self.ports['TPROXY_PORT'], 'hetu-redirect': self.ports['REDIRECT_PORT'],
                                     'hetu-dns-in': self.ports['DNS_PORT']}, name)
            tags = [item['tag'] for item in root['outbounds']]
            self.assertEqual(len(tags), len(set(tags)))
            self.assertEqual(root['outbounds'][0]['protocol'], protocol, 'first outbound is the default route')
            self.assertIn('hetu-dns-out', tags)
            for item in root['outbounds']:
                for server in item.get('settings', {}).get('vnext', []):
                    self.assertEqual(server['address'], PLACEHOLDER)
                    for user in server['users']:
                        self.assertEqual(user['id'], '00000000-0000-0000-0000-000000000000')
            rules = root['routing']['rules']
            for rule in rules:
                self.assertIn(rule['outboundTag'], tags)
            self.assertIn({'type': 'field', 'domain': ['geosite:cn'], 'outboundTag': 'DIRECT'}, rules)
            self.assertIn({'type': 'field', 'ip': ['geoip:cn'], 'outboundTag': 'DIRECT'}, rules)
            self.assertIn({'type': 'field', 'ip': ['geoip:private'], 'outboundTag': 'DIRECT'}, rules)

    def test_hysteria_template_is_a_single_server_client_without_owned_listeners(self):
        root = simple_yaml(self.t['HYSTERIA'])
        self.assertEqual(root['server'], PLACEHOLDER + ':443')
        self.assertEqual(root['tls']['sni'], PLACEHOLDER)
        owned = re.search(r'HYSTERIA_OWNED = new LinkedHashSet<>\(Arrays.asList\((.*?)\)\);',
                          (PKG / 'ProxyCoreConfig.java').read_text(), re.S).group(1)
        for key in re.findall(r'"(\w+)"', owned):
            self.assertNotIn(key, root, 'Hetu writes the %s listener itself' % key)
        for forbidden in ('proxies:', 'proxy-groups:', 'rules:'):
            self.assertNotIn(forbidden, self.t['HYSTERIA'], 'must not look like a Clash profile')

    def test_no_real_server_secret_or_private_subscription(self):
        joined = '\n'.join(self.t.values())
        hosts = set(re.findall(r'"(?:server|address)"\s*:\s*"([^"]+)"', joined))
        # 'dns-direct' is a sing-box DNS rule naming its server tag, not a host.
        self.assertLessEqual(hosts, {PLACEHOLDER, '223.5.5.5', '1.1.1.1', 'dns-direct'})
        self.assertNotRegex(joined, r'https?://(?!raw\.githubusercontent\.com/SagerNet/)')
        self.assertEqual(set(re.findall(r'"secret"\s*:\s*"([^"]*)"', joined)), {''})

    def test_library_lists_without_selecting_and_protects_built_ins(self):
        library = (PKG / 'ProxyConfigLibrary.java').read_text()
        body = re.search(r'private void ensureCoreTemplate\(.*?\n    }\n', library, re.S).group(0)
        self.assertNotIn('putString', body, 'a template must never become the selection implicitly')
        self.assertIn('if(!target.isFile())', body, 'a user-edited template is never overwritten')
        self.assertIn('if(isBundled(e.name))throw new IOException("内置配置不能删除', library)
        self.assertIn('if(isBundled(e.name))throw new IOException("内置配置不能重命名', library)
        manager = (PKG / 'RootProxyManager.java').read_text()
        self.assertIn('if(selected==null)selected=configs.selected(ProxyRuntimeProfile.Core.MIHOMO);', manager)
        source = TEMPLATES.read_text()
        for core, name in (('SING_BOX', 'sing-box.json'), ('XRAY', 'Xray.json'), ('V2FLY', 'V2Fly.json'), ('HYSTERIA', 'Hysteria.yaml')):
            self.assertIn('static final String %s_NAME = "河图内置-%s";' % (core, name), source)

    def test_core_pick_adopts_template_only_for_non_mihomo_without_own_selection(self):
        library = (PKG / 'ProxyConfigLibrary.java').read_text()
        body = re.search(r'String adoptTemplateOnCoreSelect\(.*?\n    }\n', library, re.S).group(0)
        self.assertIn('core==ProxyRuntimeProfile.Core.MIHOMO||core==ProxyRuntimeProfile.Core.MIHOMO_SMART)return null;', body)
        self.assertIn('selected(core)!=null)return null;', body, 'an existing own selection is kept')
        self.assertIn('select(core,name)', body)
        settings = (PKG / 'app/SettingsScreen.kt').read_text()
        pick = settings.index('ProxyConfigLibrary(context).adoptTemplateOnCoreSelect(picked)')
        self.assertLess(pick, settings.index('putString("proxyBaseCore", it)', pick), 'template selected before the core is saved')
        # 5.1.0: a mode the picked core cannot run in (TUN / Enhance / eBPF) becomes TPROXY with the pick.
        self.assertLess(settings.index('ProxyRuntimeProfile.modeForPick(prefs, picked, profile.mode)', pick), settings.index('putString("proxyBaseCore", it)', pick))
        controller = (PKG / 'ProxyComposeController.kt').read_text()
        self.assertIn('configs.adoptTemplateOnCoreSelect(core)', controller)
        self.assertIn('ProxyRuntimeProfile.modeForPick(prefs, core, ProxyRuntimeProfile.load(prefs).mode)', controller)

    def test_wave_budget_scales_and_keeps_floor_and_concurrency(self):
        budget = (PKG / 'LatencyProbeBudget.java').read_text()
        constants = {k: int(v.replace('_', '')) for k, v in re.findall(r'static final (?:long|int) (\w+) = ([\d_]+);', budget)}
        slot = constants['NODE_TIMEOUT_MS'] + 1500
        def wave(n):
            if n <= 0: return constants['DEFAULT_TIMEOUT_MS']
            rounds = -(-n // constants['WAVE_CONCURRENCY'])
            first = 4000 + rounds * slot
            return max(constants['DEFAULT_TIMEOUT_MS'], min(constants['MAX_WAVE_TIMEOUT_MS'], first + max(slot, first // 2)))
        self.assertEqual(constants['DEFAULT_TIMEOUT_MS'], 20000)
        self.assertEqual(constants['WAVE_CONCURRENCY'], 6)
        self.assertEqual(wave(6), 20000)
        self.assertGreater(wave(60), 10 * constants['NODE_TIMEOUT_MS'])
        self.assertIn('private static final long LEAF_SLOT_MS = NODE_TIMEOUT_MS + 1_500;', budget)
        self.assertIn('private static final long METADATA_MS = 4_000;', budget)
        repo = (PKG / 'ProxyDashboardRepository.kt').read_text()
        self.assertIn('latencyProbeOperation(LatencyProbeBudget.waveTimeoutMs(targetHint))', repo)
        self.assertIn('latencyProbeOperation(LatencyProbeBudget.waveTimeoutMs(targets.distinct().size))', repo)
        self.assertIn('List(minOf(LatencyProbeBudget.WAVE_CONCURRENCY, nodes.size))', repo)
        vm = (PKG / 'app/HetuViewModel.kt').read_text()
        self.assertIn('repo.globalDelay(targets.size)', vm)


@unittest.skipUnless(os.environ.get('HETU_CORE_BIN_DIR'), 'HETU_CORE_BIN_DIR not set: core self-checks skipped')
class CoreSelfCheck93Test(unittest.TestCase):
    def setUp(self):
        self.bin = Path(os.environ['HETU_CORE_BIN_DIR'])
        self.dir = Path(tempfile.mkdtemp(prefix='hetu-templates-'))
        self.t = templates()

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def write(self, name, text):
        path = self.dir / name
        path.write_text(text)
        return path

    def run_core(self, args, env=None, timeout=30):
        return subprocess.run(args, capture_output=True, text=True, timeout=timeout, env={**os.environ, **(env or {})})

    def test_sing_box_check(self):
        result = self.run_core([str(self.bin / 'sing-box'), 'check', '-c', str(self.write('sb.json', self.t['SING_BOX']))])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_xray_run_test(self):
        result = self.run_core([str(self.bin / 'xray'), 'run', '-test', '-c', str(self.write('xray.json', self.t['XRAY']))],
                               {'XRAY_LOCATION_ASSET': str(self.bin)})
        self.assertIn('Configuration OK', result.stdout + result.stderr)

    def test_v2ray_test(self):
        result = self.run_core([str(self.bin / 'v2ray'), 'test', '-c', str(self.write('v2.json', self.t['V2FLY']))],
                               {'V2RAY_LOCATION_ASSET': str(self.bin)})
        self.assertIn('Configuration OK', result.stdout + result.stderr)

    def test_hysteria_client_parses(self):
        text = self.t['HYSTERIA'].replace(PLACEHOLDER + ':443', '127.0.0.1:9')
        try:
            result = self.run_core([str(self.bin / 'hysteria'), 'client', '-c', str(self.write('hy.yaml', text))], timeout=4)
            output = result.stdout + result.stderr
        except subprocess.TimeoutExpired as running:
            output = (running.stdout or b'').decode() + (running.stderr or b'').decode()
        self.assertNotIn('invalid config', output)
        self.assertNotIn('failed to read', output)


if __name__ == '__main__':
    unittest.main()
