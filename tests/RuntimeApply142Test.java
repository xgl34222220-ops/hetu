package io.github.xgl34222220.hetu;

import android.content.SharedPreferences;
import java.util.*;

public final class RuntimeApply142Test {
    private static int checks;
    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); checks++; }
    public static void main(String[] args) {
        MemoryPrefs p = new MemoryPrefs();
        check(!ProxyRuntimeSettings.pending(true,p), "No applied record is not an automatic repair request");
        p.edit().putBoolean(ProxyRuntimeSettings.DIRTY_KEY,true).apply();
        check(ProxyRuntimeSettings.pending(true,p), "Explicit legacy unsaved changes remain pending");
        check(!ProxyRuntimeSettings.pending(false,p), "Stopped service has no restart request");
        String initial = ProxyRuntimeSettings.signature(p);
        p.edit().putString(ProxyRuntimeSettings.APPLIED_SETTINGS_KEY,initial).apply();
        for(int i=0;i<10;i++) {
            p.edit().putBoolean(ProxyRuntimeSettings.DIRTY_KEY,true).apply();
            check(!ProxyRuntimeSettings.pending(true,p), "Matching snapshot defeats stale dirty flag " + i);
        }
        ProxyRuntimeSettings.markDirty(p,"proxyBaseMode");
        check(!p.getBoolean(ProxyRuntimeSettings.DIRTY_KEY,true), "Resaving unchanged mode is not dirty");
        p.edit().putString("proxyBaseIpv6","strict").apply();
        check(ProxyRuntimeSettings.pending(true,p), "Real config difference is detected even without dirty flag");
        p.edit().putString("proxyBaseIpv6","enable").apply();
        check(!ProxyRuntimeSettings.pending(true,p), "Undoing the edit restores applied state");
        p.edit().putString("accentHex","#14B8A6").putString("defaultPanelTab","Subscriptions").apply();
        ProxyRuntimeSettings.markDirty(p,"defaultPanelTab");
        check(!ProxyRuntimeSettings.pending(true,p), "Theme and navigation never demand restart");
        p.edit().putString("proxyBaseIpv6","strict").apply();
        ProxyRuntimeSettings.markDirty(p,"proxyBaseIpv6");
        String prepared = ProxyRuntimeSettings.signature(p);
        p.edit().putString("proxyBaseIpv6","disable").apply(); // edit while backend is applying 'strict'
        p.edit().putString(ProxyRuntimeSettings.APPLIED_SETTINGS_KEY,prepared).apply(); // successful prepared snapshot
        check(ProxyRuntimeSettings.acknowledgeApplied(p,true), "Completion is acknowledged");
        check(prepared.equals(p.getString(ProxyRuntimeSettings.APPLIED_SETTINGS_KEY,"")), "Completion never substitutes a later desired snapshot");
        check(ProxyRuntimeSettings.pending(true,p), "Concurrent edit is still waiting to apply");
        check(p.getBoolean(ProxyRuntimeSettings.DIRTY_KEY,false), "Concurrent edit retains dirty bookkeeping");
        String nextPrepared=ProxyRuntimeSettings.signature(p);
        p.edit().putString(ProxyRuntimeSettings.APPLIED_SETTINGS_KEY,nextPrepared).putBoolean("proxyRootRuntimeRefreshPending",true).apply();
        check(ProxyRuntimeSettings.acknowledgeApplied(p,true), "Next real replacement saves successfully");
        check(!ProxyRuntimeSettings.pending(true,p), "Successfully applied settings stay current");
        check(!p.getBoolean("proxyRootRuntimeRefreshPending",false), "Legacy upgrade flag removed on success");
        check(p.getInt(ProxyRuntimeSettings.APPLIED_RUNTIME_REVISION_KEY,0)==ProxyRuntimeSettings.RUNTIME_REVISION, "Runtime revision recorded");
        p.edit().putBoolean("proxyKillSwitch",true).apply();
        ProxyRuntimeSettings.markDirty(p,"proxyKillSwitch");
        ProxyRuntimeSettings.recordFailure(p,new Exception("核心配置校验失败：DNS 端口被占用"));
        check(ProxyRuntimeSettings.applyDescription().contains("DNS 端口被占用"), "Settings page gets real failure reason");
        check(ProxyRuntimeSettings.pending(true,p), "Failure does not pretend settings were applied");
        check(!ProxyRuntimeSettings.acknowledgeApplied(p,false), "Cancelled/incomplete replacement not acknowledged");
        check(nextPrepared.equals(p.getString(ProxyRuntimeSettings.APPLIED_SETTINGS_KEY,"")), "Failure preserves previous applied snapshot");
        check(ProxyRuntimeSettings.applyDescription().startsWith("应用失败："), "Failure survives incomplete completion");
        MemoryPrefs reopened = new MemoryPrefs(); reopened.values.putAll(p.values);
        ProxyRuntimeSettings.pending(true,reopened);
        check(ProxyRuntimeSettings.applyDescription().contains("DNS 端口被占用"), "Reopened settings restores error");
        ProxyRuntimeSettings.beginApply(reopened);
        check(!ProxyRuntimeSettings.applyDescription().contains("应用失败"), "New attempt clears old error");
        check(ProxyRuntimeSettings.pending(true,reopened), "New attempt does not clear actual difference");
        reopened.edit().putString("proxyAdblockLastError","规则文件无法读取").putBoolean("proxyAdblockChain",true).apply();
        check(ProxyRuntimeSettings.applyDescription().contains("广告串联尚未生效"), "Fallback explains why a requested feature is not applied");
        reopened.edit().putString(ProxyRuntimeSettings.APPLIED_SETTINGS_KEY,ProxyRuntimeSettings.signature(reopened)).apply();
        check(ProxyRuntimeSettings.acknowledgeApplied(reopened,true), "Successful transaction consumes the captured requested settings");
        check(!ProxyRuntimeSettings.pending(true,reopened), "Fallback warning must not turn into a generic restart loop");
        check(reopened.getString("proxyAdblockLastError","").equals("规则文件无法读取"), "Fallback health warning remains available after acknowledgment");
        ProxyRuntimeSettings.recordFailure(reopened,new Exception());
        check(ProxyRuntimeSettings.applyDescription().contains("Exception"), "Empty exception message has usable fallback");
        MemoryPrefs empty=new MemoryPrefs();
        check(!ProxyRuntimeSettings.acknowledgeApplied(empty,true), "No backend snapshot cannot be claimed applied");
        empty.edit().putString(ProxyRuntimeSettings.APPLIED_SETTINGS_KEY,ProxyRuntimeSettings.signature(empty)).apply();
        empty.failCommit=true;
        check(!ProxyRuntimeSettings.acknowledgeApplied(empty,true), "Persistence failure is reported");
        check(!ProxyRuntimeSettings.runtimeUpgradePending(true,0,true), "Missing version never invents an update request");
        empty.failCommit=false;
        empty.edit().putStringSet("proxyAppPackages",new LinkedHashSet<>(Arrays.asList("a","b"))).apply();
        String sorted=ProxyRuntimeSettings.signature(empty);
        empty.edit().putStringSet("proxyAppPackages",new LinkedHashSet<>(Arrays.asList("b","a"))).apply();
        check(sorted.equals(ProxyRuntimeSettings.signature(empty)), "Set iteration order cannot create pending state");
        System.out.println("RuntimeApply142Test passed: "+checks);
    }

    static final class MemoryPrefs implements SharedPreferences {
        final Map<String,Object> values=new HashMap<>(); boolean failCommit;
        public Map<String,?> getAll(){return new HashMap<>(values);}
        public String getString(String k,String d){Object v=values.get(k);return v instanceof String?(String)v:d;}
        @SuppressWarnings("unchecked") public Set<String> getStringSet(String k,Set<String>d){Object v=values.get(k);return v instanceof Set?new HashSet<>((Set<String>)v):d;}
        public int getInt(String k,int d){Object v=values.get(k);return v instanceof Integer?(Integer)v:d;}
        public long getLong(String k,long d){Object v=values.get(k);return v instanceof Long?(Long)v:d;}
        public float getFloat(String k,float d){Object v=values.get(k);return v instanceof Float?(Float)v:d;}
        public boolean getBoolean(String k,boolean d){Object v=values.get(k);return v instanceof Boolean?(Boolean)v:d;}
        public boolean contains(String k){return values.containsKey(k);}
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){}
        public Editor edit(){return new Editor(){
            final Map<String,Object> changed=new HashMap<>();final Set<String> removed=new HashSet<>();boolean clear;
            public Editor putString(String k,String v){changed.put(k,v);return this;}
            public Editor putStringSet(String k,Set<String>v){changed.put(k,new HashSet<>(v));return this;}
            public Editor putInt(String k,int v){changed.put(k,v);return this;}
            public Editor putLong(String k,long v){changed.put(k,v);return this;}
            public Editor putFloat(String k,float v){changed.put(k,v);return this;}
            public Editor putBoolean(String k,boolean v){changed.put(k,v);return this;}
            public Editor remove(String k){removed.add(k);return this;}
            public Editor clear(){clear=true;return this;}
            public boolean commit(){if(failCommit)return false;apply();return true;}
            public void apply(){if(clear)values.clear();for(String k:removed)values.remove(k);values.putAll(changed);}
        };}
    }
}
