package io.github.xgl34222220.hetu;

import java.io.IOException;
import org.json.JSONObject;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;

/** Only Root observation is isolated; every controller read uses real loopback HTTP. */
public final class ControllerRunState90Shadows {
    @Implements(value = RootProxyManager.class, isInAndroidSdk = false)
    public static class RootStatus {
        public static String reply = "{}";
        public static boolean fail;
        public static int reads;
        @Resetter public static void reset() { reply = "{}"; fail = false; reads = 0; }
        @Implementation public JSONObject status() throws Exception {
            reads++;
            if (fail) throw new IOException("运行状态暂时无法确认");
            return new JSONObject(reply);
        }
    }
}
