#!/usr/bin/env python3
from pathlib import Path
r=Path(__file__).resolve().parents[1]
p=r/'tools/build_preview.py';s=p.read_text();assert "VERSION = '0.4.0-test.1'" in s and 'CODE = 401' in s;s=s.replace("VERSION = '0.4.0-test.1'","VERSION = '0.4.0-test.2'").replace('CODE = 401','CODE = 402');p.write_text(s)
b=r/'android-app/app/src/main/java/io/github/xgl34222220/bichen'
p=b/'MainActivity.java';s=p.read_text();old='private boolean ruleTarget()throws IOException{';assert old in s;s=s.replace(old,old+'if(MihomoVpnService.engaged)throw new IOException("请先停止 Mihomo 后修改名单；规则在重新连接时加载");');p.write_text(s)
p=b/'ProxyActivity.java';s=p.read_text();needle='button(c,"恢复上一份配置",';assert needle in s;s=s.replace(needle,'button(c,"重试恢复原模块",()->{if(!MihomoVpnService.engaged)startService(new Intent(this,MihomoVpnService.class).setAction("RESTORE"));});\n  '+needle)
s=s.replace('else if(request==901&&result==RESULT_OK&&data!=null){task(', 'else if(request==901&&result==RESULT_OK&&data!=null&&!locked()){task(')
p.write_text(s)
