package io.github.xgl34222220.bichen;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Temporary compatibility route: the existing proxy entry now opens Basic Proxy Configuration. */
public final class ProxyActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = new Intent(this, RootTproxyActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }
}
