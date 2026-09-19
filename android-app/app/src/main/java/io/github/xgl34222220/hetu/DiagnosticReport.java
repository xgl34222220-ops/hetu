package io.github.xgl34222220.hetu;

import java.util.Locale;

/** Bounded, on-demand diagnostic text; never contains message payloads. */
final class DiagnosticReport {
    private final StringBuilder text=new StringBuilder();
    private final String secret;

    DiagnosticReport(String secret){this.secret=secret==null?"":secret;}

    void section(String name,String value,int limit){
        String safe=redact(value,secret);
        text.append("\n--- ").append(name).append(" ---\n");
        if(safe.length()>limit){
            int half=limit/2;
            safe=safe.substring(0,half)+"\n[本节过长，省略中间部分]\n"+safe.substring(safe.length()-half);
        }
        text.append(safe.isEmpty()?"无记录":safe).append('\n');
    }

    static String redact(String value,String secret){
        String safe=value==null?"":value.trim();
        if(secret!=null&&!secret.isEmpty())safe=safe.replace(secret,"[redacted]");
        // Subscription credentials can be in the path, and proxy URIs use many
        // schemes. Keep plain connection hostnames; redact complete URLs in logs.
        safe=safe.replaceAll("(?i)[a-z][a-z0-9+.-]*://[^\\s\"'<>]+","[url redacted]");
        safe=safe.replaceAll("(?i)(authorization\\s*[:=]\\s*)(?:bearer\\s+)?[^\\r\\n]+","$1[redacted]");
        return safe;
    }

    static boolean isWechat(String process,String host,int uid,int wechatUid){
        String p=process==null?"":process;
        String h=host==null?"":host.toLowerCase(Locale.ROOT);
        return p.equals("com.tencent.mm")||p.startsWith("com.tencent.mm:")
                ||h.equals("weixin.qq.com")||h.endsWith(".weixin.qq.com")
                ||wechatUid>=10000&&uid==wechatUid;
    }

    @Override public String toString(){return text.toString().trim();}
}
