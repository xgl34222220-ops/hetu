package io.github.xgl34222220.hetu;

import java.io.IOException;
import java.util.*;
import java.util.regex.*;

/** Builds a private Mihomo startup copy. The selected source config is never edited. */
final class MihomoStartupConfig {
    static final int TPROXY_PORT=19898;
    static final int REDIRECT_PORT=19797;
    static final int DNS_PORT=11053;
    static final int CONTROLLER_PORT=29090;
    /** AOSP-reserved high fwmark bit used only by Mihomo outbound sockets. */
    static final int OUTBOUND_ROUTING_MARK=0x08000000;
    static final String CNIP_V4_URL="https://raw.githubusercontent.com/gaoyifan/china-operator-ip/ip-lists/china.txt";
    static final String CNIP_V6_URL="https://raw.githubusercontent.com/gaoyifan/china-operator-ip/ip-lists/china6.txt";
    static final String CNIP_V4_PATH="./ruleset/hetu-cn-v4.txt";
    static final String CNIP_V6_PATH="./ruleset/hetu-cn-v6.txt";
    static final String ADBLOCK_PATH=ProxyAdblockRules.PROVIDER_PATH;

    static final class Result {
        final String yaml;
        final int tproxyPort,redirectPort;
        Result(String yaml,int tproxyPort,int redirectPort){this.yaml=yaml;this.tproxyPort=tproxyPort;this.redirectPort=redirectPort;}
    }

    static Result generate(String source,ProxyRuntimeProfile profile)throws IOException{
        return generate(source,profile,"hetu-test-controller",CONTROLLER_PORT);
    }

    static Result generate(String source,ProxyRuntimeProfile profile,String controllerSecret)throws IOException{
        return generate(source,profile,controllerSecret,CONTROLLER_PORT);
    }

    static Result generate(String source,ProxyRuntimeProfile profile,String controllerSecret,int controllerPort)throws IOException{
        return generate(source,profile,controllerSecret,controllerPort,ProxyRuntimeProfile.AppScope.CORE,Collections.emptySet(),"");
    }

    static Result generate(String source,ProxyRuntimeProfile profile,String controllerSecret,int controllerPort,
            ProxyRuntimeProfile.AppScope appScope,Set<String> appPackages,String ebpfInterface)throws IOException{
        return generate(source,profile,controllerSecret,controllerPort,appScope,appPackages,Collections.emptySet(),ebpfInterface);
    }

    static Result generate(String source,ProxyRuntimeProfile profile,String controllerSecret,int controllerPort,
            ProxyRuntimeProfile.AppScope appScope,Set<String> appPackages,Set<String> directPackages,String ebpfInterface)throws IOException{
        if(source==null||source.trim().isEmpty())throw new IOException("源配置为空");
        if(controllerSecret==null||controllerSecret.trim().isEmpty())throw new IOException("控制接口密钥为空");
        if(controllerPort<1024||controllerPort>65535)throw new IOException("控制接口端口无效");
        if(profile.core!=ProxyRuntimeProfile.Core.MIHOMO&&profile.core!=ProxyRuntimeProfile.Core.MIHOMO_SMART)
            throw new IOException("当前启动配置生成器只支持 Mihomo / Mihomo Smart");
        ProxyRuntimeProfile.Capability capability=profile.capability();
        if(!capability.available)throw new IOException(capability.reason.isEmpty()?"当前核心不支持该运行模式":capability.reason);

        int sourceTp=detectScalarPort(source,"tproxy-port");
        int sourceRp=detectScalarPort(source,"redir-port");
        String yaml=normalize(source);
        yaml=sanitizeStrictDomainCompatibility(yaml);
        yaml=normalizeDeprecatedEncryptedDns(yaml);
        // Runtime mode is authoritative. The selected source config remains byte-for-byte untouched.
        yaml=removeTopLevelKey(yaml,"listeners");
        yaml=removeTopLevelScalar(yaml,"global-client-fingerprint");
        yaml=removeTopLevelScalar(yaml,"tproxy-port");
        yaml=removeTopLevelScalar(yaml,"redir-port");
        // TPROXY/Redirect Root mode is the only ingress owned by this private runtime.
        // Never inherit local HTTP/SOCKS/Mixed listeners from the user's subscription;
        // they are unnecessary here and commonly collide with another Clash app on 7890.
        yaml=removeTopLevelScalar(yaml,"mixed-port");
        yaml=removeTopLevelScalar(yaml,"socks-port");
        yaml=removeTopLevelScalar(yaml,"port");
        // Root TPROXY must listen on a wildcard transparent socket. The source config may
        // deliberately bind ordinary HTTP/SOCKS listeners to loopback; do not inherit that
        // restriction into Hetu's private transparent runtime.
        yaml=removeTopLevelScalar(yaml,"allow-lan");
        yaml=removeTopLevelScalar(yaml,"bind-address");
        yaml=removeTopLevelBlock(yaml,"tun");
        yaml=removeTopLevelBlock(yaml,"ebpf");
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
        // Hetu owns ad-block hit accounting. Mihomo only emits matched RULE-SET details
        // at info/debug levels, so normalize the private runtime to info while the DNS
        // filtering chain is enabled. The user's source YAML remains untouched.
        if(profile.adblockChain) yaml=removeTopLevelScalar(yaml,"log-level");

        // Both Root DNS modes terminate DNS in Mihomo's private built-in resolver.
        // This preserves fake-ip / respect-rules and prevents plaintext DNS from being sent
        // as an ordinary transparent UDP flow. The source's 1053 listener is never reused.
        if(profile.dnsHijack!=ProxyRuntimeProfile.DnsHijack.OFF)
            yaml=ensureDnsListener(yaml,DNS_PORT);
        // Preserve every source rule in its original relative position. Hetu additions live
        // immediately before the final MATCH so WebRTC/DNS/privacy guards in the source stay authoritative.
        if(profile.adblockChain)
            yaml=ensureAdblock(yaml);
        if(profile.cnIpDirect)
            yaml=ensureCnIpDirect(yaml);

        int tp=0,rp=0;
        StringBuilder override=new StringBuilder();
        override.append("\n# --- Hetu runtime isolation; source file is unchanged ---\n");
        switch(profile.mode){
            case TPROXY:
                tp=TPROXY_PORT;
                override.append("tproxy-port: ").append(tp).append('\n');
                override.append("redir-port: 0\n");
                override.append("tun:\n  enable: false\n");
                break;
            case REDIRECT:
                rp=REDIRECT_PORT;
                if(profile.dnsHijack==ProxyRuntimeProfile.DnsHijack.TPROXY)
                    tp=TPROXY_PORT;
                override.append("redir-port: ").append(rp).append('\n');
                override.append("tproxy-port: ").append(tp).append('\n');
                override.append("tun:\n  enable: false\n");
                break;
            case ENHANCE:
                tp=TPROXY_PORT;
                rp=REDIRECT_PORT;
                override.append("redir-port: ").append(rp).append('\n');
                override.append("tproxy-port: ").append(tp).append('\n');
                override.append("tun:\n  enable: false\n");
                break;
            case TUN:
                override.append("tproxy-port: 0\nredir-port: 0\n");
                appendTun(override,profile,appScope,appPackages,directPackages,true,true);
                break;
            case EBPF:
                if(ebpfInterface==null||ebpfInterface.isEmpty())throw new IOException("eBPF 未找到可用默认出口接口");
                override.append("tproxy-port: 0\nredir-port: 0\n");
                appendTun(override,profile,ProxyRuntimeProfile.AppScope.CORE,Collections.emptySet(),Collections.emptySet(),false,false);
                override.append("ebpf:\n  redirect-to-tun:\n    - '").append(yamlQuote(ebpfInterface)).append("'\n");
                break;
            case MIXED:
                throw new IOException(profile.mode.label+" 的统一 Root 事务后端尚未接入，当前不开放");
        }
        // Mihomo marks its own outbound sockets. Root netfilter returns this bit before interception,
        // so Root/system UID traffic no longer needs to be blanket-bypassed just to avoid a core loop.
        // The transparent listener must accept packets re-routed to loopback while retaining
        // their original destination. No HTTP/SOCKS/Mixed listener is present in this private
        // runtime, so enabling wildcard ingress does not expose the user's former 7890 proxy.
        override.append("allow-lan: true\n");
        override.append("bind-address: '*'\n");
        // Android netd owns the socket fwmark/netId. Do not overwrite the full SO_MARK here;
        // the Root controller exempts the root-owned Mihomo process from OUTPUT interception.
        override.append("find-process-mode: strict\n");
        if(profile.adblockChain) override.append("log-level: info\n");
        override.append("external-controller: 127.0.0.1:").append(controllerPort).append('\n');
        override.append("secret: '").append(controllerSecret.replace("'","''")).append("'\n");
        override.append("# --- end Hetu runtime isolation ---\n");
        return new Result(yaml+override,tp,rp);
    }

    /**
     * Mihomo 1.19.30 tightened domain-pattern validation. Old configs sometimes put
     * human-readable labels (for example "Mijia Cloud") or partial wildcards into
     * domain-only lists. Keep the user's source untouched, but omit invalid legacy
     * entries from Hetu's private runtime copy so one stale item cannot brick startup.
     */
    private static String sanitizeStrictDomainCompatibility(String source){
        String[] lines=normalize(source).split("\n",-1);
        String top="";
        String child="";
        String grandchild="";
        int listIndent=-1;
        boolean domainList=false;
        StringBuilder out=new StringBuilder();
        for(int i=0;i<lines.length;i++){
            String raw=lines[i];
            String trimmed=raw.trim();
            int ind=indent(raw);
            if(trimmed.isEmpty()||trimmed.startsWith("#")){
                out.append(raw).append('\n');
                continue;
            }
            if(ind==0){
                top=keyOfMappingLine(trimmed);
                child="";grandchild="";domainList=false;listIndent=-1;
            }else if(ind==2){
                child=keyOfMappingLine(trimmed);
                grandchild="";
                domainList=("sniffer".equals(top)&&("skip-domain".equals(child)||"force-domain".equals(child)))
                        ||("dns".equals(top)&&"fake-ip-filter".equals(child));
                listIndent=domainList?ind:-1;
            }else if(ind==4&&"dns".equals(top)&&"fallback-filter".equals(child)){
                grandchild=keyOfMappingLine(trimmed);
                domainList="domain".equals(grandchild);
                listIndent=domainList?ind:-1;
            }

            if(domainList&&listIndent>=0&&ind>listIndent&&trimmed.startsWith("- ")){
                String scalar=stripYamlInlineComment(trimmed.substring(2).trim());
                String value=unquoteYamlScalar(scalar);
                if(!isStrictDomainPattern(value)){
                    out.append(spaces(ind)).append("# Hetu 1.19.30 compatibility: skipped invalid domain entry: ")
                            .append(value.replace("\n"," ").replace("\r"," ")).append('\n');
                    continue;
                }
            }
            out.append(raw).append('\n');
        }
        return trimOne(out.toString());
    }

    private static String keyOfMappingLine(String trimmed){
        if(trimmed.startsWith("-"))return "";
        int colon=trimmed.indexOf(':');
        if(colon<=0)return "";
        return unquoteYamlScalar(trimmed.substring(0,colon).trim());
    }

    private static String stripYamlInlineComment(String value){
        boolean single=false,dbl=false;
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(c=='\''&&!dbl)single=!single;
            else if(c=='"'&&!single)dbl=!dbl;
            else if(c=='#'&&!single&&!dbl&&(i==0||Character.isWhitespace(value.charAt(i-1))))
                return value.substring(0,i).trim();
        }
        return value.trim();
    }

    private static String unquoteYamlScalar(String value){
        String v=value==null?"":value.trim();
        if(v.length()>=2&&((v.charAt(0)=='"'&&v.charAt(v.length()-1)=='"')||(v.charAt(0)=='\''&&v.charAt(v.length()-1)=='\'')))
            return v.substring(1,v.length()-1).trim();
        return v;
    }

    private static boolean isStrictDomainPattern(String value){
        if(value==null)return false;
        String v=value.trim();
        if(v.isEmpty()||!v.equals(value)||v.endsWith(".")||v.startsWith(".")||containsWhitespace(v))return false;
        String lower=v.toLowerCase(Locale.ROOT);
        if(lower.startsWith("rule-set:")||lower.startsWith("geosite:")||lower.startsWith("regexp:"))return true;
        String[] parts=v.split("\\.",-1);
        if(parts.length==0)return false;
        for(int i=0;i<parts.length;i++){
            String part=parts[i];
            if(part.isEmpty())return false;
            if("+".equals(part)){
                if(i!=0||parts.length<2)return false;
                continue;
            }
            if("*".equals(part))continue;
            if(part.indexOf('+')>=0||part.indexOf('*')>=0)return false;
        }
        return true;
    }

    private static boolean containsWhitespace(String value){
        for(int i=0;i<value.length();i++)if(Character.isWhitespace(value.charAt(i)))return true;
        return false;
    }

    private static void appendTun(StringBuilder out,ProxyRuntimeProfile profile,ProxyRuntimeProfile.AppScope scope,
            Set<String> packages,Set<String> directPackages,boolean autoRoute,boolean packageFilter){
        out.append("tun:\n");
        out.append("  enable: true\n");
        out.append("  device: hetu0\n");
        out.append("  stack: mixed\n");
        out.append("  auto-route: ").append(autoRoute?"true":"false").append('\n');
        out.append("  auto-redirect: false\n");
        out.append("  auto-detect-interface: ").append(autoRoute?"true":"false").append('\n');
        out.append("  strict-route: ").append(autoRoute?"true":"false").append('\n');
        out.append("  exclude-uid-range:\n    - '0:9999'\n");
        out.append("  route-exclude-address:\n");
        out.append("    - 10.0.0.0/8\n    - 100.64.0.0/10\n    - 127.0.0.0/8\n    - 169.254.0.0/16\n    - 172.16.0.0/12\n    - 192.168.0.0/16\n    - fc00::/7\n    - fe80::/10\n");
        if(profile.dnsHijack!=ProxyRuntimeProfile.DnsHijack.OFF){
            out.append("  dns-hijack:\n    - any:53\n    - tcp://any:53\n");
        }
        if(packageFilter){
            TreeSet<String> selected=new TreeSet<>();
            if(packages!=null)selected.addAll(packages);
            TreeSet<String> direct=new TreeSet<>();
            if(directPackages!=null)direct.addAll(directPackages);
            if(scope==ProxyRuntimeProfile.AppScope.WHITELIST){
                selected.removeAll(direct);
                if(selected.isEmpty())throw new IllegalArgumentException("TUN 仅所选应用代理模式被 DIRECT 规则全部排除");
                out.append("  include-package:\n");
                for(String name:selected)out.append("    - '").append(yamlQuote(name)).append("'\n");
            }else{
                selected.addAll(direct);
                if(!selected.isEmpty()){
                    out.append("  exclude-package:\n");
                    for(String name:selected)out.append("    - '").append(yamlQuote(name)).append("'\n");
                }
            }
        }
    }

    static Set<String> extractDirectProcessPackages(String source){
        TreeSet<String> out=new TreeSet<>();
        if(source==null||source.isEmpty())return out;
        Pattern p=Pattern.compile("(?m)^\\s*-\\s*(?:PROCESS-NAME|PROCESS-NAME-WILDCARD)\\s*,\\s*([^,#]+?)\\s*,\\s*DIRECT(?:\\s*,[^#]*)?(?:\\s*#.*)?$");
        Matcher m=p.matcher(normalize(source));
        Pattern safe=Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+(?:\\*)?");
        while(m.find()){
            String value=m.group(1).trim();
            if((value.startsWith("'")&&value.endsWith("'"))||(value.startsWith("\"")&&value.endsWith("\"")))value=value.substring(1,value.length()-1).trim();
            if(safe.matcher(value).matches())out.add(value);
        }
        return out;
    }

    private static String yamlQuote(String value){return value==null?"":value.replace("'","''");}

    /**
     * Runtime-only compatibility for public DNS providers that retired raw-IP DoH access.
     * The user's selected YAML remains byte-for-byte unchanged. Using 223.6.6.6 here keeps
     * bootstrap encrypted without creating a resolver-domain bootstrap loop.
     */
    private static String normalizeDeprecatedEncryptedDns(String source){
        return source
                .replace("https://1.12.12.12/dns-query","https://223.6.6.6/dns-query")
                .replace("https://120.53.53.53/dns-query","https://223.6.6.6/dns-query");
    }

    /** Merge the local effective Hetu ad-block snapshot into the private startup copy. */
    private static String ensureAdblock(String source)throws IOException{
        String yaml=normalize(source);
        yaml=injectAdblockProvider(yaml);
        yaml=insertAdblockRuleRespectingUserPolicy(yaml);
        return yaml;
    }

    private static String injectAdblockProvider(String source)throws IOException{
        String[] lines=normalize(source).split("\n",-1);
        int index=-1;Matcher found=null;Pattern top=Pattern.compile("^rule-providers\\s*:(.*)$");
        for(int i=0;i<lines.length;i++){if(indent(lines[i])!=0)continue;Matcher m=top.matcher(lines[i]);if(m.find()){index=i;found=m;break;}}
        String providerBlock=
                "  "+ProxyAdblockRules.ALLOW_PROVIDER_NAME+":\n"+
                "    type: file\n"+
                "    behavior: domain\n"+
                "    format: text\n"+
                "    path: "+ProxyAdblockRules.ALLOW_PROVIDER_PATH+"\n"+
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
        for(int i=index+1;i<end;i++){
            String t=lines[i].trim();
            if(t.startsWith(ProxyAdblockRules.PROVIDER_NAME+":")||t.startsWith(ProxyAdblockRules.ALLOW_PROVIDER_NAME+":"))
                throw new IOException("源配置占用了河图保留的 DNS 过滤 provider 名称");
        }
        StringBuilder out=new StringBuilder();
        for(int i=0;i<lines.length;i++){if(i==index){out.append("rule-providers:\n").append(providerBlock);continue;}out.append(lines[i]).append('\n');}
        return trimOne(out.toString());
    }

    private static boolean isSpecificDirectWhitelist(String raw){
        String t=raw==null?"":raw.trim();
        if(!t.startsWith("-"))return false;
        t=t.substring(1).trim();
        String[] parts=t.split(",");
        if(parts.length<3)return false;
        String type=parts[0].trim().toUpperCase(Locale.ROOT);
        boolean direct=false;
        for(int i=2;i<parts.length;i++)if("DIRECT".equalsIgnoreCase(parts[i].trim())){direct=true;break;}
        if(!direct)return false;
        switch(type){
            case "PROCESS-NAME":
            case "PROCESS-NAME-WILDCARD":
            case "PROCESS-PATH":
            case "PROCESS-PATH-REGEX":
            case "DOMAIN":
            case "DOMAIN-SUFFIX":
            case "DOMAIN-WILDCARD":
                return true;
            default:
                return false;
        }
    }

    private static String insertAdblockRuleRespectingUserPolicy(String source)throws IOException{
        String rule="  - RULE-SET,"+ProxyAdblockRules.PROVIDER_NAME+",REJECT\n";
        String anchored=insertRuleBeforeSourceAdblockAnchor(source,rule);
        if(anchored!=null)return anchored;
        return insertRulesBeforeFinalMatch(
                source,
                rule,
                "代理串联去广告需要普通 rules: 列表；当前源配置使用行内 rules 写法");
    }

    /**
     * Reuse the source YAML's own ad-block placement when it has one. This preserves
     * WebRTC/DNS/IPv6 security rules and explicit whitelists above the source adblock,
     * while keeping Hetu's REJECT ahead of later app/CN/DIRECT routing.
     */
    private static String insertRuleBeforeSourceAdblockAnchor(String source,String injected)throws IOException{
        String[] lines=normalize(source).split("\n",-1);
        int index=-1;Matcher found=null;Pattern top=Pattern.compile("^rules\\s*:(.*)$");
        for(int i=0;i<lines.length;i++){
            if(indent(lines[i])!=0)continue;
            Matcher m=top.matcher(lines[i]);
            if(m.find()){index=i;found=m;break;}
        }
        if(index<0)return null;
        String rest=found.group(1).trim();
        if(rest.startsWith("#"))rest="";
        if(!rest.isEmpty()&&!rest.equals("[]"))
            throw new IOException("代理串联去广告需要普通 rules: 列表；当前源配置使用行内 rules 写法");

        int end=lines.length;
        for(int i=index+1;i<lines.length;i++){
            String t=lines[i].trim();
            if(t.isEmpty()||t.startsWith("#"))continue;
            if(indent(lines[i])==0){end=i;break;}
        }
        int anchor=-1;
        for(int i=index+1;i<end;i++){
            if(isSourceAdblockRule(lines[i])){anchor=i;break;}
        }
        if(anchor<0)return null;

        StringBuilder out=new StringBuilder();
        for(int i=0;i<lines.length;i++){
            if(i==anchor)out.append(injected);
            out.append(lines[i]).append('\n');
        }
        return trimOne(out.toString());
    }

    private static boolean isSourceAdblockRule(String raw){
        String t=raw==null?"":raw.trim();
        if(!t.startsWith("-"))return false;
        String upper=t.toUpperCase(Locale.ROOT);
        if(!upper.startsWith("- RULE-SET,"))return false;
        return t.contains("广告")
                || upper.contains("ADBLOCK")
                || upper.contains("ADGUARD")
                || upper.contains("ANTI-AD")
                || upper.contains("ANTIAD");
    }

    /**
     * Insert a Hetu-owned runtime rule without changing the relative order of any source rule.
     * This remains the fallback for configs that have no explicit source ad-block anchor.
     */
    private static String insertRulesBeforeFinalMatch(String source,String injected,String inlineError)throws IOException{
        String[] lines=normalize(source).split("\n",-1);
        int index=-1;Matcher found=null;Pattern top=Pattern.compile("^rules\\s*:(.*)$");
        for(int i=0;i<lines.length;i++){
            if(indent(lines[i])!=0)continue;
            Matcher m=top.matcher(lines[i]);
            if(m.find()){index=i;found=m;break;}
        }
        if(index<0){
            String base=trimOne(source);
            return base+(base.isEmpty()?"":"\n")+"rules:\n"+injected;
        }
        String rest=found.group(1).trim();
        if(rest.startsWith("#"))rest="";
        if(!rest.isEmpty()&&!rest.equals("[]"))throw new IOException(inlineError);

        int end=lines.length;
        for(int i=index+1;i<lines.length;i++){
            String t=lines[i].trim();
            if(t.isEmpty()||t.startsWith("#"))continue;
            if(indent(lines[i])==0){end=i;break;}
        }
        int insertion=end;
        for(int i=index+1;i<end;i++){
            String t=lines[i].trim().toUpperCase(Locale.ROOT);
            if(t.startsWith("- MATCH,")){insertion=i;break;}
        }

        StringBuilder out=new StringBuilder();
        for(int i=0;i<lines.length;i++){
            if(i==insertion)out.append(injected);
            out.append(lines[i]).append('\n');
        }
        if(insertion==lines.length)out.append(injected);
        return trimOne(out.toString());
    }

    /** Merge Hetu CN IP providers into the private startup copy without editing the subscription. */
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
                "  hetu-cn-v4:\n"+
                "    type: http\n"+
                "    behavior: ipcidr\n"+
                "    format: text\n"+
                "    path: "+CNIP_V4_PATH+"\n"+
                "    url: '"+CNIP_V4_URL+"'\n"+
                "    interval: 86400\n"+
                "  hetu-cn-v6:\n"+
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
            if(t.startsWith("hetu-cn-v4:")||t.startsWith("hetu-cn-v6:"))
                throw new IOException("源配置占用了河图保留的 CNIP provider 名称");
        }
        StringBuilder out=new StringBuilder();
        for(int i=0;i<lines.length;i++){
            if(i==index){out.append("rule-providers:\n").append(providerBlock);continue;}
            out.append(lines[i]).append('\n');
        }
        return trimOne(out.toString());
    }

    private static String prependCnRules(String source)throws IOException{
        String cnRules="  - RULE-SET,hetu-cn-v4,DIRECT,no-resolve\n"+
                "  - RULE-SET,hetu-cn-v6,DIRECT,no-resolve\n";
        return insertRulesBeforeFinalMatch(
                source,
                cnRules,
                "中国 IP 自动直连需要普通 rules: 列表；当前源配置使用行内 rules 写法");
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
