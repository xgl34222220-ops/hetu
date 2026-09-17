from pathlib import Path
import re

ROOT = Path("android-app/app/src/main/assets/proxy-root-v3.sh")
text = ROOT.read_text(encoding="utf-8")

start_marker = "start(){\n"
status_marker = "\nstatus(){\n"
case_marker = '\ncase "${1:-status}" in\n'
if start_marker not in text or status_marker not in text or case_marker not in text:
    raise SystemExit("test48 root fix: controller function markers missing")

start_at = text.index(start_marker)
status_at = text.index(status_marker, start_at)
case_at = text.index(case_marker, status_at)
start = text[start_at:status_at]
status = text[status_at:case_at]

# /system/bin/sh functions share variables unless explicitly isolated. The old start()
# stored the Mihomo path in BIN and the mode in M; cleanup()/unhook()/loadnet() reused
# those names, so BIN became ip6tables before the launch line. The result was literally
# `ip6tables -d ... -f startup-config`, which fails on IPv6 legacy iptables.
old_start_assign = (
    '  BIN="$1"; CFG="$2"; M="$3"; TP="$4"; RP="$5"; V6="$6"; TCP="$7"; UDP="$8"; '
    'DNS="$9"; QUIC="${10}"; DP="${11}"; CP="${12}"; S="${13}"; UIDS="${14}"; '
    'SHARE="${15}"; KILL="${16}"; CIDRS="${17}"; IFACES="${18}"'
)
new_start_assign = (
    '  START_BIN="$1"; START_CFG="$2"; START_MODE="$3"; START_TP="$4"; START_RP="$5"; '
    'START_V6="$6"; START_TCP="$7"; START_UDP="$8"; START_DNS="$9"; START_QUIC="${10}"; '
    'START_DP="${11}"; START_CP="${12}"; START_SCOPE="${13}"; START_UIDS="${14}"; '
    'START_SHARE="${15}"; START_KILL="${16}"; START_CIDRS="${17}"; START_IFACES="${18}"'
)
if old_start_assign in start:
    start = start.replace(old_start_assign, new_start_assign, 1)
elif new_start_assign not in start:
    raise SystemExit("test48 root fix: start() argument layout changed")

start_vars = {
    "BIN": "START_BIN",
    "CFG": "START_CFG",
    "M": "START_MODE",
    "TP": "START_TP",
    "RP": "START_RP",
    "V6": "START_V6",
    "TCP": "START_TCP",
    "UDP": "START_UDP",
    "DNS": "START_DNS",
    "QUIC": "START_QUIC",
    "DP": "START_DP",
    "CP": "START_CP",
    "S": "START_SCOPE",
    "UIDS": "START_UIDS",
    "SHARE": "START_SHARE",
    "KILL": "START_KILL",
    "CIDRS": "START_CIDRS",
    "IFACES": "START_IFACES",
}
for old, new in sorted(start_vars.items(), key=lambda item: -len(item[0])):
    start = re.sub(rf"\${re.escape(old)}\b", f"${new}", start)

if " P=$!;" in start:
    start = start.replace(" P=$!;", " START_PID=$!;", 1)
elif " START_PID=$!;" not in start:
    raise SystemExit("test48 root fix: core PID capture changed")
start = re.sub(r"\$P\b", "$START_PID", start)

# status() had the same global-name hazard: loadnet() overwrote P/M, corrupting the
# reported PID and mode. Keep UI/runtime status stable while fixing the launch path.
if "  root; R=false; P=0" in status:
    status = status.replace("  root; R=false; P=0", "  root; STATUS_RUNNING=false; STATUS_PID=0", 1)
elif "STATUS_RUNNING=false; STATUS_PID=0" not in status:
    raise SystemExit("test48 root fix: status() state layout changed")
if '  M=$(cat "$MODEFILE" 2>/dev/null || echo none);' in status:
    status = status.replace(
        '  M=$(cat "$MODEFILE" 2>/dev/null || echo none);',
        '  STATUS_MODE=$(cat "$MODEFILE" 2>/dev/null || echo none);',
        1,
    )
elif "STATUS_MODE=$(cat" not in status:
    raise SystemExit("test48 root fix: status() mode layout changed")
status = status.replace('R=true; P="$X"', 'STATUS_RUNNING=true; STATUS_PID="$X"')
status = status.replace("M=none", "STATUS_MODE=none")
status = re.sub(r"\$R\b", "$STATUS_RUNNING", status)
status = re.sub(r"\$P\b", "$STATUS_PID", status)
status = re.sub(r"\$M\b", "$STATUS_MODE", status)

# Hard guards: never allow a firewall binary to inherit the Mihomo CLI -f config flag.
required = [
    '"$START_BIN" -d "$RUN" -f "$START_CFG"',
    'wait_ready "$START_PID" "$START_MODE"',
    'start_watchdog "$START_PID"',
    '"$STATUS_RUNNING" "$STATUS_PID" "$STATUS_MODE"',
]
combined = start + status
for token in required:
    if token not in combined:
        raise SystemExit(f"test48 root fix: missing invariant {token}")
for old in start_vars:
    if re.search(rf"\${re.escape(old)}\b", start):
        raise SystemExit(f"test48 root fix: unscoped start variable remains: ${old}")

text = text[:start_at] + start + status + text[case_at:]
ROOT.write_text(text, encoding="utf-8")
print("Applied test48 Root shell scope fix: Mihomo launch path/PID/mode are isolated")
