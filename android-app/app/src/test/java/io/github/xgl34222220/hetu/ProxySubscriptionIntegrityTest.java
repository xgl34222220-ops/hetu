package io.github.xgl34222220.hetu;

import android.app.Application;
import androidx.test.core.app.ApplicationProvider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.yaml.snakeyaml.nodes.*;
import static org.junit.Assert.*;

/** Import and mutate synthetic source files through the real library, then compose their saved bytes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={35},application=Application.class)
public class ProxySubscriptionIntegrityTest {
    private ProxyConfigLibrary library;
    private static final ProxyRuntimeProfile.Core CORE=ProxyRuntimeProfile.Core.MIHOMO;
    private static final String RULES="rules:\n  - DOMAIN,first.example,DIRECT # first remains first\n  - MATCH,DIRECT\n";
    @Before public void createLibrary(){Application app=ApplicationProvider.getApplicationContext();library=new ProxyConfigLibrary(app);}
    private ProxyConfigLibrary.Entry imported(String source)throws Exception{return library.importConfig(CORE,"subscription-integrity.yaml",new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));}
    private static Node value(Node node,String key){return RuntimeYaml15.inherited(node,key);}
    private static String text(Node node){return ((ScalarNode)node).getValue();}
    private static List<String> items(Node node){List<String> result=new ArrayList<>();for(Node item:((SequenceNode)node).getValue())result.add(text(item));return result;}
    private Node saved(ProxyConfigLibrary.Entry entry)throws Exception{return RuntimeYaml15.compose(library.read(entry));}
    private static Node group(Node root,int index){return ((SequenceNode)value(root,"proxy-groups")).getValue().get(index);}
    private static String providers(){return "proxy-providers:\n  alpha:\n    type: http\n    url: 'https://alpha.example/sub'\n  beta:\n    type: http\n    url: 'https://beta.example/sub'\n";}
    private interface Change {void run()throws Exception;}
    private void unchanged(ProxyConfigLibrary.Entry entry,Change change)throws Exception{byte[] before=java.nio.file.Files.readAllBytes(entry.file.toPath());try{change.run();fail("unsafe subscription mutation succeeded");}catch(IOException expected){assertFalse(expected.getMessage().isEmpty());}assertArrayEquals(before,java.nio.file.Files.readAllBytes(entry.file.toPath()));}

    @Test public void addWithoutTemplateWritesHttpAndUpdatesBothGroupListStyles()throws Exception{
        for(String prefix:Arrays.asList(providers(),providers().replace("proxy-providers:\n","proxy-providers: &providers\n"))){
        String source=prefix+"proxy-groups:\n  - name: Flow\n    type: select\n    use: [alpha, beta] # flow comment\n  - name: Block\n    type: select\n    use:\n      - alpha # existing item\n      - beta\n"+RULES;
        ProxyConfigLibrary.Entry entry=imported(source);library.addSubscription(entry,"new provider","https://new.example/sub?x=1#part");Node root=saved(entry),added=value(value(root,"proxy-providers"),"new provider");
        assertEquals("http",text(value(added,"type")));assertEquals("3600",text(value(added,"interval")));assertEquals("https://new.example/sub?x=1#part",text(value(added,"url")));
        assertEquals(Arrays.asList("alpha","beta","new provider"),items(value(group(root,0),"use")));assertEquals(items(value(group(root,0),"use")),items(value(group(root,1),"use")));
        assertFalse(library.read(entry).contains("*BaseProvider"));assertTrue(library.read(entry).endsWith(RULES));assertTrue(library.read(entry).contains("# flow comment"));assertTrue(library.read(entry).contains("# existing item"));
        assertTrue(library.read(entry).startsWith(prefix));
        }
    }
    @Test public void addWithRealHttpTemplateKeepsHeadersHealthCheckAndIntervalInheritance()throws Exception{
        String base="base: &BaseProvider\n  type: http\n  interval: 7200\n  header:\n    User-Agent: ['original-agent']\n  health-check: {enable: true, url: 'https://health.example/204', interval: 123}\n";
        ProxyConfigLibrary.Entry entry=imported(base+providers()+"proxy-groups: [{name: G, type: select, use: [alpha]}]\n"+RULES);library.addSubscription(entry,"extra","https://extra.example/sub");Node root=saved(entry),template=value(root,"base"),added=value(value(root,"proxy-providers"),"extra");
        assertSame(template,value(added,"<<"));assertSame(value(template,"header"),value(added,"header"));assertSame(value(template,"health-check"),value(added,"health-check"));assertEquals("7200",text(value(added,"interval")));assertTrue(library.read(entry).startsWith(base));assertTrue(library.read(entry).endsWith(RULES));
    }
    @Test public void deleteRemovesAllFlowAndBlockReferencesAndRetainsOtherUse()throws Exception{
        String unrelated="rule-providers:\n  alpha: {type: http, behavior: classical, url: 'https://rules.example/list'}\ncustom:\n  use: [alpha, beta] # not a proxy group\n";
        String source=providers()+"# provider separator stays\nproxy-groups:\n  - {name: Flow, type: select, use: [alpha, beta, alpha]} # group comment\n  - name: Block\n    type: select\n    use:\n      - alpha # item comment stays\n      # between items stays\n      - beta\n"+unrelated+RULES;
        ProxyConfigLibrary.Entry entry=imported(source);library.deleteSubscription(entry,"alpha");Node root=saved(entry);
        assertNull(value(value(root,"proxy-providers"),"alpha"));assertEquals(Collections.singletonList("beta"),items(value(group(root,0),"use")));assertEquals(Collections.singletonList("beta"),items(value(group(root,1),"use")));
        String result=library.read(entry);assertTrue(result.contains(unrelated));assertTrue(result.contains("# provider separator stays\n"));assertTrue(result.contains("# item comment stays"));assertTrue(result.contains("# between items stays"));assertTrue(result.endsWith(RULES));
    }
    @Test public void deletingOnlyUseValueKeepsAnEmptySequenceInBothStyles()throws Exception{
        ProxyConfigLibrary.Entry entry=imported(providers()+"proxy-groups:\n  - {name: Flow, type: select, use: [alpha, alpha,]}\n  - name: Block\n    type: select\n    use:\n      - alpha # preserve this\n      - alpha\n"+RULES);library.deleteSubscription(entry,"alpha");Node root=saved(entry);
        assertTrue(items(value(group(root,0),"use")).isEmpty());assertTrue(items(value(group(root,1),"use")).isEmpty());assertTrue(library.read(entry).contains("# preserve this"));
    }
    @Test public void quotedCommaHashAndEscapedProviderNamesAreNotSplitOrUnquotedIncorrectly()throws Exception{
        String source="proxy-providers:\n  'O''Brien, #East': # header comment\n    type: http\n    url: 'https://east.example/sub#fragment'\n  \"back\\\\slash\": {type: http, url: \"https://west.example/sub\"}\nproxy-groups:\n  - {name: G, type: select, use: ['O''Brien, #East', \"back\\\\slash\"]}\n"+RULES;
        ProxyConfigLibrary.Entry entry=imported(source);List<ProxyConfigLibrary.Subscription> subs=library.subscriptions(entry);assertEquals(2,subs.size());assertEquals("O'Brien, #East",subs.get(0).name);assertEquals("back\\slash",subs.get(1).name);assertEquals("https://east.example/sub#fragment",subs.get(0).url);
        library.deleteSubscription(entry,"O'Brien, #East");Node root=saved(entry);assertEquals(Collections.singletonList("back\\slash"),items(value(group(root,0),"use")));assertTrue(library.read(entry).endsWith(RULES));
    }
    @Test public void addingQuotedNameRetainsOriginalCrLfEmojiAndUnrelatedSourceBytes()throws Exception{
        String prefix="# \uD83D\uDE00 untouched\r\nmode: rule\r\n";String tail=("custom: {use: [alpha]} # unrelated\n"+RULES).replace("\n","\r\n");String source=prefix+(providers()+"proxy-groups: [{name: G, type: select, use: [alpha,]}]\n").replace("\n","\r\n")+tail;
        ProxyConfigLibrary.Entry entry=imported(source);library.addSubscription(entry,"O'Brien, #new","https://new.example/sub?a='b'");Node root=saved(entry);assertEquals(Arrays.asList("alpha","O'Brien, #new"),items(value(group(root,0),"use")));
        String result=library.read(entry);assertTrue(result.startsWith(prefix));assertTrue(result.endsWith(tail));assertFalse(result.replace("\r\n","").contains("\n"));assertEquals("https://new.example/sub?a='b'",text(value(value(value(root,"proxy-providers"),"O'Brien, #new"),"url")));
    }
    @Test public void flowUseCommentsAndTrailingCommasSurviveDeletion()throws Exception{
        String groups="proxy-groups:\n  - name: G\n    type: select\n    use: [alpha, # first comment\n          beta, # survivor comment\n          alpha,] # after list\n";
        ProxyConfigLibrary.Entry entry=imported(providers()+groups+RULES);library.deleteSubscription(entry,"alpha");Node root=saved(entry);assertEquals(Collections.singletonList("beta"),items(value(group(root,0),"use")));String result=library.read(entry);for(String comment:Arrays.asList("# first comment","# survivor comment","# after list"))assertTrue(result.contains(comment));
    }
    @Test public void sharedOrInheritedGroupUsesFailWithoutChangingAnySourceBytes()throws Exception{
        for(String groups:Arrays.asList("shared: &shared [alpha, beta]\nproxy-groups: [{name: G, type: select, use: *shared}]\n","template: &G {type: select, use: [alpha, beta]}\nproxy-groups: [{name: G, <<: *G}]\n")){
            ProxyConfigLibrary.Entry entry=imported(providers()+groups+RULES);unchanged(entry,()->library.deleteSubscription(entry,"alpha"));unchanged(entry,()->library.addSubscription(entry,"new","https://new.example/sub"));
        }
    }
    @Test public void deletingAnAnchorStillUsedElsewhereFailsAndKeepsOriginalFile()throws Exception{
        String source="proxy-providers:\n  alpha: &A {type: http, url: 'https://alpha.example/sub'}\n  beta: {type: http, url: 'https://beta.example/sub'}\ncustom: *A\nproxy-groups: [{name: G, type: select, use: [alpha, beta]}]\n"+RULES;
        ProxyConfigLibrary.Entry entry=imported(source);unchanged(entry,()->library.deleteSubscription(entry,"alpha"));
    }
    @Test public void unsupportedContainersInvalidTemplatesAndDuplicateFieldsKeepOriginalFiles()throws Exception{
        for(String source:Arrays.asList("proxy-providers: {alpha: {type: http, url: 'https://alpha.example/sub'}, beta: {type: http, url: 'https://beta.example/sub'}}\n"+RULES,"base: &BaseProvider {type: file, path: ./existing.yaml}\n"+providers()+RULES,providers()+"mode: rule\nmode: direct\n"+RULES,providers()+"broken: [}\n")){
            ProxyConfigLibrary.Entry entry=imported(source);unchanged(entry,()->library.addSubscription(entry,"new","https://new.example/sub"));
        }
    }
    @Test public void updatingAQuotedFlowUrlPreservesCommentsAndRejectsProviderAliases()throws Exception{
        String source="proxy-providers:\n  'quoted provider': {type: http, url: 'https://old.example/sub#part'} # comment survives\n  beta: {type: http, url: 'https://beta.example/sub'}\n"+RULES;
        ProxyConfigLibrary.Entry entry=imported(source);library.updateSubscription(entry,"quoted provider","https://new.example/sub?x='value'#part");String expected=source.replace("'https://old.example/sub#part'","'https://new.example/sub?x=''value''#part'");assertEquals(expected,library.read(entry));saved(entry);
        ProxyConfigLibrary.Entry aliases=imported("proxy-providers:\n  alpha: &A {type: http, url: 'https://alpha.example/sub'}\n  beta: *A\n"+RULES);unchanged(aliases,()->library.updateSubscription(aliases,"beta","https://new.example/sub"));unchanged(aliases,()->library.updateSubscription(aliases,"alpha","https://new.example/sub"));
    }
    @Test public void aLastSubscriptionCannotBeDeletedAndNewCachePathsDoNotCollide()throws Exception{
        ProxyConfigLibrary.Entry entry=imported("proxy-providers:\n  'a/b': {type: http, url: 'https://alpha.example/sub', path: './proxy_provider/a_b.yaml'}\n"+RULES);unchanged(entry,()->library.deleteSubscription(entry,"a/b"));library.addSubscription(entry,"a_b","https://new.example/sub");Node providers=value(saved(entry),"proxy-providers");assertNotEquals(text(value(value(providers,"a/b"),"path")),text(value(value(providers,"a_b"),"path")));assertTrue(library.read(entry).endsWith(RULES));
    }
}
