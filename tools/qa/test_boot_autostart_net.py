#!/usr/bin/env python3
"""Boot autostart vs. an early-boot network (OnePlus PLK110 / ColorOS, Android 17, KernelSU field report:
sometimes no start, sometimes "started" without network, sometimes a start error).

* The service.d worker starts only on a real uplink route (never unreachable / dev lo) seen on two consecutive
  checks, and waits a bounded 6 more checks for Android's default-network rule (22000) before its first start.
* A worker that gives up with a core it started still unverified stops it (system network clean) and reports the
  failure as a failure, not as an explicit stop of this boot.
* The watchdog of a boot-time automatic start neither runs the Wi-Fi heal nor fails open during its boot grace
  until the network was seen working once; a manual start never gets the grace.

Host only: isolated fixtures from test_root_autostart / test_netloss93; no Root, device or firewall.
"""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_netloss93 import ShellCase, TEXT, function  # noqa: E402

spec = importlib.util.spec_from_file_location('autostart_fixture_boot_net', Path(__file__).with_name('test_root_autostart.py'))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)

DEFAULT_RULE = '22000:\tfrom all fwmark 0x0/0xffff iif lo lookup wlan0'


class Worker(fixture.BootWorker):
    def ip(self, route, rule='', route_after=0):
        self.write_command('ip', '''t=$(cat "$HETU_TEST/ticks" 2>/dev/null || echo 0)
case "$*" in
  *rule*) printf '%%s\\n' %r;;
  *) [ "$t" -lt %d ] || printf '%%s\\n' %r;;
esac''' % (rule, route_after, route))

    def start_tick(self):
        p = self.dir / 'start-tick'
        return int(p.read_text()) if p.exists() else None

    def setUp(self):
        super().setUp()
        (self.boot / 'start.sh').write_text('''n=$(cat "$HETU_TEST/starts" 2>/dev/null || echo 0); n=$((n+1)); printf '%s\\n' "$n" > "$HETU_TEST/starts"
cat "$HETU_TEST/ticks" 2>/dev/null > "$HETU_TEST/start-tick" || echo 0 > "$HETU_TEST/start-tick"
[ "$n" -gt "${FAIL_STARTS:-0}" ] || exit 1
printf '%s\\n' "$HETU_CORE_PID" > "$HETU_TEST/hetu/run/core.pid"
''')

    def test_unreachable_or_loopback_route_is_not_a_network(self):
        for route in ('unreachable 1.1.1.1 dev lo table unspec error -101', '1.1.1.1 dev lo src 127.0.0.1'):
            with self.subTest(route=route):
                self.ip(route, DEFAULT_RULE)
                self.assertEqual(1, self.run_worker().returncode)
                self.assertEqual(0, self.starts())
                self.assertEqual('restore-timeout', self.state())

    def test_route_must_settle_and_default_network_is_awaited_with_a_bound(self):
        self.ip('1.1.1.1 via 192.168.1.1 dev wlan0 src 192.168.1.141', DEFAULT_RULE)
        self.assertEqual(0, self.run_worker().returncode)
        self.assertEqual(1, self.starts())
        self.assertEqual(1, self.start_tick(), 'one settle check, then start with a default network')
        for name in ('starts', 'ticks', 'start-tick'):
            (self.dir / name).unlink(missing_ok=True)
        (self.boot / 'status').unlink()
        (self.base / 'run/core.pid').unlink()
        self.ip('1.1.1.1 via 192.168.1.1 dev wlan0 src 192.168.1.141', '')
        self.assertEqual(0, self.run_worker().returncode)
        self.assertEqual(1, self.starts())
        self.assertEqual(7, self.start_tick(), 'no default-network rule: bounded wait (1 settle + 6), then start')

    def test_route_loss_restarts_the_settle(self):
        self.ip('1.1.1.1 dev wlan0', DEFAULT_RULE, route_after=3)
        self.assertEqual(0, self.run_worker().returncode)
        self.assertEqual(4, self.start_tick())

    def test_giving_up_stops_an_unverified_core_and_is_not_an_explicit_stop(self):
        self.ip('1.1.1.1 dev wlan0', DEFAULT_RULE)
        (self.base / 'hetu-root.sh').write_text('''#!/bin/sh
case "$1" in
  stop) echo stop >> "$HETU_TEST/stops"; cat "$HETU_TEST/boot-id" > "$HETU_TEST/hetu/boot/stopped-boot"; rm -f "$HETU_TEST/hetu/run/core.pid";;
  *) printf '%s\\n' '{"ok":true,"networkIntegrity":"degraded","dataPlaneHealthy":false}';;
esac
''')
        self.assertEqual(1, self.run_worker().returncode)
        self.assertEqual(3, self.starts())
        self.assertEqual('restore-timeout', self.state())
        self.assertEqual('stop\n', (self.dir / 'stops').read_text())
        self.assertFalse((self.boot / 'stopped-boot').exists(), 'a failed restore must not read as an explicit stop')

    def test_giving_up_keeps_a_previous_boot_stop_record(self):
        self.ip('1.1.1.1 dev wlan0', DEFAULT_RULE)
        (self.boot / 'stopped-boot').write_text('previous-boot\n')
        (self.base / 'hetu-root.sh').write_text('''#!/bin/sh
case "$1" in
  stop) echo stop >> "$HETU_TEST/stops"; cat "$HETU_TEST/boot-id" > "$HETU_TEST/hetu/boot/stopped-boot"; rm -f "$HETU_TEST/hetu/run/core.pid";;
  *) printf '%s\\n' '{"ok":true,"networkIntegrity":"degraded","dataPlaneHealthy":false}';;
esac
''')
        self.assertEqual(1, self.run_worker().returncode)
        self.assertEqual('previous-boot\n', (self.boot / 'stopped-boot').read_text())

    def test_no_start_means_nothing_to_stop(self):
        self.ip('', DEFAULT_RULE)
        self.assertEqual(1, self.run_worker().returncode)
        self.assertFalse((self.dir / 'stops').exists())


# Only the tests above, not the inherited BootWorker inventory.
for name in list(fixture.BootWorker.__dict__):
    if name.startswith('test_'):
        setattr(Worker, name, None)


class BootGrace(ShellCase):
    def seen(self, extra_body='', grace_until=None):
        fns = function('wd_offline_seen') + function('wd_failopen_due') + function('wd_no_default_network')
        stubs = ('wifi_heal_async(){ echo "heal $WD_NOW" >> "$RUN/heal.calls"; return 0; }\n'
                 'wd_failopen(){ echo "FAILOPEN now=$WD_NOW"; }\nWIFI_HEAL_OFFLINE_LIMIT=2; WIFI_HEAL_GAP=600\n')
        body = 'KILL=0; WD_OFF_N=0; WD_OFF_NEXT=0; WD_OFF_SINCE=0; WD_OFF_LAST=0; WD_OFF_HEAL_AT=0; WD_FO_HELD=0\n'
        if grace_until is not None:
            body += 'WD_BOOT_GRACE_UNTIL=%d; WD_BOOT_GRACE_LOGGED=0\n' % grace_until
        body += extra_body
        for now in (100, 135, 170, 205, 400, 435, 470, 640):
            body += 'WD_NOW=%d; wd_offline_seen probe; echo "t=%d since=$WD_OFF_SINCE heal=$WD_OFF_HEAL_AT"\n' % (now, now)
        return self.sh(body, extra=fns + stubs)

    def heals(self):
        p = self.run_dir / 'heal.calls'
        return p.read_text().split('\n')[:-1] if p.exists() else []

    def test_no_heal_or_fail_open_during_the_boot_grace(self):
        r = self.seen(grace_until=380)
        self.assertNotIn('t=205 since=100', r.stdout)
        self.assertIn('t=205 since=0 heal=0', r.stdout, r.stderr)
        self.assertEqual(['heal 435'], self.heals(), 'the outage only counts from the end of the grace')
        self.assertIn('t=400 since=400', r.stdout)
        self.assertEqual(self.log_text().count('boot grace: no network yet'), 1)

    def test_without_the_grace_the_shipped_timing_is_unchanged(self):
        r = self.seen()
        self.assertEqual('heal 135', self.heals()[0])
        self.assertIn('t=205 since=100', r.stdout, r.stderr)

    def test_a_verified_network_ends_the_grace(self):
        tick = function('wd_dataplane_tick')
        self.assertIn('WD_BOOT_GRACE_UNTIL=0', tick[tick.index('    ok)'):tick.index('    offline)')])
        egress = function('wd_egress_tick')
        self.assertIn('WD_BOOT_GRACE_UNTIL=0', egress[egress.index('    0)'):egress.index('    2)')])

    def mark(self, env):
        body = 'boot_grace_mark; cat "$BOOT_GRACE_STATE" 2>/dev/null || echo none'
        extra = function('boot_grace_mark') + 'BOOT_GRACE_STATE="$RUN/state/boot-grace"\n'
        return self.sh(body, extra=extra, env=env).stdout.strip()

    def test_only_an_early_automatic_start_gets_the_marker(self):
        up = float(Path('/proc/uptime').read_text().split()[0])
        boot = Path('/proc/sys/kernel/random/boot_id').read_text().strip()
        window = {'WD_BOOT_WINDOW': str(int(up) + 1000)}
        self.assertEqual(boot, self.mark(dict(window, HETU_BOOT_RESTORE_ID=boot)))
        self.assertEqual(boot, self.mark(dict(window, HETU_AUTOMATIC_RECOVERY_ID=boot)))
        self.assertEqual('none', self.mark(dict(window)), 'a manual start never gets the grace')
        self.assertEqual('none', self.mark({'WD_BOOT_WINDOW': '1', 'HETU_BOOT_RESTORE_ID': boot}), 'late in the boot: no grace')

    def test_shipped_wiring(self):
        start = function('start')
        self.assertLess(start.index('start_selfheal_record "$@"'), start.index('boot_grace_mark'))
        self.assertLess(start.index('boot_grace_mark'), start.index('start_watchdog "$START_PID"'))
        self.assertIn('BOOT_GRACE_STATE', function('wd_selfheal_init'))
        seen = function('wd_offline_seen')
        self.assertLess(seen.index('WD_BOOT_GRACE_UNTIL'), seen.index('wd_failopen_due'))


if __name__ == '__main__':
    unittest.main(verbosity=2)
