from pathlib import Path
import re

def patch(path, fn):
    p=Path(path); s=p.read_text(encoding='utf-8'); n=fn(s); p.write_text(n,encoding='utf-8')

def must(s, old, new, name):
    if old not in s: raise SystemExit('missing '+name)
    return s.replace(old,new,1)

# Compose animation extensions for shimmer and checkbox spring.
def apps(s):
    if 'import androidx.compose.animation.core.*' not in s:
        s=must(s,'import androidx.activity.enableEdgeToEdge\n','import androidx.activity.enableEdgeToEdge\nimport androidx.compose.animation.core.*\n','animation import')
    return s
patch('android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyAppSelectionActivity.kt', apps)

# Avoid fragile Java string regex escaping while detecting the eBPF default interface.
def mgr(s):
    pattern=r'''RootBridge\.Result result=RootBridge\.rootShell\(context,\n\s*"ip route get 1\.1\.1\.1.*?",5000L\);'''
    repl='''RootBridge.Result result=RootBridge.rootShell(context,\n                "set -- $(ip route get 1.1.1.1 2>/dev/null); while [ \\\"$#\\\" -gt 1 ]; do if [ \\\"$1\\\" = dev ]; then printf '%s' \\\"$2\\\"; break; fi; shift; done",5000L);'''
    out,n=re.subn(pattern,repl,s,count=1,flags=re.S)
    if n!=1: raise SystemExit('default interface command not found')
    return out
patch('android-app/app/src/main/java/io/github/xgl34222220/bichen/RootProxyManager.java', mgr)

# Local ruleset success should visibly morph to a green check for the 1.2 s success window.
def ref(s):
    old='''Icon(Icons.Rounded.Download, "远端更新", tint = if (success) Color(0xFF10B981) else scheme.primary, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = if (refreshing) spin else 0f })'''
    new='''Icon(if (success) Icons.Rounded.CheckCircle else Icons.Rounded.Download, if (success) "更新完成" else "远端更新", tint = if (success) Color(0xFF10B981) else scheme.primary, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = if (refreshing) spin else 0f })'''
    return must(s,old,new,'ruleset success icon')
patch('android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt', ref)

print('test56 compile fixes applied')
