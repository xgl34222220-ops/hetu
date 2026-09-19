package io.github.xgl34222220.hetu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;

/** Only starts previously authorized, explicitly opted-in protection after boot. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        SharedPreferences prefs = context.getSharedPreferences("hetu", Context.MODE_PRIVATE);

        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // Never touch a live Root proxy during APK replacement. Android may kill/restart
            // app processes around package replacement, and an automatic core transaction
            // here can turn an otherwise healthy proxy into an outage if the new runtime
            // fails after validation. Mark the deployed runtime stale instead; the user's
            // next explicit restart applies the new assets/policy safely.
            if (prefs.getBoolean("proxyRootWanted", false)) {
                prefs.edit()
                        .putBoolean("proxyRootRuntimeRefreshPending", true)
                        .putLong("proxyRootRuntimeRefreshPendingAt", System.currentTimeMillis())
                        .remove("proxyRootUpgradeError")
                        .apply();
            }
            return;
        }

        boolean bootOrUnlock=Intent.ACTION_BOOT_COMPLETED.equals(action)||Intent.ACTION_USER_UNLOCKED.equals(action);
        if (!bootOrUnlock) return;
        if (prefs.getBoolean("proxyRootAutoStart", false) && prefs.getBoolean("proxyRootWanted", false)) {
            try {
                Intent restore=new Intent(context,ProxyNetworkMatchService.class)
                        .setAction(ProxyNetworkMatchService.ACTION_BOOT_RESTORE);
                if (android.os.Build.VERSION.SDK_INT>=26) context.startForegroundService(restore);
                else context.startService(restore);
                prefs.edit().putLong("proxyRootBootRestoreRequestedAt",System.currentTimeMillis()).remove("proxyRootBootError").apply();
            } catch (Exception e) {
                prefs.edit().putString("proxyRootBootError","开机守护服务未启动："+e.getClass().getSimpleName()).apply();
            }
            return;
        }

        boolean autoStart = prefs.getBoolean("autoStartVpn", false);
        boolean authorized = VpnService.prepare(context) == null;

        if ("mihomo".equals(prefs.getString("engineOwner", ""))) {
            boolean restart = autoStart && prefs.getBoolean("proxyWanted", false) && authorized;
            boolean restoreHosts = prefs.getBoolean("proxyRestoreHosts", false);
            if (!restart && !restoreHosts) {
                if (autoStart && prefs.getBoolean("proxyWanted", false) && !authorized)
                    prefs.edit().putString("proxyError", "重启后未恢复 Mihomo：VPN 授权不可用，请打开河图重新确认").apply();
                return;
            }
            try {
                context.startForegroundService(new Intent(context, MihomoVpnService.class)
                        .setAction(restart ? "START" : "RESTORE"));
            } catch (Exception e) {
                prefs.edit().putString("proxyError", "Mihomo 开机恢复未启动，请打开河图继续：" + e.getClass().getSimpleName()).apply();
            }
            return;
        }

        boolean restart = autoStart && prefs.getBoolean("vpnWanted", false) && authorized;
        if (!restart && !prefs.getBoolean("vpnRestoreHosts", false)) {
            if (autoStart && prefs.getBoolean("vpnWanted", false) && !authorized)
                prefs.edit().putString("vpnError", "重启后未恢复应用保护：VPN 授权不可用，请打开河图重新确认").apply();
            return;
        }
        try {
            context.startForegroundService(new Intent(context, DnsVpnService.class)
                    .setAction(restart ? DnsVpnService.ACTION_START : DnsVpnService.ACTION_STOP));
        } catch (Exception e) {
            prefs.edit().putString("vpnError", "开机恢复未启动，请打开河图继续：" + e.getClass().getSimpleName()).apply();
        }
    }
}
