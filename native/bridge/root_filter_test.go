package main

import (
    "os"
    "path/filepath"
    "testing"

    "github.com/metacubex/mihomo/config"
    C "github.com/metacubex/mihomo/constant"
    "github.com/metacubex/mihomo/tunnel"
)

// Exercise the exact YAML produced by the Java Root configuration generator,
// including rule-provider matching in the same Mihomo revision used by the APK.
func TestRootFilterExceptionsAndRouting(t *testing.T) {
    fixture := os.Getenv("HETU_RUNTIME_FIXTURE")
    if fixture == "" { t.Skip("run tools/test_proxy_continuity.py --fixture first") }
    yaml, err := os.ReadFile(fixture)
    if err != nil { t.Fatal(err) }
    home := t.TempDir()
    C.SetHomeDir(home)
    if err = os.MkdirAll(filepath.Join(home,"ruleset"),0700); err != nil { t.Fatal(err) }
    for name, text := range map[string]string{
        "hetu-adblock.txt": "+.weixin.qq.com\n+.tracker.example.test\n+.stun.example.test\n",
        "hetu-adblock-allow.txt": "+.szlong.weixin.qq.com\n+.safe.tracker.example.test\n+.stun.example.test\n",
    } {
        if err = os.WriteFile(filepath.Join(home,"ruleset",name),[]byte(text),0600); err != nil { t.Fatal(err) }
    }
    cfg, err := config.Parse(yaml)
    if err != nil { t.Fatal(err) }
    for _, provider := range cfg.RuleProviders {
        if err = provider.Initial(); err != nil { t.Fatal(err) }
    }
    tunnel.UpdateRules(cfg.Rules,nil,cfg.RuleProviders)
    defer tunnel.UpdateRules(nil,nil,nil)
    cases := []struct{ host string; port uint16; want string }{
        {"szlong.weixin.qq.com",443,"DIRECT"},
        {"ad.weixin.qq.com",443,"REJECT"},
        {"safe.tracker.example.test",443,"SELECT"},
        {"ads.tracker.example.test",443,"REJECT"},
        {"source-ad.example.test",443,"REJECT"},
        {"szlong.weixin.qq.com",853,"REJECT"},
        {"stun.example.test",443,"REJECT"},
    }
    for _, item := range cases {
        got := ""
        metadata := &C.Metadata{Host:item.host,DstPort:item.port,NetWork:C.TCP}
        for _, rule := range cfg.Rules {
            if matched, adapter := rule.Match(metadata,C.RuleMatchHelper{}); matched { got=adapter; break }
        }
        if got != item.want { t.Errorf("%s:%d got %q, want %q",item.host,item.port,got,item.want) }
    }

    // Disabling the final local source is a valid hot update. The controller's
    // PUT /providers/rules/{name} calls Update, not Initial or a core restart.
    block := cfg.RuleProviders["hetu-adblock"]
    if block == nil { t.Fatal("missing local block provider") }
    if err = os.WriteFile(filepath.Join(home,"ruleset","hetu-adblock.txt"),nil,0600); err != nil { t.Fatal(err) }
    if err = block.Update(); err != nil { t.Fatalf("empty provider hot update: %v",err) }
    if block.Count() != 0 { t.Fatalf("empty update retained %d old rules",block.Count()) }
    emptyCases := []struct{ host string; port uint16; want string }{
        {"ad.weixin.qq.com",443,"DIRECT"},
        {"ads.tracker.example.test",443,"SELECT"},
        {"source-ad.example.test",443,"REJECT"},
        {"szlong.weixin.qq.com",853,"REJECT"},
        {"stun.example.test",443,"REJECT"},
    }
    for _, item := range emptyCases {
        got := ""
        metadata := &C.Metadata{Host:item.host,DstPort:item.port,NetWork:C.TCP}
        for _, rule := range cfg.Rules {
            if matched, adapter := rule.Match(metadata,C.RuleMatchHelper{}); matched { got=adapter; break }
        }
        if got != item.want { t.Errorf("after empty update %s:%d got %q, want %q",item.host,item.port,got,item.want) }
    }

    // A later source re-enable must also take effect without replacing the core.
    if err = os.WriteFile(filepath.Join(home,"ruleset","hetu-adblock.txt"),[]byte("+.tracker.example.test\n"),0600); err != nil { t.Fatal(err) }
    if err = block.Update(); err != nil { t.Fatalf("restore provider hot update: %v",err) }
    if !block.Match(&C.Metadata{Host:"ads.tracker.example.test",DstPort:443,NetWork:C.TCP},C.RuleMatchHelper{}) {
        t.Fatal("provider did not reload restored rules")
    }
}
