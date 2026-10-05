package io.github.xgl34222220.hetu;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Map;
import kotlin.coroutines.Continuation;
import org.json.JSONObject;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;

/** Only runtime status and site transport are isolated; controller HTTP remains real. */
public final class PanelRequestOwnershipShadows {
    @Implements(value = RootProxyManager.class, isInAndroidSdk = false)
    public static class RootStatus {
        public static int pid = 77;
        @Resetter public static void reset() { pid = 77; }
        @Implementation public JSONObject status() throws Exception {
            return new JSONObject().put("running", true).put("dataPlaneHealthy", true).put("pid", pid);
        }
    }

    @Implements(value = ProxyDashboardRepository.class, isInAndroidSdk = false)
    public static class Sites {
        public static boolean pause;
        public static Map<String, Long> value = Collections.emptyMap();
        private static final ArrayDeque<Continuation<? super Map<String, Long>>> pending = new ArrayDeque<>();
        @Resetter public static synchronized void reset() { pause = false; value = Collections.emptyMap(); pending.clear(); }
        @Implementation public synchronized Object siteLatencies(Continuation<? super Map<String, Long>> done) {
            if (pause) {
                synchronized (Sites.class) { pending.add(done); }
                return kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED();
            }
            return value;
        }
        public static synchronized int pendingCount() { return pending.size(); }
        public static void releaseFirst(Map<String, Long> result) {
            Continuation<? super Map<String, Long>> done;
            synchronized (Sites.class) { done = pending.poll(); }
            if (done != null) done.resumeWith(result);
        }
        public static void releaseAll() {
            while (pendingCount() > 0) releaseFirst(Collections.emptyMap());
        }
    }
}
