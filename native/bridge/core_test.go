package main
import("strings";"testing";"gopkg.in/yaml.v3";"github.com/metacubex/mihomo/config";C "github.com/metacubex/mihomo/constant")
const sample="mode: rule\nproxies: []\nproxy-groups:\n - name: SELECT\n   type: select\n   proxies: [DIRECT]\nrules: [MATCH,SELECT]\ndns:\n enable: true\n nameserver: [https://1.1.1.1/dns-query]\n nameserver-policy:\n  stun.example.test: [rcode://name_error]\n"
func TestRuntimeCopy(t *testing.T){
 s:=strings.Replace(sample,"rules: [MATCH,SELECT]","rules: ['MATCH,SELECT']",1);r:=request{YAML:s,Filter:true,DnsGuard:true,Domains:[]string{"ads.example.test"},SuffixDomains:[]string{"tracker.example.test"},AllowDomains:[]string{"safe.tracker.example.test"},DohDomains:[]string{"dns.google","cloudflare-dns.com"}}
 b,e:=prepare(r);if e!=nil{t.Fatal(e)};text:=string(b);var m map[string]any;if yaml.Unmarshal(b,&m)!=nil{t.Fatal("generated yaml invalid")}
 if !strings.Contains(text,"stun.example.test")||!strings.Contains(text,"rcode://name_error"){t.Fatal("DNS policy lost")}
 if !strings.Contains(text,"DOMAIN-SUFFIX,tracker.example.test"){t.Fatal("suffix rule missing")}
 if !strings.Contains(text,"DOMAIN,safe.tracker.example.test,PASS"){t.Fatal("allow PASS missing")}
 if strings.Contains(text,"DOMAIN,safe.tracker.example.test,DIRECT"){t.Fatal("whitelist must never force direct")}
 if !strings.Contains(text,"DOMAIN-SUFFIX,dns.google")||!strings.Contains(text,"DST-PORT,853,REJECT"){t.Fatal("encrypted DNS guard missing")}
 if !strings.Contains(text,"SUB-RULE,(OR,((NETWORK,TCP),(NETWORK,UDP))),"+filterSubRule){t.Fatal("isolated filter branch missing")}
 if strings.Index(text,"DOMAIN,safe.tracker.example.test,PASS")>strings.Index(text,"RULE-SET,"+filterName+",REJECT"){t.Fatal("allow PASS must precede reject inside branch")}
 C.SetHomeDir(t.TempDir());if _,e=config.Parse(b);e!=nil{t.Fatal(e)}
 if r.YAML!=s{t.Fatal("input changed")}
}
func TestDnsGuardWorksWithoutAdFilter(t *testing.T){
 s:=strings.Replace(sample,"rules: [MATCH,SELECT]","rules: ['MATCH,SELECT']",1);b,e:=prepare(request{YAML:s,DnsGuard:true,DohDomains:[]string{"dns.google"}});if e!=nil{t.Fatal(e)};text:=string(b)
 if !strings.Contains(text,"RULE-SET,"+dnsGuardName+",REJECT")||strings.Contains(text,"RULE-SET,"+filterName+",REJECT"){t.Fatal("DNS guard must be independent of ad filter")}
 C.SetHomeDir(t.TempDir());if _,e=config.Parse(b);e!=nil{t.Fatal(e)}
}
func TestImportedDotIsNotBroken(t *testing.T){
 s:=strings.Replace(sample,"rules: [MATCH,SELECT]","rules: ['MATCH,SELECT']",1);s=strings.Replace(s,"https://1.1.1.1/dns-query","tls://dns.example.test:853",1)
 b,e:=prepare(request{YAML:s,DnsGuard:true,DohDomains:[]string{"dns.example.test","dns.google"}});if e!=nil{t.Fatal(e)};text:=string(b)
 if !strings.Contains(text,"DOMAIN,dns.example.test,PASS"){t.Fatal("configured resolver exemption missing")}
 if strings.Contains(text,"DST-PORT,853,REJECT"){t.Fatal("explicit imported DoT/DoQ resolver must not be broken")}
 C.SetHomeDir(t.TempDir());if _,e=config.Parse(b);e!=nil{t.Fatal(e)}
}
func TestRejectUnsafeOrUnsupported(t *testing.T){for _,s:=range []string{"[bad","proxies: []\nproxies: []","proxies: []\nmode: global","proxies: []\nlisteners: [{type: ebpf}]","proxies: []\ndns: {enable: false}"}{if _,e:=prepare(request{YAML:s});e==nil{t.Fatalf("accepted %q",s)}}}
func TestRejectBadInjectedDomains(t *testing.T){
 s:=strings.Replace(sample,"rules: [MATCH,SELECT]","rules: ['MATCH,SELECT']",1)
 for _,r:=range []request{{YAML:s,Filter:true,Domains:[]string{"bad/domain"}},{YAML:s,Filter:true,SuffixDomains:[]string{"bad,domain"}},{YAML:s,Filter:true,AllowDomains:[]string{"no-dot"}},{YAML:s,DnsGuard:true,DohDomains:[]string{"bad domain"}}}{if _,e:=prepare(r);e==nil{t.Fatal("unsafe injected domain accepted")}}
}
func TestRejectInternalNameCollisions(t *testing.T){
 s:=strings.Replace(sample,"rules: [MATCH,SELECT]","rules: ['MATCH,SELECT']",1)
 cases:=[]request{
  {YAML:s+"\nrule-providers:\n  "+filterName+": {type: inline, behavior: classical, payload: ['DOMAIN,x.test']}\n",Filter:true,Domains:[]string{"ads.example.test"}},
  {YAML:s+"\nrule-providers:\n  "+dnsGuardName+": {type: inline, behavior: classical, payload: ['DOMAIN,x.test']}\n",DnsGuard:true,DohDomains:[]string{"dns.google"}},
  {YAML:s+"\nsub-rules:\n  "+filterSubRule+": ['MATCH,DIRECT']\n",Filter:true,Domains:[]string{"ads.example.test"}},
 }
 for _,r:=range cases{if _,e:=prepare(r);e==nil{t.Fatal("internal name collision accepted")}}
}
func TestNoExternalListeners(t *testing.T){b,e:=prepare(request{YAML:sample+"external-controller: 0.0.0.0:9090\nsecret: private-token\ntproxy-port: 9898\n"});if e!=nil{t.Fatal(e)};if strings.Contains(string(b),"private-token")||strings.Contains(string(b),"0.0.0.0:9090"){t.Fatal("controller retained")}}
