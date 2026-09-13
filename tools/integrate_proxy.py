#!/usr/bin/env python3
"""Small checked integration edits; no encoded payloads or user-data writes."""
from pathlib import Path
r=Path(__file__).resolve().parents[1]
def edit(file,old,new):
 p=r/file;s=p.read_text();assert s.count(old)==1,(file,s.count(old));p.write_text(s.replace(old,new))
base='android-app/app/src/main/java/io/github/xgl34222220/bichen/'
edit(base+'RuleStore.java','    public int count()', '    public java.util.List<String> effectiveDomains() { Snapshot s=live; return s==null?java.util.Collections.emptyList():new java.util.ArrayList<>(s.effective); }\n    public int count()')
edit(base+'MainActivity.java','    private void home(){','    private void home(){\n        LinearLayout proxyCard=card(body);actionRow(proxyCard,"activity","代理与去广告 · Mihomo",MihomoVpnService.engaged?MihomoVpnService.state:"配置、节点与真实连接",()->{if(installed()&&!prefs.contains("proxyManageHosts"))prefs.edit().putBoolean("proxyManageHosts",true).apply();startActivity(new Intent(this,ProxyActivity.class));});')
for sig in ['private void toggleProtection(){','private void prepareVpn(){','private void startVpn(){','private void restartVpn(){']:
 edit(base+'MainActivity.java',sig,sig+'if(MihomoVpnService.engaged){message("请先在代理页面停止 Mihomo，避免两套引擎重复接管");return;}')
edit(base+'DnsVpnService.java','        latestStartId = startId;', '        if(MihomoVpnService.engaged){stopSelf(startId);return START_NOT_STICKY;}\n        latestStartId = startId;')
edit(base+'BootReceiver.java','        boolean restart =', '        if ("mihomo".equals(prefs.getString("engineOwner", ""))) return;\n        boolean restart =')
edit(base+'MainActivity.java','prefs.edit().putString("preferredMode","vpn").putBoolean("vpnWanted",true)', 'prefs.edit().putString("engineOwner","legacy").putString("preferredMode","vpn").putBoolean("vpnWanted",true)')
man='android-app/app/src/main/AndroidManifest.xml'
edit(man,'    </application>','''        <activity android:name=".ProxyActivity" android:exported="false" />
        <service android:name=".MihomoVpnService" android:permission="android.permission.BIND_VPN_SERVICE" android:exported="true" android:foregroundServiceType="specialUse">
            <intent-filter><action android:name="android.net.VpnService" /></intent-filter>
            <meta-data android:name="android.net.VpnService.SUPPORTS_ALWAYS_ON" android:value="false" />
            <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="User initiated local Mihomo proxy and domain filtering VPN" />
        </service>
    </application>''')
edit(man,'android:allowBackup="false"','android:extractNativeLibs="true" android:allowBackup="false"')
edit('tools/build_app.py','    run([tools / "zipalign", "-f", "-p", "4", BUILD / "unsigned.apk", BUILD / "aligned.apk"])','''    with zipfile.ZipFile(BUILD / "unsigned.apk", "a", zipfile.ZIP_DEFLATED) as apk:
        for file in sorted((MAIN / "jniLibs").rglob("*.so")):
            apk.write(file, "lib/" + file.relative_to(MAIN / "jniLibs").as_posix())
    run([tools / "zipalign", "-f", "-p", "4", BUILD / "unsigned.apk", BUILD / "aligned.apk"])''')
edit('tools/build_preview.py',"VERSION = '0.3.0-test.6'","VERSION = '0.4.0-test.1'")
edit('tools/build_preview.py','CODE = 306','CODE = 401')
edit('tools/build_preview.py',"'.git', 'out', 'downloads', 'build', '__pycache__'","'.git', '.upstream', 'out', 'downloads', 'build', '__pycache__'")
edit('tools/build_preview.py',"'.keystore', '.jks', '.p12', '.pyc', '.ttf', '.otf'","'.keystore', '.jks', '.p12', '.pyc', '.ttf', '.otf', '.so'")
