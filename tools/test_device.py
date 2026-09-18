#!/usr/bin/env python3
"""Install built preview on a CI emulator, then test actual UI and synthetic state fixtures."""
import os, subprocess, zipfile, json, time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'out/device'; OUT.mkdir(parents=True,exist_ok=True)
SDK=Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ['ANDROID_HOME'])
TOOLS=SDK/'build-tools/35.0.0'; JAR=SDK/'platforms/android-35/android.jar'
def run(*args, **kwargs):return subprocess.run(list(map(str,args)),check=True,**kwargs)
meta=json.loads((ROOT/'out/preview/build-info.json').read_text())
version=meta['versionName']
key=OUT/'ci-only.keystore'
run('keytool','-genkeypair','-keystore',key,'-storepass','android','-keypass','android','-alias','test','-keyalg','RSA','-keysize','2048','-validity','2','-dname','CN=Disposable CI Test')
def sign(src,dst):
 run(TOOLS/'apksigner','sign','--ks',key,'--ks-key-alias','test','--ks-pass','pass:android','--key-pass','pass:android','--v4-signing-enabled','false','--out',dst,src)
sign(ROOT/('out/preview/Hetu-'+version+'-unsigned.apk'),OUT/'device-app.apk')
manifest=OUT/'AndroidManifest.xml'
manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="hetu.devicecheck"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/><application android:label="Hetu device checks" android:debuggable="true"/><instrumentation android:name="hetu.devicecheck.Smoke" android:targetPackage="io.github.xgl34222220.hetu.preview" android:functionalTest="true"/></manifest>''')
classes=OUT/'classes';classes.mkdir(exist_ok=True)
run('javac','-source','8','-target','8','-encoding','UTF-8','-bootclasspath',str(JAR)+os.pathsep+str(TOOLS/'core-lambda-stubs.jar'),'-d',classes,*sorted((ROOT/'tests/device').glob('*.java')))
with zipfile.ZipFile(OUT/'classes.jar','w') as z:
 for p in classes.rglob('*.class'):z.write(p,p.relative_to(classes).as_posix())
dex=OUT/'dex';dex.mkdir(exist_ok=True)
run(TOOLS/'d8','--min-api','26','--lib',JAR,'--output',dex,OUT/'classes.jar')
run(TOOLS/'aapt','package','-f','-M',manifest,'-I',JAR,'-F',OUT/'test-unsigned.apk')
with zipfile.ZipFile(OUT/'test-unsigned.apk','a') as z:
 for p in dex.glob('*.dex'):z.write(p,p.name)
run(TOOLS/'zipalign','-f','4',OUT/'test-unsigned.apk',OUT/'test-aligned.apk')
sign(OUT/'test-aligned.apk',OUT/'device-test.apk')
run('adb','install','-r',OUT/'device-app.apk');run('adb','install','-r',OUT/'device-test.apk')
run('adb','shell','settings','put','system','system_locales','zh-CN')
run('adb','shell','settings','put','system','font_scale','1.3')
proc=run('adb','shell','am','instrument','-w','-e','expectedVersion',version,'hetu.devicecheck/.Smoke',capture_output=True,text=True,timeout=150)
(OUT/'device-results.txt').write_text(proc.stdout+'\n'+proc.stderr);print(proc.stdout)
assert 'HETU_DEVICE_PASS' in proc.stdout and 'HETU_DEVICE_FAIL' not in proc.stdout
# adb root restarts adbd and can return "closed" before the new daemon connects.
# This is only the disposable CI emulator, after app tests have finished. Check
# actual UID instead of treating a closed transport as a test/application failure.
restart=subprocess.run(['adb','root'],capture_output=True,text=True,timeout=15)
print(restart.stdout+restart.stderr)
run('adb','wait-for-device',timeout=30)
root_ready=False
for attempt in range(20):
 uid=subprocess.run(['adb','shell','id','-u'],capture_output=True,text=True,timeout=5)
 if uid.returncode==0 and uid.stdout.strip()=='0':
  root_ready=True;break
 time.sleep(0.25)
assert root_ready, 'CI emulator did not allow screenshot collection; UI result saved separately'
run('adb','pull','/data/user/0/io.github.xgl34222220.hetu.preview/files',OUT/'screenshots',timeout=30)
shots=list((OUT/'screenshots').glob('actual-page-*.png'))
assert len(shots)==4, 'Expected four actual-page screenshots'
