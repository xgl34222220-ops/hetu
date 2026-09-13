package bichen.probe;

import android.app.Activity;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.SystemClock;
import java.net.*;
import java.io.*;

/** Separate UID. Wait for Android's real default VPN, not another process's flag. */
public final class Probe extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            boolean ready=false, allowed=false, blocked=false, adsAllowed=false;
            String detail="";
            try {
                ready=awaitDefaultVpn();
                if(!ready) throw new IOException("default VPN and DNS were not published to probe UID");
                allowed=fetch("http://normal.integration.test/check").equals("BICHEN_PROXY_OK");
            } catch(Exception e) { detail=e.getClass().getSimpleName()+": "+e.getMessage(); }
            if(ready) {
                try { adsAllowed=fetch("http://ads.integration.test/check").equals("BICHEN_PROXY_OK"); }
                catch(IOException expected) { blocked=true; }
            }
            sendBroadcast(new Intent("bichen.integration.RESULT")
                    .setPackage("io.github.xgl34222220.bichen.preview")
                    .putExtra("allowed",allowed).putExtra("blocked",blocked)
                    .putExtra("adsAllowed",adsAllowed).putExtra("defaultVpnReady",ready)
                    .putExtra("detail",detail));
            runOnUiThread(this::finish);
        },"independent-vpn-probe").start();
    }
    private boolean awaitDefaultVpn() {
        ConnectivityManager cm=getSystemService(ConnectivityManager.class);
        long deadline=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<deadline) {
            Network n=cm.getActiveNetwork();
            NetworkCapabilities caps=n==null?null:cm.getNetworkCapabilities(n);
            LinkProperties lp=n==null?null:cm.getLinkProperties(n);
            if(caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)&&lp!=null
                    &&lp.getInterfaceName()!=null&&!lp.getDnsServers().isEmpty()
                    &&lp.getDnsServers().get(0).getHostAddress().equals("172.29.0.2")) return true;
            SystemClock.sleep(50);
        }
        return false;
    }
    private String fetch(String url)throws IOException {
        // Ordinary default-network request: never bind around the VPN or retry a failed request.
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(6000);c.setReadTimeout(6000);
        try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
            return out.toString("UTF-8");
        } finally { c.disconnect(); }
    }
}
