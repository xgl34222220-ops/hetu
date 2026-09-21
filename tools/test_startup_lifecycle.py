#!/usr/bin/env python3
"""Test actual test.100 shell polling and Java observation gates; no device required."""
import os,subprocess,tempfile,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'android-app/app/src/main/java/io/github/xgl34222220/hetu'
SCRIPT=ROOT/'android-app/app/src/main/assets/hetu-root.sh'
checks=0
with tempfile.TemporaryDirectory(prefix='hetu-startup100-') as td:
 d=Path(td);(d/'bin').mkdir();(d/'run').mkdir()
 (d/'functions').write_text(SCRIPT.read_text().split('case "${1:-status}" in')[0])
 (d/'sockets').write_text('Netid State Recv-Q Send-Q Local Address:Port Peer Address:Port\n'+ '\n'.join(f'{proto} {state} 0 0 {addr}:{port} *:*' for proto,state,addr,port in [('tcp','LISTEN','127.0.0.1',29090),('tcp','LISTEN','[::]',19898),('udp','UNCONN','0.0.0.0',19898),('tcp','LISTEN','*',11053),('udp','UNCONN','[::]',11053)]))
 (d/'bin/ss').write_text('#!/bin/sh\necho ss >> "$HETU_TEST_DIR/calls"\ncat "$HETU_TEST_DIR/sockets"\n');(d/'bin/ss').chmod(0o755)
 env=dict(os.environ,PATH=str(d/'bin')+':'+os.environ['PATH'],HETU_TEST_DIR=td)
 prefix=f'. "{d}/functions"\nRUN="{d}/run"; LOCK_DIR="$RUN/lock"; START_ERROR="$RUN/error"\ncore_maybe_alive(){{ return 0; }}\n'
 def shell(body):
  p=subprocess.run(['sh'],input=prefix+body,env=env,text=True,capture_output=True,timeout=15)
  assert p.returncode==0,(p.returncode,p.stdout,p.stderr);return p.stdout
 shell(f'ready {os.getpid()} tproxy 19898 0 1 1 redirect 11053 29090 || exit 1\n')
 assert (d/'calls').read_text().count('ss')==1;checks+=1
 original=(d/'sockets').read_text();(d/'sockets').write_text(original.replace('udp UNCONN 0 0 0.0.0.0:19898 *:*','udp UNCONN 0 0 0.0.0.0:29999 *:*'))
 shell(f'if ready {os.getpid()} tproxy 19898 0 1 1 redirect 11053 29090; then exit 1; fi\n');checks+=1
 (d/'sockets').write_text(original)
 shell(f'ready {os.getpid()} tproxy 19898 0 1 1 redirect 11053 29090 || exit 1\n');checks+=1
 (d/'sockets').write_text('tcp LISTEN 0 0 127.0.0.1:329090 127.0.0.1:29090\n')
 shell(f'if ready {os.getpid()} tproxy 19898 0 1 1 redirect 11053 29090; then exit 1; fi\n');checks+=1
 shell(f'''CLOCK=0; CALLS=0
monotonic_seconds(){{ MONO_SECONDS=$CLOCK; }}
ready(){{ CALLS=$((CALLS+1)); CLOCK=$((CLOCK+31)); return 1; }}
sleep(){{ :; }}
wait_ready {os.getpid()} tproxy 19898 0 1 1 redirect 11053 29090; RC=$?
[ "$RC" = 3 ] && [ "$CALLS" = 3 ] || exit 1
''');checks+=1
 shell('''absent(){ echo "ip6tables: can't initialize table nat: Table does not exist"; return 3; }
denied(){ echo 'Permission denied'; return 1; }
[ -z "$(cleanup_snapshot_read absent nat)" ] || exit 1
if cleanup_snapshot_read denied nat; then exit 1; fi
''');checks+=2
 common=prefix+'''root(){ :; }; has(){ return 0; }; probeowner(){ return 0; }; probegid(){ return 0; }; probecidrs(){ return 0; }
probe_ingress(){ printf '%s\\n' probe >> "$HETU_TEST_DIR/probes"; }
'''
 locker=subprocess.Popen(['sh'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=env,text=True)
 locker.stdin.write(prefix+'acquire_lock || exit 1; echo locked; sleep 0.4\n');locker.stdin.close()
 assert locker.stdout.readline().strip()=='locked'
 started=time.monotonic()
 p=subprocess.run(['sh'],input=common+"preflight tproxy 19898 0 disable 1 1 redirect 0 11053 29090 core '' 0 0 '' '' ''\n",env=env,text=True,capture_output=True,timeout=10)
 assert p.returncode==0,(p.stdout,p.stderr)
 assert time.monotonic()-started>=.25
 assert (d/'probes').read_text()=='probe\n';locker.wait(timeout=5);checks+=2
 gid_ok=subprocess.run(['sh'],input=common+"preflight tproxy 19898 0 disable 1 1 redirect 0 11053 29090 core '' 0 0 '' '' '' 10123\n",env=env,text=True,capture_output=True,timeout=10)
 assert gid_ok.returncode==0,(gid_ok.stdout,gid_ok.stderr);checks+=1
 gid_bad=subprocess.run(['sh'],input=common+"preflight tproxy 19898 0 disable 1 1 redirect 0 11053 29090 core '' 0 0 '' '' '' 3003\n",env=env,text=True,capture_output=True,timeout=10)
 assert gid_bad.returncode!=0 and 'DIRECT GID' in (gid_bad.stdout+gid_bad.stderr);checks+=1
 assert '--gid-owner' in SCRIPT.read_text();checks+=1
 harness=d/'EpochTest.java'
 harness.write_text('''package io.github.xgl34222220.hetu;
public class EpochTest {
 public static void main(String[] args)throws Exception{
  ProxyControlEpoch e=new ProxyControlEpoch();int[] writes={0};long old=e.observe();
  if(!e.publish(old,()->writes[0]++))throw new AssertionError("idle publication");
  e.lock();if(e.observe()!=-1)throw new AssertionError("busy observation");
  final boolean[] queued={false};Thread worker=new Thread(()->queued[0]=e.tryLock());worker.start();worker.join(1000);
  if(worker.isAlive()||queued[0])throw new AssertionError("background control must not queue");
  e.lock();e.unlock();e.unlock();
  if(e.publish(old,()->writes[0]++))throw new AssertionError("stale result overwrote new transaction");
  long fresh=e.observe();if(!e.publish(fresh,()->writes[0]++))throw new AssertionError("fresh result");
  if(writes[0]!=2)throw new AssertionError("unexpected publication");
  e.lock();e.unlock();if(e.publish(fresh,()->writes[0]++))throw new AssertionError("completed short transaction not detected");
  System.out.println("Epoch gate: idle/busy/stale/reentrant/nonqueued publication passed");
 }
}''')
 subprocess.run(['javac','-d',td,str(JAVA/'ProxyControlEpoch.java'),str(harness)],check=True)
 subprocess.run(['java','-cp',td,'io.github.xgl34222220.hetu.EpochTest'],check=True);checks+=6
 service=(JAVA/'ProxyNetworkMatchService.java').read_text()
 assert 'controller.closeConnection(' not in service
 assert 'default-changed-connections-preserved' in service
 assert service.count('RootProxyManager.publishObservation(')>=5
 assert 'CONTROL_LOCK.tryLock()' in (JAVA/'RootProxyManager.java').read_text();checks+=4
print(f'Startup/lifecycle regression checks passed: {checks}')
