#!/usr/bin/env python3
"""Actual installed JNI/service + separate UID VPN probes against local fixture servers.
Only the disposable CI emulator is changed. No user subscriptions or public targets.
"""
import json,os,subprocess,zipfile,time,socket,struct,threading,socketserver
from pathlib import Path
R=Path(__file__).resolve().parents[1];O=R/'out/mihomo';O.mkdir(parents=True,exist_ok=True)
SDK=Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ['ANDROID_HOME']);T=SDK/'build-tools/35.0.0';J=SDK/'platforms/android-35/android.jar';KEY=R/'out/device/ci-only.keystore'
PKG='io.github.xgl34222220.bichen.preview';DIR='/data/user/0/'+PKG+'/files/'
def run(*a,**kw):return subprocess.run(list(map(str,a)),check=True,**kw)
def build(name,pkg,target,klass):
 d=O/name;d.mkdir(exist_ok=True);classes=d/'classes';classes.mkdir(exist_ok=True);dex=d/'dex';dex.mkdir(exist_ok=True)
 manifest=d/'AndroidManifest.xml';manifest.write_text(f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{pkg}"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/><uses-permission android:name="android.permission.INTERNET"/><application android:debuggable="true" android:usesCleartextTraffic="true"/><instrumentation android:name="{pkg}.{klass}" android:targetPackage="{target}"/></manifest>')
 run('javac','-source','8','-target','8','-bootclasspath',str(J)+os.pathsep+str(T/'core-lambda-stubs.jar'),'-d',classes,R/'tests/mihomo'/f'{klass}.java')
 with zipfile.ZipFile(d/'classes.jar','w') as z:
  for p in classes.rglob('*.class'):z.write(p,p.relative_to(classes).as_posix())
 run(T/'d8','--min-api','26','--lib',J,'--output',dex,d/'classes.jar');run(T/'aapt','package','-f','-M',manifest,'-I',J,'-F',d/'unsigned.apk')
 with zipfile.ZipFile(d/'unsigned.apk','a') as z:
  for p in dex.glob('*.dex'):z.write(p,p.name)
 run(T/'zipalign','-f','4',d/'unsigned.apk',d/'aligned.apk');run(T/'apksigner','sign','--ks',KEY,'--ks-key-alias','test','--ks-pass','pass:android','--key-pass','pass:android','--v4-signing-enabled','false','--out',d/'test.apk',d/'aligned.apk');run('adb','install','-r',d/'test.apk')
def exact(s,n):
 result=b''
 while len(result)<n:
  part=s.recv(n-len(result))
  if not part:raise EOFError()
  result+=part
 return result
received=[];lock=threading.Lock()
def received_count():
 with lock:return len(received)
def received_since(index):
 with lock:return list(received[index:])
def assert_no_probe_leak(index,label):
 delta=received_since(index);(O/(label+'-upstream-delta.json')).write_text(json.dumps(delta,indent=2))
 assert not any(x['host']=='198.51.100.7' and x['port']==18080 for x in delta), label+' excluded UID reached SOCKS fixture'
class Socks(socketserver.BaseRequestHandler):
 def handle(self):
  s=self.request;s.settimeout(10)
  try:
   version,count=exact(s,2);exact(s,count);s.sendall(b'\x05\x00');v,cmd,rsv,typ=exact(s,4)
   if typ==1:host=socket.inet_ntop(socket.AF_INET,exact(s,4))
   elif typ==3:host=exact(s,exact(s,1)[0]).decode('ascii')
   elif typ==4:host=socket.inet_ntop(socket.AF_INET6,exact(s,16))
   else:return
   port=struct.unpack('!H',exact(s,2))[0]
   with lock:received.append({'host':host,'port':port})
   s.sendall(b'\x05\x00\x00\x01\x00\x00\x00\x00\x00\x00');request=s.recv(4096)
   if request.startswith(b'GET /bichen-e2e '):
    s.sendall(b'HTTP/1.0 200 OK\r\nContent-Length: 16\r\n\r\nBICHEN_PROXY_E2E!');time.sleep(3)
  except (OSError,EOFError):pass
class Health(socketserver.BaseRequestHandler):
 def handle(self):
  try:self.request.recv(4096);self.request.sendall(b'HTTP/1.0 200 OK\r\n\r\nBICHEN_HEALTH')
  except OSError:pass
class Server(socketserver.ThreadingTCPServer):allow_reuse_address=True;daemon_threads=True
servers=[Server(('0.0.0.0',19088),Socks),Server(('0.0.0.0',19089),Health)]
for s in servers:threading.Thread(target=s.serve_forever,daemon=True).start()
build('control','bichen.mihomotest',PKG,'Control');build('probe','bichen.proxyprobe','bichen.proxyprobe','Probe')
run('adb','shell','am','force-stop',PKG);run('adb','shell','pm','clear',PKG);run('adb','shell','appops','set',PKG,'ACTIVATE_VPN','allow')
run('adb','logcat','-c')
control_output=open(O/'control-results.txt','w')
control=subprocess.Popen(['adb','shell','am','instrument','-w','bichen.mihomotest/.Control'],stdout=control_output,stderr=subprocess.STDOUT)
def wait_for(name):
 for i in range(180):
  p=subprocess.run(['adb','shell','test','-f',DIR+name],capture_output=True)
  if p.returncode==0:return
  if control.poll() is not None:raise AssertionError('control exited before '+name)
  time.sleep(.5)
 raise AssertionError('timeout waiting for '+name)
def signal(name):run('adb','shell','touch',DIR+name)
def probe(mode,suffix):
 p=run('adb','shell','am','instrument','-w','-e','mode',mode,'bichen.proxyprobe/.Probe',capture_output=True,text=True,timeout=75);(O/(suffix+'-probe.txt')).write_text(p.stdout+p.stderr);print(p.stdout);assert 'BICHEN_MIHOMO_PROBE_PASS' in p.stdout and 'BICHEN_MIHOMO_PROBE_FAIL' not in p.stdout
try:
 wait_for('vpn-ready');probe('path','first');signal('path-done');wait_for('vpn-stopped');probe('stopped','stopped');signal('stop-probe-done');wait_for('vpn-restarted');probe('path','restart');signal('restart-probe-done')
 wait_for('bypass-ready');before=received_count();probe('bypass','bypass');assert_no_probe_leak(before,'bypass');signal('bypass-done')
 wait_for('bypass-draft-ready');before=received_count();probe('bypass','bypass-draft');assert_no_probe_leak(before,'bypass-draft');signal('bypass-draft-done')
 wait_for('reincluded-ready');before=received_count();probe('path','reincluded');delta=received_since(before);(O/'reincluded-upstream-delta.json').write_text(json.dumps(delta,indent=2));assert any(x['host']=='198.51.100.7' and x['port']==18080 for x in delta), 'reincluded UID did not return to SOCKS path';signal('reincluded-done');control.wait(timeout=40);control_output.close()
 text=(O/'control-results.txt').read_text();print(text);assert 'BICHEN_MIHOMO_CONTROL_PASS' in text and 'CONTROL_FAIL' not in text
 assert any(x['host']=='198.51.100.7' for x in received), 'proxy did not receive real TUN request'
 assert any(x['host']=='allowed.bichen.test' for x in received), 'DNS domain mapping not observed'
 assert any(x['host']=='safe.0.0-02.net' for x in received), 'PASS whitelist did not preserve original proxy routing'
 assert not any(x['host']=='ads.bichen.test' for x in received), 'exact blocked domain reached upstream proxy'
 assert not any(x['host']=='child.0.0-02.net' for x in received), 'suffix-blocked child domain reached upstream proxy'
 assert not any(x['host']=='doh.360.cn' for x in received), 'DoH guard domain reached upstream proxy'
 assert not any(x['port']==853 for x in received), 'TCP 853 escaped encrypted DNS guard'
 assert not any(x['host']=='198.51.100.7' and x['port']==443 for x in received), 'pure-IP TLS SNI DoH attempt escaped sniffer guard'
 (O/'fixture-connections.json').write_text(json.dumps(received,indent=2))
 print('BICHEN_MIHOMO_E2E_PASS: real TUN, SOCKS routing, DNS, exact/suffix ad rejection, PASS whitelist routing, encrypted DNS domain/853 guard, pure-IP TLS SNI guard, stop, restart, app bypass, pending settings, reinclude; independent UID')
finally:
 if control.poll() is None:control.terminate()
 control_output.close()
 log=subprocess.run(['adb','logcat','-d','-t','1500'],capture_output=True,text=True);(O/'android-logcat.txt').write_text(log.stdout)
 for name in ['live-connections.json','control-error.txt','proxy-checks.txt']:subprocess.run(['adb','pull',DIR+name,str(O/name)],capture_output=True)
 for s in servers:s.shutdown();s.server_close()
