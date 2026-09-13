package io.github.xgl34222220.bichen;

import android.content.Context;
import android.os.Build;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** BoxProxy-style Root control plane for modes that are actually wired. */
final class RootProxyManager {
    private static final String ROOT="/data/adb/bichen/proxy";
    private static final String BIN=ROOT+"/bin/core";
    private static final String CONFIG=ROOT+"/run/state/startup-config";
    private static final String SCRIPT=ROOT+"/proxy-root.sh";
    private final Context context;
    private final ProxyCoreStore cores;
    private final ProxyConfigLibrary configs;

    interface Progress { void onStage(String text); }

    static final class Prepared {
        final ProxyRuntimeProfile profile;
        final ProxyConfigLibrary.Entry source;
        final String startup;
        final int tproxyPort,redirectPort;
        Prepared(ProxyRuntimeProfile p,ProxyConfigLibrary.Entry source,String startup,int tp,int rp){this.profile=p;this.source=source;this.startup=startup;this.tproxyPort=tp;this.redirectPort=rp;}
    }

    RootProxyManager(Context c){context=c.getApplicationContext();cores=new ProxyCoreStore(context);configs=new ProxyConfigLibrary(context);}

    private static void stage(Progress progress,String text){if(progress!=null)progress.onStage(text);}

    Prepared prepare(ProxyRuntimeProfile profile)throws Exception{
        RootBridge.requireWorkerThread();
        if(profile.core!=ProxyRuntimeProfile.Core.MIHOMO&&profile.core!=ProxyRuntimeProfile.Core.MIHOMO_SMART)
            throw new IOException(profile.core.label+" 的运行后端还未接入；不会假报启动成功");
        if(profile.mode!=ProxyRuntimeProfile.Mode.TPROXY&&profile.mode!=ProxyRuntimeProfile.Mode.REDIRECT&&profile.mode!=ProxyRuntimeProfile.Mode.ENHANCE)
            throw new IOException(profile.mode.label+" 的 Root 后端还未接入；当前先完成 TPROXY / Redirect / Enhance");
        ProxyConfigLibrary.Entry selected=configs.selected(profile.core);
        if(selected==null)throw new IOException("请先为 "+profile.core.label+" 选择配置文件");
        String source=configs.read(selected);
        if(source==null||source.trim().isEmpty())throw new IOException("源配置为空");
        if(source.getBytes(StandardCharsets.UTF_8).length>4*1024*1024)throw new IOException("配置超过 4 MiB");
        MihomoStartupConfig.Result generated=MihomoStartupConfig.generate(source,profile);
        writeStartupCopy(generated.yaml);
        return new Prepared(profile,selected,generated.yaml,generated.tproxyPort,generated.redirectPort);
    }

    JSONObject preflight(Prepared p)throws Exception{
        installRuntimeFiles(p,false);
        return runJson("preflight",p.profile.mode.id,String.valueOf(p.tproxyPort),String.valueOf(p.redirectPort),p.profile.ipv6.id);
    }

    JSONObject start(ProxyRuntimeProfile profile)throws Exception{return start(profile,null);}

    JSONObject start(ProxyRuntimeProfile profile,Progress progress)throws Exception{
        stage(progress,"检查配置文件…");
        Prepared p=prepare(profile);
        stage(progress,"部署 Root 核心与启动配置…");
        installRuntimeFiles(p,true);
        stage(progress,"用 Mihomo 校验最终启动配置…");
        validateRuntimeConfig();
        stage(progress,"检查 Root、iptables 与透明代理能力…");
        JSONObject pre=runJson("preflight",profile.mode.id,String.valueOf(p.tproxyPort),String.valueOf(p.redirectPort),profile.ipv6.id);
        if(!pre.optBoolean("ok"))throw new IOException(pre.optString("message","Root 代理预检失败"));
        stage(progress,"启动 Mihomo 与透明代理规则…");
        JSONObject result=runJson("start",BIN,CONFIG,profile.mode.id,String.valueOf(p.tproxyPort),String.valueOf(p.redirectPort),profile.ipv6.id);
        if(!result.optBoolean("ok"))throw new IOException(result.optString("message","Root 代理启动失败"));
        stage(progress,"确认核心运行状态…");
        JSONObject state=status();
        if(!state.optBoolean("running",false)){
            String detail=diagnostics();
            throw new IOException("启动命令已返回，但核心未保持运行"+(detail.isEmpty()?"":"："+detail));
        }
        return result.put("sourceConfig",p.source.name).put("core",profile.core.id).put("mode",profile.mode.id);
    }

    JSONObject stop()throws Exception{return stop(null);}
    JSONObject stop(Progress progress)throws Exception{
        stage(progress,"停止核心并清理透明代理规则…");
        JSONObject result=runJsonAllowMissing("stop",new JSONObject().put("ok",true).put("running",false).put("state","idle").put("message","Root 代理未运行"));
        stage(progress,"确认网络规则已恢复…");
        return result;
    }
    JSONObject status()throws Exception{return runJsonAllowMissing("status",new JSONObject().put("ok",true).put("running",false).put("state","idle").put("message","尚未启动"));}

    String diagnostics(){
        try{
            RootBridge.requireWorkerThread();
            String command="echo '--- root ---'; id; echo '--- runtime ---'; ls -l "+RootBridge.quote(ROOT)+" "+RootBridge.quote(ROOT+"/bin")+" "+RootBridge.quote(ROOT+"/run")+" 2>&1; echo '--- log ---'; tail -n 35 "+RootBridge.quote(ROOT+"/run/core.log")+" 2>&1 || true";
            RootBridge.Result r=RootBridge.rootShell(context,command,10_000L);
            String text=r.output.trim().replace('\n',' ');
            return text.length()>520?text.substring(text.length()-520):text;
        }catch(Exception ignored){return"";}
    }

    String startupConfig()throws IOException{
        File f=startupFile();if(!f.isFile())throw new IOException("尚未生成启动配置");byte[] bytes=Files.readAllBytes(f.toPath());return new String(bytes,StandardCharsets.UTF_8);
    }
    File startupFile(){return new File(context.getFilesDir(),"proxy/run/state/startup-config");}

    private void writeStartupCopy(String text)throws IOException{
        File target=startupFile(),dir=target.getParentFile();if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法创建启动状态目录");File tmp=new File(dir,"startup-config.new");
        try(FileOutputStream out=new FileOutputStream(tmp,false)){out.write(text.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        try{Files.move(tmp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(Exception e){if(!tmp.renameTo(target)){tmp.delete();throw new IOException("无法保存最终启动配置");}}
    }

    private void validateRuntimeConfig()throws Exception{
        RootBridge.requireWorkerThread();
        String command="mkdir -p "+RootBridge.quote(ROOT+"/run")+" && "+RootBridge.quote(BIN)+" -t -d "+RootBridge.quote(ROOT+"/run")+" -f "+RootBridge.quote(CONFIG);
        RootBridge.Result result=RootBridge.rootShell(context,command,30_000L);
        if(result.ok())return;
        String detail=result.output==null?"":result.output.trim().replace('\n',' ');
        if(detail.length()>600)detail=detail.substring(detail.length()-600);
        throw new IOException("Mihomo 最终启动配置校验失败"+(detail.isEmpty()?"":"："+detail));
    }

    private JSONObject runJsonAllowMissing(String action,JSONObject missing)throws Exception{
        RootBridge.requireWorkerThread();
        String command="if [ -x "+RootBridge.quote(SCRIPT)+" ]; then exec "+RootBridge.quote(SCRIPT)+" "+RootBridge.quote(action)+"; else printf '%s\\n' "+RootBridge.quote(missing.toString())+"; fi";
        RootBridge.Result result=RootBridge.rootShell(context,command,15_000L);
        JSONObject json;
        try{json=RootBridge.parseObject(result.output.trim());}catch(Exception malformed){throw new IOException(result.output.isEmpty()?"Root 授权或状态查询不可用":result.output);}
        if(result.code!=0)throw new IOException(json.optString("message","Root 状态查询失败，退出码 "+result.code));
        return json;
    }

    private JSONObject runJson(String...args)throws Exception{
        RootBridge.requireWorkerThread();StringBuilder cmd=new StringBuilder("exec ").append(RootBridge.quote(SCRIPT));for(String a:args)cmd.append(' ').append(RootBridge.quote(a));RootBridge.Result result=RootBridge.rootShell(context,cmd.toString(),45_000L);JSONObject json;
        try{json=RootBridge.parseObject(result.output.trim());}catch(Exception malformed){throw new IOException(result.output.isEmpty()?"Root 控制器没有返回状态":result.output);}
        if(result.code!=0)throw new IOException(json.optString("message","Root 代理命令失败，退出码 "+result.code));return json;
    }

    private void installRuntimeFiles(Prepared prepared,boolean includeConfig)throws Exception{
        RootBridge.requireWorkerThread();File stage=new File(context.getCacheDir(),"proxy-root-stage");if(!stage.isDirectory()&&!stage.mkdirs())throw new IOException("无法创建 Root 代理临时目录");File script=new File(stage,"proxy-root.sh");copyAsset("proxy-root.sh",script,true);
        File binary=coreFile(prepared.profile.core,stage);
        File cfg=new File(stage,"startup-config");if(includeConfig)Files.write(cfg.toPath(),prepared.startup.getBytes(StandardCharsets.UTF_8));
        StringBuilder command=new StringBuilder("set -e; mkdir -p ").append(RootBridge.quote(ROOT+"/bin")).append(' ').append(RootBridge.quote(ROOT+"/run/state")).append("; ")
                .append("cp ").append(RootBridge.quote(script.getAbsolutePath())).append(' ').append(RootBridge.quote(SCRIPT)).append("; chmod 700 ").append(RootBridge.quote(SCRIPT)).append("; chown 0:0 ").append(RootBridge.quote(SCRIPT)).append("; ")
                .append("cp ").append(RootBridge.quote(binary.getAbsolutePath())).append(' ').append(RootBridge.quote(BIN)).append("; chmod 700 ").append(RootBridge.quote(BIN)).append("; chown 0:0 ").append(RootBridge.quote(BIN));
        if(includeConfig)command.append("; cp ").append(RootBridge.quote(cfg.getAbsolutePath())).append(' ').append(RootBridge.quote(CONFIG)).append("; chmod 600 ").append(RootBridge.quote(CONFIG)).append("; chown 0:0 ").append(RootBridge.quote(CONFIG));
        RootBridge.Result installed=RootBridge.rootShell(context,command.toString(),45_000L);if(!installed.ok())throw new IOException("无法安装 Root 运行文件："+installed.output.trim());
    }

    private File coreFile(ProxyRuntimeProfile.Core core,File stage)throws IOException{
        if(cores.installed(core))return cores.file(core);
        if(core!=ProxyRuntimeProfile.Core.MIHOMO)throw new IOException(core.label+" 尚未安装核心，请先在核心管理中导入或更新");
        File out=new File(stage,"mihomo");copyAsset(rootBinaryAsset(),out,true);return out;
    }

    private String rootBinaryAsset()throws IOException{
        for(String abi:Build.SUPPORTED_ABIS){String v=abi.toLowerCase(Locale.ROOT);if(v.equals("arm64-v8a"))return"mihomo-root/arm64-v8a/mihomo";if(v.equals("x86_64"))return"mihomo-root/x86_64/mihomo";}
        throw new IOException("当前 CPU 架构没有内置 Mihomo："+Arrays.toString(Build.SUPPORTED_ABIS));
    }
    private void copyAsset(String name,File out,boolean executable)throws IOException{
        try(InputStream in=context.getAssets().open(name);FileOutputStream fos=new FileOutputStream(out,false)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)fos.write(b,0,n);fos.getFD().sync();}
        if(!out.setReadable(true,true)||(executable&&!out.setExecutable(true,true)))throw new IOException("无法设置运行文件权限");
    }
}
