package io.github.xgl34222220.hetu;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;

/** Only Root observation is isolated; every controller read uses real loopback HTTP. */
public final class ControllerRunState90Shadows {
    @Implements(value = RootProxyManager.class, isInAndroidSdk = false)
    public static class RootStatus {
        public static volatile String reply = "{}";
        public static volatile boolean fail;
        public static volatile int reads;
        public static volatile CountDownLatch entered;
        public static volatile CountDownLatch gate;
        @Resetter public static void reset() {
            if (gate != null) gate.countDown();
            reply = "{}"; fail = false; reads = 0; entered = null; gate = null;
        }
        @Implementation public JSONObject status() throws Exception {
            String captured = reply;
            boolean capturedFailure = fail;
            reads++;
            CountDownLatch capturedEntered = entered, capturedGate = gate;
            if (capturedEntered != null) capturedEntered.countDown();
            if (capturedGate != null && !capturedGate.await(8, TimeUnit.SECONDS))
                throw new AssertionError("Controlled Root observation was not released");
            if (capturedFailure) throw new IOException("运行状态暂时无法确认");
            return new JSONObject(captured);
        }
    }
}
