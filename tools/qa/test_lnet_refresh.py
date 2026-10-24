#!/usr/bin/env python3
"""7.0.0: on-link bypass refresh (HETU_LNET) after a Wi-Fi network change.

Runs the real lnet_* functions from hetu-root.sh against stub `ip` / `iptables` binaries.
"""
import os, re, subprocess, tempfile, unittest, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = (ROOT / "android-app/app/src/main/assets/hetu-root.sh").read_text(encoding="utf-8")

def func(name):
    m = re.search(r"^%s\(\)\{.*?^\}\n" % re.escape(name), SCRIPT, re.S | re.M)
    if not m:  # one-line definitions
        m = re.search(r"^%s\(\)\{.*?\}\n" % re.escape(name), SCRIPT, re.M)
    assert m, name
    return m.group(0)

def var(name):
    m = re.search(r"^%s=.*$" % name, SCRIPT, re.M); assert m, name; return m.group(0)

IPT = r'''#!/bin/sh
# Minimal per-command iptables model: state file holds "-N chain" / "-A chain spec" lines per table.
st="$STUB_DIR/$(basename "$0").state"; touch "$st"; echo "$(basename "$0") $*" >> "$STUB_DIR/calls"
case " $* " in *" restore "*|*-restore*) exit 9;; esac
[ "$1" = -w ] && shift 2
[ "$1" = -t ] && { t="$2"; shift 2; }
op="$1"; shift; c="$1"; shift; rest="$*"
key="$t $c"
case "$op" in
  -S) grep -q "^$t -N $c\$" "$st" || [ "$c" = OUTPUT ] || exit 1; echo "-N $c"; grep "^$t -A $c " "$st" | sed "s/^$t //"; exit 0;;
  -N) grep -q "^$t -N $c\$" "$st" && exit 1; echo "$t -N $c" >> "$st";;
  -A) echo "$t -A $c $rest" >> "$st";;
  -I) n="$1"; shift; rest="$*"; { grep "^$t -A $c " "$st" | head -n $((n-1)); echo "$t -A $c $rest"; } > "$st.h"; grep -v "^$t -A $c " "$st" > "$st.o"; grep "^$t -A $c " "$st" | tail -n +"$n" > "$st.t"; cat "$st.o" "$st.h" "$st.t" > "$st"; rm -f "$st.h" "$st.o" "$st.t";;
  -C) grep -Fqx -- "$t -A $c $rest" "$st";;
  -D) grep -Fqx -- "$t -A $c $rest" "$st" || exit 1; awk -v l="$t -A $c $rest" '$0==l && !d {d=1; next} {print}' "$st" > "$st.n"; mv "$st.n" "$st";;
  *) exit 2;;
esac
'''
IP = r'''#!/bin/sh
case "$*" in
  "-o -4 route show table all") cat "$STUB_DIR/r4";;
  "-o -6 route show table all") cat "$STUB_DIR/r6";;
  *"-4 addr show scope global"*) cat "$STUB_DIR/a4";;
  *"-6 addr show scope global"*) cat "$STUB_DIR/a6";;
  *) exit 0;;
esac
'''

class LnetTest(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        for n, body in (("iptables", IPT), ("ip6tables", IPT), ("ip", IP)):
            p = os.path.join(self.d, n); open(p, "w").write(body); os.chmod(p, 0o755)
        for f in ("r4", "r6", "a4", "a6", "calls"): open(os.path.join(self.d, f), "w").close()
        self.prelude = "\n".join([
            var("LAN_RETURN_V4"), var("LAN_RETURN_V6"), var("BYPASS_MARK"), var("BYPASS_MASK"), "LNET=HETU_LNET",
            'FAKE_IP_V4="198.18.0.0/15"; FAKE_IP_V6="fc00::/18"',
            func("has"), "has(){ [ \"$1\" != timeout ] && command -v \"$1\" >/dev/null 2>&1; }",
            "v6supported(){ return 0; }",
            func("lnet_x"), func("lnet_iface"), func("lnet_wanted"), func("lnet_apply"),
        ])

    def routes(self, r4="", r6=""):
        open(os.path.join(self.d, "r4"), "w").write(r4); open(os.path.join(self.d, "r6"), "w").write(r6)

    def sh(self, body):
        env = dict(os.environ, PATH=self.d + ":" + os.environ["PATH"], STUB_DIR=self.d)
        return subprocess.run(["sh", "-c", self.prelude + "\n" + body], env=env, capture_output=True, text=True)

    def state(self, fam="iptables"):
        p = os.path.join(self.d, fam + ".state"); return open(p).read() if os.path.exists(p) else ""

    def test_private_subnet_change_needs_nothing(self):
        self.routes("192.168.2.0/23 dev wlan0 table wlan0 proto kernel scope link src 192.168.2.156\n"
                    "192.168.1.0/24 dev wlan0 table wlan0 proto kernel scope link src 192.168.1.141\n"
                    "10.0.0.0/8 dev eth0 proto kernel scope link\n")
        r = self.sh('lnet_wanted 4'); self.assertEqual(r.stdout.strip(), "", r.stderr)
        r = self.sh('lnet_apply 4 ""'); self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.state(), "")

    def test_public_v4_lan_and_v6_prefix_added(self):
        self.routes("203.0.113.0/24 dev wlan0 table wlan0 proto kernel scope link src 203.0.113.9\n"
                    "default via 203.0.113.1 dev wlan0 table wlan0\n"
                    "100.70.0.0/16 dev rmnet_data0 proto kernel scope link\n"
                    "198.18.0.0/16 dev wlan0 proto kernel scope link\n",
                    "240e:3a1:2:3::/64 dev wlan0 table 1002 proto kernel metric 256\n"
                    "fe80::/64 dev wlan0 table local proto kernel metric 256\n"
                    "2408:8000::/64 dev rmnet_data1 proto ra metric 256\n"
                    "240e::/20 dev wlan0 proto ra\n")
        self.assertEqual(self.sh("lnet_wanted 4").stdout.split(), ["203.0.113.0/24"])
        self.assertEqual(self.sh("lnet_wanted 6").stdout.split(), ["240e:3a1:2:3::/64"])
        r = self.sh('lnet_apply 4 "$(lnet_wanted 4)"'); self.assertEqual(r.returncode, 0, r.stderr)
        st = self.state()
        self.assertIn("mangle -A HETU_LNET -p udp --dport 53 -j RETURN", st)
        self.assertIn("mangle -A HETU_LNET -d 203.0.113.0/24 -j MARK --set-xmark 0x08000000/0x08000000", st)
        self.assertIn("mangle -A OUTPUT -j HETU_LNET", st)

    def test_change_removes_old_prefix_per_command(self):
        self.routes("203.0.113.0/24 dev wlan0 proto kernel scope link\n")
        self.sh('lnet_apply 4 "$(lnet_wanted 4)"')
        self.routes("198.51.100.0/24 dev wlan0 proto kernel scope link\n")
        r = self.sh('lnet_apply 4 "$(lnet_wanted 4)"'); self.assertEqual(r.returncode, 0, r.stderr)
        st = self.state()
        self.assertNotIn("203.0.113.0/24", st); self.assertIn("198.51.100.0/24", st)
        self.assertEqual(st.count("-A OUTPUT -j HETU_LNET"), 1)
        calls = open(os.path.join(self.d, "calls")).read()
        self.assertNotIn("restore", calls)
        self.assertIn("-D HETU_LNET -d 203.0.113.0/24", calls)

    def test_cap_and_validation(self):
        self.routes("".join("203.0.%d.0/24 dev wlan0 proto kernel scope link\n" % i for i in range(30))
                    + "8.0.0.0/8 dev wlan0 proto kernel scope link\n")
        self.assertEqual(len(self.sh("lnet_wanted 4").stdout.split()), 16)

    def test_dispatch_cleanup_and_watchdog_wiring(self):
        self.assertIn('lnet-sync) [ "$#" = 1 ] || exit 1; root; lnet_sync;;', SCRIPT)
        self.assertIn('unhook xt4 mangle OUTPUT "$LNET"', SCRIPT)
        self.assertIn('unhook xt6 mangle OUTPUT "$LNET"', SCRIPT)
        self.assertIn("OUTPUT:HETU_LNET", func("force_known"))
        self.assertIn("try_lock_once || exit 3", func("lnet_sync"))
        self.assertIn("wd_lnet_tick", SCRIPT.split("watchdog(){", 1)[1])
        self.assertNotIn("iptables-restore", func("lnet_apply") + func("lnet_sync"))
        # The chain is not part of the integrity manifest, so a refresh never reads as drift.
        self.assertNotIn("LNET", func("health_owned"))

if __name__ == "__main__":
    unittest.main()
