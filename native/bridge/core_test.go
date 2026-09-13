package main
import("strings";"testing";"gopkg.in/yaml.v3";"github.com/metacubex/mihomo/config";C "github.com/metacubex/mihomo/constant")
const sample="mode: rule\nproxies: []\nproxy-groups:\n - name: SELECT\n   type: select\n   proxies: [DIRECT]\nrules: [MATCH,SELECT]\ndns:\n enable: true\n nameserver: [https://1.1.1.1/dns-query]\n nameserver-policy:\n  stun.example.test: [rcode://name_error]\n"
func TestRuntimeCopy(t *testing.T){
 s:=strings.Replace(sample,"rules: [MATCH,SELECT]","rules: ['MATCH,SELECT']",1);r:=request{YAML:s,Filter:true,Domains:[]string{"ads.example.test"},SuffixDomains:[]string{"tracker.example.test"},AllowDomains:[]string{"safe.tracker.example.test"}}
 b,e:=prepare(r);if e!=nil{t.Fatal(e)};text:=string(b);var m map[string]any;if yaml.Unmarshal(b,&m)!=nil{t.Fatal("generated yaml invalid")}
 if !strings.Contains(text,"stun.example.test")||!strings.Contains(text,"rcode://name_error"){t.Fatal("DNS policy lost")}
 if !strings.Contains(text,"DOMAIN-SUFFIX,tracker.example.test"){t.Fatal("suffix rule missing")}
 if !strings.Contains(text,"DOMAIN,safe.tracker.example.test,PASS"){t.Fatal("allow PASS missing")}
 if strings.Contains(text,"DOMAIN,safe.tracker.example.test,DIRECT"){t.Fatal("whitelist must never force direct")}
 if !strings.Contains(text,"SUB-RULE,(OR,((NETWORK,TCP),(NETWORK,UDP))),"+filterSubRule){t.Fatal("isolated filter branch missing")}
 if strings.Index(text,"DOMAIN,safe.tracker.example.test,PASS")>strings.Index(text,"RULE-SET,"+filterName+",REJECT"){t.Fatal("allow PASS must precede reject inside branch")}
 C.SetHomeDir(t.TempDir());if _,e=config.Parse(b);e!=nil{t.Fatal(e)}
 if r.YAML!=s{t.Fatal("input changed")}
}
func TestRejectUnsafeOrUnsupported(t *testing.T){for _,s:=range []string{"[bad","proxies: []\nproxies: []","proxies: []\nmode: global","proxies: []\nlisteners: [{type: ebpf}]","proxies: []\ndns: {enable: false}"}{if _,e:=prepare(request{YAML:s});e==nil{t.Fatalf("accepted %q",s)}}}
func TestRejectBadInjectedDomains(t *testing.T){
 s:=strings.Replace(sample,"rules: [MATCH,SELECT]","rules: ['MATCH,SELECT']",1)
 for _,r:=range []request{{YAML:s,Filter:true,Domains:[]string{"bad/domain"}},{YAML:s,Filter:true,SuffixDomains:[]string{"bad,domain"}},{YAML:s,Filter:true,AllowDomains:[]string{"no-dot"}}}{if _,e:=prepare(r);e==nil{t.Fatal("unsafe injected domain accepted")}}
}
func TestRejectInternalNameCollisions(t *testing.T){
 s:=strings.Replace(sample,"rules: [MATCH,SELECT]","rules: ['MATCH,SELECT']",1)
 for _,extra:=range []string{"\nrule-providers:\n  "+filterName+": {type: inline, behavior: classical, payload: ['DOMAIN,x.test']}\n","\nsub-rules:\n  "+filterSubRule+": ['MATCH,DIRECT']\n"}{if _,e:=prepare(request{YAML:s+extra,Filter:true,Domains:[]string{"ads.example.test"}});e==nil{t.Fatal("internal name collision accepted")}}
}
func TestNoExternalListeners(t *testing.T){b,e:=prepare(request{YAML:sample+"external-controller: 0.0.0.0:9090\nsecret: private-token\ntproxy-port: 9898\n"});if e!=nil{t.Fatal(e)};if strings.Contains(string(b),"private-token")||strings.Contains(string(b),"0.0.0.0:9090"){t.Fatal("controller retained")}}
