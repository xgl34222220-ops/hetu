package io.github.xgl34222220.hetu;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowApplication;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class BootStartup60Test {
    Application app;
    SharedPreferences prefs;
    @Before public void reset(){
        app=ApplicationProvider.getApplicationContext();
        prefs=app.getSharedPreferences("hetu",Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
    }
    private void seed(boolean auto,boolean wanted){
        prefs.edit().putBoolean("proxyRootAutoStart",auto).putBoolean("proxyRootWanted",wanted)
                .putLong("proxyRootBootRestoreSuccessAt",123L).commit();
    }
    @Test public void optedInWantedBootSchedulesRootRestoreAndClearsOldSuccess(){
        seed(true,true);
        new BootReceiver().onReceive(app,new Intent(Intent.ACTION_BOOT_COMPLETED));
        Intent started=((ShadowApplication) Shadow.extract(app)).getNextStartedService();
        assertNotNull(started);
        assertEquals(ProxyNetworkMatchService.ACTION_BOOT_RESTORE,started.getAction());
        assertEquals(ProxyNetworkMatchService.class.getName(),started.getComponent().getClassName());
        assertTrue(prefs.getBoolean("proxyRootWanted",false));
        assertFalse(prefs.contains("proxyRootBootRestoreSuccessAt"));
        assertTrue(prefs.getLong("proxyRootBootRestoreRequestedAt",0)>0);
    }
    @Test public void unlockAlsoSchedulesWantedRestore(){
        seed(true,true);
        new BootReceiver().onReceive(app,new Intent(Intent.ACTION_USER_UNLOCKED));
        assertEquals(ProxyNetworkMatchService.ACTION_BOOT_RESTORE,((ShadowApplication) Shadow.extract(app)).getNextStartedService().getAction());
    }
    @Test public void explicitStopIsNeverUndoneByAutostart(){
        seed(true,false);
        new BootReceiver().onReceive(app,new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertNull(((ShadowApplication) Shadow.extract(app)).getNextStartedService());
        assertFalse(prefs.getBoolean("proxyRootWanted",true));
    }
    @Test public void installedNativeBootRequestsConfirmationWithoutRevokingExplicitStop(){
        seed(true,false);prefs.edit().putBoolean("proxyRootAutoStartInstalled",true).commit();
        new BootReceiver().onReceive(app,new Intent(Intent.ACTION_USER_UNLOCKED));
        assertEquals(RootAutostart.ACTION_RUNNING,((ShadowApplication) Shadow.extract(app)).getNextStartedService().getAction());
        assertFalse(prefs.getBoolean("proxyRootWanted",true));
    }
    @Test public void noOptInNeverStarts(){
        seed(false,true);
        new BootReceiver().onReceive(app,new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertNull(((ShadowApplication) Shadow.extract(app)).getNextStartedService());
    }
    @Test public void upgradePreservesWantedSessionWithoutRestart(){
        seed(true,true);
        prefs.edit().putBoolean("proxyRootRuntimeRunning",true)
                .putInt(ProxyRuntimeSettings.APPLIED_RUNTIME_REVISION_KEY,146).commit();
        new BootReceiver().onReceive(app,new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        assertNull(((ShadowApplication) Shadow.extract(app)).getNextStartedService());
        assertTrue(prefs.getBoolean("proxyRootRuntimeRunning",false));
        assertTrue(prefs.getBoolean("proxyRootWanted",false));
    }
}
