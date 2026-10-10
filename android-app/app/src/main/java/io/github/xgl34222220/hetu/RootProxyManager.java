package io.github.xgl34222220.hetu;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.security.MessageDigest;

/** Root transparent-proxy control plane plus private localhost Clash API bootstrap. */
final class RootProxyManager {
    private static final String ROOT="/data/adb/hetu";
    private static final String BIN=ROOT+"/bin/core";
    private static final String CONFIG=ROOT+"/run/state/startup-config";
    private static final String CORE_TOKEN=ROOT+"/run/state/core.token";
    /** Which CLI bin/core speaks (hetu-root.sh core_kind); absent = Mihomo. */
    private static final String CORE_KIND=ROOT+"/run/state/core.kind";
    /** Xray/V2Fly geoip.dat + geosite.dat (XRAY_LOCATION_ASSET / V2RAY_LOCATION_ASSET). */
    private static final String CORE_ASSETS=ROOT+"/bin/assets";
    private static final String SCRIPT=ROOT+"/hetu-root.sh";
    private static final String HEALTH_CHECKER=ROOT+"/run/state/hetu-health-checker.sh";
    private static final String OLD_HETU_SCRIPT=ROOT+"/proxy-root.sh";
    /** hetu-root.sh keeps plain root as the core identity while this file exists in ROOT/policy. */
    static final String SYSTEM_DNS_DIRECT_MARKER="system-dns-direct";
    private static final String LEGACY_ROOT="/data/adb/bichen/proxy";
    private static final String LEGACY_MODULE="/data/adb/modules/bichen";
    private static final String LEGACY_MODULE_UPDATE="/data/adb/modules_update/bichen";
    private static final ProxyControlEpoch CONTROL_LOCK=new ProxyControlEpoch();
    // Recovery93 reconstruction: only short intent publication holds this monitor.
    // Network suppliers and Root calls never execute under it (lock inversion).
    private static final Object INTENT_LOCK=new Object();
    static final Object RECOVERY_BUDGET_LOCK=new Object();
    private static final java.util.concurrent.atomic.AtomicLong STOP_GENERATION=new java.util.concurrent.atomic.AtomicLong();
    private static final ThreadLocal<StartIntent> START_INTENT=new ThreadLocal<>();
    private static final ThreadLocal<Long> AUTOMATIC_DEADLINE=new ThreadLocal<>();
    private static final ThreadLocal<Long> STOP_DEADLINE=new ThreadLocal<>();
    private static final ThreadLocal<Long> AUTOMATIC_RESERVE=new ThreadLocal<>();
    /** Set only for the user's own Start: the pre-start hook rides on the single start command. */
    private static final ThreadLocal<Boolean> PRE_START_HOOK=new ThreadLocal<>();
    private static final class StartIntent {
        final long generation; final boolean wanted,automatic; final java.util.function.BooleanSupplier current;
        boolean nativeDispatched;
        StartIntent(long generation,boolean wanted,java.util.function.BooleanSupplier current){this(generation,wanted,wanted,current);}
        StartIntent(long generation,boolean wanted,boolean automatic,java.util.function.BooleanSupplier current){
            this.generation=generation;this.wanted=wanted;this.automatic=automatic;this.current=current;
        }
    }
    static void beginAutomaticRecoveryScope(long deadlineElapsed){
        if(AUTOMATIC_DEADLINE.get()!=null)throw new IllegalStateException("恢复范围不得嵌套");
        AUTOMATIC_DEADLINE.set(deadlineElapsed);AUTOMATIC_RESERVE.set(165000L);
    }
    static void endAutomaticRecoveryScope(){AUTOMATIC_DEADLINE.remove();AUTOMATIC_RESERVE.remove();}
    static void automaticStartCompleted(){if(AUTOMATIC_DEADLINE.get()!=null)AUTOMATIC_RESERVE.set(20000L);}
    static long automaticRootTimeout(long requested)throws IOException{
        Long stop=STOP_DEADLINE.get(),deadline=stop!=null?stop:AUTOMATIC_DEADLINE.get();
        if(deadline==null)return requested;
        long remaining=deadline-SystemClock.elapsedRealtime()-(stop==null?AUTOMATIC_RESERVE.get():0L);
        long bounded=Math.min(requested,remaining);
        if(bounded<1000L)throw new IOException("恢复总期限不足一秒，已保留启动及回滚预算");
        return bounded;
    }
    private RootBridge.Result boundedRootShell(Context c,String command,long requested){
        try{return RootBridge.rootShell(c,command,automaticRootTimeout(requested));}
        catch(IOException deadline){return new RootBridge.Result(124,deadline.getMessage());}
    }
    private void checkStartIntent()throws IOException{
        StartIntent intent=START_INTENT.get();
        if(intent==null)return;
        // A caller may stop the proxy inside the supplier. Recheck generation after it.
        boolean current=intent.current==null||intent.current.getAsBoolean();
        synchronized(INTENT_LOCK){
            if(!current||intent.generation!=STOP_GENERATION.get()
                    ||intent.wanted&&!prefs.getBoolean("proxyRootWanted",false))
                throw new IOException("启动意图已撤销或网络已变化");
        }
    }
    private void publishStartIntent(Runnable publish)throws IOException{
        checkStartIntent();
        synchronized(INTENT_LOCK){
            StartIntent intent=START_INTENT.get();
            if(intent!=null&&(intent.generation!=STOP_GENERATION.get()
                    ||intent.wanted&&!prefs.getBoolean("proxyRootWanted",false)))
                throw new IOException("启动意图已撤销");
            publish.run();
        }
    }
    private static final ReentrantLock HEALTH_CHECKER_LOCK=new ReentrantLock(true);
    static long observationTicket(){return CONTROL_LOCK.observe();}
    /** Read-only controller probes: invalid only during a real start/stop/reload transaction. */
    static long probeTicket(){return CONTROL_LOCK.transactionEpoch();}
    static boolean publishObservation(long ticket,Runnable publish){return CONTROL_LOCK.publish(ticket,publish);}
    private final Context context;
    private final SharedPreferences prefs;
    private final ProxyCoreStore cores;
    private final ProxyConfigLibrary configs;

    interface Progress{void onStage(String text);}
    private static final class StartupTrace {
        final long startedAt=SystemClock.elapsedRealtime();
        long phaseStartedAt=startedAt;
        String phase="controlLock",outcome="failed";
        final StringBuilder completed=new StringBuilder();
        void next(String next){
            long now=SystemClock.elapsedRealtime();
            completed.append(phase).append("Ms=").append(now-phaseStartedAt).append('\n');
            phase=next;phaseStartedAt=now;
        }
        String finish(){
            long now=SystemClock.elapsedRealtime();
            return "outcome="+outcome+" totalMs="+(now-startedAt)+"\n"+completed
                    +phase+"Ms="+(now-phaseStartedAt);
        }
    }
    static final class Prepared{
        final ProxyRuntimeProfile profile;
        final ProxyConfigLibrary.Entry source;
        final RootProxyPolicy policy;
        final ProxyAdblockRules.Snapshot adblock;
        final RootFakeIpRanges fakeIps;
        final String startup,settingsSignature;
        final int tproxyPort,redirectPort,controllerPort;
        Prepared(ProxyRuntimeProfile p,ProxyConfigLibrary.Entry s,RootProxyPolicy policy,ProxyAdblockRules.Snapshot adblock,RootFakeIpRanges fakeIps,String y,int tp,int rp,int cp,String signature){
            profile=p;source=s;this.policy=policy;this.adblock=adblock;this.fakeIps=fakeIps;startup=y;tproxyPort=tp;redirectPort=rp;controllerPort=cp;settingsSignature=signature;
        }
    }

    RootProxyManager(Context c){
        context=c.getApplicationContext();
        prefs=context.getSharedPreferences("hetu",0);
        cores=new ProxyCoreStore(context);
        configs=new ProxyConfigLibrary(context);
    }
    private static void stage(Progress p,String text){if(p!=null)p.onStage(text);}
    private static String bit(boolean value){return value?"1":"0";}
    private static ProxyRuntimeProfile withoutAdblock(ProxyRuntimeProfile p){
        return new ProxyRuntimeProfile(p.core,p.mode,p.ipv6,p.appScope,p.dnsHijack,p.autoOverwrite,p.tcp,p.udp,p.quicBlocked,p.cnIpDirect,false);
    }
    private static boolean adblockPreparationFailure(Exception error){
        String m=error==null||error.getMessage()==null?"":error.getMessage();
        return m.contains("代理串联去广告")||m.contains("广告 provider")||m.contains("广告规则")||m.contains("hetu-adblock");
    }
    private void rememberAdblockFallback(Exception error){
        String m=error==null||error.getMessage()==null?"广告串联与当前配置不兼容":error.getMessage();
        if(m.length()>600)m=m.substring(0,600)+"…";
        prefs.edit().putString("proxyAdblockLastError",m).apply();
    }
    String controllerSecret(){
        String s=prefs.getString("proxyControllerSecret","");
        if(s==null||s.isEmpty()){
            s=UUID.randomUUID().toString().replace("-","")+Long.toHexString(System.nanoTime());
            prefs.edit().putString("proxyControllerSecret",s).commit();
        }
        return s;
    }

    private int chooseControllerPort()throws IOException{
        int preferred=prefs.getInt("proxyControllerPort",MihomoStartupConfig.CONTROLLER_PORT);
        LinkedHashSet<Integer> candidates=new LinkedHashSet<>();
        // Prefer the last successful port when it is free. Preparing/preflighting must
        // never publish a candidate port to the dashboard client.
        if(preferred>=29090&&preferred<=29149)candidates.add(preferred);
        int span=60;
        int start=(int)(Math.abs(System.nanoTime())%span);
        for(int offset=0;offset<span;offset++)candidates.add(29090+((start+offset)%span));
        for(int port:candidates){
            try(ServerSocket socket=new ServerSocket();ServerSocket egress=new ServerSocket()){
                socket.setReuseAddress(false);
                egress.setReuseAddress(false);
                InetAddress loopback=InetAddress.getByName("127.0.0.1");
                socket.bind(new InetSocketAddress(loopback,port));
                egress.bind(new InetSocketAddress(loopback,MihomoStartupConfig.egressProbePort(port)));
                return port;
            }catch(IOException occupied){ }
        }
        throw new IOException("河图控制接口或出口探针动态端口已被占用，请关闭冲突代理后重试");
    }

    private Set<String> selectedTunPackages(){
        Set<String> raw=prefs.getStringSet("proxyAppPackages",Collections.emptySet());
        TreeSet<String> out=new TreeSet<>();
        if(raw!=null)for(String value:raw){
            if(value!=null&&value.length()<=255&&value.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"))out.add(value);
        }
        return out;
    }

    private String detectDefaultInterface()throws IOException{
        RootBridge.Result result=boundedRootShell(context,
                "set -- $(ip route get 1.1.1.1 2>/dev/null); while [ \"$#\" -gt 1 ]; do if [ \"$1\" = dev ]; then printf '%s' \"$2\"; break; fi; shift; done",5000L);
        String iface=result.output==null?"":result.output.trim();
        if(!iface.matches("[A-Za-z0-9_.:@-]{1,32}"))throw new IOException("eBPF 无法识别当前默认出口接口");
        return iface;
    }

    private String embeddedMihomoRevision(){
        try(InputStream in=context.getAssets().open("mihomo-revision.txt");
            BufferedReader reader=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){
            String revision=reader.readLine();
            if(revision!=null&&revision.trim().matches("[0-9a-fA-F]{7,64}"))return revision.trim().toLowerCase(Locale.ROOT);
        }catch(Exception ignored){}
        return "unknown";
    }

    private String expectedCoreToken(ProxyRuntimeProfile.Core core){
        if(cores.installed(core)){
            File file=cores.file(core);
            return "file:"+core.id+":"+file.length()+":"+file.lastModified();
        }
        return "asset:"+core.id+":"+embeddedMihomoRevision()+":fmt2-root-keepalive";
    }

    private boolean runtimeCoreCurrent(String token){
        try{
            RootBridge.Result result=boundedRootShell(context,
                    "if [ -x "+RootBridge.quote(BIN)+" ] && [ -r "+RootBridge.quote(CORE_TOKEN)+" ] && [ \"$(cat "+RootBridge.quote(CORE_TOKEN)+" 2>/dev/null)\" = "+RootBridge.quote(token)+" ]; then printf 1; else printf 0; fi",
                    4000L);
            return result.ok()&&"1".equals(result.output.trim());
        }catch(Exception ignored){
            return false;
        }
    }

    private RootStartupProbe.Result probeStartupRuntime(){
        try{
            RootBridge.requireWorkerThread();
            RootBridge.Result result=boundedRootShell(context,
                    RootStartupProbe.command(ROOT+"/run/core.pid",BIN,CORE_TOKEN),4000L);
            return RootStartupProbe.parse(result.ok(),result.output);
        }catch(Exception ignored){return RootStartupProbe.parse(false,"");}
    }

    private boolean coreAliveFast(){
        try{
            RootBridge.requireWorkerThread();
            String command=ProxyContinuity.coreProbeCommand(ROOT+"/run/core.pid",BIN);
            RootBridge.Result result=boundedRootShell(context,command,4000L);
            ProxyContinuity.ProcessState state=ProxyContinuity.processState(result.ok(),result.output);
            return ProxyContinuity.preserveRunning(state,
                    prefs.getBoolean("proxyRootRuntimeRunning",false)&&prefs.getBoolean("proxyRootWanted",false));
        }catch(Exception ignored){
            return prefs.getBoolean("proxyRootRuntimeRunning",false)&&prefs.getBoolean("proxyRootWanted",false);
        }
    }

    String ensureRuntimeBase(ProxyRuntimeProfile.Core requestedCore)throws Exception{
        RootBridge.requireWorkerThread();
        CONTROL_LOCK.lock();
        try{
        ProxyRuntimeProfile.Core core=requestedCore;
        if(core==ProxyRuntimeProfile.Core.MIHOMO_SMART&&!cores.installed(core))core=ProxyRuntimeProfile.Core.MIHOMO;
        File stage=new File(context.getCacheDir(),"hetu-runtime-init");
        if(!stage.isDirectory()&&!stage.mkdirs())throw new IOException("无法创建河图运行初始化目录");
        File script=new File(stage,"hetu-root.sh");
        copyScriptAsset(script);
        String coreToken=expectedCoreToken(core);
        boolean deployCore=!runtimeCoreCurrent(coreToken);
        File binary=deployCore?coreFile(core,stage):null;
        String deploySuffix=".new."+Long.toHexString(System.nanoTime());
        String scriptTmp=SCRIPT+deploySuffix;
        String binTmp=BIN+deploySuffix;
        String info=ROOT+"/run/state/runtime.info";
        String infoText="app="+BuildConfig.VERSION_NAME+"\ncore="+core.id+"\nroot="+ROOT+"\n";
        StringBuilder cmd=new StringBuilder("set -e; mkdir -p ")
                .append(RootBridge.quote(ROOT+"/bin")).append(' ')
                .append(RootBridge.quote(ROOT+"/run/state")).append(' ')
                .append(RootBridge.quote(ROOT+"/run/ruleset"))
                .append("; cp ").append(RootBridge.quote(script.getAbsolutePath())).append(' ').append(RootBridge.quote(scriptTmp))
                .append("; chmod 700 ").append(RootBridge.quote(scriptTmp)).append("; chown 0:0 ").append(RootBridge.quote(scriptTmp))
                .append("; mv -f ").append(RootBridge.quote(scriptTmp)).append(' ').append(RootBridge.quote(SCRIPT));
        if(deployCore){
            cmd.append("; cp ").append(RootBridge.quote(binary.getAbsolutePath())).append(' ').append(RootBridge.quote(binTmp))
                    .append("; chmod 700 ").append(RootBridge.quote(binTmp)).append("; chown 0:0 ").append(RootBridge.quote(binTmp))
                    .append("; mv -f ").append(RootBridge.quote(binTmp)).append(' ').append(RootBridge.quote(BIN))
                    .append("; printf %s ").append(RootBridge.quote(coreToken)).append(" > ").append(RootBridge.quote(CORE_TOKEN))
                    .append("; chmod 600 ").append(RootBridge.quote(CORE_TOKEN)).append("; chown 0:0 ").append(RootBridge.quote(CORE_TOKEN));
            appendCoreAssets(cmd,core);
        }
        appendCoreKind(cmd,core);
        cmd.append("; rm -f ").append(RootBridge.quote(OLD_HETU_SCRIPT))
                .append("; printf %s ").append(RootBridge.quote(infoText)).append(" > ").append(RootBridge.quote(info))
                .append("; chmod 600 ").append(RootBridge.quote(info)).append("; chown 0:0 ").append(RootBridge.quote(info));
        RootBridge.Result r=boundedRootShell(context,cmd.toString(),45000L);
        if(!r.ok())throw new IOException("无法初始化河图运行目录："+r.output.trim());
        return ROOT;
        }finally{CONTROL_LOCK.unlock();}
    }

    private void verifyAdblockRuntime(MihomoControllerClient controller,boolean enabled,int expectedCount)throws Exception{
        JSONArray rules=controller.rules().optJSONArray("rules");
        if(rules==null)throw new IOException("Controller 未返回运行规则，过滤状态未确认");
        boolean found=false;
        for(int i=0;i<rules.length();i++){
            JSONObject rule=rules.optJSONObject(i);
            if(rule==null)continue;
            JSONObject extra=rule.optJSONObject("extra");
            boolean disabled=rule.optBoolean("disabled",false)||(extra!=null&&extra.optBoolean("disabled",false));
            if(AdblockRuleInspection.isBlockingRule(rule.optString("type"),rule.optString("payload"),
                    rule.optString("proxy"),disabled)){found=true;break;}
        }
        if(found!=enabled)throw new IOException(enabled?"Controller 尚未加载河图广告拦截规则":"Controller 仍在执行河图广告拦截规则");
        if(!enabled)return;
        JSONObject providers=controller.ruleProviders().optJSONObject("providers");
        JSONObject block=providers==null?null:providers.optJSONObject(ProxyAdblockRules.PROVIDER_NAME);
        JSONObject allow=providers==null?null:providers.optJSONObject(ProxyAdblockRules.ALLOW_PROVIDER_NAME);
        if(block==null||allow==null||block.optInt("ruleCount",-1)<0||allow.optInt("ruleCount",-1)<0
                ||(expectedCount>0&&block.optInt("ruleCount",0)==0))
            throw new IOException("Controller 广告过滤文件尚未完整加载");
    }

    String refreshAdblockRuntime()throws Exception{
        CONTROL_LOCK.lock();
        try{
            RootBridge.requireWorkerThread();
            if(!prefs.getBoolean("proxyAdblockChain",true))return "广告过滤串联未开启";
            JSONObject state=status();
            if(!state.optBoolean("running",false))return "规则已保存；代理下次启动时自动使用新规则";
            ProxyRuntimeProfile.Core running=ProxyRuntimeProfile.Core.from(prefs.getString("proxyRootRuntimeCore","mihomo"));
            if(!ProxyCoreSupport.hotReload(running))
                return "规则已保存；"+ProxyCoreSupport.unsupported(running,"运行中热更新过滤规则")+"，重启代理后生效";
            ProxyAdblockRules.Snapshot snapshot=ProxyAdblockRules.export(context);
            String blockDst=ROOT+"/run/ruleset/hetu-adblock.txt";
            String allowDst=ROOT+"/run/ruleset/hetu-adblock-allow.txt";
            String suffix=".new."+Long.toHexString(System.nanoTime());
            String cmd="set -e; mkdir -p "+RootBridge.quote(ROOT+"/run/ruleset")
                    +"; cp "+RootBridge.quote(snapshot.file.getAbsolutePath())+" "+RootBridge.quote(blockDst+suffix)
                    +"; chmod 600 "+RootBridge.quote(blockDst+suffix)+"; chown 0:0 "+RootBridge.quote(blockDst+suffix)
                    +"; cp "+RootBridge.quote(snapshot.allowFile.getAbsolutePath())+" "+RootBridge.quote(allowDst+suffix)
                    +"; chmod 600 "+RootBridge.quote(allowDst+suffix)+"; chown 0:0 "+RootBridge.quote(allowDst+suffix)
                    +"; mv -f "+RootBridge.quote(allowDst+suffix)+" "+RootBridge.quote(allowDst)
                    +"; mv -f "+RootBridge.quote(blockDst+suffix)+" "+RootBridge.quote(blockDst);
            RootBridge.Result copied=boundedRootShell(context,cmd,20000L);
            if(!copied.ok())throw new IOException("规则已更新，但写入运行目录失败："+copied.output.trim());

            int port=liveControllerPort(state);
            if(port>0)prefs.edit().putInt("proxyControllerPort",port).apply();
            MihomoControllerClient controller=new MihomoControllerClient(context);
            try{
                controller.reloadLocalRuleProvider(ProxyAdblockRules.ALLOW_PROVIDER_NAME);
                controller.reloadLocalRuleProvider(ProxyAdblockRules.PROVIDER_NAME);
                verifyAdblockRuntime(controller,true,snapshot.count);
            }catch(Exception providerError){
                // Do not reload the entire proxy after a provider error: this can
                // disturb live transports, and an old config without the providers
                // can reload successfully while the new filtering still isn't active.
                String detail=providerError.getMessage()==null?providerError.getClass().getSimpleName():providerError.getMessage();
                String message="规则已保存，但运行中的过滤规则未确认更新："+detail+"；请重试或主动重启代理";
                prefs.edit().putString("proxyAdblockLastError",message).apply();
                throw new IOException(message,providerError);
            }
            String actualFilterMode14=controller.configs().optString("mode", "unknown");
            prefs.edit()
                    .putBoolean("proxyAdblockLastEffective",snapshot.count>0&&AdblockRuleInspection.isRuleMode(actualFilterMode14))
                    .putString("proxyAdblockActualMode",actualFilterMode14)
                    .putLong("proxyAdblockProviderReloadAt",System.currentTimeMillis())
                    .putInt("proxyAdblockLastRuleCount",snapshot.count)
                    .putString("proxyAdblockLastRevision",snapshot.revision)
                    .putLong("proxyAdblockHotReloadAt",System.currentTimeMillis())
                    .remove("proxyAdblockLastError")
                    .apply();
            return RuntimeCompatibility14.filterReloadMessage(actualFilterMode14,snapshot.count);
        }finally{
            CONTROL_LOCK.unlock();
        }
    }

    Prepared prepare(ProxyRuntimeProfile profile)throws Exception{
        return prepare(profile,0);
    }

    private Prepared prepare(ProxyRuntimeProfile profile,int fixedControllerPort)throws Exception{
        RootBridge.requireWorkerThread();
        String settingsSignature=ProxyRuntimeSettings.signature(profile,prefs.getAll());
        ProxyRuntimeProfile.Capability capability=profile.capability();
        if(!capability.available)throw new IOException(capability.reason.isEmpty()?profile.mode.label+" 暂不可用":capability.reason);
        if(ProxyCoreConfig.kind(profile.core)!=ProxyCoreConfig.Kind.MIHOMO)
            return prepareNativeCore(profile,fixedControllerPort,settingsSignature);
        if(profile.mode!=ProxyRuntimeProfile.Mode.TPROXY&&profile.mode!=ProxyRuntimeProfile.Mode.REDIRECT&&profile.mode!=ProxyRuntimeProfile.Mode.ENHANCE&&
                profile.mode!=ProxyRuntimeProfile.Mode.TUN&&profile.mode!=ProxyRuntimeProfile.Mode.EBPF)
            throw new IOException(profile.mode.label+" 的统一 Root 后端还未接入");

        ProxyConfigLibrary.Entry selected=configs.selected(profile.core);
        if(selected==null)throw new IOException("请先为 "+profile.core.label+" 选择配置文件");
        String source=configs.read(selected);
        if(source==null||source.trim().isEmpty())throw new IOException("源配置为空");
        if(source.getBytes(StandardCharsets.UTF_8).length>4*1024*1024)throw new IOException("配置超过 4 MiB");
        // Source YAML rule order is authoritative. PROCESS-NAME ... DIRECT remains inside
        // Mihomo and must not be promoted into a pre-Mihomo Root UID bypass.
        RootProxyPolicy policy=RootProxyPolicy.load(context,prefs,profile);
        ProxyAdblockRules.Snapshot adblock=profile.adblockChain?ProxyAdblockRules.export(context):null;
        int controllerPort=(fixedControllerPort>=29090&&fixedControllerPort<=29149)?fixedControllerPort:chooseControllerPort();
        String ebpfInterface=profile.mode==ProxyRuntimeProfile.Mode.EBPF?detectDefaultInterface():"";
        Set<String> tunPackages=profile.mode==ProxyRuntimeProfile.Mode.TUN?selectedTunPackages():Collections.emptySet();
        MihomoStartupConfig.Result generated=MihomoStartupConfig.generate(source,profile,controllerSecret(),controllerPort,profile.appScope,tunPackages,policy.directPackages,ebpfInterface);
        RootFakeIpRanges fakeIps=RootFakeIpRanges.parse(generated.yaml,profile);
        // Private comments carry an already validated routing projection into start/boot.
        // Keep the established startup argument protocol and the user's source untouched.
        String startup=fakeIps.privateStartup(generated.yaml);
        writeStartupCopy(startup);
        return new Prepared(profile,selected,policy,adblock,fakeIps,startup,generated.tproxyPort,generated.redirectPort,controllerPort,settingsSignature);
    }


    /**
     * sing-box / Xray / V2Fly / Hysteria: a native config for that core when the user selected one,
     * otherwise the selected Clash/Mihomo YAML converted by ProxyCoreConfig. The same TPROXY/Redirect
     * ports, DNS port, controller/egress ports and fake-IP routing metadata as the Mihomo path.
     */
    private Prepared prepareNativeCore(ProxyRuntimeProfile profile,int fixedControllerPort,String settingsSignature)throws Exception{
        ProxyConfigLibrary.Entry selected=configs.selected(profile.core);
        if(selected==null)selected=configs.selected(ProxyRuntimeProfile.Core.MIHOMO);
        if(selected==null)throw new IOException("请先为 "+profile.core.label+" 选择配置文件，或在 Mihomo 配置中选择一个可转换的订阅");
        String source=configs.read(selected);
        if(source==null||source.trim().isEmpty())throw new IOException("源配置为空");
        if(source.getBytes(StandardCharsets.UTF_8).length>4*1024*1024)throw new IOException("配置超过 4 MiB");
        RootProxyPolicy policy=RootProxyPolicy.load(context,prefs,profile);
        ProxyAdblockRules.Snapshot adblock=profile.adblockChain?ProxyAdblockRules.export(context):null;
        int controllerPort=(fixedControllerPort>=29090&&fixedControllerPort<=29149)?fixedControllerPort:chooseControllerPort();
        ProxyCoreConfig.Options options=nativeOptions(profile,controllerPort,adblock);
        ProxyCoreConfig.Result built=ProxyCoreConfig.build(profile.core,source,options,this::fetchProvider);
        if(profile.adblockChain&&!built.adblockApplied){
            profile=withoutAdblock(profile);
            adblock=null;
        }
        prefs.edit().putString("proxyCoreConversionWarnings",built.warningText())
                .putBoolean("proxyCoreConversionNative",built.nativeSource)
                .putInt("proxyCoreConversionNodes",built.nodes)
                .putString("proxyCoreConversionSource",selected.core.id+"/"+selected.name).apply();
        RootFakeIpRanges fakeIps=nativeFakeIps(profile,built.fakeIpV4);
        // The startup copy keeps the established protocol: native payload + the terminal
        // fake-IP block, which hetu-root.sh strips again before the core reads its file.
        String startup=built.config+(built.config.endsWith("\n")?"":"\n")
                +"# HETU_FAKE_IP_POLICY=1\n# HETU_FAKE_IP_V4="+fakeIps.ipv4+"\n# HETU_FAKE_IP_V6="+fakeIps.ipv6
                +"\n# HETU_LAN_RETURN_V4="+fakeIps.ipv4BypassCidrs+"\n# HETU_LAN_RETURN_V6="+fakeIps.ipv6BypassCidrs+"\n";
        writeStartupCopy(startup);
        return new Prepared(profile,selected,policy,adblock,fakeIps,startup,options.tproxyPort,options.redirectPort,controllerPort,settingsSignature);
    }

    private ProxyCoreConfig.Options nativeOptions(ProxyRuntimeProfile profile,int controllerPort,ProxyAdblockRules.Snapshot adblock)throws IOException{
        ProxyCoreConfig.Options o=new ProxyCoreConfig.Options();
        o.mode=profile.mode;
        if(profile.mode==ProxyRuntimeProfile.Mode.TPROXY)o.tproxyPort=MihomoStartupConfig.TPROXY_PORT;
        else{
            o.redirectPort=MihomoStartupConfig.REDIRECT_PORT;
            if(profile.dnsHijack==ProxyRuntimeProfile.DnsHijack.TPROXY)o.tproxyPort=MihomoStartupConfig.TPROXY_PORT;
        }
        o.dnsPort=profile.dnsHijack==ProxyRuntimeProfile.DnsHijack.OFF?0:MihomoStartupConfig.DNS_PORT;
        o.controllerPort=controllerPort;
        o.egressPort=MihomoStartupConfig.egressProbePort(controllerPort);
        o.secret=controllerSecret();
        o.ipv6=profile.ipv6==ProxyRuntimeProfile.Ipv6.ENABLE||profile.ipv6==ProxyRuntimeProfile.Ipv6.BYPASS;
        o.tcp=profile.tcp;o.udp=profile.udp;
        o.cnIpDirect=profile.cnIpDirect;
        File geo=new File(cores.file(profile.core).getParentFile(),profile.core.id+".geosite.dat");
        o.geoAssets=geo.isFile()&&geo.length()>0;
        if(adblock!=null){
            o.adblock=adblockDomains(adblock.file);
            o.adblockAllow=adblockDomains(adblock.allowFile);
        }
        o.hostResolver=host->{
            InetAddress[] all=InetAddress.getAllByName(host);
            for(InetAddress a:all)if(a instanceof Inet4Address)return a.getHostAddress();
            return all.length==0?null:all[0].getHostAddress();
        };
        return o;
    }

    private static List<String> adblockDomains(File file)throws IOException{
        ArrayList<String> out=new ArrayList<>();
        if(file==null||!file.isFile())return out;
        for(String line:Files.readAllLines(file.toPath(),StandardCharsets.UTF_8)){
            String d=line.trim();
            if(d.startsWith("+."))d=d.substring(2);
            if(!d.isEmpty()&&!d.startsWith("#"))out.add(d);
        }
        return out;
    }

    /** Same fake-IP routing projection as the Mihomo path, from the converted DNS range. */
    private static RootFakeIpRanges nativeFakeIps(ProxyRuntimeProfile profile,String fakeIpV4)throws IOException{
        ProxyRuntimeProfile projection=new ProxyRuntimeProfile(ProxyRuntimeProfile.Core.MIHOMO,profile.mode,profile.ipv6,profile.appScope,
                profile.dnsHijack,profile.autoOverwrite,profile.tcp,profile.udp,profile.quicBlocked,profile.cnIpDirect,profile.adblockChain);
        String yaml=fakeIpV4==null||fakeIpV4.isEmpty()?"dns:\n  enable: false\n"
                :"dns:\n  enable: true\n  enhanced-mode: fake-ip\n  fake-ip-range: "+fakeIpV4+"\n";
        return RootFakeIpRanges.parse(yaml,projection);
    }

    /**
     * proxy-providers / rule-providers for a conversion. Hetu's own UID is exempt from Root
     * interception, so this is a direct fetch; the last good body is kept for offline restarts.
     */
    private String fetchProvider(String url)throws IOException{
        if(url==null||!(url.startsWith("https://")||url.startsWith("http://")))return null;
        File dir=new File(context.getCacheDir(),"core-provider-cache");
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法创建订阅缓存目录");
        File cache;
        try{cache=new File(dir,hex(MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8))));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IOException(impossible);}
        HttpURLConnection connection=null;
        try{
            checkStartIntent();
            connection=(HttpURLConnection)new URL(url).openConnection();
            connection.setConnectTimeout(10000);connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent","clash.meta");
            int code=connection.getResponseCode();
            if(code<200||code>299)throw new IOException("HTTP "+code);
            ByteArrayOutputStream body=new ByteArrayOutputStream();
            try(InputStream in=connection.getInputStream()){
                byte[] b=new byte[32768];int n;
                while((n=in.read(b))!=-1){body.write(b,0,n);if(body.size()>ProxyCoreConfig.MAX_PROVIDER_BYTES)throw new IOException("订阅内容过大");}
            }
            String text=new String(body.toByteArray(),StandardCharsets.UTF_8);
            try(FileOutputStream out=new FileOutputStream(cache,false)){out.write(body.toByteArray());out.getFD().sync();}
            return text;
        }catch(IOException failed){
            if(cache.isFile())return new String(Files.readAllBytes(cache.toPath()),StandardCharsets.UTF_8);
            return null;
        }finally{if(connection!=null)connection.disconnect();}
    }

    private String topologyFingerprint(ProxyRuntimeProfile profile,RootProxyPolicy policy,RootFakeIpRanges fakeIps){
        return profile.core.id+"|"+profile.mode.id+"|"+profile.ipv6.id+"|"+profile.dnsHijack.id
                +"|tcp="+bit(profile.tcp)+"|udp="+bit(profile.udp)+"|quic="+bit(profile.quicBlocked)
                +"|dnsForward="+bit(prefs.getBoolean("proxyMihomoDnsForward",true))
                +"|dnsTcp="+bit(prefs.getBoolean("proxyDnsHijackTcp",true))+"|dnsUdp="+bit(prefs.getBoolean("proxyDnsHijackUdp",true))
                +"|perf="+bit(prefs.getBoolean("proxyPerformanceMode",false))+"|cpu="+prefs.getString("proxyCpuAffinity","")
                +"|mem="+prefs.getString("proxyMemoryLimit","")+"|io="+prefs.getString("proxyIoWeight","")
                +"|vendorClean="+bit(prefs.getBoolean("proxyVendorFirewallCleanup",false))
                +"|systemDns="+bit(prefs.getBoolean("proxyDnsSystemResolver",true))
                +"|scope="+policy.appScope+"|uids="+policy.uidRanges+"|share="+bit(policy.sharedNetwork)
                +"|kill="+bit(policy.killSwitch)+"|cidrs="+policy.cidrs+"|ifaces="+policy.interfaces
                +"|sharedMacs="+policy.sharedBypassMacs
                +"|direct="+policy.directUidRanges+"|directGids="+policy.directGidRanges
                +"|fake4="+fakeIps.ipv4+"|fake6="+fakeIps.ipv6
                +"|lan4="+fakeIps.ipv4BypassCidrs+"|lan6="+fakeIps.ipv6BypassCidrs;
    }

    private void installHotReloadFiles(Prepared p)throws Exception{
        File cfg=startupFile();
        if(!cfg.isFile())throw new IOException("热重载配置尚未生成");
        String suffix=".reload."+Long.toHexString(System.nanoTime());
        String backup=CONFIG+".before-reload";
        String candidate=CONFIG+suffix;
        StringBuilder cmd=new StringBuilder("set -e; mkdir -p ")
                .append(RootBridge.quote(ROOT+"/run/state")).append(' ').append(RootBridge.quote(ROOT+"/run/ruleset"))
                .append("; cp ").append(RootBridge.quote(CONFIG)).append(' ').append(RootBridge.quote(backup))
                .append("; cp ").append(RootBridge.quote(cfg.getAbsolutePath())).append(' ').append(RootBridge.quote(candidate))
                .append("; chmod 600 ").append(RootBridge.quote(candidate)).append("; chown 0:0 ").append(RootBridge.quote(candidate))
                .append("; mv -f ").append(RootBridge.quote(candidate)).append(' ').append(RootBridge.quote(CONFIG));
        if(p.adblock!=null){
            String dst=ROOT+"/run/ruleset/hetu-adblock.txt",tmp=dst+suffix;
            String allowDst=ROOT+"/run/ruleset/hetu-adblock-allow.txt",allowTmp=allowDst+suffix;
            cmd.append("; cp ").append(RootBridge.quote(p.adblock.file.getAbsolutePath())).append(' ').append(RootBridge.quote(tmp))
                    .append("; chmod 600 ").append(RootBridge.quote(tmp)).append("; chown 0:0 ").append(RootBridge.quote(tmp))
                    .append("; mv -f ").append(RootBridge.quote(tmp)).append(' ').append(RootBridge.quote(dst));
            if(p.adblock.allowFile!=null){
                cmd.append("; cp ").append(RootBridge.quote(p.adblock.allowFile.getAbsolutePath())).append(' ').append(RootBridge.quote(allowTmp))
                        .append("; chmod 600 ").append(RootBridge.quote(allowTmp)).append("; chown 0:0 ").append(RootBridge.quote(allowTmp))
                        .append("; mv -f ").append(RootBridge.quote(allowTmp)).append(' ').append(RootBridge.quote(allowDst));
            }
        }
        RootBridge.Result installed=boundedRootShell(context,cmd.toString(),15000L);
        if(!installed.ok())throw new IOException("无法写入热重载配置："+installed.output.trim());
    }

    String reloadCurrentConfig()throws Exception{
        CONTROL_LOCK.lock();
        try{
            RootBridge.requireWorkerThread();
            if(!coreAliveFast())throw new IOException("代理未运行，无法热重载");
            ProxyRuntimeProfile profile=ProxyRuntimeProfile.load(prefs);
            ProxyRuntimeProfile.Core running=ProxyRuntimeProfile.Core.from(prefs.getString("proxyRootRuntimeCore",profile.core.id));
            if(!ProxyCoreSupport.hotReload(running)||!ProxyCoreSupport.hotReload(profile.core))
                throw new IOException(ProxyCoreSupport.unsupported(running,"热重载")+"；请使用「重启」应用配置");
            int port=prefs.getInt("proxyControllerPort",MihomoStartupConfig.CONTROLLER_PORT);
            Prepared p=prepare(profile,port);
            String nextFingerprint=topologyFingerprint(profile,p.policy,p.fakeIps);
            String liveFingerprint=prefs.getString("proxyRootTopologyFingerprint","");
            if(liveFingerprint!=null&&!liveFingerprint.isEmpty()&&!liveFingerprint.equals(nextFingerprint))
                throw new IOException("运行模式、应用范围、DNS、IPv6、TCP/UDP 或绕过策略已变化，请使用「重启」应用这些网络层设置");

            installHotReloadFiles(p);
            MihomoControllerClient controller=new MihomoControllerClient(context);
            boolean effective=false;
            try{
                controller.reloadConfig(CONFIG);
                verifyAdblockRuntime(controller,profile.adblockChain,p.adblock==null?0:p.adblock.count);
                effective=profile.adblockChain&&AdblockRuleInspection.isRuleMode(controller.configs().optString("mode", ""));
            }catch(Exception error){
                boundedRootShell(context,
                        "if [ -f "+RootBridge.quote(CONFIG+".before-reload")+" ]; then mv -f "
                                +RootBridge.quote(CONFIG+".before-reload")+" "+RootBridge.quote(CONFIG)+"; fi",
                        5000L);
                try{controller.reloadConfig(CONFIG);}catch(Exception ignored){}
                prefs.edit().putString("proxyAdblockLastError","过滤设置未确认应用："+String.valueOf(error.getMessage())).apply();
                throw error;
            }
            boundedRootShell(context,"rm -f "+RootBridge.quote(CONFIG+".before-reload"),3000L);
            prefs.edit()
                    .putString("proxyRootTopologyFingerprint",nextFingerprint)
                    .putString("proxyRootAppliedSettings",p.settingsSignature)
                    .putInt("proxyAdblockLastRuleCount",p.adblock==null?0:p.adblock.count)
                    .putString("proxyAdblockLastRevision",p.adblock==null?"":p.adblock.revision)
                    .putBoolean("proxyAdblockLastEffective",effective)
                    .putBoolean("proxyAdblockCounterArmed",profile.adblockChain)
                    .remove("proxyAdblockLastError")
                    .apply();
            String bootWarning=refreshAutostart(p,autostartArgs(p));
            return "运行配置已热重载"+(bootWarning.isEmpty()?"":"；"+bootWarning);
        }finally{
            CONTROL_LOCK.unlock();
        }
    }

    private static String hex(byte[] bytes){
        StringBuilder out=new StringBuilder(bytes.length*2);
        for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&0xff));
        return out.toString();
    }

    private String validationFingerprint(Prepared p)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        digest.update(p.startup.getBytes(StandardCharsets.UTF_8));
        digest.update((byte)0);
        digest.update(expectedCoreToken(p.profile.core).getBytes(StandardCharsets.UTF_8));
        if(p.adblock!=null){
            digest.update((byte)0);
            digest.update(p.adblock.revision.getBytes(StandardCharsets.UTF_8));
        }
        return hex(digest.digest());
    }

    private String capabilityFingerprint(ProxyRuntimeProfile profile,RootProxyPolicy policy,RootFakeIpRanges fakeIps){
        return "caps-v2|"+ProxyRuntimeSettings.RUNTIME_REVISION+"|"+Build.FINGERPRINT+"|"+topologyFingerprint(profile,policy,fakeIps);
    }

    JSONObject preflight(Prepared p)throws Exception{
        RootBridge.requireWorkerThread();
        CONTROL_LOCK.lock();
        try{
        installRuntimeFiles(p,false);
        RootProxyPolicy policy=p.policy;
        String preflightDns=prefs.getBoolean("proxyMihomoDnsForward",true)?p.profile.dnsHijack.id:"off";
        return runJson("preflight",
                p.profile.mode.id,String.valueOf(p.tproxyPort),String.valueOf(p.redirectPort),p.profile.ipv6.id,
                bit(p.profile.tcp),bit(p.profile.udp),preflightDns,bit(p.profile.quicBlocked),
                String.valueOf(MihomoStartupConfig.DNS_PORT),String.valueOf(p.controllerPort),
                policy.appScope,policy.uidRanges,bit(policy.sharedNetwork),bit(policy.killSwitch),policy.cidrs,policy.interfaces,policy.directUidRanges,policy.directGidRanges,policy.sharedBypassMacs);
        }finally{CONTROL_LOCK.unlock();}
    }

    JSONObject start(ProxyRuntimeProfile p)throws Exception{return startInternal(p,null,false);}
    JSONObject start(ProxyRuntimeProfile profile,Progress progress)throws Exception{return startInternal(profile,progress,false);}
    JSONObject startManual(ProxyRuntimeProfile profile,Progress progress)throws Exception{return startOwned(profile,progress,false);}
    /** The user's Start button: the pre-start hook runs inside the same Root invocation as the start. */
    JSONObject startManual(ProxyRuntimeProfile profile,Progress progress,boolean preStartHook)throws Exception{
        if(!preStartHook)return startOwned(profile,progress,false);
        PRE_START_HOOK.set(Boolean.TRUE);
        try{return startOwned(profile,progress,false);}finally{PRE_START_HOOK.remove();}
    }
    JSONObject replaceRunningManually(ProxyRuntimeProfile profile,Progress progress)throws Exception{return startOwned(profile,progress,true);}
    private JSONObject startOwned(ProxyRuntimeProfile profile,Progress progress,boolean replace)throws Exception{
        final long request=STOP_GENERATION.get();
        CONTROL_LOCK.lock();
        StartIntent previousIntent=START_INTENT.get();
        START_INTENT.set(new StartIntent(request,false,null));
        String previous=prefs.getString("proxyRootSessionOwner","");
        try{
            checkStartIntent();
            final long manual=Math.max(prefs.getLong("proxyRootManualStartGeneration",0L),
                    prefs.getLong("proxyRootStoppedManualGeneration",0L))+1L;
            publishStartIntent(()->prefs.edit().putString("proxyRootSessionOwner","manual")
                    .putLong("proxyRootManualStartGeneration",manual).apply());
            JSONObject result=startInternal(profile,progress,replace);
            if(result.optBoolean("ok",false)&&!result.optBoolean("cancelled",false)){
                int boot=android.provider.Settings.Global.getInt(context.getContentResolver(),android.provider.Settings.Global.BOOT_COUNT,-1);
                publishStartIntent(()->prefs.edit().putLong("proxyRootManualStartSucceededGeneration",manual)
                        .putInt("proxyRootManualStartSucceededBootCount",boot).apply());
            }
            return result;
        }catch(Exception error){
            synchronized(INTENT_LOCK){
                if(request==STOP_GENERATION.get()){
                    if(previous==null||previous.isEmpty())prefs.edit().remove("proxyRootSessionOwner").apply();
                    else prefs.edit().putString("proxyRootSessionOwner",previous).apply();
                }
            }
            throw error;
        }finally{if(previousIntent==null)START_INTENT.remove();else START_INTENT.set(previousIntent);CONTROL_LOCK.unlock();}
    }
    JSONObject startIfAutomationAllowed(ProxyRuntimeProfile profile,java.util.function.BooleanSupplier currentDecision)throws Exception{
        final long request=STOP_GENERATION.get();
        CONTROL_LOCK.lock();
        StartIntent previous=START_INTENT.get();
        START_INTENT.set(new StartIntent(request,false,true,currentDecision));
        boolean ownScope=AUTOMATIC_DEADLINE.get()==null;
        try{
            if(!prefs.getBoolean("networkMatchEnabled",false)||"manual".equals(prefs.getString("proxyRootSessionOwner","")))
                return new JSONObject().put("ok",true).put("cancelled",true);
            checkStartIntent();
            if(ownScope)beginMatchingRecoveryScope(request);
            JSONObject result=startInternal(profile,null,false);
            if(result.optBoolean("ok",false)&&!result.optBoolean("cancelled",false))
                publishStartIntent(()->prefs.edit().putString("proxyRootSessionOwner","automation").apply());
            return result;
        }finally{if(ownScope)endAutomaticRecoveryScope();if(previous==null)START_INTENT.remove();else START_INTENT.set(previous);CONTROL_LOCK.unlock();}
    }
    private void beginMatchingRecoveryScope(long request)throws IOException{
        synchronized(INTENT_LOCK){synchronized(RECOVERY_BUDGET_LOCK){
            if(request!=STOP_GENERATION.get())throw new IOException("自动匹配启动意图已撤销");
            int boot=android.provider.Settings.Global.getInt(context.getContentResolver(),android.provider.Settings.Global.BOOT_COUNT,-1);
            long now=SystemClock.elapsedRealtime(),manual=prefs.getLong("proxyRootManualStartGeneration",0L);
            if(!prefs.contains("proxyRecoveryBootCount")||boot!=prefs.getInt("proxyRecoveryBootCount",-1)
                    ||manual!=prefs.getLong("proxyRecoveryManualGeneration",0L)){
                if(!prefs.edit().putInt("proxyRecoveryBootCount",boot).putLong("proxyRecoveryManualGeneration",manual)
                        .putLong("proxyRecoveryStartedElapsed",now).putLong("proxyRecoveryDeadlineElapsed",now+300000L)
                        .putInt("proxyRecoveryStarts",0).putBoolean("proxyRecoveryEpisodeComplete",false)
                        .remove("proxyRecoveryTerminalReason").remove("proxyRecoveryCompletedAfterBudget")
                        .remove("proxyRecoveryMatchingAttemptElapsed").commit())
                    throw new IOException("自动匹配恢复预算未能保存");
            }
            long deadline=prefs.getLong("proxyRecoveryDeadlineElapsed",0L);
            int starts=prefs.getInt("proxyRecoveryStarts",0);
            if(deadline-now<165000L||starts>=6||prefs.getBoolean("proxyRecoveryCompletedAfterBudget",false)
                    ||!prefs.getString("proxyRecoveryTerminalReason","").isEmpty())
                throw new IOException("自动匹配已耗尽本轮恢复预算，请手动检查");
            long last=prefs.getLong("proxyRecoveryMatchingAttemptElapsed",-1L);
            if(last>=0L&&now-last<30000L)throw new IOException("自动匹配等待恢复退避间隔");
            if(!prefs.edit().putInt("proxyRecoveryStarts",starts+1).putLong("proxyRecoveryMatchingAttemptElapsed",now).commit())
                throw new IOException("自动匹配恢复次数未能保存");
            beginAutomaticRecoveryScope(deadline);
        }}
    }
    JSONObject stopIfAutomationOwned(java.util.function.BooleanSupplier currentDecision)throws Exception{
        CONTROL_LOCK.lock();
        try{
            if(!prefs.getBoolean("networkMatchEnabled",false)||!"automation".equals(prefs.getString("proxyRootSessionOwner",""))||!currentDecision.getAsBoolean())
                return new JSONObject().put("ok",true).put("cancelled",true);
            return stop();
        }finally{CONTROL_LOCK.unlock();}
    }
    JSONObject startIfWanted(ProxyRuntimeProfile profile)throws Exception{
        return startIfWanted(profile,()->true);
    }
    JSONObject startIfWanted(ProxyRuntimeProfile profile,java.util.function.BooleanSupplier currentNetwork)throws Exception{
        final long request=STOP_GENERATION.get();
        if(!prefs.getBoolean("proxyRootWanted",false))
            return new JSONObject().put("ok",true).put("running",false).put("cancelled",true);
        if(!currentNetwork.getAsBoolean())
            return new JSONObject().put("ok",true).put("running",false).put("cancelled",true).put("reason","network-changed");
        synchronized(INTENT_LOCK){
            if(request!=STOP_GENERATION.get()||!prefs.getBoolean("proxyRootWanted",false))
                return new JSONObject().put("ok",true).put("running",false).put("cancelled",true).put("reason","intent-revoked");
        }
        if(!CONTROL_LOCK.tryLock())return new JSONObject().put("ok",true).put("cancelled",true).put("reason","control-busy");
        StartIntent previous=START_INTENT.get();
        START_INTENT.set(new StartIntent(request,true,currentNetwork));
        try{return startInternal(profile,null,false);}
        finally{if(previous==null)START_INTENT.remove();else START_INTENT.set(previous);CONTROL_LOCK.unlock();}
    }
    JSONObject replaceRunningAfterUpgrade(ProxyRuntimeProfile profile)throws Exception{return startInternal(profile,null,true);}
    JSONObject replaceRunningAfterUpgrade(ProxyRuntimeProfile profile,Progress progress)throws Exception{return startInternal(profile,progress,true);}

    private JSONObject startInternal(ProxyRuntimeProfile profile,Progress progress,boolean replaceRunning)throws Exception{
        StartupTrace trace=new StartupTrace();
        final long request=STOP_GENERATION.get();
        CONTROL_LOCK.lock();
        boolean ownIntent=START_INTENT.get()==null;
        boolean nativeStartSucceeded=false,adblockCoordinatorEntered=false;
        if(ownIntent)START_INTENT.set(new StartIntent(request,false,null));
        try{
            checkStartIntent();
            trace.next("runtimeProbe");
            StartIntent entryIntent=START_INTENT.get();
            boolean automaticEntry=entryIntent!=null&&entryIntent.automatic;
            // A user/app start after a clean stop (nothing wanted, nothing recorded running) does not
            // open a separate Root shell just to probe liveness and the installed core token: the
            // single start command compares the token itself, and hetu-root.sh start still cleans up
            // and stops any orphan core before it takes over the network.
            boolean mergedProbe=!replaceRunning&&!automaticEntry
                    &&!prefs.getBoolean("proxyRootRuntimeRunning",false)&&!prefs.getBoolean("proxyRootWanted",false);
            RootStartupProbe.Result initial=mergedProbe?RootStartupProbe.parse(false,""):probeStartupRuntime();
            checkStartIntent();
            if(initial.process==ProxyContinuity.ProcessState.DEAD){
                // Clear stale health only on confirmed absence, never on a probe
                // timeout, and do not revoke the user's boot recovery intent.
                prefs.edit().putBoolean("proxyRootRuntimeRunning",false)
                        .putString("proxyNetworkIntegrity","stopped")
                        .putString("proxyNetworkFault","not-running")
                        .putString("proxyPolicyEgressState","unverified")
                        .remove("proxyRootBootRestoreSuccessAt").apply();
            }
            boolean existingRunning=ProxyContinuity.preserveRunning(initial.process,
                    prefs.getBoolean("proxyRootRuntimeRunning",false)&&prefs.getBoolean("proxyRootWanted",false));
            if(existingRunning&&!replaceRunning){
                JSONObject health=networkHealth();
                if(initial.process!=ProxyContinuity.ProcessState.ALIVE||!"healthy".equals(health.optString("networkIntegrity"))
                        ||!health.optBoolean("dataPlaneHealthy",false))
                    throw new IOException("核心存活但网络接管未通过完整验证，已保留现场");
                publishStartIntent(()->prefs.edit().putBoolean("proxyRootWanted",true).putBoolean("proxyRootRuntimeRunning",true).apply());
                ensureContinuityService(true);
                trace.outcome="alreadyRunning";
                int appliedRevision=prefs.getInt(ProxyRuntimeSettings.APPLIED_RUNTIME_REVISION_KEY,0);
                String liveMessage="Root 代理已在运行，已忽略重复启动请求";
                if(appliedRevision>0&&appliedRevision<ProxyRuntimeSettings.RUNTIME_REVISION)
                    liveMessage+="；当前网络仍使用旧运行版，请使用「重启」应用 fake-IP 路由修复";
                JSONObject unchanged=new JSONObject().put("ok",true).put("running",true).put("alreadyRunning",true)
                        .put("message",liveMessage);
                if(prefs.getBoolean("proxyRootAutoStart",false)&&!prefs.getBoolean("proxyRootAutoStartRevoked",false)
                        &&!prefs.getBoolean("proxyRootAutoStartInstalled",false)){
                    try{setAutoStart(true);}catch(Exception error){unchanged.put("warning","开机脚本未安装："+error.getMessage());}
                }
                return unchanged;
            }
            if(replaceRunning) {
                // Preserve the user's intent before any new preparation. The shell start transaction
                // validates the new config before it cleans up the old core/network rules.
                publishStartIntent(()->prefs.edit().putBoolean("proxyRootWanted",true).apply());
                stage(progress,"校验新版运行环境，确认可替换后再切换旧核心…");
            } else {
                stage(progress,"检查配置、应用范围与绕过策略…");
            }
        int restartControllerPort=(replaceRunning&&existingRunning)
                ?prefs.getInt("proxyControllerPort",MihomoStartupConfig.CONTROLLER_PORT):0;
        // Capture the request before deployment and any fallback. Changes made
        // during the transaction remain pending; fallback health is reported
        // separately through the effective/error fields, not a restart loop.
        String requestedSettingsSignature=ProxyRuntimeSettings.captureRequest(profile,prefs);
        trace.next("configuration");
        Prepared p;
        try{
            p=prepare(profile,restartControllerPort);
        }catch(Exception prepareFailure){
            if(!profile.adblockChain||!adblockPreparationFailure(prepareFailure))throw prepareFailure;
            rememberAdblockFallback(prepareFailure);
            stage(progress,"广告串联与当前 YAML 结构不兼容，先保留原配置启动代理…");
            profile=withoutAdblock(profile);
            p=prepare(profile,restartControllerPort);
        }
        RootProxyPolicy policy=p.policy;
        // A core that cannot run the adblock chain (Hysteria) starts without it, explicitly.
        if(profile.adblockChain&&!p.profile.adblockChain)profile=p.profile;
        String conversionWarnings=ProxyCoreConfig.kind(profile.core)==ProxyCoreConfig.Kind.MIHOMO?"":prefs.getString("proxyCoreConversionWarnings","");
        String validationKey=validationFingerprint(p);
        boolean validationKnown=validationKey.equals(prefs.getString("proxyRootValidatedFingerprint",""));
        boolean preserveLiveNeedsValidation=replaceRunning&&existingRunning&&!validationKnown;
        // Deployment rides on the native start command (one Root invocation) unless a live
        // replacement must validate the new files first, or this is an automatic recovery start.
        boolean mergedDeploy=!preserveLiveNeedsValidation&&!automaticEntry;
        // The probe (when one ran) also read the installed core token; null lets the start
        // command compare it itself.
        String probedCoreToken=mergedProbe?null:initial.installedCoreToken;
        stage(progress,"部署必要运行文件…");
        trace.next("deployment");
        checkStartIntent();
        if(!mergedDeploy){
            installRuntimeFiles(p,true,probedCoreToken);
            checkStartIntent();
        }

        trace.next("validation");
        if(preserveLiveNeedsValidation){
            stage(progress,"配置有变化，先校验后再替换当前核心…");
            try{
                validateRuntimeConfig(profile.core);
                prefs.edit().putString("proxyRootValidatedFingerprint",validationKey).apply();
                if(profile.adblockChain)prefs.edit().remove("proxyAdblockLastError").apply();
            }catch(Exception fullFailure){
                if(!profile.adblockChain)throw fullFailure;
                stage(progress,"广告串联校验失败，尝试保留原 YAML 重新校验…");
                ProxyRuntimeProfile fallbackProfile=withoutAdblock(profile);
                Prepared fallback=prepare(fallbackProfile,restartControllerPort);
                installRuntimeFiles(fallback,true);
                String fallbackValidationKey=validationFingerprint(fallback);
                try{validateRuntimeConfig(fallbackProfile.core);}
                catch(Exception fallbackFailure){
                    throw new IOException((fullFailure.getMessage()==null?"最终配置校验失败":fullFailure.getMessage())+"；关闭广告串联后仍失败："+(fallbackFailure.getMessage()==null?"未知错误":fallbackFailure.getMessage()),fullFailure);
                }
                prefs.edit().putString("proxyRootValidatedFingerprint",fallbackValidationKey).apply();
                rememberAdblockFallback(fullFailure);
                profile=fallbackProfile;
                p=fallback;
                policy=p.policy;
                validationKey=fallbackValidationKey;
                stage(progress,"代理配置可用；本次仅关闭串联广告过滤继续启动…");
            }
        }else if(validationKnown){
            stage(progress,"配置未变化，使用已验证快启动路径…");
        }else{
            stage(progress,"首次启动由核心直接校验，监听就绪后再接管网络…");
        }

        trace.next("legacyCleanup");
        if(!prefs.getBoolean("hetuLegacyRetired",false))quiesceLegacyRuntime(progress);
        String capabilityKey=capabilityFingerprint(profile,policy,p.fakeIps);
        boolean capabilityKnown=capabilityKey.equals(prefs.getString("proxyRootCapabilityFingerprint",""));
        stage(progress,capabilityKnown?"设备能力未变化，跳过重复探测…":"检查网络能力并启动核心…");

        trace.next("filterHandoff");
        boolean independentFallback=prefs.getBoolean("proxyAdblockFallbackEnabled",false);
        if(profile.adblockChain||independentFallback||DnsVpnService.running){
            stage(progress,profile.adblockChain?"切换到代理串联去广告，暂停独立 DNS / hosts 过滤…":"暂停独立广告过滤，避免与 Root 代理并行…");
            ProxyAdblockCoordinator.enter(context);adblockCoordinatorEntered=true;
        }
        stage(progress,"启动核心并等待订阅、规则与监听就绪（首次可能较慢）…");
        synchronized(ProxyAdblockSession.LOCK){
        prefs.edit()
                .putBoolean("proxyAdblockCounterArmed",false)
                .putLong("proxyAdblockSessionGeneration",prefs.getLong("proxyAdblockSessionGeneration",0L)+1L)
                .putLong("proxyAdblockSessionHits",0L)
                .putLong("proxyAdblockLogOffset",0L)
                .putLong("proxyAdblockLastHitAt",0L)
                .remove("proxyAdblockLastDomain")
                .remove("proxyAdblockRecentDomains")
                .remove("proxyAdblockPendingLogLine")
                .remove("proxyAdblockDiscardLogLine")
                .apply();
        }
        trace.next("coreAndNetwork");
        String runtimeDns=prefs.getBoolean("proxyMihomoDnsForward",true)?profile.dnsHijack.id:"off";
        String dnsTcp=bit(prefs.getBoolean("proxyDnsHijackTcp",true));
        String dnsUdp=bit(prefs.getBoolean("proxyDnsHijackUdp",true));
        String perf=bit(prefs.getBoolean("proxyPerformanceMode",false));
        String cpu=prefs.getBoolean("proxyCpuAffinityEnabled",false)?prefs.getString("proxyCpuAffinity","0-7"):"";
        String mem=prefs.getBoolean("proxyMemoryLimitEnabled",false)?prefs.getString("proxyMemoryLimit","100M"):"";
        String ioWeight=prefs.getBoolean("proxyIoWeightEnabled",false)?prefs.getString("proxyIoWeight","4"):"";
        String vendorClean=bit(prefs.getBoolean("proxyVendorFirewallCleanup",false));
        JSONObject result;
        String[] bootArgs=new String[]{
                BIN,CONFIG,profile.mode.id,String.valueOf(p.tproxyPort),String.valueOf(p.redirectPort),profile.ipv6.id,
                bit(profile.tcp),bit(profile.udp),runtimeDns,bit(profile.quicBlocked),
                String.valueOf(MihomoStartupConfig.DNS_PORT),String.valueOf(p.controllerPort),
                policy.appScope,policy.uidRanges,bit(policy.sharedNetwork),bit(policy.killSwitch),policy.cidrs,policy.interfaces,policy.directUidRanges,"1",bit(capabilityKnown),policy.directGidRanges,policy.sharedBypassMacs,
                dnsTcp,dnsUdp,perf,cpu,mem,ioWeight,vendorClean};
        String prelude=null;
        if(mergedDeploy){
            StringBuilder inline=new StringBuilder();
            if(Boolean.TRUE.equals(PRE_START_HOOK.get()))
                inline.append(StartPrelude.preStartHook(profile.mode.id,p.source==null?"":p.source.name));
            inline.append(inlineDeployment(p,true,probedCoreToken));
            prelude=inline.toString();
        }
        checkStartIntent();
        try{result=runJsonWithTimeout(145000L,prelude,prependStart(bootArgs));
            if(!result.optBoolean("ok"))throw new IOException(result.optString("message","Root 代理启动失败"));
            nativeStartSucceeded=true;automaticStartCompleted();
        }catch(Exception startFailure){throw startFailure;}

        // hetu-root.sh does not return success until the private core is alive,
        // every required listener is present, network rules are installed, a
        // health manifest is recorded and the watchdog has started. Re-spawning
        // su here only to probe the same PID again adds visible start latency on
        // Magisk/KernelSU devices without closing a meaningful race.
        checkStartIntent();
        trace.next("publishRuntime");
        stage(progress,"核心监听与网络接管已就绪，守护将持续复核运行状态…");
        if(!prefs.getBoolean("hetuLegacyRetired",false))retireLegacyInstallation(progress);
        // Publish only the port of a successfully running core.
        prefs.edit().putInt("proxyControllerPort",p.controllerPort).commit();
        // The shell transaction already verified the controller TCP listener. Do not block
        // the Start/Restart button on another synchronous HTTP readiness loop; the normal
        // dashboard refresh verifies the API asynchronously.
        prefs.edit().remove("proxyRootEgressWarning").apply();
        // Connectivity probing is intentionally asynchronous. A slow captive portal or
        // blocked 204 endpoint must never keep the Start button spinning after the core
        // and transparent routing are already healthy.
        prefs.edit()
                .putBoolean("proxyRootEgressPending",true)
                .putInt("proxyRootEgressProbeAttempts",0)
                .remove("proxyRootEgressProbeLastError")
                // What the running core answers from; the DNS takeover self-check compares with it.
                .putString("proxyRootFakeIpV4",p.fakeIps==null?"":p.fakeIps.ipv4)
                .apply();

        String warning=policy.warning();
        String adblockFallbackReason=prefs.getString("proxyAdblockLastError","");
        if(!profile.adblockChain&&adblockFallbackReason!=null&&!adblockFallbackReason.isEmpty()){
            warning=(warning.isEmpty()?"":warning+"；")+"代理已启动，但广告串联本次降级："+adblockFallbackReason;
        }
        String legacyCleanupWarning=prefs.getString("hetuLegacyCleanupWarning","");
        if(legacyCleanupWarning!=null&&!legacyCleanupWarning.isEmpty()){
            warning=(warning.isEmpty()?"":warning+"；")+legacyCleanupWarning;
        }
        if(conversionWarnings!=null&&!conversionWarnings.isEmpty()){
            String shown=conversionWarnings.length()>900?conversionWarnings.substring(0,900)+"…":conversionWarnings;
            warning=(warning.isEmpty()?"":warning+"；")+profile.core.label+" 配置："+shown;
        }
        result.put("sourceConfig",p.source.name)
                .put("core",profile.core.id)
                .put("mode",profile.mode.id)
                .put("dnsHijack",profile.dnsHijack.id)
                .put("tcp",profile.tcp)
                .put("udp",profile.udp)
                .put("quicBlocked",profile.quicBlocked)
                .put("ipv6",profile.ipv6.id)
                .put("appScope",policy.appScope)
                .put("uidRanges",policy.uidRanges)
                .put("directUidRanges",policy.directUidRanges)
                .put("directGidRanges",policy.directGidRanges)
                .put("directPackageCount",policy.directPackages.size())
                .put("sharedNetwork",policy.sharedNetwork)
                .put("sharedBypassMacs",policy.sharedBypassMacs)
                .put("killSwitch",policy.killSwitch)
                .put("cnIpDirect",profile.cnIpDirect)
                .put("adblockChain",profile.adblockChain)
                .put("adblockRuleCount",p.adblock==null?0:p.adblock.count)
                .put("controllerPort",p.controllerPort)
                .put("adblockRevision",p.adblock==null?"":p.adblock.revision)
                .put("bypassCidrs",policy.cidrs)
                .put("fakeIpV4",p.fakeIps.ipv4)
                .put("fakeIpV6",p.fakeIps.ipv6)
                .put("bypassInterfaces",policy.interfaces);
        if(!warning.isEmpty())result.put("warning",warning);
        checkStartIntent();
        synchronized(INTENT_LOCK){
        StartIntent publishingIntent=START_INTENT.get();
        if(publishingIntent!=null&&publishingIntent.generation!=STOP_GENERATION.get())throw new IOException("启动意图已撤销");
        synchronized(ProxyAdblockSession.LOCK){
        prefs.edit()
                .putBoolean("proxyRootWanted",true)
                .putBoolean("proxyRootRuntimeRunning",true)
                // Which core answers on this session's ports: the panel, latency tests and the
                // adblock/hot-reload paths read their per-core capability from it.
                .putString("proxyRootRuntimeCore",profile.core.id)
                .putBoolean("proxyAdblockCounterArmed",profile.adblockChain)
                .putInt("proxyAutoDirectPackageCount",policy.directPackages.size())
                .putBoolean("proxyAdblockLastEffective",profile.adblockChain)
                .putInt("proxyAdblockLastRuleCount",p.adblock==null?0:p.adblock.count)
                .putString("proxyAdblockLastRevision",p.adblock==null?"":p.adblock.revision)
                .putString("proxyRootTopologyFingerprint",topologyFingerprint(profile,policy,p.fakeIps))
                // A completed network transaction consumes its captured request.
                // Runtime fallbacks (for example adblock-chain degradation) are reported through
                // their own effective/error fields; they must not create an endless "restart again"
                // loop by leaving the generic settings signature permanently different.
                .putString("proxyRootAppliedSettings",requestedSettingsSignature)
                .putInt(ProxyRuntimeSettings.APPLIED_RUNTIME_REVISION_KEY,ProxyRuntimeSettings.RUNTIME_REVISION)
                .remove(ProxyRuntimeSettings.DIRTY_KEY)
                .putString("proxyRootEffectiveIpv6",profile.ipv6.id)
                .putLong("proxyRootHealthProbeElapsed",0L)
                .putString("proxyRootValidatedFingerprint",validationKey)
                .putString("proxyRootCapabilityFingerprint",capabilityKey)
                .remove("proxyRootRuntimeRefreshPending")
                .remove("proxyRootRuntimeRefreshPendingAt")
                .remove("proxyRootBootError")
                .apply();
        }
        }
        checkStartIntent();
        String bootWarning=refreshAutostart(p,bootArgs);
        checkStartIntent();
        if(!bootWarning.isEmpty())result.put("warning",(result.optString("warning","").isEmpty()?"":result.optString("warning")+"；")+bootWarning);
        ensureContinuityService(true);
        trace.outcome="ready";
        return result;
        }catch(Exception failure){
            try{recoverFailedHandoff(nativeStartSucceeded,START_INTENT.get()!=null&&START_INTENT.get().nativeDispatched,adblockCoordinatorEntered);}
            catch(Exception cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }finally{
            try{
                // A repeated Start/boot request is not a new startup; keep the
                // useful timing of the last actual transaction for diagnostics.
                if(!"alreadyRunning".equals(trace.outcome))prefs.edit()
                        .putString("proxyRootLastStartupTiming",trace.finish())
                        .putLong("proxyRootLastStartupAt",System.currentTimeMillis()).apply();
            }finally{if(ownIntent)START_INTENT.remove();CONTROL_LOCK.unlock();}
        }
    }

    private void recoverFailedHandoff(boolean nativeSucceeded,boolean nativeAttempted,boolean coordinatorEntered)throws Exception{
        if(nativeSucceeded){
            rollbackUnpublishedStart();
            if(coordinatorEntered)ProxyAdblockCoordinator.exit(context);
        }else if(coordinatorEntered){
            if(!nativeAttempted)ProxyAdblockCoordinator.exit(context);
            else if(probeStartupRuntime().process==ProxyContinuity.ProcessState.DEAD){
                rollbackUnpublishedStart();
                ProxyAdblockCoordinator.exit(context);
            }else{
                prefs.edit().putString("proxyAutoRecoveryError","启动结果未确认，已保留代理与过滤接管现场；请检查后停止")
                        .putString("proxyNetworkIntegrity","unknown").apply();
            }
        }
    }

    private void rollbackUnpublishedStart()throws Exception{
        // Still owns CONTROL_LOCK: a newer start cannot be destroyed by this cleanup.
        Long previousStop=STOP_DEADLINE.get();
        Long automatic=AUTOMATIC_DEADLINE.get();
        long deadline=SystemClock.elapsedRealtime()+20000L;
        if(automatic!=null)deadline=Math.min(deadline,automatic);
        STOP_DEADLINE.set(deadline);
        try{
            JSONObject result=runJsonAllowMissing("stop",new JSONObject().put("ok",false)
                    .put("message","启动结果已撤销，但停止脚本缺失"));
            if(!result.optBoolean("ok",false)||result.optBoolean("running",false))
                throw new IOException(result.optString("message","撤销后的实体清理未确认"));
            prefs.edit().putBoolean("proxyRootRuntimeRunning",false).putString("proxyNetworkIntegrity","stopped")
                    .putString("proxyNetworkFault","start-revoked").apply();
        }catch(Exception cleanup){
            // A failed cleanup is unknown/running, never silently idle.
            prefs.edit().putBoolean("proxyRootRuntimeRunning",true).putString("proxyNetworkIntegrity","unknown")
                    .putString("proxyNetworkFault","revoked-start-cleanup-unconfirmed")
                    .putString("proxyAutoRecoveryError","启动已撤销，但运行现场尚未清理完成，请检查后停止").apply();
            throw cleanup;
        }finally{if(previousStop==null)STOP_DEADLINE.remove();else STOP_DEADLINE.set(previousStop);}
    }

    private static String[] prependStart(String[] args){
        String[] out=new String[args.length+1];out[0]="start";System.arraycopy(args,0,out,1,args.length);return out;
    }

    private String[] autostartArgs(Prepared p){
        ProxyRuntimeProfile profile=p.profile;RootProxyPolicy policy=p.policy;
        return new String[]{BIN,CONFIG,profile.mode.id,String.valueOf(p.tproxyPort),String.valueOf(p.redirectPort),profile.ipv6.id,
                bit(profile.tcp),bit(profile.udp),prefs.getBoolean("proxyMihomoDnsForward",true)?profile.dnsHijack.id:"off",bit(profile.quicBlocked),
                String.valueOf(MihomoStartupConfig.DNS_PORT),String.valueOf(p.controllerPort),policy.appScope,policy.uidRanges,
                bit(policy.sharedNetwork),bit(policy.killSwitch),policy.cidrs,policy.interfaces,policy.directUidRanges,"0","0",policy.directGidRanges,policy.sharedBypassMacs,
                bit(prefs.getBoolean("proxyDnsHijackTcp",true)),bit(prefs.getBoolean("proxyDnsHijackUdp",true)),
                bit(prefs.getBoolean("proxyPerformanceMode",false)),prefs.getBoolean("proxyCpuAffinityEnabled",false)?prefs.getString("proxyCpuAffinity","0-7"):"",
                prefs.getBoolean("proxyMemoryLimitEnabled",false)?prefs.getString("proxyMemoryLimit","100M"):"",
                prefs.getBoolean("proxyIoWeightEnabled",false)?prefs.getString("proxyIoWeight","4"):"",bit(prefs.getBoolean("proxyVendorFirewallCleanup",false))};
    }

    private void installAutostart(Prepared p,String[] args)throws Exception{
        File stage=new File(context.getCacheDir(),"hetu-autostart");
        if(!stage.isDirectory()&&!stage.mkdirs())throw new IOException("无法准备开机脚本");
        File script=new File(stage,"hetu-root.sh");copyScriptAsset(script);
        RootAutostart.install(context,new File(CONFIG),args,script);
    }

    private String refreshAutostart(Prepared p,String[] args){
        if(!prefs.getBoolean("proxyRootAutoStart",false)||prefs.getBoolean("proxyRootAutoStartRevoked",false))return "";
        try{
            checkStartIntent();
            installAutostart(p,args);
            try{publishStartIntent(()->prefs.edit().putBoolean("proxyRootAutoStartInstalled",true).remove("proxyRootAutoStartError").apply());}
            catch(IOException revoked){RootAutostart.remove(context);throw revoked;}
            return "";
        }catch(Exception error){
            String detail=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
            prefs.edit().putBoolean("proxyRootAutoStartInstalled",false).putString("proxyRootAutoStartError",detail).apply();
            return "开机脚本未更新："+detail;
        }
    }

    boolean adoptBootRuntime()throws Exception{
        RootBridge.requireWorkerThread();
        final long request=STOP_GENERATION.get();
        CONTROL_LOCK.lock();
        try{
            if(!prefs.getBoolean("proxyRootAutoStart",false)||prefs.getBoolean("proxyRootAutoStartRevoked",false)
                    ||!RootAutostart.confirmedRunningThisBoot(context))return false;
            if(probeStartupRuntime().process!=ProxyContinuity.ProcessState.ALIVE)return false;
            JSONObject health=networkHealth();
            if(!"healthy".equals(health.optString("networkIntegrity"))||!health.optBoolean("dataPlaneHealthy",false))return false;
            synchronized(INTENT_LOCK){
                if(request!=STOP_GENERATION.get()||!prefs.getBoolean("proxyRootAutoStart",false)
                        ||prefs.getBoolean("proxyRootAutoStartRevoked",false))return false;
                prefs.edit().putBoolean("proxyRootWanted",true).putBoolean("proxyRootRuntimeRunning",true)
                        .putLong("proxyRootBootRestoreSuccessAt",System.currentTimeMillis()).remove("proxyRootBootError").apply();
            }
            return true;
        }finally{CONTROL_LOCK.unlock();}
    }

    String setAutoStart(boolean enabled)throws Exception{
        RootBridge.requireWorkerThread();
        final long request;
        synchronized(INTENT_LOCK){
            if(!enabled){
                STOP_GENERATION.incrementAndGet();CONTROL_LOCK.invalidateObservations();
                prefs.edit().putBoolean("proxyRootAutoStartRevoked",true).commit();
            }
            request=STOP_GENERATION.get();
        }
        CONTROL_LOCK.lock();
        try{
            if(request!=STOP_GENERATION.get())throw new IOException("自启设置请求已被后续停止取代");
            if(enabled){
                checkStartIntent();
                if(!RootBridge.hasRoot(context))throw new IOException("无法取得 Root 权限，请在 Root 管理器中授权河图");
                ProxyRuntimeProfile profile=ProxyRuntimeProfile.load(prefs);
                ProxyRuntimeProfile.Core bootCore=profile.core;
                if(bootCore==ProxyRuntimeProfile.Core.MIHOMO_SMART&&!cores.installed(bootCore))bootCore=ProxyRuntimeProfile.Core.MIHOMO;
                if(!runtimeCoreCurrent(expectedCoreToken(bootCore)))
                    throw new IOException("请先成功启动一次所选核心，再开启开机自启");
                Prepared p=prepare(profile,prefs.getInt("proxyControllerPort",MihomoStartupConfig.CONTROLLER_PORT));
                // Copy the last successfully deployed config. Enabling must not change a
                // live proxy or claim unvalidated settings are boot-ready.
                RootBridge.Result available=boundedRootShell(context,"test -x "+RootBridge.quote(SCRIPT)+" && test -s "+RootBridge.quote(CONFIG),4000L);
                if(!available.ok())throw new IOException("请先成功启动一次代理，再开启开机自启");
                if(!p.settingsSignature.equals(prefs.getString("proxyRootAppliedSettings","")))
                    throw new IOException("代理设置已变化，请先启动或重启应用设置，再开启开机自启");
                // A failed restart may have replaced CONFIG without publishing a
                // successful settings snapshot. Validate the actual deployed bytes.
                validateRuntimeConfig(bootCore);
                installAutostart(p,autostartArgs(p));
            }else RootAutostart.remove(context);
            synchronized(INTENT_LOCK){
                if(request!=STOP_GENERATION.get())throw new IOException("自启设置请求已被后续停止取代");
                if(!prefs.edit().putBoolean("proxyRootAutoStart",enabled).putBoolean("proxyRootAutoStartInstalled",enabled)
                        .putBoolean("proxyRootAutoStartRevoked",!enabled)
                        .remove("proxyRootAutoStartError").commit())throw new IOException("开机自启状态保存失败，请重试");
            }
            return enabled?"开机脚本已安装，重启后自动启动代理":"开机自启已关闭";
        }catch(Exception error){
            if(enabled&&request!=STOP_GENERATION.get()){
                try{RootAutostart.remove(context);}catch(Exception cleanup){error.addSuppressed(cleanup);}
            }
            prefs.edit().putString("proxyRootAutoStartError",error.getMessage()==null?error.getClass().getSimpleName():error.getMessage()).apply();
            throw error;
        }finally{CONTROL_LOCK.unlock();}
    }

    JSONObject stop()throws Exception{return stop(null);}
    JSONObject stop(Progress progress)throws Exception{
        RootBridge.requireWorkerThread();
        final long deadline=SystemClock.elapsedRealtime()+20000L;
        final boolean persisted;
        synchronized(INTENT_LOCK){
            STOP_GENERATION.incrementAndGet();CONTROL_LOCK.invalidateObservations();
            synchronized(ProxyAdblockSession.LOCK){
                persisted=prefs.edit().putBoolean("proxyRootWanted",false)
                        .putLong("proxyRootStoppedManualGeneration",prefs.getLong("proxyRootManualStartGeneration",0L))
                        .putBoolean("proxyAdblockCounterArmed",false)
                        .putLong("proxyAdblockSessionGeneration",prefs.getLong("proxyAdblockSessionGeneration",0L)+1L)
                        .remove("proxyAdblockPendingLogLine").remove("proxyAdblockDiscardLogLine").commit();
            }
        }
        RootAutostart.cancelCurrentBoot(context);
        if(!persisted)throw new IOException("停止意图未能持久保存；已撤销本次原生恢复，尚未确认清理完成");
        long waitUntil=SystemClock.elapsedRealtime()+2000L,wallUntil=System.nanoTime()+2000000000L;
        boolean acquired=false;
        while(!(acquired=CONTROL_LOCK.tryLock())&&SystemClock.elapsedRealtime()<waitUntil&&System.nanoTime()<wallUntil)Thread.sleep(10L);
        if(!acquired)throw new IOException("启动意图已撤销，仍等待进行中的事务退出；未确认停止成功");
        STOP_DEADLINE.set(deadline);
        try{
            stage(progress,"停止守护、Kill Switch、核心并回滚透明代理规则…");
            JSONObject r=runJsonAllowMissing("stop",new JSONObject().put("ok",false).put("running",prefs.getBoolean("proxyRootRuntimeRunning",false))
                    .put("state","unknown").put("message","停止脚本缺失，无法确认网络清理，已保留运行记录"));
            if(!r.optBoolean("ok",false)||r.optBoolean("running",false))
                throw new IOException(r.optString("message","停止清理未完成，已保留运行记录"));
            ProxyAdblockCoordinator.exit(context);
            stage(progress,"网络规则、广告过滤接管与临时 IPv6 状态已恢复");
            prefs.edit().putBoolean("proxyRootWanted",false).putBoolean("proxyRootRuntimeRunning",false)
                    .putBoolean("proxyAdblockCounterArmed",false).putLong("proxyRootHealthProbeElapsed",0L)
                    .remove("proxyRootSessionOwner").apply();
            ensureContinuityService(false);
            return r;
        }finally{STOP_DEADLINE.remove();CONTROL_LOCK.unlock();}
    }
    private int liveControllerPort(JSONObject state){
        int port=state.optInt("controllerPort",0);
        if(port>=29090&&port<=29149)return port;
        if(!state.optBoolean("running",false))return 0;
        // Recovery for old sessions: test.45-test.52 preflight could overwrite prefs
        // while the live Mihomo still listened on the old controller port. The deployed
        // Root startup config is authoritative because preflight does not replace CONFIG.
        try{
            RootBridge.Result result=boundedRootShell(context,"cat "+RootBridge.quote(CONFIG)+" 2>/dev/null || true",4000L);
            String output=result.output==null?"":result.output;
            int found=0;
            try(BufferedReader reader=new BufferedReader(new StringReader(output))){
                String line;
                while((line=reader.readLine())!=null){
                    String trimmed=line.trim();
                    String prefix="external-controller:";
                    if(!trimmed.startsWith(prefix))continue;
                    String endpoint=trimmed.substring(prefix.length()).trim();
                    String host="127.0.0.1:";
                    if(!endpoint.startsWith(host))continue;
                    try{
                        int candidate=Integer.parseInt(endpoint.substring(host.length()).trim());
                        if(candidate>=29090&&candidate<=29149)found=candidate;
                    }catch(NumberFormatException ignored){ }
                }
            }
            return found;
        }catch(Exception ignored){return 0;}
    }

    JSONObject networkHealth()throws Exception{
        return runHealthChecker("network-health");
    }

    JSONObject repairSessionRecord()throws Exception{
        RootBridge.requireWorkerThread();
        CONTROL_LOCK.lock();
        try{
            if(!RootBridge.hasRoot(context))throw new IOException("无法取得 Root 权限；运行记录未改动");
            JSONObject result=runHealthChecker("repair-session");
            JSONObject checked=networkHealth();
            prefs.edit().putString("proxyNetworkIntegrity",checked.optString("networkIntegrity","unknown"))
                    .putString("proxyNetworkFault",checked.optString("networkFault",""))
                    .putLong("proxyNetworkCheckedAt",System.currentTimeMillis()).apply();
            if(!checked.optBoolean("dataPlaneHealthy",false))result.put("message",result.optString("message")
                    +"；网络核验仍未通过："+checked.optString("networkFault","unknown"));
            return result;
        }finally{CONTROL_LOCK.unlock();}
    }

    private JSONObject runHealthChecker(String action)throws Exception{
        RootBridge.requireWorkerThread();
        HEALTH_CHECKER_LOCK.lock();
        try{
            // Update only the inspector, never the running controller/core or boot
            // entry. Merely opening a diagnostic does not migrate a session.
            File stage=new File(context.getNoBackupFilesDir(),"hetu-health-stage");
            if(!stage.isDirectory()&&!stage.mkdirs())throw new IOException("无法创建健康检查目录");
            File checker=new File(stage,"hetu-health-checker.sh");
            copyScriptAsset(checker);
            String digest=RuntimeCompatibility14.sha256(checker);
            String temporary=HEALTH_CHECKER+".new";
            String command="set -e; test -s "+RootBridge.quote(ROOT+"/run/session.state")
                    +"; if [ \"$(sha256sum "+RootBridge.quote(HEALTH_CHECKER)+" 2>/dev/null | cut -d ' ' -f 1)\" != "+RootBridge.quote(digest)+" ]; then"
                    +" cp "+RootBridge.quote(checker.getAbsolutePath())+" "+RootBridge.quote(temporary)
                    +"; chmod 700 "+RootBridge.quote(temporary)+"; chown 0:0 "+RootBridge.quote(temporary)
                    +"; test \"$(sha256sum "+RootBridge.quote(temporary)+" | cut -d ' ' -f 1)\" = "+RootBridge.quote(digest)
                    +"; mv -f "+RootBridge.quote(temporary)+" "+RootBridge.quote(HEALTH_CHECKER)+"; fi; exec "+RootBridge.quote(HEALTH_CHECKER)+" "+RootBridge.quote(action);
            RootBridge.Result reply=boundedRootShell(context,command,"repair-session".equals(action)?20000L:10000L);
            return RootCommandReply.read(reply.code,reply.output);
        }finally{HEALTH_CHECKER_LOCK.unlock();}
    }

    JSONObject status()throws Exception{
        final boolean controlOwned=CONTROL_LOCK.isHeldByCurrentThread();
        final long ticket=observationTicket();
        final String networkSession=prefs.getString("proxyNetworkSessionId","");
        final long networkEpoch=prefs.getLong("proxyNetworkEpoch",0L);
        JSONObject state=runJsonAllowMissing("status",new JSONObject().put("ok",true).put("running",false).put("state","idle").put("message","尚未启动"));
        if(state.optBoolean("running",false)){
            JSONObject health=networkHealth();
            for(String key:new String[]{"networkIntegrity","networkFault","sessionManifestState","baselineRepairAvailable","dataPlaneHealthy"})
                state.put(key,health.get(key));
        }
        int livePort=liveControllerPort(state);
        if(livePort>0)state.put("controllerPort",livePort);
        final boolean[] current={false};
        Runnable publish=()->{
            if(!Objects.equals(networkSession,prefs.getString("proxyNetworkSessionId",""))
                    ||networkEpoch!=prefs.getLong("proxyNetworkEpoch",0L))return;
            if(livePort>0&&prefs.getInt("proxyControllerPort",MihomoStartupConfig.CONTROLLER_PORT)!=livePort)
                prefs.edit().putInt("proxyControllerPort",livePort).apply();
            current[0]=true;
        };
        // Existing operation-owned reads stay inside their original lock. A
        // different thread's transaction never lends ownership to an observer.
        if(controlOwned&&CONTROL_LOCK.isHeldByCurrentThread())publish.run();
        else publishObservation(ticket,publish);
        if(!current[0])throw new IOException("运行状态读取已被新的操作替代，请重试");
        return state;
    }

    /** Called only from a foreground Activity lifecycle; reattach the observer after
     * process/package/OEM loss without replacing the core or changing wanted intent.
     * True acknowledges the service request, never proves that the service stayed alive. */
    boolean resumeContinuityFromForeground(boolean rootObserved,long ticket,String networkSession,long networkEpoch,
                                          boolean automationOnly){
        if(!rootObserved)return false;
        final boolean[] accepted={false};
        publishObservation(ticket,()->{
            if(automationOnly&&!prefs.getBoolean("networkMatchEnabled",false))return;
            if(!prefs.getBoolean("proxyRootWanted",false)&&!prefs.getBoolean("networkMatchEnabled",false))return;
            if(!Objects.equals(networkSession,prefs.getString("proxyNetworkSessionId",""))
                    ||networkEpoch!=prefs.getLong("proxyNetworkEpoch",0L))return;
          try{
            Intent intent=new Intent(context,ProxyNetworkMatchService.class);
            android.content.ComponentName requested=Build.VERSION.SDK_INT>=26
                    ?context.startForegroundService(intent):context.startService(intent);
            if(requested==null)throw new IllegalStateException("service-request-not-accepted");
            prefs.edit().putLong("proxyContinuityResumeRequestedAt",System.currentTimeMillis())
                    .remove("proxyContinuityResumeError").apply();
            accepted[0]=true;
          }catch(Exception error){
            prefs.edit().putLong("proxyContinuityResumeFailedAt",System.currentTimeMillis())
                    .putString("proxyContinuityResumeError",error.getClass().getSimpleName()).apply();
          }
        });
        return accepted[0];
    }

    private void ensureContinuityService(boolean running){
        try{
            Intent intent=new Intent(context,ProxyNetworkMatchService.class);
            if(running){
                if(Build.VERSION.SDK_INT>=26)context.startForegroundService(intent);else context.startService(intent);
            }else if(!prefs.getBoolean("networkMatchEnabled",false)){
                context.stopService(intent);
            }
        }catch(Exception ignored){}
    }

    String diagnostics(){
        RootBridge.requireWorkerThread();
        DiagnosticReport report=new DiagnosticReport(prefs.getString("proxyControllerSecret",""));
        report.section("上次应用闪退（本地记录）",AppCrashReport.read(context),16384);
        report.section("网络事件记录（本地，可独立于 Root 读取）",new ProxyNetworkJournal(context).report(prefs),65536);
        StringBuilder events=new StringBuilder("time=").append(new Date())
                .append("\napp=").append(BuildConfig.VERSION_NAME)
                .append("\nexpectedCore=").append(expectedCoreToken(ProxyRuntimeProfile.load(prefs).core));
        try{
            RootBridge.Result boot=boundedRootShell(context,"ls -l "+RootBridge.quote(RootAutostart.ENTRY)+" 2>/dev/null; cat "
                    +RootBridge.quote(RootAutostart.BASE+"/status")+" 2>/dev/null; tail -c 16384 "
                    +RootBridge.quote(RootAutostart.BASE+"/restore.log")+" 2>/dev/null; true",4000L);
            report.section("Root 开机脚本状态与恢复记录",boot.output,18000);
        }catch(Exception ignored){}
        Map<String,?> values=prefs.getAll();
        for(String key:new String[]{"proxyBaseCore","proxyBaseMode","proxyBaseIpv6","proxyRootWanted",
                "proxyRootRuntimeRunning","proxyRootRuntimeRefreshPending","proxyRootBootError",
                "proxyContinuityResumeRequestedAt","proxyContinuityResumeFailedAt","proxyContinuityResumeError",
                "proxyRootAppliedRuntimeRevision","proxyQuicBlocked","proxyAdblockChain","proxyAppScope","proxyDnsHijack",
                "proxyRootBootRestoreSuccessAt","proxyLastUnknownProcessProbeAt","proxyAutoRecoveryAttempt",
                "proxyAutoRecoverySuccess","proxyAutoRecoveryError","proxyLastNetworkSessionReset",
                "proxyLastNetworkSessionResetCount","proxyLastNetworkSessionResetReason","proxyLastNetworkObservationAt","proxyLastNetworkObservationReason","proxyNetworkSessionResetError",
                "proxyLastAutoStopAt","proxyLastAutoStopReason","proxyAdblockLastRevision","proxyAdblockLastError",
                "proxySelectorDisconnectOnSelect","proxyLastSelectionAt","proxyLastSelectionGroup",
                "proxyLastSelectionClosed","proxyLastSelectionCloseFailed",
                "proxyAdblockHotReloadAt","proxyAdblockLastHitAt","proxyRootEgressProbeLastError",
                "proxyNetworkIntegrity","proxyNetworkFault","proxyNetworkCheckedAt","proxyPolicyEgressState","proxyPolicyEgressCheckedAt",
                "proxyNetworkEpoch","proxyPhysicalNetworkState","proxyLastNetworkCallback","proxyLastNetworkCallbackAt",
                "proxyNetworkHealthTraceId","proxyNetworkHealthReadError","proxyPolicyEgressTraceId",
                "proxyNetworkRepairExit","proxyNetworkRepairTraceId","proxyNetworkJournalError","proxyNetworkJournalDropped","proxyNetworkSessionId"}){
            if(values.containsKey(key))events.append('\n').append(key).append('=').append(values.get(key));
        }
        report.section("版本与最近运行事件",events.toString(),6000);
        report.section("最近启动耗时（毫秒）", "time="+prefs.getLong("proxyRootLastStartupAt",0L)+"\n"
                +prefs.getString("proxyRootLastStartupTiming","尚无启动记录"),2000);
        try{
            ProxyConfigLibrary.Entry source=configs.selected(ProxyRuntimeProfile.load(prefs).core);
            if(source!=null){
                byte[] contents=configs.read(source).getBytes(StandardCharsets.UTF_8);
                report.section("源配置标识", "bytes="+contents.length+"\nsha256="
                        +hex(MessageDigest.getInstance("SHA-256").digest(contents)),1000);
            }
        }catch(Exception ignored){report.section("源配置标识","当前源配置不可读",1000);}
        try{
            String cmd=String.join("\n",
                    "id",
                    "echo '--- actual core token ---'; cat "+RootBridge.quote(CORE_TOKEN)+" 2>/dev/null; echo",
                    "echo '--- core version ---'; cat "+RootBridge.quote(CORE_KIND)+" 2>/dev/null; echo; "+RootBridge.quote(BIN)+" "+ProxyCoreConfig.versionArgs(ProxyRuntimeProfile.Core.from(prefs.getString("proxyRootRuntimeCore",ProxyRuntimeProfile.load(prefs).core.id)))+" 2>&1 | head -n 3",
                    "echo '--- shell startup stages (uptime seconds) ---'; tail -n 24 "+RootBridge.quote(ROOT+"/run/startup-timing")+" 2>/dev/null || true",
                    "echo '--- session ---'; cat "+RootBridge.quote(ROOT+"/run/session.state")+" 2>/dev/null || true",
                    "echo '--- transport config ---'; grep -E '^(disable-keep-alive|keep-alive-idle|keep-alive-interval|find-process-mode|mode|ipv6):' "+RootBridge.quote(CONFIG)+" 2>/dev/null || true",
                    "echo '--- WeChat processes ---'; ps -A -o UID,PID,NAME 2>/dev/null | grep -F 'com.tencent.mm' || true",
                    "echo '--- listeners ---'; (ss -lntup 2>/dev/null || netstat -lntup 2>/dev/null || true) | tail -n 24",
                    "echo '--- core TCP timers ---'; (ss -ntoep 2>/dev/null || true) | grep -E 'core|mihomo' | head -n 24",
                    "echo '--- IPv6 interfaces ---'; cat /proc/net/if_inet6 2>/dev/null || true",
                    "echo '--- IPv6 routes ---'; ip -6 route show table all 2>/dev/null | head -n 24",
                    "echo '--- policy ---'; ip rule show 2>/dev/null | tail -n 24; ip -6 rule show 2>/dev/null | tail -n 24",
                    "echo '--- Hetu chains ---'; iptables-save 2>/dev/null | grep -E 'HETU|BICHEN' | tail -n 70; ip6tables-save 2>/dev/null | grep -E 'HETU|BICHEN' | tail -n 70",
                    "true");
            RootBridge.Result r=boundedRootShell(context,cmd,12000L);
            report.section("Root 网络状态","exit="+r.code+"\n"+r.output,18000);
        }catch(Exception e){report.section("Root 网络状态",String.valueOf(e),1000);}
        try{
            String cmd="echo '--- IPv4 UDP 443 policy counters ---'; iptables -w 1 -t filter -nvxL HETU_QUICOUT 2>/dev/null; "
                    +"echo '--- IPv6 UDP 443 policy counters ---'; ip6tables -w 1 -t filter -nvxL HETU_QUICOUT 2>/dev/null; true";
            RootBridge.Result r=boundedRootShell(context,cmd,5000L);
            report.section("UDP 443 拦截计数（所有应用合计，不等同于微信命中）",r.output,4000);
        }catch(Exception e){report.section("UDP 443 拦截计数",String.valueOf(e),1000);}
        try{
            report.section("网络完整性（不等同于外部网站可达）",networkHealth().toString(),2500);
            RootBridge.Result repairs=boundedRootShell(context,"tail -n 30 "+RootBridge.quote(ROOT+"/run/network-repair.log")+" 2>/dev/null; true",3000L);
            report.section("网络原位修复记录",repairs.output,5000);
            RootBridge.Result sessionRepairs=boundedRootShell(context,"tail -n 20 "+RootBridge.quote(ROOT+"/run/session-repair.log")+" 2>/dev/null; true",3000L);
            report.section("运行记录兼容修复",sessionRepairs.output,3000);
            RootBridge.Result google=boundedRootShell(context,"cat "+RootBridge.quote(ROOT+"/run/google-firewall-status")
                    +" 2>/dev/null; cat "+RootBridge.quote(ROOT+"/run/google-firewall-detail")
                    +" 2>/dev/null; tail -n 30 "+RootBridge.quote(ROOT+"/run/google-firewall.log")+" 2>/dev/null; true",3000L);
            report.section("Google 服务防火墙修复（chains 为空 = 本机没有这些厂商链，开关不起作用）",google.output,5000);
        }catch(Exception error){report.section("网络完整性",String.valueOf(error),1000);}
        try{
            // Whether ordinary lookups reach the core: the session's own record, the rule counters
            // (root-owned port 53 packets are the system resolver), and one real lookup from this app.
            String dns="grep -E '^(DNS|DNS_PORT|CORE_GID|SYSTEM_DNS|DOT_GUARD|PRIVATE_DNS|DNS_PROTOS)=' "+RootBridge.quote(ROOT+"/run/session.state")
                    +" 2>/dev/null; echo '--- DNS takeover counters ---'; iptables -w 1 -t nat -nvxL HETU_DNSOUT 2>/dev/null; "
                    +"echo '--- resolver DoT guard ---'; iptables -w 1 -t filter -nvxL HETU_DOTOUT 2>/dev/null; "
                    +"echo '--- tuning ---'; cat "+RootBridge.quote(ROOT+"/run/tuning-status")+" 2>/dev/null; true";
            String probe=DnsTakeoverProbe.run(prefs.getString("proxyRootFakeIpV4",""),4000L).describe();
            report.section("DNS 接管（系统解析是否进入核心）",probe+"\n"+boundedRootShell(context,dns,5000L).output,6000);
        }catch(Exception error){report.section("DNS 接管",String.valueOf(error),1000);}
        try{
            int wechatUid=-1;
            try{wechatUid=context.getPackageManager().getApplicationInfo("com.tencent.mm",0).uid;}catch(Exception ignored){}
            org.json.JSONArray connections=new MihomoControllerClient(context).connections().optJSONArray("connections");
            StringBuilder matched=new StringBuilder("WeChat UID=").append(wechatUid).append('\n');
            int count=0,otherCount=0,googleCount=0;
            java.util.Set<Integer> googleUids=new java.util.HashSet<>();
            for(String pkg:new String[]{"com.google.android.gms","com.google.android.gsf","com.android.vending"}) {
                try { googleUids.add(context.getPackageManager().getApplicationInfo(pkg,0).uid); } catch(Exception ignored) {}
            }
            StringBuilder googleConnections=new StringBuilder(GoogleConnectionEvidence.LIMITATION).append('\n');
            StringBuilder other=new StringBuilder();
            for(int i=0;connections!=null&&i<connections.length();i++){
                JSONObject connection=connections.optJSONObject(i);
                JSONObject metadata=connection==null?null:connection.optJSONObject("metadata");
                if(metadata==null)continue;
                String destination=metadata.optString("host","").toLowerCase(java.util.Locale.ROOT);
                String process=metadata.optString("process","").toLowerCase(java.util.Locale.ROOT);
                boolean affected=destination.contains("github")||destination.contains("google")||destination.contains("gstatic")
                        ||destination.contains("telegram")||destination.contains("twitter")||destination.equals("x.com")
                        ||destination.endsWith(".x.com")||process.contains("telegram")||process.contains("twitter")||process.contains("chrome");
                boolean wechat=DiagnosticReport.isWechat(metadata.optString("process"),metadata.optString("host"),metadata.optInt("uid",-1),wechatUid);
                boolean googleConnection=GoogleConnectionEvidence.matches(metadata.optString("process"),metadata.optString("host"),metadata.optInt("uid",-1),googleUids);
                // Independent quotas keep busy browsers from hiding GMS/Play or WeChat evidence.
                if(wechat ? count>=25 : googleConnection ? googleCount>=25 : !affected||otherCount>=10)continue;
                StringBuilder destinationText=wechat?matched:googleConnection?googleConnections:other;
                int number=wechat?++count:googleConnection?++googleCount:++otherCount;
                destinationText.append("#").append(number).append(" process=").append(metadata.optString("process"))
                        .append(" uid=").append(metadata.optInt("uid",-1))
                        .append(" host=").append(metadata.optString("host"))
                        .append(" destination=").append(metadata.optString("destinationIP")).append(':').append(metadata.optString("destinationPort"))
                        .append(" network=").append(metadata.optString("network"))
                        .append(" start=").append(connection.optString("start"))
                        .append(" up=").append(connection.optLong("upload")).append(" down=").append(connection.optLong("download"))
                        .append(" rule=").append(connection.optString("rule")).append('/').append(connection.optString("rulePayload"))
                        .append(" chains=").append(connection.optJSONArray("chains")).append('\n');
            }
            if(count==0)matched.append("当前未识别到微信连接；这不代表微信未联网，可能走应用绕过、OEM推送或已断连。\n");
            report.section("微信连接（仅元数据）",matched.toString(),6000);
            if(googleCount==0)googleConnections.append("当前没有可识别的 Google/GMS/Play 连接；可能被应用绕过、已经断连或缺少核心进程元数据，认证状态仍未验证。\n");
            report.section("Google/GMS/Play 连接（认证状态未验证）",googleConnections.toString(),6000);
            if(otherCount>0)report.section("其他应用连接（仅元数据）",other.toString(),2500);
        }catch(Exception e){report.section("微信当前连接","Controller 读取失败："+e.getMessage(),1000);}
        try{
            String cmd="echo '--- recent core log ---'; tail -c 9000 "+RootBridge.quote(ROOT+"/run/core.log")
                    +" 2>/dev/null; echo; echo '--- last crash ---'; tail -c 1500 "+RootBridge.quote(ROOT+"/run/last-crash")+" 2>/dev/null; true";
            RootBridge.Result logs=boundedRootShell(context,cmd,5000L);
            report.section("最近核心日志",logs.output,10000);
        }catch(Exception e){report.section("最近核心日志",String.valueOf(e),1000);}
        try {
            ProxyRuntimeProfile profile=ProxyRuntimeProfile.load(prefs);
            ProxyConfigLibrary.Entry source=configs.selected(profile.core);
            if(source!=null) report.section("DNS/TLS/保活配置（源文件，只含安全标量）",RuntimeCompatibility14.safeConfigSummary(configs.read(source)),2500);
            String runtime14="awk 'BEGIN{dns=0} /^[^ #]/{dns=($0 ~ /^dns[ ]*:/)} "
                    +"/^(mode|ipv6|disable-keep-alive|keep-alive-idle|keep-alive-interval):/{print} "
                    +"dns && /^  (enable|ipv6|enhanced-mode|fake-ip-filter-mode|respect-rules|prefer-h3):/{print}' "+RootBridge.quote(CONFIG)+" 2>/dev/null; true";
            report.section("实际运行 DNS/保活标量",boundedRootShell(context,runtime14,4000L).output,2500);
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
        return report.toString();
    }

    String startupConfig()throws IOException{
        File f=startupFile();
        if(!f.isFile())throw new IOException("尚未生成启动配置");
        return new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8);
    }
    File startupFile(){return new File(context.getFilesDir(),"hetu/run/state/startup-config");}

    private void writeStartupCopy(String text)throws IOException{
        File target=startupFile(),dir=target.getParentFile();
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法创建启动状态目录");
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        if(target.isFile()&&target.length()==bytes.length&&Arrays.equals(Files.readAllBytes(target.toPath()),bytes))return;
        File tmp=new File(dir,"startup-config.new");
        try(FileOutputStream out=new FileOutputStream(tmp,false)){
            out.write(bytes);out.getFD().sync();
        }
        try{Files.move(tmp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(Exception e){if(!tmp.renameTo(target)){tmp.delete();throw new IOException("无法保存最终启动配置");}}
    }

    private void validateRuntimeConfig(ProxyRuntimeProfile.Core core)throws Exception{
        RootBridge.requireWorkerThread();
        if(ProxyCoreConfig.kind(core)!=ProxyCoreConfig.Kind.MIHOMO){
            validateWithCoreCli(CONFIG,core,"最终启动配置");
            return;
        }
        String cmd="mkdir -p "+RootBridge.quote(ROOT+"/run")+" && "+RootBridge.quote(BIN)+" -t -d "+RootBridge.quote(ROOT+"/run")+" -f "+RootBridge.quote(CONFIG);
        RootBridge.Result r=boundedRootShell(context,cmd,30000L);
        if(r.ok())return;
        String d=r.output==null?"":r.output.trim().replace('\n',' ');
        if(d.length()>600)d=d.substring(d.length()-600);
        throw new IOException("Mihomo 最终启动配置校验失败"+(d.isEmpty()?"":"："+d));
    }

    void validateConfigText(String text)throws Exception{
        RootBridge.requireWorkerThread();
        if(text==null||text.trim().isEmpty())throw new IOException("YAML 配置不能为空");
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>4*1024*1024)throw new IOException("YAML 配置超过 4 MiB");
        ProxyRuntimeProfile profile=ProxyRuntimeProfile.load(prefs);
        ensureRuntimeBase(profile.core);
        if(ProxyCoreConfig.kind(profile.core)!=ProxyCoreConfig.Kind.MIHOMO){
            validateNativeText(text,profile);
            return;
        }
        File stage=new File(context.getCacheDir(),"hetu-config-validator");
        if(!stage.isDirectory()&&!stage.mkdirs())throw new IOException("无法创建 YAML 校验缓存目录");
        File source=File.createTempFile("editor-", ".yaml", stage);
        try(FileOutputStream out=new FileOutputStream(source,false)){
            out.write(bytes);out.getFD().sync();
        }
        String token=Long.toHexString(System.nanoTime());
        String rootFile=ROOT+"/run/state/editor-validation-"+token+".yaml";
        String workDir=ROOT+"/run/validate-"+token;
        String cmd="set +e; mkdir -p "+RootBridge.quote(ROOT+"/run/state")+" "+RootBridge.quote(workDir)
                +"; cp "+RootBridge.quote(source.getAbsolutePath())+" "+RootBridge.quote(rootFile)
                +"; copy_rc=$?; if [ \"$copy_rc\" -ne 0 ]; then rm -f "+RootBridge.quote(rootFile)
                +"; rm -rf "+RootBridge.quote(workDir)+"; exit \"$copy_rc\"; fi"
                +"; chmod 600 "+RootBridge.quote(rootFile)
                +"; "+RootBridge.quote(BIN)+" -t -d "+RootBridge.quote(workDir)+" -f "+RootBridge.quote(rootFile)
                +"; rc=$?; rm -f "+RootBridge.quote(rootFile)+"; rm -rf "+RootBridge.quote(workDir)+"; exit \"$rc\"";
        try{
            RootBridge.Result result=boundedRootShell(context,cmd,30000L);
            if(result.ok())return;
            String detail=result.output==null?"":result.output.trim();
            if(detail.length()>1200)detail=detail.substring(detail.length()-1200);
            throw new IOException("YAML 校验失败"+(detail.isEmpty()?"":"："+detail));
        }finally{
            source.delete();
        }
    }


    /**
     * 配置选择 / editor check for sing-box, Xray, V2Fly and Hysteria: a native file is checked
     * as written (plus Hetu's ingress); a Clash/Mihomo YAML is converted first, so the check
     * answers "does this run under the selected core".
     */
    private void validateNativeText(String text,ProxyRuntimeProfile profile)throws Exception{
        ProxyCoreConfig.Options options=nativeOptions(profile,prefs.getInt("proxyControllerPort",MihomoStartupConfig.CONTROLLER_PORT),null);
        options.hostResolver=null;
        ProxyCoreConfig.Result built=ProxyCoreConfig.build(profile.core,text,options,this::fetchProvider);
        File stage=new File(context.getCacheDir(),"hetu-config-validator");
        if(!stage.isDirectory()&&!stage.mkdirs())throw new IOException("无法创建配置校验缓存目录");
        File source=File.createTempFile("editor-",".cfg",stage);
        String token=Long.toHexString(System.nanoTime());
        String rootFile=ROOT+"/run/state/editor-validation-"+token;
        try{
            try(FileOutputStream out=new FileOutputStream(source,false)){out.write(built.config.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
            RootBridge.Result copied=boundedRootShell(context,"set -e; mkdir -p "+RootBridge.quote(ROOT+"/run/state")+"; cp "+RootBridge.quote(source.getAbsolutePath())
                    +" "+RootBridge.quote(rootFile)+"; chmod 600 "+RootBridge.quote(rootFile),10000L);
            if(!copied.ok())throw new IOException("无法写入校验文件："+copied.output.trim());
            validateWithCoreCli(rootFile,profile.core,"配置");
        }finally{
            source.delete();
            boundedRootShell(context,"rm -f "+RootBridge.quote(rootFile),3000L);
        }
    }

    /** `hetu-root.sh validate FILE KIND`: sing-box check, xray run -test, v2ray test, Hysteria YAML. */
    private void validateWithCoreCli(String file,ProxyRuntimeProfile.Core core,String what)throws Exception{
        String cmd="exec "+RootBridge.quote(SCRIPT)+" validate "+RootBridge.quote(file)+" "+RootBridge.quote(ProxyCoreConfig.kind(core).id);
        RootBridge.Result r=boundedRootShell(context,cmd,45000L);
        JSONObject reply=RootCommandReply.read(r.code,r.output);
        if(!reply.optBoolean("ok",false))throw new IOException(core.label+" "+what+"校验失败："+reply.optString("message",compactRootError(r.output)));
    }

    private JSONObject runJsonAllowMissing(String action,JSONObject missing)throws Exception{
        RootBridge.requireWorkerThread();
        String cmd="if [ -x "+RootBridge.quote(SCRIPT)+" ]; then exec "+RootBridge.quote(SCRIPT)+" "+RootBridge.quote(action)+"; else printf '%s\\n' "+RootBridge.quote(missing.toString())+"; fi";
        long timeout="status".equals(action)?8000L:20000L;
        RootBridge.Result r=boundedRootShell(context,cmd,timeout);
        return RootCommandReply.read(r.code,r.output);
    }

    private static String compactRootError(String raw){
        if(raw==null||raw.trim().isEmpty())return "Root 命令没有返回可读信息";
        String text=raw.trim();
        int newline=text.indexOf('\n');
        if(text.startsWith("{")&&newline>0)text=text.substring(newline+1).trim();
        text=text.replace('\n',' ');
        return text.length()>220?text.substring(0,220)+"…":text;
    }

    private JSONObject runJson(String...args)throws Exception{return runJsonWithTimeout(55000L,args);}
    private JSONObject runJsonWithTimeout(long timeoutMs,String...args)throws Exception{return runJsonWithTimeout(timeoutMs,null,args);}
    /**
     * prelude (start only): shell run inside the same Root invocation before hetu-root.sh start.
     * The cancellation receipt is then read in that shell first, before the hook and deployment,
     * so a Stop during either still cancels natively. The prelude ends in a StartPrelude refusal
     * line instead of JSON when it declines to dispatch the native transaction.
     */
    private JSONObject runJsonWithTimeout(long timeoutMs,String prelude,String...args)throws Exception{
        RootBridge.requireWorkerThread();
        boolean starting=args.length>0&&"start".equals(args[0]);
        boolean automaticStart=starting&&START_INTENT.get()!=null&&START_INTENT.get().automatic;
        StringBuilder cmd=new StringBuilder();
        if(starting&&prelude!=null){
            // Final Java intent check before the single privileged dispatch; the shell then reads
            // the receipt as its very first step. A Stop racing in between is still caught by
            // the post-native checkStartIntent in startInternal and rolled back exactly once.
            checkStartIntent();
            cmd.append(StartPrelude.cancelReceipt(ROOT+"/run/start-cancel-generation"));
        }else if(starting){
            checkStartIntent();
            String marker=RootBridge.quote(ROOT+"/run/start-cancel-generation");
            RootBridge.Result capture=boundedRootShell(context,"if [ -e "+marker+" ]; then cat "+marker+"; else printf ''; fi",3000L);
            String token=capture.output==null?"":capture.output.trim();
            if(!capture.ok()||token.length()>64||!token.isEmpty()&&!token.matches("[0-9]+-[0-9]+"))
                throw new IOException("无法确认启动撤销代次，已拒绝启动");
            // Token is captured before the final Java intent check. Native must
            // compare this exact token after joining its own transaction lock.
            checkStartIntent();
            cmd.append("export HETU_START_CANCEL_TOKEN=").append(RootBridge.quote(token)).append("; ");
        }
        if(automaticStart){
            // Capture boot identity in this command before joining the native transaction lock.
            cmd.append("b=$(cat /proc/sys/kernel/random/boot_id) || exit 1; [ -n \"$b\" ] || exit 1; export HETU_AUTOMATIC_RECOVERY_ID=\"$b\"; ");
            Long deadline=AUTOMATIC_DEADLINE.get();
            if(deadline==null||deadline-SystemClock.elapsedRealtime()<timeoutMs+20000L)
                throw new IOException("恢复剩余时间不足以完成启动及回滚");
        }
        checkStartIntent();
        if(starting){
            if(prelude!=null)cmd.append(prelude);
            // The App answers what `settings get global private_dns_mode` would (a Java process
            // on the device, 0.3-0.8 s); boot/autostart starts without it still ask settings.
            cmd.append("export HETU_PRIVATE_DNS_MODE=").append(RootBridge.quote(privateDnsMode())).append("; ");
            // Vendor GMS firewall cleanup: app IDs from PackageManager instead of pm/cmd package.
            if(prefs.getBoolean("proxyVendorFirewallCleanup",false))
                cmd.append("export HETU_GMS_APPIDS=").append(RootBridge.quote(gmsAppIds())).append("; ");
        }
        cmd.append("exec ").append(RootBridge.quote(SCRIPT));
        for(String a:args)cmd.append(' ').append(RootBridge.quote(a==null?"":a));
        if(args.length>0&&"start".equals(args[0])&&START_INTENT.get()!=null)START_INTENT.get().nativeDispatched=true;
        RootBridge.Result r=automaticStart?RootBridge.rootShell(context,cmd.toString(),timeoutMs):boundedRootShell(context,cmd.toString(),timeoutMs);
        if(starting&&prelude!=null){
            String refused=StartPrelude.refusal(r.output);
            if(refused!=null){
                // Nothing native ran: the hook, receipt or deployment stopped before exec.
                if(START_INTENT.get()!=null)START_INTENT.get().nativeDispatched=false;
                throw new IOException(refused);
            }
        }
        return RootCommandReply.read(r.code,r.output);
    }

    private String privateDnsMode(){
        String mode=null;
        try{mode=android.provider.Settings.Global.getString(context.getContentResolver(),"private_dns_mode");}
        catch(RuntimeException ignored){}
        return StartPrelude.privateDnsMode(mode);
    }

    /** App IDs (uid % 100000) of GMS, Play Store and GSF; "0" when none is installed. */
    private String gmsAppIds(){
        StringBuilder out=new StringBuilder();
        android.content.pm.PackageManager pm=context.getPackageManager();
        for(String name:new String[]{"com.google.android.gms","com.android.vending","com.google.android.gsf"}){
            try{
                int app=pm.getApplicationInfo(name,0).uid%100000;
                if(app>=10000&&app<=19999)out.append(out.length()==0?"":",").append(app);
            }catch(Exception ignored){}
        }
        return out.length()==0?"0":out.toString();
    }

    /** installRuntimeFiles' commands, run by `sh -c` inside the start invocation (set -e intact). */
    private String inlineDeployment(Prepared p,boolean includeConfig,String probedCoreToken)throws Exception{
        return StartPrelude.deployment(deploymentCommand(p,includeConfig,probedCoreToken));
    }

    private void quiesceLegacyRuntime(Progress progress){
        try{
            RootBridge.requireWorkerThread();
            String legacyBin=LEGACY_ROOT+"/bin/core";
            String legacyWatchdog=LEGACY_ROOT+"/run/watchdog.pid";
            String cmd="set +e; legacy=0; "
                    +"if [ -d "+RootBridge.quote(LEGACY_ROOT)+" ]; then legacy=1; "
                    +"if [ -f "+RootBridge.quote(legacyWatchdog)+" ]; then w=$(cat "+RootBridge.quote(legacyWatchdog)+" 2>/dev/null); case \"$w\" in ''|*[!0-9]*) ;; *) kill \"$w\" >/dev/null 2>&1 || true;; esac; fi; "
                    +"for p in /proc/[0-9]*; do [ -r \"$p/cmdline\" ] || continue; pid=$(basename \"$p\"); cmdline=$(tr '\\000' ' ' < \"$p/cmdline\" 2>/dev/null); exe=$(readlink \"$p/exe\" 2>/dev/null); "
                    +"case \"$cmdline $exe\" in *"+RootBridge.quote(legacyBin)+"*) kill \"$pid\" >/dev/null 2>&1 || true;; esac; done; "
                    +"rm -f "+RootBridge.quote(LEGACY_ROOT+"/run/core.pid")+" "+RootBridge.quote(legacyWatchdog)+" >/dev/null 2>&1 || true; fi; "
                    +"printf 'legacy=%s\\n' \"$legacy\"; exit 0";
            RootBridge.Result r=boundedRootShell(context,cmd,5000L);
            if(r.ok()&&r.output!=null&&r.output.contains("legacy=1")){
                stage(progress,"已隔离旧辟尘运行进程，继续由河图接管…");
            }else if(!r.ok()){
                prefs.edit().putString("hetuLegacyCleanupWarning","旧辟尘进程清理未完成，将由河图启动事务继续回收").apply();
            }
        }catch(Exception ignored){
            prefs.edit().putString("hetuLegacyCleanupWarning","旧辟尘进程清理未完成，将由河图启动事务继续回收").apply();
        }
    }

    private void retireLegacyInstallation(Progress progress){
        try{
            RootBridge.requireWorkerThread();
            String uninstall=LEGACY_MODULE+"/uninstall.sh";
            String disable=LEGACY_MODULE+"/disable";
            String remove=LEGACY_MODULE+"/remove";
            String cmd="set +e; legacy=0; "
                    +"if [ -d "+RootBridge.quote(LEGACY_MODULE)+" ]; then legacy=1; "
                    +"if [ -f "+RootBridge.quote(uninstall)+" ]; then sh "+RootBridge.quote(uninstall)+" >/dev/null 2>&1 || true; fi; "
                    +"touch "+RootBridge.quote(disable)+" "+RootBridge.quote(remove)+" >/dev/null 2>&1 || true; fi; "
                    +"if [ -d "+RootBridge.quote(LEGACY_MODULE_UPDATE)+" ]; then legacy=1; rm -rf "+RootBridge.quote(LEGACY_MODULE_UPDATE)+"; fi; "
                    +"if [ -e "+RootBridge.quote(LEGACY_ROOT)+" ]; then legacy=1; rm -rf "+RootBridge.quote(LEGACY_ROOT)+"; fi; "
                    +"echo legacy=$legacy; exit 0";
            RootBridge.Result r=boundedRootShell(context,cmd,20000L);
            if(!r.ok()){
                String detail=r.output==null?"未知 Root 错误":r.output.trim();
                prefs.edit().putString("hetuLegacyCleanupWarning","河图已运行，但旧安装清理失败："+detail).apply();
                return;
            }
            prefs.edit().remove("hetuLegacyCleanupWarning").putBoolean("hetuLegacyRetired",true).apply();
            if(r.output!=null&&r.output.contains("legacy=1"))stage(progress,"河图已接管；旧模块已停用并标记卸载，重启后彻底退出旧挂载");
        }catch(Exception e){
            String detail=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
            if(detail.length()>400)detail=detail.substring(0,400)+"…";
            prefs.edit().putString("hetuLegacyCleanupWarning","河图已运行，但旧安装清理失败："+detail).apply();
        }
    }

    private void installRuntimeFiles(Prepared p,boolean includeConfig)throws Exception{
        installRuntimeFiles(p,includeConfig,null);
    }

    private void installRuntimeFiles(Prepared p,boolean includeConfig,String probedCoreToken)throws Exception{
        RootBridge.requireWorkerThread();
        if(probedCoreToken==null){
            String expected=expectedCoreToken(p.profile.core);
            probedCoreToken=runtimeCoreCurrent(expected)?expected:"";
        }
        RootBridge.Result r=boundedRootShell(context,deploymentCommand(p,includeConfig,probedCoreToken),45000L);
        if(!r.ok())throw new IOException("无法安装 Root 运行文件："+r.output.trim());
    }

    /**
     * probedCoreToken null: the command itself keeps bin/core when the installed token already
     * matches (the merged start path, which skipped the separate probe).
     */
    private String deploymentCommand(Prepared p,boolean includeConfig,String probedCoreToken)throws Exception{
        RootBridge.requireWorkerThread();
        File stage=new File(context.getCacheDir(),"hetu-root-stage");
        if(!stage.isDirectory()&&!stage.mkdirs())throw new IOException("无法创建河图运行临时目录");
        File script=new File(stage,"hetu-root.sh");
        copyScriptAsset(script);
        String coreToken=expectedCoreToken(p.profile.core);
        boolean shellDecidesCore=probedCoreToken==null;
        boolean deployCore=shellDecidesCore||!coreToken.equals(probedCoreToken);
        File binary=deployCore?coreFile(p.profile.core,stage):null;
        File cfg=new File(stage,"startup-config");
        File adblock=p.adblock==null?null:p.adblock.file;
        File adblockAllow=p.adblock==null?null:p.adblock.allowFile;
        File cn4=null,cn6=null;
        if(p.profile.cnIpDirect){
            cn4=new File(stage,"hetu-cn-v4.txt");copyAsset("cnip/hetu-cn-v4.txt",cn4,false);
            cn6=new File(stage,"hetu-cn-v6.txt");copyAsset("cnip/hetu-cn-v6.txt",cn6,false);
        }
        if(includeConfig)Files.write(cfg.toPath(),p.startup.getBytes(StandardCharsets.UTF_8));
        String deploySuffix=".new."+Long.toHexString(System.nanoTime());
        String scriptTmp=SCRIPT+deploySuffix;
        String binTmp=BIN+deploySuffix;
        StringBuilder cmd=new StringBuilder("set -e; mkdir -p ")
                .append(RootBridge.quote(ROOT+"/bin")).append(' ').append(RootBridge.quote(ROOT+"/run/state")).append(' ').append(RootBridge.quote(ROOT+"/run/ruleset"))
                .append("; cp ").append(RootBridge.quote(script.getAbsolutePath())).append(' ').append(RootBridge.quote(scriptTmp))
                .append("; chmod 700 ").append(RootBridge.quote(scriptTmp)).append("; chown 0:0 ").append(RootBridge.quote(scriptTmp))
                .append("; mv -f ").append(RootBridge.quote(scriptTmp)).append(' ').append(RootBridge.quote(SCRIPT))
                .append("; rm -f ").append(RootBridge.quote(OLD_HETU_SCRIPT));
        // The script reads this marker, not an argument: the 30-field start protocol and the
        // stored boot plan stay as they are. Present = leave the system resolver alone.
        String policyDir=ROOT+"/policy",resolverMarker=policyDir+"/"+SYSTEM_DNS_DIRECT_MARKER;
        cmd.append("; mkdir -p ").append(RootBridge.quote(policyDir)).append("; chmod 700 ").append(RootBridge.quote(policyDir));
        if(prefs.getBoolean("proxyDnsSystemResolver",true))cmd.append("; rm -f ").append(RootBridge.quote(resolverMarker));
        else cmd.append("; : > ").append(RootBridge.quote(resolverMarker)).append("; chmod 600 ").append(RootBridge.quote(resolverMarker))
                .append("; chown 0:0 ").append(RootBridge.quote(resolverMarker));
        if(deployCore){
            if(shellDecidesCore)cmd.append("; if [ -x ").append(RootBridge.quote(BIN)).append(" ] && [ \"$(cat ").append(RootBridge.quote(CORE_TOKEN))
                    .append(" 2>/dev/null)\" = ").append(RootBridge.quote(coreToken)).append(" ]; then :; else :");
            cmd.append("; cp ").append(RootBridge.quote(binary.getAbsolutePath())).append(' ').append(RootBridge.quote(binTmp))
                    .append("; chmod 700 ").append(RootBridge.quote(binTmp)).append("; chown 0:0 ").append(RootBridge.quote(binTmp))
                    .append("; mv -f ").append(RootBridge.quote(binTmp)).append(' ').append(RootBridge.quote(BIN))
                    .append("; printf %s ").append(RootBridge.quote(coreToken)).append(" > ").append(RootBridge.quote(CORE_TOKEN))
                    .append("; chmod 600 ").append(RootBridge.quote(CORE_TOKEN)).append("; chown 0:0 ").append(RootBridge.quote(CORE_TOKEN));
            appendCoreAssets(cmd,p.profile.core);
            if(shellDecidesCore)cmd.append("; fi");
        }
        appendCoreKind(cmd,p.profile.core);
        if(adblock!=null){
            String dst=ROOT+"/run/ruleset/hetu-adblock.txt",tmp=dst+".new";
            String allowDst=ROOT+"/run/ruleset/hetu-adblock-allow.txt",allowTmp=allowDst+".new";
            String revisionFile=ROOT+"/run/state/adblock.revision";
            cmd.append("; if [ ! -f ").append(RootBridge.quote(dst))
                    .append(" ] || [ ! -f ").append(RootBridge.quote(allowDst))
                    .append(" ] || [ \"$(cat ").append(RootBridge.quote(revisionFile)).append(" 2>/dev/null)\" != ")
                    .append(RootBridge.quote(p.adblock.revision))
                    .append(" ] || [ \"$(sha256sum ").append(RootBridge.quote(dst)).append(" 2>/dev/null | cut -d ' ' -f 1)\" != ")
                    .append(RootBridge.quote(RuntimeCompatibility14.sha256(adblock)))
                    .append(" ] || [ \"$(sha256sum ").append(RootBridge.quote(allowDst)).append(" 2>/dev/null | cut -d ' ' -f 1)\" != ")
                    .append(RootBridge.quote(RuntimeCompatibility14.sha256(adblockAllow))).append(" ]; then")
                    .append(" cp ").append(RootBridge.quote(adblock.getAbsolutePath())).append(' ').append(RootBridge.quote(tmp))
                    .append("; chmod 600 ").append(RootBridge.quote(tmp)).append("; chown 0:0 ").append(RootBridge.quote(tmp));
            if(adblockAllow!=null){
                cmd.append("; cp ").append(RootBridge.quote(adblockAllow.getAbsolutePath())).append(' ').append(RootBridge.quote(allowTmp))
                        .append("; chmod 600 ").append(RootBridge.quote(allowTmp)).append("; chown 0:0 ").append(RootBridge.quote(allowTmp))
                        .append("; test \"$(sha256sum ").append(RootBridge.quote(allowTmp)).append(" | cut -d ' ' -f 1)\" = ")
                        .append(RootBridge.quote(RuntimeCompatibility14.sha256(adblockAllow)));
            }
            cmd.append("; test \"$(sha256sum ").append(RootBridge.quote(tmp)).append(" | cut -d ' ' -f 1)\" = ")
                    .append(RootBridge.quote(RuntimeCompatibility14.sha256(adblock)))
                    .append("; mv -f ").append(RootBridge.quote(allowTmp)).append(' ').append(RootBridge.quote(allowDst))
                    .append("; mv -f ").append(RootBridge.quote(tmp)).append(' ').append(RootBridge.quote(dst));
            cmd.append("; printf %s ").append(RootBridge.quote(p.adblock.revision)).append(" > ").append(RootBridge.quote(revisionFile))
                    .append("; chmod 600 ").append(RootBridge.quote(revisionFile)).append("; chown 0:0 ").append(RootBridge.quote(revisionFile))
                    .append("; fi");
        }
        if(p.profile.cnIpDirect){
            String dst4=ROOT+"/run/ruleset/hetu-cn-v4.txt",dst6=ROOT+"/run/ruleset/hetu-cn-v6.txt";
            cmd.append("; if [ ! -s ").append(RootBridge.quote(dst4)).append(" ]; then cp ").append(RootBridge.quote(cn4.getAbsolutePath())).append(' ').append(RootBridge.quote(dst4)).append("; chmod 600 ").append(RootBridge.quote(dst4)).append("; chown 0:0 ").append(RootBridge.quote(dst4)).append("; fi")
                    .append("; if [ ! -s ").append(RootBridge.quote(dst6)).append(" ]; then cp ").append(RootBridge.quote(cn6.getAbsolutePath())).append(' ').append(RootBridge.quote(dst6)).append("; chmod 600 ").append(RootBridge.quote(dst6)).append("; chown 0:0 ").append(RootBridge.quote(dst6)).append("; fi");
        }
        if(includeConfig){
            String configTmp=CONFIG+deploySuffix;
            cmd.append("; cp ").append(RootBridge.quote(cfg.getAbsolutePath())).append(' ').append(RootBridge.quote(configTmp))
                    .append("; chmod 600 ").append(RootBridge.quote(configTmp)).append("; chown 0:0 ").append(RootBridge.quote(configTmp))
                    .append("; mv -f ").append(RootBridge.quote(configTmp)).append(' ').append(RootBridge.quote(CONFIG));
        }
        return cmd.toString();
    }

    /** hetu-root.sh reads which CLI bin/core speaks from this file (absent = Mihomo). */
    private static void appendCoreKind(StringBuilder cmd,ProxyRuntimeProfile.Core core){
        cmd.append("; printf '%s\\n' ").append(RootBridge.quote(ProxyCoreConfig.kind(core).id)).append(" > ").append(RootBridge.quote(CORE_KIND))
                .append("; chmod 600 ").append(RootBridge.quote(CORE_KIND)).append("; chown 0:0 ").append(RootBridge.quote(CORE_KIND));
    }

    /** geoip.dat / geosite.dat extracted with Xray or V2Fly, deployed next to the binary. */
    private void appendCoreAssets(StringBuilder cmd,ProxyRuntimeProfile.Core core){
        cmd.append("; rm -rf ").append(RootBridge.quote(CORE_ASSETS)).append("; mkdir -p ").append(RootBridge.quote(CORE_ASSETS))
                .append("; chmod 700 ").append(RootBridge.quote(CORE_ASSETS));
        File dir=cores.file(core).getParentFile();
        for(String name:new String[]{"geoip.dat","geosite.dat"}){
            File asset=new File(dir,core.id+"."+name);
            if(!asset.isFile()||asset.length()==0)continue;
            String target=CORE_ASSETS+"/"+name;
            cmd.append("; cp ").append(RootBridge.quote(asset.getAbsolutePath())).append(' ').append(RootBridge.quote(target))
                    .append("; chmod 600 ").append(RootBridge.quote(target)).append("; chown 0:0 ").append(RootBridge.quote(target));
        }
    }

    private File coreFile(ProxyRuntimeProfile.Core core,File stage)throws IOException{
        if(cores.installed(core))return cores.file(core);
        if(core!=ProxyRuntimeProfile.Core.MIHOMO)throw new IOException(core.label+" 尚未安装核心");
        // The embedded binary is staged once per revision: the merged start command always needs
        // a source path even when the installed token matches and nothing is copied.
        File out=new File(stage,"mihomo"),stamp=new File(stage,"mihomo.token");
        String token=expectedCoreToken(core);
        try{
            if(out.isFile()&&out.length()>0&&out.canExecute()&&stamp.isFile()
                    &&token.equals(new String(Files.readAllBytes(stamp.toPath()),StandardCharsets.UTF_8)))return out;
        }catch(IOException ignored){}
        if(stamp.exists()&&!stamp.delete())throw new IOException("无法刷新内置核心暂存标记");
        copyAsset(rootBinaryAsset(),out,true);
        try(FileOutputStream target=new FileOutputStream(stamp,false)){target.write(token.getBytes(StandardCharsets.UTF_8));target.getFD().sync();}
        return out;
    }
    private String rootBinaryAsset()throws IOException{
        for(String abi:Build.SUPPORTED_ABIS){
            String v=abi.toLowerCase(Locale.ROOT);
            if(v.equals("arm64-v8a"))return"mihomo-root/arm64-v8a/mihomo";
            if(v.equals("x86_64"))return"mihomo-root/x86_64/mihomo";
        }
        throw new IOException("当前 CPU 架构没有内置 Mihomo："+Arrays.toString(Build.SUPPORTED_ABIS));
    }
    private void copyScriptAsset(File out)throws IOException{
        byte[] bytes;
        try(InputStream in=context.getAssets().open("hetu-root.sh")){
            ByteArrayOutputStream data=new ByteArrayOutputStream();
            byte[] block=new byte[32768];int count;
            while((count=in.read(block))!=-1)data.write(block,0,count);
            bytes=data.toByteArray();
        }
        if(!out.isFile()||out.length()!=bytes.length||!Arrays.equals(Files.readAllBytes(out.toPath()),bytes)){
            try(FileOutputStream target=new FileOutputStream(out,false)){
                target.write(bytes);target.getFD().sync();
            }
        }
        if(!out.setReadable(true,true)||!out.setExecutable(true,true))throw new IOException("无法设置运行脚本权限");
    }

    private void copyAsset(String name,File out,boolean executable)throws IOException{
        try(InputStream in=context.getAssets().open(name);FileOutputStream fos=new FileOutputStream(out,false)){
            byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)fos.write(b,0,n);fos.getFD().sync();
        }
        if(!out.setReadable(true,true)||(executable&&!out.setExecutable(true,true)))throw new IOException("无法设置运行文件权限");
    }
}
