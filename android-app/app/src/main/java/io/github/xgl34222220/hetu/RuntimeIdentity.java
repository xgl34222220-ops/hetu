package io.github.xgl34222220.hetu;

import org.json.JSONObject;

/** Identity of a completed launch, verified against the observed process, never preferences. */
final class RuntimeIdentity {
    static final String RECORD_KEY = "proxyRootCompletedIdentity";
    static final String OBSERVATION_KEY = "proxyRootIdentityObservation";
    static final String UNKNOWN = "未确认";

    static final class Snapshot {
        final boolean confirmed;
        final String coreId, core, modeId, mode, source;
        private Snapshot(boolean confirmed, String coreId, String core, String modeId, String mode, String source) {
            this.confirmed=confirmed; this.coreId=coreId; this.core=core;
            this.modeId=modeId; this.mode=mode; this.source=source;
        }
    }
    private static Snapshot unknown() {
        return new Snapshot(false,"",UNKNOWN,"",UNKNOWN,UNKNOWN);
    }
    private static boolean processValid(JSONObject value) {
        return value != null && value.optBoolean("running",false) && value.optInt("pid",0)>0
                && value.optString("processStartTicks","").matches("[1-9][0-9]{0,19}")
                && value.optString("bootId","").matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    }
    private static JSONObject parse(String value) {
        try { return new JSONObject(value == null ? "" : value); }
        catch (Exception invalid) { return new JSONObject(); }
    }
    static JSONObject observation(JSONObject status) {
        if(!processValid(status)) return new JSONObject();
        try { return new JSONObject().put("running",true).put("pid",status.getInt("pid"))
                .put("processStartTicks",status.getString("processStartTicks"))
                .put("bootId",status.getString("bootId")).put("mode",status.optString("mode","")); }
        catch (Exception invalid) { return new JSONObject(); }
    }
    static JSONObject cachedObservation(String value) { return observation(parse(value)); }

    static String completed(JSONObject result, String coreId, String modeId, String source) {
        if(result==null || !result.optBoolean("ok",false) || result.optBoolean("alreadyRunning",false)
                || result.optBoolean("cancelled",false) || !processValid(result)
                || modeId==null || !modeId.equals(result.optString("mode",""))) return "";
        try {
            JSONObject record=observation(result).put("core",coreId).put("mode",modeId).put("source",source);
            return resolve(record,record.toString()).confirmed ? record.toString() : "";
        } catch(Exception invalid) { return ""; }
    }
    static Snapshot resolve(JSONObject observed, String stored) {
        JSONObject record=parse(stored);
        if(!processValid(observed) || !processValid(record) || observed.optBoolean("healthProbeFailed",false)
                || observed.optInt("pid")!=record.optInt("pid")
                || !observed.optString("processStartTicks").equals(record.optString("processStartTicks"))
                || !observed.optString("bootId").equals(record.optString("bootId"))
                || !observed.optString("mode").equals(record.optString("mode"))) return unknown();
        ProxyRuntimeProfile.Core core=null;
        ProxyRuntimeProfile.Mode mode=null;
        for(ProxyRuntimeProfile.Core candidate:ProxyRuntimeProfile.Core.values())
            if(candidate.id.equals(record.optString("core"))) core=candidate;
        for(ProxyRuntimeProfile.Mode candidate:ProxyRuntimeProfile.Mode.values())
            if(candidate.id.equals(record.optString("mode"))) mode=candidate;
        String source=record.optString("source","");
        if(core==null || mode==null || source.isEmpty() || source.length()>255
                || source.contains("/") || source.contains("\\") || source.contains("\n") || source.contains("\r")) return unknown();
        return new Snapshot(true,core.id,core.label,mode.id,mode.label,source);
    }
    static String reloaded(JSONObject observed, String stored, String coreId, String modeId, String source) {
        Snapshot before=resolve(observed,stored);
        if(!before.confirmed || !before.coreId.equals(coreId) || !before.modeId.equals(modeId)) return "";
        try { return completed(new JSONObject(observed.toString()).put("ok",true),coreId,modeId,source); }
        catch(Exception invalid) { return ""; }
    }
}
