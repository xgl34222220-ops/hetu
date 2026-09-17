package io.github.xgl34222220.bichen;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import org.json.JSONObject;
import java.io.IOException;

/** Keeps exactly one ad-block execution path active while Root proxy chaining is enabled. */
final class ProxyAdblockCoordinator {
    private static final long DNS_STOP_WAIT_MS = 2200L;

    static void enter(Context context) throws Exception {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("bichen", Context.MODE_PRIVATE);
        if (prefs.getBoolean("proxyAdblockChainActive", false)) return;

        boolean resumeDns = DnsVpnService.running && prefs.getBoolean("vpnWanted", false);
        prefs.edit()
                .putBoolean("proxyAdblockChainActive", true)
                .putBoolean("proxyResumeDnsAfterChain", resumeDns)
                .putBoolean("proxyRestoreHostsAfterChain", false)
                .apply();

        if (DnsVpnService.running) {
            Intent pause = new Intent(app, DnsVpnService.class).setAction(DnsVpnService.ACTION_PAUSE_FOR_PROXY);
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(pause); else app.startService(pause);
            long deadline = System.currentTimeMillis() + DNS_STOP_WAIT_MS;
            while (DnsVpnService.running && System.currentTimeMillis() < deadline) Thread.sleep(40L);
            if (DnsVpnService.running) {
                prefs.edit().putBoolean("proxyAdblockChainActive", false).apply();
                throw new IOException("独立 DNS 去广告未能及时暂停，未启动代理串联过滤");
            }
            return;
        }

        JSONObject status = RootBridge.status(app);
        if (!status.optBoolean("ok", false) || !status.optBoolean("installed", false)) return;
        boolean disabled = status.optBoolean("moduleDisabled", false) || status.optBoolean("moduleRemovalPending", false);
        boolean activeHosts = status.optBoolean("enabled", false) || status.optBoolean("mounted", false);
        if (!disabled && activeHosts) {
            RootBridge.Result paused = RootBridge.run(app, "pause");
            JSONObject checked = RootBridge.status(app);
            if (paused.code != 0 || checked.optBoolean("enabled", false) || checked.optBoolean("mounted", false)) {
                prefs.edit().putBoolean("proxyAdblockChainActive", false).apply();
                throw new IOException("无法暂停独立 hosts 去广告，未启动代理串联过滤：" + paused.output);
            }
            prefs.edit().putBoolean("proxyRestoreHostsAfterChain", true).apply();
        }
    }

    static void exit(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("bichen", Context.MODE_PRIVATE);
        boolean active = prefs.getBoolean("proxyAdblockChainActive", false);
        boolean resumeDns = prefs.getBoolean("proxyResumeDnsAfterChain", false);
        boolean restoreHosts = prefs.getBoolean("proxyRestoreHostsAfterChain", false);
        if (!active && !resumeDns && !restoreHosts) return;

        prefs.edit().putBoolean("proxyAdblockChainActive", false).apply();
        if (resumeDns && prefs.getBoolean("vpnWanted", false) && VpnService.prepare(app) == null) {
            try {
                Intent start = new Intent(app, DnsVpnService.class).setAction(DnsVpnService.ACTION_START);
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(start); else app.startService(start);
                prefs.edit().remove("proxyResumeDnsAfterChain").remove("proxyRestoreHostsAfterChain").apply();
                return;
            } catch (Exception ignored) { }
        }

        if (restoreHosts || prefs.getBoolean("vpnRestoreHosts", false)) {
            try {
                JSONObject before = RootBridge.status(app);
                boolean disabled = before.optBoolean("moduleDisabled", false) || before.optBoolean("moduleRemovalPending", false);
                if (before.optBoolean("installed", false) && !disabled) RootBridge.run(app, "enable");
            } catch (Exception ignored) { }
        }
        prefs.edit().remove("proxyResumeDnsAfterChain").remove("proxyRestoreHostsAfterChain").apply();
    }
}
