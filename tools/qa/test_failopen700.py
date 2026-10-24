#!/usr/bin/env python3
"""7.0.0 fail-open host regression for hetu-root.sh (no Root, no device, no real firewall).

Field report (2026-10-22, OnePlus PLK110 / ColorOS, Android 17, KernelSU, no ipset): the whole network dropped while
Hetu ran (17:34, wlan0 192.168.2.156/23 -> 192.168.1.141/24). The watchdog saw "data-plane probe: no network ...
DIRECT fails too", ran its Wi-Fi heal (flush/reevaluate, one Wi-Fi off/on) and still found "validated=0" with every
probe "no-dns"; `ip rule` had no default-network rule and root got "Network is unreachable". Core, watchdog and
monitor kept running with HETU_MPRE/HETU_MOUT and the fwmark rule 14500 -> 20260 in place; 停止 and 重启 hung and only a
reboot gave the phone its network back.

* Fail-open: an outage that outlasts the Wi-Fi heal (150 s, or 60 s when the heal's own check saw validated=0, every
  probe no-dns and no default network) ends Hetu: force_teardown, a failopen-state record, one Wi-Fi off/on, and no
  automatic start (App recovery, network match, watchdog restart, boot restore) until a manual start or a reboot.
  The Kill Switch keeps blocking.
* Stop never hangs: `stop` gives the normal transaction 10 s, then kills every other hetu-root.sh process and the core
  and removes every Hetu hook/chain/policy rule one command at a time, whatever the locks say; `force-stop` does that
  at once. Neither path, nor cnip_xt_drop, ever rewrites a whole table (ColorOS netd's oplus `-m bpf --object-pinned`
  matches break iptables-save | iptables-restore -T).

The netfilter / policy-routing state is tools/qa/netfilter_model93.py; the watchdog functions run from the shipped
script (test_netloss93's extraction).
"""
import importlib.util
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_netloss93 import ShellCase, TEXT, function, shq, top_assignment  # noqa: E402

spec = importlib.util.spec_from_file_location('autostart_fixture_700', Path(__file__).with_name('test_root_autostart.py'))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
MODEL = Path(__file__).with_name('netfilter_model93.py')

# The field capture's `ip rule` (no "22000: from all fwmark 0x0/0xffff iif lo lookup <wifi>" default-network rule).
FIELD_RULES = '''0:\tfrom all lookup local
9999:\tfrom all to 172.30.254.79 lookup main
14500:\tfrom all fwmark 0x200000/0x200000 lookup 20260
16000:\tfrom all fwmark 0x10065/0x1ffff iif lo lookup wlan0
17000:\tfrom all iif lo oif wlan0 lookup wlan0
20000:\tfrom all fwmark 0x0/0x10000 lookup local_network
21001:\tfrom all to 192.168.1.141/24 lookup main
23000:\tfrom all fwmark 0x65/0x1ffff iif lo lookup wlan0
32000:\tfrom all unreachable
'''
HEALTHY_RULES = FIELD_RULES.replace('23000:', '22000:\tfrom all fwmark 0x0/0xffff iif lo lookup wlan0\n23000:')
FIELD_CHECK = 'bad wlan0 validated=0 probe=www.google.cn=no-dns,connect.rom.miui.com=no-dns,www.qualcomm.cn=no-dns'
DECISION = ('wd_offline_seen', 'wd_no_default_network', 'wd_failopen_due')


class Decision(ShellCase):
    """When the watchdog gives the network back (wd_offline_seen -> wd_failopen_due)."""

    def ip_stub(self, rules):
        b = self.dir / 'bin'
        b.mkdir(exist_ok=True)
        (self.dir / 'rules.txt').write_text(rules)
        ip = b / 'ip'
        ip.write_text('#!/bin/sh\ncat %s\n' % shq(self.dir / 'rules.txt'))
        ip.chmod(0o755)
        return {'PATH': '%s:%s' % (b, os.environ['PATH'])}

    def run_seq(self, steps, rules=FIELD_RULES, kill=0, check=None, heal_pid=None, extra=''):
        """steps: WD_NOW values of successive offline verdicts. Prints one line per verdict."""
        env = self.ip_stub(rules)
        if check is not None:
            (self.run_dir / 'state' / 'wifi-check').write_text(check + '\n')
        if heal_pid is not None:
            (self.run_dir / 'wifi-heal.lock').mkdir(exist_ok=True)
            (self.run_dir / 'wifi-heal.lock' / 'pid').write_text(str(heal_pid))
        fns = ''.join(function(n) for n in DECISION)
        stubs = ('wifi_heal_async(){ echo "heal $WD_NOW" >> "$RUN/heal.calls"; return 0; }\n'
                 'wd_failopen(){ echo "FAILOPEN now=$WD_NOW why=$WD_FO_WHY"; }\n'
                 'WIFI_HEAL_OFFLINE_LIMIT=2; WIFI_HEAL_GAP=600\n'
                 + top_assignment('WD_FAILOPEN_FAST') + top_assignment('WD_FAILOPEN_AFTER') + top_assignment('WD_FAILOPEN_HEAL_MAX'))
        body = 'KILL=%d; WD_OFF_N=0; WD_OFF_NEXT=0; WD_OFF_SINCE=0; WD_OFF_LAST=0; WD_OFF_HEAL_AT=0; WD_FO_HELD=0\n' % kill
        for now in steps:
            body += 'WD_NOW=%d; wd_offline_seen "probe"; echo "t=%d since=$WD_OFF_SINCE heal=$WD_OFF_HEAL_AT"\n' % (now, now)
        r = self.sh(body, extra=fns + stubs + extra, env=env)
        return r

    def test_field_outage_fails_open_once_the_heal_saw_no_dns_and_no_default_network(self):
        # Field timing: offline 17:34:20, heal launched at the second verdict, its check written right after.
        r = self.run_seq([1000, 1035], check='1036 ' + FIELD_CHECK)
        self.assertNotIn('FAILOPEN', r.stdout, r.stderr)
        self.assertIn('t=1035 since=1000 heal=1035', r.stdout)
        r = self.run_seq([1000, 1035, 1070], check='1040 ' + FIELD_CHECK)
        self.assertIn('FAILOPEN now=1070 why=Wi-Fi validated=0, every probe no-dns, no default network', r.stdout, r.stderr)

    def test_a_default_network_or_a_reachable_probe_waits_for_the_full_outage(self):
        for rules, check in ((HEALTHY_RULES, FIELD_CHECK), (FIELD_RULES, FIELD_CHECK.replace('www.qualcomm.cn=no-dns', 'www.qualcomm.cn=000'))):
            with self.subTest(rules=rules[-40:], check=check[-30:]):
                r = self.run_seq([1000, 1035, 1070, 1105], rules=rules, check='1040 ' + check)
                self.assertNotIn('FAILOPEN', r.stdout, r.stderr)
                r = self.run_seq([1000, 1035, 1070, 1105, 1150], rules=rules, check='1040 ' + check)
                self.assertIn('FAILOPEN now=1150 why=still offline 150s after the outage began, after the Wi-Fi heal', r.stdout, r.stderr)

    def test_a_check_older_than_the_heal_is_not_evidence(self):
        r = self.run_seq([1000, 1035, 1070], check='900 ' + FIELD_CHECK)
        self.assertNotIn('FAILOPEN', r.stdout, r.stderr)

    def test_a_heal_still_running_is_waited_for_up_to_its_limit(self):
        alive = subprocess.Popen(['sleep', '30'])
        self.cleanups.append(alive.kill)
        r = self.run_seq([1000, 1035, 1150, 1200], heal_pid=alive.pid)
        self.assertNotIn('FAILOPEN', r.stdout, r.stderr)
        r = self.run_seq([1000, 1035, 1150, 1200, 1240, 1276], heal_pid=alive.pid)
        self.assertIn('FAILOPEN now=1276', r.stdout, r.stderr)

    def test_no_fail_open_before_the_heal_ran_or_after_the_outage_ended(self):
        # Without a heal (off Android / not yet due) nothing happens however long it lasts.
        r = self.run_seq([1000, 1500, 1700, 1900], extra='wifi_heal_async(){ return 1; }\n')
        self.assertNotIn('FAILOPEN', r.stdout, r.stderr)
        # Verdicts more than 300 s apart are separate outages: the old start and heal do not count.
        r = self.run_seq([1000, 1035, 1400], check='1040 ' + FIELD_CHECK)
        self.assertNotIn('FAILOPEN', r.stdout, r.stderr)
        self.assertIn('t=1400 since=1400 heal=0', r.stdout)

    def test_kill_switch_keeps_blocking_and_says_so_once(self):
        r = self.run_seq([1000, 1035, 1070, 1200, 1300], kill=1, check='1040 ' + FIELD_CHECK)
        self.assertNotIn('FAILOPEN', r.stdout, r.stderr)
        self.assertEqual(self.log_text().count('Kill Switch is on: Hetu keeps blocking'), 1)

    def test_recovered_verdicts_end_the_outage(self):
        tick = function('wd_dataplane_tick')
        ok_branch = tick[tick.index('    ok)'):tick.index('    offline)')]
        self.assertIn('WD_OFF_SINCE=0; WD_OFF_HEAL_AT=0', ok_branch)
        self.assertIn('WD_OFF_N=0; WD_OFF_SINCE=0; WD_OFF_HEAL_AT=0', tick[tick.index('  esac'):])
        egress = function('wd_egress_tick')
        self.assertEqual(egress.count('WD_OFF_SINCE=0; WD_OFF_HEAL_AT=0'), 2)
        self.assertIn('WD_OFF_SINCE=0; WD_OFF_LAST=0; WD_OFF_HEAL_AT=0; WD_FO_HELD=0', function('wd_selfheal_init'))

    def test_wifi_check_records_what_the_watchdog_reads(self):
        stubs = ('wifi_nai(){ echo "wlan0 0"; }\nwifi_probe(){ WP_HOW="www.google.cn=no-dns,connect.rom.miui.com=no-dns,www.qualcomm.cn=no-dns"; return 1; }\n'
                 'dumpsys(){ :; }\n')
        r = self.sh('wifi_check; echo "$WC_STATE"', extra=stubs + function('wifi_check'))
        self.assertIn('bad', r.stdout, r.stderr)
        line = (self.run_dir / 'state' / 'wifi-check').read_text().strip()
        self.assertRegex(line, r'^\d+ ' + FIELD_CHECK.replace('.', r'\.') + '$')


class NetModelCase(unittest.TestCase):
    """The shipped script against the netfilter / policy-routing model (as test_root_safety93's NetworkLossRecovery93)."""

    START = ('START_MODE=tproxy; START_TP=7893; START_RP=7892; START_V6=enable; START_TCP=1; START_UDP=1; START_DNS=redirect\n'
             "START_QUIC=1; START_DP=1053; START_SCOPE=core; START_UIDS=''; START_SHARE=1; START_CIDRS=''; START_IFACES=''\n"
             "START_DIRECT_UIDS=''; START_DIRECT_GIDS=''; START_SHARED_MACS=''; START_KILL=0\n")

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='hetu-failopen700-')
        self.dir = Path(self.temp.name)
        self.base = self.dir / 'hetu'
        (self.base / 'run' / 'state').mkdir(parents=True)
        (self.base / 'boot').mkdir()
        self.bin = self.dir / 'commands'
        self.bin.mkdir()
        self.state = self.dir / 'net.json'
        self.boot_id = self.dir / 'boot-id'
        self.boot_id.write_text('current-boot\n')
        for tool in ('iptables', 'ip6tables', 'iptables-restore', 'ip6tables-restore', 'iptables-save', 'ip6tables-save', 'ip'):
            c = self.bin / tool
            c.write_text('#!/bin/sh\nexec ' + shlex.quote(sys.executable) + ' ' + shlex.quote(str(MODEL)) + ' ' + tool + ' "$@"\n')
            c.chmod(0o700)
        self.env = dict(os.environ, PATH=str(self.bin) + ':' + os.environ['PATH'], HETU_NETMODEL=str(self.state),
                        HETU_NETMODEL_RESTORE_DELAY=str(self.dir / 'restore-delay'), HETU_NETMODEL_RESTORE_FAIL=str(self.dir / 'restore-fail'))
        source = (fixture.ASSETS / 'hetu-root.sh').read_text().replace('/data/adb/hetu', str(self.base)).replace(
            '/proc/sys/kernel/random/boot_id', str(self.boot_id))
        at = source.rindex('case "${1:-status}" in')
        stubs = 'root(){ :; }\nv6supported(){ return 0; }\nhealth_collect(){ H_STATE=unknown; H_REASON=fixture; }\n'
        # The script lives where Android keeps it ($BASE/hetu-root.sh): force_kill_helpers matches that path.
        self.script = self.base / 'hetu-root.sh'
        self.script.write_text(source[:at] + stubs + source[at:])
        self.functions = self.dir / 'functions.sh'
        self.functions.write_text(source[:at] + stubs)
        self.procs = []

    def tearDown(self):
        for p in self.procs:
            try:
                p.kill()
                p.wait(5)
            except Exception:
                pass
        self.temp.cleanup()

    def model(self, *args):
        return subprocess.run([sys.executable, str(MODEL), *args], env=self.env, capture_output=True, text=True, timeout=10)

    def dump(self):
        out = {}
        for fam, tool in (('4', 'iptables'), ('6', 'ip6tables')):
            for table in ('mangle', 'nat', 'filter'):
                out[fam + '-' + table] = self.model(tool, '-t', table, '-S').stdout.split('\n')
            out[fam + '-rules'] = self.model('ip', '-' + fam, 'rule', 'show').stdout.split('\n')
            out[fam + '-routes'] = self.model('ip', '-' + fam, 'route', 'show', 'table', 'all').stdout.split('\n')
        return out

    def owned(self):
        found = []
        for key, lines in self.dump().items():
            for line in lines:
                if 'HETU_' in line or 'BICHEN_' in line or any(' lookup %d' % t in line or ' table %d' % t in line for t in range(20260, 20300)):
                    found.append((key, line))
        return found

    def calls(self):
        f = Path(str(self.state) + '.calls')
        return [json.loads(l) for l in f.read_text().splitlines()] if f.exists() else []

    def body(self, text, timeout=60):
        script = self.dir / 'body.sh'
        script.write_text('. ' + shlex.quote(str(self.functions)) + '\n' + text + '\n')
        return subprocess.run(['sh', str(script)], env=self.env, capture_output=True, text=True, timeout=timeout)

    def dispatch(self, *args, timeout=60, env=None):
        return subprocess.run(['sh', str(self.script), *args], env=env or self.env, capture_output=True, text=True, timeout=timeout)

    def install(self):
        r = self.body(self.START + 'cleanup\nallocnet || exit 3\nxt_batch_begin || exit 4\n'
                      'XT_BATCH=1; install_capture_rules; XT_BATCH=0\nxt_batch_commit || exit 6\n')
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertGreater(len(self.owned()), 10)
        Path(str(self.state) + '.calls').unlink()

    def sleeper(self, role):
        """Start `sh $BASE/hetu-root.sh <role>` running a sleep, without executing the real script."""
        real = self.script.read_text()
        self.script.write_text('sleep 60\n')
        p = subprocess.Popen(['sh', str(self.script), role])
        self.procs.append(p)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            try:
                if str(self.script) in Path('/proc/%d/cmdline' % p.pid).read_bytes().decode().split('\0'):
                    break
            except FileNotFoundError:
                pass
            time.sleep(0.05)
        time.sleep(0.2)
        self.script.write_text(real)
        return p

    def no_table_rewrite(self, calls):
        for c in calls:
            self.assertNotIn(c[0], ('iptables-save', 'ip6tables-save'), c)
            self.assertNotIn('-T', c, c)


class ForceTeardown(NetModelCase):
    def test_force_teardown_removes_everything_one_command_at_a_time(self):
        self.install()
        (self.base / 'run' / 'session.state').write_text('MODE=tproxy\nKILL=0\n')
        r = self.body('force_teardown stop; echo "rc=$? left=[$FORCE_LEFT]"')
        self.assertIn('rc=0 left=[]', r.stdout, r.stderr)
        self.assertEqual([], self.owned())
        calls = self.calls()
        self.assertFalse([c for c in calls if c[0].endswith('-restore')], 'force path never restores a table')
        self.no_table_rewrite(calls)
        deletes = [c for c in calls if c[0] == 'iptables' and '-D' in c]
        self.assertTrue(deletes and all(c[c.index('-D') + 1] in ('OUTPUT', 'PREROUTING', 'FORWARD') for c in deletes), deletes)
        self.assertFalse((self.base / 'run' / 'session.state').exists())
        self.assertEqual('current-boot', (self.base / 'boot' / 'stopped-boot').read_text().strip(), 'no boot restore into it')
        self.assertIn('force-stop (stop):', (self.base / 'run' / 'watchdog.log').read_text())

    def test_duplicate_hooks_and_policy_rules_all_go(self):
        self.install()
        for args in (('iptables', '-t', 'mangle', '-A', 'OUTPUT', '-j', 'HETU_MOUT'), ('iptables', '-t', 'mangle', '-A', 'PREROUTING', '-j', 'HETU_MPRE'),
                     ('ip', 'rule', 'add', 'pref', '14500', 'fwmark', '0x200000/0x200000', 'table', '20260'),
                     ('ip', '-6', 'rule', 'add', 'pref', '14501', 'fwmark', '0x200000/0x200000', 'table', '20261')):
            self.assertEqual(0, self.model(*args).returncode)
        (self.base / 'run' / 'net.state').unlink(missing_ok=True)
        r = self.body('force_teardown stop; echo "rc=$?"')
        self.assertIn('rc=0', r.stdout, r.stderr)
        self.assertEqual([], self.owned())

    def test_bounded_when_iptables_hangs(self):
        self.install()
        hang = self.bin / 'iptables'
        hang.write_text('#!/bin/sh\nsleep 30\n')
        started = time.monotonic()
        r = self.body('FORCE_STEP_TIMEOUT=1; FORCE_BUDGET=4; force_teardown stop; echo "rc=$? left=[$FORCE_LEFT]"', timeout=120)
        elapsed = time.monotonic() - started
        self.assertIn('rc=1', r.stdout, r.stderr)
        self.assertIn('iptables-', r.stdout)
        self.assertLess(elapsed, 30, 'every iptables call is bounded and the budget ends the known-hook fallback')
        # Past the budget: the IPv6 hooks (the jumps that carry traffic) and every policy rule still go; only
        # unhooked IPv6 chains may stay for the next start to sweep.
        left = self.owned()
        self.assertFalse([o for o in left if o[0].endswith('rules') or o[0].endswith('routes')], left)
        self.assertFalse([o for o in left if o[0].startswith('6-') and o[1].startswith('-A ') and o[1].split()[1] in ('OUTPUT', 'PREROUTING', 'FORWARD')], left)


class BoundedStop(NetModelCase):
    def test_stuck_lock_and_hung_helpers_are_forced_within_bounds(self):
        self.install()
        watchdog = self.sleeper('watchdog')
        (self.base / 'run' / '.txn.lock').mkdir()
        (self.base / 'run' / '.txn.lock' / 'pid').write_text(str(watchdog.pid))
        started = time.monotonic()
        r = self.dispatch('stop', timeout=90)
        elapsed = time.monotonic() - started
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        reply = json.loads(r.stdout)
        self.assertTrue(reply['ok'] and reply['forced'] and not reply['running'], reply)
        self.assertEqual('Root 代理已强制停止并恢复网络（正常停止未能完成）', reply['message'])
        self.assertLess(elapsed, 45)
        self.assertEqual([], self.owned())
        self.assertEqual(-9, watchdog.wait(5), 'the stuck helper was killed')
        self.assertFalse((self.base / 'run' / '.txn.lock').exists())
        log = (self.base / 'run' / 'watchdog.log').read_text()
        self.assertIn('stop: normal stop failed; forcing', log)

    def test_a_hanging_normal_stop_is_cut_off_after_ten_seconds(self):
        self.install()
        (self.dir / 'restore-delay').write_text('40')
        started = time.monotonic()
        r = self.dispatch('stop', timeout=110)
        elapsed = time.monotonic() - started
        (self.dir / 'restore-delay').unlink()
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        reply = json.loads(r.stdout)
        self.assertTrue(reply['forced'], reply)
        self.assertLess(elapsed, 60)
        self.assertGreaterEqual(elapsed, 9)
        self.assertEqual([], self.owned())
        self.assertIn('normal stop did not finish within 10s; forcing', (self.base / 'run' / 'watchdog.log').read_text())
        # The hung iptables-restore of the killed stop is killed with it (it could hold the xtables lock).
        hung = subprocess.run(['pgrep', '-f', str(MODEL) + ' iptables-restore'], capture_output=True, text=True)
        self.assertEqual('', hung.stdout.strip())

    def test_normal_stop_stays_normal(self):
        self.install()
        r = self.dispatch('stop')
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        reply = json.loads(r.stdout)
        self.assertEqual('Root 代理已停止并恢复网络状态', reply['message'])
        self.assertNotIn('forced', reply)
        self.assertEqual([], self.owned())

    def test_force_stop_action(self):
        self.install()
        r = self.dispatch('force-stop')
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        reply = json.loads(r.stdout)
        self.assertEqual({'ok': True, 'forced': True, 'running': False, 'clean': True, 'message': 'Root 代理已强制停止并恢复网络'}, reply)
        self.assertEqual([], self.owned())
        self.no_table_rewrite(self.calls())


class Descendants(NetModelCase):
    def test_descendants_are_found_without_touching_others(self):
        r = self.body('sh -c "sleep 30 & sleep 30 & wait" & ROOT=$!; sleep 0.5; sleep 31 & OTHER=$!\n'
                      'D=$(force_descendants "$ROOT"); echo "D=[$D]"; for p in $D; do ps -o args= -p "$p"; done\n'
                      'case " $D " in *" $OTHER "*) echo other-included;; esac; kill $ROOT $OTHER $D 2>/dev/null; true')
        self.assertEqual(r.stdout.count('sleep 30'), 2, r.stdout + r.stderr)
        self.assertNotIn('other-included', r.stdout)


class CnipDrop(NetModelCase):
    def test_failed_batch_falls_back_to_single_commands_never_a_table_rewrite(self):
        for args in (('-N', 'HETU_CNC'), ('-N', 'HETU_CNY'), ('-N', 'HETU_CNL'), ('-N', 'HETU_CN_16_258'),
                     ('-A', 'HETU_CNL', '-d', '1.2.0.0/16', '-g', 'HETU_CN_16_258'), ('-A', 'HETU_CN_16_258', '-d', '1.2.3.0/24', '-g', 'HETU_CNY'),
                     ('-A', 'HETU_CNC', '-j', 'HETU_CNL'), ('-I', 'PREROUTING', '1', '-j', 'HETU_CNC'), ('-I', 'OUTPUT', '1', '-j', 'HETU_CNC')):
            self.assertEqual(0, self.model('iptables', '-t', 'mangle', *args).returncode, args)
        (self.dir / 'restore-fail').write_text('')
        r = self.body('cnip_xt_drop 4; echo "rc=$?"')
        self.assertIn('rc=0', r.stdout, r.stderr)
        self.assertEqual([], self.owned())
        self.no_table_rewrite(self.calls())

    def test_no_whole_table_restore_left_in_the_script(self):
        self.assertNotIn('cnip_xt_full_restore', TEXT)
        self.assertNotIn('-T mangle', TEXT)
        for name in ('cnip_xt_drop', 'cleanup', 'force_teardown', 'force_xt_table', 'force_routes'):
            self.assertNotIn('iptables-save', function(name), name)


class FailOpenState(NetModelCase):
    def failopen(self):
        self.install()
        stubs = ('failopen_wifi(){ echo wifi >> "$RUN/fo.calls"; }\nnet_recover(){ echo "net_recover $1" >> "$RUN/fo.calls"; }\n'
                 'sleep(){ :; }\nwd_notify(){ echo "notify $1|$2" >> "$RUN/fo.calls"; }\n')
        r = self.body(stubs + 'KILL=0; WD_FO_WHY="Wi-Fi validated=0, every probe no-dns, no default network"; '
                      'wd_failopen "www.gstatic.com via 198.18.0.14 no answer; DIRECT fails too"; echo unreachable')
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertNotIn('unreachable', r.stdout, 'the watchdog exits after the fail-open')
        return r

    def test_watchdog_fail_open_tears_down_records_reconnects_and_notifies(self):
        self.failopen()
        self.assertEqual([], self.owned())
        state = dict(l.split('=', 1) for l in (self.base / 'run' / 'failopen-state').read_text().splitlines())
        self.assertEqual('current-boot', state['BOOT'])
        self.assertEqual('watchdog', state['SOURCE'])
        self.assertEqual('clean', state['RESULT'])
        self.assertIn('no default network; www.gstatic.com', state['REASON'])
        calls = (self.base / 'run' / 'fo.calls').read_text().splitlines()
        self.assertEqual(calls[0], 'notify failopen whole-phone-outage|检测到整机断网，河图已自动退出并恢复系统网络。网络恢复后请在河图中手动启动代理。')
        self.assertEqual(calls[1:], ['wifi', 'net_recover fail-open'])
        self.assertEqual('current-boot', (self.base / 'boot' / 'stopped-boot').read_text().strip())
        self.assertIn('fail-open whole-phone outage', (self.base / 'run' / 'last-crash').read_text())
        self.no_table_rewrite(self.calls())

    def test_status_reports_it_and_automatic_starts_refuse_for_this_boot_only(self):
        self.failopen()
        r = self.dispatch('status')
        status = json.loads(r.stdout)
        self.assertFalse(status['running'])
        self.assertRegex(status['failOpen'], r'^\d{4}-\d\d-\d\dT')
        args = ['start', '/x/core', '/x/cfg', 'tproxy', '7893', '7892', 'enable', '1', '1', 'redirect', '1', '1053', '29090',
                'core', '', '1', '0', '', '', '', '0', '0', '', '']
        for var in ('HETU_AUTOMATIC_RECOVERY_ID', 'HETU_SELFHEAL_RESTART', 'HETU_BOOT_RESTORE_ID'):
            with self.subTest(var=var):
                r = self.dispatch(*args, env=dict(self.env, **{var: 'current-boot'}))
                self.assertNotEqual(0, r.returncode)
                self.assertEqual('检测到整机断网，河图已自动退出并恢复系统网络；本次开机不再自动启动，网络恢复后请手动启动', json.loads(r.stdout)['message'])
        # A manual start is not refused by it (it fails later here only because the fixture has no core).
        r = self.dispatch(*args)
        self.assertNotIn('本次开机不再自动启动', r.stdout)
        # Another boot: the record no longer counts.
        self.boot_id.write_text('next-boot\n')
        self.assertEqual('', json.loads(self.dispatch('status').stdout)['failOpen'])
        r = self.dispatch(*args, env=dict(self.env, HETU_AUTOMATIC_RECOVERY_ID='next-boot'))
        self.assertNotIn('本次开机不再自动启动', r.stdout)

    def test_a_started_session_clears_the_record(self):
        body = function('start_selfheal_body')
        self.assertIn('[ -n "${HETU_SELFHEAL_RESTART:-}" ] || rm -f "$FAILOPEN_STATE"', body)


class SourceGuards(unittest.TestCase):
    def test_dispatch_entries(self):
        self.assertIn("stop) root; trap '' TERM INT HUP; shield_run stop_transaction_bounded ||", TEXT)
        self.assertIn("force-stop) root; trap '' TERM INT HUP; shield_run force_stop_run manual ||", TEXT)

    def test_fail_open_runs_only_from_the_offline_path_and_after_the_heal(self):
        seen = function('wd_offline_seen')
        self.assertLess(seen.index('wd_failopen_due'), seen.index('wifi_heal_async watchdog'))
        self.assertIn('[ "${WD_OFF_HEAL_AT:-0}" != 0 ]', seen[:seen.index('wd_failopen_due')])
        self.assertEqual(TEXT.count('wd_failopen "'), 1)
        fo = function('wd_failopen')
        self.assertLess(fo.index('failopen_record'), fo.index('force_teardown failopen'))
        self.assertLess(fo.index('force_teardown failopen'), fo.index('failopen_wifi'))
        self.assertTrue(fo.rstrip().endswith('exit 0\n}') or 'exit 0' in fo)

    def test_force_steps_are_bounded(self):
        for name in ('force_xt_table', 'force_routes', 'force_left'):
            text = function(name)
            for line in text.splitlines():
                s = line.strip()
                if s.startswith('#'):
                    continue
                for tool in ('"$FXT_BIN" -w', 'ip -"$FR_F" ', '"$FL_BIN" -w', 'ip -"$FL_F" '):
                    if tool in s:
                        self.assertIn('fx ' + tool, s, (name, s))

    def test_script_parses(self):
        for shell in ('/bin/sh', 'dash'):
            try:
                subprocess.run([shell, '-n', str(fixture.ASSETS / 'hetu-root.sh')], check=True, timeout=30)
            except FileNotFoundError:
                continue


if __name__ == '__main__':
    unittest.main(verbosity=2)
