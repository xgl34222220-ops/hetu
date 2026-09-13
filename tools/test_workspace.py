#!/usr/bin/env python3
"""Compile instrumentation against the already installed candidate. No real user devices."""
import os, subprocess, zipfile
from pathlib import Path
r=Path(__file__).resolve().parents[1]; out=r/'out/workspace';out.mkdir(parents=True,exist_ok=True)
sdk=Path(os.environ['ANDROID_HOME']);tools=sdk/'build-tools/35.0.0';jar=sdk/'platforms/android-35/android.jar'
def run(*args,**kwargs):return subprocess.run([str(a) for a in args],check=True,**kwargs)
(out/'AndroidManifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="bichen.uitest"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/><application android:debuggable="true"/><instrumentation android:name="bichen.uitest.Workspace" android:targetPackage="io.github.xgl34222220.bichen.preview"/></manifest>')
classes=out/'classes';classes.mkdir(exist_ok=True);dex=out/'dex';dex.mkdir(exist_ok=True)
run('javac','-source','8','-target','8','-encoding','UTF-8','-bootclasspath',str(jar)+os.pathsep+str(tools/'core-lambda-stubs.jar'),'-d',classes,r/'tests/ui/Workspace.java')
with zipfile.ZipFile(out/'classes.jar','w') as z:
 for p in classes.rglob('*.class'):z.write(p,p.relative_to(classes).as_posix())
run(tools/'d8','--min-api','26','--lib',jar,'--output',dex,out/'classes.jar')
run(tools/'aapt','package','-f','-M',out/'AndroidManifest.xml','-I',jar,'-F',out/'unsigned.apk')
with zipfile.ZipFile(out/'unsigned.apk','a') as z:
 for p in dex.glob('*.dex'):z.write(p,p.name)
run(tools/'zipalign','-f','4',out/'unsigned.apk',out/'aligned.apk')
run(tools/'apksigner','sign','--ks',r/'out/device/ci-only.keystore','--ks-key-alias','test','--ks-pass','pass:android','--key-pass','pass:android','--v4-signing-enabled','false','--out',out/'test.apk',out/'aligned.apk')
run('adb','install','-r',out/'test.apk')
for theme in ('light','dark'):
 p=run('adb','shell','am','instrument','-w','-e','theme',theme,'bichen.uitest/.Workspace',capture_output=True,text=True,timeout=100)
 (out/f'{theme}-results.txt').write_text(p.stdout+p.stderr);print(p.stdout)
 assert 'BICHEN_WORKSPACE_PASS' in p.stdout and 'BICHEN_WORKSPACE_FAIL' not in p.stdout
run('adb','pull','/data/user/0/io.github.xgl34222220.bichen.preview/files',out/'screenshots',timeout=30)
assert len(list((out/'screenshots').glob('workspace-*.png')))==8
