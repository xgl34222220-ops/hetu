package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Real service callbacks and publication fence, with all Root/HTTP workers stopped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RootNetworkRecoveryTest {
    private lateinit var controller: ServiceController<ProxyNetworkMatchService>
    private lateinit var service: ProxyNetworkMatchService
    private lateinit var callback: ConnectivityManager.NetworkCallback
    private lateinit var epochs: NetworkEpoch<Network>
    private var destroyed = false
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
    private val a = ShadowNetwork.newInstance(101)
    private val b = ShadowNetwork.newInstance(102)

    @Before fun prepare() {
        prefs.edit().clear().commit()
        controller = Robolectric.buildService(ProxyNetworkMatchService::class.java).create()
        service = controller.get()
        ReflectionHelpers.getField<ExecutorService>(service, "metrics").shutdownNow()
        ReflectionHelpers.getField<ExecutorService>(service, "worker").shutdownNow()
        callback = ReflectionHelpers.getField(service, "cb")
        epochs = ReflectionHelpers.getField(service, "networkEvents")
        prefs.edit().putBoolean("proxyRootWanted", true).commit()
    }
    @After fun finish() {
        if (!destroyed) controller.destroy()
        ReflectionHelpers.getField<ExecutorService>(service, "journalWorker").awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun capabilities(validated: Boolean = false, captive: Boolean = false, physical: Boolean = true): NetworkCapabilities {
        val value = NetworkCapabilities()
        val shadow = Shadow.extract<ShadowNetworkCapabilities>(value)
        shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (physical) shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        else shadow.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        if (validated) shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (captive) shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        shadow.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        return value
    }
    private fun links(dns: String = "192.0.2.53", name: String? = "wlan0") = LinkProperties().apply {
        interfaceName = name
        ReflectionHelpers.callInstanceMethod<Boolean>(this, "addDnsServer",
            ReflectionHelpers.ClassParameter.from(InetAddress::class.java, InetAddress.getByName(dns)))
    }
    private fun ready(n: Network = a) {
        callback.onAvailable(n)
        callback.onCapabilitiesChanged(n, capabilities())
        callback.onLinkPropertiesChanged(n, links())
    }
    private fun publish(route: NetworkEpoch.Snapshot<Network>, ticket: Long = RootProxyManager.observationTicket()): Boolean =
        service.publishNetworkObservation(route, ticket) {
            prefs.edit().putString("proxyPolicyEgressState", "reachable").apply()
        }

    @Test fun waitsForCallbackLinksButDoesNotRequireOsValidation() {
        callback.onAvailable(a)
        callback.onCapabilitiesChanged(a, capabilities())
        assertNull(epochs.snapshot().network)
        callback.onLinkPropertiesChanged(a, links())
        assertEquals(a, epochs.snapshot().network)
        assertEquals("unverified", prefs.getString("proxyPhysicalNetworkState", ""))
        assertTrue(publish(epochs.snapshot()))
    }
    @Test fun blockedAndUnblockedInvalidateInFlightResults() {
        ready(); val old = epochs.snapshot()
        callback.onBlockedStatusChanged(a, true)
        assertEquals("blocked", epochs.snapshot().status)
        assertNull(epochs.snapshot().network); assertFalse(publish(old))
        callback.onBlockedStatusChanged(a, false)
        assertEquals(a, epochs.snapshot().network); assertFalse(publish(old))
    }
    @Test fun captivePortalWaitsThenUnvalidatedNetworkCanRecover() {
        ready(); val old = epochs.snapshot()
        callback.onCapabilitiesChanged(a, capabilities(captive = true))
        assertNull(epochs.snapshot().network)
        assertEquals("captive", prefs.getString("proxyPhysicalNetworkState", ""))
        callback.onCapabilitiesChanged(a, capabilities())
        assertEquals(a, epochs.snapshot().network); assertFalse(publish(old))
    }
    @Test fun sameNetworkIdAfterAtoBtoARejectsOldPublication() {
        ready(a); val old = epochs.snapshot()
        ready(b); ready(a)
        assertEquals(old.network, epochs.snapshot().network)
        assertFalse(publish(old))
        assertEquals("unverified", prefs.getString("proxyPolicyEgressState", ""))
    }
    @Test fun lateOldLossCapabilitiesLinksAndBlockedCannotClearCurrentNetwork() {
        ready(a); ready(b); val current = epochs.snapshot()
        callback.onLost(a)
        callback.onCapabilitiesChanged(a, capabilities(captive = true))
        callback.onLinkPropertiesChanged(a, links("192.0.2.99"))
        callback.onBlockedStatusChanged(a, true)
        assertSame(current, epochs.snapshot()); assertTrue(publish(current))
    }
    @Test fun sameNetworkDnsChangeInvalidatesReachabilityAndPendingResult() {
        ready(); val old = epochs.snapshot(); assertTrue(publish(old))
        callback.onLinkPropertiesChanged(a, links("192.0.2.54"))
        assertFalse(publish(old))
        assertEquals("unverified", prefs.getString("proxyPolicyEgressState", ""))
        assertEquals(0L, prefs.getLong("proxyPolicyEgressCheckedAt", -1))
        assertTrue(prefs.getBoolean("proxyRootEgressPending", false))
    }
    @Test fun duplicateCallbacksDoNotInvalidateCurrentResult() {
        ready(); val current = epochs.snapshot()
        repeat(1000) {
            callback.onAvailable(a)
            callback.onCapabilitiesChanged(a, capabilities())
            callback.onLinkPropertiesChanged(a, links())
        }
        assertSame(current, epochs.snapshot()); assertTrue(publish(current))
    }
    @Test fun missingInterfaceAndLossWaitWithoutInventingReachability() {
        callback.onAvailable(a); callback.onCapabilitiesChanged(a, capabilities())
        callback.onLinkPropertiesChanged(a, links(name = null))
        assertNull(epochs.snapshot().network)
        ready(); val old = epochs.snapshot(); callback.onLost(a)
        assertEquals("offline", epochs.snapshot().status); assertFalse(publish(old))
    }
    @Test fun manualStopIntentRejectsCurrentObservation() {
        ready(); prefs.edit().putBoolean("proxyRootWanted", false).commit()
        assertFalse(publish(epochs.snapshot()))
    }
    @Test fun interveningControlTransactionRejectsCurrentNetworkObservation() {
        ready(); val ticket = RootProxyManager.observationTicket()
        val control = ReflectionHelpers.getStaticField<ProxyControlEpoch>(RootProxyManager::class.java, "CONTROL_LOCK")
        control.lock(); control.unlock()
        assertFalse(publish(epochs.snapshot(), ticket))
    }
    @Test fun destroyedServiceCannotPublishOrAcceptFurtherCallbacks() {
        ready(); val old = epochs.snapshot(); controller.destroy(); destroyed = true
        val ended = epochs.snapshot(); callback.onAvailable(b)
        assertSame(ended, epochs.snapshot()); assertFalse(publish(old))
    }
    @Test fun vpnCapabilitiesCannotTriggerRootRecovery() {
        ready(); callback.onCapabilitiesChanged(a, capabilities(physical = false))
        assertNull(epochs.snapshot().network)
    }
    @Test fun validationChangeOnSameNetworkInvalidatesPreviousObservation() {
        ready(); val old = epochs.snapshot()
        callback.onCapabilitiesChanged(a, capabilities(validated = true))
        assertEquals(a, epochs.snapshot().network)
        assertEquals("ready", prefs.getString("proxyPhysicalNetworkState", ""))
        assertFalse(publish(old)); assertTrue(publish(epochs.snapshot()))
    }
}
