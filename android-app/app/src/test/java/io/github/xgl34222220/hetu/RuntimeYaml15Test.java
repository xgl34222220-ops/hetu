package io.github.xgl34222220.hetu;

import org.junit.Test;
import static org.junit.Assert.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.*;
import java.io.*;
import java.util.*;

/** Synthetic fixtures only. No user subscription, credentials or source YAML. */
public class RuntimeYaml15Test {
    static String templates(int count) {
        StringBuilder text = new StringBuilder("global-client-fingerprint: chrome\nbase: &base {type: vless, tls: true}\nproxies:\n");
        for (int i=0;i<count;i++) text.append("  - {name: Node-").append(i).append(", <<: *base, server: example.test}\n");
        return text + "dns:\n  enhanced-mode: fake-ip\n  fake-ip-filter-mode: blacklist\n  fake-ip-filter: ['+.local']\nrules:\n  - MATCH,DIRECT\n";
    }
    static Node field(Node n,String name) { return RuntimeYaml15.inherited(n,name); }
    static String scalar(Node n) { return ((ScalarNode)n).getValue(); }
    static IOException rejected(String text) {
        try { RuntimeYaml15.compose(text);fail("invalid or over-limit document accepted");return null; }
        catch(IOException expected){ return expected; }
    }
    @Test public void originalUi14FiftyAliasGateRejectsOrdinaryEightyEightTemplates() {
        LoaderOptions o=new LoaderOptions();o.setMaxAliasesForCollections(50);o.setCodePointLimit(4*1024*1024);o.setNestingDepthLimit(50);o.setAllowDuplicateKeys(false);
        try { new Yaml(new SafeConstructor(o)).compose(new StringReader(templates(88)));fail("UI14 failure not reproduced"); }
        catch(org.yaml.snakeyaml.error.YAMLException expected){ assertTrue(expected.getMessage().contains("aliases for non-scalar nodes exceeds")); }
    }
    @Test public void eightyEightTemplatesAreAcceptedWithoutExpansion() throws Exception {
        Node root=RuntimeYaml15.compose(templates(88));
        SequenceNode nodes=(SequenceNode)field(root,"proxies");assertEquals(88,nodes.getValue().size());
        Node base=field(root,"base");
        for(Node node:nodes.getValue()){ assertSame(base,field(node,"<<")); assertEquals("vless",scalar(field(node,"type"))); }
    }
    @Test public void collectionAliasBudgetHasAFiniteBoundary() throws Exception {
        RuntimeYaml15.compose(templates(RuntimeYaml15.MAX_COLLECTION_ALIASES));
        IOException error=rejected(templates(RuntimeYaml15.MAX_COLLECTION_ALIASES+1));
        assertTrue(error.getMessage().contains("[YAML_ALIAS_LIMIT]"));assertTrue(error.getMessage().contains("4096"));
    }
    @Test public void codePointBudgetIsRetained() {
        assertTrue(rejected("padding: '"+"x".repeat(RuntimeYaml15.MAX_CODE_POINTS)+"'\n").getMessage().contains("[YAML_SIZE_LIMIT]"));
    }
    @Test public void nestingBudgetIsRetained() {
        assertTrue(rejected("deep: "+"[".repeat(65)+"0"+"]".repeat(65)+"\n").getMessage().contains("[YAML_DEPTH_LIMIT]"));
    }
    @Test public void syntaxErrorsReportLocationWithoutCredentialsOrCause() {
        IOException error=rejected("secret: '\u79c1\u5bc6-TOKEN-ABC'\nproxies: [{url: 'https://private.test/?token=TOPSECRET', broken: [}\n");
        assertTrue(error.getMessage().contains("[YAML_SYNTAX]"));assertTrue(error.getMessage().contains("第 2 行"));
        assertFalse(error.getMessage().contains("TOPSECRET"));assertFalse(error.getMessage().contains("private.test"));
        assertFalse(error.getMessage().contains("TOKEN-ABC"));assertNull(error.getCause());
        StringWriter stack=new StringWriter();error.printStackTrace(new PrintWriter(stack));assertFalse(stack.toString().contains("TOPSECRET"));
    }
    @Test public void nonMappingAndEmptyRootsAreExplicitlyRejected() {
        assertTrue(rejected("[1,2,3]").getMessage().contains("[YAML_ROOT]"));
        assertTrue(rejected("# comments only\n").getMessage().contains("[YAML_ROOT]"));
        assertTrue(rejected("  \n").getMessage().contains("[YAML_EMPTY]"));
    }
    @Test public void multipleDocumentsAreNotSilentlyTruncated() {
        assertTrue(rejected("mode: rule\n---\nmode: direct\n").getMessage().contains("[YAML_DOCUMENTS]"));
    }
    @Test public void untrustedObjectTagsAreNotConstructed() {
        IOException error=rejected("object: !!java.net.URL ['https://TOPSECRET.test']\n");
        assertTrue(error.getMessage().contains("[YAML_SYNTAX]"));assertFalse(error.getMessage().contains("TOPSECRET"));
    }
    @Test public void explicitValueAndMergeSequencePriorityArePreserved() throws Exception {
        Node root=RuntimeYaml15.compose("a: &a {type: vless, client-fingerprint: safari}\nb: &b {type: trojan, client-fingerprint: chrome}\nnode: {<<: [*a, *b]}\nexplicit: {<<: [*a, *b], client-fingerprint: firefox}\n");
        assertEquals("vless",scalar(field(field(root,"node"),"type")));
        assertEquals("safari",scalar(field(field(root,"node"),"client-fingerprint")));
        assertEquals("firefox",scalar(field(field(root,"explicit"),"client-fingerprint")));
    }
    @Test(timeout=4000) public void recursiveMergeTraversalIsFinite() throws Exception {
        Node root=RuntimeYaml15.compose("base: &a {<<: *a, type: vless}\nnode: {<<: *a}\n");
        assertEquals("vless",scalar(field(field(root,"node"),"type")));
        assertNull(field(field(root,"node"),"client-fingerprint"));
    }
    @Test(timeout=4000) public void longAliasChainDoesNotUseRecursiveJavaCalls() throws Exception {
        StringBuilder source=new StringBuilder("a0: &a0 {type: vless}\n");
        for(int i=1;i<=2000;i++)source.append("a").append(i).append(": &a").append(i).append(" {<<: *a").append(i-1).append("}\n");
        Node root=RuntimeYaml15.compose(source.toString());
        assertEquals("vless",scalar(field(field(root,"a2000"),"type")));
        assertNull(field(field(root,"a2000"),"client-fingerprint"));
    }
    @Test(timeout=4000) public void repeatedAliasDagStaysASmallGraph() throws Exception {
        StringBuilder source=new StringBuilder("a0: &a0 {type: vless}\n");
        for(int i=1;i<=20;i++)source.append("a").append(i).append(": &a").append(i).append(" {<<: [*a").append(i-1).append(", *a").append(i-1).append("]}\n");
        Node root=RuntimeYaml15.compose(source.toString());
        assertNull(field(field(root,"a20"),"missing"));
        assertEquals("vless",scalar(field(field(root,"a20"),"type")));
        Set<Node> seen=Collections.newSetFromMap(new IdentityHashMap<>());Deque<Node> pending=new ArrayDeque<>();pending.add(root);
        while(!pending.isEmpty()){
            Node n=pending.pop();if(!seen.add(n))continue;
            if(n instanceof MappingNode)for(NodeTuple e:((MappingNode)n).getValue()){pending.add(e.getKeyNode());pending.add(e.getValueNode());}
            else if(n instanceof SequenceNode)pending.addAll(((SequenceNode)n).getValue());
        }
        assertTrue("graph was expanded",seen.size()<120);
    }
    @Test public void sourceTextAndEmojiMarksRemainUnchanged() throws Exception {
        String source="# \uD83C\uDDEF\uD83C\uDDF5 comment\r\nnode: {type: vless, name: '\uD83D\uDE00'} # retained\r\n";
        byte[] before=source.getBytes(java.nio.charset.StandardCharsets.UTF_8);Node root=RuntimeYaml15.compose(source);
        Node node=field(root,"node");int end=source.offsetByCodePoints(0,node.getEndMark().getIndex());
        assertEquals('}',source.charAt(end-1));assertArrayEquals(before,source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
