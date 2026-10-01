package io.github.xgl34222220.hetu;

import android.content.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Synthetic source files only; invokes the production subscription operations. */
public final class SubscriptionStructureTest {
    private static int checks, failures;
    private static final class Prefs implements SharedPreferences {
        final Map<String,String> data=new HashMap<>();
        public String getString(String k,String fallback){return data.getOrDefault(k,fallback);}
        public Editor edit(){return new Editor(){
            public Editor putString(String k,String v){data.put(k,v);return this;}
            public Editor remove(String k){data.remove(k);return this;}
            public void apply(){}
        };}
    }
    private static final class Host extends Context {
        final File root; final Prefs prefs=new Prefs();
        Host(String path){root=new File(path);root.mkdirs();}
        public File getFilesDir(){return root;}
        public SharedPreferences getSharedPreferences(String n,int mode){return prefs;}
    }
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private interface Case {void run()throws Exception;}
    private static void test(String name,Case action){try{action.run();System.out.println("PASS "+name);}catch(Throwable failure){failures++;System.out.println("FAIL "+name+": "+failure.getClass().getSimpleName()+": "+failure.getMessage());}}
    private static ProxyConfigLibrary.Entry load(ProxyConfigLibrary lib,String name,String text)throws Exception {
        return lib.importConfig(ProxyRuntimeProfile.Core.MIHOMO,name+".yaml",new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }
    private static void unchangedOnReject(ProxyConfigLibrary lib,ProxyConfigLibrary.Entry entry,Case action)throws Exception {
        String before=lib.read(entry);String selected=lib.selected(entry.core).name;
        try{action.run();throw new AssertionError("unsupported edit should reject");}catch(IOException expected){
            check(expected.getMessage().contains("YAML 编辑器"),"rejection gives actionable editor fallback");
        }
        check(lib.read(entry).equals(before),"rejected edit leaves source bytes intact");
        check(lib.selected(entry.core).name.equals(selected),"rejected edit keeps selection");
    }
    private static String providers(int indent){
        String p=" ".repeat(indent),q=p+p;
        return "proxy-providers:\n"+p+"alpha: # alpha heading\n"+q+"type: http\n"+q+"health-check:\n"+q+p+"url: 'https://health.invalid/204' # health stays\n"+q+"url: 'https://subscription.invalid/alpha' # account stays\n"+p+"beta:\n"+q+"type: http\n"+q+"url: 'https://subscription.invalid/beta'\n";
    }
    public static void main(String[] args)throws Exception {
        ProxyConfigLibrary lib=new ProxyConfigLibrary(new Host(args[0]));
        test("only direct provider URL is read",()->{
            var entry=load(lib,"read",providers(2)+"rules: []\n");
            var values=lib.subscriptions(entry);
            check(values.size()==2,"two providers with comments");
            check(values.get(0).url.equals("https://subscription.invalid/alpha"),"health-check must not become subscription");
        });
        test("URL edit preserves unrelated source bytes",()->{
            String source=providers(2)+"# footer 😀\nrules: []\n\n";
            var entry=load(lib,"update",source);
            lib.updateSubscription(entry,"alpha","https://subscription.invalid/changed#fragment");
            check(lib.read(entry).equals(source.replace("https://subscription.invalid/alpha","https://subscription.invalid/changed#fragment")),"only direct scalar is changed, including comments and trailing lines");
        });
        test("ordinary imported indentation and CRLF",()->{
            String source=(providers(4)+"rules: []\n").replace("\n","\r\n");
            var entry=load(lib,"indent",source);
            check(lib.subscriptions(entry).size()==2,"four-space providers discovered");
            lib.updateSubscription(entry,"alpha","https://subscription.invalid/new");
            check(lib.read(entry).equals(source.replace("https://subscription.invalid/alpha","https://subscription.invalid/new")),"CRLF and four-space layout remain");
        });
        test("add without BaseProvider produces independent provider",()->{
            var entry=load(lib,"add",providers(2)+"proxy-groups:\n  - name: Select\n    type: select\n    use: [alpha, 'beta'] # group stays\nrules: []\n");
            lib.addSubscription(entry,"gamma","https://subscription.invalid/gamma");
            String result=lib.read(entry);
            check(!result.contains("*BaseProvider"),"no undefined template alias");
            check(lib.subscriptions(entry).size()==3,"new provider readable");
            check(result.contains("# group stays"),"inline comment preserved");
            check(result.contains("'beta'"),"existing quoted name remains");
        });
        test("add updates block use",()->{
            var entry=load(lib,"add-block",providers(4)+"proxy-groups:\n  - name: Select\n    type: select\n    use: # retained\n      - 'alpha' # first\n      - beta\nrules: []\n");
            lib.addSubscription(entry,"gamma","https://subscription.invalid/gamma");
            String result=lib.read(entry);
            check(result.contains("      - 'gamma'"),"block reference added using existing indentation");
            check(result.contains("      - 'alpha' # first"),"existing entry remains byte-for-byte");
        });
        test("delete clears block and inline use references",()->{
            var entry=load(lib,"delete",providers(2)+"proxy-groups:\n  - name: Both\n    type: select\n    use:\n      - alpha # keep this explanation\n      - 'beta' # other\n  - name: Only\n    type: select\n    use:\n    - alpha\n  - name: Inline\n    type: select\n    use: ['alpha', beta] # tail\nrules: []\n");
            lib.deleteSubscription(entry,"alpha");
            String result=lib.read(entry);
            check(lib.subscriptions(entry).size()==1,"provider removed");
            check(!result.contains("- alpha")&&!result.contains("'alpha'"),"all references removed");
            check(result.contains("use: []"),"empty block use is a sequence, not null");
            check(result.contains("# keep this explanation")&&result.contains("'beta' # other")&&result.contains("# tail"),"comments and unrelated entries preserved");
        });
        test("shared URL alias is overridden locally",()->{
            String source="shared: &shared 'https://subscription.invalid/shared'\nproxy-providers:\n  alpha:\n    type: http\n    url: *shared # alias comment\n  beta:\n    type: http\n    url: *shared\nrules: []\n";
            var entry=load(lib,"url-alias",source);
            lib.updateSubscription(entry,"alpha","https://subscription.invalid/new");
            String result=lib.read(entry);
            check(result.equals(source.replace("url: *shared # alias comment","url: 'https://subscription.invalid/new' # alias comment")),"only selected alias occurrence replaced");
        });
        test("missing direct URL cannot overwrite nested health",()->{
            String source="proxy-providers:\n  alpha:\n    type: http\n    health-check:\n      url: 'https://health.invalid/204'\nrules: []\n";
            var entry=load(lib,"no-url",source);
            check(lib.subscriptions(entry).isEmpty(),"health only provider is not a subscription");
            try{lib.updateSubscription(entry,"alpha","https://subscription.invalid/new");throw new AssertionError("missing direct URL should reject");}catch(IOException expected){}
            check(lib.read(entry).equals(source),"rejection does not modify source");
        });
        test("merged URL rejects without changing shared defaults",()->{
            String source="defaults: &defaults\n  type: http\n  url: 'https://subscription.invalid/shared'\nproxy-providers:\n  alpha:\n    <<: *defaults\n  beta:\n    <<: *defaults\n    url: 'https://subscription.invalid/beta'\nrules: []\n";
            var entry=load(lib,"merged-url",source);
            unchangedOnReject(lib,entry,()->lib.updateSubscription(entry,"alpha","https://subscription.invalid/new"));
        });
        test("URL anchor declaration rejects without changing alias users",()->{
            String source="proxy-providers:\n  alpha:\n    type: http\n    url: &shared 'https://subscription.invalid/shared'\n  beta:\n    type: http\n    url: *shared\nrules: []\n";
            var entry=load(lib,"declared-url",source);
            unchangedOnReject(lib,entry,()->lib.updateSubscription(entry,"alpha","https://subscription.invalid/new"));
        });
        test("inline use preserves commas hashes escaped quotes and unrelated fields",()->{
            String source=providers(2)+"proxy-groups:\n  - name: Inline\n    type: select\n    use: [\"al\\u0070ha\", 'beta', 'other, node', 'it''s # fine', \"escaped\\\"name\"] # tail\n    extra:\n      use: [alpha] # unrelated nested key\nother:\n  use: [alpha] # unrelated root map\nrules: []\n";
            var entry=load(lib,"quoted-list",source);lib.deleteSubscription(entry,"alpha");String result=lib.read(entry);
            check(result.contains("use: [ 'beta', 'other, node', 'it''s # fine', \"escaped\\\"name\"] # tail"),"surviving list tokens remain exact");
            check(result.contains("      use: [alpha] # unrelated nested key")&&result.contains("  use: [alpha] # unrelated root map"),"unrelated use fields untouched");
        });
        test("quoted new names and duplicate add",()->{
            var entry=load(lib,"quoted-name",providers(2)+"proxy-groups:\n  - name: Select\n    use: [alpha, beta]\nrules: []\n");
            lib.addSubscription(entry,"it's new","https://subscription.invalid/new");String before=lib.read(entry);
            check(before.contains("'it''s new':")&&before.contains("'it''s new']"),"new name is YAML-quoted in both mapping and references");
            check(lib.subscriptions(entry).stream().anyMatch(s->s.name.equals("it's new")),"quoted name decodes consistently");
            try{lib.addSubscription(entry,"it's new","https://subscription.invalid/duplicate");throw new AssertionError("duplicate accepted");}catch(IOException expected){}
            check(lib.read(entry).equals(before),"duplicate add does not write");
        });
        test("existing BaseProvider template is retained",()->{
            String source="BaseProvider: &BaseProvider\n  type: http\n  interval: 86400\n  health-check: {enable: true, url: 'https://health.invalid/check'}\n"+providers(2)+"rules: []\n";
            var entry=load(lib,"base-provider",source);lib.addSubscription(entry,"gamma","https://subscription.invalid/gamma");String result=lib.read(entry);
            check(result.startsWith(source.substring(0,source.indexOf("proxy-providers:"))),"template stays exact");
            check(result.contains("'gamma':\n    <<: *BaseProvider\n"),"existing template reused");
        });
        test("bundled source supports round trip subscription edits",()->{
            String source;try(InputStream input=BundledProxyConfig.open()){source=new String(input.readAllBytes(),StandardCharsets.UTF_8);}
            var entry=load(lib,"bundled",source);int before=lib.subscriptions(entry).size();
            lib.addSubscription(entry,"合成测试","https://subscription.invalid/synthetic");
            check(lib.subscriptions(entry).size()==before+1,"bundled subscription added");
            check(lib.read(entry).contains("*BaseProvider"),"bundled defaults retained");
            lib.updateSubscription(entry,"合成测试","https://subscription.invalid/updated");
            check(lib.subscriptions(entry).stream().anyMatch(s->s.name.equals("合成测试")&&s.url.endsWith("/updated")),"bundled URL edit verified");
            lib.deleteSubscription(entry,"合成测试");
            check(lib.subscriptions(entry).size()==before&&!lib.read(entry).contains("合成测试"),"bundled references removed");
        });
        test("group template block use is updated once",()->{
            String source="Unrelated: &Unrelated\n  use: [alpha] # not a group template\nGroup: &Group\n  type: select\n  use:\n    - alpha # template note\n    - beta\nInherited: &Inherited\n  <<: *Group\n"+providers(2)+"proxy-groups:\n  - name: One\n    <<: *Inherited\n  - name: Two\n    <<: *Group\nrules: []\n";
            var entry=load(lib,"group-template",source);lib.deleteSubscription(entry,"alpha");String result=lib.read(entry);
            check(!result.contains("- alpha")&&result.contains("# template note")&&result.contains("- beta"),"shared use cleared and comments retained");
            check(result.contains("<<: *Group"),"group merge remains");
            check(result.startsWith("Unrelated: &Unrelated\n  use: [alpha] # not a group template\n"),"unreferenced anchored data remains exact");
        });
        test("complex structures reject before any partial write",()->{
            for(String shape:new String[]{"use: *shared", "use: [alpha,\n      beta]", "use: [{name: alpha}]"}){
                String source="shared: &shared [alpha, beta]\n"+providers(2)+"proxy-groups:\n  - name: Select\n    "+shape+"\nrules: []\n";
                var entry=load(lib,"unsupported-use",source);
                unchangedOnReject(lib,entry,()->lib.deleteSubscription(entry,"alpha"));
                unchangedOnReject(lib,entry,()->lib.addSubscription(entry,"gamma","https://subscription.invalid/gamma"));
            }
            var flow=load(lib,"flow-provider","proxy-providers: {alpha: {type: http, url: 'https://subscription.invalid/alpha'}}\nrules: []\n");
            unchangedOnReject(lib,flow,()->lib.updateSubscription(flow,"alpha","https://subscription.invalid/new"));
        });
        test("deleting a referenced provider anchor rejects",()->{
            String source=providers(2).replace("alpha: # alpha heading","alpha: &alpha # alpha heading")+"another: *alpha\nrules: []\n";
            var entry=load(lib,"provider-anchor",source);
            unchangedOnReject(lib,entry,()->lib.deleteSubscription(entry,"alpha"));
            String nested=providers(2).replace("health-check:","health-check: &health")+"another: *health\nrules: []\n";
            var nestedEntry=load(lib,"nested-anchor",nested);
            unchangedOnReject(lib,nestedEntry,()->lib.deleteSubscription(nestedEntry,"alpha"));
        });
        test("unsupported inherited references reject safely",()->{
            for(String template:new String[]{"{use: [alpha, beta]}", "{\"use\": [alpha, beta]}", "{\"u\\u0073e\": [alpha, beta]}"}){
                var entry=load(lib,"flow-template","Group: &Group "+template+"\n"+providers(2)+"proxy-groups:\n  - name: One\n    <<: *Group\nrules: []\n");
                unchangedOnReject(lib,entry,()->lib.deleteSubscription(entry,"alpha"));
            }
        });
        test("multiline scalar URL rejects without truncation",()->{
            var entry=load(lib,"multiline",providers(2).replace("'https://subscription.invalid/alpha' # account stays","https://subscription.invalid/alpha\n      continuation")+"rules: []\n");
            unchangedOnReject(lib,entry,()->lib.updateSubscription(entry,"alpha","https://subscription.invalid/new"));
        });
        test("quoted punctuation names round trip safely",()->{
            var entry=load(lib,"punctuation",providers(2)+"proxy-groups:\n  - name: One\n    use: [alpha, beta]\nrules: []\n");
            String name="alpha: # [one], two";
            lib.addSubscription(entry,name,"https://subscription.invalid/new");
            check(lib.subscriptions(entry).stream().anyMatch(s->s.name.equals(name)),"punctuation name is read exactly");
            lib.updateSubscription(entry,name,"https://subscription.invalid/updated");
            lib.deleteSubscription(entry,name);
            check(!lib.read(entry).contains(name),"quoted punctuation provider and reference removed");
        });
        test("empty provider map and no final newline",()->{
            var empty=load(lib,"empty-provider","proxy-providers: {} # retained\nrules: []");
            lib.addSubscription(empty,"alpha","https://subscription.invalid/alpha");
            check(lib.subscriptions(empty).size()==1,"empty mapping expanded safely");
            check(lib.read(empty).contains("# retained")&&lib.read(empty).endsWith("rules: []"),"comment and final line unchanged");
            var eof=load(lib,"eof",providers(2).stripTrailing());
            lib.addSubscription(eof,"gamma","https://subscription.invalid/gamma");
            check(lib.subscriptions(eof).size()==3,"last provider without newline can accept append");
        });
        test("group mapping column follows spaces after sequence dash",()->{
            String source=providers(2)+"proxy-groups:\n  -   name: Wide\n      type: select\n      use: [alpha, beta]\n  - name: Ordinary\n    use:\n      - alpha\n      - beta\nrules: []\n";
            var entry=load(lib,"group-columns",source);
            lib.addSubscription(entry,"gamma","https://subscription.invalid/gamma");
            String added=lib.read(entry);
            check(added.contains("      use: [alpha, beta, 'gamma']"),"wide mapping gets the new provider reference");
            check(added.contains("      - 'gamma'"),"next ordinary group gets the new provider reference");
            lib.deleteSubscription(entry,"alpha");String removed=lib.read(entry);
            check(removed.contains("      use: [ beta, 'gamma']"),"wide mapping cannot retain a deleted provider");
            check(!removed.contains("- alpha")&&lib.subscriptions(entry).size()==2,"ordinary block references and provider are deleted together");
        });
        test("multiline quotes cannot disguise group fields",()->{
            String source=providers(2)+"proxy-groups:\n  - name: \"Multi\n    use: [alpha, beta]\n      line\"\n    type: select\n    proxies: [DIRECT]\nrules: ['MATCH,Multi use: [alpha, beta] line']\n";
            var entry=load(lib,"quoted-group-continuation",source);
            unchangedOnReject(lib,entry,()->lib.deleteSubscription(entry,"alpha"));
            unchangedOnReject(lib,entry,()->lib.addSubscription(entry,"gamma","https://subscription.invalid/gamma"));
        });
        test("block scalar content is never preserved as structural comments",()->{
            String source="proxy-providers:\n  beta:\n    type: http\n    url: 'https://subscription.invalid/beta'\n    filter: |-\n      ^beta$\n  alpha:\n    type: http\n    url: 'https://subscription.invalid/alpha'\n    filter: |\n      # literal filter data\nrules: []\n";
            var entry=load(lib,"block-filter",source);
            unchangedOnReject(lib,entry,()->lib.deleteSubscription(entry,"alpha"));
            unchangedOnReject(lib,entry,()->lib.addSubscription(entry,"gamma","https://subscription.invalid/gamma"));
            for(String header:new String[]{"|",">-","|2+","&note |","!!str >","&note !!str |"}){
                var variant=load(lib,"block-header","notes: "+header+"\n  use: [alpha, beta]\n"+providers(2)+"rules: []\n");
                unchangedOnReject(lib,variant,()->lib.updateSubscription(variant,"alpha","https://subscription.invalid/new"));
            }
            var sequence=load(lib,"block-sequence","notes:\n  - |\n    fake: &url 'https://subscription.invalid/fake'\n"+providers(2)+"rules: []\n");
            unchangedOnReject(lib,sequence,()->lib.deleteSubscription(sequence,"alpha"));
        });
        test("block scalar text cannot impersonate a URL anchor",()->{
            String source="links:\n  - &url https://subscription.invalid/real\nnotes: |\n  fake: &url 'https://subscription.invalid/fake'\n"+providers(2).replace("'https://subscription.invalid/alpha' # account stays","*url # account stays")+"rules: []\n";
            var entry=load(lib,"fake-scalar-anchor",source);
            unchangedOnReject(lib,entry,()->lib.updateSubscription(entry,"alpha","https://subscription.invalid/new"));
            try{lib.subscriptions(entry);throw new AssertionError("fake block anchor must not supply the displayed URL");}catch(IOException expected){check(expected.getMessage().contains("YAML 编辑器"),"unsafe source gets editor fallback");}
        });
        test("group templates shared with unrelated consumers reject",()->{
            for(String reference:new String[]{"other: *Group\n","other:\n  <<: *Group\n","other: {copy: *Group}\n"}){
                String source="Group: &Group\n  type: select\n  use: [alpha, beta]\n"+reference+providers(2)+"proxy-groups:\n  - name: Select\n    <<: *Group\nrules: []\n";
                var entry=load(lib,"shared-group-template",source);
                unchangedOnReject(lib,entry,()->lib.addSubscription(entry,"gamma","https://subscription.invalid/gamma"));
                unchangedOnReject(lib,entry,()->lib.deleteSubscription(entry,"alpha"));
            }
            String inherited="Group: &Group\n  type: select\n  use: [alpha, beta]\nInherited: &Inherited\n  <<: *Group\nother: *Inherited\n"+providers(2)+"proxy-groups:\n  - name: Select\n    <<: *Inherited\nrules: []\n";
            var entry=load(lib,"indirect-shared-template",inherited);
            unchangedOnReject(lib,entry,()->lib.deleteSubscription(entry,"alpha"));
        });
        test("anchor redefinitions cannot select the wrong source mapping",()->{
            String source="Group: &Group\n  type: select\n  use: [alpha, beta]\nother:\n  actual: &Group\n    type: select\n    use: [alpha, beta]\n"+providers(2)+"proxy-groups:\n  - name: Select\n    <<: *Group\nrules: []\n";
            var entry=load(lib,"redefined-group-anchor",source);
            unchangedOnReject(lib,entry,()->lib.deleteSubscription(entry,"alpha"));
            unchangedOnReject(lib,entry,()->lib.addSubscription(entry,"gamma","https://subscription.invalid/gamma"));
            String scalar="shared: &url 'https://subscription.invalid/first'\nlinks:\n  - &url https://subscription.invalid/real\n"+providers(2).replace("'https://subscription.invalid/alpha' # account stays","*url # account stays")+"rules: []\n";
            var scalarEntry=load(lib,"redefined-scalar-anchor",scalar);
            unchangedOnReject(lib,scalarEntry,()->lib.updateSubscription(scalarEntry,"alpha","https://subscription.invalid/new"));
        });
        test("multiline flow content cannot masquerade as direct group use",()->{
            String source=providers(2)+"proxy-groups:\n  - name: Select\n    extra: {\n    use: [alpha, beta]\n    }\n    type: select\n    proxies: [DIRECT]\nrules: []\n";
            var entry=load(lib,"flow-group-continuation",source);
            unchangedOnReject(lib,entry,()->lib.deleteSubscription(entry,"alpha"));
            unchangedOnReject(lib,entry,()->lib.addSubscription(entry,"gamma","https://subscription.invalid/gamma"));
        });
        test("explicit mapping keys cannot hide a group provider reference",()->{
            String source=providers(2)+"proxy-groups:\n  - name: Select\n    type: select\n    ? use\n    : [alpha, beta]\nrules: []\n";
            var entry=load(lib,"explicit-group-key",source);
            unchangedOnReject(lib,entry,()->lib.deleteSubscription(entry,"alpha"));
            unchangedOnReject(lib,entry,()->lib.addSubscription(entry,"gamma","https://subscription.invalid/gamma"));
        });
        if(failures>0)throw new AssertionError(failures+" scenarios failed ("+checks+" checks reached)");
        System.out.println("SubscriptionStructureTest passed: "+checks+" checks");
    }
}
