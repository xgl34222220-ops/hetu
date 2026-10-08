package io.github.xgl34222220.hetu;

import android.app.Application;
import androidx.test.core.app.ApplicationProvider;
import kotlin.coroutines.Continuation;
import kotlin.jvm.functions.Function1;
import org.json.JSONObject;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;

/** Only the requested stop is isolated; state and node reads still use real HTTP. */
public final class HomeReadiness93Shadows {
    @Implements(value = ProxyComposeController.class, isInAndroidSdk = false)
    public static class Stop {
        public static int calls;
        @Resetter public static void reset() { calls = 0; }
        @Implementation public Object stop(Function1<? super String, kotlin.Unit> progress,
                Continuation<? super JSONObject> done) throws Exception {
            calls++;
            Application app = ApplicationProvider.getApplicationContext();
            app.getSharedPreferences("hetu", 0).edit().putBoolean("proxyRootWanted", false)
                .putBoolean("proxyRootRuntimeRunning", false).putLong("proxyRootHealthProbeElapsed", 0L).commit();
            ControllerRunState90Shadows.RootStatus.reply = "{\"ok\":true,\"running\":false,\"pid\":0,\"dataPlaneHealthy\":false}";
            return new JSONObject().put("ok", true).put("running", false);
        }
    }
}
