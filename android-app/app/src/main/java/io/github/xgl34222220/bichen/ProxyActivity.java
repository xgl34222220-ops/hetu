package io.github.xgl34222220.bichen;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Single proxy entry. All proxy modes are controlled from Basic Proxy Configuration. */
public final class ProxyActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        startActivity(new Intent(this, RootTproxyActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
}
