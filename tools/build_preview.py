#!/usr/bin/env python3
"""Build isolated preview and assert the BoxProxy backend + LuoShu/BaiZe UI baseline."""
from __future__ import annotations
import hashlib,json,os,re,shutil,subprocess,sys,tempfile,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
BASE='io.github.xgl34222220.bichen';PREVIEW=BASE+'.preview';VERSION='0.4.0-test.18';CODE=418

def run(*args,cwd):subprocess.run([str(a) for a in args],cwd=cwd,check=True)
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
 out=ROOT/'out/preview';out.mkdir(parents=True,exist_ok=True);revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
 with tempfile.TemporaryDirectory(prefix='bichen-preview-') as temp:
  stage=Path(temp)/'Bichen';shutil.copytree(ROOT,stage,ignore=shutil.ignore_patterns('.git','.upstream','out','downloads','build','__pycache__','*.keystore','*.jks','*.p12','*.idsig'))
  main_dir=stage/'android-app/app/src/main';manifest=main_dir/'AndroidManifest.xml';value=manifest.read_text();assert f'package="{BASE}"' in value;assert value.count('android.intent.category.LAUNCHER')==1;assert 'android:name=".BichenApplication"' in value;assert 'android:usesCleartextTraffic="false"' in value
  value=value.replace(f'package="{BASE}"',f'package="{PREVIEW}"').replace('android:label="辟尘"','android:label="辟尘·测试"');value=re.sub(r'android:versionCode="[^"]+"',f'android:versionCode="{CODE}"',value);value=re.sub(r'android:versionName="[^"]+"',f'android:versionName="{VERSION}"',value);manifest.write_text(value)
  for path in list((main_dir/'java').rglob('*.java'))+list((stage/'tests').glob('*.java')):
   text=path.read_text().replace(BASE,PREVIEW)
   if path.name=='MainActivity.java':text=text.replace('"辟尘"','"辟尘·测试"').replace('"少一点打扰，多一点清净"','"洛书视觉对齐测试 · 请停止旧版保护"')
   path.write_text(text)
  dock=(main_dir/'java/io/github/xgl34222220/bichen/LuoShuDockView.java').read_text();assert 'setCornerRadius(dp(31))' in dock and 'dp(23)' in dock and 'dp(60)' in dock and 'setElevation(dp(18))' in dock
  skin=(main_dir/'java/io/github/xgl34222220/bichen/BichenApplication.java').read_text();assert 'styleMainDock' in skin and 'lastDockGeometry' in skin and 'system_accent1_500' in skin and '0xfff4f6fa' in skin and 'MainActivity.class.getDeclaredField("nav")' in skin
  controller=(main_dir/'java/io/github/xgl34222220/bichen/MihomoControllerClient.java').read_text();assert 'new Socket()' in controller and '127.0.0.1' in controller and 'HttpURLConnection' not in controller
  proxy=(main_dir/'java/io/github/xgl34222220/bichen/ProxyActivity.java').read_text()
  for required in ('"首页"','"面板"','"工具"','"设置"','当前策略','节点选择','全部测速','连接活动','MihomoControllerClient','controller.select','controller.delay','RootTproxyActivity.class','LuoShuDockView','panelReady','实时连接与流量'):assert required in proxy,required
  ui=(main_dir/'java/io/github/xgl34222220/bichen/ProxyUi.java').read_text();assert '0xfff4f6fa' in ui and 'system_accent1_' in ui and 'emphasizedCard' in ui and 'setElevation(dp(1))' in ui
  basic=(main_dir/'java/io/github/xgl34222220/bichen/RootTproxyActivity.java').read_text()
  for required in ('基础代理配置','核心选择','运行模式','IPv6','自动覆写','查看启动配置','配置选择','尚无配置','renderConfigs'):assert required in basic,required
  assert '启动代理' not in basic and '运行日志' not in basic and '核心管理' not in basic
  startup=(main_dir/'java/io/github/xgl34222220/bichen/MihomoStartupConfig.java').read_text();assert 'removeTopLevelKey(yaml,"listeners")' in startup;assert 'external-controller: 127.0.0.1:' in startup and 'CONTROLLER_PORT=19090' in startup
  manager=(main_dir/'java/io/github/xgl34222220/bichen/RootProxyManager.java').read_text();assert 'proxyControllerSecret' in manager and 'waitReady(3500)' in manager
  controller=(main_dir/'java/io/github/xgl34222220/bichen/MihomoControllerClient.java').read_text();assert 'boolean waitReady' in controller and 'return false;' in controller
  for name in ('ProxyCoreStore.java','ProxyConfigLibrary.java','ProxyRuntimeProfile.java','RootProxyManager.java','MihomoControllerClient.java','LuoShuDockView.java','BichenApplication.java'):assert (main_dir/'java/io/github/xgl34222220/bichen'/name).is_file()
  assert (stage/'docs/BOXPROXY_PARITY.md').is_file()
  process_test=stage/'tests/root_process_test.py';process_test.write_text(process_test.read_text().replace(BASE,PREVIEW));test_script=stage/'tools/test_java.py';test_script.write_text(test_script.read_text().replace(BASE,PREVIEW))
  builder=stage/'tools/build_app.py';b=builder.read_text();b=re.sub(r'^VERSION = .*$',f'VERSION = "{VERSION}"',b,flags=re.M);b=re.sub(r'^VERSION_CODE = .*$',f'VERSION_CODE = {CODE}',b,flags=re.M);builder.write_text(b)
  run(sys.executable,'tools/package.py','--prepare-only',cwd=stage);run(sys.executable,'tools/build_app.py','--out',stage/'out/compile-check.apk',cwd=stage);run(sys.executable,'tools/test_java.py',cwd=stage);run(sys.executable,'-m','unittest','discover','-s','tests','-p','*test.py','-v',cwd=stage)
  sdk=Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ['ANDROID_HOME']);tools=sdk/'build-tools/35.0.0';unsigned=out/f'Bichen-{VERSION}-unsigned.apk';shutil.copyfile(stage/'android-app/build/aligned.apk',unsigned);run(tools/'zipalign','-c','-p','4',unsigned,cwd=stage)
  badging=subprocess.check_output([str(tools/'aapt'),'dump','badging',str(unsigned)],text=True);assert f"package: name='{PREVIEW}'" in badging;assert f"versionName='{VERSION}'" in badging and f"versionCode='{CODE}'" in badging;assert f"launchable-activity: name='{PREVIEW}.MainActivity'" in badging;assert badging.count('launchable-activity:')==1;(out/'apk-badging.txt').write_text(badging)
  with zipfile.ZipFile(unsigned) as apk:
   assert apk.testzip() is None;module=apk.read('assets/bichen-module.zip');info=json.loads(apk.read('assets/module-info.json'));assert hashlib.sha256(module).hexdigest()==info['sha256'];dex=apk.read('classes.dex')
   for name in ('ProxyActivity','RootTproxyActivity','RootProxyManager','ProxyRuntimeProfile','ProxyCoreStore','ProxyConfigLibrary','MihomoControllerClient','LuoShuDockView','BichenApplication'):assert f'Lio/github/xgl34222220/bichen/preview/{name};'.encode() in dex
   for text in ('首页','面板','工具','设置','基础代理配置','核心选择','运行模式','配置选择','策略组','全部测速','实时连接','TPROXY'):assert text.encode('utf-8') in dex
  (out/'Bichen-0.3.0-beta.1-module.zip').write_bytes(module);shutil.copyfile(tools/'lib/apksigner.jar',out/'apksigner.jar');meta={'sourceCommit':revision,'versionName':VERSION,'versionCode':CODE,'packageName':PREVIEW,'moduleVersion':info['version'],'moduleSha256':info['sha256'],'unsignedApkSha256':digest(unsigned),'signingToolSha256':digest(out/'apksigner.jar'),'note':'Unsigned BoxProxy-backend + LuoShu/BaiZe-visual preview; do not distribute as production.'};(out/'build-info.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2)+'\n')
  source=out/f'Bichen-{VERSION}-source.zip'
  with zipfile.ZipFile(source,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
   for path in sorted(stage.rglob('*')):
    rel=path.relative_to(stage)
    if not path.is_file() or any(p in {'out','build','.git','__pycache__','downloads'} for p in rel.parts):continue
    if path.suffix in {'.keystore','.jks','.p12','.pyc','.ttf','.otf','.so'}:continue
    z.write(path,str(Path('Bichen')/rel))
  files=[unsigned,out/'Bichen-0.3.0-beta.1-module.zip',source,out/'apksigner.jar',out/'build-info.json',out/'apk-badging.txt'];(out/'SHA256SUMS.txt').write_text(''.join(f'{digest(p)}  {p.name}\n' for p in files));print(json.dumps(meta,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
