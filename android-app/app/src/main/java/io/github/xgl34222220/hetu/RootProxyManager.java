package io.github.xgl34222220.hetu;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import org.json.JSONObject;
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
    private static final String SCRIPT=ROOT+"/hetu-root.sh";
    private static final String OLD_HETU_SCRIPT=ROOT+"/proxy-root.sh";
    private static final String LEGACY_ROOT="/data/adb/bichen/proxy";
    private static final String LEGACY_MODULE="/data/adb/modules/bichen";
    private static final String LEGACY_MODULE_UPDATE="/data/adb/modules_update/bichen";
    private static final ReentrantLock CONTROL_LOCK=new ReentrantLock(true);
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
        final String startup,settingsSignature;
        final int tproxyPort,redirectPort,controllerPort;
        Prepared(ProxyRuntimeProfile p,ProxyConfigLibrary.Entry s,RootProxyPolicy policy,ProxyAdblockRules.Snapshot adblock,String y,int tp,int rp,int cp,String signature){
            profile=p;source=s;this.policy=policy;this.adblock=adblock;startup=y;tproxyPort=tp;redirectPort=rp;controllerPort=cp;settingsSignature=signature;
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
        RootBridge.Result result=RootBridge.rootShell(context,
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
            RootBridge.Result result=RootBridge.rootShell(context,
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
            RootBridge.Result result=RootBridge.rootShell(context,
                    RootStartupProbe.command(ROOT+"/run/core.pid",BIN,CORE_TOKEN),4000L);
            return RootStartupProbe.parse(result.ok(),result.output);
        }catch(Exception ignored){return RootStartupProbe.parse(false,"");}
    }

    private boolean coreAliveFast(){
        try{
            RootBridge.requireWorkerThread();
            String command=ProxyContinuity.coreProbeCommand(ROOT+"/run/core.pid",BIN);
            RootBridge.Result result=RootBridge.rootShell(context,command,4000L);
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
        }
        cmd.append("; rm -f ").append(RootBridge.quote(OLD_HETU_SCRIPT))
                .append("; printf %s ").append(RootBridge.quote(infoText)).append(" > ").append(RootBridge.quote(info))
                .append("; chmod 600 ").append(RootBridge.quote(info)).append("; chown 0:0 ").append(RootBridge.quote(info));
        RootBridge.Result r=RootBridge.rootShell(context,cmd.toString(),45000L);
        if(!r.ok())throw new IOException("无法初始化河图运行目录："+r.output.trim());
        return ROOT;
        }finally{CONTROL_LOCK.unlock();}
    }

    String refreshAdblockRuntime()throws Exception{
        CONTROL_LOCK.lock();
        try{
            RootBridge.requireWorkerThread();
            if(!prefs.getBoolean("proxyAdblockChain",true))return "广告过滤串联未开启";
            JSONObject state=status();
            if(!state.optBoolean("running",false))return "规则已保存；代理下次启动时自动使用新规则";
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
            RootBridge.Result copied=RootBridge.rootShell(context,cmd,20000L);
            if(!copied.ok())throw new IOException("规则已更新，但写入运行目录失败："+copied.output.trim());

            int port=liveControllerPort(state);
            if(port>0)prefs.edit().putInt("proxyControllerPort",port).apply();
            MihomoControllerClient controller=new MihomoControllerClient(context);
            try{
                controller.reloadLocalRuleProvider(ProxyAdblockRules.ALLOW_PROVIDER_NAME);
                controller.reloadLocalRuleProvider(ProxyAdblockRules.PROVIDER_NAME);
            }catch(Exception providerError){
                // Do not reload the entire proxy after a provider error: this can
                // disturb live transports, and an old config without the providers
                // can reload successfully while the new filtering still isn't active.
                String detail=providerError.getMessage()==null?providerError.getClass().getSimpleName():providerError.getMessage();
                String message="规则已保存，但运行中的过滤规则未确认更新："+detail+"；请重试或主动重启代理";
                prefs.edit().putString("proxyAdblockLastError",message).apply();
                throw new IOException(message,providerError);
            }
            prefs.edit()
                    .putLong("proxyAdblockProviderReloadAt",System.currentTimeMillis())
                    .putInt("proxyAdblockLastRuleCount",snapshot.count)
                    .putString("proxyAdblockLastRevision",snapshot.revision)
                    .putLong("proxyAdblockHotReloadAt",System.currentTimeMillis())
                    .remove("proxyAdblockLastError")
                    .apply();
            return "已热更新 "+snapshot.count+" 条广告规则";
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
        if(profile.core!=ProxyRuntimeProfile.Core.MIHOMO&&profile.core!=ProxyRuntimeProfile.Core.MIHOMO_SMART)
            throw new IOException(profile.core.label+" 的运行后端还未接入");
        ProxyRuntimeProfile.Capability capability=profile.capability();
        if(!capability.available)throw new IOException(capability.reason.isEmpty()?profile.mode.label+" 暂不可用":capability.reason);
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
        writeStartupCopy(generated.yaml);
        return new Prepared(profile,selected,policy,adblock,generated.yaml,generated.tproxyPort,generated.redirectPort,controllerPort,settingsSignature);
    }

    private String topologyFingerprint(ProxyRuntimeProfile profile,RootProxyPolicy policy){
        return profile.core.id+"|"+profile.mode.id+"|"+profile.ipv6.id+"|"+profile.dnsHijack.id
                +"|tcp="+bit(profile.tcp)+"|udp="+bit(profile.udp)+"|quic="+bit(profile.quicBlocked)
                +"|scope="+policy.appScope+"|uids="+policy.uidRanges+"|share="+bit(policy.sharedNetwork)
                +"|kill="+bit(policy.killSwitch)+"|cidrs="+policy.cidrs+"|ifaces="+policy.interfaces
                +"|direct="+policy.directUidRanges;
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
        RootBridge.Result installed=RootBridge.rootShell(context,cmd.toString(),15000L);
        if(!installed.ok())throw new IOException("无法写入热重载配置："+installed.output.trim());
    }

    String reloadCurrentConfig()throws Exception{
        CONTROL_LOCK.lock();
        try{
            RootBridge.requireWorkerThread();
            if(!coreAliveFast())throw new IOException("代理未运行，无法热重载");
            ProxyRuntimeProfile profile=ProxyRuntimeProfile.load(prefs);
            int port=prefs.getInt("proxyControllerPort",MihomoStartupConfig.CONTROLLER_PORT);
            Prepared p=prepare(profile,port);
            String nextFingerprint=topologyFingerprint(profile,p.policy);
            String liveFingerprint=prefs.getString("proxyRootTopologyFingerprint","");
            if(liveFingerprint!=null&&!liveFingerprint.isEmpty()&&!liveFingerprint.equals(nextFingerprint))
                throw new IOException("运行模式、应用范围、DNS、IPv6、TCP/UDP 或绕过策略已变化，请使用「重启」应用这些网络层设置");

            installHotReloadFiles(p);
            MihomoControllerClient controller=new MihomoControllerClient(context);
            try{
                controller.reloadConfig(CONFIG);
            }catch(Exception error){
                RootBridge.rootShell(context,
                        "if [ -f "+RootBridge.quote(CONFIG+".before-reload")+" ]; then mv -f "
                                +RootBridge.quote(CONFIG+".before-reload")+" "+RootBridge.quote(CONFIG)+"; fi",
                        5000L);
                try{controller.reloadConfig(CONFIG);}catch(Exception ignored){}
                throw error;
            }
            RootBridge.rootShell(context,"rm -f "+RootBridge.quote(CONFIG+".before-reload"),3000L);
            prefs.edit()
                    .putString("proxyRootTopologyFingerprint",nextFingerprint)
                    .putString("proxyRootAppliedSettings",p.settingsSignature)
                    .putInt("proxyAdblockLastRuleCount",p.adblock==null?0:p.adblock.count)
                    .putString("proxyAdblockLastRevision",p.adblock==null?"":p.adblock.revision)
                    .apply();
            return "运行配置已热重载";
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

    private String capabilityFingerprint(ProxyRuntimeProfile profile,RootProxyPolicy policy){
        return "caps-v1|"+Build.FINGERPRINT+"|"+topologyFingerprint(profile,policy);
    }

    JSONObject preflight(Prepared p)throws Exception{
        RootBridge.requireWorkerThread();
        CONTROL_LOCK.lock();
        try{
        installRuntimeFiles(p,false);
        RootProxyPolicy policy=p.policy;
        return runJson("preflight",
                p.profile.mode.id,String.valueOf(p.tproxyPort),String.valueOf(p.redirectPort),p.profile.ipv6.id,
                bit(p.profile.tcp),bit(p.profile.udp),p.profile.dnsHijack.id,bit(p.profile.quicBlocked),
                String.valueOf(MihomoStartupConfig.DNS_PORT),String.valueOf(p.controllerPort),
                policy.appScope,policy.uidRanges,bit(policy.sharedNetwork),bit(policy.killSwitch),policy.cidrs,policy.interfaces,policy.directUidRanges);
        }finally{CONTROL_LOCK.unlock();}
    }

    JSONObject start(ProxyRuntimeProfile p)throws Exception{return startInternal(p,null,false);}
    JSONObject start(ProxyRuntimeProfile profile,Progress progress)throws Exception{return startInternal(profile,progress,false);}
    JSONObject startManual(ProxyRuntimeProfile profile,Progress progress)throws Exception{return startOwned(profile,progress,false);}
    JSONObject replaceRunningManually(ProxyRuntimeProfile profile,Progress progress)throws Exception{return startOwned(profile,progress,true);}
    private JSONObject startOwned(ProxyRuntimeProfile profile,Progress progress,boolean replace)throws Exception{
        CONTROL_LOCK.lock();
        String previous=prefs.getString("proxyRootSessionOwner","");
        try{
            prefs.edit().putString("proxyRootSessionOwner","manual").apply();
            return startInternal(profile,progress,replace);
        }catch(Exception error){
            if(previous==null||previous.isEmpty())prefs.edit().remove("proxyRootSessionOwner").apply();
            else prefs.edit().putString("proxyRootSessionOwner",previous).apply();
            throw error;
        }finally{CONTROL_LOCK.unlock();}
    }
    JSONObject startIfAutomationAllowed(ProxyRuntimeProfile profile,java.util.function.BooleanSupplier currentDecision)throws Exception{
        CONTROL_LOCK.lock();
        try{
            if(!prefs.getBoolean("networkMatchEnabled",false)||"manual".equals(prefs.getString("proxyRootSessionOwner",""))||!currentDecision.getAsBoolean())
                return new JSONObject().put("ok",true).put("cancelled",true);
            JSONObject result=startInternal(profile,null,false);
            if(result.optBoolean("ok",false)&&!result.optBoolean("cancelled",false))
                prefs.edit().putString("proxyRootSessionOwner","automation").apply();
            return result;
        }finally{CONTROL_LOCK.unlock();}
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
        CONTROL_LOCK.lock();
        try{
            if(!prefs.getBoolean("proxyRootWanted",false))
                return new JSONObject().put("ok",true).put("running",false).put("cancelled",true);
            return startInternal(profile,null,false);
        }finally{CONTROL_LOCK.unlock();}
    }
    JSONObject replaceRunningAfterUpgrade(ProxyRuntimeProfile profile)throws Exception{return startInternal(profile,null,true);}
    JSONObject replaceRunningAfterUpgrade(ProxyRuntimeProfile profile,Progress progress)throws Exception{return startInternal(profile,progress,true);}

    private JSONObject startInternal(ProxyRuntimeProfile profile,Progress progress,boolean replaceRunning)throws Exception{
        StartupTrace trace=new StartupTrace();
        CONTROL_LOCK.lock();
        try{
            trace.next("runtimeProbe");
            RootStartupProbe.Result initial=probeStartupRuntime();
            boolean existingRunning=ProxyContinuity.preserveRunning(initial.process,
                    prefs.getBoolean("proxyRootRuntimeRunning",false)&&prefs.getBoolean("proxyRootWanted",false));
            if(existingRunning&&!replaceRunning){
                prefs.edit().putBoolean("proxyRootWanted",true).putBoolean("proxyRootRuntimeRunning",true).apply();
                ensureContinuityService(true);
                trace.outcome="alreadyRunning";
                return new JSONObject().put("ok",true).put("running",true).put("alreadyRunning",true)
                        .put("message","Root 代理已在运行，已忽略重复启动请求");
            }
            if(replaceRunning) {
                // Preserve the user's intent before any new preparation. The shell start transaction
                // validates the new config before it cleans up the old core/network rules.
                prefs.edit().putBoolean("proxyRootWanted",true).apply();
                stage(progress,"校验新版运行环境，确认可替换后再切换旧核心…");
            } else {
                stage(progress,"检查配置、应用范围与绕过策略…");
            }
        int restartControllerPort=(replaceRunning&&existingRunning)
                ?prefs.getInt("proxyControllerPort",MihomoStartupConfig.CONTROLLER_PORT):0;
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
        stage(progress,"部署必要运行文件…");
        trace.next("deployment");
        // The initial process probe also read the installed core token. Reuse that
        // result instead of opening a second privileged shell just to read one file.
        installRuntimeFiles(p,true,initial.installedCoreToken);

        trace.next("validation");
        String validationKey=validationFingerprint(p);
        boolean validationKnown=validationKey.equals(prefs.getString("proxyRootValidatedFingerprint",""));
        boolean preserveLiveNeedsValidation=replaceRunning&&existingRunning&&!validationKnown;
        if(preserveLiveNeedsValidation){
            stage(progress,"配置有变化，先校验后再替换当前核心…");
            try{
                validateRuntimeConfig();
                prefs.edit().putString("proxyRootValidatedFingerprint",validationKey).apply();
                if(profile.adblockChain)prefs.edit().remove("proxyAdblockLastError").apply();
            }catch(Exception fullFailure){
                if(!profile.adblockChain)throw fullFailure;
                stage(progress,"广告串联校验失败，尝试保留原 YAML 重新校验…");
                ProxyRuntimeProfile fallbackProfile=withoutAdblock(profile);
                Prepared fallback=prepare(fallbackProfile,restartControllerPort);
                installRuntimeFiles(fallback,true);
                String fallbackValidationKey=validationFingerprint(fallback);
                try{validateRuntimeConfig();}
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
        String capabilityKey=capabilityFingerprint(profile,policy);
        boolean capabilityKnown=capabilityKey.equals(prefs.getString("proxyRootCapabilityFingerprint",""));
        stage(progress,capabilityKnown?"设备能力未变化，跳过重复探测…":"检查网络能力并启动核心…");

        trace.next("filterHandoff");
        boolean adblockCoordinatorEntered=false;
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
        JSONObject result;
        try{result=runJsonWithTimeout(125000L,"start",
                BIN,CONFIG,profile.mode.id,String.valueOf(p.tproxyPort),String.valueOf(p.redirectPort),profile.ipv6.id,
                bit(profile.tcp),bit(profile.udp),profile.dnsHijack.id,bit(profile.quicBlocked),
                String.valueOf(MihomoStartupConfig.DNS_PORT),String.valueOf(p.controllerPort),
                policy.appScope,policy.uidRanges,bit(policy.sharedNetwork),bit(policy.killSwitch),policy.cidrs,policy.interfaces,policy.directUidRanges,"1",bit(capabilityKnown));
            if(!result.optBoolean("ok"))throw new IOException(result.optString("message","Root 代理启动失败"));
        }catch(Exception startFailure){if(adblockCoordinatorEntered)ProxyAdblockCoordinator.exit(context);throw startFailure;}

        trace.next("finalProcessCheck");
        stage(progress,"确认核心进程、策略控制接口与守护状态…");
        if(!coreAliveFast()){
            if(adblockCoordinatorEntered)ProxyAdblockCoordinator.exit(context);
            RootBridge.Result failure=RootBridge.rootShell(context,"tail -c 1800 "+RootBridge.quote(ROOT+"/run/core.log")+" 2>/dev/null || true",3000L);
            String detail=DiagnosticReport.redact(failure.output,prefs.getString("proxyControllerSecret",""));
            throw new IOException("启动命令已返回，但未检测到河图私有核心进程"+(detail.isEmpty()?"":"："+detail));
        }
        trace.next("publishRuntime");
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
                .put("directPackageCount",policy.directPackages.size())
                .put("sharedNetwork",policy.sharedNetwork)
                .put("killSwitch",policy.killSwitch)
                .put("cnIpDirect",profile.cnIpDirect)
                .put("adblockChain",profile.adblockChain)
                .put("adblockRuleCount",p.adblock==null?0:p.adblock.count)
                .put("controllerPort",p.controllerPort)
                .put("adblockRevision",p.adblock==null?"":p.adblock.revision)
                .put("bypassCidrs",policy.cidrs)
                .put("bypassInterfaces",policy.interfaces);
        if(!warning.isEmpty())result.put("warning",warning);
        synchronized(ProxyAdblockSession.LOCK){
        prefs.edit()
                .putBoolean("proxyRootWanted",true)
                .putBoolean("proxyRootRuntimeRunning",true)
                .putBoolean("proxyAdblockCounterArmed",profile.adblockChain)
                .putInt("proxyAutoDirectPackageCount",policy.directPackages.size())
                .putBoolean("proxyAdblockLastEffective",profile.adblockChain)
                .putInt("proxyAdblockLastRuleCount",p.adblock==null?0:p.adblock.count)
                .putString("proxyAdblockLastRevision",p.adblock==null?"":p.adblock.revision)
                .putString("proxyRootTopologyFingerprint",topologyFingerprint(profile,policy))
                .putString("proxyRootAppliedSettings",p.settingsSignature)
                .putString("proxyRootEffectiveIpv6",profile.ipv6.id)
                .putLong("proxyRootHealthProbeElapsed",0L)
                .putString("proxyRootValidatedFingerprint",validationKey)
                .putString("proxyRootCapabilityFingerprint",capabilityKey)
                .remove("proxyRootRuntimeRefreshPending")
                .remove("proxyRootBootError")
                .apply();
        }
        ensureContinuityService(true);
        trace.outcome="ready";
        return result;
        }finally{
            try{
                // A repeated Start/boot request is not a new startup; keep the
                // useful timing of the last actual transaction for diagnostics.
                if(!"alreadyRunning".equals(trace.outcome))prefs.edit()
                        .putString("proxyRootLastStartupTiming",trace.finish())
                        .putLong("proxyRootLastStartupAt",System.currentTimeMillis()).apply();
            }finally{CONTROL_LOCK.unlock();}
        }
    }

    JSONObject stop()throws Exception{return stop(null);}
    JSONObject stop(Progress progress)throws Exception{
        CONTROL_LOCK.lock();
        try{
            // Revoke recovery intent before shutdown; queued automatic starts recheck under this lock.
            synchronized(ProxyAdblockSession.LOCK){
                prefs.edit().putBoolean("proxyRootWanted",false)
                        .putBoolean("proxyAdblockCounterArmed",false)
                        .putLong("proxyAdblockSessionGeneration",prefs.getLong("proxyAdblockSessionGeneration",0L)+1L)
                        .remove("proxyAdblockPendingLogLine").remove("proxyAdblockDiscardLogLine").apply();
            }
            stage(progress,"停止守护、Kill Switch、核心并回滚透明代理规则…");
            JSONObject r=runJsonAllowMissing("stop",new JSONObject().put("ok",true).put("running",false).put("state","idle").put("message","Root 代理未运行"));
            ProxyAdblockCoordinator.exit(context);
            stage(progress,"网络规则、广告过滤接管与临时 IPv6 状态已恢复");
            prefs.edit()
                    .putBoolean("proxyRootWanted",false)
                    .putBoolean("proxyRootRuntimeRunning",false)
                    .putBoolean("proxyAdblockCounterArmed",false)
                    .putLong("proxyRootHealthProbeElapsed",0L)
                    .remove("proxyRootSessionOwner")
                    .apply();
            ensureContinuityService(false);
            return r;
        }finally{
            CONTROL_LOCK.unlock();
        }
    }
    private int liveControllerPort(JSONObject state){
        int port=state.optInt("controllerPort",0);
        if(port>=29090&&port<=29149)return port;
        if(!state.optBoolean("running",false))return 0;
        // Recovery for old sessions: test.45-test.52 preflight could overwrite prefs
        // while the live Mihomo still listened on the old controller port. The deployed
        // Root startup config is authoritative because preflight does not replace CONFIG.
        try{
            RootBridge.Result result=RootBridge.rootShell(context,"cat "+RootBridge.quote(CONFIG)+" 2>/dev/null || true",4000L);
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
        // Old deployed scripts intentionally require one explicit restart after upgrade.
        return runJsonWithTimeout(8000L,"network-health");
    }

    JSONObject status()throws Exception{
        JSONObject state=runJsonAllowMissing("status",new JSONObject().put("ok",true).put("running",false).put("state","idle").put("message","尚未启动"));
        int livePort=liveControllerPort(state);
        if(livePort>0&&prefs.getInt("proxyControllerPort",MihomoStartupConfig.CONTROLLER_PORT)!=livePort){
            prefs.edit().putInt("proxyControllerPort",livePort).apply();
            state.put("controllerPort",livePort);
        }
        return state;
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
        StringBuilder events=new StringBuilder("time=").append(new Date())
                .append("\napp=").append(BuildConfig.VERSION_NAME)
                .append("\nexpectedCore=").append(expectedCoreToken(ProxyRuntimeProfile.load(prefs).core));
        Map<String,?> values=prefs.getAll();
        for(String key:new String[]{"proxyBaseCore","proxyBaseMode","proxyBaseIpv6","proxyRootWanted",
                "proxyRootRuntimeRunning","proxyRootRuntimeRefreshPending","proxyRootBootError",
                "proxyRootBootRestoreSuccessAt","proxyLastUnknownProcessProbeAt","proxyAutoRecoveryAttempt",
                "proxyAutoRecoverySuccess","proxyAutoRecoveryError","proxyLastNetworkSessionReset",
                "proxyLastNetworkSessionResetCount","proxyLastNetworkSessionResetReason","proxyNetworkSessionResetError",
                "proxyLastAutoStopAt","proxyLastAutoStopReason","proxyAdblockLastRevision","proxyAdblockLastError",
                "proxyAdblockHotReloadAt","proxyAdblockLastHitAt","proxyRootEgressProbeLastError",
                "proxyNetworkIntegrity","proxyNetworkFault","proxyNetworkCheckedAt","proxyPolicyEgressState","proxyPolicyEgressCheckedAt"}){
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
                    "echo '--- core version ---'; "+RootBridge.quote(BIN)+" -v 2>&1 | head -n 3",
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
            RootBridge.Result r=RootBridge.rootShell(context,cmd,12000L);
            report.section("Root 网络状态","exit="+r.code+"\n"+r.output,18000);
        }catch(Exception e){report.section("Root 网络状态",String.valueOf(e),1000);}
        try{
            report.section("网络完整性（不等同于外部网站可达）",networkHealth().toString(),2500);
            RootBridge.Result repairs=RootBridge.rootShell(context,"tail -n 30 "+RootBridge.quote(ROOT+"/run/network-repair.log")+" 2>/dev/null; true",3000L);
            report.section("网络原位修复记录",repairs.output,5000);
        }catch(Exception error){report.section("网络完整性",String.valueOf(error),1000);}
        try{
            int wechatUid=-1;
            try{wechatUid=context.getPackageManager().getApplicationInfo("com.tencent.mm",0).uid;}catch(Exception ignored){}
            org.json.JSONArray connections=new MihomoControllerClient(context).connections().optJSONArray("connections");
            StringBuilder matched=new StringBuilder("WeChat UID=").append(wechatUid).append('\n');
            int count=0;
            for(int i=0;connections!=null&&i<connections.length()&&count<25;i++){
                JSONObject connection=connections.optJSONObject(i);
                JSONObject metadata=connection==null?null:connection.optJSONObject("metadata");
                if(metadata==null)continue;
                String destination=metadata.optString("host","").toLowerCase(java.util.Locale.ROOT);
                String process=metadata.optString("process","").toLowerCase(java.util.Locale.ROOT);
                boolean affected=destination.contains("github")||destination.contains("google")||destination.contains("gstatic")
                        ||destination.contains("telegram")||destination.contains("twitter")||destination.equals("x.com")
                        ||destination.endsWith(".x.com")||process.contains("telegram")||process.contains("twitter")||process.contains("chrome");
                if(!affected&&!DiagnosticReport.isWechat(metadata.optString("process"),metadata.optString("host"),metadata.optInt("uid",-1),wechatUid))continue;
                count++;
                matched.append("#").append(count).append(" process=").append(metadata.optString("process"))
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
            report.section("消息与常见代理应用连接（仅元数据）",matched.toString(),6000);
        }catch(Exception e){report.section("微信当前连接","Controller 读取失败："+e.getMessage(),1000);}
        try{
            String cmd="echo '--- recent core log ---'; tail -c 9000 "+RootBridge.quote(ROOT+"/run/core.log")
                    +" 2>/dev/null; echo; echo '--- last crash ---'; tail -c 1500 "+RootBridge.quote(ROOT+"/run/last-crash")+" 2>/dev/null; true";
            RootBridge.Result logs=RootBridge.rootShell(context,cmd,5000L);
            report.section("最近核心日志",logs.output,10000);
        }catch(Exception e){report.section("最近核心日志",String.valueOf(e),1000);}
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

    private void validateRuntimeConfig()throws Exception{
        RootBridge.requireWorkerThread();
        String cmd="mkdir -p "+RootBridge.quote(ROOT+"/run")+" && "+RootBridge.quote(BIN)+" -t -d "+RootBridge.quote(ROOT+"/run")+" -f "+RootBridge.quote(CONFIG);
        RootBridge.Result r=RootBridge.rootShell(context,cmd,30000L);
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
            RootBridge.Result result=RootBridge.rootShell(context,cmd,30000L);
            if(result.ok())return;
            String detail=result.output==null?"":result.output.trim();
            if(detail.length()>1200)detail=detail.substring(detail.length()-1200);
            throw new IOException("YAML 校验失败"+(detail.isEmpty()?"":"："+detail));
        }finally{
            source.delete();
        }
    }

    private JSONObject runJsonAllowMissing(String action,JSONObject missing)throws Exception{
        RootBridge.requireWorkerThread();
        String cmd="if [ -x "+RootBridge.quote(SCRIPT)+" ]; then exec "+RootBridge.quote(SCRIPT)+" "+RootBridge.quote(action)+"; else printf '%s\\n' "+RootBridge.quote(missing.toString())+"; fi";
        long timeout="status".equals(action)?8000L:20000L;
        RootBridge.Result r=RootBridge.rootShell(context,cmd,timeout);
        String raw=r.output==null?"":r.output.trim();
        JSONObject j=null;
        if(!raw.isEmpty()){
            int newline=raw.indexOf('\n');
            String first=newline<0?raw:raw.substring(0,newline).trim();
            try{j=RootBridge.parseObject(first);}catch(Exception ignored){}
            if(j==null)try{j=RootBridge.parseObject(raw);}catch(Exception ignored){}
        }
        if(j==null)throw new IOException(raw.isEmpty()?"Root 授权或状态查询不可用":compactRootError(raw));
        if(r.code!=0){
            if("status".equals(action)&&j.optBoolean("ok",false))return j;
            throw new IOException(j.optString("message","Root 状态查询失败，退出码 "+r.code));
        }
        return j;
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
    private JSONObject runJsonWithTimeout(long timeoutMs,String...args)throws Exception{
        RootBridge.requireWorkerThread();
        StringBuilder cmd=new StringBuilder("exec ").append(RootBridge.quote(SCRIPT));
        for(String a:args)cmd.append(' ').append(RootBridge.quote(a==null?"":a));
        RootBridge.Result r=RootBridge.rootShell(context,cmd.toString(),timeoutMs);
        JSONObject j;
        try{j=RootBridge.parseObject(r.output.trim());}
        catch(Exception e){throw new IOException(r.output.isEmpty()?"Root 控制器没有返回状态":r.output);}
        if(r.code!=0)throw new IOException(j.optString("message","Root 代理命令失败，退出码 "+r.code));
        return j;
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
            RootBridge.Result r=RootBridge.rootShell(context,cmd,5000L);
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
            RootBridge.Result r=RootBridge.rootShell(context,cmd,20000L);
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
        File stage=new File(context.getCacheDir(),"hetu-root-stage");
        if(!stage.isDirectory()&&!stage.mkdirs())throw new IOException("无法创建河图运行临时目录");
        File script=new File(stage,"hetu-root.sh");
        copyScriptAsset(script);
        String coreToken=expectedCoreToken(p.profile.core);
        boolean deployCore=probedCoreToken==null?!runtimeCoreCurrent(coreToken):!coreToken.equals(probedCoreToken);
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
        if(deployCore){
            cmd.append("; cp ").append(RootBridge.quote(binary.getAbsolutePath())).append(' ').append(RootBridge.quote(binTmp))
                    .append("; chmod 700 ").append(RootBridge.quote(binTmp)).append("; chown 0:0 ").append(RootBridge.quote(binTmp))
                    .append("; mv -f ").append(RootBridge.quote(binTmp)).append(' ').append(RootBridge.quote(BIN))
                    .append("; printf %s ").append(RootBridge.quote(coreToken)).append(" > ").append(RootBridge.quote(CORE_TOKEN))
                    .append("; chmod 600 ").append(RootBridge.quote(CORE_TOKEN)).append("; chown 0:0 ").append(RootBridge.quote(CORE_TOKEN));
        }
        if(adblock!=null){
            String dst=ROOT+"/run/ruleset/hetu-adblock.txt",tmp=dst+".new";
            String allowDst=ROOT+"/run/ruleset/hetu-adblock-allow.txt",allowTmp=allowDst+".new";
            String revisionFile=ROOT+"/run/state/adblock.revision";
            cmd.append("; if [ ! -f ").append(RootBridge.quote(dst))
                    .append(" ] || [ ! -f ").append(RootBridge.quote(allowDst))
                    .append(" ] || [ \"$(cat ").append(RootBridge.quote(revisionFile)).append(" 2>/dev/null)\" != ")
                    .append(RootBridge.quote(p.adblock.revision)).append(" ]; then")
                    .append(" cp ").append(RootBridge.quote(adblock.getAbsolutePath())).append(' ').append(RootBridge.quote(tmp))
                    .append("; chmod 600 ").append(RootBridge.quote(tmp)).append("; chown 0:0 ").append(RootBridge.quote(tmp))
                    .append("; mv -f ").append(RootBridge.quote(tmp)).append(' ').append(RootBridge.quote(dst));
            if(adblockAllow!=null){
                cmd.append("; cp ").append(RootBridge.quote(adblockAllow.getAbsolutePath())).append(' ').append(RootBridge.quote(allowTmp))
                        .append("; chmod 600 ").append(RootBridge.quote(allowTmp)).append("; chown 0:0 ").append(RootBridge.quote(allowTmp))
                        .append("; mv -f ").append(RootBridge.quote(allowTmp)).append(' ').append(RootBridge.quote(allowDst));
            }
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
        RootBridge.Result r=RootBridge.rootShell(context,cmd.toString(),45000L);
        if(!r.ok())throw new IOException("无法安装 Root 运行文件："+r.output.trim());
    }

    private File coreFile(ProxyRuntimeProfile.Core core,File stage)throws IOException{
        if(cores.installed(core))return cores.file(core);
        if(core!=ProxyRuntimeProfile.Core.MIHOMO)throw new IOException(core.label+" 尚未安装核心");
        File out=new File(stage,"mihomo");copyAsset(rootBinaryAsset(),out,true);return out;
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
