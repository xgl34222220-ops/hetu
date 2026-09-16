#!/usr/bin/env python3
from pathlib import Path
p=Path(__file__).resolve().parents[1]/"tests/MihomoStartupConfigTest.java"
s=p.read_text(encoding="utf-8")
old='MihomoStartupConfig.Result r=MihomoStartupConfig.generate(source,p(ProxyRuntimeProfile.Mode.REDIRECT,true));check(r.redirectPort==9797&&r.tproxyPort==0,"Redirect ports generated");check(r.yaml.contains("redir-port: 9797")&&r.yaml.contains("tproxy-port: 0"),"Redirect startup values written");'
new='MihomoStartupConfig.Result r=MihomoStartupConfig.generate(source,p(ProxyRuntimeProfile.Mode.REDIRECT,true));check(r.redirectPort==9797&&r.tproxyPort==9898,"Redirect plus TPROXY DNS ports generated");check(r.yaml.contains("redir-port: 9797")&&r.yaml.contains("tproxy-port: 9898"),"Redirect startup keeps TPROXY listener for TPROXY DNS");'
if old not in s:
    raise SystemExit("Redirect regression anchor missing")
p.write_text(s.replace(old,new,1),encoding="utf-8")
print("Redirect + TPROXY DNS host regression expectation fixed")
