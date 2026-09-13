package main

import(
 "fmt"
 "strings"
 "testing"
 "gopkg.in/yaml.v3"
 "github.com/metacubex/mihomo/config"
 C "github.com/metacubex/mihomo/constant"
)

const sample="mode: rule\nproxies: []\nproxy-groups:\n - name: SELECT\n   type: select\n   proxies: [DIRECT]\nrules: [MATCH,SELECT]\ndns:\n enable: true\n nameserver: [https://1.1.1.1/dns-query]\n nameserver-policy:\n  stun.example.test: [rcode://name_error]\n"

func parsed(t *testing.T,b []byte)map[string]any{t.Helper();var m map[string]any;if yaml.Unmarshal(b,&m)!=nil{t.Fatal("generated yaml invalid")};return m}
func object(t *testing.T,v any,name string)map[string]any{t.Helper();m,ok:=v.(map[string]any);if !ok{t.Fatalf("%s is not an object: %T",name,v)};return m}
func fold(m map[string]any,key string)(any,bool){for k,v:=range m{if strings.EqualFold(k,key){return v,true}};return nil,false}
func hasPort(v any,want string)bool{switch x:=v.(type){case []any:for _,p:=range x{if fmt.Sprint(p)==want{return true}};case []string:for _,p:=range x{if p==want{return true}}};return false}
func boolValue(t *testing.T,m map[string]any,key string)bool{t.Helper();v,ok:=m[key].(bool);if !ok{t.Fatalf("%s is not bool: %T",key,m[key])};return v}

func TestRuntimeCopy(t *testing.T){
 s:=strings.Replace(sample,"rules: [MATCH,SELECT]","rules: ['MATCH,SELECT']",1);r:=request{YAML:s,Filter:true,DnsGuard:true,Domains:[]string{"ads.example.test"},SuffixDomains:[]string{"tracker.example.test"},AllowDomains:[]string{"safe.tracker.example.test"},DohDomains:[]string{"dns.google","cloudflare-dns.com"}}
 b,e:=prepare(r);if e!=nil{t.Fatal(e)};text:=string(b);m:=parsed(t,b)
 if !strings.Contains(text,"stun.example.test")||!strings.Contains(text,"rcode://name_error"){t.Fatal("DNS policy lost")}
 if !strings.Contains(text,"DOMAIN-SUFFIX,tracker.example.test"){t.Fatal("suffix rule missing")}
 if !strings.Contains(text,"DOMAIN,safe.tracker.example.test,PASS"){t.Fatal("allow PASS missing")}
 if strings.Contains(text,"DOMAIN,safe.tracker.example.test,DIRECT"){t.Fatal("whitelist must never force direct")}
 if !strings.Contains(text,"DOMAIN-SUFFIX,dns.google")||!strings.Contains(text,"DST-PORT,853,REJECT"){t.Fatal("encrypted DNS guard missing")}
 if !strings.Contains(text,"SUB-RULE,(OR,((NETWORK,TCP),(NETWORK,UDP))),"+filterSubRule){t.Fatal("isolated filter branch missing")}
 if strings.Index(text,"DOMAIN,safe.tracker.example.test,PASS")>strings.Index(text,"RULE-SET,"+filterName+",REJECT"){t.Fatal("allow PASS must precede reject inside branch")}
 sniffer:=object(t,m["sniffer"],"sniffer");if !boolValue(t,sniffer,"enable")||!boolValue(t,sniffer,"parse-pure-ip")||!boolValue(t,sniffer,"force-dns-mapping"){t.Fatal("DNS guard sniffer not fully enabled")}
 sniff:=object(t,sniffer["sniff"],"sniffer.sniff")
 for _,proto:=range []string{"TLS","QUIC"}{raw,ok:=fold(sniff,proto);if !ok{t.Fatalf("%s sniffer missing",proto)};cfg:=object(t,raw,"sniffer."+proto);if !hasPort(cfg["ports"],"443")||!hasPort(cfg["ports"],"853"){t.Fatalf("%s guard ports missing: %v",proto,cfg["ports"])};if override,ok:=cfg["override-destination"].(bool);!ok||override{t.Fatalf("%s Bichen sniffer must not rewrite destination",proto)}}
 C.SetHomeDir(t.TempDir());if _,e=config.Parse(b);e!=nil{t.Fatal(e)}
 if r.YAML!=s{t.Fatal("input changed")}
}

func TestDnsGuardWorksWithoutAdFilter(t *testing.T){
 s:=strings.Replace(sample,"rules: [MATCH,SELECT]","rules: ['MATCH,SELECT']",1);b,e:=prepare(request{YAML:s,DnsGuard:true,AllowDomains:[]string{"safe.dns.google"},DohDomains:[]string{"dns.google"}});if e!=nil{t.Fatal(e)};text:=string(b)
 if !strings.Contains(text,"RULE-SET,"+dnsGuardName+",REJECT")||strings.Contains(text,"RULE-SET,"+filterName+",REJECT"){t.Fatal("DNS guard must be independent of ad filter")}
 if !strings.Contains(text,"DOMAIN,safe.dns.google,PASS"){t.Fatal("whitelist must remain active when ad filter is disabled")}
 if strings.Index(text,"DOMAIN,safe.dns.google,PASS")>strings.Index(text,"RULE-SET,"+dnsGuardName+",REJECT"){t.Fatal("DNS guard whitelist must precede resolver rejection")}
 C.SetHomeDir(t.TempDir());if _,e=config.Parse(b);e!=nil{t.Fatal(e)}
}

func TestDnsGuardMergesExistingSniffer(t *testing.T){
 s:=strings.Replace(sample,"rules: [MATCH,SELECT]","rules: ['MATCH,SELECT']",1)+"sniffer:\n  enable: false\n  override-destination: true\n  sniff:\n    tls:\n      ports: [8443]\n      override-destination: true\n"
 b,e:=prepare(request{YAML:s,DnsGuard:true,DohDomains:[]string{"dns.google"}});if e!=nil{t.Fatal(e)};m:=parsed(t,b);sniffer:=object(t,m["sniffer"],"sniffer");if !boolValue(t,sniffer,"enable")||!boolValue(t,sniffer,"parse-pure-ip"){t.Fatal("existing sniffer was not activated for guard")}
 sniff:=object(t,sniffer["sniff"],"sniffer.sniff");tlsRaw,_:=fold(sniff,"TLS");tls:=object(t,tlsRaw,"sniffer.TLS");for _,p:=range []string{"8443","443","853"}{if !hasPort(tls["ports"],p){t.Fatalf("TLS port %s not preserved/added: %v",p,tls["ports"])}};if v,ok:=tls["override-destination"].(bool);!ok||!v{t.Fatal("existing TLS override choice was changed")}
 quicRaw,ok:=fold(sniff,"QUIC");if !ok{t.Fatal("QUIC sniffer not added")};quic:=object(t,quicRaw,"sniffer.QUIC");for _,p:=range []string{"443","853"}{if !hasPort(quic["ports"],p){t.Fatalf("QUIC port %s missing",p)}};if v,ok:=quic["override-destination"].(bool);!ok||v{t.Fatal("new QUIC sniffer must not rewrite destination")}
 C.SetHomeDir(t.TempDir());if _,e=config.Parse(b);e!=nil{t.Fatal(e)}
}

func TestGuardOffDoesNotInjectSniffer(t *testing.T){
 s:=strings.Replace(sample,"rules: [MATCH,SELECT]","rules: ['MATCH,SELECT']",1);b,e:=prepare(request{YAML:s,Filter:true,Domains:[]string{"ads.example.test"}});if e!=nil{t.Fatal(e)};m:=parsed(t,b);if _,ok:=m["sniffer"];ok{t.Fatal("guard-off runtime unexpectedly injected sniffer")};C.SetHomeDir(t.TempDir());if _,e=config.Parse(b);e!=nil{t.Fatal(e)}
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
