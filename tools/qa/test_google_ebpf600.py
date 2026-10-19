#!/usr/bin/env python3
"""6.0.0: ColorOS netd eBPF Google block list, cleared through hetu-bpf vendor-uid.

The shipped shell runs with the fake Android/xtables tools of test_google_firewall (never host rules) and a fake
hetu-bpf over one fake pinned map; the real loader is checked on a real kernel map by native/ebpf/loader tests.
"""
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_google_firewall import MOCK, PREFIX, SOURCE  # noqa: E402

# hetu-bpf vendor-uid over one fake ColorOS map (FIXTURE/bpf-wlan: the UIDs it lists).
BPF_MOCK = r'''#!/usr/bin/env python3
import json, os, pathlib, sys
p=pathlib.Path(os.environ['FIXTURE']); args=sys.argv[1:]
with (p/'calls').open('a') as log: log.write(json.dumps(['hetu-bpf',*args])+'\n')
assert len(args)==3 and args[0]=='vendor-uid' and args[1] in ('check','unblock'), args
want={int(x) for x in args[2].split(',')}
f=p/'bpf-wlan'; keys=[int(x) for x in f.read_text().split()]
hit=[k for k in keys if k in want]
if args[1]=='unblock': f.write_text(' '.join(str(k) for k in keys if k not in want))
print('maps=1 present=%d removed=%d failed=0 names=wlan' % (len(hit), len(hit) if args[1]=='unblock' else 0))
'''



class GoogleEbpf(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.base = Path(self.temp.name)
        self.fixture = self.base / 'fixture'; self.fixture.mkdir()
        self.run = self.base / 'run'; self.run.mkdir()
        self.bin = self.base / 'bin'; self.bin.mkdir()
        self.functions = self.base / 'functions.sh'; self.functions.write_text(PREFIX + '\n')
        for tool in ('pm', 'cmd', 'iptables', 'ip6tables'):
            file = self.bin / tool; file.write_text(MOCK); file.chmod(0o755)
        (self.fixture / 'packages-0').write_text('package:com.google.android.gms uid:10123\npackage:com.android.vending uid:10124\npackage:com.google.android.gsf uid:10125\npackage:com.google.android.gms.fake uid:19999\npackage:other.app uid:10200\n')
        (self.fixture / 'packages-10').write_text('package:com.google.android.gms uid:1010123\n')
        self.bpfdir = self.base / 'sysfs-bpf'; self.bpfdir.mkdir()
        tool = self.base / 'hetu-bpf'; tool.write_text(BPF_MOCK); tool.chmod(0o755)
        self.env = dict(os.environ, FIXTURE=str(self.fixture), TEST_RUN=str(self.run),
                        TEST_BPF_TOOL=str(tool), TEST_BPF_DIR=str(self.bpfdir),
                        PATH=str(self.bin) + ':' + os.environ['PATH'])

    def tearDown(self): self.temp.cleanup()

    def shell(self, body, extra=None):
        harness = self.base / 'harness.sh'
        harness.write_text('. ' + shlex.quote(str(self.functions)) + '\n' +
                           'RUN="$TEST_RUN"; SESSION="$RUN/session"; PIDFILE="$RUN/pid"; '
                           'LOCK_DIR="$RUN/lock"; START_ERROR="$RUN/error"\n'
                           'EBPF_TOOL="$TEST_BPF_TOOL"; GOOGLE_VENDOR_BPF="$TEST_BPF_DIR"\n' + body + '\n')
        result = subprocess.run(['sh', str(harness)], env=dict(self.env, **(extra or {})),
                                text=True, capture_output=True, timeout=15)
        self.assertEqual(result.stderr, '', result.stderr)
        return result

    def calls(self):
        file = self.fixture / 'calls'
        return [json.loads(x) for x in file.read_text().splitlines()] if file.exists() else []

    def enable(self): (self.run / 'session').write_text('GOOGLE_FIREWALL_CLEAN=1\n')

    def maintain(self, now=100, before='', extra_body=''):
        return self.shell('printf "%s\\n" "$$" > "$PIDFILE"\n'
                          'pidcore(){ [ "$1" = "$$" ]; }\n'
                          f'monotonic_seconds(){{ MONO_SECONDS={now}; }}\n' + before + '\n' +
                          'google_firewall_maintain "$$"\n' + extra_body)

    def colormap(self, *uids):
        """A ColorOS device: the pinned map exists and lists these UIDs."""
        (self.bpfdir / 'map_oplus-netd_app_wlan_socket_uid_limit_map').write_text('')
        (self.fixture / 'bpf-wlan').write_text(' '.join(map(str, uids)))

    def bpf_calls(self): return [c[1:] for c in self.calls() if c[0] == 'hetu-bpf']

    def left(self): return (self.fixture / 'bpf-wlan').read_text().split()

    def ebpf(self): return (self.run / 'google-firewall-ebpf').read_text()

    def test_colorOS_map_loses_only_google_uids_on_cleanup(self):
        self.colormap(10123, 10200, 10125, 1010123)
        self.assertEqual(self.shell('google_firewall_cleanup').returncode, 0)
        self.assertEqual(self.bpf_calls(), [['vendor-uid', 'unblock', '10123,10124,10125,1010123']])
        self.assertEqual(self.left(), ['10200'])  # the user's own per-app block stays
        self.assertRegex(self.ebpf(), r'^bpfmode=unblock bpfmaps=1 bpfnames=wlan bpflisted=3 bpfremoved=3 bpffailed=0 bpfat=\S+\n$')
        self.assertIn('ebpf wlan removed=3', (self.run / 'google-firewall.log').read_text())
        self.assertEqual((self.run / 'google-firewall-uids').read_text().split(), ['10123', '10124', '10125', '1010123'])

    def test_no_colorOS_map_never_runs_the_loader(self):
        self.assertEqual(self.shell('google_firewall_cleanup').returncode, 0)
        self.assertEqual(self.bpf_calls(), [])
        self.assertFalse((self.run / 'google-firewall-ebpf').exists())

    def test_watchdog_clears_a_relisted_uid_between_full_passes_without_pm(self):
        self.enable(); self.colormap(10123)
        self.assertEqual(self.maintain(100).returncode, 0)
        self.assertEqual(self.left(), [])
        before = len(self.calls())
        self.colormap(10124, 10300)  # ColorOS lists Play Store again after an interface change
        self.assertEqual(self.maintain(112).returncode, 0)
        quick = self.calls()[before:]
        self.assertEqual([c[0] for c in quick], ['hetu-bpf'])  # no pm, no iptables in a quick pass
        self.assertEqual(self.left(), ['10300'])
        self.assertIn('bpflisted=1 bpfremoved=2 ', self.ebpf())
        self.assertFalse((self.run / 'lock').exists())

    def test_quiet_quick_pass_leaves_the_status_file_alone(self):
        self.enable(); self.colormap()
        self.assertEqual(self.maintain(100).returncode, 0)
        first = self.ebpf(); stamp = (self.run / 'google-firewall-ebpf').stat().st_mtime_ns
        self.assertEqual(self.maintain(112).returncode, 0)
        self.assertEqual(len(self.bpf_calls()), 2)
        self.assertEqual(self.ebpf(), first)
        self.assertEqual((self.run / 'google-firewall-ebpf').stat().st_mtime_ns, stamp)

    def test_switch_off_only_checks_and_never_deletes(self):
        self.colormap(10123, 10200)
        result = self.shell('START_GMS_APPIDS=10123,10124\ngoogle_firewall_detect')
        self.assertEqual(result.returncode, 0)
        self.assertEqual(self.bpf_calls(), [['vendor-uid', 'check', '10123,10124']])
        self.assertIn('bpfmode=check bpfmaps=1 bpfnames=wlan bpflisted=1 bpfremoved=0 ', self.ebpf())
        (self.run / 'session').write_text('GOOGLE_FIREWALL_CLEAN=0\n')
        self.assertEqual(self.maintain(100).returncode, 0)
        self.assertEqual(self.maintain(112).returncode, 0)  # no quick pass while off
        self.assertEqual(self.bpf_calls(), [['vendor-uid', 'check', '10123,10124']] * 2)
        self.assertEqual(self.left(), ['10123', '10200'])
        self.assertFalse([c for c in self.calls() if c[0] in ('pm', 'cmd', 'iptables', 'ip6tables')])

    def test_switch_off_without_app_ids_from_the_app_asks_nothing(self):
        self.colormap(10123)
        self.assertEqual(self.shell('START_GMS_APPIDS=\ngoogle_firewall_detect').returncode, 0)
        self.assertEqual(self.calls(), [])

    def test_start_and_status_wire_the_ebpf_pass(self):
        self.assertIn('[ "$START_VENDOR_CLEAN" = 1 ] || google_firewall_detect', SOURCE)
        self.assertIn('rm -f "$RUN/google-firewall-at" "$RUN/google-firewall-ebpf" "$RUN/google-firewall-uids"', SOURCE)
        self.assertIn('GFEV=$(head -n 1 "$RUN/google-firewall-ebpf"', SOURCE)
        self.assertIn('package:com.google.android.configupdater', SOURCE)



if __name__ == '__main__': unittest.main(verbosity=2)
