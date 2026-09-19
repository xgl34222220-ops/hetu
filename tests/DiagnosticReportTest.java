package io.github.xgl34222220.hetu;

public final class DiagnosticReportTest {
    private static int checks;
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    public static void main(String[] args){
        check(DiagnosticReport.isWechat("com.tencent.mm:push","",-1,-1),"message subprocess is included");
        check(DiagnosticReport.isWechat("","szlong.weixin.qq.com",-1,-1),"message hostname is included");
        check(DiagnosticReport.isWechat("","",10234,10234),"matching known application UID is included");
        check(!DiagnosticReport.isWechat("com.tencent.mmalicious","weixin.qq.com.attacker.test",-1,-1),"unrelated names and missing UID are excluded");
        check(!DiagnosticReport.isWechat("","",0,0),"root UID is not assumed to be WeChat");
        String safe=DiagnosticReport.redact("https://user:password@provider.test/sub?token=private-token\nAuthorization: Bearer private-auth\ncontroller=private-controller","private-controller");
        check(!safe.contains("password")&&!safe.contains("private-token")&&!safe.contains("private-auth")&&!safe.contains("private-controller"),"credentials are removed");
        check(safe.contains("controller=")&&safe.contains("\n"),"diagnostic context and line breaks survive redaction");
        check(!DiagnosticReport.redact("failed https://provider.test/link/SECRET?clash=1","").contains("SECRET"),"subscription path credentials are hidden");
        check(!DiagnosticReport.redact("trojan://PASSWORD@node.test:443","").contains("PASSWORD"),"non-HTTP proxy credentials are hidden");
        DiagnosticReport report=new DiagnosticReport("");
        report.section("process","running=true\npid=123",200);
        report.section("large log","first"+"x".repeat(5000)+"last",100);
        report.section("events","recent restart",200);
        String output=report.toString();
        check(output.contains("running=true\npid=123")&&output.contains("recent restart"),"large logs do not erase earlier or later sections");
        check(output.contains("first")&&output.contains("last")&&output.length()<500,"oversized sections retain bounded context at both ends");
        System.out.println("DiagnosticReportTest passed: "+checks);
    }
}
