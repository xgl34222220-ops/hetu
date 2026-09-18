package io.github.xgl34222220.bichen;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;

/** Only starts previously authorized, explicitly opted-in protection after boot. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        SharedPreferences prefs = context.getSharedPreferences("bichen", Context.MODE_PRIVATE);

        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            if (!prefs.getBoolean("proxyRootWanted", false)) return;
            final PendingResult pending = goAsync();
            new Thread(() -> {
                RootProxyManager root = new RootProxyManager(context.getApplicationContext());
                try {
                    prefs.edit().putBoolean("proxyRootWanted", true).apply();
                    root.replaceRunningAfterUpgrade(ProxyRuntimeProfile.load(prefs));
                    prefs.edit()
                            .putLong("proxyRootLastUpgradeRestartAt", System.currentTimeMillis())
                            .remove("proxyRootUpgradeError")
                            .apply();
                } catch (Exception e) {
                    // Never reinterpret an upgrade failure as "the user asked to stop".
                    // If the old core is still healthy, it stays alive because replacement is
                    // validated before the shell transaction switches runtime state.
                    prefs.edit()
                            .putBoolean("proxyRootWanted", true)
                            .putString(
                                    "proxyRootUpgradeError",
                                    "升级后运行环境刷新失败，已保留运行意图：" +
                                            (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                            )
                            .apply();
                } finally {
                    pending.finish();
                }
            }, "bichen-root-upgrade").start();
            return;
        }

        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)) return;
        if (prefs.getBoolean("proxyRootAutoStart", false) && prefs.getBoolean("proxyRootWanted", false)) {
            final PendingResult pending = goAsync();
            new Thread(() -> {
                try {
                    new RootProxyManager(context.getApplicationContext()).start(ProxyRuntimeProfile.load(prefs));
                } catch (Exception e) {
                    prefs.edit().putString("proxyRootBootError", "Root 代理开机恢复失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())).apply();
                } finally { pending.finish(); }
            }, "bichen-root-boot").start();
            return;
        }

        boolean autoStart = prefs.getBoolean("autoStartVpn", false);
        boolean authorized = VpnService.prepare(context) == null;

        if ("mihomo".equals(prefs.getString("engineOwner", ""))) {
            boolean restart = autoStart && prefs.getBoolean("proxyWanted", false) && authorized;
            boolean restoreHosts = prefs.getBoolean("proxyRestoreHosts", false);
            if (!restart && !restoreHosts) {
                if (autoStart && prefs.getBoolean("proxyWanted", false) && !authorized)
                    prefs.edit().putString("proxyError", "重启后未恢复 Mihomo：VPN 授权不可用，请打开辟尘重新确认").apply();
                return;
            }
            try {
                context.startForegroundService(new Intent(context, MihomoVpnService.class)
                        .setAction(restart ? "START" : "RESTORE"));
            } catch (Exception e) {
                prefs.edit().putString("proxyError", "Mihomo 开机恢复未启动，请打开辟尘继续：" + e.getClass().getSimpleName()).apply();
            }
            return;
        }

        boolean restart = autoStart && prefs.getBoolean("vpnWanted", false) && authorized;
        if (!restart && !prefs.getBoolean("vpnRestoreHosts", false)) {
            if (autoStart && prefs.getBoolean("vpnWanted", false) && !authorized)
                prefs.edit().putString("vpnError", "重启后未恢复应用保护：VPN 授权不可用，请打开辟尘重新确认").apply();
            return;
        }
        try {
            context.startForegroundService(new Intent(context, DnsVpnService.class)
                    .setAction(restart ? DnsVpnService.ACTION_START : DnsVpnService.ACTION_STOP));
        } catch (Exception e) {
            prefs.edit().putString("vpnError", "开机恢复未启动，请打开辟尘继续：" + e.getClass().getSimpleName()).apply();
        }
    }
}
