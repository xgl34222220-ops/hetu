#!/usr/bin/env python3
"""Real Android proxy interface/storage/action checks; reuse the CI-only certificate."""
import os,subprocess,zipfile
from pathlib import Path
r=Path(__file__).resolve().parents[1];o=r/'out/proxyui';o.mkdir(parents=True,exist_ok=True)
s=Path(os.environ['ANDROID_HOME']);t=s/'build-tools/35.0.0';j=s/'platforms/android-35/android.jar';key=r/'out/device/ci-only.keystore'
def run(*a,**kw):return subprocess.run([str(x) for x in a],check=True,**kw)
(o/'AndroidManifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="hetu.proxyuicheck"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/><application android:debuggable="true"/><instrumentation android:name="hetu.proxyuicheck.UiCheck" android:targetPackage="io.github.xgl34222220.hetu.preview"/></manifest>')
classes=o/'classes';classes.mkdir(exist_ok=True);dex=o/'dex';dex.mkdir(exist_ok=True)
run('javac','-source','8','-target','8','-encoding','UTF-8','-bootclasspath',str(j)+os.pathsep+str(t/'core-lambda-stubs.jar'),'-d',classes,r/'tests/proxyui/UiCheck.java')
with zipfile.ZipFile(o/'classes.jar','w') as z:
 for p in classes.rglob('*.class'):z.write(p,p.relative_to(classes).as_posix())
run(t/'d8','--min-api','26','--lib',j,'--output',dex,o/'classes.jar')
run(t/'aapt','package','-f','-M',o/'AndroidManifest.xml','-I',j,'-F',o/'unsigned.apk')
with zipfile.ZipFile(o/'unsigned.apk','a') as z:
 for p in dex.glob('*.dex'):z.write(p,p.name)
run(t/'zipalign','-f','4',o/'unsigned.apk',o/'aligned.apk')
run(t/'apksigner','sign','--ks',key,'--ks-key-alias','test','--ks-pass','pass:android','--key-pass','pass:android','--v4-signing-enabled','false','--out',o/'test.apk',o/'aligned.apk')
run('adb','install','-r',o/'test.apk');run('adb','shell','am','force-stop','io.github.xgl34222220.hetu.preview');run('adb','logcat','-c')
p=None
try:
 p=run('adb','shell','am','instrument','-w','hetu.proxyuicheck/.UiCheck',capture_output=True,text=True,timeout=180)
 (o/'results.txt').write_text(p.stdout+p.stderr);print(p.stdout)
finally:
 log=subprocess.run(['adb','logcat','-d','-t','1800'],capture_output=True,text=True)
 text=log.stdout+log.stderr
 (o/'android-logcat.txt').write_text(text)
 if p is None or 'HETU_PROXY_UI_PASS' not in p.stdout:
  lines=text.splitlines();selected=[];grab=False;left=0
  for line in lines:
   if 'FATAL EXCEPTION' in line or 'AndroidRuntime' in line and 'FATAL' in line:
    grab=True;left=45
   if grab:
    selected.append(line);left-=1
    if left<=0:grab=False
  print('\n===== PROXY UI CRASH LOGCAT =====')
  print('\n'.join(selected[-140:]) if selected else '\n'.join(lines[-220:]))
  print('===== END CRASH LOGCAT =====\n')
 subprocess.run(['adb','pull','/data/user/0/io.github.xgl34222220.hetu.preview/files',str(o/'screenshots')],capture_output=True,text=True,timeout=30)
assert p is not None and 'HETU_PROXY_UI_PASS' in p.stdout and 'HETU_PROXY_UI_FAIL' not in p.stdout
