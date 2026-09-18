package io.github.xgl34222220.hetu;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import java.io.IOException;

/**
 * Keeps exactly one Hetu DNS-filter execution path active:
 * either the standalone DNS VPN or Mihomo's injected rule providers.
 * No Magisk/hosts module participates in this coordination.
 */
final class ProxyAdblockCoordinator {
    private static final long DNS_STOP_WAIT_MS = 2200L;

    static void enter(Context context) throws Exception {
        Context app=context.getApplicationContext();
        SharedPreferences prefs=app.getSharedPreferences("hetu",Context.MODE_PRIVATE);
        if(prefs.getBoolean("proxyAdblockChainActive",false))return;

        boolean resumeDns=DnsVpnService.running && prefs.getBoolean("vpnWanted",false);
        prefs.edit()
                .putBoolean("proxyAdblockChainActive",true)
                .putBoolean("proxyResumeDnsAfterChain",resumeDns)
                .remove("proxyRestoreHostsAfterChain")
                .remove("vpnRestoreHosts")
                .apply();

        if(!DnsVpnService.running)return;
        Intent pause=new Intent(app,DnsVpnService.class).setAction(DnsVpnService.ACTION_PAUSE_FOR_PROXY);
        if(Build.VERSION.SDK_INT>=26)app.startForegroundService(pause);else app.startService(pause);
        long deadline=System.currentTimeMillis()+DNS_STOP_WAIT_MS;
        while(DnsVpnService.running&&System.currentTimeMillis()<deadline)Thread.sleep(40L);
        if(DnsVpnService.running){
            prefs.edit().putBoolean("proxyAdblockChainActive",false).apply();
            throw new IOException("独立 DNS 过滤未能及时暂停，未切换到代理内过滤");
        }
    }

    static void exit(Context context) {
        Context app=context.getApplicationContext();
        SharedPreferences prefs=app.getSharedPreferences("hetu",Context.MODE_PRIVATE);
        boolean active=prefs.getBoolean("proxyAdblockChainActive",false);
        boolean resumeDns=prefs.getBoolean("proxyResumeDnsAfterChain",false);
        boolean fallbackEnabled=prefs.getBoolean("proxyAdblockFallbackEnabled",false);
        if(!active&&!resumeDns&&!fallbackEnabled)return;

        prefs.edit()
                .putBoolean("proxyAdblockChainActive",false)
                .remove("proxyRestoreHostsAfterChain")
                .remove("vpnRestoreHosts")
                .apply();

        boolean shouldResume=(fallbackEnabled||resumeDns) && prefs.getBoolean("vpnWanted",fallbackEnabled);
        if(shouldResume&&VpnService.prepare(app)==null){
            try{
                prefs.edit().putBoolean("vpnWanted",true).remove("vpnError").apply();
                Intent start=new Intent(app,DnsVpnService.class).setAction(DnsVpnService.ACTION_START);
                if(Build.VERSION.SDK_INT>=26)app.startForegroundService(start);else app.startService(start);
            }catch(Exception e){
                prefs.edit().putString("vpnError","独立 DNS 过滤恢复失败："+e.getClass().getSimpleName()).apply();
            }
        }
        prefs.edit().remove("proxyResumeDnsAfterChain").apply();
    }
}
