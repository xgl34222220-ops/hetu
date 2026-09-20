#!/usr/bin/env python3
"""Execute the actual optional IPv6 DNS path with missing NAT and warm preflight.
No real host firewall modification. Packet/route tests remain a separate netns CI gate.
"""
import json,os,subprocess,tempfile,shutil,time
from pathlib import Path
from test_network_integrity import FAKE, initial, SCRIPT

FAKE = FAKE.replace("chains=state[str(family)+':'+table]", """key=str(family)+':'+table
 if os.environ.get('NO_TABLE')==key:
  print(\"ip6tables v1.8.11 (legacy): can't initialize ip6tables table `nat': Table does not exist (do you need to insmod?)\",file=sys.stderr);sys.exit(3)
 if os.environ.get('UNKNOWN_TABLE')==key:print('Permission denied',file=sys.stderr);sys.exit(4)
 if os.environ.get('NO_REDIRECT_UDP') and family==6 and table=='nat' and 'REDIRECT' in args and 'udp' in args:sys.exit(2)
 chains=state[key]""")
checks=0
with tempfile.TemporaryDirectory(prefix='hetu-no-ip6nat-') as td:
 d=Path(td);(d/'bin').mkdir();(d/'run').mkdir();(d/'calls').write_text('')
 (d/'functions.sh').write_text(SCRIPT.read_text().split('case "${1:-status}" in')[0])
 for n in ('iptables','ip6tables','iptables-restore','ip6tables-restore','ip','ss'):
  p=d/'bin'/n;p.write_text(FAKE);p.chmod(0o755)
 prefix='''. "$HETU_TEST_DIR/functions.sh"
RUN="$HETU_TEST_DIR/run"; BASE="$HETU_TEST_DIR"; PIDFILE="$RUN/core.pid"; NET_STATE="$RUN/net.state"
SESSION="$RUN/session.state"; MODEFILE="$RUN/mode"; LOCK_DIR="$RUN/.txn.lock"; WATCHDOG_PID="$RUN/watchdog.pid"
START_ERROR="$RUN/start-error"; START_STATE="$RUN/start-state"; START_TIMING="$RUN/startup-timing"
IPV6_STATE="$RUN/ipv6.state"; V6_CONF="$HETU_TEST_DIR/conf"
root(){ :; }; pidcore(){ return 0; }; core_maybe_alive(){ return 0; }; findcorepid(){ return 1; }; v6supported(){ return 0; }
START_MODE=tproxy; START_V6=disable; START_TCP=1; START_UDP=1; START_TP=19898; START_RP=0
START_DNS=redirect; START_DP=11053; START_SCOPE=core; START_UIDS=''; START_SHARE=0; START_IFACES=''
'''
 def shell(body,fail=False,**extra):
  env=dict(os.environ,HETU_TEST_DIR=td,PATH=str(d/'bin')+':'+os.environ['PATH'],**extra)
  p=subprocess.run(['sh'],input=prefix+body,text=True,capture_output=True,env=env,timeout=35)
  if fail:assert p.returncode!=0,(p.stdout,p.stderr)
  else:assert p.returncode==0,(p.stdout,p.stderr)
  return p.stdout
 def reset():
  (d/'net.json').write_text(json.dumps(initial()));(d/'calls').write_text('')
  shutil.rmtree(d/'run');(d/'run').mkdir()
 def state():return json.loads((d/'net.json').read_text())
 pid=os.getpid()
 setup=f'''printf '%s\\n' {pid} > "$PIDFILE"; printf '%s\\n' {pid} > "$WATCHDOG_PID"
MARK=0x200000; MASK=0x200000; TABLE=20260; PREF=14500; savenet
select_dns6_policy
write_session tproxy disable redirect 11053 core "$START_SHARE" 0 29090 ''
install_mangle4 19898 tproxy 1 1 redirect core '' "$START_SHARE" '' '' '' || exit 1
install_dns_redirect4 11053 core '' "$START_SHARE" '' || exit 1
install_v6_disable "$START_SHARE" || exit 1
install_disabled_dns6 || exit 1
health_record || exit 1
health_json
'''
 for shared in (0,1):
  reset();result=json.loads(shell(f'START_SHARE={shared}\n'+setup,NO_TABLE='6:nat'))
  assert result['dataPlaneHealthy'] and result['ipv6DnsPolicy']=='blocked-no-nat',result;checks+=1
  assert not (d/'run/network-manifest/6-nat').exists();checks+=1
  s=state();guard=s['6:filter']['HETU_V6OUT']
  for proto in ('tcp','udp'):
   assert ['-p',proto,'--dport','53','-j','REJECT'] in guard;checks+=1
  assert guard.index(['-p','udp','--dport','53','-j','REJECT']) < guard.index(['-m','owner','--uid-owner','0-9999','-j','RETURN']);checks+=1
  assert any('REDIRECT' in r for r in s['4:nat']['HETU_DNSOUT']);checks+=1
  if shared:assert s['6:filter']['HETU_V6FWD']==[['-j','REJECT']];checks+=1
  (d/'calls').write_text('');after=json.loads(shell('health_json',NO_TABLE='6:nat'))
  calls=[json.loads(x) for x in (d/'calls').read_text().splitlines()]
  assert after['dataPlaneHealthy'] and not any(c[0]=='ip6tables' and 'nat' in c for c in calls);checks+=1
  s['6:filter']['HETU_V6OUT']=[];(d/'net.json').write_text(json.dumps(s))
  before=json.loads(shell('health_json',NO_TABLE='6:nat'));assert not before['dataPlaneHealthy'];checks+=1
  after=json.loads(shell(f'health_repair {pid}\nhealth_json',NO_TABLE='6:nat'));assert after['dataPlaneHealthy'];checks+=1
 # Missing redirect target also fails closed, but a readable NAT table keeps the normal path.
 for env,expect in (({},'redirect'),({'NO_REDIRECT_UDP':'1'},'blocked-no-redirect')):
  reset();result=json.loads(shell(setup,**env));assert result['dataPlaneHealthy'] and result['ipv6DnsPolicy']==expect;checks+=1
  assert (d/'run/network-manifest/6-nat').exists()==(expect=='redirect');checks+=1
 # Permission/lock/read errors cannot be laundered into 'unsupported'.
 reset();shell('select_dns6_policy\nprintf unsafe > "$RUN/mutated"\n',fail=True,UNKNOWN_TABLE='6:nat')
 assert not (d/'run/mutated').exists();checks+=1
 # Explicit IPv6 enable still requires IPv6 NAT; do not silently weaken it.
 reset();shell("probe_ingress tproxy 19898 0 enable 1 1 redirect 0 0 11053\n",fail=True,NO_TABLE='6:nat');checks+=1
 # Even a cached start makes a fresh decision before cleanup or launching a core.
 reset();stub='''
preflight(){ echo unexpected-preflight >&2; exit 1; }
cleanup(){ [ "$START_DNS6" = blocked-no-nat ] || exit 71; printf checked > "$RUN/cached-path-ok"; exit 0; }
stopwatchdog(){ :; }
start /bin/true "$HETU_TEST_DIR/functions.sh" tproxy 19898 0 disable 1 1 redirect 0 11053 29090 core '' 0 0 '' '' '' 1 1
'''
 shell(stub,NO_TABLE='6:nat');assert (d/'run/cached-path-ok').read_text()=='checked';checks+=1
 reset();result=json.loads(shell('health_json'));assert result['networkIntegrity']=='stopped';checks+=1
 # High process counts: built-in candidate enumeration, strict final executable check retained.
 proc=d/'proc';proc.mkdir();private=d/'bin/core';private.write_text('binary')
 for n in range(10000,10500):
  q=proc/str(n);q.mkdir();(q/'comm').write_text('unrelated-app\n');(q/'exe').symlink_to('/bin/sh')
 q=proc/'20000';q.mkdir();(q/'comm').write_text('renamed-core\n');(q/'exe').symlink_to(private)
 q=proc/'20001';q.mkdir();(q/'comm').write_text('core\n');(q/'exe').symlink_to(str(private)+' (deleted)')
 result=shell('''CORE_PROCFS="$HETU_TEST_DIR/proc"
readlink(){ echo unexpected-external-readlink >&2; exit 73; }
for p in "$CORE_PROCFS"/*; do if core_candidate "${p##*/}"; then printf '%s\\n' "${p##*/}"; fi; done
''')
 assert result.splitlines()==['20000','20001'],result;checks+=1
print(f'Optional IPv6 NAT regression checks passed: {checks}')
