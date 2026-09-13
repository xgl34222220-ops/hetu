package io.github.xgl34222220.bichen;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;

/** Only starts previously authorized, explicitly opted-in protection after boot. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SharedPreferences prefs = context.getSharedPreferences("bichen", Context.MODE_PRIVATE);
        boolean restart = prefs.getBoolean("autoStartVpn", false) && prefs.getBoolean("vpnWanted", false)
                && VpnService.prepare(context) == null;
        if (!restart && !prefs.getBoolean("vpnRestoreHosts", false)) return;
        try {
            context.startForegroundService(new Intent(context, DnsVpnService.class)
                    .setAction(restart ? DnsVpnService.ACTION_START : DnsVpnService.ACTION_STOP));
        } catch (Exception e) {
            prefs.edit().putString("vpnError", "开机恢复未启动，请打开辟尘继续：" + e.getClass().getSimpleName()).apply();
        }
    }
}
