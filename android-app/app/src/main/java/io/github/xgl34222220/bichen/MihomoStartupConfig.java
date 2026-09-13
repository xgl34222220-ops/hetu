package io.github.xgl34222220.bichen;

import java.io.IOException;
import java.util.*;
import java.util.regex.*;

/** Builds a private Mihomo startup copy. The selected source config is never edited. */
final class MihomoStartupConfig {
    static final int TPROXY_PORT=9898;
    static final int REDIRECT_PORT=9797;

    static final class Result {
        final String yaml;
        final int tproxyPort,redirectPort;
        Result(String yaml,int tproxyPort,int redirectPort){this.yaml=yaml;this.tproxyPort=tproxyPort;this.redirectPort=redirectPort;}
    }

    static Result generate(String source, ProxyRuntimeProfile profile)throws IOException{
        if(source==null||source.trim().isEmpty())throw new IOException("源配置为空");
        if(profile.core!=ProxyRuntimeProfile.Core.MIHOMO&&profile.core!=ProxyRuntimeProfile.Core.MIHOMO_SMART)
            throw new IOException("当前启动配置生成器只支持 Mihomo / Mihomo Smart");
        ProxyRuntimeProfile.Capability capability=profile.capability();
        if(!capability.available)throw new IOException(capability.reason.isEmpty()?"当前核心不支持该运行模式":capability.reason);
        if(!profile.autoOverwrite)return new Result(source,detectScalarPort(source,"tproxy-port"),detectScalarPort(source,"redir-port"));

        String yaml=normalize(source);
        yaml=removeTopLevelScalar(yaml,"tproxy-port");
        yaml=removeTopLevelScalar(yaml,"redir-port");
        yaml=removeTopLevelBlock(yaml,"tun");
        int tp=0,rp=0;
        StringBuilder override=new StringBuilder();
        override.append("\n# --- Bichen generated startup override; source file is unchanged ---\n");
        switch(profile.mode){
            case TPROXY:
                tp=TPROXY_PORT;
                override.append("tproxy-port: ").append(tp).append('\n');
                override.append("redir-port: 0\n");
                override.append("tun:\n  enable: false\n");
                break;
            case REDIRECT:
                rp=REDIRECT_PORT;
                override.append("redir-port: ").append(rp).append('\n');
                override.append("tproxy-port: 0\n");
                override.append("tun:\n  enable: false\n");
                break;
            case ENHANCE:
                tp=TPROXY_PORT;rp=REDIRECT_PORT;
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
        override.append("# --- end Bichen startup override ---\n");
        return new Result(yaml+override,tp,rp);
    }

    static int detectScalarPort(String source,String key){
        Pattern p=Pattern.compile("(?m)^"+Pattern.quote(key)+"\\s*:\\s*([0-9]{1,5})\\s*(?:#.*)?$");Matcher m=p.matcher(source==null?"":source);if(!m.find())return 0;try{int v=Integer.parseInt(m.group(1));return v>0&&v<=65535?v:0;}catch(Exception ignored){return 0;}
    }

    private static String normalize(String s){return s.replace("\r\n","\n").replace('\r','\n');}
    private static String removeTopLevelScalar(String source,String key){
        StringBuilder out=new StringBuilder();String[] lines=source.split("\n",-1);Pattern p=Pattern.compile("^"+Pattern.quote(key)+"\\s*:");for(String line:lines){if(indent(line)==0&&p.matcher(line).find())continue;out.append(line).append('\n');}return trimOne(out.toString());
    }
    private static String removeTopLevelBlock(String source,String key){
        String[] lines=source.split("\n",-1);StringBuilder out=new StringBuilder();boolean skipping=false;Pattern start=Pattern.compile("^"+Pattern.quote(key)+"\\s*:\\s*(?:#.*)?$");
        for(String line:lines){int ind=indent(line);String t=line.trim();if(!skipping&&ind==0&&start.matcher(line).find()){skipping=true;continue;}if(skipping){if(t.isEmpty()||t.startsWith("#"))continue;if(ind>0)continue;skipping=false;}out.append(line).append('\n');}
        return trimOne(out.toString());
    }
    private static int indent(String s){int n=0;while(n<s.length()&&(s.charAt(n)==' '||s.charAt(n)=='\t'))n++;return n;}
    private static String trimOne(String s){while(s.endsWith("\n\n\n"))s=s.substring(0,s.length()-1);return s;}
}
