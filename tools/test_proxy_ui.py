#!/usr/bin/env python3
"""New real Android interface/storage checks; reuse the CI-only test certificate."""
import os,subprocess,zipfile
from pathlib import Path
r=Path(__file__).resolve().parents[1];o=r/'out/proxyui';o.mkdir(parents=True,exist_ok=True)
s=Path(os.environ['ANDROID_HOME']);t=s/'build-tools/35.0.0';j=s/'platforms/android-35/android.jar';key=r/'out/device/ci-only.keystore'
def run(*a,**kw):return subprocess.run([str(x) for x in a],check=True,**kw)
(o/'AndroidManifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="bichen.proxyuicheck"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/><application android:debuggable="true"/><instrumentation android:name="bichen.proxyuicheck.UiCheck" android:targetPackage="io.github.xgl34222220.bichen.preview"/></manifest>')
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
run('adb','install','-r',o/'test.apk');run('adb','shell','am','force-stop','io.github.xgl34222220.bichen.preview')
p=run('adb','shell','am','instrument','-w','bichen.proxyuicheck/.UiCheck',capture_output=True,text=True,timeout=180)
(o/'results.txt').write_text(p.stdout+p.stderr);print(p.stdout)
# prior test_device already restarted disposable emulator adbd as root.
run('adb','pull','/data/user/0/io.github.xgl34222220.bichen.preview/files',o/'screenshots',timeout=30)
assert 'BICHEN_PROXY_UI_PASS' in p.stdout and 'BICHEN_PROXY_UI_FAIL' not in p.stdout
