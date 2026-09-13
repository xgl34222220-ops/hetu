from pathlib import Path
import hashlib
r=Path(__file__).resolve().parents[1]
p=r/'android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyActivity.java'
assert hashlib.sha256(p.read_bytes()).hexdigest()=='9a5d9e650f490fa954c328abe81031769514d9f34544c8668fe5d89de35354ef'
s=p.read_text()
a='s.setClipToPadding(false);s.setFillViewport(true);';b='s.setClipToPadding(false);s.setFillViewport(true);s.setVerticalScrollBarEnabled(false);'
assert s.count(a)==1;s=s.replace(a,b)
a='toolbar.addView(pause,new LinearLayout.LayoutParams(u.dp(102),-2));';b='pause.setSingleLine(true);toolbar.addView(pause,new LinearLayout.LayoutParams(-2,-2));'
assert s.count(a)==1;s=s.replace(a,b);p.write_text(s)
p=r/'tests/ui/Workspace.java'
assert hashlib.sha256(p.read_bytes()).hexdigest()=='652f4ec11eef70d0f45393f1866b1f67fd964f759fe14588055e9e2b093e400f'
s=p.read_text();a='    private void waitIdle()';b='    private void checkPause(View v){if(v instanceof TextView && "暂停刷新".contentEquals(((TextView)v).getText()))check(((TextView)v).getLineCount()==1,"pause action fits enlarged font on one line");if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)checkPause(g.getChildAt(i));}}\n    private void waitIdle()'
assert s.count(a)==1;s=s.replace(a,b)
a='            screenshot("page-"+n);';b='            if(n==2)main(()->checkPause(a.getWindow().getDecorView()));screenshot("page-"+n);'
assert s.count(a)==1;s=s.replace(a,b)
a='"fixture: selected member and automatic type visible");';b=a+'screenshot("fixture-nodes");'
assert s.count(a)==1;s=s.replace(a,b)
a='"fixture: connection metadata visible");';b=a+'screenshot("fixture-connections");'
assert s.count(a)==1;s=s.replace(a,b);p.write_text(s)
p=r/'tools/test_workspace.py';s=p.read_text();assert s.count("glob('workspace-*.png')))==8")==1;p.write_text(s.replace("glob('workspace-*.png')))==8","glob('workspace-*.png')))==12"))
