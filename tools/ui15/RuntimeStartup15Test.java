package io.github.xgl34222220.hetu;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.yaml.snakeyaml.nodes.*;

/** Actual generator/migration entry points; no networking, source credentials or mocks. */
public class RuntimeStartup15Test {
    private static ProxyRuntimeProfile profile(ProxyRuntimeProfile.Mode mode) {
        return new ProxyRuntimeProfile(ProxyRuntimeProfile.Core.MIHOMO,mode,ProxyRuntimeProfile.Ipv6.DISABLE,
            ProxyRuntimeProfile.AppScope.BLACKLIST,ProxyRuntimeProfile.DnsHijack.TPROXY,true,true,true,false,false,true);
    }
    private static String generate(String source) throws IOException {
        return MihomoStartupConfig.generate(source,profile(ProxyRuntimeProfile.Mode.TPROXY),"synthetic-controller",29147).yaml;
    }
    private static Node field(Node node,String key){return RuntimeYaml15.inherited(node,key);}
    private static String scalar(Node node){return ((ScalarNode)node).getValue();}
    private static List<Node> nodes(String text)throws IOException {return ((SequenceNode)field(RuntimeYaml15.compose(text),"proxies")).getValue();}

    @Test public void actualStartupGeneratorAcceptsEightyEightMergedTlsNodes() throws Exception {
        String source=RuntimeYaml15Test.templates(88),output=generate(source);
        List<Node> nodes=nodes(output);assertEquals(88,nodes.size());
        for(Node n:nodes)assertEquals("chrome",scalar(field(n,"client-fingerprint")));
        assertNull(field(RuntimeYaml15.compose(output),"global-client-fingerprint"));
        assertTrue(AdblockRuleInspection.isInjected(output));
        assertTrue(output.contains("external-controller: 127.0.0.1:29147"));
    }
    @Test public void allAvailableNonEbpfRuntimeModesStillGenerate() throws Exception {
        String source=RuntimeYaml15Test.templates(88);
        for(ProxyRuntimeProfile.Mode mode:new ProxyRuntimeProfile.Mode[]{ProxyRuntimeProfile.Mode.TPROXY,ProxyRuntimeProfile.Mode.TUN,ProxyRuntimeProfile.Mode.REDIRECT,ProxyRuntimeProfile.Mode.ENHANCE}){
            String output=MihomoStartupConfig.generate(source,profile(mode)).yaml;
            assertNotNull(RuntimeYaml15.compose(output));assertEquals(88,nodes(output).size());
            assertTrue(AdblockRuleInspection.isInjected(output));
        }
    }
    @Test public void manyProviderAndPolicyTemplatesDoNotTriggerATlsRewrite() throws Exception {
        StringBuilder raw=new StringBuilder("global-client-fingerprint: chrome\nprovider: &provider {type: http, interval: 3600}\npolicy: &policy {type: select, proxies: [DIRECT]}\nproxy-providers:\n");
        for(int i=0;i<4;i++)raw.append("  p").append(i).append(": {<<: *provider, url: 'https://example.test/public-"+i+"'}\n");
        raw.append("proxy-groups:\n");
        for(int i=0;i<84;i++)raw.append("  - {name: G").append(i).append(", <<: *policy}\n");
        raw.append("proxies: [{name: Direct, type: direct}, {name: Reject, type: reject}]\ndns:\n  fake-ip-filter-mode: blacklist\n  enhanced-mode: fake-ip\nrules:\n  - MATCH,DIRECT\n");
        String source=raw.toString();assertEquals(source,RuntimeCompatibility14.migrateFingerprint(source));
        String output=generate(source);assertEquals(84,((SequenceNode)field(RuntimeYaml15.compose(output),"proxy-groups")).getValue().size());
    }
    @Test public void ruleModeWithSpacesSurvivesTheFullGeneratorAndManyAliases() throws Exception {
        String raw=RuntimeYaml15Test.templates(88).replace("fake-ip-filter-mode: blacklist","fake-ip-filter-mode: rule")
            .replace("fake-ip-filter: ['+.local']","fake-ip-filter:\n    - 'RULE-SET,My Rules,real-ip'\n    - 'MATCH,fake-ip'");
        assertTrue(RuntimeCompatibility14.ruleFakeIpFilter(raw));
        String output=generate(raw);assertTrue(output.contains("    - 'RULE-SET,My Rules,real-ip'"));
        assertTrue(output.contains("    - 'MATCH,fake-ip'"));assertFalse(output.contains("skipped invalid domain entry"));
    }
    @Test public void blacklistSanitizationRemainsIntactWithManyAliases() throws Exception {
        String raw=RuntimeYaml15Test.templates(88).replace("fake-ip-filter: ['+.local']","fake-ip-filter:\n    - Mijia Cloud\n    - '+.local'");
        String output=generate(raw);assertFalse(output.contains("\n    - Mijia Cloud\n"));assertTrue(output.contains("    - '+.local'"));
    }
    @Test public void inlinePayloadsPreserveExplicitFingerprintsAndUnicode() throws Exception {
        StringBuilder raw=new StringBuilder("global-client-fingerprint: chrome\nbase: &base {type: vless}\nproxy-providers:\n  inline:\n    type: inline\n    payload:\n");
        for(int i=0;i<88;i++) raw.append("      - {name: '\uD83C\uDDF8\uD83C\uDDEC Node "+i+"', <<: *base"+(i%2==0?", client-fingerprint: firefox":"")+"}\n");
        String output=RuntimeCompatibility14.migrateFingerprint(raw.toString());
        SequenceNode list=(SequenceNode)field(field(field(RuntimeYaml15.compose(output),"proxy-providers"),"inline"),"payload");
        assertEquals(88,list.getValue().size());
        for(int i=0;i<88;i++)assertEquals(i%2==0?"firefox":"chrome",scalar(field(list.getValue().get(i),"client-fingerprint")));
        assertFalse(output.contains("override:"));
    }
    @Test public void sharedNodeAliasesAreEditedOnlyOnce() throws Exception {
        String source="global-client-fingerprint: chrome\nnode: &node {name: 'Node', type: vless, server: example.test}\nproxies: [*node, *node]\nrules:\n  - MATCH,DIRECT\n";
        String output=RuntimeCompatibility14.migrateFingerprint(source);
        assertEquals(1,output.split("client-fingerprint: 'chrome'",-1).length-1);
        List<Node> list=nodes(output);assertSame(list.get(0),list.get(1));
    }
    @Test public void flowTrailingCommaStillProducesValidPrivateYaml() throws Exception {
        String source="global-client-fingerprint: chrome\nproxies: [{name: N, type: vless, server: example.test, }]\nrules:\n  - MATCH,DIRECT\n";
        assertEquals("chrome",scalar(field(nodes(generate(source)).get(0),"client-fingerprint")));
    }
    @Test public void diagnosticSummaryWorksWithTheTemplateHeavyDocument() {
        String summary=RuntimeCompatibility14.safeConfigSummary(RuntimeYaml15Test.templates(88));
        assertTrue(summary.contains("dns.fake-ip-filter-mode=blacklist"));
        assertTrue(summary.contains("global-client-fingerprint=chrome"));
        assertFalse(summary.contains("Node-"));assertFalse(summary.contains("example.test"));
    }
    @Test public void summaryReportsSafeReasonAndLocationRatherThanBlamingGenericYaml() {
        String summary=RuntimeCompatibility14.safeConfigSummary("secret: TOPSECRET\nproxies: [}\n");
        assertTrue(summary.contains("[YAML_SYNTAX]"));assertTrue(summary.contains("第 2 行"));assertFalse(summary.contains("TOPSECRET"));
        assertTrue(summary.contains("未输出配置或凭据"));
    }
    @Test public void invalidSourcesDoNotBypassMigrationToReachRuntimeGeneration() {
        String raw="global-client-fingerprint: chrome\nsecret: TOPSECRET\nproxies: [*private-undefined-anchor]\n";
        try { generate(raw);fail("malformed YAML accepted"); }
        catch(IOException error){assertTrue(error.getMessage().contains("[YAML_SYNTAX]"));assertFalse(error.getMessage().contains("private-undefined-anchor"));assertNull(error.getCause());}
    }
    @Test public void privateCopyGenerationNeverOverwritesTheSourceFile() throws Exception {
        Path file=Files.createTempFile("synthetic-hetu-source", ".yaml");
        try {
            byte[] raw=RuntimeYaml15Test.templates(88).getBytes(StandardCharsets.UTF_8);Files.write(file,raw);
            String result=generate(Files.readString(file));assertFalse(result.isEmpty());assertArrayEquals(raw,Files.readAllBytes(file));
        } finally { Files.deleteIfExists(file); }
    }
    @Test public void existingInlineRulesRefusalIsNotBypassedByTheParserHotfix() {
        String raw = "mode: rule\nproxies: []\nrules: ['MATCH,DIRECT']\n";
        try { generate(raw); fail("unsupported inline adblock insertion should remain explicit"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("rules")); }
    }

}
