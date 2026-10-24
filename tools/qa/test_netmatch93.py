#!/usr/bin/env python3
"""Network-match automation extension + shared-network App rules host regression.

No Android, device, Root or network. The production pure-Java rule classes
(NetworkMatchRules.java, SharedNetworkRules.java) are compiled verbatim with javac and driven by a
harness; the Root command of the "run script" action is executed by /bin/sh in a temp directory
(paths rewritten), so timeout, exit code and scripts.log are real.

* Conditions: Wi-Fi SSID/BSSID (unchanged), any cellular, SIM slot (default data SIM in slot N on
  cellular) and MCC+MNC (SIM or registered network operator, on cellular). Operator values from
  getprop lists are per slot. No READ_PHONE_STATE (manifest unchanged, no SubscriptionInfo list).
* Actions: start/stop/none/switch_config:<name>/run_script:<name>; malformed values act as none.
* Debounce/loop limiter: same decision not again within 30 s, at most 3 background actions/min.
* Script: only managed names under the scripts dir, bounded by timeout, exit code preserved and
  logged with HETU_EVENT/HETU_NETWORK, never inside the start/stop control lock.
* Switch config: validate-then-swap restart only when running, previous selection restored on a
  refused restart, owner unchanged.
* Shared network: interfaces sanitized (no lo/rmnet/ccmni/tun), policy body only when scoped,
  tether candidates exclude the upstream, dnsmasq leases and dumpsys tethering parsed, and the
  share page no longer writes proxyBypassInterfaces.
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
SRC = REPO / 'android-app/app/src/main'
PKG = SRC / 'java/io/github/xgl34222220/hetu'

HARNESS = r'''
package io.github.xgl34222220.hetu;
import java.util.*;
public final class NetMatchHarness {
  static StringBuilder out = new StringBuilder("{");
  static void put(String k, Object v) {
    if (out.length() > 1) out.append(',');
    out.append('"').append(k).append("\":");
    if (v instanceof String) out.append('"').append(((String) v).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")).append('"');
    else out.append(v);
  }
  static NetworkMatchRules.Conditions c(Collection<String> ssid, Collection<String> bssid, boolean mobile, Collection<String> slots, Collection<String> mcc) {
    return new NetworkMatchRules.Conditions(ssid, bssid, mobile, slots, mcc);
  }
  static NetworkMatchRules.Observation o(boolean wifi, boolean cell, String ssid, String bssid, int slot, String sim, String net) {
    return new NetworkMatchRules.Observation(wifi, cell, ssid, bssid, slot, sim, net);
  }
  public static void main(String[] a) throws Exception {
    List<String> none = Collections.emptyList();
    // ----- conditions
    put("wifiAnyWhenEmpty", NetworkMatchRules.matched(o(true,false,"Home","aa:bb:cc:dd:ee:ff",-1,"",""), c(none,none,false,none,none)));
    put("wifiSsidAndBssid", NetworkMatchRules.matched(o(true,false,"Home","AA:BB:CC:DD:EE:FF",-1,"",""), c(List.of("Home"),List.of("aa:bb:cc:dd:ee:ff"),false,none,none)));
    put("wifiWrongBssid", NetworkMatchRules.matched(o(true,false,"Home","11:22:33:44:55:66",-1,"",""), c(List.of("Home"),List.of("aa:bb:cc:dd:ee:ff"),false,none,none)));
    put("cellularNoRule", NetworkMatchRules.matched(o(false,true,"","",1,"46000","46000"), c(List.of("Home"),none,false,none,none)));
    put("cellularAny", NetworkMatchRules.matched(o(false,true,"","",1,"46000","46000"), c(List.of("Home"),none,true,none,none)));
    put("slot2Matches", NetworkMatchRules.matched(o(false,true,"","",2,"46011","46011"), c(List.of("Home"),none,false,List.of("2"),none)));
    put("slot1NotSlot2", NetworkMatchRules.matched(o(false,true,"","",1,"46000","46000"), c(List.of("Home"),none,false,List.of("2"),none)));
    put("slotUnknown", NetworkMatchRules.matched(o(false,true,"","",-1,"46000","46000"), c(none,none,false,List.of("1","2"),none)));
    put("slotNeedsCellular", NetworkMatchRules.matched(o(true,false,"Cafe","",1,"46000",""), c(List.of("Home"),none,false,List.of("1"),none)));
    put("mccSim", NetworkMatchRules.matched(o(false,true,"","",1,"45403","46001"), c(List.of("Home"),none,false,none,List.of("454-03"))));
    put("mccRoamingNetwork", NetworkMatchRules.matched(o(false,true,"","",1,"46000","44010"), c(List.of("Home"),none,false,none,List.of("44010"))));
    put("mccNoMatch", NetworkMatchRules.matched(o(false,true,"","",1,"46000","46000"), c(List.of("Home"),none,false,none,List.of("46011"))));
    put("mccOnWifi", NetworkMatchRules.matched(o(true,false,"Cafe","",1,"46000","46000"), c(List.of("Home"),none,false,none,List.of("46000"))));
    put("mccSanitized", String.join(",", NetworkMatchRules.sanitizeMccMncs(List.of("460-00"," 46011 ","abc","4601","3101500","310150"))));
    put("slotSanitized", String.join(",", NetworkMatchRules.sanitizeSlots(Arrays.asList("1","3","2",null," 2 "))));
    put("propSlot1", NetworkMatchRules.operatorForSlot("46000,46011", 1));
    put("propSlot2", NetworkMatchRules.operatorForSlot("46000,46011", 2));
    put("propEmptySlot", NetworkMatchRules.operatorForSlot(",46011", 1));
    put("propMissing", NetworkMatchRules.operatorForSlot("46000", 2));
    put("labelCell", NetworkMatchRules.label(o(false,true,"","",2,"46011","44010")));
    put("labelWifi", NetworkMatchRules.label(o(true,false,"Home","aa:bb:cc:dd:ee:ff",-1,"","")));
    // ----- actions
    String[] raws = {"start","stop","none","switch_config:Home.yaml","run_script:on-home.sh","switch_config:../etc","run_script:pre-start.sh","run_script:x;rm -rf.sh","bogus","", null, "switch_config:a/b.yaml"};
    StringBuilder acts = new StringBuilder();
    for (String r : raws) { NetworkMatchRules.Action x = NetworkMatchRules.parseAction(r); acts.append(x.kind).append('=').append(x.target).append('|'); }
    put("actions", acts.toString());
    put("encodeSwitch", NetworkMatchRules.parseAction("switch_config:Home.yaml").encode());
    put("encodeScript", NetworkMatchRules.parseAction("run_script:on-home.sh").encode());
    put("backgroundKinds", NetworkMatchRules.parseAction("start").background() + "," + NetworkMatchRules.parseAction("switch_config:Home.yaml").background());
    // ----- limiter: repeat 30 s, window 60 s, burst 3
    NetworkMatchRules.Limiter l = new NetworkMatchRules.Limiter(30000L, 60000L, 3);
    StringBuilder lim = new StringBuilder();
    lim.append(l.allow("A", 0)).append(',').append(l.allow("A", 1000)).append(',').append(l.allow("B", 2000)).append(',')
       .append(l.allow("C", 3000)).append(',').append(l.allow("D", 4000)).append(',').append(l.allow("A", 31000)).append(',')
       .append(l.allow("D", 61000)).append(',').append(l.allow("E", 62000));
    put("limiter", lim.toString());
    // A flapping network (decision alternates every second for 10 minutes) never exceeds 3 per minute.
    NetworkMatchRules.Limiter flap = new NetworkMatchRules.Limiter(30000L, 60000L, 3);
    int ran = 0, worstMinute = 0; ArrayDeque<Long> win = new ArrayDeque<>();
    for (long t = 0; t < 600000L; t += 1000L) {
      if (flap.allow((t / 1000L) % 2 == 0 ? "home|true|switch_config:Home.yaml" : "cell|false|run_script:x.sh", t)) {
        ran++; win.add(t); while (t - win.peekFirst() >= 60000L) win.pollFirst(); worstMinute = Math.max(worstMinute, win.size());
      }
    }
    put("flapRuns", ran); put("flapWorstMinute", worstMinute);
    // ----- script command
    put("script", NetworkMatchRules.scriptCommand("on-home.sh", "network-match", "Wi‑Fi · Home's \"5G\"\n", "tproxy", "Home.yaml"));
    String bad = "";
    for (String n : new String[]{"../x.sh", "pre-start.sh", "x", "a b.sh"}) {
      try { NetworkMatchRules.scriptCommand(n, "network-match", "", "", ""); bad += "accepted:" + n + ";"; } catch (IllegalArgumentException ok) { bad += "refused;"; }
    }
    try { NetworkMatchRules.scriptCommand("ok.sh", "boot", "", "", ""); bad += "accepted-event;"; } catch (IllegalArgumentException ok) { bad += "refused-event;"; }
    put("scriptRefusals", bad);
    // ----- shared network rules
    put("ifaces", SharedNetworkRules.sanitizeInterfaces(List.of("wlan2", "rndis0", "ap+", " ncm0 ")));
    String refused = "";
    for (String n : new String[]{"lo", "rmnet_data0", "ccmni1", "tun0", "bad iface", "hetu0"}) {
      try { SharedNetworkRules.sanitizeInterfaces(List.of(n)); refused += "accepted:" + n + ";"; } catch (java.io.IOException ok) { refused += "refused;"; }
    }
    put("ifaceRefusals", refused);
    List<String> many = new ArrayList<>(); for (int i = 0; i < 17; i++) many.add("rndis" + i);
    try { SharedNetworkRules.sanitizeInterfaces(many); put("ifaceCap", "accepted"); } catch (java.io.IOException ok) { put("ifaceCap", "refused"); }
    put("bodyLegacy", SharedNetworkRules.policyBody(none, "direct", List.of("aa:aa:aa:aa:aa:01")));
    put("bodyScoped", SharedNetworkRules.policyBody(List.of("wlan2"), "direct", List.of("aa:aa:aa:aa:aa:01")));
    put("bodyOnly", SharedNetworkRules.policyBody(none, "only", List.of("BB:BB:BB:BB:BB:02", "aa:aa:aa:aa:aa:01")));
    put("bodyBogusMode", SharedNetworkRules.policyBody(List.of("wlan2"), "all", none));
    try { SharedNetworkRules.policyBody(none, "only", List.of("ff:ff:ff:ff:ff:ff")); put("broadcast", "accepted"); } catch (java.io.IOException ok) { put("broadcast", "refused"); }
    Set<String> up = Set.of("wlan0");
    put("candidates", SharedNetworkRules.tetherCandidate("wlan2", up, Set.of()) + "," + SharedNetworkRules.tetherCandidate("wlan0", up, Set.of())
        + "," + SharedNetworkRules.tetherCandidate("rndis0", up, Set.of()) + "," + SharedNetworkRules.tetherCandidate("rmnet_data0", up, Set.of())
        + "," + SharedNetworkRules.tetherCandidate("p2p-wlan0-0", up, Set.of("p2p-wlan0-0")) + "," + SharedNetworkRules.tetherCandidate("eth0", Set.of("eth0"), Set.of())
        + "," + SharedNetworkRules.tetherCandidate("wlan0", up, Set.of("wlan0")));
    put("routeDev", SharedNetworkRules.routeDevice("8.8.8.8 via 10.0.0.1 dev rmnet_data2 table 1003 src 10.1.2.3 uid 0"));
    put("tethered", String.join(",", SharedNetworkRules.tetheredInterfaces("  wlan2 - TetheredState - lastError = 0\n  rndis0 - TETHERED\n  wlan0 - AvailableState\n  ncm0 - UnTethered\nTethered: [ap0, usb0]")));
    StringBuilder leases = new StringBuilder();
    for (SharedNetworkRules.Lease x : SharedNetworkRules.parseLeases("1760000000 AA:BB:CC:DD:EE:01 192.168.43.12 Pixel-8 01:aa\n1760000001 aa:bb:cc:dd:ee:02 192.168.43.13 * *\nbroken line\n0 zz:zz 1.2.3.4 x"))
      leases.append(x.mac).append('/').append(x.ip).append('/').append(x.hostname).append('|');
    put("leases", leases.toString());
    out.append('}');
    System.out.println(out);
  }
}
'''


def compile_and_run():
    with tempfile.TemporaryDirectory(prefix='hetu-netmatch-') as temp:
        root = Path(temp); src = root / 'src/io/github/xgl34222220/hetu'; src.mkdir(parents=True)
        for name in ('NetworkMatchRules.java', 'SharedNetworkRules.java'):
            (src / name).write_text((PKG / name).read_text())
        (src / 'NetMatchHarness.java').write_text(HARNESS)
        javac = ['javac'] if shutil.which('javac') else ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main']
        subprocess.run([*javac, '--release', '17', '-d', str(root / 'classes'), *map(str, src.glob('*.java'))],
                       check=True, timeout=120, capture_output=True, text=True)
        r = subprocess.run(['java', '-cp', str(root / 'classes'), 'io.github.xgl34222220.hetu.NetMatchHarness'],
                           check=True, timeout=60, capture_output=True, text=True)
        return json.loads(r.stdout)


class NetMatch93(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.r = compile_and_run()

    def test_conditions_cover_wifi_cellular_sim_slot_and_mcc_mnc(self):
        r = self.r
        self.assertTrue(r['wifiAnyWhenEmpty']); self.assertTrue(r['wifiSsidAndBssid']); self.assertFalse(r['wifiWrongBssid'])
        self.assertFalse(r['cellularNoRule']); self.assertTrue(r['cellularAny'])
        self.assertTrue(r['slot2Matches']); self.assertFalse(r['slot1NotSlot2']); self.assertFalse(r['slotUnknown'])
        self.assertFalse(r['slotNeedsCellular'])
        self.assertTrue(r['mccSim']); self.assertTrue(r['mccRoamingNetwork']); self.assertFalse(r['mccNoMatch']); self.assertFalse(r['mccOnWifi'])
        self.assertEqual('310150,46000,46011', r['mccSanitized'])
        self.assertEqual('1,2', r['slotSanitized'])
        self.assertEqual(('46000', '46011', '', ''), (r['propSlot1'], r['propSlot2'], r['propEmptySlot'], r['propMissing']))
        self.assertEqual('移动数据 · 卡2 · 46011 · 漫游 44010', r['labelCell'])
        self.assertEqual('Wi‑Fi · Home · aa:bb:cc:dd:ee:ff', r['labelWifi'])

    def test_actions_parse_and_reject_malformed_targets(self):
        self.assertEqual('start=|stop=|none=|switch=Home.yaml|script=on-home.sh|none=|none=|none=|none=|none=|none=|none=|', self.r['actions'])
        self.assertEqual('switch_config:Home.yaml', self.r['encodeSwitch']); self.assertEqual('run_script:on-home.sh', self.r['encodeScript'])
        self.assertEqual('false,true', self.r['backgroundKinds'])

    def test_limiter_debounces_repeats_and_bounds_bursts(self):
        # A at 0 runs; A again at 1 s is a repeat; B, C run; D is the 4th in a minute; A at 31 s:
        # window still holds 3 (0,2,3 s) -> refused; D at 61 s and E at 62 s run once the window moved.
        self.assertEqual('true,false,true,true,false,false,true,true', self.r['limiter'])
        self.assertLessEqual(self.r['flapWorstMinute'], 3)
        self.assertLessEqual(self.r['flapRuns'], 30)

    def test_script_command_is_bounded_logged_and_preserves_exit_code(self):
        cmd = self.r['script']
        self.assertIn("test -f '/data/adb/hetu/scripts/on-home.sh' || exit 44;", cmd)
        self.assertIn("export HETU_EVENT='network-match';", cmd)
        self.assertIn('timeout 12 sh', cmd)
        self.assertNotIn('\n', cmd, 'control characters are stripped from the network label')
        self.assertEqual('refused;refused;refused;refused;refused-event;', self.r['scriptRefusals'])
        with tempfile.TemporaryDirectory(prefix='hetu-netmatch-sh-') as temp:
            base = Path(temp) / 'hetu'; (base / 'scripts').mkdir(parents=True); (base / 'run').mkdir()
            local = cmd.replace('/data/adb/hetu', str(base)).replace('timeout 12 ', 'timeout 1 ')
            script = base / 'scripts/on-home.sh'
            script.write_text('echo "event=$HETU_EVENT network=$HETU_NETWORK mode=$HETU_MODE config=$HETU_CONFIG"\nexit 7\n')
            r = subprocess.run(['sh', '-c', local], capture_output=True, text=True, timeout=20)
            self.assertEqual(7, r.returncode, r.stderr)
            log = (base / 'run/scripts.log').read_text()
            self.assertIn("event=network-match network=Wi‑Fi · Home's \"5G\" mode=tproxy config=Home.yaml", log)
            self.assertIn('[network-match] exit=7', log)
            if shutil.which('timeout'):
                script.write_text('sleep 5\n')
                r = subprocess.run(['sh', '-c', local], capture_output=True, text=True, timeout=20)
                self.assertEqual(124, r.returncode)
                self.assertIn('[network-match] exit=124', (base / 'run/scripts.log').read_text())
            script.unlink()
            r = subprocess.run(['sh', '-c', local], capture_output=True, text=True, timeout=20)
            self.assertEqual(44, r.returncode)

    def test_shared_network_rules(self):
        r = self.r
        self.assertEqual('ap+,ncm0,rndis0,wlan2', r['ifaces'])
        self.assertEqual('refused;' * 6, r['ifaceRefusals'])
        self.assertEqual('refused', r['ifaceCap'])
        self.assertEqual('', r['bodyLegacy'], 'blacklist without interfaces keeps the legacy unscoped rules')
        self.assertEqual('IFACES=wlan2\nMODE=direct\nMACS=\n', r['bodyScoped'])
        self.assertEqual('IFACES=\nMODE=only\nMACS=aa:aa:aa:aa:aa:01,bb:bb:bb:bb:bb:02\n', r['bodyOnly'])
        self.assertEqual('IFACES=wlan2\nMODE=direct\nMACS=\n', r['bodyBogusMode'])
        self.assertEqual('refused', r['broadcast'])
        self.assertEqual('true,false,true,false,true,false,false', r['candidates'])
        self.assertEqual('rmnet_data2', r['routeDev'])
        self.assertEqual('ap0,rndis0,usb0,wlan2', r['tethered'])
        self.assertEqual('aa:bb:cc:dd:ee:01/192.168.43.12/Pixel-8|aa:bb:cc:dd:ee:02/192.168.43.13/|', r['leases'])

    def test_wiring_permissions_and_locks(self):
        manifest = (SRC / 'AndroidManifest.xml').read_text()
        self.assertNotIn('READ_PHONE_STATE', manifest)
        service = (PKG / 'ProxyNetworkMatchService.java').read_text()
        self.assertNotIn('getActiveSubscriptionInfoList', service)
        self.assertIn('SubscriptionManager.getDefaultDataSubscriptionId()', service)
        self.assertIn('getprop gsm.sim.operator.numeric; getprop gsm.operator.numeric', service)
        self.assertIn('new NetworkMatchRules.Limiter(30000L,60000L,3)', service)
        self.assertIn('},NetworkMatchRules.STABLE_DELAY_MS);', service)
        bg = service[service.index('private void scheduleBackgroundAction('):service.index('private static String actionText(')]
        self.assertNotIn('CONTROL_LOCK', bg, 'the script never runs inside the control lock')
        self.assertIn('generation!=automaticStopGeneration', bg)
        manager = (PKG / 'RootProxyManager.java').read_text()
        sw = manager[manager.index('JSONObject switchConfigForAutomation('):manager.index('JSONObject startIfWanted(ProxyRuntimeProfile profile)throws')]
        self.assertIn('startInternal(profile,null,true)', sw, 'validate-then-swap restart')
        self.assertIn('configs.select(profile.core,from)', sw, 'refused restart restores the previous selection')
        self.assertNotIn('proxyRootSessionOwner', sw, 'owner unchanged')
        self.assertIn('new StartIntent(request,false,false,currentDecision)', sw)
        self.assertIn('String sharePolicy=policyDir+"/share";', manager)
        self.assertIn('SharedNetworkRules.policyBody(', manager)
        adapter = (PKG / 'tools/ToolsFeatureAdapter.kt').read_text()
        save = adapter[adapter.index('saveShare = { value ->'):adapter.index('/* ------------------------------ CNIP')]
        self.assertNotIn('Keys.BypassInterfaces', save, 'the share page never writes 绕过规则 interfaces')
        self.assertIn('Keys.SharedInterfaces', save)
        legacy = (PKG / 'app/ToolScreens.kt').read_text()
        screen = legacy[legacy.index('fun saveIfaces('):legacy.index('fun saveMacs(')]
        self.assertNotIn('proxyBypassInterfaces', screen)


if __name__ == '__main__':
    unittest.main(verbosity=2)
