#!/usr/bin/env python3
"""Apply this request on UI13 only; do not replay earlier UI migrations."""
from pathlib import Path
import hashlib,json,subprocess
BASE='2150e2dcae0921d418e8bd5700e39dae535c5370'
ROOT='android-app/app/src/main/java/io/github/xgl34222220/hetu/'
TEST='android-app/app/src/test/java/io/github/xgl34222220/hetu/'
OUT=Path('integration-evidence');OUT.mkdir(exist_ok=True)
changed={}
def save(path,text):
    p=Path(path);before=p.read_text() if p.exists() else None
    p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
    changed[path]={'before':before,'after':text}
def once(s,a,b):
    assert s.count(a)==1,(a[:120],s.count(a))
    return s.replace(a,b,1)
def read_base(path):
    old=subprocess.check_output(['git','show',BASE+':'+path]).decode()
    assert Path(path).read_text()==old,('base changed',path)
    return old
for name in ['PanelPolish14.kt','RuntimeCompatibility14.java']:
    save(ROOT+name,Path('tools/ui14/'+name).read_text())

p=ROOT+'PanelStrategy11.kt';s=read_base(p)
s=once(s,'    val fill by animateColorAsState','    val label14 = remember(node.name) { nodeLabel14(node.name) }\n    val fill by animateColorAsState')
s=once(s,'else t.controlBackground.copy(alpha = .26f)','else t.cardBackground')
s=once(s,'Column(modifier.clip(RoundedCornerShape(14.dp)).background(fill)',
    'Column(modifier.diffuseCardShadow(HomeContinuousShape(18.dp)).clip(HomeContinuousShape(18.dp)).background(fill)')
s=once(s,'else t.textMuted.copy(alpha = .1f), RoundedCornerShape(14.dp))','else t.textMuted.copy(alpha = .07f), HomeContinuousShape(18.dp))')
s=once(s,'Text(node.name, Modifier.weight(1f).testTag("panel11-node-name:$group:${node.name}"),','Text(label14.title, Modifier.weight(1f).testTag("panel11-node-name:$group:${node.name}"),')
s=once(s,'color = t.textPrimary, fontSize = 12.sp, lineHeight = 18.sp,','color = t.textPrimary, fontSize = 14.sp, lineHeight = 20.sp,')
s=once(s,'maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)',
    'maxLines = if (compact) 2 else 3, overflow = TextOverflow.Ellipsis)')
s=once(s,'        Text(listOf(node.type.uppercase(Locale.ROOT)',
    '        NodeTags14(label14.tags, Modifier.testTag("panel14-node-tags:$group:${node.name}"))\n        Text(listOf(node.type.uppercase(Locale.ROOT)')
start=s.index('@Composable\ninternal fun PanelDelayLabel11(')
s=s[:start]+'''@Composable
internal fun PanelDelayLabel11(value: Long?, loading: Boolean) {
    QuietDelay14(value, loading)
}
'''
save(p,s)

p=ROOT+'PanelNavigation13.kt';s=read_base(p)
s=once(s,'    val batchTesting = remember { mutableStateMapOf<String, Boolean>() }',
    '    val batchTesting = remember { mutableStateMapOf<String, Boolean>() }\n    val batchDone14 = remember { mutableStateMapOf<String, Int>() }')
s=once(s,'        batchTesting[group.name] = true\n        scope.launch {','        batchTesting[group.name] = true\n        batchDone14[group.name] = 0\n        scope.launch {')
s=once(s,'coroutineScope { chunk.map { async { testNode(it) } }.awaitAll() }',
    'coroutineScope { chunk.map { name -> async { try { testNode(name) } finally { batchDone14[group.name] = (batchDone14[group.name] ?: 0) + 1 } } }.awaitAll() }')
s=once(s,'            HomePullIndicator(pull, motion, Modifier.align(Alignment.TopCenter))\n        }\n    }\n    val activeGroup',
'''            HomePullIndicator(pull, motion, Modifier.align(Alignment.TopCenter))
        }
        PanelShortcut14(state.running && groups.isNotEmpty()) {
            val target = groups.firstOrNull { it.name == "节点选择" }
                ?: groups.firstOrNull { it.type.equals("Selector", true) && it.name != "GLOBAL" }
                ?: groups.firstOrNull()
            if (target != null) { notice = ""; path = listOf(target.name) }
        }
    }
    val activeGroup''')
s=once(s,'nestedNames = allGroups.keys, onNested = { path = panelPush13(path, it); notice = "" })',
    'nestedNames = allGroups.keys, onNested = { path = panelPush13(path, it); notice = "" },\n        batchCompleted14 = batchDone14[activeGroup.name] ?: 0)')
s=once(s,'onTestAll: () -> Unit, onName: (String) -> Unit, nestedNames: Set<String>, onNested: (String) -> Unit) {',
    'onTestAll: () -> Unit, onName: (String) -> Unit, nestedNames: Set<String>, onNested: (String) -> Unit,\n    batchCompleted14: Int = 0) {')
s=once(s,'Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).navigationBarsPadding().imePadding())',
    'Column(Modifier.fillMaxWidth().fillMaxHeight(.76f).navigationBarsPadding().imePadding())')
s=once(s,'                    if (batchTesting) NativeSpinner(Color(0xFF2563EB), LocalHetuMotionEnabled.current, Modifier.size(14.dp))\n                    Text(if (batchTesting) " 测速中" else "全部测速", fontSize = 12.sp)',
    '                    Text(if (batchTesting) "${batchCompleted14}/${group.nodes.map { it.name }.distinct().size}" else "全部测速", fontSize = 12.sp)')
a=s.index('                OutlinedTextField(query, { query = it }',s.index('internal fun PanelGroupSheet13'))
b=s.index('                PanelNotice13(',a)
s=s[:a]+'                CompactNodeSearch14(query) { query = it }\n'+s[b:]
save(p,s)

p=ROOT+'MihomoStartupConfig.java';s=read_base(p)
s=once(s,'        String yaml=normalize(source);\n        yaml=sanitizeStrictDomainCompatibility(yaml);',
    '        String yaml=normalize(source);\n        yaml=RuntimeCompatibility14.migrateFingerprint(yaml);\n        yaml=sanitizeStrictDomainCompatibility(yaml);')
s=once(s,'private static String sanitizeStrictDomainCompatibility(String source){',
    'private static String sanitizeStrictDomainCompatibility(String source)throws IOException{\n        boolean ruleFilter=source.contains("fake-ip-filter-mode")&&RuntimeCompatibility14.ruleFakeIpFilter(source);')
s=once(s,'||("dns".equals(top)&&"fake-ip-filter".equals(child));','||(!ruleFilter&&"dns".equals(top)&&"fake-ip-filter".equals(child));')
s=once(s,'if(packages!=null)selected.addAll(packages);','if(scope!=ProxyRuntimeProfile.AppScope.CORE&&packages!=null)selected.addAll(packages);')
save(p,s)

p=ROOT+'ProxyAdblockRules.java';s=read_base(p)
s=once(s,'"filter-v3:"','"filter-v4-sha256:"')
s=once(s,'if(persisted.equals(cached.getProperty("revision",""))){',
    'if(persisted.equals(cached.getProperty("revision",""))&&RuntimeCompatibility14.cachedPairValid(cached,target,allowTarget)){')
s=once(s,'saved.setProperty("revision",revision);','saved.setProperty("revision",revision);\n        saved.setProperty("blockSha256",RuntimeCompatibility14.sha256(target));\n        saved.setProperty("allowSha256",RuntimeCompatibility14.sha256(allowTarget));')
save(p,s)

p=ROOT+'RootProxyManager.java';s=read_base(p)
s=once(s,'            prefs.edit()\n                    .putLong("proxyAdblockProviderReloadAt",',
    '            String actualFilterMode14=controller.configs().optString("mode", "unknown");\n            prefs.edit()\n                    .putBoolean("proxyAdblockLastEffective",snapshot.count>0&&AdblockRuleInspection.isRuleMode(actualFilterMode14))\n                    .putString("proxyAdblockActualMode",actualFilterMode14)\n                    .putLong("proxyAdblockProviderReloadAt",')
s=once(s,'            return "已热更新 "+snapshot.count+" 条广告规则";',
    '            return RuntimeCompatibility14.filterReloadMessage(actualFilterMode14,snapshot.count);')
s=once(s,'.append(RootBridge.quote(p.adblock.revision)).append(" ]; then")',
'''.append(RootBridge.quote(p.adblock.revision))
                    .append(" ] || [ \\\"$(sha256sum ").append(RootBridge.quote(dst)).append(" 2>/dev/null | cut -d ' ' -f 1)\\\" != ")
                    .append(RootBridge.quote(RuntimeCompatibility14.sha256(adblock)))
                    .append(" ] || [ \\\"$(sha256sum ").append(RootBridge.quote(allowDst)).append(" 2>/dev/null | cut -d ' ' -f 1)\\\" != ")
                    .append(RootBridge.quote(RuntimeCompatibility14.sha256(adblockAllow))).append(" ]; then")''')
s=once(s,'        return report.toString();',r'''        try {
            ProxyRuntimeProfile profile=ProxyRuntimeProfile.load(prefs);
            ProxyConfigLibrary.Entry source=configs.selected(profile.core);
            if(source!=null) report.section("DNS/TLS/保活配置（源文件，只含安全标量）",RuntimeCompatibility14.safeConfigSummary(configs.read(source)),2500);
            String runtime14="awk 'BEGIN{dns=0} /^[^ #]/{dns=($0 ~ /^dns[ ]*:/)} "
                    +"/^(mode|ipv6|disable-keep-alive|keep-alive-idle|keep-alive-interval):/{print} "
                    +"dns && /^  (enable|ipv6|enhanced-mode|fake-ip-filter-mode|respect-rules|prefer-h3):/{print}' "+RootBridge.quote(CONFIG)+" 2>/dev/null; true";
            report.section("实际运行 DNS/保活标量",RootBridge.rootShell(context,runtime14,4000L).output,2500);
            String privateDns=android.provider.Settings.Global.getString(context.getContentResolver(),"private_dns_mode");
            android.os.PowerManager power=(android.os.PowerManager)context.getSystemService(Context.POWER_SERVICE);
            String device="privateDnsMode="+String.valueOf(privateDns)+"\nappScope="+profile.appScope.id
                    +"\ntcp="+profile.tcp+" udp="+profile.udp+" quicBlocked="+profile.quicBlocked
                    +"\nappBypassOrIncludeCount="+prefs.getStringSet("proxyAppPackages",Collections.emptySet()).size();
            if(power!=null)device+="\ndoZe="+power.isDeviceIdleMode()+" powerSave="+power.isPowerSaveMode()
                    +"\nwechatBatteryExempt="+power.isIgnoringBatteryOptimizations("com.tencent.mm");
            device+="\n以上是排查线索，不等于已定位微信延迟原因。分应用绕过、私人DNS、配置内置分流可使同一YAML实际走不同路径。";
            report.section("系统网络与消息排查线索",device,2500);
            MihomoControllerClient controller=new MihomoControllerClient(context);
            String mode14=controller.configs().optString("mode","unknown");
            JSONObject providers14=controller.ruleProviders().optJSONObject("providers");
            JSONObject block14=providers14==null?null:providers14.optJSONObject(ProxyAdblockRules.PROVIDER_NAME);
            JSONObject allow14=providers14==null?null:providers14.optJSONObject(ProxyAdblockRules.ALLOW_PROVIDER_NAME);
            boolean linked14=false;JSONArray rules14=controller.rules().optJSONArray("rules");
            for(int i=0;rules14!=null&&i<rules14.length();i++){
                JSONObject r=rules14.optJSONObject(i);if(r==null)continue;
                JSONObject extra=r.optJSONObject("extra");
                if(AdblockRuleInspection.isBlockingRule(r.optString("type"),r.optString("payload"),r.optString("proxy"),
                        r.optBoolean("disabled",false)||(extra!=null&&extra.optBoolean("disabled",false))))linked14=true;
            }
            report.section("实际广告过滤链（核心回读）","mode="+mode14+"\nruleLinked="+linked14
                    +"\nblockRules="+(block14==null?-1:block14.optInt("ruleCount",-1))
                    +"\nallowRules="+(allow14==null?-1:allow14.optInt("ruleCount",-1))
                    +"\n本项核对运行模式、规则和provider；不把下载数量当成拦截效果，也不承诺过滤同域广告或HTTPS页面元素。",2500);
        } catch(Exception error) { report.section("网络/过滤补充诊断","未完成读取："+error.getClass().getSimpleName(),1000); }
        return report.toString();''')
save(p,s)

p='android-app/app/build.gradle.kts';s=read_base(p)
s=once(s,'versionCode = 1014','versionCode = 1015');s=once(s,'0.4.0-ui92-r146.13','0.4.0-ui92-r146.14');save(p,s)
# Update only the explicit UX expectations superseded by this latest request.
for name in ['PanelUi11Test.kt','PanelNavigation13Test.kt']:
    p=TEST+name;s=read_base(p)
    s=s.replace('node("panel11-fab").assertDoesNotExist()', 'node("panel11-fab").assertIsDisplayed()')
    s=s.replace('WithoutAccordionsOrFloatingControl','WithoutAccordionsAndWithReservedShortcut')
    if s!=Path(p).read_text():save(p,s)
for name in ['RuntimeCompatibility14Test.kt','PanelPolish14Test.kt']:
    save(TEST+name,Path('tools/ui14/'+name).read_text())
(OUT/'ui14-source-changes.json').write_text(json.dumps(changed,ensure_ascii=False))
contract={p:hashlib.sha256(Path(p).read_bytes()).hexdigest() for p in changed}
(OUT/'ui14-contract.json').write_text(json.dumps(contract,indent=2))
print('UI14 integrated',len(changed),'reviewed files; runtime binaries and network interception script unchanged')
