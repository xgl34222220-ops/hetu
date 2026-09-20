#!/usr/bin/env python3
"""Linux kernel packet regression, only inside a NEW disposable network namespace."""
import json,os,socket,subprocess,sys,tempfile,threading
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def run(*args):return subprocess.run(args,text=True,capture_output=True,check=True,timeout=20).stdout
if '--inside' not in sys.argv:
    if os.geteuid()!=0:raise SystemExit('Run via sudo; this test only operates inside unshare --net')
    os.execvp('unshare',['unshare','--net',sys.executable,__file__,'--inside'])
assert os.readlink('/proc/self/ns/net')!=os.readlink('/proc/1/ns/net'), 'Refuse host network namespace'
run('ip','link','set','lo','up');run('ip','link','add','underlay','type','dummy');run('ip','addr','add','192.0.2.2/24','dev','underlay');run('ip','link','set','underlay','up');run('ip','route','add','default','dev','underlay')
for iface in ('all','default','lo','underlay'):
    run('sysctl','-qw',f'net.ipv4.conf.{iface}.rp_filter=0')
listeners=[]
for proto,port in ((socket.SOCK_STREAM,29090),(socket.SOCK_STREAM,19898),(socket.SOCK_DGRAM,19898),(socket.SOCK_STREAM,11053),(socket.SOCK_DGRAM,11053)):
    sock=socket.socket(socket.AF_INET,proto);sock.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
    if port==19898:sock.setsockopt(socket.SOL_IP,19,1) # IP_TRANSPARENT
    sock.bind(('0.0.0.0',port))
    if proto==socket.SOCK_STREAM:sock.listen(16)
    listeners.append(sock)
def echo():
    while True:
        c,_=listeners[1].accept()
        with c:
            c.settimeout(2);c.recv(8);c.sendall(b'hetu-ok')
threading.Thread(target=echo,daemon=True).start()
with tempfile.TemporaryDirectory(prefix='hetu-netns-') as td:
    d=Path(td);(d/'run').mkdir();functions=ROOT/'android-app/app/src/main/assets/hetu-root.sh'
    (d/'functions.sh').write_text(functions.read_text().split('case "${1:-status}" in')[0])
    prefix=f'''. '{d}/functions.sh'
RUN='{d}/run'; BASE='{d}'; PIDFILE="$RUN/core.pid"; NET_STATE="$RUN/net.state"; SESSION="$RUN/session.state"; MODEFILE="$RUN/mode"; LOCK_DIR="$RUN/lock"
pidcore(){{ return 0; }}; core_maybe_alive(){{ return 0; }}
'''
    def shell(body):
        x=subprocess.run(['sh'],input=prefix+body,text=True,capture_output=True,timeout=20)
        assert x.returncode==0,(x.stdout,x.stderr);return x.stdout
    shell(f'''printf '%s\\n' {os.getpid()} > "$PIDFILE"
START_MODE=tproxy; START_TCP=1; START_UDP=1; START_TP=19898; START_RP=0
MARK=0x200000; MASK=0x200000; TABLE=20260; PREF=14500; savenet
write_session tproxy bypass redirect 11053 core 0 0 29090 ''
iptables -t mangle -N OEM_KEEP; iptables -t mangle -A OEM_KEEP -j RETURN; iptables -t mangle -A OUTPUT -j OEM_KEEP
install_mangle4 19898 tproxy 1 1 redirect core '' 0 '' '' '' || exit 1
install_dns_redirect4 11053 core '' 0 '' || exit 1
health_record || exit 1
''')
    client='import socket; s=socket.create_connection(("198.51.100.24",443),2); s.sendall(b"ping"); assert s.recv(7)==b"hetu-ok"'
    def packet():
        x=subprocess.run(['setpriv','--reuid','10001','--regid','10001','--clear-groups',sys.executable,'-c',client],capture_output=True,text=True,timeout=6)
        assert x.returncode==0,x.stderr
    packet()
    for command in (
        ['ip','rule','del','pref','14500','fwmark','0x200000/0x200000','table','20260'],
        ['ip','route','del','local','0.0.0.0/0','dev','lo','table','20260'],
        ['iptables','-t','mangle','-D','PREROUTING','-j','HETU_MPRE'],
        ['iptables','-t','mangle','-F','HETU_MPRE'],
        ['iptables','-t','nat','-F','HETU_DNSOUT'],
    ):
        run(*command)
        status=json.loads(shell('health_json'))
        assert status['networkIntegrity']=='degraded',status
        (d/'run/network-repair-at').unlink(missing_ok=True)
        status=json.loads(shell(f'health_repair {os.getpid()}\nhealth_json'))
        assert status['networkIntegrity']=='healthy',status
        packet()
        run('iptables','-t','mangle','-C','OUTPUT','-j','OEM_KEEP');run('iptables','-t','mangle','-C','OEM_KEEP','-j','RETURN')
    # IPv6 app guard preserves infrastructure but rejects normal app UID flows.
    shell('install_v6_disable 0 || exit 1')
    rules=run('ip6tables','-t','filter','-S','HETU_V6OUT')
    assert '0-9999' in rules and '--dport 53' in rules and '-j REJECT' in rules
print('Real Linux netns: 5 rule/route/chain faults repaired; 6 TCP app packets delivered; OEM chains preserved')
