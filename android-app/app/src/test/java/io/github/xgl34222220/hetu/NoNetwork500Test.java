package io.github.xgl34222220.hetu;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import java.io.IOException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/**
 * 5.0.0「测速有网但实际没网，停止启动都一样」: a Root proxy start always pauses Hetu's own standalone DNS VPN, also
 * when the chain flag survived a session that never reached a clean stop (killed App, watchdog exit, reboot).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35}, application = Application.class)
public class NoNetwork500Test {
    private Application app;
    private SharedPreferences prefs;
    private boolean running;

    @Before public void reset() {
        app = ApplicationProvider.getApplicationContext();
        prefs = app.getSharedPreferences("hetu", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        running = DnsVpnService.running;
    }

    @After public void restore() { DnsVpnService.running = running; }

    @Test public void aStaleChainFlagStillPausesARunningDnsVpn() throws Exception {
        prefs.edit().putBoolean("proxyAdblockChainActive", true).putBoolean("vpnWanted", true).commit();
        DnsVpnService.running = true;
        try {
            ProxyAdblockCoordinator.enter(app);
            fail("a running DNS VPN must be paused; it never stops here, so the start is refused instead of sharing the network");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("未能及时暂停"));
            assertFalse(prefs.getBoolean("proxyAdblockChainActive", true));
        }
        assertTrue("the DNS VPN comes back after the proxy stops", prefs.getBoolean("proxyResumeDnsAfterChain", false));
    }

    @Test public void aStaleChainFlagWithoutADnsVpnHasNothingToPause() throws Exception {
        prefs.edit().putBoolean("proxyAdblockChainActive", true).commit();
        DnsVpnService.running = false;
        ProxyAdblockCoordinator.enter(app);
        assertTrue(prefs.getBoolean("proxyAdblockChainActive", false));
    }
}
