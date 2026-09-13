package main

import (
 "context"
 "encoding/json"
 "errors"
 "io"
 "net"
 "net/netip"
 "net/url"
 "os"
 "path/filepath"
 "strings"
 "sync"
 "time"
 "github.com/metacubex/mihomo/adapter/outboundgroup"
 "github.com/metacubex/mihomo/common/utils"
 "github.com/metacubex/mihomo/component/profile/cachefile"
 "github.com/metacubex/mihomo/config"
 C "github.com/metacubex/mihomo/constant"
 "github.com/metacubex/mihomo/hub/executor"
 LC "github.com/metacubex/mihomo/listener/config"
 "github.com/metacubex/mihomo/listener/sing_tun"
 "github.com/metacubex/mihomo/tunnel"
 "github.com/metacubex/mihomo/tunnel/statistic"
 "gopkg.in/yaml.v3"
 "golang.org/x/sys/unix"
)
const coreRevision="ac017cdd246ce8bd547653d927e7bf77d7ee73d5"
const filterName="__bichen_exact_filter"
const dnsGuardName="__bichen_dns_guard"
const filterSubRule="__bichen_filter_branch"
var mu sync.Mutex
var tunCloser io.Closer
var started bool

type request struct {
 Action string `json:"action"`
 Home string `json:"home"`
 YAML string `json:"yaml"`
 Domains []string `json:"domains"`
 SuffixDomains []string `json:"suffixDomains"`
 AllowDomains []string `json:"allowDomains"`
 DohDomains []string `json:"dohDomains"`
 Filter bool `json:"filter"`
 DnsGuard bool `json:"dnsGuard"`
 FD int `json:"fd"`
 Group string `json:"group"`
 Name string `json:"name"`
}
func validDomain(domain string)bool{return len(domain)>0&&len(domain)<=253&&strings.Contains(domain,".")&&!strings.ContainsAny(domain,",\r\n /:")}
func checkedDomains(values []string,kind string)([]string,error){
 out:=make([]string,0,len(values));seen:=make(map[string]struct{},len(values))
 for _,domain:=range values{
  domain=strings.ToLower(strings.TrimSuffix(strings.TrimSpace(domain),"."))
  if !validDomain(domain){return nil,errors.New(kind+"包含无效域名")}
  if _,ok:=seen[domain];ok{continue};seen[domain]=struct{}{};out=append(out,domain)
 }
 return out,nil
}
func endpointHost(value string)string{
 value=strings.TrimSpace(value);if value==""||strings.HasPrefix(value,"rcode://"){return ""}
 if i:=strings.Index(value,"#");i>=0{value=value[:i]}
 if u,err:=url.Parse(value);err==nil&&u.Hostname()!=""{h:=strings.ToLower(strings.TrimSuffix(u.Hostname(),"."));if validDomain(h){return h};return ""}
 host:=value
 if h,_,err:=net.SplitHostPort(host);err==nil{host=h}else if strings.Count(host,":")==1{if i:=strings.LastIndex(host,":");i>0{host=host[:i]}}
 host=strings.ToLower(strings.Trim(strings.TrimSuffix(host,"."),"[]"));if validDomain(host){return host};return ""
}
func endpointUses853(value string)bool{
 value=strings.TrimSpace(value);if value==""||strings.HasPrefix(value,"rcode://"){return false}
 if i:=strings.Index(value,"#");i>=0{value=value[:i]}
 if u,err:=url.Parse(value);err==nil&&u.Host!=""{return u.Port()=="853"||u.Scheme=="tls"||u.Scheme=="quic"}
 _,port,err:=net.SplitHostPort(value);return err==nil&&port=="853"
}
func eachString(value any,fn func(string)){
 switch v:=value.(type){case string:fn(v);case []string:for _,s:=range v{fn(s)};case []any:for _,x:=range v{eachString(x,fn)};case map[string]any:for _,x:=range v{eachString(x,fn)}}
}
func dnsGuardExemptions(dns map[string]any)([]string,bool){
 seen:=map[string]struct{}{};uses853:=false
 add:=func(s string){if endpointUses853(s){uses853=true};if h:=endpointHost(s);h!=""{seen[h]=struct{}{}}}
 for _,key:=range []string{"nameserver","fallback","default-nameserver","proxy-server-nameserver","direct-nameserver","nameserver-policy"}{if v,ok:=dns[key];ok{eachString(v,add)}}
 out:=make([]string,0,len(seen));for h:=range seen{out=append(out,h)};return out,uses853
}
// Decode a private runtime copy. The original YAML is never overwritten.
func prepare(r request)([]byte,error){
 if len(r.YAML)==0||len(r.YAML)>4<<20{return nil,errors.New("配置为空或超过 4 MiB")}
 var m map[string]any
 if yaml.Unmarshal([]byte(r.YAML),&m)!=nil||m==nil{return nil,errors.New("YAML 无效或包含重复键；原配置未修改")}
 if v,ok:=m["listeners"].([]any);ok&&len(v)>0{return nil,errors.New("此配置包含自定义入站；本轮 Android VPN 不接管 TPROXY/eBPF listeners，原配置保留")}
 if v,ok:=m["mode"].(string);ok&&v!=""&&v!="rule"{return nil,errors.New("代理与广告过滤共用规则，请使用 mode: rule；未自动改写原配置")}
 if _,a:=m["proxies"];!a{if _,b:=m["proxy-providers"];!b{return nil,errors.New("需要包含 proxies 或 proxy-providers 的完整配置")}}
 for _,k:=range []string{"external-controller","external-controller-tls","external-controller-unix","external-controller-pipe","external-ui","external-ui-url","external-doh-server","secret","interface-name","routing-mark"}{delete(m,k)}
 for _,k:=range []string{"port","socks-port","mixed-port","redir-port","tproxy-port"}{m[k]=0}
 for _,k:=range []string{"listeners","tunnels","iptables","script","ntp","tuic-server","ss-config","vmess-config"}{delete(m,k)}
 m["allow-lan"]=false;m["bind-address"]="127.0.0.1";m["mode"]="rule";m["log-level"]="silent";m["find-process-mode"]="off"
 m["tun"]=map[string]any{"enable":false};m["geo-auto-update"]=false
 dns,_:=m["dns"].(map[string]any)
 if dns==nil{dns=map[string]any{"enable":true,"enhanced-mode":"fake-ip","fake-ip-range":"198.18.0.1/16","nameserver":[]string{"https://1.1.1.1/dns-query"},"proxy-server-nameserver":[]string{"https://1.1.1.1/dns-query"}}}
 if on,exists:=dns["enable"];exists&&on==false{return nil,errors.New("当前配置关闭 DNS；本轮 VPN 需要启用 DNS，不会静默更改你的选择")}
 dns["enable"]=true;dns["listen"]="";m["dns"]=dns
 if r.Filter||r.DnsGuard{
  if len(r.Domains)+len(r.SuffixDomains)>500000||len(r.AllowDomains)>50000||len(r.DohDomains)>50000{return nil,errors.New("去广告规则、白名单或 DNS 防绕过规则过多")}
  exact,err:=checkedDomains(r.Domains,"去广告规则");if err!=nil{return nil,err}
  suffix,err:=checkedDomains(r.SuffixDomains,"子域拦截规则");if err!=nil{return nil,err}
  allow,err:=checkedDomains(r.AllowDomains,"白名单");if err!=nil{return nil,err}
  doh,err:=checkedDomains(r.DohDomains,"加密 DNS 防绕过规则");if err!=nil{return nil,err}
  original,ok:=m["rules"].([]any);if !ok{return nil,errors.New("配置缺少 rules 分流列表")}
  providers,_:=m["rule-providers"].(map[string]any);if providers==nil{providers=map[string]any{}}
  branch:=make([]any,0,len(allow)+len(doh)+4)
  // PASS inside SUB-RULE exits Bichen's branch and resumes the user's main rules.
  for _,domain:=range allow{branch=append(branch,"DOMAIN,"+domain+",PASS")}
  if r.DnsGuard{
   exempt,uses853:=dnsGuardExemptions(dns);for _,domain:=range exempt{branch=append(branch,"DOMAIN,"+domain+",PASS")}
   if len(doh)>0{
    if _,exists:=providers[dnsGuardName];exists{return nil,errors.New("配置占用了辟尘 DNS 防绕过内部规则名称")}
    payload:=make([]string,0,len(doh));for _,domain:=range doh{payload=append(payload,"DOMAIN-SUFFIX,"+domain)}
    providers[dnsGuardName]=map[string]any{"type":"inline","behavior":"classical","payload":payload};branch=append(branch,"RULE-SET,"+dnsGuardName+",REJECT")
   }
   // Respect an explicitly imported DoT/DoQ resolver instead of breaking it.
   // Otherwise block both TCP and UDP 853 after domain-based DoH protection.
   if !uses853{branch=append(branch,"DST-PORT,853,REJECT")}
  }
  if r.Filter&&len(exact)+len(suffix)>0{
   if _,exists:=providers[filterName];exists{return nil,errors.New("配置占用了辟尘内部规则名称")}
   payload:=make([]string,0,len(exact)+len(suffix));for _,domain:=range exact{payload=append(payload,"DOMAIN,"+domain)};for _,domain:=range suffix{payload=append(payload,"DOMAIN-SUFFIX,"+domain)}
   providers[filterName]=map[string]any{"type":"inline","behavior":"classical","payload":payload};branch=append(branch,"RULE-SET,"+filterName+",REJECT")
  }
  if len(branch)>0{
   m["rule-providers"]=providers
   subRules,_:=m["sub-rules"].(map[string]any);if subRules==nil{subRules=map[string]any{}}
   if _,exists:=subRules[filterSubRule];exists{return nil,errors.New("配置占用了辟尘内部子规则名称")}
   subRules[filterSubRule]=branch;m["sub-rules"]=subRules
   m["rules"]=append([]any{"SUB-RULE,(OR,((NETWORK,TCP),(NETWORK,UDP))),"+filterSubRule},original...)
  }
 }
 return yaml.Marshal(m)
}
func execute(r request)(any,error){
 mu.Lock();defer mu.Unlock()
 switch r.Action{
 case "version":return map[string]any{"revision":coreRevision,"engine":"Mihomo","running":started},nil
 case "inspect":_,err:=prepare(r);return map[string]any{"compatible":err==nil,"notice":"源文件原样保存；运行时适配 Android TUN、关闭额外入站和控制端口，保留原 DNS 与分流规则。"},err
 case "start":
  if started{return nil,errors.New("内核已经运行")}
  if !filepath.IsAbs(r.Home)||r.FD<0{return nil,errors.New("无效的私有目录或隧道")}
  if err:=os.MkdirAll(r.Home,0700);err!=nil{return nil,errors.New("无法创建内核私有目录")};C.SetHomeDir(r.Home)
  data,err:=prepare(r);if err!=nil{return nil,err}
  cfg,err:=config.Parse(data);if err!=nil{return nil,errors.New("Mihomo 配置校验失败；请检查节点类型、规则引用及资源可用性（不输出凭据）")}
  executor.ApplyConfig(cfg,true)
  if r.FD>0{
   fd,err:=unix.Dup(r.FD);if err!=nil{executor.Shutdown();return nil,err}
   options:=LC.Tun{Enable:true,Device:"bichen",Stack:C.TunGvisor,MTU:1500,FileDescriptor:fd,DNSHijack:[]string{"any:53","tcp://any:53"},Inet4Address:[]netip.Prefix{netip.MustParsePrefix("172.29.0.1/30")},Inet6Address:[]netip.Prefix{netip.MustParsePrefix("fdfe:dcba:9876::1/126")}}
   t,err:=sing_tun.New(options,tunnel.Tunnel);if err!=nil{executor.Shutdown();return nil,errors.New("Mihomo 创建 Android TUN 失败；未报告启动成功")};tunCloser=t
  }
  started=true;return map[string]any{"running":true,"revision":coreRevision,"filterDomains":len(r.Domains)+len(r.SuffixDomains),"dnsGuard":r.DnsGuard,"dnsGuardDomains":len(r.DohDomains)},nil
 case "stop":
  started=false;if tunCloser!=nil{_ = tunCloser.Close();tunCloser=nil}
  statistic.DefaultManager.Range(func(t statistic.Tracker)bool{_ = t.Close();return true});executor.Shutdown();return map[string]any{"running":false},nil
 case "proxies":if !started{return nil,errors.New("内核未运行")};return tunnel.Proxies(),nil
 case "connections":if !started{return nil,errors.New("内核未运行")};return statistic.DefaultManager.Snapshot(),nil
 case "select":
  if !started{return nil,errors.New("内核未运行")};p:=tunnel.Proxies()[r.Group];if p==nil{return nil,errors.New("策略组不存在")}
  s,ok:=p.Adapter().(outboundgroup.SelectAble);if !ok{return nil,errors.New("此策略组不支持手动选择")};if s.Set(r.Name)!=nil{return nil,errors.New("节点不属于该策略组")};cachefile.Cache().SetSelected(r.Group,r.Name);return true,nil
 case "delay":
  p:=tunnel.Proxies()[r.Name];if !started||p==nil{return nil,errors.New("节点不可用")};ctx,cancel:=context.WithTimeout(context.Background(),5*time.Second);defer cancel();codes,_:=utils.NewUnsignedRanges[uint16]("200-299")
  delay,err:=p.URLTest(ctx,"https://www.gstatic.com/generate_204",codes);if err!=nil{return nil,errors.New("延迟测试失败或超时")};return map[string]any{"delay":delay},nil
 default:return nil,errors.New("未知内核操作")
 }
}
func invoke(data []byte)(out []byte){
 defer func(){if recover()!=nil{out=[]byte(`{"ok":false,"error":"内核调用异常，未报告成功"}`)}}()
 var r request;if len(data)>40<<20||json.Unmarshal(data,&r)!=nil{return []byte(`{"ok":false,"error":"无效内核请求"}`)}
 value,err:=execute(r);response:=map[string]any{"ok":err==nil,"data":value};if err!=nil{response["error"]=err.Error()};out,err=json.Marshal(response);if err!=nil{return []byte(`{"ok":false,"error":"内核结果序列化失败"}`)};return out
}
func main(){}
