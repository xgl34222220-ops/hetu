package io.github.xgl34222220.hetu;

import android.content.Context;
import kotlin.coroutines.Continuation;
import org.json.JSONObject;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;

/** Test-only runtime boundaries. Actual controller requests use loopback MockWebServer. */
public final class PanelActionRuntimeShadows {
    @Implements(value = RootProxyManager.class, isInAndroidSdk = false)
    public static class RootStatus {
        @Implementation public JSONObject status() throws Exception {
            return new JSONObject().put("running", true).put("dataPlaneHealthy", true);
        }
    }

    @Implements(value = ProxyRuntimeInspector.class, isInAndroidSdk = false)
    public static class RuntimeSample {
        public static ProxyRuntimeSnapshot value = new ProxyRuntimeSnapshot();
        public static boolean pause;
        public static Continuation<? super ProxyRuntimeSnapshot> pending;
        @Resetter public static void reset() { value = new ProxyRuntimeSnapshot(); pause = false; pending = null; }
        @Implementation public Object sample(Continuation<? super ProxyRuntimeSnapshot> done) {
            if (pause) { pending = done; return kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED(); }
            return value;
        }
        public static void release() {
            Continuation<? super ProxyRuntimeSnapshot> done = pending;
            pending = null;
            if (done != null) done.resumeWith(value);
        }
    }

    @Implements(value = ProxyApiHistoryStore.class, isInAndroidSdk = false)
    public static class HistoryRecord {
        public static volatile boolean pause;
        public static volatile Continuation<? super kotlin.Unit> pending;
        public static final java.util.List<kotlin.Pair<Long, Long>> samples = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        @Resetter public static void reset() { pause = false; pending = null; samples.clear(); }
        @Implementation public Object record(Context app, long upload, long download,
                java.util.List<ProxyConnectionUi> connections, long now, Continuation<? super kotlin.Unit> done) {
            samples.add(new kotlin.Pair<>(upload, download));
            if (pause) { pending = done; return kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED(); }
            return kotlin.Unit.INSTANCE;
        }
        public static void release() {
            Continuation<? super kotlin.Unit> done = pending;
            pending = null;
            if (done != null) done.resumeWith(kotlin.Unit.INSTANCE);
        }
    }

    @Implements(value = RootBridge.class, isInAndroidSdk = false)
    public static class NoRoot {
        public static int calls;
        @Resetter public static void reset() { calls = 0; }
        @Implementation public static RootBridge.Result rootShell(Context app, String command, long timeout) {
            calls++;
            throw new SecurityException("Root commands are forbidden in panel action fixtures");
        }
    }
}
