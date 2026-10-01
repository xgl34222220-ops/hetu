package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Real Android preferences and production socket client; only loopback fixtures, no Root. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ControllerEndpointRoutingTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("hetu", 0)
    private val local = MockWebServer()
    private val panel = MockWebServer()

    @Before fun prepare() {
        for (server in listOf(local, panel)) {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody("{}")
            }
            server.start(InetAddress.getByName("127.0.0.1"), 0)
        }
        prefs.edit().clear().putBoolean("proxyCustomApiEnabled", true)
            .putString("proxyCustomApiHost", "127.0.0.1").putInt("proxyCustomApiPort", panel.port)
            .putString("proxyCustomApiSecret", "synthetic-panel-secret")
            .putInt("proxyControllerPort", local.port).putString("proxyControllerSecret", "synthetic-local-secret").commit()
    }
    @After fun close() { local.shutdown(); panel.shutdown() }
    private fun request(server: MockWebServer) = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))

    @Test fun rootReloadUsesLocalRuntimeWhilePanelKeepsItsConfiguredEndpoint() {
        val before = prefs.all
        MihomoControllerClient.forLocalRuntime(app).reloadConfig("/data/adb/hetu/config.yaml")
        val root = request(local)
        assertEquals("PUT", root.method)
        assertEquals("/configs?force=true", root.path)
        assertEquals("Bearer synthetic-local-secret", root.getHeader("Authorization"))
        assertEquals(0, panel.requestCount)
        MihomoControllerClient(app).configs()
        assertEquals("Bearer synthetic-panel-secret", request(panel).getHeader("Authorization"))
        assertEquals(before, prefs.all)
    }

    @Test fun preferencePublicationDuringRequestCannotMixEndpointAndCredential() {
        var changed = false
        fun publish() {
            if (!changed) { changed = true; prefs.edit().putBoolean("proxyCustomApiEnabled", false).commit() }
        }
        val switching = object : SharedPreferences by prefs {
            override fun getAll(): MutableMap<String, *> {
                val snapshot = HashMap(prefs.all)
                publish()
                return snapshot
            }
            override fun getInt(key: String?, defValue: Int): Int {
                val value = prefs.getInt(key, defValue)
                if (key == "proxyCustomApiPort") publish()
                return value
            }
        }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = switching
        }
        val client = MihomoControllerClient(context)
        client.configs()
        assertTrue(changed)
        assertEquals("Bearer synthetic-panel-secret", request(panel).getHeader("Authorization"))
        assertEquals(0, local.requestCount)
        client.configs()
        assertEquals("Bearer synthetic-local-secret", request(local).getHeader("Authorization"))
    }

    @Test fun invalidPanelHostCannotRedirectOrDisableLocalMaintenance() {
        prefs.edit().putString("proxyCustomApiHost", "https://invalid.example.invalid").commit()
        assertThrows(IOException::class.java) { MihomoControllerClient(app).configs() }
        assertEquals(0, local.requestCount)
        assertEquals(0, panel.requestCount)
        MihomoControllerClient.forLocalRuntime(app).configs()
        assertEquals("Bearer synthetic-local-secret", request(local).getHeader("Authorization"))
        assertEquals(0, panel.requestCount)
    }

    @Test fun uninitializedLocalRuntimeCannotBorrowExternalAuthentication() {
        prefs.edit().remove("proxyControllerSecret").commit()
        assertThrows(IOException::class.java) { MihomoControllerClient.forLocalRuntime(app).configs() }
        assertEquals(0, local.requestCount)
        assertEquals(0, panel.requestCount)
        MihomoControllerClient(app).configs()
        assertEquals("Bearer synthetic-panel-secret", request(panel).getHeader("Authorization"))
    }

    @Test fun runtimeInspectorReloadNeverUsesThePanelEndpoint() = runBlocking {
        ProxyRuntimeInspector(app).reloadConfig()
        val reload = request(local)
        assertEquals("PUT", reload.method)
        assertEquals("/configs?force=true", reload.path)
        assertEquals("/data/adb/hetu/run/state/startup-config", JSONObject(reload.body.readUtf8()).getString("path"))
        assertEquals("Bearer synthetic-local-secret", reload.getHeader("Authorization"))
        assertEquals(0, panel.requestCount)
    }

    @Test @Config(shadows = [AdblockLocalRootBoundary::class])
    fun adblockConfirmationReadsTheLocallyReloadedRuntimeMode() {
        assertTrue(Shadow.extract<Any>(RootProxyManager(app)) is AdblockLocalRootBoundary)
        AdblockLocalRootBoundary.reloads = 0
        local.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("{\"mode\":\"rule\"}")
        }
        panel.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("{\"mode\":\"global\"}")
        }
        val message = ProxyAdblockRuntimeBridge.setEnabled(app, true)
        assertEquals(1, AdblockLocalRootBoundary.reloads)
        assertTrue(message.contains("运行规则已确认加载"))
        assertEquals("Bearer synthetic-local-secret", request(local).getHeader("Authorization"))
        assertEquals(0, panel.requestCount)
    }
}

/** Only the Root transaction boundary is stubbed; mode verification uses the real socket client. */
@Implements(value = RootProxyManager::class, isInAndroidSdk = false, callThroughByDefault = false)
internal class AdblockLocalRootBoundary {
    companion object { var reloads = 0 }
    @Implementation fun status(): JSONObject = JSONObject().put("running", true)
    @Implementation fun reloadCurrentConfig(): String { reloads++; return "synthetic local reload" }
}
