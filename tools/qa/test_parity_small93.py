#!/usr/bin/env python3
"""Parity batch A host regression for hetu-root.sh (no Root, no real sysctl, no real cgroup).

The shipped functions are extracted from assets/hetu-root.sh and run under /bin/sh against a
temporary directory that stands in for /proc/sys/net and the cgroup mounts:

* rp_filter / ip_forward: loose rp_filter (all=2) only when something is strict and TPROXY or
  sharing is active; ip_forward only for sharing and only when off; the journal is written before
  the change; restore puts back the original only while the value is still ours, keeps the journal
  on a failed write, and never fails.
* cgroup limits (opt-in, "cg:" prefix): v2 memory.max / io.weight with the core PID moved in, v1
  memory.limit_in_bytes / blkio.weight fallback, a report when neither exists, release on stop.
* Reference-cleanup notices in the startup copy reach the fresh core log, and native cores never
  see them in their own config file.
* Source guards: where start/stop/rollback/watchdog/status self-heal call the new functions.
"""
from pathlib import Path
import os
import re
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / 'android-app/app/src/main/assets/hetu-root.sh'
TEXT = SCRIPT.read_text()


def function(name):
    one_line = re.search(r'^' + re.escape(name) + r'\(\)\{ .*\}\n', TEXT, re.M)
    if one_line:
        return one_line.group(0)
    match = re.search(r'^' + re.escape(name) + r'\(\)\{\n.*?^\}\n', TEXT, re.S | re.M)
    if not match:
        raise AssertionError('function missing from hetu-root.sh: ' + name)
    return match.group(0)


def body(name):
    return function(name)


class ShellCase(unittest.TestCase):
    NAMES = ()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='hetu-parity-a-')
        self.dir = Path(self.temp.name)
        self.run_dir = self.dir / 'run'
        self.run_dir.mkdir()

    def tearDown(self):
        self.temp.cleanup()

    def sh(self, script, env=None):
        prelude = 'set -u\nRUN=%s\nLOG="$RUN/core.log"\n' % shq(str(self.run_dir))
        prelude += ''.join(function(n) for n in self.NAMES)
        result = subprocess.run(['/bin/sh', '-c', prelude + script], capture_output=True, text=True, timeout=30,
                                env={**os.environ, **(env or {})})
        return result


def shq(value):
    return "'" + value.replace("'", "'\\''") + "'"


class RpFilterTests(ShellCase):
    NAMES = ('net_sysctl_apply', 'net_sysctl_restore')

    def setUp(self):
        super().setUp()
        self.net = self.dir / 'net'
        for iface, value in (('all', '0'), ('default', '0'), ('lo', '0'), ('wlan0', '1'), ('rmnet_data0', '0')):
            path = self.net / 'ipv4/conf' / iface
            path.mkdir(parents=True)
            (path / 'rp_filter').write_text(value + '\n')
        (self.net / 'ipv4/ip_forward').write_text('0\n')

    def vars(self, mode='tproxy', share='0', need_tp='1'):
        return ('SYSCTL_NET=%s\nSYSCTL_TUNE_STATE="$RUN/sysctl-tune.state"\nNS_NOTES=""\nSTART_MODE=%s\nSTART_SHARE=%s\nNEED_TP=%s\n'
                % (shq(str(self.net)), mode, share, need_tp))

    def value(self, rel):
        return (self.net / rel).read_text().strip()

    def test_strict_interface_makes_all_loose_and_stop_restores(self):
        r = self.sh(self.vars() + 'net_sysctl_apply; echo "rc=$?"; printf "notes=%s\\n" "$NS_NOTES"')
        self.assertIn('rc=0', r.stdout)
        self.assertEqual(self.value('ipv4/conf/all/rp_filter'), '2')
        self.assertEqual(self.value('ipv4/conf/wlan0/rp_filter'), '1', 'only all is written; max(all, iface) is loose')
        self.assertEqual(self.value('ipv4/ip_forward'), '0', 'no sharing: ip_forward untouched')
        journal = (self.run_dir / 'sysctl-tune.state').read_text().splitlines()
        self.assertEqual(journal, [str(self.net / 'ipv4/conf/all/rp_filter') + '\t0\t2'])
        self.assertIn('conf/all/rp_filter 0→2', r.stdout)
        r = self.sh(self.vars() + 'net_sysctl_restore; echo "rc=$?"')
        self.assertIn('rc=0', r.stdout)
        self.assertEqual(self.value('ipv4/conf/all/rp_filter'), '0')
        self.assertFalse((self.run_dir / 'sysctl-tune.state').exists())

    def test_nothing_changes_when_no_interface_is_strict_or_mode_needs_none(self):
        (self.net / 'ipv4/conf/wlan0/rp_filter').write_text('2\n')
        self.sh(self.vars() + 'net_sysctl_apply')
        self.assertEqual(self.value('ipv4/conf/all/rp_filter'), '0')
        self.assertFalse((self.run_dir / 'sysctl-tune.state').exists())
        (self.net / 'ipv4/conf/wlan0/rp_filter').write_text('1\n')
        self.sh(self.vars(mode='redirect', share='0', need_tp='0') + 'net_sysctl_apply')
        self.assertEqual(self.value('ipv4/conf/all/rp_filter'), '0', 'plain Redirect without sharing keeps strict rp_filter')
        (self.net / 'ipv4/conf/all/rp_filter').write_text('2\n')
        self.sh(self.vars() + 'net_sysctl_apply')
        self.assertFalse((self.run_dir / 'sysctl-tune.state').exists(), 'already loose: nothing to journal')

    def test_sharing_enables_forwarding_and_restore_respects_later_changes(self):
        self.sh(self.vars(mode='redirect', share='1', need_tp='0') + 'net_sysctl_apply')
        self.assertEqual(self.value('ipv4/ip_forward'), '1')
        self.assertEqual(self.value('ipv4/conf/all/rp_filter'), '2')
        # netd changed rp_filter meanwhile: that value is theirs and stays; ip_forward is ours and goes back.
        (self.net / 'ipv4/conf/all/rp_filter').write_text('1\n')
        self.sh(self.vars() + 'net_sysctl_restore')
        self.assertEqual(self.value('ipv4/conf/all/rp_filter'), '1')
        self.assertEqual(self.value('ipv4/ip_forward'), '0')
        self.assertFalse((self.run_dir / 'sysctl-tune.state').exists())

    def test_restore_ignores_foreign_paths_and_keeps_journal_after_a_failed_write(self):
        outside = self.dir / 'outside'
        outside.write_text('2\n')
        state = self.run_dir / 'sysctl-tune.state'
        all_path = self.net / 'ipv4/conf/all/rp_filter'
        all_path.write_text('2\n')
        state.write_text('%s\t0\t2\n%s\t1\t2\n' % (outside, all_path))
        all_path.chmod(0o444)
        try:
            if os.access(all_path, os.W_OK):
                self.skipTest('running as root: a read-only fixture file stays writable')
            r = self.sh(self.vars() + 'net_sysctl_restore; echo "rc=$?"')
            self.assertIn('rc=0', r.stdout, 'a restore failure never fails stop')
            self.assertTrue(state.exists(), 'journal kept so the next stop retries')
        finally:
            all_path.chmod(0o644)
        self.assertEqual(outside.read_text().strip(), '2', 'paths outside net.ipv4 are never written')

    def test_unwritable_sysctl_tree_is_a_no_op(self):
        r = self.sh(self.vars().replace(str(self.net), str(self.dir / 'missing')) + 'net_sysctl_apply; echo "rc=$?"')
        self.assertIn('rc=0', r.stdout)
        self.assertFalse((self.run_dir / 'sysctl-tune.state').exists())


class CgroupTests(ShellCase):
    NAMES = ('tuning_cgroup_flags', 'cg_v1_dir', 'cg_join', 'cg_record', 'core_cgroup', 'cgroup_release')

    def vars(self, mem='', io='', mounts='', v1mem='', v1blk=''):
        return ('CG_MOUNTS=%s\nCG_V1_MEMORY=%s\nCG_V1_BLKIO=%s\nCG_STATE="$RUN/cgroup.state"\nSTART_MEM=%s\nSTART_IO=%s\n'
                'printf "cpu=applied:3 \\n" > "$RUN/tuning-status"\n'
                % (shq(mounts or str(self.dir / 'no-mounts')), shq(v1mem), shq(v1blk), shq(mem), shq(io)))

    def test_prefix_is_opt_in_and_stripped_before_validation(self):
        r = self.sh(self.vars(mem='cg:256M', io='4') + 'tuning_cgroup_flags; echo "$START_MEM|$START_IO|$START_CG_MEM|$START_CG_IO"')
        self.assertEqual(r.stdout.strip(), '256M|4|1|0')
        r = self.sh(self.vars(mem='', io='cg:') + 'tuning_cgroup_flags; echo "$START_MEM|$START_IO|$START_CG_MEM|$START_CG_IO"')
        self.assertEqual(r.stdout.strip(), '||0|0', 'an empty value never turns the cgroup path on')
        r = self.sh(self.vars() + 'START_CG_MEM=0; START_CG_IO=0; core_cgroup 4242; echo "rc=$?"; ls "$RUN"')
        self.assertIn('rc=0', r.stdout)
        self.assertNotIn('cgroup.state', r.stdout, 'switch off: nothing is created')

    def test_cgroup_v2_sets_memory_max_and_io_weight_then_release(self):
        root = self.dir / 'cg2'
        root.mkdir()
        (root / 'cgroup.subtree_control').write_text('')
        mounts = self.dir / 'mounts'
        mounts.write_text('proc /proc proc rw 0 0\nnone %s cgroup2 rw 0 0\n' % root)
        # The kernel creates the interface files with the directory; the fixture pre-creates them.
        (root / 'hetu_core').mkdir()
        for name in ('memory.max', 'io.weight', 'cgroup.procs'):
            (root / 'hetu_core' / name).write_text('')
        r = self.sh(self.vars(mem='cg:256M', io='cg:2', mounts=str(mounts)) +
                    'tuning_cgroup_flags; START_MEM_BYTES=268435456; core_cgroup 4242; echo "rc=$?"')
        self.assertIn('rc=0', r.stdout, r.stderr)
        self.assertEqual((root / 'hetu_core/memory.max').read_text().strip(), '268435456')
        self.assertEqual((root / 'hetu_core/io.weight').read_text().strip(), 'default 150')
        self.assertEqual((root / 'hetu_core/cgroup.procs').read_text().strip(), '4242')
        self.assertEqual((root / 'cgroup.subtree_control').read_text(), '+io\n', 'controllers are enabled in the parent (last write: io)')
        status = (self.run_dir / 'tuning-status').read_text().strip()
        self.assertEqual(status, 'cpu=applied:3 memcg=v2:268435456 iocg=v2:150')
        self.assertIn('[河图] cgroup 资源限制：memcg=v2:268435456 iocg=v2:150', (self.run_dir / 'core.log').read_text())
        self.assertEqual((self.run_dir / 'cgroup.state').read_text().strip(), str(root / 'hetu_core'))
        for name in ('memory.max', 'io.weight', 'cgroup.procs'):
            (root / 'hetu_core' / name).unlink()
        r = self.sh(self.vars() + 'cgroup_release; echo "rc=$?"')
        self.assertIn('rc=0', r.stdout)
        self.assertFalse((root / 'hetu_core').exists())
        self.assertFalse((self.run_dir / 'cgroup.state').exists())

    def test_cgroup_v1_fallback_and_failure_report(self):
        memcg = self.dir / 'memcg'
        (memcg / 'hetu_core').mkdir(parents=True)
        (memcg / 'memory.limit_in_bytes').write_text('')
        (memcg / 'hetu_core/memory.limit_in_bytes').write_text('')
        (memcg / 'hetu_core/tasks').write_text('')
        r = self.sh(self.vars(mem='cg:64M', io='cg:7', v1mem=str(self.dir / 'absent') + ' ' + str(memcg), v1blk=str(self.dir / 'no-blkio')) +
                    'tuning_cgroup_flags; START_MEM_BYTES=67108864; core_cgroup 77; echo "rc=$?"')
        self.assertIn('rc=0', r.stdout, r.stderr)
        self.assertEqual((memcg / 'hetu_core/memory.limit_in_bytes').read_text().strip(), '67108864')
        self.assertEqual((memcg / 'hetu_core/tasks').read_text().strip(), '77')
        status = (self.run_dir / 'tuning-status').read_text().strip()
        self.assertEqual(status, 'cpu=applied:3 memcg=v1:67108864 iocg=failed', 'a missing io controller only warns')
        # A cgroup that still holds a process is kept in the state for the next release.
        r = self.sh(self.vars() + 'cgroup_release; echo "rc=$?"')
        self.assertIn('rc=0', r.stdout)
        self.assertTrue((memcg / 'hetu_core').exists())
        self.assertIn('hetu_core', (self.run_dir / 'cgroup.state').read_text())

    def test_release_only_touches_hetu_core_directories(self):
        other = self.dir / 'someone-else'
        other.mkdir()
        (self.run_dir / 'cgroup.state').write_text(str(other) + '\n')
        self.sh(self.vars() + 'cgroup_release')
        self.assertTrue(other.exists())


class NoticeTests(ShellCase):
    NAMES = ('start_log_notices', 'core_payload')

    def test_cleanup_notices_lead_the_fresh_core_log(self):
        cfg = self.run_dir / 'config.yaml'
        cfg.write_text('# HETU_NOTICE: [清理] 代理组「S」引用的节点或组「x」未定义，启动前已从组内移除\nmixed-port: 0\n'
                       'proxies: []\n# HETU_NOTICE_FAKE: no\n')
        r = self.sh('NS_NOTES="conf/all/rp_filter 0→2（停止后恢复）"\n: > "$LOG"\nstart_log_notices %s; echo "rc=$?"' % shq(str(cfg)))
        self.assertIn('rc=0', r.stdout)
        log = (self.run_dir / 'core.log').read_text().splitlines()
        self.assertEqual(log, ['[河图] [清理] 代理组「S」引用的节点或组「x」未定义，启动前已从组内移除',
                               '[河图] 网络参数：conf/all/rp_filter 0→2（停止后恢复）'])
        r = self.sh(': > "$LOG"\nNS_NOTES=""\nstart_log_notices %s' % shq(str(self.dir / 'absent.yaml')))
        self.assertEqual((self.run_dir / 'core.log').read_text(), '')

    def test_native_core_payload_drops_notice_comments(self):
        src = self.run_dir / 'config.yaml'
        src.write_text('# HETU_NOTICE: [清理] sing-box 出站「p」引用的出站「x」未定义，启动前已移除\n{\n  "outbounds": []\n}\n'
                       '# HETU_FAKE_IP_POLICY=1\n# HETU_FAKE_IP_V4=198.18.0.1/16\n# HETU_FAKE_IP_V6=\n'
                       '# HETU_LAN_RETURN_V4=10.0.0.0/8\n# HETU_LAN_RETURN_V6=fc00::/7\n')
        r = self.sh('core_payload %s sing-box %s' % (shq(str(src)), shq(str(self.run_dir / 'out'))))
        self.assertEqual(r.returncode, 0, r.stderr)
        out = Path(r.stdout.strip())
        self.assertEqual(out.read_text(), '{\n  "outbounds": []\n}\n')
        r = self.sh('core_payload %s mihomo %s' % (shq(str(src)), shq(str(self.run_dir / 'out'))))
        self.assertEqual(r.stdout.strip(), str(src), 'Mihomo reads its YAML (comments included) unchanged')


class SourceGuards(unittest.TestCase):
    def test_start_applies_after_ipv6_guard_and_logs_before_launch(self):
        start = function('start')
        self.assertLess(start.index('install_v6_disable'), start.index('net_sysctl_apply'))
        self.assertLess(start.index('net_sysctl_apply'), start.index('core_launch'))
        self.assertIn(': > "$LOG" || fail "无法准备核心日志"; start_log_notices "$START_CFG"; core_launch', start)
        self.assertLess(start.index('core_tune "$START_PID"'), start.index('core_cgroup "$START_PID"'))
        self.assertLess(start.index('tuning_cgroup_flags'), start.index('tuning_valid || fail'))
        cleanup = start.index('stopwatchdog; cleanup; restorev6')
        self.assertLess(cleanup, start.index('net_sysctl_restore'), 'leftovers of an earlier session are restored first')
        self.assertNotIn('net_sysctl_apply ||', TEXT)
        self.assertNotIn('core_cgroup "$START_PID" ||', TEXT)

    def test_every_restoring_path_restores_sysctl_and_releases_cgroup(self):
        for name in ('stop_transaction', 'rollback_start', 'status_heal_body'):
            text = function(name)
            self.assertIn('net_sysctl_restore', text, name)
            self.assertIn('cgroup_release', text, name)
            self.assertLess(text.index('restorev6'), text.index('net_sysctl_restore'), name)
        watchdog = function('watchdog')
        self.assertIn('cleanup_confirmed && restorev6; then', watchdog)
        self.assertIn('net_sysctl_restore', watchdog)
        self.assertIn('cgroup_release', watchdog)
        # stop runs shielded from TERM, like the existing IPv6 sysctl restore.
        self.assertIn("stop) root; trap '' TERM INT HUP; shield_run stop_transaction", TEXT)

    def test_script_parses(self):
        for shell in ('/bin/sh', 'dash'):
            try:
                subprocess.run([shell, '-n', str(SCRIPT)], check=True, timeout=30)
            except FileNotFoundError:
                continue


if __name__ == '__main__':
    unittest.main()
