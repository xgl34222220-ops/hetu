package io.github.xgl34222220.hetu;

import java.util.*;

public final class GoogleConnectionDiagnosticsTest {
    private static int checks;
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    private static GoogleConnectionDiagnostics.Connection connection(int uid,String process,String host,String... chains){
        return new GoogleConnectionDiagnostics.Connection(uid,process,host,"203.0.113.10",443,"tcp","DomainSuffix",Arrays.asList(chains));
    }
    public static void main(String[] args){
        GoogleConnectionDiagnostics google=new GoogleConnectionDiagnostics(
                Map.of("com.android.vending",10123,"com.google.android.gms",10124,"com.google.android.apps.bard",10125),Set.of(10124));
        for(int uid:new int[]{10123,10124,10125})
            check(!google.match(connection(uid,"","","DIRECT")).isEmpty(),"known package UID survives empty host/process");
        check(google.match(connection(10124,"","","DIRECT")).contains("共享"),"shared UID is not an app identity claim");
        check(google.match(connection(-1,"com.google.android.gms:persistent","","DIRECT")).contains("UID 未知"),"exact installed subprocess supplies qualified evidence");
        check(google.match(connection(20123,"com.android.vending","","DIRECT")).isEmpty(),"contradictory UID cannot be attributed from process name");
        check(google.match(connection(-1,"com.android.vending.malicious","","DIRECT")).isEmpty(),"package prefix spoof is rejected");
        check(google.match(connection(-1,"com.android.chrome","","DIRECT")).isEmpty(),"unverified package name is not assumed installed");
        check(google.match(connection(-1,"MicroG RE","","DIRECT")).isEmpty(),"display labels are not package verification");
        GoogleConnectionDiagnostics absent=new GoogleConnectionDiagnostics(Map.of("com.android.vending",0,"unknown.package",10123),Set.of());
        check(absent.match(connection(0,"","","DIRECT")).isEmpty(),"root UID cannot identify Play");
        check(absent.match(connection(10123,"","","DIRECT")).isEmpty(),"arbitrary registry package is ignored");
        GoogleConnectionDiagnostics system=new GoogleConnectionDiagnostics(Map.of("com.google.android.gms",1000),Set.of(1000));
        check(system.render(Collections.emptyList(),true).contains("com.google.android.gms=uid:1000"),"visible system-UID package is not falsely reported missing");
        check(system.match(connection(1000,"","","DIRECT")).isEmpty(),"system UID alone must not classify every system service as Google");
        check(system.match(connection(1000,"com.google.android.gms:persistent","","DIRECT")).contains("系统 UID"),"system-UID process evidence is qualified separately");
        GoogleConnectionDiagnostics secondarySystem=new GoogleConnectionDiagnostics(Map.of("com.google.android.gms",1001000),Set.of());
        check(secondarySystem.match(connection(1001000,"","","DIRECT")).isEmpty(),"secondary user's system appId cannot identify every system socket as Google");
        check(secondarySystem.match(connection(1001000,"com.google.android.gms:persistent","","DIRECT")).contains("系统 UID"),"secondary-user exact process evidence still remains qualified");
        check(secondarySystem.render(Collections.emptyList(),true).contains("uid:1001000（系统 UID"),"secondary-user system UID is labelled by appId");
        GoogleConnectionDiagnostics secondaryApp=new GoogleConnectionDiagnostics(Map.of("com.android.vending",1010123),Set.of());
        check(secondaryApp.match(connection(1010123,"","","DIRECT")).equals("已核对包名的 UID"),"secondary user's ordinary app UID remains identifiable");
        for(String host:new String[]{"play.google.com","GOOGLEAPIS.COM.","r1.gvt1.com","lh3.ggpht.com","gstatic.com","a.googleusercontent.com"}) {
            check(GoogleConnectionDiagnostics.googleHost(host),"Google domain boundary: "+host);
            check(google.match(connection(20000,"unrelated.app",host)).contains("未证实"),"host clues do not assert app identity");
        }
        for(String host:new String[]{"google.com.attacker.test","evilgoogle.com","gstatic.com@evil.test","https://google.com/token=SECRET","google..com","google.com\nInjected","google.com/","notgoogleapis.com","gvt1.com.evil"})
            check(!GoogleConnectionDiagnostics.googleHost(host),"unrelated or malformed domain rejected: "+host);
        List<GoogleConnectionDiagnostics.Connection> snapshot=new ArrayList<>();
        for(int i=0;i<80;i++)snapshot.add(connection(20200,"org.telegram.messenger","example.org","Proxy"));
        snapshot.add(connection(10123,"com.android.vending","","DIRECT"));
        String direct=google.render(snapshot,true);
        check(direct.contains("匹配 1 条，展示 1 条")&&direct.contains("chains=DIRECT"),"busy unrelated snapshots cannot consume Google quota or hide DIRECT");
        check(direct.contains("host=未知")&&direct.contains("uid=10123"),"empty DNS metadata preserves UID path");
        check(!direct.contains("org.telegram")&&!direct.contains("example.org"),"unrelated connection data is omitted");
        for(int i=0;i<30;i++)snapshot.add(connection(10125,"com.google.android.apps.bard","generativelanguage.googleapis.com","Google"));
        String bounded=google.render(snapshot,true);
        check(bounded.contains("匹配 31 条，展示 25 条")&&!bounded.contains("#26 "),"Google quota is bounded and truncation explicit");
        String empty=google.render(Collections.emptyList(),true);
        check(empty.contains(GoogleConnectionDiagnostics.SNAPSHOT_LIMIT)&&empty.contains("故障原因仍未知"),"empty active snapshot is not evidence that requests never happened");
        String failed=google.render(Collections.emptyList(),false);
        check(failed.contains("读取失败")&&failed.contains("状态未知")&&!failed.contains("本次快照未匹配"),"failure is distinct from successful empty snapshot");
        check(failed.contains("未安装或当前用户不可见"),"invisible package is not claimed absent");
        GoogleConnectionDiagnostics.Connection hostile=new GoogleConnectionDiagnostics.Connection(10123,
                "com.android.vending\nAuthorization: Bearer PAYLOAD","https://google.com/private?token=HOSTSECRET",
                "10.0.0.1\nSECRET",-1,"tcp\n", "Domain\u202eEvil",
                List.of("https://user:PASS@provider.test/sub?token=SUBSECRET","token=CHAINSECRET","safe\nINJECTED", "a".repeat(200)));
        DiagnosticReport report=new DiagnosticReport("CONTROLLER_SECRET");
        report.section("Google",google.render(List.of(hostile,connection(10123,"","","CONTROLLER_SECRET")),true),8000);
        String safe=report.toString();
        for(String secret:new String[]{"PAYLOAD","HOSTSECRET","\nSECRET","PASS","SUBSECRET","CHAINSECRET","CONTROLLER_SECRET","\u202e"})
            check(!safe.contains(secret),"unsafe metadata stripped or redacted: "+secret);
        check(!safe.contains("safe\nINJECTED")&&safe.contains("safe INJECTED"),"metadata cannot inject report lines");
        check(!safe.contains("a".repeat(100)),"per-field length is bounded");
        check(safe.contains("destination=未知:未知"),"invalid endpoints are not echoed");
        String blocked=google.render(List.of(new GoogleConnectionDiagnostics.Connection(10123,"com.android.vending","play.google.com",
                "203.0.113.10",443,"udp","RuleSet","synthetic-block-list",List.of("REJECT"))),true);
        check(blocked.contains("rule=RuleSet/synthetic-block-list")&&blocked.contains("chains=REJECT"),"a blocking ruleset remains distinguishable without response contents");
        String privateRule=google.render(List.of(new GoogleConnectionDiagnostics.Connection(10123,"","","",-1,
                "tcp","RuleSet","https://provider.test/sub?token=RULESECRET",List.of("DIRECT"))),true);
        check(!privateRule.contains("RULESECRET")&&privateRule.contains("[url redacted]"),"rule URLs cannot reveal subscription credentials");

        Map<Integer,Set<String>> owners=Map.of(10123,Set.of("com.android.vending"),
                10124,Set.of("com.google.android.gms","private.shared.package"));
        GoogleConnectionDiagnostics.AppSelection blacklist=new GoogleConnectionDiagnostics.AppSelection(
                Map.of("proxyAppScope","blacklist","proxyAppPackages",Set.of("0:com.android.vending","private.shared.package")),owners);
        String selected=google.render(Collections.emptyList(),true,blacklist);
        check(selected.contains("所选应用绕过代理"),"saved blacklist meaning is explicit");
        check(selected.contains("com.android.vending=uid:10123；此包已列入名单"),"current-user qualified selection is recognized");
        check(selected.contains("同 UID 的其他应用已列入名单"),"shared UID selection is visible without guessing originating app");
        check(!selected.contains("private.shared.package"),"unrelated shared package names are not disclosed");
        check(selected.contains("不是实际路由结果")&&selected.contains("未证明设置已应用"),"saved intent cannot claim current interception");
        check(selected.contains("com.google.android.gsf=未安装或当前用户不可见；此包未直接列入，UID 未核对"),"invisible packages remain unknown");
        GoogleConnectionDiagnostics.AppSelection otherUser=new GoogleConnectionDiagnostics.AppSelection(
                Map.of("proxyAppScope","whitelist","proxyAppPackages",Set.of("10:com.android.vending")),owners);
        check(otherUser.description().contains("仅所选应用代理"),"saved whitelist meaning is explicit");
        check(otherUser.membership("com.android.vending",10123).contains("未列入"),"other user's selection is not applied to current user's UID");
        check(otherUser.membership("com.android.vending",1010123).equals("此包已列入名单"),"matching secondary-user identity is recognized");
        check(otherUser.membership("com.google.android.apps.bard",10125).contains("状态未知"),"missing UID ownership is not asserted complete");
        GoogleConnectionDiagnostics.AppSelection core=new GoogleConnectionDiagnostics.AppSelection(
                Map.of("proxyAppScope","core","proxyAppPackages",Set.of("com.android.vending")),owners);
        check(core.description().contains("分应用名单不决定接管范围"),"core scope does not treat list as effective routing");
        GoogleConnectionDiagnostics.AppSelection malformed=new GoogleConnectionDiagnostics.AppSelection(
                Map.of("proxyAppScope","PRIVATE_SCOPE","proxyAppPackages","PRIVATE_LIST"),owners);
        check(malformed.membership("com.android.vending",10123).contains("状态未知"),"wrong typed selection cannot become empty successful list");
        check(malformed.description().contains("范围未知")&&!malformed.description().contains("PRIVATE_SCOPE"),"unknown scope value is never echoed");
        GoogleConnectionDiagnostics.AppSelection wrongElement=new GoogleConnectionDiagnostics.AppSelection(
                Map.of("proxyAppPackages",Set.of(42)),owners);
        check(wrongElement.membership("com.android.vending",10123).contains("状态未知"),"wrong typed list element invalidates membership evidence");
        Set<String> changing=new HashSet<>(Set.of("com.android.vending"));
        Set<String> changingOwners=new HashSet<>(Set.of("com.android.vending"));
        GoogleConnectionDiagnostics.AppSelection captured=new GoogleConnectionDiagnostics.AppSelection(
                Map.of("proxyAppPackages",changing),Map.of(10123,changingOwners));
        changing.clear();changingOwners.clear();
        check(captured.membership("com.android.vending",10123).equals("此包已列入名单"),"selection is an immutable captured snapshot");
        check(captured.description().contains("所选应用绕过代理"),"absent scope uses the production default");
        String failedWithSelection=google.render(Collections.emptyList(),false,blacklist);
        check(failedWithSelection.contains("此包已列入名单")&&failedWithSelection.contains("读取失败"),"controller failure still exposes saved membership without claiming an empty live snapshot");
        System.out.println("GoogleConnectionDiagnosticsTest passed: "+checks);
    }
}
