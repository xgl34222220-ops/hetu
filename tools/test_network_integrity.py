#!/usr/bin/env python3
"""Execute actual Root audit/recovery functions against a stateful isolated netfilter/netlink model."""
import json,os,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
SCRIPT=ROOT/'android-app/app/src/main/assets/hetu-root.sh'
FAKE=r'''#!/usr/bin/env -S python3 -S
import json,os,sys,copy,shlex
from pathlib import Path
p=Path(os.environ['HETU_TEST_DIR']);f=p/'net.json';s=json.loads(f.read_text());a=sys.argv[1:];name=Path(sys.argv[0]).name
with (p/'calls').open('a') as l:l.write(json.dumps([name,*a])+'\n')
family=6 if name.startswith('ip6') else 4
if name=='ss':
 if os.environ.get('SOCKET_UNKNOWN'):sys.exit(1)
 for proto,port in [('tcp',29090),('tcp',19898),('udp',19898),('tcp',11053),('udp',11053)]:
  if os.environ.get('NO_PORT')!=str(port):print(proto+' LISTEN 0 0 [::]:'+str(port)+' [::]:*')
 sys.exit(0)
if name=='ip':
 if a and a[0] in ('-4','-6'):family=int(a.pop(0)[1:])
 k=str(family)
 if a[:2]==['link','show']:sys.exit(0)
 if a[0]=='rule':
  if a[1]=='show':
   if os.environ.get('RULE_UNKNOWN'):sys.exit(1)
   print('\n'.join(s['rules'+k]));sys.exit(0)
  if a[1]=='add':
   pref=a[a.index('pref')+1];mark=a[a.index('fwmark')+1];table=a[a.index('table')+1]
   s['rules'+k].append(pref+': from all fwmark '+mark+' lookup '+table)
  elif a[1]=='del':s['rules'+k]=[]
  else:raise AssertionError(a)
 elif a[0]=='route':
  table=a[a.index('table')+1];key='routes'+k
  if a[1]=='show':
   if os.environ.get('ROUTE_UNKNOWN'):print('Operation not permitted',file=sys.stderr);sys.exit(1)
   if table not in s[key]:print('Error: ipv'+k+': FIB table does not exist.',file=sys.stderr);sys.exit(2)
   print('\n'.join(s[key][table]));sys.exit(0)
  elif a[1]=='replace':s[key][table]=['local '+a[3]+' dev lo scope host']
  elif a[1]=='del':s[key].pop(table,None)
  else:raise AssertionError(a)
 else:raise AssertionError(a)
 f.write_text(json.dumps(s));sys.exit(0)
def execute(table,args,state):
 op=args[0];c=args[1] if len(args)>1 else ''
 chains=state[str(family)+':'+table]
 if op=='-S':
  if os.environ.get('SNAPSHOT_UNKNOWN')==str(family)+':'+table:sys.exit(4)
  for chain,rules in chains.items():
   print(('-P '+chain+' ACCEPT') if chain in ('OUTPUT','PREROUTING','FORWARD') else '-N '+chain)
  for chain,rules in chains.items():
   for rule in rules:print('-A '+chain+' '+' '.join(rule))
  return
 rule=args[2:]
 if op=='-N':
  if c in chains:raise ValueError('exists')
  chains[c]=[]
 elif op in ('-A','-I'):
  if c not in chains:raise ValueError('absent')
  if op=='-I':
   n=int(rule.pop(0))-1 if rule and rule[0].isdigit() else 0;chains[c].insert(n,rule)
  else:chains[c].append(rule)
 elif op in ('-C','-D'):
  if c not in chains or rule not in chains[c]:raise ValueError('rule absent')
  if op=='-D':chains[c].remove(rule)
 elif op=='-F':
  assert c.startswith('HETU_'), 'Must not flush a global or foreign chain'
  if c not in chains:raise ValueError('absent')
  chains[c]=[]
 elif op=='-X':chains.pop(c,None)
 else:raise AssertionError(args)
if name.endswith('-restore'):
 assert '--noflush' in a
 # Work on a copy: model the table-level atomic commit.
 proposed=copy.deepcopy(s);text=sys.stdin.read();table=''
 if os.environ.get('RESTORE_FAIL'):sys.exit(1)
 try:
  for line in text.splitlines():
   if line.startswith('*'):table=line[1:];continue
   if line=='COMMIT':continue
   execute(table,shlex.split(line),proposed)
 except ValueError:sys.exit(1)
 f.write_text(json.dumps(proposed));sys.exit(0)
if a[:1]==['-w']:a=a[2:]
assert a[:1]==['-t'],a
table=a[1]
try:execute(table,a[2:],s)
except ValueError:sys.exit(1)
if a[2]!='-S':f.write_text(json.dumps(s))
'''
def initial():
 d={str(n)+':'+t:{'OUTPUT':[['-j','OEM_CHAIN']],'PREROUTING':[],'FORWARD':[], 'OEM_CHAIN':[['-j','RETURN']]} for n in (4,6) for t in ('mangle','nat','filter')}
 for n in (4,6):d['rules'+str(n)]=[];d['routes'+str(n)]={}
 return d

def main():
 checks=0
 with tempfile.TemporaryDirectory(prefix='hetu-integrity-') as td:
  d=Path(td);(d/'bin').mkdir();(d/'functions.sh').write_text(SCRIPT.read_text().split('case "${1:-status}" in')[0]);(d/'net.json').write_text(json.dumps(initial()))
  for n in ('iptables','ip6tables','iptables-restore','ip6tables-restore','ip','ss'):
   p=d/'bin'/n;p.write_text(FAKE);p.chmod(0o755)
  (d/'run').mkdir(); (d/'calls').write_text('')
  prefix='''. "$HETU_TEST_DIR/functions.sh"
RUN="$HETU_TEST_DIR/run"; BASE="$HETU_TEST_DIR"; PIDFILE="$RUN/core.pid"; NET_STATE="$RUN/net.state"
SESSION="$RUN/session.state"; MODEFILE="$RUN/mode"; LOCK_DIR="$RUN/.txn.lock"; WATCHDOG_PID="$RUN/watchdog.pid"
IPV6_STATE="$RUN/ipv6.state"; V6_CONF="$HETU_TEST_DIR/conf"
# Deterministic early-boot run still executes the real collection/repair code.
if [ -n "${HETU_TEST_UPTIME:-}" ]; then
  monotonic_seconds(){ MONO_SECONDS="$HETU_TEST_UPTIME"; }
fi
root(){ :; }; pidcore(){ return 0; }; core_maybe_alive(){ return 0; }; findcorepid(){ return 1; }; v6supported(){ return 0; }
'''
  def shell(body,**extra):
   env=dict(os.environ, HETU_TEST_DIR=td,PATH=str(d/'bin')+':'+os.environ['PATH'],**extra)
   p=subprocess.run(['sh'],input=prefix+body,text=True,capture_output=True,env=env,timeout=35)
   assert p.returncode==0,(p.returncode,p.stdout,p.stderr);return p.stdout
  def load():return json.loads((d/'net.json').read_text())
  def save(x): (d/'net.json').write_text(json.dumps(x))
  pid=os.getpid()
  shell(f'''printf '%s\\n' {pid} > "$PIDFILE"; printf '%s\\n' {pid} > "$WATCHDOG_PID"
START_MODE=tproxy; START_TCP=1; START_UDP=1; START_TP=19898; START_RP=0
printf 'tproxy\\n' > "$MODEFILE"
MARK=0x200000; MASK=0x200000; TABLE=20260; PREF=14500; savenet
write_session tproxy disable redirect 11053 core 0 0 29090 ''
install_mangle4 19898 tproxy 1 1 redirect core '' 0 '' '' '' || exit 1
install_dns_redirect4 11053 core '' 0 '' || exit 1
install_dns_redirect6 11053 core '' 0 '' || exit 1
install_v6_disable 0 || exit 1
health_record || exit 1
''')
  healthy=load()
  result=json.loads(shell('health_json\n'));assert result['networkIntegrity']=='healthy',result;checks+=1
  result=json.loads(shell('status\n'));assert result['running'] and result['dataPlaneHealthy'],result;checks+=1
  oldpid=(d/'run/core.pid').read_bytes();manifest={p.name:p.read_bytes() for p in (d/'run/network-manifest').iterdir()}
  scenarios=['output','prerouting','policy','local-route','empty-chain','deleted-chains','dns','v6guard','duplicate']
  for scenario in scenarios:
   save(json.loads(json.dumps(healthy)));s=load()
   if scenario=='output':s['4:mangle']['OUTPUT'].remove(['-j','HETU_MOUT'])
   elif scenario=='prerouting':s['4:mangle']['PREROUTING'].remove(['-j','HETU_MPRE'])
   elif scenario=='policy':s['rules4']=[]
   elif scenario=='local-route':s['routes4']={}
   elif scenario=='empty-chain':s['4:mangle']['HETU_MPRE']=[]
   elif scenario=='deleted-chains':
    s['4:mangle']={k:v for k,v in s['4:mangle'].items() if not k.startswith('HETU_')};s['4:mangle']['OUTPUT']=[['-j','OEM_CHAIN']];s['4:mangle']['PREROUTING']=[]
   elif scenario=='dns':s['4:nat']['HETU_DNSOUT']=[]
   elif scenario=='v6guard':s['6:filter']['HETU_V6OUT']=[]
   elif scenario=='duplicate':s['4:mangle']['OUTPUT'].append(['-j','HETU_MOUT'])
   save(s); (d/'run/network-repair-at').unlink(missing_ok=True)
   before=json.loads(shell('health_json\n'));assert not before['dataPlaneHealthy'],(scenario,before)
   after=json.loads(shell(f'health_repair {pid}\nhealth_json\n'));assert after['networkIntegrity']=='healthy',(scenario,after)
   actual=load()
   for key,chains in actual.items():
    if ':' in key:assert chains['OEM_CHAIN']==[['-j','RETURN']] and chains['OUTPUT'][0 if key!='4:nat' and key!='6:nat' and key!='6:filter' else 1]==['-j','OEM_CHAIN'],(scenario,key,chains)
   assert (d/'run/core.pid').read_bytes()==oldpid
   assert {p.name:p.read_bytes() for p in (d/'run/network-manifest').iterdir()}==manifest
   checks+=4
  # Unknown netlink/xtables/socket state never triggers a write/restart.
  for flags in ({'SNAPSHOT_UNKNOWN':'4:mangle'},{'RULE_UNKNOWN':'1'},{'ROUTE_UNKNOWN':'1'},{'SOCKET_UNKNOWN':'1'}):
   save(healthy);(d/'calls').write_text('');(d/'run/network-repair-at').unlink(missing_ok=True)
   result=json.loads(shell(f'health_repair {pid}\nhealth_json\n',**flags));assert result['networkIntegrity']=='unknown',result
   calls=[json.loads(x) for x in (d/'calls').read_text().splitlines()]
   assert not any(c[0].endswith('-restore') or 'replace' in c or 'add' in c for c in calls),calls
   checks+=2
  # Stops, replaced sessions and stale PIDs cannot resurrect rules.
  save(healthy);s=load();s['rules4']=[];save(s)
  shell(f'health_repair {pid+1000000}\n');assert not load()['rules4'];checks+=1
  original_session=(d/'run/session.state').read_bytes();(d/'run/session.state').write_text('MODE=redirect\n')
  result=json.loads(shell(f'health_repair {pid}\nhealth_json\n'));assert result['networkIntegrity']=='upgrade-required' and not load()['rules4'];checks+=1
  (d/'run/session.state').write_bytes(original_session)
  # Failed atomic restore leaves foreign and existing rules unchanged, throttle prevents loops.
  save(healthy);s=load();s['4:mangle']['HETU_MPRE']=[];save(s);(d/'run/network-repair-at').unlink(missing_ok=True)
  shell(f'health_repair {pid}\n',RESTORE_FAIL='1');assert load()['4:mangle']==s['4:mangle'];checks+=1
  calls_before=(d/'calls').read_text().count('"iptables-restore"');shell(f'health_repair {pid}\n');assert (d/'calls').read_text().count('"iptables-restore"')==calls_before;checks+=1
  # An unrelated rule at our old priority is reported, not overwritten.
  save(healthy);s=load();s['rules4']=['14500: from all fwmark 0x400000/0x400000 lookup 999'];save(s);(d/'run/network-repair-at').unlink(missing_ok=True)
  shell(f'health_repair {pid}\n');assert load()['rules4']==s['rules4'];checks+=1
  result=json.loads(shell('health_json\n',NO_PORT='19898'));assert 'listener-19898' in result['networkFault'];checks+=1
  # No global interface disable writes in the new controller.
  text=SCRIPT.read_text();assert 'enforcev6(){' not in text and 'disablev6(){' not in text and 'maintainv6 ||' not in text;checks+=1
 print(f'Network integrity fault-injection checks passed: {checks}')
if __name__=='__main__':main()
