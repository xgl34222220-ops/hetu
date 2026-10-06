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
        check(RootBridge.rootShell(context,"set -e; test \"$(id -u)\" = 0; rm -f "
                +RootBridge.quote(ENTRY)+"; test ! -e "+RootBridge.quote(ENTRY)+"; rm -f "+RootBridge.quote(BASE+"/enabled"),10000L),"关闭开机自启失败");
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
