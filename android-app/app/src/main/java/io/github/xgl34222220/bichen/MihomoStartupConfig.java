package io.github.xgl34222220.bichen;

import java.io.IOException;
import java.util.*;
import java.util.regex.*;

/** Builds a private Mihomo startup copy. The selected source config is never edited. */
final class MihomoStartupConfig {
    static final int TPROXY_PORT=9898;
    static final int REDIRECT_PORT=9797;
    static final int DNS_PORT=1053;
    static final int CONTROLLER_PORT=19090;
    /** AOSP-reserved high fwmark bit used only by Mihomo outbound sockets. */
    static final int OUTBOUND_ROUTING_MARK=0x08000000;
    static final String EXTERNAL_UI_DIR="ui";
    static final String EXTERNAL_UI_URL="https://github.com/Zephyruso/zashboard/releases/latest/download/dist-no-fonts.zip";
    static final String CNIP_V4_URL="https://raw.githubusercontent.com/gaoyifan/china-operator-ip/ip-lists/china.txt";
    static final String CNIP_V6_URL="https://raw.githubusercontent.com/gaoyifan/china-operator-ip/ip-lists/china6.txt";
    static final String CNIP_V4_PATH="./ruleset/bichen-cn-v4.txt";
    static final String CNIP_V6_PATH="./ruleset/bichen-cn-v6.txt";
    static final String ADBLOCK_PATH=ProxyAdblockRules.PROVIDER_PATH;

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
        // Runtime mode is authoritative. The selected source config remains byte-for-byte untouched.
        yaml=removeTopLevelKey(yaml,"listeners");
        yaml=removeTopLevelScalar(yaml,"global-client-fingerprint");
        yaml=removeTopLevelScalar(yaml,"tproxy-port");
        yaml=removeTopLevelScalar(yaml,"redir-port");
        yaml=removeTopLevelBlock(yaml,"tun");
        yaml=removeTopLevelScalar(yaml,"routing-mark");
        yaml=removeTopLevelScalar(yaml,"external-controller");
        yaml=removeTopLevelScalar(yaml,"external-controller-tls");
        yaml=removeTopLevelBlock(yaml,"external-controller-cors");
        yaml=removeTopLevelScalar(yaml,"external-controller-routing-mark");
        yaml=removeTopLevelScalar(yaml,"secret");
        yaml=removeTopLevelScalar(yaml,"external-ui");
        yaml=removeTopLevelScalar(yaml,"external-ui-name");
        yaml=removeTopLevelScalar(yaml,"external-ui-url");
        yaml=removeTopLevelScalar(yaml,"find-process-mode");

        if(profile.dnsHijack==ProxyRuntimeProfile.DnsHijack.REDIRECT)
            yaml=ensureDnsListener(yaml,DNS_PORT);
        if(profile.cnIpDirect)
            yaml=ensureCnIpDirect(yaml);
        // Apply ad blocking last so its REJECT rule stays ahead of CNIP and all source routing.
        if(profile.adblockChain)
            yaml=ensureAdblock(yaml);

        int tp=0,rp=0;
        StringBuilder override=new StringBuilder();
        override.append("\n# --- Bichen runtime isolation; source file is unchanged ---\n");
        switch(profile.mode){
            case TPROXY:
                tp=profile.autoOverwrite||sourceTp==0?TPROXY_PORT:sourceTp;
                override.append("tproxy-port: ").append(tp).append('\n');
                override.append("redir-port: 0\n");
                override.append("tun:\n  enable: false\n");
                break;
            case REDIRECT:
                rp=profile.autoOverwrite||sourceRp==0?REDIRECT_PORT:sourceRp;
                if(profile.dnsHijack==ProxyRuntimeProfile.DnsHijack.TPROXY)
                    tp=profile.autoOverwrite||sourceTp==0?TPROXY_PORT:sourceTp;
                override.append("redir-port: ").append(rp).append('\n');
                override.append("tproxy-port: ").append(tp).append('\n');
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
                throw new IOException(profile.mode.label+" 的统一 Root 事务后端尚未接入，当前不假报支持");
            case EBPF:
                throw new IOException("eBPF 必须使用兼容核心和经过能力探测的 eBPF 入站，不能用普通 Mihomo 冒充");
        }
        // Mihomo marks its own outbound sockets. Root netfilter returns this bit before interception,
        // so Root/system UID traffic no longer needs to be blanket-bypassed just to avoid a core loop.
        override.append("routing-mark: ").append(OUTBOUND_ROUTING_MARK).append('\n');
        override.append("find-process-mode: strict\n");
        override.append("external-controller: 127.0.0.1:").append(CONTROLLER_PORT).append('\n');
        override.append("secret: '").append(controllerSecret.replace("'","''")).append("'\n");
        override.append("external-ui: /data/adb/bichen/proxy/run/").append(EXTERNAL_UI_DIR).append('\n');
        override.append("external-ui-url: '").append(EXTERNAL_UI_URL).append("'\n");
        override.append("# --- end Bichen runtime isolation ---\n");
        return new Result(yaml+override,tp,rp);
    }

    /** Merge the local effective Bichen ad-block snapshot into the private startup copy. */
    private static String ensureAdblock(String source)throws IOException{
        String yaml=normalize(source);
        yaml=injectAdblockProvider(yaml);
        yaml=prependAdblockRule(yaml);
        return yaml;
    }

    private static String injectAdblockProvider(String source)throws IOException{
        String[] lines=normalize(source).split("\n",-1);
        int index=-1;Matcher found=null;Pattern top=Pattern.compile("^rule-providers\\s*:(.*)$");
        for(int i=0;i<lines.length;i++){if(indent(lines[i])!=0)continue;Matcher m=top.matcher(lines[i]);if(m.find()){index=i;found=m;break;}}
        String providerBlock=
                "  "+ProxyAdblockRules.PROVIDER_NAME+":\n"+
                "    type: file\n"+
                "    behavior: domain\n"+
                "    format: text\n"+
                "    path: "+ADBLOCK_PATH+"\n";
        if(index<0){String base=trimOne(source);return base+(base.isEmpty()?"":"\n")+"rule-providers:\n"+providerBlock;}
        String rest=found.group(1).trim();
        if(rest.startsWith("#"))rest="";
        if(!rest.isEmpty()&&!rest.equals("{}"))throw new IOException("代理串联去广告需要普通 rule-providers: 配置块；当前源配置使用行内写法");
        int end=lines.length;
        for(int i=index+1;i<lines.length;i++){String t=lines[i].trim();if(t.isEmpty()||t.startsWith("#"))continue;if(indent(lines[i])==0){end=i;break;}}
        for(int i=index+1;i<end;i++)if(lines[i].trim().startsWith(ProxyAdblockRules.PROVIDER_NAME+":"))throw new IOException("源配置占用了辟尘保留的广告 provider 名称");
        StringBuilder out=new StringBuilder();
        for(int i=0;i<lines.length;i++){if(i==index){out.append("rule-providers:\n").append(providerBlock);continue;}out.append(lines[i]).append('\n');}
        return trimOne(out.toString());
    }

    private static String prependAdblockRule(String source)throws IOException{
        String[] lines=normalize(source).split("\n",-1);
        int index=-1;Matcher found=null;Pattern top=Pattern.compile("^rules\\s*:(.*)$");
        for(int i=0;i<lines.length;i++){if(indent(lines[i])!=0)continue;Matcher m=top.matcher(lines[i]);if(m.find()){index=i;found=m;break;}}
        String rule="  - RULE-SET,"+ProxyAdblockRules.PROVIDER_NAME+",REJECT\n";
        if(index<0){String base=trimOne(source);return base+(base.isEmpty()?"":"\n")+"rules:\n"+rule;}
        String rest=found.group(1).trim();
        if(rest.startsWith("#"))rest="";
        if(!rest.isEmpty()&&!rest.equals("[]"))throw new IOException("代理串联去广告需要普通 rules: 列表；当前源配置使用行内 rules 写法");
        StringBuilder out=new StringBuilder();
        for(int i=0;i<lines.length;i++){if(i==index){out.append("rules:\n").append(rule);continue;}out.append(lines[i]).append('\n');}
        return trimOne(out.toString());
    }

    /** Merge Bichen CN IP providers into the private startup copy without editing the subscription. */
    private static String ensureCnIpDirect(String source)throws IOException{
        String yaml=normalize(source);
        yaml=injectCnProviders(yaml);
        yaml=prependCnRules(yaml);
        return yaml;
    }

    private static String injectCnProviders(String source)throws IOException{
        String[] lines=normalize(source).split("\\n",-1);
        int index=-1;
        Matcher found=null;
        Pattern top=Pattern.compile("^rule-providers\\s*:(.*)$");
        for(int i=0;i<lines.length;i++){
            if(indent(lines[i])!=0)continue;
            Matcher m=top.matcher(lines[i]);
            if(m.find()){index=i;found=m;break;}
        }
        String providerBlock=
                "  bichen-cn-v4:\n"+
                "    type: http\n"+
                "    behavior: ipcidr\n"+
                "    format: text\n"+
                "    path: "+CNIP_V4_PATH+"\n"+
                "    url: '"+CNIP_V4_URL+"'\n"+
                "    interval: 86400\n"+
                "  bichen-cn-v6:\n"+
                "    type: http\n"+
                "    behavior: ipcidr\n"+
                "    format: text\n"+
                "    path: "+CNIP_V6_PATH+"\n"+
                "    url: '"+CNIP_V6_URL+"'\n"+
                "    interval: 86400\n";
        if(index<0){
            String base=trimOne(source);
            return base+(base.isEmpty()?"":"\n")+"rule-providers:\n"+providerBlock;
        }
        String rest=found.group(1).trim();
        if(!rest.isEmpty()&&!rest.equals("{}"))
            throw new IOException("中国 IP 自动直连需要普通 rule-providers: 配置块；当前源配置使用行内写法");
        int end=lines.length;
        for(int i=index+1;i<lines.length;i++){
            String t=lines[i].trim();
            if(t.isEmpty()||t.startsWith("#"))continue;
            if(indent(lines[i])==0){end=i;break;}
        }
        for(int i=index+1;i<end;i++){
            String t=lines[i].trim();
            if(t.startsWith("bichen-cn-v4:")||t.startsWith("bichen-cn-v6:"))
                throw new IOException("源配置占用了辟尘保留的 CNIP provider 名称");
        }
        StringBuilder out=new StringBuilder();
        for(int i=0;i<lines.length;i++){
            if(i==index){out.append("rule-providers:\n").append(providerBlock);continue;}
            out.append(lines[i]).append('\n');
        }
        return trimOne(out.toString());
    }

    private static String prependCnRules(String source)throws IOException{
        String[] lines=normalize(source).split("\\n",-1);
        int index=-1;
        Matcher found=null;
        Pattern top=Pattern.compile("^rules\\s*:(.*)$");
        for(int i=0;i<lines.length;i++){
            if(indent(lines[i])!=0)continue;
            Matcher m=top.matcher(lines[i]);
            if(m.find()){index=i;found=m;break;}
        }
        String cnRules="  - RULE-SET,bichen-cn-v4,DIRECT,no-resolve\n  - RULE-SET,bichen-cn-v6,DIRECT,no-resolve\n";
        if(index<0){
            String base=trimOne(source);
            return base+(base.isEmpty()?"":"\n")+"rules:\n"+cnRules;
        }
        String rest=found.group(1).trim();
        if(!rest.isEmpty()&&!rest.equals("[]"))
            throw new IOException("中国 IP 自动直连需要普通 rules: 列表；当前源配置使用行内 rules 写法");
        StringBuilder out=new StringBuilder();
        for(int i=0;i<lines.length;i++){
            if(i==index){out.append("rules:\n").append(cnRules);continue;}
            out.append(lines[i]).append('\n');
        }
        return trimOne(out.toString());
    }

    /** Ensure Mihomo's built-in DNS server owns a dedicated TCP+UDP loop used by DNS REDIRECT. */
    private static String ensureDnsListener(String source,int port)throws IOException{
        String[] lines=normalize(source).split("\n",-1);
        int start=-1,end=lines.length,childIndent=Integer.MAX_VALUE;
        Pattern startPattern=Pattern.compile("^dns\\s*:\\s*(?:#.*)?$");
        Pattern anyDns=Pattern.compile("^dns\\s*:(.*)$");
        for(int i=0;i<lines.length;i++){
            if(indent(lines[i])!=0)continue;
            Matcher any=anyDns.matcher(lines[i]);
            if(!any.find())continue;
            if(!startPattern.matcher(lines[i]).find())
                throw new IOException("DNS Redirect 需要普通 dns: 配置块；当前源配置使用了行内 DNS 写法，请改用 TPROXY DNS 或展开 dns 配置");
            start=i;break;
        }
        if(start<0){
            StringBuilder out=new StringBuilder(trimOne(source));
            if(out.length()>0&&!out.toString().endsWith("\n"))out.append('\n');
            out.append("dns:\n");
            out.append("  enable: true\n");
            out.append("  listen: 0.0.0.0:").append(port).append('\n');
            out.append("  nameserver:\n");
            out.append("    - system\n");
            return out.toString();
        }
        for(int i=start+1;i<lines.length;i++){
            String t=lines[i].trim();
            if(t.isEmpty()||t.startsWith("#"))continue;
            int ind=indent(lines[i]);
            if(ind==0){end=i;break;}
            childIndent=Math.min(childIndent,ind);
        }
        if(childIndent==Integer.MAX_VALUE)childIndent=2;
        StringBuilder out=new StringBuilder();
        for(int i=0;i<lines.length;i++){
            out.append(lines[i]).append('\n');
            if(i==start){
                out.append(spaces(childIndent)).append("enable: true\n");
                out.append(spaces(childIndent)).append("listen: 0.0.0.0:").append(port).append('\n');
            }else if(i>start&&i<end){
                int ind=indent(lines[i]);
                String t=lines[i].trim();
                if(ind==childIndent&&(t.startsWith("enable:")||t.startsWith("listen:"))){
                    int cut=lines[i].length()+1;
                    out.setLength(out.length()-cut);
                }
            }
        }
        return trimOne(out.toString());
    }

    static int detectScalarPort(String source,String key){
        Pattern p=Pattern.compile("(?m)^"+Pattern.quote(key)+"\\s*:\\s*([0-9]{1,5})\\s*(?:#.*)?$");
        Matcher m=p.matcher(source==null?"":source);if(!m.find())return 0;
        try{int v=Integer.parseInt(m.group(1));return v>0&&v<=65535?v:0;}catch(Exception ignored){return 0;}
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
    private static String spaces(int n){StringBuilder b=new StringBuilder(n);for(int i=0;i<n;i++)b.append(' ');return b.toString();}
    private static String trimOne(String s){while(s.endsWith("\n\n\n"))s=s.substring(0,s.length()-1);return s;}
}
