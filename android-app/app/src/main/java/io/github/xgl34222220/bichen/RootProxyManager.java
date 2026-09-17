package io.github.xgl34222220.bichen;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Root transparent-proxy control plane plus private localhost Clash API bootstrap. */
final class RootProxyManager {
    private static final String ROOT="/data/adb/bichen/proxy";
    private static final String BIN=ROOT+"/bin/core";
    private static final String CONFIG=ROOT+"/run/state/startup-config";
    private static final String SCRIPT=ROOT+"/proxy-root.sh";
    private final Context context;
    private final SharedPreferences prefs;
    private final ProxyCoreStore cores;
    private final ProxyConfigLibrary configs;

    interface Progress{void onStage(String text);}
    static final class Prepared{
        final ProxyRuntimeProfile profile;
        final ProxyConfigLibrary.Entry source;
        final RootProxyPolicy policy;
        final ProxyAdblockRules.Snapshot adblock;
        final String startup;
        final int tproxyPort,redirectPort;
        Prepared(ProxyRuntimeProfile p,ProxyConfigLibrary.Entry s,RootProxyPolicy policy,ProxyAdblockRules.Snapshot adblock,String y,int tp,int rp){
            profile=p;source=s;this.policy=policy;this.adblock=adblock;startup=y;tproxyPort=tp;redirectPort=rp;
        }
    }

    RootProxyManager(Context c){
        context=c.getApplicationContext();
        prefs=context.getSharedPreferences("bichen",0);
        cores=new ProxyCoreStore(context);
        configs=new ProxyConfigLibrary(context);
    }
    private static void stage(Progress p,String text){if(p!=null)p.onStage(text);}
    private static String bit(boolean value){return value?"1":"0";}
    String controllerSecret(){
        String s=prefs.getString("proxyControllerSecret","");
        if(s==null||s.isEmpty()){
            s=UUID.randomUUID().toString().replace("-","")+Long.toHexString(System.nanoTime());
            prefs.edit().putString("proxyControllerSecret",s).commit();
        }
        return s;
    }

    Prepared prepare(ProxyRuntimeProfile profile)throws Exception{
        RootBridge.requireWorkerThread();
        if(profile.core!=ProxyRuntimeProfile.Core.MIHOMO&&profile.core!=ProxyRuntimeProfile.Core.MIHOMO_SMART)
            throw new IOException(profile.core.label+" 的运行后端还未接入");
        ProxyRuntimeProfile.Capability capability=profile.capability();
        if(!capability.available)throw new IOException(capability.reason.isEmpty()?profile.mode.label+" 暂不可用":capability.reason);
        if(profile.mode!=ProxyRuntimeProfile.Mode.TPROXY&&profile.mode!=ProxyRuntimeProfile.Mode.REDIRECT&&profile.mode!=ProxyRuntimeProfile.Mode.ENHANCE)
            throw new IOException(profile.mode.label+" 的统一 Root 后端还未接入");

        RootProxyPolicy policy=RootProxyPolicy.load(context,prefs,profile);
        ProxyConfigLibrary.Entry selected=configs.selected(profile.core);
        if(selected==null)throw new IOException("请先为 "+profile.core.label+" 选择配置文件");
        String source=configs.read(selected);
        if(source==null||source.trim().isEmpty())throw new IOException("源配置为空");
        if(source.getBytes(StandardCharsets.UTF_8).length>4*1024*1024)throw new IOException("配置超过 4 MiB");
        ProxyAdblockRules.Snapshot adblock=profile.adblockChain?ProxyAdblockRules.export(context):null;
        if(profile.adblockChain&&adblock.count<=0)throw new IOException("代理串联去广告已开启，但当前没有有效广告规则；请先启用或更新规则源");
        MihomoStartupConfig.Result generated=MihomoStartupConfig.generate(source,profile,controllerSecret());
        writeStartupCopy(generated.yaml);
        return new Prepared(profile,selected,policy,adblock,generated.yaml,generated.tproxyPort,generated.redirectPort);
    }

    JSONObject preflight(Prepared p)throws Exception{
        installRuntimeFiles(p,false);
        RootProxyPolicy policy=p.policy;
        return runJson("preflight",
                p.profile.mode.id,String.valueOf(p.tproxyPort),String.valueOf(p.redirectPort),p.profile.ipv6.id,
                bit(p.profile.tcp),bit(p.profile.udp),p.profile.dnsHijack.id,bit(p.profile.quicBlocked),
                String.valueOf(MihomoStartupConfig.DNS_PORT),String.valueOf(MihomoStartupConfig.CONTROLLER_PORT),
                policy.appScope,policy.uidRanges,bit(policy.sharedNetwork),bit(policy.killSwitch),policy.cidrs,policy.interfaces);
    }

    JSONObject start(ProxyRuntimeProfile p)throws Exception{return start(p,null);}
    JSONObject start(ProxyRuntimeProfile profile,Progress progress)throws Exception{
        stage(progress,"检查配置、应用范围与绕过策略…");
        Prepared p=prepare(profile);
        RootProxyPolicy policy=p.policy;
        stage(progress,"部署 Root 核心与事务控制器…");
        installRuntimeFiles(p,true);
        stage(progress,"用 Mihomo 校验最终启动配置…");
        validateRuntimeConfig();
        stage(progress,"检查 TPROXY / Redirect / UID / IPv6 能力…");
        JSONObject pre=runJson("preflight",
                profile.mode.id,String.valueOf(p.tproxyPort),String.valueOf(p.redirectPort),profile.ipv6.id,
                bit(profile.tcp),bit(profile.udp),profile.dnsHijack.id,bit(profile.quicBlocked),
                String.valueOf(MihomoStartupConfig.DNS_PORT),String.valueOf(MihomoStartupConfig.CONTROLLER_PORT),
                policy.appScope,policy.uidRanges,bit(policy.sharedNetwork),bit(policy.killSwitch),policy.cidrs,policy.interfaces);
        if(!pre.optBoolean("ok"))throw new IOException(pre.optString("message","Root 代理预检失败"));

        boolean chainEntered=false;
        if(profile.adblockChain){
            stage(progress,"切换到代理串联去广告，暂停独立 DNS / hosts 过滤…");
            ProxyAdblockCoordinator.enter(context);chainEntered=true;
        }
        stage(progress,"启动核心并等待监听端口就绪…");
        JSONObject result;
        try{result=runJson("start",
                BIN,CONFIG,profile.mode.id,String.valueOf(p.tproxyPort),String.valueOf(p.redirectPort),profile.ipv6.id,
                bit(profile.tcp),bit(profile.udp),profile.dnsHijack.id,bit(profile.quicBlocked),
                String.valueOf(MihomoStartupConfig.DNS_PORT),String.valueOf(MihomoStartupConfig.CONTROLLER_PORT),
                policy.appScope,policy.uidRanges,bit(policy.sharedNetwork),bit(policy.killSwitch),policy.cidrs,policy.interfaces);
            if(!result.optBoolean("ok"))throw new IOException(result.optString("message","Root 代理启动失败"));
        }catch(Exception startFailure){if(chainEntered)ProxyAdblockCoordinator.exit(context);throw startFailure;}

        stage(progress,"确认策略控制接口、守护与回滚状态…");
        try{
            JSONObject state=status();
            if(!state.optBoolean("running",false))
                throw new IOException("启动命令已返回，但核心未保持运行"+(diagnostics().isEmpty()?"":"："+diagnostics()));
            new MihomoControllerClient(context).waitReady(4500);
        }catch(Exception verify){
            try{stop();}catch(Exception ignored){}
            throw new IOException("核心启动后健康检查失败，网络已回滚："+(verify.getMessage()==null?"控制接口不可用":verify.getMessage()),verify);
        }

        String warning=policy.warning();
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
                .put("sharedNetwork",policy.sharedNetwork)
                .put("killSwitch",policy.killSwitch)
                .put("cnIpDirect",profile.cnIpDirect)
                .put("adblockChain",profile.adblockChain)
                .put("adblockRuleCount",p.adblock==null?0:p.adblock.count)
                .put("adblockRevision",p.adblock==null?"":p.adblock.revision)
                .put("bypassCidrs",policy.cidrs)
                .put("bypassInterfaces",policy.interfaces);
        if(!warning.isEmpty())result.put("warning",warning);
        prefs.edit().putBoolean("proxyRootWanted",true).apply();
        return result;
    }

    JSONObject stop()throws Exception{return stop(null);}
    JSONObject stop(Progress progress)throws Exception{
        stage(progress,"停止守护、Kill Switch、核心并回滚透明代理规则…");
        JSONObject r=runJsonAllowMissing("stop",new JSONObject().put("ok",true).put("running",false).put("state","idle").put("message","Root 代理未运行"));
        ProxyAdblockCoordinator.exit(context);
        stage(progress,"网络规则、广告过滤接管与临时 IPv6 状态已恢复");
        prefs.edit().putBoolean("proxyRootWanted",false).apply();
        return r;
    }
    JSONObject status()throws Exception{
        return runJsonAllowMissing("status",new JSONObject().put("ok",true).put("running",false).put("state","idle").put("message","尚未启动"));
    }

    String diagnostics(){
        try{
            RootBridge.requireWorkerThread();
            String cmd="echo '--- root ---'; id; echo '--- runtime ---'; ls -l "+RootBridge.quote(ROOT)+" "+RootBridge.quote(ROOT+"/bin")+" "+RootBridge.quote(ROOT+"/run")+" 2>&1; "+
                    "echo '--- session ---'; cat "+RootBridge.quote(ROOT+"/run/session.state")+" 2>/dev/null || true; "+
                    "echo '--- cnip cache ---'; ls -lh "+RootBridge.quote(ROOT+"/run/ruleset")+" 2>&1 || true; "+
                    "echo '--- sockets ---'; (ss -lntup 2>/dev/null || netstat -lntup 2>/dev/null || true) | tail -n 35; "+
                    "echo '--- policy ---'; ip rule show 2>/dev/null | tail -n 30; ip -6 rule show 2>/dev/null | tail -n 20; "+
                    "echo '--- bichen chains ---'; iptables-save 2>/dev/null | grep BICHEN | tail -n 70; ip6tables-save 2>/dev/null | grep BICHEN | tail -n 55; "+
                    "echo '--- crash ---'; cat "+RootBridge.quote(ROOT+"/run/last-crash")+" 2>/dev/null || true; "+
                    "echo '--- log ---'; tail -n 55 "+RootBridge.quote(ROOT+"/run/core.log")+" 2>&1 || true";
            RootBridge.Result r=RootBridge.rootShell(context,cmd,12000L);
            String t=r.output.trim().replace('\n',' ');
            return t.length()>1800?t.substring(t.length()-1800):t;
        }catch(Exception ignored){return"";}
    }

    String startupConfig()throws IOException{
        File f=startupFile();
        if(!f.isFile())throw new IOException("尚未生成启动配置");
        return new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8);
    }
    File startupFile(){return new File(context.getFilesDir(),"proxy/run/state/startup-config");}

    private void writeStartupCopy(String text)throws IOException{
        File target=startupFile(),dir=target.getParentFile();
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法创建启动状态目录");
        File tmp=new File(dir,"startup-config.new");
        try(FileOutputStream out=new FileOutputStream(tmp,false)){
            out.write(text.getBytes(StandardCharsets.UTF_8));out.getFD().sync();
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

    private JSONObject runJsonAllowMissing(String action,JSONObject missing)throws Exception{
        RootBridge.requireWorkerThread();
        String cmd="if [ -x "+RootBridge.quote(SCRIPT)+" ]; then exec "+RootBridge.quote(SCRIPT)+" "+RootBridge.quote(action)+"; else printf '%s\\n' "+RootBridge.quote(missing.toString())+"; fi";
        RootBridge.Result r=RootBridge.rootShell(context,cmd,20000L);
        JSONObject j;
        try{j=RootBridge.parseObject(r.output.trim());}
        catch(Exception e){throw new IOException(r.output.isEmpty()?"Root 授权或状态查询不可用":r.output);}
        if(r.code!=0)throw new IOException(j.optString("message","Root 状态查询失败，退出码 "+r.code));
        return j;
    }

    private JSONObject runJson(String...args)throws Exception{
        RootBridge.requireWorkerThread();
        StringBuilder cmd=new StringBuilder("exec ").append(RootBridge.quote(SCRIPT));
        for(String a:args)cmd.append(' ').append(RootBridge.quote(a==null?"":a));
        RootBridge.Result r=RootBridge.rootShell(context,cmd.toString(),55000L);
        JSONObject j;
        try{j=RootBridge.parseObject(r.output.trim());}
        catch(Exception e){throw new IOException(r.output.isEmpty()?"Root 控制器没有返回状态":r.output);}
        if(r.code!=0)throw new IOException(j.optString("message","Root 代理命令失败，退出码 "+r.code));
        return j;
    }

    private void installRuntimeFiles(Prepared p,boolean includeConfig)throws Exception{
        RootBridge.requireWorkerThread();
        File stage=new File(context.getCacheDir(),"proxy-root-stage");
        if(!stage.isDirectory()&&!stage.mkdirs())throw new IOException("无法创建 Root 代理临时目录");
        File script=new File(stage,"proxy-root.sh");
        copyAsset("proxy-root-v3.sh",script,true);
        File binary=coreFile(p.profile.core,stage);
        File cfg=new File(stage,"startup-config");
        File adblock=p.adblock==null?null:p.adblock.file;
        File cn4=null,cn6=null;
        if(p.profile.cnIpDirect){
            cn4=new File(stage,"bichen-cn-v4.txt");copyAsset("cnip/bichen-cn-v4.txt",cn4,false);
            cn6=new File(stage,"bichen-cn-v6.txt");copyAsset("cnip/bichen-cn-v6.txt",cn6,false);
        }
        if(includeConfig)Files.write(cfg.toPath(),p.startup.getBytes(StandardCharsets.UTF_8));
        StringBuilder cmd=new StringBuilder("set -e; mkdir -p ")
                .append(RootBridge.quote(ROOT+"/bin")).append(' ').append(RootBridge.quote(ROOT+"/run/state")).append(' ').append(RootBridge.quote(ROOT+"/run/ruleset"))
                .append("; cp ").append(RootBridge.quote(script.getAbsolutePath())).append(' ').append(RootBridge.quote(SCRIPT))
                .append("; chmod 700 ").append(RootBridge.quote(SCRIPT)).append("; chown 0:0 ").append(RootBridge.quote(SCRIPT))
                .append("; cp ").append(RootBridge.quote(binary.getAbsolutePath())).append(' ').append(RootBridge.quote(BIN))
                .append("; chmod 700 ").append(RootBridge.quote(BIN)).append("; chown 0:0 ").append(RootBridge.quote(BIN));
        if(adblock!=null){
            String dst=ROOT+"/run/ruleset/bichen-adblock.txt",tmp=dst+".new";
            cmd.append("; cp ").append(RootBridge.quote(adblock.getAbsolutePath())).append(' ').append(RootBridge.quote(tmp))
                    .append("; chmod 600 ").append(RootBridge.quote(tmp)).append("; chown 0:0 ").append(RootBridge.quote(tmp))
                    .append("; mv -f ").append(RootBridge.quote(tmp)).append(' ').append(RootBridge.quote(dst));
        }
        if(p.profile.cnIpDirect){
            String dst4=ROOT+"/run/ruleset/bichen-cn-v4.txt",dst6=ROOT+"/run/ruleset/bichen-cn-v6.txt";
            cmd.append("; if [ ! -s ").append(RootBridge.quote(dst4)).append(" ]; then cp ").append(RootBridge.quote(cn4.getAbsolutePath())).append(' ').append(RootBridge.quote(dst4)).append("; chmod 600 ").append(RootBridge.quote(dst4)).append("; chown 0:0 ").append(RootBridge.quote(dst4)).append("; fi")
                    .append("; if [ ! -s ").append(RootBridge.quote(dst6)).append(" ]; then cp ").append(RootBridge.quote(cn6.getAbsolutePath())).append(' ').append(RootBridge.quote(dst6)).append("; chmod 600 ").append(RootBridge.quote(dst6)).append("; chown 0:0 ").append(RootBridge.quote(dst6)).append("; fi");
        }
        if(includeConfig)cmd.append("; cp ").append(RootBridge.quote(cfg.getAbsolutePath())).append(' ').append(RootBridge.quote(CONFIG))
                .append("; chmod 600 ").append(RootBridge.quote(CONFIG)).append("; chown 0:0 ").append(RootBridge.quote(CONFIG));
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
    private void copyAsset(String name,File out,boolean executable)throws IOException{
        try(InputStream in=context.getAssets().open(name);FileOutputStream fos=new FileOutputStream(out,false)){
            byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)fos.write(b,0,n);fos.getFD().sync();
        }
        if(!out.setReadable(true,true)||(executable&&!out.setExecutable(true,true)))throw new IOException("无法设置运行文件权限");
    }
}
