#!/usr/bin/env python3
"""JS override scripts (覆写脚本) host regression: the shipped worker and page JavaScript under Node.

No Android, WebView, Root or network. The exact WORKER_JS / HARNESS_JS constants are extracted from
WebViewOverrideEngine.kt and evaluated by Node; the page's Web Worker is shimmed with
node:worker_threads so the real timeout/terminate path runs.

* main(config, profile) receives the config object and profile (or null) and its returned mapping
  is the result; async main is awaited; console output is collected (bounded).
* A missing main, an exception, a non-mapping result and an oversized result are errors.
* An endless loop is terminated by the page at the script's timeout, and the page keeps working.
* Every shipped example (add rule, set DNS, merge proxies, sing-box rule) produces the documented change.
* The Kotlin start path keeps the gate: no new dependency, native library or manifest entry.
"""
from pathlib import Path
import json
import re
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
SRC = REPO / 'android-app/app/src/main/java/io/github/xgl34222220/hetu'
ENGINE = SRC / 'app/WebViewOverrideEngine.kt'
SCRIPTS = SRC / 'app/OverrideScripts.kt'
NODE = shutil.which('node')


def kotlin_raw(text, begin, end):
    block = text.split(begin, 1)[1].split(end, 1)[0]
    match = re.search(r'const val \w+ = """(.*?)"""', block, re.S)
    assert match, begin
    assert '${' not in match.group(1) and '$' not in match.group(1), 'JS must not contain Kotlin templates'
    return match.group(1)


ENGINE_TEXT = ENGINE.read_text()
WORKER_JS = kotlin_raw(ENGINE_TEXT, '// HETU-OVERRIDE-WORKER-BEGIN', '// HETU-OVERRIDE-WORKER-END')
HARNESS_JS = kotlin_raw(ENGINE_TEXT, '// HETU-OVERRIDE-HARNESS-BEGIN', '// HETU-OVERRIDE-HARNESS-END')


def examples():
    text = SCRIPTS.read_text()
    found = {}
    for name, body in re.findall(r'val (\w+) = Example\("[^"]*", """(.*?)"""\.trimIndent\(\)', text, re.S):
        lines = body.split('\n')
        lines = lines[1:] if lines and not lines[0].strip() else lines
        indent = min(len(l) - len(l.lstrip()) for l in lines if l.strip())
        found[name] = '\n'.join(l[indent:] for l in lines).strip() + '\n'
    return found


RUNNER = r'''
const fs = require('fs');
const vm = require('vm');
const { Worker } = require('worker_threads');
const input = JSON.parse(fs.readFileSync(0, 'utf8'));
const workerJs = input.workerJs;
// Web Worker shim over worker_threads: same postMessage/onmessage/terminate contract as the page.
class ShimWorker {
  constructor() {
    const boot = 'const { parentPort } = require("worker_threads");' +
      'globalThis.self = globalThis; globalThis.importScripts = function () {};' +
      'self.postMessage = (m) => parentPort.postMessage(m);' +
      'parentPort.on("message", (d) => self.onmessage({ data: d }));' + workerJs;
    this.w = new Worker(boot, { eval: true });
    this.w.on('message', (m) => this.onmessage && this.onmessage({ data: m }));
    this.w.on('error', (e) => this.onerror && this.onerror({ message: String(e && e.message || e) }));
  }
  postMessage(m) { this.w.postMessage(m); }
  terminate() { this.terminated = true; this.w.terminate(); }
}
const page = { Worker: ShimWorker, setTimeout, clearTimeout, console, JSON, String, Promise };
vm.createContext(page);
vm.runInContext(input.harnessJs, page);
page.__hetuNewWorker = () => { const w = new ShimWorker(); page.__last = w; return w; };
(async () => {
  const out = [];
  for (const job of input.jobs) {
    const started = Date.now();
    const r = await new Promise((resolve) => page.__hetuRunJob(job, (ok, payload, logs) => resolve({ ok, payload, logs })));
    r.ms = Date.now() - started;
    r.terminated = !!(page.__last && page.__last.terminated);
    out.push(r);
  }
  process.stdout.write(JSON.stringify(out));
  process.exit(0);
})();
'''


@unittest.skipIf(NODE is None, 'node is required')
class OverrideJsTests(unittest.TestCase):
    def run_jobs(self, jobs, timeout=60):
        payload = json.dumps({'workerJs': WORKER_JS, 'harnessJs': HARNESS_JS, 'jobs': jobs})
        with tempfile.NamedTemporaryFile('w', suffix='.cjs', delete=False) as f:
            f.write(RUNNER)
            runner = f.name
        try:
            out = subprocess.run([NODE, runner], input=payload, capture_output=True, text=True, timeout=timeout, check=True)
        finally:
            Path(runner).unlink()
        return json.loads(out.stdout)

    @staticmethod
    def job(source, config, profile=None, timeout_ms=3000, max_chars=8 * 1024 * 1024):
        return {'source': source, 'config': json.dumps(config), 'profile': json.dumps(profile),
                'timeoutMs': timeout_ms, 'maxChars': max_chars}

    def test_main_receives_config_and_profile_and_returns_mapping(self):
        r, = self.run_jobs([self.job(
            'function main(config, profile) { console.log("p", profile.name); config.rules.unshift("DOMAIN,x.example,DIRECT"); config.seen = profile.core; return config; }',
            {'rules': ['MATCH,DIRECT']}, {'name': 'sub.yaml', 'core': 'mihomo', 'format': 'yaml'})])
        self.assertTrue(r['ok'], r)
        result = json.loads(r['payload'])
        self.assertEqual(['DOMAIN,x.example,DIRECT', 'MATCH,DIRECT'], result['rules'])
        self.assertEqual('mihomo', result['seen'])
        self.assertEqual('p sub.yaml', r['logs'])

    def test_async_main_and_null_profile(self):
        r, = self.run_jobs([self.job(
            'async function main(config, profile) { await null; return { generated: profile === null, port: 7890 }; }', {})])
        self.assertTrue(r['ok'], r)
        self.assertEqual({'generated': True, 'port': 7890}, json.loads(r['payload']))

    def test_errors_are_reported_not_applied(self):
        cases = [
            ('var x = 1;', 'main(config, profile)'),
            ('function main(c) { throw new TypeError("bad field"); }', 'TypeError: bad field'),
            ('function main(c) { return [1, 2]; }', '配置对象'),
            ('function main(c) { return null; }', '配置对象'),
            ('function main(c) { return "text"; }', '配置对象'),
            ('function main(c) { return { big: "x".repeat(2048) }; }', '过大'),
            ('function main(c) { const a = {}; a.self = a; return a; }', 'TypeError'),
            ('function main(c) { return c', 'SyntaxError'),
        ]
        results = self.run_jobs([self.job(src, {'a': 1}, max_chars=1024) for src, _ in cases])
        for (src, expected), r in zip(cases, results):
            self.assertFalse(r['ok'], src)
            self.assertIn(expected, r['payload'], src)

    def test_endless_loop_is_terminated_at_timeout_and_next_script_still_runs(self):
        loop, after = self.run_jobs([
            self.job('function main(c) { while (true) {} }', {}, timeout_ms=400),
            self.job('function main(c) { c.ok = true; return c; }', {}, timeout_ms=2000),
        ])
        self.assertFalse(loop['ok'])
        self.assertIn('超时', loop['payload'])
        self.assertLess(loop['ms'], 3000)
        self.assertTrue(after['ok'], after)
        self.assertEqual({'ok': True}, json.loads(after['payload']))

    def test_console_output_is_bounded(self):
        r, = self.run_jobs([self.job('function main(c) { for (let i = 0; i < 500; i++) console.log("line", i); return c; }', {})])
        self.assertTrue(r['ok'])
        self.assertEqual(200, len(r['logs'].split('\n')))

    def test_shipped_examples(self):
        ex = examples()
        self.assertEqual({'BLANK', 'ADD_RULE', 'SET_DNS', 'MERGE_PROXIES', 'SING_BOX_RULE'}, set(ex))
        mihomo = {'proxies': [{'name': 'a', 'type': 'ss'}], 'proxy-groups': [{'name': 'PROXY', 'type': 'select', 'proxies': ['a']}],
                  'rules': ['MATCH,PROXY'], 'dns': {'enable': False, 'listen': '0.0.0.0:1053'}}
        box = {'outbounds': [{'type': 'direct', 'tag': 'direct'}], 'route': {'rules': [{'ip_is_private': True, 'outbound': 'direct'}]}}
        yaml_profile = {'name': 'sub.yaml', 'core': 'mihomo', 'format': 'yaml'}
        json_profile = {'name': 'box.json', 'core': 'sing-box', 'format': 'json'}
        blank, rule, dns, merge, merge_twice, singbox, singbox_on_yaml = [json.loads(r['payload']) if r['ok'] else self.fail(r) for r in self.run_jobs([
            self.job(ex['BLANK'], mihomo, yaml_profile),
            self.job(ex['ADD_RULE'], mihomo, yaml_profile),
            self.job(ex['SET_DNS'], mihomo, yaml_profile),
            self.job(ex['MERGE_PROXIES'], mihomo, yaml_profile),
            self.job(ex['MERGE_PROXIES'], dict(mihomo, proxies=[{'name': '自建-SS', 'type': 'ss'}]), None),
            self.job(ex['SING_BOX_RULE'], box, json_profile),
            self.job(ex['SING_BOX_RULE'], mihomo, yaml_profile),
        ])]
        self.assertEqual(mihomo, blank)
        self.assertEqual(['DOMAIN-SUFFIX,example.com,DIRECT', 'DOMAIN-KEYWORD,tracker,REJECT', 'MATCH,PROXY'], rule['rules'])
        self.assertEqual('fake-ip', dns['dns']['enhanced-mode'])
        self.assertEqual('0.0.0.0:1053', dns['dns']['listen'], 'existing DNS keys are kept')
        self.assertEqual(['a', '自建-SS'], [p['name'] for p in merge['proxies']])
        self.assertEqual('自建', merge['proxy-groups'][0]['name'])
        self.assertEqual(1, [p['name'] for p in merge_twice['proxies']].count('自建-SS'), 'merging is idempotent by name')
        self.assertEqual({'domain_suffix': ['example.com'], 'outbound': 'direct'}, singbox['route']['rules'][0])
        self.assertEqual(mihomo, singbox_on_yaml, 'the sing-box example leaves a YAML profile alone')


class OverrideGateTests(unittest.TestCase):
    def test_engine_is_platform_webview_without_new_dependency_or_native_payload(self):
        build = (REPO / 'android-app/app/build.gradle.kts').read_text()
        for forbidden in ('javascriptengine', 'quickjs', 'rhino', 'j2v8', 'graal'):
            self.assertNotIn(forbidden, build.lower())
        self.assertIn('android.webkit.WebView', ENGINE_TEXT)
        self.assertIn('blockNetworkLoads = true', ENGINE_TEXT)
        self.assertIn("connect-src 'none'", ENGINE_TEXT)
        self.assertIn('onRenderProcessGone', ENGINE_TEXT)
        jni = REPO / 'android-app/app/src/main/jniLibs'
        names = sorted(p.name for p in jni.rglob('*')) if jni.exists() else []
        self.assertFalse([n for n in names if 'js' in n.lower()], names)

    def test_start_path_uses_scripts_and_falls_back(self):
        manager = (SRC / 'RootProxyManager.java').read_text()
        self.assertEqual(2, manager.count('overrideScripts(profile,selected,source,'), 'Mihomo and sing-box preparation')
        self.assertIn('OverrideScriptStart.hasEnabled(context)', manager)
        self.assertIn('p.source==null?"":p.source.name', manager)
        pipeline = SCRIPTS.read_text()
        self.assertIn('OverrideOutcome(source, applied = false, fallback = true', pipeline)


if __name__ == '__main__':
    unittest.main()
