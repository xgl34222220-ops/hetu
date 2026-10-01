package io.github.xgl34222220.hetu;

import org.json.JSONObject;

public final class RuntimeIdentityTest {
    private static int checks;
    private static void check(boolean value,String message) {
        if(!value) throw new AssertionError(message);
        checks++;
    }
    private static JSONObject process() throws Exception {
        return new JSONObject().put("ok",true).put("running",true).put("pid",142)
                .put("processStartTicks","90112").put("bootId","aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
                .put("mode","tproxy");
    }
    public static void main(String[] args) throws Exception {
        JSONObject live=process();
        String launched=RuntimeIdentity.completed(live,"mihomo","tproxy","A.yaml");
        RuntimeIdentity.Snapshot actual=RuntimeIdentity.resolve(live,launched);
        check(actual.confirmed,"completed launch identity resolves");
        check(actual.core.equals("Mihomo") && actual.mode.equals("TPROXY") && actual.source.equals("A.yaml"),"labels describe completed launch");
        check(!RuntimeIdentity.resolve(live,"").confirmed,"legacy session is unknown");
        check(!RuntimeIdentity.resolve(live,"not json").confirmed,"corrupt persisted record is unknown");
        for(String key:new String[]{"pid","processStartTicks","bootId","mode"}) {
            JSONObject missing=process(); missing.remove(key);
            check(!RuntimeIdentity.resolve(missing,launched).confirmed,"missing observed "+key);
            JSONObject record=new JSONObject(launched); record.remove(key);
            check(!RuntimeIdentity.resolve(live,record.toString()).confirmed,"missing recorded "+key);
        }
        check(!RuntimeIdentity.resolve(process().put("pid",143),launched).confirmed,"replacement PID cannot inherit identity");
        check(!RuntimeIdentity.resolve(process().put("processStartTicks","90200"),launched).confirmed,"reused PID cannot inherit identity");
        check(!RuntimeIdentity.resolve(process().put("bootId","11111111-2222-3333-4444-555555555555"),launched).confirmed,"reboot cannot inherit identity");
        check(!RuntimeIdentity.resolve(process().put("mode","tun"),launched).confirmed,"mode mismatch remains unknown");
        check(!RuntimeIdentity.resolve(process().put("running",false),launched).confirmed,"stopped core cannot be confirmed");
        check(!RuntimeIdentity.resolve(process().put("healthProbeFailed",true),launched).confirmed,"failed fresh probe never uses old identity");
        for(String flag:new String[]{"alreadyRunning","cancelled"})
            check(RuntimeIdentity.completed(process().put(flag,true),"sing-box","tun","B.json").isEmpty(),"no new binding for "+flag);
        check(RuntimeIdentity.completed(process().put("ok",false),"mihomo","tproxy","B.yaml").isEmpty(),"failed launch cannot publish metadata");
        for(String invalid:new String[]{"","0","-1","x","12\n","123456789012345678901"})
            check(RuntimeIdentity.completed(process().put("processStartTicks",invalid),"mihomo","tproxy","A.yaml").isEmpty(),"invalid ticks rejected");
        for(String source:new String[]{"","../A.yaml","A\n.yaml","A\\B.yaml"})
            check(RuntimeIdentity.completed(live,"mihomo","tproxy",source).isEmpty(),"invalid source identity rejected");
        check(RuntimeIdentity.completed(live,"unrecognized","tproxy","A.yaml").isEmpty(),"unknown core does not default to Mihomo");
        check(RuntimeIdentity.completed(live,"mihomo","bad-mode","A.yaml").isEmpty(),"unknown mode does not default to TPROXY");
        String renamed=RuntimeIdentity.reloaded(live,launched,"mihomo","tproxy","B \"one\".yaml");
        check(RuntimeIdentity.resolve(live,renamed).source.equals("B \"one\".yaml"),"same live process receives exact successful reload source");
        check(RuntimeIdentity.reloaded(live,launched,"sing-box","tproxy","B.yaml").isEmpty(),"selected core cannot rewrite live identity");
        check(RuntimeIdentity.reloaded(live,launched,"mihomo","tun","B.yaml").isEmpty(),"selected mode cannot rewrite live identity");
        check(RuntimeIdentity.reloaded(process().put("pid",143),launched,"mihomo","tproxy","B.yaml").isEmpty(),"restart during reload cannot inherit source");
        check(RuntimeIdentity.reloaded(live,"","mihomo","tproxy","B.yaml").isEmpty(),"reload cannot invent unknown core identity");
        JSONObject cached=RuntimeIdentity.observation(new JSONObject(launched).put("secret","test-only").put("url","https://example.invalid"));
        check(!cached.has("secret") && !cached.has("url") && !cached.has("source") && !cached.has("core"),"observation cache contains only process evidence");
        check(RuntimeIdentity.resolve(RuntimeIdentity.cachedObservation(cached.toString()),launched).confirmed,"bounded healthy observation can retain verified identity");
        check(!RuntimeIdentity.resolve(RuntimeIdentity.cachedObservation("bad"),launched).confirmed,"invalid cache cannot restore identity");
        System.out.println("RuntimeIdentityTest passed: "+checks);
    }
}
