#!/usr/bin/env python3
"""Controlled HTTP CONNECT proxy plus a separate Android UID end-to-end probe."""
import os, subprocess, zipfile, json, socketserver, threading, time
from pathlib import Path
R=Path(__file__).resolve().parents[1];O=R/'out/proxy-device';O.mkdir(parents=True,exist_ok=True)
S=Path(os.environ['ANDROID_HOME']);T=S/'build-tools/35.0.0';J=S/'platforms/android-35/android.jar'
def run(*a,**kw):return subprocess.run([str(x) for x in a],check=True,**kw)
seen=[]
class Proxy(socketserver.StreamRequestHandler):
 def handle(self):
  self.request.settimeout(12)
  line=self.rfile.readline(8192).decode();seen.append(line.strip())
  while self.rfile.readline(8192).strip():pass
  if not line.startswith('CONNECT '):return
  self.wfile.write(b'HTTP/1.1 200 Connection Established\r\n\r\n');self.wfile.flush()
  while self.rfile.readline(8192).strip():pass
  self.wfile.write(b'HTTP/1.1 200 OK\r\nContent-Length: 15\r\nConnection: close\r\n\r\nHETU_PROXY_OK');self.wfile.flush()
class Server(socketserver.ThreadingTCPServer):allow_reuse_address=True;daemon_threads=True
server=Server(('0.0.0.0',18081),Proxy);threading.Thread(target=server.serve_forever,daemon=True).start()
key=O/'ci-only.keystore';run('keytool','-genkeypair','-keystore',key,'-storepass','android','-keypass','android','-alias','test','-keyalg','RSA','-validity','2','-dname','CN=Disposable Integration')
def sign(src,dst):run(T/'apksigner','sign','--ks',key,'--ks-key-alias','test','--ks-pass','pass:android','--key-pass','pass:android','--v4-signing-enabled','false','--out',dst,src)
subprocess.run(['adb','uninstall','io.github.xgl34222220.hetu.preview'],capture_output=True)
meta=json.loads((R/'out/preview/build-info.json').read_text());sign(R/f'out/preview/Hetu-{meta["versionName"]}-unsigned.apk',O/'app.apk');run('adb','install',O/'app.apk')
for kind,pkg,cls in [('test','hetu.proxytest','ProxySmoke'),('probe','hetu.probe','Probe')]:
 d=O/kind;d.mkdir(exist_ok=True);classes=d/'classes';classes.mkdir(exist_ok=True)
 app='<application android:debuggable="true" android:usesCleartextTraffic="true">'+('<activity android:name=".Probe" android:exported="true"/>' if kind=='probe' else '')+'</application>'
 instr='<instrumentation android:name="hetu.proxytest.ProxySmoke" android:targetPackage="io.github.xgl34222220.hetu.preview"/>' if kind=='test' else ''
 manifest=f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{pkg}"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/><uses-permission android:name="android.permission.INTERNET"/>{app}{instr}</manifest>'
 (d/'AndroidManifest.xml').write_text(manifest)
 run('javac','-source','8','-target','8','-encoding','UTF-8','-bootclasspath',str(J)+os.pathsep+str(T/'core-lambda-stubs.jar'),'-d',classes,R/f'tests/proxy/{cls}.java')
 with zipfile.ZipFile(d/'classes.jar','w') as z:
  for f in classes.rglob('*.class'):z.write(f,f.relative_to(classes).as_posix())
 dex=d/'dex';dex.mkdir(exist_ok=True);run(T/'d8','--min-api','26','--lib',J,'--output',dex,d/'classes.jar')
 run(T/'aapt','package','-f','-M',d/'AndroidManifest.xml','-I',J,'-F',d/'unsigned.apk')
 with zipfile.ZipFile(d/'unsigned.apk','a') as z:
  for f in dex.glob('*.dex'):z.write(f,f.name)
 run(T/'zipalign','-f','4',d/'unsigned.apk',d/'aligned.apk');sign(d/'aligned.apk',d/'app.apk');run('adb','install','-r',d/'app.apk')
run('adb','shell','appops','set','io.github.xgl34222220.hetu.preview','ACTIVATE_VPN','allow')
run('adb','shell','pm','grant','io.github.xgl34222220.hetu.preview','android.permission.POST_NOTIFICATIONS')
p=run('adb','shell','am','instrument','-w','hetu.proxytest/.ProxySmoke',capture_output=True,text=True,timeout=120)
(O/'result.txt').write_text(p.stdout+'\n'+p.stderr);(O/'proxy-received.txt').write_text('\n'.join(seen));print(p.stdout)
server.shutdown()
logs=run('adb','logcat','-d','-t','1500',capture_output=True,text=True)
(O/'emulator-logcat.txt').write_text(logs.stdout)
assert 'HETU_PROXY_PASS' in p.stdout and 'HETU_PROXY_FAIL' not in p.stdout
assert any('normal.integration.test:80' in line for line in seen),'No traffic reached configured proxy'
assert not any('ads.integration.test' in line for line in seen),'Blocked domain reached proxy'
subprocess.run(['adb','root'],capture_output=True,timeout=15);run('adb','wait-for-device',timeout=30)
for attempt in range(20):
 uid=subprocess.run(['adb','shell','id','-u'],capture_output=True,text=True,timeout=5)
 if uid.returncode==0 and uid.stdout.strip()=='0':break
 time.sleep(.25)
run('adb','pull','/data/user/0/io.github.xgl34222220.hetu.preview/files/mihomo-screen.png',O/'mihomo-screen.png',timeout=20)
