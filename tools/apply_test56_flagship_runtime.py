from pathlib import Path
import re

ROOT = Path('.')

def read(path): return (ROOT / path).read_text(encoding='utf-8')
def write(path, text): (ROOT / path).write_text(text, encoding='utf-8')
def rep(text, old, new, label):
    if old not in text:
        raise SystemExit(f'missing anchor: {label}')
    return text.replace(old, new, 1)

def sub(text, pattern, repl, label, flags=re.S):
    out, n = re.subn(pattern, repl, text, count=1, flags=flags)
    if n != 1:
        raise SystemExit(f'expected one regex match for {label}, got {n}')
    return out

# 1) Version
p='android-app/app/build.gradle.kts'; s=read(p)
s=rep(s,'versionCode = 455','versionCode = 456','versionCode')
s=rep(s,'versionName = "0.4.0-test.55"','versionName = "0.4.0-test.56"','versionName')
write(p,s)

# 2) Make TUN real and eBPF an explicitly validated experimental mode.
p='android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyRuntimeProfile.java'; s=read(p)
s=rep(s,
'''            case TUN:return new Capability(false,true,true,true,false,false,false,true,true,"Root TUN 正在并入统一运行时；旧 VpnService 仍保留，但这里不再假报为 Root 模式可用");
            case EBPF:return new Capability(false,false,false,false,false,false,false,false,false,"eBPF 必须完成 verifier/attach/map 能力探测并使用兼容核心；后端接通前不开放");''',
'''            case TUN:return new Capability(true,true,true,true,true,false,true,true,true,"");
            case EBPF:return new Capability(true,true,true,true,false,false,false,false,true,"");''','mode capabilities')
write(p,s)

# 3) Generate actual TUN / eBPF runtime config.
p='android-app/app/src/main/java/io/github/xgl34222220/bichen/MihomoStartupConfig.java'; s=read(p)
s=rep(s,
'''    static Result generate(String source,ProxyRuntimeProfile profile,String controllerSecret,int controllerPort)throws IOException{
        if(source==null||source.trim().isEmpty())throw new IOException("源配置为空");''',
'''    static Result generate(String source,ProxyRuntimeProfile profile,String controllerSecret,int controllerPort)throws IOException{
        return generate(source,profile,controllerSecret,controllerPort,ProxyRuntimeProfile.AppScope.CORE,Collections.emptySet(),"");
    }

    static Result generate(String source,ProxyRuntimeProfile profile,String controllerSecret,int controllerPort,
            ProxyRuntimeProfile.AppScope appScope,Set<String> appPackages,String ebpfInterface)throws IOException{
        if(source==null||source.trim().isEmpty())throw new IOException("源配置为空");''','generate overload')
s=rep(s,'        yaml=removeTopLevelBlock(yaml,"tun");\n        yaml=removeTopLevelScalar(yaml,"routing-mark");',
          '        yaml=removeTopLevelBlock(yaml,"tun");\n        yaml=removeTopLevelBlock(yaml,"ebpf");\n        yaml=removeTopLevelScalar(yaml,"routing-mark");','remove ebpf source block')
s=rep(s,
'''            case TUN:
            case MIXED:
                throw new IOException(profile.mode.label+" 的统一 Root 事务后端尚未接入，当前不假报支持");
            case EBPF:
                throw new IOException("eBPF 必须使用兼容核心和经过能力探测的 eBPF 入站，不能用普通 Mihomo 冒充");''',
'''            case TUN:
                override.append("tproxy-port: 0\\nredir-port: 0\\n");
                appendTun(override,profile,appScope,appPackages,true,true);
                break;
            case EBPF:
                if(ebpfInterface==null||ebpfInterface.isEmpty())throw new IOException("eBPF 未找到可用默认出口接口");
                override.append("tproxy-port: 0\\nredir-port: 0\\n");
                appendTun(override,profile,ProxyRuntimeProfile.AppScope.CORE,Collections.emptySet(),false,false);
                override.append("ebpf:\\n  redirect-to-tun:\\n    - '").append(yamlQuote(ebpfInterface)).append("'\\n");
                break;
            case MIXED:
                throw new IOException(profile.mode.label+" 的统一 Root 事务后端尚未接入，当前不开放");''','tun ebpf switch')
anchor='''    /**
     * Runtime-only compatibility for public DNS providers that retired raw-IP DoH access.'''
helper='''    private static void appendTun(StringBuilder out,ProxyRuntimeProfile profile,ProxyRuntimeProfile.AppScope scope,
            Set<String> packages,boolean autoRoute,boolean packageFilter){
        out.append("tun:\\n");
        out.append("  enable: true\\n");
        out.append("  device: bichen0\\n");
        out.append("  stack: mixed\\n");
        out.append("  auto-route: ").append(autoRoute?"true":"false").append('\\n');
        out.append("  auto-redirect: false\\n");
        out.append("  auto-detect-interface: ").append(autoRoute?"true":"false").append('\\n');
        out.append("  strict-route: ").append(autoRoute?"true":"false").append('\\n');
        out.append("  exclude-uid-range:\\n    - '0:9999'\\n");
        out.append("  route-exclude-address:\\n");
        out.append("    - 10.0.0.0/8\\n    - 100.64.0.0/10\\n    - 127.0.0.0/8\\n    - 169.254.0.0/16\\n    - 172.16.0.0/12\\n    - 192.168.0.0/16\\n    - fc00::/7\\n    - fe80::/10\\n");
        if(profile.dnsHijack!=ProxyRuntimeProfile.DnsHijack.OFF){
            out.append("  dns-hijack:\\n    - any:53\\n    - tcp://any:53\\n");
        }
        if(packageFilter&&packages!=null&&!packages.isEmpty()){
            if(scope==ProxyRuntimeProfile.AppScope.WHITELIST)out.append("  include-package:\\n");
            else if(scope==ProxyRuntimeProfile.AppScope.BLACKLIST)out.append("  exclude-package:\\n");
            else return;
            for(String name:new TreeSet<>(packages))out.append("    - '").append(yamlQuote(name)).append("'\\n");
        }
    }

    private static String yamlQuote(String value){return value==null?"":value.replace("'","''");}

'''+anchor
s=rep(s,anchor,helper,'tun helper')
write(p,s)

# 4) Root manager: allow modes, pass package filtering to TUN, detect eBPF interface.
p='android-app/app/src/main/java/io/github/xgl34222220/bichen/RootProxyManager.java'; s=read(p)
s=rep(s,
'''        if(profile.mode!=ProxyRuntimeProfile.Mode.TPROXY&&profile.mode!=ProxyRuntimeProfile.Mode.REDIRECT&&profile.mode!=ProxyRuntimeProfile.Mode.ENHANCE)
            throw new IOException(profile.mode.label+" 的统一 Root 后端还未接入");''',
'''        if(profile.mode!=ProxyRuntimeProfile.Mode.TPROXY&&profile.mode!=ProxyRuntimeProfile.Mode.REDIRECT&&profile.mode!=ProxyRuntimeProfile.Mode.ENHANCE&&
                profile.mode!=ProxyRuntimeProfile.Mode.TUN&&profile.mode!=ProxyRuntimeProfile.Mode.EBPF)
            throw new IOException(profile.mode.label+" 的统一 Root 后端还未接入");''','manager mode gate')
insert='''    private Set<String> selectedTunPackages(){
        Set<String> raw=prefs.getStringSet("proxyAppPackages",Collections.emptySet());
        TreeSet<String> out=new TreeSet<>();
        if(raw!=null)for(String value:raw){
            if(value!=null&&value.length()<=255&&value.matches("[A-Za-z_][A-Za-z0-9_]*(\\\\.[A-Za-z_][A-Za-z0-9_]*)*"))out.add(value);
        }
        return out;
    }

    private String detectDefaultInterface()throws IOException{
        RootBridge.Result result=RootBridge.rootShell(context,
                "ip route get 1.1.1.1 2>/dev/null | sed -n 's/.* dev \\([^ ]*\\).*/\\1/p' | head -n 1",5000L);
        String iface=result.output==null?"":result.output.trim();
        if(!iface.matches("[A-Za-z0-9_.:@-]{1,32}"))throw new IOException("eBPF 无法识别当前默认出口接口");
        return iface;
    }

'''
s=rep(s,'    Prepared prepare(ProxyRuntimeProfile profile)throws Exception{',insert+'    Prepared prepare(ProxyRuntimeProfile profile)throws Exception{','manager helpers')
s=rep(s,
'''        int controllerPort=chooseControllerPort();
        MihomoStartupConfig.Result generated=MihomoStartupConfig.generate(source,profile,controllerSecret(),controllerPort);''',
'''        int controllerPort=chooseControllerPort();
        String ebpfInterface=profile.mode==ProxyRuntimeProfile.Mode.EBPF?detectDefaultInterface():"";
        Set<String> tunPackages=profile.mode==ProxyRuntimeProfile.Mode.TUN?selectedTunPackages():Collections.emptySet();
        MihomoStartupConfig.Result generated=MihomoStartupConfig.generate(source,profile,controllerSecret(),controllerPort,profile.appScope,tunPackages,ebpfInterface);''','manager generate args')
s=s.replace('stage(progress,"检查 TPROXY / Redirect / UID / IPv6 能力…");','stage(progress,"检查 TUN / TPROXY / eBPF / UID / IPv6 能力…");')
write(p,s)

# 5) Root shell: actual root TUN path + experimental eBPF capability checks.
p='android-app/app/src/main/assets/proxy-root-v3.sh'; s=read(p)
s=rep(s,'mode(){ case "${1:-}" in tproxy|redirect|enhance) return 0;; *) return 1;; esac; }',
          'mode(){ case "${1:-}" in tproxy|redirect|enhance|tun|ebpf) return 0;; *) return 1;; esac; }','root modes')
s=rep(s,'cleanup(){ MARK=""; MASK=""; TABLE=""; PREF=""; loadnet >/dev/null 2>&1 || true; cleanup4; cleanup6; cleanlegacy; rm -f "$NET_STATE"; MARK=""; MASK=""; TABLE=""; PREF=""; }',
          'cleanup(){ MARK=""; MASK=""; TABLE=""; PREF=""; loadnet >/dev/null 2>&1 || true; cleanup4; cleanup6; cleanlegacy; ip link del bichen0 >/dev/null 2>&1 || true; rm -f "$NET_STATE"; MARK=""; MASK=""; TABLE=""; PREF=""; }','tun cleanup')
s=rep(s,
'''  probecidrs "$CIDRS" || fail "CIDR 绕过列表包含当前系统不支持的地址"

  NEED_TP=0; NEED_RP=0''',
'''  probecidrs "$CIDRS" || fail "CIDR 绕过列表包含当前系统不支持的地址"
  if [ "$M" = tun ] || [ "$M" = ebpf ]; then
    { [ -c /dev/tun ] || [ -c /dev/net/tun ]; } || fail "当前设备没有可用 TUN 字符设备"
  fi
  if [ "$M" = ebpf ]; then
    [ -d /sys/fs/bpf ] || fail "eBPF 需要已挂载的 /sys/fs/bpf"
    grep -qw bpf /proc/filesystems 2>/dev/null || fail "当前内核未启用 BPF 文件系统支持"
  fi

  NEED_TP=0; NEED_RP=0''','tun preflight')
s=rep(s,
'''  if [ "$NEED_TP" = 0 ] && [ "$NEED_RP" = 0 ] && [ "$DNS" = off ]; then fail "TCP、UDP 与 DNS 接管均已关闭，代理没有可接管流量"; fi''',
'''  if [ "$M" != tun ] && [ "$M" != ebpf ] && [ "$NEED_TP" = 0 ] && [ "$NEED_RP" = 0 ] && [ "$DNS" = off ]; then fail "TCP、UDP 与 DNS 接管均已关闭，代理没有可接管流量"; fi''','tun no-traffic exemption')
s=rep(s,
'''  case "$M" in tproxy) [ "$TCP" = 0 ] || tcp_listen "$TP" || return 1; [ "$UDP" = 0 ] || udp_listen "$TP" || return 1;; redirect) [ "$TCP" = 0 ] || tcp_listen "$RP" || return 1;; enhance) [ "$TCP" = 0 ] || tcp_listen "$RP" || return 1; [ "$UDP" = 0 ] || udp_listen "$TP" || return 1;; esac''',
'''  case "$M" in tproxy) [ "$TCP" = 0 ] || tcp_listen "$TP" || return 1; [ "$UDP" = 0 ] || udp_listen "$TP" || return 1;; redirect) [ "$TCP" = 0 ] || tcp_listen "$RP" || return 1;; enhance) [ "$TCP" = 0 ] || tcp_listen "$RP" || return 1; [ "$UDP" = 0 ] || udp_listen "$TP" || return 1;; tun|ebpf) ip link show bichen0 >/dev/null 2>&1 || return 1;; esac''','tun ready')
s=rep(s,'[ "$START_DNS" = tproxy ] && NEED_TP=1',
          'if [ "$START_DNS" = tproxy ] && [ "$START_MODE" != tun ] && [ "$START_MODE" != ebpf ]; then NEED_TP=1; fi','tun allocnet')
s=rep(s,
'''  [ "$START_DNS" = off ] || install_dns_redirect4 "$START_DP" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_IFACES" || { cleanup; stopcore; restorev6; rm -f "$SESSION"; fail "IPv4 DNS 劫持安装失败，已回滚"; }''',
'''  if [ "$START_MODE" != tun ] && [ "$START_MODE" != ebpf ] && [ "$START_DNS" != off ]; then install_dns_redirect4 "$START_DP" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_IFACES" || { cleanup; stopcore; restorev6; rm -f "$SESSION"; fail "IPv4 DNS 劫持安装失败，已回滚"; }; fi''','skip tun external dns4')
s=rep(s,
'''    [ "$START_DNS" = off ] || install_dns_redirect6 "$START_DP" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_IFACES" || { cleanup; stopcore; restorev6; rm -f "$SESSION"; fail "IPv6 DNS 劫持安装失败，已回滚"; }''',
'''    if [ "$START_MODE" != tun ] && [ "$START_MODE" != ebpf ] && [ "$START_DNS" != off ]; then install_dns_redirect6 "$START_DP" "$START_SCOPE" "$START_UIDS" "$START_SHARE" "$START_IFACES" || { cleanup; stopcore; restorev6; rm -f "$SESSION"; fail "IPv6 DNS 劫持安装失败，已回滚"; }; fi''','skip tun external dns6')
s=rep(s,
'''  if [ "$READY_RC" -ne 0 ]; then
    if [ "$READY_RC" -eq 2 ]; then READY_MSG="Mihomo 启动后提前退出，请查看核心日志"; else READY_MSG="Mihomo 初始化超过 90 秒，透明代理/DNS/API 监听仍未就绪；首次加载大量远程订阅或规则时请检查网络与核心日志"; fi''',
'''  if [ "$READY_RC" -ne 0 ]; then
    if [ "$READY_RC" -eq 2 ]; then READY_MSG="Mihomo 启动后提前退出，请查看核心日志"; else READY_MSG="Mihomo 初始化超过 90 秒，代理入站/DNS/API 监听仍未就绪；首次加载大量远程订阅或规则时请检查网络与核心日志"; fi''','ready wording')
s=rep(s,
'''  start_stage "install-ipv4-tproxy"''',
'''  if [ "$START_MODE" = ebpf ]; then
    sleep 0.25
    if grep -Ei '(^|[^a-z])(e?bpf|bpf)([^a-z]|$)' "$LOG" 2>/dev/null | tail -n 20 | grep -Eqi 'error|failed|failure|not supported|operation not permitted|permission denied|attach.*fail'; then
      stopcore; cleanup; restorev6; rm -f "$SESSION"; fail "eBPF attach 失败；当前内核/接口不兼容，请改用 TUN 或 TPROXY"
    fi
  fi

  start_stage "install-ipv4-tproxy"''','ebpf attach validation')
write(p,s)

# 6) Adblock page: initial local rules must render independently of Root status; add protection profiles.
p='android-app/app/src/main/java/io/github/xgl34222220/bichen/ui/BichenComposeController.kt'; s=read(p)
s=rep(s,
'''    suspend fun setRuleSource(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val rules = RuleStore(app)
        rules.reload()
        rules.setSource(id, enabled, moduleInstalled())
    }
''',
'''    suspend fun setRuleSource(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val rules = RuleStore(app)
        rules.reload()
        rules.setSource(id, enabled, moduleInstalled())
    }

    suspend fun setRuleProfile(id: String) = withContext(Dispatchers.IO) {
        val rules = RuleStore(app)
        rules.reload()
        rules.setProfile(id, moduleInstalled())
    }
''','adblock profile controller')
write(p,s)

p='android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyAdblockChainActivity.kt'; s=read(p)
s=rep(s,
'''    val snapshot by produceState(initialValue = ChainSnapshot(), revision) {
        value = try {
            val rules = adController.rulesSnapshot()
            val state = proxyController.state()
            val independent = adController.homeSnapshot()
            val hits = if (state.running) {
                runCatching {
                    proxyController.rules().firstOrNull {
                        it.proxy.equals("REJECT", true) && it.payload.contains(ProxyAdblockRules.PROVIDER_NAME, true)
                    }?.hitCount ?: 0L
                }.getOrDefault(0L)
            } else 0L
            ChainSnapshot(rules, state.running, hits, independent.vpnRunning, independent.moduleEnabled, state.message)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            ChainSnapshot(message = error.message ?: "读取广告过滤状态失败")
        }
    }
''',
'''    val cachedRules = remember { RulesSnapshot(count = prefs.getInt("proxyAdblockUiRuleCount", 0), profile = prefs.getString("proxyAdblockUiProfile", "加载中") ?: "加载中") }
    val snapshot by produceState(initialValue = ChainSnapshot(rules = cachedRules), revision) {
        val rulesResult = runCatching { adController.rulesSnapshot() }
        val rules = rulesResult.getOrDefault(cachedRules)
        if (rules.count > 0 || rules.sources.isNotEmpty()) {
            prefs.edit().putInt("proxyAdblockUiRuleCount", rules.count).putString("proxyAdblockUiProfile", rules.profile).apply()
        }
        val state = runCatching { proxyController.state() }.getOrNull()
        val independent = runCatching { adController.homeSnapshot() }.getOrNull()
        val hits = if (state?.running == true) runCatching {
            proxyController.rules().firstOrNull {
                it.proxy.equals("REJECT", true) && it.payload.contains(ProxyAdblockRules.PROVIDER_NAME, true)
            }?.hitCount ?: 0L
        }.getOrDefault(0L) else 0L
        value = ChainSnapshot(
            rules = rules,
            running = state?.running == true,
            hitCount = hits,
            vpnFallbackRunning = independent?.vpnRunning == true,
            hostsFallbackRunning = independent?.moduleEnabled == true,
            message = listOfNotNull(state?.message?.takeIf { it.isNotBlank() }, rulesResult.exceptionOrNull()?.message).joinToString("；"),
        )
    }
''','adblock independent initial load')
s=rep(s,'Text(if (chainEnabled) "广告规则会插在代理分流规则之前" else "代理仅负责转发，不执行辟尘广告规则", color = t.textSecondary, fontSize = 11.sp)',
          'Text(if (chainEnabled) "尊重用户显式规则；广告规则只在最终兜底前拦截" else "代理仅负责转发，不执行辟尘广告规则", color = t.textSecondary, fontSize = 11.sp)','adblock wording master')
s=rep(s,'Text("应用流量 → Root TPROXY / Redirect → 广告 RULE-SET → REJECT → CNIP / 用户规则 / 策略组 → 节点或 DIRECT", color = t.textSecondary, fontSize = 11.sp, lineHeight = 17.sp)',
          'Text("应用流量 → TUN / TPROXY / eBPF → 用户显式规则 → 广告 RULE-SET → 最终兜底 → 节点或 DIRECT", color = t.textSecondary, fontSize = 11.sp, lineHeight = 17.sp)','adblock flow wording')
profile_item='''
        item("profile") {
            Surface(shape = RoundedCornerShape(20.dp), color = if (dark) t.elevatedCardBackground else Color.White, shadowElevation = if (dark) 0.dp else 1.dp) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Tune, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("保护强度", color = t.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(snapshot.rules.profile, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf("lite" to "轻量", "balanced" to "均衡", "enhanced" to "加强").forEach { (id, label) ->
                            FilterChip(
                                selected = snapshot.rules.profile == label,
                                onClick = {
                                    if (!busy) scope.launch {
                                        busy = true
                                        runCatching { adController.setRuleProfile(id) }
                                            .onSuccess { notice = "已切换到${label}保护；Root 代理运行中请重启以载入新快照"; revision++ }
                                            .onFailure { notice = it.message ?: "保护强度切换失败" }
                                        busy = false
                                    }
                                },
                                label = { Text(label) },
                                modifier = Modifier.weight(1f),
                                enabled = !busy,
                            )
                        }
                    }
                    Text("轻量优先兼容；均衡适合日常；加强会启用更激进的 Hagezi 规则源。用户黑白名单始终保留。", color = t.textSecondary, fontSize = 10.sp, lineHeight = 15.sp)
                }
            }
        }

'''
s=rep(s,'        item("flow") {',profile_item+'        item("flow") {','adblock profile UI')
write(p,s)

# 7) Cold-start snapshot, panel no-remount, duplicate cleanup, UI micro polish.
p='android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt'; s=read(p)
s=rep(s,'private enum class RefProxyPage { Home, Panel, Tools, Settings }',
'''private enum class RefProxyPage { Home, Panel, Tools, Settings }
private data class RefSubscriptionCache(val used: Long = 0L, val total: Long = 0L, val count: Int = 0)''','subscription cache class')
s=rep(s,
'''    var runtime by remember { mutableStateOf(ProxyRuntimeSnapshot()) }
    var providers by remember { mutableStateOf<List<DashboardProviderUi>>(emptyList()) }
    var siteDelays by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    val delays = remember { mutableStateMapOf<String, Long>() }
    var cpuPercent by remember { mutableFloatStateOf(0f) }
    var lastProcessTicks by remember { mutableLongStateOf(0L) }
    var lastSystemTicks by remember { mutableLongStateOf(0L) }
    var upRate by remember { mutableLongStateOf(0L) }
    var downRate by remember { mutableLongStateOf(0L) }''',
'''    var runtime by remember { mutableStateOf(ProxyRuntimeSnapshot(
        running = prefs.getBoolean("proxyUiLastRunning", false),
        elapsedSeconds = prefs.getLong("proxyUiLastElapsed", 0L),
        rssBytes = prefs.getLong("proxyUiLastRss", 0L),
        lanAddress = prefs.getString("proxyUiLastLan", "—") ?: "—",
        lanInterface = prefs.getString("proxyUiLastLanIf", "—") ?: "—",
        wanAddress = prefs.getString("proxyUiLastWan", "—") ?: "—",
        wanCountryCode = prefs.getString("proxyUiLastWanCountry", "") ?: "",
        wanRegion = prefs.getString("proxyUiLastWanRegion", "—") ?: "—",
    )) }
    var providers by remember { mutableStateOf<List<DashboardProviderUi>>(emptyList()) }
    var cachedSubscription by remember { mutableStateOf(RefSubscriptionCache(
        used = prefs.getLong("proxyUiLastSubUsed", 0L),
        total = prefs.getLong("proxyUiLastSubTotal", 0L),
        count = prefs.getInt("proxyUiLastSubCount", 0),
    )) }
    var siteDelays by remember { mutableStateOf(mapOf(
        "Baidu" to prefs.getLong("proxyUiLastDelayBaidu", -2L),
        "Cloudflare" to prefs.getLong("proxyUiLastDelayCloudflare", -2L),
        "Google" to prefs.getLong("proxyUiLastDelayGoogle", -2L),
    ).filterValues { it != -2L }) }
    val delays = remember { mutableStateMapOf<String, Long>() }
    var cpuPercent by remember { mutableFloatStateOf(prefs.getFloat("proxyUiLastCpu", 0f)) }
    var lastProcessTicks by remember { mutableLongStateOf(0L) }
    var lastSystemTicks by remember { mutableLongStateOf(0L) }
    var upRate by remember { mutableLongStateOf(prefs.getLong("proxyUiLastUpRate", 0L)) }
    var downRate by remember { mutableLongStateOf(prefs.getLong("proxyUiLastDownRate", 0L)) }''','cold snapshot vars')
s=rep(s,
'''            runtime = sampled
            providers = if (next.running) runCatching { repo.providers() }.getOrDefault(providers) else emptyList()
            state = next
            prefs.edit()
                .putBoolean("proxyUiLastRunning", next.running)
                .putString("proxyUiLastCore", next.core)
                .putString("proxyUiLastMode", next.mode)
                .putString("proxyUiLastConfig", next.config)
                .apply()''',
'''            runtime = sampled
            providers = if (next.running) runCatching { repo.providers() }.getOrDefault(providers) else emptyList()
            if (providers.isNotEmpty()) {
                val tracked = providers.filter { it.hasSubscriptionInfo && it.total > 0L }
                cachedSubscription = RefSubscriptionCache(tracked.sumOf { it.used }, tracked.sumOf { it.total }, providers.size)
            }
            state = next
            prefs.edit()
                .putBoolean("proxyUiLastRunning", next.running)
                .putString("proxyUiLastCore", next.core)
                .putString("proxyUiLastMode", next.mode)
                .putString("proxyUiLastConfig", next.config)
                .putLong("proxyUiLastElapsed", sampled.elapsedSeconds)
                .putLong("proxyUiLastRss", sampled.rssBytes)
                .putString("proxyUiLastLan", sampled.lanAddress)
                .putString("proxyUiLastLanIf", sampled.lanInterface)
                .putString("proxyUiLastWan", sampled.wanAddress)
                .putString("proxyUiLastWanCountry", sampled.wanCountryCode)
                .putString("proxyUiLastWanRegion", sampled.wanRegion)
                .putFloat("proxyUiLastCpu", cpuPercent)
                .putLong("proxyUiLastUpRate", upRate)
                .putLong("proxyUiLastDownRate", downRate)
                .putLong("proxyUiLastSubUsed", cachedSubscription.used)
                .putLong("proxyUiLastSubTotal", cachedSubscription.total)
                .putInt("proxyUiLastSubCount", cachedSubscription.count)
                .apply()''','persist cold snapshot')
s=rep(s,'            if (measured.isNotEmpty()) siteDelays = measured',
'''            if (measured.isNotEmpty()) {
                siteDelays = measured
                prefs.edit()
                    .putLong("proxyUiLastDelayBaidu", measured["Baidu"] ?: -2L)
                    .putLong("proxyUiLastDelayCloudflare", measured["Cloudflare"] ?: -2L)
                    .putLong("proxyUiLastDelayGoogle", measured["Google"] ?: -2L)
                    .apply()
            }''','persist home delays')
# pass cached subscription into Home
s=rep(s,'                    providers = providers,\n                    siteDelays = siteDelays,',
          '                    providers = providers,\n                    cachedSubscription = cachedSubscription,\n                    siteDelays = siteDelays,','home call cache')
s=rep(s,'    providers: List<DashboardProviderUi>,\n    siteDelays: Map<String, Long>,',
          '    providers: List<DashboardProviderUi>,\n    cachedSubscription: RefSubscriptionCache,\n    siteDelays: Map<String, Long>,','home signature cache')
s=rep(s,'                RefSubscriptionCompact(providers, Modifier.weight(1f), onSubscription)',
          '                RefSubscriptionCompact(providers, cachedSubscription, Modifier.weight(1f), onSubscription)','home subscription call')
# replace LAN/WAN hard mid-flip with visible slide-fade
pattern=r'@Composable\nprivate fun RefNetworkIdentityCard\(runtime: ProxyRuntimeSnapshot, connections: Int, modifier: Modifier\) \{.*?\n\}\n\n@Composable\nprivate fun RefSpeedCard'
new_func='''@Composable
private fun RefNetworkIdentityCard(runtime: ProxyRuntimeSnapshot, connections: Int, modifier: Modifier) {
    val t = LocalBichenTokens.current
    val view = LocalView.current
    var showLan by rememberSaveable { mutableStateOf(true) }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .74f, stiffness = 560f), label = "networkCardPress")
    val shape = RoundedCornerShape(20.dp)
    val valueColor = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A)
    Surface(
        modifier = modifier.height(112.dp).graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .94f else 1f }
            .clip(shape).clickable(interactionSource = source, indication = null) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); showLan = !showLan
            },
        shape = shape, color = t.cardBackground, shadowElevation = 1.dp,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (showLan) "LAN" else "WAN", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(Modifier.size(24.dp).background(Color(0xFFF1F5F9), CircleShape).border(.6.dp, Color.White.copy(alpha = .90f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.SwapHoriz, "切换 LAN/WAN", tint = Color(0xFF2563EB), modifier = Modifier.size(14.dp))
                }
            }
            androidx.compose.animation.AnimatedContent(
                targetState = showLan,
                transitionSpec = {
                    (androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(220)) { 4.dp.roundToPx() } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) + androidx.compose.animation.scaleIn(initialScale = .98f))
                        .togetherWith(androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(160)) { -4.dp.roundToPx() } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)))
                },
                label = "lanWanMetricSwap",
            ) { lan ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (lan) runtime.lanAddress else runtime.wanAddress, color = valueColor, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(20.dp))
                    Text(if (lan) "${runtime.lanInterface} · $connections 连接" else "${countryEmoji(runtime.wanCountryCode)} ${runtime.wanRegion}", color = Color(0xFF64748B), fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(18.dp))
                }
            }
        }
    }
}

@Composable
private fun RefSpeedCard'''
s=sub(s,pattern,new_func,'LAN/WAN animated content')
# subscription fallback
s=rep(s,'private fun RefSubscriptionCompact(items: List<DashboardProviderUi>, modifier: Modifier, onClick: () -> Unit) {',
          'private fun RefSubscriptionCompact(items: List<DashboardProviderUi>, cached: RefSubscriptionCache, modifier: Modifier, onClick: () -> Unit) {','subscription cache signature')
s=rep(s,
'''    val used = tracked.sumOf { it.used }
    val total = tracked.sumOf { it.total }
    val ratio = if (total <= 0L) 0f else (used.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)''',
'''    val liveUsed = tracked.sumOf { it.used }
    val liveTotal = tracked.sumOf { it.total }
    val used = if (liveTotal > 0L) liveUsed else cached.used
    val total = if (liveTotal > 0L) liveTotal else cached.total
    val itemCount = if (items.isNotEmpty()) items.size else cached.count
    val ratio = if (total <= 0L) 0f else (used.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)''','subscription cached values')
s=s.replace('"${items.size} 个订阅"','"$itemCount 个订阅"',1)
# hero running check breath
s=rep(s,
'''                        Box(
                            Modifier
                                .size(56.dp)
                                .shadow(
                                    if (state.running) 16.dp else 0.dp,''',
'''                        val checkPulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "heroCheckGlow")
                        val checkGlow by checkPulse.animateFloat(initialValue = 0f, targetValue = 1f, animationSpec = androidx.compose.animation.core.infiniteRepeatable(animation = androidx.compose.animation.core.tween(1500, easing = androidx.compose.animation.core.FastOutSlowInEasing), repeatMode = androidx.compose.animation.core.RepeatMode.Reverse), label = "heroCheckGlowValue")
                        Box(
                            Modifier
                                .size(56.dp)
                                .graphicsLayer { if (state.running) { scaleX = .99f + checkGlow * .018f; scaleY = .99f + checkGlow * .018f } }
                                .shadow(
                                    if (state.running) (13f + checkGlow * 10f).dp else 0.dp,''','hero check pulse')
# panel: stop destroying and fading the entire page on every tab
s=rep(s,'    val tabFade = remember { Animatable(1f) }\n','', 'remove tabFade state')
s=sub(s,r'    LaunchedEffect\(tab\) \{\n        tabFade\.snapTo\(0f\).*?\n    \}\n    LaunchedEffect\(searchRequest\)',
      '    LaunchedEffect(searchRequest)', 'remove tab global fade')
s=rep(s,'    Box(Modifier.fillMaxSize()) {\n        key(tab) {\n            PullToRefreshBox(',
          '    Box(Modifier.fillMaxSize()) {\n            PullToRefreshBox(','remove tab key')
s=rep(s,'                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = tabFade.value },',
          '                modifier = Modifier.fillMaxSize(),','remove tab alpha')
# remove the extra key(tab) closing brace: target just before floating capsule visibility
s=rep(s,'            }\n        }\n        }\n        androidx.compose.animation.AnimatedVisibility(\n            visible = capsuleText.isNotBlank(),',
          '            }\n        }\n        androidx.compose.animation.AnimatedVisibility(\n            visible = capsuleText.isNotBlank(),','remove tab key close')
# rule set success local feedback
s=rep(s,'    val ruleSetRefreshing = remember { mutableStateMapOf<String, Boolean>() }',
          '    val ruleSetRefreshing = remember { mutableStateMapOf<String, Boolean>() }\n    val ruleSetSucceeded = remember { mutableStateMapOf<String, Boolean>() }','ruleset success state')
s=rep(s,'                    RefRuleSetRow(item, refreshing = ruleSetRefreshing[item.name] == true) {',
          '                    RefRuleSetRow(item, refreshing = ruleSetRefreshing[item.name] == true, success = ruleSetSucceeded[item.name] == true) {','ruleset success param')
s=rep(s,
'''                                capsuleText = "规则集更新完成"
                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                delay(70)
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)''',
'''                                capsuleText = "规则集更新完成"
                                ruleSetSucceeded[item.name] = true
                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                delay(70)
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                delay(1200)
                                ruleSetSucceeded.remove(item.name)''','ruleset success duration')
s=rep(s,'private fun RefRuleSetRow(item: DashboardRuleSetUi, refreshing: Boolean, onRefresh: () -> Unit) {',
          'private fun RefRuleSetRow(item: DashboardRuleSetUi, refreshing: Boolean, success: Boolean, onRefresh: () -> Unit) {','ruleset function signature')
# swap rule-set icon expression by broad known icon pattern once inside function
idx=s.find('private fun RefRuleSetRow(')
if idx<0: raise SystemExit('missing RefRuleSetRow')
segment=s[idx:idx+6000]
segment2=segment.replace('Icon(Icons.Rounded.Download, "更新规则集",', 'Icon(if (success) Icons.Rounded.CheckCircle else Icons.Rounded.Download, if (success) "更新完成" else "更新规则集",',1)
segment2=segment2.replace('tint = scheme.primary,', 'tint = if (success) Color(0xFF10B981) else scheme.primary,',1)
if segment2==segment: raise SystemExit('missing rule set icon anchors')
s=s[:idx]+segment2+s[idx+len(segment):]
# overview protocol: 4dp, blue + warm orange
s=s.replace('.height(5.dp).background(Color(0xFFE2E8F0), CircleShape)', '.height(4.dp).background(Color(0xFFE2E8F0), CircleShape)',1)
s=s.replace('background(Color(0xFF10B981)))\n                            }\n                        }\n                    }\n                }\n            }\n            Surface(', 'background(Color(0xFFF59E0B)))\n                            }\n                        }\n                    }\n                }\n            }\n            Surface(',1)
# tools/settings de-duplicate: Tools owns operational destinations; Settings only preferences.
s=rep(s,
'''                RefValueRow("当前配置", state.config, Icons.Rounded.Description, Color(0xFF8B5CF6), highlightValue = true) {
                    context.startActivity(Intent(context, ProxySubscriptionActivity::class.java))
                }
                RefDivider()
                RefValueRow("高级代理配置",''',
'''                RefValueRow("高级代理配置",''','remove settings duplicate subscription')
s=rep(s,
'''                RefDivider()
                RefValueRow("WebUI 面板", "本地", Icons.Rounded.Language, Color(0xFF2563EB), highlightValue = true) {
                    context.startActivity(Intent(context, ProxyWebUiActivity::class.java))
                }
            }
        }
        item { RefSectionLabel("管理") }
        item {
            RefGroup {
                RefValueRow("文件管理", "代理运行目录", Icons.Rounded.Folder, Color(0xFFF59E0B)) {
                    context.startActivity(Intent(context, ReferenceFileManagerActivity::class.java))
                }
                RefDivider()
                RefValueRow("订阅与配置", "链接 · 更新", Icons.Rounded.CloudDownload, Color(0xFF2563EB)) {
                    context.startActivity(Intent(context, ProxySubscriptionActivity::class.java))
                }
            }
        }''',
'''            }
        }''','remove settings duplicate operational entries')
write(p,s)

# 8) Shimmer app skeleton + spring checkbox feedback.
p='android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyAppSelectionActivity.kt'; s=read(p)
s=rep(s,'import androidx.compose.ui.graphics.Color\n', 'import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.Brush\nimport androidx.compose.ui.graphics.graphicsLayer\n','app shimmer imports')
s=rep(s,'    val pageBg = if (dark) t.pageBackground else Color(0xFFF1F5F9)\n',
'''    val pageBg = if (dark) t.pageBackground else Color(0xFFF1F5F9)
    val shimmer = androidx.compose.animation.core.rememberInfiniteTransition(label = "appSkeletonShimmer")
    val shimmerX by shimmer.animateFloat(initialValue = -1f, targetValue = 2f, animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1500, easing = androidx.compose.animation.core.LinearEasing)), label = "appSkeletonShimmerX")
    val shimmerBrush = Brush.horizontalGradient(listOf(t.controlBackground.copy(alpha = .46f), if (dark) Color.White.copy(alpha = .11f) else Color.White.copy(alpha = .92f), t.controlBackground.copy(alpha = .46f)), startX = shimmerX * 360f, endX = (shimmerX + 1f) * 360f)
''','shimmer state')
s=s.replace('background(t.controlBackground.copy(alpha = .72f), RoundedCornerShape(12.dp))','background(shimmerBrush, RoundedCornerShape(12.dp))')
s=s.replace('background(t.controlBackground.copy(alpha = .76f), CircleShape)','background(shimmerBrush, CircleShape)')
s=s.replace('background(t.controlBackground.copy(alpha = .50f), CircleShape)','background(shimmerBrush, CircleShape)')
s=s.replace('background(t.controlBackground.copy(alpha = .62f), CircleShape)','background(shimmerBrush, CircleShape)')
s=rep(s,
'''                    Checkbox(checked = checked, onCheckedChange = { value ->
                        setProxyApp(app.packageName, value)
                        selected = proxyApps()
                    })''',
'''                    val checkScale by animateFloatAsState(if (checked) 1f else .78f, spring(dampingRatio = .50f, stiffness = 620f), label = "appCheck${app.packageName}")
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { value -> setProxyApp(app.packageName, value); selected = proxyApps() },
                        modifier = Modifier.graphicsLayer { scaleX = checkScale; scaleY = checkScale },
                    )''','checkbox pop')
write(p,s)

# 9) YAML gutter divider.
p='android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxySubscriptionActivity.kt'; s=read(p)
s=rep(s,
'''                            }
                            BasicTextField(
                                state = editorState,''',
'''                            }
                            Box(Modifier.width(1.dp).fillMaxHeight().background(if (dark) Color.White.copy(alpha = .06f) else Color(0xFFE2E8F0)))
                            BasicTextField(
                                state = editorState,''','yaml gutter divider')
write(p,s)

# 10) Remove heavyweight close button from generic diagnostic sheet as well.
p='android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyAdvancedSettingsActivity.kt'; s=read(p)
s=rep(s,
'''            FilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(15.dp)) { Text("关闭") }''',
'''            Text("下滑即可关闭", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.CenterHorizontally))''','generic sheet close button')
write(p,s)

print('test56 patch applied')
