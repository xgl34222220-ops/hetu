package io.github.xgl34222220.bichen;

import java.io.IOException;
import java.util.regex.*;

/** Builds a private Mihomo startup copy. The selected source config is never edited. */
final class MihomoStartupConfig {
    static final int TPROXY_PORT=9898;
    static final int REDIRECT_PORT=9797;
    static final int CONTROLLER_PORT=19090;
    static final String EXTERNAL_UI_DIR="ui";
    // Keep the dashboard on Mihomo's own /ui/ origin. This avoids hosted-dashboard
    // CORS / Private Network Access failures in Android WebView and is also much faster.
    static final String EXTERNAL_UI_URL="https://github.com/Zephyruso/zashboard/releases/latest/download/dist-no-fonts.zip";

    static final class Result {
        final String yaml;
        final int tproxyPort,redirectPort;
        Result(String yaml,int tproxyPort,int redirectPort){this.yaml=yaml;this.tproxyPort=tproxyPort;this.redirectPort=redirectPort;}
    }

    static Result generate(String source,ProxyRuntimeProfile profile)throws IOException{
        return generate(source,profile,"bichen-test-controller");
    }

    static Result generate(String source,ProxyRuntimeProfile profile,String controllerSecret)throws IOException{
        if(source==null||source.trim().isEmpty())throw new IOException("源配置为空");
        if(controllerSecret==null||controllerSecret.trim().isEmpty())throw new IOException("控制接口密钥为空");
        if(profile.core!=ProxyRuntimeProfile.Core.MIHOMO&&profile.core!=ProxyRuntimeProfile.Core.MIHOMO_SMART)
            throw new IOException("当前启动配置生成器只支持 Mihomo / Mihomo Smart");
        ProxyRuntimeProfile.Capability capability=profile.capability();
        if(!capability.available)throw new IOException(capability.reason.isEmpty()?"当前核心不支持该运行模式":capability.reason);

        int sourceTp=detectScalarPort(source,"tproxy-port");
        int sourceRp=detectScalarPort(source,"redir-port");
        String yaml=normalize(source);
        // Runtime mode is authoritative. Source config stays untouched, while conflicting
        // listeners/TUN and old or externally exposed controller settings are isolated here.
        yaml=removeTopLevelKey(yaml,"listeners");
        yaml=removeTopLevelScalar(yaml,"global-client-fingerprint");
        yaml=removeTopLevelScalar(yaml,"tproxy-port");
        yaml=removeTopLevelScalar(yaml,"redir-port");
        yaml=removeTopLevelBlock(yaml,"tun");
        yaml=removeTopLevelScalar(yaml,"external-controller");
        yaml=removeTopLevelScalar(yaml,"external-controller-tls");
        yaml=removeTopLevelBlock(yaml,"external-controller-cors");
        yaml=removeTopLevelScalar(yaml,"secret");
        // Bichen owns its loopback-only WebUI runtime, so a source profile cannot replace it
        // with a remote/exposed path or dashboard URL. The source file itself remains untouched.
        yaml=removeTopLevelScalar(yaml,"external-ui");
        yaml=removeTopLevelScalar(yaml,"external-ui-name");
        yaml=removeTopLevelScalar(yaml,"external-ui-url");
        // Android TPROXY connections need package/process metadata for the connection panel.
        // strict is Mihomo's normal process-matching mode; keep the user's source file untouched.
        yaml=removeTopLevelScalar(yaml,"find-process-mode");

        int tp=0,rp=0;
        StringBuilder override=new StringBuilder();
        override.append("\n# --- Bichen runtime-mode isolation; source file is unchanged ---\n");
        switch(profile.mode){
            case TPROXY:
                tp=profile.autoOverwrite||sourceTp==0?TPROXY_PORT:sourceTp;
                override.append("tproxy-port: ").append(tp).append('\n');
                override.append("redir-port: 0\n");
                override.append("tun:\n  enable: false\n");
                break;
            case REDIRECT:
                rp=profile.autoOverwrite||sourceRp==0?REDIRECT_PORT:sourceRp;
                override.append("redir-port: ").append(rp).append('\n');
                override.append("tproxy-port: 0\n");
                override.append("tun:\n  enable: false\n");
                break;
            case ENHANCE:
                tp=profile.autoOverwrite||sourceTp==0?TPROXY_PORT:sourceTp;
                rp=profile.autoOverwrite||sourceRp==0?REDIRECT_PORT:sourceRp;
                override.append("redir-port: ").append(rp).append('\n');
                override.append("tproxy-port: ").append(tp).append('\n');
                override.append("tun:\n  enable: false\n");
                break;
            case TUN:
            case MIXED:
                throw new IOException(profile.mode.label+" 的 Root 启动配置还在接入，当前不假报支持");
            case EBPF:
                throw new IOException("eBPF 必须使用兼容核心和 eBPF 入站，不能用普通 Mihomo 启动配置冒充");
        }
        override.append("find-process-mode: strict\n");
        override.append("external-controller: 127.0.0.1:").append(CONTROLLER_PORT).append('\n');
        // Keep standards-correct CORS settings for external tools, even though the bundled
        // dashboard deliberately uses the same loopback origin and therefore does not depend on CORS.
        override.append("external-controller-cors:\n");
        override.append("  allow-origins:\n");
        override.append("    - '*'\n");
        override.append("  allow-private-network: true\n");
        override.append("secret: '").append(controllerSecret.replace("'","''")).append("'\n");
        override.append("external-ui: ").append(EXTERNAL_UI_DIR).append('\n');
        override.append("external-ui-url: '").append(EXTERNAL_UI_URL).append("'\n");
        override.append("# --- end Bichen runtime-mode isolation ---\n");
        return new Result(yaml+override,tp,rp);
    }

    static int detectScalarPort(String source,String key){
        Pattern p=Pattern.compile("(?m)^"+Pattern.quote(key)+"\\s*:\\s*([0-9]{1,5})\\s*(?:#.*)?$");Matcher m=p.matcher(source==null?"":source);if(!m.find())return 0;try{int v=Integer.parseInt(m.group(1));return v>0&&v<=65535?v:0;}catch(Exception ignored){return 0;}
    }
    private static String normalize(String s){return s.replace("\r\n","\n").replace('\r','\n');}
    private static String removeTopLevelScalar(String source,String key){StringBuilder out=new StringBuilder();String[] lines=source.split("\n",-1);Pattern p=Pattern.compile("^"+Pattern.quote(key)+"\\s*:");for(String line:lines){if(indent(line)==0&&p.matcher(line).find())continue;out.append(line).append('\n');}return trimOne(out.toString());}
    private static String removeTopLevelKey(String source,String key){
        String[] lines=source.split("\n",-1);StringBuilder out=new StringBuilder();boolean skipping=false;Pattern start=Pattern.compile("^"+Pattern.quote(key)+"\\s*:(.*)$");Pattern nextKey=Pattern.compile("^[A-Za-z0-9_.-]+\\s*:");
        for(String line:lines){int ind=indent(line);String t=line.trim();if(!skipping&&ind==0){Matcher m=start.matcher(line);if(m.find()){String rest=m.group(1).trim();skipping=rest.isEmpty()||rest.startsWith("#");continue;}}if(skipping){if(t.isEmpty())continue;if(ind>0)continue;if(t.startsWith("-"))continue;if(t.startsWith("#")){out.append(line).append('\n');continue;}if(nextKey.matcher(line).find())skipping=false;else continue;}out.append(line).append('\n');}
        return trimOne(out.toString());
    }
    private static String removeTopLevelBlock(String source,String key){String[] lines=source.split("\n",-1);StringBuilder out=new StringBuilder();boolean skipping=false;Pattern start=Pattern.compile("^"+Pattern.quote(key)+"\\s*:\\s*(?:#.*)?$");for(String line:lines){int ind=indent(line);String t=line.trim();if(!skipping&&ind==0&&start.matcher(line).find()){skipping=true;continue;}if(skipping){if(t.isEmpty())continue;if(ind>0)continue;if(t.startsWith("#")){out.append(line).append('\n');continue;}skipping=false;}out.append(line).append('\n');}return trimOne(out.toString());}
    private static int indent(String s){int n=0;while(n<s.length()&&(s.charAt(n)==' '||s.charAt(n)=='\t'))n++;return n;}
    private static String trimOne(String s){while(s.endsWith("\n\n\n"))s=s.substring(0,s.length()-1);return s;}
}