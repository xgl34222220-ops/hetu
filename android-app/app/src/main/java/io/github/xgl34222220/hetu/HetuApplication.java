package io.github.xgl34222220.hetu;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

public final class HetuApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (previous != null) Thread.setDefaultUncaughtExceptionHandler(AppCrashReport.handler(this, previous));
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            public void onActivityResumed(Activity activity) { AppCrashReport.screen(activity.getClass().getSimpleName()); }
            public void onActivityCreated(Activity activity, Bundle state) {}
            public void onActivityStarted(Activity activity) {}
            public void onActivityPaused(Activity activity) {}
            public void onActivityStopped(Activity activity) {}
            public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            public void onActivityDestroyed(Activity activity) {}
        });
    }
}
