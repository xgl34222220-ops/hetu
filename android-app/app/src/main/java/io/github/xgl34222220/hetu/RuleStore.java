package io.github.xgl34222220.hetu;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;

/** DNS suffix filter rules. Readers only see a complete, immutable snapshot. */
public final class RuleStore {
    private static final Object LOCK = new Object();
    private static final RuleUpdateGate UPDATE_GATE = new RuleUpdateGate();
    private static final int MAX_SOURCE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_COMBINED_BYTES = 128 * 1024 * 1024;
    private static final int MAX_DOMAINS = 1500000;
    private static final long DOWNLOAD_BATCH_MILLIS = 300000L;
    private static final ScheduledExecutorService DOWNLOAD_WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t=new Thread(r,"hetu-rule-download-guard");t.setDaemon(true);return t;
    });
    private static volatile Snapshot live;
    private static String liveRoot;
    private static JSONObject pendingImportedPreferences;
    private final Context context;
    private final SharedPreferences prefs;
    private final File root;
    private final LinkedHashMap<String,Source> catalog = new LinkedHashMap<>();

    private static final class Source {
        final String id, name;
        final List<String> urls;
        final boolean defaultOn;
        Source(String i, String n, List<String> u, boolean d) {
            id=i; name=n; urls=Collections.unmodifiableList(new ArrayList<>(u)); defaultOn=d;
        }
        String primaryUrl(){ return urls.isEmpty()?"":urls.get(0); }
    }
    private static final class Snapshot {
        final String generation, revision;
        final boolean fromModule;
        final long updatedAt;
        final Set<String> effective, allow, block, filterExceptions;
        final Map<String,Boolean> enabled;
        final Map<String,Set<String>> sourceRules;
        Snapshot(String g, String r, boolean m, Set<String> e, Set<String> a, Set<String> b, Set<String> exceptions,
                 Map<String,Boolean> on, Map<String,Set<String>> sources, long updated) {
            generation=g; revision=r; fromModule=m;
            updatedAt=updated;
            effective=immutable(e); allow=immutable(a); block=immutable(b); filterExceptions=immutable(exceptions);
            enabled=Collections.unmodifiableMap(new LinkedHashMap<>(on));
            Map<String,Set<String>> copy=new LinkedHashMap<>();
            for (Map.Entry<String,Set<String>> item:sources.entrySet()) copy.put(item.getKey(),immutable(item.getValue()));
            sourceRules=Collections.unmodifiableMap(copy);
        }
    }
    public RuleStore(Context c) {
        context=c.getApplicationContext(); prefs=context.getSharedPreferences("hetu",Context.MODE_PRIVATE);
        root=new File(context.getFilesDir(),"rules-v2");
        try (BufferedReader reader=new BufferedReader(new InputStreamReader(context.getAssets().open("sources.tsv"),StandardCharsets.UTF_8))) {
            String line;
            while ((line=reader.readLine())!=null) {
                if (line.trim().isEmpty() || line.startsWith("#")) continue;
                String[] p=line.split("\t",4);
                if(p.length!=4 || !p[0].matches("[a-z0-9][a-z0-9_-]{1,31}") || catalog.containsKey(p[0])) throw new IOException("内置订阅目录无效");
                ArrayList<String> urls=new ArrayList<>();
                for(String raw:p[3].split(";")){
                    String u=raw.trim();
                    if(!u.startsWith("https://"))throw new IOException("规则源必须使用 HTTPS");
                    if(!urls.contains(u))urls.add(u);
                }
                if(urls.isEmpty())throw new IOException("规则源缺少下载地址");
                catalog.put(p[0],new Source(p[0],p[2],urls,p[1].equals("1")));
            }
            if (!catalog.keySet().containsAll(Arrays.asList("china","hagezi"))) throw new IOException("内置 DNS 过滤目录不完整");
        } catch (IOException e) { throw new IllegalStateException("无法读取内置订阅",e); }
    }

    /** Fast local load; this never opens su or the network. */
    public void reload() throws Exception {
        synchronized (LOCK) {
            if (live!=null && root.getAbsolutePath().equals(liveRoot)) {
                recoverImportedPreferences();
                migrateDnsFilterDefaultsIfNeeded();
                return;
            }
            ensureRoot();
            JSONObject pointer=readPointer();
            if (pointer!=null) {
                Snapshot loaded=readGeneration(pointer.getString("current"));
                publish(loaded);
                pendingImportedPreferences=pointer.optJSONObject("importSettings");
                recoverImportedPreferences();
            } else {
                Map<String,Boolean> enabled=new LinkedHashMap<>();
                for(Source s:catalog.values()) enabled.put(s.id,prefs.getBoolean("source_"+s.id,s.defaultOn));
                Set<String> allow=cleanPreferenceSet("user_allow"), block=cleanPreferenceSet("user_block");
                Map<String,Set<String>> sources=readBuiltins();
                commit(compose("",false,allow,block,enabled,sources,null));
            }
            migrateDnsFilterDefaultsIfNeeded();
        }
    }
    private void migrateDnsFilterDefaultsIfNeeded() throws Exception {
        if(prefs.getBoolean("dns_filter_v3_migrated",false)||live==null)return;
        Map<String,Boolean> flags=new LinkedHashMap<>(live.enabled);
        if(catalog.containsKey("adguard"))flags.put("adguard",true);
        if(catalog.containsKey("hagezi"))flags.put("hagezi",false);
        if(catalog.containsKey("china"))flags.put("china",true);
        if(catalog.containsKey("adaway"))flags.put("adaway",false);
        if(catalog.containsKey("tracking"))flags.put("tracking",false);
        if(!flags.equals(live.enabled)){
            Snapshot current=live;
            commit(compose("",false,current.allow,current.block,flags,current.sourceRules,null));
        }
        prefs.edit().putBoolean("dns_filter_v3_migrated",true).apply();
    }
    /** Domain list and revision are captured from the same immutable generation. */
    static final class EffectiveRules {
        final String revision; final java.util.List<String> domains;
        EffectiveRules(String revision, java.util.List<String> domains) { this.revision=revision; this.domains=java.util.Collections.unmodifiableList(domains); }
    }
    EffectiveRules effectiveRules() { Snapshot s=live; return new EffectiveRules(s==null?"":s.generation,s==null?new java.util.ArrayList<>():new java.util.ArrayList<>(s.effective)); }
    static String publishedRevision() { Snapshot s=live; return s==null?"":s.generation; }
    public java.util.List<String> effectiveDomains() { Snapshot s=live; return s==null?java.util.Collections.emptyList():new java.util.ArrayList<>(s.effective); }
    public int count() { Snapshot s=live; return s==null?0:s.effective.size(); }
    /** No I/O; changes only after a complete snapshot has been published. */
    public String currentRevision() { Snapshot s=live; return s==null?"":s.generation; }
    public String revisionToken() { return currentRevision(); }
    public JSONObject summary() {
        Snapshot s=live;
        try {
            return new JSONObject().put("loaded",s!=null).put("effectiveCount",s==null?0:s.effective.size())
                .put("allowCount",s==null?0:s.allow.size()).put("blockCount",s==null?0:s.block.size())
                .put("updatedAt",s==null?0:s.updatedAt).put("lastRuleUpdate",prefs.getLong("last_rule_update",0))
                .put("lastRuleCheck",prefs.getLong("last_rule_check",0))
                .put("lastRuleUpdateWarning",prefs.getString("last_rule_update_warning",""))
                .put("fromModule",s!=null&&s.fromModule).put("source",s==null?"unloaded":s.fromModule?"module":"local")
                .put("revision",s==null?"":s.generation).put("moduleRevision",s==null?"":s.revision)
                .put("needsModuleSync",prefs.getBoolean("rules_need_module_sync",false))
                .put("lastImportAt",prefs.getLong("last_config_import_at",0))
                .put("lastImportResult",prefs.getString("last_config_import_result",""));
        } catch(Exception impossible) { throw new IllegalStateException(impossible); }
    }
    public boolean isBlocked(String domain) {
        String normalized=normalize(domain); Snapshot s=live;
        if(normalized==null||s==null)return false;
        if(suffixMatch(s.allow,normalized) || suffixMatch(s.filterExceptions,normalized))return false;
        return suffixMatch(s.effective,normalized);
    }

    public java.util.List<String> effectiveAllowDomains() {
        Snapshot s=live;
        if(s==null)return java.util.Collections.emptyList();
        java.util.TreeSet<String> merged=new java.util.TreeSet<>(s.allow);
        merged.addAll(s.filterExceptions);
        return new java.util.ArrayList<>(merged);
    }
    private static boolean suffixMatch(Set<String> rules,String domain){
        if(rules==null||rules.isEmpty()||domain==null||domain.isEmpty())return false;
        String current=domain;
        while(true){
            if(rules.contains(current))return true;
            int dot=current.indexOf('.');
            if(dot<0||dot+1>=current.length())return false;
            current=current.substring(dot+1);
        }
    }
    public List<String> userList(boolean allow) {
        Snapshot s=live; List<String> result=new ArrayList<>();
        if(s!=null) result.addAll(allow?s.allow:s.block);
        Collections.sort(result); return result;
    }
    public JSONArray sources() {
        JSONArray result=new JSONArray(); Snapshot s=live;
        try {
            for(Source source:catalog.values()) {
                JSONObject item=new JSONObject().put("id",source.id).put("name",source.name).put("url",source.primaryUrl());
                item.put("enabled",s==null?source.defaultOn:Boolean.TRUE.equals(s.enabled.get(source.id)));
                // An exported effective set does not identify individual subscription counts.
                item.put("count",s==null?0:(s.fromModule?-1:s.sourceRules.get(source.id).size()));
                result.put(item);
            }
        } catch(Exception impossible) { throw new IllegalStateException(impossible); }
        return result;
    }
    public String describe(String input) {
        String d=normalize(input); if(d==null) return "请输入有效的域名或 HTTP(S) 网址";
        Snapshot s=live; if(s==null) return d+"：规则尚未加载";
        String suffix="\nDNS 后缀匹配：一条域名规则会覆盖该域名及其子域；白名单优先。此处显示规则命中，不代表所有应用的实际流量。";
        if(prefs.getBoolean("rules_need_module_sync",false)) suffix="\n注意：模块变更尚未同步，以下是应用保留的上一份完整快照。"+suffix;
        if(suffixMatch(s.allow,d)) return d+"：白名单放行（优先于黑名单与订阅）"+suffix;
        if(suffixMatch(s.filterExceptions,d)) return d+"：命中过滤器例外规则，放行"+suffix;
        if(suffixMatch(s.block,d)) return d+"：命中自定义黑名单"+suffix;
        if(s.fromModule) return d+(suffixMatch(s.effective,d)?"：命中已同步的有效规则":"：未命中已同步的有效规则")+suffix;
        List<String> active=new ArrayList<>(), inactive=new ArrayList<>();
        for(Source source:catalog.values()) if(suffixMatch(s.sourceRules.get(source.id),d)) {
            (Boolean.TRUE.equals(s.enabled.get(source.id))?active:inactive).add(source.name);
        }
        if(!active.isEmpty()) return d+"：命中 "+join(active)+suffix;
        if(!inactive.isEmpty()) return d+"：当前放行；仅存在于未启用订阅 "+join(inactive)+suffix;
        return d+"：未命中当前规则，放行"+suffix;
    }

    public void syncFromModule() throws Exception {
        synchronized(LOCK) {
            reload();
            try { syncModule(null); }
            catch(Exception error) { markModulePending();throw error; }
        }
    }
    private void syncModule(Map<String,Set<String>> replacementSources) throws Exception {
        JSONObject config=null, domains=null;
        // Export calls are individually locked by the module. Match their revision to
        // avoid importing rules from a different generation during concurrent edits.
        for(int attempt=0;attempt<3;attempt++) {
            config=rootJson("export-config");
            // A generation is immutable. Recheck its compact config first; unchanged
            // generations do not need a second su process or the complete domain list.
            if(replacementSources==null && live.fromModule && config.getString("configRevision").equals(live.revision)) {
                mirrorPrefs(live); clearModulePending(); return;
            }
            domains=rootJson("export-domains");
            if(config.getString("configRevision").equals(domains.getString("configRevision"))) break;
            config=null;
        }
        if(config==null) throw new IOException("模块规则正在变更，请稍后重新同步");
        String revision=config.getString("configRevision");
        Set<String> allow=domainArray(config.getJSONArray("allow")), block=domainArray(config.getJSONArray("block"));
        Map<String,Boolean> enabled=sourceFlags(config.getJSONArray("sources"),true);
        Set<String> effective=domainArray(domains.getJSONArray("domains"));
        for(String d:allow) if(effective.contains(d)) throw new IOException("模块导出不一致：白名单仍在有效规则中");
        for(String d:block) if(!allow.contains(d)&&!effective.contains(d)) throw new IOException("模块导出不一致：黑名单缺少有效规则");
        Map<String,Set<String>> sourceData=replacementSources==null?live.sourceRules:replacementSources;
        commit(compose(revision,true,allow,block,enabled,sourceData,effective));
        clearModulePending();
    }

    /** Update enabled DNS blocklists independently; a slow mirror must not discard other successful sources. */
    public boolean updateRules(boolean moduleInstalled) throws Exception {
        try(RuleUpdateGate.Lease update=UPDATE_GATE.begin()) {
            final Snapshot before;
            synchronized(LOCK) {
                checkInterrupted();
                reload();
                // Hetu no longer requires a separate module for DNS filtering.
                before=live;
            }
            Map<String,Set<String>> next=new LinkedHashMap<>(before.sourceRules);
            File staging=new File(context.getCacheDir(),"rules-download-"+UUID.randomUUID());
            if(!staging.mkdirs()) throw new IOException("无法建立规则下载目录");
            long batchDeadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(DOWNLOAD_BATCH_MILLIS);
            ArrayList<String> failures=new ArrayList<>();
            ArrayList<String> successes=new ArrayList<>();
            try {
                for(Source source:catalog.values()) {
                    checkInterrupted();
                    if(!Boolean.TRUE.equals(before.enabled.get(source.id))) continue;
                    Set<String> parsed=null;
                    Throwable lastError=null;
                    for(int mirror=0;mirror<source.urls.size();mirror++) {
                        checkInterrupted();
                        File downloaded=new File(staging,source.id+"-"+mirror+".download");
                        long mirrorDeadline=Math.min(batchDeadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(75));
                        try {
                            download(source.urls.get(mirror),downloaded,mirrorDeadline);
                            try(InputStream in=new FileInputStream(downloaded)) { parsed=parseRules(in,false); }
                            if(parsed!=null&&!parsed.isEmpty())break;
                        } catch(Throwable error) {
                            if(error instanceof InterruptedException)Thread.currentThread().interrupt();
                            checkInterrupted();
                            lastError=error;
                            parsed=null;
                        }
                    }
                    if(parsed==null||parsed.isEmpty()) {
                        failures.add(source.name+"："+errorMessage(lastError==null?new IOException("所有镜像均不可用"):lastError));
                        continue;
                    }
                    next.put(source.id,parsed);
                    successes.add(source.name+" "+parsed.size()+" 条");
                }
                if(successes.isEmpty()) {
                    String detail=failures.isEmpty()?"没有启用规则源":join(failures);
                    prefs.edit().putLong("last_rule_check",System.currentTimeMillis())
                            .putString("last_rule_update_warning",detail+"；已继续使用本地旧快照").apply();
                    if(before.effective.isEmpty()) throw new IOException("首次规则下载失败："+detail);
                    return false;
                }

                Snapshot candidate=compose("",false,before.allow,before.block,before.enabled,next,null);
                synchronized(LOCK) {
                    checkInterrupted();
                    reload();
                    update.verify(before.generation,live.generation);
                    long now=System.currentTimeMillis();
                    String warning=failures.isEmpty()?"":"部分来源沿用旧快照："+join(failures);
                    if(next.equals(before.sourceRules)) {
                        prefs.edit().putLong("last_rule_check",now)
                                .putString("last_rule_update_warning",warning).apply();
                        return false;
                    }
                    commit(candidate);
                    prefs.edit().putLong("last_rule_update",now).putLong("last_rule_check",now)
                            .putString("last_rule_update_warning",warning)
                            .putString("last_rule_update_success",join(successes)).apply();
                    return true;
                }
            } finally { deleteTree(staging); }
        }
    }
    public void setSource(String id,boolean enabled,boolean moduleInstalled) throws Exception {
        synchronized(LOCK) {
            reload(); if(!catalog.containsKey(id)) throw new IllegalArgumentException("未知订阅源");
            verifyLocalTarget(moduleInstalled);
            if(moduleInstalled) { mutateModule("set-source",id,enabled?"1":"0"); }
            else {
                Map<String,Boolean> flags=new LinkedHashMap<>(live.enabled); flags.put(id,enabled);
                commit(compose("",false,live.allow,live.block,flags,live.sourceRules,null));
            }
        }
    }
    public String profileTitle() {
        Snapshot snapshot=live;
        return snapshot==null?"加载中":RuleProfiles.title(RuleProfiles.identify(snapshot.enabled));
    }
    public void setProfile(String id,boolean moduleInstalled) throws Exception {
        Map<String,Boolean> flags=RuleProfiles.flags(id); // Reject before any mutation.
        synchronized(LOCK) {
            reload(); verifyLocalTarget(moduleInstalled);
            // Read the latest authoritative lists before applying a preset. Reuse the
            // existing single-generation import, not four individually committed toggles.
            if(moduleInstalled) syncModule(null);
            JSONObject config=exportSettings();
            JSONArray sources=new JSONArray();
            for(Map.Entry<String,Boolean> entry:flags.entrySet())
                sources.put(new JSONObject().put("id",entry.getKey()).put("enabled",entry.getValue()));
            config.put("sources",sources);
            config.remove("vpn_bypass_packages"); // Never overwrite an in-flight app selection.
            importSettings(config,moduleInstalled);
        }
    }
    public String blockedAlias(DnsPacket.Query query,byte[] response,boolean enabled) throws IOException {
        Snapshot snapshot=live;
        if(snapshot==null) return null;
        // One immutable snapshot for both exceptions and blocked targets.
        return DnsResponseFilter.blockedAlias(query,response,enabled,
                domain -> suffixMatch(snapshot.allow,domain) || suffixMatch(snapshot.filterExceptions,domain),
                domain -> suffixMatch(snapshot.effective,domain));
    }
    public void changeDomain(String raw,boolean allow,boolean add,boolean moduleInstalled) throws Exception {
        String domain=normalize(raw); if(domain==null) throw new IllegalArgumentException("请输入有效域名或 HTTP(S) 网址，不支持 IP 或通配符");
        synchronized(LOCK) {
            reload();
            verifyLocalTarget(moduleInstalled);
            if(moduleInstalled) { mutateModule((add?"add-":"remove-")+(allow?"allow":"block"),domain); }
            else {
                Set<String> a=new HashSet<>(live.allow), b=new HashSet<>(live.block), target=allow?a:b;
                if(add) target.add(domain); else target.remove(domain);
                commit(compose("",false,a,b,live.enabled,live.sourceRules,null));
            }
        }
    }
    public JSONObject exportSettings() throws Exception {
        synchronized(LOCK) {
            reload(); Snapshot s=live;
            JSONObject result=new JSONObject().put("schema",1).put("application","Hetu");
            result.put("exportedAt",System.currentTimeMillis()).put("ruleRevision",s.generation)
                .put("origin",s.fromModule?"module":"local").put("requiresModuleSync",prefs.getBoolean("rules_need_module_sync",false));
            result.put("allow",array(s.allow)).put("block",array(s.block));
            JSONArray flags=new JSONArray();
            for(Source source:catalog.values()) flags.put(new JSONObject().put("id",source.id).put("enabled",Boolean.TRUE.equals(s.enabled.get(source.id))));
            result.put("sources",flags);
            result.put("vpn_bypass_packages",array(prefs.getStringSet("bypassApps",Collections.emptySet())));
            return result;
        }
    }
    public void importSettings(JSONObject json,boolean moduleInstalled) throws Exception {
        if(json==null || json.optInt("schema",-1)!=1) throw new IOException("不支持的配置文件版本");
        if(json.has("application")&&!"Hetu".equals(json.getString("application"))) throw new IOException("此配置文件不属于河图");
        if(json.toString().getBytes(StandardCharsets.UTF_8).length>MAX_COMBINED_BYTES) throw new IOException("配置文件超过 32 MiB");
        // Fully validate before any root mutation. Black/white lists commit together.
        Set<String> allow=domainArray(json.getJSONArray("allow")), block=domainArray(json.getJSONArray("block"));
        Map<String,Boolean> flags=sourceFlags(json.getJSONArray("sources"),true);
        Set<String> bypass=null;
        if(json.has("vpn_bypass_packages")) {
            bypass=new HashSet<>(); JSONArray list=json.getJSONArray("vpn_bypass_packages");
            if(list.length()>2000) throw new IOException("应用放行名单过长");
            for(int i=0;i<list.length();i++) {
                String p=list.getString(i);
                if(p.length()>255 || !p.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) throw new IOException("应用包名无效");
                bypass.add(p);
            }
        }
        synchronized(LOCK) {
            reload();
            verifyLocalTarget(moduleInstalled);
            // Verify composition and disk limits before changing either destination.
            Snapshot candidate=compose("",false,allow,block,flags,live.sourceRules,null);
            if(moduleInstalled) {
                File staging=new File(context.getCacheDir(),"rules-import-"+UUID.randomUUID());
                if(!staging.mkdirs()) throw new IOException("无法建立配置导入目录");
                try {
                    File a=new File(staging,"allow"),b=new File(staging,"block"); writeDomains(a,allow);writeDomains(b,block);
                    markModulePending();
                    // One module generation includes both lists and all source flags.
                    // An older module rejects this command before applying any list.
                    List<String> arguments=new ArrayList<>(Arrays.asList("import-settings",a.getAbsolutePath(),b.getAbsolutePath()));
                    for(String id:catalog.keySet())
                        arguments.add(Boolean.TRUE.equals(flags.get(id))?"1":"0");
                    rootJson(arguments.toArray(new String[0]));
                    persistImportPreferences(bypass,"模块配置已整批提交，等待同步");
                    try {
                        syncModule(null);
                    } catch(Exception e) {
                        throw new IOException("配置已在模块整批提交，但应用同步失败；请重新同步模块："+errorMessage(e),e);
                    }
                } finally { deleteTree(staging); }
            } else {
                // The settings file is written before the snapshot pointer moves;
                // recovery can finish preferences if the process dies after commit.
                commitImported(candidate,bypass);
            }
            persistImportPreferences(bypass,"配置已整批导入");
        }
    }
    public void rollback(boolean moduleInstalled) throws Exception {
        synchronized(LOCK) {
            reload();
            verifyLocalTarget(moduleInstalled);
            if(moduleInstalled) { mutateModule("rollback"); return; }
            JSONObject pointer=readPointer(); String previous=pointer==null?"":pointer.optString("previous","");
            if(previous.isEmpty()) throw new IOException("还没有可回滚的本地规则");
            Snapshot restored=readGeneration(previous);
            writePointer(new JSONObject().put("current",previous).put("previous",live.generation));
            publish(restored); mirrorPrefs(restored);
        }
    }

    private void markModulePending() throws IOException {
        // Set before invoking root: an interrupted response cannot be mistaken for
        // a rolled-back module transaction after the process is restarted.
        if(!prefs.edit().putBoolean("rules_need_module_sync",true).commit()) throw new IOException("无法保存模块同步状态，未继续操作");
    }
    private void clearModulePending() { prefs.edit().putBoolean("rules_need_module_sync",false).apply(); }
    private void mutateModule(String...args) throws Exception {
        markModulePending();rootJson(args);
        try { syncModule(null); }
        catch(Exception e) { throw new IOException("模块操作已提交，但应用同步失败；请重新同步模块："+errorMessage(e),e); }
    }
    private void verifyLocalTarget(boolean moduleInstalled) throws Exception {
        if(moduleInstalled || (live!=null&&!live.fromModule&&!prefs.getBoolean("rules_need_module_sync",false))) return;
        JSONObject status=RootBridge.status(context);
        if(!status.optBoolean("ok",false)||!status.has("installed")||status.optBoolean("installed",true)
                ||status.optBoolean("pendingReboot",false))
            throw new IOException("上一份规则来自模块，当前模块状态尚未确认；请刷新模块状态后操作，未改成本地规则");
        clearModulePending();
    }
    private void persistImportPreferences(Set<String> bypass,String result) throws IOException {
        SharedPreferences.Editor edit=prefs.edit().putLong("last_config_import_at",System.currentTimeMillis())
            .putString("last_config_import_result",result);
        if(bypass!=null) edit.putStringSet("bypassApps",new HashSet<>(bypass));
        if(!edit.commit()) throw new IOException("规则已提交，但应用放行配置保存失败；请重新导入配置");
    }
    private void recoverImportedPreferences() throws Exception {
        JSONObject pending=pendingImportedPreferences;if(pending==null)return;
        String generation=pending.getString("generation");
        if(!generation.equals(prefs.getString("last_config_import_generation",""))) {
            SharedPreferences.Editor edit=prefs.edit().putString("last_config_import_generation",generation)
                .putLong("last_config_import_at",pending.getLong("at")).putString("last_config_import_result","配置已整批导入");
            if(pending.has("bypass")) {
                Set<String> bypass=new HashSet<>();JSONArray items=pending.getJSONArray("bypass");
                for(int i=0;i<items.length();i++)bypass.add(items.getString(i));
                edit.putStringSet("bypassApps",bypass);
            }
            if(!edit.commit()) throw new IOException("规则已提交，应用放行配置等待恢复；请释放存储后重试");
        }
        pendingImportedPreferences=null;
    }

    /** URL paths are discarded; request URLs are never persisted or logged. */
    public static String normalize(String raw) {
        if(raw==null) return null;
        String domain=raw.trim(); if(domain.isEmpty() || domain.length()>8192) return null;
        try {
            if(domain.contains("://")) {
                URI uri=new URI(domain);
                if(!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) return null;
                domain=uri.getRawAuthority();
                if(domain==null || domain.indexOf('@')>=0 || domain.indexOf('%')>=0 || domain.startsWith("[")) return null;
                int colon=domain.lastIndexOf(':');
                if(colon>=0) {
                    String port=domain.substring(colon+1); if(!port.matches("[0-9]{1,5}")) return null;
                    int value=Integer.parseInt(port); if(value<1 || value>65535) return null;
                    domain=domain.substring(0,colon);
                }
            } else if(domain.indexOf('/')>=0 || domain.indexOf(':')>=0 || domain.indexOf('?')>=0 || domain.indexOf('#')>=0) return null;
            domain=IDN.toASCII(domain,IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if(domain.endsWith(".")) domain=domain.substring(0,domain.length()-1);
            if(domain.length()>253 || domain.indexOf('.')<0 || domain.equals("localhost.localdomain")) return null;
            String[] labels=domain.split("\\.",-1);
            for(String label:labels) if(label.length()<1 || label.length()>63 || !label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) return null;
            if(!labels[labels.length-1].matches(".*[a-z].*")) return null;
            return domain;
        } catch(Exception invalid) { return null; }
    }

    private JSONObject rootJson(String...args) throws Exception {
        checkInterrupted();
        RootBridge.Result result=RootBridge.run(context,120000L,args);
        checkInterrupted();
        if(result.output==null || result.output.getBytes(StandardCharsets.UTF_8).length>MAX_COMBINED_BYTES) throw new IOException("模块返回内容为空或超过 32 MiB");
        JSONObject json;
        try { json=RootBridge.parseObject(result.output.trim()); }
        catch(Exception e) { throw new IOException("模块未返回有效结果，请确认已安装内置新版模块",e); }
        if(result.code!=0 || !json.optBoolean("ok",false)) throw new IOException(json.optString("message","模块操作失败"));
        return json;
    }
    private Snapshot compose(String revision,boolean fromModule,Set<String> allow,Set<String> block,
            Map<String,Boolean> enabled,Map<String,Set<String>> sources,Set<String> exact) throws IOException {
        Set<String> exceptions=sourceExceptions(enabled,sources);
        Set<String> effective=exact;
        if(effective==null) {
            effective=new HashSet<>(block);
            for(String id:catalog.keySet()) if(Boolean.TRUE.equals(enabled.get(id))) {
                Set<String> values=sources.get(id);
                if(values==null)continue;
                for(String rule:values) if(!rule.startsWith("@@")) effective.add(rule);
            }
            effective.removeIf(domain->suffixMatch(allow,domain) || suffixMatch(exceptions,domain));
        }
        if(effective.size()>MAX_DOMAINS) throw new IOException("合并后规则超过 "+MAX_DOMAINS+" 条");
        return new Snapshot("",revision,fromModule,effective,allow,block,exceptions,enabled,sources,System.currentTimeMillis());
    }

    private static Set<String> sourceExceptions(Map<String,Boolean> enabled,Map<String,Set<String>> sources){
        Set<String> out=new HashSet<>();
        for(Map.Entry<String,Boolean> flag:enabled.entrySet()){
            if(!Boolean.TRUE.equals(flag.getValue()))continue;
            Set<String> values=sources.get(flag.getKey());
            if(values==null)continue;
            for(String rule:values)if(rule.startsWith("@@")&&rule.length()>2)out.add(rule.substring(2));
        }
        return out;
    }
    private void commit(Snapshot candidate) throws Exception {
        commit(candidate,null,false);
    }
    private void commitImported(Snapshot candidate,Set<String> bypass) throws Exception {
        commit(candidate,bypass,true);
    }
    private void commit(Snapshot candidate,Set<String> importedBypass,boolean importing) throws Exception {
        checkInterrupted();
        ensureRoot();
        String id="g."+UUID.randomUUID().toString(); File dir=new File(root,id);
        if(!dir.mkdir()) throw new IOException("无法建立规则快照");
        boolean published=false;
        try {
            JSONObject cfg=new JSONObject().put("schema",1).put("fromModule",candidate.fromModule).put("revision",candidate.revision).put("updatedAt",candidate.updatedAt);
            cfg.put("allow",array(candidate.allow)).put("block",array(candidate.block));
            JSONArray flags=new JSONArray();
            for(String source:catalog.keySet()) {
                checkInterrupted();
                flags.put(new JSONObject().put("id",source).put("enabled",Boolean.TRUE.equals(candidate.enabled.get(source))));
                writeDomains(new File(dir,"source-"+source),candidate.sourceRules.get(source));
            }
            cfg.put("sources",flags); writeDomains(new File(dir,"effective"),candidate.effective,MAX_COMBINED_BYTES);
            writeBytes(new File(dir,"config.json"),cfg.toString().getBytes(StandardCharsets.UTF_8),MAX_COMBINED_BYTES);
            Snapshot next=new Snapshot(id,candidate.revision,candidate.fromModule,candidate.effective,candidate.allow,candidate.block,candidate.filterExceptions,candidate.enabled,candidate.sourceRules,candidate.updatedAt);
            String previous=live==null || !root.getAbsolutePath().equals(liveRoot)?"":live.generation;
            // Cancellation is checked immediately before the atomic switch. Once the
            // pointer commits, always publish that same generation to DNS readers.
            checkInterrupted();
            JSONObject pointer=new JSONObject().put("current",id).put("previous",previous);
            JSONObject importSettings=null;
            if(importing) {
                importSettings=new JSONObject().put("generation",id).put("at",System.currentTimeMillis());
                if(importedBypass!=null)importSettings.put("bypass",array(importedBypass));
                pointer.put("importSettings",importSettings);
            }
            writePointer(pointer);
            published=true; publish(next); mirrorPrefs(next);
            pendingImportedPreferences=importSettings;recoverImportedPreferences();
            File[] dirs=root.listFiles();
            if(dirs!=null) for(File old:dirs) if(old.isDirectory() && old.getName().startsWith("g.") && !old.getName().equals(id) && !old.getName().equals(previous)) deleteTree(old);
        } finally { if(!published) deleteTree(dir); }
    }
    private Snapshot readGeneration(String id) throws Exception {
        if(!id.matches("g\\.[a-f0-9-]{36}")) throw new IOException("本地规则快照名称无效");
        File dir=new File(root,id);
        JSONObject cfg=new JSONObject(new String(readBytes(new FileInputStream(new File(dir,"config.json")),MAX_COMBINED_BYTES),StandardCharsets.UTF_8));
        if(cfg.getInt("schema")!=1) throw new IOException("不支持的本地规则格式");
        Map<String,Set<String>> sources=new LinkedHashMap<>();
        for(String source:catalog.keySet()) {
            File stored=new File(dir,"source-"+source);
            if(stored.isFile()) {
                try(InputStream in=new FileInputStream(stored)){ sources.put(source,parseRules(in,true)); }
            } else {
                try(InputStream in=context.getAssets().open("rules/"+source+".txt")) { sources.put(source,parseRules(in,true)); }
                catch(FileNotFoundException missing){ sources.put(source,new HashSet<>()); }
            }
        }
        Set<String> effective;
        try(InputStream in=new FileInputStream(new File(dir,"effective"))) { effective=parseRules(in,true); }
        Map<String,Boolean> enabled=sourceFlags(cfg.getJSONArray("sources"),true);
        Set<String> allow=domainArray(cfg.getJSONArray("allow")), block=domainArray(cfg.getJSONArray("block"));
        return new Snapshot(id,cfg.optString("revision",""),cfg.optBoolean("fromModule",false),effective,
                allow,block,sourceExceptions(enabled,sources),enabled,sources,cfg.optLong("updatedAt",new File(dir,"config.json").lastModified()));
    }
    private void publish(Snapshot s) { liveRoot=root.getAbsolutePath(); live=s; }
    private void mirrorPrefs(Snapshot s) {
        SharedPreferences.Editor edit=prefs.edit().putStringSet("user_allow",new HashSet<>(s.allow)).putStringSet("user_block",new HashSet<>(s.block));
        for(String id:catalog.keySet()) edit.putBoolean("source_"+id,Boolean.TRUE.equals(s.enabled.get(id)));
        edit.apply();
    }
    private Set<String> cleanPreferenceSet(String key) {
        Set<String> result=new HashSet<>();
        for(String item:prefs.getStringSet(key,Collections.emptySet())) { String d=normalize(item); if(d!=null) result.add(d); }
        return result;
    }
    private Map<String,Set<String>> readBuiltins() throws Exception {
        Map<String,Set<String>> result=new LinkedHashMap<>();
        for(String id:catalog.keySet()) {
            try(InputStream in=context.getAssets().open("rules/"+id+".txt")) { result.put(id,parseRules(in,true)); }
            catch(FileNotFoundException missing){ result.put(id,new HashSet<>()); }
        }
        return result;
    }
    private Map<String,Boolean> sourceFlags(JSONArray list,boolean complete) throws Exception {
        Map<String,Boolean> result=new LinkedHashMap<>();
        for(int i=0;i<list.length();i++) {
            JSONObject item=list.getJSONObject(i); String id=item.getString("id");
            if(!catalog.containsKey(id) || result.containsKey(id) || !(item.get("enabled") instanceof Boolean)) throw new IOException("订阅配置无效");
            result.put(id,item.getBoolean("enabled"));
        }
        if(complete){
            for(Source source:catalog.values()) if(!result.containsKey(source.id)) result.put(source.id,source.defaultOn);
        }
        return result;
    }
    private static Set<String> domainArray(JSONArray list) throws Exception {
        if(list.length()>MAX_DOMAINS) throw new IOException("域名名单过长");
        Set<String> result=new HashSet<>();
        for(int i=0;i<list.length();i++) {
            Object value=list.get(i); if(!(value instanceof String)) throw new IOException("域名必须为文本");
            String d=normalize((String)value); if(d==null) throw new IOException("名单包含无效域名"); result.add(d);
        }
        return result;
    }
    private static Set<String> immutable(Set<String> source) { return Collections.unmodifiableSet(new HashSet<>(source)); }
    private static JSONArray array(Set<String> items) { List<String> list=new ArrayList<>(items); Collections.sort(list); return new JSONArray(list); }
    private static String join(List<String> values) { StringBuilder s=new StringBuilder(); for(String v:values) { if(s.length()>0)s.append("、");s.append(v); } return s.toString(); }
    private void ensureRoot() throws IOException { if(!root.isDirectory() && !root.mkdirs()) throw new IOException("无法建立本地规则目录"); }
    private JSONObject readPointer() throws Exception {
        AtomicFile pointer=new AtomicFile(new File(root,"current.json"));
        if(!pointer.getBaseFile().exists() && !new File(root,"current.json.bak").exists()) return null;
        return new JSONObject(new String(readBytes(pointer.openRead()),StandardCharsets.UTF_8));
    }
    private void writePointer(JSONObject value) throws Exception {
        AtomicFile pointer=new AtomicFile(new File(root,"current.json")); FileOutputStream out=null;
        try { out=pointer.startWrite();out.write(value.toString().getBytes(StandardCharsets.UTF_8));pointer.finishWrite(out); }
        catch(Exception e) { if(out!=null)pointer.failWrite(out);throw e; }
    }
    private static void writeBytes(File file,byte[] data) throws IOException {
        writeBytes(file,data,MAX_SOURCE_BYTES);
    }
    private static void writeBytes(File file,byte[] data,int maxBytes) throws IOException {
        checkInterrupted();
        if(data.length>maxBytes) throw new IOException("文件超过 "+(maxBytes/1024/1024)+" MiB");
        try(FileOutputStream out=new FileOutputStream(file)) { out.write(data);out.getFD().sync(); }
    }
    private static void writeDomains(File file,Set<String> domains) throws IOException {
        writeDomains(file,domains,MAX_SOURCE_BYTES);
    }
    private static void writeDomains(File file,Set<String> domains,int maxBytes) throws IOException {
        checkInterrupted();
        List<String> sorted=new ArrayList<>(domains);Collections.sort(sorted);
        try(FileOutputStream stream=new FileOutputStream(file)) {
            BufferedWriter out=new BufferedWriter(new OutputStreamWriter(stream,StandardCharsets.UTF_8));
            long bytes=0;
            for(String d:sorted) { checkInterrupted();bytes+=d.length()+1; if(bytes>maxBytes) throw new IOException("规则快照超过 "+(maxBytes/1024/1024)+" MiB");out.write(d);out.newLine();}out.flush();stream.getFD().sync();
        }
    }
    private static byte[] readBytes(InputStream in) throws IOException {
        return readBytes(in,MAX_SOURCE_BYTES);
    }
    private static byte[] readBytes(InputStream in,int maxBytes) throws IOException {
        try(InputStream input=in;ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[16384];int size,total=0;
            while((size=input.read(buffer))!=-1) {checkInterrupted();total+=size;if(total>maxBytes)throw new IOException("规则文件超过 "+(maxBytes/1024/1024)+" MiB");out.write(buffer,0,size);}
            return out.toByteArray();
        }
    }
    static Set<String> parseRules(InputStream in,boolean emptyAllowed) throws IOException {
        byte[] bytes=readBytes(in,emptyAllowed?MAX_COMBINED_BYTES:MAX_SOURCE_BYTES); String text;
        try { text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
        catch(CharacterCodingException e) { throw new IOException("规则不是有效 UTF-8 文本",e); }
        Set<String> rules=new HashSet<>(); int lineNumber=0;
        try(BufferedReader reader=new BufferedReader(new StringReader(text))) {
            String line;
            while((line=reader.readLine())!=null) {
                checkInterrupted();
                if(++lineNumber>1200000 || line.length()>8192) throw new IOException("规则行数或单行长度超过限制");
                if(lineNumber==1 && line.startsWith("\uFEFF")) line=line.substring(1);
                line=line.trim();
                if(line.isEmpty() || line.startsWith("!") || line.startsWith("#") || line.startsWith("[")) continue;

                // AdGuard Home compatible core DNS syntax:
                // ||example.org^ blocks the domain tree; @@||example.org^ is an exception.
                boolean exception=line.startsWith("@@");
                String adblock=exception?line.substring(2):line;
                if(adblock.startsWith("||")) {
                    int endMarker=adblock.indexOf('^',2);
                    if(endMarker<0)endMarker=adblock.indexOf('

                int comment=line.indexOf('#'); if(comment>=0)line=line.substring(0,comment).trim();
                if(line.isEmpty())continue;
                String[] fields=line.split("\\s+"); int first=0;
                if(fields[0].equals("0.0.0.0")||fields[0].equals("127.0.0.1")||fields[0].equals("::")||fields[0].equals("::1")) first=1;
                else if(fields.length!=1) continue;
                if(first>=fields.length)continue;
                for(int i=first;i<fields.length;i++) {
                    String raw=fields[i].toLowerCase(Locale.ROOT);
                    if(raw.startsWith("*."))raw=raw.substring(2);
                    if(raw.endsWith("."))raw=raw.substring(0,raw.length()-1);
                    if(raw.equals("localhost")||raw.equals("localhost.localdomain")||raw.equals("local")||raw.equals("broadcasthost")||raw.matches("ip6-(localhost|loopback|localnet|mcastprefix|allnodes|allrouters|allhosts)")) continue;
                    if(raw.indexOf('*')>=0||raw.indexOf('/')>=0||raw.indexOf(':')>=0||raw.indexOf('$')>=0)continue;
                    String d=normalize(raw);
                    if(d==null)continue;
                    rules.add(d);
                    if(rules.size()>MAX_DOMAINS)throw new IOException("规则条数超过限制");
                }
            }
        }
        if(rules.isEmpty()&&!emptyAllowed)throw new IOException("订阅未包含可用 DNS 规则，保留原规则");
        return rules;
    }
    private static void download(String address,File target,long batchDeadline) throws Exception {
        URL url=new URL(address);
        final long deadline=Math.min(batchDeadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(90));
        final Thread owner=Thread.currentThread();
        for(int hop=0;hop<6;hop++) {
            checkDownloadDeadline(deadline);
            if(!url.getProtocol().equals("https"))throw new IOException("订阅及跳转必须使用 HTTPS");
            final HttpsURLConnection connection=(HttpsURLConnection)url.openConnection();
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(timeout(deadline,8000));connection.setReadTimeout(timeout(deadline,15000));
            connection.setRequestProperty("User-Agent","Hetu/1.0 (Android; dns-filter)");connection.setRequestProperty("Accept-Encoding","identity");
            // Interrupting a Future does not interrupt a blocking HTTPS read. Close
            // the socket as well, so stopped background jobs release their worker.
            ScheduledFuture<?> cancellation=DOWNLOAD_WATCHDOG.scheduleWithFixedDelay(() -> {
                if(owner.isInterrupted()||System.nanoTime()>=deadline)connection.disconnect();
            },250,250,TimeUnit.MILLISECONDS);
            try {
                int status=connection.getResponseCode();
                checkDownloadDeadline(deadline);
                if(status==301||status==302||status==303||status==307||status==308) {
                    String location=connection.getHeaderField("Location");if(location==null)throw new IOException("订阅跳转地址缺失");url=new URL(url,location);continue;
                }
                if(status!=200)throw new IOException("规则下载失败：HTTP "+status);
                long expected=connection.getContentLengthLong();if(expected>MAX_SOURCE_BYTES)throw new IOException("订阅超过 8 MiB");
                String encoding=connection.getContentEncoding();if(encoding!=null&&!encoding.equalsIgnoreCase("identity"))throw new IOException("订阅返回不支持的压缩传输");
                byte[] data;
                try(InputStream input=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                    byte[] buffer=new byte[16384];int length,total=0;
                    while(true) {
                        checkInterrupted();
                        long left=(deadline-System.nanoTime())/1000000;
                        if(left<=0)throw new IOException("规则镜像下载超时；已尝试备用地址，保留该来源旧快照");
                        connection.setReadTimeout((int)Math.min(25000,Math.max(1,left)));
                        length=input.read(buffer);if(length<0)break;
                        checkInterrupted();
                        total+=length;if(total>MAX_SOURCE_BYTES)throw new IOException("订阅超过 8 MiB");
                        out.write(buffer,0,length);
                    }
                    data=out.toByteArray();
                }
                if(data.length==0 || (expected>=0 && expected!=data.length))throw new IOException("订阅下载不完整，保留原规则");
                checkDownloadDeadline(deadline);
                writeBytes(target,data);return;
            } catch(IOException error) {
                checkDownloadDeadline(deadline);throw error;
            } finally {cancellation.cancel(false);connection.disconnect();}
        }
        throw new IOException("订阅跳转次数过多");
    }
    private static void deleteTree(File file) {
        File[] children=file.listFiles();if(children!=null)for(File child:children)deleteTree(child);file.delete();
    }
    private static void checkInterrupted() throws InterruptedIOException {
        if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException("规则更新已取消；已经完成的提交保留");
    }
    static void checkDownloadDeadline(long deadline) throws IOException {
        checkInterrupted();
        if(System.nanoTime()>=deadline)throw new IOException("规则镜像下载超时；已尝试备用地址，保留该来源旧快照");
    }
    private static int timeout(long deadline,int cap) throws IOException {
        checkDownloadDeadline(deadline);
        return (int)Math.max(1,Math.min(cap,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())));
    }
    private static String errorMessage(Throwable error) {
        String message=error.getMessage();return message==null||message.trim().isEmpty()?error.getClass().getSimpleName():message;
    }
}
,2);
                    if(endMarker<0)endMarker=adblock.length();
                    String raw=adblock.substring(2,endMarker).trim().toLowerCase(Locale.ROOT);
                    if(raw.startsWith("*."))raw=raw.substring(2);
                    if(raw.indexOf('*')>=0||raw.indexOf('/')>=0||raw.indexOf(':')>=0)continue;
                    String d=normalize(raw);
                    if(d!=null)rules.add(exception?"@@"+d:d);
                    if(rules.size()>MAX_DOMAINS)throw new IOException("规则条数超过限制");
                    continue;
                }
                if(exception)continue;

                int comment=line.indexOf('#'); if(comment>=0)line=line.substring(0,comment).trim();
                if(line.isEmpty())continue;
                String[] fields=line.split("\\s+"); int first=0;
                if(fields[0].equals("0.0.0.0")||fields[0].equals("127.0.0.1")||fields[0].equals("::")||fields[0].equals("::1")) first=1;
                else if(fields.length!=1) continue;
                if(first>=fields.length)continue;
                for(int i=first;i<fields.length;i++) {
                    String raw=fields[i].toLowerCase(Locale.ROOT);
                    if(raw.startsWith("*."))raw=raw.substring(2);
                    if(raw.endsWith("."))raw=raw.substring(0,raw.length()-1);
                    if(raw.equals("localhost")||raw.equals("localhost.localdomain")||raw.equals("local")||raw.equals("broadcasthost")||raw.matches("ip6-(localhost|loopback|localnet|mcastprefix|allnodes|allrouters|allhosts)")) continue;
                    if(raw.indexOf('*')>=0||raw.indexOf('/')>=0||raw.indexOf(':')>=0||raw.indexOf('$')>=0)continue;
                    String d=normalize(raw);
                    if(d==null)continue;
                    rules.add(d);
                    if(rules.size()>MAX_DOMAINS)throw new IOException("规则条数超过限制");
                }
            }
        }
        if(rules.isEmpty()&&!emptyAllowed)throw new IOException("订阅未包含可用 DNS 规则，保留原规则");
        return rules;
    }
    private static void download(String address,File target,long batchDeadline) throws Exception {
        URL url=new URL(address);
        final long deadline=Math.min(batchDeadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(45));
        final Thread owner=Thread.currentThread();
        for(int hop=0;hop<6;hop++) {
            checkDownloadDeadline(deadline);
            if(!url.getProtocol().equals("https"))throw new IOException("订阅及跳转必须使用 HTTPS");
            final HttpsURLConnection connection=(HttpsURLConnection)url.openConnection();
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(timeout(deadline,8000));connection.setReadTimeout(timeout(deadline,15000));
            connection.setRequestProperty("User-Agent","Hetu/1.0 (Android; dns-filter)");connection.setRequestProperty("Accept-Encoding","identity");
            // Interrupting a Future does not interrupt a blocking HTTPS read. Close
            // the socket as well, so stopped background jobs release their worker.
            ScheduledFuture<?> cancellation=DOWNLOAD_WATCHDOG.scheduleWithFixedDelay(() -> {
                if(owner.isInterrupted()||System.nanoTime()>=deadline)connection.disconnect();
            },250,250,TimeUnit.MILLISECONDS);
            try {
                int status=connection.getResponseCode();
                checkDownloadDeadline(deadline);
                if(status==301||status==302||status==303||status==307||status==308) {
                    String location=connection.getHeaderField("Location");if(location==null)throw new IOException("订阅跳转地址缺失");url=new URL(url,location);continue;
                }
                if(status!=200)throw new IOException("规则下载失败：HTTP "+status);
                long expected=connection.getContentLengthLong();if(expected>MAX_SOURCE_BYTES)throw new IOException("订阅超过 8 MiB");
                String encoding=connection.getContentEncoding();if(encoding!=null&&!encoding.equalsIgnoreCase("identity"))throw new IOException("订阅返回不支持的压缩传输");
                byte[] data;
                try(InputStream input=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                    byte[] buffer=new byte[16384];int length,total=0;
                    while(true) {
                        checkInterrupted();
                        long left=(deadline-System.nanoTime())/1000000;
                        if(left<=0)throw new IOException("规则镜像下载超时；已尝试备用地址，保留该来源旧快照");
                        connection.setReadTimeout((int)Math.min(25000,Math.max(1,left)));
                        length=input.read(buffer);if(length<0)break;
                        checkInterrupted();
                        total+=length;if(total>MAX_SOURCE_BYTES)throw new IOException("订阅超过 8 MiB");
                        out.write(buffer,0,length);
                    }
                    data=out.toByteArray();
                }
                if(data.length==0 || (expected>=0 && expected!=data.length))throw new IOException("订阅下载不完整，保留原规则");
                checkDownloadDeadline(deadline);
                writeBytes(target,data);return;
            } catch(IOException error) {
                checkDownloadDeadline(deadline);throw error;
            } finally {cancellation.cancel(false);connection.disconnect();}
        }
        throw new IOException("订阅跳转次数过多");
    }
    private static void deleteTree(File file) {
        File[] children=file.listFiles();if(children!=null)for(File child:children)deleteTree(child);file.delete();
    }
    private static void checkInterrupted() throws InterruptedIOException {
        if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException("规则更新已取消；已经完成的提交保留");
    }
    static void checkDownloadDeadline(long deadline) throws IOException {
        checkInterrupted();
        if(System.nanoTime()>=deadline)throw new IOException("规则镜像下载超时；已尝试备用地址，保留该来源旧快照");
    }
    private static int timeout(long deadline,int cap) throws IOException {
        checkDownloadDeadline(deadline);
        return (int)Math.max(1,Math.min(cap,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())));
    }
    private static String errorMessage(Throwable error) {
        String message=error.getMessage();return message==null||message.trim().isEmpty()?error.getClass().getSimpleName():message;
    }
}
