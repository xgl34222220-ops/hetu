package io.github.xgl34222220.hetu;

import java.io.IOException;

public final class ConfigEditSnapshotTest {
    static int checks;
    static void rejects(ConfigEditSnapshot snapshot, String core, String name, String text, String expected) throws Exception {
        try { snapshot.requireUnchanged(core, name, text); throw new AssertionError("Unexpected save permission"); }
        catch (IOException error) { if (!error.getMessage().contains(expected)) throw error; }
        checks++;
    }
    public static void main(String[] args) throws Exception {
        ConfigEditSnapshot s = new ConfigEditSnapshot("mihomo", "A.yaml", "mixed-port: 7890\nrules: []\n");
        s.requireUnchanged("mihomo", "A.yaml", "mixed-port: 7890\nrules: []\n"); checks++;
        rejects(s,"mihomo-smart","A.yaml",s.originalText,"已切换");
        rejects(s,"mihomo","B.yaml",s.originalText,"已切换");
        rejects(s,"mihomo","A.yaml","mixed-port: 7891\nrules: []\n","其他页面更新");
        rejects(s,"mihomo","A.yaml",s.originalText+"# external change\n","其他页面更新");
        rejects(s,null,"A.yaml",s.originalText,"已切换");
        rejects(s,"mihomo",null,s.originalText,"已切换");
        rejects(s,"mihomo","A.yaml",null,"其他页面更新");
        rejects(s,"mihomo","A.yaml","","其他页面更新");
        ConfigEditSnapshot renamed = new ConfigEditSnapshot("mihomo", "中文配置.yaml", "规则: 中文\n");
        renamed.requireUnchanged("mihomo", "中文配置.yaml", "规则: 中文\n"); checks++;
        System.out.println(checks + " snapshot safety checks passed");
    }
}
