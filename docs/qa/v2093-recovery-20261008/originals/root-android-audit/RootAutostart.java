package io.github.xgl34222220.hetu;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Owns only Hetu's explicitly installed service.d entry and private boot files. */
final class RootAutostart {
    static final String BASE="/data/adb/hetu/boot";
    static final String ENTRY="/data/adb/service.d/hetu-autostart.sh";
    static final String ACTION_RUNNING="io.github.xgl34222220.hetu.ROOT_BOOT_RUNNING";

    static String plan(String[] runtimeArgs){
        if(runtimeArgs.length!=30)throw new IllegalArgumentException("启动参数必须完整");
        String[] args=runtimeArgs.clone();
        args[1]=BASE+"/config";
        // Never reuse config/kernel validation caches from a previous boot.
        args[19]="0";args[20]="0";
        StringBuilder out=new StringBuilder("#!/system/bin/sh\nexec /system/bin/sh /data/adb/hetu/hetu-root.sh start");
        for(String arg:args)out.append(' ').append(RootBridge.quote(arg==null?"":arg));
        return out.append('\n').toString();
    }

    static void install(Context context,File config,String[] args,File runtimeScript)throws IOException{
        RootBridge.requireWorkerThread();
        File stage=new File(context.getCacheDir(),"hetu-autostart");
        if(!stage.isDirectory()&&!stage.mkdirs())throw new IOException("无法准备开机脚本");
        File entry=new File(stage,"hetu-autostart.sh"),plan=new File(stage,"start.sh");
        try(InputStream in=context.getAssets().open("hetu-autostart.sh");OutputStream out=new FileOutputStream(entry)){
            byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
        }
        try(OutputStream out=new FileOutputStream(plan)){out.write(plan(args).getBytes(StandardCharsets.UTF_8));}
        String suffix=".new."+Long.toHexString(System.nanoTime());
        StringBuilder cmd=new StringBuilder("set -e; test \"$(id -u)\" = 0; test -x /data/adb/hetu/bin/core; mkdir -p ")
                .append(RootBridge.quote(BASE)).append(" /data/adb/service.d; chmod 700 ").append(RootBridge.quote(BASE))
                .append("; chown 0:0 ").append(RootBridge.quote(BASE));
        copy(cmd,config,BASE+"/config",suffix,"600");
        copy(cmd,plan,BASE+"/start.sh",suffix,"700");
        copy(cmd,runtimeScript,"/data/adb/hetu/hetu-root.sh",suffix,"700");
        copy(cmd,entry,ENTRY,suffix,"700");
        cmd.append("; printf 1 > ").append(RootBridge.quote(BASE+"/enabled"))
                .append("; chmod 600 ").append(RootBridge.quote(BASE+"/enabled"))
                .append("; chown 0:0 ").append(RootBridge.quote(BASE+"/enabled"))
                .append("; test -x ").append(RootBridge.quote(ENTRY))
                .append("; test -s ").append(RootBridge.quote(BASE+"/start.sh"))
                .append("; test -s ").append(RootBridge.quote(BASE+"/config"));
        check(RootBridge.rootShell(context,cmd.toString(),20000L),"开机脚本安装失败");
    }

    private static void copy(StringBuilder cmd,File source,String target,String suffix,String mode){
        String tmp=target+suffix;
        cmd.append("; cp ").append(RootBridge.quote(source.getAbsolutePath())).append(' ').append(RootBridge.quote(tmp))
                .append("; chmod ").append(mode).append(' ').append(RootBridge.quote(tmp))
                .append("; chown 0:0 ").append(RootBridge.quote(tmp))
                .append("; mv -f ").append(RootBridge.quote(tmp)).append(' ').append(RootBridge.quote(target));
    }

    static void remove(Context context)throws IOException{
        RootBridge.requireWorkerThread();
        // Revoke first. A worker already inside start/health sees the same boot
        // cancellation; removing the service.d entry alone cannot cancel it.
        String command="set -e; test \"$(id -u)\" = 0; rm -f "+RootBridge.quote(BASE+"/enabled")
                +"; test ! -e "+RootBridge.quote(BASE+"/enabled")+"; "+cancelCommand()
                +"; rm -f "+RootBridge.quote(ENTRY)+"; test ! -e "+RootBridge.quote(ENTRY)
                +"; read -r t unused < /proc/uptime; t=${t%%.*}; deadline=$((t+8)); "
                +"while [ -d "+RootBridge.quote(BASE+"/restore.lock")+" ] || [ -f "+RootBridge.quote(BASE+"/restore.owner")+" ]; do "
                +"p=$(cat "+RootBridge.quote(BASE+"/restore.lock/pid")+" 2>/dev/null || true); "
                +"if [ -f "+RootBridge.quote(BASE+"/restore.owner")+" ]; then read -r b p < "+RootBridge.quote(BASE+"/restore.owner")+"; fi; "
                +"case \"$p\" in ''|*[!0-9]*) exit 1;; esac; "
                +"kill -0 \"$p\" 2>/dev/null || break; "
                +"read -r t unused < /proc/uptime; t=${t%%.*}; [ \"$t\" -lt \"$deadline\" ] || exit 1; sleep 0.1; done";
        check(RootBridge.rootShell(context,command,10000L),"自启已撤销，但进行中的恢复尚未确认退出，请重试关闭");
    }

    private static String cancelCommand(){
        return "b=$(cat /proc/sys/kernel/random/boot_id); [ -n \"$b\" ]; "
                +"if [ -d "+RootBridge.quote(BASE)+" ]; then printf '%s\\n' \"$b\" > "+RootBridge.quote(BASE+"/stopped-boot")+"; fi; "
                +"if [ -x /data/adb/hetu/hetu-root.sh ]; then /data/adb/hetu/hetu-root.sh cancel-boot >/dev/null 2>&1 || true; fi";
    }

    static void cancelCurrentBoot(Context context)throws IOException{
        RootBridge.requireWorkerThread();
        check(RootBridge.rootShell(context,"set -e; "+cancelCommand(),3000L),"无法撤销本次开机恢复");
    }

    enum BootRestoreState { IDLE, PENDING, ACTIVE, STOPPED, TERMINAL, UNKNOWN }

    static BootRestoreState restoreState(Context context){
        RootBridge.requireWorkerThread();
        String q=RootBridge.quote(BASE);
        String cmd="b=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null) || exit 1; [ -n \"$b\" ] || exit 1; "
                +"test -d "+q+" || { printf IDLE; exit 0; }; "
                +"s=$(cat "+RootBridge.quote(BASE+"/stopped-boot")+" 2>/dev/null || true); "
                +"[ \"$s\" != \"$b\" ] || { printf STOPPED; exit 0; }; "
                +"[ -f "+RootBridge.quote(BASE+"/enabled")+" ] || { printf IDLE; exit 0; }; "
                +"if [ -f "+RootBridge.quote(BASE+"/restore.owner")+" ]; then "
                +"read -r ob p < "+RootBridge.quote(BASE+"/restore.owner")+" || exit 1; "
                +"if [ \"$ob\" = \"$b\" ]; then case \"$p\" in ''|*[!0-9]*) exit 1;; esac; "
                +"if kill -0 \"$p\" 2>/dev/null; then "
                +"c=$(tr '\\000' ' ' < \"/proc/$p/cmdline\" 2>/dev/null) || exit 1; "
                +"case \"$c\" in *'/data/adb/service.d/hetu-autostart.sh --worker'*) printf ACTIVE; exit 0;; *) exit 1;; esac; fi; fi; fi; "
                +"if [ -f "+RootBridge.quote(BASE+"/status")+" ]; then read -r sb st < "+RootBridge.quote(BASE+"/status")+" || exit 1; "
                +"if [ \"$sb\" = \"$b\" ]; then case \"$st\" in "
                +"cancelled) printf STOPPED;; running) printf IDLE;; "
                +"restore-timeout|system-timeout|missing-plan|executor-missing|clock-unavailable|network-unhealthy) printf TERMINAL;; "
                +"waiting-system|waiting-network|restoring|retrying|verifying) printf UNKNOWN;; *) printf UNKNOWN;; esac; exit 0; fi; fi; printf PENDING";
        RootBridge.Result r=RootBridge.rootShell(context,cmd,3000L);
        if(!r.ok())return BootRestoreState.UNKNOWN;
        try{return BootRestoreState.valueOf(r.output.trim());}catch(IllegalArgumentException error){return BootRestoreState.UNKNOWN;}
    }

    static boolean restoreInProgress(Context context){
        RootBridge.requireWorkerThread();
        RootBridge.Result r=RootBridge.rootShell(context,
                "b=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null); "
                +"[ -n \"$b\" ] && [ -f "+RootBridge.quote(BASE+"/enabled")+" ] || exit 1; "
                +"[ \"$b\" = \"$(cat "+RootBridge.quote(BASE+"/restore.lock/boot-id")+" 2>/dev/null)\" ] || exit 1; "
                +"p=$(cat "+RootBridge.quote(BASE+"/restore.lock/pid")+" 2>/dev/null); case \"$p\" in ''|*[!0-9]*) exit 1;; esac; "
                +"c=$(tr '\\000' ' ' < \"/proc/$p/cmdline\" 2>/dev/null); case \"$c\" in *'/data/adb/service.d/hetu-autostart.sh --worker'*) printf 1;; *) exit 1;; esac",3000L);
        return r.ok()&&"1".equals(r.output.trim());
    }

    static boolean confirmedRunningThisBoot(Context context){
        RootBridge.requireWorkerThread();
        RootBridge.Result r=RootBridge.rootShell(context,"b=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null); "
                +"[ -n \"$b\" ] && [ -f "+RootBridge.quote(BASE+"/enabled")+" ] || exit 1; "
                +"[ \"$b\" != \"$(cat "+RootBridge.quote(BASE+"/stopped-boot")+" 2>/dev/null)\" ] || exit 1; "
                +"[ \"$(cat "+RootBridge.quote(BASE+"/status")+" 2>/dev/null)\" = \"$b running\" ] && printf 1",3000L);
        return r.ok()&&"1".equals(r.output.trim());
    }

    private static void check(RootBridge.Result r,String title)throws IOException{
        if(!r.ok())throw new IOException(title+"（"+r.code+"）："+r.output.trim());
    }
}
