#!/usr/bin/env python3
"""Navigation smoke in a fresh non-root Android emulator.
Changes fixture display language and exercises denied Root setup in non-root AOSP.
Never starts/stops a proxy, imports user data, or clears app data.
"""
import hashlib, json, os, re, subprocess, sys, time, uuid, xml.etree.ElementTree as ET
from pathlib import Path
from contextlib import contextmanager
PKG='io.github.xgl34222220.hetu'
ADB=str(Path(os.environ['ANDROID_HOME'])/'platform-tools'/'adb')
OUT=Path(os.environ.get('HETU_SMOKE_OUT','out/android-smoke')); OUT.mkdir(parents=True,exist_ok=True)
from mock_webview_controller import controller_fixture, panel_controller_fixture, PANEL_AUTH_SECRET
from native_webview_bounds import webview_bounds
from native_scroll_bounds import scroll_bounds, scroll_gesture
from run_hetu_emulator import verify_owned_emulator_target
checks=[]
def adb(*args, timeout=90, check=True):
 try:
  return subprocess.run([ADB,*args],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout,check=check).stdout.decode(errors='replace')
 except subprocess.TimeoutExpired as error:
  (OUT/'adb-timeout.txt').write_text(' '.join(args)+'\n'+(error.output or b'').decode(errors='replace'))
  raise
ui_sequence=0
def ui():
 global ui_sequence
 deadline=time.monotonic()+60
 last='No snapshot attempted'
 while time.monotonic()<deadline:
  ui_sequence+=1
  path=f'/data/local/tmp/hetu-smoke-{ui_sequence}.xml'
  try:
   dumped=adb('shell','uiautomator','dump',path,timeout=min(20,max(1,deadline-time.monotonic())),check=False)
   if 'Permission denied' in dumped:raise PermissionError(dumped)
   raw=adb('shell','cat',path,timeout=15,check=False)
   start=raw.find('<?xml')
   if start>=0:
    try:return ET.fromstring(raw[start:]),raw[start:]
    except ET.ParseError as error:last='Incomplete hierarchy: '+str(error)
   else:
    if 'Permission denied' in raw:raise PermissionError(raw)
    last=dumped+'\n'+raw
   with (OUT/'ui-snapshot-retries.txt').open('a') as log:log.write(path+'\n'+last+'\n')
  except subprocess.TimeoutExpired as error:last='Snapshot command timeout: '+str(error)
  finally:
   adb('shell','rm','-f',path,timeout=10,check=False)
  time.sleep(1)
 raise AssertionError('No fresh accessibility hierarchy within60s: '+last)

def save_png(name):
 png=subprocess.run([ADB,'exec-out','screencap','-p'],check=True,stdout=subprocess.PIPE,timeout=45).stdout
 assert png.startswith(b'\x89PNG\r\n\x1a\n');(OUT/(name+'.png')).write_bytes(png)

def capture(name):
 try:root,raw=ui()
 except Exception:
  save_png(name)
  raise
 (OUT/(name+'.xml')).write_text(raw)
 save_png(name)
 return root

def click(label,scroll=False,bottom=False):
 for attempt in range(7 if scroll else 1):
  root,_=ui();found=[n for n in root.iter('node') if n.get('text')==label or n.get('content-desc')==label]
  viewport=scroll_bounds(root,PKG,allow_missing=True) if scroll else None
  if bottom: found.sort(key=lambda n: int(re.findall(r'\d+',n.get('bounds','[0,0][0,0]'))[1]),reverse=True)
  if found:
   x1,y1,x2,y2=map(int,re.findall(r'\d+',found[0].get('bounds','')))
   assert x2>x1 and y2>y1,(label,found[0].attrib)
   gesture=scroll_gesture(viewport,(x1,y1,x2,y2)) if scroll else None
   if gesture is not None:
    adb('shell','input','swipe',*(str(v) for v in gesture),'400');time.sleep(1);continue
   adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(2);return
  if scroll: adb('shell','input','swipe',*(str(v) for v in scroll_gesture(viewport)),'400');time.sleep(1)
 raise AssertionError('UI label unavailable: '+label)

def expect(label,name):
 root=capture(name);assert any(label in n.get('text','') or label in n.get('content-desc','') for n in root.iter('node')),(label,name)
 checks.append({'name':name,'result':'passed'})

# The home title is localized since the home/panel refactor (河图/Hetu/河圖);
# the emulator boots en-US, so the title check must accept every variant.
HOME_TITLES=('河图','Hetu','河圖')
def expect_any(labels,name):
 root=capture(name);assert any(any(label==n.get('text','') for label in labels) for n in root.iter('node')),(labels,name)
 checks.append({'name':name,'result':'passed'})

def switch_node(root,label):
 parents={child:parent for parent in root.iter() for child in parent}
 titles=[n for n in root.iter('node') if n.get('text')==label]
 assert len(titles)==1,('Expected one switch label',label,len(titles))
 row=titles[0]
 while row in parents:
  row=parents[row]
  switches=[n for n in row.iter('node') if n.get('checkable')=='true' and n.get('clickable')=='true']
  if switches:
   assert len(switches)==1,('Ambiguous switch row',label)
   return switches[0]
 raise AssertionError('No accessible switch for '+label)

def click_switch(label):
 root,_=ui();node=switch_node(root,label)
 assert node.get('enabled')=='true' and node.get('checked')=='false',node.attrib
 x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds','')))
 assert x2>x1 and y2>y1,node.attrib
 adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(2)

def expect_eventually(label,name):
 deadline=time.monotonic()+120
 while time.monotonic()<deadline:
  root,_=ui()
  if any(label in n.get('text','') or label in n.get('content-desc','') for n in root.iter('node')):
   expect(label,name);return
  time.sleep(2)
 raise AssertionError('Timed out waiting for accessibility label: '+label)

def install_network_event_fixture(full=False):
 # Only this job's fresh AOSP app sandbox; never overwrite a retained segment.
 directory='no_backup/hetu-network-events'
 name='events-00000000000000000002.jsonl' if full else 'events-00000000000000000001.jsonl'
 target=directory+'/'+name
 adb('shell','run-as',PKG,'mkdir','-p',directory)
 adb('shell','run-as',PKG,'test','!','-e',target)
 def record(n):
  return {'schema':1,'id':str(uuid.UUID(int=n)),'timeMs':1,'elapsedMs':1,'stage':'HEALTH_RESULT','epoch':7,'outcome':'DEGRADED','code':0,'faults':['watchdog-missing']}
 rows=[record(n) for n in range(1,102)]
 rows[-1].update(configuration='https://user:TOPSECRET@private.invalid/config',authorization='Bearer TOPSECRET',message='/data/private/config.yaml')
 rows[-1]['causes']=[{'type':'java.lang.NoSuchMethodError','frames':['com.materialkolor.ktx.DynamicSchemeKt.toDynamicScheme-Iv8Zu3U:41']}]
 if full:
  # Legacy over-limit history is retained, rather than cleared by the new reader.
  rows=[dict(record(102),configuration='SYNTHETIC-PADDING-'+'x'*(2*1024*1024)),record(103)]
 source=OUT/name
 source.write_text(''.join(json.dumps(r,separators=(',',':'))+'\n' for r in rows))
 temporary='/data/local/tmp/hetu-'+name
 adb('push',str(source),temporary)
 adb('shell','run-as',PKG,'cp',temporary,target)
 actual=adb('shell','run-as',PKG,'sha256sum',target).split()[0]
 assert actual==hashlib.sha256(source.read_bytes()).hexdigest(),'Fixture transport changed journal bytes'
 return {'path':target,'sha256':actual,'latestId':rows[-1]['id'],'syntheticOnly':True,'overwrites':False}

def wait_for_home():
 # Software-emulated AOSP may present its own first-boot System UI ANR.
 # Preserve evidence, wait for that exact system process at most twice, and
 # never dismiss an application ANR or turn off Android's ANR detector.
 deadline=time.monotonic()+300
 recoveries=0
 while time.monotonic()<deadline:
  root,_=ui()
  nodes=list(root.iter('node'))
  titles=[n.get('text','') for n in nodes if n.get('resource-id')=='android:id/alertTitle']
  if "System UI isn't responding" in titles:
   assert recoveries<2,'System UI repeatedly failed to recover'
   capture(f'system-ui-anr-{recoveries+1}')
   waiters=[n for n in nodes if n.get('package')=='android' and n.get('resource-id')=='android:id/aerr_wait' and n.get('text')=='Wait']
   assert len(waiters)==1,[(n.get('text'),n.get('resource-id')) for n in nodes]
   x1,y1,x2,y2=map(int,re.findall(r'\d+',waiters[0].get('bounds','')))
   adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));recoveries+=1
   time.sleep(20);continue
  assert not any("isn't responding" in title for title in titles),('Unexpected application ANR',titles)
  if any(n.get('package')==PKG and n.get('text') in HOME_TITLES for n in nodes):
   (OUT/'first-frame-ready.json').write_text(json.dumps({'system_ui_wait_recoveries':recoveries,'app_visible':True}))
   return
  assert adb('shell','pidof',PKG,check=False).strip(),'App process exited before first frame'
  time.sleep(5)
 raise AssertionError('Hetu did not become visible after system startup settled')

@contextmanager
def webview_cdp():
 pid=adb('shell','pidof',PKG).strip()
 assert pid.isdigit(),('Expected one app process',pid)
 port=adb('forward','tcp:0','localabstract:webview_devtools_remote_'+pid).strip()
 assert port.isdigit(),port
 try:yield int(port)
 finally:
  adb('forward','--remove','tcp:'+port)
  remaining=adb('forward','--list')
  assert not any(len(parts)>1 and parts[1]=='tcp:'+port for parts in (line.split() for line in remaining.splitlines())),remaining
  proof=OUT/'webview-forward-cleanup.json'
  entries=json.loads(proof.read_text()) if proof.exists() else []
  entries.append({'app_pid':int(pid),'local_port':int(port),'removed':True})
  proof.write_text(json.dumps(entries,indent=2))

def web_command(port,action='read',label=None):
 command=['node',str(Path(__file__).with_name('check_hetu_webview.cjs')),str(port),action]
 if label is not None:command.append(label)
 result=subprocess.run(command,check=False,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=45)
 if result.returncode:
  detail=result.stderr.decode(errors='replace')+'\n'+result.stdout.decode(errors='replace')
  (OUT/'webview-driver-error.txt').write_text(detail)
  raise AssertionError('Native WebView driver failed: '+detail)
 return json.loads(result.stdout.decode())['value']

def web_expect(port,label,name,required=None):
 deadline=time.monotonic()+120
 value={}
 while time.monotonic()<deadline:
  value=web_command(port)
  if label in value.get('text','') and all(value.get(k)==v for k,v in (required or {}).items()):
   # DOM readiness precedes Android's compositor. The CDP read awaits two
   # animation frames; then recheck after a short surface-settle interval.
   time.sleep(.4)
   value=web_command(port)
   if label not in value.get('text','') or not all(value.get(k)==v for k,v in (required or {}).items()):continue
   (OUT/(name+'.dom.json')).write_text(json.dumps(value,ensure_ascii=False,indent=2))
   capture(name) # Wait for the native accessibility/UI idle snapshot before screencap.
   checks.append({'name':name,'result':'passed','evidence':'actual Android WebView DOM + native adb screenshot'})
   return
  time.sleep(1)
 raise AssertionError(('Actual WebView did not reach expected state',label,required,value))

def verify_distinct_webview_frames(output):
 expected={f'reference-03B-{number:03d}-native-{state}.png' for number,state in [(40,'overview'),(41,'groups'),(42,'connections'),(43,'node-dialog'),(44,'switch-failure')]}
 frames=sorted(output.glob('reference-03B-04*-native-*.png'))
 assert {p.name for p in frames}==expected,('Missing native frame', [p.name for p in frames])
 frame_hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in frames}
 (output/'native-webview-frame-hashes.json').write_text(json.dumps(frame_hashes,indent=2))
 assert len(set(frame_hashes.values()))==5,('Stale duplicate native frames',frame_hashes)

def web_click(port,label):
 # CDP is read-only here: resolve layout, then send the same native input path
 # used by other installed-APK checks. JS click() can precede surface painting.
 target=web_command(port,'locate',label)
 assert target.get('located')==label,target
 root,_=ui()
 (x1,y1,x2,y2),document_nodes=webview_bounds(root,PKG)
 width,height=target['viewportWidth'],target['viewportHeight']
 assert width>0 and height>0 and x2>x1 and y2>y1,target
 assert 0<target['x']<width and 0<target['y']<height,('Offscreen button',target)
 x=round(x1+target['x']*(x2-x1)/width);y=round(y1+target['y']*(y2-y1)/height)
 assert x1<x<x2 and y1<y<y2,('Tap outside WebView',x,y)
 proof=OUT/'native-webview-input.json'
 entries=json.loads(proof.read_text()) if proof.exists() else []
 entries.append({'label':label,'webview_bounds':[x1,y1,x2,y2],'document_nodes':document_nodes,'viewport':[width,height],'tap':[x,y],'input':'native adb tap'})
 proof.write_text(json.dumps(entries,ensure_ascii=False,indent=2))
 adb('shell','input','tap',str(x),str(y));time.sleep(2)

def palette_preferences(original,accent,palette,appearance,monet=False):
 # Only a fresh disposable emulator fixture is edited. Preserve unrelated keys.
 root=ET.fromstring(original) if original.lstrip().startswith('<?xml') else ET.Element('map')
 changes={'accentHex':('string',accent),'colorPalette':('string',palette),
          'appearance':('string',appearance),'enableMonet':('boolean',str(monet).lower())}
 for key,(kind,value) in changes.items():
  for node in list(root):
   if node.get('name')==key:root.remove(node)
  node=ET.SubElement(root,kind,{'name':key})
  if kind=='string':node.text=value
  else:node.set('value',value)
 return ET.tostring(root,encoding='utf-8',xml_declaration=True)

def write_fixture_preferences(data):
 adb('shell','am','force-stop',PKG)
 subprocess.run([ADB,'shell','run-as',PKG,'sh','-c',"'cat > shared_prefs/hetu.xml'"],
                input=data,stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True,timeout=30)

def palette_scripts_cases(component,apk,expect_crash=False):
 original=adb('shell','run-as',PKG,'cat','shared_prefs/hetu.xml',check=False)
 palettes=['TonalSpot','Neutral','Vibrant','Expressive','Rainbow','FruitSalad','Monochrome','Fidelity']
 cases=[('#EF4444',palette,appearance,False) for appearance in ['light','dark'] for palette in palettes]
 cases += [('#2A62E8','Vibrant',appearance,False) for appearance in ['light','dark']]
 cases += [('#22C55E','Fidelity','dark',False),('#A855F7','FruitSalad','light',False),('#22C55E','Expressive','dark',True)]
 if expect_crash:cases=cases[:1]
 try:
  for index,(accent,palette,appearance,monet) in enumerate(cases):
   write_fixture_preferences(palette_preferences(original,accent,palette,appearance,monet))
   adb('shell','am','start','-W','-n',component,timeout=240)
   wait_for_home()
   click('工具',bottom=True);click('脚本',scroll=True)
   if expect_crash:
    log=adb('logcat','-d','-v','brief',timeout=30)
    (OUT/'expected-v2074-palette-crash-logcat.txt').write_text(log)
    report=adb('shell','run-as',PKG,'cat','files/last-app-crash.txt',check=False)
    (OUT/'expected-v2074-palette-crash-record.txt').write_text(report)
    save_png('expected-v2074-palette-crash')
    assert re.search(r'FATAL EXCEPTION[\s\S]{0,1000}Process: '+re.escape(PKG),log),log[-4000:]
    assert 'NoSuchMethodError' in log and 'com.materialkolor.scheme.SchemeTonalSpot' in log,log[-4000:]
    assert 'screen=ProxyScriptsActivity' in report and 'app=0.12.4-v20 (2074)' in report,report
    (OUT/'results.json').write_text(json.dumps({'result':'expected_failure_reproduced','apkSha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
       'versionCode':2074,'accent':accent,'palette':palette,'appearance':appearance,
       'screen':'ProxyScriptsActivity','exception':'NoSuchMethodError','apiLevel':int(adb('shell','getprop','ro.build.version.sdk').strip()),
       'fixtureOnly':True,'limitations':'Controlled AOSP reproduction of historical APK. No phone Root or network validation.'},indent=2))
    return
   expect('服务启动前',f'custom-palette-{index:02d}-{palette}-{appearance}')
   assert adb('shell','pidof',PKG,check=False).strip(),'Custom palette exited app process'
   assert 'ProxyScriptsActivity' in adb('shell','dumpsys','activity','activities'), 'Expected actual scripts activity'
 finally:
  if original.lstrip().startswith('<?xml'):
   write_fixture_preferences(original.encode())
  else:
   adb('shell','am','force-stop',PKG)
   adb('shell','run-as',PKG,'rm','-f','shared_prefs/hetu.xml')
 if not expect_crash:
  adb('shell','am','start','-W','-n',component,timeout=240)
  wait_for_home()

def panel_fixture_target():
 # Refuse preference injection outside the existing owned fresh AOSP job.
 serial=adb('get-serialno').strip()
 assert serial=='emulator-5554',('Not the owned fresh emulator',serial)
 owned=verify_owned_emulator_target(os.environ)
 # Console commands are fire-and-forget. An empty reply is not an identity;
 # the live host supervisor is mandatory, while conflicting console names fail.
 avd=[line.strip() for line in adb('emu','avd','name').splitlines() if line.strip() and line.strip()!='OK']
 assert not avd or avd==['hetu-smoke'],('Unexpected AVD',avd)
 assert adb('shell','getprop','ro.kernel.qemu').strip()=='1','Panel fixture requires an emulator'
 assert adb('shell','id','-u').strip()=='2000','Panel fixture requires the non-root AOSP shell'
 api=int(adb('shell','getprop','ro.build.version.sdk').strip())
 assert api in (35,36),('Unexpected fixture API',api)
 assert api==owned['apiLevel'],('Guest API differs from owned AOSP AVD',api,owned['apiLevel'])
 return dict(owned,serial=serial,nonRootShell=True,consoleAvdReply=avd)

def panel_fixture_preferences(original,port):
 root=ET.fromstring(original)
 assert root.tag=='map','Expected existing SharedPreferences map'
 changes={
  'proxyRootRuntimeRunning':('boolean','true'),'proxyRootWanted':('boolean','true'),
  'proxyUiLastRunning':('boolean','true'),'startOnPanel':('boolean','true'),
  'defaultPanelSection':('string','overview'),'proxyRootAutoStart':('boolean','false'),
  'networkMatchEnabled':('boolean','false'),'latencyAutoRefreshSeconds':('int','0'),
  'proxyStatusNotificationEnabled':('boolean','false'),
  'proxyCustomApiEnabled':('boolean','true'),'proxyCustomApiHost':('string','127.0.0.1'),
  'proxyCustomApiPort':('int',str(port)),
  'proxyCustomApiSecret':('string',PANEL_AUTH_SECRET),
  'hetuCiSyntheticCachedRuntimeHint':('boolean','true'),
 }
 for key,(kind,value) in changes.items():
  for node in list(root):
   if node.get('name')==key:root.remove(node)
  node=ET.SubElement(root,kind,{'name':key})
  if kind=='string':node.text=value
  else:node.set('value',value)
 return ET.tostring(root,encoding='utf-8',xml_declaration=True)

@contextmanager
def panel_preferences_fixture(port,report):
 original=adb('shell','run-as',PKG,'cat','shared_prefs/hetu.xml').encode()
 replacement=panel_fixture_preferences(original,port) # Validate before force-stop/write.
 report['originalPreferencesSha256']=hashlib.sha256(original).hexdigest()
 report['syntheticCachedRuntimeHint']=True
 try:
  write_fixture_preferences(replacement)
  yield
 finally:
  write_fixture_preferences(original)
  restored=adb('shell','run-as',PKG,'cat','shared_prefs/hetu.xml').encode()
  report['restoredPreferencesSha256']=hashlib.sha256(restored).hexdigest()
  report['originalPreferencesRestored']=restored==original
  assert report['originalPreferencesRestored'],'Panel fixture did not restore original preference bytes'

def panel_labels(root):
 return [value for node in root.iter('node') for key in ('text','content-desc') if (value:=node.get(key,''))]

def assert_native_panel(root):
 assert not any(node.get('class')=='android.webkit.WebView' for node in root.iter('node')),'Expected actual NativeCompose panel, not WebUI'

def assert_panel_read_failure(root):
 assert_native_panel(root)
 labels=panel_labels(root)
 assert '无法读取面板' in labels and any('HTTP 401' in label for label in labels),labels
 assert not any(label in ('运行概况','当前连接') for label in labels),'Failed first controller read displayed an overview/counter'

def panel_counter(root,label):
 # Compose emits separate value/caption accessibility nodes. Pair them by their
 # actual column positions, rather than accepting an unrelated12/18 elsewhere.
 captions=[n for n in root.iter('node') if n.get('text')==label]
 bounds=lambda n:tuple(map(int,re.findall(r'\d+',n.get('bounds',''))))
 matches=[]
 for caption in captions:
  x1,y1,x2,y2=bounds(caption)
  candidates=[]
  for node in root.iter('node'):
   value=node.get('text','')
   if not re.fullmatch(r'\d+',value):continue
   a,b,c,d=bounds(node)
   if x1-4<=(a+c)/2<=x2+4 and 0<=y1-d<=80 and c>a and d>b:
    candidates.append((y1-d,value))
  if not candidates:continue # The strip also has a “策略” tab without a value.
  nearest=min(gap for gap,_ in candidates)
  values=[value for gap,value in candidates if gap==nearest]
  assert len(values)==1,('Ambiguous native panel counter',label,values)
  matches.append(int(values[0]))
 assert len(matches)==1,('Expected one numeric native panel counter',label,matches)
 return matches[0]

def capture_panel_when(labels,name,counts=None):
 deadline=time.monotonic()+120
 last=[]
 while time.monotonic()<deadline:
  root,_=ui();last=panel_labels(root)
  if all(any(label in value for value in last) for label in labels):
   try:
    assert_native_panel(root)
    if counts is not None:
     assert {label:panel_counter(root,label) for label in counts}==counts
   except AssertionError:
    time.sleep(1);continue
   root=capture(name)
   assert_native_panel(root)
   if counts is not None:assert {label:panel_counter(root,label) for label in counts}==counts
   return root
  time.sleep(1)
 raise AssertionError(('Native panel state did not become visible',name,labels,counts,last))

def panel_retry_control(root):
 parents={child:parent for parent in root.iter() for child in parent}
 matches=[n for n in root.iter('node') if n.get('text')=='重试' or n.get('content-desc')=='重试']
 assert len(matches)==1,('Expected one real native retry control',len(matches))
 node=matches[0]
 while node.get('clickable')!='true' and node in parents:node=parents[node]
 assert node.get('clickable')=='true',node.attrib
 return node

def wait_panel_retry_ready(root,timeout=30):
 # Foreground polls can retain the401 message while disabling retry in flight.
 # Only wait for a fresh real enabled control while the fixture remains401.
 # Missing/ambiguous controls, WebView or an invented overview still fail.
 deadline=time.monotonic()+timeout
 while True:
  assert_panel_read_failure(root)
  node=panel_retry_control(root)
  if node.get('enabled')=='true':return root
  assert node.get('enabled')=='false',node.attrib
  assert time.monotonic()<deadline,('Native panel retry stayed disabled',node.attrib)
  time.sleep(.25)
  root,_=ui()

def tap_panel_retry(root):
 node=panel_retry_control(root)
 assert node.get('enabled')=='true',node.attrib
 x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds','')))
 assert x2>x1 and y2>y1,node.attrib
 tapped=time.monotonic()
 adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
 return {'timeMonotonic':tapped,'bounds':[x1,y1,x2,y2],'input':'native adb tap'}

def panel_controller_auth_cases(component):
 target=panel_fixture_target()
 report={'result':'FAIL','fixtureOnly':True,'syntheticCachedRuntimeHint':True,
  'rootMutationActions':0,'controllerHost':'127.0.0.1','target':target,
  'rootHealthEvidence':False,'toast401Claimed':False,
  'limitations':'Synthetic cached running hint in the owned fresh non-root AOSP sandbox. Loopback401/200 exercises NativeCompose error/recovery only; no real Root/core/network health evidence.'}
 first_check=len(checks)
 device_port=29184
 reverse_before=adb('reverse','--list').splitlines()
 assert not any(f'tcp:{device_port}' in line.split() for line in reverse_before),'Panel fixture would overwrite an existing adb reverse'
 reverse_owned=False
 preferences_entered=False
 try:
  with panel_controller_fixture() as (port,requests,mode):
   try:
    adb('reverse',f'tcp:{device_port}',f'tcp:{port}');reverse_owned=True
    with panel_preferences_fixture(device_port,report):
     preferences_entered=True
     adb('shell','am','start','-W','-n',component,timeout=240)
     # startOnPanel/defaultPanelSection are test-only display hints. No proxy
     # start/restart action, boot broadcast, CDP mutation or API Save is sent.
     root=capture_panel_when(['无法读取面板','HTTP 401'],'panel-controller-401-first-read')
     assert_panel_read_failure(root)
     assert any(r.get('method')=='GET' and r.get('status')==401 for r in requests),requests
     services=adb('shell','dumpsys','activity','services',PKG)
     (OUT/'panel-fixture-services.txt').write_text(services)
     assert 'ServiceRecord' not in services or 'ProxyNetworkMatchService' not in services,'Synthetic Wanted unexpectedly started continuity service'
     report['continuityServiceObserved']=False
     checks.append({'name':'panel-controller-401-first-read','result':'passed','evidence':'actual NativeCompose UI/XML + loopback HTTP401'})
     click('API 设置')
     root=capture_panel_when(['测速与 API','外部 Clash API'],'panel-controller-401-api-settings')
     assert 'WebUI' not in panel_labels(root)
     checks.append({'name':'panel-controller-401-api-settings','result':'passed','evidence':'actual NativeCompose ApiSheet opened by native tap'})
     adb('shell','input','keyevent','4');time.sleep(1)
     root=capture_panel_when(['无法读取面板','HTTP 401'],'panel-controller-401-api-return')
     assert_panel_read_failure(root)
     denied_tap=tap_panel_retry(wait_panel_retry_ready(root))
     report['retryWhile401']=denied_tap
     root=capture_panel_when(['无法读取面板','HTTP 401'],'panel-controller-401-retry')
     assert_panel_read_failure(root)
     assert any(r.get('status')==401 and r.get('timeMonotonic',0)>=denied_tap['timeMonotonic'] for r in requests),'No actual HTTP401 request after native retry input'
     checks.append({'name':'panel-controller-401-retry','result':'passed','evidence':'native retry input while fixture remained401; subsequent HTTP401 and explicit error UI'})
     root=wait_panel_retry_ready(root)
     report['retryReadyBeforeFixture200']={'observedAtMonotonic':time.monotonic(),'enabled':True,'source':'fresh native XML while fixture remained401'}
     report['fixture200AtMonotonic']=time.monotonic();mode.set_status(200)
     # Send retry using bounds from the just-captured real error state. A2s
     # foreground poll may win this race; record timing and never claim that
     # recovery itself proves which request was initiated by the retry.
     report['retryAfterFixture200']=tap_panel_retry(root)
     expected={'策略':12,'规则':3,'当前连接':18}
     root=capture_panel_when(['运行概况'],'panel-controller-200-recovered',expected)
     assert not any('无法读取面板' in label or 'HTTP 401' in label for label in panel_labels(root)),panel_labels(root)
     required={'/configs','/proxies','/providers/proxies','/connections','/rules'}
     succeeded={r['path'] for r in requests if r.get('status')==200 and r.get('valid_fixture_authorization') is True and r.get('timeMonotonic',0)>=report['fixture200AtMonotonic']}
     assert required<=succeeded,('Recovery lacked complete real controller reads',succeeded)
     assert all(r.get('valid_fixture_authorization') is True for r in requests if r.get('status')==200),'Controller fixture accepted an unauthenticated200 request'
     report['actualBearerAuthorizationVerified']=True
     report['observedNativeCounts']=expected
     first_success=min(r['timeMonotonic'] for r in requests if r.get('status')==200)
     report['first200AtMonotonic']=first_success
     report['recoveryObservation']='poll completed before retry input' if first_success<report['retryAfterFixture200']['timeMonotonic'] else 'retry input preceded successful reads; foreground poll also remained active'
     report['exclusiveRetryRecoveryClaimed']=False
     checks.append({'name':'panel-controller-200-recovered','result':'passed','evidence':'actual NativeCompose12 strategies/3 rules/18 connections + complete loopback HTTP200 request log'})
   except Exception:
    try:capture('panel-controller-fixture-failure')
    except Exception:pass
    raise
   finally:
    (OUT/'panel-controller-fixture-requests.json').write_text(json.dumps(requests,ensure_ascii=False,indent=2))
 finally:
  try:
   if reverse_owned:
    adb('reverse','--remove',f'tcp:{device_port}')
    reverse_after=adb('reverse','--list').splitlines()
    report['adbReverseRestored']=sorted(reverse_after)==sorted(reverse_before)
    assert report['adbReverseRestored'],'Panel fixture changed a retained adb reverse'
   if preferences_entered:
    adb('shell','am','start','-W','-n',component,timeout=240)
    wait_for_home()
  finally:
   report['newChecks']=checks[first_check:]
   (OUT/'panel-controller-auth-fixture.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
 assert report.get('originalPreferencesRestored') and report.get('adbReverseRestored'),report
 checks.append({'name':'panel-controller-fixture-cleanup','result':'passed','evidence':'original preference bytes and retained adb reverses restored; original launcher flow relaunched'})
 report['newChecks']=checks[first_check:];report['result']='PASS'
 (OUT/'panel-controller-auth-fixture.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))

def main():
 end=time.monotonic()+900
 while time.monotonic()<end:
  try:
   if adb('shell','getprop','sys.boot_completed',timeout=10,check=False).strip()=='1':break
  except subprocess.TimeoutExpired:pass
  time.sleep(5)
 else: raise AssertionError('Emulator boot did not complete in 15 minutes')
 # Boot-completed precedes some first-boot package/role work under software emulation.
 ready_until=time.monotonic()+180
 stable=0
 while time.monotonic()<ready_until:
  try:
   packages=adb('shell','cmd','package','list','packages','android',timeout=30,check=False)
   animation=adb('shell','getprop','init.svc.bootanim',timeout=15,check=False).strip()
   stable=stable+1 if 'package:android' in packages and animation in ('stopped','') else 0
   if stable>=3:
    (OUT/'boot-ready.json').write_text(json.dumps({'boot_completed':True,'boot_animation':animation,'package_manager_stable_samples':stable}))
    break
  except subprocess.TimeoutExpired:stable=0
  time.sleep(5)
 else:raise AssertionError('Package manager/boot animation did not settle')
 if os.environ.get('HETU_EXPECT_NEW_UI')=='1' and int(os.environ.get('HETU_EXPECTED_VERSION','0'))>=2084:
  (OUT/'panel-target-preflight.json').write_text(json.dumps(panel_fixture_target(),indent=2)+'\n')
 adb('shell','input','keyevent','82');time.sleep(3)
 apk=next(Path('candidate').glob('*.apk'))
 installed=adb('install','--no-streaming','-r',str(apk),timeout=600)
 (OUT/'install.txt').write_text(installed)
 assert 'Success' in installed,installed
 version=adb('shell','dumpsys','package',PKG);assert 'versionCode='+os.environ.get('HETU_EXPECTED_VERSION','2070') in version
 (OUT/'version.txt').write_text('\n'.join(line for line in version.splitlines() if 'versionCode=' in line or 'versionName=' in line))
 components=adb('shell','cmd','package','query-activities','--brief','--components','--query-flags','0','--user','0','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-p',PKG)
 choices=[line.strip() for line in components.splitlines() if line.strip().startswith(PKG+'/')];assert choices,components
 adb('logcat','-c')
 (OUT/'launch.txt').write_text(adb('shell','am','start','-W','-n',choices[0],timeout=240))
 wait_for_home()
 assert adb('shell','pidof',PKG,check=False).strip(),'App process exited at launch'
 expect_any(HOME_TITLES,'01-home')
 # New language binding follows the actual system locale. Exercise the visible
 # preference picker before collecting the Chinese-reference navigation set.
 root,_=ui()
 if any(n.get('text')=='Settings' for n in root.iter('node')):
  click('Settings',bottom=True);click('Language and appearance');click('Language')
  click('简体中文');expect('主题设置','00-language-selected')
  adb('shell','input','keyevent','4');time.sleep(2);click('首页',bottom=True)
  expect('河图','01-home-language-restored')
 if os.environ.get('HETU_EXPECT_NEW_UI')=='1':
  expect('本机直测','01-home-new-ui')
 if os.environ.get('HETU_EXPECT_PALETTE_CRASH')=='1':
  palette_scripts_cases(choices[0],apk,expect_crash=True)
  return
 click('面板',bottom=True);expect('代理未运行','02-panel-stopped')
 if os.environ.get('HETU_EXPECT_NEW_UI')=='1':
  expect('概览','02-panel-new-ui-overview')
  expect('规则集','02-panel-new-ui-rulesets')
 click('工具',bottom=True);expect('文件管理','03-tools')
 click('脚本',scroll=True);expect('服务启动前','scripts-entry');expect('服务停止后','scripts-stop')
 expect('详情','scripts-root-error-details');click('详情');expect('复制诊断','scripts-root-error-expanded')
 click('脚本环境');expect('HETU_HOOK','scripts-environment')
 adb('shell','input','keyevent','4');time.sleep(2)
 click('更多');expect('导入自定义脚本','scripts-overflow')
 click('导入自定义脚本');root=capture('scripts-document-picker')
 assert any(n.get('package','').endswith('.documentsui') for n in root.iter('node')),'OS document picker did not open'
 checks.append({'name':'scripts-document-picker','result':'passed'})
 adb('shell','input','keyevent','4');time.sleep(2)
 expect('服务启动前','scripts-import-cancelled')
 adb('shell','input','keyevent','4');time.sleep(2)

 click('设置',bottom=True);expect('基础代理配置','04-settings')
 click('开机启动与下载',scroll=True);expect('开机自启','boot-settings')
 click_switch('开机自启');expect_eventually('无法取得 Root 权限','boot-root-denial-visible')
 root,_=ui();assert switch_node(root,'开机自启').get('checked')=='false','Root denial left switch enabled'
 raw=adb('shell','run-as',PKG,'cat','shared_prefs/hetu.xml')
 prefs=ET.fromstring(raw)
 assert not any(n.get('name')=='proxyRootAutoStart' and n.get('value')=='true' for n in prefs),'Failed setup was falsely saved as enabled'
 checks.append({'name':'boot-denial-keeps-toggle-off','result':'passed'})
 adb('shell','input','keyevent','4');time.sleep(2)

 click('关于',scroll=True);expect('内置核心','05-about')
 adb('shell','input','keyevent','4');time.sleep(2)
 click('工具',bottom=True);click('诊断工具',scroll=True)
 expect('网络事件记录','network-events-entry')
 # The concept detail delegates retained journal/session tools to the existing Hx page.
 click('网络事件记录',scroll=True)
 click('网络事件记录',scroll=True);expect_eventually('尚无网络事件记录','network-events-without-root')
 expect('storageLimitBytes=2097152','network-events-storage-bound-visible')
 adb('shell','input','keyevent','4');time.sleep(2)
 journal_fixture=install_network_event_fixture()
 click('网络事件记录',scroll=True);expect_eventually('viewTruncated=true','network-events-truncation-visible')
 raw=(OUT/'network-events-truncation-visible.xml').read_text()
 assert journal_fixture['latestId'] in raw,'Latest trace ID was omitted'
 assert 'toDynamicScheme-Iv8Zu3U:41' in raw,'Legitimate Kotlin source location was redacted'
 assert 'TOPSECRET' not in raw and 'private.invalid' not in raw and '/data/private' not in raw,'Poisoned metadata reached report UI'
 click('复制');expect('网络事件记录','network-events-copy-action')
 # Installed copy input is sanitized above; real ClipboardManager contents are
 # checked through hxCopy in Robolectric, without new emulator permissions.
 adb('shell','input','keyevent','4');time.sleep(2)
 full_fixture=install_network_event_fixture(full=True)
 click('网络事件记录',scroll=True);expect_eventually('storagePaused=true','network-events-full-history-preserved')
 assert adb('shell','run-as',PKG,'sha256sum',journal_fixture['path']).split()[0]==journal_fixture['sha256'],'Reading full history modified an earlier segment'
 (OUT/'network-journal-fixture.json').write_text(json.dumps({'recent':journal_fixture,'overLimitLegacy':full_fixture,'rootMutationActions':0,'originalHistoryPreserved':True,'installedClipboardContentsRead':False,'clipboardContentsVerifiedBy':'JournalReliabilityTest actual hxCopy'},ensure_ascii=False,indent=2))
 adb('shell','input','keyevent','4');time.sleep(2)
 expect('修复运行记录','safe-session-repair-entry')
 click('修复运行记录',scroll=True);expect_eventually('无法取得 Root 权限','safe-session-repair-root-denial')
 adb('shell','input','keyevent','4');time.sleep(2)
 adb('shell','input','keyevent','4');time.sleep(2)
 adb('shell','input','keyevent','4');time.sleep(2)
 click('工具',bottom=True);click('WebUI',scroll=True);expect('河图本地面板','06-web-panels')
 click('河图本地面板');time.sleep(3)
 with webview_cdp() as web_port:
  web_expect(web_port,'河图 WebUI','07-native-webview')
  web_expect(web_port,'未连接','08-native-webview-disconnected',{'status':'未连接','metricCount':0})
 adb('shell','input','keyevent','4');time.sleep(2);expect('河图本地面板','09-webview-back')
 # Fresh emulator only: no proxy process, subscription, network rules or Root.
 # Server listens solely on host127.0.0.1; always remove the test-only reverse.
 with controller_fixture() as (fixture_port, requests):
  device_port=29090
  adb('reverse',f'tcp:{device_port}',f'tcp:{fixture_port}')
  try:
   click('河图本地面板')
   with webview_cdp() as web_port:
    web_expect(web_port,'Mihomo v1.19.0','reference-03B-040-native-overview',{'metricCount':4})
    web_click(web_port,'策略组');web_expect(web_port,'节点选择','reference-03B-041-native-groups',{'groupCount':12})
    web_click(web_port,'连接');web_expect(web_port,'api.example.com','reference-03B-042-native-connections',{'connectionCount':18})
    web_click(web_port,'策略组');web_click(web_port,'节点选择：选择节点')
    web_expect(web_port,'香港 02','reference-03B-043-native-node-dialog',{'nodeDialogOpen':True})
    web_click(web_port,'香港 02');web_expect(web_port,'切换失败：HTTP 503','reference-03B-044-native-switch-failure',{'noticeOpen':True,'firstSelected':'香港 01'})
    assert any(r.get('method')=='PUT' and r.get('valid_fixture_request') for r in requests),requests
    verify_distinct_webview_frames(OUT)
    web_click(web_port,'确定')
  finally:
   adb('reverse','--remove',f'tcp:{device_port}')
   (OUT/'native-webview-fixture-requests.json').write_text(json.dumps(requests,ensure_ascii=False,indent=2))
 palette_scripts_cases(choices[0],apk)
 legacy_check_count=len(checks)
 palette_check_count=sum(c['name'].startswith('custom-palette-') for c in checks)
 # Older fixed-APK installation continuation keeps its original59 checks.
 # The new candidate adds an independent NativeCompose authentication fixture.
 if os.environ.get('HETU_EXPECT_NEW_UI')=='1' and int(os.environ.get('HETU_EXPECTED_VERSION','0'))>=2084:
  panel_controller_auth_cases(choices[0])
 log=adb('logcat','-d','-v','brief',timeout=30);(OUT/'logcat.txt').write_text(log)
 assert not re.search(r'FATAL EXCEPTION[\s\S]{0,1000}Process: '+re.escape(PKG),log),'Application crash in logcat'
 assert not re.search(r'ANR in '+re.escape(PKG)+r'(?:\s|\(|:)',log),'Application ANR recorded during navigation'
 (OUT/'last-anr.txt').write_text(adb('shell','dumpsys','activity','lastanr',timeout=60,check=False))
 assert adb('shell','pidof',PKG,check=False).strip(),'App process exited after navigation'
 (OUT/'results.json').write_text(json.dumps({'checks':checks,'passed':len(checks),'legacyChecksPassed':legacy_check_count,'paletteCasesPassed':palette_check_count,'newPanelAuthChecks':checks[legacy_check_count:],'fixtureOnly':True,'rootMutationActions':0,'rootSetupDenialAttempts':1,'apiLevel':int(adb('shell','getprop','ro.build.version.sdk').strip()),'limitations':'Fresh AOSP emulator. Five populated native-WebView states use an isolated loopback fixture with deliberate HTTP503 switch rejection. New panel authentication checks, when enabled for2084+, use synthetic cached running hints and loopback401/200, never real Root/core/network health. No K80, real core, Root boot or actual network validation.'},ensure_ascii=False,indent=2))
 if os.environ.get('HETU_NATIVE_SOAK90_SECONDS'):
  def application_identity():
   pids=adb('shell','pidof',PKG,check=False).split()
   assert len(pids)==1 and pids[0].isdigit(),'Missing unique application process during native soak'
   pid=int(pids[0]);raw=adb('shell','cat',f'/proc/{pid}/stat')
   end=raw.rfind(')');fields=raw[end+2:].split()
   assert end>0 and int(raw.split(' ',1)[0])==pid and len(fields)>=20 and fields[0] not in ('Z','X'),'Application process is not live'
   return {'pid':pid,'startTicks':int(fields[19]),'package':PKG,'proof':'installed package PID and Android proc start ticks'}
  app_before=application_identity();period_started=time.monotonic()
  sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'tools/qa'))
  from run_mihomo_soak90 import run_soak
  native_report=run_soak(output=OUT/'native-soak90',seconds=int(os.environ['HETU_NATIVE_SOAK90_SECONDS']),environment=os.environ,apk=apk)
  observation={'result':'FAIL','apkSha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
   'observedRepositoryCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=Path(__file__).resolve().parents[2]).decode().strip(),
   'before':app_before,'nativeSoakActualSeconds':native_report['actualContinuousSeconds'],
   'scope':'Application PID continuity while a separate owned native CLI core exchanges controlled loopback traffic',
   'limitations':'No app VPN/TPROXY, real Root, radio handover, Google/GMS, OEM or device evidence.'}
  try:
   observation['after']=application_identity()
   assert observation['before']==observation['after'],'Application process exited or restarted during native soak'
   observation['observedWallSeconds']=time.monotonic()-period_started
   assert observation['nativeSoakActualSeconds']>=900 and observation['observedWallSeconds']>=observation['nativeSoakActualSeconds']
   after_log=adb('logcat','-d','-v','brief',timeout=30);(OUT/'post-native-application-logcat.txt').write_text(after_log)
   assert not re.search(r'FATAL EXCEPTION[\s\S]{0,1000}Process: '+re.escape(PKG),after_log),'Application crash during native soak'
   assert not re.search(r'ANR in '+re.escape(PKG)+r'(?:\s|\(|:)',after_log),'Application ANR during native soak'
   # Wake and resume only this fresh owned AOSP application's existing launcher
   # after the continuity check; do not hide a process restart by relaunching it.
   adb('shell','input','keyevent','224')
   adb('shell','input','keyevent','82')
   adb('shell','am','start','-W','-n',choices[0])
   assert application_identity()==app_before,'Application process changed while resuming its launcher'
   final_root=capture('post-native-application')
   assert any(n.get('package')==PKG for n in final_root.iter('node')),'Final frame does not contain the application UI'
   assert application_identity()==app_before,'Application process changed during the final UI capture'
   observation['launcherResumedForFinalCapture']=True
   observation['applicationUiVisibleInFinalCapture']=True
   observation['noApplicationFatalOrAnrInRetainedLog']=True
   observation['result']='PASS'
  finally:
   (OUT/'post-native-application.json').write_text(json.dumps(observation,indent=2)+'\n')
try:main()
except Exception:
 (OUT/'failure-preferences.xml').write_text(adb('shell','run-as',PKG,'cat','shared_prefs/hetu.xml',check=False))
 (OUT/'last-app-crash.txt').write_text(adb('shell','run-as',PKG,'cat','files/last-app-crash.txt',check=False))
 try:capture('failure')
 except Exception:pass
 (OUT/'failure-logcat.txt').write_text(adb('logcat','-d','-v','threadtime',timeout=60,check=False))
 try:
  (OUT/'last-anr.txt').write_text(adb('shell','dumpsys','activity','lastanr',timeout=60,check=False))
  # Fresh disposable emulator contains only test fixtures. Keep the original
  # app/system ANR trace; never dismiss an app ANR or disable its detector.
  (OUT/'bugreport-command.txt').write_text(adb('bugreport',str(OUT/'bugreport.zip'),timeout=240,check=False))
 except Exception as diagnostic_error:
  (OUT/'bugreport-error.txt').write_text(repr(diagnostic_error))
 raise
