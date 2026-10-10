#!/usr/bin/env python3
"""Fast-start host regression: one Root invocation per manual start, cached identity probes.

No real network, firewall or Root operation. The shipped hetu-root.sh runs against the stateful
netfilter model (netfilter_model93.py); the shipped StartPrelude.java (the App's shell-fragment
builder for the merged start) is compiled with the host JDK and its fragments are executed.

* A manual start is ONE shell: cancellation receipt -> pre-start hook (only a `[ -s ]` test when
  there is none) -> deployment in its own `set -e` shell -> exec hetu-root.sh start. That single
  invocation completes a real start, and every refusal happens before the network is touched.
* A Stop during the hook still cancels natively (receipt read first, compared after the lock).
* core_identity_prepare reuses a verified BusyBox runner within one boot (boot_id, core group,
  IPv6 availability and the BusyBox inode/mtime/size must match) and never caches a negative.
* private_dns_mode and the GMS app IDs come from the App: the start forks no settings/pm/cmd.
"""
from pathlib import Path
import json
import os
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_core_support93 import FAKE_CORE_PY, METADATA, MODEL, free_port  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
SRC = REPO / 'android-app/app/src/main'
ASSETS = SRC / 'assets'
PKG = SRC / 'java/io/github/xgl34222220/hetu'

FAKE_BUSYBOX = r'''#!/bin/sh
printf '%s\n' "$*" >> "$HETU_BB_LOG"
case "$1" in
  setuidgid) [ "${HETU_BB_REFUSE:-0}" = 1 ] && exit 1; shift 2; exec "$@";;
  id) case "$2" in -u) echo 0;; -g) echo 3005;; esac;;
  *) exit 64;;
esac
'''

GEN_JAVA = r'''package io.github.xgl34222220.hetu;
public final class FastStartGen {
    public static void main(String[] a) {
        StringBuilder out = new StringBuilder(StartPrelude.cancelReceipt(a[0]));
        if (!a[1].isEmpty()) out.append(StartPrelude.preStartHook(a[1], a[2]));
        out.append(StartPrelude.deployment(a[3]));
        out.append("export HETU_PRIVATE_DNS_MODE=").append(StartPrelude.quote(StartPrelude.privateDnsMode(a[4]))).append("; ");
        System.out.print(out);
    }
}
'''


def java_tool(name):
    home = os.environ.get('JAVA_HOME')
    if home and (Path(home) / 'bin' / name).exists(): return str(Path(home) / 'bin' / name)
    return shutil.which(name)


class FastStart93(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.jdir = tempfile.TemporaryDirectory(prefix='hetu-faststart-java-')
        javac, java = java_tool('javac'), java_tool('java')
        if not javac or not java: raise unittest.SkipTest('host JDK required to execute StartPrelude')
        gen = Path(cls.jdir.name) / 'FastStartGen.java'; gen.write_text(GEN_JAVA)
        r = subprocess.run([javac, '-d', cls.jdir.name, str(PKG / 'StartPrelude.java'), str(gen)], capture_output=True, text=True)
        if r.returncode: raise AssertionError(r.stdout + r.stderr)
        cls.java = java

    @classmethod
    def tearDownClass(cls): cls.jdir.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='hetu-faststart-')
        self.dir = Path(self.temp.name); self.base = self.dir / 'hetu'
        for d in ('run/state', 'boot', 'scripts'): (self.base / d).mkdir(parents=True, exist_ok=True)
        self.bin = self.dir / 'commands'; self.bin.mkdir()
        self.state = self.dir / 'net.json'; self.boot_id = self.dir / 'boot-id'; self.boot_id.write_text('boot-one\n')
        for tool in ('iptables', 'ip6tables', 'iptables-restore', 'ip6tables-restore', 'iptables-save', 'ip6tables-save', 'ip'):
            c = self.bin / tool; c.write_text('#!/bin/sh\nexec ' + shlex.quote(sys.executable) + ' ' + shlex.quote(str(MODEL)) + ' ' + tool + ' "$@"\n'); c.chmod(0o700)
        self.log = self.dir / 'forks.log'
        for tool in ('chown', 'settings', 'pm', 'cmd'):  # chown: host is not root; the rest: Java tools to count
            c = self.bin / tool
            c.write_text('#!/bin/sh\nprintf "%s %s\\n" ' + tool + ' "$*" >> ' + shlex.quote(str(self.log)) + '\n'
                         + ('echo off\n' if tool == 'settings' else '') + 'exit 0\n'); c.chmod(0o700)
        self.busybox = self.dir / 'ksu/bin/busybox'; self.busybox.parent.mkdir(parents=True)
        self.busybox.write_text(FAKE_BUSYBOX); self.busybox.chmod(0o700)
        self.bb_log = self.dir / 'bb.log'
        self.users = self.dir / 'data-user'; (self.users / '0').mkdir(parents=True); (self.users / '10').mkdir()
        (self.dir / 'fakecore.py').write_text(FAKE_CORE_PY)
        self.tp, self.dp, self.cp = free_port(), free_port(), free_port()
        self.env = dict(os.environ, PATH=str(self.bin) + ':' + os.environ['PATH'], HETU_NETMODEL=str(self.state),
                        HETU_NETMODEL_RESTORE_DELAY=str(self.dir / 'restore-delay'), HETU_NETMODEL_RESTORE_FAIL=str(self.dir / 'restore-fail'),
                        HETU_FAKE_LOG=str(self.dir / 'core.jsonl'), HETU_BB_LOG=str(self.bb_log),
                        HETU_FAKE_LISTEN='tcp:%d,udp:%d,tcp:%d,udp:%d,tcp:%d' % (self.tp, self.tp, self.dp, self.dp, self.cp))
        source = (ASSETS / 'hetu-root.sh').read_text().replace('/system/bin/sh', '/bin/sh').replace('/data/adb/hetu', str(self.base)) \
            .replace('/proc/sys/kernel/random/boot_id', str(self.boot_id)).replace('/data/adb/ksu/bin/busybox', str(self.busybox)) \
            .replace('ls /data/user ', 'ls ' + shlex.quote(str(self.users)) + ' ')
        at = source.rindex('case "${1:-status}" in')
        # Fixture authorization, IPv6-capable kernel, wrapper-core identity. The fake runner cannot
        # really switch to uid 0/gid 3005 on a host, so only the /proc status read is modelled.
        stubs = ('root(){ :; }\nv6supported(){ return 0; }\n'
                 'pidcore(){ case "$1" in \'\'|*[!0-9]*) return 1;; esac; [ -r "/proc/$1/cmdline" ] || return 1; '
                 'tr \'\\000\' \'\\n\' < "/proc/$1/cmdline" 2>/dev/null | sed -n 2p | grep -qx "$BASE/bin/core"; }\n'
                 'core_candidate(){ pidcore "$1"; }\n'
                 'core_identity_confirm(){ CORE_GID=""; SYSTEM_DNS=exempt; [ -n "$CORE_RUNNER" ] || return 0; pidcore "$1" || return 1; '
                 'CORE_GID="$CORE_GROUP_ID"; [ "$START_DNS" != off ] || return 0; case "$START_MODE" in tun|ebpf) ;; *) SYSTEM_DNS=captured;; esac; }\n')
        self.stage = self.dir / 'stage'; self.stage.mkdir()
        (self.stage / 'hetu-root.sh').write_text(source[:at] + stubs + source[at:])
        self.functions = self.dir / 'functions.sh'; self.functions.write_text(source[:at] + stubs)
        core = self.stage / 'core'
        core.write_text('#!/bin/sh\n' + shlex.quote(sys.executable) + ' ' + shlex.quote(str(self.dir / 'fakecore.py')) + ' "$@" &\n'
                        'C=$!\ntrap \'kill "$C" 2>/dev/null; wait "$C"; exit 0\' TERM INT\nwait "$C"\n')
        core.chmod(0o700)
        (self.stage / 'startup-config').write_text('mode: rule\n' + METADATA)

    def tearDown(self):
        script = self.base / 'hetu-root.sh'
        if script.exists(): subprocess.run(['sh', str(script), 'stop'], env=self.env, capture_output=True, text=True, timeout=30)
        for proc in Path('/proc').iterdir():
            if proc.name.isdigit():
                try:
                    if str(self.dir) in (proc / 'cmdline').read_bytes().decode(errors='ignore'): os.kill(int(proc.name), signal.SIGKILL)
                except (OSError, ValueError): pass
        self.temp.cleanup()

    # ------------------------------------------------------------------ helpers
    def q(self, value): return shlex.quote(str(value))

    def deployment(self):
        """The shape RootProxyManager.deploymentCommand emits: temp copy, chmod/chown, atomic mv."""
        b = self.base
        steps = ['set -e', 'mkdir -p %s %s' % (self.q(b / 'bin'), self.q(b / 'run/state'))]
        for src, dst, mode in (('hetu-root.sh', b / 'hetu-root.sh', 700), ('core', b / 'bin/core', 700), ('startup-config', b / 'run/state/startup-config', 600)):
            tmp = str(dst) + '.new.1'
            steps += ['cp %s %s' % (self.q(self.stage / src), self.q(tmp)), 'chmod %d %s' % (mode, self.q(tmp)),
                      'chown 0:0 %s' % self.q(tmp), 'mv -f %s %s' % (self.q(tmp), self.q(dst))]
        return '; '.join(steps)

    def merged(self, hook=True, private_dns='off', deploy=None):
        argv = [self.java, '-cp', self.jdir.name, 'io.github.xgl34222220.hetu.FastStartGen', '/data/adb/hetu/run/start-cancel-generation',
                'tproxy' if hook else '', 'fixture.yaml', deploy or self.deployment(), private_dns]
        prelude = subprocess.run(argv, capture_output=True, text=True, check=True).stdout.replace('/data/adb/hetu', str(self.base))
        args = ['start', str(self.base / 'bin/core'), str(self.base / 'run/state/startup-config'), 'tproxy', str(self.tp), '0', 'enable', '1', '1',
                'redirect', '0', str(self.dp), str(self.cp), 'core', '', '0', '0', '', '', '', '1', '1', '', '', '1', '1', '0', '', '', '', '0']
        return prelude + 'exec ' + self.q(self.base / 'hetu-root.sh') + ' ' + ' '.join(self.q(a) for a in args)

    def invoke(self, command, timeout=90):
        """Exactly one shell process stands in for the one `su -c` of a manual start."""
        return subprocess.run(['sh', '-c', command], env=self.env, capture_output=True, text=True, timeout=timeout)

    def owned(self):
        out = []
        for tool in ('iptables', 'ip6tables'):
            for table in ('mangle', 'nat', 'filter'):
                out += [l for l in subprocess.run([sys.executable, str(MODEL), tool, '-t', table, '-S'], env=self.env, capture_output=True, text=True).stdout.splitlines() if 'HETU_' in l]
        return out

    def body(self, text):
        script = self.dir / 'body.sh'; script.write_text('. ' + self.q(self.functions) + '\n' + text + '\n')
        return subprocess.run(['sh', str(script)], env=self.env, capture_output=True, text=True, timeout=30)

    def forks(self, tool):
        return [l for l in (self.log.read_text().splitlines() if self.log.exists() else []) if l.startswith(tool + ' ')]

    def bb_calls(self):
        return [l for l in (self.bb_log.read_text().splitlines() if self.bb_log.exists() else []) if l.startswith('setuidgid')]

    # ------------------------------------------------------------------ one invocation
    def test_single_invocation_without_hook_deploys_and_completes_a_real_start(self):
        (self.base / 'run/start-cancel-generation').write_text('41-4242\n')
        r = self.invoke(self.merged())
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertTrue(json.loads(r.stdout.strip().splitlines()[-1])['ok'], r.stdout)
        self.assertTrue((self.base / 'bin/core').exists() and (self.base / 'hetu-root.sh').exists())
        self.assertFalse((self.base / 'run/scripts.log').exists(), 'no hook file: nothing ran, nothing logged')
        self.assertTrue(any('TPROXY --on-port %d' % self.tp in l for l in self.owned()))
        self.assertEqual([], self.forks('settings'), 'private_dns_mode came from the App')
        self.assertEqual([], self.forks('pm') + self.forks('cmd'))

    def test_existing_hook_runs_inside_the_same_invocation_and_its_failure_refuses_before_network(self):
        hook = self.base / 'scripts/pre-start.sh'
        hook.write_text('echo "mode=$HETU_MODE config=$HETU_CONFIG hook=$HETU_HOOK"\n')
        r = self.invoke(self.merged())
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn('mode=tproxy config=fixture.yaml hook=pre-start', (self.base / 'run/scripts.log').read_text())
        self.assertEqual(0, subprocess.run(['sh', str(self.base / 'hetu-root.sh'), 'stop'], env=self.env, capture_output=True).returncode)
        self.assertEqual([], self.owned())
        hook.write_text('exit 3\n')
        r = self.invoke(self.merged())
        self.assertEqual('HETU_PRENATIVE\thook\t3', r.stdout.strip().splitlines()[-1])
        self.assertEqual([], self.owned(), 'a refused hook never reaches the network')
        self.assertFalse((self.base / 'run/core.pid').exists())

    def test_stop_during_the_hook_is_refused_by_the_native_receipt_comparison(self):
        (self.base / 'run/start-cancel-generation').write_text('7-77\n')
        # The hook stands in for a concurrent Stop: cancel_boot writes a new receipt meanwhile.
        (self.base / 'scripts/pre-start.sh').write_text("printf '9-99\\n' > %s\n" % self.q(self.base / 'run/start-cancel-generation'))
        r = self.invoke(self.merged())
        self.assertEqual(1, r.returncode, r.stdout + r.stderr)
        self.assertFalse(json.loads(r.stdout.strip().splitlines()[-1])['ok'])
        self.assertEqual([], self.owned())

    def test_unreadable_receipt_and_failed_deployment_refuse_before_exec(self):
        (self.base / 'run/start-cancel-generation').write_text('not-a-receipt\n')
        r = self.invoke(self.merged(hook=False))
        self.assertEqual('HETU_PRENATIVE\ttoken', r.stdout.strip())
        self.assertFalse((self.base / 'hetu-root.sh').exists(), 'nothing deployed after a bad receipt')
        (self.base / 'run/start-cancel-generation').unlink()
        broken = self.deployment().replace(str(self.stage / 'core'), str(self.stage / 'missing-core'))
        r = self.invoke(self.merged(hook=False, deploy=broken))
        last = r.stdout.strip().splitlines()[-1]
        self.assertTrue(last.startswith('HETU_PRENATIVE\tdeploy\t'), r.stdout)
        self.assertIn('missing-core', last)
        self.assertEqual([], self.owned())

    def test_without_app_value_the_shell_still_asks_settings(self):
        r = self.invoke(self.merged(hook=False, private_dns=''))  # Java null -> "unknown", never empty
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertEqual([], self.forks('settings'))
        self.assertIn('PRIVATE_DNS=unknown\n', (self.base / 'run/session.state').read_text())
        subprocess.run(['sh', str(self.base / 'hetu-root.sh'), 'stop'], env=self.env, capture_output=True)
        command = self.merged(hook=False).replace("export HETU_PRIVATE_DNS_MODE='off'; ", '')
        r = self.invoke(command)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertEqual(1, len(self.forks('settings')), 'boot/autostart starts keep the settings fallback')

    # ------------------------------------------------------------------ cached probes
    def prepare(self):
        r = self.body('core_identity_prepare\nprintf "%s|%s|%s\\n" "$CORE_RUNNER" "$CORE_SPEC" "$IDENTITY_SOURCE"')
        self.assertEqual((0, ''), (r.returncode, r.stderr), r.stdout)
        return r.stdout.strip()

    def test_identity_probe_is_cached_per_boot_and_busybox(self):
        first = self.prepare()
        self.assertEqual('%s|root:net_admin|probe' % self.busybox, first)
        probes = len(self.bb_calls()); self.assertGreater(probes, 0)
        model_calls = Path(str(self.state) + '.calls').read_text().count('HETU_PROBE')
        self.assertEqual('%s|root:net_admin|cache' % self.busybox, self.prepare())
        self.assertEqual(probes, len(self.bb_calls()), 'cache hit: no setuidgid probe')
        self.assertEqual(model_calls, Path(str(self.state) + '.calls').read_text().count('HETU_PROBE'), 'cache hit: no owner probe')
        self.boot_id.write_text('boot-two\n')
        self.assertTrue(self.prepare().endswith('|probe'), 'new boot_id re-probes')
        self.assertTrue(self.prepare().endswith('|cache'))
        time.sleep(1.1); self.busybox.write_text(FAKE_BUSYBOX + '# replaced\n')
        self.assertTrue(self.prepare().endswith('|probe'), 'replaced BusyBox re-probes')
        (self.base / 'run/state/identity.cache').write_text('garbage\n')
        self.assertTrue(self.prepare().endswith('|probe'), 'unreadable cache falls back to the full probe')

    def test_negative_identity_and_system_resolver_are_never_cached(self):
        self.env['HETU_BB_REFUSE'] = '1'
        self.assertEqual('||probe', self.prepare())
        self.assertFalse((self.base / 'run/state/identity.cache').exists())
        del self.env['HETU_BB_REFUSE']
        self.assertTrue(self.prepare().endswith('|probe'), 'a negative result is re-probed next time')
        (self.base / 'policy').mkdir(); (self.base / 'policy/system-dns-direct').write_text('')
        self.assertEqual('||probe', self.prepare())

    def test_failed_identity_confirmation_drops_the_cache(self):
        self.prepare()
        self.assertTrue((self.base / 'run/state/identity.cache').exists())
        r = self.body('core_identity_confirm(){ return 1; }\nSTART_ACTIVE=0\n'
                      'core_identity_confirm 1 || { rm -f "$RUN/state/identity.cache" 2>/dev/null; echo dropped; }')
        self.assertIn('dropped', r.stdout)
        script = (ASSETS / 'hetu-root.sh').read_text()
        self.assertIn('core_identity_confirm "$START_PID" || { rm -f "$RUN/state/identity.cache" 2>/dev/null; fail', script)

    def test_app_gms_app_ids_resolve_every_user_without_pm(self):
        r = self.body('START_GMS_APPIDS=10123,10456,99,abc\ngoogle_firewall_uids')
        self.assertEqual(['10123', '10456', '1010123', '1010456'], r.stdout.split())
        self.assertEqual([], self.forks('pm') + self.forks('cmd'))
        r = self.body('START_GMS_APPIDS=0\ngoogle_firewall_uids')
        self.assertEqual('', r.stdout.strip(), '"0" means resolved: no GMS installed')
        self.body('START_GMS_APPIDS=\ngoogle_firewall_uids')
        self.assertTrue(self.forks('pm'), 'without App values (watchdog upkeep) pm still resolves')

    def test_start_reads_app_values_shell_locally(self):
        script = (ASSETS / 'hetu-root.sh').read_text()
        self.assertIn('unset HETU_GMS_APPIDS HETU_PRIVATE_DNS_MODE', script)
        self.assertIn('if [ "$START_VENDOR_CLEAN" = 1 ]; then google_firewall_cleanup; fi', script)
        manager = (PKG / 'RootProxyManager.java').read_text()
        self.assertIn('export HETU_PRIVATE_DNS_MODE=', manager)
        self.assertIn('export HETU_GMS_APPIDS=', manager)


if __name__ == '__main__':
    unittest.main(verbosity=2)
