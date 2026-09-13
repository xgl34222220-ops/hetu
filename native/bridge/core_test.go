package main
import("strings";"testing";"gopkg.in/yaml.v3";"github.com/metacubex/mihomo/config";C "github.com/metacubex/mihomo/constant")
const sample="mode: rule\nproxies: []\nproxy-groups:\n - name: SELECT\n   type: select\n   proxies: [DIRECT]\nrules: [MATCH,SELECT]\ndns:\n enable: true\n nameserver: [https://1.1.1.1/dns-query]\n nameserver-policy:\n  stun.example.test: [rcode://name_error]\n"
func TestRuntimeCopy(t *testing.T){
 s:=strings.Replace(sample,"rules: [MATCH,SELECT]","rules: ['MATCH,SELECT']",1);r:=request{YAML:s,Filter:true,Domains:[]string{"ads.example.test"}}
 b,e:=prepare(r);if e!=nil{t.Fatal(e)};var m map[string]any;yaml.Unmarshal(b,&m)
 if !strings.Contains(string(b),"stun.example.test")||!strings.Contains(string(b),"rcode://name_error"){t.Fatal("DNS policy lost")}
 if strings.Contains(string(b),"DOMAIN,ads.example.test,DIRECT"){t.Fatal("whitelist must never force direct")}
 C.SetHomeDir(t.TempDir());if _,e=config.Parse(b);e!=nil{t.Fatal(e)}
 if r.YAML!=s{t.Fatal("input changed")}
}
func TestRejectUnsafeOrUnsupported(t *testing.T){for _,s:=range []string{"[bad","proxies: []\nproxies: []","proxies: []\nmode: global","proxies: []\nlisteners: [{type: ebpf}]","proxies: []\ndns: {enable: false}"}{if _,e:=prepare(request{YAML:s});e==nil{t.Fatalf("accepted %q",s)}}}
func TestNoExternalListeners(t *testing.T){b,e:=prepare(request{YAML:sample+"external-controller: 0.0.0.0:9090\nsecret: private-token\ntproxy-port: 9898\n"});if e!=nil{t.Fatal(e)};if strings.Contains(string(b),"private-token")||strings.Contains(string(b),"0.0.0.0:9090"){t.Fatal("controller retained")}}
