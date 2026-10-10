#!/usr/bin/env python3
"""Per-core host regression for hetu-root.sh: no real network, firewall or Root operations.

Every core Hetu lets the user download and select is deployed to $BASE/bin/core. The root script
validates it with `core -t -d RUN -f CFG`, launches it with `core -d RUN -f CFG` and recognises the
running process by /proc/<pid>/exe == $BASE/bin/core. These checks run for each runtime-supported
core (Mihomo and Mihomo Smart share that CLI). Cores the runtime cannot start must be refused by the
App before anything reaches the script, and never offered for download.
"""
from pathlib import Path
import os
import re
import shutil
import subprocess
import tempfile
import time
import unittest

REPO = Path(__file__).resolve().parents[2]
SRC = REPO / 'android-app/app/src/main'
ASSETS = SRC / 'assets'
PKG = SRC / 'java/io/github/xgl34222220/hetu'
SUPPORTED = ('mihomo', 'mihomo-smart')
UNSUPPORTED = ('sing-box', 'sing-box-ref1nd', 'xray', 'v2fly', 'hysteria')


def script_body(base):
    text = (ASSETS / 'hetu-root.sh').read_text()
    text = text.replace('/data/adb/hetu', str(base)).replace('/system/bin/sh', '/bin/sh')
    return text.rsplit('\ncase "${1:-status}" in', 1)[0]


class CoreSupport93(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp(prefix='hetu-core93-'))
        self.base = self.dir / 'hetu'
        (self.base / 'bin').mkdir(parents=True)
        (self.base / 'run').mkdir(parents=True)

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def run_body(self, body):
        script = self.dir / 'body.sh'
        script.write_text(script_body(self.base) + '\n' + body + '\n')
        return subprocess.run(['sh', str(script)], capture_output=True, text=True, timeout=20)

    def deploy_recording_core(self, core_id):
        """What RootProxyManager.ensureRuntimeBase installs: the chosen core's binary as bin/core."""
        argv = self.dir / ('argv-' + core_id)
        core = self.base / 'bin/core'
        core.write_text('#!/bin/sh\nprintf "%s\\n" "' + core_id + '" "$@" > ' + str(argv) + '\n')
        core.chmod(0o700)
        return core, argv

    def test_each_supported_core_is_validated_and_launched_with_the_mihomo_cli(self):
        for core_id in SUPPORTED:
            with self.subTest(core=core_id):
                core, argv = self.deploy_recording_core(core_id)
                cfg = self.base / 'run/state-config.yaml'
                cfg.write_text('mode: rule\n')
                result = self.run_body(f'START_ACTIVE=0\nvalidatecfg "{core}" "{cfg}"')
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual([core_id, '-t', '-d', str(self.base / 'run'), '-f', str(cfg)], argv.read_text().split('\n')[:-1])
                argv.unlink()
                result = self.run_body(f'CORE_RUNNER=""\nSTART_BIN="{core}"\nSTART_CFG="{cfg}"\ncore_launch\nwait "$START_PID"')
                self.assertEqual(0, result.returncode, result.stderr)
                for _ in range(50):
                    if argv.exists() and argv.read_text().count('\n') >= 5: break
                    time.sleep(.1)
                self.assertEqual([core_id, '-d', str(self.base / 'run'), '-f', str(cfg)], argv.read_text().split('\n')[:-1])

    def test_running_deployed_core_is_recognised_by_its_bin_core_identity(self):
        sleep = shutil.which('sleep')
        self.assertIsNotNone(sleep)
        for core_id in SUPPORTED:
            with self.subTest(core=core_id):
                core = self.base / 'bin/core'
                shutil.copyfile(sleep, core)
                core.chmod(0o700)
                process = subprocess.Popen([str(core), '30'])
                try:
                    time.sleep(.2)
                    result = self.run_body(f'pidcore {process.pid}')
                    self.assertEqual(0, result.returncode, result.stderr)
                    other = subprocess.Popen([sleep, '30'])
                    try:
                        self.assertEqual(1, self.run_body(f'pidcore {other.pid}').returncode)
                    finally:
                        other.kill(); other.wait()
                finally:
                    process.kill(); process.wait()
                    core.unlink()

    def test_unsupported_cores_are_refused_before_the_root_script(self):
        manager = (PKG / 'RootProxyManager.java').read_text()
        self.assertIn('if(profile.core!=ProxyRuntimeProfile.Core.MIHOMO&&profile.core!=ProxyRuntimeProfile.Core.MIHOMO_SMART)', manager)
        profile = (PKG / 'ProxyRuntimeProfile.java').read_text()
        self.assertIn('boolean mihomo=core==Core.MIHOMO||core==Core.MIHOMO_SMART;', profile)
        self.assertIn('if(!mihomo)return new Capability(false', profile)
        ids = dict(re.findall(r'([A-Z_]+)\("([a-z0-9-]+)", "[^"]+", set\(', profile))
        self.assertEqual(set(SUPPORTED) | set(UNSUPPORTED), set(ids.values()))
        downloads = (PKG / 'ProxyCoreDownloadManager.kt').read_text()
        support = downloads.split('internal object ProxyCoreSupport', 1)[1].split('\n}\n', 1)[0]
        self.assertIn('core == ProxyRuntimeProfile.Core.MIHOMO || core == ProxyRuntimeProfile.Core.MIHOMO_SMART', support)
        # Download/update (one path) and import both refuse a core the runtime cannot start.
        self.assertEqual(2, downloads.count('if (!ProxyCoreSupport.runtimeSupported(core)) throw IOException('))
        self.assertIn('if (!ProxyCoreSupport.runtimeSupported(core)) return@withContext localStatus(core, "暂不支持："', downloads)

    def test_root_script_has_one_core_slot_and_mihomo_cli_only(self):
        text = (ASSETS / 'hetu-root.sh').read_text()
        self.assertIn('"$BIN" -t -d "$RUN" -f "$CFG"', text)
        self.assertIn('"$START_BIN" -d "$RUN" -f "$START_CFG"', text)
        for foreign in ('sing-box', 'xray', 'v2ray', 'hysteria', ' run -c '):
            self.assertNotIn(foreign, text, 'The root script has no launch path for ' + foreign)


if __name__ == '__main__':
    unittest.main(verbosity=2)
